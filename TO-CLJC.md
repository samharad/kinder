# TO-CLJC.md

## Goal

Restructure the project so the runtime is effectively all JavaScript:

- a browser-local ClojureScript app for interactive generation/rendering
- a Node-targeted ClojureScript CLI for batch export and local file output
- a shared `cljc` core for generation, mutation, coordinated circles, and SVG rendering

The design goal is to keep `cljs` at the edges and keep duplication close to zero. Shared logic should live in `cljc` unless there is a concrete platform-specific reason not to.

Verification during the migration is by eyeballing outputs, not by a test suite. Parity issues between browser and Node will be addressed as they show up.

## Target Architecture

### Shared Core

These namespaces should become shared `cljc` code:

- `kinder.rng`
- `kinder.core`
- `kinder.state` (pure transition functions only)
- `kinder.svg`
- `kinder.options` — shared defaults and parameter normalization (see below)

These namespaces should stay very small and platform-specific:

- `kinder.browser` (cljs)
- `kinder.cli` (cljs, Node target)

The current JVM-specific app namespaces should disappear:

- `kinder.web`
- `kinder.dev`
- `kinder.draw`
- `intellij`

### What `kinder.options` is for

Both the browser UI and the Node CLI need to shape user input (control values, flag strings) into a normalized config map before calling the generator. `options.cljc` owns:

- default values for all knobs (palette, unit, stroke-weight, corner-radius, min-depth, etc.)
- normalization of stringy input into typed values
- one canonical `defaults` map both edges merge user input into

Without this, defaults drift between CLI and browser. With it, both edges become one line: `(generate-pane dim (options/normalize user-input))`.

## High-Level Strategy

1. Stop thinking in terms of "server mode vs browser mode".
   The browser app becomes the primary runtime.

2. Keep one rendering path.
   The browser app reuses the shared SVG renderer in `cljc`.

3. Replace the RNG before moving the core to `cljc`.
   This is the main technical prerequisite for CLJ/CLJS parity.

4. Keep file IO at the edges.
   The Node CLI handles filesystem writes.
   The browser uses blob downloads and optionally the File System Access API when available.

## Why This Is A Better Fit Than Keeping CLJ Around

This repo's actual application flow is already close to a browser-local model:

- the web UI already ends by injecting SVG into the DOM
- the core generator is mostly pure data logic
- the direct SVG renderer is already independent of Quil

The current server is doing orchestration and string assembly, not hosting an indispensable JVM-only rendering pipeline.

That means the likely lowest-duplication architecture is:

- shared `cljc` generator + shared `cljc` SVG renderer
- browser app calls shared code directly
- Node CLI calls the same shared code directly

## Modern CLJS Guidance This Plan Uses

- Use `shadow-cljs` as the build tool for both browser and Node targets.
  Source: https://clojurescript.org/tools/shadow-cljs
- Use `package.json` and npm directly for JavaScript dependencies.
  shadow-cljs explicitly recommends this and explicitly says not to use CLJSJS.
  Source: https://shadow-cljs.github.io/docs/UsersGuide.html#_javascript
  Source: https://shadow-cljs.github.io/docs/UsersGuide.html#_why_not_use_cljsjs
- Use `.cljc` for mostly platform-independent code; keep `.cljs` only for real runtime edges.
  Source: https://clojure.org/guides/reader_conditionals

## Known Parity Landmines

These are concrete cross-runtime issues the migration must solve. None are catastrophic, but all need explicit handling rather than being left to discover.

### 1. `format` does not exist in CLJS

`kinder.svg` is built on `(format "%.2f" ...)` and similar — roughly a dozen calls in that one file. `clojure.core/format` is JVM-only. Options:

- `goog.string/format` — limited specifier support, subtle differences
- `cljs.pprint/cl-format` — different syntax, heavier
- Hand-rolled `fmt-fixed` / `fmt-int` helpers — ~10 lines, identical output on both sides

**Decision: hand-rolled helpers.** One `fmt-fixed` for decimals and one `fmt-int` for integers, defined in `kinder.svg` (or a tiny `kinder.fmt.cljc`). All `format` calls in the renderer get rewritten to use them. No reader conditionals needed — the helpers work identically in CLJ and CLJS.

### 2. `Math/round` diverges between JVM and JS

Java rounds half-up; JS `Math.round` rounds half toward +∞ (so `-0.5` goes to `0`, not `-1` like Java does). `svg.clj:10`, `svg.clj:24-26` round color channels; `core.clj:125-126`, `core.clj:208-210` round dimensions. For byte-identical SVGs across runtimes, every `Math/round` call in shared code must route through a single helper.

**Decision:** add `kinder.rng/round` (or `kinder.fmt/round`) with explicit half-up semantics, defined once. All shared code uses it. `Math/sqrt`, `Math/pow`, `Math/sin`, `Math/abs`, `Math/PI` are safe as-is — they compile to `js/Math.*` in CLJS and behave identically.

### 3. Seed format is a hard break

The new portable RNG will not accept old `random-seed` integer seeds. Any previously saved seed becomes unreproducible. **Accepted.** No migration shim.

### 4. Specs and orchestra go away

See "Drop Specs and Orchestra" below. No reader conditional gymnastics needed because all spec code is removed, not ported.

## Drop Specs and Orchestra

Remove all `clojure.spec.alpha` and `orchestra` usage as part of the migration. The existing specs (`::color`, `::rect`, `::pane`, two `s/fdef`s) are shape declarations that:

- lose their most useful dev-time behavior in CLJS (orchestra's `:ret`/`:fn` enforcement is JVM-only)
- duplicate information already visible in constructor functions
- would require reader-conditional noise in every shared namespace that declares them

**Replacement:** a single `;; Data shapes:` block at the top of `kinder.core.cljc` that describes the pane/rect/circle/palette keys and their types in plain prose. This serves the same documentation role for both humans and coding agents, with zero runtime cost and no platform coupling.

Dependencies to remove: `org.clojure/spec.alpha`, `orchestra/orchestra`.

## Rendering Direction

Do not introduce a second renderer.

The browser app:

1. generates pane data locally in CLJS
2. calls the shared SVG renderer in `cljc`
3. inserts the SVG string into the DOM

The current Quil path is not preserved as a first-class runtime target.

## Progressive Reveal Preservation

The stepwise render-depth reveal (currently driven by Quil's animation loop) is a feature worth keeping. It ports cleanly:

- `take-depth` stays in `kinder.core.cljc` (it is already pure).
- `kinder.state.cljc` keeps the pure `step-state` / `back-state` / `all-done?` transitions.
- The browser owns the animation loop: on "new," set depth to 0, then use `js/setTimeout` or `requestAnimationFrame` to step until `all-done?`, re-rendering the SVG string and replacing the DOM node on each step.
- Full SVG regeneration per step is fine for the current pane complexity (handful of steps, small shape counts). If it ever feels laggy, switch to appending only the newly-revealed rects.

The Node CLI does not need progressive reveal — it just calls `complete` and writes one SVG.

## Browser Saving Requirement

The browser-local app must allow the user to save an SVG to local disk.

### Primary path

- Create a `Blob` from the generated SVG string.
- Create a blob URL with `URL.createObjectURL()`.
- Trigger a download using an `<a download>` link.
- Revoke the object URL afterward.

### Optional enhancement

- Use `showSaveFilePicker()` when available for a native save flow.
- Fall back to the blob download path when unavailable.

Sources:

- `URL.createObjectURL()`: https://developer.mozilla.org/docs/Web/API/URL/createObjectURL_static
- Blob URLs: https://developer.mozilla.org/en-US/docs/Web/URI/Reference/Schemes/blob
- `showSaveFilePicker()`: https://developer.mozilla.org/en-US/docs/Web/API/Window/showSaveFilePicker

## Inspiration Directory

`kinder.web` currently serves images from `resources/public/inspiration/` via `/inspiration` routes. In the browser-local world:

**Decision:** serve the folder as static assets. shadow-cljs `:app` output goes under `public/assets/app/js`; the existing `resources/public/inspiration/` moves (or is symlinked) under the same static root. The browser picker reads a manifest (either a small JSON file built at dev time, or a hardcoded list) rather than a server directory listing.

If the inspiration feature is not worth the migration effort, cut it and reintroduce later.

## Step-By-Step Plan

### Phase 1: JS-First Build Toolchain

1. Add `package.json`.
2. Add `shadow-cljs.edn`.
3. Add:
   - one browser build
   - one node-script build
4. Put browser output in a static assets directory.
5. Keep the current CLJ app runnable during the transition.

Initial shape:

```clojure
{:source-paths ["src"]
 :dependencies [...]
 :builds
 {:app
  {:target :browser
   :output-dir "public/assets/app/js"
   :asset-path "/assets/app/js"
   :modules {:main {:init-fn kinder.browser/init}}}

  :cli
  {:target :node-script
   :main kinder.cli/main
   :output-to "dist/kinder-cli.js"}}}
```

### Phase 2: `kinder.rng.cljc`

1. Create a shared RNG namespace.
2. Implement a small explicit PRNG (e.g. mulberry32 or xoshiro128) with 32-bit arithmetic.
3. **RNG API style: boxed, stateful.** `make-rng` returns an object (deftype with a mutable field, or a volatile/atom wrapping a 32-bit word). `rand-double`, `rand-int`, `rand-nth` take that object, mutate it in place, and return the drawn value. This keeps call sites almost identical to today's global-`rand` shape and avoids threading return tuples through 889 lines of recursive generator.
4. Public seed is a string, hashed into the RNG's internal state. Add an `ambient-seed` helper that generates a fresh string seed portably (e.g. `(str (random-uuid))` — works in both CLJ and CLJS, no `java.util.Random` needed).
5. Remove `random-seed`, `set-random-seed!`, all `java.util.Random` usage (the only JVM coupling in `core.clj`, at line 612).
6. Pass the RNG object explicitly into the top-level generator entry points; recursive calls receive it as a regular argument.
7. Add the shared `round` helper while you're here (see Parity Landmines).

Deliverable: the generator no longer touches JVM-specific or global RNG state, but call-site shapes are preserved.

### Phase 3: Convert `kinder.core` to `cljc`

1. Rename `src/kinder/core.clj` to `src/kinder/core.cljc`.
2. Remove all `spec.alpha` / `orchestra` usage; replace with a `;; Data shapes:` docstring block.
3. Replace remaining `Math/round` calls with the shared helper.
4. Confirm `Math/sqrt`, `Math/pow`, `Math/sin`, `Math/abs`, `Math/PI` work unchanged in CLJS (they do, via `js/Math`).
5. Keep reader conditionals minimal and local — if a namespace needs many host branches, it probably belongs in a separate edge file.

End state: `kinder.core` is pure shared logic, receives config and seed explicitly, doesn't know if the caller is browser or Node.

### Phase 4: Convert `kinder.svg` to `cljc`

1. Rename `src/kinder/svg.clj` to `src/kinder/svg.cljc`.
2. Replace every `format` call with the hand-rolled `fmt-fixed` / `fmt-int` helpers.
3. Route any rounding through the shared helper.
4. Keep it purely data-in, string-out.

Once shared: browser rendering, Node CLI export, and any future tooling all use the same renderer.

### Phase 5: Convert Pure Parts of `kinder.state` to `cljc`

1. Move `step-state`, `back-state`, `all-done?`, `init-state` (minus any JVM deps) to `src/kinder/state.cljc`.
2. Leave atom management to the edges.
3. Drop the `clojure.java.shell` require (unused in state logic anyway).

### Phase 6: Add `kinder.options.cljc`

1. Extract the defaults currently scattered across `cli.clj`, `web.clj`, and `dev.clj` into one `defaults` map.
2. Add `normalize` to coerce stringy/missing inputs into typed values.
3. Browser and CLI both call `(options/normalize user-input)` before generating.

### Phase 7: Browser-Local CLJS App

1. Create `src/kinder/browser.cljs`.
2. Replace the current server-rendered HTML + inline JS with a CLJS entrypoint.
3. Keep the existing UI shape initially to reduce risk.
4. In the browser:
   - read current controls
   - normalize via `kinder.options`
   - generate pane data locally using shared `cljc`
   - render SVG locally using shared `cljc`
   - inject the SVG into the DOM
5. Wire up the progressive reveal (see "Progressive Reveal Preservation").
6. Remove dependency on `/generate`.

### Phase 8: Browser-Local Save

1. Derive a filename from seed + timestamp + mode (shared helper; pure, so it lives in `kinder.options` or a small `kinder.filename.cljc`).
2. Primary save path:
   - SVG string → `Blob`
   - `URL.createObjectURL()`
   - hidden `<a download>` click
   - `URL.revokeObjectURL()` afterward
3. Optionally detect and use `showSaveFilePicker()` when present.

This fully replaces the server-side `/save` path.

### Phase 9: Node CLI in CLJS

1. Create `src/kinder/cli.cljs`.
2. Target Node with shadow-cljs `:node-script`.
3. Port the existing CLI behavior:
   - parse flags
   - normalize via `kinder.options`
   - generate single / triptych / variation outputs
   - write SVG files to disk
4. Keep Node-specific code limited to: CLI arg parsing, filesystem access, path handling.

### Phase 10: Remove CLJ Runtime Dependencies

1. Delete `kinder.web`, `kinder.draw`, `kinder.dev`, `intellij`.
2. Delete `src/kinder/cli.clj` (replaced by `cli.cljs`).
3. Remove from `deps.edn` (or delete `deps.edn` entirely if shadow-cljs handles everything):
   - `quil/quil`
   - `random-seed/random-seed`
   - `org.clojure/spec.alpha`
   - `orchestra/orchestra`
   - `http-kit/http-kit`
   - `org.clojure/data.json`
   - `org.clojure/core.async` — confirmed used only by `intellij.clj`
   - `tubular` — confirmed used only by `intellij.clj`
4. Keep `org.clojure/tools.cli` — it is cljc-compatible and works under CLJS without modification.

At this point the application runtime is entirely JS-based.

## Recommended Final File Layout

```text
src/kinder/rng.cljc
src/kinder/core.cljc
src/kinder/state.cljc
src/kinder/svg.cljc
src/kinder/options.cljc
src/kinder/browser.cljs
src/kinder/cli.cljs
```

Optional edge-only helpers:

```text
src/kinder/browser_save.cljs
src/kinder/filename.cljc
```

## What Should Not Be Duplicated

Exists once, in shared code:

- pane generation
- subtree mutation
- coordinated circles
- color logic
- triptych assembly
- SVG rendering
- defaults and option normalization
- filename derivation

Differs at the edges:

- DOM access
- browser save implementation
- Node filesystem writes
- CLI arg parsing

## Risks and Mitigations

### 1. RNG parity risk

Risk: Node and browser outputs drift for the same seed.
Mitigation: Define a portable RNG and seed format before any `cljc` conversion. Eyeball outputs in both runtimes after Phase 2.

### 2. SVG formatting drift

Risk: CLJ and CLJS produce slightly different number strings.
Mitigation: Hand-rolled `fmt-fixed` / `fmt-int` helpers. Shared `round`. No reliance on host `format`.

### 3. Browser save UX

Risk: Saving feels weaker without a server.
Mitigation: Blob download as the baseline, `showSaveFilePicker()` as enhancement.

### 4. Edge code swelling in browser app

Risk: Generation logic leaks into `browser.cljs`.
Mitigation: Keep `browser.cljs` orchestration-only. All art logic stays in `cljc`.

### 5. Progressive reveal lag

Risk: Re-rendering the full SVG string on each reveal step feels sluggish.
Mitigation: Unlikely at current pane complexity. If it happens, switch to incremental DOM updates (append new rects per step) rather than full re-render.

## Concrete Implementation Order

1. Add `package.json` and `shadow-cljs.edn`.
2. Introduce `kinder.rng.cljc` with the portable PRNG and the shared `round` helper.
3. Remove global/JVM-specific RNG usage from the generator.
4. Convert `kinder.core` to `cljc`; drop specs.
5. Convert `kinder.svg` to `cljc`; replace `format` calls.
6. Convert pure `kinder.state` logic to `cljc`.
7. Add `kinder.options.cljc`.
8. Add browser-local CLJS generation/rendering, including progressive reveal.
9. Add browser-local SVG save.
10. Add Node CLI in CLJS.
11. Remove CLJ runtime code, Quil, random-seed, spec, orchestra, http-kit.

## Definition of Done

Migration is complete when:

- the browser app generates and renders locally using shared `cljc` code
- the browser user can save SVG locally without a server
- the CLI runs under Node using the same shared generator and renderer
- platform-specific code is limited to DOM and filesystem edges
- the same seed and params produce the same SVG in browser and Node (verified by eye)
- the progressive reveal works in the browser
- no CLJ runtime code remains in `src/`

## Sources

- Clojure reader conditionals: https://clojure.org/guides/reader_conditionals
- shadow-cljs tooling page: https://clojurescript.org/tools/shadow-cljs
- shadow-cljs user guide: https://shadow-cljs.github.io/docs/UsersGuide.html
- shadow-cljs JS dependencies: https://shadow-cljs.github.io/docs/UsersGuide.html#_javascript
- shadow-cljs vs CLJSJS: https://shadow-cljs.github.io/docs/UsersGuide.html#_why_not_use_cljsjs
- MDN `URL.createObjectURL()`: https://developer.mozilla.org/docs/Web/API/URL/createObjectURL_static
- MDN blob URLs: https://developer.mozilla.org/en-US/docs/Web/URI/Reference/Schemes/blob
- MDN `showSaveFilePicker()`: https://developer.mozilla.org/en-US/docs/Web/API/Window/showSaveFilePicker

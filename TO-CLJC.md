# TO-CLJC.md

## Goal

Restructure the project so the runtime is effectively all JavaScript:

- a browser-local ClojureScript app for interactive generation/rendering
- a Node-targeted ClojureScript CLI for batch export and local file output
- a shared `cljc` core for generation, mutation, coordinated circles, and SVG rendering

The design goal is to keep `cljs` at the edges and keep duplication close to zero. Shared logic should live in `cljc` unless there is a concrete platform-specific reason not to.

## Target Architecture

### Shared Core

These namespaces should become shared `cljc` code:

- `kinder.rng`
- `kinder.core`
- `kinder.state` or a smaller pure subset of it
- `kinder.svg`
- optionally `kinder.options` for shared defaults and parameter shaping

These namespaces should stay very small and platform-specific:

- `kinder.browser.cljs`
- `kinder.browser-ui.cljs` if the browser entrypoint grows large
- `kinder.cli.cljs` for the Node CLI

The current JVM-specific app namespaces should disappear or become unnecessary:

- `kinder.web`
- `kinder.dev`
- `kinder.draw`
- `intellij`

## High-Level Strategy

1. Stop thinking in terms of "server mode vs browser mode".
   The browser app becomes the primary runtime.

2. Keep one rendering path.
   The browser app should reuse the existing SVG renderer in shared code instead of introducing a second rendering implementation.

3. Replace the current RNG model before moving the core to `cljc`.
   This is the main technical prerequisite for real CLJ/CLJS parity and for a future Node/browser shared runtime.

4. Keep file IO at the edges.
   The Node CLI handles filesystem writes.
   The browser uses the standard web download path and optionally the File System Access API when available.

## Why This Is A Better Fit Than Keeping CLJ Around

This repo’s actual application flow is already close to a browser-local model:

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
- Use shadow-cljs test targets for CLJS verification, especially `:node-test` and `:browser-test`.
  Source: https://shadow-cljs.github.io/docs/UsersGuide.html#testing

## Non-Negotiable Technical Prerequisite: Portable RNG

The current code relies on:

- global RNG mutation from `random-seed`
- JVM-specific seed generation
- seed values that are already awkward in JavaScript because of 64-bit integer precision

That cannot be the final foundation for a browser runtime plus a Node CLI.

### Required RNG direction

1. Add `src/kinder/rng.cljc`.
2. Implement a small explicit PRNG that behaves identically in CLJ and CLJS.
3. Use 32-bit arithmetic and a browser-safe seed representation.
4. Thread RNG state explicitly through the shared generator.
5. Remove `random-seed` from the shared core.

### Recommended seed format

- Public seed should be a string.
- The string is normalized into one or more 32-bit internal state words.

That gives:

- portable browser/Node behavior
- no precision surprises
- easier future copy/paste/shareability in the UI

## Rendering Direction

Do not introduce a second renderer in the first migration pass.

The browser app should:

1. generate pane data locally in CLJS
2. call the shared SVG renderer in `cljc`
3. insert the SVG string into the DOM

This is the right first step because it preserves one renderer for:

- browser app
- Node CLI export
- tests / parity checks

The current Quil path should not be preserved as a first-class runtime target unless there is a later reason to resurrect it.

## Browser Saving Requirement

The browser-local app must still allow the user to save an SVG to local disk.

### Recommended save behavior

Primary path:

- Create a `Blob` from the generated SVG string.
- Create a blob URL with `URL.createObjectURL()`.
- Trigger a download using an `<a download>` link.
- Revoke the object URL afterward.

This path is broadly supported and is the safest default for a normal web app.

Optional enhancement:

- Use `showSaveFilePicker()` when available for a better native save flow.
- Fall back to the blob download path when unavailable.

This should be explicitly treated as an enhancement, not the baseline dependency, because `showSaveFilePicker()` is not universally available.

Sources:

- `URL.createObjectURL()` on MDN:
  https://developer.mozilla.org/docs/Web/API/URL/createObjectURL_static
- Blob URLs on MDN:
  https://developer.mozilla.org/en-US/docs/Web/URI/Reference/Schemes/blob
- `showSaveFilePicker()` on MDN:
  https://developer.mozilla.org/en-US/docs/Web/API/Window/showSaveFilePicker

## Step-By-Step Plan

### Phase 0: Freeze Existing Behavior

Before moving code around:

1. Add CLJ golden tests around current behavior.
2. Cover at minimum:
   - `generate-pane`
   - `mutate-pane`
   - `coordinated-circles`
   - `svg/render`
   - `svg/render-triptych`
3. Prefer data-level assertions where possible.
4. Add a few whole-SVG snapshot tests per layout mode.

This gives us a baseline before the RNG rewrite.

### Phase 1: Add A JS-First Build Toolchain

1. Add `package.json`.
2. Add `shadow-cljs.edn`.
3. Add:
   - one browser build
   - one node-script build
   - one node-test build
4. Put browser output in a static assets directory.
5. Keep the current app temporarily runnable while the new builds come online.

Recommended initial shape:

```clojure
{:source-paths ["src" "test"]
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
   :output-to "dist/kinder-cli.js"}

  :test
  {:target :node-test
   :output-to "dist/tests.js"
   :autorun true}}}
```

The exact paths can differ, but the shape should stay this simple.

### Phase 2: Introduce `kinder.rng.cljc`

1. Create a shared RNG namespace.
2. Move all randomness behind a small API:
   - `make-rng`
   - `rand-double`
   - `rand-int`
   - `rand-nth`
3. Remove direct use of:
   - `random-seed`
   - `set-random-seed!`
   - `java.util.Random`
4. Convert public seed handling to a string-based portable form.
5. Make deterministic tests pass in CLJ before proceeding.

Deliverable:

- the generation logic is no longer coupled to JVM-specific or global RNG behavior

### Phase 3: Convert `kinder.core` To `cljc`

1. Rename `src/kinder/core.clj` to `src/kinder/core.cljc`.
2. Remove JVM-only imports and assumptions.
3. Move any host-specific helpers out of `core`.
4. Keep reader conditionals minimal and local.
5. Move any development-only instrumentation out of the shared namespace.

Expected end state:

- `kinder.core` is pure shared logic
- it receives config and seed state explicitly
- it does not know whether the caller is browser or Node

### Phase 4: Convert `kinder.svg` To `cljc`

1. Rename `src/kinder/svg.clj` to `src/kinder/svg.cljc`.
2. Keep it purely data-in, string-out.
3. Centralize numeric formatting if CLJ/CLJS formatting differences appear.
4. Add parity tests to ensure the same pane data yields the same SVG text in both runtimes.

This is the main duplication-prevention move.

Once this is shared:

- browser rendering uses it
- Node CLI export uses it
- tests use it

### Phase 5: Convert The Pure Parts Of `kinder.state`

1. Decide whether `kinder.state` is still valuable.
2. If yes, move only the pure transition functions to `src/kinder/state.cljc`.
3. Keep atom helpers out of shared code.
4. Prefer pure input/output state transforms.

If the browser app ends up managing state directly in the UI layer, this shared state namespace can stay very small.

### Phase 6: Replace The Current Web Portal With A Browser-Local CLJS App

This is the first major runtime milestone.

1. Create `src/kinder/browser.cljs`.
2. Replace the current imperative inline JS with a CLJS entrypoint.
3. Keep the existing UI shape initially to reduce risk.
4. In the browser:
   - read current controls
   - generate pane data locally using shared `cljc`
   - render SVG locally using shared `cljc`
   - inject the SVG into the DOM
5. Remove dependency on `/generate`.

Recommended first browser design:

- preserve the current HTML/CSS structure as much as possible
- progressively replace inline JS with CLJS
- only improve the UI architecture after parity is stable

### Phase 7: Implement Browser-Local Save

After browser-local generation works:

1. Add a shared helper or browser-edge helper to derive a filename from:
   - seed
   - timestamp
   - mode
2. Implement the primary save path using:
   - SVG string -> `Blob`
   - `URL.createObjectURL()`
   - hidden `<a download>`
3. Revoke the object URL after use.
4. Optionally detect and use `showSaveFilePicker()` when present.

This should fully replace the server-side `/save` path.

### Phase 8: Add A Node CLI In CLJS

Once the shared generator and renderer are in `cljc`:

1. Create `src/kinder/cli.cljs`.
2. Target Node with shadow-cljs `:node-script`.
3. Port the CLI behavior that still matters:
   - parse flags
   - generate single / triptych / variation outputs
   - write SVG files to disk
4. Keep the Node-specific code limited to:
   - CLI arg parsing
   - filesystem access
   - path handling

The CLI should become a thin wrapper over shared generation/rendering functions.

### Phase 9: Remove CLJ Runtime Dependencies

After the browser app and Node CLI are working:

1. Remove `kinder.web`.
2. Remove `kinder.draw`.
3. Remove `kinder.dev`, unless a tiny compatibility/dev script is still useful during transition.
4. Remove the HTTP server dependency if it is no longer used.
5. Remove Quil if it is no longer used anywhere.
6. Remove `random-seed`.

At this point, the application runtime is entirely JS-based, even though the compiler/build tooling still uses the normal CLJS toolchain.

### Phase 10: Add Cross-Runtime Parity Tests

1. Add shared tests for the `cljc` core.
2. Run them under:
   - CLJ temporarily during migration, if useful
   - CLJS node-test as the main fast path
   - browser-test for smoke verification
3. Assert:
   - identical pane data for the same seed
   - identical circle placement
   - identical SVG strings
   - browser and Node entrypoints both call the same shared core successfully

Eventually, CLJ test execution can become optional if the repo truly stops using CLJ runtime code.

## Recommended Final File Layout

```text
src/kinder/rng.cljc
src/kinder/core.cljc
src/kinder/state.cljc
src/kinder/svg.cljc
src/kinder/browser.cljs
src/kinder/cli.cljs
src/kinder/options.cljc
```

Optional browser-only helpers:

```text
src/kinder/browser_ui.cljs
src/kinder/browser_save.cljs
```

## What Should Not Be Duplicated

These should exist once, in shared code:

- pane generation
- subtree mutation
- coordinated circles
- color logic
- triptych assembly
- SVG rendering
- filename generation logic, if it can be kept pure

These can differ at the edges:

- DOM access
- browser save implementation
- Node filesystem writes
- CLI arg parsing

## Risks And Mitigations

### 1. RNG parity risk

Risk:
Node and browser outputs drift for the same seed.

Mitigation:
Define a portable RNG and seed format before any serious `cljc` conversion.

### 2. SVG formatting drift

Risk:
CLJ and CLJS serialize floats differently.

Mitigation:
Introduce one shared numeric formatting helper in `kinder.svg`.

### 3. Browser save UX risk

Risk:
Saving feels weaker without a server.

Mitigation:
Use blob download as the broad-compatibility baseline and optionally add `showSaveFilePicker()` as a progressive enhancement.

### 4. Edge code swelling in browser app

Risk:
Too much generation logic leaks into `browser.cljs`.

Mitigation:
Make the browser namespace orchestration-only. All art logic stays in shared namespaces.

### 5. Overuse of reader conditionals

Risk:
Shared files become noisy and fragile.

Mitigation:
If a namespace needs many host branches, it probably belongs in separate edge files.

## Concrete Implementation Order

Do the work in this order:

1. Add `package.json` and `shadow-cljs.edn`.
2. Add baseline tests around current generation/rendering.
3. Introduce `kinder.rng.cljc`.
4. Remove global/JVM-specific RNG usage from the generator.
5. Convert `kinder.core` to `cljc`.
6. Convert `kinder.svg` to `cljc`.
7. Convert pure `kinder.state` logic to `cljc` if still useful.
8. Add browser-local CLJS generation/rendering.
9. Add browser-local SVG save.
10. Add Node CLI in CLJS.
11. Remove CLJ server/Quil runtime code and obsolete dependencies.

## Definition Of Done

This migration is complete when:

- the app generates locally in the browser using shared `cljc` code
- the browser app renders SVG locally using shared `cljc` code
- the browser user can save SVG locally without a server
- the CLI runs under Node using the same shared generator and renderer
- the remaining platform-specific code is limited to DOM and filesystem edges
- output for the same seed and params is consistent across browser and Node

## Sources

- Clojure reader conditionals guide:
  https://clojure.org/guides/reader_conditionals
- ClojureScript tooling page for shadow-cljs:
  https://clojurescript.org/tools/shadow-cljs
- shadow-cljs user guide:
  https://shadow-cljs.github.io/docs/UsersGuide.html
- shadow-cljs JS dependency guidance:
  https://shadow-cljs.github.io/docs/UsersGuide.html#_javascript
- shadow-cljs guidance against CLJSJS:
  https://shadow-cljs.github.io/docs/UsersGuide.html#_why_not_use_cljsjs
- shadow-cljs testing targets:
  https://shadow-cljs.github.io/docs/UsersGuide.html#testing
- MDN `URL.createObjectURL()`:
  https://developer.mozilla.org/docs/Web/API/URL/createObjectURL_static
- MDN blob URLs:
  https://developer.mozilla.org/en-US/docs/Web/URI/Reference/Schemes/blob
- MDN `showSaveFilePicker()`:
  https://developer.mozilla.org/en-US/docs/Web/API/Window/showSaveFilePicker

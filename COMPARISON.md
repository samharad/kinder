# TO-CLJC.md Implementation Comparison: `kinder` vs `kinder-wt-animate`

Both directories have completed essentially the same task — Quil/JVM stack
ripped out, replaced with shadow-cljs + a CLJC core + browser & node-script
edges — but the two parties made meaningfully different design choices.

## Where they agree

Both produce the exact same namespace set (`rng`, `core`, `state`, `svg`,
`options`, `browser.cljs`, `cli.cljs`), both delete the same eight JVM
files, and both make the headline parity decisions called for in
TO-CLJC.md the same way:

- **RNG**: mulberry32 PRNG with FNV-1a string→u32 hashing, boxed in a
  `volatile!`, identical algebra on JVM and JS.
- **Rounding/format**: hand-rolled `fmt-fixed`/`pow10`/`pad` helpers and
  a shared `round` in `kinder.rng` so SVGs are byte-identical across
  runtimes.
- **Specs/orchestra**: gone, replaced by a `;; Data shapes:` comment
  block at the top of `core.cljc`.
- **Browser save**: blob + `URL.createObjectURL` + hidden `<a download>`.
- **State**: pure `step-state` / `back-state` / `all-done?` over
  `{:pane :render-depth}`; atom mgmt at the edges.
- **HTML shell** preserved (modes, controls, viewport, gallery,
  fullscreen, keyboard shortcuts).

## Where they differ

### 1. Layout assembly (`kinder.layouts.cljc`)

This is the single most consequential design split.

- `kinder-wt-animate` introduces a shared `kinder.layouts.cljc` (~104
  lines) with `generate-scene` and `render-scene` that own the
  `single`/`triptych`/`triptych-equal`/`triptych-variation` dispatch.
  `browser.cljs` and `cli.cljs` are thin callers.
- `kinder` does *not* extract this. The same `case` on `:mode` (with
  mutate-pane orchestration, sub-seeds `"/l"` `"/c"` `"/cc"`,
  coordinated-circles wiring) is **duplicated** in both `browser.cljs`
  (`build-panes`) and `cli.cljs` (`build-svg`).

TO-CLJC.md explicitly lists "triptych assembly" under *"What Should Not
Be Duplicated."* `kinder-wt-animate` honors that; `kinder` does not.

### 2. CLJ build wiring

- `kinder` deletes `deps.edn` and inlines
  `:dependencies [[org.clojure/tools.cli "1.1.230"]]` directly in
  `shadow-cljs.edn`. Cleaner — only one place declares deps.
- `kinder-wt-animate` keeps `deps.edn` and uses shadow's `:deps true`,
  holding clojure/clojurescript/shadow-cljs/tools.cli there.

### 3. Defaults / single source of truth

- `kinder` has `defaults` in `options.cljc` only. CLI flag specs
  deliberately omit `:default` so values flow from `opts/defaults`
  through `opts/normalize`. Coercion is generic and table-driven
  (`int-keys`/`double-keys`/`bool-keys`).
- `kinder-wt-animate` *also* defines `defaults` in `options.cljc` *and*
  repeats every default again in `cli.cljs` (`:default "30"`,
  `:default "0.2"`, etc.). Two sources of truth that can drift — and
  the TO-CLJC plan was specifically to prevent that. `normalize` is
  hand-written field-by-field, longer and more repetitive.

### 4. CLAUDE.md

- `kinder` rewrote CLAUDE.md to match the new architecture (JS runtime,
  shadow-cljs commands, namespace responsibilities, deterministic-RNG
  note about pre-migration seeds being incompatible). 102-line diff.
- `kinder-wt-animate` left CLAUDE.md untouched — it still describes
  Quil, `clojure -M:dev`, `kinder.dev`, spec instrumentation. Stale
  documentation about a runtime that no longer exists.

### 5. Browser save

- `kinder` ships both the blob fallback and `showSaveFilePicker()` (the
  "Optional enhancement" path TO-CLJC.md mentions).
- `kinder-wt-animate` ships blob only.

### 6. Animation loop

- `kinder` uses `requestAnimationFrame` (matches what CLAUDE.md
  describes).
- `kinder-wt-animate` uses `setTimeout` with a fixed 90 ms delay
  (slower, more controllable cadence) but uses an `:anim-token` counter
  to cancel stale loops — a slightly more idiomatic cancellation
  pattern than `kinder`'s separate `!raf` atom.

### 7. App state shape

- `kinder` uses ~8 separate top-level atoms (`!current`, `!raf`,
  `!view`, `!drag`, `!view-name`, `!inspiration-images`, `!fs-index`,
  `!debounce-id`).
- `kinder-wt-animate` uses one `defonce app-state` holding everything.
  More idiomatic for hot-reload survival.

### 8. Inspiration gallery

- `kinder` fetches `/inspiration.json` manifest (and ships one in
  `public/`).
- `kinder-wt-animate` hardcodes the file list in `browser.cljs`.
  Simpler, but adding an image now requires a code change.

### 9. State namespace responsibilities

- `kinder.state/init-state` takes dimensions + optional seed and *calls
  into core to generate the pane*. State knows about generation.
- `kinder-wt-animate.state/init-state` takes an already-generated pane.
  Better separation; also adds a `complete-state` helper (loops to
  `all-done?`).

### 10. RNG surface

- `kinder.rng/rand` (refer-clojure :exclude rand). Accepts string OR
  number seeds. Has `ambient-seed`.
- `kinder-wt-animate.rng/rand-double` — clearer semantic name, only
  string seeds, defensive `(throw …)` on non-positive `rand-int` bound,
  explicit ceil-for-negatives in `round` (more literal Java half-up
  parity).

### 11. Tone of `svg.cljc` and `index.html`

- `kinder-wt-animate` carries inline comments explaining design choices
  that surfaced from real bugs ("which is why the bottom of tall
  triptychs was being cut off in the web UI", "viewport-boundary stroke
  clipping during sub-pixel rasterization"). Suggests iteration against
  an actually-running browser.
- `kinder-wt-animate`'s `index.html` is much more verbose: every form
  control gets a long `title=""` tooltip explaining what it does.
  Better UX; more bytes.
- `kinder`'s code is leaner and more comment-light, but doesn't carry
  that bug-driven context.

### 12. Node CLI niceties

- `kinder-wt-animate` cross-platform `open` (`darwin`/`linux`/`win32`);
  `kinder` only `open` (macOS).
- `kinder` uses shadow-idiomatic `["fs" :as fs]`; `kinder-wt-animate`
  uses `(js/require "node:fs")`.
- `kinder` has finer warnings (`--seed ignores --count`);
  `kinder-wt-animate` adds a
  `--out with --count > 1 reuses the same path` warning that `kinder`
  lacks.

## Net assessment

| Dimension | `kinder` | `kinder-wt-animate` |
|---|---|---|
| TO-CLJC adherence (no duplication of triptych logic / defaults) | weaker | stronger |
| Documentation freshness | updated | stale |
| Optional `showSaveFilePicker` | yes | no |
| Inline design rationale / bug context | sparse | richer |
| Build minimalism (no `deps.edn`) | yes | no |
| State separation of concerns | weaker | stronger |
| App-state / hot-reload idiom | many atoms | one `defonce` |
| UX polish (tooltips, cross-platform open) | thinner | richer |
| Code volume | similar (~2050 src LOC) | similar (~1860 src LOC) |

`kinder-wt-animate` won the architecture call (the `layouts` namespace +
state-takes-a-pane + single app-state atom) and the UX polish, but
skimped on the optional save path and let `cli.cljs` re-declare every
default — and never updated CLAUDE.md. `kinder` made the cleaner
build/options story and refreshed the docs, but duplicated the layout
dispatch across browser and CLI exactly the way TO-CLJC.md warned
against.

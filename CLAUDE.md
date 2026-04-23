# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Kinder is a generative art project. It procedurally composes abstract
works from nested rectangles and circles, using seeded randomness for
reproducibility. Output is SVG.

The runtime is entirely JavaScript. Shared generation + rendering logic
lives in ClojureScript (`.cljc`); a browser app and a Node CLI both call
the same shared code.

## Development workflow

```bash
# First time only
npm install

# Browser dev: watch-builds the app and serves public/ at :8080
npx shadow-cljs watch app
# open http://localhost:8080

# Node CLI: compile, then run
npx shadow-cljs compile cli
node dist/kinder-cli.js --layout triptych-variation --seed foo

# Production builds
npx shadow-cljs release app   # optimized public/assets/app/js
npx shadow-cljs release cli   # optimized dist/kinder-cli.js
```

## Architecture

All generation and SVG rendering is shared CLJC. Only DOM and filesystem
edges are platform-specific.

**`kinder.rng` (cljc)** — portable seeded PRNG (mulberry32). Boxed,
stateful: `make-rng` returns a volatile; `rand`, `rand-int`, `rand-nth`
mutate it and return the drawn value. `round` is a shared half-up
rounding helper so shared code never depends on host `Math/round`.
Seeds are strings (hashed into a 32-bit word via FNV-1a).

**`kinder.core` (cljc)** — pure generation logic.
- Three color palettes: `kinder-palette`, `red-palette`, `orange-palette`
- Recursive rectangle subdivision (horizontal/vertical, even/symmetric/random/stripe)
- Two-phase coloring: `assign-some-color` picks accent colors probabilistically; `express-some-color` selects final color by rect size, shape, and ID parity
- Circle placement with collision avoidance + coordinated-circle curves for triptychs
- Entry points: `generate-pane`, `mutate-pane`, `coordinated-circles`
- RNG is built from `:seed` inside these entry points and threaded through every recursive call

**`kinder.svg` (cljc)** — data-in, string-out SVG renderer. Numeric
formatting is hand-rolled (`fmt-fixed`) so output is byte-identical on
JVM and JS runtimes.

**`kinder.state` (cljc)** — pure transitions `step-state`, `back-state`,
`all-done?` over `{:pane :render-depth}`. Atom management is a
platform-edge concern.

**`kinder.options` (cljc)** — single source of truth for defaults and
input normalization. Both browser and CLI funnel user values through
`normalize`. Also hosts the shared filename helper.

**`kinder.browser` (cljs)** — browser entry. Reads form controls,
normalizes, generates locally, injects SVG into the DOM, and owns the
progressive-reveal animation loop (`requestAnimationFrame`). Save uses
`URL.createObjectURL` + `<a download>`, preferring
`showSaveFilePicker()` when available.

**`kinder.cli` (cljs)** — Node entry (`:node-script`). Parses flags,
normalizes, generates, writes SVG via `fs.writeFileSync`.

## Key concepts

- **Deterministic randomness.** The portable RNG keyed to a string seed
  gives identical output for identical inputs across runtimes. Integer
  seeds from the pre-migration (`random-seed`-based) codebase do **not**
  map to the new RNG's domain.
- **Render depth.** The tree is fully generated upfront; `take-depth`
  limits how deep the renderer walks, enabling progressive reveal.
- **Coordinate system.** Canvas is specified in abstract units
  (e.g. `[30 70]` at `10` pixels/unit). Subdivision and circle placement
  operate in units; unit is applied only at render.
- **Triptych sub-seeds.** Panel-specific seeds are derived by string
  concatenation (`(str seed "/l")`, `/c`, `/r`, `/cc`) so the top-level
  seed fully determines the work.

## Inspiration

Static images in `inspiration/` (symlinked from `public/inspiration/`)
are listed in `public/inspiration.json`. The browser fetches that
manifest for the gallery view. Add a new file and append its name to the
manifest.

(`AGENTS.md` is a symlink to this file — update this file and both
reflect the change.)

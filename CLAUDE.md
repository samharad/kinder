# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Kinder is a generative art project in Clojure that procedurally creates abstract compositions of nested rectangles and circles, using seeded randomness for reproducibility. Output is SVG files rendered via Quil (a Processing wrapper).

## Development workflow

This is a REPL-driven project with no build artifact. Development happens by starting a Clojure REPL and evaluating code from `src/kinder/dev.clj`.

```bash
# Download dependencies
clojure -P

# Start a REPL (then load dev.clj)
clojure -M:dev
```

The `kinder.dev` namespace is the interactive entry point. It initializes state, calls `(sketch)` to open a Quil window, and writes SVG to `output/svg/dev.svg`.

Spec instrumentation is active in dev via `(st/instrument)` and `(spect/instrument)` — runtime spec errors will surface as exceptions during development.

## Architecture

The project has three layers with strict dependency direction:

**`kinder.core`** — pure generation logic, no side effects
- Defines specs for `:color`, `:rect`, `:circle`, `:pane`
- Three color palettes: `kinder-palette`, `red-palette`, `orange-palette`
- Recursive rectangle subdivision strategies (horizontal/vertical, even/symmetric/random/stripe variants)
- Two-phase coloring: `assign-some-color` picks accent colors probabilistically; `express-some-color` selects final color based on rect size, shape, and ID parity
- Entry point: `(generate-pane dimensions & {:keys [seed palette]})` returns a complete pane data structure

**`kinder.state`** — atom-based state wrapping a pane
- State shape: `{:pane <pane-data> :render-depth <integer>}`
- `step!` increments render-depth one level at a time (progressive reveal effect)
- `complete!` steps to full depth; `re-init!` generates new artwork

**`kinder.draw`** — Quil visualization
- `draw` walks the rect tree and renders to screen or SVG
- Parent-bearing rects rendered neon pink to indicate partial tiling during animation
- Unit (pixels per coordinate unit) and stroke-weight are passed as options

**`intellij.clj`** — pREPL bridge for IntelliJ/Cursive, not part of the art system.

## Key concepts

- **Deterministic randomness:** The `random-seed` library seeds Clojure's RNG; the same seed always produces identical output.
- **Render depth:** The tree is fully generated upfront; render-depth limits how deep the walker descends, enabling progressive reveal animation.
- **Coordinate system:** Canvas is specified in abstract units (e.g. `[30 70]` units at `10` pixels/unit). Subdivision and circle placement operate in these units.
- **Circles:** Placed at rect corner coordinates with jitter, collision-checked against existing circles.

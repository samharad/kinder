# GitHub Pages Plan

## Goal

Host the browser app at:

- `https://kinder.samadams.dev/` for the latest pushed version
- `https://kinder.samadams.dev/<short-sha>/` for immutable historical snapshots

Primary requirement: links to serialized art should remain valid for pushed commits without manual deploy work.

## Assumptions

- GitHub Pages will serve the site from the custom subdomain `kinder.samadams.dev`.
- We only archive commits pushed to the deployment branch, likely `main`.
- We are fine with dropping source maps from published output.
- We want the smallest practical release build.
- Deployment must be automated from GitHub, not dependent on local manual steps.

## Constraints

- GitHub Pages publishes a single site at a time; historical commit URLs are not built in. We need to publish one site tree that contains both the latest version and archived commit snapshots.
- GitHub Pages has a published site size limit of 1 GB.
- Because old commits must remain reachable, we should archive snapshots when the commit is pushed rather than trying to rebuild old commits later.

## Current Size Baseline

Measured on 2026-04-23:

- `inspiration/`: about `8.5 MB`
- clean release payload excluding shared images:
  - `public/index.html`
  - `public/inspiration.json`
  - `public/assets/app/js/main.js`
  - `public/assets/app/js/manifest.edn`
  - total about `276 KB`
- current `public/` tree: about `11 MB`, but this includes stale watch-build `cljs-runtime/` output that should not be deployed

Implication:

- If shared images are published once, each archived commit is currently only about `276 KB`
- With a safety budget of roughly `850 MB`, current sizing supports on the order of `3000` archived commits before pruning

## Recommended Published Layout

Use one site tree with shared assets at the root and one directory per archived commit.

```text
/index.html                       # latest
/versions.json                    # manifest of archived versions
/inspiration.json                 # shared
/inspiration/*                    # shared
/assets/commits/<full-sha>/main.js
/assets/commits/<full-sha>/manifest.edn
/<short-sha>/index.html
/<short-sha>/meta.json            # optional, useful for debugging and tooling
```

Notes:

- `index.html` at the root always points to the latest pushed commit.
- Each `/<short-sha>/index.html` is immutable once published.
- Use a 12-character short SHA for URLs and store the full 40-character SHA in `versions.json`.
- Keep shared images at the root so they are not duplicated per commit.
- Root-relative URLs are acceptable here because the final host is the custom domain root, not a repo subpath.

## Build Changes

### 1. Publish only release output

The deployment workflow should use `shadow-cljs release app` and stage a clean publish directory. It should not deploy the existing `public/` tree as-is, because that tree currently accumulates dev/watch artifacts under `public/assets/app/js/cljs-runtime`.

Implementation detail:

- build into a temporary staging directory, or
- clean the staged `assets/app/js` directory before copying release outputs

### 2. Drop source maps from published output

We do not want source maps in production archives.

Practical rule:

- if release output emits maps, delete them in the staging step before publish
- do not copy watch-build artifacts at all

### 3. Keep the release fully minified

Use the production `shadow-cljs release app` build as the only deploy build. That is the correct place to get Closure optimization and a compact bundle.

### 4. Make per-commit HTML point at commit-scoped JS

The app entry HTML should reference commit-specific JS under:

- `/assets/commits/<full-sha>/main.js`

This makes each archived URL immutable even as `/` moves forward.

## Deployment Architecture

Use a GitHub Actions workflow as the source of truth.

Recommended trigger:

- `push` on `main`

Recommended Pages source:

- a dedicated `gh-pages` branch containing the fully assembled published site tree

Why use a branch instead of only the artifact-based Pages deploy action:

- we need persistent state between deploys
- we need to append new commit snapshots without reconstructing the whole archive from scratch
- pruning old snapshots is much easier when the published site exists in git

## Workflow Outline

### 1. Trigger on push

Run one workflow on each push to `main`.

### 2. Determine which commits need archiving

Use the pushed range:

- from `${{ github.event.before }}`
- to `${{ github.sha }}`

Then enumerate commits in order:

- `git rev-list --reverse <before>..<after>`

This matters because a single push may contain multiple commits, and the goal is to archive every pushed commit, not just the new tip.

### 3. Check out the publish branch

Materialize `gh-pages` into a worktree or temporary checkout.

Expected existing files there:

- current `/`
- existing `/<short-sha>/` snapshots
- `versions.json`

### 4. Ensure shared assets exist once

Copy these once into the publish branch if missing or changed:

- `inspiration/`
- `inspiration.json`

### 5. Build and archive each new commit

For each commit in the push range:

- create a detached worktree for that commit
- install dependencies
- run the production app build
- collect only release files into a temporary staging directory
- write commit-scoped asset files under `/assets/commits/<full-sha>/`
- write `/<short-sha>/index.html` that references the commit-scoped asset path
- optionally write `/<short-sha>/meta.json`
- append an entry to `versions.json`

Suggested `versions.json` fields:

- `short_sha`
- `full_sha`
- `committed_at`
- `pushed_at`
- `url`
- `latest` boolean for the current root target

### 6. Update latest

After archiving all new commits:

- rewrite `/index.html` to point at the newest pushed commit
- update `versions.json` so only the newest entry is marked latest

### 7. Enforce retention

After staging new versions, measure the assembled site size.

Policy:

- target soft cap: `850 MB`
- if over cap, prune oldest archived commit directories first
- never prune the current latest version
- prune matching commit assets under `/assets/commits/<full-sha>/`
- update `versions.json` after pruning

This gives headroom under the 1 GB Pages limit.

### 8. Publish automatically

Push the updated `gh-pages` branch from the workflow.

GitHub Pages should then publish from:

- branch: `gh-pages`
- folder: `/`

## Optional Local Hook

Local git hooks should not be the deployment mechanism. They are useful only as guardrails.

If desired, install a `pre-push` hook that runs:

- `npm ci` only when lockfile changes, or skip if already installed
- `npm run release:app`

Purpose:

- catch obviously broken pushes before they reach CI

But:

- the authoritative archive must still be created by GitHub Actions
- hooks are bypassable and do not run for merges made in GitHub

## HTML Strategy

Keep the HTML for archived versions intentionally small and dumb.

Two good options:

### Option A: one static template per snapshot

Each archived `index.html` is generated from a simple template and only differs in the JS path and embedded metadata.

Pros:

- simple
- easy to debug
- no runtime redirect logic

### Option B: one shared loader plus metadata

Each archived path contains a tiny metadata file, and a shared loader bootstraps the correct JS.

Pros:

- less HTML duplication

Cons:

- more moving parts

Recommendation:

- start with Option A

The per-snapshot HTML is small enough that simplicity wins.

## Domain Setup

Configure GitHub Pages with the custom domain:

- `kinder.samadams.dev`

DNS:

- add a `CNAME` record for `kinder.samadams.dev` pointing to `samadams.github.io`

GitHub:

- set the repo Pages custom domain to `kinder.samadams.dev`
- enable HTTPS after DNS is valid
- verify the domain in GitHub if available

## Important Operational Notes

- Use full SHA internally even if the public URL uses a short SHA.
- Treat archived commit paths as immutable.
- Archive at push time. Do not rely on rebuilding historical commits later.
- Keep the deploy tree generated by CI only; do not hand-edit the publish branch.
- If the build pipeline changes later, old archived snapshots remain valid because they are already published static files.

## Suggested Implementation Order

1. Add a clean staging build script for production pages output.
2. Add a minimal HTML templating step that can emit root and per-commit pages.
3. Add a `versions.json` generator.
4. Add the GitHub Actions workflow to build and update `gh-pages`.
5. Add pruning logic once the basic archive flow works.
6. Optionally add a local `pre-push` hook installer.

## Open Questions

- Should archiving happen only for pushes to `main`, or also for tags?
- Should old commits be pruned strictly by age, by size pressure, or never until the soft cap is hit?
- Do we want `/<short-sha>/` only, or also `/commit/<full-sha>/` as a canonical internal path?
- Do we want `versions.json` exposed for UI navigation, or only for CI bookkeeping?

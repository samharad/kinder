import {execFileSync} from "node:child_process";
import {access, copyFile, cp, mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile} from "node:fs/promises";
import os from "node:os";
import path from "node:path";

const ZERO_SHA = "0".repeat(40);

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith("--")) {
      continue;
    }

    const key = token.slice(2);
    const next = argv[i + 1];
    if (!next || next.startsWith("--")) {
      args[key] = "true";
      continue;
    }

    args[key] = next;
    i += 1;
  }
  return args;
}

function run(cmd, args, options = {}) {
  return execFileSync(cmd, args, {
    cwd: options.cwd,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "inherit"],
  }).trim();
}

function runInherited(cmd, args, options = {}) {
  execFileSync(cmd, args, {
    cwd: options.cwd,
    stdio: "inherit",
  });
}

async function pathExists(target) {
  try {
    await access(target);
    return true;
  } catch {
    return false;
  }
}

function fullShaFor(repoDir, rev) {
  return run("git", ["rev-parse", rev], {cwd: repoDir});
}

function shortShaFor(repoDir, sha) {
  return run("git", ["rev-parse", "--short=12", sha], {cwd: repoDir});
}

function committedAtFor(repoDir, sha) {
  return run("git", ["show", "-s", "--format=%cI", sha], {cwd: repoDir});
}

function listPushedCommits(repoDir, before, after) {
  const latest = fullShaFor(repoDir, after);

  if (!before || before === ZERO_SHA) {
    return [latest];
  }

  const previous = fullShaFor(repoDir, before);
  const output = run("git", ["rev-list", "--reverse", `${previous}..${latest}`], {cwd: repoDir});
  if (!output) {
    return [latest];
  }

  return output.split("\n").filter(Boolean);
}

async function ensureDir(target) {
  await mkdir(target, {recursive: true});
}

async function emptyDir(target) {
  await rm(target, {recursive: true, force: true});
  await mkdir(target, {recursive: true});
}

async function collectFiles(rootDir) {
  const files = [];

  async function walk(dir) {
    const entries = await readdir(dir, {withFileTypes: true});
    for (const entry of entries) {
      const fullPath = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        await walk(fullPath);
        continue;
      }
      if (entry.isFile()) {
        files.push(fullPath);
      }
    }
  }

  await walk(rootDir);
  return files;
}

function rewriteIndexHtml(html, fullSha) {
  const sourcePrefix = "/assets/app/js/";
  const targetPrefix = `/assets/commits/${fullSha}/`;

  if (!html.includes(sourcePrefix)) {
    throw new Error("public/index.html does not reference /assets/app/js/");
  }

  return html.replaceAll(sourcePrefix, targetPrefix);
}

function renderRootRedirectHtml(latestEntry) {
  const targetPath = latestEntry.url.endsWith("/") ? latestEntry.url : `${latestEntry.url}/`;

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>kinder</title>
<meta name="robots" content="noindex">
<meta name="viewport" content="width=device-width, initial-scale=1">
<script>
  (function () {
    var target = ${JSON.stringify(targetPath)};
    var next = target + window.location.search + window.location.hash;
    window.location.replace(next);
  }());
</script>
<meta http-equiv="refresh" content="0; url=${targetPath}">
<style>
  body {
    margin: 0;
    padding: 24px;
    font: 14px/1.4 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    background: #fafafa;
    color: #222;
  }
</style>
</head>
<body>
<p>Redirecting to the latest snapshot: <a href="${targetPath}">${targetPath}</a></p>
</body>
</html>
`;
}

async function loadManifest(siteDir) {
  const manifestPath = path.join(siteDir, "versions.json");
  if (!(await pathExists(manifestPath))) {
    return {generated_at: null, latest: null, versions: []};
  }

  const raw = JSON.parse(await readFile(manifestPath, "utf8"));
  const versions = Array.isArray(raw)
    ? raw
    : Array.isArray(raw.versions)
      ? raw.versions
      : [];

  return {
    generated_at: raw.generated_at ?? null,
    latest: raw.latest ?? null,
    versions,
  };
}

function upsertVersion(manifest, entry) {
  const existingIndex = manifest.versions.findIndex((item) => item.full_sha === entry.full_sha);
  if (existingIndex === -1) {
    manifest.versions.push(entry);
    return;
  }

  manifest.versions[existingIndex] = {
    ...manifest.versions[existingIndex],
    ...entry,
  };
}

function markLatest(manifest, latestSha) {
  manifest.versions = manifest.versions.map((entry) => ({
    ...entry,
    latest: entry.full_sha === latestSha,
  }));

  const latestEntry = manifest.versions.find((entry) => entry.full_sha === latestSha) ?? null;
  manifest.latest = latestEntry
    ? {
        short_sha: latestEntry.short_sha,
        full_sha: latestEntry.full_sha,
        url: latestEntry.url,
      }
    : null;
}

async function copyBuildOutput(buildDir, destDir) {
  await emptyDir(destDir);
  const files = await collectFiles(buildDir);

  for (const file of files) {
    if (file.endsWith(".map")) {
      continue;
    }

    const relative = path.relative(buildDir, file);
    const destFile = path.join(destDir, relative);
    await ensureDir(path.dirname(destFile));
    await copyFile(file, destFile);
  }
}

async function buildSnapshot({repoDir, siteDir, fullSha, pushedAt}) {
  const shortSha = shortShaFor(repoDir, fullSha);
  const committedAt = committedAtFor(repoDir, fullSha);
  const tempRoot = await mkdtemp(path.join(os.tmpdir(), "kinder-pages-"));
  const worktreeDir = path.join(tempRoot, shortSha);

  try {
    runInherited("git", ["worktree", "add", "--detach", worktreeDir, fullSha], {cwd: repoDir});

    if (await pathExists(path.join(worktreeDir, "package-lock.json"))) {
      runInherited("npm", ["ci"], {cwd: worktreeDir});
    } else {
      runInherited("npm", ["install"], {cwd: worktreeDir});
    }

    runInherited("npx", ["shadow-cljs", "release", "app"], {cwd: worktreeDir});

    const buildDir = path.join(worktreeDir, "public", "assets", "app", "js");
    const htmlTemplate = await readFile(path.join(worktreeDir, "public", "index.html"), "utf8");
    const snapshotHtml = rewriteIndexHtml(htmlTemplate, fullSha);
    const commitAssetDir = path.join(siteDir, "assets", "commits", fullSha);
    const snapshotDir = path.join(siteDir, shortSha);

    await copyBuildOutput(buildDir, commitAssetDir);
    await emptyDir(snapshotDir);
    await writeFile(path.join(snapshotDir, "index.html"), snapshotHtml);

    const meta = {
      short_sha: shortSha,
      full_sha: fullSha,
      committed_at: committedAt,
      pushed_at: pushedAt,
      url: `/${shortSha}/`,
      asset_path: `/assets/commits/${fullSha}/main.js`,
      latest: false,
    };

    await writeFile(path.join(snapshotDir, "meta.json"), `${JSON.stringify(meta, null, 2)}\n`);
    return meta;
  } finally {
    try {
      runInherited("git", ["worktree", "remove", "--force", worktreeDir], {cwd: repoDir});
    } catch (error) {
      console.warn(`failed to remove worktree ${worktreeDir}:`, error.message);
    }
    await rm(tempRoot, {recursive: true, force: true});
  }
}

async function syncSharedAssets(repoDir, siteDir) {
  await rm(path.join(siteDir, "assets", "app"), {recursive: true, force: true});
  await rm(path.join(siteDir, "inspiration"), {recursive: true, force: true});
  await cp(path.join(repoDir, "inspiration"), path.join(siteDir, "inspiration"), {recursive: true});
  await copyFile(
    path.join(repoDir, "public", "inspiration.json"),
    path.join(siteDir, "inspiration.json"),
  );
}

async function sizeOf(target) {
  if (!(await pathExists(target))) {
    return 0;
  }

  const info = await stat(target);
  if (!info.isDirectory()) {
    return info.size;
  }

  let total = 0;
  const entries = await readdir(target, {withFileTypes: true});
  for (const entry of entries) {
    if (entry.name === ".git") {
      continue;
    }
    total += await sizeOf(path.join(target, entry.name));
  }
  return total;
}

async function pruneToSoftCap(siteDir, manifest, softCapBytes) {
  let totalSize = await sizeOf(siteDir);
  if (totalSize <= softCapBytes) {
    return {totalSize, pruned: []};
  }

  const latestSha = manifest.latest?.full_sha;
  const pruned = [];
  const kept = [];

  for (const entry of manifest.versions) {
    if (entry.full_sha === latestSha) {
      kept.push(entry);
      continue;
    }

    if (totalSize <= softCapBytes) {
      kept.push(entry);
      continue;
    }

    const snapshotDir = path.join(siteDir, entry.short_sha);
    const assetDir = path.join(siteDir, "assets", "commits", entry.full_sha);
    const reclaimed = (await sizeOf(snapshotDir)) + (await sizeOf(assetDir));

    await rm(snapshotDir, {recursive: true, force: true});
    await rm(assetDir, {recursive: true, force: true});

    totalSize -= reclaimed;
    pruned.push(entry);
  }

  manifest.versions = kept;
  return {totalSize, pruned};
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const repoDir = process.cwd();
  const siteDir = path.resolve(repoDir, args["site-dir"] ?? "");
  const before = args.before ?? process.env.GITHUB_EVENT_BEFORE ?? "";
  const after = args.after ?? process.env.GITHUB_SHA ?? "HEAD";
  const domain = args.domain ?? process.env.PAGES_DOMAIN ?? "";
  const softCapMb = Number(args["soft-cap-mb"] ?? process.env.PAGES_SOFT_CAP_MB ?? "850");

  if (!args["site-dir"]) {
    throw new Error("missing required --site-dir");
  }

  if (!Number.isFinite(softCapMb) || softCapMb <= 0) {
    throw new Error("soft cap must be a positive number of megabytes");
  }

  await ensureDir(siteDir);
  const manifest = await loadManifest(siteDir);
  const pushedAt = new Date().toISOString();
  const commits = [...new Set(listPushedCommits(repoDir, before, after))];
  const latestSha = fullShaFor(repoDir, after);

  for (const fullSha of commits) {
    const existing = manifest.versions.find((entry) => entry.full_sha === fullSha);
    const shortSha = existing?.short_sha ?? shortShaFor(repoDir, fullSha);
    const snapshotPath = path.join(siteDir, shortSha, "index.html");
    const assetPath = path.join(siteDir, "assets", "commits", fullSha, "main.js");

    if (existing && (await pathExists(snapshotPath)) && (await pathExists(assetPath))) {
      continue;
    }

    const entry = await buildSnapshot({repoDir, siteDir, fullSha, pushedAt});
    upsertVersion(manifest, entry);
  }

  let latestEntry = manifest.versions.find((entry) => entry.full_sha === latestSha) ?? null;
  if (!latestEntry) {
    latestEntry = await buildSnapshot({repoDir, siteDir, fullSha: latestSha, pushedAt});
    upsertVersion(manifest, latestEntry);
  }

  await syncSharedAssets(repoDir, siteDir);
  await writeFile(path.join(siteDir, ".nojekyll"), "\n");
  if (domain) {
    await writeFile(path.join(siteDir, "CNAME"), `${domain}\n`);
  }

  markLatest(manifest, latestSha);
  latestEntry = manifest.versions.find((entry) => entry.full_sha === latestSha);
  if (!latestEntry) {
    throw new Error(`latest version ${latestSha} is missing after assembly`);
  }

  await writeFile(path.join(siteDir, "index.html"), renderRootRedirectHtml(latestEntry));

  const pruneResult = await pruneToSoftCap(siteDir, manifest, softCapMb * 1024 * 1024);
  markLatest(manifest, latestSha);
  manifest.generated_at = new Date().toISOString();

  await writeFile(path.join(siteDir, "versions.json"), `${JSON.stringify(manifest, null, 2)}\n`);

  const summary = {
    latest: manifest.latest,
    versions: manifest.versions.length,
    pruned: pruneResult.pruned.map((entry) => entry.short_sha),
    total_size_mb: Number((pruneResult.totalSize / (1024 * 1024)).toFixed(2)),
  };

  console.log(JSON.stringify(summary, null, 2));
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});

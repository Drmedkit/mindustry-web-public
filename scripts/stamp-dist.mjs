#!/usr/bin/env node
// Final step of the web build: versions script URLs by content hash and checks
// Cloudflare Pages' per-file size limit.
//
// Assets are versioned in the preloader from their manifest sha256; the page
// scripts and the Arc FX shaders are versioned here with a build ID. Versioned
// URLs let the domain proxy cache them as immutable without serving stale
// files after a deploy.

import {execFileSync} from "node:child_process";
import {createHash} from "node:crypto";
import {readdir, readFile, stat, writeFile} from "node:fs/promises";
import path from "node:path";
import {fileURLToPath} from "node:url";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const dist = path.resolve(process.argv[2] ?? path.join(projectRoot, "browser-game", "build", "browser-dist"));
const pagesFileLimit = 25 * 1024 * 1024;

const hash = bytes => createHash("sha256").update(bytes).digest("hex").slice(0, 16);

async function walk(directory) {
    const files = [];
    for (const entry of await readdir(directory, {withFileTypes: true})) {
        const absolute = path.join(directory, entry.name);
        if (entry.isDirectory()) files.push(...await walk(absolute));
        else if (entry.isFile()) files.push(absolute);
    }
    return files;
}

const oversized = [];
for (const file of await walk(dist)) {
    const {size} = await stat(file);
    if (size > pagesFileLimit) oversized.push(`${path.relative(dist, file)} (${(size / 1048576).toFixed(1)} MiB)`);
}
if (oversized.length) {
    throw new Error(`Cloudflare Pages rejects files over 25 MiB:\n - ${oversized.join("\n - ")}`);
}

const indexPath = path.join(dist, "index.html");
let index = await readFile(indexPath, "utf8");
const versions = {};
for (const script of ["js/mindustry.js", "freetype.js", "vfs-storage.js", "png-decoder.js", "asset-preloader.js"]) {
    versions[script] = hash(await readFile(path.join(dist, script)));
    const pattern = new RegExp(`(src="${script.replace(/[.]/g, "\\.")})(\\?v=[^"]*)?"`);
    if (!pattern.test(index)) throw new Error(`index.html does not load ${script}`);
    index = index.replace(pattern, `$1?v=${versions[script]}"`);
}
const build = hash(Object.values(versions).join(""));
index = index.replace(/globalThis\.mindustryBuild = '[^']*'/, `globalThis.mindustryBuild = '${build}'`);
if (!index.includes(`globalThis.mindustryBuild = '${build}'`)) throw new Error("index.html does not define mindustryBuild");
await writeFile(indexPath, index, "utf8");

// The GitHub repository this checkout was cloned from (the "origin" remote), so a
// fork's build links its own source; otherwise the public upstream repository.
function sourceRepository() {
    const fallback = "https://github.com/Drmedkit/mindustry-web-public";
    let remote;
    try {
        remote = execFileSync("git", ["-C", projectRoot, "remote", "get-url", "origin"], {encoding: "utf8", stdio: ["ignore", "pipe", "ignore"]}).trim();
    } catch {
        return fallback;
    }
    const match = remote.match(/github\.com[:/]([^/]+)\/([^/]+?)(?:\.git)?\/?$/);
    return match ? `https://github.com/${match[1]}/${match[2]}` : fallback;
}

// The source offer points at the exact commit this build came from.
let sourceUrl = process.env.MINDUSTRY_SOURCE_URL;
if (!sourceUrl) {
    const commit = execFileSync("git", ["-C", projectRoot, "rev-parse", "HEAD"], {encoding: "utf8"}).trim();
    const dirty = execFileSync("git", ["-C", projectRoot, "status", "--porcelain", "--untracked-files=no"], {encoding: "utf8"}).trim();
    if (dirty) console.warn("Warning: building with uncommitted changes; the source link names the last commit.");
    sourceUrl = `${sourceRepository()}/tree/${commit}`;
}
const noticePath = path.join(dist, "NOTICE.txt");
await writeFile(noticePath, (await readFile(noticePath, "utf8")).replace("@SOURCE_URL@", sourceUrl), "utf8");
index = await readFile(indexPath, "utf8");
index = index.replace(/(<a id="source-link" href=")[^"]*"/, `$1${sourceUrl}"`);
await writeFile(indexPath, index, "utf8");

const {size} = await stat(path.join(dist, "js", "mindustry.js"));
console.log(`Stamped build ${build}; mindustry.js is ${(size / 1048576).toFixed(1)} of 25 MiB.`);

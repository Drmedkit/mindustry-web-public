#!/usr/bin/env node

import {createHash} from "node:crypto";
import {readdir, readFile, stat, writeFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";
import path from "node:path";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDirectory, "..");
const assetRoot = path.join(projectRoot, "Mindustry", "core", "assets");
const defaultOutputPath = path.join(projectRoot, "browser-port", "assets-manifest.json");

function parseOutputPath(arguments_) {
    let outputPath = defaultOutputPath;

    for (let index = 0; index < arguments_.length; index++) {
        const argument = arguments_[index];
        if (argument === "--output") {
            const value = arguments_[++index];
            if (!value) {
                throw new Error("--output requires a path");
            }
            outputPath = path.resolve(value);
        } else {
            throw new Error(`unknown argument: ${argument}`);
        }
    }

    return outputPath;
}

const outputPath = parseOutputPath(process.argv.slice(2));

const sourceCommit = "439799ce8ff1480d1d169c5ba7ff3def422999de";

async function walk(directory, prefix = "") {
    const entries = await readdir(directory, {withFileTypes: true});
    entries.sort((left, right) => left.name.localeCompare(right.name, "en"));

    const files = [];
    for (const entry of entries) {
        const relativePath = prefix ? `${prefix}/${entry.name}` : entry.name;
        const absolutePath = path.join(directory, entry.name);

        if (entry.isDirectory()) {
            files.push(...await walk(absolutePath, relativePath));
        } else if (entry.isFile()) {
            files.push({relativePath, absolutePath});
        }
    }
    return files;
}

function groupFor(relativePath) {
    const separator = relativePath.indexOf("/");
    return separator === -1 ? "root" : relativePath.slice(0, separator);
}

// Pages holding only hidden (Erekir) sprites: the browser substitutes a placeholder.
// Missing file: no pages are deferred (for example in a freshly imported atlas).
const deferredPages = new Set(await readFile(path.join(projectRoot, "browser-port", "deferred-atlas-pages.json"), "utf8")
    .then(text => JSON.parse(text).pages, () => []));

const sourceFiles = await walk(assetRoot);
const files = [];
const groups = {};
let totalBytes = 0;

for (const sourceFile of sourceFiles) {
    const bytes = await readFile(sourceFile.absolutePath);
    const metadata = await stat(sourceFile.absolutePath);
    const group = groupFor(sourceFile.relativePath);
    const size = metadata.size;

    totalBytes += size;
    groups[group] ??= {files: 0, bytes: 0};
    groups[group].files += 1;
    groups[group].bytes += size;

    files.push({
        path: sourceFile.relativePath,
        group,
        bytes: size,
        sha256: createHash("sha256").update(bytes).digest("hex"),
        ...(deferredPages.has(sourceFile.relativePath) ? {deferred: true} : {})
    });
}

const presentPaths = new Set(files.map(file => file.path));
const requiredGeneratedAssets = [
    "version.properties",
    "sprites/sprites.aatls",
    "sprites/fallback/sprites.aatls"
].map(requiredPath => ({
    path: requiredPath,
    present: presentPaths.has(requiredPath)
}));

const manifest = {
    schemaVersion: 1,
    source: {
        project: "Anuken/Mindustry",
        commit: sourceCommit,
        root: "core/assets"
    },
    totals: {
        files: files.length,
        bytes: totalBytes
    },
    requiredGeneratedAssets,
    groups,
    files
};

await writeFile(outputPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
console.log(`Wrote ${files.length} files (${totalBytes} bytes) to ${outputPath}`);

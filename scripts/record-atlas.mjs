#!/usr/bin/env node
// Records the generated atlases: provenance hashes, and which atlas pages hold
// only deferred (hidden-content) sprites. The page never downloads those.

import {createHash} from "node:crypto";
import {readFileSync, writeFileSync} from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {readAtlas} from "./lib/aatls.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const assets = path.join(root, "Mindustry/core/assets");
const deferredSprites = new Set(readFileSync(path.join(root, "browser-port/deferred-sprites.txt"), "utf8").split("\n").filter(Boolean));
const atlases = ["sprites/sprites.aatls", "sprites/fallback/sprites.aatls"];

const deferredPages = [];
for (const atlas of atlases) {
    for (const page of readAtlas(readFileSync(path.join(assets, atlas)))) {
        const hidden = page.regions.filter(region => deferredSprites.has(region.name)).length;
        const pagePath = path.posix.join(path.posix.dirname(atlas), page.image);
        if (hidden === page.regions.length) deferredPages.push(pagePath);
        else if (hidden > 0) throw new Error(`${pagePath} mixes ${hidden} deferred and ${page.regions.length - hidden} visible sprites`);
    }
}
writeFileSync(path.join(root, "browser-port/deferred-atlas-pages.json"), `${JSON.stringify({
    description: "Atlas pages holding only sprites of content hidden in the browser build. The page substitutes a 1x1 transparent image and never downloads them.",
    pages: deferredPages
}, null, 2)}\n`);

const provenancePath = path.join(root, "browser-port/generated-atlas-provenance.json");
const provenance = JSON.parse(readFileSync(provenancePath, "utf8"));
provenance.generatedAt = new Date().toISOString();
provenance.command = "scripts/pack-atlas.sh (Mindustry :tools:pack with -PsplitSprites=scripts/split-erekir-sprites.mjs)";
provenance.outputs = atlases.map(atlas => ({
    path: atlas,
    sha256: createHash("sha256").update(readFileSync(path.join(assets, atlas))).digest("hex")
}));
writeFileSync(provenancePath, `${JSON.stringify(provenance, null, 2)}\n`);
console.log(`Deferred atlas pages: ${deferredPages.join(", ")}`);

#!/usr/bin/env node
// Atlas packing hook (Mindustry/tools/build.gradle, -PsplitSprites=<this file>).
//
// Moves the sprites of content the browser build hides (Erekir) into
// sprites_out/zz-erekir/, which has its own pack.json and therefore its own
// atlas pages. The browser never downloads those pages; see
// browser-port/deferred-atlas-pages.json and asset-preloader.js.
//
// A sprite belongs to the content whose name is the longest prefix of the
// sprite name (after an optional block-/unit-/item-/liquid-/status- prefix),
// so "reinforced-conduit-top" is Erekir while "conduit-top" stays Serpulo.
// Environment sprites always stay: several Serpulo maps use Erekir walls.

import {copyFileSync, mkdirSync, readdirSync, readFileSync, renameSync, writeFileSync} from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const spritesOut = path.resolve(process.argv[2] ?? path.join(projectRoot, "Mindustry/core/assets-raw/sprites_out"));
const snapshot = JSON.parse(readFileSync(path.join(projectRoot, "browser-port/content-snapshot.json"), "utf8"));
const deferredFolder = "zz-erekir";

const owners = new Map();
for (const content of snapshot) {
    if (!["block", "unit", "item", "liquid", "status"].includes(content.type)) continue;
    // A name used by both a visible and a hidden object stays visible.
    owners.set(content.name, (owners.get(content.name) ?? true) && content.erekirOnly);
}
const names = [...owners.keys()].sort((a, b) => b.length - a.length);

export function owner(candidate) {
    // Names are sorted longest first, so the first match is the longest.
    // Variant sprites append a number ("red-stone-wall2").
    return names.find(name => candidate === name || candidate.startsWith(`${name}-`)
        || (candidate.startsWith(name) && /^\d+$/.test(candidate.slice(name.length)))) ?? null;
}

export function spriteOwner(region) {
    // Generated icons are "<type>-<name>-full" etc. The name after the prefix
    // decides, not the prefix itself (a unit is literally called "block").
    const stripped = region.replace(/^(block|unit|item|liquid|status)-/, "");
    return (stripped !== region ? owner(stripped) : null) ?? owner(region);
}

export function isHiddenSprite(region) {
    const best = spriteOwner(region);
    return best != null && owners.get(best) === true;
}

function walk(directory, relative = "") {
    const files = [];
    for (const entry of readdirSync(directory, {withFileTypes: true})) {
        const child = relative ? `${relative}/${entry.name}` : entry.name;
        if (entry.isDirectory()) files.push(...walk(path.join(directory, entry.name), child));
        else if (entry.name.endsWith(".png")) files.push(child);
    }
    return files;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const target = path.join(spritesOut, deferredFolder);
    mkdirSync(target, {recursive: true});
    copyFileSync(path.join(spritesOut, "pack.json"), path.join(target, "pack.json"));

    // Size pages to their contents instead of the next power of two: without
    // Erekir the main page needs a little over 4096x2048, and a 4096x4096 page
    // would decode and upload 64 MB for it. The atlas uses no mipmaps and
    // clamps at the edges, so non-power-of-two pages work in WebGL 1 and 2.
    for (const pack of walk(spritesOut).length >= 0 ? [path.join(spritesOut, "pack.json")] : []) {
        const settings = readFileSync(pack, "utf8").trim();
        if (!/\bpot\s*:/.test(settings)) writeFileSync(pack, settings.replace(/}\s*$/, ",\n  pot: false\n}\n"));
    }

    const spriteName = file => path.basename(file).replace(/(\.9)?\.png$/, "");
    const files = walk(spritesOut);
    // Terrain keeps every sprite, generated icons included: Serpulo maps use
    // several Erekir walls and floors, and the minimap and editor draw icons.
    const terrain = new Set(files.filter(file => file.startsWith("blocks/environment/") || file.startsWith("blocks/props/"))
        .map(file => spriteOwner(spriteName(file))).filter(Boolean));

    const moved = [];
    for (const file of files) {
        if (file.startsWith(`${deferredFolder}/`) || file.startsWith("blocks/environment/")) continue;
        const region = spriteName(file);
        if (!isHiddenSprite(region) || terrain.has(spriteOwner(region))) continue;
        renameSync(path.join(spritesOut, file), path.join(target, path.basename(file)));
        moved.push(region);
    }
    writeFileSync(path.join(projectRoot, "browser-port/deferred-sprites.txt"), `${moved.sort().join("\n")}\n`);
    console.log(`Moved ${moved.length} hidden-content sprites to ${deferredFolder}/.`);
}

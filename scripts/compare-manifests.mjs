#!/usr/bin/env node
// Compares two asset manifests, ignoring version.properties: Mindustry's build
// rewrites it with a timestamp on every compile, so its size and hash churn.
import {readFileSync} from "node:fs";

const load = file => {
    const manifest = JSON.parse(readFileSync(file, "utf8"));
    const files = manifest.files.filter(entry => entry.path !== "version.properties");
    return JSON.stringify({files, generated: manifest.requiredGeneratedAssets, source: manifest.source});
};
const [committed, fresh] = process.argv.slice(2);
if (load(committed) !== load(fresh)) {
    console.error(`${committed} is out of date; run node scripts/generate-asset-manifest.mjs and commit it.`);
    process.exit(1);
}

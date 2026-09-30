# Browser runtime

This directory contains generated asset metadata for the static browser
distribution. The checked-in manifest is deterministic for the pinned
Mindustry source snapshot and lets the browser preload assets into a synchronous
in-memory filesystem before Java initialization begins. The deployable output
is emitted to `browser-game/build/browser-dist`.

Regenerate it with:

```bash
node ../scripts/generate-asset-manifest.mjs
```

The source archive did not include generated `version.properties` or either
packed sprite atlas. Both atlases have now been generated from the pinned
sources; their provenance is recorded in `generated-atlas-provenance.json`.
`requiredGeneratedAssets` records these outputs so the web build can fail early
instead of producing a mysterious startup error.

As a temporary bootstrap, the two generated atlases can be imported from a
user-supplied official Mindustry JAR, ZIP, or APK:

```bash
python3 ../scripts/import-generated-atlases.py ~/Downloads/Mindustry.jar
```

The importer only reads ZIP entries, rejects unsafe or ambiguous paths, and
does not replace existing atlases unless `--overwrite` is passed. Files are
staged and published with atomic renames, with rollback on a failed manifest
check. The importer regenerates and validates this directory's asset manifest and writes
`generated-atlas-provenance.json` with the archive and entry SHA-256 hashes.
That provenance records the supplied bytes; it is not proof that the archive
was published by the upstream project.

Run its synthetic-archive safety tests from the project root with:

```bash
python3 -m unittest -v scripts/tests/test_import_generated_atlases.py
```

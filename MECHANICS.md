# Working on the game

The browser game is the pinned Mindustry engine compiled with TeaVM. Edit the
source here, run `npm run build:web`, and serve `browser-game/build/browser-dist`
over HTTP.

## Where to edit

| Change | Source |
| --- | --- |
| Sector capture waves and campaign difficulty | [`SectorPresets.java`](Mindustry/core/src/mindustry/content/SectorPresets.java) |
| Building costs, production, turrets, and block stats | [`Blocks.java`](Mindustry/core/src/mindustry/content/Blocks.java) |
| Unit stats, weapons, and behavior | [`UnitTypes.java`](Mindustry/core/src/mindustry/content/UnitTypes.java) |
| Item definitions | [`Items.java`](Mindustry/core/src/mindustry/content/Items.java) |
| Campaign research tree | [`SerpuloTechTree.java`](Mindustry/core/src/mindustry/content/SerpuloTechTree.java) |
| Shared wave progression and victory logic | [`Logic.java`](Mindustry/core/src/mindustry/core/Logic.java) |
| Campaign map data (for example Ground Zero and Frozen Forest) | [`serpulo/`](Mindustry/core/assets/maps/serpulo/) (`groundZero.msav`, `frozenForest.msav`) |
| Browser graphics, input, storage, and platform support | [`backend-teavm/`](Arc/backends/backend-teavm/) |
| Which planets and content are hidden (Serpulo only) | [`SerpuloOnly.java`](browser-game/src/main/java/io/mindustry/browser/SerpuloOnly.java) |
| First-run graphics preset for weak devices | `applyGraphicsPreset()` in [`MindustryBrowserLauncher.java`](browser-game/src/main/java/io/mindustry/browser/MindustryBrowserLauncher.java) |
| Startup flow: first sector, campaign map, co-op room links | [`MindustryBrowserLauncher.java`](browser-game/src/main/java/io/mindustry/browser/MindustryBrowserLauncher.java) |
| Which assets download at startup and which on demand | [`asset-preloader.js`](browser-game/src/main/webapp/asset-preloader.js) |

Changes to `Mindustry/` change the game itself, for the campaign, custom games
and co-op alike. Browser-only behaviour belongs in the launcher or in
`backend-teavm`.

## Edit, build, and check

```bash
npm run build:web
npm run check
python3 -m http.server 8080 --directory browser-game/build/browser-dist
```

Open `http://localhost:8080/` and check the behavior you changed.
`npm run check:js` runs the fast Node tests without rebuilding.

If you add block classes, regenerate the browser's explicit block factories
with `java scripts/src/BlockBuildFactoryGenerator.java --write` before building.
Asset edits require a matching manifest; the full web build regenerates it.
Commit the updated manifest with any changed assets. Do not edit generated
JavaScript under `build/`, as the next build replaces it.

## GitHub workflow

Work on a branch, commit source changes, and push it to your repository:

```bash
git switch -c mechanics/my-change
git add <changed-files>
git commit -m "Describe the gameplay change"
git push -u origin HEAD
```

Review and merge the branch on GitHub when ready. Deployment is a separate
manual step; a push alone does not change any live game. Keep credentials and
local caches out of commits. Upstream source versions and licenses are recorded
in [DEPENDENCIES.md](DEPENDENCIES.md) and the retained license files.

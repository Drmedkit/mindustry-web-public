# mindustry-web-public

An unofficial browser build of open-source Mindustry (mindustry-web-public),
focused on the complete solo Serpulo campaign, with co-op over a small
Cloudflare Worker relay. It compiles the pinned Java game and Arc runtime to
JavaScript with TeaVM instead of rewriting the simulation.

Start with [MECHANICS.md](MECHANICS.md) to change gameplay, resources or
other game rules. To host your own copy on Cloudflare, follow
[DEPLOY.md](DEPLOY.md). Coding agents: see [AGENTS.md](AGENTS.md).

GitHub stores the source; pushing a commit does not deploy anything.

## Current status

Ground Zero opens automatically until it has been captured. Later browser
sessions open on the Serpulo world map, where the complete solo campaign is
available. The production build renders the map, HUD, minimap and objectives,
accepts mouse and keyboard input, and restores campaign progress after a
browser restart.

Play is limited to Serpulo. Erekir and the other planets stay registered, so
content IDs match vanilla Mindustry (saves and multiplayer depend on them),
but `SerpuloOnly` hides them from the planet map, database, editor and custom
games, and maps made for another planet are refused. Erekir's sprites sit on
their own atlas pages, which the browser never downloads.

Assets the game reads at startup (about 22 MB) are fetched before Java
starts. The rest (music and sounds while audio is silent, other languages,
the atlas this GPU does not use) is registered with its size and fetched on
first read. Saves live in IndexedDB; each flush is one transaction, and a
Web Lock keeps a second tab from loading a competing copy.

On first run the game picks a graphics preset: weak devices get 30 FPS, a 1x
pixel ratio and fewer effects; others get a 60 FPS cap.

JVM/Rhino mods, arbitrary filesystem access and native platform integrations
are disabled. Audio currently uses a silent backend. Chromium is the validated
target; Firefox and Safari still need compatibility testing.

## Co-op

Open the pause menu and choose **Host Multiplayer Game**: the game opens a
room and shows a six-character code and a `?room=CODE` link. Friends open the
link, or choose **Join Game** and type the code. Ticking **List this game
publicly** also puts the room on the Join Game screen's public list (a lobby
Durable Object, `domain-proxy/src/lobby.js`; listings expire after two minutes
without a refresh from the host). Works for the campaign (guests
use the host's research and resources; progress stays in the host's save),
custom maps and PvP maps.

The host's browser runs the Mindustry server, exactly like desktop "Host game".
A Cloudflare Durable Object per room (`domain-proxy/src/room.js`) relays binary
WebSocket messages between the host and up to eight players; it runs no game
logic. The game side is `browser-game/src/main/java/io/mindustry/browser/net/`.
Multiplayer games keep running in a hidden or covered tab, so the host can
switch tabs. Rooms only pair players running the same build (`BUILD_ID`, set
by `scripts/deploy.sh`), and room creation and join attempts are rate-limited
per address.

Local test: run `npx wrangler dev --port 8787 --var ALLOW_LOCALHOST:true
--var BUILD_ID:any` in `domain-proxy/`, serve the build on port 8080, open
`http://localhost:8080/?relay=ws://127.0.0.1:8787` to host and
`http://127.0.0.1:8080/?relay=ws://127.0.0.1:8787&room=CODE` to join (a
different origin, so it has its own saves). `npm run check:relay` checks the
relay alone.

## Build

Install a JDK 17 (including `javac`), Node.js 22 or newer and Python 3. The
scripts use `JAVA_HOME`, or `vendor/toolchains/jdk-17.0.19+10` when present.
On Linux, macOS, or Windows with Git Bash, from the repository root:

```bash
npm ci
npm run build:web
```

The build compiles Mindustry and Arc first, regenerates the asset manifest,
then compiles the browser launcher with TeaVM. The first build needs internet
access for Gradle and Maven dependencies. Required sprite atlases and map assets
are committed; local JDK installations, dependency caches, `node_modules`, and
build outputs are excluded from Git. The optional original JDK location at
`vendor/toolchains/jdk-17.0.19+10` is used when present and `JAVA_HOME` is unset.

The static site is emitted to `browser-game/build/browser-dist`. It must be
served over HTTP; opening `index.html` directly will not allow asset fetching.

The last build step (`scripts/stamp-dist.mjs`) versions every script URL by
content hash, links the exact source commit from `NOTICE.txt` and the page,
and fails if any file exceeds Cloudflare Pages' 25 MiB limit.

Production is uploaded to Cloudflare Pages. `domain-proxy` is the Worker that
serves the game's public URL; it streams the Pages deployment, caches only
content-versioned (`?v=`) URLs as immutable, and hosts the co-op relay.
`npm run deploy` builds and deploys both (needs Cloudflare credentials and the
settings described in [DEPLOY.md](DEPLOY.md)).

### Sprite atlases

The atlases are committed. After changing sprites under
`Mindustry/core/assets-raw`, regenerate them with `scripts/pack-atlas.sh`. It
runs Mindustry's packer with `scripts/split-erekir-sprites.mjs`, which moves
hidden-content sprites onto their own pages, then records provenance and
`browser-port/deferred-atlas-pages.json`. The split uses
`browser-port/content-snapshot.json`; refresh that from a local build at
`http://localhost:8080/?dump-content=1` when content changes.

```bash
python3 -m http.server 8080 --directory browser-game/build/browser-dist
```

Then open `http://localhost:8080`.

## Verification

```bash
./scripts/check-port.sh
(cd browser-probe && ./gradlew --no-daemon browserDist)
./scripts/check-browser-probe.sh
npm run check:js
```

Run these checks after building so the TeaVM and JZlib dependencies are cached.
`npm run check:js` runs only the fast Node tests (storage, relay, Worker cache
policy, asset policy, atlas split, PNG decoder). Before deploying, also check
campaign persistence after reloading in Chromium.
`http://localhost:8080/?validate-serpulo=1` loads every Serpulo sector and
reports any visible content or map tile that would draw from a
never-downloaded atlas page.

## Release archives

```bash
./scripts/package-release.sh
```

This creates a ready-to-host playable ZIP and a corresponding-source ZIP in
`~/Downloads`.

## Licensing

Mindustry and this combined modified build are distributed under GPL-3.0.
Every build ships `LICENSE.txt`, `NOTICE.txt` and the Apache-2.0 licenses, and
the page links the source code. The link points at the exact commit on the
GitHub repository named by the `origin` remote (so a fork links its own source),
which must be public (or the link replaced via `MINDUSTRY_SOURCE_URL`) for the
hosted site to meet GPL-3.0 section 6.
Upstream license files are retained in `Mindustry/LICENSE` and `Arc/LICENSE`.
The adapted FreeType browser bridge comes from the pinned Apache-2.0
`gdx-teavm` source under `vendor/src`; its license and notices are retained.
Exact source commits and dependency hashes are recorded in `DEPENDENCIES.md`.

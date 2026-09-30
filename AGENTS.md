# AGENTS.md

Notes for coding agents working in this repository.

## What this is

mindustry-web-public: an unofficial browser build of the open-source game
Mindustry (GPL-3.0). The pinned Java game (`Mindustry/`) and its Arc framework
(`Arc/`) are compiled to JavaScript with TeaVM, not rewritten. Play is limited
to the Serpulo campaign; co-op runs through a Cloudflare Worker relay.

## Layout

| Path | Contents |
| --- | --- |
| `Mindustry/` | Vendored Mindustry source with browser-port changes; `core/assets` holds game assets. |
| `Arc/` | Vendored Arc; `backends/backend-teavm` is the browser backend (graphics, input, files, storage). |
| `browser-game/` | TeaVM entry point (`src/main/java/io/mindustry/browser/`: launcher, Serpulo-only filter, co-op UI and relay client) and the page (`src/main/webapp/`). Output: `build/browser-dist`. |
| `browser-probe/` | Gradle wrapper used by all builds (`browser-probe/gradlew`) and a small TeaVM probe. |
| `browser-port/` | Generated asset manifest, atlas provenance, license notice template. |
| `domain-proxy/` | Cloudflare Worker: serves the game from Pages and hosts the co-op relay (Room and Lobby Durable Objects). |
| `scripts/` | Build, check, packaging and deploy scripts; `scripts/tests/` has Node and Python tests. |
| `vendor/src/` | Pinned third-party sources (gdx-teavm FreeType bridge). `vendor/toolchains/` is a git-ignored optional JDK location. |

Upstream versions and licenses: `DEPENDENCIES.md`, `LICENSE`, `Mindustry/LICENSE`,
`Arc/LICENSE`. Keep all license notices.

## Build and test

Needs JDK 17 (`JAVA_HOME`), Node.js 22+, Python 3 and bash (Git Bash on Windows).

```bash
npm ci
npm run build:web      # full browser build -> browser-game/build/browser-dist
npm run check          # offline checks + unit tests (scripts/check-port.sh)
npm run check:js       # only the fast Node tests
python3 -m http.server 8080 --directory browser-game/build/browser-dist
```

- `npm run build:web` regenerates `browser-port/assets-manifest.json`; commit it
  with asset changes. It also rewrites `Mindustry/core/assets/version.properties`
  with a timestamp; do not commit that change.
- The stamp step needs at least one git commit (or `MINDUSTRY_SOURCE_URL`).
- Do not edit generated files under any `build/` directory.
- On Windows, `git config core.longpaths true` avoids path-length errors.
- `MECHANICS.md` lists where to change gameplay.

## Deploy

To deploy, follow [DEPLOY.md](DEPLOY.md). Never commit Cloudflare credentials;
use `npx wrangler login` or `CLOUDFLARE_API_TOKEN` in the environment.

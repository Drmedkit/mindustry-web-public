# Deploying to Cloudflare

This guide puts your own copy of the game online: the static game files on
**Cloudflare Pages**, and a **Worker** (`domain-proxy/`) in front of it that
serves the game's public URL and runs the co-op relay (Durable Objects). It is
written as numbered steps a person or a coding agent can follow in order. Run
every command from the repository root unless a step says otherwise. On
Windows, use Git Bash.

```text
browser ──► Worker (domain-proxy)  ──► Cloudflare Pages (static game files)
              ├─ /ws/*, /api/rooms/*  ──► Room Durable Object (one per co-op room)
              └─ /api/lobby            ──► Lobby Durable Object (public room list)
```

The page opens its co-op WebSocket on its own origin, so players must open the
game through the Worker URL, not the `*.pages.dev` URL, for co-op to work.

## 1. Prerequisites

- **Node.js 22 or newer** and npm.
- **JDK 17** with `javac` (for example Eclipse Temurin 17). Set `JAVA_HOME` to it,
  or place it at `vendor/toolchains/jdk-17.0.19+10` (git-ignored); the scripts
  use `JAVA_HOME` first.
- **Python 3** (used by the checks).
- **bash** (Linux, macOS, or Git Bash on Windows).
- Internet access for the first build (Gradle and Maven dependencies).
- A **Cloudflare account**. The Workers Free plan is enough: the Durable Objects
  use the SQLite storage backend, which Free supports.

Install the Node tools (this installs `wrangler`, pinned in `package.json`) and
log in:

```bash
npm ci
npx wrangler login          # opens a browser; or set CLOUDFLARE_API_TOKEN instead
npx wrangler whoami         # confirms the account wrangler will use
```

If `whoami` lists several accounts, set `CLOUDFLARE_ACCOUNT_ID=<YOUR_ACCOUNT_ID>`
in your shell, or add `"account_id"` to `domain-proxy/wrangler.jsonc` (step 4).

## 2. Build the browser game

```bash
npm run build:web           # same as: bash scripts/build-web.sh
```

This compiles Mindustry and Arc, regenerates `browser-port/assets-manifest.json`,
compiles the browser launcher to JavaScript with TeaVM, and stamps the output
(`scripts/stamp-dist.mjs`). The result is the static site in
`browser-game/build/browser-dist`. The first build takes several minutes.

The stamp step needs at least one git commit: it links the exact source commit
from the page and `NOTICE.txt` (GPL-3.0). It uses the GitHub repository of your
`origin` remote; set `MINDUSTRY_SOURCE_URL` to override the link.

Optional checks and a local look:

```bash
npm run check               # offline checks and unit tests (bash scripts/check-port.sh)
python3 -m http.server 8080 --directory browser-game/build/browser-dist
# open http://localhost:8080
```

## 3. Create the Pages project and upload the game

Pick a project name (the default the deploy script uses is
`mindustry-browser-port`) and create it once:

```bash
npx wrangler pages project create <PAGES_PROJECT> --production-branch main
npx wrangler pages deploy browser-game/build/browser-dist --project-name <PAGES_PROJECT> --branch main
```

The output prints the project's host name, normally `<PAGES_PROJECT>.pages.dev`
(Cloudflare adds a suffix if that name is taken elsewhere). Note it: the Worker
needs it in the next step. `--branch main` makes this the production
deployment; another branch name creates a preview deployment instead.

## 4. Configure the Worker (`domain-proxy/wrangler.jsonc`)

Edit these values; everything else works as-is on a fresh account.

| Key | Required? | Set it to |
| --- | --- | --- |
| `vars.PAGES_ORIGIN` | **Yes** | Your Pages host name from step 3, e.g. `"my-mindustry.pages.dev"` (no `https://`). The placeholder is `REPLACE_WITH_YOUR_PAGES_PROJECT.pages.dev`; the deploy script refuses to run while it is there. |
| `name` | Optional | The Worker's name, also the start of its URL `https://<name>.<your-subdomain>.workers.dev`. Default `mindustry-browser-domain`. |
| `account_id` | Only with several accounts | `"<YOUR_CLOUDFLARE_ACCOUNT_ID>"` (top level), or use `CLOUDFLARE_ACCOUNT_ID`. |
| `routes` | Optional | To use your own domain, uncomment the example and set `<YOUR_GAME_HOSTNAME>` (e.g. `play.example.com`). The domain must be a zone in the same Cloudflare account; `custom_domain: true` creates the DNS record and certificate. |
| `vars.ALLOWED_ORIGINS` | Optional | Extra page origins that may open co-op rooms, comma-separated. The Worker's own origin (workers.dev URL or custom domain) is always allowed, so this can stay `""`. |
| `vars.SITE_URL`, `SHARE_*` | Optional | Public URL, title, description, site name and image URL for link previews (Open Graph/Twitter tags). Leave `SITE_URL` empty to skip them. |
| `ratelimits[].namespace_id` | No | Any number unique within your account; `1101`/`1102` are fine unless you already use them. |

Leave these alone:

- `durable_objects` and `migrations`: the Room and Lobby classes and their
  migrations (`v1` creates `Room`, `v2` creates `Lobby`, both SQLite-backed).
  Wrangler applies them automatically on the first deploy. Never edit or delete
  an applied migration; add a new tag (for example `v3`) for later changes.
- `vars.BUILD_ID`: the deploy script sets it for each deploy (step 6).
- `vars.ALLOW_LOCALHOST`: keep `"false"` in production.

Check the configuration without deploying:

```bash
(cd domain-proxy && npx wrangler deploy --dry-run)
```

It should list `env.ROOMS (Room)`, `env.LOBBY (Lobby)`, two rate limits and the
variables.

## 5. Commit

`scripts/deploy.sh` refuses to run with uncommitted changes to tracked files,
because the page links the exact source commit:

```bash
git add domain-proxy/wrangler.jsonc
git commit -m "Configure Cloudflare deployment"
```

## 6. Deploy

```bash
PAGES_PROJECT=<PAGES_PROJECT> npm run deploy     # same as: bash scripts/deploy.sh
```

The script:

1. rebuilds the game (`scripts/build-web.sh`);
2. reads the build ID from `browser-game/build/browser-dist/index.html`
   (`globalThis.mindustryBuild = '<id>'`);
3. uploads the game: `npx wrangler pages deploy browser-game/build/browser-dist --project-name "$PAGES_PROJECT" --branch main`;
4. deploys the Worker with that build ID:
   `(cd domain-proxy && npx wrangler deploy --var "BUILD_ID:<id>")`.

To deploy the Worker by hand instead, run step 4's command with the ID from
step 2. Wrangler prints the Worker URL. On an account that never used Workers,
it first asks you to choose a `workers.dev` subdomain (or do it in the dashboard
under Workers & Pages).

Open `https://<name>.<your-subdomain>.workers.dev` (or your custom domain). The
first visit downloads about 22 MB; later visits are cached.

## 7. How co-op connects

- **Relay URL.** The page connects to `wss://<page host>/ws/...`, the same origin
  it was loaded from (`relayUrl()` in `MindustryBrowserLauncher.java`). The Worker
  handles `/ws/host`, `/ws/rehost/<code>`, `/ws/join/<code>`, `/api/rooms/<code>`
  and `/api/lobby` (`domain-proxy/src/rooms.js`); everything else is proxied to
  `PAGES_ORIGIN`. A `?relay=wss://...` query parameter overrides the relay (for
  testing).
- **Origin check.** WebSocket upgrades must come from the Worker's own origin, an
  origin in `ALLOWED_ORIGINS`, or `localhost`/`127.0.0.1` when `ALLOW_LOCALHOST`
  is `"true"`. Other origins get HTTP 403.
- **Version gate (`BUILD_ID`).** Each client sends its build ID as `?v=<id>`.
  If the Worker's `BUILD_ID` is not `"any"` and differs, the connection gets
  HTTP 409 "Game version mismatch; reload the page". All players in a room run
  the same bundle, since content IDs and packet layouts come from it. Deploy the
  game and the Worker together (the deploy script does).
- **Rooms.** Each room is one `Room` Durable Object that relays binary messages
  between the host's browser (which runs the Mindustry server) and up to eight
  players. Hosts can list a room publicly; the `Lobby` Durable Object keeps
  those listings for two minutes after the host's last update.

### Local test with `wrangler dev`

```bash
# terminal 1: the relay (no Cloudflare login needed)
cd domain-proxy
npx wrangler dev --port 8787 --var ALLOW_LOCALHOST:true --var BUILD_ID:any

# terminal 2: automated relay check (host <-> player round trip)
npm run check:relay                 # node scripts/check-relay.mjs ws://127.0.0.1:8787

# terminal 3: the game
python3 -m http.server 8080 --directory browser-game/build/browser-dist
```

Host at `http://localhost:8080/?relay=ws://127.0.0.1:8787` (pause menu, **Host
Multiplayer Game**), then join from
`http://127.0.0.1:8080/?relay=ws://127.0.0.1:8787&room=<CODE>`. That is a
different origin, so it has its own saves and does not wait for the host's tab.

## 8. Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Worker page says "Set PAGES_ORIGIN in domain-proxy/wrangler.jsonc…" (HTTP 500) | `PAGES_ORIGIN` still has the placeholder. Do step 4, commit, redeploy. |
| Worker URL shows a Cloudflare 404 or error page from `*.pages.dev` | `PAGES_ORIGIN` names the wrong host, or the Pages upload went to a preview branch. Compare with the host printed by `wrangler pages deploy`; deploy with `--branch main` to a project whose production branch is `main`. |
| "Commit your changes first" | `scripts/deploy.sh` needs a clean tree of tracked files. Commit or stash. |
| Co-op: "Game version mismatch; reload the page" | The Worker's `BUILD_ID` differs from the loaded bundle. Reload the page; if it persists, redeploy with `npm run deploy` (it deploys both halves with the same ID). |
| Co-op: "Forbidden origin" (HTTP 403) | The page is not on the Worker's origin. Open the game via the Worker URL, or add the page's origin to `ALLOWED_ORIGINS`. |
| Co-op: "Too many attempts; try again in a minute" (HTTP 429) | Rate limits in `ratelimits` (10 rooms and 30 joins per minute per address). Wait, or raise `limit`. |
| Deploy error about Durable Object migrations | An applied migration was edited or removed. Restore `v1` and `v2` exactly and add changes as a new tag. |
| Deploy error about the rate limit binding | Choose other `namespace_id` numbers, or remove the `ratelimits` block (the Worker skips limits when the bindings are missing). |
| `wrangler` uses the wrong account | Set `CLOUDFLARE_ACCOUNT_ID` or `account_id`; check with `npx wrangler whoami`. |
| Build: `javac` not found, or wrong Java version | Point `JAVA_HOME` at a JDK 17 (not a JRE). |
| Build: Gradle dependency verification failure | Dependencies are checked against `browser-probe/gradle/verification-metadata.xml`. After confirming the new artifacts are genuine, rerun the failing Gradle command with `--dependency-verification=lenient`. |
| Build: `git rev-parse HEAD` fails in `stamp-dist.mjs` | The repository has no commit yet. Commit first, or set `MINDUSTRY_SOURCE_URL`. |
| Build: "Cloudflare Pages rejects files over 25 MiB" | A file in the dist grew past the Pages per-file limit (usually `js/mindustry.js`). The build prints its size; reduce what TeaVM compiles in. |
| Page says "Mindustry is already open in another tab" | Only one tab per origin runs the campaign (a Web Lock). Close the other tab; `?room=` links play from memory instead of waiting. |
| Game does not load when `index.html` is opened from disk | It must be served over HTTP (step 2 or a deployment) so it can fetch its assets. |

#!/usr/bin/env bash
# Builds and deploys the game (Cloudflare Pages) and the domain Worker with the
# co-op relay. The Worker only pairs players running this build (BUILD_ID).
# Needs Cloudflare credentials (CLOUDFLARE_API_TOKEN, or `npx wrangler login`).
# See DEPLOY.md. PAGES_PROJECT names the Pages project (default: mindustry-browser-port).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/env.sh
source "$root/scripts/lib/env.sh"
cd "$root"

project="${PAGES_PROJECT:-mindustry-browser-port}"

if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
    printf 'Commit your changes first: the page links the exact source commit.\n' >&2
    exit 1
fi
if grep -q 'REPLACE_WITH_YOUR_PAGES_PROJECT' domain-proxy/wrangler.jsonc; then
    printf 'Set PAGES_ORIGIN in domain-proxy/wrangler.jsonc first (see DEPLOY.md).\n' >&2
    exit 1
fi

./scripts/build-web.sh
build="$(sed -n "s/.*globalThis.mindustryBuild = '\([^']*\)'.*/\1/p" browser-game/build/browser-dist/index.html)"
[[ -n "$build" ]] || { printf 'Could not read the build ID from index.html.\n' >&2; exit 1; }

npx wrangler pages deploy browser-game/build/browser-dist --project-name "$project" --branch main
(cd domain-proxy && npx wrangler deploy --var "BUILD_ID:$build")
printf 'Deployed build %s.\n' "$build"

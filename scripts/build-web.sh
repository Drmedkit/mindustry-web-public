#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/env.sh
source "$root/scripts/lib/env.sh"

# Compile mechanics before TeaVM so edits in Mindustry/ and Arc/ take effect.
(cd "$root/Mindustry" && "$root/browser-probe/gradlew" --no-daemon :core:jar \
    :Arc:extensions:flabel:jar :Arc:extensions:g3d:jar :Arc:extensions:fx:jar \
    :Arc:extensions:arcnet:jar :Arc:extensions:packer:jar)
node "$root/scripts/generate-asset-manifest.mjs"
(cd "$root/browser-game" && "$root/browser-probe/gradlew" --no-daemon \
    -Pproduction=true generateJavaScript browserDist)
node "$root/scripts/stamp-dist.mjs"

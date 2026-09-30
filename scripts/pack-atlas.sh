#!/usr/bin/env bash
# Regenerates both sprite atlases from Mindustry/core/assets-raw with Erekir's
# sprites on their own pages, then records provenance and the deferred pages.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/env.sh
source "$root/scripts/lib/env.sh"

# Dependency verification does not cover the atlas tool's own build classpath.
(cd "$root/Mindustry" && "$root/browser-probe/gradlew" --no-daemon --dependency-verification=lenient \
    :tools:pack -PsplitSprites="$(native_path "$root/scripts/split-erekir-sprites.mjs")")
node "$root/scripts/record-atlas.mjs"

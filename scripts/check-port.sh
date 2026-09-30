#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/env.sh
source "$root/scripts/lib/env.sh"
manifest_check="$(mktemp "${TMPDIR:-/tmp}/mindustry-assets-manifest.XXXXXX")"

cleanup() {
    rm -f "$manifest_check"
}
trap cleanup EXIT

cd "$root"

./scripts/check-arc-core.sh
./scripts/check-block-build-factories.sh
"$python" -m unittest -v \
    scripts/tests/test_browser_source_contracts.py \
    scripts/tests/test_import_generated_atlases.py
node --test scripts/tests/vfs-storage.test.mjs scripts/tests/domain-proxy.test.mjs scripts/tests/asset-policy.test.mjs scripts/tests/atlas-split.test.mjs scripts/tests/relay.test.mjs scripts/tests/png-decoder.test.mjs

node scripts/generate-asset-manifest.mjs --output "$manifest_check"
node scripts/compare-manifests.mjs browser-port/assets-manifest.json "$manifest_check"

printf 'PASS: all offline browser-port checks completed successfully.\n'

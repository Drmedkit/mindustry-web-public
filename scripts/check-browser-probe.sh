#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/env.sh
source "$root/scripts/lib/env.sh"
page="$root/browser-probe/build/browser-dist/index.html"
bundle="$root/browser-probe/build/browser-dist/js/arc-probe.js"
expected="TeaVM is executing Arc core. First Serpulo sector: Ground Zero."
lifecycle_expected="Lifecycle frames: 2. Input: true."

if [[ ! -f "$page" || ! -f "$bundle" ]]; then
    printf 'Browser probe is not built. Run: (cd browser-probe && ./gradlew --no-daemon browserDist)\n' >&2
    exit 1
fi

if [[ -n "${BROWSER:-}" ]]; then
    browser="$BROWSER"
elif command -v chromium >/dev/null 2>&1; then
    browser="$(command -v chromium)"
elif command -v chromium-browser >/dev/null 2>&1; then
    browser="$(command -v chromium-browser)"
elif command -v google-chrome >/dev/null 2>&1; then
    browser="$(command -v google-chrome)"
elif ((is_windows)) && [[ -x "/c/Program Files/Google/Chrome/Application/chrome.exe" ]]; then
    browser="/c/Program Files/Google/Chrome/Application/chrome.exe"
elif ((is_windows)) && [[ -x "/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" ]]; then
    browser="/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
else
    printf 'Chromium was not found; set BROWSER to a Chromium-compatible executable.\n' >&2
    exit 1
fi

set +e
output="$("$browser" \
    --headless \
    --no-sandbox \
    --use-gl=angle \
    --use-angle=swiftshader \
    --enable-unsafe-swiftshader \
    --allow-file-access-from-files \
    --virtual-time-budget=5000 \
    --dump-dom \
    "file:///$(native_path "$page" | sed 's#^/##')" 2>&1)"
status=$?
set -e

if ((status != 0)); then
    printf '%s\n' "$output" >&2
    printf 'FAIL: Chromium exited with status %s.\n' "$status" >&2
    exit "$status"
fi

if [[ "$output" != *"$expected"* || "$output" != *"$lifecycle_expected"* ]]; then
    printf '%s\n' "$output" >&2
    printf 'FAIL: TeaVM probe did not report the expected Arc/VFS result.\n' >&2
    exit 1
fi

printf 'PASS: TeaVM JavaScript executed Arc core and the browser VFS in Chromium.\n'

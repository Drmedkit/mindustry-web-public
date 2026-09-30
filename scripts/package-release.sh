#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/env.sh
source "$root/scripts/lib/env.sh"
dist="$root/browser-game/build/browser-dist"
output="${1:-$HOME/Downloads}"
stage="$(mktemp -d "${TMPDIR:-/tmp}/mindustry-browser-release.XXXXXX")"

cleanup() {
    rm -rf "$stage"
}
trap cleanup EXIT

if [[ ! -f "$dist/index.html" || ! -f "$dist/js/mindustry.js" ]]; then
    printf 'Production distribution is missing. Build browser-game first.\n' >&2
    exit 1
fi

mkdir -p "$output" "$stage/playable"
cp -a "$dist/." "$stage/playable/"
cp "$root/browser-game/PLAYABLE_README.txt" "$stage/playable/README.txt"

playable_zip="$output/mindustry-browser-playable.zip"
source_zip="$output/mindustry-browser-source.zip"
rm -f "$playable_zip" "$source_zip"

# Python's zipfile is available everywhere the build runs, unlike the zip CLI.
"$python" -c 'import shutil, sys; shutil.make_archive(sys.argv[1], "zip", sys.argv[2])' \
    "$(native_path "${playable_zip%.zip}")" "$(native_path "$stage/playable")"

# The corresponding source is exactly the committed tree of the built revision.
if [[ -n "$(git -C "$root" status --porcelain)" ]]; then
    printf 'Warning: uncommitted changes are not included in the source archive.\n' >&2
fi
git -C "$root" archive --format=zip -9 -o "$(native_path "$source_zip")" HEAD

printf 'Created %s\n' "$playable_zip"
printf 'Created %s\n' "$source_zip"

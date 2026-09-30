#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/env.sh
source "$root/scripts/lib/env.sh"
arc_root="$root/Arc"
classes_dir="$(mktemp -d "${TMPDIR:-/tmp}/mindustry-arc-core-classes.XXXXXX")"
unsafe_jar="$arc_root/arc-core/unsafe/unsafe.jar"

cleanup() {
    rm -rf "$classes_dir"
}
trap cleanup EXIT

# Writes one quoted path per line, the javac @argfile format (paths may contain spaces).
native_all() {
    local path
    for path in "$@"; do printf '"%s"\n' "$(native_path "$path")"; done
}

read_lines arc_sources < <(find "$arc_root/arc-core/src" -type f -name '*.java' | sort)
read_lines core_checks < <(find "$arc_root/arc-core/test" -maxdepth 1 -type f -name '*Check.java' | sort)
read_lines teavm_sources < <(find "$arc_root/backends/backend-teavm/src" -type f -name '*.java' | sort)
read_lines teavm_tests < <(find "$arc_root/backends/backend-teavm/test" -type f -name '*Test.java' | sort)

read_lines teavm_jars < <(
    find "$HOME/.gradle/caches/modules-2/files-2.1/org.teavm" \
        -type f -path '*/0.15.0/*.jar' ! -name '*-sources.jar' 2>/dev/null | sort
)
if ((${#teavm_jars[@]} == 0)); then
    printf 'TeaVM 0.15 jars are missing. Build browser-game once before running this gate.\n' >&2
    exit 1
fi
read_lines jzlib_jars < <(
    find "$HOME/.gradle/caches/modules-2/files-2.1/com.jcraft/jzlib" \
        -type f -name '*.jar' ! -name '*-sources.jar' 2>/dev/null | sort
)
if ((${#jzlib_jars[@]} == 0)); then
    printf 'JZlib jars are missing. Build browser-game once before running this gate.\n' >&2
    exit 1
fi

# javac reads long source lists from an @argfile; this also avoids Windows command-line limits.
sources_file="$classes_dir/sources.txt"

printf 'Compiling Arc core with Java 8 bytecode compatibility...\n'
native_all "${arc_sources[@]}" > "$sources_file"
javac --release 8 -encoding UTF-8 -nowarn \
    -cp "$(classpath "$unsafe_jar")" \
    -d "$(native_path "$classes_dir")" \
    "@$(native_path "$sources_file")"

native_all "$root/scripts/src/ArcCoreSmoke.java" "${core_checks[@]}" > "$sources_file"
javac --release 8 -encoding UTF-8 \
    -cp "$(classpath "$classes_dir" "$unsafe_jar")" \
    -d "$(native_path "$classes_dir")" \
    "@$(native_path "$sources_file")"

native_all "${teavm_sources[@]}" "${teavm_tests[@]}" \
    "$root/browser-probe/src/main/java/io/mindustry/browser/probe/ArcProbe.java" > "$sources_file"
javac --release 17 -encoding UTF-8 \
    -cp "$(classpath "$classes_dir" "$unsafe_jar" "${teavm_jars[@]}" "${jzlib_jars[@]}")" \
    -d "$(native_path "$classes_dir")" \
    "@$(native_path "$sources_file")"

runtime_classpath="$(classpath "$classes_dir" "$unsafe_jar" "${jzlib_jars[@]}")"
java -cp "$runtime_classpath" ArcCoreSmoke
for check in "${core_checks[@]}"; do
    java -cp "$runtime_classpath" "$(basename "$check" .java)"
done
for test in "${teavm_tests[@]}"; do
    test_class="${test#"$arc_root/backends/backend-teavm/test/"}"
    test_class="${test_class%.java}"
    test_class="${test_class//\//.}"
    java -cp "$runtime_classpath" "$test_class"
done

class_count="$(find "$classes_dir" -type f -name '*.class' | wc -l | tr -d ' ')"
printf 'PASS: %s Arc sources produced %s classes and passed all compatibility checks.\n' \
    "${#arc_sources[@]}" "$class_count"

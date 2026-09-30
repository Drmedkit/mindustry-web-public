# Shared toolchain setup for the build and check scripts.
# Works with bash 3.2+ on Linux and macOS, and with Git Bash on Windows.
# Source it after setting $root to the repository root.

# Use an explicitly selected JDK, the optional local toolchain, or Java on PATH.
if [[ -z "${JAVA_HOME:-}" && -d "$root/vendor/toolchains/jdk-17.0.19+10" ]]; then
    export JAVA_HOME="$root/vendor/toolchains/jdk-17.0.19+10"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) is_windows=1; classpath_separator=';' ;;
    *) is_windows=0; classpath_separator=':' ;;
esac

# Converts a POSIX path to the form native tools (javac, java) expect.
native_path() {
    if ((is_windows)); then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# Joins paths into one classpath argument for the native JVM.
classpath() {
    local joined="" entry
    for entry in "$@"; do
        [[ -z "$entry" ]] && continue
        joined+="${joined:+$classpath_separator}$(native_path "$entry")"
    done
    printf '%s' "$joined"
}

# Reads newline-separated command output into the named array (bash 3.2 has no mapfile).
read_lines() {
    local __name="$1" __line
    eval "$__name=()"
    while IFS= read -r __line; do
        eval "$__name+=(\"\$__line\")"
    done
}

if command -v python3 >/dev/null 2>&1 && python3 -c '' >/dev/null 2>&1; then
    python=python3
else
    python=python
fi

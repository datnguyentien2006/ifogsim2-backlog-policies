#!/usr/bin/env bash
# Build script (Linux/macOS mirror of build.ps1): compiles the iFogSim2 vendor sources (once,
# cached) and then the backfill simulator into sim/build.
#
# Requirements: JDK 21 (upstream iFogSim2 needs SequencedCollection, e.g. List.getLast()).
# Set JAVA_HOME or make javac/java point at a JDK 21 binary.
set -euo pipefail

scriptdir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(dirname "$scriptdir")"
build="$repo/sim/build"
clVendor="$build/vendor"
clSim="$build/classes"

if [ -n "${JAVA_HOME:-}" ]; then
    PATH="$JAVA_HOME/bin:$PATH"
fi
command -v javac >/dev/null 2>&1 || { echo "[build] javac not found — install JDK 21 and set JAVA_HOME" >&2; exit 1; }
java_version=$(javac -version | awk '{print $2}')
case "$java_version" in
    21*|22*|23*|24*) ;;
    *) echo "[build] need JDK 21+ (found javac $java_version)" >&2; exit 1;;
esac

# jar separator: ':' on Unix (Windows Git-Bash handled by build.ps1 instead)
jars=$(find "$repo/iFogSim2-vendor/jars" -name '*.jar' -printf '%p:' | sed 's/:$//')

# ---- vendor (cached: skipped when already compiled) ----
if [ ! -d "$clVendor/org" ]; then
    echo "[build] compiling iFogSim2-vendor ..."
    mkdir -p "$clVendor"
    opts="$build/vendor_opts.txt"
    {
        printf -- '-encoding\nUTF-8\n-nowarn\n-cp\n%s\n-d\n%s\n' "$jars" "$clVendor"
        find "$repo/iFogSim2-vendor/src" -name '*.java' -printf '%p\n'
    } > "$opts"
    javac "@$opts"
fi

# ---- simulator ----
mkdir -p "$clSim"
opts="$build/sim_opts.txt"
{
    printf -- '-encoding\nUTF-8\n-nowarn\n-cp\n%s\n-d\n%s\n' "$clVendor:$jars" "$clSim"
    find "$repo/sim/src" -name '*.java' -printf '%p\n'
} > "$opts"
javac "@$opts"
echo "[build] OK -> $clSim"

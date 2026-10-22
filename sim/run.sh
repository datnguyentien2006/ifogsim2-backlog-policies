#!/usr/bin/env bash
# Run driver (Linux/macOS mirror of run.ps1).
#   ./run.sh run  -P fifo  -S base     -s 0
#   ./run.sh grid                    -s 3        # full batch -> results/runs/<scenario>/
#   ./run.sh grid -P newfirst -S N10 -s 2        # filtered batch
#   ./run.sh test                                 # framework invariants (SelfTest)
# Compiles first if needed, then runs each experiment in its own JVM.
set -euo pipefail

scriptdir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(dirname "$scriptdir")"

# --- crude arg parsing mirroring run.ps1 ---
mode=""; policy=""; scenario=""; seed=0; seeds=3; outdir="results/runs"
while [ $# -gt 0 ]; do
    case "$1" in
        run|grid|test) mode="$1";;
        -Policy|-P)    policy="$2"; shift;;
        -Scenario|-S)  scenario="$2"; shift;;
        -Seed)         seed="$2"; shift;;
        -Seeds|-s)     seeds="$2"; shift;;
        -OutDir)       outdir="$2"; shift;;
        *) echo "usage: run.sh {run|grid|test} [-Policy|-P k] [-Scenario|-S name] [-Seed n] [-Seeds n] [-OutDir d]" >&2; exit 1;;
    esac
    shift
done
[ -n "$mode" ] || { echo "mode required: run|grid|test" >&2; exit 1; }

bash "$scriptdir/build.sh"

if [ -n "${JAVA_HOME:-}" ]; then
    PATH="$JAVA_HOME/bin:$PATH"
fi
cp="$repo/sim/build/classes:$repo/sim/build/vendor:$(find "$repo/iFogSim2-vendor/jars" -name '*.jar' -printf '%p:')"
cp="${cp%:}"
# Heap headroom (see run.ps1): override with BACKFILL_JVM, e.g. BACKFILL_JVM="-Xmx4g"
jvm=(${BACKFILL_JVM:-"-Xmx2g"})

cd "$repo"
mkdir -p "$outdir"
case "$mode" in
    run)
        scenario="${scenario:-base}"
        java "${jvm[@]}" -cp "$cp" backfill.run.RunMain "${policy:-fifo}" "$scenario" "$seed" "$outdir"
        ;;
    test)
        java "${jvm[@]}" -cp "$cp" backfill.run.SelfTest
        ;;
    grid)
        args=("$outdir" "$seeds")
        [ -n "$policy" ]   && args+=("$policy")      # ExperimentRunner arg[2] = onlyPolicy
        [ -n "$scenario" ] && args+=("$scenario")    # arg[3] = onlyScenario
        java "${jvm[@]}" -cp "$cp" backfill.run.ExperimentRunner "${args[@]}"
        ;;
esac

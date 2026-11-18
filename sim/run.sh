#!/usr/bin/env bash
# Run driver (Linux/macOS mirror of run.ps1).
#   ./run.sh run  -P fifo  -S base     -s 0
#   ./run.sh grid                    -s 3        # full batch -> results/runs/<scenario>/
#   ./run.sh grid -P newfirst -S N10 -s 2        # filtered batch
#   ./run.sh grid -FirstSeed 5 -s 25 -Jobs 4 -NoTicks -OutDir results/replications
#                                                 # extra replications, 4 JVMs in parallel
#   ./run.sh test                                 # framework invariants (SelfTest)
# Compiles first if needed, then runs each experiment in its own JVM.
set -euo pipefail

scriptdir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(dirname "$scriptdir")"

# --- crude arg parsing mirroring run.ps1 ---
mode=""; policy=""; scenario=""; seed=0; seeds=3; outdir="results/runs"
firstseed=0; jobs=1; ticks=true
while [ $# -gt 0 ]; do
    case "$1" in
        run|grid|test) mode="$1";;
        -Policy|-P)    policy="$2"; shift;;
        -Scenario|-S)  scenario="$2"; shift;;
        -Seed)         seed="$2"; shift;;
        -Seeds|-s)     seeds="$2"; shift;;
        -FirstSeed)    firstseed="$2"; shift;;
        -Jobs|-j)      jobs="$2"; shift;;
        -NoTicks)      ticks=false;;
        -OutDir)       outdir="$2"; shift;;
        *) echo "usage: run.sh {run|grid|test} [-Policy|-P k] [-Scenario|-S name] [-Seed n] [-Seeds n] [-FirstSeed n] [-Jobs n] [-NoTicks] [-OutDir d]" >&2; exit 1;;
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
        # Same layout as the grid: results/runs/<scenario>/run_<scenario>_s<seed>_<policy>.csv
        java "${jvm[@]}" -cp "$cp" backfill.run.RunMain "${policy:-fifo}" "$scenario" "$seed" "$outdir/$scenario"
        ;;
    test)
        java "${jvm[@]}" -cp "$cp" backfill.run.SelfTest
        ;;
    grid)
        args=("$outdir" "$seeds")
        # ExperimentRunner arg[2] = onlyPolicy, arg[3] = onlyScenario ("all" = no filter)
        if [ -n "$policy" ] || [ -n "$scenario" ]; then args+=("${policy:-all}"); fi
        [ -n "$scenario" ] && args+=("$scenario")
        java "${jvm[@]}" -Dbackfill.firstSeed="$firstseed" -Dbackfill.jobs="$jobs" -Dbackfill.ticks="$ticks" \
            -cp "$cp" backfill.run.ExperimentRunner "${args[@]}"
        ;;
esac

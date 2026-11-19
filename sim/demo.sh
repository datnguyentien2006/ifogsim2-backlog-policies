#!/usr/bin/env bash
# Demo of the measurement results (Linux/macOS mirror of demo.ps1).
#   ./sim/demo.sh                          # analyse the committed results, print the verdicts
#   ./sim/demo.sh quick [scenario] [seeds] [jobs]   # live: run 4 policies x seeds of one scenario, analyse
#   ./sim/demo.sh web                      # open the browser demo (web/index.html)
# Requirements: JDK 21+ (see build.sh) and Python 3 with analysis/requirements.txt installed.
set -euo pipefail

scriptdir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(dirname "$scriptdir")"
mode="${1:-results}"
scenario="${2:-base}"
seeds="${3:-10}"
jobs="${4:-2}"
python="$(command -v python3 || command -v python || true)"

case "$mode" in
    web)
        page="$repo/web/index.html"
        (command -v xdg-open >/dev/null && xdg-open "$page") || (command -v open >/dev/null && open "$page") \
            || echo "open $page in a browser"
        exit 0;;
    results|quick) ;;
    *) echo "usage: demo.sh [results | quick [scenario] [seeds] [jobs] | web]" >&2; exit 1;;
esac
[ -n "$python" ] || { echo "Python 3 not found: install it and run 'pip install -r analysis/requirements.txt'" >&2; exit 1; }

out="${TMPDIR:-/tmp}/backfill_demo_$mode"
rm -rf "$out"
if [ "$mode" = quick ]; then
    echo "[demo] $scenario : 4 policies x $seeds seeds, $jobs runs in parallel (same seeds => same inputs for every policy)"
    bash "$scriptdir/run.sh" grid -S "$scenario" -s "$seeds" -Jobs "$jobs" -NoTicks -OutDir "$out/runs"
    "$python" "$repo/analysis/stats.py" --runs "$out/runs" --out "$out"
else
    echo "[demo] statistical analysis of the committed results (results/runs + results/replications)"
    "$python" "$repo/analysis/stats.py" --out "$out"
fi
echo
echo "[demo] tables : $out/analysis/STATS.md"
echo "[demo] figures: $out/analysis/figures/stats_*.png"
echo "[demo] report : $repo/docs/REPORT_VI.md (method: docs/STATISTICS.md)"

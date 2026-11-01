#!/usr/bin/env python3
"""Aggregate results/runs into per-policy comparison tables and figures.

Inputs : results/runs/<scenario>/run_<scenario>_s<seed>_<policy>_run.csv   (one row each)
         results/runs/<scenario>/run_<scenario>_s<seed>_<policy>_tick.csv (time series)
Outputs: analysis/figures/*.png  +  analysis/tables.md
"""
import csv
from collections import defaultdict
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent.parent
RUNS = ROOT / "results" / "runs"
FIGS = ROOT / "analysis" / "figures"

POLICIES = ["fifo", "rr", "ratelimit", "newfirst"]
NICE = {"fifo": "FIFO", "rr": "Round-robin", "ratelimit": "Rate-limit", "newfirst": "New-first"}
COLOR = {"fifo": "#e67e22", "rr": "#8e44ad", "ratelimit": "#27ae60", "newfirst": "#2980b9"}

RUN_COLS = ["policy", "scenario", "seed", "N", "rho", "C", "imbalance", "outageMode",
            "dataStart", "dataEnd", "genNew", "genBack", "delivNew", "delivBack",
            "leftNew", "leftBack", "newDelayMean", "newDelayP50", "newDelayP95", "newDelayP99",
            "backDelayMean", "backDelayP50", "backDelayP95", "backDelayP99",
            "backlogArea", "backlogPeak", "outages", "restoresWithBacklog",
            "drains", "drainMean", "drainMax", "jainBackfill"]
NUMERIC = set(RUN_COLS) - {"policy", "scenario", "imbalance", "outageMode"}


def load_summaries():
    per_seed = defaultdict(list)  # (scenario, policy) -> [row]
    for run_csv in sorted(RUNS.glob("*/*_run.csv")):
        # name scheme: run_<scenario>_s<seed>_<policy>_run.csv; scenario may contain '_'
        policy = run_csv.stem.removesuffix("_run").rsplit("_", 1)[1]
        scen_dir = run_csv.parent.name
        with open(run_csv, newline="") as fh:
            reader = csv.DictReader(fh)
            try:
                row = next(reader)
            except StopIteration:
                continue
        row["policy"] = policy
        row["scenario"] = scen_dir
        for k in NUMERIC:
            if row.get(k) not in (None, ""):
                try:
                    row[k] = float(row[k])
                except ValueError:
                    pass
        per_seed[(scen_dir, policy)].append(row)
    # average across seeds
    agg = {}
    for (scen, pol), rows in per_seed.items():
        m = {"scenario": scen, "policy": pol, "seeds": len(rows)}
        for k in NUMERIC:
            vals = [r[k] for r in rows if isinstance(r.get(k), (int, float))]
            m[k] = sum(vals) / len(vals) if vals else 0.0
        agg[(scen, pol)] = m
    return agg


def scenario_order(agg):
    order = []
    scen_first = ["base", "N4", "N10", "N16", "rho0.4", "rho0.9", "rho1.1",
                  "imbalance_zipf", "imbalance_dominant", "storm"]
    scen_seen = {sc for (sc, _) in agg}
    for s in scen_first:
        if s in scen_seen:
            order.append(s)
    for s in sorted(scen_seen):
        if s not in order:
            order.append(s)
    return order


def grouped_bars(agg, order, col, ylabel, title, fname, log=False):
    nS, nP = len(order), len(POLICIES)
    fig, ax = plt.subplots(figsize=(1.0 + 1.05 * nS, 4.2))
    w = 0.2
    for pi, pol in enumerate(POLICIES):
        xs = [s + (pi - (nP - 1) / 2) * w for s in range(nS)]
        ys = [agg.get((sc, pol), {}).get(col, 0.0) for sc in order]
        bars = ax.bar(xs, ys, w, color=COLOR[pol], label=NICE[pol])
        for b, y in zip(bars, ys):
            if y > 0:
                ax.text(b.get_x() + b.get_width() / 2, y * 1.02 + 0.3,
                        f"{y:.1f}" if y < 100 else f"{y:.0f}", fontsize=5.5,
                        ha="center", va="bottom", rotation=90)
    ax.set_xticks(range(nS))
    ax.set_xticklabels(order, fontsize=7.5, rotation=20, ha="right")
    ax.set_ylabel(ylabel)
    ax.set_title(title, fontsize=10)
    if log:
        ax.set_yscale("log")
    ax.legend(fontsize=7.5, ncol=4, loc="upper left")
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    fig.savefig(FIGS / fname, dpi=150)
    plt.close(fig)


def tick_figure(scen, seed, fname, title):
    paths = {}
    for pol in POLICIES:
        p = RUNS / scen / f"run_{scen}_s{seed}_{pol}_tick.csv"
        if p.exists():
            paths[pol] = p
    if len(paths) < 4:
        return
    fig, axes = plt.subplots(1, 2, figsize=(11, 3.6))
    for pol, p in paths.items():
        t, back, gnew, gback = [], [], [], []
        with open(p, newline="") as fh:
            for row in csv.DictReader(fh):
                t.append(float(row["t"]))
                back.append(int(row["backlogBackTot"]))
                gnew.append(int(row["grantedNew"]))
                gback.append(int(row["grantedBack"]))
        axes[0].plot(t, back, label=NICE[pol], color=COLOR[pol], lw=1.1)
        axes[1].plot(t, [a + b for a, b in zip(gnew, gback)], label=NICE[pol],
                     color=COLOR[pol], lw=0.9, alpha=0.85)
    axes[0].set_ylabel("accumulated backlog (units)")
    axes[0].set_title("drain dynamics", fontsize=10)
    axes[1].set_ylabel("granted units per tick")
    axes[1].set_title("served stream (new + backfill)", fontsize=10)
    for ax in axes:
        ax.grid(alpha=0.3)
        ax.legend(fontsize=7)
    fig.suptitle(title, fontsize = 10)
    fig.tight_layout()
    fig.savefig(FIGS / fname, dpi=150)
    plt.close(fig)


def tables_md(agg, order):
    metrics = [("backDelayMean", "backfill delay mean (s)", "{:.2f}"),
               ("backDelayP95", "backfill delay p95 (s)", "{:.2f}"),
               ("newDelayMean", "fresh-data delay mean (s)", "{:.3f}"),
               ("delivBack", "delivered backfill (units)", "{:,.0f}"),
               ("backlogPeak", "backlog peak (units)", "{:,.0f}"),
               ("backlogArea", "backlog area (unit*s)", "{:,.0f}"),
               ("drainMean", "drain mean (s)", "{:.2f}"),
               ("jainBackfill", "jain fairness (backfill)", "{:.3f}")]
    lines = ["# Result tables (mean over all seeds per scenario)\n"]
    for col, label, fmt in metrics:
        lines.append(f"\n### {label} (`{col}`)\n")
        lines.append("| scenario | " + " | ".join(NICE[p] for p in POLICIES) + " |")
        lines.append("|---|" + "---|" * len(POLICIES))
        for sc in order:
            cells = []
            for p in POLICIES:
                v = agg.get((sc, p), {}).get(col, float("nan"))
                cells.append(fmt.format(v) if v == v else "—")
            lines.append("| " + sc + " | " + " | ".join(cells) + " |")
    (ROOT / "analysis" / "tables.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    FIGS.mkdir(parents=True, exist_ok=True)
    agg = load_summaries()
    order = scenario_order(agg)

    grouped_bars(agg, order, "backDelayMean", "seconds",
                 "Backfill delivery latency — mean by scenario and policy", "back_delay_mean.png")
    grouped_bars(agg, order, "backDelayP95", "seconds",
                 "Backfill delivery latency — p95", "back_delay_p95.png")
    grouped_bars(agg, order, "newDelayMean", "seconds",
                 "Fresh-data latency — mean (log scale)", "new_delay_mean.png", log=True)
    grouped_bars(agg, order, "backlogPeak", "units",
                 "Accumulated backlog peak", "backlog_peak.png")
    grouped_bars(agg, order, "drainMean", "seconds",
                 "Mean backlog drain time after reconnect", "drain_mean.png")

    for scen in ("base", "storm", "rho1.1", "imbalance_dominant", "imbalance_zipf"):
        for seed in (0,):
            tick_figure(scen, seed,
                        f"tick_{scen}.png",
                        f"Per-tick dynamics — scenario '{scen}', seed {seed}")

    tables_md(agg, order)
    print("figures ->", FIGS)
    print("tables  ->", ROOT / "analysis" / "tables.md")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Statistical analysis of the backfill experiment: 4 policies x scenarios x replications.

Design (see docs/STATISTICS.md):
  * one run = one independent replication of a 600 s operating window (50 s warmup deleted);
    run-level metrics are the observations, never the per-unit delays inside a run
    (those are autocorrelated, so treating them as a sample would be pseudo-replication);
  * the four policies of one (scenario, seed) see identical arrival and outage streams
    (common random numbers), so every policy comparison is PAIRED by seed;
  * per cell: mean with a Student-t 95% CI, cross-checked by a BCa bootstrap CI;
  * per (scenario, metric): Friedman test over the 4 policies (blocks = seeds), then the 6
    pairwise comparisons on the per-seed log-ratio log(A/B): paired t-test (primary, the same
    scale as the reported ratio and its CI) and Wilcoxon signed-rank (robustness),
    Holm-adjusted within the family; Benjamini-Hochberg over all tests as a second look;
    effect sizes: geometric-mean ratio with CI, d_z and win rate;
  * replication adequacy: the number of replications needed for a given relative precision,
    of a cell mean (Law & Kelton's criterion, gamma/(1+gamma)) and of a policy ratio.

Inputs : results/runs/<scenario>/run_<scenario>_s<seed>_<policy>_run.csv   (seeds 0-4)
         results/replications/<scenario>/...                               (extra seeds)
Outputs: results/summary_runs.csv, analysis/stats/*.csv, analysis/STATS.md,
         analysis/figures/stats_*.png

Usage:   python analysis/stats.py [--runs DIR ...] [--out DIR] [--alpha 0.05] [--no-figures]
"""
import argparse
import csv
import math
import re
import sys
import warnings
from itertools import combinations
from pathlib import Path

import numpy as np
from scipy import stats

ROOT = Path(__file__).resolve().parent.parent

POLICIES = ["fifo", "rr", "ratelimit", "newfirst"]
NICE = {"fifo": "FIFO", "rr": "Round-robin", "ratelimit": "Rate-limit", "newfirst": "New-first"}
COLOR = {"fifo": "#e67e22", "rr": "#8e44ad", "ratelimit": "#27ae60", "newfirst": "#2980b9"}
SCENARIOS = ["base", "N4", "N10", "N16", "rho0.4", "rho0.9", "rho1.1",
             "imbalance_zipf", "imbalance_dominant", "storm"]
SWEEPS = {
    "N": [("N4", 4), ("base", 6), ("N10", 10), ("N16", 16)],
    "rho": [("rho0.4", 0.4), ("base", 0.7), ("rho0.9", 0.9), ("rho1.1", 1.1)],
}

# key, label, unit, better ("min" or "max")
METRICS = [
    ("newDelayMean", "fresh-data delay, mean", "s", "min"),
    ("newDelayP95", "fresh-data delay, p95", "s", "min"),
    ("backDelayMean", "backfill delay, mean", "s", "min"),
    ("backDelayP95", "backfill delay, p95", "s", "min"),
    ("backlogPeak", "backlog peak", "units", "min"),
    ("drainMean", "drain time after reconnect, mean", "s", "min"),
    ("backDelivered", "backfill delivered / generated", "ratio", "max"),
    ("jainBackfill", "Jain fairness of backfill delivery", "index", "max"),
]
METRIC = {m[0]: m for m in METRICS}
HEADLINE = ["newDelayMean", "backDelayMean", "backlogPeak", "backDelivered"]

RUN_NAME = re.compile(r"run_(.+)_s(\d+)_([a-z]+)_run")


# ------------------------------------------------------------------------------- loading

def load_runs(roots):
    """(scenario, policy, seed) -> row dict with floats; the first root holding a run wins."""
    runs = {}
    for root in roots:
        for f in sorted(Path(root).glob("*/*_run.csv")):
            m = RUN_NAME.fullmatch(f.stem)
            if not m:
                continue
            key = (m.group(1), m.group(3), int(m.group(2)))
            if key in runs:
                continue
            with open(f, newline="") as fh:
                raw = next(csv.DictReader(fh))
            row = {}
            for k, v in raw.items():
                try:
                    row[k] = float(v)
                except (TypeError, ValueError):
                    row[k] = v
            row["scenario"], row["policy"], row["seed"] = key[0], key[1], key[2]
            gen_back = row.get("genBack", 0.0)
            row["backDelivered"] = row["delivBack"] / gen_back if gen_back > 0 else math.nan
            # a drain mean is only defined when at least one drain completed
            if row.get("drains", 0.0) == 0:
                row["drainMean"] = math.nan
            runs[key] = row
    return runs


def write_tidy(runs, path):
    cols = ["scenario", "policy", "seed"]
    extra = []
    for row in runs.values():
        for k in row:
            if k not in cols and k not in extra:
                extra.append(k)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(cols + extra)
        for key in sorted(runs, key=lambda k: (scen_rank(k[0]), POLICIES.index(k[1]) if k[1] in POLICIES else 9, k[2])):
            row = runs[key]
            w.writerow([row.get(c, "") for c in cols + extra])


def scen_rank(s):
    return SCENARIOS.index(s) if s in SCENARIOS else len(SCENARIOS)


def paired_matrix(runs, scenario, metric):
    """Seeds present for all four policies, and the (policy -> values) arrays in seed order."""
    seed_sets = [{k[2] for k in runs if k[0] == scenario and k[1] == p} for p in POLICIES]
    seeds = sorted(set.intersection(*seed_sets)) if all(seed_sets) else []
    data = {p: np.array([runs[(scenario, p, s)][metric] for s in seeds], dtype=float) for p in POLICIES}
    return seeds, data


# ------------------------------------------------------------------------- statistics

def t_ci(x, alpha):
    x = x[~np.isnan(x)]
    n = len(x)
    if n == 0:
        return dict(n=0, mean=math.nan, sd=math.nan, lo=math.nan, hi=math.nan, half=math.nan)
    m = float(x.mean())
    if n < 2:
        return dict(n=n, mean=m, sd=math.nan, lo=math.nan, hi=math.nan, half=math.nan)
    s = float(x.std(ddof=1))
    h = float(stats.t.ppf(1 - alpha / 2, n - 1) * s / math.sqrt(n))
    return dict(n=n, mean=m, sd=s, lo=m - h, hi=m + h, half=h)


def bca_ci(x, alpha, rng):
    x = x[~np.isnan(x)]
    if len(x) < 3 or np.all(x == x[0]):
        v = float(x[0]) if len(x) else math.nan
        return v, v
    try:
        res = stats.bootstrap((x,), np.mean, confidence_level=1 - alpha, n_resamples=9999,
                              method="BCa", random_state=rng)
        lo, hi = float(res.confidence_interval.low), float(res.confidence_interval.high)
        if math.isnan(lo) or math.isnan(hi):
            raise ValueError("degenerate BCa interval")
        return lo, hi
    except Exception:
        res = stats.bootstrap((x,), np.mean, confidence_level=1 - alpha, n_resamples=9999,
                              method="percentile", random_state=rng)
        return float(res.confidence_interval.low), float(res.confidence_interval.high)


_T_N = np.arange(2, 100_001)
_T_Q = {}


def n_for_half_width(s, target, alpha):
    """Smallest n >= 2 whose Student-t half-width t(1-alpha/2; n-1) * s / sqrt(n) is <= target."""
    if math.isnan(s) or math.isnan(target):
        return math.nan
    if s == 0:
        return 2
    if target <= 0:
        return math.inf
    if alpha not in _T_Q:
        _T_Q[alpha] = stats.t.ppf(1 - alpha / 2, _T_N - 1)
    ok = np.nonzero(_T_Q[alpha] * s / np.sqrt(_T_N) <= target)[0]
    return int(_T_N[ok[0]]) if len(ok) else math.inf


def required_replications(x, gamma, alpha):
    """Smallest n whose t half-width is within relative error gamma (Law & Kelton: the
    half-width must not exceed gamma/(1+gamma) of |mean|), using the pilot sd of x."""
    x = x[~np.isnan(x)]
    if len(x) < 2:
        return math.nan
    return n_for_half_width(float(x.std(ddof=1)), gamma / (1 + gamma) * abs(float(x.mean())), alpha)


def holm(pvals):
    """Holm step-down adjusted p-values (NaN entries are kept as NaN)."""
    p = np.array(pvals, dtype=float)
    idx = [i for i in range(len(p)) if not math.isnan(p[i])]
    adj = np.full(len(p), math.nan)
    order = sorted(idx, key=lambda i: p[i])
    running = 0.0
    m = len(order)
    for rank, i in enumerate(order):
        running = max(running, min(1.0, (m - rank) * p[i]))
        adj[i] = running
    return adj


def bh(pvals):
    """Benjamini-Hochberg adjusted p-values (q-values)."""
    p = np.array(pvals, dtype=float)
    idx = [i for i in range(len(p)) if not math.isnan(p[i])]
    q = np.full(len(p), math.nan)
    order = sorted(idx, key=lambda i: p[i])
    m = len(order)
    prev = 1.0
    for rank in range(m - 1, -1, -1):
        i = order[rank]
        prev = min(prev, p[i] * m / (rank + 1))
        q[i] = prev
    return q


def better(a, b, direction):
    """Per-replication score of 'a beats b': 1 win, 0.5 tie, 0 loss."""
    if direction == "min":
        return np.where(a < b, 1.0, np.where(a == b, 0.5, 0.0))
    return np.where(a > b, 1.0, np.where(a == b, 0.5, 0.0))


def paired(a, b, alpha, direction):
    """Paired comparison of A and B over the seeds where both are defined.

    Inference is on the per-seed log-ratio log(A/B) when every value is positive: policy effects
    are multiplicative (a policy that doubles the backfill delay doubles it in a calm and in a
    stormy replication alike), so log-ratios are far more homogeneous and closer to normal than
    raw differences, and the test then lives on the same scale as the reported ratio and its CI.
    Otherwise (a zero or negative value) the raw difference A - B is used. The raw-difference CI
    and t-test p are kept in the output for reference."""
    ok = ~(np.isnan(a) | np.isnan(b))
    a, b = a[ok], b[ok]
    n = len(a)
    out = dict(n=n)
    raw = t_ci(a - b, alpha)
    out.update(diff=raw["mean"], diff_lo=raw["lo"], diff_hi=raw["hi"])
    if n < 2:
        out["p_t_raw"] = math.nan
    elif raw["sd"] > 0:
        out["p_t_raw"] = float(stats.ttest_rel(a, b).pvalue)
    else:
        out["p_t_raw"] = 1.0 if raw["mean"] == 0 else 0.0
    log_scale = n >= 2 and bool(np.all(a > 0)) and bool(np.all(b > 0))
    x, y = (np.log(a), np.log(b)) if log_scale else (a, b)
    d = x - y
    ci = t_ci(d, alpha)
    sd = ci["sd"]
    out["scale"] = "log" if log_scale else "raw"
    if n < 2:
        out.update(p_t=math.nan, p_w=math.nan, p_sw=math.nan, dz=math.nan)
    elif np.all(d == 0):
        out.update(p_t=1.0, p_w=1.0, p_sw=math.nan, dz=0.0)
    else:
        out["p_t"] = float(stats.ttest_1samp(d, 0.0).pvalue) if sd > 0 else 0.0
        out["p_w"] = float(stats.wilcoxon(d, zero_method="wilcox").pvalue)
        out["p_sw"] = float(stats.shapiro(d).pvalue) if n >= 3 and sd > 0 else math.nan
        out["dz"] = ci["mean"] / sd if sd > 0 else math.copysign(math.inf, ci["mean"])
    if log_scale:
        out.update(ratio=math.exp(ci["mean"]),
                   ratio_lo=math.exp(ci["lo"]) if not math.isnan(ci["lo"]) else math.nan,
                   ratio_hi=math.exp(ci["hi"]) if not math.isnan(ci["hi"]) else math.nan,
                   ratio_half=math.exp(ci["half"]) - 1 if not math.isnan(ci["half"]) else math.nan,
                   n_req_5=n_for_half_width(sd, math.log(1.05), alpha),
                   n_req_10=n_for_half_width(sd, math.log(1.10), alpha))
    else:
        out.update(ratio=math.nan, ratio_lo=math.nan, ratio_hi=math.nan, ratio_half=math.nan,
                   n_req_5=math.nan, n_req_10=math.nan)
    out["win"] = float(better(a, b, direction).mean()) if n else math.nan
    vx, vy, vd = (float(np.var(v, ddof=1)) if n > 1 else math.nan for v in (x, y, d))
    out["corr"] = float(np.corrcoef(x, y)[0, 1]) if n > 2 and vx > 0 and vy > 0 else math.nan
    out["crn_gain"] = (vx + vy) / vd if n > 1 and vd > 0 else math.nan
    return out


def favours(f, first, direction):
    """True when the point estimate of comparison f says policy `first` beats the other one."""
    if f["scale"] == "log":
        eff = math.log(f["ratio"]) if f["ratio"] > 0 else math.nan
    else:
        eff = f["diff"]
    if math.isnan(eff):
        return False
    if f["a"] != first:
        eff = -eff
    return eff < 0 if direction == "min" else eff > 0


def friedman(data):
    arrays = [data[p] for p in POLICIES]
    ok = ~np.any(np.isnan(np.vstack(arrays)), axis=0)
    arrays = [x[ok] for x in arrays]
    n, k = len(arrays[0]), len(arrays)
    if n < 2 or all(np.all(x == arrays[0]) for x in arrays):
        return dict(n=n, chi2=math.nan, p=math.nan, W=math.nan)
    try:
        chi2, p = stats.friedmanchisquare(*arrays)
    except ValueError:
        return dict(n=n, chi2=math.nan, p=math.nan, W=math.nan)
    return dict(n=n, chi2=float(chi2), p=float(p), W=float(chi2) / (n * (k - 1)))


# ---------------------------------------------------------------------------- analysis

def analyse(runs, alpha):
    rng = np.random.default_rng(20261119)
    scenarios = sorted({k[0] for k in runs}, key=scen_rank)
    cells, pairs, fried, winners = [], [], [], []
    for sc in scenarios:
        for key, label, unit, direction in METRICS:
            seeds, data = paired_matrix(runs, sc, key)
            if not seeds:
                continue
            means = {}
            for p in POLICIES:
                ci = t_ci(data[p], alpha)
                blo, bhi = bca_ci(data[p], alpha, rng)
                rel = (ci["half"] / abs(ci["mean"])
                       if ci["mean"] and not math.isnan(ci["mean"]) and not math.isnan(ci["half"]) else math.nan)
                cells.append(dict(scenario=sc, metric=key, policy=p, n=ci["n"], mean=ci["mean"], sd=ci["sd"],
                                  ci_lo=ci["lo"], ci_hi=ci["hi"], rel_half=rel, boot_lo=blo, boot_hi=bhi,
                                  n_req_5=required_replications(data[p], 0.05, alpha),
                                  n_req_10=required_replications(data[p], 0.10, alpha)))
                means[p] = ci["mean"]
            fr = friedman(data)
            fried.append(dict(scenario=sc, metric=key, **fr))
            family = []
            for a, b in combinations(POLICIES, 2):
                r = paired(data[a], data[b], alpha, direction)
                family.append(dict(scenario=sc, metric=key, a=a, b=b, **r))
            adj = holm([f["p_t"] for f in family])
            adj_w = holm([f["p_w"] for f in family])
            for f, h, hw in zip(family, adj, adj_w):
                f["p_t_holm"], f["p_w_holm"] = float(h), float(hw)
            pairs.extend(family)
            # winner: best mean; a policy ties with it unless both paired tests (t and Wilcoxon on
            # the log-ratios, Holm) show the best one significantly better, in the direction of the means
            valid = [p for p in POLICIES if not math.isnan(means[p])]
            if not valid:
                continue
            ranked = sorted(valid, key=lambda p: means[p], reverse=(direction == "max"))
            best = ranked[0]
            tied = []
            for p in ranked[1:]:
                f = next(f for f in family if {f["a"], f["b"]} == {best, p})
                robust = all(not math.isnan(f[k]) and f[k] < alpha for k in ("p_t_holm", "p_w_holm"))
                if not robust or not favours(f, best, direction):
                    tied.append(p)
            winners.append(dict(scenario=sc, metric=key, best=best, tied="+".join(tied),
                                ranking=" > ".join(ranked), friedman_p=fr["p"]))
    q = bh([f["p_t"] for f in pairs])
    for f, qq in zip(pairs, q):
        f["q_bh"] = float(qq)
    return cells, pairs, fried, winners


def sweep_effects(runs, alpha):
    """Paired (by seed) ratio of a metric between consecutive levels of a sweep, per policy."""
    rows = []
    for sweep, levels in SWEEPS.items():
        for (s0, x0), (s1, x1) in zip(levels, levels[1:]):
            for key, _, _, direction in METRICS:
                for p in POLICIES:
                    seeds = sorted({k[2] for k in runs if k[0] == s0 and k[1] == p} &
                                   {k[2] for k in runs if k[0] == s1 and k[1] == p})
                    if len(seeds) < 2:
                        continue
                    a = np.array([runs[(s1, p, s)][key] for s in seeds], dtype=float)
                    b = np.array([runs[(s0, p, s)][key] for s in seeds], dtype=float)
                    r = paired(a, b, alpha, direction)
                    rows.append(dict(sweep=sweep, frm=x0, to=x1, metric=key, policy=p, n=r["n"],
                                     ratio=r["ratio"], ratio_lo=r["ratio_lo"], ratio_hi=r["ratio_hi"],
                                     p_t=r["p_t"]))
    return rows


# ------------------------------------------------------------------------------ output

def fmt(v, unit=""):
    if v is None or (isinstance(v, float) and (math.isnan(v))):
        return "—"
    if isinstance(v, float) and math.isinf(v):
        return "∞"
    a = abs(v)
    if unit == "ratio" or unit == "index":
        return f"{v:.3f}"
    if a >= 1000:
        return f"{v:,.0f}"
    if a >= 100:
        return f"{v:.1f}"
    if a >= 1:
        return f"{v:.2f}"
    return f"{v:.3f}"


def fmt_p(p):
    if p is None or math.isnan(p):
        return "—"
    if p < 1e-4:
        return "<1e-4"
    return f"{p:.4f}"


def pct(r):
    return "—" if math.isnan(r) else f"{(r - 1) * 100:+.1f}%"


def count(n):
    if n is None or (isinstance(n, float) and math.isnan(n)):
        return "—"
    return "∞" if math.isinf(n) else f"{int(n):,}"


def write_csv(path, rows):
    if not rows:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        for r in rows:
            w.writerow({k: (f"{v:.6g}" if isinstance(v, float) else v) for k, v in r.items()})


def markdown(runs, cells, pairs, fried, winners, sweeps, alpha, out_md):
    conf = int(round((1 - alpha) * 100))
    seeds_per_cell = sorted({c["n"] for c in cells if c["metric"] == "newDelayMean"})
    L = []
    L.append("# Statistical analysis of the policy comparison (generated)\n")
    L.append(f"Generated by `analysis/stats.py` from {len(runs)} runs "
             f"({len({k[0] for k in runs})} scenarios × {len(POLICIES)} policies, "
             f"{'/'.join(map(str, seeds_per_cell))} replications per cell). "
             f"Confidence level {conf}%, α = {alpha}. Method: [`docs/STATISTICS.md`](../docs/STATISTICS.md).\n")
    L.append("Cells show **mean [t-CI]** over replications; all policy comparisons are paired by seed "
             "(common random numbers). ✓ = best mean and significantly better than every other policy "
             "(paired t-test and Wilcoxon signed-rank on the per-seed log-ratio, both Holm-adjusted); "
             "policies in *italics* are not robustly different from the best.\n")

    # 1. per-scenario tables
    L.append("## 1. Means with confidence intervals\n")
    by = {(c["scenario"], c["metric"], c["policy"]): c for c in cells}
    win = {(w["scenario"], w["metric"]): w for w in winners}
    scenarios = sorted({c["scenario"] for c in cells}, key=scen_rank)
    for sc in scenarios:
        L.append(f"### {sc}\n")
        L.append("| metric | " + " | ".join(NICE[p] for p in POLICIES) + " | Friedman p |")
        L.append("|---|" + "---|" * (len(POLICIES) + 1))
        for key, label, unit, direction in METRICS:
            w = win.get((sc, key))
            row = []
            for p in POLICIES:
                c = by.get((sc, key, p))
                if c is None:
                    row.append("—")
                    continue
                cell = f"{fmt(c['mean'], unit)} [{fmt(c['ci_lo'], unit)}, {fmt(c['ci_hi'], unit)}]"
                if w and p == w["best"]:
                    cell = f"**{cell}**" + (" ✓" if not w["tied"] else "")
                elif w and p in w["tied"].split("+"):
                    cell = f"*{cell}*"
                row.append(cell)
            fp = next((f["p"] for f in fried if f["scenario"] == sc and f["metric"] == key), math.nan)
            L.append(f"| {label} ({unit}, {'lower' if direction == 'min' else 'higher'} is better) | "
                     + " | ".join(row) + f" | {fmt_p(fp)} |")
        L.append("")

    # 2. winners
    L.append("## 2. Best policy per scenario and metric\n")
    L.append("| scenario | " + " | ".join(METRIC[k][1] for k in HEADLINE) + " |")
    L.append("|---|" + "---|" * len(HEADLINE))
    for sc in scenarios:
        cells_txt = []
        for k in HEADLINE:
            w = win.get((sc, k))
            if not w:
                cells_txt.append("—")
                continue
            txt = NICE[w["best"]] + (" ✓" if not w["tied"] else " = " + " = ".join(NICE[t] for t in w["tied"].split("+")))
            cells_txt.append(txt)
        L.append(f"| {sc} | " + " | ".join(cells_txt) + " |")
    L.append("")

    # 3. pairwise ratios for the headline metrics
    L.append("## 3. Pairwise comparisons (ratio A/B, paired by seed)\n")
    L.append(f"Each entry: A/B − 1, the geometric mean of the per-seed ratios, with its {conf}% CI; p = Holm-adjusted "
             "paired t-test on log(A/B) (Wilcoxon signed-rank on the same log-ratios in brackets); d_z = mean / sd "
             "of the log-ratios; win = share of replications in which A beats B.\n")
    for k in HEADLINE:
        L.append(f"### {METRIC[k][1]}\n")
        L.append("| scenario | A vs B | A/B − 1 [CI] | p (Wilcoxon) | d_z | win(A) |")
        L.append("|---|---|---|---|---|---|")
        for sc in scenarios:
            for f in (f for f in pairs if f["scenario"] == sc and f["metric"] == k):
                ci = "—" if math.isnan(f["ratio"]) else f"{pct(f['ratio'])} [{pct(f['ratio_lo'])}, {pct(f['ratio_hi'])}]"
                dz = "—" if math.isnan(f["dz"]) else (f"{f['dz']:+.2f}" if not math.isinf(f["dz"]) else "±∞")
                L.append(f"| {sc} | {NICE[f['a']]} vs {NICE[f['b']]} | {ci} | {fmt_p(f['p_t_holm'])} "
                         f"({fmt_p(f['p_w_holm'])}) | {dz} | {f['win']:.2f} |")
        L.append("")

    # 4. sweeps
    L.append("## 4. Sensitivity along the sweeps (paired ratio between consecutive levels)\n")
    for sweep in SWEEPS:
        L.append(f"### {sweep}\n")
        L.append("| metric | step | " + " | ".join(NICE[p] for p in POLICIES) + " |")
        L.append("|---|---|" + "---|" * len(POLICIES))
        for k in HEADLINE:
            steps = sorted({(r["frm"], r["to"]) for r in sweeps if r["sweep"] == sweep and r["metric"] == k})
            for frm, to in steps:
                row = []
                for p in POLICIES:
                    r = next((r for r in sweeps if r["sweep"] == sweep and r["metric"] == k and r["policy"] == p
                              and r["frm"] == frm and r["to"] == to), None)
                    row.append("—" if r is None or math.isnan(r["ratio"]) else
                               f"{pct(r['ratio'])} [{pct(r['ratio_lo'])}, {pct(r['ratio_hi'])}]")
                L.append(f"| {METRIC[k][1]} | {frm:g} → {to:g} | " + " | ".join(row) + " |")
        L.append("")

    # 5. replication adequacy
    L.append("## 5. Replication adequacy\n")
    L.append("### 5.1 Absolute level of a cell\n")
    L.append("Largest relative CI half-width of a cell mean over the four policies, and the replications that "
             "the worst cell would need for ±5% / ±10% relative precision (Law & Kelton criterion). The level "
             "varies a lot between replications because the number and length of outages do, so these "
             "numbers are large; the policy comparisons below are paired and far more precise.\n")
    L.append("| scenario | " + " | ".join(METRIC[k][1] for k in HEADLINE) + " |")
    L.append("|---|" + "---|" * len(HEADLINE))
    for sc in scenarios:
        row = []
        for k in HEADLINE:
            cs = [by[(sc, k, p)] for p in POLICIES if (sc, k, p) in by]
            rel = max((c["rel_half"] for c in cs if not math.isnan(c["rel_half"])), default=math.nan)
            n5 = max((c["n_req_5"] for c in cs if not math.isnan(c["n_req_5"])), default=math.nan)
            n10 = max((c["n_req_10"] for c in cs if not math.isnan(c["n_req_10"])), default=math.nan)
            row.append(f"±{rel * 100:.1f}% (n₅={count(n5)}, n₁₀={count(n10)})" if not math.isnan(rel) else "—")
        L.append(f"| {sc} | " + " | ".join(row) + " |")
    L.append("")
    L.append("### 5.2 Policy comparisons\n")
    L.append(f"Relative half-width of the {conf}% CI of a ratio A/B over the 6 pairs: median, worst, and the "
             "replications the worst pair would need for ±10%. A wide interval on a ratio close to 1 "
             "only means that the two policies are hard to tell apart on that metric.\n")
    L.append("| scenario | " + " | ".join(METRIC[k][1] for k in HEADLINE) + " |")
    L.append("|---|" + "---|" * len(HEADLINE))
    for sc in scenarios:
        row = []
        for k in HEADLINE:
            fam = [f for f in pairs if f["scenario"] == sc and f["metric"] == k and not math.isnan(f["ratio_half"])]
            if not fam:
                row.append("—")
                continue
            halves = [f["ratio_half"] for f in fam]
            worst = max(fam, key=lambda f: f["ratio_half"])
            row.append(f"±{np.median(halves) * 100:.1f}% · worst ±{worst['ratio_half'] * 100:.1f}% "
                       f"(n₁₀={count(worst['n_req_10'])})")
        L.append(f"| {sc} | " + " | ".join(row) + " |")
    L.append("")

    # 6. assumptions and CRN
    L.append("## 6. Assumption checks and common random numbers\n")
    sw = [f["p_sw"] for f in pairs if not math.isnan(f.get("p_sw", math.nan))]
    disagree = [f for f in pairs if not math.isnan(f["p_t_holm"]) and not math.isnan(f["p_w_holm"])
                and (f["p_t_holm"] < alpha) != (f["p_w_holm"] < alpha)]
    gains = [f["crn_gain"] for f in pairs if not math.isnan(f["crn_gain"])]
    L.append(f"* Shapiro–Wilk on the paired log-ratios: normality is rejected at α = {alpha} in "
             f"{sum(p < alpha for p in sw)} of {len(sw)} comparisons; for those, read the Wilcoxon result next to "
             "the t-test, and the BCa bootstrap CI (`analysis/stats/cells.csv`) next to the t-CI of a cell.")
    L.append(f"* Paired t-test and Wilcoxon disagree on significance (Holm) in {len(disagree)} of {len(pairs)} comparisons"
             + (": " + ", ".join(f"{d['scenario']}/{d['metric']}/{d['a']}-{d['b']}" for d in disagree[:12])
                + (" …" if len(disagree) > 12 else "") if disagree else "") + ".")
    if gains:
        g = np.array(gains)
        L.append(f"* Variance reduction from common random numbers, (Var log A + Var log B) / Var log(A/B): median "
                 f"{np.median(g):.1f}×, interquartile range {np.percentile(g, 25):.1f}–{np.percentile(g, 75):.1f}× "
                 f"over {len(g)} comparisons — the paired design needs that many times fewer replications than "
                 "independent sampling for the same precision.")
    sig_t = sum(1 for f in pairs if not math.isnan(f["p_t_holm"]) and f["p_t_holm"] < alpha)
    sig_q = sum(1 for f in pairs if not math.isnan(f["q_bh"]) and f["q_bh"] < alpha)
    L.append(f"* Significant pairwise differences: {sig_t} of {len(pairs)} (Holm within each scenario × metric family); "
             f"{sig_q} of {len(pairs)} with Benjamini–Hochberg FDR control across all comparisons.")
    L.append("")
    out_md.write_text("\n".join(L) + "\n", encoding="utf-8")


# ----------------------------------------------------------------------------- figures

def figures(runs, cells, alpha, figdir):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    figdir.mkdir(parents=True, exist_ok=True)
    by = {(c["scenario"], c["metric"], c["policy"]): c for c in cells}
    scenarios = [s for s in SCENARIOS if any(k[0] == s for k in by)]
    conf = int(round((1 - alpha) * 100))

    # point-range plot per headline metric over all scenarios
    for key in HEADLINE + ["newDelayP95", "backDelayP95", "drainMean", "jainBackfill"]:
        _, label, unit, direction = METRIC[key]
        fig, ax = plt.subplots(figsize=(11, 3.8))
        for i, p in enumerate(POLICIES):
            xs, ys, lo, hi = [], [], [], []
            for j, sc in enumerate(scenarios):
                c = by.get((sc, key, p))
                if c is None or math.isnan(c["mean"]):
                    continue
                xs.append(j + (i - 1.5) * 0.18)
                ys.append(c["mean"])
                lo.append(c["mean"] - c["ci_lo"] if not math.isnan(c["ci_lo"]) else 0)
                hi.append(c["ci_hi"] - c["mean"] if not math.isnan(c["ci_hi"]) else 0)
            ax.errorbar(xs, ys, yerr=[lo, hi], fmt="o", ms=4, capsize=2.5, color=COLOR[p], label=NICE[p])
        ax.set_xticks(range(len(scenarios)))
        ax.set_xticklabels(scenarios, rotation=20, ha="right", fontsize=8)
        ax.set_ylabel(f"{label} ({unit})")
        positive = all(c["mean"] > 0 for c in cells if c["metric"] == key and not math.isnan(c["mean"]))
        if unit in ("s", "units") and positive:
            ax.set_yscale("log")
        ax.set_title(f"{label}: mean and {conf}% CI over replications ({'lower' if direction == 'min' else 'higher'} is better)",
                     fontsize=10)
        ax.grid(alpha=0.3, which="both", axis="y")
        ax.legend(fontsize=8, ncol=4)
        fig.tight_layout()
        fig.savefig(figdir / f"stats_ci_{key}.png", dpi=150)
        plt.close(fig)

    # sweeps: mean and CI band versus the swept factor
    for sweep, levels in SWEEPS.items():
        fig, axes = plt.subplots(1, len(HEADLINE), figsize=(4.0 * len(HEADLINE), 3.4))
        for ax, key in zip(axes, HEADLINE):
            _, label, unit, _ = METRIC[key]
            for p in POLICIES:
                pts = [(x, by[(s, key, p)]) for s, x in levels if (s, key, p) in by]
                if not pts:
                    continue
                xs = [x for x, _ in pts]
                ax.plot(xs, [c["mean"] for _, c in pts], "o-", color=COLOR[p], label=NICE[p], ms=3.5)
                ax.fill_between(xs, [c["ci_lo"] for _, c in pts], [c["ci_hi"] for _, c in pts],
                                color=COLOR[p], alpha=0.18)
            ax.set_xlabel("number of edges N" if sweep == "N" else "load ρ = Σλ / C")
            ax.set_title(f"{label} ({unit})", fontsize=9)
            if unit in ("s", "units"):
                ax.set_yscale("log")
            ax.grid(alpha=0.3, which="both")
        axes[0].legend(fontsize=7)
        fig.suptitle(f"Sweep over {'N' if sweep == 'N' else 'ρ'}: mean with {conf}% CI band", fontsize=10)
        fig.tight_layout()
        fig.savefig(figdir / f"stats_sweep_{sweep}.png", dpi=150)
        plt.close(fig)

    # forest plot: every policy relative to FIFO, headline metrics
    pair_rows = []
    for key in HEADLINE:
        for sc in scenarios:
            seeds, data = paired_matrix(runs, sc, key)
            for p in POLICIES[1:]:
                r = paired(data[p], data["fifo"], alpha, METRIC[key][3])
                pair_rows.append((key, sc, p, r))
    fig, axes = plt.subplots(1, len(HEADLINE), figsize=(4.2 * len(HEADLINE), 0.42 * len(scenarios) + 1.6), sharey=True)
    for ax, key in zip(axes, HEADLINE):
        for i, p in enumerate(POLICIES[1:]):
            ys, xs, lo, hi = [], [], [], []
            for j, sc in enumerate(scenarios):
                r = next((r for k, s, q, r in pair_rows if k == key and s == sc and q == p), None)
                if r is None or math.isnan(r["ratio"]):
                    continue
                ys.append(j + (i - 1) * 0.22)
                xs.append(r["ratio"])
                lo.append(r["ratio"] - r["ratio_lo"])
                hi.append(r["ratio_hi"] - r["ratio"])
            ax.errorbar(xs, ys, xerr=[lo, hi], fmt="o", ms=3.5, capsize=2, color=COLOR[p], label=NICE[p])
        ax.axvline(1.0, color="#555", lw=0.8, ls="--")
        ax.set_xscale("log")
        ax.set_title(f"{METRIC[key][1]}\nratio to FIFO ({conf}% CI)", fontsize=9)
        ax.grid(alpha=0.3, which="both", axis="x")
    axes[0].set_yticks(range(len(scenarios)))
    axes[0].set_yticklabels(scenarios, fontsize=8)
    axes[0].invert_yaxis()
    axes[-1].legend(fontsize=7, loc="lower right")
    fig.tight_layout()
    fig.savefig(figdir / "stats_forest_vs_fifo.png", dpi=150)
    plt.close(fig)


# -------------------------------------------------------------------------------- main

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--runs", nargs="*", default=None,
                    help="run roots (default: results/runs and results/replications)")
    ap.add_argument("--out", default=None, help="output root (default: the repository)")
    ap.add_argument("--alpha", type=float, default=0.05)
    ap.add_argument("--no-figures", action="store_true")
    args = ap.parse_args(argv)
    # normal-approximation notices of Wilcoxon / Shapiro on tied data; the tests still run
    warnings.filterwarnings("ignore", category=UserWarning, module="scipy")
    warnings.filterwarnings("ignore", category=RuntimeWarning, module="scipy")

    roots =[Path(r) for r in args.runs] if args.runs else [ROOT / "results" / "runs", ROOT / "results" / "replications"]
    roots = [r for r in roots if r.exists()]
    out = Path(args.out) if args.out else ROOT
    runs = load_runs(roots)
    if not runs:
        print("no run CSVs found under: " + ", ".join(map(str, roots)))
        return 1

    tidy = out / "results" / "summary_runs.csv"
    stats_dir = out / "analysis" / "stats"
    write_tidy(runs, tidy)
    cells, pairs, fried, winners = analyse(runs, args.alpha)
    sweeps = sweep_effects(runs, args.alpha)
    write_csv(stats_dir / "cells.csv", cells)
    write_csv(stats_dir / "pairwise.csv", pairs)
    write_csv(stats_dir / "friedman.csv", fried)
    write_csv(stats_dir / "winners.csv", winners)
    write_csv(stats_dir / "sweeps.csv", sweeps)
    md = out / "analysis" / "STATS.md"
    markdown(runs, cells, pairs, fried, winners, sweeps, args.alpha, md)
    if not args.no_figures:
        figures(runs, cells, args.alpha, out / "analysis" / "figures")

    print(f"runs analysed : {len(runs)} from {', '.join(str(r) for r in roots)}")
    print(f"tidy dataset  : {tidy}")
    print(f"statistics    : {stats_dir}{'' if args.no_figures else ' + analysis/figures/stats_*.png'}")
    print(f"report tables : {md}")
    print()
    print("best policy per scenario (= : not robustly different from the best; paired t and Wilcoxon "
          "on log-ratios, Holm)")
    for k in HEADLINE:
        print(f"  {METRIC[k][1]}:")
        for w in (w for w in winners if w["metric"] == k):
            tie = " = " + " = ".join(NICE[t] for t in w["tied"].split("+")) if w["tied"] else ""
            print(f"    {w['scenario']:<20s} {NICE[w['best']]}{tie}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

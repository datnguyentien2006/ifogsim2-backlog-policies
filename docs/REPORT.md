# Backfill scheduling in fog computing: policy comparison report

**Topic 2 — University course project.**
Simulator: [iFogSim2](https://github.com/Cloudslab/iFogSim2) extended with a backfill
scheduling framework (`sim/`), plus a pure-JS visual mirror (`web/`).

All numbers below are **means over 5 seeds** per (scenario × policy), 600 s data window
each; the raw CSVs are in `results/runs/<scenario>/`, the pivots in
[`../analysis/tables.md`](../analysis/tables.md), the figures in [`../analysis/figures/`](../analysis/figures/).

---

## 1. Problem

Data-backfill is the retransmission of measurement data that **accumulated while an edge
server's uplink was down**. When the link reconnects, a burst backlog competes with the
continuous stream of *fresh* data for one shared bottleneck: the receiving center's
capacity `C`. The scheduling decision — how much of every decision round to give to whom —
directly trades fresh-data latency against backlog drain time.

We simulate this process with **1 unit = 1 tuple**, a token-bucket center, edge servers
holding two queues each (fresh / accumulated), and realistic outage processes, and compare
four scheduling policies under **strictly identical inputs** (same arrival streams, same
outage timelines, same seeds, same token budget).

## 2. Framework design (why the comparison is fair)

* **Separation of policy and mechanism.** All egress funnels through the scheduler; an edge's
  transmission is *only ever* triggered by a grant a policy decided. Policies see immutable
  per-tick queue views and return allocations; the scheduler executes them verbatim (it even
  validates that a grant never exceeds what was reported).
* **Identical inputs.** Arrival processes are seeded per `(seed, edge)`; outages are seeded
  separately from arrivals. Given the same scenario and seed, all four policies replay the
  exact same generation and outage timelines. This is asserted continuously by
  `backfill.run.SelfTest` (equal-input + conservation + capacity-bound invariants) and
  mirrored in the web tests.
* **Reuse of upstream iFogSim2.** Topology, application, sensors, brokers, transmission math
  (tuple network size / bandwidth / latency) and the CloudSim event engine are stock
  iFogSim2/CloudSim; our code only intercepts `sendUp`, plugs in the token-bucket scheduler
  and adds the outage controller.

Model details: [`MODEL.md`](MODEL.md). Extension guide: [`ADD_POLICY.md`](ADD_POLICY.md).

## 3. Policies compared

| policy | rule |
|--------|------|
| FIFO | global generation order — after a reconnect the backlog head leads, fresh data lines up behind it |
| Round-robin | budget split in turn over connected edges with data; per-edge oldest-first drain; rotating cursor |
| Rate-limit | backfill transmits at a **fixed rate R = 0.5·C** regardless of backlog size; unused reserved capacity flows to fresh data |
| New-data-first | fresh data absolute priority; backfill limited to a small fixed quota q = 0.05·C |

## 4. Method

* Scenario grid = base (`N=6, ρ=0.7, EVEN, SCATTERED outages`) plus one-factor sweeps
  `N ∈ {4,10,16}`, `ρ ∈ {0.4,0.9,1.1}`, backlog imbalance `ZIPF` / `DOMINANT`, and a
  **storm** regime (`COHORT` outages: every 120 s half of the currently connected edges drop
  simultaneously and reconnect within a few-seconds window — ≈40–48 link-loss events per
  600 s run, and in the base regime each reconnect brings a backlog that must be drained).
* 5 seeds per (scenario, policy) → 200 runs; every metric below is the mean over seeds.
* Measured dimensions (the three requested): **fresh-data latency**, **backfill latency**,
  **backlog dynamics** (peak, area, drain time), plus delivered counts and per-edge fairness.
* Known artifact, handled: the lognormal outage tail can keep one edge down at the simulation
  cutoff, so `leftBack = backlogPeak` may occur in some runs; all statistics are computed over
  *delivered* units only, and conservation (`delivered + left = generated`) is asserted for
  every run.

## 5. Results

### 5.1 Headline — base scenario (N=6, ρ=0.7, EVEN, scattered outages)

*Revision note: the results below supersede the project's first experiment round. While
reviewing the framework we found and fixed two bugs — an unused-turn-quota accounting defect
in round-robin (§5.2, *Imbalance*), and a dead fairness counter (§5.3). All 200 runs were
regenerated after the fix; the defect changed only RR's quantitative results, but the fairness
metric now measures real delivery for every policy.*

| metric | FIFO | Round-robin | Rate-limit | New-first |
|---|---|---|---|---|
| fresh-data delay, mean (s) | 0.75 | 0.42 | **0.39** | **0.07** |
| backfill delay, mean (s) | **16.1** | 19.1 | 18.7 | 171.7 |
| backfill delay, p95 (s) | 45.3 | 52.8 | 48.9 | 323.9 |
| backlog peak (units) | **15.6k** | 16.2k | 15.8k | 52.3k |
| mean drain after reconnect (s) | 6.5 | **6.2** | 6.7 | 74.1 |
| delivered backfill (units) | 75.9k | 75.9k | 75.9k | 34.5k |

Reading: under a *fair* workload (all edges symmetric, center not overloaded) the three
serving policies are close, and each has a distinct signature. **FIFO is the best backlog
clearer** in strict latency terms (any unit, first-come). **Round-robin and rate-limit both
halve the fresh-data latency** (0.42/0.39 s vs 0.75 s) at a modest backlog price (+19 % / +16 %
mean backfill delay vs FIFO) — RR because each edge's merged backlog sits behind its own
shared turn, rate-limit because its reserved backfill rate R leaves fresh data the rest of the
budget by construction. RR's per-tick share (120/6 = 20 units/tick) rarely binds at balanced
load — a single edge's post-outage backlog fits a few ticks of its own share — so its drain
mean (6.2 s) even edges out FIFO's (6.5 s). **New-data-first protects fresh data 10×** at the
price of a backlog that never fully drains (only ~45 % of generated backfill delivered by
cutoff; q = 6 units/tick is simply too small).

### 5.2 Regime sweeps

**Scaling the number of edges (N = 4/10/16, ρ = 0.7).** FIFO, rate-limit *and* (now-fixed)
round-robin all improve monotonically with N: per-edge generation shrinks with 1/N, so each
outage accumulates less backlog (backlog peak 15.9k → 11.7k → 7.7k; FIFO drain mean
11.1 → 5.5 → 2.9 s; RR 9.9 → 4.6 → 2.5 s). With per-edge backlog fitting each edge's turn
share, RR's per-tick turn stops binding and it behaves essentially like FIFO — its fresh-data
delay at N=16 is 0.13 s, the lowest of the serving policies. New-first is essentially
insensitive to N (its quota decides everything).

**Workload vs. center capacity (ρ = 0.4 / 0.7 / 0.9 / 1.1).**
Under light load (ρ=0.4) even newfirst recovers partially (backfill mean 98 s, Jain 0.93 —
its highest); the regime is not stressful. At ρ=0.9 the queues begin to saturate: RR's
per-turn cap starts to bite (25.6 s backfill mean, 66.7 s p95, vs FIFO 17.1/46.7), though its
drains still complete sooner (18.2 s vs FIFO 25.2 s). At **ρ=1.1 the center is permanently
overloaded** and the ranking inverts:

| ρ=1.1 | FIFO | Round-robin | Rate-limit | New-first |
|---|---|---|---|---|
| backfill delay mean (s) | 32.9 | 59.6 | **22.6** | 215.3 |
| backlog peak (units) | 75.0k | 76.3k | 75.0k | 108.9k |
| delivered backfill (units) | 116.2k | 114.5k | **119.3k** | 34.8k |

Rate-limit becomes the best backfill server *and* keeps its fresh-data reservation, and
delivers the most backfill. This is the strongest practical result of the
study: **fixed-rate reservation pays off exactly when the system is closest to or beyond
saturation** — the classic controller-stability trade-off of schedulable-capacity knobs.
Round-robin is the clear loser here: with every edge deep in backlog, each edge must wait for
its slower turns, so individual drains stretch out (drain mean 68 s, max 128 s, vs 3.5 s
for FIFO/rate-limit) even though RR's *aggregate* throughput stays close to FIFO's
(114.5k vs 116.2k).

**Backlog imbalance (ZIPF, DOMINANT).** This is where the framework's history is most
instructive. The first experiment round reported a dramatic "round-robin collapses under
imbalance" result (69 s fresh delay, 187k backlog peak, only ~55 % of generated backfill
delivered). Independent re-verification traced almost all of it to an **implementation
defect, not a policy property**: the old `drainEdgeMerged` was void-returning, so turn quota
that an edge could not fully use was *discarded* instead of being offered to the edges that
still had data. On the first-round data the defect cost RR ≈43 % of the entire window budget
under DOMINANT (≈315k of ~717k tokens; fresh data on the light edges starved at a mean delay
of 80.7 s). After the fix, RR **redistributes unused quota in turn** (tokens are never
discarded), and the "collapse" disappears:

| DOMINANT (means over 5 seeds) | FIFO | RR | Rate-limit | New-first |
|---|---|---|---|---|
| fresh-data delay mean (s) | 2.17 | 1.96 | 1.24 | 0.07 |
| backfill delay mean (s) | 20.1 | 22.0 | 24.6 | 157.4 |
| backlog peak (units) | 24.8k | 24.9k | 24.7k | 52.3k |
| delivered backfill (units) | 75.7k | 75.7k | 75.7k | 33.3k |

Residual: under DOMINANT, backfill delay is FIFO 20.1 < RR 22.0 (+9 %) < rate-limit 24.6 —
equal-turn sharing still spends budget where it is not needed (light edges) before finally
redistributing it, so the heavy edge waits an extra tick-round. ZIPF is milder still
(backfill 17.5/19.6/20.5 s for FIFO/RR/rate-limit), and fresh data is actually *lower* under
RR (0.79 s) and rate-limit (0.62 s) than under FIFO (1.21 s). The honest conclusion:
with correct budget accounting, **the equal-turn sharing problem is mild under generation
imbalance — the earlier "structural" failure was parametric after all** (budget reuse),
though RR remains consistently a few percent behind the unit-level allocators and (per the
ρ=1.1 sweep) stays the wrong choice under saturation.

**Outage regime — scattered vs. storm (COHORT).** In the base scattered regime a reconnect
typically happens while other edges are idle, so drains are short. In the storm regime,
~50 % of the fleet reconnects within a window of seconds: the scheduler sees several huge
backlogs at once. All three serving policies still complete every backlog-carrying
reconnection inside the horizon (≈32–33 drains counted per run), and round-robin's drains
per spell are even the quickest (drain mean 8.5 s vs FIFO 10.6 s; drain max 24.0 s vs
31.9 s) because several edges drain *concurrently* at partial share instead of one edge
monopolizing the budget. The cost shows up in the delay dimension instead: RR's backfill
mean is 17.6 s vs FIFO 14.8 s. FIFO and rate-limit perform similarly (14.8 / 18.3 s;
rate-limit +23 % as the price of its reservation), and newfirst again starves the backlog
(174 s mean, 79.8k peak — in a storm the 6/tick quota can only chew a fraction of what
arrives).

### 5.3 Fairness (Jain index on per-edge delivered/generated share of backfill)

*This metric is real data as of the fixed revision: in the first round the underlying counter
was never incremented, so the index read a constant 1.000; it now counts actual per-edge
arrivals at the center (delivered/generated ratio per edge; edges that generated no backfill
are excluded, starved edges count with ratio 0).*

| Jain index (mean over seeds) | min across scenarios | max across scenarios |
|---|---|---|
| FIFO / RR / rate-limit | 0.946 | 1.00 |
| new-first | 0.665 (storm) | 0.927 (ρ=0.4) |

Two findings. First, **the index discriminates newfirst very clearly**: under storms its
per-edge completion ratios diverge (0.665 — some edges receive almost no backfill all run),
and it degrades under overload (0.684) and ρ=0.9 (0.736), tightening only under light load
(0.927). Throughput starvation is thus visible in the fairness dimension, not only in the
delay dimension of §5.1/5.2. Second, **the three serving policies are nearly flat at
0.95–1.00 in every regime**: when an edge's backlog is not cut off by the run's tail, the
policy eventually delivers *all* of its accumulated data, so completion ratios equalize even
though the *time* profile differs strongly (§5.1). For backfill scheduling, delay fairness —
not throughput fairness — is the discriminating dimension among serving policies.

### 5.4 Figures

* `analysis/figures/back_delay_mean.png` / `back_delay_p95.png` — backfill latency by scenario.
* `analysis/figures/new_delay_mean.png` (log scale) — fresh-data latency.
* `analysis/figures/backlog_peak.png`, `drain_mean.png` — backlog dynamics.
* `analysis/figures/tick_base.png` / `tick_storm.png` / `tick_rho1.1.png` — per-tick drain
  dynamics and served streams for one representative seed.

## 6. Conclusions

1. **There is no universally best policy; the right choice tracks the bottleneck regime.**
   * Balanced load, outages scattered — **FIFO** has the lowest backlog latency; **RR and
     rate-limit both halve fresh-data latency** for a +16–19 % backlog-delay premium.
   * Overload / storms / saturation — **rate-limit** dominates: lowest backfill latency
     *and* highest backfill throughput, with bounded center headroom; **round-robin is
     the wrong choice here** (stretched per-edge turns → 68 s mean drains) even though
     its own budget use is now correct.
   * Fresh-data latency is mission-critical — **new-first** gives a ~10× improvement at a
     heavy backlog price; raise q above 0.05·C to make it viable for periodic storms.
   * Heterogeneous fleet (ZIPF/dominant generation) — with correct budget reuse, round-robin
     is a reasonable middle (+9 % backlog delay vs FIFO under DOMINANT), not a misfit.
2. **Implementation fidelity matters as much as the policy model.** The first round's
   "round-robin collapses under imbalance" was mostly a bookkeeping defect (discarded
   left-over turn quota, ≈43 % of the budget under DOMINANT, fresh-data starvation at 80 s
   mean). After making grant accounting report *actually consumed* units and redistributing
   the rest, RR tracks the other serving policies at every load level; its genuine residual
   costs are the ρ0.9–1.1 turn-cap stretching (68 s mean drains) and a slightly worse
   backfill delay everywhere (+9–19 % vs FIFO). Fair-comparison frameworks should enforce
   conservation *of the budget*, not only of the data.
3. **Bounded rates beat reactive fairness.** The fixed-R reservation behaves like a control
   knob: below the spare capacity it is invisible; at/beyond saturation it becomes the only
   policy that simultaneously protects fresh data and maximizes backlog throughput.
4. **Quota-based starvation is real — in delay *and* in fairness.** With q = 5 % of C,
   new-first never delivers more than ~45 % of the generated backfill inside the horizon in
   any scenario, and it is the only policy whose per-edge completion ratios visibly diverge
   (Jain 0.66–0.93, §5.3) — quotas must scale with the expected outage-induced backlog
   (e.g. q ≥ backlog/(C·drain-window)).

## 7. Web visual demonstration

`web/index.html` (no dependencies; open in any browser) reproduces the entire model in JS
with the **same four policies on the same engine**, and adds:

* live topology view — per-edge LED (link up/down), fresh/backfill buffer bars, animated
  grant flow towards the center, live token budget;
* three live charts: queued units, granted transmissions, delivery latency (2 s rolling);
* an event log of link drops/repairs with backlog sizes;
* a **batch mode**: `Run 4 policies (same seed)` replays four engines over identical streams
  inside the browser (~0.12 s for a 120 s window) and renders a comparison table + bar
  chart, with one-click CSV export (summary + per-tick series).

Parity with the Java engine is structural — identical tick pipeline, drains, knobs, seeds —
and asserted by `web/test/engine_test.js` (equal-input + conservation invariants; passes for
all outage regimes and imbalances per `web/test/variants_test.js`).

## 8. Reproducibility

```powershell
.\sim\run.ps1 grid -Seeds 5        # full CSV grid, deterministic per seed (200 runs, ~25 min)
.\sim\run.ps1 run  -Policy rr -Scenario base -Seed 0   # single run
python .\analysis\analyze.py       # analysis/tables.md + analysis/figures/*.png
node web\test\engine_test.js       # web engine parity/conservation
node web\test\variants_test.js     # web engine across outage/imbalance variants
java  backfill.run.SelfTest        # Java framework invariants (equal input, conservation,...)
```

Everything is deterministic: a scenario+seed always produces byte-identical arrival and
outage streams for every policy.

## 9. Appendix — full measurement tables

See [`../analysis/tables.md`](../analysis/tables.md).

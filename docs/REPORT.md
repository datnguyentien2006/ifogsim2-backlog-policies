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
  simultaneously and reconnect within a few-seconds window — 15 link-loss events per 600 s run
  (five storms × three edges), each reconnect bringing a backlog that must be drained. The
  storm regime is **storms-only** (`cohortStormsOnly = true`): a reconnected edge stays up until
  a later storm picks it, so the regime is exactly "many edges reconnect together", one outage
  chain per edge, and matches the web mirror).
  In the `SCATTERED` regime every edge has its own drop probability and mean outage duration:
  both ramp linearly around the fleet means (0.01/s, 20 s) — edge 0 drops most and recovers
  fastest, edge N−1 least and slowest (`outageRateSpread = 0.5`, `outageDownSpread = 0.3`),
  so heterogeneous link quality is part of the workload.
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
in round-robin (§5.2, *Imbalance*), and a dead fairness counter (§5.3) — and later made the
SCATTERED outage model per-edge (every edge owns its own drop probability and mean outage
duration, §4), which is what the current data reflects. All 200 runs were regenerated after
each change; the defects changed only RR's quantitative results, the fairness metric now
measures real delivery for every policy, and the per-edge outages add realistic link
heterogeneity without changing any framework invariant. A later revision made the storm
regime **storms-only** (`cohortStormsOnly = true`, §4), removing a duplicated outage chain
that a reconnected storm member could start on its own scattered clock; only the 20 storm runs
were regenerated, every other scenario's numbers are unchanged.*

| metric | FIFO | Round-robin | Rate-limit | New-first |
|---|---|---|---|---|
| fresh-data delay, mean (s) | 0.90 | 0.49 | **0.44** | **0.07** |
| backfill delay, mean (s) | **17.9** | 20.2 | 20.7 | 165.0 |
| backfill delay, p95 (s) | 44.7 | 47.4 | 48.6 | 315.0 |
| backlog peak (units) | **16.3k** | 16.2k | 16.3k | 52.9k |
| mean drain after reconnect (s) | 7.8 | **7.1** | 8.3 | 91.4 |
| delivered backfill (units) | 76.6k | 76.6k | 76.6k | 34.7k |

Reading: under a *fair* workload (all edges symmetric, center not overloaded) the three
serving policies are close, and each has a distinct signature. **FIFO is the best backlog
clearer** in strict latency terms (any unit, first-come). **Round-robin and rate-limit both
halve the fresh-data latency** (0.49/0.44 s vs 0.90 s) at a modest backlog price (+13 % / +16 %
mean backfill delay vs FIFO) — RR because each edge's merged backlog sits behind its own
shared turn, rate-limit because its reserved backfill rate R leaves fresh data the rest of the
budget by construction. RR's per-tick share (120/6 = 20 units/tick) rarely binds at balanced
load — a single edge's post-outage backlog fits a few ticks of its own share — so its drain
mean (7.1 s) even edges out FIFO's (7.8 s). **New-data-first protects fresh data ~12×** at the
price of a backlog that never fully drains (only ~40 % of generated backfill delivered by
cutoff; q = 6 units/tick is simply too small).

### 5.2 Regime sweeps

**Scaling the number of edges (N = 4/10/16, ρ = 0.7).** FIFO, rate-limit *and* (now-fixed)
round-robin all improve monotonically with N: per-edge generation shrinks with 1/N, so each
outage accumulates less backlog (backlog peak 11.9k → 11.0k → 8.6k; FIFO drain mean
9.5 → 4.6 → 3.1 s; RR 9.0 → 4.0 → 2.7 s). With per-edge backlog fitting each edge's turn
share, RR's per-tick turn stops binding and it behaves essentially like FIFO — its fresh-data
delay at N=16 is 0.15 s, the lowest of the serving policies. New-first is essentially
insensitive to N (its quota decides everything).

**Workload vs. center capacity (ρ = 0.4 / 0.7 / 0.9 / 1.1).**
Under light load (ρ=0.4) even newfirst recovers partially (backfill mean 100 s, Jain 0.90 —
its highest); the regime is not stressful. At ρ=0.9 the queues begin to saturate: RR's
per-turn cap starts to bite (27.2 s backfill mean, 63.5 s p95, vs FIFO 19.3/46.3), though its
drains still complete sooner (23.5 s vs FIFO 28.9 s). At **ρ=1.1 the center is permanently
overloaded** and the ranking inverts:

| ρ=1.1 | FIFO | Round-robin | Rate-limit | New-first |
|---|---|---|---|---|
| backfill delay mean (s) | 35.0 | 53.7 | **25.7** | 210.4 |
| backlog peak (units) | 77.3k | 78.6k | 77.3k | 106.5k |
| delivered backfill (units) | 116.6k | 109.8k | **119.9k** | 35.0k |

Rate-limit becomes the best backfill server *and* keeps its fresh-data reservation, and
delivers the most backfill. This is the strongest practical result of the
study: **fixed-rate reservation pays off exactly when the system is closest to or beyond
saturation** — the classic controller-stability trade-off of schedulable-capacity knobs.
Round-robin is the clear loser here: with every edge deep in backlog, each edge must wait for
its slower turns, so individual drains stretch out (drain mean 108 s, max 198 s, vs 30 s
for FIFO/rate-limit) even though RR's *aggregate* throughput stays close to FIFO's
(109.8k vs 116.6k).

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
| fresh-data delay mean (s) | 1.55 | 1.78 | 0.89 | 0.08 |
| backfill delay mean (s) | 16.4 | 18.6 | 21.0 | 163.8 |
| backlog peak (units) | 20.4k | 21.8k | 20.9k | 47.0k |
| delivered backfill (units) | 75.2k | 75.2k | 75.1k | 34.0k |

Residual: under DOMINANT, backfill delay is FIFO 16.4 < RR 18.6 (+13 %) < rate-limit 21.0 —
equal-turn sharing still spends budget where it is not needed (light edges) before finally
redistributing it, so the heavy edge waits an extra tick-round. ZIPF is milder still
(backfill 16.0/18.0/19.1 s for FIFO/RR/rate-limit), and fresh data is actually *lower* under
RR (0.72 s) and rate-limit (0.48 s) than under FIFO (0.99 s). The honest conclusion:
with correct budget accounting, **the equal-turn sharing problem is mild under generation
imbalance — the earlier "structural" failure was parametric after all** (budget reuse),
though RR remains consistently a few percent behind the unit-level allocators and (per the
ρ=1.1 sweep) stays the wrong choice under saturation.

**Outage regime — scattered vs. storm (COHORT).** In the base scattered regime a reconnect
typically happens while other edges are idle, so drains are short. In the storm regime,
~50 % of the fleet reconnects within a window of seconds: the scheduler sees several huge
backlogs at once. Because the storm is storms-only (no scattered drops layered on top), all
three serving policies drain the storm backlog completely inside the horizon (15 drains per
run, `leftBack = 0`), and round-robin's drains per spell are the quickest (drain mean 14.7 s
vs FIFO 17.4 s; drain max 21.2 s vs 23.4 s) because several edges drain *concurrently* at
partial share instead of one edge monopolizing the budget. In this fully-drained regime the
cost shows up in the delay dimension instead: **FIFO now has the lowest backfill latency**
(12.1 s) — with no standing overload, "any unit, first-come" clears the burst fastest — while
RR (14.1 s) and rate-limit (14.9 s, +23 % as the price of its reservation) trade a little
backlog latency for better fresh-data latency (0.38 / 0.32 s vs FIFO 0.56 s). New-first again
starves the backlog (102 s mean, 12.6k peak — it delivers only 74 % of the generated backfill
before cutoff).

### 5.3 Fairness (Jain index on per-edge delivered/generated share of backfill)

*This metric is real data as of the fixed revision: in the first round the underlying counter
was never incremented, so the index read a constant 1.000; it now counts actual per-edge
arrivals at the center (delivered/generated ratio per edge; edges that generated no backfill
are excluded, starved edges count with ratio 0).*

| Jain index (mean over seeds) | min across scenarios | max across scenarios |
|---|---|---|
| FIFO / RR / rate-limit | 0.949 | 1.00 |
| new-first | 0.699 (N16) | 0.901 (ρ=0.4) |

Two findings. First, **the index discriminates newfirst very clearly**: its minimum is at
N16 (0.699 — some edges receive almost no backfill all run), and it degrades under overload
(0.71), N10 (0.74) and ρ=0.9 (0.75), tightening under light load (0.90) and in the storm
regime (0.94, where the smaller fleet-wide backlog drains further). Throughput starvation is
thus visible in the fairness dimension, not only in the delay dimension of §5.1/5.2. Second,
**the three serving policies are nearly flat at 0.95–1.00 in every regime**: when an edge's
backlog is not cut off by the run's tail, the policy eventually delivers *all* of its
accumulated data, so completion ratios equalize even though the *time* profile differs
strongly (§5.1). For backfill scheduling, delay fairness — not throughput fairness — is the
discriminating dimension among serving policies.

### 5.4 Figures

* `analysis/figures/back_delay_mean.png` / `back_delay_p95.png` — backfill latency by scenario.
* `analysis/figures/new_delay_mean.png` (log scale) — fresh-data latency.
* `analysis/figures/backlog_peak.png`, `drain_mean.png` — backlog dynamics.
* `analysis/figures/tick_base.png` / `tick_storm.png` / `tick_rho1.1.png` — per-tick drain
  dynamics and served streams for one representative seed.

## 6. Conclusions

1. **There is no universally best policy; the right choice tracks the bottleneck regime.**
   * Balanced load, outages scattered — **FIFO** has the lowest backlog latency; **RR and
     rate-limit both halve fresh-data latency** for a +13–16 % backlog-delay premium.
   * Overload / saturation (ρ ≥ 0.9) — **rate-limit** dominates: lowest backfill latency
     *and* highest backfill throughput, with bounded center headroom; **round-robin is
     the wrong choice here** (stretched per-edge turns → 108 s mean drains) even though
     its own budget use is now correct.
   * Simultaneous reconnection storms (`COHORT`) — with the storms-only model every serving
     policy drains the burst completely (`leftBack = 0`), so **FIFO has the lowest backlog
     latency** (12.1 s) and **rate-limit/RR trade ~2 s of backlog latency for ~1.8× better
     fresh-data latency** (0.32 / 0.38 s vs 0.56 s). With no standing overload this regime
     rewards the simplest policy, not the fixed-rate reservation.
   * Fresh-data latency is mission-critical — **new-first** gives a ~12× improvement at a
     heavy backlog price; raise q above 0.05·C to make it viable for periodic storms.
   * Heterogeneous fleet (ZIPF/dominant generation) — with correct budget reuse, round-robin
     is a reasonable middle (+13 % backlog delay vs FIFO under DOMINANT), not a misfit.
2. **Implementation fidelity matters as much as the policy model.** The first round's
   "round-robin collapses under imbalance" was mostly a bookkeeping defect (discarded
   left-over turn quota, ≈43 % of the budget under DOMINANT, fresh-data starvation at 80 s
   mean). After making grant accounting report *actually consumed* units and redistributing
   the rest, RR tracks the other serving policies at every load level; its genuine residual
   costs are the ρ0.9–1.1 turn-cap stretching (108 s mean drains at ρ=1.1) and a consistently
   worse backfill delay (+7–18 % vs FIFO in every balanced regime, +41–53 % at ρ0.9–1.1).
   Fair-comparison frameworks should enforce conservation *of the budget*, not only of the
   data.
3. **Bounded rates beat reactive fairness.** The fixed-R reservation behaves like a control
   knob: below the spare capacity it is invisible; at/beyond saturation it becomes the only
   policy that simultaneously protects fresh data and maximizes backlog throughput.
4. **Quota-based starvation is real — in delay *and* in fairness.** With q = 5 % of C,
   new-first falls well short of draining the generated backfill inside the horizon whenever the
   center is loaded (26 % at ρ=1.1, 31 % at ρ=0.9, and 42–46 % under the N sweeps); under milder
   loads it recovers more (41–42 % under ZIPF/DOMINANT, 64 % at ρ=0.4), and in the storms-only
   regime it reaches 74 % because the outage load is lightest there — it is the only policy whose
   per-edge completion ratios visibly diverge (Jain 0.70–0.90, §5.3) — quotas must scale with the
   expected outage-induced backlog (e.g. q ≥ backlog/(C·drain-window)).

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

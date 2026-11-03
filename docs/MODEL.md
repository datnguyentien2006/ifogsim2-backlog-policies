# Simulation model

This document specifies the backfill scheduling model shared by the Java/iFogSim2 framework
and the web visual mirror. Both implementations follow it line by line.

## 1. System

* `N` edge servers, each collecting fresh measurement data and sending it to **one
  receiving center** over a shared uplink that only has services the center's capacity `C`
  (units/second).
* Each edge stays connected to the center except during **link outages**. Data that is
  generated while the link is down cannot leave the edge: it waits in an *accumulated
  buffer* (the "backlog") and must be sent later.

## 2. Discrete unit model

* **1 unit = 1 tuple worth of measurement data** (in the Java code: one stream
  transmission through iFogSim2's North pipe; 1000 B network size, 10 MI CPU at the center).
* Fresh data arrives at edge *i* as a **Poisson process with rate λ_i**, with
  `Σ λ_i = ρ·C` where `ρ` is the workload factor. `ρ < 1` means the center can, on average,
  serve everything; `ρ > 1` means regular overload even without outages.
* Per-edge imbalance of the generation rates:

  | mode        | λ_i                                        |
  |-------------|--------------------------------------------|
  | `EVEN`      | `ρ·C / N` (all edges equal)                |
  | `ZIPF`      | proportional to `1/(1+i)^α` (default α=1)   |
  | `DOMINANT`  | edge 0 has share 0.6, the rest split evenly |

## 3. Data taxonomy (the core idea)

At generation time `t_gen`, a unit is classified **by the state of its edge's link**:

* **new data** — generated while the link is **up**: goes to the edge's *fresh buffer* `Qn`;
* **backfill data** — generated while the link is **down**: goes to the edge's
  *accumulated buffer* `Qb`.

Both are identical once generated; only the **scheduling policy** decides how the middle
line between them is drawn during transmission. There is no service differentiation other
than what the policy grants.

## 4. Transmission mechanics

* The center's capacity is honored with a **token bucket**: the scheduler receives
  `⌊C·ΔT⌋` tokens per decision tick, `ΔT = 0.1 s` ⇒ 120 tokens/tick at C=1200 u/s.
* All egress is executed by the scheduler: an edge's `sendUp()` **only buffers**. The
  scheduler calls the active *policy* with a set of IMMUTABLE queue views
  (`head-of-line generation times + counts + link state`) of all edges, and the policy
  decides which edge sends what (`tagging new`/`backfill`). The framework then executes
  exactly those grants through iFogSim2's native North-pipe transmission (0.2 ms/tuple at
  5 Mb/s + 10 ms latency, RELEASE events reuse upstream `updateNorthTupleQueue` chain).
* The **scheduler never overrides** the policy; policies never touch the transmission path.
  This separation is what makes the policies *comparable*.

## 5. Link outage model

| mode        | behavior |
|-------------|----------|
| `SCATTERED` | every edge independently: up-spells are exponential (Poisson drop rate 0.01/s), down-spells are lognormal with mean 20 s and cv 0.8 (heavy-tailed: rare long outages). |
| `COHORT`    | ("storm") every 120 s a random 50% of the currently connected edges drop **at the same instant**; each restores after a lognormal with mean 20 s and **cv 0.15** — i.e. several edges reconnect within a short window, dumping their backlog on the scheduler at once. |

Outage streams are seeded independently of arrival streams so the same
(scenario, seed) pair gives **identical arrival streams and identical outage timelines**
to all four policies (fairness requirement).

## 6. Policies

Each policy gets `tokens` (⌊C·ΔT⌋) + queue views + its immutable knobs and decides:

| key         | name                     | semantics |
|-------------|--------------------------|-----------|
| `fifo`      | FIFO (generation order)  | Strict global order across ALL queues of connected edges: whichever unit was generated first is served first. After a reconnect, the huge backlog is at the head, so **fresh data queues behind the backlog** until it drains. |
| `rr`        | Round-robin              | The per-tick budget IS split evenly, in turn, over connected edges holding data (disconnected/empty edges are skipped); a rotating pointer prevents systematic favoritism. Each edge drains its own merged queue oldest-first. Quota left unused by an edge (its queues held fewer units than its share) is **not discarded** — it is passed on, in turn, to edges that still hold data. Otherwise fair per edge, but the per-tick share limits the drain rate on a reconnecting edge. |
| `ratelimit` | Rate-limit (fixed R)     | **Backfill transmits at a fixed rate R = rateLimitFactor·C, regardless of backlog size.** The rate is reserved each tick (oldest backfill first); whichever units R does not consume are given to fresh data (oldest-first). The backlog drains in exactly `B/R` seconds — predictable, and the center keeps at least (1−f) of C free during drains. Default R-factor: 0.5 → R = 600 u/s. |
| `newfirst`  | New-data-first (quota q) | Fresh data has absolute priority; the backlog may transmit only a small fixed quota q = quotaFactor·C per tick (5% → 60 u/s), reserved before the fresh stream so the quota survives a fresh-data flood. Fresh latency is excellent; the backlog may essentially never drain under moderate outages. |

All four obey the same rules: never grant to a disconnected edge; never grant more than
the tokens allow; ordering inside a bucket is global generation order (FIFO).

## 7. Metrics (per run and per tick)

Per 0.1 s tick: `grantedNew`, `grantedBack`, per-edge queue sizes, connected flags.
Per run:

* `genNew/genBack, delivNew/delivBack, left*: generated / delivered / still buffered counts.
* delays (delivery time − generation time): mean, p50, p95, p99 per kind.
* `backlogArea` (units·time), `backlogPeak`, outages, restoresWithBacklog, drain statistics.
* Jain fairness over per-edge delivered/generated **ratios** of accumulated data (edges that
  generated no backfill are excluded; a starved edge contributes ratio 0 and stays in the
  sample; if nothing was delivered at all the index reports 1.0 — "nothing to differentiate").

**Measurement conventions** (consistent across all four policies, so comparisons are valid):

* The per-tick snapshot (and hence `backlogArea`/`backlogPeak`) is taken **after** the tick's
  allocation — up to `tokens` (120) granted units are already popped from the queues when the
  snapshot runs, so the peak is at most ~120 units higher in reality. Same bias for every
  policy → same offset in every comparison.
* The `outages` counter spans the **whole simulated time, including the 0–50 s warmup**
  (links begin cycling before the first data unit exists); `restoresWithBacklog` is always 0
  during warmup, so the drain statistics are unaffected.
* **Drain timer**: an edge's drain clock starts at each restore-with-backlog; if the edge
  drops again before its backlog empties, the later restore **overwrites** `drainStart` —
  recorded drain durations always run from the most recent `LINK_UP` that started a drain to
  the tick at which the edge's backlog first hits zero (partially drained spells are not
  measured separately).

**Cutoff semantics**: the simulation stops at `warmup + simDuration + 5 s`; since a heavy
tailed outage spell may still be in progress at cutoff, `leftBack = backlogPeak` may happen
(that backlog never got to transmit — semantically correct).

## 8. Defaults

| knob            | value   |
|-----------------|---------|
| `N`             | 6       |
| `ρ`             | 0.7     |
| `C`             | 1200 u/s|
| `ΔT`            | 0.1 s   |
| loss rate       | 0.01/s per edge |
| down spells     | lognormal(20 s, cv 0.8) (scattered) / (20 s, cv 0.15) (cohort) |
| storm timeframe | every 120 s, 50% of edges (cohort only) |
| warmup          | 5 s (web) / 50 s (Java/iFogSim2 engine bootstrap) |
| data window     | 600 s (Java grid) / 300 s default (web) |
| seed base       | 42 (Java); per-sensor seeding = base·1000003 + i·131 + 17 |

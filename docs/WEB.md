# BackfillSched — web visual demonstator guide

`web/` is a **dependency-free visual mirror of the Java/iFogSim2 simulation** — open
`web/index.html` in any browser (no server, no build, no npm; just `file://`). It reproduces
the same model, the same four policies and the same scheduling pipeline as `sim/`, and adds
live animation, charts, an in-browser batch comparison and CSV export.

```
web/
  index.html            page layout: controls | topology + charts
  style.css             styling (light theme, cards, charts)
  engine.js             the simulation engine (deterministic, headless-capable)
  app.js                UI wiring: animation, charts, batch mode, CSV export
  test/engine_test.js   headless parity + conservation test   (node web/test/engine_test.js)
  test/variants_test.js headless test across outage/imbalance variants
```

---

## 1. Layout at a glance

| area | what you see |
|---|---|
| **left column** | three cards: *Scenario* (model knobs), *Policy* (selector + description), *Playback* (buttons + live stats). Below: *Batch compare + export* |
| **right column** | *Topology* canvas, three chart canvases (queued units / served rates / delivery latency), and a scrolling *Link events* log |

Everything updates live while the simulation runs.

---

## 2. Scenario card (model knobs)

Each selector maps 1-to-1 to a `SimParams` field in the Java framework:

| control | values | meaning |
|---|---|---|
| **Edge servers N** | 4, 6, 10, 16 | number of edge servers around the receiving center |
| **Load ρ** | 0.4, 0.7, 0.9, 1.1 | total fresh-data rate as a fraction of center capacity `C = 1200 u/s` (ρ = Σλᵢ/C). 0.7 = nominal; 1.1 = permanently overloaded |
| **Backlog imbalance** | EVEN / ZIPF / DOMINANT | how the fresh-data rate is split among edges: even shares; ZIPF(α, editable when selected, default α=1) skew; DOMINANT (edge-0 emits 60 % of all data) |
| **Outage regime** | SCATTERED / COHORT | SCATTERED: each edge independently drops (Poisson, rate 0.01/s) with lognormal outages (mean 20 s, cv 0.8 — heavy-tailed). COHORT: "storm" every 120 s drops ~50 % of the connected edges *at the same instant*, reconnected within a short window (cv 0.15) |
| **Seed** | integer | base of all random streams. Same seed + same scenario = **identical arrival streams and outage timelines for every policy** (the fairness guarantee) |
| **Data window** | 120 / 300 / 600 s | simulated duration of data generation after a 5 s warmup (during warmup only the links cycle; no data) |

Changing any control **resets** the simulation deterministically.

## 3. Policy card

Pick one of the four comparison policies. The description line under the selector is the
policy's own semantics statement:

| key | name | rule |
|---|---|---|
| `fifo` | **FIFO** | strict global generation order over *all* queues of connected edges — after a reconnect, the huge accumulated backlog is at the head, so fresh data queues behind it until the backlog drains |
| `rr` | **Round-robin** | each tick the budget is split evenly, in turn, over the connected edges that hold data (empty/down edges are skipped); each server drains its own merged queue oldest-first; a rotating cursor prevents systematic favoritism. Quota an edge could not use (its queues held fewer units than its share) is **redistributed in turn** to edges that still hold data, never discarded |
| `ratelimit` | **Rate-limit (R fixed)** | backfill transmits at a *fixed rate* `R = rateLimitFactor·C = 600 u/s` (per-project `rateLimitTokensPerTick = 0.5·C·δT = 60` units/tick) **regardless of how big the backlog is**; whatever R does not use flows to fresh data (oldest first) |
| `newfirst` | **New-data-first (quota q)** | fresh data first; the backlog may transmit only a small fixed quota `q = 0.05·C` (6 units/tick), reserved before the fresh stream so the quota survives a fresh-data flood |

The mechanics are identical for all of them — a per-tick token budget of
`round(C·ΔT) = 120` units (ΔT = 0.1 s decision tick), never granting to disconnected edges,
thinking strictly in units — only the *decision* differs. That is exactly the Java contract:
**the scheduler executes policy decisions verbatim; policies never touch the transmission path.**

## 4. Playback card

- **▶ Play / ⏸ Pause** — run or freeze the animation.
- **Step tick** — advance exactly one decision tick (ΔT = 0.1 s) and redraw.
- **Reset** — rebuild the engine with the current knobs.
- **Speed** — simulated time per real second: *1×* (teaching pace, ~0.1 s sim/s), *20×*, *100×*
  (fast-forward). Playback stops automatically at the end of the data window.
- **Live stats** (updated every frame):
  - `sim time` — current simulation clock / end of window
  - `delivered` + *new* / *backfill* split, `queued now`, `backlog peak`
  - `outages` — link-down events so far and how many reconnections carried_backlog
  - `policy rule` — a one-line description of the active policy.

The engine itself is deterministic per (scenario, seed): the web demo replays the same
*kind* of streams as the Java runs (identical seeding structure; different RNG
implementation, so numbers match in structure, not byte-for-byte — see §7).

## 5. Topology canvas

- Each **edge server** is a card with:
  - the edge name and a **link LED**: green = connected, red = LINK DOWN (dashed red line);
  - a **blue bar** = fresh buffer (`Qn`, generated while the link was up) and an
    **orange bar** = accumulated buffer (`Qb`, generated while the link was down);
    bars are log-scaled for visibility;
  - small numeric labels under the bars.
- Illustrator arrows: data flows from each edge to the **receiving center** (right-hand
  green box), which shows its fixed capacity (`1200 u/s`, budget `120/tick`) and how many
  units were granted this tick.
- **Animated grant dots** travel along the link whenever the policy grants — **blue** dots =
  fresh units, **orange** dots = backfill units. At high speeds the flow looks like a solid
  train; at 1× you can follow single bursts. When an edge's links goes down its dots stop.
- During a storm (COHORT mode) you see a whole cluster of red LEDs flip simultaneously —
  the motivation for studying *reconnection bursts*.

## 6. Charts (live)

1. **Queued units** (log-free line chart) — total queued units at the edge servers
  (purple, filled) with the backfill share highlighted (orange). The shaded **red
  background zones** mark instants when *any* edge is disconnected — you can see the
  backlog builds sync perfectly with the outage spells, and how each policy drains them.
2. **Granted transmissions** — units the policy granted per 0.1 s tick, stacked per kind
  (blue = fresh, orange = backfill). For rate-limit you literally see a **flat orange band
  at 60/tick** while backlog exists — the fixed-rate signature. For newfirst: a tiny orange
  ribbon of 6/tick. For FIFO/rr: varying mix following the oldest-head.
3. **Delivery latency** — 2 s rolling mean of delays of units freshly delivered
  (green = fresh, red = backfill). This makes regimes visible at a glance: FIFO's
  green curve spikes right after each reconnect (fresh waits behind drain), rate-limit
  keeps both curves lower, newfirst gives a flat green line near 0 and an *exploding* red line.
  The batch table's `Jain` column is the Java-parity fairness index: per-edge
  delivered/generated **ratios** of accumulated data (starved edges count with ratio 0).

A useful detail: all charts show the full run from tick 0 (including the 5 s
warm-up with no data), so x-axes are directly comparable to the Java `*_tick.csv`.

## 7. Link events log

Every link drop / reconnection gets one line: `t=75.9  edge-3  reconnected · 2,554 units to
backfill`. Green lines = restorations (with the queued backlog size at rejoin), red lines =
drops. The backlog number at rejoin is what the flood is about to fight over.

## 8. Batch compare + export card

- **Run 4 policies (same seed)** — runs four complete **headless** engines (one per policy)
  over *identical* inputs (same config, same seed). It is fast (~0.12 s for a 120 s window,
  ~0.3 s for 300 s) because there is no rendering. The table shows, per policy:
  `delivNew, delivBack, newDelay mean, backDelay mean/p95, backlog peak, drains (mean, max),
  Jain fairness`. Below the table, a grouped bar chart plots
  `newDelay mean` / `backDelay mean` / `backlogPeak/1000` side-by-side per policy.
- **CSV** — exports the four summary rows exactly in the Java `_run.csv` column format
  (`policy, n, rho, ..., jainBackfill`) as `compare_<scenario>_s<seed>.csv`.
- **Tick CSV** — exports the per-tick series of the current (animated) run
  (`t, grantedNew, grantedBack, down, backlogNewTot, backlogBackTot`), i.e. the web mirror
  of the Java `*_tick.csv`.

## 9. How the engine maps to the Java framework (parity contract)

| Java element (`sim/src/backfill/...`) | web mirror (`web/engine.js`) |
|---|---|
| `SimParams` fields + `rateOfEdge` | ``defaultConfig()` + `rateOfEdge`` (EVEN/ZIPF/DOMINANT) |
| `ExponentialDistribution` per-sensor seeding | `Rng(seed·1000003 + i·131 + 17)` per edge — same seeding structure |
| `EdgeServerDevice` (newQ / backQ, link up/down) | `EdgeQueue` class — same two buffers + queue heads |
| `SchedulingPolicy.drainOldestGlobal / drainEdgeMerged` | literally re-implemented the same helpers |
| the four `*Policy.java` classes | `Policies.*.make()` — same reservation/quota math |
| `SchedulerEntity.doTick()` pipeline | `Engine.tick()` — link events → generation → flush arrivals → grants → snapshot |
| `OutageController` (SCATTERED/COHORT state machine) | same spell draws: exponential up-spells, lognormal down-spells (cv per mode) |
| `UnitRegistry` summary columns | `Engine.summary()` — same column names |

Parity is verified by the two node tests (`web/test/…`): they assert that all four policies
see **identical generations and outage counts** and that **conservation holds** (delivered +
left = generated, for both kinds) across many scenarios and both outage modes. Determinism
is intentional: two plays of the same configuration and seed are byte-identical on the web
side; `Rng` is a mulberry32 PRNG (different from java.util.Random, but seeded with the same
structure), so the **web** and **Java** runs are structurally identical but not byte-identical
numerically.

## 10. Suggested demos (matches the REPORT conclusions)

| to show | set |
|---|---|
| FIFO vs rate-limit on a reconnect burst | N=6, ρ=0.7, EVEN, SCATTERED, one seed; search an outage where a big backlog (20k+) restored to you will see FIFO park fresh data behind the drain while ratelimit keeps the fresh band full |
| the flat backfill rate of rate-limit | run the same config at speed 20× and watch chart 2: orange band at 60/tick while backlog > 0 |
| quota starvation (newfirst) | same config, policy `newfirst`: backlog ramps almost linearly all run (chart 1) |
| the reconnection storm | outage regime COHORT: at every t = 120, 240,… ~half of the LEDs flip red at once and several backlogs peak together; batch-compare FIFO/draining vs RR's stretched turns |
| overload | ρ = 1.1: everything saturates; batch-compare shows rate-limit with the lowest backfill delay *and* the highest delivered backfill |
| heterogeneous fleet | imbalance = DOMINANT with the batch-compare card: round-robin's fresh delay explodes (~69 s mean) while FIFO/rate-limit stay at ~1–2 s |

## 11. Files — at a glance

* **index.html** — semantic structure; also holds the read-only model footer (C, ΔT, warmup,
  outage params) for reference during a demo.
* **style.css** — theme tokens (`--accent: green` for connectivity), card grid, LEDs/bars are
  drawn inside canvases, so most CSS is layout.
* **engine.js** — `Rng`, `EdgeQueue`, the four policies (with the Java helper twins),
  `Engine` (event state machine + registry + summary/percentiles/Jain). ~420 lines, no DOM.
* **app.js** — reads the controls → builds `Engine`; animation loop (play/step/speed);
  topology + chart renderers (pure canvas); event log; batch mode; exports. No frameworks.

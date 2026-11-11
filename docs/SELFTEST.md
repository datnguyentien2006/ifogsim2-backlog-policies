# Framework self-test (`backfill.run.SelfTest`)

The self-test is the framework's executable specification. It verifies, without any JUnit
dependency, that the four policies are compared fairly and that the measurement pipeline is
sound. It is organized in **three independent layers**, each catching a different class of bug.

Run it with the project driver, or directly:

```powershell
.\sim\run.ps1 test                  # default mode (A + B on base + C), ~1 min
java backfill.run.SelfTest fast     # part A only, < 1 s
java backfill.run.SelfTest full     # default + B on storm / overload / dominant scenarios
```

Exit code `0` = all hard checks passed; `1` = failure (the run stops at the first failing
check); `2` = bad usage. Qualitative policy-ordering expectations only print `WARN` and never
fail the build.

## Part A — policy contract (synthetic queues, no simulation)

Feeds hand-made and random queue states straight into every policy registered in
`SchedulingPolicy.standardPolicies()` — a newly added policy is picked up automatically.

* **A1 — reconnect-burst worked example.** A fixed queue state (edge 0 reconnected with 500
  backlog units, edges 1–3 hold 200 fresh units each) with exact expected `(backfill, new)`
  splits per policy: `fifo (120,0)`, `rr (30,90)`, `ratelimit (60,60)`, `newfirst (6,114)`.
  Also pins the default knobs (tokens/R/q = 120/60/6 per tick) so a knob change cannot silently
  invalidate the numbers.
* **A2 — randomized contract** (2000 seeded states × every policy): the generic contract that
  `SchedulingPolicy` documents, plus the exact allocation rule of each standard policy:
  - never grant to a **disconnected** edge (the `Recorder` sink throws);
  - never exceed the **per-tick token budget**;
  - never **over-grant** a queue (grant ≤ units the view reported);
  - **oldest-first order** inside every queue, and the exact split rule for
    `fifo` (global generation order, work-conserving), `ratelimit` (R reserved for backfill,
    remainder to fresh data), `newfirst` (fixed quota q reserved for backfill), `rr`
    (even share in turn, work-conserving, no edge starved while tokens ≥ active edges).

## Part B — end-to-end invariants (real iFogSim2 runs)

Runs all four policies on the same scenario + seed (default mode: a shortened base case,
N=4, 90 s window, seed 5; `full` adds storm, ρ=1.1 and DOMINANT cases) and checks:

| # | invariant | what it catches |
|---|-----------|-----------------|
| B1 | **Equal inputs** — `genNew/genBack`, `outages`, `restoresWithBacklog`, `dataStart/dataEnd`, `N`, `seed` identical across the 4 policies | a policy-specific RNG or a seeded event that leaks into one policy's timeline |
| B2 | **Conservation** — `delivered + left = generated`, separately for new data and backfill | dropped / duplicated units in the grant → transmit → arrive chain |
| B3 | **Capacity bound** — delivered units ≤ `C × (dataEnd − dataStart)` + one tick of tokens | a scheduler that lets the center absorb more than its token bucket |
| B4 | **Policy separation** — at least one policy differs in `delivBack` or `backDelayMean` | a wiring bug that runs the same policy twice |
| B5 | **Predictable rates** — per tick, ratelimit sends ≤ R backfill units, newfirst ≤ q; every policy ≤ tokens; ratelimit/newfirst actually **reach** their cap (otherwise the scenario is too tame and the check would be vacuous) | a policy that ignores its own knob |
| B6 | **Grant/arrival closure + time-series sanity** — nothing arrives that was not granted; tick rows are evenly spaced at `decisionInterval`; `backlogTot = new + back`; delivered ≤ granted totals (re-grants after a link loss may inflate granted, never deflate) | units teleporting past the grant bookkeeping, or a corrupt tick CSV |

Soft (WARN-only) expectations: `newfirst` fresh delay ≤ `fifo`'s, and `newfirst` does not
drain more backlog than `fifo`. Backfill *delay* is deliberately not used for soft ordering:
`backDelayMean` averages over delivered units only, so a starved policy (newfirst) would look
fast by survivorship bias.

## Part C — determinism replay

Runs the same `(scenario, policy, seed)` twice **in the same JVM** (round-robin, the most
state-sensitive policy because of its rotating cursor) and asserts the two `_run.csv` and
`_tick.csv` outputs are **byte-identical** (`Files.mismatch == -1`). Byte-identical replay
across separate JVM invocations is additionally verified by re-running any cell of
`results/runs/` (see README → Reproducibility).

## What the self-test does NOT cover

* Performance/energy metrics of upstream iFogSim2 (power model numbers are plumbing only).
* The web mirror — it has its own tests: `node web/test/engine_test.js` (parity + conservation
  on one storm-heavy window) and `node web/test/variants_test.js` (COHORT/SCATTERED ×
  EVEN/ZIPF/DOMINANT × ρ × N). Both assert the same equal-input + conservation invariants as
  Part B, so the JS engine stays a faithful mirror of the Java one.

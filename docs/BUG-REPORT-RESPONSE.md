# Independent verification of the 15-item bug report

A third-party review produced a 15-item bug report against this repository. Every item was
**re-verified independently against the actual code and measured data** before acting:
correct findings were fixed (with re-verification), incorrect/overstated ones are rebutted
technically below. Verdicts: **Confirmed** — real bug, fixed; **Confirmed (only C-group)**
— real observation, addressed by docs or tooling; **Rebutted (partially)** — mechanism is
real but the reviewer's stated magnitude is wrong.

## Group A — claimed "real bugs"

### #1 `jainBackfill ≡ 1.0` (dead metric) — CONFIRMED, fixed

*Evidence.* `UnitRegistry.onArrived()` incremented `deliveredNew`/`deliveredBack` and the
delay samples but **never touched `deliveredBackA[]`**; `jainDeliveredBack()` reads
`deliveredBackA[i] / genBackEdgeA[i]`, so every ratio was 0, `sum == 0` and the function
short-circuited to 1.0. Empirical check over the whole pre-fix grid: **all 200 summary rows
had `jainBackfill == 1.000000`** — not one row differed.

*Fix.* `deliveredBackA[u.edgeIndex]++` in the backfill branch of `onArrived()`.

*Post-fix reality (5 seeds per value).* The reviewer's predicted values came from a
different patched engine and do not transfer; the real measured ranges are:
the three serving policies (FIFO/RR/rate-limit) sit at **0.946–1.000** in every scenario
(they finish their backlogs before they finish the run, so completion ratios equalize);
newfirst is the discriminator at **0.665** (storm) up to **0.927** (ρ=0.4). See REPORT §5.3.

### #2 RR wastes tokens and does not redistribute — CONFIRMED, fixed (magnitude larger than claimed)

*Evidence.* `SchedulingPolicy.drainEdgeMerged` was `void`; `RoundRobinPolicy.allocate`
subtracted the requested `take` from `remaining` even when the edge's queues held fewer
units than its share. Leftover budget per tick vanished.

*Measured magnitude on the OLD code, before the fix (seed 0, 600 s window, budget
≈120 tokens/tick):*

| scenario | budget tokens unused by RR | share of window budget |
|---|---|---|
| imbalance_dominant | ≈315k (132k fresh + 25k backfill units extra delivered after fix) | **≈43 %** |
| imbalance_zipf | ≈130k | ≈18 % |
| base | ≈2k | ≈0.3 % |
| storm | ≈8k | ≈1.2 % |

The reviewer's direction was right; their "~33 % / 157k tokens on dominant" slightly
*understates* our measurement (43 %), and their "base ≈0.6 % waste" matches our 0.3 %
(maybe a different seed/window).

*Fix.* `drainEdgeMerged` now **returns the granted count** and `RoundRobinPolicy`
redistributes leftover quota, in turn, to the edges that still hold data (multi-pass loop;
tokens are never discarded). Mirrored identically in `web/engine.js` (the reviewer did not
mention that the *web engine had the same defect* — also fixed).

*Effect.* Same dominant run after the fix: fresh-data mean delay **80.75 → 0.71 s**,
undrainable backlog halved (51,302 → 26,069 units). The whole 200-run grid was regenerated;
the "RR collapses under imbalance" conclusion of the old report has been revised (see
REPORT §5.2): residual RR costs are real but mild (+9 % backfill delay under DOMINANT).

### #3 web jain formula mismatch — CONFIRMED, fixed

*Evidence.* `engine.js jainBackfill()` used raw delivered counts filtered `x > 0`,
excluding starved edges entirely. Java uses per-edge ratios `delivered/generated` over
edges that generated backfill (ratio 0 = starving edge stays in the sample).

*Fix.* Track `e.totalBack` (already present) per edge; compute the Java formula.
Additionally the Java version returns 1.0 when nothing at all was delivered (edge case
parity). Web tests (`engine_test.js`, `variants_test.js`) pass after the change.

### #4 OOM at ρ=1.1 with small default heap — CONFIRMED (operational), fixed

*Evidence.* Neither `sim/run.ps1` nor the `ExperimentRunner` ProcessBuilder set a heap
size; the JVM default is ¼ of physical RAM (~1 GB on a 4 GB machine). ρ1.1@600 s holds
~633k unit objects plus per-tick buffers — tight on such a machine.

*Fix.* `-Xmx2g` in `run.ps1` (overridable via `BACKFILL_JVM`) and in the ExperimentRunner
child-process command. Note: the first attempt exposed a PowerShell quirk —
`$jvm = if (...) { } else { @("-Xmx2g") }` unwraps a single-element array to a string, and
splatting `@jvm` a string feeds the JVM its *characters* ("Unrecognized option: -"); the
final assignment avoids the unwrap.

## Group B — hardening items

### #5 total grants ≤ tokens not asserted — CONFIRMED, fixed

`SchedulerEntity` validated each `grant()` per queue but nothing bounded the *sum*. A new
post-`allocate()` assertion fails fast if `counts[new] + counts[back] > tokens`. All
current policies comply; the guard protects future policies.

### #6 AllocationSink javadoc contradiction — CONFIRMED, fixed

The javadoc claimed "No-op for units that no longer exist"; the scheduler's sink throws
`IllegalStateException` on over-grant. Javadoc rewritten to state the real contract
(fail-fast on over-grant; grants must be sized to the view).

### #7 backlogPeak/backlogArea recorded after allocation — CONFIRMED as an observation, documented

True: the snapshot runs after the tick's grants pop up to 120 units, so peak/area
underestimate by at most ~120 units (0.16 % of the base peak of 75k, far below run-to-run
variance). Deliberately **not** "fixed" (moved before allocation) because that would shift
the definition of two published metrics; it is now documented in `docs/MODEL.md`
("Measurement conventions") so the ≤120 bias is explicit and policy-invariant. The jTable/net
effect on all cross-policy comparisons is identical because every policy uses the same
snapshot point.

### #8 `outages` column includes the 0–50 s warmup — CONFIRMED as an observation, documented

True: `onLinkChange` counts link drops from simulated time 0, before the first unit. The
counter is consistent across policies and the drain statistics are unaffected
(restore-with-backlog cannot occur during warmup). Documented in `docs/MODEL.md` rather than
changed, for comparability with all published runs.

### #9 drain timer restart on re-drop — CONFIRMED as designed behavior, documented

`drainStart` is overwritten by a later restore: durations measure only *successful,
uninterrupted* drains from the most recent restore to zero-backlog. The code already said
so in a comment; `docs/MODEL.md` now documents it in the measurement conventions.

### #10 SelfTest weak guards — CONFIRMED, fixed

Two changes: (a) the equal-input check compared rows by *policy name* — it now iterates by
index, always compares rows 2–4 against row 1, and additionally asserts **same scenario +
seed across policies**; (b) the capacity bound used the unbounded slack `+ (gBack − dBack)`;
tightened to the principled bound `C·(dataEnd − dataStart) + tokens` (one tick of tokens
slack — every delivered unit must have consumed a token issued inside the data window; no
data exists during warmup). SelfTest passes on JDK 21.

### #11 `transmitUnit` missing `NetworkUsageMonitor.sendingTuple()` — CONFIRMED, fixed

The stock `sendUpFreeLink`/`sendThroughFreeClusterLink` paths call
`NetworkUsageMonitor.sendingTuple(latency, size)`; our intercepted path did not (verified:
grep over the vendor shows the calls in `FogDevice` lines 868/899/1173, none in
`backfill/`). `EdgeServerDevice.transmitUnit` now calls it with the uplink latency and unit
size (cheap in-memory accounting, no I/O).

### #12 RunMain javadoc mislabels arg 4 — CONFIRMED, fixed

The `<pre>` usage block said `[seedOffsetOnSimParams]` for the 5th argument; the runtime
usage string and code all use `runNameOverride`. Javadoc corrected.

## Group C — environment / presentation notes

### #13 Windows-only scripts — CONFIRMED, addressed

`sim/build.sh` + `sim/run.sh` now mirror `build.ps1`/`run.ps1` for Linux/macOS (JDK 21
detected via `JAVA_HOME`, same commands, same `-Xmx2g` default). Documented in README.

### #14 JDK 21 must be installed manually — CORRECT, no action needed

By design: the upstream iFogSim2 uses `SequencedCollection`/`List.getLast()` (JDK 21+), and
bundling a JDK is not appropriate for a course repo (500+ MB, license diversa from the
code). README documents the requirement, points at Temurin 21, and the new `build.sh`
verifies the toolchain with a clear error message.

### #15 LargestBacklogFirst is example-only — CORRECT, clarified

It is the worked example of `docs/ADD_POLICY.md`, and the document never claimed otherwise.
A sentence was added to make it explicit (not part of the four-policy comparison, not
compiled into the grid).

## Summary

| # | verdict | action |
|---|---|---|
| 1 | Confirmed | fixed (`deliveredBackA++`), metric now live |
| 2 | Confirmed — magnitude **larger** than claimed (43 % vs 33 %) | fixed (grant returns consumed; RR redistributes; web too); grid regenerated |
| 3 | Confirmed | fixed (ratio formula + starved-edge retention) |
| 4 | Confirmed (operational) | fixed (`-Xmx2g` + override) |
| 5 | Confirmed | fixed (total-grant assertion) |
| 6 | Confirmed | fixed (javadoc) |
| 7 | Confirmed (observation) | documented in MODEL.md |
| 8 | Confirmed (observation) | documented in MODEL.md |
| 9 | Confirmed (observation) | documented in MODEL.md |
| 10 | Confirmed | fixed (explicit guards, tight capacity bound) |
| 11 | Confirmed | fixed (`sendingTuple` call) |
| 12 | Confirmed | fixed (javadoc) |
| 13 | Correct | addressed (bash wrappers) |
| 14 | Correct | no action (by design); documented |
| 15 | Correct | clarified in ADD_POLICY.md |

**One correction to the reviewer's claims:** their quantifications (#1 "0.59–0.81 / 0.750";
#2 "324,639/481,970") come from a *separately patched engine*, not this repository — the
mechanisms are correct, but the exact numbers do not match this codebase's real post-fix
measurements (this report and `analysis/tables.md` carry the authoritative values).

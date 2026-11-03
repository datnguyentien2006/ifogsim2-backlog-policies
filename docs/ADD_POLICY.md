# Adding a new policy

## 1. Where policies live

```
sim/src/backfill/policy/
    SchedulingPolicy.java   <- the interface + shared drain helpers
    FifoPolicy.java, RoundRobinPolicy.java, RateLimitPolicy.java, NewFirstPolicy.java
```

A policy is a pure **decision-maker**. It sees only:

* `double now` — current sim time;
* `double tokens` — the center budget for this tick (`⌊C·ΔT⌋`, identical for all policies);
* `List<EdgeQueueView> edges` — one immutable view per edge: link state, first-element
  generation times and counts of the fresh/accumulated buffers;
* `AllocationSink sink` — call `sink.grant(edgeIndex, isNew, count)` to give units out;
* `SimParams params` — your policy's knobs.

It must **never** touch transmission, event queues or state outside itself. The scheduler
(`backfill/controllers/SchedulerEntity`) executes exactly what the policy granted and
(validates that a grant never exceeds what the view reported).

## 2. Minimal policy

```java
package backfill.policy;

import java.util.List;
import backfill.SimParams;

/** Shortest-backlog-first: serve the connected edge whose backlog is largest. */
public class LargestBacklogFirst implements SchedulingPolicy {

    @Override public String key() { return "largest"; }
    @Override public String displayName() { return "Largest-backlog-first"; }
    @Override public String description() {
        return "Grants go to the connected edge with the largest accumulated backlog.";
    }

    @Override
    public void allocate(double now, double tokens, List<EdgeQueueView> edges,
                         AllocationSink sink, SimParams params) {
        int granted = 0;
        while (granted < tokens && anyData(edges)) {
            EdgeQueueView best = null;
            for (EdgeQueueView v : edges) {
                if (v.connected() && (best == null || v.backCount() > best.backCount())) {
                    best = (v.backCount() > 0) ? v : best;
                }
            }
            if (best == null) break;
            sink.grant(best.edgeIndex(), false, 1);   // false = a backfill unit
            granted++;
        }
    }
}
```

Helpers in `SchedulingPolicy` you can reuse instead of hand-rolling loops:

* `drainOldestGlobal(edges, sink, limit, Queue.NEW|BACK|BOTH)` — global generation-order drain;
* `drainEdgeMerged(view, limit, sink)` — one edge, both buckets merged, oldest first.

## 3. Register it (three places)

1. `SchedulingPolicy.standardPolicies()` — add `list.add(new LargestBacklogFirst());`.
   (Note: `LargestBacklogFirst` is the worked example of this document only — it is **not**
   part of the four-policy project comparison and is not compiled into the experiment grid.
2. Scenario runs pick policies by `key()`; results files use the same key, so use a short
   lowercase key that is safe in filenames (`fifo`, `rr`, …).
3. Web mirror (optional but recommended): add a `make()` entry to `web/engine.js -> Policies`
   and an `<option>` for it in `web/index.html#cfgPolicy` with a color in
   `web/app.js -> POLICY_COLORS`.

## 4. Test it

Run the self-test through your policy once (it is already covered by the pass over
`standardPolicies()`), then a scenario slice:

```powershell
.\sim\run.ps1 run  -Policy largest -Scenario base -Seed 0
.\sim\run.ps1 grid -Policy largest -Seeds 5      # full comparison slice
```

`backfill.run.SelfTest` then also checks conservation/equal-input invariants for it.

## 5. Knobs

Add knobs to `SimParams` (e.g. `public double myCoolness = 0.3;`), copy them inside
`SimParams.copy()`, and derive per-tick quantities with the same pattern as
`rateLimitTokensPerTick()` (`⌊C·ΔT·factor⌋`). Keep knobs **policy-specific**: the model must
not grant a policy any information the others lack.

package backfill.policy;

import java.util.List;

import backfill.SimParams;

/**
 * New-data-first with fixed backfill quota: fresh data is transmitted first (global generation
 * order among the new-data queues of connected edges). The accumulated backlog receives only a
 * small fixed quota q = backfillQuotaFactor x C units per tick, drained from the oldest backlog
 * first (served before the new data, so the quota is reserved even under a fresh-data flood).
 * Capacity unused by either kind stays idle as center headroom.
 */
public class NewFirstPolicy implements SchedulingPolicy {

    @Override public String key() { return "newfirst"; }
    @Override public String displayName() { return "New-data-first (backfill quota)"; }
    @Override public String description() {
        return "Fresh data first; backlog receives only a small fixed quota q per tick; "
             + "leftover capacity not used by fresh data is held free.";
    }

    @Override
    public void allocate(double now, double tokens, List<EdgeQueueView> edges, AllocationSink sink, SimParams params) {
        int quota = params.backfillQuotaTokensPerTick();
        int grantedBack = SchedulingPolicy.drainOldestGlobal(edges, sink, quota, Queue.BACK);
        int grantableNew = (int) tokens - grantedBack;
        if (grantableNew > 0) {
            SchedulingPolicy.drainOldestGlobal(edges, sink, grantableNew, Queue.NEW);
        }
    }
}

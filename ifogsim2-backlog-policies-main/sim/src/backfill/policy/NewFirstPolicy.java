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
        return "Fresh data first; backlog is guaranteed only a small fixed quota q per tick, plus "
             + "any capacity the fresh data does not use.";
    }

    private static int wholeTokens(double t) {
        if (!(t >= 1.0)) return 0;
        return t >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) t;
    }
    
    @Override
    public void allocate(double now, double tokens, List<EdgeQueueView> edges, AllocationSink sink, SimParams params) {
        final int capacity = wholeTokens(tokens);
        if (capacity <= 0) return;
        final int quota = Math.max(0, Math.min(params.backfillQuotaTokensPerTick(), capacity));
        int grantedBack = quota > 0 ? SchedulingPolicy.drainOldestGlobal(edges, sink, quota, Queue.BACK) : 0;
        
        int grantedNew = 0;
        int forNew = capacity - grantedBack;
        if (forNew > 0) {
            grantedNew = SchedulingPolicy.drainOldestGlobal(edges, sink, forNew, Queue.NEW);
        }

        int spare = capacity - grantedBack - grantedNew;
        if (spare > 0) {
            SchedulingPolicy.drainOldestGlobal(edges, sink, spare, Queue.BACK);
        }
    }

    
}
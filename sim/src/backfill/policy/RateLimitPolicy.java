package backfill.policy;

import java.util.List;

import backfill.SimParams;

/**
 * Rate-limit ("industrial" fixed throughput): backfill is transmitted at a CONSTANT rate
 * R = rateLimitFactor * centerCapacity units/s, no matter how much data has accumulated.
 * Each tick the backlog may consume at most R's per-tick share (reserved first, so the rate
 * stays fixed even under a fresh-data flood); every unit of capacity the reserved rate does
 * not use goes to new data in global generation order.
 *
 * <p>Properties: backfill delay is predictable (a backlog of B units drains in exactly B/R
 * seconds) and a reconnection burst can never flood the center, which keeps at least
 * (1 - rateLimitFactor) * C of its capacity free during a drain.</p>
 */
public class RateLimitPolicy implements SchedulingPolicy {

    @Override public String key() { return "ratelimit"; }
    @Override public String displayName() { return "Rate-limit (fixed R)"; }
    @Override public String description() {
        return "Backfill rate fixed at R = rateLimitFactor x C regardless of backlog size; "
             + "unused reserved capacity flows to fresh data (oldest first).";
    }

    @Override
    public void allocate(double now, double tokens, List<EdgeQueueView> edges, AllocationSink sink, SimParams params) {
        int reservedBack = Math.min(params.rateLimitTokensPerTick(), (int) tokens);
        int grantedBack = SchedulingPolicy.drainOldestGlobal(edges, sink, reservedBack, Queue.BACK);
        int remaining = (int) tokens - grantedBack;
        if (remaining > 0) {
            SchedulingPolicy.drainOldestGlobal(edges, sink, remaining, Queue.NEW);
        }
    }
}

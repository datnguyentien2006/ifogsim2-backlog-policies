package backfill.policy;

import java.util.List;

import backfill.SimParams;

/**
 * FIFO / oldest-first: data is transmitted in exact generation order — accumulated data is
 * ahead of everything generated later, and new data simply "queues behind the accumulated
 * backlog". New and accumulated data share one strict global ordering across all edge servers.
 * Uses the full center capacity (no rate cap).
 */
public class FifoPolicy implements SchedulingPolicy {

    @Override public String key() { return "fifo"; }
    @Override public String displayName() { return "FIFO (oldest-first)"; }
    @Override public String description() {
        return "Strict global generation order; new data waits behind the accumulated backlog; "
             + "uses the full center capacity.";
    }

    @Override
    public void allocate(double now, double tokens, List<EdgeQueueView> edges, AllocationSink sink, SimParams params) {
        SchedulingPolicy.drainOldestGlobal(edges, sink, (int) tokens, Queue.BOTH);
    }
}

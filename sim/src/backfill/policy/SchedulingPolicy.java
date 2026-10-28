package backfill.policy;

import java.util.ArrayList;
import java.util.List;

import backfill.SimParams;

/**
 * A backfill scheduling policy.
 *
 * <p>Every policy receives IDENTICAL inputs each decision tick:</p>
 * <ul>
 *   <li>{@code tokens} - how many units the shared receiving center may absorb this tick
 *       (the same budget for every policy of the same scenario);</li>
 *   <li>{@code edges} - the connection state and both queue heads (new/accumulated) of every
 *       edge server, generation-time ordered;</li>
 *   <li>its own immutable configuration from {@link SimParams}.</li>
 * </ul>
 *
 * <p>The policy decides, entirely by itself, how the budget is allocated between new data and
 * accumulated data and across edge servers, by calling {@link AllocationSink#grant}. The
 * scheduler executes only those decisions. See docs/ADD_POLICY.md to add a new policy.</p>
 */
public interface SchedulingPolicy {

    /** Stable key used in results files, CLI args and the web app (e.g. "fifo"). */
    String key();

    /** Human-readable name for tables and charts. */
    String displayName();

    /** One-line description of the allocation semantics. */
    String description();

    /**
     * Allocate the per-tick budget. Must never grant to a disconnected edge and must never
     * grant more than {@code tokens} units in total (use the shared helpers below).
     */
    void allocate(double now, double tokens, List<EdgeQueueView> edges, AllocationSink sink, SimParams params);

    // ---------------------------------------------------------------

    /** The standard four policies of the project comparison. */
    static List<SchedulingPolicy> standardPolicies() {
        List<SchedulingPolicy> list = new ArrayList<>();
        list.add(new FifoPolicy());
        list.add(new RoundRobinPolicy());
        list.add(new RateLimitPolicy());
        list.add(new NewFirstPolicy());
        return list;
    }

    /** Lookup by stable key. */
    static SchedulingPolicy byKey(String key) {
        for (SchedulingPolicy p : standardPolicies()) {
            if (p.key().equalsIgnoreCase(key)) return p;
        }
        throw new IllegalArgumentException("Unknown policy key: " + key + " (valid: fifo, rr, ratelimit, newfirst)");
    }

    // ---------------------------------------------------------------
    // Shared helpers used by several policies.

    enum Queue { NEW, BACK, BOTH }

    /** true if at least one grantable unit remains anywhere on connected edges. */
    static boolean anyData(List<EdgeQueueView> edges) {
        for (EdgeQueueView v : edges) {
            if (v.connected() && v.hasAny()) return true;
        }
        return false;
    }

    /**
     * Drain up to {@code limit} units in GLOBAL generation-time order across all connected edges.
     *
     * @param which {@link Queue#NEW} only new-data queues; {@link Queue#BACK} only accumulated
     *              backlogs; {@link Queue#BOTH} strict global FIFO across both kinds.
     * @return units actually granted.
     */
    static int drainOldestGlobal(List<EdgeQueueView> edges, AllocationSink sink, int limit, Queue which) {
        int granted = 0;
        while (granted < limit && anyData(edges)) {
            EdgeQueueView best = null;
            boolean bestIsNew = false;
            double bestTime = Double.POSITIVE_INFINITY;
            for (EdgeQueueView v : edges) {
                if (!v.connected()) continue;
                if (which != Queue.BACK) {
                    Double t = v.newHeadGenTime();
                    if (t != null && t < bestTime) { best = v; bestIsNew = true; bestTime = t; }
                }
                if (which != Queue.NEW) {
                    Double t = v.backHeadGenTime();
                    if (t != null && t < bestTime) { best = v; bestIsNew = false; bestTime = t; }
                }
            }
            if (best == null) break;
            sink.grant(best.edgeIndex(), bestIsNew, 1);
            granted++;
        }
        return granted;
    }

    /**
     * Within ONE edge, pop units in the edge's own generation order (both buckets merged).
     *
     * @return units actually granted — determined by how many units the edge's queues held,
     *         which can be fewer than {@code limit}; the caller MUST account only this number.
     */
    static int drainEdgeMerged(EdgeQueueView v, int limit, AllocationSink sink) {
        int granted = 0;
        for (int k = 0; k < limit; k++) {
            Double tn = v.newHeadGenTime();
            Double tb = v.backHeadGenTime();
            if (tn == null && tb == null) break;
            boolean takeNew = (tb == null) || (tn != null && tn <= tb);
            sink.grant(v.edgeIndex(), takeNew, 1);
            granted++;
        }
        return granted;
    }
}

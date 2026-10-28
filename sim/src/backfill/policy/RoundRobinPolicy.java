package backfill.policy;

import java.util.List;

import backfill.SimParams;

/**
 * Round-robin: the per-tick budget is split evenly, in turn, over the edge servers that still
 * hold data (disconnected or empty servers are skipped). Each server drains its own queue in
 * generation order — so within one server, new data is behind that server's own backlog, but
 * no single server can monopolize the center. Leftover quota from servers that could not use
 * their full share is redistributed, in turn, to the servers that still hold data (tokens are
 * never discarded). A rotating cursor avoids systematically favoring the first servers.
 */
public class RoundRobinPolicy implements SchedulingPolicy {

    private int cursor = 0;

    @Override public String key() { return "rr"; }
    @Override public String displayName() { return "Round-robin"; }
    @Override public String description() {
        return "Capacity split evenly, in turn, over servers that still hold data; each server "
             + "drains its own oldest-first mix of backlog and new data.";
    }

    @Override
    public void allocate(double now, double tokens, List<EdgeQueueView> edges, AllocationSink sink, SimParams params) {
        int n = edges.size();
        int remaining = (int) tokens;
        if (remaining <= 0) return;

        // Servers that still hold data, ordered by the rotating cursor (fair turn order).
        int[] order = new int[n];
        int activeCount = 0;
        for (int k = 0; k < n; k++) {
            int i = (cursor + k) % n;
            EdgeQueueView v = edges.get(i);
            if (v.connected() && v.hasAny()) order[activeCount++] = i;
        }
        if (activeCount == 0) return;

        // First pass: even share in turn. A server may use less than its share (its queues
        // hold fewer units than requested); the leftover is NOT discarded — it is passed on
        // in turn (subsequent passes) to the servers that still hold data.
        int lastGranted = -1;
        while (remaining > 0) {
            int grantedThisPass = 0;
            int share = Math.max(1, remaining / activeCount);
            for (int k = 0; k < activeCount; k++) {
                int i = order[k];
                if (remaining <= 0) break;
                EdgeQueueView v = edges.get(i);
                if (!v.connected() || !v.hasAny()) continue; // drained by an earlier pass
                int take = Math.min(share, remaining);
                int got = SchedulingPolicy.drainEdgeMerged(edges.get(i), take, sink);
                remaining -= got;
                grantedThisPass += got;
                if (got > 0) lastGranted = i;
            }
            if (grantedThisPass == 0) break; // nothing grantable anywhere
        }
        if (lastGranted >= 0) cursor = (lastGranted + 1) % n;
    }
}

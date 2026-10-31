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
    private int[] order = new int[0];
    @Override public String key() { return "rr"; }
    @Override public String displayName() { return "Round-robin"; }
    @Override public String description() {
        return "Capacity split evenly, in turn, over servers that still hold data; each server "
             + "drains its own oldest-first mix of backlog and new data.";
    }

    public void reset() { cursor = 0; } // Helper for tester
    private static int wholeTokens(double t) {
        if (!(t >= 1.0)) return 0;
        return t >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) t;
    }
    
    @Override
    public void allocate(double now, double tokens, List<EdgeQueueView> edges, AllocationSink sink, SimParams params) {
        final int n = edges.size();
        int remaining = wholeTokens(tokens);
        if (n == 0 || remaining <= 0) return;
        if (cursor >= n) cursor = 0;

        if (order.length < n) { order = new int[n];}
        int active = 0;
        for (int k = 0; k < n; k++) {
            int i = (cursor + k) % n;
            EdgeQueueView v = edges.get(i);
            if (v.connected() && v.hasAny()) order[active++] = i;
        }

        // Each pass offers every still-active server an equal share of what is left. Servers
        // that drain are dropped, so the share is recomputed over the servers
        int lastGranted = -1;
        while (remaining > 0 && active > 0) {
            final int share = Math.max(1, remaining / active);
            int grantedThisPass = 0;
            int stillActive = 0; // in-place overlapped: stillActive <= k, so unvisited slots are safe
            for (int k = 0; k < active && remaining > 0; k++) {
                int i = order[k];
                EdgeQueueView v = edges.get(i);
                int got = SchedulingPolicy.drainEdgeMerged(v, Math.min(share, remaining), sink);
                if (got > 0) {
                    remaining -= got;
                    grantedThisPass += got;
                    lastGranted = i;
                }
                if (v.hasAny()) order[stillActive++] = i;
            }
            if (grantedThisPass == 0) break; // nothing grantable anywhere and prevents looping
            active = stillActive;
        }
        if (lastGranted >= 0) cursor = (lastGranted + 1) % n;
    }

    
}
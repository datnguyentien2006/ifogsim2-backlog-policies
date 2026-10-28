package backfill.controllers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;

import backfill.BackfillEvents;
import backfill.SimParams;
import backfill.data.EgressUnit;
import backfill.data.GrantPayload;
import backfill.devices.EdgeServerDevice;
import backfill.metrics.UnitRegistry;
import backfill.policy.AllocationSink;
import backfill.policy.EdgeQueueView;
import backfill.policy.SchedulingPolicy;

/**
 * The scheduling controller of the backfill framework. Once per decision tick:
 *
 * <ol>
 *   <li>build one immutable {@link EdgeQueueView} per edge (identical input for every policy);</li>
 *   <li>call the active policy's {@code allocate(...)}; the policy decides which unit counts go
 *       out (new vs. accumulated, and across edges);</li>
 *   <li>execute exactly those decisions: pop the units, hand them to the edge devices as
 *       {@link BackfillEvents#EGRESS_GRANT};</li>
 *   <li>record the per-tick measurement snapshot.</li>
 * </ol>
 */
public class SchedulerEntity extends SimEntity {

    private final SimParams p;
    private final List<EdgeServerDevice> edges;
    private final SchedulingPolicy policy;

    public SchedulerEntity(String name, SimParams params, List<EdgeServerDevice> edges, SchedulingPolicy policy) {
        super(name);
        this.p = params;
        this.edges = edges;
        this.policy = policy;
    }

    @Override
    public void startEntity() {
        // First tick as soon as the DES allows; afterwards steady DT-spacing.
        send(getId(), CloudSim.getMinTimeBetweenEvents(), BackfillEvents.TICK);
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == BackfillEvents.TICK) {
            doTick();
            double delay = p.decisionInterval;
            send(getId(), delay, BackfillEvents.TICK);
            return;
        }
        throw new IllegalStateException("SchedulerEntity: unexpected event " + ev.getTag());
    }

    @Override
    public void shutdownEntity() { }

    // ------------------------------------------------------------------

    private void doTick() {
        double now = CloudSim.clock();

        int tokens = p.centerTokensPerTick();
        List<EdgeQueueView> views = buildViews();

        // The sink executes policy decisions: pops units from the device queues and bundles
        // them into per-edge grant payloads.
        Map<Integer, List<EgressUnit>> granted = new HashMap<>();
        int[] counts = new int[2]; // [newGranted, backGranted]
        AllocationSink sink = new AllocationSink() {
            @Override
            public void grant(int edgeIndex, boolean isNew, int count) {
                if (count <= 0) return;
                EdgeServerDevice dev = edges.get(edgeIndex);
                int available = isNew ? dev.newCount() : dev.backCount();
                if (count > available) {
                    throw new IllegalStateException("Policy over-granted edge " + edgeIndex
                            + " request=" + count + " available=" + available);
                }
                for (int k = 0; k < count; k++) {
                    EgressUnit unit = isNew ? dev.popNew() : dev.popBack();
                    granted.computeIfAbsent(edgeIndex, x -> new ArrayList<>()).add(unit);
                    if (isNew) counts[0]++; else counts[1]++;
                }
            }
        };

        policy.allocate(now, tokens, views, sink, p);

        // Global guard: no policy may hand out more than the per-tick budget in total.
        // (Individual policies enforce this themselves; this fails fast if a future policy
        // violates the contract.)
        if (counts[0] + counts[1] > tokens) {
            throw new IllegalStateException("Policy granted " + (counts[0] + counts[1])
                    + " units but the per-tick budget is only " + tokens);
        }

        // Hand the decisions to the devices (same clock, minimal event delay for ordering).
        for (Map.Entry<Integer, List<EgressUnit>> e : granted.entrySet()) {
            EdgeServerDevice dev = edges.get(e.getKey());
            schedule(dev.getId(), CloudSim.getMinTimeBetweenEvents(), BackfillEvents.EGRESS_GRANT,
                    new GrantPayload(dev.edgeIndex, e.getValue()));
        }

        // Measurement snapshot after allocation.
        boolean[] up = new boolean[edges.size()];
        int[] backlogNew = new int[edges.size()];
        int[] backlogBack = new int[edges.size()];
        for (int i = 0; i < edges.size(); i++) {
            EdgeServerDevice dev = edges.get(i);
            up[i] = dev.isLinkUp();
            backlogNew[i] = dev.newCount();
            backlogBack[i] = dev.backCount();
        }
        UnitRegistry.onTick(now, counts[0], counts[1], up, backlogNew, backlogBack);
    }

    private List<EdgeQueueView> buildViews() {
        List<EdgeQueueView> views = new ArrayList<>(edges.size());
        for (EdgeServerDevice dev : edges) {
            views.add(new EdgeQueueView() {
                @Override public int edgeIndex() { return dev.edgeIndex; }
                @Override public boolean connected() { return dev.isLinkUp(); }
                @Override public int newCount() { return dev.newCount(); }
                @Override public int backCount() { return dev.backCount(); }
                @Override public Double newHeadGenTime() { return dev.newHeadTime(); }
                @Override public Double backHeadGenTime() { return dev.backHeadTime(); }
            });
        }
        return views;
    }
}

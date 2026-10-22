package org.fog.hk261backlog.disruption;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;

/** Single-threaded CloudSim replay. Call applyDue before reading state in a runner tick. */
public final class ReconnectionScheduler extends SimEntity {

    enum Tag implements CloudSimTags { APPLY_DUE, HORIZON }

    private final DisruptionPlan plan;
    private final DisconnectEventLog log;
    private final List<Transition> transitions;
    private final Map<Integer, ConnectionState> current = new TreeMap<>();
    private final List<Consumer<Transition>> listeners = new ArrayList<>();
    private int nextIndex = 0;
    private double lastApplyTime = 0;
    private boolean applying;

    public ReconnectionScheduler(String name, DisruptionPlan plan, DisconnectEventLog log) {
        super(validatedName(name, plan, log));
        this.plan = Objects.requireNonNull(plan, "plan");
        this.log = Objects.requireNonNull(log, "log");
        this.transitions = plan.transitions();

        for (int edge : plan.edgeKeys())
            current.put(edge, ConnectionState.ONLINE);
    }

    private static String validatedName(String name, DisruptionPlan plan, DisconnectEventLog log) {
        // SimEntity registers itself in its constructor, so reject invalid arguments first.
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(log, "log");
        if (plan.horizon() == Double.MAX_VALUE)
            throw new IllegalArgumentException("CloudSim requires horizon < Double.MAX_VALUE");
        return name;
    }

    public void addListener(Consumer<Transition> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void startEntity() {
        double now = CloudSim.clock();
        applyDue(now);
        // One wake-up per timestamp, even when several edges change together.
        double previousAt = Double.NaN;
        for (int i = nextIndex; i < transitions.size(); i++) {
            double at = transitions.get(i).at();
            if (at != previousAt) scheduleAt(at, now, Tag.APPLY_DUE);
            previousAt = at;
        }

        scheduleAt(plan.horizon(), now, Tag.HORIZON);
    }

    private void scheduleAt(double at, double now, Tag tag) {
        double delay = at - now;
        // CloudSim reconstructs the absolute time as now + delay. Prevent an early
        // wake-up caused by subtraction/addition rounding when starting late.
        while (now + delay < at) delay = Math.nextUp(delay);
        schedule(getId(), delay, tag);
    }

    @Override
    public void processEvent(SimEvent event) {
        if (event.getTag() == Tag.APPLY_DUE)
            applyDue(CloudSim.clock());
        else if (event.getTag() == Tag.HORIZON) {}
        else
            throw new IllegalArgumentException("Unknown event tag: " + event.getTag());
    }

    @Override
    public void shutdownEntity() {}

    /**
     * Applies each due transition once. A listener failure propagates after the
     * current transition is committed. Reentrant application is forbidden.
     */
    public List<Transition> applyDue(double now) {
        if (applying) throw new IllegalStateException("applyDue cannot be called from a transition listener");
        if (!Double.isFinite(now) || now < lastApplyTime || now > plan.horizon())
            throw new IllegalStateException("Time must be monotonic and within [0, horizon]");
        lastApplyTime = now;

        List<Transition> applied = new ArrayList<>();
        applying = true;
        try {
            while (nextIndex < transitions.size() && transitions.get(nextIndex).at() <= now) {
                Transition t = transitions.get(nextIndex);
                nextIndex++;

                ConnectionState old = current.put(t.edgeKey(), t.newState());
                if (old == t.newState()) throw new IllegalStateException("Transition does not change state");

                log.recordApplied(t, now);
                applied.add(t);

                for (Consumer<Transition> listener : List.copyOf(listeners))
                    listener.accept(t);
            }
            return List.copyOf(applied);
        } finally {
            applying = false;
        }
    }

    public ConnectionState stateOf(int edgeKey) {
        ConnectionState state = current.get(edgeKey);
        if (state == null)
            throw new IllegalArgumentException("Unknown edge key " + edgeKey);
        return state;
    }

    public boolean isOnline(int edgeKey) {
        return stateOf(edgeKey) == ConnectionState.ONLINE;
    }

    public int appliedCount() {
        return nextIndex;
    }

    public DisruptionPlan plan() {
        return plan;
    }
}

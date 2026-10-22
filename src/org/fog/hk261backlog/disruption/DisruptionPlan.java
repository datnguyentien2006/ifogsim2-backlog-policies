package org.fog.hk261backlog.disruption;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class DisruptionPlan {

    public enum Mode {FIXED, SCATTERED, CLUSTERED}
    private static final Comparator<Transition> BY_TIME_THEN_EDGE = Comparator.comparingDouble(Transition::at).
                                                                    thenComparingInt(Transition::edgeKey);

    private final Mode mode;
    private final double horizon;
    private final List<Integer> edgeKeys;
    private final Map<Integer, List<Outage>> outagesByEdge;
    private final List<Transition> transitions;

    public DisruptionPlan(Mode mode, Collection<Integer> edgeKeys, double horizon, Collection<Outage> outages) {
        this.mode = Objects.requireNonNull(mode, "mode");
        Validation.requirePositive(horizon, "horizon");
        this.horizon = horizon;

        Objects.requireNonNull(edgeKeys, "edgeKeys");
        Objects.requireNonNull(outages, "outages");

        this.edgeKeys = Validation.edgeKeys(edgeKeys, "edgeKeys");

        Map<Integer, List<Outage>> tempMap = new TreeMap<>();
        for (int key : this.edgeKeys)
            tempMap.put(key, new ArrayList<>());
        for (Outage outage : outages) {
            Objects.requireNonNull(outage, "outage");
            List<Outage> list = tempMap.get(outage.edgeKey());

            if (list == null)
                throw new IllegalArgumentException("Unknown edge key " + outage.edgeKey());

            if (outage.downAt() >= horizon)
                throw new IllegalArgumentException("Outage starts at or after horizon");

            list.add(outage);
        }

        for (Map.Entry<Integer, List<Outage>> entry : tempMap.entrySet()) {
            int edgeKey = entry.getKey();
            List<Outage> list = entry.getValue();

            list.sort(Comparator.comparingDouble(Outage::downAt));

            for (int i = 1; i < list.size(); i++) {
                Outage truoc = list.get(i - 1);
                Outage sau   = list.get(i);

                if (sau.downAt() <= truoc.upAt())
                    throw new IllegalArgumentException("Overlapping/touching outages at edge " + edgeKey);

                if (sau.cycle() <= truoc.cycle())
                    throw new IllegalArgumentException("Cycle must increase at edge " + edgeKey);
            }
        }

        List<Transition> tempTransitions = new ArrayList<>();
        for (List<Outage> list : tempMap.values()) {
            for (Outage outage : list) {
                tempTransitions.add(new Transition(outage, ConnectionState.OFFLINE));
                if (outage.recoversBefore(horizon)) tempTransitions.add(new Transition(outage, ConnectionState.ONLINE));
            }
        }
        tempTransitions.sort(BY_TIME_THEN_EDGE);

        Map<Integer, List<Outage>> frozenMap = new TreeMap<>();
        for (Map.Entry<Integer, List<Outage>> entry : tempMap.entrySet())
            frozenMap.put(entry.getKey(), List.copyOf(entry.getValue()));

        this.outagesByEdge = Collections.unmodifiableMap(frozenMap);
        this.transitions = List.copyOf(tempTransitions);
    }

    public static DisruptionPlan fixed(Collection<Integer> edgeKeys, double horizon, Outage... outages) {
        return new DisruptionPlan(Mode.FIXED, edgeKeys, horizon, List.of(outages));
    }

    public Mode mode() {
        return mode;
    }

    public double horizon() {
        return horizon;
    }

    public List<Integer> edgeKeys() {
        return edgeKeys;
    }

    public List<Outage> outagesOf(int edgeKey) {
        List<Outage> list = outagesByEdge.get(edgeKey);
        if (list == null)
            throw new IllegalArgumentException("Unknown edge key " + edgeKey);
        return list;
    }

    public List<Outage> allOutages() {
        List<Outage> all = new ArrayList<>();
        outagesByEdge.values().forEach(all::addAll);
        all.sort(Comparator.comparingDouble(Outage::downAt).thenComparingInt(Outage::edgeKey));
        return List.copyOf(all);
    }

    public List<Transition> transitions() {
        return transitions;
    }


    public boolean isOnline(int edgeKey, double time) {
        if (!Double.isFinite(time) || time > horizon || time < 0) throw new IllegalArgumentException("Invalid time");
        List<Outage> outages = outagesOf(edgeKey);

        for (Outage outage : outages)
            if (outage.covers(time)) return false;

        return true;
    }

    public ConnectionState stateAt(int edgeKey, double time) {
        return isOnline(edgeKey, time) ? ConnectionState.ONLINE : ConnectionState.OFFLINE;
    }
}

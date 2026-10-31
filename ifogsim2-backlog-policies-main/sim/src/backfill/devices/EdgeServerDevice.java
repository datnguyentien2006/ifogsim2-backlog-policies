package backfill.devices;

import java.util.ArrayDeque;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.power.PowerHost;
import org.fog.entities.FogDevice;
import org.fog.entities.Tuple;
import org.fog.utils.FogEvents;
import org.fog.utils.NetworkUsageMonitor;

import backfill.BackfillEvents;
import backfill.data.EgressUnit;
import backfill.data.GrantPayload;
import backfill.metrics.UnitRegistry;

/**
 * An edge server that buffers data locally instead of forwarding when its link to the center is
 * down. All upstream tuple flow is intercepted here and released ONLY when the scheduling
 * controller grants it — this is the single enforcement point of the four policies inside the
 * iFogSim2 event machine.
 *
 * <p>Re-uses iFogSim2's own transmission chain: a granted unit is sent with the same
 * {@code fileSize / uplinkBandwidth} serialization and {@code uplinkLatency} propagation as the
 * stock {@code sendUpFreeLink}, and the link frees itself through the same
 * {@link FogEvents#UPDATE_NORTH_TUPLE_QUEUE} release event, so network timing stays authentic.</p>
 */
public class EdgeServerDevice extends FogDevice {

    public final int edgeIndex;

    /** Connectivity to the receiving center. */
    private boolean linkUp = true;

    /** Units generated while connected, generation-time ordered (FIFO). */
    private final ArrayDeque<EgressUnit> newQ = new ArrayDeque<>();

    /** Units accumulated while the link was down, generation-time ordered (FIFO). */
    private final ArrayDeque<EgressUnit> backQ = new ArrayDeque<>();

    /** Units currently in the transmission pipeline (granted, not yet handed to the wire). */
    private final ArrayDeque<EgressUnit> inFlight = new ArrayDeque<>();

    public EdgeServerDevice(String name, int edgeIndex,
                            org.fog.entities.FogDeviceCharacteristics characteristics,
                            org.cloudbus.cloudsim.VmAllocationPolicy vmAllocationPolicy,
                            java.util.List<org.cloudbus.cloudsim.Storage> storageList,
                            double schedulingInterval,
                            double uplinkBandwidth, double downlinkBandwidth,
                            double uplinkLatency, double ratePerMips) throws Exception {
        super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval,
                uplinkBandwidth, downlinkBandwidth, uplinkLatency, ratePerMips);
        this.edgeIndex = edgeIndex;
    }

    // ---------------------------------------------------------------- helpers

    public boolean isLinkUp() { return linkUp; }
    public int newCount() { return newQ.size(); }
    public int backCount() { return backQ.size(); }
    public Double newHeadTime() { return newQ.isEmpty() ? null : newQ.peekFirst().genTime; }
    public Double backHeadTime() { return backQ.isEmpty() ? null : backQ.peekFirst().genTime; }

    /** Pop the head new-data unit (SchedulerEntity only). */
    public EgressUnit popNew() { return newQ.pollFirst(); }

    /** Pop the head accumulated-data unit (SchedulerEntity only). */
    public EgressUnit popBack() { return backQ.pollFirst(); }

    // -------------------------------------------------------- iFogSim2 hooks

    /**
     * EGRESS INTERCEPTION. The stock FogDevice transmits here — instead, every data unit the
     * edge generates is classified (new vs. accumulated by the connectivity at generation time),
     * buffered, and accounted. Nothing is physically sent until the scheduler grants it.
     */
    @Override
    protected void sendUp(Tuple tuple) {
        EgressUnit unit = new EgressUnit(tuple, CloudSim.clock(), linkUp, edgeIndex);
        (linkUp ? newQ : backQ).addLast(unit);
        UnitRegistry.onGenerated(unit);
    }

    @Override
    protected void processOtherEvent(SimEvent ev) {
        if (ev.getTag() instanceof BackfillEvents tagE) {
            switch (tagE) {
                case LINK_DOWN -> setLinkState(false);
                case LINK_UP -> setLinkState(true);
                case EGRESS_GRANT -> applyGrant((GrantPayload) ev.getData());
                default -> { }
            }
            return;
        }
        super.processOtherEvent(ev);
    }

    /** Releases the next queued unit, or frees the uplink when the pipeline is empty/down. */
    @Override
    protected void updateNorthTupleQueue() {
        if (!linkUp) {
            returnGrantedUnits();
            setNorthLinkBusy(false);
            return;
        }
        if (inFlight.isEmpty()) {
            setNorthLinkBusy(false);
            return;
        }
        transmitUnit(inFlight.pollFirst());
    }

    // ---------------------------------------------------------------- internals

    private void setLinkState(boolean up) {
        if (linkUp == up) return;
        boolean wasDown = !linkUp;
        linkUp = up;
        if (wasDown) {
            // reconnecting with pending data starts (or continues) a drain measurement
            UnitRegistry.onLinkChange(edgeIndex, CloudSim.clock(), true, newQ.size() + backQ.size() + inFlight.size(), false);
            if (!inFlight.isEmpty()) {
                schedule(getId(), org.cloudbus.cloudsim.core.CloudSim.getMinTimeBetweenEvents(),
                        FogEvents.UPDATE_NORTH_TUPLE_QUEUE);
            }
        } else {
            UnitRegistry.onLinkChange(edgeIndex, CloudSim.clock(), false, 0, true);
            // pipeline tail event will find the link down and return the units
        }
    }

    /** Scheduler decision executed here: queue the granted units onto the transmission pipeline. */
    private void applyGrant(GrantPayload grant) {
        if (grant.edgeIndex != edgeIndex) return; // defensive: wrong routing
        if (!linkUp) {
            // Should not happen (policies never grant to disconnected edges): restore silently.
            // The busy-flag invariant (busy => pending release event) is left untouched so the
            // pending release event cleans up consistently.
            for (int i = grant.units.size() - 1; i >= 0; i--) {
                EgressUnit unit = grant.units.get(i);
                (unit.isNew ? newQ : backQ).addFirst(unit);
            }
            return;
        }
        for (EgressUnit unit : grant.units) {
            inFlight.addLast(unit);
        }
        if (!isNorthLinkBusy()) {
            updateNorthTupleQueue();
        }
    }

    /** Same wire semantics as the stock sendUpFreeLink, incl. network-usage accounting. */
    private void transmitUnit(EgressUnit unit) {
        double networkDelay = unit.fileSize() / getUplinkBandwidth();
        setNorthLinkBusy(true);
        NetworkUsageMonitor.sendingTuple(getUplinkLatency(), unit.fileSize());
        send(getId(), networkDelay, FogEvents.UPDATE_NORTH_TUPLE_QUEUE);
        send(getParentId(), networkDelay + getUplinkLatency(), FogEvents.TUPLE_ARRIVAL, unit.tuple);
    }

    /** On link loss: hand granted-but-unsent units back to their generation queues, preserving order. */
    private void returnGrantedUnits() {
        while (!inFlight.isEmpty()) {
            EgressUnit unit = inFlight.pollLast();
            (unit.isNew ? newQ : backQ).addFirst(unit);
        }
    }
}

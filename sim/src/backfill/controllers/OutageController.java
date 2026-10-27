package backfill.controllers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;

import backfill.BackfillEvents;
import backfill.SimParams;
import backfill.devices.EdgeServerDevice;

/**
 * Decides when each edge loses and regains its link to the center (SCATTERED or COHORT mode)
 * and sends LINK_DOWN / LINK_UP to the EdgeServerDevice.
 */
public class OutageController extends SimEntity {

    /** Payload of OUTAGE_MOVE: which edge, which cycle, and where it goes next. */
    public record Transition(int edgeKey, long cycle, boolean toOffline) { }

    static final int MAX_TRANSITIONS = 1_000_000;

    private final SimParams p;
    private final List<EdgeServerDevice> edges;
    private final Random rng;
    private long transitionCount = 0;
    private long stormCount = 0;

    public OutageController(String name, SimParams params, List<EdgeServerDevice> edges) {
        super(validatedName(name, params, edges));
        this.p = params;
        this.edges = edges;
        this.rng = new Random(params.seed * 8191L + 101);
    }

    /** Validates the arguments before SimEntity registers the entity. */
    private static String validatedName(String name, SimParams params, List<EdgeServerDevice> edges) {
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(edges, "edges");

        if (edges.isEmpty()) throw new IllegalArgumentException("edges must not be empty");
        requirePositive(params.downMean, "downMean");

        if (params.outageMode == SimParams.OutageMode.SCATTERED) {
            requirePositive(params.dropRatePerEdge, "dropRatePerEdge");
            requirePositive(params.downCV, "downCV");
        } else {
            requirePositive(params.cohortPeriod, "cohortPeriod");
            requirePositive(params.cohortCV, "cohortCV");
            if (!(params.cohortFraction > 0 && params.cohortFraction <= 1))
                throw new IllegalArgumentException("cohortFraction must be in (0, 1], got " + params.cohortFraction);
        }

        if (edges.size() != params.numEdges) {
            throw new IllegalArgumentException("edges.size() must equal params.numEdges: expected "
                    + params.numEdges + ", got " + edges.size());
        }
        for (int i = 0; i < edges.size(); i++) {
            if (edges.get(i).edgeIndex != i) {
                throw new IllegalArgumentException("edges[" + i + "].edgeIndex must be " + i
                        + ", got " + edges.get(i).edgeIndex);
            }
        }

        return name;
    }

    /** Schedules the first drop of every edge (SCATTERED) or the first storm (COHORT) at t = 0. */
    @Override
    public void startEntity() {
        if (p.outageMode == SimParams.OutageMode.SCATTERED) {
            for (EdgeServerDevice dev : edges) {
                schedule(getId(), drawOnline(), BackfillEvents.OUTAGE_MOVE,
                        new Transition(dev.edgeIndex, 0, true));
            }
        } else {
            schedule(getId(), p.cohortPeriod, BackfillEvents.STORM_MOVE, null);
        }
    }

    @Override
    public void processEvent(SimEvent event) {
        if (event.getTag() instanceof BackfillEvents tag) {
            switch (tag) {
                case OUTAGE_MOVE -> applyTransition((Transition) event.getData());
                case STORM_MOVE -> handleStorm();
                default -> throw new IllegalArgumentException("Unknown event tag: " + tag);
            }
            return;
        }
        throw new IllegalArgumentException("Unknown event tag: " + event.getTag());
    }

    @Override
    public void shutdownEntity() { }

    /**
     * Takes one edge offline or back online. In COHORT a recovered storm member schedules
     * nothing further.
     */
    private void applyTransition(Transition t) {
        requireReasonableCount();
        EdgeServerDevice dev = edges.get(t.edgeKey());
        double linkDelay = CloudSim.getMinTimeBetweenEvents();

        if (t.toOffline()) {
            send(dev.getId(), linkDelay, BackfillEvents.LINK_DOWN);
            send(getId(), drawOffline(), BackfillEvents.OUTAGE_MOVE,
                    new Transition(t.edgeKey(), t.cycle(), false));
        } else {
            send(dev.getId(), linkDelay, BackfillEvents.LINK_UP);
            if (p.outageMode == SimParams.OutageMode.SCATTERED) {
                send(getId(), drawOnline(), BackfillEvents.OUTAGE_MOVE,
                        new Transition(t.edgeKey(), t.cycle() + 1, true));
            }
        }
    }

    /** Drops a random subset of connected edges at once and schedules the next storm. */
    private void handleStorm() {
        List<Integer> members = selectStormMembers();
        double linkDelay = CloudSim.getMinTimeBetweenEvents();

        for (int idx : members) {
            send(edges.get(idx).getId(), linkDelay, BackfillEvents.LINK_DOWN);
            send(getId(), drawCohortOffline(), BackfillEvents.OUTAGE_MOVE,
                    new Transition(idx, stormCount, false));
        }

        stormCount++;
        schedule(getId(), p.cohortPeriod, BackfillEvents.STORM_MOVE, null);
    }

    /** Picks min(max(1, round(cohortFraction * numEdges)), connected edges) storm members. */
    private List<Integer> selectStormMembers() {
        List<Integer> candidates = new ArrayList<>();

        for (EdgeServerDevice dev : edges) {
            if (dev.isLinkUp()) {
                candidates.add(dev.edgeIndex);
            }
        }

        Collections.shuffle(candidates, rng);
        int m = (int) Math.max(1, Math.round(p.cohortFraction * p.numEdges));

        return candidates.subList(0, Math.min(m, candidates.size()));
    }

    /** Online spell: exponential with rate dropRatePerEdge. */
    private double drawOnline() {
        return expDraw(1.0 / Math.max(1e-9, p.dropRatePerEdge));
    }

    /** Offline spell of a SCATTERED drop: lognormal(downMean, downCV). */
    private double drawOffline() {
        return lognormalDraw(p.downMean, Math.max(0.01, p.downCV));
    }

    /** Offline spell of a storm member: lognormal(downMean, cohortCV). */
    private double drawCohortOffline() {
        return lognormalDraw(p.downMean, Math.max(0.01, p.cohortCV));
    }

    /** Exponential draw with the given mean. */
    private double expDraw(double mean) {
        double u = rng.nextDouble();
        if (u <= 0.0) u = 1e-12;
        return Math.max(1e-6, -mean * Math.log(u));
    }

    /** Lognormal draw with the given mean and coefficient of variation. */
    private double lognormalDraw(double mean, double cv) {
        double sigma2 = Math.log(1.0 + cv * cv);
        double mu = Math.log(Math.max(1e-9, mean)) - sigma2 / 2.0;
        double z = rng.nextGaussian();
        return Math.max(1e-6, Math.exp(mu + Math.sqrt(sigma2) * z));
    }

    /** Fails fast if the transition count explodes (usually a time-unit mistake). */
    private void requireReasonableCount() {
        if (++transitionCount > MAX_TRANSITIONS)
            throw new IllegalStateException("Too many transitions: check duration units (seconds?)");
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(name + " must be finite and > 0, got " + value);
        }
    }
}

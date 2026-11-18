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
 * Connectivity-event module (project step 2): each edge server loses its link to the center and
 * reconnects according to configurable probability distributions.
 *
 * <ul>
 *   <li><b>SCATTERED</b> — every edge has an independent clock: link-up spells are exponential
 *       (Poisson drop process at the edge's own rate {@link SimParams#dropRateOfEdge}); the
 *       down-spells are log-normal with the edge's own mean {@link SimParams#downMeanOfEdge} and
 *       cv {@code downCV}, so edges reconnect at scattered, uncorrelated points in time. The
 *       per-edge rates and durations ramp linearly around the fleet means, so every edge owns a
 *       distinct (drop probability, outage duration) pair.</li>
 *   <li><b>COHORT</b> — storms: every {@code cohortPeriod} seconds a random subset of
 *       {@code cohortFraction} of the (currently connected) edges drops AT THE SAME INSTANT; each
 *       member's down-spell is log-normal with the same mean but a small cv ({@code cohortCV}),
 *       so several edges restore their links within a short window of each other — a realistic
 *       simultaneous-reconnection burst that piles backlog onto the scheduler at once.</li>
 * </ul>
 */
public class OutageController extends SimEntity {

    /** Payload: which edge moves to which state next. */
    public record OutageMove(int edgeIndex, boolean toDown) { }

    /** Fail-fast cap on handled link moves: a count this high almost always means a time-unit mistake. */
    static final int MAX_MOVES = 1_000_000;

    private final SimParams p;
    private final List<EdgeServerDevice> edges;
    private final Random rng;
    private long moveCount = 0;

    public OutageController(String name, SimParams params, List<EdgeServerDevice> edges) {
        super(validatedName(name, params, edges));
        this.p = params;
        this.edges = edges;
        this.rng = new Random(params.seed * 8191L + 101);
    }

    /** Validates the arguments before SimEntity registers the entity with CloudSim. */
    private static String validatedName(String name, SimParams params, List<EdgeServerDevice> edges) {
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(edges, "edges");
        Objects.requireNonNull(params.outageMode, "outageMode");

        // Per-edge up/down spells: SCATTERED, and COHORT members once their storm is over.
        requireNonNegative(params.dropRatePerEdge, "dropRatePerEdge");
        requireUnitRange(params.outageRateSpread, "outageRateSpread");
        requireUnitRange(params.outageDownSpread, "outageDownSpread");
        requirePositive(params.downMean, "downMean");
        requireNonNegative(params.downCV, "downCV");

        if (params.outageMode == SimParams.OutageMode.COHORT) {
            requirePositive(params.cohortPeriod, "cohortPeriod");
            if (!(params.cohortFraction > 0 && params.cohortFraction <= 1))
                throw new IllegalArgumentException("cohortFraction must be in (0, 1], got " + params.cohortFraction);
            requireNonNegative(params.cohortCV, "cohortCV");
        }

        if (edges.isEmpty()) throw new IllegalArgumentException("edges must not be empty");
        if (edges.size() != params.numEdges)
            throw new IllegalArgumentException("edges.size() must equal params.numEdges: expected "
                    + params.numEdges + ", got " + edges.size());
        for (int i = 0; i < edges.size(); i++) {
            if (edges.get(i).edgeIndex != i)
                throw new IllegalArgumentException("edges[" + i + "].edgeIndex must be " + i
                        + ", got " + edges.get(i).edgeIndex);
        }
        return name;
    }

    @Override
    public void startEntity() {
        if (p.outageMode == SimParams.OutageMode.SCATTERED) {
            for (EdgeServerDevice dev : edges) {
                schedule(getId(), drawUpSpell(dev.edgeIndex), BackfillEvents.OUTAGE_MOVE, new OutageMove(dev.edgeIndex, true));
            }
        } else {
            schedule(getId(), p.cohortPeriod, BackfillEvents.STORM_MOVE, null);
        }
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() instanceof BackfillEvents tagE) {
            switch (tagE) {
                case OUTAGE_MOVE -> handleMove((OutageMove) ev.getData());
                case STORM_MOVE -> handleStorm();
                default -> throw new IllegalStateException("OutageController: unexpected tag " + ev.getTag());
            }
            return;
        }
        throw new IllegalStateException("OutageController: unexpected event " + ev.getTag());
    }

    @Override
    public void shutdownEntity() { }

    // ------------------------------------------------------------------ state machine

    private void handleMove(OutageMove move) {
        if (++moveCount > MAX_MOVES)
            throw new IllegalStateException("OutageController: more than " + MAX_MOVES
                    + " link moves; check that rates are per second and durations in seconds");
        EdgeServerDevice dev = edges.get(move.edgeIndex());
        if (move.toDown()) {
            send(dev.getId(), CloudSim.getMinTimeBetweenEvents(), BackfillEvents.LINK_DOWN);
            send(getId(), drawDownSpell(move.edgeIndex()), BackfillEvents.OUTAGE_MOVE, new OutageMove(move.edgeIndex(), false));
        } else {
            send(dev.getId(), CloudSim.getMinTimeBetweenEvents(), BackfillEvents.LINK_UP);
            send(getId(), drawUpSpell(move.edgeIndex()), BackfillEvents.OUTAGE_MOVE, new OutageMove(move.edgeIndex(), true));
        }
    }

    private void handleStorm() {
        int n = p.numEdges;

        // Only currently-connected edges can be taken down by this storm.
        List<Integer> candidates = new ArrayList<>();
        for (EdgeServerDevice dev : edges) {
            if (dev.isLinkUp()) candidates.add(dev.edgeIndex);
        }
        Collections.shuffle(candidates, rng);
        int m = (int) Math.max(1, Math.round(p.cohortFraction * n));
        int size = Math.min(m, candidates.size());

        for (int k = 0; k < size; k++) {
            int idx = candidates.get(k);
            EdgeServerDevice dev = edges.get(idx);
            send(dev.getId(), CloudSim.getMinTimeBetweenEvents(), BackfillEvents.LINK_DOWN);
            // Same mean, small cv => the storm's members reconnect within a short window.
            send(getId(), drawDownSpellCohort(), BackfillEvents.OUTAGE_MOVE, new OutageMove(idx, false));
        }
        schedule(getId(), p.cohortPeriod, BackfillEvents.STORM_MOVE, null);
    }

    // ----------------------------------------------------------------------------- draws

    /** Exponential link-up spell duration for one edge: its own Poisson drop process at rate
     *  {@code p.dropRateOfEdge(edge)} — every edge has its own drop probability. */
    private double drawUpSpell(int edge) {
        double mean = 1.0 / Math.max(1e-9, p.dropRateOfEdge(edge));
        return expDraw(mean);
    }

    /** Log-normal down-spell for one edge with its own mean {@code p.downMeanOfEdge(edge)}
     *  (wide cv => scattered reconnection). */
    private double drawDownSpell(int edge) {
        return lognormalDraw(p.downMeanOfEdge(edge), Math.max(0.01, p.downCV));
    }

    /** Log-normal down-spell with the cohort's small cv (light-tailed => aligned reconnection).
     *  Storms are fleet-wide events, so the per-edge down-mean spread does not apply. */
    private double drawDownSpellCohort() {
        return lognormalDraw(p.downMean, Math.max(0.01, p.cohortCV));
    }

    private double expDraw(double mean) {
        double u = rng.nextDouble();
        if (u <= 0.0) u = 1e-12;
        return Math.max(1e-6, -mean * Math.log(u));
    }

    private double lognormalDraw(double mean, double cv) {
        double sigma2 = Math.log(1.0 + cv * cv);
        double mu = Math.log(Math.max(1e-9, mean)) - sigma2 / 2.0;
        double z = rng.nextGaussian();
        return Math.max(1e-6, Math.exp(mu + Math.sqrt(sigma2) * z));
    }

    // ------------------------------------------------------------------------ validation

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0)
            throw new IllegalArgumentException(name + " must be finite and > 0, got " + value);
    }

    private static void requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0)
            throw new IllegalArgumentException(name + " must be finite and >= 0, got " + value);
    }

    private static void requireUnitRange(double value, String name) {
        if (!(value >= 0 && value <= 1))
            throw new IllegalArgumentException(name + " must be in [0, 1], got " + value);
    }
}

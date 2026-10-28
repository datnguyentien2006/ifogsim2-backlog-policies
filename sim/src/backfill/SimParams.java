package backfill;

import java.util.ArrayList;
import java.util.List;

/**
 * Complete configuration of one backfill-simulation scenario.
 *
 * <p>Model semantics (see docs/MODEL.md for the full spec):</p>
 * <ul>
 *   <li>1 "data unit" = 1 data tuple (one reading produced by an edge server).</li>
 *   <li>The central receiving center can ingest up to {@link #centerCapacity} units per second.
 *       The scheduler enforces this as a token budget of {@code (int) round(centerCapacity * decisionInterval)}
 *       units per decision tick.</li>
 *   <li>Each edge generates data at a configured per-edge rate (Poisson arrivals). While its link is
 *       down, generated data accumulates locally (backfill backlog). Units generated while the link
 *       is up are "new data"; units generated while it is down are "accumulated data".</li>
 * </ul>
 *
 * <p>The policy layer only sees this model: per-edge queues (new/accumulated, generation-time
 * ordered heads), connectivity state, and a token budget. Policies decide how tokens are spent;
 * {@code SchedulerEntity} executes those decisions. Security: nothing else may pop from the queues.</p>
 */
public class SimParams {

    public enum ImbalanceMode { EVEN, ZIPF, DOMINANT }
    public enum OutageMode { SCATTERED, COHORT }

    // ---------------- topology & time ----------------
    /** Scenario label used in file names and report tables. */
    public String name = "base";
    /** Number of edge servers (edge fog devices) reporting to one receiving center. */
    public int numEdges = 6;
    /** Effective simulated data window in seconds (measured from the first data unit, warmup excluded). */
    public double simDuration = 600.0;
    /** Decision tick interval DT of the scheduling controller, in seconds. */
    public double decisionInterval = 0.1;

    // ---------------- receiving center / load ----------------
    /** Center ingest capacity in units/second (the shared bottleneck). */
    public double centerCapacity = 1200.0;
    /** Workload: total new-data rate as a fraction of the center capacity (rho = sum(lambda_i) / C). */
    public double loadFactor = 0.7;

    /** How the per-edge generation rates are split (drives backlog imbalance after outages). */
    public ImbalanceMode imbalance = ImbalanceMode.EVEN;
    public double zipfAlpha = 1.0;
    public double dominantShare = 0.6;

    // ---------------- outage generator ----------------
    /** SCATTERED: independent per-edge outage clocks. COHORT: groups of edges drop/reconnect together. */
    public OutageMode outageMode = OutageMode.SCATTERED;
    /** SCATTERED mode: mean drops per edge per second (Poisson rate of the link-up spell length). */
    public double dropRatePerEdge = 0.01;
    /** Mean down-spell duration in seconds (log-normal with the cv below). */
    public double downMean = 20.0;
    /** Coefficient of variation of the down-spell duration (log-normal). */
    public double downCV = 0.8;
    /** COHORT mode: seconds between storms; a random {@link #cohortFraction} of the edges drops each time. */
    public double cohortPeriod = 120.0;
    /** COHORT mode: fraction of edges joining each storm. */
    public double cohortFraction = 0.5;
    /** COHORT mode: cv of member reconnect offsets within one storm (small => simultaneous reconnection). */
    public double cohortCV = 0.15;

    // ---------------- policy knobs (equal inputs for every policy) ----------------
    /** Rate-limit policy: fixed backfill rate R = rateLimitFactor * centerCapacity (units/s),
     *  independent of the backlog size; leftover capacity goes to new data. */
    public double rateLimitFactor = 0.5;
    /** New-first policy: fixed backfill quota q = quotaFactor * centerCapacity (units/s). */
    public double backfillQuotaFactor = 0.05;

    // ---------------- transport plumbing (defaults sized so the shared center is the ONLY binding capacity) ----------------
    /** Per-tuple network "file size" (1 unit). Transmit time = tupleNwLength / uplinkBandwidth. */
    public double tupleNwLength = 1000.0;
    /** Per-tuple CPU length in MI at the receiving module. */
    public double tupleCpuLength = 10.0;
    public double uplinkBandwidth = 5_000_000.0;   // 0.2 ms per tuple
    public double downlinkBandwidth = 5_000_000.0;
    public double edgeUplinkLatency = 0.01;        // s, edge -> center
    public double sensorLatency = 0.005;           // s, sensor -> edge device
    public double edgeMips = 20_000;
    public double centerMips = 120_000;
    public int edgeRamMb = 20_000;
    public int centerRamMb = 40_000;
    public double edgeBusyPowerW = 87.53;
    public double edgeIdlePowerW = 37.0;
    public double centerBusyPowerW = 250.0;
    public double centerIdlePowerW = 130.0;

    // ---------------- reproducibility & output ----------------
    /** Base random seed. Every policy run of the same scenario uses the SAME seed, hence the SAME
     *  arrival streams and the SAME outage timeline (fair comparison requirement of the project). */
    public long seed = 42;
    /** Write a per-unit detail CSV (genTime, arrivalTime, edge, isNew, delay) for this run. */
    public boolean writeUnitsCsv = false;

    // ------------------------------------------------------------------

    public double totalNewRate() { return loadFactor * centerCapacity; }

    /** Per-edge new-data generation rate lambda_i (units/s), implementing the imbalance mode. */
    public double rateOfEdge(int i) {
        int n = numEdges;
        double total = totalNewRate();
        return switch (imbalance) {
            case EVEN -> total / n;
            case ZIPF -> {
                double z = 0.0;
                for (int k = 0; k < n; k++) z += 1.0 / Math.pow(1.0 + k, zipfAlpha);
                yield total * (1.0 / Math.pow(1.0 + i, zipfAlpha)) / z;
            }
            case DOMINANT -> (i == 0)
                    ? dominantShare * total
                    : (1.0 - dominantShare) * total / Math.max(1, n - 1);
        };
    }

    /** Token budget available to the scheduler each decision tick (shared center capacity). */
    public int centerTokensPerTick() {
        return (int) Math.max(1, Math.round(centerCapacity * decisionInterval));
    }

    /** Rate-limit policy: fixed BACKFILL share per decision tick, residual tokens go to new data. */
    public int rateLimitTokensPerTick() {
        return (int) Math.max(1, Math.round(centerCapacity * decisionInterval * rateLimitFactor));
    }

    /** Fixed backfill quota per decision tick for the new-data-first policy. */
    public int backfillQuotaTokensPerTick() {
        return (int) Math.max(1, Math.round(centerCapacity * decisionInterval * backfillQuotaFactor));
    }

    public SimParams copy() {
        SimParams p = new SimParams();
        shallow(p);
        return p;
    }

    private void shallow(SimParams p) {
        p.name = name; p.numEdges = numEdges; p.simDuration = simDuration; p.decisionInterval = decisionInterval;
        p.centerCapacity = centerCapacity; p.loadFactor = loadFactor;
        p.imbalance = imbalance; p.zipfAlpha = zipfAlpha; p.dominantShare = dominantShare;
        p.outageMode = outageMode; p.dropRatePerEdge = dropRatePerEdge; p.downMean = downMean; p.downCV = downCV;
        p.cohortPeriod = cohortPeriod; p.cohortFraction = cohortFraction; p.cohortCV = cohortCV;
        p.rateLimitFactor = rateLimitFactor; p.backfillQuotaFactor = backfillQuotaFactor;
        p.tupleNwLength = tupleNwLength; p.tupleCpuLength = tupleCpuLength;
        p.uplinkBandwidth = uplinkBandwidth; p.downlinkBandwidth = downlinkBandwidth;
        p.edgeUplinkLatency = edgeUplinkLatency; p.sensorLatency = sensorLatency;
        p.edgeMips = edgeMips; p.centerMips = centerMips; p.edgeRamMb = edgeRamMb; p.centerRamMb = centerRamMb;
        p.edgeBusyPowerW = edgeBusyPowerW; p.edgeIdlePowerW = edgeIdlePowerW;
        p.centerBusyPowerW = centerBusyPowerW; p.centerIdlePowerW = centerIdlePowerW;
        p.seed = seed; p.writeUnitsCsv = writeUnitsCsv;
    }

    @Override public String toString() {
        return name + "[N=" + numEdges + ", rho=" + loadFactor + ", imb=" + imbalance
                + ", outage=" + outageMode + ", seed=" + seed + "]";
    }
}

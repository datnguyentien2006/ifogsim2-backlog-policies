package backfill.metrics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.cloudbus.cloudsim.core.CloudSim;

import backfill.SimParams;
import backfill.data.EgressUnit;
import backfill.util.Csv;

/**
 * Per-run accounting registry for every data unit along its life cycle:
 * generated on an edge -> granted by the scheduler -> transmitted -> arrived at the center.
 *
 * <p>Units are keyed by the iFogSim tuple id ({@code cloudletId}), making the registry the
 * shared-memory channel between the edge devices (generation), the scheduler (SINK accounting)
 * and the receiving-center device (arrival). Policies never touch it; they only make grant
 * decisions, which keeps measured differences attributable to the policies themselves.</p>
 */
public final class UnitRegistry {

    private UnitRegistry() {}

    // ---- run identity ---------------------------------------------------------
    private static SimParams p;
    private static String policyKey;
    private static long seed;

    // ---- live units  ------------------------------------------------------------
    private static final Map<Integer, EgressUnit> live = new HashMap<>();

    // ---- generation / delivery counters -----------------------------------------
    private static long generatedNew, generatedBack;
    private static long deliveredNew, deliveredBack;
    private static long[] genNewEdgeA = new long[1];
    private static long[] genBackEdgeA = new long[1];
    private static long[] deliveredBackA = new long[1];

    // ---- delay statistics (bounded-memory reservoir sampling) --------------------
    private static final int RESERVOIR = 12_000;
    private static final double[] delaysNew = new double[RESERVOIR];
    private static final double[] delaysBack = new double[RESERVOIR];
    private static int nNew = 0, inResNew = 0;      // totals observed vs. currently held
    private static int nBack = 0, inResBack = 0;
    private static double sumDelayNew = 0, sumDelayBack = 0;

    // ---- backlog & throughput time series ---------------------------------------
    private static double backlogArea = 0;      // units * s (integrated per tick)
    private static double backlogPeak = 0;
    private static int arrivalsTick = 0;        // arrivals since previous tick
    private static double simDataStart = -1;    // first generated unit
    private static double simEndTime = -1;

    // ---- outage & drain ----------------------------------------------------------
    private static long outageCount = 0;
    private static long restoresWithBacklog = 0;
    private static double[] drainStartA = new double[1];
    private static boolean[] drainPendingA = new boolean[1];
    private static final List<Double> drainDurations = new ArrayList<>();

    // ---- writers ------------------------------------------------------------------
    private static Csv tickCsv;
    private static Csv unitCsv;
    private static Csv summaryCsv;

    private static final Random reservoirRng = new Random();

    // ================================================================================

    public static void beginRun(SimParams params, String policy, long runSeed, String dir, String runName) throws IOException {
        p = params;
        policyKey = policy;
        seed = runSeed;

        int n = Math.max(1, p.numEdges);
        genNewEdgeA = new long[n];
        genBackEdgeA = new long[n];
        deliveredBackA = new long[n];
        drainStartA = new double[n];
        drainPendingA = new boolean[n];
        Arrays.fill(drainStartA, -1.0);

        live.clear();
        generatedNew = generatedBack = 0;
        deliveredNew = deliveredBack = 0;
        nNew = inResNew = nBack = inResBack = 0;
        sumDelayNew = sumDelayBack = 0;
        backlogArea = backlogPeak = 0;
        arrivalsTick = 0;
        simDataStart = -1;
        simEndTime = -1;
        outageCount = 0;
        restoresWithBacklog = 0;
        drainDurations.clear();
        reservoirRng.setSeed(seed * 7919L + 13);

        String filePrefix = dir + "/" + runName;
        List<String> header = new ArrayList<>(List.of(
                "t", "grantedNew", "grantedBack", "arrivals", "arrivalsRate",
                "backlogNewTot", "backlogBackTot", "backlogTot"));
        for (int i = 0; i < n; i++) header.add("e" + i + "_new");
        for (int i = 0; i < n; i++) header.add("e" + i + "_back");
        for (int i = 0; i < n; i++) header.add("e" + i + "_up");
        tickCsv = new Csv(filePrefix + "_tick.csv", String.join(",", header));
        unitCsv = p.writeUnitsCsv
                ? new Csv(filePrefix + "_units.csv", "cloudletId,edge,genT,arriveT,isNew,delay")
                : null;
        summaryCsv = new Csv(filePrefix + "_run.csv",
                "policy,scenario,seed,N,rho,C,imbalance,outageMode,dataStart,dataEnd,"
                + "genNew,genBack,delivNew,delivBack,leftNew,leftBack,"
                + "newDelayMean,newDelayP50,newDelayP95,newDelayP99,"
                + "backDelayMean,backDelayP50,backDelayP95,backDelayP99,"
                + "backlogArea,backlogPeak,outages,restoresWithBacklog,drains,drainMean,drainMax,jainBackfill");
    }

    // ================================================================================

    /** Unit generated on an edge; called ONLY by EdgeServerDevice.sendUp. */
    public static void onGenerated(EgressUnit u) {
        live.put(u.cloudletId(), u);
        if (u.isNew) { generatedNew++; genNewEdgeA[u.edgeIndex]++; }
        else         { generatedBack++; genBackEdgeA[u.edgeIndex]++; }
        if (simDataStart < 0) simDataStart = u.genTime;
    }

    /** Unit physically arrived at the receiving center; called ONLY by CentralReceiverDevice. */
    public static void onArrived(int cloudletId) {
        EgressUnit u = live.remove(cloudletId);
        if (u == null) return;
        double now = CloudSim.clock();
        double latency = Math.max(0, now - u.genTime);
        arrivalsTick++;
        if (u.isNew) {
            deliveredNew++;
            onDelaySample(delaysNew, true, latency);
        } else {
            deliveredBack++;
            deliveredBackA[u.edgeIndex]++;
            onDelaySample(delaysBack, false, latency);
        }
        if (unitCsv != null) {
            unitCsv.row(cloudletId, u.edgeIndex, fmt(u.genTime), fmt(now), u.isNew ? 1 : 0, fmt(latency));
        }
    }

    private static void onDelaySample(double[] reservoir, boolean isNew, double latency) {
        if (isNew) {
            nNew++; sumDelayNew += latency;
            if (inResNew < RESERVOIR) { reservoir[inResNew++] = latency; }
            else { int j = reservoirRng.nextInt(nNew); if (j < RESERVOIR) reservoir[j] = latency; }
        } else {
            nBack++; sumDelayBack += latency;
            if (inResBack < RESERVOIR) { reservoir[inResBack++] = latency; }
            else { int j = reservoirRng.nextInt(nBack); if (j < RESERVOIR) reservoir[j] = latency; }
        }
    }

    /**
     * Edge link state change; {@code outageStarted} true = this is a LINK DOWN event worth
     * counting as an outage (not just reconnection). {@code backlogAtChange} = the edge's
     * accumulated backlog size just before the change (drain-timer trigger).
     */
    public static void onLinkChange(int edgeIndex, double now, boolean up, int backlogAtChange, boolean outageStarted) {
        if (up) {
            if (backlogAtChange > 0) {
                restoresWithBacklog++;
                drainPendingA[edgeIndex] = true;
                drainStartA[edgeIndex] = now;
            }
        }
        if (outageStarted) outageCount++;
    }

    /**
     * Per-tick snapshot from SchedulerEntity, called AFTER allocation:
     * writes the tick row (era-listed edge backlog arrays) and advances drain-time detection.
     */
    public static void onTick(double now, int grantedNew, int grantedBack, boolean[] up,
                              int[] backlogNew, int[] backlogBack) {
        int n = p.numEdges;
        int totNew = 0, totBack = 0;
        for (int i = 0; i < n; i++) { totNew += backlogNew[i]; totBack += backlogBack[i]; }
        int tot = totNew + totBack;
        backlogArea += tot * p.decisionInterval;
        if (tot > backlogPeak) backlogPeak = tot;

        StringBuilder cells = new StringBuilder();
        cells.append(fmt(now)).append(',').append(grantedNew).append(',').append(grantedBack).append(',')
             .append(arrivalsTick).append(',').append(fmt(arrivalsTick / p.decisionInterval)).append(',')
             .append(totNew).append(',').append(totBack).append(',').append(tot);
        for (int i = 0; i < n; i++) cells.append(',').append(backlogNew[i]);
        for (int i = 0; i < n; i++) cells.append(',').append(backlogBack[i]);
        for (int i = 0; i < n; i++) cells.append(',').append(up[i] ? 1 : 0);
        tickCsv.rawLine(cells.toString());

        // drain detection: a reconnect burst with backlog is "drained" when the edge hits zero backlog
        for (int i = 0; i < n; i++) {
            if (drainPendingA[i]) {
                boolean empty = backlogNew[i] + backlogBack[i] == 0;
                if (empty) {
                    drainPendingA[i] = false;
                    drainDurations.add(Math.max(0, now - drainStartA[i]));
                }
            }
        }
        arrivalsTick = 0;
    }

    /** Finalize one run: writes its summary row and closes the CSV files. */
    public static void endRun(double dataEnd) {
        simEndTime = dataEnd;
        long leftNew = 0, leftBack = 0;
        for (EgressUnit u : live.values()) { if (u.isNew) leftNew++; else leftBack++; }

        double window = Math.max(1e-9, simEndTime - simDataStart);

        double[] pctNew = percentiles(delaysNew, inResNew);
        double[] pctBack = percentiles(delaysBack, inResBack);
        double meanNew = nNew > 0 ? sumDelayNew / nNew : 0;
        double meanBack = nBack > 0 ? sumDelayBack / nBack : 0;

        double jain = jainDeliveredBack();
        double drainMean = drainDurations.isEmpty() ? 0 : avg(drainDurations);
        double drainMax = drainDurations.isEmpty() ? 0 : maxOf(drainDurations);

        summaryCsv.row(policyKey, p.name, seed, p.numEdges, fmt(p.loadFactor), fmt(p.centerCapacity),
                p.imbalance, p.outageMode, fmt(simDataStart), fmt(simEndTime),
                generatedNew, generatedBack, deliveredNew, deliveredBack, leftNew, leftBack,
                fmt(meanNew), fmt(pctNew[0]), fmt(pctNew[1]), fmt(pctNew[2]),
                fmt(meanBack), fmt(pctBack[0]), fmt(pctBack[1]), fmt(pctBack[2]),
                fmt(backlogArea), fmt(backlogPeak),
                outageCount, restoresWithBacklog, drainDurations.size(),
                fmt(drainMean), fmt(drainMax), fmt(jain));
        closeAll();
    }

    private static void closeAll() {
        if (tickCsv != null) { tickCsv.close(); tickCsv = null; }
        if (unitCsv != null) { unitCsv.close(); unitCsv = null; }
        if (summaryCsv != null) { summaryCsv.close(); summaryCsv = null; }
    }

    // ---- helpers ---------------------------------------------------------------

    private static double[] percentiles(double[] reservoir, int held) {
        double[] out = new double[3];
        if (held == 0) return out;
        double[] copy = Arrays.copyOf(reservoir, held);
        Arrays.sort(copy);
        out[0] = pct(copy, 0.50);
        out[1] = pct(copy, 0.95);
        out[2] = pct(copy, 0.99);
        return out;
    }

    private static double pct(double[] sorted, double q) {
        return sorted[(int) Math.round(q * (sorted.length - 1))];
    }

    private static double avg(List<Double> xs) {
        double s = 0;
        for (double x : xs) s += x;
        return s / xs.size();
    }

    private static double maxOf(List<Double> xs) {
        double m = 0;
        for (double x : xs) if (x > m) m = x;
        return m;
    }

    /** Jain fairness index of the per-edge delivered/generated ratio for accumulated data. */
    private static double jainDeliveredBack() {
        int n = p.numEdges;
        double sum = 0, sumSq = 0;
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (genBackEdgeA[i] == 0) continue;
            double r = (double) deliveredBackA[i] / genBackEdgeA[i];
            sum += r; sumSq += r * r; m++;
        }
        if (m == 0 || sum == 0) return 1.0;   // nothing to differentiate yet (or nothing delivered)
        return (sum * sum) / (m * sumSq);
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.6f", d);
    }
}

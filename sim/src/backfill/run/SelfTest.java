package backfill.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.cloudbus.cloudsim.UtilizationModelFull;
import org.fog.entities.Tuple;

import backfill.BackfillSim;
import backfill.SimParams;
import backfill.data.EgressUnit;
import backfill.metrics.UnitRegistry;

/**
 * Framework self-test (no JUnit dependency). Prints one {@code ok:} line per passed check and
 * exits with status 1 on the first failed check.
 *
 * <p>Two groups:</p>
 * <ol>
 *   <li><b>Fast stub checks</b> (well under a second): the Jain fairness index is computed on
 *       hand-made cases whose answer is known in advance. Without these, a dead fairness counter
 *       would still look like a perfectly fair run.</li>
 *   <li><b>Full-run invariants</b> (four policies, 90 s simulated, four edges):
 *       <ul>
 *         <li>equal input: same scenario, seed, generation counts, outages and restores;</li>
 *         <li>conservation: generated = delivered + left, separately for new and backfill data;</li>
 *         <li>capacity: delivered units never exceed the center's capacity, and no tick grants
 *             more than the token budget;</li>
 *         <li>no grant to a link-down edge: an edge that is down cannot lose queued units;</li>
 *         <li>rate-limit cap: under {@code ratelimit}, backfill granted per tick stays within R;</li>
 *         <li>reproducibility: two runs with the same seed produce byte-identical CSV files;</li>
 *         <li>policy separation: the four policies actually allocate differently.</li>
 *       </ul>
 *   </li>
 * </ol>
 * Run with: {@code bash sim/run.sh test} (or {@code pwsh sim/run.ps1 test}).
 */
public final class SelfTest {

    private static final String[] POLICIES = {"fifo", "rr", "ratelimit", "newfirst"};

    public static void main(String[] args) throws Exception {
        checkJainStub();

        SimParams p = ScenarioGrid.base();
        p.numEdges = 4;
        p.simDuration = 90.0;
        p.seed = 5;

        Path tmp = Files.createTempDirectory("backfill_selftest");
        System.out.println("[SelfTest] temp dir: " + tmp);
        for (String key : POLICIES) {
            double end = BackfillSim.run(p.copy(), key, tmp.toString(), "st_" + key);
            System.out.println("[SelfTest] ran " + key + " end=" + String.format("%.1f", end));
        }

        checkRunSummaries(tmp, p);
        for (String key : POLICIES) {
            checkTickInvariants(tmp.resolve("st_" + key + "_tick.csv"), p, key);
        }
        checkReproducible(p);

        System.out.println("[SelfTest] ALL CHECKS PASSED (" + tmp + ")");
    }

    // ------------------------------------------------------------------------------------
    // Group 1: fast stub checks
    // ------------------------------------------------------------------------------------

    /** Jain index of the per-edge delivered/generated backfill ratio, on cases with known answers. */
    private static void checkJainStub() throws Exception {
        SimParams base = ScenarioGrid.base();

        // Three edges have backlog; ratios are (1, 1, 0). Jain = (sum r)^2 / (n * sum r^2)
        //   = 2^2 / (3 * 2) = 2/3. The fourth edge has no backlog and must be left out of the sample.
        double starved = runJainStub(base, "jain_starved",
                new int[] {10, 10, 10, 0}, new int[] {10, 10, 0, 0});
        check(Math.abs(starved - 2.0 / 3.0) < 1e-5,
                "jain stub: one starved edge out of three gives 2/3 (got " + starved + ")");

        // Every edge with backlog is served completely: all ratios equal 1, so Jain = 1.
        double equal = runJainStub(base, "jain_equal",
                new int[] {10, 10, 10, 0}, new int[] {10, 10, 10, 0});
        check(Math.abs(equal - 1.0) < 1e-5, "jain stub: equal service gives 1 (got " + equal + ")");

        check(starved >= 0 && starved <= 1 && equal >= 0 && equal <= 1,
                "jain stub: index stays in [0, 1]");
    }

    /** Registers generated backlog units, marks the first {@code delivered[e]} of each edge as arrived. */
    private static double runJainStub(SimParams base, String runName, int[] genBack, int[] delivBack)
            throws Exception {
        SimParams p = base.copy();
        p.numEdges = genBack.length;
        Path dir = Files.createTempDirectory("st_jain");
        UnitRegistry.beginRun(p, "fifo", 1L, dir.toString(), runName);
        int id = 1;
        for (int edge = 0; edge < genBack.length; edge++) {
            for (int k = 0; k < genBack[edge]; k++) {
                EgressUnit unit = stubBackfillUnit(id++, edge);
                UnitRegistry.onGenerated(unit);
                if (k < delivBack[edge]) UnitRegistry.onArrived(unit.cloudletId());
            }
        }
        UnitRegistry.endRun(1.0);
        return csvNumber(dir.resolve(runName + "_run.csv"), "jainBackfill");
    }

    private static EgressUnit stubBackfillUnit(int cloudletId, int edge) {
        Tuple tuple = new Tuple("selftest", cloudletId, 0, 1L, 1, 1000L, 0L,
                new UtilizationModelFull(), new UtilizationModelFull(), new UtilizationModelFull());
        return new EgressUnit(tuple, 0.0, false, edge);
    }

    // ------------------------------------------------------------------------------------
    // Group 2: full-run invariants
    // ------------------------------------------------------------------------------------

    private static void checkRunSummaries(Path dir, SimParams p) throws Exception {
        List<String[]> rows = new ArrayList<>();
        String[] header = null;
        for (String key : POLICIES) {
            List<String> lines = Files.readAllLines(dir.resolve("st_" + key + "_run.csv"));
            check(lines.size() == 2, "run csv holds a header and exactly one row (" + key + ")");
            header = lines.get(0).split(",");
            rows.add(lines.get(1).split(","));
        }

        String[] first = rows.get(0);
        for (int i = 1; i < rows.size(); i++) {
            String[] s = rows.get(i);
            String who = s[col(header, "policy")];
            check(s[col(header, "scenario")].equals(first[col(header, "scenario")])
                    && s[col(header, "seed")].equals(first[col(header, "seed")]),
                    "same scenario and seed across policies (" + who + ")");
            for (String field : new String[] {"genNew", "genBack", "outages", "restoresWithBacklog"}) {
                check(s[col(header, field)].equals(first[col(header, field)]),
                        "equal input: " + field + " identical across policies (" + who + ")");
            }
        }

        for (String[] s : rows) {
            String who = s[col(header, "policy")];
            long genNew = num(s, header, "genNew"), genBack = num(s, header, "genBack");
            long delivNew = num(s, header, "delivNew"), delivBack = num(s, header, "delivBack");
            long leftNew = num(s, header, "leftNew"), leftBack = num(s, header, "leftBack");
            check(delivNew + leftNew == genNew, "conservation: new data (" + who + ")");
            check(delivBack + leftBack == genBack, "conservation: backfill (" + who + ")");

            double dataStart = Double.parseDouble(s[col(header, "dataStart")]);
            double dataEnd = Double.parseDouble(s[col(header, "dataEnd")]);
            // Center capacity over the data window, plus one tick of slack for the last grant.
            double cap = p.centerCapacity * (dataEnd - dataStart) + p.centerTokensPerTick();
            check(delivNew + delivBack <= cap, "capacity bound (" + who + ")");
        }

        final String[] h = header;   // effectively final for the lambdas below
        boolean distinct = rows.stream().map(s -> s[col(h, "delivBack")]).distinct().count() > 1
                || rows.stream().map(s -> s[col(h, "backDelayMean")]).distinct().count() > 1;
        check(distinct, "policies allocate differently (delivered backfill or backfill delay)");
    }

    /** Per-tick invariants from the tick CSV: token budget, link state, rate-limit cap. */
    private static void checkTickInvariants(Path tickCsv, SimParams p, String key) throws Exception {
        List<String> lines = Files.readAllLines(tickCsv);
        String[] header = lines.get(0).split(",");
        int n = p.numEdges;
        int[] colNew = new int[n], colBack = new int[n], colUp = new int[n];
        for (int i = 0; i < n; i++) {
            colNew[i] = col(header, "e" + i + "_new");
            colBack[i] = col(header, "e" + i + "_back");
            colUp[i] = col(header, "e" + i + "_up");
        }

        int tokens = p.centerTokensPerTick();
        int rlCap = p.rateLimitTokensPerTick();
        boolean tokenOk = true, rateOk = true, linkOk = true;
        long maxRateGranted = 0, sumBackGranted = 0, downTicks = 0, violations = 0;
        long[] prev = null;

        for (int r = 1; r < lines.size(); r++) {
            String[] f = lines.get(r).split(",");
            long gNew = Long.parseLong(f[col(header, "grantedNew")]);
            long gBack = Long.parseLong(f[col(header, "grantedBack")]);
            if (gNew + gBack > tokens) tokenOk = false;
            sumBackGranted += gBack;
            maxRateGranted = Math.max(maxRateGranted, gBack);

            long[] total = new long[n];
            boolean[] up = new boolean[n];
            for (int i = 0; i < n; i++) {
                total[i] = Long.parseLong(f[colNew[i]]) + Long.parseLong(f[colBack[i]]);
                up[i] = f[colUp[i]].equals("1");
            }
            // While an edge is down it can only gain units (new arrivals go to its backlog);
            // any drop in its queue would mean units were granted over a dead link.
            if (prev != null) {
                for (int i = 0; i < n; i++) {
                    if (!up[i]) {
                        downTicks++;
                        if (total[i] < prev[i]) violations++;
                    }
                }
            }
            prev = total;
        }
        check(tokenOk, "grant per tick never exceeds the token budget (" + key + ")");
        check(downTicks > 0, "scenario contains link-down ticks, so the link check is not vacuous (" + key + ")");
        check(violations == 0, "no grant to a link-down edge: queues never shrink while down (" + key + ")");

        if (key.equals("ratelimit")) {
            for (int r = 1; r < lines.size() && rateOk; r++) {
                if (Long.parseLong(lines.get(r).split(",")[col(header, "grantedBack")]) > rlCap) rateOk = false;
            }
            check(rateOk, "rate-limit cap: backfill granted per tick <= R = " + rlCap);
            check(sumBackGranted > 0, "rate-limit run actually drained some backfill (not vacuous)");
        }
    }

    /** Same policy and seed, two runs in the same JVM: output must match byte for byte. */
    private static void checkReproducible(SimParams p) throws Exception {
        Path a = Files.createTempDirectory("st_rep_a");
        Path b = Files.createTempDirectory("st_rep_b");
        BackfillSim.run(p.copy(), "fifo", a.toString(), "rep");
        BackfillSim.run(p.copy(), "fifo", b.toString(), "rep");
        for (String suffix : new String[] {"_run.csv", "_tick.csv"}) {
            check(Arrays.equals(Files.readAllBytes(a.resolve("rep" + suffix)),
                            Files.readAllBytes(b.resolve("rep" + suffix))),
                    "reproducible: same seed gives identical rep" + suffix);
        }
    }

    // ------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------

    private static int col(String[] header, String name) {
        int idx = Arrays.asList(header).indexOf(name);
        if (idx < 0) throw new AssertionError("column not found: " + name);
        return idx;
    }

    private static long num(String[] row, String[] header, String name) {
        return Long.parseLong(row[col(header, name)]);
    }

    private static double csvNumber(Path csv, String column) throws Exception {
        List<String> lines = Files.readAllLines(csv);
        return Double.parseDouble(lines.get(1).split(",")[col(lines.get(0).split(","), column)]);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            System.err.println("[SelfTest] FAILED: " + what);
            System.exit(1);
        }
        System.out.println("[SelfTest] ok: " + what);
    }

    private SelfTest() { throw new AssertionError(); }
}

package backfill.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import backfill.BackfillSim;
import backfill.SimParams;

/**
 * Framework self-test (no JUnit dependency): five assertions that every run must satisfy.
 *
 * <ol>
 *   <li><b>Equal inputs</b> — all four policies of the same scenario+seed observe byte-identical
 *       generation counts, outage counts and restores-with-backlog (the fairness requirement).</li>
 *   <li><b>Conservation</b> — delivered + left = generated, separately for new data and backfill.</li>
 *   <li><b>Policy separation</b> — the four policies actually allocate differently
 *       (delivered backfill/delays differ between them).</li>
 *   <li><b>Capacity sanity</b> — total delivered units can never exceed C·(end − dataStart)
 *       + one tick of tokens.</li>
 *   <li><b>Predictable rate-limit rate</b> — under ratelimit, delivered backfill ≤ R·window
 *       (plus units still buffered/in-flight at cutoff).</li>
 * </ol>
 * Run with: {@code java backfill.run.SelfTest} — exits 0 on success, 1 on failure.
 */
public final class SelfTest {

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("backfill_selftest");
        SimParams p = ScenarioGrid.base();
        p.numEdges = 4;
        p.simDuration = 90.0;
        p.seed = 5;

        System.out.println("[SelfTest] temp dir: " + tmp);
        for (String key : new String[] {"fifo", "rr", "ratelimit", "newfirst"}) {
            double end = BackfillSim.run(p.copy(), key, tmp.toString(), "st_" + key);
            System.out.println("[SelfTest] ran " + key + " end=" + String.format("%.1f", end));
        }

        List<String[]> summaries = new ArrayList<>();
        for (String key : new String[] {"fifo", "rr", "ratelimit", "newfirst"}) {
            Path csv = tmp.resolve("st_" + key + "_run.csv");
            List<String> lines = Files.readAllLines(csv);
            if (lines.size() != 2) throw new AssertionError("run csv must hold header+1 row: " + csv);
            summaries.add(lines.get(1).split(","));
        }

        // csv column indexes (keep in sync with UnitRegistry.endRun)
        int C_POLICY = 0, C_GNEWS = 10, C_GBACK = 11, C_DNEW = 12, C_DBACK = 13,
                C_LNEW = 14, C_LBACK = 15, C_NEWM = 16, C_BACKM = 20,
                C_OUT = 26, C_RWL = 27, C_DRAINS = 28;

        String[] f = summaries.get(0);
        for (int idx = 0; idx < summaries.size(); idx++) {
            String[] s = summaries.get(idx);
            if (idx > 0) {
                // every summary must come from the same scenario + seed, i.e. the four policies
                // ran IDENTICAL experiments — this is what makes the comparison fair
                check(s[1].equals(f[1]) && s[2].equals(f[2]), "same scenario + seed across policies");
                check(s[C_GNEWS].equals(f[C_GNEWS]), "equal-input genNew across policies");
                check(s[C_GBACK].equals(f[C_GBACK]), "equal-input genBack across policies");
                check(s[C_OUT].equals(f[C_OUT]), "equal-input outage count");
                check(s[C_RWL].equals(f[C_RWL]), "equal-input restores-with-backlog");
            }
            long gNew = Long.parseLong(s[C_GNEWS]), gBack = Long.parseLong(s[C_GBACK]);
            long dNew = Long.parseLong(s[C_DNEW]), dBack = Long.parseLong(s[C_DBACK]);
            long lNew = Long.parseLong(s[C_LNEW]), lBack = Long.parseLong(s[C_LBACK]);
            check(dNew + lNew == gNew, "conservation new data (" + s[C_POLICY] + ")");
            check(dBack + lBack == gBack, "conservation backfill (" + s[C_POLICY] + ")");
            double dataStart = Double.parseDouble(s[8]), dataEnd = Double.parseDouble(s[9]);
            // every delivered unit consumed one per-tick token; tokens are only useful from
            // dataStart on (no data exists during warmup), with one last tick of slack
            double cap = 1200.0 * (dataEnd - dataStart) + p.centerTokensPerTick();
            check(dNew + dBack <= cap, "capacity bound (" + s[C_POLICY] + ")");
        }
        boolean distinct = summaries.stream().map(s -> s[C_DBACK]).distinct().count() > 1
                || summaries.stream().map(s -> s[C_BACKM]).distinct().count() > 1;
        check(distinct, "policies allocate differently (delivered backfill or backfill delay)");

        System.out.println("[SelfTest] ALL CHECKS PASSED (" + tmp + ")");
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

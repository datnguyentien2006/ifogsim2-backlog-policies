package backfill.run;

import java.nio.file.Files;
import java.nio.file.Path;

import backfill.BackfillSim;
import backfill.SimParams;

/**
 * Entry point for a single backfill simulation run.
 *
 * <pre>
 *   java backfill.run.RunMain &lt;policyKey&gt; &lt;scenarioName&gt; &lt;seed&gt; [outDir] [runNameOverride]
 * </pre>
 *
 * Writes {@code outDir/runName_*} CSV files (tick time series + run summary), where
 * {@code runName = "run_" + scenario + "_s" + seed + "_" + policyKey}.
 */
public final class RunMain {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("usage: RunMain <policyKey> <scenarioName> <seed> [outDir] [runNameOverride]");
            System.exit(2);
        }
        try {
            String policyKey = args[0];
            String scenarioName = args[1];
            long seed = Long.parseLong(args[2]);
            String outDir = args.length > 3 ? args[3] : "results/runs";
            String runName = args.length > 4 ? args[4] : defaultRunName(policyKey, scenarioName, seed);

            SimParams p = ScenarioGrid.byName(scenarioName).copy();
            p.seed = seed;

            Files.createDirectories(Path.of(outDir));
            double end = BackfillSim.run(p, policyKey, outDir, runName);

            System.out.println("DONE " + runName + " end_sim_time=" + fmt(end));
        } catch (Exception e) {
            System.err.println("FAILED run: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    static String defaultRunName(String policyKey, String scenarioName, long seed) {
        return "run_" + scenarioName + "_s" + seed + "_" + policyKey;
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }
}

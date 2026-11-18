package backfill.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import backfill.SimParams;

import backfill.policy.SchedulingPolicy;

/**
 * Batch driver: runs every (scenario × seed × policy) combination of the experiment grid,
 * each in its own JVM process (clean iFogSim/CloudSim static state between runs), logging
 * progress to the console. Results land under {@code outRoot/<scenario>/...}.
 *
 * <p>Arguments: {@code outRoot seeds [onlyPolicy] [onlyScenario]}; an empty filter or
 * {@code all} means no filter. System properties:</p>
 * <ul>
 *   <li>{@code backfill.firstSeed} (default 0): seeds run from firstSeed to firstSeed + seeds − 1,
 *       so extra replications can be added without re-running the first ones;</li>
 *   <li>{@code backfill.jobs} (default 1): number of runs executed in parallel (one JVM each);</li>
 *   <li>{@code backfill.ticks} (default true): false deletes each run's per-tick CSV and log
 *       after a successful run, keeping only the run-level CSV (replication batches).</li>
 * </ul>
 */
public final class ExperimentRunner {

    public static void main(String[] args) throws Exception {
        String outRoot = args.length > 0 ? args[0] : "results/runs";
        int seeds = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        String onlyPolicy = filter(args, 2);
        String onlyScenario = filter(args, 3);
        long firstSeed = Long.getLong("backfill.firstSeed", 0L);
        int jobs = Math.max(1, Integer.getInteger("backfill.jobs", 1));
        boolean keepTicks = Boolean.parseBoolean(System.getProperty("backfill.ticks", "true"));

        String javaBin = System.getProperty("java.home") + (System.getProperty("os.name").toLowerCase().contains("win") ? "\\bin\\java.exe" : "/bin/java");
        String cp = System.getProperty("java.class.path");

        List<SimParams> scenarios = ScenarioGrid.grid();
        if (onlyScenario != null) {
            scenarios.removeIf(s -> !s.name.equals(onlyScenario));
        }
        List<String> policies = new ArrayList<>();
        for (SchedulingPolicy p : SchedulingPolicy.standardPolicies()) {
            policies.add(p.key());
        }
        if (onlyPolicy != null) {
            policies.removeIf(value -> !value.equals(onlyPolicy));
        }
        if (scenarios.isEmpty() || policies.isEmpty()) {
            System.out.println("[ExperimentRunner] nothing to run: unknown "
                    + (scenarios.isEmpty() ? "scenario '" + onlyScenario + "'" : "policy '" + onlyPolicy + "'"));
            System.exit(1);
        }

        int total = scenarios.size() * seeds * policies.size();
        Files.createDirectories(Path.of(outRoot));

        System.out.println("[ExperimentRunner] " + total + " runs: " + scenarios.size() + " scenarios x "
                + policies.size() + " policies x " + seeds + " seeds (from seed " + firstSeed + ") -> " + outRoot
                + (jobs > 1 ? " [" + jobs + " parallel]" : "") + (keepTicks ? "" : " [run CSVs only]"));

        AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        for (SimParams scenario : scenarios) {
            String scenarioDir = Path.of(outRoot, scenario.name).toString();
            Files.createDirectories(Path.of(scenarioDir));
            for (String policyKey : policies) {
                for (long seed = firstSeed; seed < firstSeed + seeds; seed++) {
                    String runName = RunMain.defaultRunName(policyKey, scenario.name, seed);
                    String logFile = Path.of(scenarioDir, runName + ".log").toString();
                    List<String> cmd = List.of(
                            javaBin, "-Xmx2g",
                            "-cp", cp,
                            "backfill.run.RunMain", policyKey, scenario.name, String.valueOf(seed),
                            scenarioDir, runName);
                    pool.submit(() -> {
                        try {
                            ProcessBuilder pb = new ProcessBuilder(cmd);
                            pb.redirectErrorStream(true);
                            pb.redirectOutput(new java.io.File(logFile));
                            long t0 = System.nanoTime();
                            int exit = pb.start().waitFor();
                            double secs = (System.nanoTime() - t0) / 1e9;
                            int n = done.incrementAndGet();
                            if (exit == 0) {
                                if (!keepTicks) {
                                    Files.deleteIfExists(Path.of(scenarioDir, runName + "_tick.csv"));
                                    Files.deleteIfExists(Path.of(logFile));
                                }
                                System.out.printf("[%d/%d] OK    %-40s %.1fs%n", n, total, runName, secs);
                            } else {
                                failed.incrementAndGet();
                                System.out.printf("[%d/%d] FAIL  %-40s (see %s)%n", n, total, runName, logFile);
                            }
                        } catch (Exception e) {
                            done.incrementAndGet();
                            failed.incrementAndGet();
                            System.out.println("[ExperimentRunner] FAIL " + runName + ": " + e);
                        }
                    });
                }
            }
        }
        pool.shutdown();
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
        System.out.println("[ExperimentRunner] finished: " + (done.get() - failed.get()) + " ok, " + failed.get() + " failed");
        if (failed.get() > 0) System.exit(1);
    }

    /** Optional positional filter; empty or "all" means no filter. */
    private static String filter(String[] args, int i) {
        if (args.length <= i) return null;
        String v = args[i].trim();
        return v.isEmpty() || v.equalsIgnoreCase("all") ? null : v;
    }

    private ExperimentRunner() {
        throw new AssertionError();
    }
}

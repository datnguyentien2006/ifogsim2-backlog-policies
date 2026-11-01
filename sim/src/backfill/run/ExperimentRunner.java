package backfill.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import backfill.SimParams;

import backfill.policy.SchedulingPolicy;

/**
 * Batch driver: runs every (scenario × seed × policy) combination of the experiment grid,
 * each in its own JVM process (clean iFogSim/CloudSim static state between runs), logging
 * progress to the console. Results land under {@code outRoot/<scenario>/...}.
 */
public final class ExperimentRunner {

    public static void main(String[] args) throws Exception {
        String outRoot = args.length > 0 ? args[0] : "results/runs";
        int seeds = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        String onlyPolicy = args.length > 2 ? args[2] : null;
        String onlyScenario = args.length > 3 ? args[3] : null;

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

        int total = scenarios.size() * seeds * policies.size();
        int done = 0, failed = 0;
        Files.createDirectories(Path.of(outRoot));

        System.out.println("[ExperimentRunner] " + total + " runs: " + scenarios.size() + " scenarios x "
                + policies.size() + " policies x " + seeds + " seeds -> " + outRoot);

        for (SimParams scenario : scenarios) {
            String scenarioDir = Path.of(outRoot, scenario.name).toString();
            Files.createDirectories(Path.of(scenarioDir));
            for (String policyKey : policies) {
                for (long seed = 0; seed < seeds; seed++) {
                    String runName = RunMain.defaultRunName(policyKey, scenario.name, seed);
                    String logFile = Path.of(scenarioDir, runName + ".log").toString();

                    List<String> cmd = List.of(
                            javaBin, "-Xmx2g",
                            "-cp", cp,
                            "backfill.run.RunMain", policyKey, scenario.name, String.valueOf(seed),
                            scenarioDir, runName);
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.redirectErrorStream(true);
                    pb.redirectOutput(new java.io.File(logFile));

                    long t0 = System.nanoTime();
                    Process proc = pb.start();
                    int exit = proc.waitFor();
                    done++;
                    double secs = (System.nanoTime() - t0) / 1e9;
                    if (exit == 0) {
                        System.out.printf("[%d/%d] OK    %-40s %.1fs%n", done, total, runName, secs);
                    } else {
                        failed++;
                        System.out.printf("[%d/%d] FAIL  %-40s (see %s)%n", done, total, runName, logFile);
                    }
                }
            }
        }
        System.out.println("[ExperimentRunner] finished: " + (done - failed) + " ok, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private ExperimentRunner() {
        throw new AssertionError();
    }
}

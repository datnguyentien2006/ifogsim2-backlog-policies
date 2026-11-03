package backfill.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import backfill.BackfillSim;
import backfill.SimParams;
import backfill.SimParams.ImbalanceMode;
import backfill.SimParams.OutageMode;
import backfill.policy.AllocationSink;
import backfill.policy.EdgeQueueView;
import backfill.policy.SchedulingPolicy;

/**
 * Framework self-test (no JUnit dependency). Three independent layers, each catching a
 * different class of bug. Full explanation: {@code docs/SELFTEST.md}.
 *
 * <h3>Part A - policy contract (synthetic queues, no simulation, &lt; 1 s)</h3>
 * Feeds hand-made and random queue states straight into each policy and checks the contract
 * that {@code SchedulingPolicy} documents: never grant to a disconnected edge, never exceed
 * the per-tick budget, never over-grant a queue (applies to EVERY policy returned by
 * {@code SchedulingPolicy.standardPolicies()}), plus the exact allocation rule of each of
 * the four standard policies (executable specification).
 * <ul>
 *   <li><b>A1</b> worked example: a reconnect burst with exact expected numbers per policy.</li>
 *   <li><b>A2</b> randomized contract check (fixed seed, so reproducible).</li>
 * </ul>
 *
 * <h3>Part B - end-to-end invariants (real iFogSim2 runs, 4 policies, same scenario + seed)</h3>
 * <ol>
 *   <li><b>B1 Equal inputs</b> - all four policies observe identical generation counts, outage
 *       counts and restores-with-backlog (the fairness requirement).</li>
 *   <li><b>B2 Conservation</b> - delivered + left = generated, separately for new data and backfill.</li>
 *   <li><b>B3 Capacity</b> - delivered units never exceed C*(dataEnd - dataStart) + one tick of tokens.</li>
 *   <li><b>B4 Policy separation</b> - the four policies really allocate differently.</li>
 *   <li><b>B5 Predictable rate</b> - per tick, rate-limit never sends more than R backfill units,
 *       new-first never more than its quota q; every policy stays within the token budget.</li>
 *   <li><b>B6 Grant/arrival closure + time-series sanity</b> - nothing arrives that was not granted;
 *       tick rows are evenly spaced and internally consistent.</li>
 * </ol>
 *
 * <h3>Part C - determinism replay</h3>
 * The same (scenario, policy, seed) run twice in one JVM must write byte-identical CSVs.
 *
 * <p>Usage: {@code java backfill.run.SelfTest [fast|default|full]} - {@code fast} = part A only,
 * {@code default} = A + B on the base scenario + C, {@code full} = additionally B on storm,
 * overload and dominant-imbalance scenarios. Exit code 0 = all hard checks passed, 1 = failure.
 * Soft expectations (qualitative policy ordering) only print {@code WARN}.</p>
 */
public final class SelfTest {

    /** Every registered policy (docs/ADD_POLICY.md: a new policy is picked up automatically). */
    private static final String[] POLICIES = SchedulingPolicy.standardPolicies().stream()
            .map(SchedulingPolicy::key).toArray(String[]::new);
    private static final long FUZZ_SEED = 20261103L;
    private static final int FUZZ_CASES = 2000;

    private static int hardChecks = 0;
    private static int warnings = 0;

    // =====================================================================================
    // main
    // =====================================================================================

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0].toLowerCase() : "default";
        if (!mode.equals("fast") && !mode.equals("default") && !mode.equals("full")) {
            System.err.println("usage: SelfTest [fast|default|full]");
            System.exit(2);
        }
        System.out.println("[SelfTest] mode=" + mode);

        partAPolicyContract();
        if (mode.equals("fast")) {
            finish(null);
            return;
        }

        Path tmp = Files.createTempDirectory("backfill_selftest");
        System.out.println("[SelfTest] temp dir: " + tmp);

        List<SimParams> cases = new ArrayList<>();
        cases.add(baseCase());
        if (mode.equals("full")) {
            cases.add(stormCase());
            cases.add(overloadCase());
            cases.add(dominantCase());
        }
        for (int i = 0; i < cases.size(); i++) {
            partBEndToEnd(cases.get(i), tmp, /* mandatory scenario */ i == 0);
        }
        partCReplay(cases.get(0), tmp);
        finish(tmp);
    }

    // ---- scenarios used by part B (kept short so default mode finishes in about a minute) ----

    /** Base: 4 edges, 90 s window, seed 5 -> one reconnect with ~2.6k backlog (exercises every cap). */
    private static SimParams baseCase() {
        SimParams p = ScenarioGrid.base();
        p.numEdges = 4;
        p.simDuration = 90.0;
        p.seed = 5;
        return p;
    }

    private static SimParams stormCase() {
        SimParams p = ScenarioGrid.base();
        p.name = "st_storm";
        p.numEdges = 6;
        p.simDuration = 150.0;
        p.outageMode = OutageMode.COHORT;
        p.seed = 7;
        return p;
    }

    private static SimParams overloadCase() {
        SimParams p = ScenarioGrid.base();
        p.name = "st_rho1.1";
        p.numEdges = 4;
        p.simDuration = 90.0;
        p.loadFactor = 1.1;
        p.seed = 11;
        return p;
    }

    private static SimParams dominantCase() {
        SimParams p = ScenarioGrid.base();
        p.name = "st_dominant";
        p.numEdges = 6;
        p.simDuration = 90.0;
        p.imbalance = ImbalanceMode.DOMINANT;
        p.seed = 13;
        return p;
    }

    // =====================================================================================
    // PART A - policy contract on synthetic queues
    // =====================================================================================

    /** Mutable queue state of one fake edge; the views the policy sees are always live. */
    private static final class FakeEdge implements EdgeQueueView {
        final int idx;
        final boolean up;
        final ArrayDeque<Double> newQ = new ArrayDeque<>();
        final ArrayDeque<Double> backQ = new ArrayDeque<>();

        FakeEdge(int idx, boolean up) { this.idx = idx; this.up = up; }

        FakeEdge copy() {
            FakeEdge c = new FakeEdge(idx, up);
            c.newQ.addAll(newQ);
            c.backQ.addAll(backQ);
            return c;
        }

        @Override public int edgeIndex() { return idx; }
        @Override public boolean connected() { return up; }
        @Override public int newCount() { return newQ.size(); }
        @Override public int backCount() { return backQ.size(); }
        @Override public Double newHeadGenTime() { return newQ.peekFirst(); }
        @Override public Double backHeadGenTime() { return backQ.peekFirst(); }
    }

    /** Executes grants like SchedulerEntity does, but ALSO rejects grants to disconnected edges. */
    private static final class Recorder implements AllocationSink {
        final List<FakeEdge> edges;
        final List<Double> newT = new ArrayList<>();
        final List<Double> backT = new ArrayList<>();
        final Map<Integer, List<Double>> perEdge = new HashMap<>();

        Recorder(List<FakeEdge> edges) { this.edges = edges; }

        @Override
        public void grant(int edgeIndex, boolean isNew, int count) {
            if (count <= 0) return;
            FakeEdge e = edges.get(edgeIndex);
            // The production EdgeServerDevice.applyGrant() silently puts such units back instead of
            // failing, so this is the only place where the "never grant to a down edge" rule is enforced.
            if (!e.up) throw new IllegalStateException("granted to DISCONNECTED edge " + edgeIndex);
            ArrayDeque<Double> q = isNew ? e.newQ : e.backQ;
            if (count > q.size()) {
                throw new IllegalStateException("over-grant edge " + edgeIndex + " request=" + count
                        + " available=" + q.size());
            }
            for (int k = 0; k < count; k++) {
                double t = q.pollFirst();
                (isNew ? newT : backT).add(t);
                perEdge.computeIfAbsent(edgeIndex, x -> new ArrayList<>()).add(t);
            }
        }

        int total() { return newT.size() + backT.size(); }
    }

    private static void partAPolicyContract() {
        System.out.println("[SelfTest] --- A. policy contract (synthetic queues) ---");
        reconnectBurstExample();
        ok("A1 reconnect-burst worked example: exact (back,new) split of fifo/rr/ratelimit/newfirst");

        Random rng = new Random(FUZZ_SEED);
        for (int c = 0; c < FUZZ_CASES; c++) {
            SimParams p = new SimParams();
            p.centerCapacity = pick(rng, 120, 600, 1200, 2500);
            p.rateLimitFactor = pick(rng, 0.25, 0.5, 0.9);
            p.backfillQuotaFactor = pick(rng, 0.02, 0.05, 0.2);
            int tokens = p.centerTokensPerTick();
            List<FakeEdge> state = randomEdges(rng, 1 + rng.nextInt(8), tokens);
            for (String key : POLICIES) {
                verifyPolicyOnState(key, "case#" + c, p, tokens, state);
            }
        }
        ok("A2 randomized contract (" + FUZZ_CASES + " states x " + POLICIES.length + " policies): never grant to a down edge, "
                + "never exceed tokens, never over-grant, exact allocation rule + oldest-first order");
    }

    /**
     * Reconnect burst, default knobs (C=1200, dT=0.1 -> 120 tokens, R=60/tick, q=6/tick):
     * edge 0 just reconnected with 500 old backlog units; edges 1-3 hold 200 fresh units each.
     */
    private static void reconnectBurstExample() {
        SimParams p = ScenarioGrid.base();
        int tokens = p.centerTokensPerTick();
        check(tokens == 120 && p.rateLimitTokensPerTick() == 60 && p.backfillQuotaTokensPerTick() == 6,
                "default knobs changed (tokens/R/q = " + tokens + "/" + p.rateLimitTokensPerTick() + "/"
                        + p.backfillQuotaTokensPerTick() + "): update the expected numbers of A1");
        List<FakeEdge> burst = new ArrayList<>();
        FakeEdge e0 = new FakeEdge(0, true);
        for (int i = 0; i < 500; i++) e0.backQ.add(10.0 + i * 0.001);       // old backlog
        burst.add(e0);
        for (int e = 1; e <= 3; e++) {
            FakeEdge f = new FakeEdge(e, true);
            for (int i = 0; i < 200; i++) f.newQ.add(100.0 + i * 0.01 + e * 0.001);   // fresh, all later
            burst.add(f);
        }
        // key -> expected {backfill granted, new granted}
        Map<String, int[]> expect = Map.of(
                "fifo",      new int[] {120, 0},    // backlog is older than everything: it takes the whole tick
                "rr",        new int[] {30, 90},    // 120 / 4 active edges = 30 each
                "ratelimit", new int[] {60, 60},    // R reserved for backfill, the rest to fresh data
                "newfirst",  new int[] {6, 114});   // fresh first, tiny quota for backfill
        for (String key : POLICIES) {
            List<FakeEdge> work = copyAll(burst);
            Recorder rec = new Recorder(work);
            SchedulingPolicy.byKey(key).allocate(0.0, tokens, new ArrayList<EdgeQueueView>(work), rec, p);
            int[] want = expect.get(key);
            if (want == null) continue;   // newly added policy: no hand-written expectation
            check(rec.backT.size() == want[0] && rec.newT.size() == want[1],
                    "A1 " + key + ": expected (back,new)=(" + want[0] + "," + want[1] + ") but got ("
                            + rec.backT.size() + "," + rec.newT.size() + ")");
        }
    }

    private static void verifyPolicyOnState(String key, String caseId, SimParams p, int tokens,
                                            List<FakeEdge> state) {
        String ctx = "A2 " + key + " " + caseId + " [N=" + state.size() + ", tokens=" + tokens + "]";
        List<FakeEdge> work = copyAll(state);
        Recorder rec = new Recorder(work);
        try {
            SchedulingPolicy.byKey(key).allocate(0.0, tokens, new ArrayList<EdgeQueueView>(work), rec, p);
        } catch (IllegalStateException ex) {
            check(false, ctx + ": contract violated - " + ex.getMessage());
        }
        check(rec.total() <= tokens, ctx + ": granted " + rec.total() + " > budget " + tokens);

        List<Double> eligNew = eligible(state, true);     // sorted ascending, connected edges only
        List<Double> eligBack = eligible(state, false);
        switch (key) {
            case "fifo" -> {
                List<Double> all = new ArrayList<>(eligNew);
                all.addAll(eligBack);
                Collections.sort(all);
                int k = Math.min(tokens, all.size());
                check(rec.total() == k, ctx + ": not work-conserving, granted " + rec.total() + " expected " + k);
                check(sameMultiset(union(rec.newT, rec.backT), all.subList(0, k)),
                        ctx + ": not strict global generation order");
            }
            case "ratelimit" -> {
                int gb = Math.min(Math.min(p.rateLimitTokensPerTick(), tokens), eligBack.size());
                int gn = Math.min(tokens - gb, eligNew.size());
                expectSplit(ctx, rec, gb, gn, eligBack, eligNew);
            }
            case "newfirst" -> {
                int gb = Math.min(p.backfillQuotaTokensPerTick(), eligBack.size());
                int gn = Math.min(Math.max(0, tokens - gb), eligNew.size());
                expectSplit(ctx, rec, gb, gn, eligBack, eligNew);
            }
            case "rr" -> verifyRoundRobin(ctx, tokens, state, rec);
            default -> { }   // a newly added policy: only the generic contract above applies
        }
    }

    private static void expectSplit(String ctx, Recorder rec, int gb, int gn,
                                    List<Double> eligBack, List<Double> eligNew) {
        check(rec.backT.size() == gb, ctx + ": backfill granted " + rec.backT.size() + " expected " + gb);
        check(rec.newT.size() == gn, ctx + ": new granted " + rec.newT.size() + " expected " + gn);
        check(sameMultiset(rec.backT, eligBack.subList(0, gb)), ctx + ": backfill not oldest-first");
        check(sameMultiset(rec.newT, eligNew.subList(0, gn)), ctx + ": new data not oldest-first");
    }

    private static void verifyRoundRobin(String ctx, int tokens, List<FakeEdge> state, Recorder rec) {
        int eligibleTotal = 0, active = 0;
        for (FakeEdge e : state) {
            if (e.up && (e.newQ.size() + e.backQ.size()) > 0) {
                active++;
                eligibleTotal += e.newQ.size() + e.backQ.size();
            }
        }
        check(rec.total() == Math.min(tokens, eligibleTotal),
                ctx + ": not work-conserving, granted " + rec.total() + " expected " + Math.min(tokens, eligibleTotal));
        for (FakeEdge e : state) {
            List<Double> got = rec.perEdge.getOrDefault(e.idx, List.of());
            if (!e.up) { check(got.isEmpty(), ctx + ": grant to down edge " + e.idx); continue; }
            List<Double> merged = new ArrayList<>(e.newQ);
            merged.addAll(e.backQ);
            Collections.sort(merged);
            check(sameMultiset(got, merged.subList(0, got.size())),
                    ctx + ": edge " + e.idx + " not drained oldest-first");
            if (tokens >= active && (e.newQ.size() + e.backQ.size()) > 0) {
                check(!got.isEmpty(), ctx + ": edge " + e.idx + " starved although tokens >= active edges");
            }
        }
    }

    // ---- random state generation & small helpers for part A ----

    private static List<FakeEdge> randomEdges(Random rng, int n, int tokens) {
        List<FakeEdge> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            FakeEdge e = new FakeEdge(i, rng.nextDouble() < 0.7);
            fill(e.newQ, rng, queueSize(rng, tokens));
            fill(e.backQ, rng, queueSize(rng, tokens));
            list.add(e);
        }
        return list;
    }

    private static int queueSize(Random rng, int tokens) {
        return switch (rng.nextInt(4)) {
            case 0 -> 0;
            case 1 -> rng.nextInt(10);
            case 2 -> rng.nextInt(tokens + 1);
            default -> rng.nextInt(4 * tokens + 1);
        };
    }

    private static void fill(ArrayDeque<Double> q, Random rng, int size) {
        for (double t : rng.doubles(size, 0.0, 1000.0).sorted().toArray()) q.add(t);
    }

    private static List<FakeEdge> copyAll(List<FakeEdge> src) {
        List<FakeEdge> out = new ArrayList<>(src.size());
        for (FakeEdge e : src) out.add(e.copy());
        return out;
    }

    /** All generation times of one kind on connected edges, sorted ascending. */
    private static List<Double> eligible(List<FakeEdge> edges, boolean isNew) {
        List<Double> out = new ArrayList<>();
        for (FakeEdge e : edges) if (e.up) out.addAll(isNew ? e.newQ : e.backQ);
        Collections.sort(out);
        return out;
    }

    private static List<Double> union(List<Double> a, List<Double> b) {
        List<Double> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static boolean sameMultiset(List<Double> a, List<Double> b) {
        if (a.size() != b.size()) return false;
        List<Double> x = new ArrayList<>(a), y = new ArrayList<>(b);
        Collections.sort(x);
        Collections.sort(y);
        return x.equals(y);
    }

    private static int pick(Random rng, int... v) { return v[rng.nextInt(v.length)]; }

    private static double pick(Random rng, double... v) { return v[rng.nextInt(v.length)]; }

    // =====================================================================================
    // PART B - end-to-end invariants on real simulation runs
    // =====================================================================================

    /** Minimal CSV table addressed by column NAME, so reordering columns cannot silently break the test. */
    private static final class Table {
        final Map<String, Integer> col = new HashMap<>();
        final List<String[]> rows = new ArrayList<>();
        final String file;

        Table(Path csv) throws IOException {
            this.file = csv.getFileName().toString();
            List<String> lines = Files.readAllLines(csv);
            check(!lines.isEmpty(), file + ": empty csv");
            String[] header = lines.get(0).split(",", -1);
            for (int i = 0; i < header.length; i++) col.put(header[i], i);
            for (int i = 1; i < lines.size(); i++) rows.add(lines.get(i).split(",", -1));
        }

        String s(int row, String name) {
            Integer i = col.get(name);
            check(i != null, file + ": missing column '" + name + "' (schema changed? see UnitRegistry)");
            return rows.get(row)[i];
        }

        double d(int row, String name) { return Double.parseDouble(s(row, name)); }

        long l(int row, String name) { return Long.parseLong(s(row, name)); }
    }

    private static void partBEndToEnd(SimParams p, Path tmp, boolean mandatory) throws Exception {
        String sc = p.name;
        System.out.println("[SelfTest] --- B. end-to-end invariants, scenario=" + p + " window=" + p.simDuration + "s ---");
        for (String key : POLICIES) {
            double end = BackfillSim.run(p.copy(), key, tmp.toString(), runName(sc, key));
            System.out.println("[SelfTest] ran " + key + " end=" + String.format("%.1f", end));
        }

        Map<String, Table> run = new HashMap<>();
        Map<String, Table> tick = new HashMap<>();
        for (String key : POLICIES) {
            run.put(key, new Table(tmp.resolve(runName(sc, key) + "_run.csv")));
            tick.put(key, new Table(tmp.resolve(runName(sc, key) + "_tick.csv")));
            check(run.get(key).rows.size() == 1, runName(sc, key) + "_run.csv must hold header + 1 row");
        }

        int tokens = p.centerTokensPerTick();
        double dt = p.decisionInterval;
        Table ref = run.get(POLICIES[0]);

        // B1 equal inputs -------------------------------------------------------------------
        for (String key : POLICIES) {
            Table t = run.get(key);
            for (String c : new String[] {"scenario", "seed", "N", "dataStart", "dataEnd",
                                          "genNew", "genBack", "outages", "restoresWithBacklog"}) {
                check(t.s(0, c).equals(ref.s(0, c)),
                        "B1 equal inputs: column '" + c + "' differs between " + POLICIES[0] + " and " + key);
            }
        }
        ok("B1 equal inputs across the " + POLICIES.length + " policies (" + sc + ")");

        // B2 conservation, B3 capacity ------------------------------------------------------
        for (String key : POLICIES) {
            Table t = run.get(key);
            check(t.l(0, "delivNew") + t.l(0, "leftNew") == t.l(0, "genNew"), "B2 conservation new data (" + key + ")");
            check(t.l(0, "delivBack") + t.l(0, "leftBack") == t.l(0, "genBack"), "B2 conservation backfill (" + key + ")");
            double window = t.d(0, "dataEnd") - t.d(0, "dataStart");
            // every delivered unit consumed one token; tokens are useful only from dataStart on, plus one tick of slack
            double cap = p.centerCapacity * window + tokens;
            check(t.l(0, "delivNew") + t.l(0, "delivBack") <= cap, "B3 capacity bound (" + key + ")");
        }
        ok("B2 conservation and B3 capacity bound (" + sc + ")");

        // B4 policy separation --------------------------------------------------------------
        boolean distinct = false;
        for (String key : POLICIES) {
            distinct |= !run.get(key).s(0, "delivBack").equals(ref.s(0, "delivBack"))
                    || !run.get(key).s(0, "backDelayMean").equals(ref.s(0, "backDelayMean"));
        }
        if (mandatory) {
            check(distinct, "B4 policies allocate differently (delivered backfill or backfill delay)");
            ok("B4 policy separation (" + sc + ")");
        } else if (distinct) {
            ok("B4 policy separation (" + sc + ")");
        } else {
            warn("B4 " + sc + ": the policies produced identical backfill results (scenario too tame?)");
        }

        // B5 / B6 per-tick checks -----------------------------------------------------------
        for (String key : POLICIES) {
            Table t = tick.get(key);
            Table r = run.get(key);
            int n = t.rows.size();
            check(n > 0, "B6 tick csv empty (" + key + ")");
            double sumNew = 0, sumBack = 0, maxBack = 0;
            for (int i = 0; i < n; i++) {
                double gn = t.d(i, "grantedNew"), gb = t.d(i, "grantedBack");
                check(gn >= 0 && gb >= 0, "B6 negative grant (" + key + ", row " + i + ")");
                check(gn + gb <= tokens, "B5 per-tick budget exceeded (" + key + ", t=" + t.s(i, "t") + "): "
                        + (gn + gb) + " > " + tokens);
                sumNew += gn;
                sumBack += gb;
                maxBack = Math.max(maxBack, gb);
                check(t.l(i, "backlogNewTot") + t.l(i, "backlogBackTot") == t.l(i, "backlogTot"),
                        "B6 backlogTot != new + back (" + key + ", row " + i + ")");
                if (i > 0) {
                    double step = t.d(i, "t") - t.d(i - 1, "t");
                    check(Math.abs(step - dt) < 1e-6, "B6 tick spacing " + step + " != decisionInterval " + dt + " (" + key + ")");
                }
            }
            // B6 closure: a unit can only arrive if some tick granted it (re-grants after a link loss may make
            // the granted total larger than the delivered total, never smaller)
            check(r.l(0, "delivNew") <= sumNew, "B6 closure: delivered new > granted new (" + key + ")");
            check(r.l(0, "delivBack") <= sumBack, "B6 closure: delivered backfill > granted backfill (" + key + ")");

            // B5 predictable backfill rate (rate-limit) / fixed quota (new-first)
            int capTick = switch (key) {
                case "ratelimit" -> p.rateLimitTokensPerTick();
                case "newfirst" -> p.backfillQuotaTokensPerTick();
                default -> -1;
            };
            if (capTick > 0) {
                check(maxBack <= capTick, "B5 " + key + ": backfill " + maxBack + "/tick exceeds fixed cap " + capTick);
                double window = r.d(0, "dataEnd") - r.d(0, "dataStart");
                double windowCap = (capTick / dt) * window + capTick;      // = rate * window + one tick
                check(r.l(0, "delivBack") <= windowCap, "B5 " + key + ": delivered backfill exceeds rate x window");
                boolean binding = maxBack == capTick;
                if (mandatory) {
                    check(binding, "B5 " + key + ": cap never reached (max " + maxBack + " < " + capTick
                            + "/tick) - scenario does not exercise the cap, test would be vacuous");
                } else if (!binding) {
                    warn("B5 " + key + " " + sc + ": cap " + capTick + "/tick never reached (max " + maxBack + ") - weak coverage");
                }
                System.out.println("[SelfTest] note: " + key + " backfill max " + (long) maxBack + "/tick, cap " + capTick);
            }
        }
        ok("B5 per-tick budget, rate-limit R cap, new-first quota cap (" + sc + ")");
        ok("B6 grant/arrival closure and tick-series sanity (" + sc + ")");

        // coverage guard: with no outage-induced backlog every cap above is vacuous
        long genBack = ref.l(0, "genBack"), restores = ref.l(0, "restoresWithBacklog");
        if (mandatory) check(genBack > 0 && restores > 0, "B1 base scenario produced no backlog/restore (genBack="
                + genBack + ", restoresWithBacklog=" + restores + "): invariants would be vacuous");
        else if (genBack == 0 || restores == 0) warn("scenario " + sc + " produced no backlog/restore: weak coverage");

        // soft, qualitative expectations from docs/MODEL.md section 6 (WARN only, never fail the build).
        // NOTE: backfill delay is NOT used here on purpose: backDelayMean is averaged over DELIVERED units only,
        // so a policy that delivers very little backlog (new-first) can look "fast" (survivorship bias).
        double newDelayNF = run.get("newfirst").d(0, "newDelayMean"), newDelayFifo = run.get("fifo").d(0, "newDelayMean");
        long delivBackNF = run.get("newfirst").l(0, "delivBack"), delivBackFifo = run.get("fifo").l(0, "delivBack");
        soft(newDelayNF <= newDelayFifo, sc + ": newfirst fresh delay (" + newDelayNF + ") should be <= fifo (" + newDelayFifo + ")");
        soft(delivBackNF <= delivBackFifo, sc + ": newfirst should not drain more backlog than fifo (" + delivBackNF + " > " + delivBackFifo + ")");
    }

    private static String runName(String scenario, String key) { return "st_" + scenario + "_" + key; }

    // =====================================================================================
    // PART C - determinism replay
    // =====================================================================================

    private static void partCReplay(SimParams p, Path tmp) throws Exception {
        System.out.println("[SelfTest] --- C. determinism replay ---");
        String key = "rr";   // round-robin carries a rotating cursor: the most state-sensitive policy
        String replay = "st_replay_" + p.name + "_" + key;
        BackfillSim.run(p.copy(), key, tmp.toString(), replay);
        String first = runName(p.name, key);
        for (String suffix : new String[] {"_run.csv", "_tick.csv"}) {
            long diff = Files.mismatch(tmp.resolve(first + suffix), tmp.resolve(replay + suffix));
            check(diff == -1L, "C determinism: " + key + suffix + " differs between two identical runs (first mismatch at byte " + diff + ")");
        }
        ok("C determinism: re-running " + key + " with the same seed gives byte-identical run and tick CSVs");
    }

    // =====================================================================================
    // reporting
    // =====================================================================================

    private static void check(boolean ok, String what) {
        hardChecks++;
        if (!ok) {
            System.err.println("[SelfTest] FAILED: " + what);
            System.exit(1);
        }
    }

    private static void ok(String what) { System.out.println("[SelfTest] ok: " + what); }

    private static void soft(boolean ok, String what) { if (!ok) warn("expectation: " + what); }

    private static void warn(String what) {
        warnings++;
        System.out.println("[SelfTest] WARN: " + what);
    }

    private static void finish(Path tmp) {
        System.out.println("[SelfTest] ALL CHECKS PASSED (" + hardChecks + " hard checks, " + warnings + " warning(s)"
                + (tmp != null ? ", output " + tmp : "") + ")");
    }

    private SelfTest() { throw new AssertionError(); }
}

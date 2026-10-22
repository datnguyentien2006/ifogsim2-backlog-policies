// Headless smoke + parity test: four policies on identical cfg+seed.
const { Engine, policyList, defaultConfig } = require("./../engine.js");

const cfg = defaultConfig();
cfg.simDuration = 300;
cfg.seed = 7;
cfg.numEdges = 6;

const rows = [];
for (const p of policyList()) {
    const e = new Engine(cfg, p.key);
    e.run(cfg.simDuration + 5 + 1);
    rows.push([e, e.summary()]);
}
console.log("policy       | delivNew  | delivBack | newDel mean | backDel mean | back P95 | peak  | drains");
for (const [e, s] of rows) {
    console.log(
        s.policy.padEnd(12) + " | " +
        String(s.delivNew).padEnd(9) + " | " +
        String(s.delivBack).padEnd(9) + " | " +
        s.newDelayMean.toFixed(3).padStart(11) + " | " +
        s.backDelayMean.toFixed(3).padStart(12) + " | " +
        s.backDelayP95.toFixed(2).padStart(8) + " | " +
        String(s.backlogPeak).padStart(5) + " | " + s.drains
    );
}
// ---- equal-input invariants: every policy must see identical streams
const ref = rows[0][1];
for (const [e, s] of rows.slice(1)) {
    const assertEq = (a, b, what) => {
        if (a !== b) throw new Error("PARITY FAIL " + e.policy.key + ": " + what + " " + a + " != " + b);
    };
    assertEq(s.genNew, ref.genNew, "genNew");
    assertEq(s.genBack, ref.genBack, "genBack");
    assertEq(s.outages, ref.outages, "outages");
    assertEq(s.restoresWithBacklog, ref.restoresWithBacklog, "restoresWithBacklog");
}
// ---- conservation: delivered + left == generated
for (const [e, s] of rows) {
    if (s.delivNew + s.leftNew !== s.genNew) throw new Error("CONSERVATION FAIL new: " + e.policy.key);
    if (s.delivBack + s.leftBack !== s.genBack) throw new Error("CONSERVATION FAIL back: " + e.policy.key);
}
console.log("PARITY OK: generations + outage timelines identical across the 4 policies; conservation holds.");
console.log("PARITY DETAIL: genNew=" + ref.genNew + " genBack=" + ref.genBack + " outages=" + ref.outages);

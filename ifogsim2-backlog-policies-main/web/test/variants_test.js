// Second engine test: COHORT (storm) regime + imbalance, headless.
const { Engine, policyList, defaultConfig } = require("./../engine.js");

const fail = (msg) => { console.error("FAIL: " + msg); process.exit(1); };

for (const cfgTweak of [
    (c) => { c.outageMode = "COHORT"; },
    (c) => { c.imbalance = "DOMINANT"; c.numEdges = 10; },
    (c) => { c.loadFactor = 1.1; },
    (c) => { c.outageMode = "COHORT"; c.loadFactor = 1.1; c.imbalance = "ZIPF"; c.numEdges = 16; }
]) {
    const cfg = defaultConfig();
    cfgTweak(cfg);
    cfg.simDuration = 120;
    cfg.seed = 11;
    const label = JSON.stringify([cfg.outageMode, cfg.imbalance, cfg.loadFactor, cfg.numEdges]);
    let rows = [];
    for (const p of policyList()) {
        try {
            const e = new Engine(cfg, p.key);
            e.run(cfg.simDuration + 5 + 1);
            rows.push([e, e.summary()]);
        } catch (ex) {
            fail(label + " policy " + p.key + " threw: " + ex.message + "\n" + ex.stack);
        }
    }
    const ref = rows[0][1];
    for (const [e, s] of rows) {
        if (s.genNew !== ref.genNew) fail(label + " genNew parity " + e.policy.key);
        if (s.genBack !== ref.genBack) fail(label + " genBack parity " + e.policy.key);
        if (s.outages !== ref.outages) fail(label + " outage parity " + e.policy.key);
        if (s.delivNew + s.leftNew !== s.genNew) fail(label + " new conservation " + e.policy.key);
        if (s.delivBack + s.leftBack !== s.genBack) fail(label + " back conservation " + e.policy.key);
    }
    console.log("OK " + label + "  backDelay means: " + rows.map(r => r[1].policy + "=" + r[1].backDelayMean.toFixed(1)).join(" "));
}
console.log("ALL VARIANTS PASS");

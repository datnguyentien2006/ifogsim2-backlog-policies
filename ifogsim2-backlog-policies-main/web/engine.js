/* ============================================================
 * BackfillSched — JS engine (faithful port of the Java model)
 *
 * Mirrors sim/src/backfill:
 *   EdgeServerDevice : two buffers (newQ = generated while link up,
 *                      backQ = generated while link down), all egress
 *                      goes through scheduler grants.
 *   SchedulerEntity  : one decision tick every DT; builds identical
 *                      views for every policy; executes allocations.
 *   OutageController : SCATTERED (Poisson drops, lognormal outages)
 *                      and COHORT (storms, synchronized reconnection).
 *   Policies: FIFO / RR / Rate-limit(R) / New-first(quota q).
 *
 * One simulation unit = one tuple/grant. Fully deterministic per
 * (config, seed): all four policies MUST be fed identical streams.
 * ============================================================ */
"use strict";

/* ---------------- deterministic RNG (mulberry32) ---------------- */
function mulberry32(a) {
    return function () {
        a |= 0; a = (a + 0x6D2B79F5) | 0;
        let t = Math.imul(a ^ (a >>> 15), 1 | a);
        t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

class Rng {
    constructor(seed) { this.next = mulberry32(seed >>> 0); }
    random() {
        let u = this.next();
        return u <= 0.0 ? 1e-12 : u;
    }
    expDraw(mean) {
        return Math.max(1e-6, -mean * Math.log(this.random()));
    }
    gaussian() {
        // Box–Muller (two draws per call, same shape as stats libraries)
        let u1 = this.random(), u2 = this.random();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }
    lognormalDraw(mean, cv) {
        const s2 = Math.log(1 + cv * cv);
        const mu = Math.log(Math.max(1e-9, mean)) - s2 / 2;
        return Math.max(1e-6, Math.exp(mu + Math.sqrt(s2) * this.gaussian()));
    }
}

/* ---------------- default configuration (SimParams) ---------------- */
const DT = 0.1;                      // decision interval (s)
const C = 1200.0;                    // receiving-center capacity (units/s)
const WARMUP = 5.0;                  // links cycle before data starts (s)
const LINK_LATENCY = 0.012;          // edge -> center latency (s)

function defaultConfig() {
    return {
        numEdges: 6,
        loadFactor: 0.7,             // rho = new-data rate / C
        imbalance: "EVEN",           // EVEN | ZIPF | DOMINANT
        zipfAlpha: 1.0,
        dominantShare: 0.6,
        outageMode: "SCATTERED",     // SCATTERED | COHORT
        dropRatePerEdge: 0.01,       // Poisson drop rate (1/s)
        downMean: 20.0, downCV: 0.8, // scattered: lognormal down-spells
        cohortPeriod: 120.0, cohortFraction: 0.5, cohortCV: 0.15,
        rateLimitFactor: 0.5,        // rate-limit: R = f * C (backfill rate)
        backfillQuotaFactor: 0.05,   // new-first: quota q = f * C
        simDuration: 300.0,
        seed: 7
    };
}

/** Per-edge new-data rate lambda_i implementing the imbalance mode. */
function rateOfEdge(cfg, i) {
    const total = cfg.loadFactor * C, n = cfg.numEdges;
    if (cfg.imbalance === "ZIPF") {
        let z = 0;
        for (let k = 0; k < n; k++) z += 1 / Math.pow(1 + k, cfg.zipfAlpha);
        return total * (1 / Math.pow(1 + i, cfg.zipfAlpha)) / z;
    }
    if (cfg.imbalance === "DOMINANT") {
        return (i === 0) ? cfg.dominantShare * total
            : (1 - cfg.dominantShare) * total / Math.max(1, n - 1);
    }
    return total / n;
}

/* ---------------- edge server view/object ---------------- */
class EdgeQueue {
    constructor(index, lambda, rng) {
        this.index = index;
        this.lambda = lambda;
        this.newQ = [];            // generated while link up
        this.backQ = [];           // generated while link down
        this.newH = 0; this.backH = 0;   // O(1) queue pop-heads
        this.up = true;
        this.nextArrival = Infinity;
        this.nextMove = null;      // {t, toDown} scattered mode
        this.totalNew = 0; this.totalBack = 0;
        this.delivNew = 0; this.delivBack = 0;
        this.busyUntil = 0;
    }
    newCount() { return this.newQ.length - this.newH; }
    backCount() { return this.backQ.length - this.backH; }
    popNew() { return this.newQ[this.newH++]; }
    popBack() { return this.backQ[this.backH++]; }
    newHeadGenT() { return this.newH < this.newQ.length ? this.newQ[this.newH].genT : null; }
    backHeadGenT() { return this.backH < this.backQ.length ? this.backQ[this.backH].genT : null; }
    hasAny() { return this.newCount() > 0 || this.backCount() > 0; }
    connected() { return this.up; }
}

/* ---------------- shared drain helpers (SchedulingPolicy) ---------------- */
function anyData(edges) {
    for (const v of edges) if (v.connected() && v.hasAny()) return true;
    return false;
}
function drainOldestGlobal(edges, sink, limit, which) {   // which: "NEW"|"BACK"|"BOTH"
    let granted = 0;
    while (granted < limit && anyData(edges)) {
        let best = null, bestIsNew = false, bestTime = Infinity;
        for (const v of edges) {
            if (!v.connected()) continue;
            if (which !== "BACK") {
                const t = v.newHeadGenT();
                if (t !== null && t < bestTime) { best = v; bestIsNew = true; bestTime = t; }
            }
            if (which !== "NEW") {
                const t = v.backHeadGenT();
                if (t !== null && t < bestTime) { best = v; bestIsNew = false; bestTime = t; }
            }
        }
        if (best === null) break;
        sink.grant(best, bestIsNew, 1);
        granted++;
    }
    return granted;
}
function drainEdgeMerged(v, limit, sink) {
    let granted = 0;
    for (let k = 0; k < limit; k++) {
        const tn = v.newHeadGenT(), tb = v.backHeadGenT();
        if (tn === null && tb === null) break;
        const takeNew = (tb === null) || (tn !== null && tn <= tb);
        sink.grant(v, takeNew, 1);
        granted++;
    }
    return granted;
}

/* ---------------- the four policies ---------------- */
const RateLimitTokensPerTick = (cfg) => Math.max(1, Math.round(C * DT * cfg.rateLimitFactor));
const QuotaTokensPerTick = (cfg) => Math.max(1, Math.round(C * DT * cfg.backfillQuotaFactor));

const Policies = {
    fifo: {
        key: "fifo", name: "FIFO (generation order)",
        description: "Strict global order: accumulated data ahead of new data until the backlog clears.",
        make() { return {
            key: "fifo", name: this.name, description: this.description,
            allocate(now, tokens, edges, sink, cfg) {
                drainOldestGlobal(edges, sink, Math.floor(tokens), "BOTH");
            }
        }; }
    },
    rr: {
        key: "rr", name: "Round-robin",
        description: "Capacity split evenly, in turn, over connected servers holding data; unused quota is passed on in turn to servers that still hold data.",
        make() { return {
            key: "rr", name: this.name, description: this.description,
            cursor: 0,
            allocate(now, tokens, edges, sink, cfg) {
                let remaining = Math.floor(tokens);
                if (remaining <= 0) return;
                const n = edges.length;
                // active servers, ordered from the rotating cursor (turn order)
                const idx = [];
                for (let k = 0; k < n; k++) {
                    const i = (this.cursor + k) % n;
                    if (edges[i].connected() && edges[i].hasAny()) idx.push(i);
                }
                if (idx.length === 0) return;
                // First pass: even share in turn. Leftover quota (servers that could not use
                // their share) is NOT discarded — follow-up passes hand it to the servers
                // that still hold data.
                let lastGranted = -1;
                while (remaining > 0) {
                    let grantedThisPass = 0;
                    const share = Math.max(1, Math.floor(remaining / idx.length));
                    for (const i of idx) {
                        if (remaining <= 0) break;
                        if (!edges[i].connected() || !edges[i].hasAny()) continue; // drained by an earlier pass
                        const got = drainEdgeMerged(edges[i], Math.min(share, remaining), sink);
                        remaining -= got;
                        grantedThisPass += got;
                        if (got > 0) lastGranted = i;
                    }
                    if (grantedThisPass === 0) break; // nothing grantable anywhere
                }
                if (lastGranted >= 0) this.cursor = (lastGranted + 1) % n;
            }
        }; }
    },
    ratelimit: {
        key: "ratelimit", name: "Rate-limit (R fixed)",
        description: "Backfill transmits at a constant fixed rate R; whatever R leaves unused goes to new data.",
        make() { return {
            key: "ratelimit", name: this.name, description: this.description,
            allocate(now, tokens, edges, sink, cfg) {
                const reserved = Math.min(RateLimitTokensPerTick(cfg), Math.floor(tokens));
                const grantedBack = drainOldestGlobal(edges, sink, reserved, "BACK");
                const remaining = Math.floor(tokens) - grantedBack;
                if (remaining > 0) drainOldestGlobal(edges, sink, remaining, "NEW");
            }
        }; }
    },
    newfirst: {
        key: "newfirst", name: "New-data-first (quota q)",
        description: "Fresh data first; the backlog may transmit only a small fixed quota q per tick.",
        make() { return {
            key: "newfirst", name: this.name, description: this.description,
            allocate(now, tokens, edges, sink, cfg) {
                const quota = QuotaTokensPerTick(cfg);
                const grantedBack = drainOldestGlobal(edges, sink, quota, "BACK");
                const remaining = Math.floor(tokens) - grantedBack;
                if (remaining > 0) drainOldestGlobal(edges, sink, remaining, "NEW");
            }
        }; }
    }
};
// keys order = the comparison order used everywhere
const POLICY_KEYS = ["fifo", "rr", "ratelimit", "newfirst"];
function policyList() { return POLICY_KEYS.map(k => Policies[k].make()); }
function policyByKey(key) { return Policies[key].make(); }

/* ---------------- the simulation engine ---------------- */
class Engine {
    constructor(cfg, policyKey) {
        this.cfg = JSON.parse(JSON.stringify(cfg));
        this.t = 0.0;
        this.tickCount = 0;
        this.policy = policyByKey(policyKey);   // fresh state per engine

        this.events = [];       // per-tick connectivity events
        this.grantArrived = []; // per-tick arrival flashes
        this.grantLog = [];     // per-tick grant summary per edge
        this.grantedNew = 0; this.grantedBack = 0;

        // registry (UnitRegistry mirror)
        this.reg = {
            genNew: 0, genBack: 0, delivNew: 0, delivBack: 0,
            newDelaySum: 0,
            delayNew: [], delayBack: [],
            backlogArea: 0, backlogPeak: 0,
            outages: 0, restores: 0, restoresWithBacklog: 0,
            drains: 0, drainSum: 0, drainMax: 0,
            delivBackPerEdge: new Array(cfg.numEdges).fill(0)
        };

        this.edges = [];
        for (let i = 0; i < cfg.numEdges; i++) {
            const arrivalRng = new Rng(cfg.seed * 1000003 + i * 131 + 17);
            this.edges.push(new EdgeQueue(i, rateOfEdge(cfg, i), arrivalRng));
        }
        // outage RNG: same structure as Java (seed*8191 + 101)
        this.outageRng = new Rng(cfg.seed * 8191 + 101);
        this.inFlight = [];                       // {unit, edge, arriveAt}
        this.grantLog = [];                       // per-tick grants for animation
        this.draining = new Array(cfg.numEdges).fill(false);
        this.restoreAt = new Array(cfg.numEdges).fill(0);

        // arm the outage machinery
        if (cfg.outageMode === "SCATTERED") {
            for (const e of this.edges) {
                const mean = 1 / Math.max(1e-9, cfg.dropRatePerEdge);
                e.nextMove = { t: this.outageRng.expDraw(mean), toDown: true };
            }
        } else {
            this.nextStorm = cfg.cohortPeriod;
        }

        // chart timeline
        this.hist = [];
        this.dataStart = Infinity; this.dataEnd = -Infinity;

        // start data generation after warmup
        this.startData(WARMUP);
    }

    startData(at) {
        for (const e of this.edges) {
            e.nextArrival = at + this._exp(e, 1 / e.lambda);
        }
    }
    _exp(e, mean) {
        // per-edge arrival stream (deterministic, per (seed, edge))
        if (!e.arrRng) e.arrRng = new Rng(this.cfg.seed * 1000003 + e.index * 131 + 17);
        return e.arrRng.expDraw(mean);
    }

    /* -- piece 1: connectivity ------------------------------------------------ */
    applyLinkEvents(now) {
        const cfg = this.cfg;
        if (cfg.outageMode === "SCATTERED") {
            for (const e of this.edges) {
                while (e.nextMove && e.nextMove.t <= now) {
                    this.setLink(e, e.nextMove.toDown, now);
                    const nextIsDown = !e.nextMove.toDown;
                    const dur = nextIsDown
                        ? this.outageRng.lognormalDraw(cfg.downMean, Math.max(0.01, cfg.downCV))
                        : this.outageRng.expDraw(1 / Math.max(1e-9, cfg.dropRatePerEdge));
                    e.nextMove = { t: now + dur, toDown: nextIsDown };
                }
            }
        } else if (this.nextStorm !== undefined && this.nextStorm <= now) {
            const candidates = this.edges.filter(e => e.up).map(e => e.index);
            // deterministic shuffle
            for (let k = candidates.length - 1; k > 0; k--) {
                const j = Math.floor(this.outageRng.random() * (k + 1));
                [candidates[k], candidates[j]] = [candidates[j], candidates[k]];
            }
            const m = Math.max(1, Math.round(cfg.cohortFraction * cfg.numEdges));
            const size = Math.min(m, candidates.length);
            for (let k = 0; k < size; k++) {
                const e = this.edges[candidates[k]];
                this.setLink(e, true, now);
                const dur = this.outageRng.lognormalDraw(cfg.downMean, Math.max(0.01, cfg.cohortCV));
                e.restorePlanned = now + dur;
            }
            this.nextStorm += cfg.cohortPeriod;
        }
        // cohort restorations
        if (cfg.outageMode === "COHORT") {
            for (const e of this.edges) {
                if (!e.up && e.restorePlanned !== undefined && e.restorePlanned <= now) {
                    e.restorePlanned = undefined;
                    this.setLink(e, false, now);
                }
            }
        }
    }

    setLink(e, toDown, now) {
        if (toDown && e.up) {
            e.up = false;
            this.reg.outages++;
            this.events.push({ t: now, kind: "down", edge: e.index });
        } else if (!toDown && !e.up) {
            e.up = true;
            if (e.backCount() > 0) {
                this.reg.restoresWithBacklog++;
                this.draining[e.index] = true;
                this.restoreAt[e.index] = now;
            }
            this.reg.restores++;
            this.events.push({ t: now, kind: "up", edge: e.index, backlog: e.backCount() });
        }
    }

    /* -- piece 2: generation --------------------------------------------------- */
    generate(now) {
        for (const e of this.edges) {
            while (e.nextArrival <= now) {
                const tl = e.nextArrival;
                if (tl > this.cfg.simDuration + WARMUP) { e.nextArrival = Infinity; break; }
                const isNew = e.up;
                const unit = { genT: tl, isNew, edge: e.index, id: this.reg.genNew + this.reg.genBack };
                if (isNew) { e.newQ.push(unit); e.totalNew++; this.reg.genNew++; }
                else       { e.backQ.push(unit); e.totalBack++; this.reg.genBack++; }
                if (tl < this.dataStart) this.dataStart = tl;
                if (tl > this.dataEnd) this.dataEnd = tl;
                e.nextArrival += this._exp(e, 1 / e.lambda);
            }
        }
    }

    /* -- piece 3: delivery of granted units ----------------------------------- */
    flushArrivals(now) {
        if (this.inFlight.length === 0) return;
        const stay = [];
        for (const f of this.inFlight) {
            if (f.arriveAt <= now) {
                const e = this.edges[f.unit.edge];
                const d = now - f.unit.genT;
                if (f.unit.isNew) { this.reg.delivNew++; this.reg.delayNew.push(d); e.delivNew++; }
                else { this.reg.delivBack++; this.reg.delayBack.push(d); e.delivBack++; this.reg.delivBackPerEdge[e.index]++; }
                this.grantArrived.push(f);
            } else stay.push(f);
        }
        this.inFlight = stay;
    }

    /* -- piece 4: one scheduler decision tick --------------------------------- */
    tick() {
        const now = this.t;
        this.events = [];
        this.grantArrived = [];
        this.grantLog = [];

        this.applyLinkEvents(now);
        this.generate(now);

        // backlog bookkeeping between ticks
        const bTot = this.edges.reduce((s, e) => s + e.backCount() + e.newCount(), 0);
        this.reg.backlogArea += bTot * DT;

        this.flushArrivals(now);

        // token budget for this tick (shared receiving-center capacity)
        const tokens = Math.max(1, Math.round(C * DT));
        const sink = {
            grant: (v, isNew, count) => {
                for (let k = 0; k < count; k++) {
                    const unit = isNew ? v.popNew() : v.popBack();
                    this.inFlight.push({ unit, arriveAt: now + LINK_LATENCY });
                }
            }
        };
        const viewGrant = {
            grant: (v, isNew, count) => {
                if (count <= 0) return;
                sink.grant(v, isNew, count);
                let g = this.grantLog.find(gl => gl.edge === v.index);
                if (!g) { g = { edge: v.index, newN: 0, backN: 0, at: now }; this.grantLog.push(g); }
                if (isNew) g.newN += count; else g.backN += count;
            }
        };
        // (views == edges themselves; EdgeQueue implements the same API as Java EdgeQueueView)
        this.policy.allocate(now, tokens, this.edges, viewGrant, this.cfg);
        this.grantedNew = this.grantLog.reduce((s, g) => s + g.newN, 0);
        this.grantedBack = this.grantLog.reduce((s, g) => s + g.backN, 0);

        // drain completion check
        for (let i = 0; i < this.edges.length; i++) {
            if (this.draining[i] && this.edges[i].backCount() === 0) {
                this.draining[i] = false;
                this.reg.drains++;
                this.reg.drainSum += now - this.restoreAt[i];
                this.reg.drainMax = Math.max(this.reg.drainMax, now - this.restoreAt[i]);
            }
        }

        // recording (AFTER allocation, mirrors the Java scheduler snapshot)
        const peakNow = this.edges.reduce((s, e) => s + e.backCount() + e.newCount(), 0);
        if (peakNow > this.reg.backlogPeak) this.reg.backlogPeak = peakNow;
        if (this.tickCount % 1 === 0) this.record(now);

        this.t = +(now + DT).toFixed(9);
        this.tickCount++;
    }

    record(now) {
        let book = { t: now, backlogTot: 0, backlogNew: 0, backlogBack: 0,
            gNew: this.grantedNew, gBack: this.grantedBack, down: 0,
            dN: this.reg.delivNew, dB: this.reg.delivBack,
            perEdge: [] };
        for (const e of this.edges) {
            book.perEdge.push({ newQ: e.newCount(), backQ: e.backCount(), up: e.up });
            book.backlogNew += e.newCount();
            book.backlogBack += e.backCount();
            if (!e.up) book.down++;
        }
        book.backlogTot = book.backlogNew + book.backlogBack;
        this.hist.push(book);
    }

    /* -- bulk running ----------------------------------------------------------- */
    run(simSeconds) {
        const targetTick = Math.round(simSeconds / DT);
        while (this.tickCount < targetTick) this.tick();
    }
    done() {
        return this.t >= WARMUP + this.cfg.simDuration + 1.0;
    }

    /* -- summary (UnitRegistry mirror) ----------------------------------------- */
    percentiles(arr, x100) {
        if (arr.length === 0) return null;
        const s = Float64Array.from(arr).sort();
        const q = p => s[Math.min(s.length - 1, Math.floor(p * s.length))];
        return [q(0.5), q(0.95), q(0.99), s.reduce((a, b) => a + b, 0) / s.length];
    }
    jainBackfill() {
        // Java parity: per-edge ratio delivered/generated of accumulated data, over every
        // edge that GENERATED backfill (ratio 0 = starved edge stays in the sample).
        const ratios = [];
        for (const e of this.edges) {
            if (e.totalBack > 0) ratios.push(this.reg.delivBackPerEdge[e.index] / e.totalBack);
        }
        if (ratios.length === 0) return 1.0;
        const s = ratios.reduce((a, b) => a + b, 0);
        if (s === 0) return 1.0;   // Java parity: nothing delivered -> "nothing to differentiate"
        const s2 = ratios.reduce((a, b) => a + b * b, 0);
        return Math.min(1, (s * s) / (ratios.length * s2));
    }
    summary() {
        const [p50n, p95n, p99n, meanN] = this.percentiles(this.reg.delayNew) || [0, 0, 0, 0];
        const [p50b, p95b, p99b, meanB] = this.percentiles(this.reg.delayBack) || [0, 0, 0, 0];
        return {
            policy: this.policy.key,
            n: this.cfg.numEdges, rho: this.cfg.loadFactor,
            imbalance: this.cfg.imbalance, outageMode: this.cfg.outageMode,
            seed: this.cfg.seed,
            genNew: this.reg.genNew, genBack: this.reg.genBack,
            delivNew: this.reg.delivNew, delivBack: this.reg.delivBack,
            leftNew: this.reg.genNew - this.reg.delivNew,
            leftBack: this.reg.genBack - this.reg.delivBack,
            newDelayMean: meanN, newDelayP50: p50n, newDelayP95: p95n, newDelayP99: p99n,
            backDelayMean: meanB, backDelayP50: p50b, backDelayP95: p95b, backDelayP99: p99b,
            backlogArea: Math.round(this.reg.backlogArea * 10) / 10,
            backlogPeak: this.reg.backlogPeak,
            outages: this.reg.outages,
            restoresWithBacklog: this.reg.restoresWithBacklog,
            drains: this.reg.drains,
            drainMean: this.reg.drains ? this.reg.drainSum / this.reg.drains : 0,
            drainMax: this.reg.drainMax,
            jainBackfill: Math.round(this.jainBackfill() * 1000) / 1000
        };
    }
}

/* --------------------------------------------------------------- export */
if (typeof module !== "undefined") module.exports = { Engine, Policies, policyByKey, policyList, defaultConfig, C, DT, WARMUP };

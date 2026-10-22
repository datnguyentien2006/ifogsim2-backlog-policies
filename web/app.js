/* ============================================================
 * BackfillSched — UI: live animated demo, batch compare, export
 * ============================================================ */
"use strict";

const POLICY_COLORS = { fifo: "#e67e22", rr: "#8e44ad", ratelimit: "#27ae60", newfirst: "#2980b9" };

let engine = null;
let playing = false;
let pendingSteps = 0;
let compareRows = [];

const el = id => document.getElementById(id);
const fmt = v => v >= 1e6 ? (v / 1e6).toFixed(2) + "M" : Math.round(v).toLocaleString("en-US");
const fmt1 = v => (v === null || v === undefined) ? "—" : (Math.round(v * 10) / 10).toLocaleString("en-US");

/* ---------------- config from controls ---------------- */
function readConfig() {
    const cfg = defaultConfig();
    cfg.numEdges = parseInt(el("cfgN").value, 10);
    cfg.loadFactor = parseFloat(el("cfgRho").value);
    cfg.imbalance = el("cfgImbalance").value;
    if (cfg.imbalance === "ZIPF") cfg.zipfAlpha = parseFloat(el("cfgZipf").value) || 1.0;
    cfg.outageMode = el("cfgOutage").value;
    cfg.simDuration = parseFloat(el("cfgDuration").value);
    cfg.seed = parseInt(el("cfgSeed").value, 10);
    return cfg;
}

function resetSim() {
    engine = new Engine(readConfig(), el("cfgPolicy").value);
    playing = false;
    el("btnPlay").textContent = "▶ Play";
    el("eventsLog").innerHTML = "";
    document.querySelectorAll(".chart-canvas").forEach(c => { c._ser = null; });
    drawAll();
}

/* ---------------- animation loop ---------------- */
function frame() {
    requestAnimationFrame(frame);
    if (!playing || !engine) return;
    const speed = parseInt(el("cfgSpeed").value, 10);          // 1x,20x,100x
    const stopTick = Math.round((WARMUP + engine.cfg.simDuration) / DT) + 5;
    const steps = Math.max(2, Math.round(speed * DT / (1 / 60)));  // ticks this 60fps frame
    for (let s = 0; s < steps && engine.tickCount < stopTick; s++) engine.tick();
    if (engine.tickCount >= stopTick && playing) { playing = false; el("btnPlay").textContent = "▶ Play"; }
    // sub-tick pulse phase for dot animation
    engine.pulsePhase = ((engine.pulsePhase || 0) + (speed / 400)) % 1;
    drawAll();
}
requestAnimationFrame(frame);

/* ---------------- topology canvas ---------------- */
const C_UNITS = Math.round(C);

function drawTopology() {
    const cv = el("topoCanvas");
    const dpr = window.devicePixelRatio || 1;
    const W = cv.width = cv.clientWidth * dpr;
    const H = cv.height = 470 * dpr;
    const ctx = cv.getContext("2d");
    ctx.scale(dpr, dpr);
    const w = cv.clientWidth, h = 470;

    const edges = engine.edges, n = edges.length;
    const cx = w - 118, cy = h / 2;
    const marginX = 14, topY = 26;
    const rows = Math.ceil(n / 2);
    const rowH = Math.min(88, (h - topY - 16) / rows);

    for (let i = 0; i < n; i++) {
        const col = i % 2, row = Math.floor(i / 2);
        const ex = marginX + col * ((w - 300) / 2);
        const ey = topY + row * rowH + 6;
        const e = edges[i], up = e.up;

        // link to center
        ctx.strokeStyle = up ? "rgba(46,204,113,0.55)" : "rgba(231,76,60,0.6)";
        ctx.lineWidth = up ? 2 : 1.4;
        ctx.setLineDash(up ? [] : [5, 4]);
        ctx.beginPath(); ctx.moveTo(ex + 118, ey + 22); ctx.lineTo(cx, cy); ctx.stroke();
        ctx.setLineDash([]);

        // animated grant dots
        const g = (engine.grantLog || []).find(gl => gl.edge === i);
        if (up && g && g.newN + g.backN > 0) {
            const flow = Math.min(7, Math.max(1, Math.round((g.newN + g.backN) / 10)));
            for (let d = 0; d < flow; d++) {
                const p = ((engine.pulsePhase || 0) + d / flow) % 1;
                const x = ex + 118 + (cx - ex - 118) * p;
                const y = ey + 22 + (cy - ey - 22) * p;
                ctx.fillStyle = d % 3 === 0 ? "rgba(230,126,34,0.9)" : "rgba(52,152,219,0.85)";
                ctx.beginPath(); ctx.arc(x, y, 3.2, 0, 2 * Math.PI); ctx.fill();
            }
        }

        // edge card
        ctx.fillStyle = up ? "#ffffff" : "#fdecea";
        ctx.strokeStyle = up ? "#95a5a6" : "#e74c3c";
        ctx.lineWidth = 1.5;
        roundRect(ctx, ex, ey, 118, 44, 6); ctx.fill(); ctx.stroke();

        ctx.fillStyle = "#2c3e50"; ctx.font = "bold 11px sans-serif";
        ctx.fillText("edge-" + i, ex + 7, ey + 14);
        led(ctx, ex + 106, ey + 10, up);

        miniBar(ctx, ex + 7, ey + 19, 56, 11, e.newCount(), "#3498db", fmt(e.newCount()));
        miniBar(ctx, ex + 7, ey + 33, 56, 8, e.backCount(), "#e67e22", fmt(e.backCount()));
        if (!up) {
            ctx.fillStyle = "#e74c3c"; ctx.font = "9px sans-serif";
            ctx.fillText("LINK DOWN", ex + 68, ey + 30);
        }
    }

    // center node
    const granted = engine.grantedNew + engine.grantedBack;
    ctx.fillStyle = "#eafaf1"; ctx.strokeStyle = "#27ae60"; ctx.lineWidth = 2;
    roundRect(ctx, cx - 10, cy - 30, 128, 60, 8); ctx.fill(); ctx.stroke();
    ctx.fillStyle = "#145a32"; ctx.font = "bold 11px sans-serif";
    ctx.fillText("receiving center", cx - 2, cy - 12);
    ctx.fillStyle = "#2c3e50"; ctx.font = "10px monospace";
    ctx.fillText(C_UNITS + " u/s  budget " + Math.round(C * DT) + "/tick", cx - 2, cy + 2);
    ctx.fillText("granted " + granted + " u", cx - 2, cy + 16);

    // legend
    ctx.fillStyle = "#7f8c8d"; ctx.font = "10px sans-serif";
    ctx.fillText("● blue = fresh data   ● orange = backfill   red LED = link down", 14, h - 6);
}

function led(ctx, x, y, up) {
    ctx.fillStyle = up ? "#27ae60" : "#e74c3c";
    ctx.beginPath(); ctx.arc(x, y, 4.5, 0, 2 * Math.PI); ctx.fill();
    ctx.strokeStyle = "#888"; ctx.lineWidth = 1; ctx.stroke();
}
function miniBar(ctx, x, y, w, h, v, color, label) {
    ctx.fillStyle = "#ecf0f1"; ctx.fillRect(x, y, w, h);
    const f = Math.min(1, Math.log(1 + v) / Math.log(1 + 50000));
    ctx.fillStyle = color; ctx.fillRect(x, y, w * f, h);
    ctx.strokeStyle = "#b2babb"; ctx.strokeRect(x, y, w, h);
    ctx.fillStyle = "#555"; ctx.font = "8px monospace";
    ctx.fillText(label, x + w + 4, y + h);
}
function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
}

/* ---------------- generic line chart ---------------- */
function lineChart(canvas, series, opts) {
    const dpr = window.devicePixelRatio || 1;
    const W = canvas.width = canvas.clientWidth * dpr;
    const H = canvas.height = (opts.height || 210) * dpr;
    const ctx = canvas.getContext("2d");
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, W, H);
    const w = W / dpr, h = H / dpr;
    const pad = { l: 58, r: 10, t: 22, b: 18 };
    const all = series.flatMap(s => s.data);
    if (all.length < 2) {
        ctx.fillStyle = "#aaa"; ctx.font = "11px sans-serif";
        ctx.fillText(opts.title || "", pad.l, h / 2); return;
    }
    const t0 = all[0].t, t1 = all[all.length - 1].t;
    let yMax = 1e-9;
    for (const s of series) for (const p of s.data) yMax = Math.max(yMax, p.y);
    yMax *= 1.12;
    const X = t => pad.l + (t - t0) / Math.max(1e-9, t1 - t0) * (w - pad.l - pad.r);
    const Y = y => h - pad.b - (y / yMax) * (h - pad.t - pad.b);

    if (opts.shade) {
        ctx.fillStyle = "rgba(231,76,60,0.08)";
        let start = null;
        for (const p of opts.shade) {
            if (p.y > 0.02 && start === null) start = p.t;
            if (p.y <= 0.02 && start !== null) { ctx.fillRect(X(start), pad.t, X(p.t) - X(start), h - pad.t - pad.b); start = null; }
        }
        if (start !== null) ctx.fillRect(X(start), pad.t, X(t1) - X(start), h - pad.t - pad.b);
    }
    ctx.strokeStyle = "#dde1e3";
    for (let g = 0; g <= 3; g++) {
        const yy = pad.t + g * (h - pad.t - pad.b) / 3;
        ctx.beginPath(); ctx.moveTo(pad.l, yy); ctx.lineTo(w - pad.r, yy); ctx.stroke();
        ctx.fillStyle = "#95a5a6"; ctx.font = "9px monospace";
        ctx.fillText(fmtShort(yMax * (1 - g / 3)), 2, yy + 3);
    }
    ctx.fillStyle = "#95a5a6"; ctx.font = "10px sans-serif";
    ctx.fillText(opts.title || "", pad.l, 12);
    ctx.fillText(t0.toFixed(0) + "s", pad.l, h - 5);
    ctx.fillText(t1.toFixed(0) + "s", w - pad.r - 24, h - 5);

    for (const s of series) {
        ctx.strokeStyle = s.color; ctx.lineWidth = 1.5;
        ctx.beginPath();
        s.data.forEach((p, i) => i === 0 ? ctx.moveTo(X(p.t), Y(p.y)) : ctx.lineTo(X(p.t), Y(p.y)));
        ctx.stroke();
        if (s.fill) {
            ctx.lineTo(X(s.data[s.data.length - 1].t), Y(0));
            ctx.lineTo(X(t0), Y(0)); ctx.closePath();
            ctx.globalAlpha = 0.15; ctx.fillStyle = s.color; ctx.fill(); ctx.globalAlpha = 1;
        }
    }
    // legend right side
    let lx = w - 12; ctx.font = "9px sans-serif";
    for (let k = series.length - 1; k >= 0; k--) {
        const s = series[k];
        const tw = ctx.measureText(s.label).width;
        lx -= tw + 14;
        ctx.fillStyle = s.color; ctx.fillRect(lx, 5, 9, 9);
        ctx.fillStyle = "#555"; ctx.fillText(s.label, lx + 12, 13);
    }
}
const fmtShort = v => v >= 1e6 ? (v / 1e6).toFixed(1) + "M" : v >= 1e3 ? (v / 1e3).toFixed(0) + "k" : v.toFixed(1);

function chartData() {
    const h = engine.hist;
    if (!h || h.length < 2) return null;
    const sTot = h.map(p => ({ t: p.t, y: p.backlogTot }));
    const sBack = h.map(p => ({ t: p.t, y: p.backlogBack }));
    const sGnew = h.map(p => ({ t: p.t, y: p.gNew }));
    const sGback = h.map(p => ({ t: p.t, y: p.gBack }));
    const shade = h.map(p => ({ t: p.t, y: p.down > 0.5 ? 1 : 0 }));
    // rolling mean of delivery delay over a 2 s window, computed per history point
    // via the CUMULATIVE delivered counters (dN/dB) -> O(n) total.
    const rollN = [], rollB = [];
    for (let i = 0; i < h.length; i++) {
        const p = h[i];
        if (p.t < WARMUP) { rollN.push({ t: p.t, y: 0 }); rollB.push({ t: p.t, y: 0 }); continue; }
        // index of the first history point in the window [t-2, t]
        let lo = 0;
        if (p.t >= 2.0) {
            const target = p.t - 2.0;
            lo = i;
            while (lo > 0 && h[lo - 1].t >= target) lo--;
        }
        const aN = windowMean(engine.reg.delayNew, h[lo].dN, p.dN);
        const aB = windowMean(engine.reg.delayBack, h[lo].dB, p.dB);
        rollN.push({ t: p.t, y: aN || 0 });
        rollB.push({ t: p.t, y: aB || 0 });
    }
    return {
        backlog: { series: [
            { label: "total", data: sTot, color: "#8e44ad", fill: true },
            { label: "backfill", data: sBack, color: "#e67e22" }],
            shade: shade, title: "Queued units at edge servers", height: 200 },
        served: { series: [
            { label: "new data", data: sGnew, color: "#3498db" },
            { label: "backfill", data: sGback, color: "#e67e22" }],
            shade: shade, title: "Granted transmissions (units per 0.1 s tick)", height: 200 },
        delays: { series: [
            { label: "new mean", data: rollN, color: "#27ae60" },
            { label: "backfill mean", data: rollB, color: "#c0392b" }],
            shade: shade, title: "Mean latency of units delivered (2 s window)", height: 200 }
    };
}
function windowMean(arr, from, to) {
    const a = Math.max(0, from), b = Math.min(arr.length, Math.max(a, to));
    if (b <= a) return null;
    let s = 0;
    for (let i = a; i < b; i++) s += arr[i];
    return s / (b - a);
}

/* ---------------- stats + event log ---------------- */
function drawStats() {
    const s = engine.summary();
    el("statsTime").textContent = engine.t.toFixed(1) + " / " + (WARMUP + engine.cfg.simDuration).toFixed(0) + " s";
    el("statsDeliv").textContent = fmt(s.delivNew + s.delivBack);
    el("statsSplit").textContent = "new " + fmt(s.delivNew) + " · backfill " + fmt(s.delivBack);
    el("statsQueue").textContent = fmt(backlogSafe());
    el("statsPeak").textContent = fmt(s.backlogPeak);
    el("statsOutages").textContent = s.outages + " (with backlog: " + s.restoresWithBacklog + ")";
    el("statsPolicyRate").textContent = engine.policy.description;
}
function backlogSafe() {
    let tot = 0;
    for (const e of engine.edges) tot += e.newCount() + e.backCount();
    return tot;
}
function drawLog() {
    const lv = el("eventsLog");
    for (const e of (engine.events || [])) {
        const d = document.createElement("div");
        d.className = "logline " + e.kind;
        d.textContent = "t=" + e.t.toFixed(1) + "  edge-" + e.edge + "  " +
            (e.kind === "down" ? "LINK DOWN" : "reconnected" + (e.backlog > 0 ? " · " + fmt(e.backlog) + " units to backfill" : ""));
        lv.appendChild(d);
    }
    while (lv.childElementCount > 300) lv.removeChild(lv.firstChild);
    lv.scrollTop = lv.scrollHeight;
}

function drawAll() {
    if (!engine) return;
    drawTopology();
    const ch = chartData();
    if (ch) {
        lineChart(el("chartBacklog"), ch.backlog.series, { shade: ch.backlog.shade, title: ch.backlog.title });
        lineChart(el("chartServed"), ch.served.series, { shade: ch.served.shade, title: ch.served.title });
        lineChart(el("chartDelay"), ch.delays.series, { shade: ch.delays.shade, title: ch.delays.title });
    }
    drawStats();
    drawLog();
}

/* ---------------- batch compare: 4 policies, same inputs ---------------- */
function runCompare() {
    const cfg = readConfig();
    const rows = [];
    for (const key of POLICY_KEYS) {
        const eng = new Engine(cfg, key);
        eng.run(WARMUP + cfg.simDuration + 1);
        rows.push(eng.summary());
    }
    compareRows = rows;
    renderCompare(rows, cfg);
}

function renderCompare(rows, cfg) {
    const cols = [
        ["Policy", r => r.policy],
        ["delivNew", r => fmt(r.delivNew)],
        ["delivBack", r => fmt(r.delivBack)],
        ["newDelay mean", r => r.newDelayMean.toFixed(2) + " s"],
        ["backDelay mean", r => r.backDelayMean.toFixed(2) + " s"],
        ["back P95", r => r.backDelayP95.toFixed(2) + " s"],
        ["backlogPeak", r => fmt(r.backlogPeak)],
        ["drains", r => r.drains + " (max " + r.drainMax.toFixed(1) + " s)"],
        ["jain", r => r.jainBackfill.toFixed(3)]
    ];
    const tbl = el("compareTable");
    let html = "<thead><tr>" + cols.map(c => "<th>" + c[0] + "</th>").join("") + "</tr></thead><tbody>";
    for (const r of rows) {
        html += "<tr>" + cols.map((c, i) => "<td" + (i === 0 ? ' class="pol ' + r.policy + '"' : "") + ">" + c[1](r) + "</td>").join("") + "</tr>";
    }
    html += "</tbody>";
    tbl.innerHTML = html;

    // grouped bar chart: newDelayMean & backDelayMean per policy
    const cv = el("barCanvas");
    const dpr = window.devicePixelRatio || 1;
    const W = cv.width = cv.clientWidth * dpr, H = cv.height = 240 * dpr;
    const ctx = cv.getContext("2d");
    ctx.scale(dpr, dpr);
    const w = W / dpr, h = 240;
    ctx.clearRect(0, 0, w, h);
    const metrics = [
        { label: "newDelay mean", get: r => r.newDelayMean, color: "#27ae60" },
        { label: "backDelay mean", get: r => r.backDelayMean, color: "#c0392b" },
        { label: "backlogPeak/1000", get: r => r.backlogPeak / 1000, color: "#8e44ad" }
    ];
    const gw = (w - 90) / rows.length / metrics.length;
    const yMax = Math.max(1, ...rows.flatMap(r => metrics.map(m => m.get(r)))) * 1.15;
    rows.forEach((r, i) => {
        metrics.forEach((m, k) => {
            const v = m.get(r);
            const x0 = 70 + (i * metrics.length + k) * gw;
            const bh = (v / yMax) * (h - 60);
            ctx.fillStyle = m.color;
            ctx.fillRect(x0, h - 34 - bh, gw * 0.8, bh);
            ctx.fillStyle = "#555"; ctx.font = "9px monospace";
            ctx.fillText(v >= 100 ? Math.round(v) : v.toFixed(1), x0 - 6, h - 38 - bh);
        });
        ctx.fillStyle = POLICY_COLORS[r.policy]; ctx.font = "bold 10px sans-serif";
        ctx.fillText(r.policy, 70 + i * metrics.length * gw + 8, h - 20);
    });
    let lx = 74;
    metrics.forEach(m => {
        ctx.fillStyle = m.color; ctx.fillRect(lx, 8, 10, 10);
        ctx.fillStyle = "#444"; ctx.font = "9px sans-serif";
        const tw = ctx.measureText(m.label).width;
        ctx.fillText(m.label, lx + 13, 16);
        lx += tw + 30;
    });
    const cfgLine = "scenario: N=" + cfg.numEdges + ", ρ=" + cfg.loadFactor + ", " + cfg.imbalance +
        ", outages=" + cfg.outageMode + ", seed=" + cfg.seed + ", duration=" + cfg.simDuration + "s";
    el("compareCfg").textContent = cfgLine;
}

/* ---------------- export ---------------- */
function saveText(name, text) {
    const blob = new Blob([text], { type: "text/csv;charset=utf-8" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.appendChild(a);
    a.click();
    URL.revokeObjectURL(a.href);
    a.remove();
}
function exportCompareCsv() {
    const cols = ["policy", "n", "rho", "imbalance", "outageMode", "seed",
        "genNew", "genBack", "delivNew", "delivBack", "leftNew", "leftBack",
        "newDelayMean", "newDelayP50", "newDelayP95", "newDelayP99",
        "backDelayMean", "backDelayP50", "backDelayP95", "backDelayP99",
        "backlogArea", "backlogPeak", "outages", "restoresWithBacklog",
        "drains", "drainMean", "drainMax", "jainBackfill"];
    const csv = [cols.join(",")]
        .concat(compareRows.map(r => cols.map(c => r[c]).join(",")))
        .join("\n");
    const cfg = readConfig();
    saveText("compare_N" + cfg.numEdges + "_rho" + cfg.loadFactor + "_" + cfg.imbalance +
        "_" + cfg.outageMode + "_s" + cfg.seed + ".csv", csv);
}
function exportTickCsv() {
    if (!engine || engine.hist.length === 0) return;
    const n = engine.cfg.numEdges;
    const head = ["t", "grantedNew", "grantedBack", "down", "backlogNewTot", "backlogBackTot"];
    const csv = [head.join(",")].concat(
        engine.hist.map(p => [
            p.t.toFixed(3), p.gNew, p.gBack, p.down, p.backlogNew, p.backlogBack
        ].join(","))
    ).join("\n");
    saveText("ticks_" + engine.policy.key + "_s" + engine.cfg.seed + ".csv", csv);
}

/* ---------------- wiring ---------------- */
function wire() {
    el("btnPlay").onclick = () => { playing = !playing; el("btnPlay").textContent = playing ? "⏸ Pause" : "▶ Play"; };
    el("btnStep").onclick = () => { if (engine) { engine.tick(); drawAll(); } };
    el("btnReset").onclick = resetSim;
    el("btnCompare").onclick = runCompare;
    el("btnCsv").onclick = exportCompareCsv;
    el("btnTickCsv").onclick = exportTickCsv;
    el("cfgImbalance").onchange = () => {
        el("cfgZipf").style.display = el("cfgImbalance").value === "ZIPF" ? "inline-block" : "none";
        resetSim();
    };
    for (const id of ["cfgN", "cfgRho", "cfgOutage", "cfgDuration", "cfgPolicy", "cfgSeed", "cfgZipf"]) {
        el(id).addEventListener("change", resetSim);
    }
    // policy description under selection
    const upd = () => {
        const p = policyByKey(el("cfgPolicy").value);
        el("policyDesc").textContent = p.description;
    };
    el("cfgPolicy").addEventListener("change", upd);
    upd();
    resetSim();
}
window.addEventListener("DOMContentLoaded", wire);

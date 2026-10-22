# Backfill scheduling simulation — edge → receiving center (Topic 2)

A complete, reproducible comparison of **four data-backfill scheduling policies** on a
fog/edge topology built with **iFogSim2**: several edge servers with realistic link outages
feed one receiving center; when a link is down, generated data waits as *backfill* and must
be scheduled back without starving *fresh* data.

## The four policies (identical inputs, fair fight)

| key | policy | in one sentence |
|-----|--------|-----------------|
| `fifo` | FIFO | strict generation order — fresh data queues behind the accumulated backlog |
| `rr` | Round-robin | budget split in turn over connected edges with data |
| `ratelimit` | Rate-limit | backfill goes at a **fixed rate R** no matter the backlog; remainder → fresh data |
| `newfirst` | New-data-first | fresh data first; backlog gets only a small fixed quota q |

All four receive **the same everything** per run: queues, arrival streams (per-sensor seeded
Poisson), outage timelines (separate stream), token budget, seed. The scheduler only
executes a policy's decisions — it never overrides them.

## Repository layout

```
iFogSim2-vendor/     pristine upstream Cloudslab/iFogSim2 (unchanged)
sim/                 our framework (Java, plain javac build — no Maven)
    build.ps1 / run.ps1    build + run/grid driver (Windows PowerShell)
    src/backfill/          model, devices, controllers, policies, metrics, runners
    build/                 compiled vendor + our classes (git-ignorable)
web/                 visual demo + in-browser batch compare (pure JS, no dependencies)
    index.html / style.css / engine.js / app.js
    test/engine_test.js    headless parity + conservation test (node web/test/engine_test.js)
    WEB.md lives in docs/ — full guide: knobs, canvas, charts, batch mode, parity contract
analysis/            Python scripts that aggregate results/runs into tables/figures
results/runs/        CSVs: <scenario>/run_<scenario>_s<seed>_<policy>{_run,_tick}.csv
docs/                MODEL.md (the model spec), ADD_POLICY.md, REPORT.md
tools/jdk-21...      local JDK 21 (upstream iFogSim2 needs SequencedCollection API)
```

## Quick start

Harness prerequisites: **JDK 21** on your machine (the upstream
iFogSim2 needs the `SequencedCollection` API). The `tools/` directory holds a local copy of
Eclipse Temurin 21 (excluded from git via `.gitignore` as it is ~500 MB — install your own
or reuse this one).

Windows (PowerShell):

```powershell
# one run (compiles the vendor once if needed, then the framework)
.\sim\run.ps1 run -Policy fifo -Scenario base -Seed 0        # -> results\runs\base\*.csv

# full experiment grid (10 scenarios x 4 policies x seeds), ~6-11 s per run
.\sim\run.ps1 grid -Seeds 5

# framework self-test (equal inputs, conservation, capacity bound)
.\sim\run.ps1 test
```

Linux/macOS (bash mirrors of the same commands):

```bash
./sim/run.sh run  -P fifo -S base -s 0     # one run
./sim/run.sh grid -s 5                     # full grid
./sim/run.sh test                          # self-test
```

Web demo and tests:

```text
# web visual demo: just open web\index.html in a browser
# web headless test:  node web\test\engine_test.js
```

Scenario slices of the experiment grid (all one-factor-at-a-time from `base`):

* `base` (N=6, ρ=0.7, EVEN, SCATTERED), `N4/N10/N16`, `ρ ∈ {0.4, 0.9, 1.1}`,
  `imbalance_zipf`, `imbalance_dominant`, `storm` (= COHORT outage regime).
* `results/runs/<scenario>/...` — summaries in `*_run.csv`, 0.1 s time series in `*_tick.csv`.

Report with measured conclusions: `docs/REPORT.md`.

See `docs/MODEL.md` for the exact model semantics, `docs/WEB.md` for the visual demo
and `docs/ADD_POLICY.md` to add a policy. An external bug-report review (15 findings) was
independently verified and actioned — see `docs/BUG-REPORT-RESPONSE.md`.

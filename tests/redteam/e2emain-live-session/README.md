# e2emain-live-session

Module sources used by `tests/e2e/tools/live-session.mjs`, one directory per kind of edit. They are rendered here for
module `live-alpha` (package `vibe.live_alpha`, command `/lalpha`). To try one by hand, copy its `LiveModule.java`
into `workspace/modules/live-alpha/` and run `curl -s -X POST http://127.0.0.1:8765/api/modules/live-alpha/reload`.

| Dir | Fault | Expected (all observed OK on 2026-10-08) |
|---|---|---|
| `good` | none: command + `PlayerCommandPreprocessEvent` listener + `every(10)` task, `config.yml` note | loads; `/lalpha` answers exactly once; `hits` goes up by exactly 1 per command (no duplicate listener) |
| `compile-error` | `hits.getValue()` (no such method) | `ok:false`, `compilation failed: 1 error(s)`, the old version keeps answering |
| `syntax-error` | missing `;` | same as above |
| `runtime-exception` | command throws `IllegalStateException`, the task throws an NPE every 7th run | loads; the sender gets `⚠ Erreur dans /lalpha ...`; module stays ENABLED (below the circuit-breaker rate) |
| `loop-command` | `while (spin) {}` in the command | the sender gets `/lalpha a été interrompue : trop long` after **252-254 ms** (13/13) |
| `loop-enable` | `while (spin) {}` in `enable()` | rolled back after **2062-2108 ms** (4/4), the previous version answers again |
| `loop-task` | `ctx.every(1, () -> { while (spin) {} })` | TRIPPED after **1.3-1.6 s** (4/4; 2 timeouts of 250 ms each), then a fixed version reloads fine |
| `async-loop` | endless `ctx.async` loop (sleeps 20 ms per turn) | loads; killed at the next reload |

Live session result (`node tools/live-session.mjs --duration-s 600 --seed 4242`, main server, bot online the whole time,
4 modules edited concurrently every 5-20 s, 182 edits in 613 s, report `tests/e2e/reports/live-2026-10-08T09-48-19-351Z.json`):

* bot connected 624 s, **0 disconnects**; `/api/status` polled 611 times, 0 errors, p50 1 ms / max 6 ms
* 1-min TPS min **18.83** / avg 19.70; `/api/status` mspt p50 2.5 ms, p95 17.5, max 34.8
* Paper `/mspt` max tick over 5 s windows: p50 6.3 ms; **4 ticks of ~2006 ms** (the 4 `loop-enable` edits) and 18 windows
  at ~255 ms (`loop-command` and `loop-task` timeouts); nothing else above 250 ms
* POST reload wall time: good p50 78 ms / p95 110 ms (86 edits), compile errors p50 76-91 ms; the 1.2-1.5 s outliers
  waited for another module's 2 s `enable()` freeze. Watcher reload after a `config.yml` save: p50 1.85 s, max 2.34 s
* good-version command round trip (bot): p50 2 ms, p95 4 ms, max 96 ms (320 commands); never a duplicate reply
* final: 4/4 modules ENABLED (generations 37-41), 0 runtime errors; no non-module ERROR/WARN in the server console

The single anomaly the run reported (t=223 s, "config reload via watcher: state COMPILING") was a harness false positive:
`lastAttemptAt` is set when the main-thread swap starts, while the state is still COMPILING. Fixed in the script (it now
waits for a settled state).

**Design trade-off worth knowing:** each infinite loop in `enable()` freezes the whole server for the full
`guard.enable-budget-ms` (2 s): players freeze or rubber-band, and the client gets no time update for ~2.9 s. During the
session that happened 4 times (1-min TPS 18.8). A smaller budget (for example 750-1000 ms) would halve it, but legitimate
heavy `enable()` code would then get rolled back.

# VibeCraft end-to-end tests

A real player (a [mineflayer](https://github.com/PrismarineJS/mineflayer) bot named **VibeBot**) joins the
server and **stays connected for the whole run** while the runner writes test modules into
`workspace/modules/e2e-*`, drives the engine through the HTTP API (`http://127.0.0.1:8765`) and checks the
results through the bot's chat (command output, listener messages, unknown-command replies, the client's
command tree / tab completion...).

Any kick / disconnect of the bot, at any time, fails the run (never masked by a reconnect: after a disconnect
the runner may connect a NEW bot so the remaining scenarios still produce results, but the run is failed).
So does the server's 1-minute TPS falling to 15 or lower (`GET /api/status`, sampled every 2 s).

## Run it against the main server (`server/`, ports 25565 / API 8765)

Prerequisites (once):

1. Node >= 20 (tested with Node 25): `cd tests/e2e && npm install`
2. **ViaVersion + ViaBackwards** in `server/plugins` (already there). mineflayer 4.39 speaks at most Minecraft
   **26.1**, the server is Paper **26.3**; ViaBackwards translates. To (re)install: `tests/e2e/setup-via.sh server/plugins`.
3. `online-mode=false` (already the case). The runner itself runs `whitelist add VibeBot`, `op VibeBot` and
   `gamemode creative VibeBot` through `POST /api/command` (RCON fallback with `--rcon-dir`).
4. VibeCraft deployed and the server running:
   ```bash
   vibecraft-plugin/build.sh --deploy
   cd server && ../runtime/jdk-25/bin/java -Xms1G -Xmx3G -jar paper.jar --nogui
   ```

Then:

```bash
cd tests/e2e
node run-e2e.mjs --rcon-dir ../../server          # everything (~4 min), ai-* scenarios SKIPPED (see below)
node run-e2e.mjs --only hot-reload,compile-error  # some scenarios (exact name or prefix, comma-separated)
node run-e2e.mjs --list                           # scenario names
npm test                                          # = node run-e2e.mjs
```

**Do not run it during a live demo**: it creates / reloads / deletes `e2e-*` modules (only those), broadcasts
"chargé à chaud" lines, freezes the main thread on purpose for up to 5 s twice (enable() budget test) and runs
two full GCs (`generation-gc`).

Output: one block per scenario (steps with timings, PASS / FAIL / SKIP with the reason), then a summary table.
A JSON report is written to `tests/e2e/reports/<timestamp>.json` (steps, failed checks, warnings, measured
timings, TPS samples, longest client-side tick gaps, bot events, last chat lines, slowest API calls).
Exit code **0** = everything passed (SKIP does not fail), **1** = at least one failure (or setup failure),
**2** = usage error.

### Options (env var in brackets)

| Option | Default | |
|---|---|---|
| `--mc-host` [`VIBE_MC_HOST`] / `--mc-port` [`VIBE_MC_PORT`] | `127.0.0.1` / `25565` | Minecraft server |
| `--mc-version` [`VIBE_MC_VERSION`] | `26.1` | protocol the bot speaks |
| `--username` [`VIBE_BOT_NAME`] | `VibeBot` | |
| `--api` [`VIBE_API`] | `http://127.0.0.1:8765` | VibeCraft HTTP API |
| `--token` [`VIBE_TOKEN`] | | `X-Vibe-Token` when `api.token` is set |
| `--modules-dir` [`VIBE_MODULES_DIR`] | `<repo>/workspace/modules` | must be the server's `modules-dir` (checked at startup with a probe module) |
| `--data-dir` [`VIBE_DATA_DIR`] | `<modules-dir>/../data` | the server's `data-dir` (store test) |
| `--prefix` [`VIBE_PREFIX`] | `e2e-` | only directories with this prefix are ever written / deleted |
| `--only` [`VIBE_ONLY`] / `--skip` | | scenario filters |
| `--rcon-dir` [`VIBE_RCON_DIR`] | | server dir for the RCON fallback (whitelist / op) |
| `--fake-claude <log>` [`VIBE_FAKE_CLAUDE`] | | the server runs `tools/fake-claude.mjs` as `ai.claude-path`: enables `ai-jobs` / `ai-ideas` |
| `--wait-api <s>` | `90` | wait for the API to come up (handy right after starting the server) |
| `--min-tps <n>` | `15` | |
| `--no-reconnect` | | after a disconnect, do not connect a new bot for the next scenarios |
| `--bail` | | stop at the first failure |
| `--keep` | | keep the test modules on disk (debugging) |

If the server started less than 3 minutes ago and its 1-minute TPS is still below 19.5 (boot ticks are part of
the rolling average), the runner waits up to 75 s before the first scenario.

## Run it in a private integration sandbox (what the TESTS role uses)

`tools/integ.sh` builds a **snapshot** of the plugin (`OUT_DIR=out-tests`, never `--deploy`) and runs it in
`tests/e2e/.sandbox-integ/`: Paper 26.3 + Via plugins, game **25572**, RCON **25573**, API **8772**, `-Xmx2G`,
`modules-dir` / `data-dir` inside the sandbox, and `ai.claude-path` = `tools/fake-claude.mjs` (a scripted stand-in
for Claude Code that never calls a model, so the AI job pipeline can be tested end to end).

```bash
tests/e2e/tools/integ.sh all              # stop + build snapshot + setup + start + full run + stop
tests/e2e/tools/integ.sh setup            # (re)build the snapshot, create the sandbox if needed, write its config
tests/e2e/tools/integ.sh start|stop|status
tests/e2e/tools/integ.sh run --only ai-   # run-e2e.mjs against the sandbox (+ any run-e2e option)
tests/e2e/tools/integ.sh restart-check    # stops/starts the sandbox: startup loading, .vibe/disabled, store
```

The sandbox server must be stopped before `setup` (Windows locks the loaded jar). `stop` / `status` only ever
touch the JVM carrying `-Dvibecraft.e2e.sandbox=<dir name>`.

## Scenarios

Each scenario is independent: it removes leftovers of its own module ids, sets up, asserts, and always
cleans up (deletes `modules/e2e-*` + `data/e2e-*` and waits for the module to disappear from the API). Version
markers contain a per-run id so `ctx.shared()` counters of previous runs never interfere. Checks done with
`t.expect()` do not stop the scenario: one run reports every broken aspect.

| Scenario | What it proves |
|---|---|
| `api-contract` | 404 `{error}` for unknown modules (GET and POST reload) and endpoints, `/api/command` output (leading `/` optional), `/api/players` (bot is op), `/api/ai`, `/api/ideas`, `POST /api/ai/{id}` -> 503 while the AI is unavailable (never called when it is available) |
| `dashboard` | `GET /` serves the complete French dashboard (`text/html; charset=utf-8`, polls `/api/status`, no external resource), `/index.html`, 404 for unknown files and path traversal; **every field and type** of `/api/status` (server, ModuleInfo, Diagnostic, AiJob, Idea, ai) with a known module; 401 without token when a token is configured |
| `hot-reload` | command + listener module (2 files) reloaded 4 times: exactly one command reply and one listener message each time, `listeners == 1`, generation increases, client command tree contains the command, no redundant watcher reload after an explicit one (warning), reload announcement in chat (warning) |
| `vibe-files-and-logs` | `.vibe/status.json`, `.vibe/build.log`, `ctx.logger()` visible in `GET /api/modules/{id}/logs`, `limit` |
| `watcher-auto-reload` | without any API call: new directory loads, `config.yml` edit reloads, `.java` edit reloads, a half-written file immediately fixed is only compiled once (debounce), a `.java` in a sub-directory is compiled (error reported as `util/Deep.java:8`), deleting a file reloads |
| `compile-error` | type and syntax errors: `ok=false`, old version keeps answering, generation unchanged, diagnostic file/line/column, `rolledBack`, `lastError`, `.vibe/build.log`, ops notified; fixed version clears them; a NEW module that does not compile is `FAILED` (generation 0) and is loaded by the watcher once fixed |
| `module-class-errors` | two `VibeModule` classes, none, no public no-arg constructor, `ctx.on(abstract event)` -> rollback with a clear error; abstract base class + one concrete class loads; a new module without module class -> `FAILED` |
| `context-cleanup` | `ctx.recipe` / `ctx.entity` / `ctx.track` / `ctx.onCleanup` / `ctx.listen` undone on reload and disable, in the spec'd order (`disable()` first, cleanups in reverse registration order); a lambda of an unloaded generation kept in `ctx.shared()` throws `ModuleKilledError` and never runs |
| `runtime-exception` | exception in a command, NPE, exception in a listener (the command still runs), exception in a task: nothing reaches Bukkit ("internal error"), errors recorded (`recentErrors`, `errors.log`, logs), module still enabled |
| `infinite-loop-command` | `while(spin){}`, a loop that catches `Throwable`, a loop calling methods: the next command answers within budget + slack, client-side tick stall measured |
| `deep-recursion` | `StackOverflowError` caught, recorded, module keeps working |
| `thread-sleep` | `Thread.sleep(10 s)` / `Thread.sleep(Duration)` on the main thread throw instead of freezing |
| `infinite-loop-enable` | `enable()` that loops / swallows Throwable / throws / recurses: rollback to v1 (answers exactly once: the failed attempt's command was cleaned up), stall < enable budget + slack; a brand new failing module ends `FAILED` with nothing registered |
| `forbidden-calls` | 17 forbidden calls + 4 hidden behind method / constructor references + one in a helper class file: each rejected at load with an ERROR diagnostic, old version still running |
| `raw-registrations` | raw `registerEvents`, `runTaskTimer`, `BukkitRunnable.runTaskTimer`, `scheduleSyncRepeatingTask`, `runTaskLater`, raw `CommandMap.register` command: old generation's tasks stop, listener / command not duplicated, an endless loop in the raw command is interrupted, everything gone after disable (incl. tab completion) |
| `async-loop-kill` | endless loops in `ctx.async`, `runTaskAsynchronously`, a raw `Thread`, a JDK executor and `CompletableFuture.runAsync` stop after reload and after disable (counters frozen, thread dead) |
| `store-persistence` | `ctx.store()` survives reload and disable/enable (`data/<id>/store.yml` written on unload), `ctx.shared()` survives reload |
| `disable-enable` | disable unloads + `.vibe/disabled` + command leaves the client command tree; reload refused ("disabled"); file edits do not re-enable; enable restores |
| `module-removal` | deleting the directory unloads the module; 404 must only be reported once it is unloaded; command, listener and tab completion gone |
| `scaffold-create` | `POST /create` scaffolds a module that the watcher loads; description; no overwrite; invalid id -> 4xx |
| `parallel-reloads` | 4 modules loaded and reloaded concurrently; wall time vs sum of durations; one module failing to compile does not affect the 3 others |
| `rapid-reloads` | 30 edits + reload requests 100 ms apart (some half-written): every request answers, final version runs exactly once, `listeners == 1` |
| `generation-gc` | 8 generations of a module with command / listeners / tasks / raw registrations, then full GCs: the 7 unloaded generations' class loaders are collected (no metaspace leak over a day of reloads) |
| `ai-jobs` | *(needs `--fake-claude`)* `POST /api/ai/{id}` -> 201 job, 409 while busy, SUCCEEDED with result / cost / session, progress lines and chat announcements (throttled), CLI run in the workspace root with the documented flags, follow-up job resumes the conversation, reset, stop -> STOPPED with the whole process tree killed, failing agent -> FAILED |
| `ai-ideas` | *(needs `--fake-claude`)* `POST /api/ideas`, accept with `?module=` -> job -> module works, idea ACCEPTED, dismiss, 404 for unknown idea, in-game `/idea` and `/vibe ai <id> <request>` |
| `circuit-breaker` | task throwing every tick -> `TRIPPED` (tasks 0, command gone, ops notified without flooding the chat); reload re-arms (trips again); fixed code stays enabled |
| `circuit-breaker-timeouts` | task looping forever every tick -> interrupted each tick, then `TRIPPED`; server kept ticking |
| `vibe-command` | in-game `/vibe list`, `info`, `reload`, `disable`, `enable`, `/vc` alias, `help`, `errors`, unknown sub-command, `dashboard` (URL with the API port), tab completion of sub-commands and module ids, `/vibe new` |
| *(global)* `tps` | 1-minute TPS from `/api/status` > 15 during the whole run |
| *(global)* `bot-connected` | the bot was never kicked or disconnected |

`t.warn(...)` observations (not failures) are listed in the report and counted in the summary table.

Outside `run-e2e.mjs`: `tools/restart-check.mjs` (via `integ.sh restart-check`) proves that a server restart
loads every module except the `.vibe/disabled` one, that a module failing to compile does not prevent the others,
and that `ctx.store()` survives the restart.

## Layout

```
run-e2e.mjs            runner (CLI, setup, scenario loop, global checks, summary, JSON report)
lib/bot.mjs            VibeBot: join (offline auth), tick_end shim, records kicked/end/error, cmd(), waitForMessage(),
                       command tree (declare_commands), tabComplete(), client-side TPS / tick gaps; NO auto-reconnect
lib/api.mjs            HTTP API client (fetch)
lib/workspace.mjs      write/delete module dirs (prefix-guarded), read .vibe files, wait for load attempts / removal
lib/harness.mjs        per-scenario context: steps, assert/expect/check/skip, cleanup, deploy(), expectCmd(), stallMs()
lib/scenarios.mjs      the scenarios
lib/fixtures.mjs       fixture rendering (package rename, %TOKENS%, /*@INJECTION@*/ points) + variants
fixtures/<name>/       test module sources (compile against the frozen API)
setup-via.sh           installs ViaVersion + ViaBackwards
tools/compile-fixtures.mjs   javac check of every fixture/variant (npm run compile-fixtures)
tools/sandbox.sh             private Paper sandbox (default 25570 / RCON 25571, no VibeCraft): create|start|stop|status
tools/integ.sh               integration sandbox with a VibeCraft snapshot + fake Claude (25572 / 25573 / API 8772)
tools/fake-claude.mjs        scripted stand-in for the Claude Code CLI (stream-json), logs every invocation
tools/restart-check.mjs      server restart check (integration sandbox only)
tools/sandbox-check.mjs      validates the bot library on the plain sandbox (join via ViaBackwards, 2+ min, commands, op, kick)
tools/kick-repro.mjs         reproduces the invalid_player_movement kick without the tick_end shim
tools/fake-api.mjs           fake VibeCraft API (RCON-backed) used by:
tools/selftest-fake-api.mjs  unit-check of the runner itself (pass run, kick detection + reconnect, unreachable API, usage)
```

## Notes / gotchas found while building this

* **mineflayer on 26.x gets kicked ~0.2-1.3 s after spawning** (`multiplayer.disconnect.invalid_player_movement`).
  Root cause: since 1.21.2 the server accepts at most one position-carrying move packet per *client tick*, client
  ticks being delimited by the serverbound `tick_end` packet; mineflayer 4.39 never sends `tick_end`, so its 2nd
  position packet (physics right after spawn / teleport, or any movement) gets it kicked. A bot that spawns exactly
  on the ground and does not move can survive for a while, which is why it looked random (other sandboxes vs the
  main server, whose spawn point differs). `lib/bot.mjs` sends `tick_end` every 50 ms like a vanilla client, and
  before a 2nd position packet in the same tick. `node tools/kick-repro.mjs --port <p> --rcon-dir <dir>` shows it:
  kicked after ~185 ms without the shim, stable with it. Not a Via or spawn-position problem, no reconnect needed.
* Chat components may contain NBT longs (`{"":[hi,lo]}`) that prismarine-chat drops (e.g. `/time query day`);
  the bot converts them before parsing.
* Emoji outside the BMP (🤖, 🗑, 🚀...) arrive garbled in the bot's chat text (NBT strings use modified UTF-8,
  prismarine decodes them as plain UTF-8). Scenarios never match on them; a real client shows them fine.
* mineflayer's own `bot.tabComplete()` writes the pre-1.13 packet layout; `VibeBot.tabComplete()` sends the
  26.x one (with a transaction id).
* 26.x `/time query` syntax: `gametime`, `day` (timeline), `time` (clock) — `daytime` no longer exists.
  `/list` needs op in 26.3.
* Never run the e2e suite against a server whose `modules-dir` is used for a live demo: it creates and
  deletes `e2e-*` modules (only those).

## Validating the harness itself

```bash
cd tests/e2e
tools/sandbox.sh create        # copies the Paper runtime from ../../server, installs Via plugins
tools/sandbox.sh start
node tools/sandbox-check.mjs   # bot joins via ViaBackwards, 130 s connected, vanilla commands, op via RCON, kick detected
node tools/selftest-fake-api.mjs
npm run compile-fixtures
tools/sandbox.sh stop
```

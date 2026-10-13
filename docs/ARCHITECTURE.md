# VibeCraft — architecture & contracts

Goal: a Minecraft (Paper 26.3) server on which people **vibe-code features live** (conference demo):
the audience asks for a feature, one or several AI sessions (Claude Code) write it, and it appears in
the running server **without disconnecting anybody** and **without ever crashing the server**, even
while code is half-written or buggy. Several AI sessions work **in parallel**, each on its own
"aspect" (= module).

## 1. Big picture

```
 players (vanilla client 26.3)            presenter / audience
        |                                 |  /vibe ai <module> <request>   (in game)
        v                                 |  dashboard http://127.0.0.1:8765/
 +---------------------------- Paper 26.3 server --------------------------------+
 |  VibeCraft host plugin (vibecraft-plugin/)                                     |
 |   - ModuleManager: watch workspace/modules/<id>/*.java -> compile in memory   |
 |     (javax.tools, off main thread) -> ASM instrumentation (guards) ->         |
 |     fresh ClassLoader per generation -> enable on main thread                 |
 |     -> on failure keep / restore previous generation (rollback)               |
 |   - Guard: exceptions caught, main-thread time budgets, infinite-loop breaker,|
 |     kill switch for unloaded code, forbidden calls rejected, circuit breaker  |
 |   - ModuleContext: tracked listeners / commands / tasks / recipes / entities  |
 |     -> everything undone on reload                                             |
 |   - ClaudeJobService: headless `claude -p` per module (stream-json), resumed  |
 |     conversation per module, N jobs in parallel                               |
 |   - ApiServer (127.0.0.1:8765): JSON API for AI agents/tests + dashboard      |
 |   - /vibe, /idea commands, Notifier (chat announcements)                      |
 +--------------------------------------------------------------------------------+
        ^ files written by AI agents (Claude Code sessions: desktop app or headless)
 workspace/
   CLAUDE.md                <- instructions + API reference for AI agents
   modules/<id>/*.java      <- one directory per feature / per AI chat
   modules/<id>/config.yml  <- optional, live-editable
   modules/<id>/module.yml  <- optional: description
   modules/<id>/.vibe/      <- written by the host: status.json, build.log, errors.log, disabled (marker)
   data/<id>/               <- persistent data (ModuleStore), never triggers reloads
   examples/<id>/           <- example modules (not loaded)
```

Optional safety net (proxy/): Velocity proxy + tiny "limbo" backend. If the main server ever dies
or must restart (JVM crash, plugin upgrade), players are moved to limbo and brought back
automatically when it is up again — the client never sees a disconnect screen.

## 2. Repository layout & ownership

| Path | Content | Owner (build phase) |
|---|---|---|
| `runtime/jdk-25/` | portable Temurin JDK 25 (needed: javac in-process) | - |
| `server/` | Paper 26.3 (`paper.jar`, `plugins/`, world). RCON 25575 enabled, online-mode=false for tests | - |
| `vibecraft-plugin/src/main/java/dev/vibecraft/api/**` | **frozen** public module API | contract |
| `vibecraft-plugin/.../core/{ModuleManager,ModuleInfo,ModuleState,ReloadResult,Diagnostic,ModuleEvent,ModuleEventListener}.java` | **frozen** contracts | contract |
| `vibecraft-plugin/.../ai/{AiJob,AiJobState,AiJobListener,AiJobService}.java`, `ideas/{Idea,IdeaQueue}.java`, `VibeCraftPlugin.java` | **frozen** contracts / wiring | contract |
| `vibecraft-plugin/.../core/**` (rest) | engine | ENGINE |
| `vibecraft-plugin/.../web/**`, `command/**`, `ideas/IdeaQueueImpl.java`, `notify/**`, `resources/web/**` | HTTP API, commands, notifier, dashboard | INTERFACE |
| `vibecraft-plugin/.../ai/**` (rest), `resources/ai/**` | Claude job runner | AI |
| `workspace/CLAUDE.md`, `workspace/examples/**` | AI-facing docs + examples | DOCS |
| `tests/**` (except `tests/tools/rcon.mjs`) | e2e harness (mineflayer bot + scenarios) | TESTS |
| `proxy/**` | Velocity safety net | PROXY |

Frozen contract files must not be changed. If a contract is genuinely wrong, work around it and
report the problem in your final answer.

Build: `vibecraft-plugin/build.sh [--deploy]` (javac against `server/libraries` + `server/versions`;
`OUT_DIR=out-foo ./build.sh` builds into another dir; works from a git worktree too).
Server: `cd server && ../runtime/jdk-25/bin/java -Xms1G -Xmx3G -jar paper.jar --nogui` (~16 s to start).
RCON: `node tests/tools/rcon.mjs [--dir server] "command" ...` (reads port/password from server.properties).
Graceful stop: `node tests/tools/rcon.mjs stop`.

Visible to plugins at runtime (verified): ASM 9.9.1 (`org.objectweb.asm.*`, tree, commons), Gson 2.14,
SnakeYAML, `javax.tools.ToolProvider.getSystemJavaCompiler()` (JDK), `com.sun.net.httpserver`,
Adventure 5.2 + MiniMessage. Paper API 26.3 (`api-version: "26.3"` accepted). Runtime is Mojang-mapped.

## 3. Module format

* Directory `workspace/modules/<id>/`, id matches `[a-z0-9][a-z0-9_-]{0,39}`.
* Any number of `.java` files (recursively; anything under `.vibe/` is ignored). Recommended package:
  `vibe.<id with '-' replaced by '_'>`; the default package also works.
* Exactly one concrete public class implementing `dev.vibecraft.api.VibeModule` with a public no-arg constructor.
* Optional `config.yml` (exposed as `ctx.config()`), optional `module.yml` (`description: ...`).
* Compiled with `--release 25` against: every jar of `server/libraries/**`, `server/versions/**`,
  `server/plugins/*.jar` (includes VibeCraft API). Records, switch patterns, text blocks... all OK.
* Each generation gets a fresh class loader (parent = VibeCraft's plugin class loader). Static state is
  lost on reload — use `ctx.store()` (persistent) or `ctx.shared()` (in-memory, global) instead.

## 4. Engine requirements (ENGINE)

### 4.1 Discovery, watching, reloading
* Poll `modules-dir` every `poll-interval-ms`; fingerprint per module = (relative path, size, content hash) of
  `*.java`, `config.yml`, `module.yml` (ignore `.vibe/`; hashes cached by size+mtime, so rewriting a file with
  the same content is not a change). When a fingerprint changes and is stable for `debounce-ms`, reload (if
  `auto-reload`). New directory → load. Deleted directory → unload (event REMOVED). Files the module itself
  wrote during `enable()` (e.g. `config.yml` with copyDefaults) count as compiled when the `.java` files did not
  change, so a module saving its own config does not reload itself in a loop.
* `reload(id)`: compile **off the main thread** (single shared compiler executor with 2 threads; per
  module at most one compile at a time; requests arriving during a compile are coalesced into exactly
  one follow-up compile). Then hop to the main thread: unload current generation, load the new one.
  Duplicate requests: a request for the same files as the compile in flight shares its result, and a request
  for the files the watcher loaded less than 20 s ago returns that generation (no 2nd swap: the AI's curl
  right after its save). `POST .../reload?force=1` always recompiles.
* Limits per compile: 1 MB of sources, 200 files, 1000 classes, 60 s (javac aborted through a TaskListener).
* Unload order: call `module.disable(ctx)` (guarded, enable-budget), close menus of this generation that
  players still have open (holder class of its loader, or inventory it created), then run all tracked
  cleanups in reverse order (listeners, commands, tasks, recipes, entities, custom track()), save store
  (io thread on reload), set the generation's kill flag, drop the class loader, then sweep leftovers that
  bypassed ctx: JUL handlers / Log4j appenders whose class is of that loader, recipes `vibecraft:<id>/...`.
  Unloading never throws (engine logging is guarded too: a broken log handler cannot break the engine).
* Load: define classes, find the VibeModule class (exactly one), instantiate, call `enable(ctx)`
  guarded with `enable-budget-ms`. If anything fails (exception, timeout, policy violation, zero/
  several module classes), clean up whatever this attempt registered, then **restore the previous
  good generation** by re-instantiating it from its kept bytecode with a new class loader and a new
  context (rolledBack=true, event ROLLED_BACK). With no previous generation → FAILED.
* Startup: load every module except those with `.vibe/disabled`. One failing module never prevents
  the others from loading. `shutdown()` unloads everything on the main thread (no new compiles).
* Write `.vibe/status.json` (ModuleInfo as JSON), `.vibe/build.log` (human-readable diagnostics of
  the last compile, `File.java:LINE:COL: error: message` + source line + caret) and append runtime
  errors to `.vibe/errors.log` (rotate at ~200 KB). AI agents read these files.
* `create(id, description)`: scaffold `modules/<id>/module.yml` + `<Pascal>Module.java`
  (package `vibe.<id_>`) whose enable() logs "enabled".

### 4.2 Compilation
* `javax.tools` in memory (no .class files on disk), `-g -parameters --release 25 -encoding UTF-8
  -proc:none -Xlint:none`. Classpath computed once at startup (see §3) from the server directory
  (`new File(".")` is the server dir) + the VibeCraft jar itself.
* Diagnostics → `Diagnostic` records (file path relative to module dir, 1-based line/col, message,
  source line). Compile errors → ReloadResult(ok=false, error="compilation failed: N error(s)").

### 4.3 Guard & instrumentation (ASM, at define time)
Every module class is rewritten before definition:
1. **Kill switch**: at each loop back-edge `Guard.check(int genId)`, at each method entry
   `if (Guard.enter(genId)) return <default>;` (constructors / static initializers: `check`). For an unloaded
   generation: back-edges throw `ModuleKilledError` (extends `Error`): stops leftover threads and loops. Method
   entry throws it when the caller is module code (stale lambdas kept in shared(), zombie threads) and
   returns a default value (no-op) when the caller is the server or host code (stale click callbacks, log
   handlers, renderers, goals...): nothing is ever thrown into server code for unloaded code.
2. **Time budget** (main thread only): `Guard.check` also throws `ModuleTimeoutError` (extends `Error`)
   if the current guarded call exceeded its budget (`handler-budget-ms` for listeners/commands/
   tasks, `enable-budget-ms` for enable/disable). Guarded calls nest (only the outermost sets the
   deadline). Module code reached outside a guarded call (raw Bukkit APIs) uses the fallback deadline
   `tickStart + tick-budget-ms` (tick start from Paper's `ServerTickStartEvent`); there (no guarded call on the
   stack) the abort is a `ModuleAbortException` (a `RuntimeException`, which vanilla / Paper call sites
   contain, an `Error` would crash the server). The check must be
   cheap (volatile reads; read `System.nanoTime()` only every ~1024 checks or so). If the module
   catches the Error, the next check throws again.
3. **Redirects**: `Thread.sleep(..)` on the main thread → throws (`ModuleTimeoutError` with clear message);
   off-main → real sleep (but still killable: sleep in slices and check the kill flag).
   `PluginManager.registerEvents(listener, plugin)` → routed to the module's guarded `listen()`.
   Calls returning `BukkitTask` / `int` task id on `BukkitScheduler` and `BukkitRunnable.runTask*`
   → the task is tracked by the generation (cancelled on unload). `cancelTask(int)` only cancels own tasks,
   `HandlerList#unregister(Listener)` / `HandlerList.unregisterAll(Listener)` only own listeners (a listener object taken from
   `getRegisteredListeners()` stays registered).
   Also redirected (see `Redirects`): blocking network / DNS / Mojang profile lookups refused on the main
   thread, `Lock.lock`, `Condition.await`, `LockSupport.park`, timed waits bounded by the budget there;
   `Entity.setVelocity` / fireball vectors scaled to ≤ 10 blocks/tick (projectiles from `launchProjectile` /
   `World.spawnArrow` too, right after the call); item meta / lore / data components
   > 64 KB and names > 32 KB refused (oversized items disconnect players at every join); `Bukkit.addRecipe`
   = `ctx.recipe`; tracked and undone on unload: `ClickEvent.callback` (wrapped: guarded, dropped on unload),
   `AsyncChatEvent.renderer` (guarded, default renderer on error), Adventure `BossBar.addViewer`,
   `ServicesManager.register`, incoming plugin channels (listener also run under the guard), `MapView.addRenderer`, conversations,
   `MobGoals.addGoal`, permission attachments, `Logger.addHandler`, inventories from `createInventory`,
   `new Timer / ThreadPoolExecutor / ScheduledThreadPoolExecutor / ForkJoinPool` (constructor hook).
4. **Policy** (reject at load with a Diagnostic, kind ERROR, file/line when available):
   `System.exit`, `Runtime.exit/halt/exec`, `ProcessBuilder` (any use), `Thread.stop`,
   `Bukkit/Server.shutdown/restart/reload/reloadData`, `PluginManager.disablePlugin/clearPlugins/
   enablePlugin`, static `HandlerList.unregisterAll()` and `HandlerList.unregisterAll(Plugin)`,
   `BukkitScheduler.cancelTasks(Plugin)`, `sun.misc.Unsafe`/`jdk.internal.*`, `System.setSecurityManager`;
   since the red-team round also `PluginLoader.disablePlugin/enablePlugin`, `Plugin.onEnable/onDisable/onLoad/
   setEnabled`, `JavaPlugin.getCommand`, `HandlerList.getHandlerLists/register/unregister(Plugin|RegisteredListener)`,
   `BukkitScheduler.getPendingTasks/getActiveWorkers`, `Player.kick/kickPlayer/transfer/ban/banIp`, `setOp`,
   `BanList.addBan/pardon`, whitelist / IP-ban changes, socket types (`java.net.Socket`, `ServerSocket`,
   `DatagramSocket`, NIO socket channels, `com.sun.net.httpserver`), server connection internals (`net.minecraft.server.network.*`,
   `net.minecraft.network.Connection`, `PlayerList`: a module could disconnect players through them), reading `System.in`, and module classes
   extending `ChunkGenerator` / `BiomeProvider` / `BlockPopulator` (the chunk system crashes the server on any
   error). Command lines dispatched by modules (and by the HTTP API, minus `/vibe`) are checked by
   `Policy.forbiddenCommandLine`: stop, restart, reload, op, deop, kick, ban*, pardon*, whitelist, transfer,
   save-off, tick, debug... also inside `execute ... run`.
5. Uncaught `StackOverflowError` / exceptions / errors from guarded calls are caught, logged with the
   module id and the module's own stack frames, recorded (ModuleInfo.recentErrors, errors.log) and
   counted. `error-burst-limit` errors in `error-burst-window-ms` → module unloaded, state TRIPPED,
   event TRIPPED. Repeated timeouts count as errors too.
6. Never let a module exception propagate into Bukkit (no "Could not pass event" spam).

### 4.4 ModuleContext implementation
Implements `dev.vibecraft.api.ModuleContext` exactly (see javadoc). Notes:
* `on(type, ...)`: `PluginManager.registerEvent(type, internalListener, priority, guardedExecutor,
  hostPlugin, ignoreCancelled)`; the executor checks `type.isInstance(event)` before calling.
  Abstract events without handler list → throw IllegalArgumentException with a clear message
  (inside enable → reported as enable failure).
* `listen(listener)`: reflectively register each `@EventHandler` method (honour priority and
  ignoreCancelled) through guarded executors (MethodHandles).
* `command(...)`: a `org.bukkit.command.Command` subclass registered in
  `Bukkit.getCommandMap().register("vibe", cmd)`; on unload remove it from `getKnownCommands()`
  (all labels/aliases pointing to it) and `cmd.unregister(map)`. A name already used by another module or
  by the server → `IllegalArgumentException` (enable fails, clear message); a taken alias is skipped with a
  WARNING diagnostic in the ReloadResult. After command changes, `Player#updateCommands()` is called (at the END of a tick,
  so Paper's async tree build does not race the swaps of the next tick) once commands were quiet for 3 ticks, and once more 20 ticks later (Paper builds trees asynchronously: a tree
  lost to a concurrent change or delivered out of order is corrected). Exceptions → sender gets a short red
  message, error recorded.
* Tasks: `later/every/async` → BukkitScheduler with host plugin, guarded runnables, tracked.
* `recipe(r)`: `Bukkit.addRecipe(r)`; on unload `Bukkit.removeRecipe(key)`. `entity(e)`: only the UUID is kept
  (no stale object after a chunk reload, dead entities not retained); removed on unload through
  `Bukkit.getEntity(uuid)`; tagged with PDC `vibecraft:module-entity = <id>|<boot id>|<load id>` and deleted
  by the engine when it comes back with a chunk (or after a crash / restart) while its generation is gone.
* `store()`: YAML at `data/<id>/store.yml`; saved on unload and every 60 s if dirty; survives generations.
  Only YAML-safe values (Strings, boxed numbers, booleans, lists, maps, ConfigurationSerializable; Instant,
  UUID, enums, CharSequence, Optional, Atomic* converted; anything else throws). Maps become sections.
  Saves copy the tree under the lock and serialize outside it (io thread); loaded off-thread before enable;
  a file with Java tags (older versions) is loaded leniently instead of starting empty.
* `shared()`: one global `ConcurrentHashMap` for all modules, seen through a per-module view that records the
  keys the module writes: kept across reloads, dropped when the module's folder is deleted. On unload, values that
  are objects of the generation are dropped; JDK containers holding some are kept but named in a WARNING of the module's log.
* `logger()`: java.util.logging Logger named `VibeCraft/<id>`, records also go to the module's ring
  buffer (`ModuleManager.logs`, 500 lines).
* `mm()` / `broadcast()`: MiniMessage; broadcast to all players + console.
* `msPerTick`: accumulate nanos of guarded main-thread calls, fold into an EMA every tick.

### 4.5 Events to listeners
Emit `ModuleEvent`s (RELOADED, FAILED, ROLLED_BACK, DISABLED, TRIPPED, RUNTIME_ERROR (≤1/s/module),
CREATED, REMOVED). Listener exceptions are caught.

## 5. HTTP API (INTERFACE) — `com.sun.net.httpserver`, bound to `api.host:api.port`

All JSON (Gson, records serialize as objects, `serializeNulls`). If `api.token` is set, require header
`X-Vibe-Token`. CORS not needed (same origin). Bodies are UTF-8 plain text unless stated.
Requests with a `Host` other than 127.0.0.1 / localhost / [::1] / `api.host` (DNS rebinding), with an
`Origin` other than this server (cross-site pages) or with `Sec-Fetch-Site: cross-site|same-site` (other sites and other
localhost ports, also for GETs without Origin) get 403; curl (no Origin, no fetch metadata) and the dashboard keep working.
Slow / half-open clients are closed after 3 s (`sun.net.httpserver.maxReqTime`), 32 request threads.
`POST /api/command` refuses admin command lines with 403 (see 4.3 Policy), `POST /api/modules/{id}/reload?force=1`
skips the duplicate-request shortcut, idea accept without `module` picks a fresh id (`slug-2`...) instead of an
existing module.
Bukkit calls happen on the main thread (`Bukkit.getScheduler().callSyncMethod` or futures), never on HTTP threads.

| Method & path | Body | Response |
|---|---|---|
| `GET /` | | dashboard `web/index.html` (from plugin resources) |
| `GET /api/status` | | `{server:{name,version,online,max,tps:[1m,5m,15m],uptimeMs}, modules:[ModuleInfo], jobs:[AiJob], ideas:[Idea], ai:{available,reason}}` |
| `GET /api/modules` | | `[ModuleInfo]` |
| `GET /api/modules/{id}` | | `ModuleInfo` or 404 `{error}` |
| `POST /api/modules/{id}/reload` | | `ReloadResult` (waits up to 120 s) |
| `POST /api/modules/{id}/enable` | | `ReloadResult` |
| `POST /api/modules/{id}/disable` | | `{ok}` |
| `POST /api/modules/{id}/create` | description (optional) | `{ok, created}` (does not wait for load) |
| `GET /api/modules/{id}/logs?limit=200` | | `{lines:[..]}` |
| `POST /api/command` | command line (leading `/` optional) | `{ok, output:[lines]}` run as console via `Server#createCommandSender(feedback)` on main thread, waits ≤10 s, plain-text output |
| `GET /api/players` | | `[{name,uuid,world,x,y,z,gameMode,health,op}]` |
| `GET /api/ai` | | `[AiJob]` |
| `POST /api/ai/{id}` | prompt | `AiJob` (201) / 409 `{error}` if busy / 503 if unavailable |
| `POST /api/ai/{id}/stop` | | `{ok}` |
| `POST /api/ai/{id}/reset` | | `{ok}` (forget conversation) |
| `GET /api/ideas` | | `[Idea]` |
| `POST /api/ideas` | text | `Idea` |
| `POST /api/ideas/{n}/accept?module=<id>` | | `AiJob` (starts AI job with the idea text) |
| `POST /api/ideas/{n}/dismiss` | | `Idea` |

Errors: `{error:"..."}` with 4xx/5xx. Unknown module id → 404.

## 6. Commands (INTERFACE)

`/vibe` (alias `/vc`, perm `vibecraft.admin`):
`help`, `list`, `info <id>`, `reload <id|all>`, `enable <id>`, `disable <id>`, `errors <id>`,
`new <id> [description]`, `ai <id> <request...>` (creates module if needed), `stop <id>`,
`jobs`, `reset <id>` (forget AI conversation), `ideas`, `accept <n> [id]` (idea → AI job; id defaults
to a slug of the idea), `dismiss <n>`, `dashboard` (prints URL). Tab completion everywhere.
Messages in **French**, colourful (MiniMessage), concise.

`/idea <text>` (perm `vibecraft.idea`, everyone): adds to the queue (cooldown), thanks the player,
notifies ops with a clickable `[Accepter]`.

Notifier: on RELOADED → everyone (if `notify.broadcast-reloads`): `✔ <id> v<gen> chargé à chaud`;
FAILED/ROLLED_BACK/TRIPPED/RUNTIME_ERROR → players with `vibecraft.notify` (+ console) with a short
reason; AI progress lines → everyone or ops (`ai.broadcast-progress`), prefixed `[🤖 <id>]`, throttled
(≤ 1 line / 1.5 s / job, drop the excess but always deliver the final line). Also an action bar while
a job runs. All Bukkit calls on the main thread.

## 7. AI jobs (AI)

`ClaudeJobService` runs Claude Code headless:
* Binary: `ai.claude-path` or auto-detect (PATH `claude`/`claude.exe`, then newest
  `%APPDATA%/Claude/claude-code/<version>/<hash>/claude.exe`).
* One job per module at a time, `max-concurrent-jobs` in parallel (others QUEUED), timeout.
* Working directory: the workspace root (`modules-dir/..`, contains CLAUDE.md). Prompt passed on
  **stdin** (avoid Windows quoting issues), flags: `-p --output-format stream-json --verbose
  --permission-mode <cfg> --allowedTools <cfg...> [--model <cfg>] [--resume <session>]`.
  The prompt = template (resource `ai/job-prompt.md`): you work ONLY in `modules/<id>/`, read
  CLAUDE.md, implement the request, call `curl -s -X POST http://127.0.0.1:<port>/api/modules/<id>/reload`
  until ok, keep it simple & fast, final answer = 1–2 sentences in French for the audience.
* Parse stream-json lines (Gson): system/init (session id), assistant text → `💬 ...` (first ~90
  chars), tool_use Write/Edit → `✏ <file>`, Read → `📖 <file>`, Bash curl reload → `🔄 rechargement`,
  tool results of reload with `"ok":false` → `⚠ erreur de compilation`, result → final text + cost.
* Persist module → session id in `plugins/VibeCraft/ai-sessions.yml` (resume next time if
  `ai.resume-sessions`); if resume fails (session not found), retry once without it.
* `stop` kills the process tree (`ProcessHandle.descendants()`), state STOPPED.
* Env: strip nothing special; set `CLAUDE_CODE_ENTRYPOINT`? not needed. Never block the main thread.

## 8. Dashboard (INTERFACE) — single self-contained `web/index.html`

Big-screen friendly (projector), dark, French. Polls `/api/status` every second. Shows: server
(TPS, players), module cards (state colour, v<generation>, last reload "il y a 5 s", ms/tick, errors,
commands, last error / diagnostics expandable, buttons reload/disable/enable), AI jobs with live
progress feed, the idea queue with accept/dismiss buttons, a form "Nouvelle demande à l'IA"
(module id + request). Token prompt if API returns 401. No external resources (works offline).

## 9. Tests (TESTS)

`tests/e2e/`: Node project with `mineflayer`. Bot joins `localhost:25565` as `VibeBot` (server runs
ViaVersion+ViaBackwards because mineflayer speaks 26.1), stays connected for the whole run and
records any `kicked`/`end`. Scenario runner drives the server through the HTTP API + files and
asserts via the bot's chat/command results. Must cover: hot reload of command & listener without
duplicates, compile error keeps old version, runtime exception isolated, infinite loop in command
& in enable (rollback), forbidden call rejected, raw registerEvents/scheduler cleaned up on reload,
async infinite loop killed on unload, deep recursion, Thread.sleep on main thread, store persistence,
parallel reloads of several modules, 30 rapid reloads, circuit breaker, TPS stays > 15, bot never
disconnected. Prints a summary table, writes `tests/e2e/reports/<timestamp>.json`, exit code 0/1.

## 10. Proxy safety net (PROXY)

`proxy/`: Velocity (version supporting 26.1–26.3 clients), `limbo` backend (minimal Paper, void
world, low RAM) and a small Velocity plugin `VibeReconnect`: when the main backend disconnects
unexpectedly / restarts, players go to limbo with a title "Le serveur redémarre…", and are sent back
automatically when the main backend answers pings again. Scripts to start everything. Modern
forwarding. Tested with a mineflayer bot through the proxy while the backend is killed & restarted.

## 11. Before the talk (security checklist)

The red-team round found that the defaults are fine for development but not for a hall full of strangers on
the same Wi-Fi:

* **Run behind the proxy in online mode** (`proxy/enable-proxy-mode.ps1 -OnlineMode` + `start-proxy.ps1
  -OnlineMode`): Mojang authentication on Velocity, and the main server binds `server-ip=127.0.0.1`, which
  also keeps its RCON port off the network. Without the proxy, `online-mode=false` lets anybody join under any
  name, including an op's name.
* **ops.json**: only the presenter's account(s); no test bot. `white-list` stays off (the audience must join).
* **RCON**: reachable from the LAN unless `server-ip=127.0.0.1` (proxy mode) or a firewall rule blocks the port;
  otherwise set `enable-rcon=false` for the talk (the e2e tools need it, a live demo does not).
  `broadcast-rcon-to-ops=false`, long random password. VibeCraft logs a WARNING at startup when RCON is reachable from the
  network, its password is short or `broadcast-rcon-to-ops=true`.
* **API**: bound to 127.0.0.1, Host/Origin checked; set `api.token` if anything untrusted runs on the laptop.
* World: `difficulty=easy` + `gamerule spawn_monsters false` (modules may spawn monsters, nature does not).

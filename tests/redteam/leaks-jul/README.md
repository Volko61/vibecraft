# leaks-jul: a java.util.logging Handler left by an old generation CRASHES THE SERVER (critical)

Module: "relay server warnings to ops" = `Logger.getLogger("").addHandler(new OpsRelay(gen))` (the first
version of the repro used `Bukkit.getLogger().addHandler(...)`), not removed in disable().

Steps: load (v1), reload once (`POST /api/modules/leaks-jul/reload`). (In the recorded run v1-v2 were the
`Bukkit.getLogger()` variant and v3 the first root-logger generation: the crash came at the v3 -> v4 reload.)

Expected: the old handlers are removed (or harmless) and the reload succeeds.

Observed (`crash-excerpt.txt`): v4's enable() logs through `ctx.logger()` -> VibeCraft's plugin logger -> the
root JUL handlers -> the stale v3 handler throws `ModuleKilledError` *inside* v4's enable -> "enable() was
interrupted"; the rollback to v3 fails the same way -> "nothing running"; the engine then logs the failure ->
throws again inside `runOnMain`'s catch -> "Task #46 for VibeCraft generated an exception" -> "Exception in
server tick loop" -> crash. The shutdown fails too ("Exception stopping the server", stale v1 handler on
`Bukkit.getLogger()`) and the JVM stays alive, hung, holding its ports (had to be killed).

Suggested fix (engine): redirect `java.util.logging.Logger.addHandler` (and Log4j `addAppender`, see
leaks-log4j) to track a `removeHandler` on unload; on unload also scan the root logger, `Bukkit.getLogger()`
and the plugin logger for handlers whose class was loaded by the generation's loader. Make engine logging
(ModuleLogger.engine / console.log and the catch blocks of runOnMain / swap / unloadGen) unable to throw
(catch Throwable). The generic "entry of killed code = no-op" fix (leaks-callback) also prevents it.
CLAUDE.md: "never add log handlers / appenders".

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

4 reloads in a row: all ok, "handlers on root logger: 2" each time (Logger.addHandler is tracked and undone on unload; handlers of a dead loader are also swept from the root / Bukkit / plugin loggers; engine logging can no longer throw). A stale handler that is still registered does nothing (../fixer-killed-entry).

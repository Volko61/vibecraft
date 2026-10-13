# crash-taskkill — cancel every module's scheduled tasks (HIGH)

`Bukkit.getScheduler().getPendingTasks()` + `task.cancel()` cancels all modules' tasks.
Policy blocks `cancelTasks(Plugin)` but not `getPendingTasks()`.

## Repro
Victim with `ctx.every(20, ...)`; reload this; victim's counter stops advancing.

## Fix
Forbid/redirect `getPendingTasks()` for module code (or filter to the caller's own tasks).

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

Rejected at load: `BukkitScheduler.getPendingTasks()` (and `getActiveWorkers()`) are forbidden; `cancelTask(int)` only cancels the module's own tasks.

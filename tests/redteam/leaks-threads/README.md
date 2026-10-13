# leaks-threads: java.util.Timer and hand-made ThreadPoolExecutor threads leak (medium)

Module starts, in enable(): a `java.util.Timer` with a task in 10 min, a `new ThreadPoolExecutor(...)` with a
thread factory (prestarted), `Executors.newSingleThreadScheduledExecutor(factory)`, a raw `new Thread(...)`
that swallows interrupts, a virtual thread. Nothing is cleaned in disable().

Steps: load + reload 4 times (5 generations), `jcmd <pid> Thread.print | grep leaks`, VM.classloader_stats.

Expected: only v5's threads.
Observed: `leaks-timer-v1..v5` and `leaks-tpe-v1..v5` all alive (waiting forever), while `leaks-ses-v5`,
`leaks-raw-v5` and `leaks-vt-v5` are the only ones of their kind (Executors.* is tracked, Thread.start is
redirected and the kill switch ends the loops). The 4 old generations' class loaders stay pinned
(ModuleClassLoader CLDs 10 for 6 live modules). One Timer thread + one pool thread per reload.

Why: `Timer`'s constructor and `ThreadPoolExecutor.addWorker` call `Thread.start()` inside JDK code, which the
instrumenter does not see.

Suggested fix: POST-redirect `new java.util.Timer(...)` (track `cancel()`), and `new ThreadPoolExecutor` /
`new ScheduledThreadPoolExecutor` / `new ForkJoinPool` (track `shutdownNow()`); or at unload enumerate live
threads whose `contextClassLoader` / thread-factory class / Runnable class belongs to the generation's loader
and interrupt them. CLAUDE.md already says "no raw Thread, Timer, executors".

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

After 4 reloads only the v5 threads exist (`new Timer` / `ThreadPoolExecutor` / `ScheduledThreadPoolExecutor` / `ForkJoinPool` are tracked through a constructor hook and cancelled / shut down on unload).

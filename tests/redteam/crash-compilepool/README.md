# crash-compilepool — shared 2-thread compile pool with no per-compile timeout (MEDIUM)

## Design risk
`ModuleManagerImpl` uses `Executors.newFixedThreadPool(2)` for ALL module compiles, and
`ModuleCompiler.compile` has no timeout. Several AI sessions reload in parallel. Two modules whose
compile is slow or huge (a large generated source, pathological generics, or heavy annotation of
many classes) pin both compile threads, so EVERY other presenter's reload queues behind them. The
reload HTTP endpoint waits up to 120 s and then the demo stalls for everyone.

## Evidence / notes
- There is no per-compile wall-clock cap; `javac` + ASM instrumentation of a very large unit also
  consumes significant heap. A 60k-method source submitted while the heap was already pressured (by a
  separate retained-allocation module, crash-sharedleak/crash-oom) pushed the JVM to
  `OutOfMemoryError: Java heap space` and the server crashed while sending a packet. (The OOM here was
  dominated by the leaked shared() arrays; the compile-pool point is the starvation + no timeout.)
- Normal-sized pathological inference (nested generic `id()` depth 180) compiled in <1 s on JDK 25,
  so this is primarily a resource-exhaustion / fairness risk, not a quick single-shot crash.

## Suggested fix
Add a per-compile timeout (cancel the javac task + reset the file manager, like the existing
RuntimeException path) and a source-size / class-count limit; consider a small per-session fair-share
so one module cannot starve the pool. Cap instrumentation memory.

## Repro sketch
`BigCompileModule.java` generator (used in testing): a class with ~60000 trivial static methods.
Reload it (and a second copy) to occupy both compile threads; other modules' reloads block.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

Per-compile limits: 1 MB of sources / 200 files (checked before javac runs), 1000 classes, 60 s (javac aborted at its next progress event). See ../fixer-compile-limits (60000-method module rejected in ~20 ms).

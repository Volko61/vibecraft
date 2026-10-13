# fixer-compile-limits (regression for crash-compilepool)

`node gen.mjs <modulesDir> [methods]` writes a module `bigcompile` with one class of N trivial methods
(60000 methods = ~3 MB of source), like the crash-compilepool sketch.

Expected (after the fix): the reload answers at once with `ok:false` and
`module sources too large (1 files, 2966 KB; max 200 files, 976 KB): keep modules small`; javac never runs,
so the 2 shared compile threads stay free for the other modules. Other limits: at most 1000 classes per
module, and a compile running longer than 60 s is aborted at javac's next progress event (TaskListener)
with "compilation took longer than 60 s".

Observed on the fixer sandbox (2026-10-13): `ok:false` with that diagnostic, durationMs 21 (javac never started).

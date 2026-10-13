# ai-coder-double-reload: following CLAUDE.md step 5 reloads the module twice per save

CLAUDE.md step 5 tells AI coders: save, then `POST /api/modules/<id>/reload` ("saving also auto-reloads,
but this call returns the result"). An LLM's next tool call usually comes 2-5 s after the Write, i.e. after
the watcher (poll 750 ms + debounce 1200 ms) has already compiled and swapped the new code. The explicit
reload then compiles the unchanged sources again and swaps a second generation.

`repro.sh <modulesDir> [apiBase]` copies the trivial module `dbl-reload/` and, for several delays D,
appends a comment line (one save), waits D, calls the reload endpoint, and counts generations.

Observed (sandbox, 2026-10-08):
```
delay=0s   -> 1 generation(s) for ONE save
delay=1.0s -> 1 generation(s) for ONE save
delay=1.5s -> 1 generation(s) for ONE save   (2 in an earlier run with the meteo module)
delay=2.0s -> 2 generation(s) for ONE save
delay=2.5s -> 2 generation(s) for ONE save
delay=3.5s -> 2 generation(s) for ONE save
```
Each generation is announced in game chat ("✔ <id> vN chargé à chaud"), so the audience sees two lines
per change, and the module is unloaded/enabled twice: tracked entities removed and respawned, running
countdowns / boss bars / open menus closed, `enable()` side effects (e.g. broadcasts) run twice.

Expected: an explicit reload whose sources are unchanged since the generation currently loaded (same
fingerprint) returns that generation's result (or waits for the in-flight watcher compile) instead of
compiling and swapping again; a force flag could keep the "reload anyway" behaviour.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

`repro.sh`: 1 generation per save for every delay (0 to 3.5 s). An explicit reload of the files the watcher loaded less than 20 s ago returns that result; a request for the files of the compile in flight shares it; `?force=1` always reloads.

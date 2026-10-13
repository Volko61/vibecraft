# crash-sharedleak — JDK-typed shared() values never reclaimed -> host memory leak / OOM (HIGH)

`ModuleManagerImpl.removeSharedFrom(loader)` only removes shared() entries whose VALUE's classloader
is the module loader. JDK/Bukkit types (byte[], ArrayList, String, ItemStack...) survive module
unload forever.

## Repro
1. Deploy leakstore (writes an 8 MB byte[] to shared("leak.bytes")) and leakread. Reload both.
2. Delete `modules/leakstore/` (unloads it; API returns 404).
3. `/leakread` -> `leak.bytes present=true size=8388608`.

A module that caches growing data in shared() leaks unbounded memory until restart; combined with an
allocation loop this OOM-crashed the server (see crash-oom note in the report).

## Fix
Track the keys each generation writes to shared() and clear them on unload regardless of value type,
or cap shared() total size and document that it is never GC'd.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

`/leakread` after deleting `modules/leakstore/` -> `leak.bytes present=false`: ctx.shared() is a per-module view that records the keys a module writes; they are dropped when the module folder is deleted (kept across reloads, disable and trip).

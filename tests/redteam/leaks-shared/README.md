# leaks-shared: shared() keeps JDK containers that hold module objects (low)

Module: `ctx.shared().put("lk.shared.list", new ArrayList<>(List.of(new Marker(gen))))` (Marker = module record)
and `ctx.shared().put("lk.shared.record", new Marker(gen))`.

Steps: load, reload. v2 reads the list left by v1 and casts its element to Marker.
Observed: `prev=java.util.ArrayList element=...LeaksSharedModule$Marker@vibe:leaks-shared#95 ClassCastException`
(the same class name from another loader); the old generation stays pinned. The bare record value is removed
by the engine (`removeSharedFrom` only checks the value's own class), and the module then silently finds
nothing.

CLAUDE.md already says "JDK/Bukkit types only, no lambdas or module objects": docs-covered. Possible engine
hardening: on unload, also drop (or deep-scan up to a small depth) Collection / Map / array values containing
objects of the dead loader, and log a warning naming the key.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

Kept as designed (a container may also hold the module's still-valid data, and the e2e `context-cleanup` scenario relies on
an old lambda found in a shared map throwing `ModuleKilledError`), but no longer silent: on unload the engine scans shared()
values (JDK containers / arrays, 3 levels, bounded) and logs in the module's logs
`shared() key 'lk.shared.list' holds objects of the unloaded version (module classes inside a list / map): the next version cannot cast them (ClassCastException). Keep only JDK / Bukkit types in ctx.shared()`.
A bare module object as value is still dropped. (Dropping whole containers was tried and rejected: it lost the module's other data.)

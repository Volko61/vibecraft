# leaks-cfgloop: a module that saves its config.yml reloads itself forever (high, demo-visible)

Module: the classic "save default config" pattern in enable():
`ctx.config().addDefault(...); ctx.config().options().copyDefaults(true); ctx.config().save(new File(ctx.sourceFolder(), "config.yml"));`

Steps: copy the module, `POST /api/modules/leaks-cfgloop/reload`, wait 20 s.

Expected: one load (the file content is identical after the first save).
Observed: generation 3 -> 5 -> 8 -> 10 in 15 s, forever: every save changes the mtime, the watcher sees a new
fingerprint, debounces 1.2 s and reloads. Every reload is broadcast to all players
("✔ leaks-cfgloop vN chargé à chaud" every ~2 s) and resets the module (tasks, entities, boss bars...).
Stopped only by deleting the module directory.

Suggested fix (engine): fingerprint config.yml / module.yml / *.java by content hash (or size + hash) instead
of mtime, and after a successful swap set `compiledFp` to the fingerprint observed right after enable() so
self-writes do not trigger a reload. CLAUDE.md: "never write config.yml (or any file in modules/<id>/) from
code; keep defaults in code (`getInt("x", 3)`); files go in ctx.dataFolder()".

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

Stays at generation 1 ("files written by the module itself during enable() ignored by the watcher"): fingerprints use content hashes and self-writes during enable() count as compiled.

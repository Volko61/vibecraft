# leaks-soak: 300 reloads of a realistic module (class loaders, heap, metaspace, threads)

Module (`src/LeaksSoakModule.java`): command `/lksoak` + alias + tab completion, `ctx.on(PlayerMoveEvent)`,
`ctx.on(PlayerJoinEvent)`, `ctx.listen(...)`, raw `PluginManager.registerEvents`, `ctx.every`, `ctx.async` +
`ctx.later(0)`, `new BukkitRunnable().runTaskTimer`, raw `BukkitScheduler.runTaskTimer`, Adventure boss bar
(`ctx.track`), main-scoreboard sidebar objective (`ctx.track`), `ctx.recipe`, tracked `TextDisplay`,
`ctx.store()` counter, `ctx.shared()` string, a record, a static final Map, instance maps.

Run: `node ../leaks-tools/soak.mjs --pid <java pid> --module leaks-soak --count 300 --every 50 --out soak300.json`
with a bot connected (LeakBot) and the probe module loaded.

Expected: no growth. Observed (`soak300-result.json`), all after a full GC:

| after reloads | ModuleClassLoader instances / CLDs | classes in module loaders | heap used | metaspace used | JVM threads |
|---|---|---|---|---|---|
| 0 | 2 / 2 | 36 | 367 MB | 181.7 MB | 91 |
| 50 | 2 / 2 | 34 | 373 MB | 183.0 MB | 97 |
| 100 | 2 / 2 | 34 | 368 MB | 183.6 MB | 97 |
| 200 | 2 / 2 | 36 | 375 MB | 184.1 MB | 93 |
| 300 | 2 / 2 | 36 | 373 MB | 184.6 MB | 92 |

0 reload failures, reload p50 140 ms / p95 282 ms / max 437 ms. Only `dev.vibecraft.core.guard.Gen` grows
(+1 per reload, a few hundred bytes each, by design: `Guard.gens` is never compacted) and metaspace grows
~10 KB per reload. After 300 reloads the bot still saw exactly one boss bar, one sidebar, one hologram, one
recipe, `/lksoak` + `/lksoakalias` once each; 3 tasks and 4 listeners for the module; TPS 19.7.

Verdict: the engine's own cleanup leaks no class loader, thread or heap for everything that goes through
ctx / the redirects. The leaks found are APIs that keep module objects outside ctx (other `leaks-*` dirs).

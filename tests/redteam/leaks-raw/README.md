# leaks-raw: inventory of what survives a reload when a module does things "raw"

`src/LeaksRawModule.java` does, in enable() and with no cleanup, every raw thing an AI coder may do, each
tagged with the generation number (`g`). Load it with a player online, run `/lkprobe` (leaks-probe), reload
1-3 times, run `/lkprobe` again and look at the bot (`botctl status`).

| Thing done in enable() | After reload | Engine handles it? |
|---|---|---|
| `world.spawn(ArmorStand)` without ctx.entity | stays (1 more per reload) | no, by design (documented) |
| ctx.entity(ArmorStand) | removed | yes (but see leaks-entity: not after a chunk unload) |
| `Bukkit.createBossBar(...)` / keyed bar + addPlayer | removed | yes (redirect) |
| Adventure `player.showBossBar(bar)` | hidden | yes (redirect) |
| Adventure `Bukkit.getServer().showBossBar(bar)` | hidden | yes (Server is an Audience) |
| Adventure `bar.addViewer(player)` | **still shown** on the client (v1 bar next to v2's) | **no** -> redirect `BossBar.addViewer` like showBossBar |
| `main.registerNewObjective` / `registerNewTeam` | removed | yes (redirect) |
| objective created with `dispatchCommand("scoreboard objectives add ...")` | stays | no (documented: world state) |
| new scoreboard + `player.setScoreboard(sb)` | player back on main | yes (redirect) |
| potion effect, `setAllowFlight(true)`, `setWalkSpeed` | stay | no, documented in CLAUDE.md |
| world border size, per-player `setWorldBorder`, gamerule, time, storm, block | stay | no; per-player border and world border are not listed in CLAUDE.md's "NOT undone" list |
| `player.addAttachment(ctx.plugin(), perm, true)` | **stays, one more per reload** (`leaks.gen.v2`, `v3`, `v4` all granted) | **no** -> redirect `Permissible.addAttachment(Plugin...)` and remove the attachment on unload |
| `player.setMetadata("leaks", new FixedMetadataValue(plugin, moduleRecord))` | replaced by the next gen (same plugin key); after removal the module object stays on the player until quit; reading it from the next gen gives a foreign class | no -> track `Metadatable.setMetadata(key, value with host plugin)` and `removeMetadata` on unload |
| `ServicesManager.register(Runnable.class, moduleLambda, plugin, Normal)` | **stays, one more per reload**, each pinning its generation's class loader; `getRegistrations()` returns killed providers | **no** -> redirect `register` and `unregister(service, provider)` on unload |
| `Messenger.registerIncomingPluginChannel(plugin, ch, moduleLambda)` | **stays, one more per reload**; every incoming message on the channel logs `Plugin VibeCraft generated an exception whilst handling plugin message ... ModuleKilledError` per dead generation; loaders pinned | **no** -> redirect and `unregisterIncomingPluginChannel(plugin, ch, listener)` on unload |
| `registerOutgoingPluginChannel` | stays (harmless) | - |
| BukkitRunnable timer, Paper global / region / entity / async `runAtFixedRate` | all stopped (old counters frozen) | yes (redirects) |
| `Bukkit.getCommandMap().register("leaks", new Command(...))` | replaced, no duplicate | yes (redirect) |

Observed with `/lkprobe` after 3 reloads: `permsGen: [leaks.gen.v2, leaks.gen.v3, leaks.gen.v4]`,
4 ServicesManager registrations, 4 incoming channel registrations (one per generation), raw armor stands +1
per reload, the `addViewer` boss bar of v1 still on the bot's screen next to v2's.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

After 3 reloads (../fixer-probe): permsGen [v4] only, 1 service, 1 incoming channel registration, every boss bar (incl. `addViewer`) of v4 only, no "plugin message" errors. Raw entities, world border, gamerules, time, weather, blocks, walk speed and metadata are still not undone (documented in CLAUDE.md). WARNING: running this module changes world state; on the main server it was restored afterwards.
Incoming plugin channel listeners now also run under the guard (handler budget, errors recorded for the module): a listener with
an infinite loop is aborted after 250 ms (`ModuleTimeoutError` in `recentErrors`) instead of freezing the tick for the 2 s fallback.

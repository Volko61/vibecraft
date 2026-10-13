# REDTEAM-LEAKS tooling

Lens: correctness of hot reload over a long session (what survives a reload that should not, what is lost
that should not). Every `leaks-*` sibling directory holds one repro module (`src/`, copy its files into
`workspace/modules/<dir name>/`) and a README with expected vs observed.

Sandbox used: `.sandbox-leaks/` (MC 25610, RCON 25611, API 8810), Paper 26.3-159, VibeCraft built from
commit e3c1132 (`OUT_DIR=out-leaks ./vibecraft-plugin/build.sh`), JVM `-Xms512M -Xmx2G`.

| File | What |
|---|---|
| `bot-daemon.mjs` | one mineflayer bot (reuses `tests/e2e/lib/bot.mjs`, tick_end shim) that stays connected; driven through files (no extra TCP port): `node bot-daemon.mjs --port 25610 --dir <ipcDir>` |
| `botctl.mjs` | `node botctl.mjs --dir <ipcDir> '{"op":"status"}'` (ops: status, cmd, chat, messages, rawchat, commandTree, tab, click, closeWindow, payload, customClick, write, sneak, quit) |
| `soak.mjs` | reload a module N times through the API and sample `jcmd` (GC.class_histogram = full GC, VM.classloader_stats, GC.heap_info, VM.metaspace, Thread.print) every K reloads |
| `lk.sh` | bash helpers (`reload`, `modinfo`, `mc` = /api/command, `probe`, `bot`, `logs`) |

`customClick` clicks a Paper `ClickEvent.callback` (26.x sends `custom_click_action` with id
`paper:click_callback` and a length-prefixed NBT payload `{id: <UUID as int array>}`; minecraft-data 26.1 encodes
the payload wrongly, so the daemon writes the packet by hand).

The probe module (`../leaks-probe`, command `/lkprobe [player]`) prints one JSON line with everything that
can leak: tagged entities, keyed boss bars, main-scoreboard objectives/teams, the player's scoreboard,
effects, flight, walk speed, permissions granted by host-plugin attachments, metadata, open inventory
holder (+ its class loader), world/player border, gamerule, time, weather, a block, ServicesManager and
Messenger registrations of the host plugin, recipes, commands, pending tasks, host listeners, threads, shared().

Soak result (300 reloads of `../leaks-soak`, a realistic module: command+alias+tab, ctx.on, ctx.listen,
raw registerEvents, every/async/later, BukkitRunnable, raw runTaskTimer, Adventure boss bar via track,
sidebar objective via track, recipe, tracked TextDisplay, store, shared): see `../leaks-soak/README.md`.

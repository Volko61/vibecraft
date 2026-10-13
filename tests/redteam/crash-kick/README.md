# crash-kick — disconnect players via the Bukkit API, bypassing the command policy (HIGH)

`Policy.forbiddenCommandLine` only blocks stop/restart/reload/rl command LINES. `Player.kick()`,
`.ban()`, `World` unload, etc. are unguarded. `/kickall` kicks everyone.

## Repro
Deploy, reload, connect a player, run `/kickall` (or via `/api/command`). Player(s) disconnect
(reason "direct-api-kick").

## Fix
Redirect/forbid `Player.kick`/`kickPlayer`/`ban`/`BanList.addBan`/`World.unloadWorld` for module
code.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

Rejected at load: `forbidden: Player.kick() is not allowed in modules ...` (also kickPlayer, transfer, ban, banIp, setOp, BanList.addBan/pardon, whitelist changes); `dispatchCommand("kick ...")` / `execute ... run kick` are refused at run time.
Also rejected at load: server internals that manage connections (`net.minecraft.server.network.*`, `net.minecraft.network.Connection`,
`net.minecraft.server.players.PlayerList`): `((CraftPlayer) p).getHandle().connection.disconnect(...)` -> `forbidden: ServerGamePacketListenerImpl is not allowed ...`.

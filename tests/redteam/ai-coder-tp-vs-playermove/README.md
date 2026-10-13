# ai-coder-tp-vs-playermove: "reach this block" logic in PlayerMoveEvent never fires for /tp tests

Not a VibeCraft bug: a Paper behaviour that AI coders hit because CLAUDE.md tells them to test through
console commands. `PlayerTeleportEvent` has its own HandlerList, so a `ctx.on(PlayerMoveEvent.class, ...)`
listener never sees teleports; and after a `tp` onto the target block, the player's next moves stay in
that block, so `hasChangedBlock()` is false.

Module `move-test`: logs "MOVE onto gold" from PlayerMoveEvent (with the documented
`if (!e.hasChangedBlock()) return;`) and "TELEPORT event" from PlayerTeleportEvent.

Repro (sandbox with the parkour course from `ai-coder-3-parkour`, gold block at 30,-43,-8):
```
curl -s -X POST http://127.0.0.1:8820/api/command --data "tp Testeur 30.5 -42 -7.5"
curl -s "http://127.0.0.1:8820/api/modules/move-test/logs?limit=4"   -> only "TELEPORT event to 30,-42,-8"
```
Expected for a naive parkour finish: never triggered by the API test. CLAUDE.md now says so and
recommends polling positions with `ctx.every(5, ...)` (what ai-coder-3-parkour does).

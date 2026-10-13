# ai-coder-3: "un mini-jeu parkour avec un chrono et un classement"

Module `parkour`. `/parkour [player]` builds (once) a 12-jump wool course 15 blocks above the first
player (origin saved in `ctx.store()` as a Location, so the same course is reused after reloads) and
teleports the player to the quartz start. `ctx.every(2)` polls running players only: action-bar chrono,
fall (y < start - 4) -> back to start, standing on the gold block -> time, personal best in the store,
broadcast. `/parkour top` = top 5; a TextDisplay leaderboard (`ctx.entity`) floats at the start.

Harness: `pk.mjs`, `pk2.mjs` (chrono packets), `pk3.mjs` (tp onto the gold block, top, fall).

Expected = observed: 2 reloads (the 2nd only replaced an emoji by ★ as CLAUDE.md recommends), 0 compile
errors; chrono updated 10 times/s; tp onto the gold block -> "★ Testeur termine le parkour en 0:28.39
(record perso !)"; `/parkour top` lists it; tp below the course -> "✖ Chute ! Retour au départ." and back
on the start; the hologram count stays 1 after a reload. Time: ~94 s.

Design note: finish detection polls positions on purpose. A `PlayerMoveEvent` + `hasChangedBlock()`
design never fires for `/tp` tests (see `ai-coder-tp-vs-playermove`).

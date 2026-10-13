# ai-coder-2: "une épée qui lance des éclairs là où on tape"

Module `thunder-sword`. `/foudre <player>` gives a golden sword tagged in its PDC. Left click on a block
(or in the air toward a block <= 60 m) -> `strikeLightningEffect` on that block, +6 damage and 2 s of fire
to living entities within 2.5 blocks (attacker excluded); hitting a mob -> lightning on the mob. A
400 ms per-player debounce avoids a double strike (attack packet + arm-swing LEFT_CLICK_AIR).
The block click is cancelled, so the sword never breaks blocks.

Harness: `sword.mjs` (left click on a grass block), `sword2.mjs` (hit a cow summoned next to the bot).

Expected = observed: 1 reload, 0 compile errors; exactly one lightning_bolt at the clicked block, block
intact; hitting the cow -> exactly one bolt, cow killed; runtimeErrorCount 0. Time: ~66 s.

Trap met while testing: the server is in PEACEFUL difficulty, so `summon minecraft:zombie` is refused
("Monsters cannot be summoned in Peaceful difficulty"); a cow was used instead. See `ai-coder-peaceful-monsters`.

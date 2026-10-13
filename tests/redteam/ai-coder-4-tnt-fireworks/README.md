# ai-coder-4: "quand on pose de la TNT elle fait un feu d'artifice au lieu d'exploser"

Module `tnt-fireworks`. `BlockPlaceEvent` TNT -> next tick the block becomes a `TNTPrimed` (40-tick fuse).
`ExplosionPrimeEvent` for any TNTPrimed (placed, flint and steel, `/summon tnt`) is cancelled and replaced
by 4 random fireworks tagged in their PDC; `EntityDamageByEntityEvent` caused by those fireworks is
cancelled (firework explosions hurt nearby entities otherwise).

Harness: `tnt.mjs` (bot places a TNT 2 blocks away).

Expected = observed: 1 reload, 0 compile errors; the bot sees `tnt` then 4 `firework_rocket` ~2.2 s later,
0 explosion packets, the ground block is still grass, bot health 20; `summon tnt ... {fuse:10}` from the
console is harmless too. Time: ~60 s. CLAUDE.md had no fireworks / ExplosionPrimeEvent hint before this
run (added).

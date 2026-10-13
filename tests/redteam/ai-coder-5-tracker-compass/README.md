# ai-coder-5: "une boussole qui pointe vers le joueur le plus proche"

Module `tracker-compass`. `/traqueur <player>` gives a PDC-tagged compass. Every 10 ticks, for players
holding it (main or off hand): `p.setCompassTarget(nearest player location)` + action bar
"➜ <name> à N m". Cleanup resets compass targets to the world spawn.

Harness: `compass.mjs` (connects a 2nd bot `Cible` and teleports it twice).

Expected = observed: 1 reload, 0 compile errors; Testeur receives spawn_position packets (2/s) pointing
at Cible (40,-21 then -21,30). Time: ~32 s.

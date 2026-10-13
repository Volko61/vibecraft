# ai-coder-peaceful-monsters: monsters spawned by modules vanish (server difficulty = peaceful)

`server/server.properties` (main server) has `difficulty=peaceful`. Every audience request that involves
hostile mobs ("un boss zombie", "une invasion de squelettes", "une arène de monstres") compiles, loads and
reports success, but the mobs are deleted at the next tick.

Module `zombie-test`: `/zombietest` spawns a Zombie named Boss at the world spawn with
`world.spawn(loc, Zombie.class, ...)`, reports `isValid()`, and logs `isValid()` again 20 ticks later.

Repro (sandbox API 8820, difficulty peaceful like the main server):
```
curl -s -X POST http://127.0.0.1:8820/api/modules/zombie-test/reload
curl -s -X POST http://127.0.0.1:8820/api/command --data "zombietest"   -> "spawned valid=true difficulty=PEACEFUL"
curl -s "http://127.0.0.1:8820/api/modules/zombie-test/logs?limit=1"  -> "after 1s valid=false dead=true"
curl -s -X POST http://127.0.0.1:8820/api/command --data "summon minecraft:zombie ~ ~ ~" -> "Monsters cannot be summoned in Peaceful difficulty"
```
With `difficulty easy` + `gamerule spawn_monsters false` (26.3 snake_case gamerule): "after 1s valid=true
dead=false" (and the zombie then killed the test bot), while natural monster spawning stays off.

Expected: monster features work on the demo server. Fix: main server `difficulty=easy` (or normal) plus
`gamerule spawn_monsters false` once; CLAUDE.md now tells AI coders about the trap and the
`world.setDifficulty(Difficulty.EASY)` workaround.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

Main server: `difficulty=easy` in server.properties and `difficulty easy` + `gamerule spawn_monsters false` in the world. `/zombietest` -> "spawned valid=true difficulty=EASY", 1 s later "valid=true dead=false"; natural monster spawning stays off.

# leaks-entity: ctx.entity(...) does not remove an entity whose chunk was unloaded and reloaded (high)

ModuleContextImpl.entity() keeps the Bukkit `Entity` object and its cleanup does `if (!entity.isDead())
entity.remove()`. When the chunk unloads, that object becomes dead for good; when the chunk loads again,
Bukkit creates a NEW object for the same entity (same UUID). The cleanup skips the stale one: the entity
stays in the world forever.

Steps (`/lkent`):
```
forceload add 2000 2000          # chunk loaded
lkent spawn                      # tracked TextDisplay at 2000 -59 2000
lkent status                     # valid=true dead=false, 1 in world
forceload remove 2000 2000       # chunk unloads (wait ~5 s)
lkent status                     # valid=false dead=true, 0 in world
forceload add 2000 2000          # chunk back
lkent status                     # tracked object still dead, but 1 entity in the world
POST /api/modules/leaks-entity/reload
execute if entity @e[tag=leaks-far]   -> "Test passed. Count: 1"  (expected 0)
```
Same thing on server crash / kill: tracked entities are saved with their chunk and come back after the
restart, next to the new generation's copies (observed: 4 soak holograms "soak v308", "soak v1", "soak v1",
"soak v2" at the same spot after two crashes, and entities spawned by enable() at startup before any player
joined were not removed on the next reload either). In a demo: holograms / NPCs / pets duplicate whenever
players left the area and came back.

Suggested fix: ctx.entity() should (1) `setPersistent(false)` (tracked entities are temporary by contract:
they are dropped when their chunk unloads and never saved, so crashes leave nothing behind), (2) remember
the UUID, and on unload remove `Bukkit.getEntity(uuid)` if present (not the stale object), (3) tag them
(PDC `vibecraft:module = <id>#<gen>`) so a ChunkLoad / EntitiesLoad listener can delete leftovers of dead
generations. Document in CLAUDE.md that ctx.entity entities are not persistent.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

After the chunk cycle and a reload: `execute if entity @e[tag=leaks-far]` -> Test failed (removed by UUID). Spawned, chunk unloaded, module reloaded, chunk loaded again -> purged when its chunk loads (PDC tag of a dead generation / previous boot).

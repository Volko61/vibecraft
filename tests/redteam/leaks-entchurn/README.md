# leaks-entchurn: every ctx.entity(...) stays referenced until unload, even when dead (medium)

Pattern of the pig-rain example: spawn entities with `ctx.entity(...)`, remove them a bit later. The
registration (holding the CraftEntity and its NMS entity) is never swept while the generation lives:
`ModuleContextImpl.add()` only sweeps finished tasks and inactive registrations.

Steps: `/lkchurn on` (20 marker armor stands per tick, each removed 10 ticks later), wait 30 s, `/lkchurn off`,
`jcmd <pid> GC.class_histogram`.

Expected: dead entities collected.
Observed after full GC: `net.minecraft.world.entity.decoration.ArmorStand` 13 -> 12 093 instances (9 MB of NMS
objects + CraftArmorStand), heap 362 -> 448 MB; everything is released only when the module reloads
(back to 13 / 363 MB). A module spawning 5 tracked entities per second during a 1-hour session retains
~18 000 dead entities.

Suggested fix: track UUIDs (see leaks-entity) instead of objects, and sweep entity registrations whose
entity is gone (`Bukkit.getEntity(uuid) == null` for a non-persistent entity) in the periodic sweep.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

25 s of churn: 304 NMS ArmorStands after full GC (was 12093 after 30 s): ctx.entity keeps only the UUID.

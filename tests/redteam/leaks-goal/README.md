# leaks-goal: a Paper MobGoals goal of an old generation deletes the mob (medium)

Module: `/lkgoal` spawns a cow (raw, not ctx.entity: think "make the cows dance" applied to existing mobs) and
adds a module `Goal<Cow>` with `Bukkit.getMobGoals().addGoal(cow, 0, goal)`.

Steps: `/lkgoal`, wait 3 s, reload the module.

Expected: the goal is removed with the module; the cow stays (a normal cow again).
Observed: `ERROR Entity threw exception at minecraft:overworld:4.0,-60.0,4.0 ... ModuleKilledError` and Paper
removes the entity: `execute if entity @e[tag=leaks-goal-cow]` -> "Test failed" (the cow is gone). Every mob a
module ever touched with a goal disappears at the next reload (villagers, pets...).

Suggested fix: redirect `MobGoals.addGoal` to track `removeGoal(mob, goal)` on unload (and wrap goals in a
guarded delegate).

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

After the reload the cow is still there (`Test passed. Count: 1`), no "Entity threw exception": MobGoals.addGoal is tracked (removeGoal on unload).

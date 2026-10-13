# leaks-recipe: Bukkit.addRecipe (tutorial style) makes the 2nd load fail AND the rollback fail (high)

Module: `Bukkit.addRecipe(new ShapedRecipe(ctx.key("raw_emerald"), ...))` + `discoverRecipe`, instead of
`ctx.recipe(...)` (every Bukkit tutorial does it this way).

Steps: load (v1 ok), reload.

Expected: v2 replaces v1's recipe.

Observed: `enable() threw IllegalStateException: Duplicate recipe ignored with ID ResourceKey[minecraft:recipe
/ vibecraft:leaks-recipe/raw_emerald] (LeaksRecipeModule.java:21); rollback to v1 also failed: ...` -> state
FAILED: nothing of the module runs any more, and every later reload fails the same way until the server
restarts. The recipe stays registered (and in the player's recipe book) while the module is dead.

Suggested fix: redirect `Bukkit.addRecipe(Recipe[, boolean])` / `Server.addRecipe` to `ctx.recipe(...)`
(it already removes an existing key first and is tracked); defense in depth: on unload and before every
enable / restore, remove any registered recipe whose key is `vibecraft:<id>/...`.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

Reloads ok (v2, v3): `Bukkit.addRecipe` / `Server.addRecipe` behave like ctx.recipe (existing key replaced, removed on unload); leftover recipes `vibecraft:<id>/...` are also swept on unload.

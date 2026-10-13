# ai-coder-naive-old-api: how good are the diagnostics for a pre-1.21 tutorial-style module?

`naive-sword/NaiveSwordModule.java` is what a model trained on old Spigot tutorials writes: `ChatColor` +
`setDisplayName(String)`, `Enchantment.DAMAGE_ALL`, `Attribute.GENERIC_MAX_HEALTH`,
`PotionEffectType.INCREASE_DAMAGE`, `Particle.REDSTONE`, `EntityType.LIGHTNING`, `Sound.valueOf(...)`,
`(Player) sender`.

Reload result (observed): ok:false, state FAILED, 5 ERROR diagnostics, each with file, line, column,
`cannot find symbol / symbol: variable DAMAGE_ALL / location: class org.bukkit.enchantments.Enchantment`
and the source line. Every rename is in CLAUDE.md section 7: fixed in ONE iteration
(SHARPNESS, MAX_HEALTH, STRENGTH, DUST, LIGHTNING_BOLT).

Then 4 NOTE diagnostics with line -1: "uses or overrides a deprecated API", "Recompile with
-Xlint:deprecation for details" (same for removal). Not actionable: the AI cannot recompile and does not
learn which lines (`ChatColor`, `setDisplayName`, `Sound.valueOf`) are legacy.

Runtime (after the fix), recorded in `recentErrors` with file:line:
- `/naive` from the API: `ClassCastException: FeedbackForwardingSender cannot be cast to Player (NaiveSwordModule.java:23)`
  (also with `execute as Testeur run naive` and `/vibe:naive`, as CLAUDE.md says).
- right click as a player: `IllegalArgumentException: missing required data class org.bukkit.Particle$DustOptions (NaiveSwordModule.java:35)`.
- side effect not undone: `setBaseValue(40)` on MAX_HEALTH stays on the player after unload (documented).

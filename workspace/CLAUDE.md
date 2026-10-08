# VibeCraft workspace: instructions for AI coding sessions

You are on stage at a conference. A **French-speaking audience** asks for Minecraft features and you build
them live as **hot-reloadable Java modules** for a running **Paper 26.3** server. Saving + reloading swaps
your code in about a second; nobody gets disconnected, and a guard keeps the server from crashing even if
your code is broken. The demo only works if your module **compiles, loads and does what was asked, fast**.

## 0. Workflow (do exactly this)

1. **Module id**: given in the request (`modules/<id>/`). If none is given, pick a short kebab-case id
   (`pig-rain`) and check with Glob that `modules/<id>/` is not already another chat's module.
2. **Work ONLY in `modules/<id>/`.** Never edit other modules, `examples/`, this file, `data/` or `.vibe/`.
   Other chats are editing other modules at the same time.
3. If a close pattern exists in `examples/` (table in section 9), read that one file. Don't explore more.
4. Write `modules/<id>/<Name>Module.java` (or edit the scaffold already there). Prefer **one file**
   (nested classes/records are fine).
5. Reload explicitly (saving also auto-reloads, but this call returns the result):
   `curl -s -X POST http://127.0.0.1:8765/api/modules/<id>/reload`. If `"ok":false`, read
   `error` + `diagnostics`, fix, reload again, until `"ok":true`.
6. Smoke-test through the API (section 4): run your command, check logs and `runtimeErrorCount`.
7. Final answer: 1-2 sentences **in French** for the audience (section 10).

Aim for a working first version in 2-3 minutes. Simple and robust beats ambitious and broken; the
audience can always ask for more. Don't ask clarifying questions: make a reasonable, fun choice and
mention it in your answer. A follow-up request in the same chat = edit your module and reload again.

## 1. Tools and environment

- Files: **Read / Write / Edit / Glob / Grep** tools. **Bash is only for `curl` to `http://127.0.0.1:8765`.**
  Headless jobs (`/vibe ai`) may run nothing else: no ls, cat, javac, gradle, mvn, sleep, jq, no pipes,
  no `&&`. One plain `curl` per Bash call. If you only have PowerShell, call `curl.exe`, not `curl`.
- Keep curl bodies **ASCII**: on Windows, curl mangles accents (`à` arrives as an invalid byte, `✔` as `?`).
  French text belongs in your Java sources (UTF-8), not in test commands.
- No build tool: the server compiles your sources in memory (`javac --release 25`: records, `var`,
  switch expressions and patterns, text blocks are all fine).
- Available libraries: Paper API 26.3, Adventure 5.2 + MiniMessage, Gson, SnakeYAML, Guava, JOML, the JDK.
  Nothing else (no Maven dependencies, no other plugins' APIs).
- Humans watch the dashboard at http://127.0.0.1:8765/ and in-game chat (reloads are announced there).

## 2. Module layout

```
modules/<id>/
  <Name>Module.java   exactly ONE public non-abstract class implementing VibeModule (public no-arg ctor)
  *.java              optional extra classes (same package)
  config.yml          optional, read with ctx.config(); saving it reloads the module (live tuning)
  module.yml          optional: description: "..." (shown on the dashboard)
  .vibe/              written by the server: status.json, build.log, errors.log (read them, never write)
data/<id>/store.yml   ctx.store(), managed by the server
```

- id: `[a-z0-9][a-z0-9_-]{0,39}`. Package: `vibe.<id with - replaced by _>` (`pig-rain` -> `vibe.pig_rain`,
  digit first -> `_`: `2048` -> `vibe._2048`); if a scaffold exists, keep its package.
- If the folder already contains a scaffold (`<Name>Module.java` created by `/vibe new` or `/vibe ai`),
  **edit it**: a second class implementing VibeModule makes the load fail.
- The watcher also reloads about 2 s after the last save. While you write several files the server may
  compile half-written code: harmless (the previous version keeps running), but the final reload must be ok.
- One module = one feature = one chat. If the request really belongs to another module, say so in your
  answer instead of editing it. Modules can exchange data through `ctx.shared()`.
- Never delete or rename a module folder (that unloads the module).

## 3. Skeleton (copy, rename, fill)

```java
package vibe.my_feature;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.event.player.PlayerJoinEvent;

public class MyFeatureModule implements VibeModule {

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("myfeature", (sender, args) -> sender.sendRichMessage("<green>Salut <white>" + sender.getName()));
        ctx.on(PlayerJoinEvent.class, e -> e.getPlayer().sendRichMessage("<gold>Bienvenue !"));
        ctx.every(20, () -> { /* every second, on the main thread */ });
        ctx.logger().info("enabled");
    }
}
```

Everything registered through `ctx` is undone automatically on reload/disable; `disable()` is optional.

Imports that are easy to get wrong:
```java
import net.kyori.adventure.text.Component;              // + text.format.NamedTextColor, text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage; // + text.minimessage.tag.resolver.Placeholder / TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;                  // + java.time.Duration
import net.kyori.adventure.bossbar.BossBar;              // NOT org.bukkit.boss.BossBar
import org.bukkit.Sound;                                 // NOT net.kyori.adventure.sound.Sound
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.EquipmentSlot;               // + inventory.ItemStack, ShapedRecipe, InventoryHolder
import org.bukkit.scheduler.BukkitTask;
import io.papermc.paper.event.player.AsyncChatEvent;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
```

## 4. Dev loop: HTTP API on 127.0.0.1:8765

| What | Command |
|---|---|
| reload (compile + hot swap, waits for the result) | `curl -s -X POST http://127.0.0.1:8765/api/modules/<id>/reload` |
| module status | `curl -s http://127.0.0.1:8765/api/modules/<id>` |
| module logs (logger + runtime errors) | `curl -s "http://127.0.0.1:8765/api/modules/<id>/logs?limit=50"` |
| run a server command as the console | `curl -s -X POST http://127.0.0.1:8765/api/command --data "say hi"` |
| online players | `curl -s http://127.0.0.1:8765/api/players` |
| scaffold a new module (optional) | `curl -s -X POST http://127.0.0.1:8765/api/modules/<id>/create --data "description"` |
| re-enable a disabled module | `curl -s -X POST http://127.0.0.1:8765/api/modules/<id>/enable` |

Reload answer:
```json
{"id":"pig-rain","ok":false,"state":"ENABLED","generation":3,"error":"compilation failed: 1 error(s)",
 "diagnostics":[{"kind":"ERROR","file":"PigRainModule.java","line":42,"column":17,
   "message":"cannot find symbol ...","sourceLine":"Particle.REDSTONE"}],"durationMs":850,"rolledBack":true}
```
- `ok:true`: your new code runs (`generation` N). Warnings in `diagnostics` can be ignored.
- `ok:false, rolledBack:true`: the previous version is still running. Fix and reload.
- `state:"FAILED"`: nothing runs (first version broken). `"TRIPPED"`: circuit breaker (too many runtime
  errors, see logs / `.vibe/errors.log`); fix and reload to re-arm. `"DISABLED"`: use the enable endpoint.
- reload works as soon as the folder holds your `.java` file (no need to wait for the watcher); 404 = no such folder.
- `GET /api/modules/<id>` also returns `lastError`, `runtimeErrorCount`, `recentErrors`, `commands`,
  `listeners`, `tasks`, `msPerTick`. Files: `.vibe/build.log` (compiler output), `.vibe/errors.log`
  (runtime stack traces), `.vibe/status.json`.

Testing:
- `/api/command` runs as the **console** and returns its output lines (`{"ok":true,"output":[...]}`).
  `execute as <player> run <yourcmd>` does **not** make the player the sender of a module command.
  So make commands usable from the console, typically with an optional player argument
  (`/wand [player]`), and test them with a name from `/api/players`.
- Handy vanilla checks: `execute if entity @e[type=minecraft:pig]` (prints the count),
  `data get entity <player> Pos`, `scoreboard objectives list`, `recipe give <player> vibecraft:<id>/<key>`,
  `give <player> diamond`, `gamemode survival <player>`, `time set day`.
- Nobody online? Compile + enable + console commands + logs is enough; never wait for players.
- After testing, check `runtimeErrorCount` / `recentErrors` with the status endpoint.

Frequent compile errors:

| Error | Fix |
|---|---|
| `cannot find symbol` on a constant (`Particle.REDSTONE`, `Attribute.GENERIC_MAX_HEALTH`...) | renamed, see section 7 |
| `String cannot be converted to Component` | wrap with `ctx.mm("...")` |
| `unreported exception ... must be caught` inside `ctx.later/every/on` lambdas | try/catch inside (only command and tab handlers may throw) |
| `local variables referenced from a lambda ... must be final` | use a field, `int[] n = {0}` or `AtomicInteger` |
| `class X is public, should be declared in a file named X.java` | file name = public class name |
| load error: no / several VibeModule classes | exactly one class implements `VibeModule` |

## 5. API reference (package `dev.vibecraft.api`, exact signatures)

```java
public interface VibeModule {
    void enable(ModuleContext ctx) throws Exception;              // main thread, register everything here
    default void disable(ModuleContext ctx) throws Exception { }  // main thread, before automatic cleanup
}

@FunctionalInterface public interface CommandHandler { void handle(CommandSender sender, String[] args) throws Exception; } // args exclude the label
@FunctionalInterface public interface TabHandler { List<String> complete(CommandSender sender, String[] args) throws Exception; } // empty list = none

public interface ModuleContext {
    String id();                         // "pig-rain"
    int generation();                    // 1, 2, 3... (+1 per successful reload)
    Plugin plugin();                     // VibeCraft host plugin, for Bukkit APIs that need a Plugin
    Server server();
    Logger logger();                     // java.util.logging; visible in /logs and on the dashboard
    File sourceFolder();                 // modules/<id>/
    File dataFolder();                   // data/<id>/ (persistent files)
    YamlConfiguration config();          // modules/<id>/config.yml (empty if absent)
    ModuleStore store();                 // persistent key/value store
    Map<String, Object> shared();        // global map shared by all modules, kept across reloads

    <E extends Event> void on(Class<E> type, Consumer<? super E> handler);   // priority NORMAL, ignoreCancelled false
    <E extends Event> void on(Class<E> type, EventPriority priority, boolean ignoreCancelled, Consumer<? super E> handler);
    void listen(Listener listener);      // classic @EventHandler methods

    void command(String name, CommandHandler handler);
    void command(String name, String description, CommandHandler handler, TabHandler tabHandler, String... aliases);

    BukkitTask later(long delayTicks, Runnable task);                    // once, main thread (20 ticks = 1 s)
    BukkitTask every(long periodTicks, Runnable task);                   // repeating, first run after one period
    BukkitTask every(long delayTicks, long periodTicks, Runnable task);
    BukkitTask async(Runnable task);                                     // once, OFF the main thread

    NamespacedKey key(String path);      // vibecraft:<id>/<path>, stable across reloads
    void recipe(Recipe recipe);          // must be Keyed (ShapedRecipe...), removed on unload
    <T extends Entity> T entity(T entity);                    // removed on unload; returns it
    <T> T track(T resource, Consumer<? super T> cleanup);     // cleanup runs on unload (reverse order); returns it
    void onCleanup(Runnable cleanup);
    Component mm(String miniMessage);    // parse MiniMessage
    void broadcast(String miniMessage);  // to every player + console
}

public interface ModuleStore {           // data/<id>/store.yml, dotted paths "players.<uuid>.score"
    Object get(String path);  String getString(String path, String def);  int getInt(String path, int def);
    long getLong(String path, long def);  double getDouble(String path, double def);
    boolean getBoolean(String path, boolean def);  List<String> getStringList(String path);
    boolean contains(String path);  Set<String> keys(String path);   // direct children, "" = root
    void set(String path, Object value); // null removes; YAML types, ItemStack, Location... never module objects
    void save();                         // rarely needed: saved on unload and every 60 s when dirty
}
```

## 6. Guard rules (they protect the live server)

- **Rejected at load** (the module does not load, ERROR diagnostic): `System.exit`, `Runtime.exit/halt/exec`,
  `ProcessBuilder`, `Thread.stop`, `Bukkit/Server.shutdown/restart/reload/reloadData`,
  `PluginManager.disablePlugin/enablePlugin/clearPlugins`, `HandlerList.unregisterAll()` (static) and
  `unregisterAll(Plugin)`, `BukkitScheduler.cancelTasks(Plugin)`, `sun.misc.Unsafe`, `jdk.internal.*`,
  `System.setSecurityManager`. They would stop the server, break other plugins or other modules.
- **Main-thread time budgets**: one event handler / command / task run <= 250 ms, `enable`/`disable` <= 5 s.
  Over budget, the call is aborted with `ModuleTimeoutError` and counted as an error. So: no long loops,
  no blocking I/O, and **never `Thread.sleep` on the main thread** (it throws immediately). Delays are
  `ctx.later(ticks, ...)`.
- **Circuit breaker**: 25 errors within 10 s and the module is unloaded (TRIPPED). Typical cause: an
  exception thrown at every run of `ctx.every(1, ...)`. Null-check things (players leave, entities die).
- Exceptions are caught and logged (command senders get a short red message). Catch `Exception` when you
  must, **never `Throwable` or `Error`**: the guard's errors must propagate.
- **Old code is killed on reload**: code of an unloaded generation (lambdas, tasks, threads) throws
  `ModuleKilledError` as soon as it runs. Use `ctx` for everything; no raw `Thread`, `Timer`, executors.
- **Static state is lost on reload** (fresh class loader each time). `static final` constants and static
  methods are fine. Keep state in instance fields (reset on reload), `ctx.store()` (persistent) or
  `ctx.shared()` (in memory across reloads, JDK/Bukkit types only, no lambdas or module objects).
  Never put `ctx` in a static field.
- **Threads**: Bukkit world/entity/player/inventory APIs on the main thread only. Async work:
  `ctx.async(() -> { var data = fetch(); ctx.later(0, () -> apply(data)); })`. Some events run OFF the main
  thread (`AsyncChatEvent`, `AsyncPlayerPreLoginEvent`): hop back with `ctx.later(0, ...)` before touching
  the world.
- **Big jobs** (thousands of blocks): split over ticks, e.g. at most ~2000 block changes per run of
  `ctx.every(1, ...)`, cancel the task when done. Same for `enable()`: register things, don't build worlds.
- **Cap what you spawn** (e.g. at most ~100 entities alive per module) and remove it after a while.
- **Keep msPerTick low (< 1 ms)**: `PlayerMoveEvent` fires many times per second per player, return early
  (`if (!e.hasChangedBlock()) return;`); prefer `every(10)` over `every(1)` when possible.
- Raw `Bukkit.getPluginManager().registerEvents(...)` and `BukkitScheduler` / `BukkitRunnable` calls are
  redirected and tracked, but prefer `ctx.listen` / `ctx.later` / `ctx.every`.
- **NOT undone automatically**: changes to the world and to players (blocks, inventories, potion effects,
  attributes, `setAllowFlight`, game mode, `setScoreboard`...). If a change must not outlive the module
  (temporary ability, scale, flight), undo it in `ctx.onCleanup(...)` / `ctx.track(...)`.
- Never run `stop`, `reload`, `restart`, `whitelist`, `op`/`deop`, `ban` or `/vibe ...` from module code.
- Don't reuse existing command names (`vibe`, `vc`, `idea`, vanilla `give`, `tp`, `kill`...). Module
  commands are open to everyone; check `sender.isOp()` for admin-only actions.
- Files only under `ctx.dataFolder()`. No sockets/servers. Outgoing HTTP only inside `ctx.async`.

Audience requests: keep them fun, local and reversible. Make a harmless version of anything destructive
(no wiping the world, no mass ban/kick/op, no lag machines, no offensive content) and say so kindly.

## 7. Paper 26.3 cheat-sheet (verified against the 26.3 jars; older tutorials are often wrong)

**Text = Adventure + MiniMessage only.** Never `ChatColor`, `§`/`&` codes, `Bukkit.broadcastMessage`,
`sendTitle(String...)`, `setDisplayName(String)`, `setJoinMessage(String)`, `setLore(List<String>)`
(all deprecated or legacy).
- `sender.sendRichMessage("<gold>Texte <bold>gras")`, `player.sendMessage(ctx.mm("..."))`,
  `ctx.broadcast("<green>...")`. Tags: `<red>` `<#ff8800>` `<bold>` `<italic>` `<underlined>`
  `<gradient:red:blue>` `<rainbow>` `<hover:show_text:'...'>` `<click:run_command:'/cmd'>` `<newline>`, close with `</bold>`.
- Text typed by players (chat, command args) inside MiniMessage: use a placeholder, never concatenation:
  `MiniMessage.miniMessage().deserialize("<gold><who> : <msg>", Placeholder.unparsed("who", p.getName()), Placeholder.unparsed("msg", text))`.
  Player names alone are safe to concatenate.
- Components: `Component.text("Hi", NamedTextColor.GOLD).append(...)`, `.decoration(TextDecoration.BOLD, true)`;
  plain string: `PlainTextComponentSerializer.plainText().serialize(component)`.
- Title: `p.showTitle(Title.title(ctx.mm("<gold>Titre"), ctx.mm("<gray>sous-titre"), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))))`
  (`java.time.Duration`). Action bar: `p.sendActionBar(ctx.mm("<yellow>..."))`.
- `e.joinMessage(Component)`, `e.quitMessage(Component)`, `e.deathMessage(Component)` (null = no message).
- Prefer symbols like ✔ ✖ ★ ✦ ❤ ☀ ❄ ⚡ ☠ ♪ ⛏ ➜ over emoji in game texts.

**Sounds.** `p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)` (that player only) or
`world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f)` (everyone nearby). `org.bukkit.Sound` is an
**interface now** (registry-backed): constants work, but no `switch`, no `Sound.valueOf()/values()`; by
name: `Registry.SOUNDS.get(NamespacedKey.minecraft("entity.pig.ambient"))` (null if unknown).

**Particles.** `world.spawnParticle(Particle.HAPPY_VILLAGER, loc, count, dx, dy, dz, speed)`. `Particle` is
still an enum, with the 1.20.5+ names: HAPPY_VILLAGER, ANGRY_VILLAGER, HEART, FLAME, SOUL_FIRE_FLAME,
SMOKE, LARGE_SMOKE, CLOUD, POOF, EXPLOSION, EXPLOSION_EMITTER, FIREWORK, CRIT, ENCHANTED_HIT, ENCHANT,
WITCH, PORTAL, END_ROD, TOTEM_OF_UNDYING, NOTE, SNOWFLAKE, SPLASH, BUBBLE, LAVA, GLOW, ELECTRIC_SPARK,
SONIC_BOOM, CHERRY_LEAVES. Old names do not compile: REDSTONE->DUST, VILLAGER_HAPPY->HAPPY_VILLAGER,
EXPLOSION_LARGE->EXPLOSION, EXPLOSION_HUGE->EXPLOSION_EMITTER, FIREWORKS_SPARK->FIREWORK,
SPELL_WITCH->WITCH, SMOKE_NORMAL->SMOKE, SMOKE_LARGE->LARGE_SMOKE, TOTEM->TOTEM_OF_UNDYING,
ENCHANTMENT_TABLE->ENCHANT, CRIT_MAGIC->ENCHANTED_HIT, WATER_SPLASH->SPLASH, DRIP_LAVA->DRIPPING_LAVA,
BLOCK_CRACK->BLOCK, ITEM_CRACK->ITEM, SPELL_MOB->ENTITY_EFFECT.
Particles that **require a data argument** (else IllegalArgumentException), passed last:
DUST `new Particle.DustOptions(Color.fromRGB(255, 0, 255), 1.5f)`; DUST_COLOR_TRANSITION `Particle.DustTransition`;
BLOCK, FALLING_DUST, BLOCK_MARKER, DUST_PILLAR, BLOCK_CRUMBLE `Material.STONE.createBlockData()`; ITEM an ItemStack;
ENTITY_EFFECT, FLASH, TINTED_LEAVES a `Color`; DRAGON_BREATH, SCULK_CHARGE a Float; SHRIEK an Integer;
EFFECT, INSTANT_EFFECT `Particle.Spell`; also VIBRATION, TRAIL. Example:
`world.spawnParticle(Particle.DUST, loc, 30, 0.5, 0.5, 0.5, 0, new Particle.DustOptions(Color.RED, 1.5f));`

**Entities.**
- `Pig pig = ctx.entity(world.spawn(loc, Pig.class, e -> { e.customName(ctx.mm("<light_purple>Bob")); e.setCustomNameVisible(true); }));`
  The consumer configures it before it appears; `ctx.entity` removes it on reload (omit it to keep it forever).
- EntityType renames: SNOWMAN->SNOW_GOLEM, MUSHROOM_COW->MOOSHROOM, PRIMED_TNT->TNT, DROPPED_ITEM->ITEM,
  FIREWORK->FIREWORK_ROCKET, LIGHTNING->LIGHTNING_BOLT, ENDER_CRYSTAL->END_CRYSTAL. Prefer `spawn(loc, X.class, ...)`.
- `world.strikeLightningEffect(loc)` (visual only), `world.strikeLightning(loc)` (damage + fire),
  `world.createExplosion(loc, 2f, false, false)` (power, setFire, breakBlocks),
  `world.spawn(loc, TNTPrimed.class, t -> t.setFuseTicks(40))`, `world.dropItemNaturally(loc, item)`.
- Hologram: `ctx.entity(world.spawn(loc, TextDisplay.class, t -> { t.text(ctx.mm("<gold>Hello")); t.setBillboard(Display.Billboard.CENTER); }))`.
- Nearby: `loc.getNearbyEntitiesByType(Zombie.class, 10)`, `loc.getNearbyPlayers(10)`, `p.getNearbyEntities(5, 5, 5)`.
- `mob.setAI(false)`, `mob.setTarget(player)` (Mob), `entity.setVelocity(new Vector(0, 1.2, 0))`,
  `entity.setGlowing(true)`, `entity.setInvulnerable(true)`. Normalizing a zero vector gives NaN: check first.

**Attributes and effects.**
- No `GENERIC_` prefix since 1.21.3: `Attribute.MAX_HEALTH`, MOVEMENT_SPEED, ATTACK_DAMAGE, ATTACK_SPEED,
  ARMOR, SCALE, JUMP_STRENGTH, GRAVITY, STEP_HEIGHT, SAFE_FALL_DISTANCE, FALL_DAMAGE_MULTIPLIER,
  KNOCKBACK_RESISTANCE, BLOCK_INTERACTION_RANGE, ENTITY_INTERACTION_RANGE, FLYING_SPEED, LUCK.
  `Attribute` is an interface (no switch / valueOf). `getAttribute(...)` can return null.
- Prefer a keyed modifier (easy to undo) over `setBaseValue`; modifiers added with `addModifier` are
  saved with the player, so remove them on cleanup:
  ```java
  NamespacedKey big = ctx.key("big");
  AttributeInstance scale = p.getAttribute(Attribute.SCALE);
  if (scale != null) { scale.removeModifier(big); scale.addModifier(new AttributeModifier(big, 1.0, AttributeModifier.Operation.ADD_SCALAR)); } // x2
  ctx.onCleanup(() -> Bukkit.getOnlinePlayers().forEach(pl -> { var a = pl.getAttribute(Attribute.SCALE); if (a != null) a.removeModifier(big); }));
  ```
- `p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 10, 1))` (ticks, amplifier 0 = level I).
  Names: SPEED, SLOWNESS, HASTE, MINING_FATIGUE, STRENGTH, INSTANT_HEALTH, INSTANT_DAMAGE, JUMP_BOOST,
  NAUSEA, REGENERATION, RESISTANCE, FIRE_RESISTANCE, WATER_BREATHING, INVISIBILITY, BLINDNESS,
  NIGHT_VISION, HUNGER, WEAKNESS, POISON, WITHER, HEALTH_BOOST, ABSORPTION, SATURATION, GLOWING,
  LEVITATION, LUCK, SLOW_FALLING, DARKNESS (no more SLOW, FAST_DIGGING, INCREASE_DAMAGE, HEAL, HARM, JUMP,
  CONFUSION, DAMAGE_RESISTANCE). `addPotionEffect(effect, boolean)` is deprecated.
- Enchantments: SHARPNESS, EFFICIENCY, UNBREAKING, FORTUNE, LOOTING, PROTECTION, POWER, INFINITY,
  MENDING, KNOCKBACK, FIRE_ASPECT, SILK_TOUCH, FEATHER_FALLING (not DAMAGE_ALL, DIG_SPEED, DURABILITY,
  LOOT_BONUS_*, ARROW_*). `item.addUnsafeEnchantment(Enchantment.SHARPNESS, 10)`.

**Items, custom items, recipes.**
- `ItemStack it = ItemStack.of(Material.DIAMOND_SWORD)`; give: `p.give(it)` (drops what doesn't fit) or
  `p.getInventory().addItem(it)`; hand: `p.getInventory().getItemInMainHand()` (`getItemInHand()` is deprecated).
- `it.editMeta(meta -> { meta.itemName(ctx.mm("<gold>Épée de feu")); meta.lore(List.of(ctx.mm("<gray>Brûle tout").decoration(TextDecoration.ITALIC, false))); meta.setEnchantmentGlintOverride(true); meta.setUnbreakable(true); });`
  `itemName` is never italic; `displayName` behaves like an anvil rename (italic unless disabled).
- Tag custom items with the PDC: inside `editMeta`, `meta.getPersistentDataContainer().set(ctx.key("fire_sword"), PersistentDataType.BOOLEAN, true)`;
  test: `it != null && it.getPersistentDataContainer().has(ctx.key("fire_sword"))` (read-only view on
  ItemStack; modify with `it.editPersistentDataContainer(pdc -> ...)`). Keys are stable, so items keep working after reloads.
- Material is still an enum. Renames: GRASS->SHORT_GRASS, SCUTE->TURTLE_SCUTE. `Material.matchMaterial("diamond")` may return null.
- Recipe: `ShapedRecipe r = new ShapedRecipe(ctx.key("fire_sword"), result); r.shape(" B ", " B ", " S "); r.setIngredient('B', Material.BLAZE_POWDER); r.setIngredient('S', Material.DIAMOND_SWORD); ctx.recipe(r);`
  (`new ShapedRecipe(ItemStack)` is deprecated). Avoid shapes that already exist in vanilla.
  `ShapelessRecipe` + `addIngredient(Material)` also works.
- Use detection: `PlayerInteractEvent`: `if (e.getHand() != EquipmentSlot.HAND || !e.getAction().isRightClick()) return;`
  then `e.getItem()` (may be null). Hits: `EntityDamageByEntityEvent` with `e.getDamager() instanceof Player p`.
  Right-click on an entity: `PlayerInteractEntityEvent` (check `getHand()` too).

**Scoreboards, boss bars, menus.**
- Sidebar on the main scoreboard, removed on unload (full version in `examples/scoreboard`):
  `Objective o = ctx.track(Bukkit.getScoreboardManager().getMainScoreboard().registerNewObjective("vibe_kills", Criteria.DUMMY, ctx.mm("<gold>Kills")), Objective::unregister);`
  `o.setDisplaySlot(DisplaySlot.SIDEBAR); o.getScore(p.getName()).setScore(3);`
  `registerNewObjective` throws if the name exists (leftover after a crash): unregister `getObjective(name)` first.
  Only one sidebar is visible at a time.
- Boss bar (Adventure): `BossBar bar = ctx.track(BossBar.bossBar(ctx.mm("<red>Boss"), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS), b -> Bukkit.getOnlinePlayers().forEach(pl -> pl.hideBossBar(b)));`
  then `p.showBossBar(bar)`, `bar.progress(0.5f)` (0..1), `bar.name(...)`, `bar.color(...)`. Show it to late joiners too.
- Chest menu: a nested `class Menu implements InventoryHolder` that creates
  `Bukkit.createInventory(this, 27, ctx.mm("<dark_purple>Menu"))` and returns it from `getInventory()`;
  `p.openInventory(menu.getInventory())`; in `InventoryClickEvent`: `if (e.getInventory().getHolder() instanceof Menu) { e.setCancelled(true); ... }`.
  On cleanup close menus that are open (`p.closeInventory()`).

**Events.** Listen to concrete classes (abstract ones like `PlayerEvent` have no handler list and fail):
PlayerJoinEvent, PlayerQuitEvent, PlayerMoveEvent, PlayerInteractEvent, PlayerInteractEntityEvent,
PlayerToggleSneakEvent, PlayerToggleFlightEvent, PlayerDropItemEvent, PlayerItemConsumeEvent,
PlayerRespawnEvent, PlayerDeathEvent, BlockBreakEvent, BlockPlaceEvent, EntityDamageEvent,
EntityDamageByEntityEvent, EntityDeathEvent, ProjectileHitEvent, EntityShootBowEvent, CreatureSpawnEvent,
EntityPickupItemEvent, InventoryClickEvent, PlayerFishEvent; Paper: `com.destroystokyo.paper.event.player.PlayerJumpEvent`,
`io.papermc.paper.event.player.AsyncChatEvent` (async; `AsyncPlayerChatEvent` is deprecated):
`ctx.on(AsyncChatEvent.class, e -> { String msg = PlainTextComponentSerializer.plainText().serialize(e.message()); if (msg.contains("feu")) ctx.later(0, () -> e.getPlayer().setFireTicks(60)); });`

**Misc.** `loc.clone().add(x, y, z)` (`add` mutates!), `loc.getDirection()`, `p.getEyeLocation()`,
`p.getTargetBlockExact(50)` (may be null), `block.setType(Material.GOLD_BLOCK)`,
`world.getHighestBlockAt(x, z)`, `block.getRelative(BlockFace.UP)`, `Bukkit.getOnlinePlayers()`,
`Bukkit.getPlayerExact(name)` (may be null), `p.getUniqueId()`, `GameMode.SURVIVAL`, `world.setTime(6000)`,
`world.setStorm(false)`, vanilla command: `Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "effect give @a speed 10 1")`.
`Player#isOnGround()` is deprecated (client-reported): check the block below instead
(`p.getLocation().add(0, -0.1, 0).getBlock().isSolid()`). Module commands also exist as `/vibe:<name>`.

## 8. Patterns

- Per-player cooldown: instance field `Map<UUID, Long>`; compare with `System.currentTimeMillis()`.
- Task that stops itself: `BukkitTask t = ctx.every(...)` stored in a field, `t.cancel()`; counters in
  `int[] left = {n}` for lambdas (see `examples/pig-rain`).
- Persistent counter: `ctx.store().getInt("kills." + p.getUniqueId(), 0)` then `set(...)`. Path parts are
  separated by dots, so never put a dot inside a part (UUIDs and player names are fine).
- Tunable values: `config.yml` + code defaults (`ctx.config().getInt("radius", 10)`); the presenter can
  edit the file live.
- Cross-module data: `ctx.shared().put("arena.center", loc)`; readers check `instanceof`.
- Everything stops on reload. If a timer must survive reloads, store its end time in the store.

## 9. Examples (`examples/<id>/`, complete modules, not loaded; copy patterns from them)

| Example | Shows |
|---|---|
| `hello` | command, alias, tab completion, MiniMessage placeholder, sound |
| `welcome` | join listener, `config.yml`, store counter, title, delayed task |
| `double-jump` | flight-toggle trick, cooldown, velocity, particles, sound, `onCleanup` restoring player state, config |
| `pig-rain` | sub-commands, repeating task that stops itself, tracked entities with a cap, store counter |
| `magic-wand` | custom item with PDC tag, right-click listener, shaped recipe, particle beam, `/wand [player]` |
| `boss-bar` | Adventure boss bar countdown, `ctx.track` cleanup, late joiners, titles |
| `scoreboard` | live sidebar on the main scoreboard, `ctx.track(obj, Objective::unregister)`, store |

## 10. Final answer

Your final answer is shown to the audience (in game chat and on the dashboard). Write **1-2 short
sentences in French**, plain text (no Markdown, no code, no file paths, no emoji), saying what was added
and exactly what to try now. Example:

> C'est en ligne ! Tapez /pigrain 20 pour une pluie de cochons parachutistes, et /pigrain stop pour l'arrêter.

If something does not work, say honestly in French what works and what doesn't.

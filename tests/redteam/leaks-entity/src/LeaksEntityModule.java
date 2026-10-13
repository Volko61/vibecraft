package vibe.leaks_entity;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

/**
 * REDTEAM-LEAKS: ctx.entity(...) tracks the Bukkit Entity object. If the entity's chunk is unloaded and loaded
 * again, Bukkit hands out a NEW object for the same entity; the tracked one is "dead" and the cleanup skips it.
 * /lkent spawn  -> spawns a tracked hologram at 2000 -60 2000 (chunk must be loaded: use forceload)
 * /lkent status -> tracked object valid/dead + entities with the tag in the world
 */
public class LeaksEntityModule implements VibeModule {
    private Entity tracked;

    @Override
    public void enable(ModuleContext ctx) {
        int g = ctx.generation();
        ctx.command("lkent", (sender, args) -> {
            World w = Bukkit.getWorlds().get(0);
            if (args.length > 0 && args[0].equals("spawn")) {
                Location loc = new Location(w, 2000.5, -59, 2000.5);
                tracked = ctx.entity(w.spawn(loc, TextDisplay.class, t -> {
                    t.text(ctx.mm("<gold>far v" + g));
                    t.addScoreboardTag("leaks-far");
                }));
                sender.sendMessage("spawned " + tracked.getUniqueId());
                return;
            }
            long n = w.getEntities().stream().filter(e -> e.getScoreboardTags().contains("leaks-far")).count();
            sender.sendMessage("v" + g + " tracked=" + (tracked == null ? "none" : "valid=" + tracked.isValid() + " dead=" + tracked.isDead()
                    + " sameObjectAsWorld=" + (tracked != null && Bukkit.getEntity(tracked.getUniqueId()) == tracked)) + " inWorld(tag leaks-far)=" + n);
        });
    }
}

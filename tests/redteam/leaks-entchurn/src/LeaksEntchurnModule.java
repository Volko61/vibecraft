package vibe.leaks_entchurn;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;

/**
 * REDTEAM-LEAKS: a long-running generation that spawns short-lived tracked entities (like the pig-rain
 * example: ctx.entity + remove after a while). /lkchurn on|off. Each tracked entity stays referenced by the
 * generation's registration list after it is removed/dead, until the module is unloaded.
 */
public class LeaksEntchurnModule implements VibeModule {
    private boolean on;

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("lkchurn", (s, a) -> {
            on = a.length > 0 && a[0].equals("on");
            s.sendMessage("churn " + on);
        });
        ctx.every(1, () -> {
            if (!on) return;
            Location loc = Bukkit.getWorlds().get(0).getSpawnLocation().add(0, 3, 6);
            for (int i = 0; i < 20; i++) {
                ArmorStand a = ctx.entity(loc.getWorld().spawn(loc, ArmorStand.class, x -> x.setMarker(true)));
                ctx.later(10, a::remove);
            }
        });
    }
}

package vibe.zombie_test;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Zombie;

/** Typical "boss zombie" request: spawn a zombie and check it survives (server difficulty may be peaceful). */
public class ZombieTestModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("zombietest", (sender, args) -> {
            Location loc = Bukkit.getWorlds().get(0).getSpawnLocation();
            Zombie z = ctx.entity(loc.getWorld().spawn(loc, Zombie.class, e -> e.customName(ctx.mm("<red>Boss"))));
            sender.sendRichMessage("spawned valid=" + z.isValid() + " difficulty=" + loc.getWorld().getDifficulty());
            ctx.later(20, () -> ctx.logger().info("after 1s valid=" + z.isValid() + " dead=" + z.isDead()));
        });
    }
}

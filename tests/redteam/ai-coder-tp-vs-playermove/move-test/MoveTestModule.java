package vibe.move_test;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Naive "reach the gold block" detection with PlayerMoveEvent: does a /tp onto the block trigger it? */
public class MoveTestModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        ctx.on(PlayerMoveEvent.class, e -> {
            if (!e.hasChangedBlock()) return;
            if (e.getTo().clone().add(0, -0.2, 0).getBlock().getType() == Material.GOLD_BLOCK)
                ctx.logger().info("MOVE onto gold: " + e.getClass().getSimpleName());
        });
        ctx.on(PlayerTeleportEvent.class, e -> ctx.logger().info("TELEPORT event to " + e.getTo().getBlockX() + "," + e.getTo().getBlockY() + "," + e.getTo().getBlockZ()));
    }
}

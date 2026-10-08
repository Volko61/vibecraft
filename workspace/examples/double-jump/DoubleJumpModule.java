package vibe.double_jump;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.util.Vector;

/**
 * Double jump: press jump twice. Classic trick: grounded survival players get "allow flight", so the
 * client sends a flight toggle when they jump again in mid-air; we cancel the flight and boost them instead.
 */
public class DoubleJumpModule implements VibeModule {

    /** Instance state is fine (it simply restarts empty after a reload). */
    private final Map<UUID, Long> lastJump = new HashMap<>();

    @Override
    public void enable(ModuleContext ctx) {
        double up = ctx.config().getDouble("power", 0.9);
        double forward = ctx.config().getDouble("forward", 0.6);
        long cooldownMs = ctx.config().getLong("cooldown-ms", 1200);

        // 1. Every 2 ticks: players standing on the ground get the permission to "fly" again.
        ctx.every(2, () -> {
            long now = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (usesTrick(p) && !p.getAllowFlight() && onGround(p)
                        && now - lastJump.getOrDefault(p.getUniqueId(), 0L) > cooldownMs) {
                    p.setAllowFlight(true);
                }
            }
        });

        // 2. The flight toggle IS the second jump.
        ctx.on(PlayerToggleFlightEvent.class, e -> {
            Player p = e.getPlayer();
            if (!usesTrick(p)) {
                return; // creative / spectator players really fly
            }
            e.setCancelled(true);
            p.setAllowFlight(false);
            p.setFlying(false);
            lastJump.put(p.getUniqueId(), System.currentTimeMillis());

            Vector dir = p.getLocation().getDirection().setY(0);   // horizontal look direction
            Vector boost = dir.lengthSquared() > 1.0E-4 ? dir.normalize().multiply(forward) : new Vector();
            p.setVelocity(boost.setY(up));                          // (normalizing a zero vector gives NaN)
            p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 25, 0.3, 0.1, 0.3, 0.02);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BREEZE_JUMP, 1f, 1.2f);
        });

        // 3. No fall damage shortly after a double jump.
        ctx.on(EntityDamageEvent.class, e -> {
            if (e.getCause() == EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p
                    && System.currentTimeMillis() - lastJump.getOrDefault(p.getUniqueId(), 0L) < 6000) {
                e.setCancelled(true);
            }
        });

        // 4. Player state is NOT undone automatically: on reload/disable, take the flight permission back.
        ctx.onCleanup(() -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (usesTrick(p)) {
                    p.setFlying(false);
                    p.setAllowFlight(false);
                }
            }
        });
    }

    private static boolean usesTrick(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    private static boolean onGround(Player p) {
        return p.getLocation().add(0, -0.1, 0).getBlock().isSolid();
    }
}

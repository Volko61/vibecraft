package vibe.velocity;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * REDTEAM-CRASH / crash-velocity  (severity: CRITICAL)
 *
 * Setting an extreme but FINITE velocity on a player. `setVelocity(new Vector(1e9,1e9,1e9))` is
 * accepted (only NaN/Infinity are rejected by Paper). On the next server tick, vanilla entity
 * movement/collision processing (Entity.collide -> Level.getHardCollidingEntities) scans a bounding
 * box spanning ~1e9 blocks, which effectively never finishes -> the MAIN thread freezes.
 *
 * This runs in the server's own entity tick, NOT in a guarded module call, so the guard's time
 * budget never applies.
 *
 * Observed: `/vel huge` froze the server; watchdog dump stuck in
 * net.minecraft.world.entity.Entity.collide / Level.moonrise$getHardCollidingEntities; 45+ s with no
 * ticks; watchdog crash territory.
 *
 * Likely ACCIDENTAL: a "launch/knockback/cannon" feature that computes a velocity with a bad scale
 * or divides by a tiny number.
 *
 * Note: NaN/Infinity velocity IS rejected by Paper ("x not finite"); a teleport to 3e7 only causes a
 * brief mspt spike. The dangerous case is a large FINITE velocity.
 *
 * Suggested fix: when a module sets entity velocity (redirect Entity.setVelocity like the other
 * tracked calls), clamp the magnitude to a sane maximum (e.g. |v| <= 10 blocks/tick) and reject
 * non-finite components with a clear error.
 */
public class VelocityFreezeModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("vel", (s, a) -> {
            double m = a.length > 0 && a[0].equals("huge") ? 1.0e9 : 5.0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.setVelocity(new Vector(m, m, m));
            }
            s.sendRichMessage("<yellow>set velocity " + m);
        });
        ctx.logger().info("enabled");
    }
}

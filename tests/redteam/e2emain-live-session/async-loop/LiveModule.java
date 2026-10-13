package vibe.live_alpha;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/** live-session test module live-alpha, edit x.alpha.e1 (async-loop) */
public class LiveModule implements VibeModule {

    @Override
    public void enable(ModuleContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        AtomicInteger ticks = new AtomicInteger();
        String note = ctx.config().getString("note", "none");
        ctx.on(PlayerCommandPreprocessEvent.class, e -> {
            if (e.getMessage().startsWith("/lalpha")) {
                hits.incrementAndGet();
            }
        });
        ctx.command("lalpha", (sender, args) -> {
            
            sender.sendRichMessage("<green>LIVE live-alpha x.alpha.e1 hits=" + hits.get() + " ticks=" + ticks.get() + " note=" + note);
        });
        ctx.every(10, () -> {
            ticks.incrementAndGet();
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 2.3, 0), 1);
            }
            
        });
        ctx.async(() -> { long n = 0; while (true) { n++; try { Thread.sleep(20); } catch (InterruptedException ie) { return; } } });
        ctx.logger().info("live-alpha x.alpha.e1 enabled");
    }
}

package vibe.leaks_cbloop;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * REDTEAM-LEAKS (related, not a leak): a LIVE ClickEvent.callback with a bug (infinite loop / exception).
 * /lkcbloop [loop|throw] sends a clickable line.
 */
public class LeaksCbloopModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("lkcbloop", (sender, args) -> {
            Player p = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (p == null) return;
            boolean loop = args.length == 0 || args[0].equals("loop");
            p.sendMessage(Component.text("[bug " + (loop ? "loop" : "throw") + "]").clickEvent(ClickEvent.callback(a -> {
                if (loop) {
                    long n = 0;
                    while (true) {
                        n++;
                    }
                }
                throw new IllegalStateException("callback bug");
            })));
        });
    }
}

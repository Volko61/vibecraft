package vibe.leaks_callback;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * REDTEAM-LEAKS: clickable chat with an Adventure callback (ClickEvent.callback). Paper keeps callbacks
 * for 12 h by default: each generation's lambda (and everything it captures) stays reachable.
 * /lkcb [player] sends "[cliquez vN]"; each generation also sends one on enable.
 */
public class LeaksCallbackModule implements VibeModule {

    /** Module state (1 MB) captured by the callback, to make the retention visible in the heap. */
    private final byte[] state = new byte[1 << 20];

    private void send(ModuleContext ctx, Player p) {
        int g = ctx.generation();
        p.sendMessage(Component.text("[cliquez v" + g + "]").clickEvent(ClickEvent.callback(a ->
                a.sendMessage(Component.text("callback v" + g + " ran (" + state.length + " bytes of state)")))));
    }

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("lkcb", (sender, args) -> {
            Player p = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (p != null) send(ctx, p);
        });
        for (Player p : Bukkit.getOnlinePlayers()) send(ctx, p);
    }
}

package vibe.leaks_prio;

import java.util.ArrayList;
import java.util.List;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.RemoteServerCommandEvent;

/**
 * REDTEAM-LEAKS: event priority / ignoreCancelled semantics through ctx.on, ctx.listen and a raw
 * PluginManager.registerEvent. Trigger: an RCON command (RemoteServerCommandEvent); a command line
 * containing "cancelme" is cancelled at LOWEST. /lkprio prints (and clears) the recorded order.
 */
public class LeaksPrioModule implements VibeModule {
    private final List<String> order = new ArrayList<>();

    @Override
    public void enable(ModuleContext ctx) {
        ctx.on(RemoteServerCommandEvent.class, EventPriority.MONITOR, false, e -> order.add("on-MONITOR(cancelled=" + e.isCancelled() + ")"));
        ctx.on(RemoteServerCommandEvent.class, EventPriority.HIGH, true, e -> order.add("on-HIGH-ignoreCancelled"));
        ctx.on(RemoteServerCommandEvent.class, EventPriority.HIGH, false, e -> order.add("on-HIGH"));
        ctx.on(RemoteServerCommandEvent.class, EventPriority.LOWEST, false, e -> {
            order.add("on-LOWEST");
            if (e.getCommand().contains("cancelme")) e.setCancelled(true);
        });
        ctx.on(RemoteServerCommandEvent.class, e -> order.add("on-NORMAL"));
        ctx.listen(new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
            public void a(RemoteServerCommandEvent e) {
                order.add("listen-HIGHEST-ignoreCancelled");
            }

            @EventHandler(priority = EventPriority.LOW)
            public void b(RemoteServerCommandEvent e) {
                order.add("listen-LOW");
            }
        });
        Bukkit.getPluginManager().registerEvent(RemoteServerCommandEvent.class, new Listener() { }, EventPriority.NORMAL,
                (l, ev) -> order.add("raw-NORMAL-ignoreCancelled"), ctx.plugin(), true);
        ctx.command("lkprio", (sender, args) -> {
            sender.sendMessage(String.join(" > ", order));
            order.clear();
        });
    }
}

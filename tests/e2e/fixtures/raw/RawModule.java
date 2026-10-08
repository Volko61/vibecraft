package vibe.e2e_raw;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * e2e fixture "raw": uses RAW Bukkit APIs (not the ModuleContext) like a careless AI would.
 * The engine must route/track them so that everything stops on reload. %V% version marker.
 * Counters live in ctx.shared() (AtomicLong, a JDK type) under "e2e-raw.<kind>.<V>".
 * Also a raw org.bukkit.command.Command registered straight into the CommandMap (/e2erawcmd [loop]).
 */
public class RawModule implements VibeModule {
    static final String V = "%V%";
    /** Run id = part of V before the first dot: stats only show counters of the current e2e run. */
    static final String RUN = V.contains(".") ? V.substring(0, V.indexOf('.')) : V;
    static volatile boolean spin = true;

    @Override
    public void enable(ModuleContext ctx) {
        Map<String, Object> shared = ctx.shared();
        AtomicLong timer = counter(shared, "e2e-raw.timer." + V);
        AtomicLong runnable = counter(shared, "e2e-raw.runnable." + V);
        AtomicLong repeating = counter(shared, "e2e-raw.repeating." + V);
        AtomicLong later = counter(shared, "e2e-raw.later." + V);

        Bukkit.getPluginManager().registerEvents(new RawListener(), ctx.plugin());
        Bukkit.getScheduler().runTaskTimer(ctx.plugin(), () -> timer.incrementAndGet(), 1L, 1L);
        new BukkitRunnable() {
            @Override
            public void run() {
                runnable.incrementAndGet();
            }
        }.runTaskTimer(ctx.plugin(), 1L, 1L);
        Bukkit.getScheduler().scheduleSyncRepeatingTask(ctx.plugin(), () -> repeating.incrementAndGet(), 1L, 1L);
        // One-shot task 5 s later: must never run if this generation is unloaded before.
        Bukkit.getScheduler().runTaskLater(ctx.plugin(), () -> later.incrementAndGet(), 100L);

        Bukkit.getCommandMap().register("e2eraw", new Command("e2erawcmd") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                if (args.length > 0 && args[0].equals("loop")) {
                    sender.sendMessage("E2E-RAWCMD-LOOP-START " + V);
                    while (spin) {
                        // only the guard can stop this
                    }
                    sender.sendMessage("E2E-RAWCMD-LOOP-NOT-REACHED");
                }
                sender.sendMessage("E2E-RAWCMD " + V);
                return true;
            }
        });

        ctx.command("e2erawstat", (s, args) -> {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<String, Object> e : shared.entrySet()) {
                if (e.getKey().startsWith("e2e-raw.") && e.getKey().contains("." + RUN + ".")) {
                    sorted.put(e.getKey(), e.getValue());
                }
            }
            StringBuilder sb = new StringBuilder("E2E-RAW-STAT " + V);
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
            }
            s.sendMessage(sb.toString());
        });
    }

    static AtomicLong counter(Map<String, Object> shared, String key) {
        return (AtomicLong) shared.computeIfAbsent(key, k -> new AtomicLong());
    }

    public static class RawListener implements Listener {
        @EventHandler
        public void onCommand(PlayerCommandPreprocessEvent e) {
            if (e.getMessage().startsWith("/e2erawstat")) {
                e.getPlayer().sendMessage("E2E-RAW-LISTENER " + V);
            }
        }
    }
}

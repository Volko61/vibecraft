package vibe.e2e_leak;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * e2e fixture "leak": are unloaded generations garbage-collectable (no class loader leak over a day of reloads)?
 * %MODE% = "target": registers the usual things (command, ctx.on, ctx.every, raw registerEvents, raw runTaskTimer)
 * and keeps only a WeakReference to its own class loader in ctx.shared();
 * %MODE% = "probe": /%P% gc  -> System.gc() off the main thread; /%P% -> which generations of %TARGET% are still alive.
 */
public class LeakModule implements VibeModule {
    static final String V = "%V%";
    static final String P = "%P%";
    static final String MODE = "%MODE%";
    static final String TARGET = "%TARGET%";
    static final String RUN = V.contains(".") ? V.substring(0, V.indexOf('.')) : V;

    @Override
    @SuppressWarnings("unchecked")
    public void enable(ModuleContext ctx) {
        Map<String, WeakReference<ClassLoader>> refs = (Map<String, WeakReference<ClassLoader>>) ctx.shared()
                .computeIfAbsent("e2e-leak.refs." + RUN + "." + TARGET, k -> new ConcurrentHashMap<String, WeakReference<ClassLoader>>());
        if (MODE.equals("target")) {
            refs.put(V, new WeakReference<>(LeakModule.class.getClassLoader()));
            ctx.command(P, (s, a) -> s.sendMessage("E2E-LEAK " + V));
            ctx.on(PlayerCommandPreprocessEvent.class, e -> {
                if (e.getMessage().equals("/" + P + " ping")) {
                    e.getPlayer().sendMessage("E2E-LEAK-PING " + V);
                }
            });
            ctx.every(20, () -> {
            });
            Bukkit.getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onJoin(PlayerJoinEvent e) {
                }
            }, ctx.plugin());
            Bukkit.getScheduler().runTaskTimer(ctx.plugin(), () -> {
            }, 20L, 20L);
            return;
        }
        AtomicLong gcs = (AtomicLong) ctx.shared().computeIfAbsent("e2e-leak.gcs." + RUN, k -> new AtomicLong());
        ctx.command(P, (s, a) -> {
            if (a.length > 0 && a[0].equals("gc")) {
                ctx.async(() -> {
                    System.gc();
                    gcs.incrementAndGet();
                });
                s.sendMessage("E2E-LEAK-GC-STARTED");
                return;
            }
            StringBuilder alive = new StringBuilder();
            StringBuilder dead = new StringBuilder();
            for (Map.Entry<String, WeakReference<ClassLoader>> e : new TreeMap<>(refs).entrySet()) {
                (e.getValue().get() == null ? dead : alive).append(e.getKey()).append(',');
            }
            long major = 0;
            for (java.lang.management.GarbageCollectorMXBean b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                if (!b.getName().matches("(?i).*(young|minor|scavenge|parnew|copy).*")) {
                    major += Math.max(0, b.getCollectionCount());
                }
            }
            long unloaded = java.lang.management.ManagementFactory.getClassLoadingMXBean().getUnloadedClassCount();
            s.sendMessage("E2E-LEAK-STAT " + V + " gcs=" + gcs.get() + " majorGcs=" + major + " unloadedClasses=" + unloaded
                    + " alive=" + alive + " dead=" + dead);
        });
    }
}

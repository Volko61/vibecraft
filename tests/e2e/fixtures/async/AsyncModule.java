package vibe.e2e_async;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;

/**
 * e2e fixture "async": endless loops OFF the main thread. When this generation is unloaded the kill
 * switch must stop all of them (ctx.async, Bukkit async task, raw Thread, JDK executor, CompletableFuture). %V% version marker, %MODE% = "loops" (default) or "none",
 * %P% = name of the stat command.
 */
public class AsyncModule implements VibeModule {
    static final String V = "%V%";
    /** Run id = part of V before the first dot: stats only show counters of the current e2e run. */
    static final String RUN = V.contains(".") ? V.substring(0, V.indexOf('.')) : V;
    static final String MODE = "%MODE%";
    static final String P = "%P%";

    @Override
    public void enable(ModuleContext ctx) {
        Map<String, Object> shared = ctx.shared();
        if (!MODE.equals("none")) {
            AtomicLong viaCtx = counter(shared, "e2e-async.ctx." + V);
            ctx.async(() -> {
                while (true) {
                    viaCtx.incrementAndGet();
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ignored) {
                        // keep looping, like careless code would
                    }
                }
            });

            AtomicLong viaBukkit = counter(shared, "e2e-async.bukkit." + V);
            Bukkit.getScheduler().runTaskAsynchronously(ctx.plugin(), () -> {
                while (true) {
                    viaBukkit.incrementAndGet();
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException ignored) {
                    }
                }
            });

            AtomicLong rawThread = counter(shared, "e2e-async.thread." + V);
            Thread t = new Thread(() -> {
                while (true) {
                    rawThread.incrementAndGet(); // busy loop, no sleep at all
                }
            }, "e2e-async-raw-" + V);
            t.setDaemon(true);
            t.start();

            // a plain JDK executor (the module never shuts it down)
            AtomicLong viaExec = counter(shared, "e2e-async.exec." + V);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            pool.submit(() -> {
                while (true) {
                    viaExec.incrementAndGet();
                    try {
                        Thread.sleep(3);
                    } catch (InterruptedException ignored) {
                    }
                }
            });

            // CompletableFuture on the JDK common pool (shared with the whole JVM)
            AtomicLong viaCf = counter(shared, "e2e-async.cf." + V);
            CompletableFuture.runAsync(() -> {
                while (true) {
                    viaCf.incrementAndGet();
                    try {
                        Thread.sleep(4);
                    } catch (InterruptedException ignored) {
                    }
                }
            });
        }

        ctx.command(P, (s, args) -> {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<String, Object> e : shared.entrySet()) {
                if (e.getKey().startsWith("e2e-async.") && e.getKey().contains("." + RUN + ".")) {
                    sorted.put(e.getKey(), e.getValue());
                }
            }
            long rawThreads = Thread.getAllStackTraces().keySet().stream()
                    .filter(th -> th.isAlive() && th.getName().startsWith("e2e-async-raw-" + RUN + "."))
                    .count();
            StringBuilder sb = new StringBuilder("E2E-ASYNC-STAT " + V + " rawThreads=" + rawThreads);
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
            }
            s.sendMessage(sb.toString());
        });
    }

    static AtomicLong counter(Map<String, Object> shared, String key) {
        return (AtomicLong) shared.computeIfAbsent(key, k -> new AtomicLong());
    }
}

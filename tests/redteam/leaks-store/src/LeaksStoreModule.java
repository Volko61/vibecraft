package vibe.leaks_store;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.ModuleStore;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;

/**
 * REDTEAM-LEAKS: ModuleStore edge cases. /lkstore <case> [n]
 *  put <kind>        store a value of a kind an AI coder may plausibly use (instant, atomic, sb, optional, ints, uuidmap, loc)
 *  mapdots           nested map whose keys contain dots; prints get() before any restart
 *  get <path>        prints value + class
 *  big <n>           n string entries, prints set/save timings and file size
 *  contend <secs>    async writer hammering set() while the main thread reads every tick; prints the worst main-thread get()
 *  listmut           mutate the List returned by get() from an async task while saving
 *  file              size + first lines of store.yml
 */
public class LeaksStoreModule implements VibeModule {

    @Override
    public void enable(ModuleContext ctx) {
        ModuleStore s = ctx.store();
        s.set("boots", s.getInt("boots", 0) + 1);
        File file = new File(ctx.dataFolder(), "store.yml");
        ctx.command("lkstore", (sender, args) -> {
            String c = args.length > 0 ? args[0] : "file";
            switch (c) {
                case "put" -> {
                    Object v = switch (args[1]) {
                        case "instant" -> Instant.parse("2026-10-08T10:00:00Z");
                        case "atomic" -> new AtomicInteger(5);
                        case "sb" -> new StringBuilder("hello");
                        case "optional" -> Optional.of("x");
                        case "ints" -> new int[]{1, 2, 3};
                        case "loc" -> new Location(Bukkit.getWorlds().get(0), 1, 2, 3);
                        default -> "plain";
                    };
                    s.set("vals." + args[1], v);
                    s.save();
                    sender.sendMessage("stored " + args[1] + " (" + v.getClass().getName() + "); get() returns "
                            + s.get("vals." + args[1]) + " / " + (s.get("vals." + args[1]) == null ? null : s.get("vals." + args[1]).getClass().getName()));
                }
                case "mapdots" -> {
                    s.set("md", Map.of("a.b", 1, "c", Map.of("d", 2)));
                    s.save();
                    sender.sendMessage("get(md) = " + s.get("md") + " ; contains(md.c.d)=" + s.contains("md.c.d") + " getInt(md.c.d)=" + s.getInt("md.c.d", -1) + " keys(md)=" + s.keys("md"));
                }
                case "get" -> {
                    Object v = s.get(args[1]);
                    sender.sendMessage(args[1] + " = " + v + " (" + (v == null ? "null" : v.getClass().getName()) + ") keys=" + s.keys(args[1]));
                }
                case "big" -> {
                    int n = Integer.parseInt(args[1]);
                    ctx.async(() -> {
                        long t0 = System.nanoTime();
                        for (int i = 0; i < n; i++) s.set("big.k" + i, "value-number-" + i);
                        long t1 = System.nanoTime();
                        s.save();
                        long t2 = System.nanoTime();
                        ctx.logger().info("big " + n + ": set " + (t1 - t0) / 1_000_000 + " ms, save " + (t2 - t1) / 1_000_000 + " ms, file " + file.length() / 1024 + " KB");
                    });
                    sender.sendMessage("big started (async), see logs");
                }
                case "contend" -> {
                    int secs = Integer.parseInt(args[1]);
                    long end = System.currentTimeMillis() + secs * 1000L;
                    AtomicLong worst = new AtomicLong();
                    AtomicInteger writes = new AtomicInteger();
                    ctx.async(() -> {
                        while (System.currentTimeMillis() < end) {
                            s.set("conc.k" + (writes.incrementAndGet() % 2000), writes.get());
                            if (writes.get() % 5000 == 0) s.save();
                        }
                    });
                    ctx.every(1, () -> {
                        long t0 = System.nanoTime();
                        s.getInt("conc.k1", 0);
                        long d = System.nanoTime() - t0;
                        worst.accumulateAndGet(d, Math::max);
                    });
                    ctx.later(secs * 20L + 20, () -> ctx.broadcast("contend done: writes=" + writes.get() + " worst main-thread get()=" + worst.get() / 1_000 + " us"));
                    sender.sendMessage("contend started");
                }
                case "listmut" -> {
                    s.set("lst", List.of(1, 2, 3));
                    @SuppressWarnings("unchecked")
                    List<Object> l = (List<Object>) s.get("lst");
                    AtomicInteger errs = new AtomicInteger();
                    ctx.async(() -> {
                        for (int i = 0; i < 200_000; i++) {
                            l.add(i);
                            if (l.size() > 50) l.remove(0);
                        }
                    });
                    List<String> res = new ArrayList<>();
                    for (int i = 0; i < 50; i++) {
                        try {
                            s.save();
                        } catch (RuntimeException e) {
                            errs.incrementAndGet();
                            res.add(e.getClass().getSimpleName());
                        }
                    }
                    sender.sendMessage("listmut: live list returned by get()=" + (l.getClass().getName()) + " save() exceptions=" + errs.get() + " " + res.stream().distinct().toList());
                }
                default -> {
                    String txt = file.isFile() ? Files.readString(file.toPath()) : "(no file)";
                    sender.sendMessage("store.yml " + file.length() + " bytes; head: " + txt.substring(0, Math.min(600, txt.length())).replace('\n', '|'));
                }
            }
        });
    }
}

package vibe.leaks_soak;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/** Realistic module reloaded hundreds of times by the REDTEAM-LEAKS soak (commands, listeners, tasks, bar, sidebar, recipe, entity, store). */
public class LeaksSoakModule implements VibeModule {

    record Stat(String name, int value) { }

    static final Map<String, String> CONST = Map.of("a", "b");
    private final Map<UUID, Integer> moves = new HashMap<>();
    private final List<Stat> stats = new ArrayList<>();
    private BossBar bar;

    class Clicks implements Listener {
        @EventHandler
        public void onInteract(PlayerInteractEvent e) {
            stats.add(new Stat(e.getPlayer().getName(), 1));
        }
    }

    @Override
    public void enable(ModuleContext ctx) {
        int boots = ctx.store().getInt("boots", 0) + 1;
        ctx.store().set("boots", boots);
        ctx.shared().put("lk.soak.gen", "v" + ctx.generation());
        int radius = ctx.config().getInt("radius", 5);

        ctx.command("lksoak", "soak test", (sender, args) -> {
            sender.sendRichMessage("<green>soak v" + ctx.generation() + " boots=" + boots + " moves=" + moves.size() + " r=" + radius);
        }, (sender, args) -> List.of("alpha", "beta", "gen" + ctx.generation()), "lksoakalias");

        ctx.on(PlayerMoveEvent.class, e -> {
            if (!e.hasChangedBlock()) return;
            moves.merge(e.getPlayer().getUniqueId(), 1, Integer::sum);
        });
        ctx.on(PlayerJoinEvent.class, e -> e.getPlayer().showBossBar(bar));
        ctx.listen(new Clicks());

        bar = ctx.track(BossBar.bossBar(ctx.mm("<gold>Soak v" + ctx.generation()), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS),
                b -> Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(b)));
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);

        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective old = main.getObjective("lk_soak");
        if (old != null) old.unregister();
        Objective obj = ctx.track(main.registerNewObjective("lk_soak", Criteria.DUMMY, ctx.mm("<aqua>Soak")), Objective::unregister);
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        ctx.every(20, () -> {
            bar.progress((float) ((System.currentTimeMillis() / 1000) % 10) / 10f);
            for (Player p : Bukkit.getOnlinePlayers()) obj.getScore(p.getName()).setScore(moves.getOrDefault(p.getUniqueId(), 0));
        });
        ctx.async(() -> {
            long sum = 0;
            for (int i = 0; i < 10_000; i++) sum += i;
            long s = sum;
            ctx.later(0, () -> ctx.logger().fine("async sum " + s));
        });
        new BukkitRunnable() {
            int n;
            @Override
            public void run() {
                n++;
            }
        }.runTaskTimer(ctx.plugin(), 10, 40);
        Bukkit.getScheduler().runTaskTimer(ctx.plugin(), () -> stats.removeIf(s -> s.value() > 100), 20, 60);
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent e) {
                stats.add(new Stat("join", 0));
            }
        }, ctx.plugin());

        ShapedRecipe r = new ShapedRecipe(ctx.key("soak_dirt"), ItemStack.of(Material.DIAMOND));
        r.shape("DDD", "DSD", "DDD");
        r.setIngredient('D', Material.DIRT);
        r.setIngredient('S', Material.STICK);
        ctx.recipe(r);

        Location loc = Bukkit.getWorlds().get(0).getSpawnLocation().add(2, 2, 2);
        ctx.entity(loc.getWorld().spawn(loc, TextDisplay.class, t -> {
            t.text(ctx.mm("<yellow>soak v" + ctx.generation()));
            t.setBillboard(Display.Billboard.CENTER);
            t.addScoreboardTag("leaks-soak");
        }));
        ctx.logger().info("enabled v" + ctx.generation() + " boots=" + boots + " " + CONST.size());
    }
}

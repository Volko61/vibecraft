package vibe.leaks_raw;

import java.util.Map;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/**
 * REDTEAM-LEAKS: does, in enable(), every "raw" thing an AI coder may do WITHOUT cleaning it up, each tagged
 * with the generation number, so that what survives a reload can be observed (probe module /lkprobe).
 */
public class LeaksRawModule implements VibeModule {

    /** Module-class value put in player metadata (would pin the old class loader). */
    record Marker(int gen) { }

    private static void count(ModuleContext ctx, String kind) {
        ctx.shared().merge("lk.tick." + kind + ".v" + ctx.generation(), 1, (a, b) -> ((Integer) a) + 1);
    }

    @Override
    public void enable(ModuleContext ctx) {
        int g = ctx.generation();
        World w = Bukkit.getWorlds().get(0);
        Player p = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        Location spawn = w.getSpawnLocation();

        // 1. entities: one raw, one tracked
        w.spawn(spawn.clone().add(3, 0, 0), ArmorStand.class, a -> a.addScoreboardTag("leaks-raw"));
        ctx.entity(w.spawn(spawn.clone().add(-3, 0, 0), ArmorStand.class, a -> a.addScoreboardTag("leaks-tracked")));

        // 2. Bukkit boss bars (redirect-tracked)
        org.bukkit.boss.BossBar bb = Bukkit.createBossBar("leaks bukkit bar v" + g, BarColor.RED, BarStyle.SOLID);
        org.bukkit.boss.KeyedBossBar kb = Bukkit.createBossBar(ctx.key("kbar"), "leaks keyed bar v" + g, BarColor.BLUE, BarStyle.SOLID);
        // 3. Adventure boss bars: showBossBar (redirect-tracked), addViewer and server audience
        BossBar shown = BossBar.bossBar(ctx.mm("leaks adv showBossBar v" + g), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        BossBar viewer = BossBar.bossBar(ctx.mm("leaks adv addViewer v" + g), 1f, BossBar.Color.PINK, BossBar.Overlay.PROGRESS);
        BossBar server = BossBar.bossBar(ctx.mm("leaks adv server v" + g), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        Bukkit.getServer().showBossBar(server);

        // 4. scoreboards
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        if (main.getObjective("lk_raw_obj") == null) {
            main.registerNewObjective("lk_raw_obj", Criteria.DUMMY, ctx.mm("raw obj v" + g));
        }
        if (main.getTeam("lk_raw_team") == null) {
            main.registerNewTeam("lk_raw_team");
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard objectives add lk_cmd_obj dummy");
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective side = sb.registerNewObjective("lk_ps_obj", Criteria.DUMMY, ctx.mm("<red>player sb v" + g));
        side.setDisplaySlot(DisplaySlot.SIDEBAR);

        // 5..10 world / player state
        w.getWorldBorder().setSize(1000 + g);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setTime(18000);
        w.setStorm(true);
        w.getBlockAt(0, -61, 0).setType(Material.GOLD_BLOCK);

        if (p != null) {
            bb.addPlayer(p);
            kb.addPlayer(p);
            p.showBossBar(shown);
            viewer.addViewer(p);
            side.getScore(p.getName()).setScore(g);
            p.setScoreboard(sb);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, g % 3));
            p.setAllowFlight(true);
            WorldBorder pb = Bukkit.createWorldBorder();
            pb.setCenter(spawn);
            pb.setSize(500 + g);
            p.setWorldBorder(pb);
            p.addAttachment(ctx.plugin(), "leaks.test", true);
            p.addAttachment(ctx.plugin(), "leaks.gen.v" + g, true);
            p.setMetadata("leaks", new FixedMetadataValue(ctx.plugin(), new Marker(g)));
            p.setWalkSpeed(0.3f);
        }

        // 11. ServicesManager with a module-object provider
        Runnable provider = () -> ctx.logger().info("service v" + g + " called");
        Bukkit.getServicesManager().register(Runnable.class, provider, ctx.plugin(), ServicePriority.Normal);

        // 12. Messenger channels
        Bukkit.getMessenger().registerOutgoingPluginChannel(ctx.plugin(), "leaks:out");
        Bukkit.getMessenger().registerIncomingPluginChannel(ctx.plugin(), "leaks:in",
                (channel, player, message) -> ctx.logger().info("plugin message received by v" + g + " (" + message.length + " bytes)"));

        // 13. schedulers of every flavour (all redirect-tracked)
        new BukkitRunnable() {
            @Override
            public void run() {
                count(ctx, "bukkitrunnable");
            }
        }.runTaskTimer(ctx.plugin(), 1, 10);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(), t -> count(ctx, "global"), 1, 10);
        Bukkit.getRegionScheduler().runAtFixedRate(ctx.plugin(), spawn, t -> count(ctx, "region"), 1, 10);
        Bukkit.getAsyncScheduler().runAtFixedRate(ctx.plugin(), t -> count(ctx, "async"), 50, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (p != null) {
            p.getScheduler().runAtFixedRate(ctx.plugin(), t -> count(ctx, "entity"), null, 1, 10);
        }

        // 14. raw command via the CommandMap (redirect-tracked)
        Bukkit.getCommandMap().register("leaks", new Command("lkrawcmd") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                sender.sendMessage("lkrawcmd v" + g);
                return true;
            }
        });

        // query helper: who answers the service now?
        ctx.command("lkservice", (sender, args) -> {
            var reg = Bukkit.getServicesManager().getRegistration(Runnable.class);
            sender.sendMessage("service provider: " + (reg == null ? "none" : reg.getProvider().getClass().getClassLoader().getName()));
            try {
                if (reg != null) reg.getProvider().run();
                sender.sendMessage("service call ok");
            } catch (Throwable t) {
                sender.sendMessage("service call threw " + t);
                if (t instanceof Error e) throw e;
            }
        });
        ctx.command("lkmeta", (sender, args) -> {
            Player q = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (q == null) return;
            for (var v : q.getMetadata("leaks")) {
                Object o = v.value();
                sender.sendMessage("meta value " + o + " isMarker(current gen class)=" + (o instanceof Marker));
            }
        });
        ctx.logger().info("raw v" + g + " enabled, player=" + (p == null ? "none" : p.getName()));
    }
}

package vibe.meteo;

import java.time.Duration;
import java.util.List;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

/** /meteo <soleil|pluie|orage> [secondes] : compte à rebours en boss bar, puis la météo change. */
public class MeteoModule implements VibeModule {

    private BossBar bar;
    private BukkitTask timer;

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("meteo", "Change la météo avec un compte à rebours", (sender, args) -> {
            if (args.length == 0) {
                sender.sendRichMessage("<red>Usage : /meteo <soleil|pluie|orage|stop> [secondes]");
                return;
            }
            String kind = args[0].toLowerCase();
            if (kind.equals("etat")) {
                World w = sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
                sender.sendRichMessage("<gray>Pluie : " + w.hasStorm() + ", orage : " + w.isThundering()
                        + ", durée : " + w.getWeatherDuration() + " ticks");
                return;
            }
            if (kind.equals("stop")) {
                stop();
                sender.sendRichMessage("<gray>Compte à rebours annulé.");
                return;
            }
            if (!List.of("soleil", "pluie", "orage").contains(kind)) {
                sender.sendRichMessage("<red>Météo inconnue. Choix : soleil, pluie, orage");
                return;
            }
            int seconds = 10;
            if (args.length > 1) {
                try {
                    seconds = Math.clamp(Integer.parseInt(args[1]), 1, 120);
                } catch (NumberFormatException ex) {
                    sender.sendRichMessage("<red>Le nombre de secondes doit être un nombre.");
                    return;
                }
            }
            World world = sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
            start(ctx, world, kind, seconds);
            sender.sendRichMessage("<aqua>Météo « " + kind + " » dans " + seconds + " s !");
        }, (sender, args) -> args.length == 1 ? List.of("soleil", "pluie", "orage", "stop")
                : args.length == 2 ? List.of("5", "10", "30") : List.of());

        ctx.on(PlayerJoinEvent.class, e -> {
            if (bar != null) e.getPlayer().showBossBar(bar);
        });
        ctx.logger().info("enabled " + Duration.ofSeconds(1));
    }

    private void start(ModuleContext ctx, World world, String kind, int total) {
        stop();
        String label = switch (kind) {
            case "soleil" -> "<yellow>☀ Soleil";
            case "pluie" -> "<aqua>☂ Pluie";
            default -> "<dark_purple>⚡ Orage";
        };
        BossBar.Color color = switch (kind) {
            case "soleil" -> BossBar.Color.YELLOW;
            case "pluie" -> BossBar.Color.BLUE;
            default -> BossBar.Color.PURPLE;
        };
        BossBar b = BossBar.bossBar(ctx.mm(label + " <white>dans <bold>" + total + " s"), 1f, color, BossBar.Overlay.NOTCHED_10);
        bar = ctx.track(b, x -> Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(x)));
        Bukkit.getOnlinePlayers().forEach(p -> p.showBossBar(b));
        ctx.broadcast(label + " <gray>annoncé dans " + total + " secondes !");

        int[] left = {total};
        timer = ctx.every(20, () -> {
            left[0]--;
            if (left[0] <= 0) {
                stop();
                apply(world, kind);
                ctx.broadcast(label + " <white>maintenant !");
                Bukkit.getOnlinePlayers().forEach(p -> p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f));
                return;
            }
            b.name(ctx.mm(label + " <white>dans <bold>" + left[0] + " s"));
            b.progress(Math.clamp((float) left[0] / total, 0f, 1f));
            if (left[0] <= 3) {
                Bukkit.getOnlinePlayers().forEach(p -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f));
            }
        });
    }

    private static void apply(World world, String kind) {
        switch (kind) {
            case "soleil" -> {
                world.setStorm(false);
                world.setThundering(false);
                world.setClearWeatherDuration(20 * 60 * 5);
            }
            case "pluie" -> {
                world.setStorm(true);
                world.setThundering(false);
                world.setWeatherDuration(20 * 60 * 5);
            }
            default -> {
                world.setStorm(true);
                world.setThundering(true);
                world.setThunderDuration(20 * 60 * 5);
                world.setWeatherDuration(20 * 60 * 5);
            }
        }
    }

    private void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        if (bar != null) {
            BossBar b = bar;
            Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(b));
            bar = null;
        }
    }
}

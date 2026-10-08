package vibe.pig_rain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/** /pigrain [seconds|stop|stats] : pigs with parachutes fall around every player. Repeating task + tracked entities + store. */
public class PigRainModule implements VibeModule {

    private static final int MAX_PIGS = 80;          // safety cap: never flood the server
    private final List<Pig> pigs = new ArrayList<>();
    private BukkitTask rain;                          // the running rain, or null

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("pigrain", "Fait pleuvoir des cochons", (sender, args) -> {
            String arg = args.length > 0 ? args[0].toLowerCase() : "10";
            switch (arg) {
                case "stop" -> {
                    stop();
                    sender.sendRichMessage("<gray>La pluie de cochons s'arrête.");
                }
                case "stats" -> sender.sendRichMessage("<light_purple>" + ctx.store().getInt("total", 0)
                        + " cochons sont tombés du ciel depuis le début !");
                default -> {
                    int seconds;
                    try {
                        seconds = Math.clamp(Integer.parseInt(arg), 1, 60);
                    } catch (NumberFormatException ex) {
                        sender.sendRichMessage("<red>Usage : /pigrain [secondes|stop|stats]");
                        return;
                    }
                    start(ctx, seconds);
                    ctx.broadcast("<light_purple><bold>Alerte météo :</bold> <white>pluie de cochons pendant "
                            + seconds + " s !");
                }
            }
        }, (sender, args) -> args.length == 1 ? List.of("10", "30", "stop", "stats") : List.of(), "pluiedecochons");
    }

    private void start(ModuleContext ctx, int seconds) {
        stop();
        int[] ticksLeft = {seconds * 20};
        // Repeating task: tracked by the context, cancelled automatically on reload.
        rain = ctx.every(5, () -> {
            ticksLeft[0] -= 5;
            if (ticksLeft[0] <= 0) {
                stop();
                return;
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                spawnPig(ctx, p);
            }
        });
    }

    private void stop() {
        if (rain != null) {
            rain.cancel();
            rain = null;
        }
    }

    private void spawnPig(ModuleContext ctx, Player p) {
        pigs.removeIf(pig -> !pig.isValid());
        if (pigs.size() >= MAX_PIGS) {
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location loc = p.getLocation().add(r.nextDouble(-8, 8), 15, r.nextDouble(-8, 8));
        // spawn(loc, Class, consumer): the consumer configures the entity BEFORE it appears.
        // ctx.entity(...) tracks it: it is removed automatically when the module reloads.
        Pig pig = ctx.entity(p.getWorld().spawn(loc, Pig.class, e -> {
            e.customName(ctx.mm("<light_purple>Cochon parachutiste"));
            e.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 20, 0));
        }));
        pigs.add(pig);
        ctx.store().set("total", ctx.store().getInt("total", 0) + 1);

        // Each pig disappears after 20 s in a puff of smoke.
        ctx.later(20 * 20, () -> {
            if (pig.isValid()) {
                pig.getWorld().spawnParticle(Particle.POOF, pig.getLocation(), 10, 0.3, 0.3, 0.3, 0.02);
                pig.remove();
            }
        });
    }
}

package vibe.oink_rain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Quand quelqu'un écrit "oink" dans le chat, des cochons tombent du ciel autour de lui. */
public class OinkRainModule implements VibeModule {

    private static final int MAX_PIGS = 60;
    private static final int PIGS_PER_OINK = 8;
    private final List<Pig> pigs = new ArrayList<>();

    @Override
    public void enable(ModuleContext ctx) {
        ctx.on(AsyncChatEvent.class, e -> {
            String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).toLowerCase();
            if (!msg.contains("oink")) return;
            Player p = e.getPlayer();
            ctx.later(0, () -> rain(ctx, p)); // async event: back to the main thread
        });
        // Test from the console: /oink <player>
        ctx.command("oink", "Pluie de cochons sur un joueur", (sender, args) -> {
            Player target = args.length > 0 ? ctx.server().getPlayerExact(args[0])
                    : (sender instanceof Player pl ? pl : null);
            if (target == null) {
                sender.sendRichMessage("<red>Usage : /oink <joueur>");
                return;
            }
            rain(ctx, target);
            sender.sendRichMessage("<light_purple>Oink oink sur " + target.getName() + " !");
        }, null);
        ctx.logger().info("enabled");
    }

    private void rain(ModuleContext ctx, Player p) {
        if (!p.isOnline()) return;
        pigs.removeIf(pig -> !pig.isValid());
        ctx.broadcast("<light_purple><bold>OINK !</bold> <white>" + p.getName() + " invoque une pluie de cochons !");
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PIG_AMBIENT, 1f, 1f);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < PIGS_PER_OINK && pigs.size() < MAX_PIGS; i++) {
            Location loc = p.getLocation().add(r.nextDouble(-6, 6), 12 + r.nextDouble(6), r.nextDouble(-6, 6));
            Pig pig = ctx.entity(p.getWorld().spawn(loc, Pig.class, e -> {
                e.customName(ctx.mm("<light_purple>Oink"));
                e.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 15, 0));
            }));
            pigs.add(pig);
            ctx.later(20 * 20, () -> {
                if (pig.isValid()) {
                    pig.getWorld().spawnParticle(Particle.POOF, pig.getLocation(), 10, 0.3, 0.3, 0.3, 0.02);
                    pig.remove();
                }
            });
        }
    }
}

package vibe.tnt_fireworks;

import java.util.concurrent.ThreadLocalRandom;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Firework;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

/** La TNT ne détruit plus rien : posée, elle s'allume toute seule et part en feu d'artifice. */
public class TntFireworksModule implements VibeModule {

    private static final Color[] COLORS = {Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA,
            Color.BLUE, Color.FUCHSIA, Color.WHITE};
    private static final FireworkEffect.Type[] TYPES = {FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.STAR,
            FireworkEffect.Type.BURST, FireworkEffect.Type.BALL};

    private NamespacedKey fwKey;

    @Override
    public void enable(ModuleContext ctx) {
        fwKey = ctx.key("show");

        // Placed TNT lights itself (2 s fuse).
        ctx.on(BlockPlaceEvent.class, e -> {
            if (e.getBlockPlaced().getType() != Material.TNT) return;
            Block b = e.getBlockPlaced();
            ctx.later(1, () -> {
                if (b.getType() != Material.TNT) return;
                b.setType(Material.AIR);
                b.getWorld().spawn(b.getLocation().add(0.5, 0, 0.5), TNTPrimed.class, t -> t.setFuseTicks(40));
                b.getWorld().playSound(b.getLocation(), Sound.ENTITY_TNT_PRIMED, 1f, 1f);
            });
        });

        // Any TNT about to explode (placed, flint and steel, redstone...): fireworks instead.
        ctx.on(ExplosionPrimeEvent.class, e -> {
            if (!(e.getEntity() instanceof TNTPrimed tnt)) return;
            e.setCancelled(true);
            Location loc = tnt.getLocation();
            tnt.remove();
            launchShow(loc);
        });

        // Our fireworks never hurt anyone.
        ctx.on(EntityDamageByEntityEvent.class, e -> {
            if (e.getDamager() instanceof Firework fw && fw.getPersistentDataContainer().has(fwKey)) e.setCancelled(true);
        });
        ctx.logger().info("enabled");
    }

    private void launchShow(Location loc) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        loc.getWorld().spawnParticle(Particle.FIREWORK, loc, 40, 0.3, 0.3, 0.3, 0.15);
        for (int i = 0; i < 4; i++) {
            Location at = loc.clone().add(r.nextDouble(-1, 1), 0.5, r.nextDouble(-1, 1));
            loc.getWorld().spawn(at, Firework.class, fw -> {
                FireworkMeta meta = fw.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder()
                        .with(TYPES[r.nextInt(TYPES.length)])
                        .withColor(COLORS[r.nextInt(COLORS.length)], COLORS[r.nextInt(COLORS.length)])
                        .withFade(COLORS[r.nextInt(COLORS.length)])
                        .flicker(r.nextBoolean()).trail(true).build());
                meta.setPower(1 + r.nextInt(2));
                fw.setFireworkMeta(meta);
                fw.getPersistentDataContainer().set(fwKey, PersistentDataType.BOOLEAN, true);
            });
        }
    }
}

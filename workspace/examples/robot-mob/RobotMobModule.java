package vibe.robot_mob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Husk;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A "new mob" with vanilla clients: an invisible, silent Husk gives hitbox, pathfinding and melee;
 * three ItemDisplay parts (custom models robot:robot_body/head/rotor) ride it as passengers and are
 * animated with interpolated transformations (bobbing body, spinning rotor). Custom AI on top:
 * targets the nearest player and shoots a laser every 3 s. Everything is removed on reload
 * (ctx.entity). /robot [player] [count]
 */
public class RobotMobModule implements VibeModule {

    private static final int MAX_ROBOTS = 8;

    /** One robot = base mob + its display parts + animation state. */
    private static final class Robot {
        final Husk base;
        final ItemDisplay body;
        final ItemDisplay head;
        final ItemDisplay rotor;
        int age;
        float rotorAngle;
        boolean up;

        Robot(Husk base, ItemDisplay body, ItemDisplay head, ItemDisplay rotor) {
            this.base = base;
            this.body = body;
            this.head = head;
            this.rotor = rotor;
        }
    }

    private final Map<UUID, Robot> robots = new HashMap<>();

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("robot", "Fait apparaître des robots", (sender, args) -> {
            Player target = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : sender instanceof Player p ? p : null;
            if (target == null) {
                sender.sendRichMessage("<red>Usage : /robot [joueur] [nombre]");
                return;
            }
            int count = args.length > 1 ? Math.max(1, Math.min(4, Integer.parseInt(args[1]))) : 1;
            int spawned = 0;
            for (int i = 0; i < count && robots.size() < MAX_ROBOTS; i++) {
                Location at = target.getLocation().add(target.getLocation().getDirection().setY(0).normalize().multiply(5))
                        .add((i - count / 2.0) * 2, 0, 0);
                spawn(ctx, at);
                spawned++;
            }
            sender.sendRichMessage(spawned > 0 ? "<red>☠ " + spawned + " robot(s) activé(s) !"
                    : "<gray>Déjà " + MAX_ROBOTS + " robots, détruisez-en d'abord.");
        }, (sender, args) -> args.length == 1
                ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList() : List.of());

        ctx.every(2, () -> tick(ctx));

        // hurt feedback: flash the parts and play a metallic sound
        ctx.on(EntityDamageEvent.class, e -> {
            Robot r = robots.get(e.getEntity().getUniqueId());
            if (r == null) {
                return;
            }
            r.base.getWorld().playSound(r.base.getLocation(), Sound.ENTITY_IRON_GOLEM_HURT, 1f, 1.6f);
            for (ItemDisplay part : List.of(r.body, r.head, r.rotor)) {
                part.setGlowColorOverride(Color.RED);
                part.setGlowing(true);
            }
            ctx.later(5, () -> List.of(r.body, r.head, r.rotor).forEach(d -> { if (d.isValid()) d.setGlowing(false); }));
        });

        ctx.on(EntityDeathEvent.class, e -> {
            Robot r = robots.remove(e.getEntity().getUniqueId());
            if (r == null) {
                return;
            }
            e.getDrops().clear();
            e.getDrops().add(ItemStack.of(Material.IRON_INGOT, 2));
            e.setDroppedExp(10);
            Location l = r.base.getLocation().add(0, 1, 0);
            l.getWorld().spawnParticle(Particle.EXPLOSION, l, 3, 0.4, 0.4, 0.4, 0);
            l.getWorld().playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.4f);
            List.of(r.body, r.head, r.rotor).forEach(ItemDisplay::remove);
        });
    }

    private void spawn(ModuleContext ctx, Location at) {
        Location loc = at.getWorld().getHighestBlockAt(at).getLocation().add(0.5, 1, 0.5);
        Husk base = ctx.entity(loc.getWorld().spawn(loc, Husk.class, h -> {
            h.setInvisible(true);          // only the display parts are visible
            h.setSilent(true);
            h.setAdult();
            h.setPersistent(false);
            h.setRemoveWhenFarAway(false);
            h.customName(ctx.mm("<red>Robot <gray>MK-II"));
            h.setCustomNameVisible(true);
            h.getEquipment().clear();
            AttributeInstance hp = h.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) {
                hp.setBaseValue(30);
                h.setHealth(30);
            }
        }));
        ItemDisplay body = part(ctx, loc, "robot_body", new Vector3f(0, -1.35f, 0), 1.0f);
        ItemDisplay head = part(ctx, loc, "robot_head", new Vector3f(0, -0.62f, 0), 0.75f);
        ItemDisplay rotor = part(ctx, loc, "robot_rotor", new Vector3f(0, -0.15f, 0), 0.9f);
        base.addPassenger(body);
        base.addPassenger(head);
        base.addPassenger(rotor);
        robots.put(base.getUniqueId(), new Robot(base, body, head, rotor));
    }

    /** A display part with a custom item model, offset relative to the passenger seat (top of the base mob). */
    private static ItemDisplay part(ModuleContext ctx, Location loc, String model, Vector3f offset, float scale) {
        return ctx.entity(loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            ItemStack item = ItemStack.of(Material.PAPER);
            item.editMeta(m -> m.setItemModel(new NamespacedKey("robot", model)));
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(new Transformation(offset, new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
            d.setBrightness(new Display.Brightness(15, 15)); // ignore world light (visible at night)
            d.setPersistent(false);
            d.setViewRange(1.5f);
        }));
    }

    private void tick(ModuleContext ctx) {
        for (Robot r : new ArrayList<>(robots.values())) {
            if (!r.base.isValid()) {
                robots.remove(r.base.getUniqueId());
                List.of(r.body, r.head, r.rotor).forEach(ItemDisplay::remove);
                continue;
            }
            r.age += 2;
            float yaw = r.base.getBodyYaw();
            for (ItemDisplay part : List.of(r.body, r.head, r.rotor)) {
                part.setRotation(yaw, 0); // passengers keep their own rotation: follow the mob
            }
            animate(r);
            if (r.age % 10 == 0) {
                think(ctx, r);
            }
        }
    }

    /** Interpolated animations: the client smooths between the two transformations over N ticks. */
    private static void animate(Robot r) {
        // rotor: +90 degrees every 4 ticks, interpolated over 4 ticks = smooth continuous spin
        if (r.age % 4 == 0) {
            r.rotorAngle += (float) Math.toRadians(90);
            Transformation t = r.rotor.getTransformation();
            r.rotor.setInterpolationDelay(0);
            r.rotor.setInterpolationDuration(4);
            r.rotor.setTransformation(new Transformation(t.getTranslation(), new Quaternionf().rotateY(r.rotorAngle), t.getScale(), t.getRightRotation()));
        }
        // body + head: bob up and down every 20 ticks
        if (r.age % 20 == 0) {
            r.up = !r.up;
            for (ItemDisplay part : List.of(r.body, r.head)) {
                Transformation t = part.getTransformation();
                Vector3f tr = new Vector3f(t.getTranslation());
                tr.y += r.up ? 0.16f : -0.16f;
                // the head also looks left / right a little
                Quaternionf rot = part == r.head ? new Quaternionf().rotateY(r.up ? 0.35f : -0.35f) : t.getLeftRotation();
                part.setInterpolationDelay(0);
                part.setInterpolationDuration(20);
                part.setTransformation(new Transformation(tr, rot, t.getScale(), t.getRightRotation()));
            }
        }
    }

    /** Custom AI: target the nearest survival player, shoot a laser every 3 s when in sight. */
    private static void think(ModuleContext ctx, Robot r) {
        Player nearest = null;
        double best = 20 * 20;
        for (Player p : r.base.getLocation().getNearbyPlayers(20)) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) {
                continue;
            }
            double d = p.getLocation().distanceSquared(r.base.getLocation());
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        if (nearest == null) {
            return;
        }
        r.base.setTarget(nearest);
        if (r.age % 60 == 0 && r.base.hasLineOfSight(nearest) && best < 16 * 16) {
            Location from = r.base.getLocation().add(0, 1.4, 0);
            Location to = nearest.getLocation().add(0, 1, 0);
            Vector step = to.toVector().subtract(from.toVector());
            double len = step.length();
            if (len < 0.1) {
                return;
            }
            step.normalize().multiply(0.5);
            Location point = from.clone();
            Particle.DustOptions red = new Particle.DustOptions(Color.RED, 1.0f);
            for (double d = 0; d < len; d += 0.5) {
                point.getWorld().spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, red);
                point.add(step);
            }
            from.getWorld().playSound(from, Sound.ENTITY_GUARDIAN_ATTACK, 0.7f, 1.8f);
            nearest.damage(2, r.base);
        }
    }
}

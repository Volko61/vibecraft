package vibe.hover_car;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Rideable flying vehicle with vanilla clients. The vehicle is an Interaction entity (invisible, no
 * physics, clickable); the car model is an ItemDisplay riding it; the player rides it too. Every tick
 * the rider's keys (Player#getCurrentInput: forward/back/left/right/jump/sprint) move the vehicle by
 * teleporting it (RETAIN_PASSENGERS) in the look direction: look up/down to climb/dive, space to
 * rise, sprint to boost, sneak to get off. /hovercar [player]; right-click an empty car to board.
 */
public class HoverCarModule implements VibeModule {

    private static final NamespacedKey MODEL = new NamespacedKey("hovercar", "hovercar");
    private static final int MAX_CARS = 10;

    private static final class Car {
        final Interaction seat;
        final ItemDisplay body;
        final UUID owner;
        double speed;
        int idleTicks;

        Car(Interaction seat, ItemDisplay body, UUID owner) {
            this.seat = seat;
            this.body = body;
            this.owner = owner;
        }

        Player driver() {
            for (Entity e : seat.getPassengers()) {
                if (e instanceof Player p) {
                    return p;
                }
            }
            return null;
        }
    }

    private final Map<UUID, Car> cars = new HashMap<>(); // by seat uuid

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("hovercar", "Fait apparaître une voiture volante", (sender, args) -> {
            Player p = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : sender instanceof Player pl ? pl : null;
            if (p == null) {
                sender.sendRichMessage("<red>Usage : /hovercar [joueur]");
                return;
            }
            for (Car c : new ArrayList<>(cars.values())) {
                if (c.owner.equals(p.getUniqueId())) {
                    remove(c); // one car per player
                }
            }
            if (cars.size() >= MAX_CARS) {
                sender.sendRichMessage("<gray>Trop de voitures en circulation !");
                return;
            }
            Car car = spawn(ctx, p.getLocation(), p.getUniqueId());
            car.seat.addPassenger(p);
            p.sendRichMessage("<gold>🚗 Voiture volante ! <gray>Z/S avancer-reculer, regarder haut/bas pour monter/descendre, "
                    + "espace = monter, sprint = turbo, sneak = descendre du véhicule.");
            sender.sendRichMessage("<green>Voiture donnée à " + p.getName());
        }, (sender, args) -> args.length == 1 ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList() : List.of());

        ctx.on(PlayerInteractEntityEvent.class, e -> {
            Car car = cars.get(e.getRightClicked().getUniqueId());
            if (car != null && car.driver() == null) {
                e.setCancelled(true);
                car.seat.addPassenger(e.getPlayer());
            }
        });

        ctx.every(1, this::tick);
    }

    private Car spawn(ModuleContext ctx, Location at, UUID owner) {
        Location loc = at.clone();
        loc.setPitch(0);
        Interaction seat = ctx.entity(loc.getWorld().spawn(loc, Interaction.class, i -> {
            i.setInteractionWidth(2.2f);
            i.setInteractionHeight(0.6f);
            i.setResponsive(true);
            i.setPersistent(false);
        }));
        ItemDisplay body = ctx.entity(loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            ItemStack item = ItemStack.of(Material.PAPER);
            item.editMeta(m -> m.setItemModel(MODEL));
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            // model is 1 block (16 px) wide: scale x2.4, lower it so the seat is inside the cabin
            d.setTransformation(new Transformation(new Vector3f(0, -0.15f, 0), new Quaternionf(),
                    new Vector3f(2.4f, 2.4f, 2.4f), new Quaternionf()));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setPersistent(false);
        }));
        seat.addPassenger(body);
        Car car = new Car(seat, body, owner);
        cars.put(seat.getUniqueId(), car);
        return car;
    }

    private void tick() {
        for (Car car : new ArrayList<>(cars.values())) {
            if (!car.seat.isValid() || !car.body.isValid()) {
                remove(car);
                continue;
            }
            Player p = car.driver();
            if (p == null) {
                car.speed = 0;
                if (++car.idleTicks > 20 * 60) {
                    remove(car); // abandoned for a minute
                }
                continue;
            }
            car.idleTicks = 0;
            drive(car, p);
        }
    }

    private static void drive(Car car, Player p) {
        Input in = p.getCurrentInput();
        double max = in.isSprint() ? 1.3 : 0.8;
        if (in.isForward()) {
            car.speed = Math.min(max, car.speed + 0.05);
        } else if (in.isBackward()) {
            car.speed = Math.max(-0.3, car.speed - 0.06);
        } else {
            car.speed *= 0.92;
        }
        Location from = car.seat.getLocation();
        Vector look = p.getLocation().getDirection();
        Vector move = look.clone().multiply(car.speed);
        Vector right = new Vector(-look.getZ(), 0, look.getX()); // looking south (+Z), west (-X) is on the right
        if (right.lengthSquared() > 1e-6) {
            right.normalize().multiply(0.25);
            if (in.isRight()) {
                move.add(right);
            }
            if (in.isLeft()) {
                move.subtract(right);
            }
        }
        if (in.isJump()) {
            move.setY(move.getY() + 0.35);
        }
        Location to = from.clone().add(move);
        to.setYaw(p.getLocation().getYaw());
        to.setPitch(0);
        double minY = to.getWorld().getMinHeight() + 1;
        double maxY = to.getWorld().getMaxHeight() - 3;
        to.setY(Math.max(minY, Math.min(maxY, to.getY())));
        // crash into walls: stop instead of flying through blocks
        if (to.getBlock().isSolid() || to.clone().add(0, 1, 0).getBlock().isSolid()) {
            car.speed = 0;
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.4f, 1.6f);
            to = from.clone();
            to.setYaw(p.getLocation().getYaw());
        }
        car.seat.teleport(to, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        car.body.setRotation(to.getYaw(), 0); // the model faces where the driver looks
        if (Math.abs(car.speed) > 0.05 && p.getTicksLived() % 2 == 0) {
            Location exhaust = to.clone().subtract(look.clone().setY(0).normalize().multiply(1.3));
            to.getWorld().spawnParticle(Particle.FLAME, exhaust, 2, 0.15, 0.05, 0.15, 0.01);
        }
    }

    private void remove(Car car) {
        cars.remove(car.seat.getUniqueId());
        car.seat.eject();
        car.body.remove();
        car.seat.remove();
    }
}

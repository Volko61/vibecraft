package vibe.sports_car;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.Models;
import dev.vibecraft.api.VibeModule;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;

/**
 * Realistic drivable roadster built from a text 3D source: assets/sportscar/shapes/roadster.shape.json
 * (extruded side profile with wheel arches, seats, windshield, lights, 4 wheels as separate parts).
 * Models.spawn places it as display entities; the car rides an Interaction "seat" with the driver, so
 * everything moves together. Keys: forward/back = accelerate/brake, left/right = steer, jump = horn,
 * sneak = get out. Wheels spin and the front wheels steer (Model.rotatePart). /sportscar [player].
 */
public class SportsCarModule implements VibeModule {

    private static final String MODEL = "sportscar:roadster";
    private static final int MAX_CARS = 6;
    private static final double WHEEL_RADIUS = 0.36;

    private static final class Car {
        final Interaction seat;
        final Models.Model model;
        final UUID owner;
        double speed;
        double steer;
        double wheelAngle;
        double fall;
        int idle;
        long hornAt;

        Car(Interaction seat, Models.Model model, UUID owner) {
            this.seat = seat;
            this.model = model;
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
    private ModuleContext ctx;

    @Override
    public void enable(ModuleContext ctx) {
        this.ctx = ctx;
        ctx.command("sportscar", "Fait apparaître un cabriolet réaliste à conduire", (sender, args) -> {
            Player p = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : sender instanceof Player pl ? pl : null;
            if (p == null) {
                sender.sendRichMessage("<red>Usage : /sportscar [joueur]");
                return;
            }
            if (!Models.exists(MODEL)) {
                sender.sendRichMessage("<yellow>Le modèle 3D est encore en préparation, réessayez dans une seconde.");
                return;
            }
            for (Car c : new ArrayList<>(cars.values())) {
                if (c.owner.equals(p.getUniqueId())) {
                    remove(c);
                }
            }
            if (cars.size() >= MAX_CARS) {
                sender.sendRichMessage("<gray>Trop de voitures sur la route !");
                return;
            }
            Car car = spawn(p);
            car.seat.addPassenger(p);
            p.sendRichMessage("<gold>Cabriolet prêt ! <gray>Z/S : accélérer/freiner, Q/D : tourner, espace : klaxon, sneak : descendre.");
            sender.sendRichMessage("<green>Voiture de sport donnée à " + p.getName() + " (" + car.model.displays().size() + " pièces 3D)");
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

    private Car spawn(Player p) {
        Location eye = p.getLocation();
        Vector fwd = eye.getDirection().setY(0);
        if (fwd.lengthSquared() < 1e-6) {
            fwd = new Vector(0, 0, 1);
        }
        Location loc = eye.clone().add(fwd.normalize().multiply(3));
        loc.setY(groundY(loc));
        loc.setPitch(0);
        Interaction seat = ctx.entity(loc.getWorld().spawn(loc, Interaction.class, i -> {
            i.setInteractionWidth(2.0f);
            i.setInteractionHeight(0.45f);
            i.setResponsive(true);
            i.setPersistent(false);
        }));
        Models.Model model = Models.spawn(ctx, MODEL, loc, 1.0);
        model.ride(seat); // the car follows the seat; its ground stays at the seat's feet
        model.setYaw(loc.getYaw());
        Car car = new Car(seat, model, p.getUniqueId());
        cars.put(seat.getUniqueId(), car);
        return car;
    }

    /** Top of the highest solid block at or below loc (within 6 blocks), else loc's y. */
    private static double groundY(Location loc) {
        Block b = loc.getBlock();
        for (int i = 0; i < 6; i++) {
            if (b.isSolid()) {
                return b.getY() + 1;
            }
            b = b.getRelative(0, -1, 0);
        }
        return loc.getY();
    }

    private void tick() {
        for (Car car : new ArrayList<>(cars.values())) {
            if (!car.seat.isValid() || !car.model.isValid()) {
                remove(car);
                continue;
            }
            Player p = car.driver();
            if (p == null) {
                car.speed *= 0.85;
                if (++car.idle > 20 * 120) {
                    remove(car); // abandoned for two minutes
                    continue;
                }
            } else {
                car.idle = 0;
            }
            drive(car, p);
        }
    }

    private void drive(Car car, Player p) {
        Input in = p == null ? null : p.getCurrentInput();
        double max = in != null && in.isSprint() ? 1.1 : 0.75;
        if (in != null && in.isForward()) {
            car.speed = Math.min(max, car.speed + 0.025);
        } else if (in != null && in.isBackward()) {
            car.speed = Math.max(-0.3, car.speed - (car.speed > 0 ? 0.06 : 0.02));
        } else {
            car.speed *= 0.97;
            if (Math.abs(car.speed) < 0.003) {
                car.speed = 0;
            }
        }
        double target = in == null ? 0 : in.isLeft() ? 0.55 : in.isRight() ? -0.55 : 0;
        car.steer += (target - car.steer) * 0.25;
        Location from = car.seat.getLocation();
        float yaw = from.getYaw() - (float) Math.toDegrees(car.steer * car.speed * 0.45);
        double rad = Math.toRadians(yaw);
        Vector dir = new Vector(-Math.sin(rad), 0, Math.cos(rad)); // Minecraft yaw: 0 = south (+z)
        Location to = from.clone().add(dir.multiply(car.speed));
        to.setYaw(yaw);
        to.setPitch(0);
        // ground: climb one block, stop at walls, fall into holes
        Block feet = to.getBlock();
        if (feet.isSolid()) {
            if (!feet.getRelative(0, 1, 0).isSolid() && !feet.getRelative(0, 2, 0).isSolid() && Math.abs(car.speed) < 0.9) {
                to.setY(feet.getY() + 1);
            } else {
                car.speed = 0;
                to = from.clone();
                to.setYaw(yaw);
                p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.4f, 1.5f);
            }
        } else if (!to.clone().add(0, -0.05, 0).getBlock().isSolid()) {
            car.fall = Math.min(1.0, car.fall + 0.08);
            double floor = groundY(to);
            to.setY(Math.max(floor, to.getY() - car.fall));
        } else {
            car.fall = 0;
        }
        if (to.getY() < to.getWorld().getMinHeight() + 1) {
            to.setY(to.getWorld().getMinHeight() + 1);
        }
        car.seat.teleport(to, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        car.model.setYaw(yaw);
        // wheels: spin with the distance travelled, front wheels steer
        if (Math.abs(car.speed) > 0.001 || Math.abs(car.steer) > 0.01) {
            car.wheelAngle = (car.wheelAngle + car.speed / WHEEL_RADIUS) % (Math.PI * 2);
            Quaternionf spin = new Quaternionf().rotateX((float) car.wheelAngle);
            Quaternionf steer = new Quaternionf().rotateY((float) car.steer).mul(spin);
            car.model.rotatePart("wheel_fl", steer, 1);
            car.model.rotatePart("wheel_fr", steer, 1);
            car.model.rotatePart("wheel_rl", spin, 1);
            car.model.rotatePart("wheel_rr", spin, 1);
        }
        if (in != null && in.isJump() && System.currentTimeMillis() - car.hornAt > 800) {
            car.hornAt = System.currentTimeMillis();
            to.getWorld().playSound(to, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.2f, 1.4f);
        }
        if (car.speed > 0.4 && p != null && p.getTicksLived() % 3 == 0) {
            Location exhaust = to.clone().add(new Vector(-Math.sin(rad), 0, Math.cos(rad)).multiply(-2.3)).add(0, 0.35, 0);
            to.getWorld().spawnParticle(Particle.SMOKE, exhaust, 1, 0.05, 0.02, 0.05, 0.005);
        }
    }

    private void remove(Car car) {
        cars.remove(car.seat.getUniqueId());
        car.seat.eject();
        car.model.remove();
        car.seat.remove();
    }
}

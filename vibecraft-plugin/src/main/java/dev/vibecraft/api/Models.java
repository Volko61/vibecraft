package dev.vibecraft.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.vibecraft.pack.ModelRegistry;
import dev.vibecraft.pack.mesh.MeshCompiler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Big, realistic 3D objects compiled from {@code assets/<ns>/shapes/<name>.shape.json} or {@code .obj}
 * (see ASSETS.md, "Realistic 3D objects"), placed in the world as a group of {@link ItemDisplay}
 * entities (one per part, any size, any rotation). Main thread only.
 * <pre>{@code
 * Models.Model car = Models.spawn(ctx, "car:sports_car", player.getLocation(), 1.0);   // removed on reload
 * car.teleport(newLocation);                                   // moves + turns (location yaw) smoothly
 * car.rotatePart("wheel_fl", new Quaternionf().rotateX(angle), 2);   // animate a part around its pivot
 * ItemStack icon = Models.item("car:sports_car");              // the whole object as a held item
 * }</pre>
 * The object's front is +z of the source (it faces the location's yaw) and y = 0 is its ground.
 * Objects exist once the resource pack is built (~1 s after the files are saved): {@link #exists}.
 */
public final class Models {

    private Models() {
    }

    /** True if the object was compiled into the current resource pack. */
    public static boolean exists(String id) {
        return ModelRegistry.get(id) != null;
    }

    /** Every compiled object id ("ns:name"). */
    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (MeshCompiler.ObjectInfo i : ModelRegistry.all()) {
            out.add(i.id());
        }
        return out;
    }

    /** Size {x, y, z} in blocks at scale 1. */
    public static double[] size(String id) {
        return info(id).size();
    }

    /** Part names of an object ("main" when the source has no parts). */
    public static List<String> parts(String id) {
        List<String> out = new ArrayList<>();
        for (MeshCompiler.PartInfo p : info(id).parts()) {
            out.add(p.name());
        }
        return out;
    }

    /** The whole object as an item (PAPER with item_model ns:name): held, in item frames, in GUIs. */
    public static ItemStack item(String id) {
        MeshCompiler.ObjectInfo i = info(id);
        ItemStack it = ItemStack.of(Material.PAPER);
        it.editMeta(m -> m.setItemModel(NamespacedKey.fromString(i.item())));
        return it;
    }

    public static Model spawn(ModuleContext ctx, String id, Location at) {
        return spawn(ctx, id, at, 1.0);
    }

    /**
     * Places the object with its origin (ground centre) at {@code at}, facing {@code at.getYaw()}.
     * The displays are tracked by {@code ctx} (removed on reload/disable).
     *
     * @param scale 1 = the size written in the source (blocks)
     */
    public static Model spawn(ModuleContext ctx, String id, Location at, double scale) {
        MeshCompiler.ObjectInfo info = info(id);
        if (!(scale > 0) || scale > 64) {
            throw new IllegalArgumentException("scale must be in ]0, 64]");
        }
        Location loc = at.clone();
        loc.setPitch(0);
        Model model = new Model(info, scale);
        double[] mn = info.min();
        double[] mx = info.max();
        double radius = Math.max(Math.max(Math.abs(mn[0]), Math.abs(mx[0])), Math.max(Math.abs(mn[2]), Math.abs(mx[2])));
        float width = (float) (2 * radius * scale + 0.5);
        float height = (float) ((mx[1] - Math.min(0, mn[1])) * scale + 0.5);
        for (MeshCompiler.PartInfo p : info.parts()) {
            for (String itemModel : p.models()) {
                ItemStack item = ItemStack.of(Material.PAPER);
                item.editMeta(m -> m.setItemModel(NamespacedKey.fromString(itemModel)));
                ItemDisplay d = loc.getWorld().spawn(loc, ItemDisplay.class, e -> {
                    e.setItemStack(item);
                    e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    e.setPersistent(false);
                    e.setDisplayWidth(width);
                    e.setDisplayHeight(height);
                    e.setViewRange((float) Math.max(1.0, Math.min(4.0, radius * scale / 6)));
                    e.setTeleportDuration(2);
                    e.setRotation(loc.getYaw(), 0);
                });
                ctx.entity(d);
                model.add(p, d);
            }
        }
        model.location = loc;
        model.applyAll(0);
        return model;
    }

    private static MeshCompiler.ObjectInfo info(String id) {
        MeshCompiler.ObjectInfo i = ModelRegistry.get(id);
        if (i == null) {
            throw new IllegalArgumentException("3D object '" + id + "' not found: compiled objects are " + ids()
                    + " (is assets/<ns>/shapes/<name>.shape.json valid? see GET /api/pack issues; the pack is built ~1 s after saving)");
        }
        return i;
    }

    /** A placed object: its display entities and helpers to move / animate it. */
    public static final class Model {
        private final MeshCompiler.ObjectInfo info;
        private final double scale;
        private final Map<String, MeshCompiler.PartInfo> parts = new LinkedHashMap<>();
        private final Map<String, List<ItemDisplay>> displays = new LinkedHashMap<>();
        private final Map<String, Quaternionf> rotations = new LinkedHashMap<>();
        private Location location;
        private float rideOffset;

        private Model(MeshCompiler.ObjectInfo info, double scale) {
            this.info = info;
            this.scale = scale;
        }

        private void add(MeshCompiler.PartInfo p, ItemDisplay d) {
            parts.put(p.name(), p);
            displays.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(d);
            rotations.putIfAbsent(p.name(), new Quaternionf());
        }

        public String id() {
            return info.id();
        }

        public double scale() {
            return scale;
        }

        /** Size {x, y, z} in blocks at this scale. */
        public double[] size() {
            double[] s = info.size();
            return new double[] {s[0] * scale, s[1] * scale, s[2] * scale};
        }

        public List<String> parts() {
            return List.copyOf(parts.keySet());
        }

        /** Every display entity (one per part, several for parts split above 1024 elements). */
        public List<ItemDisplay> displays() {
            List<ItemDisplay> out = new ArrayList<>();
            displays.values().forEach(out::addAll);
            return Collections.unmodifiableList(out);
        }

        /** First display of a part (null if no such part). */
        public ItemDisplay part(String name) {
            List<ItemDisplay> l = displays.get(name);
            return l == null || l.isEmpty() ? null : l.get(0);
        }

        public Location location() {
            return location.clone();
        }

        /** Moves every part to {@code to} and turns the object to {@code to.getYaw()} (smoothed over 2 ticks). */
        public void teleport(Location to) {
            location = to.clone();
            location.setPitch(0);
            for (ItemDisplay d : displays()) {
                if (d.isValid() && d.getVehicle() == null) {
                    d.teleport(location);
                }
            }
            setYaw(location.getYaw());
        }

        /** Turns the object (yaw in degrees, Minecraft convention: 0 = facing south / +z). */
        public void setYaw(float yaw) {
            if (location != null) {
                location.setYaw(yaw);
            }
            for (ItemDisplay d : displays()) {
                if (d.isValid()) {
                    d.setRotation(yaw, 0);
                }
            }
        }

        /**
         * Rotates a part around its pivot (object axes: x right... front is +z), interpolated by the client over
         * {@code ticks} (0 = instantly). E.g. a wheel: {@code new Quaternionf().rotateX(angle)}.
         */
        public void rotatePart(String part, Quaternionf rotation, int ticks) {
            if (!parts.containsKey(part)) {
                throw new IllegalArgumentException("no part '" + part + "' in " + info.id() + " (parts: " + parts.keySet() + ")");
            }
            rotations.put(part, new Quaternionf(rotation));
            apply(part, ticks);
        }

        /**
         * Makes every display a passenger of {@code vehicle} (they follow it, e.g. an Interaction seat moved with
         * {@code teleport(..., TeleportFlag.EntityState.RETAIN_PASSENGERS)}); call {@link #setYaw} to turn the object.
         * The object's ground is put at the vehicle's feet.
         */
        public void ride(Entity vehicle) {
            rideOffset = (float) vehicle.getHeight();
            for (ItemDisplay d : displays()) {
                if (d.isValid()) {
                    vehicle.addPassenger(d);
                }
            }
            applyAll(0);
        }

        public void setGlowing(boolean glowing) {
            for (ItemDisplay d : displays()) {
                d.setGlowing(glowing);
            }
        }

        /** Fixed light level (null = world light). */
        public void setBrightness(Display.Brightness brightness) {
            for (ItemDisplay d : displays()) {
                d.setBrightness(brightness);
            }
        }

        public boolean isValid() {
            for (ItemDisplay d : displays()) {
                if (!d.isValid()) {
                    return false;
                }
            }
            return !displays.isEmpty();
        }

        public void remove() {
            for (ItemDisplay d : displays()) {
                d.remove();
            }
        }

        private void applyAll(int ticks) {
            for (String p : parts.keySet()) {
                apply(p, ticks);
            }
        }

        private void apply(String part, int ticks) {
            MeshCompiler.PartInfo p = parts.get(part);
            Quaternionf q = rotations.get(part);
            float k = (float) (p.unit() * scale);
            Vector3f t = new Vector3f((float) (p.pivot()[0] * scale), (float) (p.pivot()[1] * scale) - rideOffset,
                    (float) (p.pivot()[2] * scale));
            Transformation tr = new Transformation(t, new Quaternionf(q), new Vector3f(k, k, k), new Quaternionf());
            for (ItemDisplay d : displays.get(part)) {
                if (!d.isValid()) {
                    continue;
                }
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(Math.max(0, ticks));
                d.setTransformation(tr);
            }
        }
    }
}

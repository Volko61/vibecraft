package vibe.floor_lamp;

import java.util.ArrayList;
import java.util.List;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.Models;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Decorative 3D object from a hand-written Wavefront OBJ (assets/lamp/shapes/floor_lamp.obj + .mtl):
 * revolved base, stem, fabric shade and glowing bulb. /lampe [player] places one (with a real light
 * block at the bulb, removed on reload), /lampe item gives it as a held item, /lampe clear removes them.
 */
public class FloorLampModule implements VibeModule {

    private static final String MODEL = "lamp:floor_lamp";
    private static final int MAX_LAMPS = 12;

    private record Lamp(Models.Model model, Block light) {
    }

    private final List<Lamp> lamps = new ArrayList<>();

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("lampe", "Pose un lampadaire 3D", (sender, args) -> {
            String sub = args.length > 0 ? args[0] : "";
            if (sub.equals("clear")) {
                int n = lamps.size();
                new ArrayList<>(lamps).forEach(this::remove);
                sender.sendRichMessage("<gray>" + n + " lampadaire(s) retiré(s).");
                return;
            }
            boolean item = sub.equals("item");
            String name = item ? (args.length > 1 ? args[1] : null) : (args.length > 0 ? args[0] : null);
            Player p = name != null ? Bukkit.getPlayerExact(name) : sender instanceof Player pl ? pl : null;
            if (p == null) {
                sender.sendRichMessage("<red>Usage : /lampe [joueur], /lampe item [joueur], /lampe clear");
                return;
            }
            if (!Models.exists(MODEL)) {
                sender.sendRichMessage("<yellow>Le modèle 3D est encore en préparation, réessayez dans une seconde.");
                return;
            }
            if (item) {
                p.give(Models.item(MODEL));
                sender.sendRichMessage("<green>Lampadaire en objet donné à " + p.getName());
                return;
            }
            if (lamps.size() >= MAX_LAMPS) {
                remove(lamps.get(0));
            }
            Vector fwd = p.getLocation().getDirection().setY(0);
            if (fwd.lengthSquared() < 1e-6) {
                fwd = new Vector(0, 0, 1);
            }
            Location at = p.getLocation().add(fwd.normalize().multiply(2)).getBlock().getLocation().add(0.5, 0, 0.5);
            at.setYaw(p.getLocation().getYaw() + 180); // facing the player
            Models.Model lamp = Models.spawn(ctx, MODEL, at, 1.3);
            Block light = at.clone().add(0, 1, 0).getBlock();
            if (light.getType().isAir()) {
                light.setType(Material.LIGHT);
                if (light.getBlockData() instanceof Levelled lv) {
                    lv.setLevel(15);
                    light.setBlockData(lv);
                }
            } else {
                light = null;
            }
            lamps.add(new Lamp(lamp, light));
            at.getWorld().playSound(at, Sound.BLOCK_LANTERN_PLACE, 1f, 1f);
            sender.sendRichMessage("<green>Lampadaire posé devant " + p.getName() + " (" + lamp.displays().size() + " pièce(s) 3D)");
        }, (sender, args) -> args.length == 1 ? List.of("item", "clear") : List.of());
        // light blocks are world changes: undo them when the module is reloaded or disabled
        ctx.onCleanup(() -> new ArrayList<>(lamps).forEach(this::remove));
    }

    private void remove(Lamp l) {
        lamps.remove(l);
        l.model().remove();
        if (l.light() != null && l.light().getType() == Material.LIGHT) {
            l.light().setType(Material.AIR);
        }
    }
}

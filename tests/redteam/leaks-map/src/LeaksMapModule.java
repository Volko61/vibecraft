package vibe.leaks_map;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;

/**
 * REDTEAM-LEAKS: "show text/an image on a map" with a custom MapRenderer. The renderer is called by the
 * server (outside VibeCraft's guard) while a player holds the map. /lkmap [player] gives the map.
 */
public class LeaksMapModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        int g = ctx.generation();
        ctx.command("lkmap", (sender, args) -> {
            Player p = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (p == null) return;
            MapView view = Bukkit.createMap(p.getWorld());
            view.getRenderers().forEach(view::removeRenderer);
            view.addRenderer(new MapRenderer() {
                @Override
                public void render(MapView map, MapCanvas canvas, Player player) {
                    canvas.drawText(10, 10, MinecraftFont.Font, "VibeCraft v" + g);
                }
            });
            ItemStack item = ItemStack.of(Material.FILLED_MAP);
            item.editMeta(MapMeta.class, m -> m.setMapView(view));
            p.getInventory().setItemInMainHand(item);
            sender.sendMessage("map " + view.getId() + " given (renderer v" + g + ")");
        });
    }
}

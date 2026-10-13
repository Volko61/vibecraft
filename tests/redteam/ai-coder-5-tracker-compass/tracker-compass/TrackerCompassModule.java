package vibe.tracker_compass;

import java.util.List;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** /traqueur [joueur] : une boussole qui pointe vers le joueur le plus proche (distance dans la barre d'action). */
public class TrackerCompassModule implements VibeModule {

    private NamespacedKey key;

    @Override
    public void enable(ModuleContext ctx) {
        key = ctx.key("tracker");

        ctx.command("traqueur", "Donne la boussole traqueuse", (sender, args) -> {
            Player target = args.length > 0 ? ctx.server().getPlayerExact(args[0])
                    : sender instanceof Player p ? p : null;
            if (target == null) {
                sender.sendRichMessage("<red>Usage : /traqueur <joueur>");
                return;
            }
            ItemStack compass = ItemStack.of(Material.COMPASS);
            compass.editMeta(meta -> {
                meta.itemName(ctx.mm("<red>Boussole traqueuse"));
                meta.lore(List.of(ctx.mm("<gray>Pointe vers le joueur le plus proche").decoration(TextDecoration.ITALIC, false)));
                meta.setEnchantmentGlintOverride(true);
                meta.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, true);
            });
            target.give(compass);
            target.sendRichMessage("<red>➜ Tu as la <gold>boussole traqueuse</gold> : elle pointe vers le joueur le plus proche !");
            sender.sendRichMessage("<gray>Boussole donnée à " + target.getName());
        }, null, "tracker");

        ctx.every(10, () -> {
            for (Player p : ctx.server().getOnlinePlayers()) {
                if (!holdsTracker(p)) continue;
                Player nearest = null;
                double best = Double.MAX_VALUE;
                for (Player o : p.getWorld().getPlayers()) {
                    if (o.equals(p)) continue;
                    double d = o.getLocation().distanceSquared(p.getLocation());
                    if (d < best) { best = d; nearest = o; }
                }
                if (nearest == null) {
                    p.sendActionBar(ctx.mm("<gray>➜ Personne à traquer dans ce monde"));
                    continue;
                }
                p.setCompassTarget(nearest.getLocation()); // compasses of this player point there
                p.sendActionBar(ctx.mm("<red>➜ " + nearest.getName() + " <gray>à <white>" + (int) Math.sqrt(best) + " m"));
            }
        });

        // Compasses go back to normal (world spawn) when the module is unloaded.
        ctx.onCleanup(() -> ctx.server().getOnlinePlayers().forEach(p -> p.setCompassTarget(p.getWorld().getSpawnLocation())));
        ctx.logger().info("enabled");
    }

    private boolean holdsTracker(Player p) {
        return isTracker(p.getInventory().getItemInMainHand()) || isTracker(p.getInventory().getItemInOffHand());
    }

    private boolean isTracker(ItemStack it) {
        return it != null && !it.isEmpty() && it.getPersistentDataContainer().has(key);
    }
}

package vibe.leaks_gui;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * REDTEAM-LEAKS: the "chest menu" pattern of CLAUDE.md (holder class + cancelled InventoryClickEvent).
 * /lkgui [player] opens a menu holding 5 diamonds (slot 13) that must not be takeable.
 */
public class LeaksGuiModule implements VibeModule {

    final class Menu implements InventoryHolder {
        private final Inventory inv;
        private final int gen;

        Menu(ModuleContext ctx) {
            gen = ctx.generation();
            inv = Bukkit.createInventory(this, 27, ctx.mm("<dark_purple>Menu v" + gen));
            inv.setItem(13, ItemStack.of(Material.DIAMOND, 5));
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    @Override
    public void enable(ModuleContext ctx) {
        int g = ctx.generation();
        ctx.command("lkgui", (sender, args) -> {
            Player p = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (p == null) {
                sender.sendMessage("no player");
                return;
            }
            p.openInventory(new Menu(ctx).getInventory());
            sender.sendMessage("opened menu v" + g + " for " + p.getName());
        });
        ctx.on(InventoryClickEvent.class, e -> {
            if (e.getInventory().getHolder() instanceof Menu m) {
                e.setCancelled(true);
                ctx.logger().info("v" + g + " cancelled click on slot " + e.getRawSlot() + " of menu v" + m.gen);
            }
        });
        ctx.on(InventoryDragEvent.class, e -> {
            if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
        });
        ctx.on(InventoryCloseEvent.class, e -> {
            if (e.getInventory().getHolder() instanceof Menu m) ctx.logger().info("v" + g + " menu v" + m.gen + " closed");
        });
        // what CLAUDE.md recommends ("On cleanup close menus that are open") is deliberately NOT done here
    }
}

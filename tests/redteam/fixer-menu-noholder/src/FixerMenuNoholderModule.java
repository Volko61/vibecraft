package vibe.fixer_menu_noholder;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * FIXER regression (leaks-gui variant): a menu created WITHOUT a holder (createInventory(null, ...)); the
 * click listener cancels clicks by comparing the inventory with the one this generation created.
 * /fxmenu [player] opens it with 5 diamonds in slot 13.
 */
public class FixerMenuNoholderModule implements VibeModule {
    private Inventory inv;

    @Override
    public void enable(ModuleContext ctx) {
        inv = Bukkit.createInventory(null, 27, ctx.mm("<gold>Menu sans holder v" + ctx.generation()));
        inv.setItem(13, ItemStack.of(Material.DIAMOND, 5));
        ctx.on(InventoryClickEvent.class, e -> {
            if (e.getInventory().equals(inv)) {
                e.setCancelled(true);
            }
        });
        ctx.command("fxmenu", (sender, args) -> {
            Player p = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : null;
            if (p != null) {
                p.openInventory(inv);
                sender.sendMessage("opened for " + p.getName());
            }
        });
    }
}

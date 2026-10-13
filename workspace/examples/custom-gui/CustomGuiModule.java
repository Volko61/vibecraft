package vibe.custom_gui;

import java.util.List;
import java.util.Map;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Custom textured chest GUI with vanilla clients. The whole background (176x166) is ONE glyph of a
 * custom font (assets/vgui/font/menu.json -> textures/gui/menu.png, drawn from menu.png.txt),
 * placed in the inventory TITLE: "" moves the pen 8 px left (to the GUI's left edge), ""
 * draws the background, "" moves back 169 px so the real title text starts at x=8.
 * Clickable buttons = vanilla items in fixed slots. /menu [player]
 */
public class CustomGuiModule implements VibeModule {

    private static final Key FONT = Key.key("vgui", "menu");

    /** Our inventories are recognised by their holder. */
    private static final class Menu implements InventoryHolder {
        private final Inventory inv;

        Menu(Component title) {
            inv = Bukkit.createInventory(this, 27, title);
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final Map<Integer, String> ACTIONS = Map.of(10, "day", 12, "rain", 14, "spawn", 16, "party");

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("menu", "Ouvre le menu magique", (sender, args) -> {
            Player target = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : sender instanceof Player p ? p : null;
            if (target == null) {
                sender.sendRichMessage("<red>Usage : /menu [joueur]");
                return;
            }
            target.openInventory(createMenu(ctx).getInventory());
            sender.sendRichMessage("<green>Menu ouvert pour " + target.getName());
        }, (sender, args) -> args.length == 1 ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList() : List.of());

        ctx.on(InventoryClickEvent.class, e -> {
            if (!(e.getInventory().getHolder() instanceof Menu)) {
                return;
            }
            e.setCancelled(true); // nothing can be taken or put
            if (e.getClickedInventory() != e.getInventory() || !(e.getWhoClicked() instanceof Player p)) {
                return;
            }
            String action = ACTIONS.get(e.getSlot());
            if (action == null) {
                return;
            }
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
            switch (action) {
                case "day" -> p.getWorld().setTime(1000);
                case "rain" -> p.getWorld().setStorm(!p.getWorld().hasStorm());
                case "spawn" -> {
                    p.closeInventory();
                    p.teleport(p.getWorld().getSpawnLocation());
                }
                case "party" -> {
                    Firework fw = p.getWorld().spawn(p.getLocation(), Firework.class);
                    var meta = fw.getFireworkMeta();
                    meta.addEffect(FireworkEffect.builder().withColor(Color.PURPLE, Color.ORANGE).with(FireworkEffect.Type.STAR).build());
                    meta.setPower(1);
                    fw.setFireworkMeta(meta);
                }
                default -> {
                }
            }
        });
        ctx.on(InventoryDragEvent.class, e -> {
            if (e.getInventory().getHolder() instanceof Menu) {
                e.setCancelled(true);
            }
        });
        // close our menus on reload (their click handler is gone)
        ctx.onCleanup(() -> Bukkit.getOnlinePlayers().forEach(p -> {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                p.closeInventory();
            }
        }));
    }

    private static Menu createMenu(ModuleContext ctx) {
        Component background = Component.text("").font(FONT).color(NamedTextColor.WHITE);
        Component title = Component.text().append(background)
                .append(Component.text("✦ Menu magique", NamedTextColor.GOLD)).build();
        Menu menu = new Menu(title);
        menu.inv.setItem(10, button(ctx, Material.SUNFLOWER, "<yellow>☀ Faire le jour"));
        menu.inv.setItem(12, button(ctx, Material.WATER_BUCKET, "<aqua>☂ Pluie on/off"));
        menu.inv.setItem(14, button(ctx, Material.ENDER_PEARL, "<light_purple>➜ Retour au spawn"));
        menu.inv.setItem(16, button(ctx, Material.FIREWORK_ROCKET, "<gold>★ Feu d'artifice"));
        return menu;
    }

    private static ItemStack button(ModuleContext ctx, Material m, String name) {
        ItemStack it = ItemStack.of(m);
        it.editMeta(meta -> {
            meta.itemName(ctx.mm(name));
            meta.lore(List.of(ctx.mm("<gray>Clic pour activer").decoration(TextDecoration.ITALIC, false)));
        });
        return it;
    }
}

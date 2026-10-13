package vibe.shop;

import java.util.List;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** /shop [joueur] : boutique en menu coffre, on paie en émeraudes. */
public class ShopModule implements VibeModule {

    record Offer(int slot, Material material, int amount, int price, String name) { }

    private static final List<Offer> OFFERS = List.of(
            new Offer(10, Material.GOLDEN_APPLE, 2, 3, "Pommes dorées"),
            new Offer(11, Material.ENDER_PEARL, 4, 4, "Perles de l'Ender"),
            new Offer(12, Material.DIAMOND_SWORD, 1, 8, "Épée en diamant"),
            new Offer(13, Material.COOKED_BEEF, 16, 1, "Steaks"),
            new Offer(14, Material.FIREWORK_ROCKET, 16, 2, "Fusées"),
            new Offer(15, Material.ELYTRA, 1, 20, "Élytres"),
            new Offer(16, Material.CAKE, 1, 1, "Gâteau"));

    private ModuleContext ctx;

    /** The chest menu: its holder lets the click listener recognise it. */
    class Menu implements InventoryHolder {
        private final Inventory inv;

        Menu(Player p) {
            inv = Bukkit.createInventory(this, 27, ctx.mm("<dark_green><bold>Boutique</bold> <gray>(émeraudes)"));
            ItemStack glass = ItemStack.of(Material.GREEN_STAINED_GLASS_PANE);
            glass.editMeta(m -> m.itemName(ctx.mm(" ")));
            for (int i = 0; i < 27; i++) inv.setItem(i, glass);
            for (Offer o : OFFERS) {
                ItemStack it = ItemStack.of(o.material(), o.amount());
                it.editMeta(m -> {
                    m.itemName(ctx.mm("<gold>" + o.name()));
                    m.lore(List.of(
                            ctx.mm("<green>Prix : <white>" + o.price() + " émeraude" + (o.price() > 1 ? "s" : "")).decoration(TextDecoration.ITALIC, false),
                            ctx.mm("<gray>Clic pour acheter").decoration(TextDecoration.ITALIC, false)));
                });
                inv.setItem(o.slot(), it);
            }
            ItemStack wallet = ItemStack.of(Material.EMERALD);
            wallet.editMeta(m -> m.itemName(ctx.mm("<green>Tu as " + emeralds(p) + " émeraudes")));
            inv.setItem(22, wallet);
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    @Override
    public void enable(ModuleContext ctx) {
        this.ctx = ctx;
        ctx.command("shop", "Ouvre la boutique", (sender, args) -> {
            Player p = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : sender instanceof Player pl ? pl : null;
            if (p == null) {
                sender.sendRichMessage("<red>Usage : /shop [joueur]");
                return;
            }
            p.openInventory(new Menu(p).getInventory());
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
            if (sender != p) sender.sendRichMessage("<gray>Boutique ouverte pour " + p.getName());
        }, null, "boutique");

        ctx.on(InventoryClickEvent.class, e -> {
            if (!(e.getInventory().getHolder() instanceof Menu)) return;
            e.setCancelled(true); // nothing can be taken out of the menu
            if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
            for (Offer o : OFFERS) {
                if (o.slot() == e.getRawSlot()) {
                    buy(p, o);
                    return;
                }
            }
        });
        ctx.on(InventoryDragEvent.class, e -> {
            if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
        });

        // Close open shop menus when the module reloads (their holder class is about to disappear).
        ctx.onCleanup(() -> Bukkit.getOnlinePlayers().forEach(p -> {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
        }));
        ctx.logger().info("enabled");
    }

    private int emeralds(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == Material.EMERALD) n += it.getAmount();
        }
        return n;
    }

    private void buy(Player p, Offer o) {
        int have = emeralds(p);
        if (have < o.price()) {
            p.sendRichMessage("<red>✖ Il te faut " + o.price() + " émeraudes (tu en as " + have + ").");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        p.getInventory().removeItem(ItemStack.of(Material.EMERALD, o.price()));
        p.give(ItemStack.of(o.material(), o.amount()));
        p.sendRichMessage("<green>✔ Acheté : <gold>" + o.name() + "</gold> pour " + o.price() + " émeraude(s).");
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
        // refresh the wallet line
        ItemStack wallet = ItemStack.of(Material.EMERALD);
        wallet.editMeta(m -> m.itemName(ctx.mm("<green>Tu as " + emeralds(p) + " émeraudes")));
        p.getOpenInventory().getTopInventory().setItem(22, wallet);
    }
}

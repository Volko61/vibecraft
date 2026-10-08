package vibe.magic_wand;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/** Custom item (PDC tag) + right-click listener + shaped recipe + /wand [player]. */
public class MagicWandModule implements VibeModule {

    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void enable(ModuleContext ctx) {
        // ctx.key() = "vibecraft:magic-wand/wand": stable across reloads, so wands crafted earlier keep working.
        NamespacedKey wandKey = ctx.key("wand");
        ItemStack wand = createWand(ctx, wandKey);

        // Recipe (removed automatically on reload). Diagonal shape: no clash with vanilla recipes.
        ShapedRecipe recipe = new ShapedRecipe(ctx.key("wand_recipe"), wand);
        recipe.shape("  D", " B ", "B  ");
        recipe.setIngredient('D', Material.DIAMOND);
        recipe.setIngredient('B', Material.BLAZE_ROD);
        ctx.recipe(recipe);

        ctx.command("wand", "Donne la baguette magique", (sender, args) -> {
            Player target = args.length > 0 ? Bukkit.getPlayerExact(args[0])
                    : sender instanceof Player p ? p : null;
            if (target == null) {
                sender.sendRichMessage("<red>Usage : /wand [joueur]");
                return;
            }
            target.give(wand.clone());
            target.sendRichMessage("<light_purple>✦ Tu as reçu la <gold>baguette magique</gold> ! Clic droit pour lancer un éclair.");
        }, (sender, args) -> args.length == 1
                ? Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase())).toList()
                : List.of());

        ctx.on(PlayerInteractEvent.class, e -> {
            // Fired once per hand: ignore the off-hand, and only react to right clicks.
            if (e.getHand() != EquipmentSlot.HAND || !e.getAction().isRightClick()) {
                return;
            }
            ItemStack item = e.getItem();
            if (item == null || !item.getPersistentDataContainer().has(wandKey)) {
                return;
            }
            e.setCancelled(true);
            Player p = e.getPlayer();
            long now = System.currentTimeMillis();
            if (now - cooldowns.getOrDefault(p.getUniqueId(), 0L) < 1000) {
                return;
            }
            cooldowns.put(p.getUniqueId(), now);
            castSpell(p);
        });
    }

    private static ItemStack createWand(ModuleContext ctx, NamespacedKey wandKey) {
        ItemStack wand = ItemStack.of(Material.BLAZE_ROD);
        wand.editMeta(meta -> {
            meta.itemName(ctx.mm("<gradient:light_purple:gold>Baguette magique"));   // itemName: never italic
            meta.lore(List.of(ctx.mm("<gray>Clic droit : éclair magique").decoration(TextDecoration.ITALIC, false)));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BOOLEAN, true);
        });
        return wand;
    }

    private static void castSpell(Player p) {
        Location eye = p.getEyeLocation();
        Block target = p.getTargetBlockExact(40);
        Location hit = target != null ? target.getLocation().add(0.5, 1, 0.5)
                : eye.clone().add(eye.getDirection().multiply(20));

        // Particle beam from the eyes to the target (at most 80 points: cheap).
        Vector step = hit.toVector().subtract(eye.toVector()).normalize().multiply(0.5);
        Location point = eye.clone();
        for (int i = 0; i < 80 && point.distanceSquared(hit) > 0.25; i++) {
            p.getWorld().spawnParticle(Particle.END_ROD, point, 1, 0, 0, 0, 0);
            point.add(step);
        }
        p.getWorld().strikeLightningEffect(hit); // visual only: no fire, no damage
        p.getWorld().spawnParticle(Particle.DUST, hit, 40, 0.5, 0.5, 0.5, 0, new Particle.DustOptions(Color.PURPLE, 1.5f));
        p.playSound(p.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 1.4f);
    }
}

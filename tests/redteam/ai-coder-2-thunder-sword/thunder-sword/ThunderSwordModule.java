package vibe.thunder_sword;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Épée de foudre : chaque coup (clic gauche) fait tomber un éclair là où on tape (bloc visé ou mob frappé). */
public class ThunderSwordModule implements VibeModule {

    private final Map<UUID, Long> lastStrike = new HashMap<>();
    private NamespacedKey swordKey;

    @Override
    public void enable(ModuleContext ctx) {
        swordKey = ctx.key("thunder_sword");

        ctx.command("foudre", "Donne l'épée de foudre", (sender, args) -> {
            Player target = args.length > 0 ? ctx.server().getPlayerExact(args[0])
                    : sender instanceof Player p ? p : null;
            if (target == null) {
                sender.sendRichMessage("<red>Usage : /foudre <joueur>");
                return;
            }
            target.give(createSword(ctx));
            target.sendRichMessage("<yellow>⚡ Tu as l'<gold>Épée de foudre</gold> ! Tape un bloc ou un mob.");
            sender.sendRichMessage("<yellow>Épée de foudre donnée à " + target.getName());
        }, null, "thundersword");

        // Hit a mob/player with the sword: lightning on the victim.
        ctx.on(EntityDamageByEntityEvent.class, e -> {
            if (!(e.getDamager() instanceof Player p) || !isSword(p.getInventory().getItemInMainHand())) return;
            strike(p, e.getEntity().getLocation());
        });

        // Left click on a block (or in the air toward a far block): lightning there.
        ctx.on(PlayerInteractEvent.class, e -> {
            if (e.getHand() != EquipmentSlot.HAND || !e.getAction().isLeftClick() || !isSword(e.getItem())) return;
            Player p = e.getPlayer();
            Block b = e.getClickedBlock() != null ? e.getClickedBlock() : p.getTargetBlockExact(60);
            if (b == null) return;
            e.setCancelled(true); // don't break the block
            strike(p, b.getLocation().add(0.5, 1, 0.5));
        });
        ctx.logger().info("enabled");
    }

    private boolean isSword(ItemStack it) {
        return it != null && !it.isEmpty() && it.getPersistentDataContainer().has(swordKey);
    }

    private void strike(Player p, Location where) {
        long now = System.currentTimeMillis();
        if (now - lastStrike.getOrDefault(p.getUniqueId(), 0L) < 400) return; // one strike per swing
        lastStrike.put(p.getUniqueId(), now);

        where.getWorld().strikeLightningEffect(where); // visual + thunder, no fire
        where.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, where, 40, 0.6, 0.6, 0.6, 0.2);
        for (LivingEntity le : where.getNearbyLivingEntities(2.5)) {
            if (le.equals(p)) continue;
            le.damage(6, p);
            le.setFireTicks(40);
        }
        p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 0.6f, 1.4f);
    }

    private ItemStack createSword(ModuleContext ctx) {
        ItemStack sword = ItemStack.of(Material.GOLDEN_SWORD);
        sword.editMeta(meta -> {
            meta.itemName(ctx.mm("<gradient:yellow:aqua>Épée de foudre"));
            meta.lore(List.of(ctx.mm("<gray>Tape : un éclair tombe là où tu frappes").decoration(TextDecoration.ITALIC, false)));
            meta.setEnchantmentGlintOverride(true);
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BOOLEAN, true);
        });
        return sword;
    }
}

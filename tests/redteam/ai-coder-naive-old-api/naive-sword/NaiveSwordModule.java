package vibe.naive_sword;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class NaiveSwordModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("naive", (sender, args) -> {
            Player p = (Player) sender;
            ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
            ItemMeta meta = sword.getItemMeta();
            meta.setDisplayName(ChatColor.GOLD + "Thor");
            sword.setItemMeta(meta);
            sword.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, 5);
            p.getInventory().addItem(sword);
            p.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(40);
            p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, 200, 1));
        });
        ctx.on(PlayerInteractEvent.class, e -> {
            Player p = e.getPlayer();
            p.getWorld().spawnParticle(Particle.REDSTONE, p.getLocation(), 10);
            p.getWorld().spawnEntity(p.getLocation(), EntityType.LIGHTNING);
            p.playSound(p.getLocation(), Sound.valueOf("ENTITY_LIGHTNING_BOLT_THUNDER"), 1, 1);
        });
    }
}

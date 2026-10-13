package vibe.laser_gun;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Custom 3D item: assets/laser/... holds the texture (pixel-art .png.txt), the cuboid model
 * (models/item/laser_gun.json) and the item definition (items/laser_gun.json). The item itself is a
 * plain STICK whose item_model points to "laser:laser_gun" (= assets/laser/items/laser_gun.json).
 * Players without the resource pack simply see a stick. Right click: laser beam.
 */
public class LaserGunModule implements VibeModule {

    private static final NamespacedKey MODEL = new NamespacedKey("laser", "laser_gun");
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void enable(ModuleContext ctx) {
        NamespacedKey tag = ctx.key("laser_gun");

        ctx.command("lasergun", "Donne un pistolet laser", (sender, args) -> {
            Player target = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : sender instanceof Player p ? p : null;
            if (target == null) {
                sender.sendRichMessage("<red>Usage : /lasergun [joueur]");
                return;
            }
            target.give(createGun(ctx, tag));
            target.sendRichMessage("<aqua>⚡ Pistolet laser reçu ! <gray>Clic droit pour tirer.");
            sender.sendRichMessage("<green>Pistolet laser donné à " + target.getName());
        }, (sender, args) -> args.length == 1
                ? Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase())).toList()
                : List.of());

        ctx.on(PlayerInteractEvent.class, e -> {
            if (e.getHand() != EquipmentSlot.HAND || !e.getAction().isRightClick()) {
                return;
            }
            ItemStack item = e.getItem();
            if (item == null || !item.getPersistentDataContainer().has(tag)) {
                return;
            }
            e.setCancelled(true);
            Player p = e.getPlayer();
            long now = System.currentTimeMillis();
            if (now - cooldowns.getOrDefault(p.getUniqueId(), 0L) < 400) {
                return;
            }
            cooldowns.put(p.getUniqueId(), now);
            fire(p);
        });
    }

    static ItemStack createGun(ModuleContext ctx, NamespacedKey tag) {
        ItemStack gun = ItemStack.of(Material.STICK);
        gun.editMeta(meta -> {
            meta.setItemModel(MODEL);                 // 3D model from the resource pack
            meta.itemName(ctx.mm("<gradient:aqua:blue>Pistolet laser"));
            meta.lore(List.of(ctx.mm("<gray>Clic droit : rayon laser").decoration(TextDecoration.ITALIC, false)));
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(tag, PersistentDataType.BOOLEAN, true);
        });
        return gun;
    }

    private static void fire(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        double range = 40;
        RayTraceResult hit = p.getWorld().rayTrace(eye, dir, range, FluidCollisionMode.NEVER, true, 0.3,
                e -> e != p && e instanceof LivingEntity);
        Location end = hit != null ? hit.getHitPosition().toLocation(p.getWorld()) : eye.clone().add(dir.clone().multiply(range));

        double len = end.distance(eye);
        Particle.DustOptions cyan = new Particle.DustOptions(Color.fromRGB(60, 230, 255), 0.8f);
        for (double d = 1; d < len && d < range; d += 0.4) {
            p.getWorld().spawnParticle(Particle.DUST, eye.clone().add(dir.clone().multiply(d)), 1, 0, 0, 0, 0, cyan);
        }
        p.getWorld().spawnParticle(Particle.END_ROD, end, 8, 0.1, 0.1, 0.1, 0.05);
        p.getWorld().playSound(eye, Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 2f);
        if (hit != null && hit.getHitEntity() instanceof LivingEntity target) {
            target.damage(6, p);
            target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, target.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.1);
        }
    }
}

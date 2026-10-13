package vibe.leaks_chat;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * REDTEAM-LEAKS: custom chat format with a ChatRenderer. The renderer runs after the (guarded) listener,
 * per viewer, outside VibeCraft's guard. If the message contains "boom", the renderer throws for players.
 * If it contains "slow", the renderer spins 3 s (to show it is not time-budgeted either).
 */
public class LeaksChatModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        int g = ctx.generation();
        ctx.on(AsyncChatEvent.class, e -> e.renderer((source, name, message, viewer) -> {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message);
            if (plain.contains("boom") && viewer instanceof Player) {
                throw new IllegalStateException("renderer bug");
            }
            if (plain.contains("slow")) {
                long end = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < end) { }
            }
            return Component.text("[v" + g + "] ", NamedTextColor.GOLD).append(name).append(Component.text(": ")).append(message);
        }));
    }
}

package vibe.welcome;

import java.time.Duration;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;

/** Custom join message + welcome title, texts read from config.yml (editing it reloads the module live). */
public class WelcomeModule implements VibeModule {

    @Override
    public void enable(ModuleContext ctx) {
        // config.yml is read once per generation: saving the file triggers a reload with the new values.
        String joinMessage = ctx.config().getString("join-message", "<yellow><name> a rejoint le serveur");
        String title = ctx.config().getString("title", "<gold>Bienvenue !");
        String subtitle = ctx.config().getString("subtitle", "<white><name>");
        boolean sound = ctx.config().getBoolean("sound", true);
        MiniMessage mm = MiniMessage.miniMessage();

        ctx.on(PlayerJoinEvent.class, e -> {
            Player p = e.getPlayer();

            // Persistent per-player counter (survives reloads and restarts). Key by UUID, not by name.
            String path = "visits." + p.getUniqueId();
            int visits = ctx.store().getInt(path, 0) + 1;
            ctx.store().set(path, visits);

            TagResolver tags = TagResolver.resolver(
                    Placeholder.unparsed("name", p.getName()),
                    Placeholder.unparsed("visits", String.valueOf(visits)));
            e.joinMessage(mm.deserialize(joinMessage, tags));

            // The client needs a moment after joining before a title is visible: wait 1 second (20 ticks).
            ctx.later(20, () -> {
                if (!p.isOnline()) {
                    return;
                }
                p.showTitle(Title.title(mm.deserialize(title, tags), mm.deserialize(subtitle, tags),
                        Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(800))));
                if (sound) {
                    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1f);
                }
            });
        });
    }
}

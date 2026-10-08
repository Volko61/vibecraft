package vibe.hello;

import java.util.List;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/** /hello [style] : a colourful greeting. Shows a command with aliases and tab completion. */
public class HelloModule implements VibeModule {

    // static final constants are fine; static MUTABLE fields would be reset on every reload.
    private static final List<String> STYLES = List.of("arc-en-ciel", "feu", "glace");

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("hello", "Dit bonjour avec style", (sender, args) -> {
            String style = args.length > 0 ? args[0].toLowerCase() : "arc-en-ciel";
            String template = switch (style) {
                case "feu" -> "<gradient:red:gold><bold>Salut <name> !</bold></gradient> <gold>☀";
                case "glace" -> "<gradient:aqua:white><bold>Salut <name> !</bold></gradient> <aqua>❄";
                default -> "<rainbow><bold>Salut <name> !</bold></rainbow>";
            };
            // Text that comes from players goes in an *unparsed* placeholder, never concatenated into MiniMessage.
            sender.sendMessage(MiniMessage.miniMessage().deserialize(template,
                    Placeholder.unparsed("name", sender.getName())));
            if (sender instanceof Player p) {
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            }
        }, (sender, args) -> args.length == 1
                ? STYLES.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList()
                : List.of(), "salut");

        ctx.logger().info("/hello ready");
    }
}

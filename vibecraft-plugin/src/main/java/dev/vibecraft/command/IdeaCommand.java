package dev.vibecraft.command;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import dev.vibecraft.ideas.Idea;
import dev.vibecraft.ideas.IdeaQueue;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code /idea <text>} (permission {@code vibecraft.idea}, everyone): the audience suggests a
 * feature. Adds it to the idea queue (per-player cooldown {@code ideas.cooldown-seconds}; admins
 * and the console are exempt), thanks the player and notifies admins with clickable
 * [Accepter] / [Ignorer] buttons.
 */
public final class IdeaCommand implements TabExecutor {

    private static final int MAX_LENGTH = 256;
    private static final String ADMIN_PERM = "vibecraft.admin";

    private final JavaPlugin plugin;
    private final IdeaQueue ideas;
    private final Map<String, Long> lastIdeaAt = new ConcurrentHashMap<>();

    public IdeaCommand(JavaPlugin plugin, IdeaQueue ideas) {
        this.plugin = plugin;
        this.ideas = ideas;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            run(sender, label, args);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "/idea failed", e);
            sender.sendMessage(Msg.pmm("<red>Oups, ton idée n'a pas pu être enregistrée.</red>"));
        }
        return true;
    }

    private void run(CommandSender sender, String label, String[] args) {
        if (!plugin.getConfig().getBoolean("ideas.enabled", true)) {
            sender.sendMessage(Msg.pmm("<gray>Les idées sont fermées pour le moment.</gray>"));
            return;
        }
        String text = String.join(" ", args).strip();
        if (text.isEmpty()) {
            sender.sendMessage(Msg.pmm("<gray>Propose une fonctionnalité aux codeurs :</gray> <white>/<label> \\<ton idée></white>"
                    + " <dark_gray>— ex : /<label> des cochons qui pleuvent du ciel</dark_gray>", Msg.text("label", label)));
            return;
        }
        if (text.length() > MAX_LENGTH) {
            sender.sendMessage(Msg.pmm("<red>Idée trop longue</red> <gray>(<n> caractères max, la tienne en fait <len>).</gray>",
                    Msg.text("n", String.valueOf(MAX_LENGTH)), Msg.text("len", String.valueOf(text.length()))));
            return;
        }
        String key = sender instanceof Player p ? p.getUniqueId().toString() : sender.getName();
        boolean exempt = !(sender instanceof Player) || sender.hasPermission(ADMIN_PERM);
        long cooldownMs = Math.max(0, plugin.getConfig().getLong("ideas.cooldown-seconds", 20)) * 1000L;
        long now = System.currentTimeMillis();
        if (!exempt && cooldownMs > 0) {
            Long last = lastIdeaAt.get(key);
            if (last != null && now - last < cooldownMs) {
                long wait = (cooldownMs - (now - last) + 999) / 1000;
                sender.sendMessage(Msg.pmm("<gold>⏳ Doucement !</gold> <gray>Attends encore <white><s> s</white> avant de proposer une autre idée.</gray>",
                        Msg.text("s", String.valueOf(wait))));
                return;
            }
        }
        String author = sender instanceof Player p ? p.getName() : "console";
        Idea idea = ideas.add(author, text);
        lastIdeaAt.put(key, now);
        if (lastIdeaAt.size() > 1000) {
            lastIdeaAt.values().removeIf(t -> now - t > cooldownMs);
        }

        sender.sendMessage(Msg.pmm("<yellow>💡 Merci <name> !</yellow> <gray>Ton idée</gray> <white>#<n></white> <gray>est dans la file des codeurs.</gray>",
                Msg.text("name", author), Msg.text("n", String.valueOf(idea.id()))));

        Component header = Msg.pmm("<yellow>💡 Nouvelle idée</yellow> <dark_gray>(/vibe ideas)</dark_gray>");
        Component line = VibeCommand.ideaLine(idea);
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.hasPermission(ADMIN_PERM)) {
                p.sendMessage(header);
                p.sendMessage(line);
            }
        }
        plugin.getServer().getConsoleSender().sendMessage(Msg.pmm("<yellow>💡 Idée #<n> de <name> :</yellow> <white><text></white>",
                Msg.text("n", String.valueOf(idea.id())), Msg.text("name", author), Msg.text("text", idea.text())));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }
}

package dev.vibecraft.command;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import dev.vibecraft.ai.AiJob;
import dev.vibecraft.ai.AiJobService;
import dev.vibecraft.ai.AiJobState;
import dev.vibecraft.core.ModuleManager;
import dev.vibecraft.reset.FreshStart;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * {@code /reset [confirm [seed] | cancel]} (permission {@code vibecraft.admin}): back to a blank server. Deletes the map
 * (new seed), every module with its data, the AI conversations and the idea queue, then restarts the server (see
 * {@link FreshStart}). {@code /reset} only explains and asks for confirmation; {@code /reset confirm} (within
 * {@value #CONFIRM_WINDOW_S} s) warns everyone, counts down {@value #COUNTDOWN_S} s and stops the server.
 */
public final class ResetCommand implements TabExecutor {

    private static final int CONFIRM_WINDOW_S = 60;
    private static final int COUNTDOWN_S = 5;
    private static final int MAX_SEED_LENGTH = 64;

    private final JavaPlugin plugin;
    private final ModuleManager modules;
    private final AiJobService ai;
    /** Sender name -> when /reset was typed (confirmation window). */
    private final Map<String, Long> asked = new ConcurrentHashMap<>();
    private BukkitTask countdown;

    public ResetCommand(JavaPlugin plugin, ModuleManager modules, AiJobService ai) {
        this.plugin = plugin;
        this.modules = modules;
        this.ai = ai;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "confirm", "confirmer", "oui" -> confirm(sender, args.length >= 2 ? args[1].strip() : "");
                case "cancel", "annuler" -> cancel(sender);
                default -> ask(sender);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "/reset failed", e);
            sender.sendMessage(Msg.pmm("<red>Erreur interne : <gray><msg></gray></red>", Msg.text("msg", Msg.cut(String.valueOf(e), 150))));
        }
        return true;
    }

    private void ask(CommandSender sender) {
        if (countdown != null) {
            sender.sendMessage(Msg.pmm("<gold>Remise à zéro déjà en cours.</gold> <btn>",
                    Msg.comp("btn", Msg.button("Annuler", NamedTextColor.GRAY, "/reset cancel", "Annuler la remise à zéro"))));
            return;
        }
        asked.put(sender.getName(), System.currentTimeMillis());
        int count = modules.list().size();
        sender.sendMessage(Msg.pmm("<red><b>⚠ Remise à zéro complète</b></red> <gray>— comme un serveur neuf :</gray>"));
        sender.sendMessage(Msg.mm(" <dark_gray>•</dark_gray> <white>nouvelle map</white> <gray>(nouvelle seed ; inventaires et"
                + " progression des joueurs effacés)</gray>"));
        sender.sendMessage(Msg.mm(" <dark_gray>•</dark_gray> <white><n> module(s) supprimé(s)</white> <gray>avec leurs données</gray>",
                Msg.text("n", String.valueOf(count))));
        sender.sendMessage(Msg.mm(" <dark_gray>•</dark_gray> <white>conversations IA et idées du public oubliées</white>"));
        sender.sendMessage(Msg.mm(" <gray>Le serveur redémarre (tout seul avec scripts/start-server.bat ou"
                + " proxy/run-main-forever.ps1, sinon relance-le à la main). Irréversible.</gray>"));
        sender.sendMessage(Msg.mm(" <confirm> <seed> <cancel>",
                Msg.comp("confirm", Msg.button("Tout effacer", NamedTextColor.RED, "/reset confirm", "Effacer et redémarrer maintenant")),
                Msg.comp("seed", Msg.suggest("Choisir la seed…", NamedTextColor.GOLD, "/reset confirm ", "/reset confirm <seed>")),
                Msg.comp("cancel", Msg.button("Annuler", NamedTextColor.GRAY, "/reset cancel", "Ne rien effacer"))));
    }

    private void confirm(CommandSender sender, String seed) {
        if (countdown != null) {
            sender.sendMessage(Msg.pmm("<gold>Remise à zéro déjà en cours.</gold>"));
            return;
        }
        Long at = asked.remove(sender.getName());
        if (at == null || System.currentTimeMillis() - at > CONFIRM_WINDOW_S * 1000L) {
            ask(sender); // typed directly (or too late): explain first
            return;
        }
        if (seed.length() > MAX_SEED_LENGTH) {
            sender.sendMessage(Msg.pmm("<red>Seed trop longue</red> <gray>(<n> caractères max).</gray>", Msg.text("n", String.valueOf(MAX_SEED_LENGTH))));
            asked.put(sender.getName(), System.currentTimeMillis());
            return;
        }
        String newSeed = seed.isEmpty() ? String.valueOf(ThreadLocalRandom.current().nextLong()) : seed;

        // the AI would go on writing modules that are about to be deleted
        for (AiJob j : ai.jobs()) {
            if (j.state() == AiJobState.QUEUED || j.state() == AiJobState.RUNNING) {
                ai.stop(j.moduleId());
            }
        }
        List<String> worlds = new ArrayList<>();
        for (World w : plugin.getServer().getWorlds()) {
            worlds.add(w.getName());
        }
        try {
            FreshStart.schedule(plugin, newSeed, worlds, sender.getName());
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "/reset: cannot schedule the reset", e);
            sender.sendMessage(Msg.pmm("<red>Remise à zéro impossible : <gray><msg></gray></red>", Msg.text("msg", Msg.cut(String.valueOf(e), 150))));
            return;
        }
        plugin.getLogger().warning("/reset by " + sender.getName() + ": new seed " + newSeed + ", server restarts in " + COUNTDOWN_S + " s");
        sender.sendMessage(Msg.pmm("<aqua>🧹</aqua> Remise à zéro dans <white><s> s</white> <dark_gray>— seed</dark_gray> <white><seed></white>"
                        + " <btn>", Msg.text("s", String.valueOf(COUNTDOWN_S)), Msg.text("seed", newSeed),
                Msg.comp("btn", Msg.button("Annuler", NamedTextColor.GRAY, "/reset cancel", "Annuler la remise à zéro"))));

        int[] left = {COUNTDOWN_S};
        countdown = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (left[0] <= 0) {
                countdown.cancel();
                plugin.getServer().shutdown(); // FreshStart.applyPending wipes everything on the next start
                return;
            }
            Title title = Title.title(Component.text("🧹 Nouvelle map", NamedTextColor.AQUA),
                    Component.text("Le serveur repart de zéro dans " + left[0] + " s…", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(300)));
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                p.showTitle(title);
                if (left[0] == COUNTDOWN_S) {
                    p.sendMessage(Msg.pmm("<aqua>🧹 Remise à zéro !</aqua> <gray>Map, modules et conversations sont effacés :"
                            + " le serveur redémarre, reconnecte-toi dans quelques secondes si tu es déconnecté.</gray>"));
                }
            }
            left[0]--;
        }, 0L, 20L);
    }

    private void cancel(CommandSender sender) {
        asked.remove(sender.getName());
        if (countdown == null) {
            sender.sendMessage(Msg.pmm("<gray>Aucune remise à zéro en cours : rien n'a été effacé.</gray>"));
            return;
        }
        countdown.cancel();
        countdown = null;
        try {
            FreshStart.cancel(plugin);
        } catch (IOException e) {
            sender.sendMessage(Msg.pmm("<red>Impossible d'annuler : supprime <white>plugins/VibeCraft/<f></white> avant le prochain"
                    + " démarrage.</red>", Msg.text("f", FreshStart.MARKER)));
            return;
        }
        Title clear = Title.title(Component.empty(), Component.text("Remise à zéro annulée", NamedTextColor.GRAY),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(300)));
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            p.showTitle(clear);
        }
        sender.sendMessage(Msg.pmm("<green>Remise à zéro annulée.</green> <gray>Rien n'a été effacé (les IA arrêtées ne"
                + " reprennent pas toutes seules).</gray>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String cur = args[0].toLowerCase(Locale.ROOT);
            return List.of("confirm", "cancel").stream().filter(s -> s.startsWith(cur)).toList();
        }
        return List.of();
    }
}

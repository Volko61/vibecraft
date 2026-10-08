package dev.vibecraft.proxy;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * /vibeproxy status | return [joueur|all] | reload
 * "status" is open to everybody, the other sub-commands need vibeproxy.admin (console has it).
 */
final class VibeProxyCommand implements SimpleCommand {

    static final String ADMIN = "vibeproxy.admin";
    private final VibeReconnect plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    VibeProxyCommand(VibeReconnect plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status", "etat" -> status(src);
            case "return", "retour" -> {
                if (!src.hasPermission(ADMIN)) {
                    msg(src, "<red>Permission manquante (" + ADMIN + ").");
                    return;
                }
                forceReturn(src, args.length > 1 ? args[1] : "all");
            }
            case "reload" -> {
                if (!src.hasPermission(ADMIN)) {
                    msg(src, "<red>Permission manquante (" + ADMIN + ").");
                    return;
                }
                String err = plugin.reloadConfig();
                msg(src, err == null ? "<green>Configuration VibeReconnect rechargée." : "<red>Erreur : <white><err>", "err", err);
            }
            default -> msg(src, "<gold>/vibeproxy</gold> <gray>status · return [joueur|all] · reload");
        }
    }

    private void status(CommandSource src) {
        ReconnectConfig cfg = plugin.config();
        Map<String, Object> s = plugin.statusMap();
        @SuppressWarnings("unchecked") Map<String, Object> main = (Map<String, Object>) s.get("main");
        @SuppressWarnings("unchecked") Map<String, Object> limbo = (Map<String, Object>) s.get("limbo");
        @SuppressWarnings("unchecked") Map<String, Object> stats = (Map<String, Object>) s.get("stats");
        msg(src, "<gold><bold>VibeReconnect</bold></gold> <gray>— filet de sécurité");
        boolean up = Boolean.TRUE.equals(main.get("up"));
        String since = duration((Long) main.get("sinceMs"));
        if (up) {
            msg(src, " <gray>Principal <white><name></white> (<addr>) : <green>EN LIGNE</green> depuis <since> · ping <lat> ms · <count> joueur(s)",
                    "name", main.get("name"), "addr", main.get("address"), "since", since,
                    "lat", main.get("lastLatencyMs"), "count", main.get("players"));
        } else {
            msg(src, " <gray>Principal <white><name></white> (<addr>) : <red>HORS LIGNE</red> depuis <since> <dark_gray>(<err>)",
                    "name", main.get("name"), "addr", main.get("address"), "since", since, "err", main.get("lastError"));
        }
        msg(src, " <gray>Limbo <white><name></white> (<addr>) : <raw_state> · <count> joueur(s) en attente",
                "name", limbo.get("name"), "addr", limbo.get("address"),
                "raw_state", Boolean.TRUE.equals(limbo.get("up")) ? "<green>EN LIGNE</green>" : "<red>HORS LIGNE</red>",
                "count", limbo.get("players"));
        List<String> waitingLines = new ArrayList<>();
        Optional<RegisteredServer> limboServer = plugin.limbo();
        if (limboServer.isPresent()) {
            for (Player p : limboServer.get().getPlayersConnected()) {
                VibeReconnect.Waiting w = plugin.waiting.get(p.getUniqueId());
                String extra = w == null ? "" : duration(System.currentTimeMillis() - w.since)
                        + (w.attempts > 0 ? ", " + w.attempts + " essai(s)" + (w.lastFailure != null ? " : " + w.lastFailure : "") : "");
                waitingLines.add(p.getUsername() + (extra.isEmpty() ? "" : " (" + extra + ")"));
            }
        }
        if (!waitingLines.isEmpty()) {
            msg(src, " <gray>En attente : <white><list>", "list", String.join(", ", waitingLines));
        }
        msg(src, " <gray>Stats : <white><redir></white> redirection(s) vers le limbo, <white><ini></white> arrivée(s) directe(s), "
                        + "<white><ret></white> retour(s), <white><fail></white> échec(s) de retour, <white><kick></white> kick(s) volontaire(s)",
                "redir", stats.get("redirectsToLimbo"), "ini", stats.get("initialToLimbo"), "ret", stats.get("returns"),
                "fail", stats.get("returnFailures"), "kick", stats.get("deliberateKicks"));
        if (((Number) stats.get("freezeEvacuations")).longValue() > 0) {
            msg(src, " <gray>Gels détectés : <white><n></white> (dernier : <when>)",
                    "n", stats.get("freezeEvacuations"), "when", main.get("lastFreezeAt"));
        }
        if (src instanceof Player p) {
            msg(src, " <gray>Tu es sur : <white><srv>", "srv", p.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("?"));
        }
        if (cfg.debug) msg(src, " <dark_gray>ping toutes les " + cfg.pingIntervalMs + " ms");
    }

    private void forceReturn(CommandSource src, String who) {
        Optional<RegisteredServer> main = plugin.main();
        Optional<RegisteredServer> limbo = plugin.limbo();
        if (main.isEmpty() || limbo.isEmpty()) {
            msg(src, "<red>Serveurs main/limbo introuvables dans velocity.toml.");
            return;
        }
        Collection<Player> targets = new ArrayList<>();
        for (Player p : limbo.get().getPlayersConnected()) {
            if (who.equalsIgnoreCase("all") || p.getUsername().equalsIgnoreCase(who)) targets.add(p);
        }
        if (targets.isEmpty()) {
            msg(src, "<yellow>Personne à renvoyer depuis le limbo.");
            return;
        }
        long stagger = plugin.config().returnStaggerMs;
        int i = 0;
        for (Player p : targets) {
            VibeReconnect.Waiting w = plugin.waiting.computeIfAbsent(p.getUniqueId(),
                    id -> new VibeReconnect.Waiting(System.currentTimeMillis(), "retour force", true));
            w.inFlight = true;
            long delay = i++ * stagger;
            plugin.proxy().getScheduler().buildTask(plugin, () -> plugin.sendBack(p, w, main.get()))
                    .delay(delay, java.util.concurrent.TimeUnit.MILLISECONDS).schedule();
        }
        msg(src, "<green>Retour forcé de <white><count></white> joueur(s) vers <white><srv></white>.",
                "count", targets.size(), "srv", main.get().getServerInfo().getName());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        List<String> out = new ArrayList<>();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            for (String s : List.of("status", "return", "reload")) if (s.startsWith(prefix)) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("return")) {
            out.add("all");
            plugin.limbo().ifPresent(l -> l.getPlayersConnected().forEach(p -> out.add(p.getUsername())));
        }
        return out;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true; // status is public; admin sub-commands check vibeproxy.admin themselves
    }

    /**
     * Sends a MiniMessage line. {@code kv} = alternating placeholder names and values; values are
     * inserted unparsed (no tag injection), except names starting with "raw_" (parsed MiniMessage).
     */
    private void msg(CommandSource src, String template, Object... kv) {
        List<TagResolver> resolvers = new ArrayList<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String name = String.valueOf(kv[i]);
            String value = String.valueOf(kv[i + 1]);
            resolvers.add(name.startsWith("raw_") ? Placeholder.parsed(name, value) : Placeholder.unparsed(name, value));
        }
        src.sendMessage(mm.deserialize(template, resolvers.toArray(new TagResolver[0])));
    }

    static String duration(Long ms) {
        if (ms == null || ms < 0) return "?";
        long s = ms / 1000;
        if (s < 60) return s + " s";
        long m = s / 60;
        if (m < 60) return m + " min " + (s % 60) + " s";
        return (m / 60) + " h " + (m % 60) + " min";
    }
}

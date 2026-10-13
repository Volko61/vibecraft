package dev.vibecraft.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;

import dev.vibecraft.ai.AiJob;
import dev.vibecraft.ai.AiJobService;
import dev.vibecraft.ai.AiJobState;
import dev.vibecraft.core.Diagnostic;
import dev.vibecraft.core.ModuleInfo;
import dev.vibecraft.core.ModuleManager;
import dev.vibecraft.core.ModuleState;
import dev.vibecraft.core.ReloadResult;
import dev.vibecraft.ideas.Idea;
import dev.vibecraft.ideas.IdeaQueue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code /vibe} (alias {@code /vc}, permission {@code vibecraft.admin}): manage modules, AI jobs and
 * the audience idea queue. Messages in French (MiniMessage), clickable shortcuts, tab completion.
 *
 * <p>Runs on the main thread; anything that may block (compilation, AI process start, file I/O)
 * goes through futures / async tasks and the answer is delivered back on the main thread.
 */
public final class VibeCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of(
            "help", "menu", "list", "info", "reload", "enable", "disable", "errors", "logs", "new",
            "ai", "stop", "jobs", "reset", "ideas", "accept", "dismiss", "dashboard");

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("aide", "help"), Map.entry("?", "help"), Map.entry("ls", "list"), Map.entry("liste", "list"),
            Map.entry("i", "info"), Map.entry("r", "reload"), Map.entry("rl", "reload"), Map.entry("on", "enable"),
            Map.entry("off", "disable"), Map.entry("err", "errors"), Map.entry("log", "logs"), Map.entry("create", "new"),
            Map.entry("ia", "ai"), Map.entry("idees", "ideas"), Map.entry("idées", "ideas"), Map.entry("web", "dashboard"));

    private static final long RELOAD_REPLY_TIMEOUT_S = 180;

    private final JavaPlugin plugin;
    private final ModuleManager modules;
    private final AiJobService ai;
    private final IdeaQueue ideas;
    /** Depth of onCommand on the main thread (replies sent while > 0 are synchronous). */
    private int executing;
    private final VibeMenu menu;

    public VibeCommand(JavaPlugin plugin, ModuleManager modules, AiJobService ai, IdeaQueue ideas) {
        this.plugin = plugin;
        this.modules = modules;
        this.ai = ai;
        this.ideas = ideas;
        this.menu = new VibeMenu(plugin, modules, ai, ideas);
    }

    // ------------------------------------------------------------------ execution

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? (sender instanceof Player ? "menu" : "help") : args[0].toLowerCase(Locale.ROOT);
        sub = ALIASES.getOrDefault(sub, sub);
        executing++;
        try {
            switch (sub) {
                case "help" -> help(sender, label);
                case "menu" -> {
                    if (sender instanceof Player p) {
                        menu.open(p);
                    } else {
                        error(sender, "Le menu est réservé aux joueurs : utilise <white>/vibe list</white>.");
                    }
                }
                case "list" -> list(sender);
                case "info" -> withId(sender, args, label, "info <id>", id -> info(sender, id));
                case "reload" -> withId(sender, args, label, "reload <id|all>", id -> reload(sender, id));
                case "enable" -> withId(sender, args, label, "enable <id>", id -> enable(sender, id));
                case "disable" -> withId(sender, args, label, "disable <id>", id -> disable(sender, id));
                case "errors" -> withId(sender, args, label, "errors <id>", id -> errors(sender, id));
                case "logs" -> withId(sender, args, label, "logs <id> [lignes]", id -> logs(sender, id, args));
                case "new" -> withId(sender, args, label, "new <id> [description]", id -> create(sender, id, join(args, 2)));
                case "ai" -> withId(sender, args, label, "ai <id> <demande…>", id -> startAi(sender, id, join(args, 2)));
                case "stop" -> withId(sender, args, label, "stop <id>", id -> stopAi(sender, id));
                case "jobs" -> jobs(sender);
                case "reset" -> withId(sender, args, label, "reset <id>", id -> reset(sender, id));
                case "ideas" -> ideasList(sender);
                case "accept" -> accept(sender, args, label);
                case "dismiss" -> dismiss(sender, args, label);
                case "dashboard" -> dashboard(sender);
                default -> {
                    error(sender, "Sous-commande inconnue : <white><arg></white>.", Msg.text("arg", Msg.cut(args[0], 30)));
                    send(sender, Msg.mm(" <gray>Tape</gray> <btn>", Msg.comp("btn",
                            Msg.button("/" + label + " help", NamedTextColor.AQUA, "/vibe help", "Afficher l'aide"))));
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "/vibe " + String.join(" ", args) + " failed", e);
            error(sender, "Erreur interne : <gray><msg></gray>", Msg.text("msg", Msg.cut(String.valueOf(e), 150)));
        } finally {
            executing--;
        }
        return true;
    }

    private interface IdAction {
        void run(String id);
    }

    private void withId(CommandSender sender, String[] args, String label, String usage, IdAction action) {
        if (args.length < 2 || args[1].isBlank()) {
            usage(sender, label, usage);
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!id.equals("all") || !args[0].equalsIgnoreCase("reload")) {
            if (!modules.isValidId(id)) {
                error(sender, "Identifiant invalide : <white><id></white> <gray>(lettres minuscules, chiffres, - et _ ; 40 max)</gray>",
                        Msg.text("id", Msg.cut(args[1], 40)));
                return;
            }
        }
        action.run(id);
    }

    // ------------------------------------------------------------------ help / list / info

    private void help(CommandSender sender, String label) {
        send(sender, Msg.pmm("<white>Coder le serveur en direct</white> <dark_gray>(clique pour remplir)</dark_gray>"));
        helpLine(sender, "menu", "", "menu graphique (inventaire)");
        helpLine(sender, "list", "", "modules et leur état");
        helpLine(sender, "info", " <id>", "détails d'un module");
        helpLine(sender, "reload", " <id|all>", "recompiler à chaud");
        helpLine(sender, "enable", " <id>", "réactiver un module");
        helpLine(sender, "disable", " <id>", "désactiver un module");
        helpLine(sender, "errors", " <id>", "erreurs et diagnostics");
        helpLine(sender, "logs", " <id> [lignes]", "derniers logs");
        helpLine(sender, "new", " <id> [description]", "créer un module vide");
        helpLine(sender, "ai", " <id> <demande…>", "demander une fonctionnalité à l'IA");
        helpLine(sender, "stop", " <id>", "arrêter l'IA sur un module");
        helpLine(sender, "jobs", "", "tâches IA en cours");
        helpLine(sender, "reset", " <id>", "oublier la conversation IA d'un module");
        helpLine(sender, "ideas", "", "idées du public");
        helpLine(sender, "accept", " <n> [id]", "envoyer une idée à l'IA");
        helpLine(sender, "dismiss", " <n>", "ignorer une idée");
        helpLine(sender, "dashboard", "", "lien du tableau de bord");
    }

    private void helpLine(CommandSender sender, String sub, String argsHint, String desc) {
        Component cmd = Component.text("/vibe " + sub, NamedTextColor.AQUA)
                .append(Component.text(argsHint, NamedTextColor.DARK_AQUA))
                .clickEvent(ClickEvent.suggestCommand("/vibe " + sub + (argsHint.isEmpty() ? "" : " ")))
                .hoverEvent(HoverEvent.showText(Component.text("Cliquer pour écrire la commande", NamedTextColor.GRAY)));
        send(sender, Msg.mm(" <cmd> <dark_gray>—</dark_gray> <gray><desc></gray>", Msg.comp("cmd", cmd), Msg.text("desc", desc)));
    }

    private void list(CommandSender sender) {
        List<ModuleInfo> all = new ArrayList<>(modules.list());
        all.sort((a, b) -> a.id().compareTo(b.id()));
        if (all.isEmpty()) {
            send(sender, Msg.pmm("<gray>Aucun module pour l'instant.</gray> <btn1> <btn2>",
                    Msg.comp("btn1", Msg.suggest("Nouveau module", NamedTextColor.AQUA, "/vibe new ", "/vibe new <id> [description]")),
                    Msg.comp("btn2", Msg.suggest("Demander à l'IA", NamedTextColor.LIGHT_PURPLE, "/vibe ai ", "/vibe ai <id> <demande…>"))));
            return;
        }
        Map<String, AiJob> jobs = activeJobs();
        long running = all.stream().filter(m -> m.state() == ModuleState.ENABLED).count();
        send(sender, Msg.pmm("<white><n> module(s)</white> <dark_gray>·</dark_gray> <green><on> actif(s)</green>",
                Msg.text("n", String.valueOf(all.size())), Msg.text("on", String.valueOf(running))));
        for (ModuleInfo m : all) {
            send(sender, moduleLine(m, jobs.get(m.id())));
        }
    }

    private Component moduleLine(ModuleInfo m, AiJob job) {
        String color = Msg.stateColor(m.state());
        StringBuilder extra = new StringBuilder();
        if (m.state() == ModuleState.ENABLED) {
            if (!m.commands().isEmpty()) {
                extra.append(" · /").append(String.join(" /", m.commands().subList(0, Math.min(3, m.commands().size()))));
                if (m.commands().size() > 3) {
                    extra.append(" …");
                }
            }
            if (m.msPerTick() >= 0.01) {
                extra.append(String.format(Locale.ROOT, " · %.2f ms/t", m.msPerTick()));
            }
            if (m.runtimeErrorCount() > 0) {
                extra.append(" · ").append(m.runtimeErrorCount()).append(" err.");
            }
        } else if (m.lastError() != null && !m.lastError().isBlank()) {
            extra.append(" · ").append(Msg.cut(m.lastError(), 50));
        }
        String hover = (m.description() == null || m.description().isBlank() ? m.id() : m.description())
                + "\nchargé : " + Msg.ago(m.lastReloadAt())
                + (m.rolledBack() ? "\n↩ dernière tentative refusée, ancienne version active" : "")
                + "\n\nclic : détails";
        Component name = Component.text(m.id(), NamedTextColor.WHITE)
                .clickEvent(ClickEvent.runCommand("/vibe info " + m.id()))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
        Component reloadBtn = Msg.button("⟳", NamedTextColor.AQUA, "/vibe reload " + m.id(), "Recompiler " + m.id());
        Component aiTag = job == null ? Component.empty()
                : Component.text(" 🤖 " + Msg.jobLabel(job.state()), NamedTextColor.LIGHT_PURPLE)
                        .hoverEvent(HoverEvent.showText(Component.text(Msg.cut(job.prompt(), 120), NamedTextColor.GRAY)));
        return Msg.mm(" <" + color + ">●</" + color + "> <name> <gray><gen></gray><" + color + "><state></" + color + "><rb>"
                        + "<dark_gray><extra></dark_gray><ai> <btn>",
                Msg.comp("name", name),
                Msg.text("gen", m.generation() > 0 ? "v" + m.generation() + " " : ""),
                Msg.text("state", Msg.stateLabel(m.state())),
                Msg.text("rb", m.rolledBack() ? " ↩" : ""),
                Msg.text("extra", extra.toString()),
                Msg.comp("ai", aiTag),
                Msg.comp("btn", reloadBtn));
    }

    private Map<String, AiJob> activeJobs() {
        Map<String, AiJob> out = new HashMap<>();
        try {
            for (AiJob j : ai.jobs()) {
                if (j.state() == AiJobState.RUNNING || j.state() == AiJobState.QUEUED) {
                    out.putIfAbsent(j.moduleId(), j);
                }
            }
        } catch (RuntimeException e) {
            // AI service unavailable: no tags
        }
        return out;
    }

    private Optional<ModuleInfo> known(CommandSender sender, String id) {
        Optional<ModuleInfo> info = modules.info(id);
        if (info.isEmpty()) {
            error(sender, "Module inconnu : <white><id></white>. <btn>", Msg.text("id", id),
                    Msg.comp("btn", Msg.button("Liste", NamedTextColor.AQUA, "/vibe list", "Voir les modules")));
        }
        return info;
    }

    private void info(CommandSender sender, String id) {
        Optional<ModuleInfo> opt = known(sender, id);
        if (opt.isEmpty()) {
            return;
        }
        ModuleInfo m = opt.get();
        String color = Msg.stateColor(m.state());
        send(sender, Msg.pmm("<white><b><id></b></white> <gray><gen></gray><" + color + "><state></" + color + "><rb>",
                Msg.text("id", m.id()), Msg.text("gen", m.generation() > 0 ? "v" + m.generation() + " " : ""),
                Msg.text("state", Msg.stateLabel(m.state())),
                Msg.text("rb", m.rolledBack() ? " (dernière version refusée, ancienne conservée)" : "")));
        if (m.description() != null && !m.description().isBlank()) {
            send(sender, Msg.mm(" <gray><i><desc></i></gray>", Msg.text("desc", Msg.cut(m.description(), 200))));
        }
        send(sender, Msg.mm(" <gray><loaded></gray> <dark_gray>·</dark_gray> <gray>dernière tentative</gray> <white><attempt></white>",
                Msg.text("loaded", m.lastReloadAt() > 0 ? "chargé " + Msg.ago(m.lastReloadAt()) : "jamais chargé"),
                Msg.text("attempt", Msg.ago(m.lastAttemptAt()))));
        send(sender, Msg.mm(" <gray>commandes</gray> <white><cmds></white> <dark_gray>·</dark_gray> <gray>listeners</gray> <white><l></white>"
                        + " <dark_gray>·</dark_gray> <gray>tâches</gray> <white><t></white> <dark_gray>·</dark_gray> <white><ms></white> <gray>ms/tick</gray>",
                Msg.text("cmds", m.commands().isEmpty() ? "aucune" : "/" + String.join(" /", m.commands())),
                Msg.text("l", String.valueOf(m.listeners())), Msg.text("t", String.valueOf(m.tasks())),
                Msg.text("ms", String.format(Locale.ROOT, "%.2f", Double.isFinite(m.msPerTick()) ? m.msPerTick() : 0.0))));
        if (m.runtimeErrorCount() > 0) {
            String last = m.recentErrors().isEmpty() ? "" : m.recentErrors().get(m.recentErrors().size() - 1);
            send(sender, Msg.mm(" <yellow>⚠ <n> erreur(s) d'exécution</yellow> <gray><last></gray>",
                    Msg.text("n", String.valueOf(m.runtimeErrorCount())), Msg.text("last", Msg.cut(last, 120))));
        }
        if (m.lastError() != null && !m.lastError().isBlank()) {
            send(sender, Msg.mm(" <red>✘ <err></red>", Msg.text("err", Msg.cut(m.lastError(), 200))));
            sendDiagnostics(sender, m.diagnostics(), 3);
        }
        AiJob job = activeJobs().get(id);
        if (job != null) {
            String last = job.progress() == null || job.progress().isEmpty() ? "" : job.progress().get(job.progress().size() - 1);
            send(sender, Msg.mm(" <light_purple>🤖 IA <state></light_purple> <gray>« <prompt> »</gray> <dark_gray><last></dark_gray>",
                    Msg.text("state", Msg.jobLabel(job.state())), Msg.text("prompt", Msg.cut(job.prompt(), 80)),
                    Msg.text("last", Msg.cut(last, 60))));
        }
        List<Component> buttons = new ArrayList<>();
        buttons.add(Msg.button("Recharger", NamedTextColor.AQUA, "/vibe reload " + id, "Recompiler et recharger à chaud"));
        if (m.state() == ModuleState.DISABLED) {
            buttons.add(Msg.button("Activer", NamedTextColor.GREEN, "/vibe enable " + id, "Réactiver le module"));
        } else {
            buttons.add(Msg.button("Désactiver", NamedTextColor.GOLD, "/vibe disable " + id, "Décharger et désactiver le module"));
        }
        buttons.add(Msg.button("Erreurs", NamedTextColor.RED, "/vibe errors " + id, "Erreurs et diagnostics"));
        buttons.add(Msg.button("Logs", NamedTextColor.GRAY, "/vibe logs " + id, "Derniers logs"));
        buttons.add(Msg.suggest("IA", NamedTextColor.LIGHT_PURPLE, "/vibe ai " + id + " ", "Demander une modification à l'IA"));
        send(sender, Component.text(" ").append(Component.join(
                net.kyori.adventure.text.JoinConfiguration.separator(Component.text(" ")), buttons)));
    }

    private void sendDiagnostics(CommandSender sender, List<Diagnostic> diags, int max) {
        if (diags == null || diags.isEmpty()) {
            return;
        }
        int shown = 0;
        for (Diagnostic d : diags) {
            if (shown >= max) {
                break;
            }
            if (d == null) {
                continue;
            }
            String where = (d.file() == null || d.file().isEmpty() ? "" : d.file()) + (d.line() > 0 ? ":" + d.line() : "");
            String color = "ERROR".equals(d.kind()) ? "red" : "yellow";
            Component line = Msg.mm("   <dark_gray>›</dark_gray> <" + color + "><where></" + color + "> <gray><msg></gray>",
                    Msg.text("where", where.isEmpty() ? String.valueOf(d.kind()).toLowerCase(Locale.ROOT) : where),
                    Msg.text("msg", Msg.cut(d.message(), 140)));
            if (d.sourceLine() != null && !d.sourceLine().isBlank()) {
                line = line.hoverEvent(HoverEvent.showText(Component.text(d.sourceLine(), NamedTextColor.WHITE)));
            }
            send(sender, line);
            shown++;
        }
        if (diags.size() > shown) {
            send(sender, Msg.mm("   <dark_gray>… et <n> autre(s)</dark_gray>", Msg.text("n", String.valueOf(diags.size() - shown))));
        }
    }

    // ------------------------------------------------------------------ reload / enable / disable

    private void reload(CommandSender sender, String id) {
        if (id.equals("all")) {
            reloadAll(sender);
            return;
        }
        if (!exists(id)) {
            known(sender, id);
            return;
        }
        send(sender, Msg.pmm("<aqua>⟳</aqua> <gray>Compilation de</gray> <white><id></white><gray>…</gray>", Msg.text("id", id)));
        whenDone(sender, () -> modules.reload(id), RELOAD_REPLY_TIMEOUT_S, (r, err) -> reportReload(sender, id, r, err, "rechargé"));
    }

    private void reloadAll(CommandSender sender) {
        List<String> ids = modules.list().stream().filter(m -> m.state() != ModuleState.DISABLED).map(ModuleInfo::id).sorted().toList();
        if (ids.isEmpty()) {
            send(sender, Msg.pmm("<gray>Aucun module actif à recharger.</gray>"));
            return;
        }
        send(sender, Msg.pmm("<aqua>⟳</aqua> <gray>Recompilation de</gray> <white><n></white> <gray>module(s)…</gray>",
                Msg.text("n", String.valueOf(ids.size()))));
        List<CompletableFuture<ReloadResult>> futures = new ArrayList<>();
        for (String id : ids) {
            CompletableFuture<ReloadResult> f;
            try {
                f = modules.reload(id).copy();
            } catch (RuntimeException e) {
                f = CompletableFuture.failedFuture(e);
            }
            futures.add(f.orTimeout(RELOAD_REPLY_TIMEOUT_S, TimeUnit.SECONDS)
                    .exceptionally(t -> new ReloadResult(id, false, null, 0, rootMessage(t), List.of(), 0, false)));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((v, t) -> sync(() -> {
            List<String> failed = new ArrayList<>();
            int ok = 0;
            for (CompletableFuture<ReloadResult> f : futures) {
                ReloadResult r = f.join();
                if (r.ok()) {
                    ok++;
                } else {
                    failed.add(r.id());
                }
            }
            if (failed.isEmpty()) {
                send(sender, Msg.pmm("<green>✔ <n> module(s) rechargé(s)</green>", Msg.text("n", String.valueOf(ok))));
            } else {
                send(sender, Msg.pmm("<green>✔ <n> ok</green> <dark_gray>·</dark_gray> <red>✘ <f> échec(s) :</red> <white><ids></white>",
                        Msg.text("n", String.valueOf(ok)), Msg.text("f", String.valueOf(failed.size())),
                        Msg.text("ids", String.join(", ", failed))));
            }
        }));
    }

    private void enable(CommandSender sender, String id) {
        if (!exists(id)) {
            known(sender, id);
            return;
        }
        send(sender, Msg.pmm("<aqua>⟳</aqua> <gray>Activation de</gray> <white><id></white><gray>…</gray>", Msg.text("id", id)));
        whenDone(sender, () -> modules.enable(id), RELOAD_REPLY_TIMEOUT_S, (r, err) -> reportReload(sender, id, r, err, "activé"));
    }

    private void disable(CommandSender sender, String id) {
        if (!exists(id)) {
            known(sender, id);
            return;
        }
        whenDone(sender, () -> modules.disable(id), 60, (ok, err) -> {
            if (err != null) {
                error(sender, "Impossible de désactiver <white><id></white> : <gray><msg></gray>", Msg.text("id", id),
                        Msg.text("msg", rootMessage(err)));
            } else if (Boolean.TRUE.equals(ok) && sender instanceof Player p && p.hasPermission("vibecraft.notify")) {
                // the notifier already shows "⏸ <id> désactivé [Réactiver]" to this player
                send(sender, Msg.mm(" <dark_gray>↳ <id> déchargé du serveur (marqueur .vibe/disabled)</dark_gray>", Msg.text("id", id)));
            } else if (Boolean.TRUE.equals(ok)) {
                send(sender, Msg.pmm("<gold>⏸</gold> <white><id></white> <gray>désactivé (déchargé du serveur)</gray> <btn>",
                        Msg.text("id", id), Msg.comp("btn", Msg.button("Réactiver", NamedTextColor.GREEN, "/vibe enable " + id,
                                "Recompiler et réactiver " + id))));
            } else {
                error(sender, "Module inconnu : <white><id></white>.", Msg.text("id", id));
            }
        });
    }

    private void reportReload(CommandSender sender, String id, ReloadResult r, Throwable err, String verb) {
        if (err != null) {
            boolean timeout = unwrap(err) instanceof TimeoutException;
            error(sender, "<white><id></white> : <gray><msg></gray>", Msg.text("id", id),
                    Msg.text("msg", timeout ? "pas de réponse après " + RELOAD_REPLY_TIMEOUT_S + " s" : rootMessage(err)));
            return;
        }
        if (r.ok()) {
            long warnings = r.diagnostics() == null ? 0 : r.diagnostics().stream().filter(d -> d != null && "WARNING".equals(d.kind())).count();
            if (sender instanceof Player p && (plugin.getConfig().getBoolean("notify.broadcast-reloads", true)
                    || p.hasPermission("vibecraft.notify"))) {
                // the notifier's "✔ <id> v<gen> chargé à chaud" reaches this player too: only add the details
                send(sender, Msg.mm(" <dark_gray>↳ <id> v<gen> <verb> en <ms> ms<warn></dark_gray>",
                        Msg.text("id", id), Msg.text("gen", String.valueOf(r.generation())), Msg.text("verb", verb),
                        Msg.text("ms", String.valueOf(r.durationMs())),
                        Msg.text("warn", warnings > 0 ? " · " + warnings + " avertissement(s)" : "")));
                return;
            }
            send(sender, Msg.pmm("<green>✔</green> <white><id></white> <gray>v<gen></gray> <green><verb></green> <dark_gray>en <ms> ms<warn></dark_gray>",
                    Msg.text("id", id), Msg.text("gen", String.valueOf(r.generation())), Msg.text("verb", verb),
                    Msg.text("ms", String.valueOf(r.durationMs())),
                    Msg.text("warn", warnings > 0 ? " · " + warnings + " avertissement(s)" : "")));
            return;
        }
        String status;
        if (r.rolledBack()) {
            status = "↩ v" + r.generation() + " toujours active";
        } else if (r.state() == ModuleState.DISABLED) {
            status = "module désactivé : utilise /vibe enable " + id;
        } else {
            status = Msg.stateLabel(r.state());
        }
        send(sender, Msg.pmm("<red>✘</red> <white><id></white> <red>: <err></red> <dark_gray>(<status>)</dark_gray>",
                Msg.text("id", id), Msg.text("err", Msg.cut(r.error() == null ? "échec" : r.error(), 140)), Msg.text("status", status)));
        if (sender instanceof Player p && !p.isOnline()) {
            return;
        }
        sendDiagnostics(sender, r.diagnostics(), 3);
        List<Component> buttons = new ArrayList<>();
        if (r.state() == ModuleState.DISABLED) {
            buttons.add(Msg.button("Activer", NamedTextColor.GREEN, "/vibe enable " + id, "Réactiver " + id));
        } else {
            buttons.add(Msg.button("Réessayer", NamedTextColor.AQUA, "/vibe reload " + id, "Recompiler " + id));
        }
        buttons.add(Msg.button("Erreurs", NamedTextColor.RED, "/vibe errors " + id, "Erreurs et diagnostics"));
        buttons.add(Msg.suggest("Corriger avec l'IA", NamedTextColor.LIGHT_PURPLE, "/vibe ai " + id + " corrige l'erreur de compilation",
                "Demander à l'IA de corriger"));
        send(sender, Component.text(" ").append(Component.join(
                net.kyori.adventure.text.JoinConfiguration.separator(Component.text(" ")), buttons)));
    }

    // ------------------------------------------------------------------ errors / logs

    private void errors(CommandSender sender, String id) {
        Optional<ModuleInfo> opt = known(sender, id);
        if (opt.isEmpty()) {
            return;
        }
        ModuleInfo m = opt.get();
        boolean any = false;
        send(sender, Msg.pmm("<white>Erreurs de <b><id></b></white> <dark_gray>(<state>)</dark_gray>",
                Msg.text("id", id), Msg.text("state", Msg.stateLabel(m.state()))));
        if (m.lastError() != null && !m.lastError().isBlank()) {
            any = true;
            send(sender, Msg.mm(" <red>✘ <err></red>", Msg.text("err", Msg.cut(m.lastError(), 220))));
        }
        List<Diagnostic> diags = m.diagnostics() == null ? List.of() : m.diagnostics();
        List<Diagnostic> errs = diags.stream().filter(d -> d != null && "ERROR".equals(d.kind())).toList();
        if (!errs.isEmpty()) {
            any = true;
            sendDiagnostics(sender, errs, 8);
        }
        if (m.recentErrors() != null && !m.recentErrors().isEmpty()) {
            any = true;
            send(sender, Msg.mm(" <yellow>⚠ <n> erreur(s) d'exécution depuis le dernier chargement</yellow>",
                    Msg.text("n", String.valueOf(m.runtimeErrorCount()))));
            List<String> recent = m.recentErrors();
            for (String e : recent.subList(Math.max(0, recent.size() - 5), recent.size())) {
                send(sender, Msg.mm("   <dark_gray>›</dark_gray> <gray><e></gray>", Msg.text("e", Msg.cut(e, 180))));
            }
        }
        if (!any) {
            send(sender, Msg.mm(" <green>✔ Aucune erreur.</green>"));
        }
        List<Diagnostic> warnings = diags.stream().filter(d -> d != null && !"ERROR".equals(d.kind())).toList();
        if (!warnings.isEmpty()) {
            send(sender, Msg.mm(" <yellow><n> avertissement(s) de compilation</yellow>", Msg.text("n", String.valueOf(warnings.size()))));
            sendDiagnostics(sender, warnings, 3);
        }
    }

    private void logs(CommandSender sender, String id, String[] args) {
        if (known(sender, id).isEmpty()) {
            return;
        }
        int n = 10;
        if (args.length >= 3) {
            try {
                n = Math.max(1, Math.min(100, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                error(sender, "Nombre de lignes invalide : <white><n></white>", Msg.text("n", Msg.cut(args[2], 10)));
                return;
            }
        }
        List<String> lines = modules.logs(id, n);
        if (lines.isEmpty()) {
            send(sender, Msg.pmm("<gray>Aucun log pour</gray> <white><id></white>.", Msg.text("id", id)));
            return;
        }
        send(sender, Msg.pmm("<white>Logs de <b><id></b></white> <dark_gray>(<n> dernières lignes)</dark_gray>",
                Msg.text("id", id), Msg.text("n", String.valueOf(lines.size()))));
        for (String l : lines) {
            send(sender, Msg.mm(" <gray><l></gray>", Msg.text("l", Msg.cut(l, 200))));
        }
    }

    // ------------------------------------------------------------------ new

    private void create(CommandSender sender, String id, String description) {
        boolean autoReload = plugin.getConfig().getBoolean("auto-reload", true);
        async(() -> {
            try {
                return modules.create(id, description);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, (created, err) -> {
            if (err != null) {
                error(sender, "Impossible de créer <white><id></white> : <gray><msg></gray>", Msg.text("id", id),
                        Msg.text("msg", rootMessage(err)));
            } else if (Boolean.TRUE.equals(created)) {
                send(sender, Msg.pmm("<aqua>✚</aqua> Module <white><id></white> créé dans <gray>modules/<id>/</gray>"
                                + " <dark_gray>— chargé automatiquement</dark_gray> <btn1> <btn2>",
                        Msg.text("id", id),
                        Msg.comp("btn1", Msg.button("Info", NamedTextColor.AQUA, "/vibe info " + id, "Voir l'état du module")),
                        Msg.comp("btn2", Msg.suggest("Coder avec l'IA", NamedTextColor.LIGHT_PURPLE, "/vibe ai " + id + " ",
                                "Demander une fonctionnalité à l'IA"))));
                if (!autoReload) {
                    whenDone(sender, () -> modules.reload(id), RELOAD_REPLY_TIMEOUT_S,
                            (r, e) -> reportReload(sender, id, r, e, "chargé"));
                }
            } else {
                error(sender, "Le module <white><id></white> existe déjà. <btn>", Msg.text("id", id),
                        Msg.comp("btn", Msg.button("Info", NamedTextColor.AQUA, "/vibe info " + id, "Voir le module")));
            }
        });
    }

    // ------------------------------------------------------------------ AI

    private void startAi(CommandSender sender, String id, String request) {
        if (request.isBlank()) {
            usage(sender, "vibe", "ai " + id + " <demande…>");
            return;
        }
        if (!ai.available()) {
            error(sender, "IA indisponible : <gray><why></gray>", Msg.text("why", Msg.cut(ai.unavailableReason(), 160)));
            return;
        }
        String by = sender instanceof Player p ? p.getName() : "console";
        async(() -> ai.start(id, request, by), (job, err) -> reportStart(sender, id, request, job, err, null));
    }

    /** Shared reply for /vibe ai and /vibe accept. */
    private void reportStart(CommandSender sender, String id, String request, AiJob job, Throwable err, Runnable onSuccess) {
        if (err != null) {
            Throwable c = unwrap(err);
            if (c instanceof IllegalStateException && !ai.available()) {
                error(sender, "IA indisponible : <gray><why></gray>", Msg.text("why", Msg.cut(ai.unavailableReason(), 160)));
            } else if (c instanceof IllegalStateException && activeJobs().containsKey(id)) {
                error(sender, "Une tâche IA est déjà en cours sur <white><id></white>. <btn1> <btn2>", Msg.text("id", id),
                        Msg.comp("btn1", Msg.button("Arrêter", NamedTextColor.GOLD, "/vibe stop " + id, "Arrêter la tâche en cours")),
                        Msg.comp("btn2", Msg.button("Tâches", NamedTextColor.AQUA, "/vibe jobs", "Voir les tâches IA")));
            } else {
                error(sender, "Impossible de lancer l'IA sur <white><id></white> : <gray><msg></gray>", Msg.text("id", id),
                        Msg.text("msg", rootMessage(err)));
            }
            return;
        }
        if (onSuccess != null) {
            onSuccess.run();
        }
        String state = job != null && job.state() == AiJobState.QUEUED ? "en file d'attente" : "au travail";
        send(sender, Msg.pmm("<light_purple>🤖 IA <state></light_purple> sur <white><id></white> <gray>: « <req> »</gray> <btn1> <btn2>",
                Msg.text("state", state), Msg.text("id", id), Msg.text("req", Msg.cut(request, 100)),
                Msg.comp("btn1", Msg.button("Arrêter", NamedTextColor.GOLD, "/vibe stop " + id, "Arrêter l'IA sur " + id)),
                Msg.comp("btn2", Msg.button("Tâches", NamedTextColor.AQUA, "/vibe jobs", "Voir les tâches IA"))));
    }

    private void stopAi(CommandSender sender, String id) {
        async(() -> ai.stop(id), (stopped, err) -> {
            if (err != null) {
                error(sender, "Arrêt impossible : <gray><msg></gray>", Msg.text("msg", rootMessage(err)));
            } else if (Boolean.TRUE.equals(stopped)) {
                send(sender, Msg.pmm("<gold>⏹</gold> Tâche IA de <white><id></white> arrêtée.", Msg.text("id", id)));
            } else {
                send(sender, Msg.pmm("<gray>Aucune tâche IA en cours sur</gray> <white><id></white>.", Msg.text("id", id)));
            }
        });
    }

    private void jobs(CommandSender sender) {
        List<AiJob> jobs = ai.jobs();
        String head = ai.available() ? "" : " <red>(IA indisponible : " + Msg.MM.escapeTags(Msg.cut(ai.unavailableReason(), 80)) + ")</red>";
        if (jobs.isEmpty()) {
            send(sender, Msg.pmm("<gray>Aucune tâche IA.</gray>" + head + " <btn>",
                    Msg.comp("btn", Msg.suggest("Demander à l'IA", NamedTextColor.LIGHT_PURPLE, "/vibe ai ", "/vibe ai <id> <demande…>"))));
            return;
        }
        send(sender, Msg.pmm("<white>Tâches IA</white>" + head));
        long now = System.currentTimeMillis();
        int shown = 0;
        for (AiJob j : jobs) {
            if (shown++ >= 10) {
                send(sender, Msg.mm(" <dark_gray>… et <n> autre(s)</dark_gray>", Msg.text("n", String.valueOf(jobs.size() - 10))));
                break;
            }
            boolean live = j.state() == AiJobState.RUNNING || j.state() == AiJobState.QUEUED;
            String color = Msg.jobColor(j.state());
            String when;
            if (live) {
                when = j.startedAt() > 0 ? Msg.duration(now - j.startedAt()) : "";
            } else {
                when = Msg.ago(j.endedAt());
            }
            String detail;
            if (live) {
                detail = j.progress() == null || j.progress().isEmpty() ? "" : j.progress().get(j.progress().size() - 1);
            } else {
                detail = j.result() == null ? "" : j.result();
            }
            Component btn = live ? Msg.button("Stop", NamedTextColor.GOLD, "/vibe stop " + j.moduleId(), "Arrêter cette tâche")
                    : Msg.suggest("Relancer", NamedTextColor.LIGHT_PURPLE, "/vibe ai " + j.moduleId() + " ", "Nouvelle demande sur ce module");
            send(sender, Msg.mm(" <" + color + ">●</" + color + "> <white><id></white> <" + color + "><state></" + color + ">"
                            + " <dark_gray><when></dark_gray> <gray>« <prompt> »</gray> <btn>",
                    Msg.text("id", j.moduleId()), Msg.text("state", Msg.jobLabel(j.state())), Msg.text("when", when),
                    Msg.text("prompt", Msg.cut(j.prompt(), 50)), Msg.comp("btn", btn)));
            if (!detail.isBlank()) {
                send(sender, Msg.mm("   <dark_gray>›</dark_gray> <gray><d></gray>", Msg.text("d", Msg.cut(detail, 140))));
            }
        }
    }

    private void reset(CommandSender sender, String id) {
        async(() -> {
            ai.resetConversation(id);
            return Boolean.TRUE;
        }, (v, err) -> {
            if (err != null) {
                error(sender, "Impossible d'oublier la conversation : <gray><msg></gray>", Msg.text("msg", rootMessage(err)));
            } else {
                send(sender, Msg.pmm("<aqua>🧹</aqua> Conversation IA de <white><id></white> oubliée"
                        + " <dark_gray>— la prochaine demande repart de zéro</dark_gray>", Msg.text("id", id)));
            }
        });
    }

    // ------------------------------------------------------------------ ideas

    private void ideasList(CommandSender sender) {
        List<Idea> all = ideas.list();
        List<Idea> fresh = all.stream().filter(i -> "NEW".equals(i.status())).toList();
        long handled = all.size() - fresh.size();
        if (fresh.isEmpty()) {
            send(sender, Msg.pmm("<gray>Aucune idée en attente</gray> <dark_gray>(<h> traitée(s))</dark_gray>."
                    + " <gray>Le public peut proposer avec</gray> <white>/idea \\<texte></white>", Msg.text("h", String.valueOf(handled))));
            return;
        }
        send(sender, Msg.pmm("<yellow>💡 <n> idée(s) en attente</yellow> <dark_gray>(<h> traitée(s))</dark_gray>",
                Msg.text("n", String.valueOf(fresh.size())), Msg.text("h", String.valueOf(handled))));
        // oldest pending first (first come, first served), at most 10
        List<Idea> ordered = new ArrayList<>(fresh);
        ordered.sort((a, b) -> Integer.compare(a.id(), b.id()));
        for (Idea i : ordered.subList(0, Math.min(10, ordered.size()))) {
            send(sender, ideaLine(i));
        }
        if (ordered.size() > 10) {
            send(sender, Msg.mm(" <dark_gray>… et <n> autre(s) (voir le tableau de bord)</dark_gray>",
                    Msg.text("n", String.valueOf(ordered.size() - 10))));
        }
    }

    /** "#3 Steve : « text » [Accepter] [Ignorer]" (also used by /idea for the ops notification). */
    static Component ideaLine(Idea i) {
        String slug = Slug.of(i.text(), "idee-" + i.id());
        Component accept = Msg.button("Accepter", NamedTextColor.GREEN, "/vibe accept " + i.id(),
                "Envoyer à l'IA (module « " + slug + " »)");
        Component acceptAs = Msg.suggest("…dans", NamedTextColor.DARK_GREEN, "/vibe accept " + i.id() + " ",
                "Envoyer à l'IA dans un module précis");
        Component dismiss = Msg.button("Ignorer", NamedTextColor.GRAY, "/vibe dismiss " + i.id(), "Retirer cette idée");
        return Msg.mm(" <yellow>#<n></yellow> <aqua><author></aqua> <gray>:</gray> <white>« <text> »</white> <a> <as> <d>",
                Msg.text("n", String.valueOf(i.id())), Msg.text("author", i.author()), Msg.text("text", Msg.cut(i.text(), 140)),
                Msg.comp("a", accept), Msg.comp("as", acceptAs), Msg.comp("d", dismiss));
    }

    private void accept(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            usage(sender, label, "accept <n> [id]");
            return;
        }
        Optional<Idea> opt = idea(sender, args[1]);
        if (opt.isEmpty()) {
            return;
        }
        Idea idea = opt.get();
        if ("ACCEPTED".equals(idea.status())) {
            error(sender, "L'idée #<n> est déjà acceptée (module <white><id></white>).", Msg.text("n", String.valueOf(idea.id())),
                    Msg.text("id", String.valueOf(idea.moduleId())));
            return;
        }
        // without an explicit id: a fresh module (an idea worded like an existing module's name must not make the
        // AI rewrite that module); "/vibe accept <n> <id>" targets an existing module on purpose
        String id = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : freeModuleId(Slug.of(idea.text(), "idee-" + idea.id()));
        if (!modules.isValidId(id)) {
            error(sender, "Identifiant invalide : <white><id></white>", Msg.text("id", Msg.cut(id, 40)));
            return;
        }
        if (!ai.available()) {
            error(sender, "IA indisponible : <gray><why></gray>", Msg.text("why", Msg.cut(ai.unavailableReason(), 160)));
            return;
        }
        String by = idea.author();
        async(() -> ai.start(id, idea.text(), by), (job, err) -> reportStart(sender, id, idea.text(), job, err, () -> {
            ideas.accept(idea.id(), id);
            send(sender, Msg.pmm("<green>💡 Idée #<n> de <author> acceptée</green> <gray>→ module</gray> <white><id></white>",
                    Msg.text("n", String.valueOf(idea.id())), Msg.text("author", idea.author()), Msg.text("id", id)));
        }));
    }

    /** {@code base}, or {@code base-2}, {@code base-3}... if a module with that id already exists. */
    private String freeModuleId(String base) {
        for (int i = 1; i < 100; i++) {
            String suffix = i == 1 ? "" : "-" + i;
            String id = (base.length() + suffix.length() > 40 ? base.substring(0, 40 - suffix.length()) : base) + suffix;
            if (modules.info(id).isEmpty() && !new java.io.File(modules.modulesDir(), id).exists()) {
                return id;
            }
        }
        return base;
    }

    private void dismiss(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            usage(sender, label, "dismiss <n>");
            return;
        }
        Optional<Idea> opt = idea(sender, args[1]);
        if (opt.isEmpty()) {
            return;
        }
        ideas.dismiss(opt.get().id());
        send(sender, Msg.pmm("<gray>🗑 Idée #<n> ignorée.</gray> <btn>", Msg.text("n", String.valueOf(opt.get().id())),
                Msg.comp("btn", Msg.button("Idées", NamedTextColor.YELLOW, "/vibe ideas", "Voir les idées en attente"))));
    }

    private Optional<Idea> idea(CommandSender sender, String raw) {
        int n;
        try {
            n = Integer.parseInt(raw.startsWith("#") ? raw.substring(1) : raw);
        } catch (NumberFormatException e) {
            error(sender, "Numéro d'idée invalide : <white><n></white>", Msg.text("n", Msg.cut(raw, 12)));
            return Optional.empty();
        }
        Optional<Idea> idea = ideas.get(n);
        if (idea.isEmpty()) {
            error(sender, "Idée #<n> introuvable. <btn>", Msg.text("n", String.valueOf(n)),
                    Msg.comp("btn", Msg.button("Idées", NamedTextColor.YELLOW, "/vibe ideas", "Voir les idées")));
        }
        return idea;
    }

    // ------------------------------------------------------------------ dashboard

    private void dashboard(CommandSender sender) {
        if (!plugin.getConfig().getBoolean("api.enabled", true)) {
            error(sender, "Le tableau de bord est désactivé (api.enabled: false).");
            return;
        }
        String host = plugin.getConfig().getString("api.host", "127.0.0.1");
        if (host == null || host.isBlank() || host.equals("0.0.0.0")) {
            host = "localhost";
        }
        String url = "http://" + host + ":" + plugin.getConfig().getInt("api.port", 8765) + "/";
        String token = plugin.getConfig().getString("api.token", "");
        Component link = Component.text(url, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Ouvrir dans le navigateur", NamedTextColor.GRAY)));
        send(sender, Msg.pmm("<white>Tableau de bord :</white> <link><tok>", Msg.comp("link", link),
                Msg.text("tok", token == null || token.isBlank() ? "" : " (jeton requis)")));
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (args.length <= 1) {
                return filter(SUBCOMMANDS, args.length == 0 ? "" : args[0]);
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            sub = ALIASES.getOrDefault(sub, sub);
            String cur = args[args.length - 1];
            if (args.length == 2) {
                return switch (sub) {
                    case "info", "errors", "logs", "reset" -> filter(moduleIds(null), cur);
                    case "reload" -> {
                        List<String> ids = new ArrayList<>();
                        ids.add("all");
                        ids.addAll(moduleIds(null));
                        yield filter(ids, cur);
                    }
                    case "enable" -> filter(moduleIds(ModuleState.DISABLED), cur);
                    case "disable" -> filter(moduleIds(null).stream()
                            .filter(id -> modules.info(id).map(m -> m.state() != ModuleState.DISABLED).orElse(false)).toList(), cur);
                    case "ai" -> filter(moduleIds(null), cur);
                    case "stop" -> filter(new ArrayList<>(activeJobs().keySet()), cur);
                    case "accept", "dismiss" -> filter(ideas.list().stream().filter(i -> "NEW".equals(i.status()))
                            .map(i -> String.valueOf(i.id())).toList(), cur);
                    default -> List.of();
                };
            }
            if (args.length == 3) {
                return switch (sub) {
                    case "accept" -> {
                        List<String> out = new ArrayList<>();
                        try {
                            ideas.get(Integer.parseInt(args[1])).ifPresent(i -> out.add(Slug.of(i.text(), "idee-" + i.id())));
                        } catch (NumberFormatException ignored) {
                            // no slug suggestion
                        }
                        out.addAll(moduleIds(null));
                        yield filter(out, cur);
                    }
                    case "logs" -> filter(List.of("10", "25", "50", "100"), cur);
                    default -> List.of();
                };
            }
            return List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private List<String> moduleIds(ModuleState onlyState) {
        List<String> ids = new ArrayList<>();
        for (ModuleInfo m : modules.list()) {
            if (onlyState == null || m.state() == onlyState) {
                ids.add(m.id());
            }
        }
        ids.sort(null);
        return ids;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p) && !out.contains(o)) {
                out.add(o);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private boolean exists(String id) {
        if (modules.info(id).isPresent()) {
            return true;
        }
        try {
            return new java.io.File(modules.modulesDir(), id).isDirectory();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String join(String[] args, int from) {
        if (args.length <= from) {
            return "";
        }
        return String.join(" ", Arrays.copyOfRange(args, from, args.length)).strip();
    }

    private void usage(CommandSender sender, String label, String usage) {
        String cmd = "/" + (label == null || label.isBlank() ? "vibe" : label) + " " + usage;
        Component c = Component.text(cmd, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.suggestCommand("/vibe " + usage.split(" ")[0] + " "))
                .hoverEvent(HoverEvent.showText(Component.text("Cliquer pour écrire la commande", NamedTextColor.GRAY)));
        send(sender, Msg.pmm("<gray>Utilisation :</gray> <c>", Msg.comp("c", c)));
    }

    private void error(CommandSender sender, String template, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        send(sender, Msg.pmm("<red>" + template + "</red>", r));
    }

    /**
     * Send if the sender can still receive it (main thread). Late replies (after the command
     * returned) to RCON / command blocks / API senders would be lost, so they are also logged.
     */
    private void send(CommandSender sender, Component c) {
        if (sender instanceof Player p && !p.isOnline()) {
            return;
        }
        sender.sendMessage(c);
        if (executing == 0 && !(sender instanceof Player) && !(sender instanceof ConsoleCommandSender)) {
            plugin.getLogger().info("(-> " + sender.getName() + ") " + Msg.plain(c));
        }
    }

    private void sync(Runnable r) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (plugin.getServer().isPrimaryThread()) {
            r.run();
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, r);
        } catch (RuntimeException e) {
            // plugin disabled meanwhile
        }
    }

    /** Run {@code work} off the main thread, then {@code then} on the main thread with (result, error). */
    private <T> void async(Supplier<T> work, BiConsumer<T, Throwable> then) {
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                T result = null;
                Throwable error = null;
                try {
                    result = work.get();
                } catch (Throwable t) {
                    error = t;
                }
                T r = result;
                Throwable e = error;
                sync(() -> then.accept(r, e));
            });
        } catch (RuntimeException e) {
            then.accept(null, e);
        }
    }

    /** Wait (without blocking) for an engine future, then run {@code then} on the main thread. */
    private <T> void whenDone(CommandSender sender, Supplier<CompletableFuture<T>> future, long timeoutS, BiConsumer<T, Throwable> then) {
        CompletableFuture<T> f;
        try {
            f = future.get().copy().orTimeout(timeoutS, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            f = CompletableFuture.failedFuture(e);
        }
        f.whenComplete((r, t) -> sync(() -> {
            try {
                then.accept(r, t);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "/vibe reply failed", e);
            }
        }));
    }

    private static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while ((c instanceof java.util.concurrent.CompletionException || c instanceof java.util.concurrent.ExecutionException
                || (c instanceof RuntimeException && c.getClass() == RuntimeException.class && c.getCause() != null))
                && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = unwrap(t);
        if (c instanceof TimeoutException) {
            return "délai dépassé";
        }
        String m = c.getMessage();
        return Msg.cut(m == null || m.isBlank() ? c.getClass().getSimpleName() : m, 160);
    }
}

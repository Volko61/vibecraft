package dev.vibecraft.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;
import java.util.logging.Level;

import dev.vibecraft.ai.AiJob;
import dev.vibecraft.ai.AiJobListener;
import dev.vibecraft.ai.AiJobService;
import dev.vibecraft.ai.AiJobState;
import dev.vibecraft.command.Msg;
import dev.vibecraft.core.ModuleEvent;
import dev.vibecraft.core.ModuleEventListener;
import dev.vibecraft.core.ModuleManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Chat / action-bar announcements of module + AI events (ARCHITECTURE.md section 6).
 *
 * <p>Listener callbacks arrive on arbitrary threads: they only enqueue. A main-thread task
 * (every tick) drains the queue and talks to Bukkit; it also refreshes the action bar of running
 * AI jobs once per second (module id + elapsed time). AI jobs only reach the chat when they end, as one short
 * line ("[id] ✔ terminé (1:15)" / "[id] ✘ erreur"), the details in its tooltip.
 */
public final class Notifier {

    private static final String NOTIFY_PERM = "vibecraft.notify";
    private static final int ACTION_BAR_PERIOD_TICKS = 20;

    private final JavaPlugin plugin;
    private final ModuleManager modules;
    private final AiJobService ai;

    /** Work for the main thread. */
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    /** Running/queued jobs by module id (fed by the listener, read by the action bar). */
    private final Map<String, Active> active = new ConcurrentHashMap<>();

    private BukkitTask task;
    private int ticks;

    private record Active(AiJob job, String lastLine, long lastSentAt, long seenAt) {
    }

    public Notifier(JavaPlugin plugin, ModuleManager modules, AiJobService ai) {
        this.plugin = plugin;
        this.modules = modules;
        this.ai = ai;
    }

    /** Subscribe to the module manager and the AI service, start the delivery task. Main thread (onEnable). */
    public void register() {
        ModuleEventListener moduleListener = this::onModuleEvent;
        modules.addListener(moduleListener);
        ai.addListener(new AiJobListener() {
            @Override
            public void onProgress(AiJob job, String line) {
                Notifier.this.onProgress(job, line);
            }

            @Override
            public void onFinished(AiJob job) {
                Notifier.this.onFinished(job);
            }
        });
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // ------------------------------------------------------------------ module events (any thread)

    private void onModuleEvent(ModuleEvent event) {
        if (event == null || event.type() == null) {
            return;
        }
        try {
            queue.add(() -> deliverModuleEvent(event));
        } catch (Throwable ignored) {
            // never throw into the manager
        }
    }

    private void deliverModuleEvent(ModuleEvent e) {
        String id = e.moduleId() == null ? "?" : e.moduleId();
        String reason = Msg.cut(e.message(), 160);
        boolean errorsToOps = plugin.getConfig().getBoolean("notify.errors-to-ops", true);
        switch (e.type()) {
            case RELOADED -> {
                Component line = Msg.pmm("<green>✔</green> <white><id></white> <gray>v<gen></gray> <green>chargé à chaud</green>",
                        Msg.text("id", id), Msg.text("gen", String.valueOf(e.generation())));
                if (plugin.getConfig().getBoolean("notify.broadcast-reloads", true)) {
                    toEveryone(line);
                } else {
                    toNotify(line);
                }
            }
            case FAILED -> toErrors(errorsToOps,
                    Msg.pmm("<red>✘ <white><id></white> n'a pas pu être chargé :</red> <gray><reason></gray> <btn>",
                            Msg.text("id", id), Msg.text("reason", reason), Msg.comp("btn", errorsButton(id))));
            case ROLLED_BACK -> toErrors(errorsToOps,
                    Msg.pmm("<gold>↩ <white><id></white> : nouvelle version refusée, v<gen> conservée</gold> <gray>— <reason></gray> <btn>",
                            Msg.text("id", id), Msg.text("gen", String.valueOf(e.generation())), Msg.text("reason", reason),
                            Msg.comp("btn", errorsButton(id))));
            case TRIPPED -> toErrors(errorsToOps,
                    Msg.pmm("<dark_red>⛔ <white><id></white> coupé par le disjoncteur</dark_red> <gray>— <reason></gray> <btn> <btn2>",
                            Msg.text("id", id), Msg.text("reason", reason), Msg.comp("btn", errorsButton(id)),
                            Msg.comp("btn2", Msg.button("Réarmer", NamedTextColor.GREEN, "/vibe reload " + id,
                                    "Recompiler et relancer " + id))));
            case RUNTIME_ERROR -> toErrors(errorsToOps,
                    Msg.pmm("<yellow>⚠ <white><id></white> :</yellow> <gray><reason></gray> <btn>",
                            Msg.text("id", id), Msg.text("reason", reason), Msg.comp("btn", errorsButton(id))));
            case DISABLED -> toNotify(Msg.pmm("<dark_gray>⏸</dark_gray> <white><id></white> <gray>désactivé</gray> <btn>",
                    Msg.text("id", id), Msg.comp("btn", Msg.button("Réactiver", NamedTextColor.GREEN, "/vibe enable " + id,
                            "Réactiver " + id))));
            case CREATED -> toNotify(Msg.pmm("<aqua>✚</aqua> nouveau module <white><id></white>", Msg.text("id", id)));
            case REMOVED -> toNotify(Msg.pmm("<gray>🗑 module <white><id></white> supprimé</gray>", Msg.text("id", id)));
            default -> {
            }
        }
    }

    private static Component errorsButton(String id) {
        return Msg.button("Erreurs", NamedTextColor.RED, "/vibe errors " + id, "Voir les erreurs de " + id);
    }

    // ------------------------------------------------------------------ AI events (background threads)

    private void onProgress(AiJob job, String line) {
        if (job == null || job.moduleId() == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            String text = Msg.cut(line, 150);
            active.compute(job.moduleId(), (k, old) -> old == null || !old.job().id().equals(job.id())
                    ? new Active(job, text, now, now) : new Active(job, text, old.lastSentAt(), now));
            // Chat gets only the outcome (deliverFinished): progress lines filled half the screen. The action bar
            // shows the running jobs; the details stay in the Claude dialog and the dashboard.
        } catch (Throwable ignored) {
            // never throw into the AI service
        }
    }

    private void onFinished(AiJob job) {
        if (job == null || job.moduleId() == null) {
            return;
        }
        try {
            active.computeIfPresent(job.moduleId(), (k, old) -> old.job().id().equals(job.id()) ? null : old);
            queue.add(() -> deliverFinished(job));
        } catch (Throwable ignored) {
            // never throw into the AI service
        }
    }

    private void deliverFinished(AiJob job) {
        String id = job.moduleId();
        long took = job.startedAt() > 0 && job.endedAt() >= job.startedAt() ? job.endedAt() - job.startedAt() : 0;
        String time = took > 0 ? " en " + Msg.duration(took) : "";
        AiJobState state = job.state() == null ? AiJobState.FAILED : job.state();
        Component line;
        switch (state) {
            case SUCCEEDED -> {
                // one short line; the AI's summary is in the tooltip
                String result = job.result() == null || job.result().isBlank() ? "terminé" : Msg.cut(job.result(), 400);
                line = Msg.mm("<hover:show_text:'<gray><result>'><dark_aqua>[<id>]</dark_aqua> <green>✔ terminé</green>"
                                + "<dark_gray><time></dark_gray></hover>",
                        Msg.text("id", id), Msg.text("result", result), Msg.text("time", time));
            }
            case STOPPED -> line = Msg.mm("<dark_aqua>[<id>]</dark_aqua> <gold>⏹ arrêté</gold><dark_gray><time></dark_gray>",
                    Msg.text("id", id), Msg.text("time", time));
            default -> {
                String why = job.result();
                if ((why == null || why.isBlank()) && job.progress() != null && !job.progress().isEmpty()) {
                    why = job.progress().get(job.progress().size() - 1);
                }
                line = Msg.mm("<hover:show_text:'<gray><why>'><dark_aqua>[<id>]</dark_aqua> <red>✘ erreur</red>"
                                + "<dark_gray><time></dark_gray></hover> <btn>",
                        Msg.text("id", id), Msg.text("time", time), Msg.text("why", why == null || why.isBlank() ? "erreur" : Msg.cut(why, 400)),
                        Msg.comp("btn", Msg.suggest("Relancer", NamedTextColor.AQUA, "/vibe ai " + id + " ",
                                "Relancer l'IA sur " + id)));
            }
        }
        toAiAudience(line);
        // replace the (now stale) progress action bar with the outcome
        String outcome = switch (state) {
            case SUCCEEDED -> "<green>✔ terminé</green>";
            case STOPPED -> "<gold>⏹ arrêté</gold>";
            default -> "<red>✘ échec</red>";
        };
        Component bar = Msg.mm("<aqua>🤖 <white><id></white></aqua> " + outcome + "<dark_gray><time></dark_gray>",
                Msg.text("id", id), Msg.text("time", time));
        Predicate<Player> who = aiAudience();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (who.test(p)) {
                p.sendActionBar(bar);
            }
        }
    }

    // ------------------------------------------------------------------ main thread

    private void tick() {
        Runnable r;
        int budget = 200;
        while (budget-- > 0 && (r = queue.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "notifier: delivery failed", t);
            }
        }
        if (++ticks % ACTION_BAR_PERIOD_TICKS == 0) {
            try {
                actionBar();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "notifier: action bar failed", t);
            }
        }
    }

    private void actionBar() {
        if (active.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long staleMs = (plugin.getConfig().getLong("ai.timeout-seconds", 900) + 120) * 1000L;
        active.values().removeIf(a -> now - a.seenAt() > staleMs);
        List<Active> jobs = new ArrayList<>(active.values());
        if (jobs.isEmpty()) {
            return;
        }
        jobs.sort((a, b) -> a.job().moduleId().compareTo(b.job().moduleId()));
        Component bar;
        if (jobs.size() == 1) {
            Active a = jobs.get(0);
            bar = Msg.mm("<aqua>🤖 <white><id></white> <gray><time></gray></aqua>",
                    Msg.text("id", a.job().moduleId()), Msg.text("time", elapsed(a.job(), now)));
        } else {
            StringBuilder sb = new StringBuilder();
            for (Active a : jobs) {
                if (!sb.isEmpty()) {
                    sb.append("  ·  ");
                }
                sb.append(a.job().moduleId()).append(' ').append(elapsed(a.job(), now));
            }
            bar = Msg.mm("<aqua>🤖 <n> IA au travail :</aqua> <white><list></white>",
                    Msg.text("n", String.valueOf(jobs.size())), Msg.text("list", Msg.cut(sb.toString(), 90)));
        }
        Predicate<Player> who = aiAudience();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (who.test(p)) {
                p.sendActionBar(bar);
            }
        }
    }

    private static String elapsed(AiJob job, long now) {
        if (job.state() == AiJobState.QUEUED || job.startedAt() <= 0) {
            return "en file";
        }
        return Msg.duration(now - job.startedAt());
    }

    // ------------------------------------------------------------------ recipients (main thread)

    private void toEveryone(Component c) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(c);
        }
        console(c);
    }

    private void toNotify(Component c) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(NOTIFY_PERM)) {
                p.sendMessage(c);
            }
        }
        console(c);
    }

    /** Errors: players with vibecraft.notify if notify.errors-to-ops, and always the console (server log). */
    private void toErrors(boolean toPlayers, Component c) {
        if (toPlayers) {
            toNotify(c);
        } else {
            console(c);
        }
    }

    private void toAiAudience(Component c) {
        Predicate<Player> who = aiAudience();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (who.test(p)) {
                p.sendMessage(c);
            }
        }
        console(c);
    }

    /**
     * Who sees the AI at work (chat lines + action bar): players with vibecraft.notify, or everyone with
     * {@code ai.progress-to-everyone: true}. (The old key {@code ai.broadcast-progress}, true in every config.yml
     * written before, is deliberately no longer read: players without permission must not see the AI by default.)
     */
    private Predicate<Player> aiAudience() {
        boolean everyone = plugin.getConfig().getBoolean("ai.progress-to-everyone", false);
        return everyone ? p -> true : p -> p.hasPermission(NOTIFY_PERM);
    }

    private static void console(Component c) {
        CommandSender console = Bukkit.getConsoleSender();
        console.sendMessage(c);
    }
}

package dev.vibecraft.ai;

import java.io.File;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The AI job scheduler behind {@link ClaudeJobService}: queue, concurrency limit, one job per module,
 * resumed conversation per module, listeners, auth gate. Depends only on the JDK + Gson (no Bukkit),
 * so it can be driven by the dev harness. Thread-safe; never blocks the caller for long
 * (processes run on worker threads, listeners are called on a dedicated dispatcher thread).
 */
public final class AiJobEngine implements AiJobService {

    /** Configuration (see {@code ai.*} in config.yml). */
    public record Settings(boolean enabled, String claudePath, List<String> commandOverride, String model,
                           String permissionMode, List<String> allowedTools, boolean restrictTools,
                           int maxConcurrentJobs, Duration timeout, boolean resumeSessions,
                           File workspaceDir, File modulesDir, String apiBaseUrl, String apiToken,
                           String effort, double maxBudgetUsd, boolean checkAuth, List<String> extraArgs,
                           Map<String, String> extraEnv) {
    }

    /** Scaffolds the module directory when it does not exist yet. */
    @FunctionalInterface
    public interface ModuleCreator {
        void ensure(String moduleId, String description) throws Exception;
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    private static final int MAX_PROGRESS = 200;
    private static final int MAX_FINISHED_LISTED = 20;
    private static final int MAX_FINISHED_KEPT = 50;
    private static final int MAX_PROMPT = 8000;

    private final Settings settings;
    private final Logger log;
    private final SessionStore sessions;
    private final PromptTemplate template;
    private final ModuleCreator creator;
    private final List<AiJobListener> listeners = new CopyOnWriteArrayList<>();

    private final ExecutorService workers = Executors.newCachedThreadPool(daemon("VibeCraft-AI-job"));
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(daemon("VibeCraft-AI-events"));
    private final ScheduledExecutorService background = Executors.newSingleThreadScheduledExecutor(daemon("VibeCraft-AI-bg"));

    private final AtomicInteger seq = new AtomicInteger();
    private final Map<String, Job> active = new HashMap<>();
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final Set<Job> running = new LinkedHashSet<>();
    private final ArrayDeque<AiJob> finished = new ArrayDeque<>();
    private final Map<String, AiJob> lastByModule = new HashMap<>();
    private final Map<String, Integer> resetEpoch = new HashMap<>();

    private volatile ClaudeCli cli;
    private volatile String locateInfo = "";
    private volatile long lastLocateAt;
    private volatile CompletableFuture<ClaudeCli.Info> cliInfo = CompletableFuture.completedFuture(null);
    private volatile ClaudeCli.Auth auth = ClaudeCli.Auth.UNKNOWN;
    private final AtomicBoolean authCheckRunning = new AtomicBoolean();
    private final AtomicBoolean authRecheckPending = new AtomicBoolean();
    private volatile boolean shuttingDown;

    public AiJobEngine(Settings settings, Logger log, SessionStore sessions, PromptTemplate template, ModuleCreator creator) {
        this.settings = settings;
        this.log = log;
        this.sessions = sessions;
        this.template = template;
        this.creator = creator;
        if (settings.enabled()) {
            locate();
            checkAuthAsync();
        }
    }

    // ------------------------------------------------------------------------------------------
    // AiJobService
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean available() {
        if (!settings.enabled() || shuttingDown) {
            return false;
        }
        ClaudeCli c = cli;
        if (c == null || !c.exists()) {
            maybeRelocate();
            c = cli;
            if (c == null) {
                return false;
            }
        }
        return auth != ClaudeCli.Auth.NOT_LOGGED_IN;
    }

    @Override
    public String unavailableReason() {
        if (!settings.enabled()) {
            return "IA désactivée (ai.enabled: false dans config.yml)";
        }
        if (shuttingDown) {
            return "le serveur s'arrête";
        }
        ClaudeCli c = cli;
        if (c == null) {
            return "Claude Code introuvable : installe Claude Desktop ou Claude Code, ou règle ai.claude-path (cherché : "
                    + locateInfo + ")";
        }
        if (auth == ClaudeCli.Auth.NOT_LOGGED_IN) {
            String bin = c.binary() == null ? "claude" : c.binary().getPath();
            return "Claude Code n'est pas connecté : lance une fois dans un terminal  \"" + bin + "\" auth login  "
                    + "(ou définis ANTHROPIC_API_KEY / CLAUDE_CODE_OAUTH_TOKEN avant de démarrer le serveur)";
        }
        return "";
    }

    @Override
    public AiJob start(String moduleId, String prompt, String requestedBy) {
        if (moduleId == null || !ID.matcher(moduleId).matches()) {
            throw new IllegalArgumentException("identifiant de module invalide : " + moduleId
                    + " (attendu : minuscules, chiffres, - ou _, 40 caractères max)");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("la demande est vide");
        }
        if (prompt.length() > MAX_PROMPT) {
            throw new IllegalArgumentException("demande trop longue (" + prompt.length() + " caractères, max " + MAX_PROMPT + ")");
        }
        if (!available()) {
            throw new IllegalStateException("IA indisponible : " + unavailableReason());
        }
        String who = requestedBy == null || requestedBy.isBlank() ? "console" : requestedBy.strip();
        Job job;
        synchronized (this) {
            Job cur = active.get(moduleId);
            if (cur != null) {
                throw new IllegalStateException("une IA travaille déjà sur " + moduleId + " (" + cur.state() + ")");
            }
            job = new Job("job-" + seq.incrementAndGet(), moduleId, prompt.strip(), who);
            active.put(moduleId, job); // reserve the module while it is being scaffolded
        }
        try {
            creator.ensure(moduleId, StreamTranslator.oneLine(prompt, 80));
        } catch (Exception e) {
            synchronized (this) {
                active.remove(moduleId, job);
            }
            throw new IllegalStateException("impossible de créer le module " + moduleId + " : " + e.getMessage(), e);
        }
        synchronized (this) {
            if (active.get(moduleId) != job || shuttingDown) { // stopped while the module was being scaffolded
                if (job.state() == AiJobState.QUEUED && job.endedAt() == 0) {
                    active.remove(moduleId, job);
                    job.end(AiJobState.STOPPED, null, null, 0);
                    progress(job, "⏹ Annulé avant de commencer");
                    archive(job);
                }
                return job.snapshot();
            }
            queue.addLast(job);
            pump();
            if (job.state() == AiJobState.QUEUED) {
                progress(job, "⏳ En file d'attente (" + running.size() + " IA déjà au travail, max "
                        + maxConcurrent() + ")");
            }
            return job.snapshot();
        }
    }

    @Override
    public boolean stop(String moduleId) {
        Job j;
        ClaudeRunner r;
        synchronized (this) {
            j = active.get(moduleId);
            if (j == null) {
                return false;
            }
            if (j.state() == AiJobState.QUEUED) {
                queue.remove(j);
                active.remove(moduleId, j);
                j.end(AiJobState.STOPPED, null, null, 0);
                progress(j, "⏹ Annulé avant de commencer");
                archive(j);
                return true;
            }
            j.stopRequested = true;
            r = j.runner;
        }
        if (r != null) {
            try {
                workers.execute(r::stop); // killing the tree may take a moment: never on the caller (main) thread
            } catch (RejectedExecutionException e) {
                r.stop();
            }
        }
        return true;
    }

    @Override
    public synchronized List<AiJob> jobs() {
        List<AiJob> out = new ArrayList<>();
        running.stream().map(Job::snapshot).sorted(Comparator.comparingLong(AiJob::startedAt)).forEach(out::add);
        queue.forEach(j -> out.add(j.snapshot()));
        finished.stream().limit(MAX_FINISHED_LISTED).forEach(out::add);
        return List.copyOf(out);
    }

    @Override
    public synchronized Optional<AiJob> job(String moduleId) {
        Job j = active.get(moduleId);
        if (j != null) {
            return Optional.of(j.snapshot());
        }
        return Optional.ofNullable(lastByModule.get(moduleId));
    }

    @Override
    public void resetConversation(String moduleId) {
        synchronized (this) {
            resetEpoch.merge(moduleId, 1, Integer::sum);
        }
        if (sessions.remove(moduleId)) {
            log.info("[AI] conversation of " + moduleId + " forgotten");
        }
    }

    @Override
    public void addListener(AiJobListener listener) {
        listeners.add(listener);
    }

    @Override
    public void shutdown() {
        shuttingDown = true;
        List<ClaudeRunner> toKill = new ArrayList<>();
        synchronized (this) {
            for (Job j : new ArrayList<>(queue)) {
                queue.remove(j);
                active.remove(j.moduleId, j);
                j.end(AiJobState.STOPPED, null, null, 0);
                progress(j, "⏹ Annulé (arrêt du serveur)");
                archive(j);
            }
            for (Job j : running) {
                j.stopRequested = true;
                if (j.runner != null) {
                    toKill.add(j.runner);
                }
            }
        }
        List<Thread> killers = new ArrayList<>();
        for (ClaudeRunner r : toKill) {
            Thread t = new Thread(r::stop, "VibeCraft-AI-kill");
            t.setDaemon(true);
            t.start();
            killers.add(t);
        }
        try {
            for (Thread t : killers) {
                t.join(4000);
            }
            workers.shutdown();
            workers.awaitTermination(5, TimeUnit.SECONDS);
            dispatcher.shutdown();
            dispatcher.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        workers.shutdownNow();
        dispatcher.shutdownNow();
        background.shutdownNow();
    }

    // ------------------------------------------------------------------------------------------
    // Extra accessors (dashboard / harness)
    // ------------------------------------------------------------------------------------------

    /** The located CLI (null if none). */
    public ClaudeCli cli() {
        return cli;
    }

    /** Version/flags probe of the CLI (null while unknown). */
    public ClaudeCli.Info cliInfo() {
        return cliInfo.getNow(null);
    }

    public ClaudeCli.Auth authState() {
        return auth;
    }

    /** Blocks until the startup probes are done (tests). */
    public void awaitReady(Duration max) {
        long deadline = System.currentTimeMillis() + max.toMillis();
        try {
            cliInfo.get(max.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // timeout / failure: proceed
        }
        while ((authCheckRunning.get() || auth == ClaudeCli.Auth.UNKNOWN && settings.checkAuth() && cli != null
                && !ClaudeCli.envProvidesAuth(envForChildren()))
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Scheduling
    // ------------------------------------------------------------------------------------------

    private int maxConcurrent() {
        return Math.max(1, settings.maxConcurrentJobs());
    }

    /** Must hold the lock. Starts queued jobs while there is capacity. */
    private void pump() {
        while (!shuttingDown && running.size() < maxConcurrent() && !queue.isEmpty()) {
            Job j = queue.pollFirst();
            running.add(j);
            j.markRunning();
            try {
                workers.execute(() -> runJob(j));
            } catch (RejectedExecutionException e) {
                running.remove(j);
                active.remove(j.moduleId, j);
                j.end(AiJobState.FAILED, null, null, 0);
                progress(j, "❌ Impossible de démarrer (arrêt en cours)");
                archive(j);
            }
        }
    }

    private void runJob(Job job) {
        ClaudeRunner.Outcome o = null;
        try {
            ClaudeCli c = currentCli();
            if (c == null) {
                finish(job, null, "❌ Claude Code introuvable");
                return;
            }
            ClaudeCli.Info info = null;
            try {
                info = cliInfo.get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // run with the base flags only
            }
            int epoch;
            synchronized (this) {
                epoch = resetEpoch.getOrDefault(job.moduleId, 0);
            }
            String resume = settings.resumeSessions() ? sessions.get(job.moduleId).orElse(null) : null;
            progress(job, "🚀 Claude se met au travail" + (resume != null ? " (suite de la conversation)" : "") + "…");
            o = runOnce(job, c, info, resume);
            if (resume != null && o.sessionNotFound() && !job.stopRequested) {
                sessions.remove(job.moduleId);
                progress(job, "♻ Conversation précédente introuvable : nouvelle conversation");
                o = runOnce(job, c, info, null);
            }
            if (o.sessionId() != null && o.realActivity() && settings.resumeSessions()) {
                boolean resetMeanwhile;
                synchronized (this) {
                    resetMeanwhile = resetEpoch.getOrDefault(job.moduleId, 0) != epoch;
                }
                if (!resetMeanwhile) {
                    sessions.put(job.moduleId, o.sessionId());
                }
            }
            if (o.authFailed()) {
                setAuth(ClaudeCli.Auth.NOT_LOGGED_IN, "job " + job.id);
            } else if (o.realActivity()) {
                setAuth(ClaudeCli.Auth.OK, "job " + job.id);
            }
            finish(job, o, null);
        } catch (Throwable t) {
            log.log(Level.WARNING, "[AI] job " + job.id + " crashed", t);
            finish(job, o, "❌ Erreur interne : " + t);
        }
    }

    private ClaudeRunner.Outcome runOnce(Job job, ClaudeCli c, ClaudeCli.Info info, String resume) {
        String prompt = renderPrompt(job, resume != null);
        List<String> extra = new ArrayList<>();
        List<String> tools = null;
        if (info != null) {
            if (info.supports("--permission-prompts")) {
                extra.add("--permission-prompts");
                extra.add("none"); // nobody answers prompts in headless mode: deny instead of waiting
            }
            if (info.supports("--strict-mcp-config")) {
                extra.add("--strict-mcp-config"); // no user MCP servers: faster start, focused agent
            }
            if (settings.effort() != null && !settings.effort().isBlank() && info.supports("--effort")) {
                extra.add("--effort");
                extra.add(settings.effort());
            }
            if (settings.maxBudgetUsd() > 0 && info.supports("--max-budget-usd")) {
                extra.add("--max-budget-usd");
                extra.add(String.format(Locale.ROOT, "%.2f", settings.maxBudgetUsd()));
            }
            if (settings.restrictTools() && info.supports("--tools")) {
                tools = toolNames(settings.allowedTools());
            }
        }
        if (settings.extraArgs() != null) {
            extra.addAll(settings.extraArgs());
        }
        Map<String, String> env = ClaudeCli.childEnv(System.getenv(), childExtraEnv(job.moduleId));
        ClaudeRunner.Request req = new ClaudeRunner.Request(c.commandPrefix(), settings.workspaceDir(), prompt,
                settings.model(), settings.permissionMode(), settings.allowedTools(), tools, resume,
                settings.timeout(), env, extra, job.moduleId);
        ClaudeRunner runner = new ClaudeRunner(req, line -> progress(job, line));
        job.runner = runner;
        if (job.stopRequested) {
            runner.stop();
        }
        return runner.run();
    }

    private String renderPrompt(Job job, boolean resumed) {
        File ws = settings.workspaceDir().getAbsoluteFile();
        File md = settings.modulesDir().getAbsoluteFile();
        String rel;
        try {
            rel = ws.toPath().normalize().relativize(md.toPath().normalize()).toString().replace('\\', '/');
            if (rel.startsWith("..") || rel.isEmpty()) {
                rel = md.getPath().replace('\\', '/');
            }
        } catch (IllegalArgumentException e) {
            rel = md.getPath().replace('\\', '/');
        }
        Map<String, String> v = new LinkedHashMap<>();
        v.put("MODULE_ID", job.moduleId);
        v.put("MODULE_DIR", rel + "/" + job.moduleId);
        v.put("MODULE_DIR_ABS", new File(md, job.moduleId).getPath());
        v.put("PACKAGE", PromptTemplate.packageFor(job.moduleId));
        v.put("API_BASE", settings.apiBaseUrl());
        v.put("AUTH_HEADER", settings.apiToken() == null || settings.apiToken().isBlank() ? ""
                : " -H \"X-Vibe-Token: " + settings.apiToken() + "\"");
        // the request is audience text: it must not be able to close the <demande> block of the template
        v.put("REQUEST", job.prompt.replaceAll("(?i)<\\s*/?\\s*demande\\s*>", "«demande»"));
        v.put("REQUESTED_BY", job.requestedBy);
        v.put("CONVERSATION_NOTE", resumed ? PromptTemplate.NOTE_RESUMED : PromptTemplate.NOTE_FIRST);
        return template.render(v);
    }

    private Map<String, String> childExtraEnv(String moduleId) {
        Map<String, String> m = new HashMap<>();
        if (settings.extraEnv() != null) {
            m.putAll(settings.extraEnv());
        }
        m.put("VIBECRAFT_API", settings.apiBaseUrl());
        m.put("VIBECRAFT_MODULE", moduleId);
        return m;
    }

    private Map<String, String> envForChildren() {
        Map<String, String> m = new HashMap<>(System.getenv());
        if (settings.extraEnv() != null) {
            m.putAll(settings.extraEnv());
        }
        return m;
    }

    /** "Bash(curl:*)" -> "Bash"; duplicates removed. */
    static List<String> toolNames(List<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        for (String a : allowed) {
            String n = a.strip();
            int p = n.indexOf('(');
            if (p > 0) {
                n = n.substring(0, p).strip();
            }
            if (!n.isEmpty()) {
                names.add(n);
            }
        }
        return names.isEmpty() ? null : List.copyOf(names);
    }

    private void finish(Job job, ClaudeRunner.Outcome o, String internalError) {
        String finalLine;
        AiJobState state;
        String result = null;
        double cost = 0;
        List<String> details = new ArrayList<>();
        if (internalError != null || o == null) {
            state = AiJobState.FAILED;
            finalLine = internalError != null ? internalError : "❌ Échec";
        } else {
            StreamTranslator.Final fin = o.result();
            cost = fin == null ? 0 : fin.costUsd();
            ClaudeRunner.End end = o.end();
            if (job.stopRequested && end == ClaudeRunner.End.COMPLETED && (fin == null || fin.isError())) {
                end = ClaudeRunner.End.STOPPED; // e.g. stop() between a failed --resume and its retry
            }
            switch (end) {
                case STOPPED -> {
                    state = AiJobState.STOPPED;
                    finalLine = "⏹ Arrêté";
                }
                case TIMED_OUT -> {
                    state = AiJobState.FAILED;
                    finalLine = "⏰ Temps limite dépassé (" + settings.timeout().toSeconds() + " s) : IA arrêtée";
                }
                case LAUNCH_FAILED -> {
                    state = AiJobState.FAILED;
                    finalLine = "❌ Impossible de lancer Claude Code : " + o.launchError();
                }
                default -> {
                    if (fin != null && !fin.isError()) {
                        state = AiJobState.SUCCEEDED;
                        result = fin.text() == null ? "" : fin.text().strip();
                        finalLine = "✅ " + (result.isEmpty() ? "Terminé"
                                : StreamTranslator.oneLine(StreamTranslator.stripFormatting(result), 300));
                    } else {
                        state = AiJobState.FAILED;
                        if (o.authFailed()) {
                            finalLine = "🔑 Claude Code n'est pas connecté : lance une fois « claude auth login » dans un terminal, puis réessaie";
                        } else if (o.apiError() != null && o.apiError().contains("rate_limit")) {
                            finalLine = "⏳ Limite d'utilisation de Claude atteinte : réessaie plus tard";
                        } else if (fin != null) {
                            String why = fin.text() != null && !fin.text().isBlank() ? fin.text()
                                    : !fin.errors().isEmpty() ? fin.errors().get(0) : String.valueOf(fin.subtype());
                            finalLine = "❌ " + StreamTranslator.oneLine(why, 200);
                        } else {
                            finalLine = "❌ Claude s'est arrêté sans réponse (code " + o.exitCode() + ")";
                        }
                        result = fin == null ? null : fin.text();
                        if (fin == null || !o.authFailed()) {
                            List<String> tail = o.stderrTail();
                            for (String s : tail.subList(Math.max(0, tail.size() - 3), tail.size())) {
                                details.add("↳ " + StreamTranslator.oneLine(s, 150));
                            }
                        }
                    }
                }
            }
            log.info(String.format(Locale.ROOT, "[AI] %s (%s) %s in %.1f s, cost $%.4f, reloads ok=%d failed=%d, exit=%d, session=%s",
                    job.id, job.moduleId, state, o.durationMs() / 1000.0, cost, o.reloadOk(), o.reloadFailed(),
                    o.exitCode(), o.sessionId()));
        }
        for (String d : details) {
            progress(job, d);
        }
        synchronized (this) {
            job.end(state, result, o == null ? null : o.sessionId(), cost);
            running.remove(job);
            active.remove(job.moduleId, job);
            progress(job, finalLine);
            archive(job);
            pump();
        }
    }

    /** Must hold the lock: record a terminal job and notify onFinished. */
    private void archive(Job j) {
        AiJob snap = j.snapshot();
        finished.addFirst(snap);
        while (finished.size() > MAX_FINISHED_KEPT) {
            finished.removeLast();
        }
        lastByModule.put(j.moduleId, snap);
        dispatch(l -> l.onFinished(snap));
    }

    private void progress(Job job, String line) {
        job.addProgress(line);
        AiJob snap = job.snapshot();
        dispatch(l -> l.onProgress(snap, line));
    }

    private void dispatch(Consumer<AiJobListener> call) {
        if (listeners.isEmpty()) {
            return;
        }
        try {
            dispatcher.execute(() -> {
                for (AiJobListener l : listeners) {
                    try {
                        call.accept(l);
                    } catch (Throwable t) {
                        log.log(Level.FINE, "[AI] listener failed", t);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // shut down
        }
    }

    // ------------------------------------------------------------------------------------------
    // CLI location & auth
    // ------------------------------------------------------------------------------------------

    private synchronized void locate() {
        lastLocateAt = System.currentTimeMillis();
        Map<String, String> extraEnv = settings.extraEnv() == null ? Map.of() : settings.extraEnv();
        if (settings.commandOverride() != null && !settings.commandOverride().isEmpty()) {
            cli = new ClaudeCli(null, settings.commandOverride(), extraEnv);
            locateInfo = "override";
        } else {
            ClaudeLocator.Located l = new ClaudeLocator().locate(settings.claudePath());
            cli = l.binary().map(b -> new ClaudeCli(b, ClaudeLocator.commandFor(b), extraEnv)).orElse(null);
            locateInfo = l.searched();
        }
        ClaudeCli c = cli;
        if (c == null) {
            log.warning("[AI] Claude Code CLI not found (searched: " + locateInfo + "). AI jobs are disabled.");
            cliInfo = CompletableFuture.completedFuture(null);
            return;
        }
        CompletableFuture<ClaudeCli.Info> f = new CompletableFuture<>();
        cliInfo = f;
        try {
            background.execute(() -> {
                ClaudeCli.Info info = c.probe(settings.workspaceDir(), Duration.ofSeconds(30));
                log.info("[AI] Claude Code " + info.version() + " -> " + String.join(" ", c.commandPrefix())
                        + " (" + locateInfo + ")");
                f.complete(info);
            });
        } catch (RejectedExecutionException e) {
            f.complete(null);
        }
    }

    private void maybeRelocate() {
        if (System.currentTimeMillis() - lastLocateAt > 15_000) {
            ClaudeCli before = cli;
            locate();
            if (before == null && cli != null) {
                checkAuthAsync();
            }
        }
    }

    private ClaudeCli currentCli() {
        ClaudeCli c = cli;
        if (c == null || !c.exists()) {
            locate(); // e.g. the desktop app updated itself and removed the old version directory
            c = cli;
        }
        return c;
    }

    private void checkAuthAsync() {
        ClaudeCli c = cli;
        if (!settings.checkAuth() || c == null || shuttingDown) {
            return;
        }
        if (ClaudeCli.envProvidesAuth(envForChildren())) {
            return; // credentials come from the environment; trust them
        }
        if (!authCheckRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            background.execute(() -> {
                try {
                    ClaudeCli.AuthInfo a = c.authStatus(settings.workspaceDir(), Duration.ofSeconds(20));
                    if (a.state() != ClaudeCli.Auth.UNKNOWN || auth == ClaudeCli.Auth.UNKNOWN) {
                        setAuth(a.state(), "auth status" + (a.method().isEmpty() ? "" : " (" + a.method() + ")")
                                + (a.detail().isEmpty() ? "" : ": " + a.detail()));
                    }
                } finally {
                    authCheckRunning.set(false);
                    if (auth == ClaudeCli.Auth.NOT_LOGGED_IN) {
                        scheduleAuthRecheck();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            authCheckRunning.set(false);
        }
    }

    /** While not logged in, re-check every 10 s so the AI becomes available as soon as the user logs in. */
    private void scheduleAuthRecheck() {
        if (shuttingDown || !authRecheckPending.compareAndSet(false, true)) {
            return;
        }
        try {
            background.schedule(() -> {
                authRecheckPending.set(false);
                checkAuthAsync();
            }, 10, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            authRecheckPending.set(false);
        }
    }

    private void setAuth(ClaudeCli.Auth state, String source) {
        ClaudeCli.Auth prev = auth;
        auth = state;
        if (prev != state) {
            if (state == ClaudeCli.Auth.NOT_LOGGED_IN) {
                log.warning("[AI] Claude Code is not logged in (" + source + "). " + unavailableReason());
            } else {
                log.info("[AI] Claude Code auth: " + state + " (" + source + ")");
            }
        }
        if (state == ClaudeCli.Auth.NOT_LOGGED_IN) {
            scheduleAuthRecheck();
        }
    }

    private static ThreadFactory daemon(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // ------------------------------------------------------------------------------------------

    /** Mutable job state; {@link #snapshot()} produces the immutable {@link AiJob}. */
    private static final class Job {
        final String id;
        final String moduleId;
        final String prompt;
        final String requestedBy;
        private AiJobState state = AiJobState.QUEUED;
        private long startedAt;
        private long endedAt;
        private final ArrayDeque<String> progress = new ArrayDeque<>();
        private String result;
        private String sessionId;
        private double cost;
        volatile ClaudeRunner runner;
        volatile boolean stopRequested;

        Job(String id, String moduleId, String prompt, String requestedBy) {
            this.id = id;
            this.moduleId = moduleId;
            this.prompt = prompt;
            this.requestedBy = requestedBy;
        }

        synchronized AiJobState state() {
            return state;
        }

        synchronized long endedAt() {
            return endedAt;
        }

        synchronized void markRunning() {
            state = AiJobState.RUNNING;
            startedAt = System.currentTimeMillis();
        }

        synchronized void end(AiJobState s, String result, String sessionId, double cost) {
            state = s;
            endedAt = System.currentTimeMillis();
            this.result = result;
            if (sessionId != null) {
                this.sessionId = sessionId;
            }
            this.cost = cost;
        }

        synchronized void addProgress(String line) {
            progress.addLast(line);
            while (progress.size() > MAX_PROGRESS) {
                progress.removeFirst();
            }
        }

        synchronized AiJob snapshot() {
            return new AiJob(id, moduleId, prompt, requestedBy, state, startedAt, endedAt, List.copyOf(progress),
                    result, sessionId, cost);
        }
    }
}

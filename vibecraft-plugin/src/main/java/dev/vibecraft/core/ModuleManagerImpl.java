package dev.vibecraft.core;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import dev.vibecraft.api.VibeModule;
import dev.vibecraft.core.ModuleRecord.KeptBuild;
import dev.vibecraft.core.ModuleRecord.Loaded;
import dev.vibecraft.core.compile.ModuleCompiler;
import dev.vibecraft.core.ctx.ModuleContextImpl;
import dev.vibecraft.core.guard.Gen;
import dev.vibecraft.core.guard.Guard;
import dev.vibecraft.core.guard.ModuleKilledError;
import dev.vibecraft.core.guard.ModuleTimeoutError;
import dev.vibecraft.core.load.ClassHierarchy;
import dev.vibecraft.core.load.Instrumenter;
import dev.vibecraft.core.load.ModuleClassLoader;
import dev.vibecraft.core.watch.ModuleFiles;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The VibeCraft engine: discovers, compiles (off the main thread), instruments, hot-swaps (on the
 * main thread), guards and unloads modules of {@code workspace/modules/<id>/}.
 */
public final class ModuleManagerImpl implements ModuleManager {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");

    private final JavaPlugin plugin;
    private final Logger log;
    private final EngineConfig cfg;
    private final ConcurrentHashMap<String, ModuleRecord> records = new ConcurrentHashMap<>();
    private final Map<String, Object> shared = new ConcurrentHashMap<>();
    private final List<ModuleEventListener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean started;
    private volatile boolean shuttingDown;
    private ModuleCompiler compiler;
    private ExecutorService compileExecutor;
    private ScheduledExecutorService io;
    private ScheduledExecutorService watcher;
    private ExecutorService notifier;
    private Listener tickListener;

    private volatile boolean commandsDirty;
    private volatile boolean recipesDirty;
    private final Set<String> warnedIds = ConcurrentHashMap.newKeySet();

    public ModuleManagerImpl(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.cfg = EngineConfig.from(plugin.getConfig());
    }

    // ================================================================== accessors used by records

    JavaPlugin plugin() {
        return plugin;
    }

    Map<String, Object> shared() {
        return shared;
    }

    long handlerBudgetNanos() {
        return TimeUnit.MILLISECONDS.toNanos(cfg.handlerBudgetMs());
    }

    long enableBudgetNanos() {
        return TimeUnit.MILLISECONDS.toNanos(cfg.enableBudgetMs());
    }

    void markCommandsDirty() {
        commandsDirty = true;
    }

    void markRecipesDirty() {
        recipesDirty = true;
    }

    // ================================================================== lifecycle

    @Override
    public void start() {
        if (started) {
            return;
        }
        started = true;
        mkdirs(cfg.modulesDir());
        mkdirs(cfg.dataDir());
        Guard.init(cfg.tickBudgetMs());

        tickListener = new Listener() {
        };
        Bukkit.getPluginManager().registerEvent(ServerTickStartEvent.class, tickListener, EventPriority.LOWEST,
                (l, e) -> onTickStart(), plugin);

        List<File> cp = computeClasspath();
        compiler = new ModuleCompiler(cp);
        if (!compiler.available()) {
            log.severe("No Java compiler available (the server must run on a JDK, not a JRE): modules cannot be compiled");
        }
        compileExecutor = Executors.newFixedThreadPool(2, daemonFactory("VibeCraft-compiler"));
        io = Executors.newSingleThreadScheduledExecutor(daemonFactory("VibeCraft-io"));
        watcher = Executors.newSingleThreadScheduledExecutor(daemonFactory("VibeCraft-watcher"));
        notifier = Executors.newSingleThreadExecutor(daemonFactory("VibeCraft-events"));

        // discovery
        List<String> toLoad = new ArrayList<>();
        File[] dirs = cfg.modulesDir().listFiles(File::isDirectory);
        if (dirs != null) {
            for (File d : dirs) {
                String id = d.getName();
                if (!isValidId(id)) {
                    if (!id.startsWith(".")) {
                        log.warning("Ignoring modules/" + id + ": invalid module id (expected [a-z0-9][a-z0-9_-]{0,39})");
                    }
                    continue;
                }
                ModuleFiles.Snapshot s = ModuleFiles.snapshot(d);
                if (!s.hasContent()) {
                    continue;
                }
                ModuleRecord rec = new ModuleRecord(this, id, d);
                rec.description = readDescription(d);
                rec.observedFp = s.fingerprint();
                rec.observedSince = System.currentTimeMillis();
                if (StatusFiles.marker(d).exists()) {
                    rec.disabled = true;
                    rec.state = ModuleState.DISABLED;
                } else if (s.hasJava()) {
                    toLoad.add(id);
                }
                records.put(id, rec);
            }
        }
        log.info("VibeCraft engine: " + records.size() + " module(s) in " + cfg.modulesDir() + " (" + toLoad.size()
                + " to load), compile classpath: " + cp.size() + " jars");
        if (toLoad.size() < 2) {
            for (int i = 0; i < 2 - toLoad.size(); i++) {
                compileExecutor.execute(this::warmUp);
            }
        }
        for (String id : toLoad) {
            ModuleRecord rec = records.get(id);
            rec.triggeredFp = rec.observedFp;
            submit(rec);
        }
        io.scheduleWithFixedDelay(this::flushStatus, 250, 250, TimeUnit.MILLISECONDS);
        io.scheduleWithFixedDelay(this::autosaveStores, 60, 60, TimeUnit.SECONDS);
        watcher.scheduleWithFixedDelay(this::poll, cfg.pollIntervalMs(), cfg.pollIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private void warmUp() {
        try {
            long t0 = System.nanoTime();
            compiler.warmUp();
            log.fine("javac warm-up: " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        } catch (Throwable t) {
            log.log(Level.FINE, "javac warm-up failed", t);
        }
    }

    @Override
    public void shutdown() {
        if (!started || shuttingDown) {
            return;
        }
        shuttingDown = true;
        watcher.shutdownNow();
        compileExecutor.shutdownNow();
        for (ModuleRecord rec : records.values()) {
            Loaded cur = rec.current;
            if (cur != null) {
                try {
                    unloadGen(rec, cur, "server shutdown");
                } catch (Throwable t) {
                    log.log(Level.WARNING, "error while unloading module " + rec.id, t);
                }
                rec.current = null;
            }
            rec.store.saveIfDirty();
            failPending(rec, "server is shutting down");
        }
        if (tickListener != null) {
            HandlerList.unregisterAll(tickListener);
        }
        io.shutdown();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        notifier.shutdown();
        Guard.shutdown();
    }

    private void failPending(ModuleRecord rec, String why) {
        CompletableFuture<ReloadResult> a;
        CompletableFuture<ReloadResult> b;
        synchronized (rec) {
            a = rec.inFlight;
            b = rec.followUp;
            rec.inFlight = null;
            rec.followUp = null;
        }
        ReloadResult r = failure(rec, why, List.of(), 0);
        if (a != null) {
            a.complete(r);
        }
        if (b != null) {
            b.complete(r);
        }
    }

    // ================================================================== queries

    @Override
    public List<ModuleInfo> list() {
        List<ModuleRecord> rs = new ArrayList<>(records.values());
        rs.sort(Comparator.comparing(r -> r.id));
        List<ModuleInfo> out = new ArrayList<>(rs.size());
        for (ModuleRecord r : rs) {
            out.add(r.info());
        }
        return out;
    }

    @Override
    public Optional<ModuleInfo> info(String id) {
        ModuleRecord r = id == null ? null : records.get(id);
        return r == null ? Optional.empty() : Optional.of(r.info());
    }

    @Override
    public boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    @Override
    public List<String> logs(String id, int limit) {
        ModuleRecord r = id == null ? null : records.get(id);
        return r == null ? List.of() : r.ring.tail(Math.max(0, limit));
    }

    @Override
    public File modulesDir() {
        return cfg.modulesDir();
    }

    @Override
    public File dataDir() {
        return cfg.dataDir();
    }

    @Override
    public void addListener(ModuleEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    // ================================================================== records

    /** Existing record, or a new one if the directory exists on disk (discovered on demand). */
    private ModuleRecord recordFor(String id) {
        ModuleRecord r = records.get(id);
        if (r != null || !isValidId(id)) {
            return r;
        }
        File d = new File(cfg.modulesDir(), id);
        if (!d.isDirectory() || !ModuleFiles.snapshot(d).hasContent()) {
            return null;
        }
        boolean[] created = new boolean[1];
        r = records.computeIfAbsent(id, k -> {
            created[0] = true;
            ModuleRecord nr = new ModuleRecord(this, k, d);
            nr.description = readDescription(d);
            if (StatusFiles.marker(d).exists()) {
                nr.disabled = true;
                nr.state = ModuleState.DISABLED;
            }
            return nr;
        });
        if (created[0]) {
            r.ring.add("INFO", "module discovered");
            emit(r, ModuleEvent.Type.CREATED, "module discovered", 0);
        }
        return r;
    }

    // ================================================================== reload pipeline

    @Override
    public CompletableFuture<ReloadResult> reload(String id) {
        if (!isValidId(id)) {
            return CompletableFuture.completedFuture(new ReloadResult(String.valueOf(id), false, ModuleState.FAILED, 0,
                    "invalid module id (expected [a-z0-9][a-z0-9_-]{0,39})", List.of(), 0, false));
        }
        if (!started || shuttingDown) {
            return CompletableFuture.completedFuture(new ReloadResult(id, false, ModuleState.FAILED, 0,
                    shuttingDown ? "server is shutting down" : "engine not started", List.of(), 0, false));
        }
        ModuleRecord rec = recordFor(id);
        if (rec == null) {
            File d = new File(cfg.modulesDir(), id);
            String why = d.isDirectory()
                    ? "modules/" + id + "/ is empty: write the module's .java file first"
                    : "unknown module: " + id + " (no directory modules/" + id + "/)";
            return CompletableFuture.completedFuture(new ReloadResult(id, false, ModuleState.FAILED, 0, why,
                    List.of(new Diagnostic("ERROR", "", -1, -1, why, "")), 0, false));
        }
        if (rec.disabled) {
            return CompletableFuture.completedFuture(new ReloadResult(id, false, ModuleState.DISABLED, 0,
                    "module is disabled", List.of(), 0, false));
        }
        return submit(rec);
    }

    private CompletableFuture<ReloadResult> submit(ModuleRecord rec) {
        synchronized (rec) {
            if (rec.inFlight != null) {
                if (rec.followUp == null) {
                    rec.followUp = new CompletableFuture<>();
                }
                return rec.followUp;
            }
            CompletableFuture<ReloadResult> f = new CompletableFuture<>();
            rec.inFlight = f;
            startPipeline(rec, f);
            return f;
        }
    }

    private void startPipeline(ModuleRecord rec, CompletableFuture<ReloadResult> f) {
        long t0 = System.nanoTime();
        rec.state = ModuleState.COMPILING;
        rec.statusDirty = true;
        try {
            compileExecutor.execute(() -> {
                CompileOutcome out;
                try {
                    out = prepare(rec, t0);
                } catch (Throwable t) {
                    log.log(Level.WARNING, "internal error while compiling module " + rec.id, t);
                    out = CompileOutcome.failure(t0, "internal error: " + t, List.of(
                            new Diagnostic("ERROR", "", -1, -1, "internal error: " + t, "")), Map.of(), -1, null);
                }
                CompileOutcome o = out;
                runOnMain(rec, f, () -> swap(rec, o));
            });
        } catch (RejectedExecutionException e) {
            finish(rec, f, failure(rec, "server is shutting down", List.of(), 0));
        }
    }

    /** Run the swap on the main thread, then complete the future (and start any coalesced follow-up). */
    private void runOnMain(ModuleRecord rec, CompletableFuture<ReloadResult> f, java.util.function.Supplier<ReloadResult> swap) {
        Runnable r = () -> {
            ReloadResult res;
            try {
                res = swap.get();
            } catch (Throwable t) {
                log.log(Level.SEVERE, "internal error while swapping module " + rec.id, t);
                res = failure(rec, "internal error: " + t, List.of(), 0);
            }
            finish(rec, f, res);
        };
        if (shuttingDown) {
            finish(rec, f, failure(rec, "server is shutting down", List.of(), 0));
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            r.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, r);
        } catch (Throwable t) {
            finish(rec, f, failure(rec, "server is shutting down", List.of(), 0));
        }
    }

    private void finish(ModuleRecord rec, CompletableFuture<ReloadResult> f, ReloadResult r) {
        CompletableFuture<ReloadResult> next;
        synchronized (rec) {
            next = rec.followUp;
            rec.followUp = null;
            rec.inFlight = next;
        }
        completeAsync(f, r);
        if (next != null) {
            if (shuttingDown || rec.removed) {
                synchronized (rec) {
                    rec.inFlight = null;
                }
                completeAsync(next, failure(rec, shuttingDown ? "server is shutting down" : "module was removed", List.of(), 0));
            } else if (rec.disabled) {
                synchronized (rec) {
                    rec.inFlight = null;
                }
                completeAsync(next, new ReloadResult(rec.id, false, ModuleState.DISABLED, 0, "module is disabled",
                        List.of(), 0, false));
            } else {
                startPipeline(rec, next);
            }
        }
    }

    private void completeAsync(CompletableFuture<ReloadResult> f, ReloadResult r) {
        try {
            notifier.execute(() -> f.complete(r));
        } catch (RejectedExecutionException e) {
            f.complete(r);
        }
    }

    private ReloadResult failure(ModuleRecord rec, String error, List<Diagnostic> diags, long ms) {
        Loaded cur = rec.current;
        return new ReloadResult(rec.id, false, rec.disabled ? ModuleState.DISABLED : (cur != null ? ModuleState.ENABLED
                : ModuleState.FAILED), cur != null ? cur.gen().generation : 0, error, diags, ms, cur != null);
    }

    // ------------------------------------------------------------------ compile step (off main)

    /** Everything needed to swap, computed off the main thread. */
    record CompileOutcome(boolean ok, long startNanos, String error, List<Diagnostic> diagnostics,
                          Map<String, String> sources, long compileMs, String description,
                          Map<String, byte[]> rawClasses, Map<String, String> sourceOf, String mainClass,
                          int loadId, Map<String, byte[]> instrumented, String configText, YamlConfiguration config) {

        static CompileOutcome failure(long t0, String error, List<Diagnostic> diags, Map<String, String> sources,
                                      long compileMs, String description) {
            return new CompileOutcome(false, t0, error, diags, sources, compileMs, description, null, null, null, -1,
                    null, null, null);
        }
    }

    private CompileOutcome prepare(ModuleRecord rec, long t0) throws IOException {
        if (!rec.dir.isDirectory()) {
            return CompileOutcome.failure(t0, "module directory not found", List.of(), Map.of(), -1, null);
        }
        ModuleFiles.Sources src = ModuleFiles.read(rec.dir);
        rec.compiledFp = src.fingerprint();
        String description = descriptionOf(src.moduleYml());
        if (src.java().isEmpty()) {
            return CompileOutcome.failure(t0, "no .java file in modules/" + rec.id + "/", List.of(new Diagnostic("ERROR",
                    "", -1, -1, "no .java source file found in modules/" + rec.id + "/", "")), Map.of(), -1, description);
        }
        // config.yml
        YamlConfiguration config = new YamlConfiguration();
        if (src.configYml() != null) {
            try {
                config.loadFromString(src.configYml());
            } catch (InvalidConfigurationException e) {
                String m = e.getMessage() == null ? e.toString() : e.getMessage();
                long line = -1;
                Matcher lm = Pattern.compile("line (\\d+)").matcher(m);
                if (lm.find()) {
                    line = Long.parseLong(lm.group(1));
                }
                Map<String, String> s = new LinkedHashMap<>(src.java());
                s.put("config.yml", src.configYml());
                return CompileOutcome.failure(t0, "config.yml is not valid YAML", List.of(new Diagnostic("ERROR",
                        "config.yml", line, -1, ModuleRecord.oneLine(m, 400), "")), s, -1, description);
            }
        }
        ModuleCompiler.Result cr = compiler.compile(src.java());
        if (!cr.ok()) {
            return CompileOutcome.failure(t0, "compilation failed: " + cr.errors() + " error(s)", cr.diagnostics(),
                    src.java(), cr.millis(), description);
        }
        ClassHierarchy h = new ClassHierarchy(cr.classes(), plugin.getClass().getClassLoader());
        List<Diagnostic> policy = withSourceLines(Instrumenter.scan(cr.classes(), cr.sourceOf(), h), src.java());
        if (!policy.isEmpty()) {
            List<Diagnostic> all = new ArrayList<>(cr.diagnostics());
            all.addAll(policy);
            String first = policy.get(0).message();
            return CompileOutcome.failure(t0, first + (policy.size() > 1 ? " (+" + (policy.size() - 1) + " more)" : ""),
                    all, src.java(), cr.millis(), description);
        }
        Instrumenter.EntryPoint ep = Instrumenter.findEntryPoint(cr.classes(), cr.sourceOf(), h);
        if (ep.className() == null) {
            List<Diagnostic> all = new ArrayList<>(cr.diagnostics());
            all.addAll(ep.problems());
            return CompileOutcome.failure(t0, ep.problems().get(0).message(), all, src.java(), cr.millis(), description);
        }
        int loadId = Guard.allocateId();
        Map<String, byte[]> inst;
        try {
            inst = Instrumenter.instrument(cr.classes(), loadId, h);
        } catch (Throwable t) {
            log.log(Level.WARNING, "instrumentation of module " + rec.id + " failed", t);
            return CompileOutcome.failure(t0, "instrumentation failed: " + t, List.of(new Diagnostic("ERROR", "", -1, -1,
                    "instrumentation failed: " + t, "")), src.java(), cr.millis(), description);
        }
        return new CompileOutcome(true, t0, null, cr.diagnostics(), src.java(), cr.millis(), description, cr.classes(),
                cr.sourceOf(), ep.className(), loadId, inst, src.configYml(), config);
    }

    private static List<Diagnostic> withSourceLines(List<Diagnostic> diags, Map<String, String> sources) {
        List<Diagnostic> out = new ArrayList<>(diags.size());
        for (Diagnostic d : diags) {
            String line = d.sourceLine();
            if (line.isEmpty() && d.line() > 0 && sources.containsKey(d.file())) {
                String[] lines = sources.get(d.file()).split("\r?\n", -1);
                if (d.line() <= lines.length) {
                    line = lines[(int) d.line() - 1].trim();
                }
            }
            out.add(new Diagnostic(d.kind(), d.file(), d.line(), d.column(), d.message(), line));
        }
        return out;
    }

    // ------------------------------------------------------------------ swap step (main thread)

    private record Attempt(Loaded loaded, String error, List<Diagnostic> diagnostics) {
    }

    private ReloadResult swap(ModuleRecord rec, CompileOutcome out) {
        long swapStart = System.nanoTime();
        rec.lastAttemptAt = System.currentTimeMillis();
        if (out.description() != null) {
            rec.description = out.description();
        }
        if (rec.removed) {
            return failure(rec, "module was removed", out.diagnostics(), ms(out.startNanos()));
        }
        if (rec.disabled) {
            rec.state = ModuleState.DISABLED;
            rec.statusDirty = true;
            return new ReloadResult(rec.id, false, ModuleState.DISABLED, 0, "module is disabled", out.diagnostics(),
                    ms(out.startNanos()), false);
        }
        if (shuttingDown) {
            return failure(rec, "server is shutting down", out.diagnostics(), ms(out.startNanos()));
        }

        // ---- compile / policy failure: nothing to swap, current generation keeps running
        if (!out.ok()) {
            Loaded cur = rec.current;
            boolean running = cur != null;
            rec.lastError = out.error();
            rec.diagnostics = out.diagnostics();
            rec.rolledBack = running;
            rec.state = running ? ModuleState.ENABLED : ModuleState.FAILED;
            rec.statusDirty = true;
            long total = ms(out.startNanos());
            String headline = "FAILED: " + out.error() + (running ? " - v" + cur.gen().generation
                    + " is still running (previous version kept)" : " - nothing running");
            writeBuildLog(rec, headline, out.diagnostics(), out.sources(), out.compileMs());
            rec.logger.engine(Level.WARNING, out.error() + (running ? " (v" + cur.gen().generation + " kept)" : ""));
            emit(rec, running ? ModuleEvent.Type.ROLLED_BACK : ModuleEvent.Type.FAILED, out.error(),
                    running ? cur.gen().generation : 0);
            return new ReloadResult(rec.id, false, rec.state, running ? cur.gen().generation : 0, out.error(),
                    out.diagnostics(), total, running);
        }

        // ---- unload the running generation, load the new one
        Loaded old = rec.current;
        if (old != null) {
            unloadGen(rec, old, "reload");
            rec.current = null;
        }
        int newGeneration = rec.generation + 1;
        Attempt a = loadGen(rec, out.loadId(), out.instrumented(), out.mainClass(), newGeneration, out.config(),
                out.sourceOf());
        long total;
        ReloadResult result;
        if (a.loaded() != null) {
            rec.current = a.loaded();
            rec.generation = newGeneration;
            rec.lastGood = new KeptBuild(out.rawClasses(), out.sourceOf(), out.mainClass(), out.configText(), newGeneration);
            rec.state = ModuleState.ENABLED;
            rec.rolledBack = false;
            rec.lastError = null;
            rec.diagnostics = out.diagnostics();
            rec.lastReloadAt = System.currentTimeMillis();
            rec.resetErrors();
            rec.statusDirty = true;
            flushPlayerUpdates();
            total = ms(out.startNanos());
            long swapMs = ms(swapStart);
            String msg = "v" + newGeneration + " loaded in " + total + " ms (compile " + out.compileMs() + " ms, swap "
                    + swapMs + " ms)";
            rec.logger.engine(Level.INFO, msg);
            writeBuildLog(rec, "OK: " + msg, out.diagnostics(), out.sources(), out.compileMs());
            emit(rec, ModuleEvent.Type.RELOADED, msg, newGeneration);
            result = new ReloadResult(rec.id, true, ModuleState.ENABLED, newGeneration, null, out.diagnostics(), total, false);
        } else {
            List<Diagnostic> diags = new ArrayList<>(out.diagnostics());
            diags.addAll(a.diagnostics());
            rec.lastError = a.error();
            rec.diagnostics = diags;
            String headline;
            ModuleEvent.Type type;
            if (old != null && rec.lastGood != null) {
                Attempt rb = restore(rec);
                if (rb.loaded() != null) {
                    rec.current = rb.loaded();
                    rec.state = ModuleState.ENABLED;
                    rec.rolledBack = true;
                    headline = "FAILED: " + a.error() + " - rolled back to v" + rec.lastGood.generation();
                    type = ModuleEvent.Type.ROLLED_BACK;
                } else {
                    rec.state = ModuleState.FAILED;
                    rec.rolledBack = false;
                    rec.lastError = a.error() + "; rollback to v" + rec.lastGood.generation() + " also failed: " + rb.error();
                    headline = "FAILED: " + rec.lastError + " - nothing running";
                    type = ModuleEvent.Type.FAILED;
                }
            } else {
                rec.state = ModuleState.FAILED;
                rec.rolledBack = false;
                headline = "FAILED: " + a.error() + " - nothing running";
                type = ModuleEvent.Type.FAILED;
            }
            rec.statusDirty = true;
            flushPlayerUpdates();
            total = ms(out.startNanos());
            rec.logger.engine(Level.WARNING, headline);
            writeBuildLog(rec, headline, diags, out.sources(), out.compileMs());
            Loaded cur = rec.current;
            int running = cur != null ? cur.gen().generation : 0;
            emit(rec, type, rec.lastError, running);
            result = new ReloadResult(rec.id, false, rec.state, running, rec.lastError, diags, total, cur != null);
        }
        return result;
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Define, instantiate and enable a generation (main thread). On failure everything it did is undone. */
    private Attempt loadGen(ModuleRecord rec, int loadId, Map<String, byte[]> classes, String mainClass, int generation,
                            YamlConfiguration config, Map<String, String> sourceOf) {
        Gen g = new Gen(loadId, rec.id, generation);
        Guard.register(g);
        ModuleClassLoader loader = new ModuleClassLoader("vibe:" + rec.id + "#" + loadId, rec.id, classes,
                plugin.getClass().getClassLoader());
        ModuleContextImpl ctx = new ModuleContextImpl(rec, g, config);
        g.attach(ctx, loader);
        // the context must be visible as "current" while enable() runs so its runtime errors are recorded
        Loaded loaded = new Loaded(g, ctx, loader);
        rec.current = loaded;
        Throwable err = Guard.invoke(g, "enable() of module '" + rec.id + "'", enableBudgetNanos(), () -> {
            Class<?> c = Class.forName(mainClass, true, loader);
            Object o;
            try {
                o = c.getDeclaredConstructor().newInstance();
            } catch (InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
            VibeModule m = (VibeModule) o;
            ctx.setModule(m);
            m.enable(ctx);
        });
        g.drainMainNanos(); // enable() time is not "per tick" load: keep it out of msPerTick
        if (err == null) {
            return new Attempt(loaded, null, List.of());
        }
        rec.current = null;
        try {
            ctx.close();
        } catch (Throwable t) {
            log.log(Level.WARNING, "cleanup after failed enable of " + rec.id + " failed", t);
        }
        rec.store.saveIfDirty();
        g.kill();
        removeSharedFrom(loader);
        String error = describeEnableFailure(err);
        appendErrorLog(rec, java.time.LocalTime.now().withNano(0) + " [v" + generation + "] enable failed: " + err + "\n"
                + ModuleRecord.moduleFrames(err, 12) + "\n");
        return new Attempt(null, error, List.of(diagnosticOf(err, error, sourceOf)));
    }

    private String describeEnableFailure(Throwable err) {
        if (err instanceof ModuleTimeoutError mte) {
            String top = ModuleRecord.topModuleFrame(err);
            if (!mte.budgetExceeded()) {
                return "enable() was stopped: " + err.getMessage() + (top == null ? "" : " (" + top + ")");
            }
            return "enable() timed out after " + cfg.enableBudgetMs() + " ms (infinite loop or too much work?)"
                    + (top == null ? "" : " at " + top);
        }
        if (err instanceof ModuleKilledError) {
            return "enable() was interrupted";
        }
        if (err instanceof LinkageError || err instanceof ClassCastException) {
            return "loading failed: " + err;
        }
        String m = err.getMessage();
        String top = ModuleRecord.topModuleFrame(err);
        return "enable() threw " + err.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": "
                + ModuleRecord.oneLine(m, 300)) + (top == null ? "" : " (" + top + ")");
    }

    private static Diagnostic diagnosticOf(Throwable err, String message, Map<String, String> sourceOf) {
        for (Throwable c = err; c != null; c = c.getCause() == c ? null : c.getCause()) {
            for (StackTraceElement e : c.getStackTrace()) {
                if (ModuleRecord.isModuleFrame(e)) {
                    String internal = e.getClassName().replace('.', '/');
                    int dollar = internal.indexOf('$');
                    String file = sourceOf == null ? null : sourceOf.get(internal);
                    if (file == null && sourceOf != null && dollar > 0) {
                        file = sourceOf.get(internal.substring(0, dollar));
                    }
                    if (file == null) {
                        file = e.getFileName() == null ? "" : e.getFileName();
                    }
                    return new Diagnostic("ERROR", file, e.getLineNumber() > 0 ? e.getLineNumber() : -1, -1, message, "");
                }
            }
        }
        return new Diagnostic("ERROR", "", -1, -1, message, "");
    }

    /** Re-instantiate the last good generation from its kept bytecode (new loader, new context). */
    private Attempt restore(ModuleRecord rec) {
        KeptBuild kb = rec.lastGood;
        try {
            int id = Guard.allocateId();
            ClassHierarchy h = new ClassHierarchy(kb.classes(), plugin.getClass().getClassLoader());
            Map<String, byte[]> inst = Instrumenter.instrument(kb.classes(), id, h);
            YamlConfiguration cfgYaml = new YamlConfiguration();
            if (kb.configText() != null) {
                try {
                    cfgYaml.loadFromString(kb.configText());
                } catch (InvalidConfigurationException ignored) {
                    // was valid when it was loaded
                }
            }
            Attempt a = loadGen(rec, id, inst, kb.mainClass(), kb.generation(), cfgYaml, kb.sourceOf());
            if (a.loaded() != null) {
                rec.logger.engine(Level.WARNING, "rolled back to v" + kb.generation());
            }
            return a;
        } catch (Throwable t) {
            return new Attempt(null, "rollback failed: " + t, List.of());
        }
    }

    /** disable() + cleanups + store save + kill (main thread). */
    private void unloadGen(ModuleRecord rec, Loaded lg, String reason) {
        Throwable t = Guard.invoke(lg.gen(), "disable() of module '" + rec.id + "'", enableBudgetNanos(), () -> {
            VibeModule m = lg.module();
            if (m != null) {
                m.disable(lg.ctx());
            }
        });
        if (t != null && !(t instanceof ModuleKilledError)) {
            rec.logger.engine(Level.WARNING, "disable() failed (" + reason + "): " + t);
            appendErrorLog(rec, java.time.LocalTime.now().withNano(0) + " [v" + lg.gen().generation + "] disable failed: "
                    + t + "\n" + ModuleRecord.moduleFrames(t, 12) + "\n");
        }
        int failures = 0;
        try {
            failures = lg.ctx().close();
        } catch (Throwable e) {
            log.log(Level.WARNING, "cleanup of module " + rec.id + " failed", e);
        }
        if (failures > 0) {
            rec.logger.engine(Level.WARNING, failures + " cleanup action(s) failed while unloading v" + lg.gen().generation);
        }
        rec.store.saveIfDirty();
        lg.gen().kill();
        removeSharedFrom(lg.loader());
        rec.ring.add("INFO", "v" + lg.gen().generation + " unloaded (" + reason + ")");
    }

    /** Drop shared() entries whose value belongs to an unloaded generation (lambdas, module objects). */
    private void removeSharedFrom(ClassLoader loader) {
        if (loader == null) {
            return;
        }
        shared.entrySet().removeIf(e -> e.getValue() != null && e.getValue().getClass().getClassLoader() == loader);
    }

    /** Refresh players' command trees / recipes after registrations changed (main thread). */
    private void flushPlayerUpdates() {
        if (commandsDirty) {
            commandsDirty = false;
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    p.updateCommands();
                } catch (Throwable t) {
                    log.log(Level.FINE, "updateCommands failed for " + p.getName(), t);
                }
            }
        }
        if (recipesDirty) {
            recipesDirty = false;
            try {
                Bukkit.updateRecipes();
            } catch (Throwable t) {
                log.log(Level.FINE, "updateRecipes failed", t);
            }
        }
    }

    // ================================================================== enable / disable / trip

    @Override
    public CompletableFuture<ReloadResult> enable(String id) {
        if (!isValidId(id)) {
            return reload(id);
        }
        ModuleRecord rec = recordFor(id);
        if (rec == null) {
            return reload(id);
        }
        try {
            Files.deleteIfExists(StatusFiles.marker(rec.dir).toPath());
        } catch (IOException e) {
            log.warning("cannot delete " + StatusFiles.marker(rec.dir) + ": " + e);
        }
        rec.disabled = false;
        if (rec.state == ModuleState.DISABLED) {
            rec.state = ModuleState.DISCOVERED;
        }
        rec.ring.add("INFO", "enabled");
        return reload(id);
    }

    @Override
    public CompletableFuture<Boolean> disable(String id) {
        ModuleRecord rec = isValidId(id) ? recordFor(id) : null;
        if (rec == null) {
            return CompletableFuture.completedFuture(false);
        }
        rec.disabled = true;
        try {
            if (rec.dir.isDirectory()) {
                Files.createDirectories(StatusFiles.vibeDir(rec.dir).toPath());
                Path m = StatusFiles.marker(rec.dir).toPath();
                if (!Files.exists(m)) {
                    Files.writeString(m, "disabled at " + java.time.LocalDateTime.now().withNano(0) + "\n",
                            StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            log.warning("cannot create " + StatusFiles.marker(rec.dir) + ": " + e);
        }
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        Runnable r = () -> {
            try {
                Loaded cur = rec.current;
                if (cur != null) {
                    unloadGen(rec, cur, "disabled");
                    rec.current = null;
                    flushPlayerUpdates();
                }
                boolean changed = rec.state != ModuleState.DISABLED;
                rec.state = ModuleState.DISABLED;
                rec.rolledBack = false;
                rec.statusDirty = true;
                if (changed || cur != null) {
                    rec.logger.engine(Level.INFO, "disabled");
                    emit(rec, ModuleEvent.Type.DISABLED, "module disabled", 0);
                }
            } finally {
                f.complete(true);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            try {
                Bukkit.getScheduler().runTask(plugin, r);
            } catch (Throwable t) {
                f.complete(true);
            }
        }
        return f;
    }

    /** Called from ModuleRecord on every recorded runtime error. */
    void onRuntimeError(ModuleRecord rec, String summary, int weight, int count) {
        long now = System.currentTimeMillis();
        if (rec.runtimeEventAllowed(now)) {
            emit(rec, ModuleEvent.Type.RUNTIME_ERROR, summary, rec.generation);
        }
        if (rec.countError(now, weight, cfg.errorBurstLimit(), cfg.errorBurstWindowMs()) && !rec.tripScheduled) {
            rec.tripScheduled = true;
            String reason = "circuit breaker: too many errors (" + cfg.errorBurstLimit() + " in "
                    + cfg.errorBurstWindowMs() / 1000 + " s), last: " + summary;
            try {
                // never unload from inside a guarded call: next tick
                Bukkit.getScheduler().runTask(plugin, () -> trip(rec, reason));
            } catch (Throwable t) {
                rec.tripScheduled = false;
            }
        }
    }

    private void trip(ModuleRecord rec, String reason) {
        Loaded cur = rec.current;
        if (cur == null || rec.state != ModuleState.ENABLED || shuttingDown) {
            rec.tripScheduled = false;
            return;
        }
        unloadGen(rec, cur, "tripped");
        rec.current = null;
        rec.state = ModuleState.TRIPPED;
        rec.rolledBack = false;
        rec.lastError = reason;
        rec.statusDirty = true;
        flushPlayerUpdates();
        rec.logger.engine(Level.WARNING, "TRIPPED - " + reason + ". Fix the code (or reload) to re-arm it.");
        appendErrorLog(rec, java.time.LocalTime.now().withNano(0) + " TRIPPED: " + reason + "\n");
        emit(rec, ModuleEvent.Type.TRIPPED, reason, 0);
    }

    // ================================================================== create

    private static final Set<String> JAVA_KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
            "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
            "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "var", "record", "yield", "sealed", "permits", "non", "_");

    /** Java package of a module: {@code vibe.<id with '-' replaced by '_'>}. */
    static String packageOf(String id) {
        String p = id.replace('-', '_');
        if (Character.isDigit(p.charAt(0)) || JAVA_KEYWORDS.contains(p)) {
            p = "_" + p;
        }
        return "vibe." + p;
    }

    /** {@code pig-rain} -> {@code PigRainModule}. */
    static String classNameOf(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("[-_]+")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        if (sb.length() == 0 || Character.isDigit(sb.charAt(0))) {
            sb.insert(0, 'M');
        }
        return sb + "Module";
    }

    @Override
    public boolean create(String id, String description) throws IOException {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("invalid module id '" + id + "' (expected [a-z0-9][a-z0-9_-]{0,39})");
        }
        File dir = new File(cfg.modulesDir(), id);
        if (dir.exists()) {
            return false;
        }
        String desc = description == null ? "" : description.strip();
        Files.createDirectories(dir.toPath());
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("description", desc);
        Files.writeString(new File(dir, "module.yml").toPath(), yml.saveToString(), StandardCharsets.UTF_8);
        String pkg = packageOf(id);
        String cls = classNameOf(id);
        String doc = desc.isEmpty() ? "Module " + id + "." : desc.replace("*/", "*\\/");
        String src = "package " + pkg + ";\n\n"
                + "import dev.vibecraft.api.ModuleContext;\n"
                + "import dev.vibecraft.api.VibeModule;\n\n"
                + "/** " + doc + " */\n"
                + "public class " + cls + " implements VibeModule {\n\n"
                + "    @Override\n"
                + "    public void enable(ModuleContext ctx) {\n"
                + "        ctx.logger().info(\"enabled\");\n"
                + "    }\n"
                + "}\n";
        Files.writeString(new File(dir, cls + ".java").toPath(), src, StandardCharsets.UTF_8);
        boolean[] created = new boolean[1];
        ModuleRecord rec = records.computeIfAbsent(id, k -> {
            created[0] = true;
            return new ModuleRecord(this, k, dir);
        });
        rec.description = desc;
        rec.statusDirty = true;
        if (created[0]) {
            rec.ring.add("INFO", "module created");
            emit(rec, ModuleEvent.Type.CREATED, "module created" + (desc.isEmpty() ? "" : ": " + desc), 0);
        }
        return true;
    }

    // ================================================================== watcher

    private void poll() {
        if (shuttingDown) {
            return;
        }
        try {
            pollOnce();
        } catch (Throwable t) {
            log.log(Level.WARNING, "module watcher error", t);
        }
    }

    private void pollOnce() {
        long now = System.currentTimeMillis();
        File[] dirs = cfg.modulesDir().listFiles(File::isDirectory);
        Set<String> present = new HashSet<>();
        if (dirs != null) {
            for (File d : dirs) {
                String id = d.getName();
                if (!isValidId(id)) {
                    if (!id.startsWith(".") && warnedIds.add(id)) {
                        log.warning("Ignoring modules/" + id + ": invalid module id (expected [a-z0-9][a-z0-9_-]{0,39})");
                    }
                    continue;
                }
                ModuleFiles.Snapshot s = ModuleFiles.snapshot(d);
                if (!s.hasContent()) {
                    continue; // empty, or only .vibe/ left over: treated as absent
                }
                present.add(id);
                ModuleRecord rec = recordFor(id);
                if (rec == null) {
                    continue;
                }
                // disabled marker created / deleted by hand
                boolean marker = StatusFiles.marker(d).exists();
                if (marker && !rec.disabled) {
                    disable(id);
                    continue;
                }
                if (!marker && rec.disabled) {
                    enable(id);
                    rec.triggeredFp = s.fingerprint();
                    continue;
                }
                // debounce
                if (!s.fingerprint().equals(rec.observedFp)) {
                    rec.observedFp = s.fingerprint();
                    rec.observedSince = now;
                    continue;
                }
                if (now - rec.observedSince < cfg.debounceMs() || !cfg.autoReload() || rec.disabled || !s.hasJava()) {
                    continue;
                }
                if (s.fingerprint().equals(rec.compiledFp) || s.fingerprint().equals(rec.triggeredFp)) {
                    continue;
                }
                rec.triggeredFp = s.fingerprint();
                rec.ring.add("INFO", "change detected: reloading");
                reload(id);
            }
        }
        for (ModuleRecord rec : new ArrayList<>(records.values())) {
            if (!present.contains(rec.id)) {
                removeModule(rec);
            }
        }
    }

    private void removeModule(ModuleRecord rec) {
        if (!records.remove(rec.id, rec)) {
            return;
        }
        rec.removed = true;
        Runnable r = () -> {
            Loaded cur = rec.current;
            if (cur != null) {
                unloadGen(rec, cur, "directory deleted");
                rec.current = null;
                flushPlayerUpdates();
            }
            rec.store.saveIfDirty();
            log.info("[" + rec.id + "] module directory deleted: unloaded");
            emit(rec, ModuleEvent.Type.REMOVED, "module directory deleted", 0);
            cleanLeftoverVibeDir(rec.dir);
        };
        try {
            Bukkit.getScheduler().runTask(plugin, r);
        } catch (Throwable t) {
            // plugin disabled
        }
    }

    /** If only our own .vibe/ folder remains in a deleted module directory, remove it. */
    private void cleanLeftoverVibeDir(File dir) {
        if (!dir.isDirectory() || ModuleFiles.snapshot(dir).hasContent()) {
            return;
        }
        File[] rest = dir.listFiles();
        if (rest == null) {
            return;
        }
        for (File f : rest) {
            if (!f.getName().equals(".vibe")) {
                return;
            }
        }
        try (Stream<Path> s = Files.walk(StatusFiles.vibeDir(dir).toPath())) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir.toPath());
        } catch (IOException ignored) {
            // best effort
        }
    }

    // ================================================================== ticks, status & io

    private void onTickStart() {
        Guard.tickStart();
        for (ModuleRecord rec : records.values()) {
            Loaded cur = rec.current;
            if (cur != null) {
                double ms = cur.gen().drainMainNanos() / 1e6;
                rec.msPerTick = rec.msPerTick * 0.95 + ms * 0.05;
            } else if (rec.msPerTick != 0) {
                rec.msPerTick = rec.msPerTick < 1e-4 ? 0 : rec.msPerTick * 0.95;
            }
        }
        if (commandsDirty || recipesDirty) {
            flushPlayerUpdates();
        }
    }

    private long lastFullStatus;

    private void flushStatus() {
        long now = System.currentTimeMillis();
        boolean full = now - lastFullStatus > 5000;
        if (full) {
            lastFullStatus = now;
        }
        for (ModuleRecord rec : records.values()) {
            if (rec.statusDirty || (full && rec.current != null)) {
                rec.statusDirty = false;
                try {
                    StatusFiles.writeStatus(rec.dir, rec.info());
                } catch (Throwable e) {
                    log.log(Level.FINE, "cannot write status of " + rec.id, e);
                }
            }
        }
    }

    private void autosaveStores() {
        for (ModuleRecord rec : records.values()) {
            try {
                rec.store.saveIfDirty();
            } catch (Throwable t) {
                log.log(Level.FINE, "autosave of " + rec.id + " failed", t);
            }
        }
    }

    void appendErrorLog(ModuleRecord rec, String text) {
        runIo(() -> StatusFiles.appendError(rec.dir, text));
    }

    private void writeBuildLog(ModuleRecord rec, String headline, List<Diagnostic> diags, Map<String, String> sources,
                               long compileMs) {
        String text = StatusFiles.renderBuildLog(rec.id, headline, diags, sources, compileMs);
        runIo(() -> StatusFiles.writeBuildLog(rec.dir, text));
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private void runIo(IoAction a) {
        Runnable r = () -> {
            try {
                a.run();
            } catch (IOException e) {
                log.log(Level.FINE, "VibeCraft io error", e);
            }
        };
        try {
            io.execute(r);
        } catch (RejectedExecutionException e) {
            r.run();
        }
    }

    // ================================================================== events

    private void emit(ModuleRecord rec, ModuleEvent.Type type, String message, int generation) {
        rec.statusDirty = true;
        ModuleEvent e = new ModuleEvent(rec.id, type, message, generation, System.currentTimeMillis());
        for (ModuleEventListener l : listeners) {
            try {
                l.onModuleEvent(e);
            } catch (Throwable t) {
                log.log(Level.WARNING, "module event listener failed", t);
            }
        }
    }

    // ================================================================== helpers

    private String readDescription(File dir) {
        File f = new File(dir, "module.yml");
        if (!f.isFile()) {
            return "";
        }
        try {
            String d = descriptionOf(Files.readString(f.toPath(), StandardCharsets.UTF_8));
            return d == null ? "" : d;
        } catch (IOException e) {
            return "";
        }
    }

    private static String descriptionOf(String moduleYml) {
        if (moduleYml == null) {
            return null;
        }
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(moduleYml);
            return y.getString("description", "");
        } catch (InvalidConfigurationException e) {
            return null;
        }
    }

    private List<File> computeClasspath() {
        Set<File> out = new LinkedHashSet<>();
        File server = new File(".").getAbsoluteFile();
        addJars(new File(server, "libraries"), true, out);
        addJars(new File(server, "versions"), true, out);
        addJars(new File(server, "plugins"), false, out);
        try {
            File self = new File(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            if (self.isFile()) {
                out.add(self.getAbsoluteFile());
            }
        } catch (URISyntaxException | RuntimeException e) {
            log.warning("cannot locate the VibeCraft jar: " + e);
        }
        return new ArrayList<>(out);
    }

    private static void addJars(File dir, boolean recursive, Set<File> out) {
        if (!dir.isDirectory()) {
            return;
        }
        if (recursive) {
            try (Stream<Path> s = Files.walk(dir.toPath())) {
                s.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".jar") && Files.isRegularFile(p))
                        .sorted().forEach(p -> out.add(p.toFile().getAbsoluteFile()));
            } catch (IOException ignored) {
                // skip
            }
        } else {
            File[] fs = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (fs != null) {
                java.util.Arrays.sort(fs);
                for (File f : fs) {
                    out.add(f.getAbsoluteFile());
                }
            }
        }
    }

    private static void mkdirs(File d) {
        if (!d.isDirectory()) {
            d.mkdirs();
        }
    }

    private static ThreadFactory daemonFactory(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}

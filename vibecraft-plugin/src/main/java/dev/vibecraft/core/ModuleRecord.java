package dev.vibecraft.core;

import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.vibecraft.api.ModuleStore;
import dev.vibecraft.api.VibeModule;
import dev.vibecraft.core.ctx.LogRing;
import dev.vibecraft.core.ctx.ModuleContextImpl;
import dev.vibecraft.core.ctx.ModuleHost;
import dev.vibecraft.core.ctx.ModuleLogger;
import dev.vibecraft.core.ctx.ModuleStoreImpl;
import dev.vibecraft.core.guard.Gen;
import dev.vibecraft.core.guard.ModuleKilledError;
import dev.vibecraft.core.guard.ModuleTimeoutError;
import dev.vibecraft.core.load.ModuleClassLoader;
import org.bukkit.plugin.Plugin;

/** Engine-side state of one module (survives generations). */
final class ModuleRecord implements ModuleHost {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** A running generation. */
    record Loaded(Gen gen, ModuleContextImpl ctx, ModuleClassLoader loader) {
        VibeModule module() {
            return ctx.module();
        }
    }

    /** Bytecode of the last good generation (kept for rollback). */
    record KeptBuild(Map<String, byte[]> classes, Map<String, String> sourceOf, String mainClass, String configText,
                     int generation) {
    }

    final ModuleManagerImpl manager;
    final String id;
    final File dir;
    final LogRing ring = new LogRing(500);
    final ModuleLogger logger;
    final ModuleStoreImpl store;

    // ------------------------------------------------------------ status (read from any thread)
    volatile ModuleState state = ModuleState.DISCOVERED;
    volatile int generation;
    volatile String description = "";
    volatile long lastReloadAt;
    volatile long lastAttemptAt;
    volatile String lastError;
    volatile List<Diagnostic> diagnostics = List.of();
    volatile boolean rolledBack;
    volatile double msPerTick;
    private final ArrayDeque<String> recentErrors = new ArrayDeque<>();
    private int runtimeErrorCount;

    // ------------------------------------------------------------ running code (main thread writes)
    volatile Loaded current;
    volatile KeptBuild lastGood;

    // ------------------------------------------------------------ pipeline (guarded by this)
    CompletableFuture<ReloadResult> inFlight;
    CompletableFuture<ReloadResult> followUp;

    // ------------------------------------------------------------ watcher (watcher thread)
    String observedFp;
    long observedSince;
    volatile String triggeredFp;
    volatile String compiledFp;
    /** Fingerprint of the sources of the running generation, and whether the watcher (not a request) loaded it. */
    volatile String loadedFp;
    volatile boolean loadedByWatcher;
    /** Fingerprint the in-flight compile was requested for (guarded by this). */
    String inFlightFp;
    boolean inFlightByWatcher;

    volatile boolean disabled;
    volatile boolean removed;
    volatile boolean statusDirty = true;

    // ------------------------------------------------------------ error accounting
    private final ArrayDeque<Long> errorTimes = new ArrayDeque<>();
    private long lastRuntimeEventAt;
    private long lastConsoleErrorAt;
    private int consoleSuppressed;
    volatile boolean tripScheduled;

    // ------------------------------------------------------------ broadcast rate limit
    private double broadcastTokens = 20;
    private long broadcastRefill = System.nanoTime();
    private long broadcastDropped;

    ModuleRecord(ModuleManagerImpl manager, String id, File dir) {
        this.manager = manager;
        this.id = id;
        this.dir = dir;
        this.logger = new ModuleLogger(id, manager.plugin().getLogger(), ring);
        this.store = new ModuleStoreImpl(new File(new File(manager.dataDir(), id), "store.yml"), logger, manager::runStoreIo);
        this.sharedView = manager.sharedView(id);
    }

    private final Map<String, Object> sharedView;

    // ================================================================== ModuleHost

    @Override
    public String id() {
        return id;
    }

    @Override
    public Plugin plugin() {
        return manager.plugin();
    }

    @Override
    public File sourceFolder() {
        return dir;
    }

    @Override
    public File dataFolder() {
        File f = new File(manager.dataDir(), id);
        if (!f.isDirectory()) {
            f.mkdirs();
        }
        return f;
    }

    @Override
    public ModuleStore store() {
        return store;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public Map<String, Object> shared() {
        return sharedView;
    }

    @Override
    public long handlerBudgetNanos() {
        return manager.handlerBudgetNanos();
    }

    @Override
    public long enableBudgetNanos() {
        return manager.enableBudgetNanos();
    }

    @Override
    public void commandsChanged() {
        manager.markCommandsDirty();
    }

    @Override
    public void recipesChanged() {
        manager.markRecipesDirty();
    }

    @Override
    public synchronized boolean allowBroadcast() {
        long now = System.nanoTime();
        broadcastTokens = Math.min(20, broadcastTokens + (now - broadcastRefill) / 1e9 * 10);
        broadcastRefill = now;
        if (broadcastTokens >= 1) {
            broadcastTokens -= 1;
            return true;
        }
        if (broadcastDropped++ % 50 == 0) {
            logger.engine(Level.WARNING, "ctx.broadcast() rate limit reached (max 10/s): messages dropped");
        }
        return false;
    }

    // ================================================================== runtime errors

    @Override
    public void runtimeError(Gen gen, String where, Throwable error) {
        try {
            recordRuntimeError(gen, where, error);
        } catch (Throwable t) {
            manager.plugin().getLogger().log(Level.WARNING, "error while recording a module error", t);
        }
    }

    private void recordRuntimeError(Gen gen, String where, Throwable t) {
        if (t == null || t instanceof ModuleKilledError || gen.killed()) {
            return;
        }
        Loaded cur = current;
        if (cur == null || cur.gen() != gen) {
            return; // stale generation
        }
        String top = topModuleFrame(t);
        String msg = t.getMessage();
        String head = t.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + oneLine(msg, 300));
        String summary = LocalTime.now().format(TIME) + " " + where + ": " + head + (top == null ? "" : " (" + top + ")");
        int count;
        synchronized (this) {
            recentErrors.addLast(summary);
            while (recentErrors.size() > 10) {
                recentErrors.pollFirst();
            }
            count = ++runtimeErrorCount;
        }
        ring.add("ERROR", where + ": " + head + (top == null ? "" : " (" + top + ")"));
        statusDirty = true;

        String frames = moduleFrames(t, 12);
        long now = System.currentTimeMillis();
        boolean toConsole;
        int suppressed;
        synchronized (this) {
            toConsole = now - lastConsoleErrorAt >= 1000;
            suppressed = consoleSuppressed;
            if (toConsole) {
                lastConsoleErrorAt = now;
                consoleSuppressed = 0;
            } else {
                consoleSuppressed++;
            }
        }
        if (toConsole) {
            manager.plugin().getLogger().warning("[" + id + "] " + where + ": " + head
                    + (suppressed > 0 ? " (+" + suppressed + " similar errors not shown)" : "")
                    + (frames.isEmpty() ? "" : "\n" + frames));
        }
        manager.appendErrorLog(this, LocalTime.now().format(TIME) + " [v" + gen.generation + "] " + where + ": "
                + t + "\n" + frames + causes(t) + "\n");
        // a real timeout froze the main thread for a whole budget: weigh it more in the circuit breaker
        int weight = t instanceof ModuleTimeoutError mte && mte.budgetExceeded() ? 5 : 1;
        manager.onRuntimeError(this, summary, weight, count);
    }

    /** Circuit breaker bookkeeping; returns true if the module must trip. */
    synchronized boolean countError(long now, int weight, int limit, long window) {
        for (int i = 0; i < weight; i++) {
            errorTimes.addLast(now);
        }
        while (!errorTimes.isEmpty() && now - errorTimes.peekFirst() > window) {
            errorTimes.pollFirst();
        }
        return errorTimes.size() >= limit;
    }

    synchronized boolean runtimeEventAllowed(long now) {
        if (now - lastRuntimeEventAt >= 1000) {
            lastRuntimeEventAt = now;
            return true;
        }
        return false;
    }

    synchronized void resetErrors() {
        recentErrors.clear();
        runtimeErrorCount = 0;
        errorTimes.clear();
        tripScheduled = false;
    }

    synchronized List<String> recentErrorsCopy() {
        return new ArrayList<>(recentErrors);
    }

    synchronized int runtimeErrorCount() {
        return runtimeErrorCount;
    }

    // ================================================================== stack trace helpers

    static boolean isModuleFrame(StackTraceElement e) {
        String ln = e.getClassLoaderName();
        return ln != null && ln.startsWith("vibe:");
    }

    /** "File.java:12" of the innermost module frame (also searches causes). */
    static String topModuleFrame(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            for (StackTraceElement e : c.getStackTrace()) {
                if (isModuleFrame(e)) {
                    return (e.getFileName() == null ? e.getClassName() : e.getFileName()) + ":" + e.getLineNumber();
                }
            }
        }
        return null;
    }

    /** Module frames of {@code t} ("    at vibe.x.Foo.bar(Foo.java:12)"), at most {@code max}. */
    static String moduleFrames(Throwable t, int max) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        StackTraceElement[] st = t.getStackTrace();
        for (StackTraceElement e : st) {
            if (isModuleFrame(e)) {
                if (n++ >= max) {
                    sb.append("    ...\n");
                    break;
                }
                sb.append("    at ").append(e.getClassName()).append('.').append(e.getMethodName()).append('(')
                        .append(e.getFileName()).append(':').append(e.getLineNumber()).append(")\n");
            }
        }
        if (n == 0 && st.length > 0) {
            // error raised outside module code (e.g. in a Bukkit call): show the first frames
            for (int i = 0; i < Math.min(4, st.length); i++) {
                sb.append("    at ").append(st[i]).append('\n');
            }
        }
        return sb.toString();
    }

    private static String causes(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (Throwable c = t.getCause(); c != null && depth < 5; c = c.getCause(), depth++) {
            sb.append("  caused by: ").append(c).append('\n').append(moduleFrames(c, 6));
        }
        return sb.toString();
    }

    static String oneLine(String s, int max) {
        s = s.replace('\r', ' ').replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ================================================================== info

    ModuleInfo info() {
        Loaded cur = current;
        List<String> commands = List.of();
        int listeners = 0;
        int tasks = 0;
        if (cur != null) {
            try {
                commands = cur.ctx().commandNames();
                listeners = cur.ctx().listenerCount();
                tasks = cur.ctx().taskCount();
            } catch (RuntimeException ignored) {
                // racing with unload
            }
        }
        double ms = Math.round(msPerTick * 1000.0) / 1000.0;
        return new ModuleInfo(id, state, generation, description == null ? "" : description, lastReloadAt,
                lastAttemptAt, lastError, List.copyOf(diagnostics), runtimeErrorCount(), recentErrorsCopy(),
                List.copyOf(commands), listeners, tasks, rolledBack, ms);
    }
}

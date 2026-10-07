package dev.vibecraft.core;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Discovers, compiles, (hot-)loads, guards and unloads modules from {@code workspace/modules/<id>/}.
 *
 * <p>Thread-safety: every method may be called from any thread. Compilation always happens off
 * the main thread; enabling/disabling module code always happens on the main thread.
 * Futures complete on an arbitrary thread.
 */
public interface ModuleManager {

    /** Discover modules, load every non-disabled one, start the file watcher. Called from onEnable. */
    void start();

    /** Unload every module (calls disable, cleans up, saves stores) and stop the watcher. Called from onDisable (main thread). */
    void shutdown();

    List<ModuleInfo> list();

    Optional<ModuleInfo> info(String id);

    /**
     * Compile + hot-swap a module. If compilation or enable fails, the previous good generation
     * keeps running (or is restored). Concurrent requests for the same module are coalesced.
     * Re-arms TRIPPED modules. For a DISABLED module, returns a failed result with state DISABLED
     * and error "module is disabled" (use {@link #enable(String)}). Unknown id: failed result, state FAILED.
     */
    CompletableFuture<ReloadResult> reload(String id);

    /** Remove the disabled marker (if any) and reload. */
    CompletableFuture<ReloadResult> enable(String id);

    /** Unload the module and create the disabled marker. Completes with false if unknown. */
    CompletableFuture<Boolean> disable(String id);

    /**
     * Scaffold a new module directory with a minimal compiling module class and a module.yml.
     * Does not load it (the watcher / caller does). Returns false if it already exists.
     */
    boolean create(String id, String description) throws IOException;

    /** Valid ids match {@code [a-z0-9][a-z0-9_-]{0,39}}. */
    boolean isValidId(String id);

    /** Recent log lines of a module (logger output + runtime errors), oldest first, at most {@code limit}. */
    List<String> logs(String id, int limit);

    File modulesDir();

    File dataDir();

    void addListener(ModuleEventListener listener);
}

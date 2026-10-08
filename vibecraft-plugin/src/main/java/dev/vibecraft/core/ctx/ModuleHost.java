package dev.vibecraft.core.ctx;

import java.io.File;
import java.util.Map;
import java.util.logging.Logger;

import dev.vibecraft.api.ModuleStore;
import dev.vibecraft.core.guard.Gen;
import org.bukkit.plugin.Plugin;

/** What a generation's context needs from the engine (implemented per module by the manager). */
public interface ModuleHost {

    String id();

    Plugin plugin();

    File sourceFolder();

    /** Creates the folder if needed. */
    File dataFolder();

    ModuleStore store();

    Logger logger();

    Map<String, Object> shared();

    long handlerBudgetNanos();

    long enableBudgetNanos();

    /** A guarded callback of {@code gen} threw / timed out. Never throws. */
    void runtimeError(Gen gen, String where, Throwable error);

    /** Commands were (un)registered: refresh players' command trees soon (main thread). */
    void commandsChanged();

    /** Recipes were added/removed: resend recipes to players soon (main thread). */
    void recipesChanged();

    /** Rate limiter for ctx.broadcast (false = drop this message). */
    boolean allowBroadcast();
}

package dev.vibecraft.api;

import java.io.File;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Everything a module needs to interact with the server safely.
 *
 * <p>All callbacks registered here run inside VibeCraft's guard: exceptions are caught and
 * logged (they never reach the server), infinite loops on the main thread are interrupted
 * after a time budget, and everything is unregistered automatically on reload/disable.
 *
 * <p>Use one context per module generation; never keep a reference to it in static state.
 */
public interface ModuleContext {

    /** Module id (= directory name), e.g. {@code "pig-rain"}. */
    String id();

    /** 1 for the first successful load, then incremented on every successful reload. */
    int generation();

    /** The VibeCraft host plugin. Use it for Bukkit APIs that require a {@link Plugin}. */
    Plugin plugin();

    Server server();

    /** Logger prefixed with the module id. Output is also visible via the HTTP API / dashboard. */
    Logger logger();

    /** {@code workspace/modules/<id>/} (source directory). */
    File sourceFolder();

    /** {@code workspace/data/<id>/}: persistent folder that survives reloads and restarts. */
    File dataFolder();

    /** Contents of {@code workspace/modules/<id>/config.yml} (empty config if absent). Editing that file triggers a reload. */
    YamlConfiguration config();

    /** Persistent key/value store (YAML) that survives reloads and restarts. */
    ModuleStore store();

    /**
     * Map shared by all modules (and kept across reloads) for cross-module communication.
     * Only put JDK / Bukkit / Adventure types in it (never instances of module classes).
     */
    Map<String, Object> shared();

    // ------------------------------------------------------------------ events

    /** Listen to a concrete event class (it must declare a static getHandlerList()). Priority NORMAL, ignoreCancelled false. */
    <E extends Event> void on(Class<E> type, Consumer<? super E> handler);

    <E extends Event> void on(Class<E> type, EventPriority priority, boolean ignoreCancelled, Consumer<? super E> handler);

    /** Register every {@code @EventHandler} method of a classic Bukkit listener (guarded and auto-unregistered). */
    void listen(Listener listener);

    // ---------------------------------------------------------------- commands

    /** Register a command {@code /name} (auto-unregistered on reload; clients' command lists are refreshed). */
    void command(String name, CommandHandler handler);

    void command(String name, String description, CommandHandler handler, TabHandler tabHandler, String... aliases);

    // --------------------------------------------------------------- scheduling

    /** Run once on the main thread after {@code delayTicks} (20 ticks = 1 second). */
    BukkitTask later(long delayTicks, Runnable task);

    /** Run on the main thread every {@code periodTicks}, starting after one period. */
    BukkitTask every(long periodTicks, Runnable task);

    BukkitTask every(long delayTicks, long periodTicks, Runnable task);

    /** Run once off the main thread. Never touch worlds/entities from there: use {@link #later} with delay 0 to hop back. */
    BukkitTask async(Runnable task);

    // ------------------------------------------------------------ misc helpers

    /** A namespaced key unique to this module: {@code vibecraft:<id>/<path>}. */
    NamespacedKey key(String path);

    /** Add a recipe (must implement {@link org.bukkit.Keyed}); removed automatically on unload. */
    void recipe(Recipe recipe);

    /** Track an entity spawned by the module: it is removed automatically on unload. Returns it. */
    <T extends Entity> T entity(T entity);

    /** Track any resource with its cleanup action (run on unload, in reverse registration order). Returns the resource. */
    <T> T track(T resource, Consumer<? super T> cleanup);

    /** Run an action on unload (after {@link VibeModule#disable}). */
    void onCleanup(Runnable cleanup);

    /** Parse a MiniMessage string, e.g. {@code "<gold>Hello <bold>world"}. */
    Component mm(String miniMessage);

    /** Broadcast a MiniMessage string to every player and the console. */
    void broadcast(String miniMessage);
}

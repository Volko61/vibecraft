package dev.vibecraft.api;

/**
 * Entry point of a hot-reloadable VibeCraft module.
 *
 * <p>Each module directory ({@code workspace/modules/<id>/}) must contain exactly one
 * non-abstract public class implementing this interface, with a public no-arg constructor.
 *
 * <p>Everything registered through the {@link ModuleContext} (listeners, commands, tasks,
 * recipes, entities, tracked resources...) is automatically undone when the module is
 * reloaded or disabled, so {@link #disable(ModuleContext)} is usually not needed.
 */
public interface VibeModule {

    /** Called on the main server thread when the module (re)loads. Register everything here. */
    void enable(ModuleContext ctx) throws Exception;

    /**
     * Called on the main server thread before the module is unloaded (reload, disable,
     * server stop). Registrations made through the context are cleaned up automatically
     * after this returns.
     */
    default void disable(ModuleContext ctx) throws Exception {
    }
}

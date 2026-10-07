package dev.vibecraft.core;

/** Receives ModuleManager notifications. May be called from any thread. Must not throw or block. */
@FunctionalInterface
public interface ModuleEventListener {
    void onModuleEvent(ModuleEvent event);
}

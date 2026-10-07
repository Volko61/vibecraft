package dev.vibecraft.core;

/** Notification emitted by the ModuleManager (used for chat announcements and the dashboard). */
public record ModuleEvent(String moduleId, Type type, String message, int generation, long at) {
    public enum Type {
        /** New code is running. */
        RELOADED,
        /** Attempt failed, nothing running. */
        FAILED,
        /** Attempt failed, previous good generation restored. */
        ROLLED_BACK,
        /** Module disabled (manually). */
        DISABLED,
        /** Circuit breaker disabled the module. */
        TRIPPED,
        /** A guarded callback threw / timed out (rate-limited by the manager: at most ~1 event per second per module). */
        RUNTIME_ERROR,
        /** Module directory created (scaffold or discovered). */
        CREATED,
        /** Module directory deleted: module unloaded. */
        REMOVED
    }
}

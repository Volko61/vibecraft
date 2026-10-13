package dev.vibecraft.core.guard;

/**
 * Thrown instead of {@link ModuleTimeoutError} / {@link ModuleKilledError} when module code must be
 * aborted on the main thread while it was entered directly by server code (no VibeCraft guarded call
 * on the stack: Adventure click callbacks, renderers, raw server callbacks...). It is a
 * {@link RuntimeException} on purpose: vanilla / Paper call sites only contain {@code Exception}s,
 * an {@code Error} there crashes the whole server. If module code swallows it, the next guard check
 * throws it again (the timed-out flag stays set until the next tick).
 */
public final class ModuleAbortException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ModuleAbortException(String message) {
        super(message);
    }
}

package dev.vibecraft.core.guard;

/**
 * Thrown by instrumented module code that belongs to an unloaded generation (kill switch).
 * Extends {@link Error} so that ordinary {@code catch (Exception e)} blocks in module code do not
 * swallow it; if it is swallowed anyway, the next guard check throws it again.
 */
public final class ModuleKilledError extends Error {
    private static final long serialVersionUID = 1L;

    public ModuleKilledError(String message) {
        // no stack trace: thrown often by zombie code, never interesting
        super(message, null, false, false);
    }
}

package dev.vibecraft.core.guard;

/**
 * Thrown inside module code running on the main server thread when the current guarded call
 * (event handler, command, task, enable/disable) exceeded its time budget, or when module code
 * tries to block the main thread (Thread.sleep...). Extends {@link Error}; if module code catches
 * it, the next guard check throws again until the guarded call returns.
 */
public final class ModuleTimeoutError extends Error {
    private static final long serialVersionUID = 1L;

    private final boolean budgetExceeded;

    public ModuleTimeoutError(String message) {
        this(message, true);
    }

    /**
     * @param budgetExceeded true if the time budget was actually consumed (infinite loop, long wait);
     *                       false for immediate rejections of blocking calls (Thread.sleep on the main thread)
     */
    public ModuleTimeoutError(String message, boolean budgetExceeded) {
        super(message);
        this.budgetExceeded = budgetExceeded;
    }

    public boolean budgetExceeded() {
        return budgetExceeded;
    }
}

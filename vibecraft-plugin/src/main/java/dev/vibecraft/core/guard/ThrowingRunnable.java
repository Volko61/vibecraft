package dev.vibecraft.core.guard;

/** A piece of (module) code that may throw anything. */
@FunctionalInterface
public interface ThrowingRunnable {
    void run() throws Throwable;
}

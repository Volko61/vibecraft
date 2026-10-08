package dev.vibecraft.core.guard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import dev.vibecraft.core.ctx.ModuleContextImpl;

/**
 * Guard-side state of one loaded generation of a module (one class loader). The numeric
 * {@link #id} is baked into the instrumented bytecode ({@code Guard.check(id)}).
 *
 * <p>Holds no strong reference to module classes once {@link #kill()} has run, so that killed
 * generations can be garbage collected while their ids stay resolvable.
 */
public final class Gen {

    /** Unique load id (never reused). */
    public final int id;
    public final String moduleId;
    /** Module generation number (ctx.generation()). */
    public final int generation;

    volatile boolean killed;
    final ModuleKilledError killedError;

    private volatile ModuleContextImpl ctx;
    private volatile ClassLoader loader;

    /** Main-thread nanos spent in guarded calls since the last tick fold (main thread only). */
    long mainNanos;

    private final Set<Thread> threads = Collections.newSetFromMap(new WeakHashMap<>());

    public Gen(int id, String moduleId, int generation) {
        this.id = id;
        this.moduleId = moduleId;
        this.generation = generation;
        this.killedError = new ModuleKilledError("module '" + moduleId + "' v" + generation
                + " was unloaded: its code is not allowed to run any more");
    }

    public boolean killed() {
        return killed;
    }

    public ModuleContextImpl ctx() {
        return ctx;
    }

    public void attach(ModuleContextImpl ctx, ClassLoader loader) {
        this.ctx = ctx;
        this.loader = loader;
    }

    public ClassLoader loader() {
        return loader;
    }

    /** Context of a live generation, or throws the kill error. */
    public ModuleContextImpl live() {
        ModuleContextImpl c = ctx;
        if (killed || c == null) {
            throw killedError;
        }
        return c;
    }

    /** Remember a thread started by module code (interrupted on kill). */
    public void trackThread(Thread t) {
        synchronized (threads) {
            threads.add(t);
        }
    }

    public void untrackThread(Thread t) {
        synchronized (threads) {
            threads.remove(t);
        }
    }

    /** Main-thread nanos accumulated since the last call (main thread only). */
    public long drainMainNanos() {
        long n = mainNanos;
        mainNanos = 0;
        return n;
    }

    /**
     * Set the kill flag: from now on every guard check in this generation's code throws
     * {@link ModuleKilledError}. Interrupts the threads it started and drops references.
     */
    public void kill() {
        killed = true;
        List<Thread> ts;
        synchronized (threads) {
            ts = new ArrayList<>(threads);
            threads.clear();
        }
        Thread self = Thread.currentThread();
        for (Thread t : ts) {
            if (t != null && t != self && t.isAlive()) {
                try {
                    t.interrupt();
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
        ctx = null;
        loader = null;
    }

    @Override
    public String toString() {
        return moduleId + " v" + generation + " (#" + id + (killed ? ", killed)" : ")");
    }
}

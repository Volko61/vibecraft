package dev.vibecraft.core.guard;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Runtime side of the module sandbox. Instrumented module code calls {@link #check(int)} at every
 * method entry and loop back-edge.
 *
 * <ul>
 *   <li>Kill switch: code of an unloaded generation throws {@link ModuleKilledError}.</li>
 *   <li>Time budget (main thread only): a guarded call ({@link #invoke}) sets a deadline; module
 *       code past the deadline throws {@link ModuleTimeoutError} (again and again until the guarded
 *       call returns). Outside guarded calls the fallback deadline is tick start + tick budget.</li>
 * </ul>
 *
 * The fast path is a few plain/volatile reads; {@code System.nanoTime()} is read every 1024 checks
 * or when the watchdog thread hints that a deadline may have passed (slow iterations).
 */
public final class Guard {

    private Guard() {
    }

    // ------------------------------------------------------------------ generations

    private static volatile Gen[] gens = new Gen[256];
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    /** Allocate a fresh load id (baked into bytecode before the Gen is registered). */
    public static int allocateId() {
        return NEXT_ID.getAndIncrement();
    }

    public static synchronized void register(Gen g) {
        Gen[] a = gens;
        if (g.id >= a.length) {
            a = Arrays.copyOf(a, Math.max(g.id + 1, a.length * 2));
        }
        a[g.id] = g;
        gens = a; // volatile write publishes the element
    }

    /** The generation with this load id (throws if unknown). */
    public static Gen gen(int id) {
        Gen[] a = gens;
        Gen g = id >= 0 && id < a.length ? a[id] : null;
        if (g == null) {
            throw new IllegalStateException("unknown module generation #" + id);
        }
        return g;
    }

    // ------------------------------------------------------------------ main thread budget state

    private static volatile Thread main;
    /** Nesting depth of guarded calls on the main thread (main thread only). */
    private static int depth;
    private static long deadline;
    private static long outerBudget;
    private static String outerWhere = "";
    private static Gen outerGen;
    private static boolean timedOut;
    private static int counter;

    private static volatile boolean hint;
    private static volatile long hintDeadline = Long.MAX_VALUE;
    private static volatile long tickStart = System.nanoTime();
    private static volatile long tickBudget = TimeUnit.MILLISECONDS.toNanos(2000);
    private static volatile boolean running;
    private static Thread watchdog;

    /** Must be called on the main server thread. */
    public static synchronized void init(long tickBudgetMs) {
        main = Thread.currentThread();
        tickBudget = TimeUnit.MILLISECONDS.toNanos(Math.max(50, tickBudgetMs));
        tickStart = System.nanoTime();
        hintDeadline = tickStart + tickBudget;
        if (watchdog == null) {
            running = true;
            Thread w = new Thread(Guard::watchdogLoop, "VibeCraft-guard-watchdog");
            w.setDaemon(true);
            w.setPriority(Thread.MAX_PRIORITY);
            w.start();
            watchdog = w;
        }
    }

    public static synchronized void shutdown() {
        running = false;
        if (watchdog != null) {
            watchdog.interrupt();
            watchdog = null;
        }
        tickStart = System.nanoTime();
    }

    private static void watchdogLoop() {
        while (running) {
            LockSupport.parkNanos(2_000_000L);
            if (System.nanoTime() - hintDeadline > 0) {
                hint = true;
            }
        }
    }

    public static boolean isMainThread() {
        return Thread.currentThread() == main;
    }

    /** Called at every server tick start (main thread). */
    public static void tickStart() {
        long now = System.nanoTime();
        tickStart = now;
        if (depth == 0) {
            timedOut = false;
            hint = false;
            hintDeadline = now + tickBudget;
        }
    }

    /** Remaining main-thread budget in nanos (may be negative). */
    public static long remainingNanos() {
        long dl = depth > 0 ? deadline : tickStart + tickBudget;
        return dl - System.nanoTime();
    }

    // ------------------------------------------------------------------ the check

    /** Inserted by the instrumenter at method entries and loop back-edges. Must stay tiny. */
    public static void check(int id) {
        Gen g = gens[id];
        if (g.killed) {
            throw killed(g);
        }
        if (Thread.currentThread() == main && (hint || (++counter & 1023) == 0)) {
            slowCheck(g);
        }
    }

    private static ModuleKilledError killed(Gen g) {
        if (Thread.currentThread() != main) {
            // throttle zombie threads that swallow the error in a loop
            boolean interrupted = Thread.interrupted();
            LockSupport.parkNanos(20_000_000L);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return g.killedError;
    }

    private static void slowCheck(Gen g) {
        long now = System.nanoTime();
        if (timedOut) {
            throw timeoutError(g, now);
        }
        long dl = depth > 0 ? deadline : tickStart + tickBudget;
        if (now - dl > 0) {
            timedOut = true;
            hint = true;
            throw timeoutError(g, now);
        }
        hint = false;
    }

    private static ModuleTimeoutError timeoutError(Gen g, long now) {
        if (depth > 0) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - (deadline - outerBudget));
            return new ModuleTimeoutError(outerWhere + " exceeded its main-thread time budget ("
                    + TimeUnit.NANOSECONDS.toMillis(outerBudget) + " ms, ran " + elapsedMs
                    + " ms): infinite loop or too much work in one tick? Split work across ticks (ctx.every) "
                    + "or move it off the main thread (ctx.async)");
        }
        return new ModuleTimeoutError("module '" + g.moduleId + "' code ran on the main thread while the current tick "
                + "exceeded " + TimeUnit.NANOSECONDS.toMillis(tickBudget) + " ms (infinite loop?)");
    }

    /**
     * Mark the current main-thread guarded call as timed out (used by blocking-call redirects)
     * and return the error to throw.
     */
    public static ModuleTimeoutError timeoutNow(Gen g, String what) {
        return timeoutNow(g, what, true);
    }

    /** Same, telling whether the budget was really consumed (false: blocking call rejected up front). */
    public static ModuleTimeoutError timeoutNow(Gen g, String what, boolean budgetExceeded) {
        if (Thread.currentThread() == main) {
            timedOut = true;
            hint = true;
        }
        return new ModuleTimeoutError(what, budgetExceeded);
    }

    // ------------------------------------------------------------------ guarded calls

    /**
     * Run module code under the guard. Never throws: returns the Throwable thrown by the code (or
     * null). On the main thread, the outermost guarded call sets the deadline
     * ({@code budgetNanos}) and accounts the elapsed time to {@code g}.
     */
    public static Throwable invoke(Gen g, String where, long budgetNanos, ThrowingRunnable r) {
        if (g.killed) {
            return g.killedError;
        }
        if (Thread.currentThread() != main) {
            try {
                r.run();
                return null;
            } catch (Throwable e) {
                return e;
            }
        }
        boolean outer = depth == 0;
        long start = 0;
        if (outer) {
            start = System.nanoTime();
            deadline = start + budgetNanos;
            outerBudget = budgetNanos;
            outerWhere = where;
            outerGen = g;
            timedOut = false;
            hint = false;
            counter = 0;
            hintDeadline = deadline;
        }
        depth++;
        try {
            r.run();
            return null;
        } catch (Throwable e) {
            return e;
        } finally {
            depth--;
            if (outer) {
                g.mainNanos += System.nanoTime() - start;
                timedOut = false;
                hint = false;
                outerGen = null;
                hintDeadline = tickStart + tickBudget;
            }
        }
    }

    /** True if we are inside a guarded call on the main thread. */
    public static boolean inGuardedCall() {
        return Thread.currentThread() == main && depth > 0;
    }
}

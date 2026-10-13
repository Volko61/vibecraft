package dev.vibecraft.core.guard;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import dev.vibecraft.core.ctx.ModuleContextImpl;
import dev.vibecraft.core.load.ModuleClassLoader;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.objectweb.asm.Type;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Targets of call-site rewrites in module bytecode. Every method takes the generation load id as
 * its last parameter. Names/descriptors must match what {@code Instrumenter} computes:
 * {@code (receiver, original args..., int id) -> original return type}.
 */
@SuppressWarnings({"unused", "deprecation", "removal"})
public final class Redirects {

    private Redirects() {
    }

    private static final long SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    private static Gen gen(int id) {
        Gen g = Guard.gen(id);
        g.live();
        return g;
    }

    private static ModuleContextImpl ctx(int id) {
        return Guard.gen(id).live();
    }

    private static long remainingOrThrow(Gen g, String what) {
        long rem = Guard.remainingNanos();
        if (rem <= 0) {
            throw Guard.timeoutNow(g, what + " blocked the main server thread beyond its time budget");
        }
        return rem;
    }

    // ================================================================== sleeping

    public static void sleep(long millis, int id) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        sleepNanos(TimeUnit.MILLISECONDS.toNanos(millis), id, "Thread.sleep(" + millis + ")");
    }

    public static void sleep(long millis, int nanos, int id) throws InterruptedException {
        if (millis < 0 || nanos < 0 || nanos > 999_999) {
            throw new IllegalArgumentException("invalid sleep time");
        }
        sleepNanos(TimeUnit.MILLISECONDS.toNanos(millis) + nanos, id, "Thread.sleep(" + millis + ")");
    }

    public static void sleep(Duration d, int id) throws InterruptedException {
        long n;
        try {
            n = d.toNanos();
        } catch (ArithmeticException e) {
            n = Long.MAX_VALUE;
        }
        sleepNanos(Math.max(0, n), id, "Thread.sleep(" + d + ")");
    }

    public static void sleep(TimeUnit unit, long amount, int id) throws InterruptedException {
        sleepNanos(Math.max(0, unit.toNanos(amount)), id, unit.name() + ".sleep(" + amount + ")");
    }

    private static void sleepNanos(long nanos, int id, String what) throws InterruptedException {
        Gen g = gen(id);
        if (Guard.isMainThread()) {
            throw Guard.timeoutNow(g, what + " on the main server thread would freeze the whole server: "
                    + "use ctx.later(ticks, ...) or ctx.every(...) instead (20 ticks = 1 second)", false);
        }
        long end = System.nanoTime() + nanos;
        while (true) {
            long rem = end - System.nanoTime();
            if (rem <= 0) {
                return;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(rem, 50_000_000L));
            g.live();
        }
    }

    // ================================================================== blocking calls (bounded on the main thread)

    public static void waitFor(Object o, int id) throws InterruptedException {
        waitImpl(o, 0, id);
    }

    public static void waitFor(Object o, long millis, int id) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        waitImpl(o, millis, id);
    }

    public static void waitFor(Object o, long millis, int nanos, int id) throws InterruptedException {
        if (millis < 0 || nanos < 0 || nanos > 999_999) {
            throw new IllegalArgumentException("invalid wait time");
        }
        waitImpl(o, millis + (nanos > 0 ? 1 : 0), id);
    }

    private static void waitImpl(Object o, long millis, int id) throws InterruptedException {
        Gen g = gen(id);
        if (Guard.isMainThread()) {
            long budgetMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingOrThrow(g, "Object.wait()")));
            o.wait(millis == 0 ? budgetMs : Math.min(millis, budgetMs));
            if ((millis == 0 || millis > budgetMs) && Guard.remainingNanos() <= 0) {
                throw Guard.timeoutNow(g, "Object.wait() blocked the main server thread beyond its time budget");
            }
            return;
        }
        // off the main thread: wake up regularly (allowed spurious wake-up) so killed code dies
        o.wait(millis == 0 ? 200 : Math.min(millis, 200));
        g.live();
    }

    public static void join(Thread t, int id) throws InterruptedException {
        joinImpl(t, 0, id);
    }

    public static void join(Thread t, long millis, int id) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        joinImpl(t, millis, id);
    }

    public static boolean join(Thread t, Duration d, int id) throws InterruptedException {
        long ms = Math.max(0, d.toMillis());
        joinImpl(t, ms == 0 ? 1 : ms, id);
        return !t.isAlive();
    }

    private static void joinImpl(Thread t, long millis, int id) throws InterruptedException {
        Gen g = gen(id);
        if (Guard.isMainThread()) {
            long budgetMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingOrThrow(g, "Thread.join()")));
            t.join(millis == 0 ? budgetMs : Math.min(millis, budgetMs));
            if (t.isAlive() && (millis == 0 || millis > budgetMs)) {
                throw Guard.timeoutNow(g, "Thread.join() blocked the main server thread beyond its time budget");
            }
            return;
        }
        long end = millis == 0 ? Long.MAX_VALUE : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (t.isAlive()) {
            long rem = end - System.nanoTime();
            if (rem <= 0) {
                return;
            }
            t.join(Math.max(1, Math.min(200, TimeUnit.NANOSECONDS.toMillis(rem))));
            g.live();
        }
    }

    public static Object futureGet(Future<?> f, int id) throws InterruptedException, ExecutionException {
        Gen g = gen(id);
        if (Guard.isMainThread()) {
            try {
                return f.get(remainingOrThrow(g, "Future.get()"), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                throw Guard.timeoutNow(g, "Future.get() blocked the main server thread (deadlock?): "
                        + "use a callback (thenAccept + ctx.later(0, ...)) instead of waiting");
            }
        }
        while (true) {
            try {
                return f.get(SLICE_NANOS, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                g.live();
            }
        }
    }

    public static Object futureJoin(CompletableFuture<?> f, int id) {
        Gen g = gen(id);
        if (Guard.isMainThread() && !f.isDone()) {
            try {
                return f.get(remainingOrThrow(g, "CompletableFuture.join()"), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                throw Guard.timeoutNow(g, "CompletableFuture.join() blocked the main server thread (deadlock?): "
                        + "use thenAccept + ctx.later(0, ...) instead of waiting");
            } catch (ExecutionException e) {
                throw new CompletionException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        }
        while (!f.isDone()) {
            try {
                f.get(SLICE_NANOS, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                g.live();
            } catch (ExecutionException | InterruptedException e) {
                break;
            }
        }
        return f.join();
    }

    public static void latchAwait(CountDownLatch l, int id) throws InterruptedException {
        Gen g = gen(id);
        if (Guard.isMainThread()) {
            if (!l.await(remainingOrThrow(g, "CountDownLatch.await()"), TimeUnit.NANOSECONDS)) {
                throw Guard.timeoutNow(g, "CountDownLatch.await() blocked the main server thread beyond its time budget");
            }
            return;
        }
        while (!l.await(SLICE_NANOS, TimeUnit.NANOSECONDS)) {
            g.live();
        }
    }

    public static Object queueTake(BlockingQueue<?> q, int id) throws InterruptedException {
        Gen g = gen(id);
        if (Guard.isMainThread()) {
            Object o = q.poll(remainingOrThrow(g, "BlockingQueue.take()"), TimeUnit.NANOSECONDS);
            if (o == null) {
                throw Guard.timeoutNow(g, "BlockingQueue.take() blocked the main server thread beyond its time budget");
            }
            return o;
        }
        while (true) {
            Object o = q.poll(SLICE_NANOS, TimeUnit.NANOSECONDS);
            if (o != null) {
                return o;
            }
            g.live();
        }
    }

    public static void semaphoreAcquire(Semaphore s, int id) throws InterruptedException {
        Gen g = gen(id);
        if (Guard.isMainThread()) {
            if (!s.tryAcquire(remainingOrThrow(g, "Semaphore.acquire()"), TimeUnit.NANOSECONDS)) {
                throw Guard.timeoutNow(g, "Semaphore.acquire() blocked the main server thread beyond its time budget");
            }
            return;
        }
        while (!s.tryAcquire(SLICE_NANOS, TimeUnit.NANOSECONDS)) {
            g.live();
        }
    }

    // ================================================================== threads & executors

    public static void startThread(Thread t, int id) {
        Gen g = gen(id);
        g.trackThread(t);
        if (t.getUncaughtExceptionHandler() instanceof ThreadGroup) {
            t.setUncaughtExceptionHandler((th, ex) -> {
                if (ex instanceof ModuleKilledError || g.killed()) {
                    return;
                }
                ModuleContextImpl c = g.ctx();
                if (c != null) {
                    c.report("thread " + th.getName(), ex);
                }
            });
        }
        t.start();
    }

    public static Thread trackThread(Thread t, int id) {
        Gen g = Guard.gen(id);
        if (t != null) {
            g.trackThread(t);
        }
        return t;
    }

    public static ExecutorService trackExecutorService(ExecutorService e, int id) {
        if (e != null) {
            ctx(id).trackInternal("executor", e::shutdownNow);
        }
        return e;
    }

    public static ScheduledExecutorService trackScheduledExecutorService(ScheduledExecutorService e, int id) {
        if (e != null) {
            ctx(id).trackInternal("scheduled executor", e::shutdownNow);
        }
        return e;
    }

    // ================================================================== events

    public static void registerEvents(PluginManager pm, Listener listener, Plugin plugin, int id) {
        ctx(id).listen(listener);
    }

    public static void registerEvent(PluginManager pm, Class<? extends Event> type, Listener listener,
                                     EventPriority priority, EventExecutor executor, Plugin plugin, int id) {
        ctx(id).registerRaw(type, listener, priority, executor, false);
    }

    public static void registerEvent(PluginManager pm, Class<? extends Event> type, Listener listener,
                                     EventPriority priority, EventExecutor executor, Plugin plugin,
                                     boolean ignoreCancelled, int id) {
        ctx(id).registerRaw(type, listener, priority, executor, ignoreCancelled);
    }

    /**
     * Every listener of a module is registered through its context (registerEvents / registerEvent are
     * redirected), so only the module's own registrations are undone: a listener object taken from
     * {@code HandlerList#getRegisteredListeners()} (the engine's, another module's or another plugin's) is
     * left alone.
     */
    public static void unregisterAll(Listener listener, int id) {
        ctx(id).unlisten(listener);
    }

    // ================================================================== scheduler

    private enum Mode { NOW, LATER, TIMER }

    private static BukkitTask schedule(BukkitScheduler s, Plugin p, Runnable r, Mode mode, long delay, long period,
                                       boolean async, int id) {
        ModuleContextImpl c = ctx(id);
        Runnable w = c.wrapTask(mode == Mode.TIMER ? (async ? "async repeating task" : "repeating task")
                : (async ? "async task" : "task"), r, async);
        BukkitTask t = switch (mode) {
            case NOW -> async ? s.runTaskAsynchronously(p, w) : s.runTask(p, w);
            case LATER -> async ? s.runTaskLaterAsynchronously(p, w, delay) : s.runTaskLater(p, w, delay);
            case TIMER -> async ? s.runTaskTimerAsynchronously(p, w, delay, period) : s.runTaskTimer(p, w, delay, period);
        };
        return c.trackTask(t, w);
    }

    private static void scheduleConsumer(BukkitScheduler s, Plugin p, Consumer<? super BukkitTask> consumer, Mode mode,
                                         long delay, long period, boolean async, int id) {
        CompletableFuture<BukkitTask> ref = new CompletableFuture<>();
        Runnable body = () -> {
            BukkitTask t;
            try {
                t = ref.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                t = null;
            }
            consumer.accept(t);
        };
        ref.complete(schedule(s, p, body, mode, delay, period, async, id));
    }

    public static BukkitTask sched_runTask(BukkitScheduler s, Plugin p, Runnable r, int id) {
        return schedule(s, p, r, Mode.NOW, 0, 0, false, id);
    }

    public static void sched_runTask(BukkitScheduler s, Plugin p, Consumer<? super BukkitTask> c, int id) {
        scheduleConsumer(s, p, c, Mode.NOW, 0, 0, false, id);
    }

    public static BukkitTask sched_runTask(BukkitScheduler s, Plugin p, BukkitRunnable r, int id) {
        return schedule(s, p, r, Mode.NOW, 0, 0, false, id);
    }

    public static BukkitTask sched_runTaskAsynchronously(BukkitScheduler s, Plugin p, Runnable r, int id) {
        return schedule(s, p, r, Mode.NOW, 0, 0, true, id);
    }

    public static void sched_runTaskAsynchronously(BukkitScheduler s, Plugin p, Consumer<? super BukkitTask> c, int id) {
        scheduleConsumer(s, p, c, Mode.NOW, 0, 0, true, id);
    }

    public static BukkitTask sched_runTaskAsynchronously(BukkitScheduler s, Plugin p, BukkitRunnable r, int id) {
        return schedule(s, p, r, Mode.NOW, 0, 0, true, id);
    }

    public static BukkitTask sched_runTaskLater(BukkitScheduler s, Plugin p, Runnable r, long d, int id) {
        return schedule(s, p, r, Mode.LATER, d, 0, false, id);
    }

    public static void sched_runTaskLater(BukkitScheduler s, Plugin p, Consumer<? super BukkitTask> c, long d, int id) {
        scheduleConsumer(s, p, c, Mode.LATER, d, 0, false, id);
    }

    public static BukkitTask sched_runTaskLater(BukkitScheduler s, Plugin p, BukkitRunnable r, long d, int id) {
        return schedule(s, p, r, Mode.LATER, d, 0, false, id);
    }

    public static BukkitTask sched_runTaskLaterAsynchronously(BukkitScheduler s, Plugin p, Runnable r, long d, int id) {
        return schedule(s, p, r, Mode.LATER, d, 0, true, id);
    }

    public static void sched_runTaskLaterAsynchronously(BukkitScheduler s, Plugin p, Consumer<? super BukkitTask> c,
                                                        long d, int id) {
        scheduleConsumer(s, p, c, Mode.LATER, d, 0, true, id);
    }

    public static BukkitTask sched_runTaskLaterAsynchronously(BukkitScheduler s, Plugin p, BukkitRunnable r, long d, int id) {
        return schedule(s, p, r, Mode.LATER, d, 0, true, id);
    }

    public static BukkitTask sched_runTaskTimer(BukkitScheduler s, Plugin p, Runnable r, long d, long per, int id) {
        return schedule(s, p, r, Mode.TIMER, d, per, false, id);
    }

    public static void sched_runTaskTimer(BukkitScheduler s, Plugin p, Consumer<? super BukkitTask> c, long d, long per,
                                          int id) {
        scheduleConsumer(s, p, c, Mode.TIMER, d, per, false, id);
    }

    public static BukkitTask sched_runTaskTimer(BukkitScheduler s, Plugin p, BukkitRunnable r, long d, long per, int id) {
        return schedule(s, p, r, Mode.TIMER, d, per, false, id);
    }

    public static BukkitTask sched_runTaskTimerAsynchronously(BukkitScheduler s, Plugin p, Runnable r, long d, long per,
                                                              int id) {
        return schedule(s, p, r, Mode.TIMER, d, per, true, id);
    }

    public static void sched_runTaskTimerAsynchronously(BukkitScheduler s, Plugin p, Consumer<? super BukkitTask> c,
                                                        long d, long per, int id) {
        scheduleConsumer(s, p, c, Mode.TIMER, d, per, true, id);
    }

    public static BukkitTask sched_runTaskTimerAsynchronously(BukkitScheduler s, Plugin p, BukkitRunnable r, long d,
                                                              long per, int id) {
        return schedule(s, p, r, Mode.TIMER, d, per, true, id);
    }

    public static int sched_scheduleSyncDelayedTask(BukkitScheduler s, Plugin p, Runnable r, long d, int id) {
        return schedule(s, p, r, Mode.LATER, d, 0, false, id).getTaskId();
    }

    public static int sched_scheduleSyncDelayedTask(BukkitScheduler s, Plugin p, BukkitRunnable r, long d, int id) {
        return schedule(s, p, r, Mode.LATER, d, 0, false, id).getTaskId();
    }

    public static int sched_scheduleSyncDelayedTask(BukkitScheduler s, Plugin p, Runnable r, int id) {
        return schedule(s, p, r, Mode.NOW, 0, 0, false, id).getTaskId();
    }

    public static int sched_scheduleSyncDelayedTask(BukkitScheduler s, Plugin p, BukkitRunnable r, int id) {
        return schedule(s, p, r, Mode.NOW, 0, 0, false, id).getTaskId();
    }

    public static int sched_scheduleSyncRepeatingTask(BukkitScheduler s, Plugin p, Runnable r, long d, long per, int id) {
        return schedule(s, p, r, Mode.TIMER, d, per, false, id).getTaskId();
    }

    public static int sched_scheduleSyncRepeatingTask(BukkitScheduler s, Plugin p, BukkitRunnable r, long d, long per,
                                                      int id) {
        return schedule(s, p, r, Mode.TIMER, d, per, false, id).getTaskId();
    }

    public static int sched_scheduleAsyncDelayedTask(BukkitScheduler s, Plugin p, Runnable r, long d, int id) {
        return schedule(s, p, r, Mode.LATER, d, 0, true, id).getTaskId();
    }

    public static int sched_scheduleAsyncDelayedTask(BukkitScheduler s, Plugin p, Runnable r, int id) {
        return schedule(s, p, r, Mode.NOW, 0, 0, true, id).getTaskId();
    }

    public static int sched_scheduleAsyncRepeatingTask(BukkitScheduler s, Plugin p, Runnable r, long d, long per, int id) {
        return schedule(s, p, r, Mode.TIMER, d, per, true, id).getTaskId();
    }

    public static Executor sched_getMainThreadExecutor(BukkitScheduler s, Plugin p, int id) {
        return ctx(id).mainExecutor(s, p);
    }

    public static Future<?> sched_callSyncMethod(BukkitScheduler s, Plugin p, Callable<?> c, int id) {
        return ctx(id).callSync(s, p, c);
    }

    // ------------------------------------------------------------------ BukkitRunnable.runTask*

    private static final Field BR_TASK;

    static {
        Field f = null;
        try {
            f = BukkitRunnable.class.getDeclaredField("task");
            f.setAccessible(true);
            if (!BukkitTask.class.isAssignableFrom(f.getType())) {
                f = null;
            }
        } catch (Throwable t) {
            f = null;
        }
        BR_TASK = f;
    }

    private static BukkitTask brSchedule(BukkitRunnable br, Plugin p, Mode mode, long delay, long period, boolean async,
                                         int id) {
        ModuleContextImpl c = ctx(id);
        synchronized (br) {
            if (BR_TASK == null) {
                // fallback: unguarded body, but the task is still tracked (cancelled on unload)
                BukkitTask t = switch (mode) {
                    case NOW -> async ? br.runTaskAsynchronously(p) : br.runTask(p);
                    case LATER -> async ? br.runTaskLaterAsynchronously(p, delay) : br.runTaskLater(p, delay);
                    case TIMER -> async ? br.runTaskTimerAsynchronously(p, delay, period) : br.runTaskTimer(p, delay, period);
                };
                return c.trackTask(t, null);
            }
            try {
                Object existing = BR_TASK.get(br);
                if (existing instanceof BukkitTask et) {
                    throw new IllegalStateException("Already scheduled as " + et.getTaskId());
                }
                BukkitTask t = schedule(Bukkit.getScheduler(), p, br, mode, delay, period, async, id);
                BR_TASK.set(br, t);
                return t;
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public static BukkitTask br_runTask(BukkitRunnable br, Plugin p, int id) {
        return brSchedule(br, p, Mode.NOW, 0, 0, false, id);
    }

    public static BukkitTask br_runTaskAsynchronously(BukkitRunnable br, Plugin p, int id) {
        return brSchedule(br, p, Mode.NOW, 0, 0, true, id);
    }

    public static BukkitTask br_runTaskLater(BukkitRunnable br, Plugin p, long d, int id) {
        return brSchedule(br, p, Mode.LATER, d, 0, false, id);
    }

    public static BukkitTask br_runTaskLaterAsynchronously(BukkitRunnable br, Plugin p, long d, int id) {
        return brSchedule(br, p, Mode.LATER, d, 0, true, id);
    }

    public static BukkitTask br_runTaskTimer(BukkitRunnable br, Plugin p, long d, long per, int id) {
        return brSchedule(br, p, Mode.TIMER, d, per, false, id);
    }

    public static BukkitTask br_runTaskTimerAsynchronously(BukkitRunnable br, Plugin p, long d, long per, int id) {
        return brSchedule(br, p, Mode.TIMER, d, per, true, id);
    }

    // ================================================================== Paper schedulers

    private static Consumer<ScheduledTask> pc(ModuleContextImpl c, Consumer<? super ScheduledTask> body, boolean repeating,
                                              boolean async) {
        return c.wrapPaperTask((async ? "async " : "") + (repeating ? "repeating task" : "task"), body, async);
    }

    public static void paper_execute(GlobalRegionScheduler s, Plugin p, Runnable r, int id) {
        s.execute(p, ctx(id).wrapTask("task", r, false));
    }

    public static ScheduledTask paper_run(GlobalRegionScheduler s, Plugin p, Consumer<ScheduledTask> c, int id) {
        ModuleContextImpl x = ctx(id);
        return x.trackPaperTask(s.run(p, pc(x, c, false, false)));
    }

    public static ScheduledTask paper_runDelayed(GlobalRegionScheduler s, Plugin p, Consumer<ScheduledTask> c, long d, int id) {
        ModuleContextImpl x = ctx(id);
        return x.trackPaperTask(s.runDelayed(p, pc(x, c, false, false), d));
    }

    public static ScheduledTask paper_runAtFixedRate(GlobalRegionScheduler s, Plugin p, Consumer<ScheduledTask> c, long d,
                                                     long per, int id) {
        ModuleContextImpl x = ctx(id);
        return x.trackPaperTask(s.runAtFixedRate(p, pc(x, c, true, false), d, per));
    }

    public static void paper_execute(RegionScheduler s, Plugin p, World w, int cx, int cz, Runnable r, int id) {
        s.execute(p, w, cx, cz, ctx(id).wrapTask("task", r, false));
    }

    public static void paper_execute(RegionScheduler s, Plugin p, Location l, Runnable r, int id) {
        s.execute(p, l, ctx(id).wrapTask("task", r, false));
    }

    public static ScheduledTask paper_run(RegionScheduler s, Plugin p, World w, int cx, int cz, Consumer<ScheduledTask> c,
                                          int id) {
        ModuleContextImpl x = ctx(id);
        return x.trackPaperTask(s.run(p, w, cx, cz, pc(x, c, false, false)));
    }

    public static ScheduledTask paper_run(RegionScheduler s, Plugin p, Location l, Consumer<ScheduledTask> c, int id) {
        ModuleContextImpl x = ctx(id);
        return x.trackPaperTask(s.run(p, l, pc(x, c, false, false)));
    }

    public static ScheduledTask paper_runDelayed(RegionScheduler s, Plugin p, World w, int cx, int cz,
                                                 Consumer<ScheduledTask> c, long d, int id) {
        ModuleContextImpl x = ctx(id);
        return x.trackPaperTask(s.runDelayed(p, w, cx, cz, pc(x, c, false, false), d));
    }

    public static ScheduledTask paper_runDelayed(RegionScheduler s, Plugin p, Location l, Consumer<ScheduledTask> c, long d,
                                                 int id) {
        ModuleContextImpl x = ctx(id);
        return x.trackPaperTask(s.runDelayed(p, l, pc(x, c, false, false), d));
    }

    public static ScheduledTask paper_runAtFixedRate(RegionScheduler s, Plugin p, World w, int cx, int cz,
                                                     Consumer<ScheduledTask> c, long d, long per, int id) {
        ModuleContextImpl x = ctx(id);
        return x.trackPaperTask(s.runAtFixedRate(p, w, cx, cz, pc(x, c, true, false), d, per));
    }

    public static ScheduledTask paper_runAtFixedRate(RegionScheduler s, Plugin p, Location l, Consumer<ScheduledTask> c,
                                                     long d, long per, int id) {
        ModuleContextImpl x = ctx(id);
        return x.trackPaperTask(s.runAtFixedRate(p, l, pc(x, c, true, false), d, per));
    }

    public static boolean paper_execute(EntityScheduler s, Plugin p, Runnable r, Runnable retired, long d, int id) {
        ModuleContextImpl x = ctx(id);
        return s.execute(p, x.wrapTask("entity task", r, false),
                retired == null ? null : x.wrapTask("entity task (retired)", retired, false), d);
    }

    public static ScheduledTask paper_run(EntityScheduler s, Plugin p, Consumer<ScheduledTask> c, Runnable retired, int id) {
        ModuleContextImpl x = ctx(id);
        return x.trackPaperTask(s.run(p, pc(x, c, false, false),
                retired == null ? null : x.wrapTask("entity task (retired)", retired, false)));
    }

    public static ScheduledTask paper_runDelayed(EntityScheduler s, Plugin p, Consumer<ScheduledTask> c, Runnable retired,
                                                 long d, int id) {
        ModuleContextImpl x = ctx(id);
        return x.trackPaperTask(s.runDelayed(p, pc(x, c, false, false),
                retired == null ? null : x.wrapTask("entity task (retired)", retired, false), d));
    }

    public static ScheduledTask paper_runAtFixedRate(EntityScheduler s, Plugin p, Consumer<ScheduledTask> c,
                                                     Runnable retired, long d, long per, int id) {
        ModuleContextImpl x = ctx(id);
        return x.trackPaperTask(s.runAtFixedRate(p, pc(x, c, true, false),
                retired == null ? null : x.wrapTask("entity task (retired)", retired, false), d, per));
    }

    public static ScheduledTask paper_runNow(AsyncScheduler s, Plugin p, Consumer<ScheduledTask> c, int id) {
        ModuleContextImpl x = ctx(id);
        return x.trackPaperTask(s.runNow(p, pc(x, c, false, true)));
    }

    public static ScheduledTask paper_runDelayed(AsyncScheduler s, Plugin p, Consumer<ScheduledTask> c, long d, TimeUnit u,
                                                 int id) {
        ModuleContextImpl x = ctx(id);
        return x.trackPaperTask(s.runDelayed(p, pc(x, c, false, true), d, u));
    }

    public static ScheduledTask paper_runAtFixedRate(AsyncScheduler s, Plugin p, Consumer<ScheduledTask> c, long d, long per,
                                                     TimeUnit u, int id) {
        ModuleContextImpl x = ctx(id);
        return x.trackPaperTask(s.runAtFixedRate(p, pc(x, c, true, true), d, per, u));
    }

    // ================================================================== scoreboards & boss bars (auto cleanup)

    public static Objective trackObjective(Objective o, int id) {
        return ctx(id).trackObjective(o);
    }

    public static Team trackTeam(Team t, int id) {
        return ctx(id).trackTeam(t);
    }

    public static BossBar trackBossBar(BossBar b, int id) {
        return ctx(id).trackBossBar(b);
    }

    public static KeyedBossBar trackKeyedBossBar(KeyedBossBar b, int id) {
        ctx(id).trackBossBar(b);
        return b;
    }

    public static void showBossBar(Audience a, net.kyori.adventure.bossbar.BossBar bar, int id) {
        ctx(id).showBossBar(a, bar);
    }

    public static void setScoreboard(Player p, Scoreboard sb, int id) {
        ctx(id).setScoreboard(p, sb);
    }

    // ================================================================== commands

    public static boolean registerCommand(CommandMap map, String fallbackPrefix, Command cmd, int id) {
        return ctx(id).registerRawCommand(map, null, fallbackPrefix, cmd);
    }

    public static boolean registerCommand(CommandMap map, String label, String fallbackPrefix, Command cmd, int id) {
        return ctx(id).registerRawCommand(map, label, fallbackPrefix, cmd);
    }

    public static void registerAllCommands(CommandMap map, String fallbackPrefix, List<Command> cmds, int id) {
        ModuleContextImpl c = ctx(id);
        if (cmds != null) {
            for (Command cmd : cmds) {
                c.registerRawCommand(map, null, fallbackPrefix, cmd);
            }
        }
    }

    private static void checkCommandLine(String line) {
        String r = Policy.forbiddenCommandLine(line);
        if (r != null) {
            throw new SecurityException(r);
        }
    }

    public static boolean dispatchCommand(CommandSender sender, String line, int id) {
        gen(id);
        checkCommandLine(line);
        return Bukkit.dispatchCommand(sender, line);
    }

    public static boolean dispatchCommand(Server server, CommandSender sender, String line, int id) {
        gen(id);
        checkCommandLine(line);
        return server.dispatchCommand(sender, line);
    }

    public static boolean performCommand(Player player, String line, int id) {
        gen(id);
        checkCommandLine(line);
        return player.performCommand(line);
    }

    // ================================================================== reflection

    private static final Map<String, Optional<Class<?>>> TYPES = new ConcurrentHashMap<>();

    private static boolean assignable(Class<?> owner, String superInternal) {
        Optional<Class<?>> s = TYPES.computeIfAbsent(superInternal, n -> {
            try {
                return Optional.of(Class.forName(n.replace('/', '.'), false, Redirects.class.getClassLoader()));
            } catch (Throwable t) {
                return Optional.empty();
            }
        });
        return s.isPresent() && s.get().isAssignableFrom(owner);
    }

    private static void checkClassName(String binaryName) {
        if (binaryName == null) {
            return;
        }
        String r = Policy.forbiddenType(binaryName.replace('.', '/'));
        if (r != null) {
            throw new SecurityException(r);
        }
    }

    private static void checkMember(Class<?> owner, String name, String desc) {
        if (owner == null) {
            return;
        }
        String internal = Type.getInternalName(owner);
        String r = Policy.forbiddenType(internal);
        if (r == null) {
            r = Policy.forbiddenCall(internal, name, desc, (type, sup) -> assignable(owner, sup));
        }
        if (r != null) {
            throw new SecurityException(r);
        }
    }

    public static Class<?> forName(String name, int id) throws ClassNotFoundException {
        Gen g = gen(id);
        checkClassName(name);
        ClassLoader l = g.loader();
        return Class.forName(name, true, l != null ? l : Redirects.class.getClassLoader());
    }

    public static Class<?> forName(String name, boolean init, ClassLoader loader, int id) throws ClassNotFoundException {
        gen(id);
        checkClassName(name);
        return Class.forName(name, init, loader);
    }

    public static Class<?> loadClass(ClassLoader loader, String name, int id) throws ClassNotFoundException {
        gen(id);
        checkClassName(name);
        return loader.loadClass(name);
    }

    public static Object invoke(Method m, Object target, Object[] args, int id)
            throws IllegalAccessException, InvocationTargetException {
        gen(id);
        checkMember(m.getDeclaringClass(), m.getName(), Type.getMethodDescriptor(m));
        try {
            return m.invoke(target, args);
        } catch (IllegalAccessException e) {
            if (m.getDeclaringClass().getClassLoader() instanceof ModuleClassLoader) {
                m.setAccessible(true); // module calling its own non-public method (caller-sensitive check lost)
                return m.invoke(target, args);
            }
            throw e;
        }
    }

    public static Object newInstance(Constructor<?> c, Object[] args, int id)
            throws InstantiationException, IllegalAccessException, InvocationTargetException {
        gen(id);
        checkMember(c.getDeclaringClass(), "<init>", Type.getConstructorDescriptor(c));
        try {
            return c.newInstance(args);
        } catch (IllegalAccessException e) {
            if (c.getDeclaringClass().getClassLoader() instanceof ModuleClassLoader) {
                c.setAccessible(true);
                return c.newInstance(args);
            }
            throw e;
        }
    }

    public static MethodHandle lookup_findStatic(MethodHandles.Lookup l, Class<?> c, String n, MethodType t, int id)
            throws NoSuchMethodException, IllegalAccessException {
        gen(id);
        checkMember(c, n, t.toMethodDescriptorString());
        return l.findStatic(c, n, t);
    }

    public static MethodHandle lookup_findVirtual(MethodHandles.Lookup l, Class<?> c, String n, MethodType t, int id)
            throws NoSuchMethodException, IllegalAccessException {
        gen(id);
        checkMember(c, n, t.toMethodDescriptorString());
        return l.findVirtual(c, n, t);
    }

    public static MethodHandle lookup_findSpecial(MethodHandles.Lookup l, Class<?> c, String n, MethodType t,
                                                  Class<?> caller, int id)
            throws NoSuchMethodException, IllegalAccessException {
        gen(id);
        checkMember(c, n, t.toMethodDescriptorString());
        return l.findSpecial(c, n, t, caller);
    }

    public static MethodHandle lookup_findConstructor(MethodHandles.Lookup l, Class<?> c, MethodType t, int id)
            throws NoSuchMethodException, IllegalAccessException {
        gen(id);
        checkMember(c, "<init>", t.toMethodDescriptorString());
        return l.findConstructor(c, t);
    }

    public static MethodHandle lookup_findGetter(MethodHandles.Lookup l, Class<?> c, String n, Class<?> t, int id)
            throws NoSuchFieldException, IllegalAccessException {
        gen(id);
        checkMember(c, n, null);
        return l.findGetter(c, n, t);
    }

    public static MethodHandle lookup_findStaticGetter(MethodHandles.Lookup l, Class<?> c, String n, Class<?> t, int id)
            throws NoSuchFieldException, IllegalAccessException {
        gen(id);
        checkMember(c, n, null);
        return l.findStaticGetter(c, n, t);
    }

    public static MethodHandle lookup_findSetter(MethodHandles.Lookup l, Class<?> c, String n, Class<?> t, int id)
            throws NoSuchFieldException, IllegalAccessException {
        gen(id);
        checkMember(c, n, null);
        return l.findSetter(c, n, t);
    }

    public static MethodHandle lookup_findStaticSetter(MethodHandles.Lookup l, Class<?> c, String n, Class<?> t, int id)
            throws NoSuchFieldException, IllegalAccessException {
        gen(id);
        checkMember(c, n, null);
        return l.findStaticSetter(c, n, t);
    }

    public static VarHandle lookup_findVarHandle(MethodHandles.Lookup l, Class<?> c, String n, Class<?> t, int id)
            throws NoSuchFieldException, IllegalAccessException {
        gen(id);
        checkMember(c, n, null);
        return l.findVarHandle(c, n, t);
    }

    public static VarHandle lookup_findStaticVarHandle(MethodHandles.Lookup l, Class<?> c, String n, Class<?> t, int id)
            throws NoSuchFieldException, IllegalAccessException {
        gen(id);
        checkMember(c, n, null);
        return l.findStaticVarHandle(c, n, t);
    }

    public static Class<?> lookup_findClass(MethodHandles.Lookup l, String name, int id)
            throws ClassNotFoundException, IllegalAccessException {
        gen(id);
        checkClassName(name);
        return l.findClass(name);
    }

    public static MethodHandle lookup_unreflect(MethodHandles.Lookup l, Method m, int id) throws IllegalAccessException {
        gen(id);
        checkMember(m.getDeclaringClass(), m.getName(), Type.getMethodDescriptor(m));
        return l.unreflect(m);
    }

    // ================================================================== network & locks (main thread)

    /** Blocking network calls are refused on the main thread (they can freeze it for the whole OS timeout). */
    private static void noNetworkOnMain(int id, String what) {
        Gen g = gen(id);
        if (Guard.isMainThread()) {
            throw Guard.timeoutNow(g, what + " on the main server thread would freeze the whole server while it waits "
                    + "for the network: do it inside ctx.async(() -> ...) and come back with ctx.later(0, ...)", false);
        }
    }

    public static <T> java.net.http.HttpResponse<T> httpSend(java.net.http.HttpClient c, java.net.http.HttpRequest r,
                                                             java.net.http.HttpResponse.BodyHandler<T> h, int id)
            throws java.io.IOException, InterruptedException {
        noNetworkOnMain(id, "HttpClient.send()");
        return c.send(r, h);
    }

    public static java.io.InputStream urlOpenStream(java.net.URL u, int id) throws java.io.IOException {
        noNetworkOnMain(id, "URL.openStream()");
        return u.openStream();
    }

    public static java.net.URLConnection urlOpenConnection(java.net.URL u, int id) throws java.io.IOException {
        noNetworkOnMain(id, "URL.openConnection()");
        return u.openConnection();
    }

    public static java.net.URLConnection urlOpenConnection(java.net.URL u, java.net.Proxy p, int id) throws java.io.IOException {
        noNetworkOnMain(id, "URL.openConnection()");
        return u.openConnection(p);
    }

    public static Object urlGetContent(java.net.URL u, int id) throws java.io.IOException {
        noNetworkOnMain(id, "URL.getContent()");
        return u.getContent();
    }

    public static Object urlGetContent(java.net.URL u, Class<?>[] classes, int id) throws java.io.IOException {
        noNetworkOnMain(id, "URL.getContent()");
        return u.getContent(classes);
    }

    public static java.net.InetAddress inetGetByName(String host, int id) throws java.net.UnknownHostException {
        noNetworkOnMain(id, "InetAddress.getByName() (DNS)");
        return java.net.InetAddress.getByName(host);
    }

    public static java.net.InetAddress[] inetGetAllByName(String host, int id) throws java.net.UnknownHostException {
        noNetworkOnMain(id, "InetAddress.getAllByName() (DNS)");
        return java.net.InetAddress.getAllByName(host);
    }

    public static java.net.InetAddress inetGetLocalHost(int id) throws java.net.UnknownHostException {
        noNetworkOnMain(id, "InetAddress.getLocalHost() (DNS)");
        return java.net.InetAddress.getLocalHost();
    }

    public static boolean profileComplete(com.destroystokyo.paper.profile.PlayerProfile p, int id) {
        noNetworkOnMain(id, "PlayerProfile.complete() (Mojang web lookup)");
        return p.complete();
    }

    public static boolean profileComplete(com.destroystokyo.paper.profile.PlayerProfile p, boolean textures, int id) {
        noNetworkOnMain(id, "PlayerProfile.complete() (Mojang web lookup)");
        return p.complete(textures);
    }

    public static boolean profileComplete(com.destroystokyo.paper.profile.PlayerProfile p, boolean textures, boolean online,
                                          int id) {
        noNetworkOnMain(id, "PlayerProfile.complete() (Mojang web lookup)");
        return p.complete(textures, online);
    }

    public static void lockLock(java.util.concurrent.locks.Lock l, int id) throws InterruptedException {
        Gen g = gen(id);
        if (Guard.isMainThread()) {
            if (!l.tryLock(remainingOrThrow(g, "Lock.lock()"), TimeUnit.NANOSECONDS)) {
                throw Guard.timeoutNow(g, "Lock.lock() blocked the main server thread beyond its time budget (deadlock?)");
            }
            return;
        }
        while (!l.tryLock(SLICE_NANOS, TimeUnit.NANOSECONDS)) {
            g.live();
        }
    }

    public static boolean lockTryLockTimed(java.util.concurrent.locks.Lock l, long time, TimeUnit unit, int id)
            throws InterruptedException {
        Gen g = gen(id);
        if (Guard.isMainThread()) {
            long want = unit.toNanos(time);
            long rem = remainingOrThrow(g, "Lock.tryLock()");
            if (l.tryLock(Math.min(want, rem), TimeUnit.NANOSECONDS)) {
                return true;
            }
            if (want > rem) {
                throw Guard.timeoutNow(g, "Lock.tryLock() blocked the main server thread beyond its time budget");
            }
            return false;
        }
        return l.tryLock(time, unit);
    }

    public static void conditionAwait(java.util.concurrent.locks.Condition c, int id) throws InterruptedException {
        Gen g = gen(id);
        if (Guard.isMainThread()) {
            if (c.awaitNanos(remainingOrThrow(g, "Condition.await()")) <= 0) {
                throw Guard.timeoutNow(g, "Condition.await() blocked the main server thread beyond its time budget");
            }
            return;
        }
        c.awaitNanos(SLICE_NANOS); // spurious wake-ups are allowed: callers loop on their condition
        g.live();
    }

    public static void park(int id) {
        Gen g = gen(id);
        java.util.concurrent.locks.LockSupport.parkNanos(Guard.isMainThread()
                ? Math.max(1, remainingOrThrow(g, "LockSupport.park()")) : SLICE_NANOS);
    }

    public static void park(Object blocker, int id) {
        Gen g = gen(id);
        java.util.concurrent.locks.LockSupport.parkNanos(blocker, Guard.isMainThread()
                ? Math.max(1, remainingOrThrow(g, "LockSupport.park()")) : SLICE_NANOS);
    }

    public static Object futureGetTimed(Future<?> f, long time, TimeUnit unit, int id)
            throws InterruptedException, ExecutionException, TimeoutException {
        Gen g = gen(id);
        if (Guard.isMainThread()) {
            long want = unit.toNanos(time);
            long rem = remainingOrThrow(g, "Future.get()");
            try {
                return f.get(Math.min(want, rem), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                if (want > rem) {
                    throw Guard.timeoutNow(g, "Future.get() blocked the main server thread beyond its time budget: "
                            + "use a callback (thenAccept + ctx.later(0, ...)) instead of waiting");
                }
                throw e;
            }
        }
        return f.get(time, unit);
    }

    public static boolean latchAwaitTimed(CountDownLatch l, long time, TimeUnit unit, int id) throws InterruptedException {
        Gen g = gen(id);
        if (Guard.isMainThread()) {
            long want = unit.toNanos(time);
            long rem = remainingOrThrow(g, "CountDownLatch.await()");
            if (l.await(Math.min(want, rem), TimeUnit.NANOSECONDS)) {
                return true;
            }
            if (want > rem) {
                throw Guard.timeoutNow(g, "CountDownLatch.await() blocked the main server thread beyond its time budget");
            }
            return false;
        }
        return l.await(time, unit);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void queuePut(BlockingQueue q, Object o, int id) throws InterruptedException {
        Gen g = gen(id);
        if (Guard.isMainThread()) {
            if (!q.offer(o, remainingOrThrow(g, "BlockingQueue.put()"), TimeUnit.NANOSECONDS)) {
                throw Guard.timeoutNow(g, "BlockingQueue.put() blocked the main server thread beyond its time budget");
            }
            return;
        }
        while (!q.offer(o, SLICE_NANOS, TimeUnit.NANOSECONDS)) {
            g.live();
        }
    }

    public static Object queuePollTimed(BlockingQueue<?> q, long time, TimeUnit unit, int id) throws InterruptedException {
        Gen g = gen(id);
        if (Guard.isMainThread()) {
            long want = unit.toNanos(time);
            long rem = remainingOrThrow(g, "BlockingQueue.poll()");
            Object o = q.poll(Math.min(want, rem), TimeUnit.NANOSECONDS);
            if (o == null && want > rem) {
                throw Guard.timeoutNow(g, "BlockingQueue.poll() blocked the main server thread beyond its time budget");
            }
            return o;
        }
        return q.poll(time, unit);
    }

    // ================================================================== extreme values

    /** Max speed a module may give an entity (blocks per tick): far beyond any "launch" effect, harmless for collisions. */
    public static final double MAX_SPEED = 10.0;

    private static org.bukkit.util.Vector clampVector(org.bukkit.util.Vector v) {
        if (v == null) {
            return null;
        }
        double x = v.getX();
        double y = v.getY();
        double z = v.getZ();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("velocity must be finite, got " + v + " (normalizing a zero vector gives NaN)");
        }
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len <= MAX_SPEED) {
            return v;
        }
        double k = MAX_SPEED / len;
        return new org.bukkit.util.Vector(x * k, y * k, z * k);
    }

    public static void setVelocity(org.bukkit.entity.Entity e, org.bukkit.util.Vector v, int id) {
        gen(id);
        e.setVelocity(clampVector(v));
    }

    /** Projectiles launched with a huge speed (launchProjectile / spawnArrow): clamped before their first tick. */
    private static <T extends org.bukkit.entity.Entity> T clampEntity(T e) {
        if (e != null) {
            try {
                org.bukkit.util.Vector v = e.getVelocity();
                org.bukkit.util.Vector c = clampVector(v);
                if (c != v) {
                    e.setVelocity(c);
                }
            } catch (IllegalArgumentException bad) {
                e.setVelocity(new org.bukkit.util.Vector());
            }
        }
        return e;
    }

    public static org.bukkit.entity.Projectile clampLaunched(org.bukkit.entity.Projectile p, int id) {
        return clampEntity(p);
    }

    public static org.bukkit.entity.AbstractArrow clampLaunched(org.bukkit.entity.AbstractArrow a, int id) {
        return clampEntity(a);
    }

    public static org.bukkit.entity.Arrow clampLaunched(org.bukkit.entity.Arrow a, int id) {
        return clampEntity(a);
    }

    public static void fireballSetDirection(org.bukkit.entity.Fireball f, org.bukkit.util.Vector v, int id) {
        gen(id);
        f.setDirection(clampVector(v));
    }

    public static void fireballSetAcceleration(org.bukkit.entity.Fireball f, org.bukkit.util.Vector v, int id) {
        gen(id);
        f.setAcceleration(clampVector(v));
    }

    public static void fireballSetPower(org.bukkit.entity.Fireball f, org.bukkit.util.Vector v, int id) {
        gen(id);
        f.setPower(clampVector(v));
    }

    /**
     * Max size of the item data a module may put on an ItemStack (SNBT characters). A whole inventory
     * (~100 slots) of such items stays under the 8 MB packet limit; above it, players holding the item
     * are disconnected (PacketTooLarge) and, since the item is saved with them, locked out for good.
     */
    public static final int MAX_ITEM_DATA = 64 * 1024;
    /** Max size of an entity name / text display text (JSON characters). */
    public static final int MAX_NAME_JSON = 32 * 1024;

    private static int metaSize(org.bukkit.inventory.meta.ItemMeta m) {
        if (m == null) {
            return 0;
        }
        try {
            return m.getAsString().length();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static void checkMeta(org.bukkit.inventory.meta.ItemMeta m) {
        int n = metaSize(m);
        if (n > MAX_ITEM_DATA) {
            throw new IllegalArgumentException("item data too large (" + n / 1024 + " KB, max " + MAX_ITEM_DATA / 1024
                    + " KB): players holding it would be disconnected. Use fewer / shorter lore lines or pages");
        }
    }

    public static boolean itemSetItemMeta(org.bukkit.inventory.ItemStack it, org.bukkit.inventory.meta.ItemMeta m, int id) {
        gen(id);
        checkMeta(m);
        return it.setItemMeta(m);
    }

    public static boolean itemEditMeta(org.bukkit.inventory.ItemStack it,
                                       Consumer<? super org.bukkit.inventory.meta.ItemMeta> c, int id) {
        gen(id);
        java.util.Objects.requireNonNull(c, "consumer");
        return it.editMeta(m -> {
            c.accept(m);
            checkMeta(m);
        });
    }

    public static <M extends org.bukkit.inventory.meta.ItemMeta> boolean itemEditMeta(org.bukkit.inventory.ItemStack it,
                                                                                      Class<M> type, Consumer<? super M> c,
                                                                                      int id) {
        gen(id);
        java.util.Objects.requireNonNull(c, "consumer");
        return it.editMeta(type, m -> {
            c.accept(m);
            checkMeta(m);
        });
    }

    public static void itemLore(org.bukkit.inventory.ItemStack it, List<? extends net.kyori.adventure.text.Component> lore,
                                int id) {
        gen(id);
        if (lore != null) {
            long n = 0;
            for (net.kyori.adventure.text.Component c : lore) {
                n += jsonLength(c);
            }
            if (n > MAX_ITEM_DATA) {
                throw new IllegalArgumentException("lore too large (" + n / 1024 + " KB, max " + MAX_ITEM_DATA / 1024
                        + " KB): players holding the item would be disconnected");
            }
        }
        it.lore(lore);
    }

    private static <T> void checkItemAfter(org.bukkit.inventory.ItemStack it,
                                           io.papermc.paper.datacomponent.DataComponentType.Valued<T> type) {
        int n = metaSize(it.getItemMeta());
        if (n > MAX_ITEM_DATA) {
            it.unsetData(type);
            throw new IllegalArgumentException("item data too large (" + n / 1024 + " KB, max " + MAX_ITEM_DATA / 1024
                    + " KB): players holding it would be disconnected");
        }
    }

    public static <T> void itemSetData(org.bukkit.inventory.ItemStack it,
                                       io.papermc.paper.datacomponent.DataComponentType.Valued<T> type, T value, int id) {
        gen(id);
        it.setData(type, value);
        checkItemAfter(it, type);
    }

    public static <T> void itemSetData(org.bukkit.inventory.ItemStack it,
                                       io.papermc.paper.datacomponent.DataComponentType.Valued<T> type,
                                       io.papermc.paper.datacomponent.DataComponentBuilder<T> value, int id) {
        gen(id);
        it.setData(type, value);
        checkItemAfter(it, type);
    }

    private static long jsonLength(net.kyori.adventure.text.Component c) {
        if (c == null) {
            return 0;
        }
        try {
            return net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(c).length();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    private static void checkName(net.kyori.adventure.text.Component c, String what) {
        long n = jsonLength(c);
        if (n > MAX_NAME_JSON) {
            throw new IllegalArgumentException(what + " too large (" + n / 1024 + " KB, max " + MAX_NAME_JSON / 1024
                    + " KB): nearby players would be disconnected");
        }
    }

    public static void customName(org.bukkit.Nameable n, net.kyori.adventure.text.Component c, int id) {
        gen(id);
        checkName(c, "custom name");
        n.customName(c);
    }

    public static void setCustomName(org.bukkit.Nameable n, String s, int id) {
        gen(id);
        if (s != null && s.length() > MAX_NAME_JSON) {
            throw new IllegalArgumentException("custom name too large (" + s.length() / 1024 + " KB)");
        }
        n.setCustomName(s);
    }

    public static void textDisplayText(org.bukkit.entity.TextDisplay d, net.kyori.adventure.text.Component c, int id) {
        gen(id);
        checkName(c, "text display text");
        d.text(c);
    }

    // ================================================================== recipes

    public static boolean addRecipe(org.bukkit.inventory.Recipe r, int id) {
        ctx(id).recipe(r);
        return true;
    }

    public static boolean addRecipe(org.bukkit.inventory.Recipe r, boolean resend, int id) {
        ctx(id).recipe(r);
        return true;
    }

    public static boolean addRecipe(Server s, org.bukkit.inventory.Recipe r, int id) {
        ctx(id).recipe(r);
        return true;
    }

    public static boolean addRecipe(Server s, org.bukkit.inventory.Recipe r, boolean resend, int id) {
        ctx(id).recipe(r);
        return true;
    }

    // ================================================================== registrations outside ctx

    public static net.kyori.adventure.text.event.ClickEvent<?> clickCallback(
            net.kyori.adventure.text.event.ClickCallback<Audience> cb, int id) {
        return net.kyori.adventure.text.event.ClickEvent.callback(ctx(id).wrapClickCallback(cb));
    }

    public static net.kyori.adventure.text.event.ClickEvent<?> clickCallback(
            net.kyori.adventure.text.event.ClickCallback<Audience> cb,
            net.kyori.adventure.text.event.ClickCallback.Options options, int id) {
        return net.kyori.adventure.text.event.ClickEvent.callback(ctx(id).wrapClickCallback(cb), options);
    }

    public static net.kyori.adventure.text.event.ClickEvent<?> clickCallback(
            net.kyori.adventure.text.event.ClickCallback<Audience> cb,
            Consumer<net.kyori.adventure.text.event.ClickCallback.Options.Builder> options, int id) {
        return net.kyori.adventure.text.event.ClickEvent.callback(ctx(id).wrapClickCallback(cb), options);
    }

    public static net.kyori.adventure.bossbar.BossBar bossBarAddViewer(net.kyori.adventure.bossbar.BossBar bar, Audience a,
                                                                       int id) {
        ctx(id).showBossBar(a, bar);
        return bar;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void servicesRegister(org.bukkit.plugin.ServicesManager sm, Class service, Object provider, Plugin plugin,
                                        org.bukkit.plugin.ServicePriority priority, int id) {
        ModuleContextImpl c = ctx(id);
        sm.register(service, provider, plugin, priority);
        c.trackInternal("service " + (service == null ? "?" : service.getSimpleName()), () -> sm.unregister(service, provider));
    }

    public static org.bukkit.plugin.messaging.PluginMessageListenerRegistration registerIncomingPluginChannel(
            org.bukkit.plugin.messaging.Messenger m, Plugin plugin, String channel,
            org.bukkit.plugin.messaging.PluginMessageListener listener, int id) {
        ModuleContextImpl c = ctx(id);
        java.util.Objects.requireNonNull(listener, "listener");
        // the server calls it from packet handling: run it under the guard (budget, errors recorded)
        org.bukkit.plugin.messaging.PluginMessageListener guarded = (ch, player, data) -> {
            if (!c.isClosed()) {
                c.guarded("plugin message " + ch, () -> listener.onPluginMessageReceived(ch, player, data));
            }
        };
        org.bukkit.plugin.messaging.PluginMessageListenerRegistration r = m.registerIncomingPluginChannel(plugin, channel, guarded);
        c.trackInternal("plugin channel " + channel, () -> m.unregisterIncomingPluginChannel(plugin, channel, guarded));
        return r;
    }

    public static void mapAddRenderer(org.bukkit.map.MapView v, org.bukkit.map.MapRenderer r, int id) {
        ModuleContextImpl c = ctx(id);
        v.addRenderer(r);
        c.trackInternal("map renderer", () -> v.removeRenderer(r));
    }

    public static void conversationBegin(org.bukkit.conversations.Conversation conv, int id) {
        ModuleContextImpl c = ctx(id);
        conv.begin();
        c.trackInternal("conversation", conv::abandon);
    }

    public static boolean beginConversation(org.bukkit.conversations.Conversable who,
                                            org.bukkit.conversations.Conversation conv, int id) {
        ModuleContextImpl c = ctx(id);
        boolean r = who.beginConversation(conv);
        c.trackInternal("conversation", conv::abandon);
        return r;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void addGoal(com.destroystokyo.paper.entity.ai.MobGoals goals, org.bukkit.entity.Mob mob, int priority,
                               com.destroystokyo.paper.entity.ai.Goal goal, int id) {
        ModuleContextImpl c = ctx(id);
        goals.addGoal(mob, priority, goal);
        c.trackInternal("mob goal", () -> {
            if (mob.isValid()) {
                goals.removeGoal(mob, goal);
            }
        });
    }

    public static org.bukkit.permissions.PermissionAttachment trackPermissionAttachment(
            org.bukkit.permissions.PermissionAttachment a, int id) {
        if (a != null) {
            ctx(id).trackInternal("permission attachment", a::remove);
        }
        return a;
    }

    public static void loggerAddHandler(java.util.logging.Logger l, java.util.logging.Handler h, int id) {
        ModuleContextImpl c = ctx(id);
        l.addHandler(h);
        c.trackInternal("log handler", () -> l.removeHandler(h));
    }

    public static void chatRenderer(io.papermc.paper.event.player.AbstractChatEvent e, io.papermc.paper.chat.ChatRenderer r,
                                    int id) {
        e.renderer(ctx(id).wrapChatRenderer(r));
    }

    public static org.bukkit.inventory.Inventory trackInventory(org.bukkit.inventory.Inventory inv, int id) {
        if (inv != null) {
            ctx(id).trackInventory(inv);
        }
        return inv;
    }

    public static void trackTimer(java.util.Timer t, int id) {
        if (t != null) {
            ctx(id).trackInternal("timer", t::cancel);
        }
    }

    public static void trackNewExecutor(ExecutorService e, int id) {
        if (e != null) {
            ctx(id).trackInternal("executor", e::shutdownNow);
        }
    }

    // ================================================================== isolation between modules

    public static void sched_cancelTask(BukkitScheduler s, int taskId, int id) {
        ctx(id).cancelOwnTask(taskId);
    }

    /** Same as {@link #unregisterAll(Listener, int)}: only the module's own listeners. */
    public static void handlerListUnregister(HandlerList hl, Listener listener, int id) {
        ctx(id).unlisten(listener);
    }

    // ================================================================== plugin lookup

    public static JavaPlugin getProvidingPlugin(Class<?> c, int id) {
        if (c != null && c.getClassLoader() instanceof ModuleClassLoader) {
            Plugin p = ctx(id).plugin();
            if (p instanceof JavaPlugin jp) {
                return jp;
            }
        }
        return JavaPlugin.getProvidingPlugin(c);
    }
}

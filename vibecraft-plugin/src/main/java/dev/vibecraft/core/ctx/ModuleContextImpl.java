package dev.vibecraft.core.ctx;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import dev.vibecraft.api.CommandHandler;
import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.ModuleStore;
import dev.vibecraft.api.TabHandler;
import dev.vibecraft.api.VibeModule;
import dev.vibecraft.core.guard.Gen;
import dev.vibecraft.core.guard.Guard;
import dev.vibecraft.core.guard.ModuleKilledError;
import dev.vibecraft.core.guard.ThrowingRunnable;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Context of one module generation. Every registration is tracked and undone (in reverse order)
 * by {@link #close()}; every callback into module code runs under the {@link Guard}.
 */
public final class ModuleContextImpl implements ModuleContext {

    private static final Pattern COMMAND_NAME = Pattern.compile("[a-z0-9_.-]{1,64}");

    private final ModuleHost host;
    private final Gen gen;
    private final YamlConfiguration config;

    private final Object lock = new Object();
    /** All registrations in order (cleanup runs in reverse). */
    private final ArrayDeque<Registration> regs = new ArrayDeque<>();
    private final Map<Object, List<Registration>> byListener = new IdentityHashMap<>();
    private volatile boolean closed;
    private int sinceSweep;

    private volatile VibeModule module;

    /** Non-fatal problems found while enable() ran (reported as WARNING diagnostics of the reload). */
    private final List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Click callbacks handed to Paper (cleared on close so that the old generation can be collected). */
    private final Set<GuardedClick> clicks = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** Inventories created by this generation (open ones are closed on unload). */
    private final Set<org.bukkit.inventory.Inventory> inventories = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public ModuleContextImpl(ModuleHost host, Gen gen, YamlConfiguration config) {
        this.host = host;
        this.gen = gen;
        this.config = config == null ? new YamlConfiguration() : config;
    }

    // ================================================================== registrations bookkeeping

    /** One undoable registration. */
    private abstract static class Registration {
        final String kind;
        final String label;
        volatile boolean active = true;

        Registration(String kind, String label) {
            this.kind = kind;
            this.label = label;
        }

        /** Undo; may throw (caught by the caller). */
        abstract void undo() throws Throwable;

        boolean isLive() {
            return active;
        }
    }

    private final class TaskRegistration extends Registration {
        final BukkitTask task;

        TaskRegistration(BukkitTask task) {
            super("task", "task #" + task.getTaskId());
            this.task = task;
        }

        @Override
        void undo() {
            task.cancel();
        }

        @Override
        boolean isLive() {
            if (!active || task.isCancelled()) {
                return false;
            }
            BukkitScheduler s = Bukkit.getScheduler();
            int id = task.getTaskId();
            return s.isQueued(id) || s.isCurrentlyRunning(id);
        }
    }

    private void ensureOpen() {
        if (closed || gen.killed()) {
            throw new IllegalStateException("module '" + host.id() + "' v" + gen.generation
                    + " is unloaded: registrations are no longer possible");
        }
    }

    private <R extends Registration> R add(R r) {
        synchronized (lock) {
            if (closed) {
                // registered while closing (e.g. from a racing async thread): undo right away
                try {
                    r.undo();
                } catch (Throwable ignored) {
                    // best effort
                }
                r.active = false;
                throw new IllegalStateException("module '" + host.id() + "' is unloading");
            }
            regs.addLast(r);
            if (++sinceSweep >= 256) {
                sinceSweep = 0;
                regs.removeIf(x -> !x.isLive() && (x instanceof TaskRegistration || x instanceof PaperTaskRegistration
                        || !x.active));
            }
        }
        return r;
    }

    private void addForListener(Object listener, Registration r) {
        synchronized (lock) {
            byListener.computeIfAbsent(listener, k -> new ArrayList<>()).add(r);
        }
    }

    /**
     * Undo every registration in reverse order: listeners, commands, tasks, recipes, entities and
     * custom cleanups (custom cleanups run guarded, with the enable budget).
     *
     * @return number of cleanup actions that failed
     */
    public int close() {
        List<Registration> todo;
        synchronized (lock) {
            closed = true;
            todo = new ArrayList<>(regs);
            regs.clear();
            byListener.clear();
        }
        List<GuardedClick> cl;
        synchronized (lock) {
            cl = new ArrayList<>(clicks);
            clicks.clear();
        }
        for (GuardedClick c : cl) {
            c.clear();
        }
        int failures = 0;
        for (int i = todo.size() - 1; i >= 0; i--) {
            Registration r = todo.get(i);
            if (!r.active) {
                continue;
            }
            r.active = false;
            try {
                r.undo();
            } catch (Throwable t) {
                failures++;
                if (!(t instanceof ModuleKilledError)) {
                    host.logger().log(Level.WARNING, "cleanup of " + r.label + " failed: " + t);
                }
            }
        }
        return failures;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Warnings collected while this generation was enabled (command name conflicts...). */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    private void warn(String msg) {
        if (warnings.size() < 20) {
            warnings.add(msg);
        }
        host.logger().warning(msg);
    }

    /** Inventories created by this generation that are still referenced somewhere. */
    public List<org.bukkit.inventory.Inventory> inventories() {
        synchronized (lock) {
            return new ArrayList<>(inventories);
        }
    }

    public void trackInventory(org.bukkit.inventory.Inventory inv) {
        synchronized (lock) {
            inventories.add(inv);
        }
    }

    // ================================================================== click callbacks & chat renderers (redirects)

    /** Paper keeps click callbacks for hours: the module callback is dropped on unload and runs under the guard. */
    static final class GuardedClick implements net.kyori.adventure.text.event.ClickCallback<Audience> {
        private volatile net.kyori.adventure.text.event.ClickCallback<Audience> inner;
        private volatile ModuleContextImpl ctx;

        GuardedClick(net.kyori.adventure.text.event.ClickCallback<Audience> inner, ModuleContextImpl ctx) {
            this.inner = inner;
            this.ctx = ctx;
        }

        void clear() {
            inner = null;
            ctx = null;
        }

        @Override
        public void accept(Audience audience) {
            net.kyori.adventure.text.event.ClickCallback<Audience> i = inner;
            ModuleContextImpl c = ctx;
            if (i == null || c == null || c.gen.killed()) {
                try {
                    audience.sendMessage(Component.text("Ce lien n'est plus actif (module rechargé).",
                            net.kyori.adventure.text.format.NamedTextColor.GRAY));
                } catch (Throwable ignored) {
                    // best effort
                }
                return;
            }
            c.guarded("click callback", () -> i.accept(audience));
        }
    }

    public net.kyori.adventure.text.event.ClickCallback<Audience> wrapClickCallback(
            net.kyori.adventure.text.event.ClickCallback<Audience> cb) {
        Objects.requireNonNull(cb, "callback");
        ensureOpen();
        GuardedClick g = new GuardedClick(cb, this);
        synchronized (lock) {
            clicks.add(g);
        }
        return g;
    }

    /** AsyncChatEvent renderer: guarded, falls back to the default format on error or once unloaded. */
    public io.papermc.paper.chat.ChatRenderer wrapChatRenderer(io.papermc.paper.chat.ChatRenderer r) {
        Objects.requireNonNull(r, "renderer");
        return (source, displayName, message, viewer) -> {
            if (!gen.killed()) {
                Component[] out = new Component[1];
                Throwable t = Guard.invoke(gen, "chat renderer", host.handlerBudgetNanos(),
                        () -> out[0] = r.render(source, displayName, message, viewer));
                if (t == null && out[0] != null) {
                    return out[0];
                }
                report("chat renderer", t);
            }
            return io.papermc.paper.chat.ChatRenderer.defaultRenderer().render(source, displayName, message, viewer);
        };
    }

    /** {@code BukkitScheduler.cancelTask(id)} redirect: only this generation's own tasks may be cancelled. */
    public void cancelOwnTask(int taskId) {
        List<Registration> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(regs);
        }
        for (Registration r : snapshot) {
            if (r instanceof TaskRegistration tr && tr.task.getTaskId() == taskId) {
                tr.task.cancel();
                return;
            }
        }
        warn("cancelTask(" + taskId + ") ignored: it is not a task of this module (keep the BukkitTask returned by "
                + "ctx.every / ctx.later and call cancel() on it)");
    }

    /** Names of the commands registered by this generation. */
    public List<String> commandNames() {
        List<String> out = new ArrayList<>();
        synchronized (lock) {
            for (Registration r : regs) {
                if (r.active && r.kind.equals("command")) {
                    out.add(r.label);
                }
            }
        }
        return out;
    }

    public int listenerCount() {
        int n = 0;
        synchronized (lock) {
            for (Registration r : regs) {
                if (r.active && r.kind.equals("listener")) {
                    n++;
                }
            }
        }
        return n;
    }

    public int taskCount() {
        List<Registration> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(regs);
        }
        int n = 0;
        for (Registration r : snapshot) {
            if ((r instanceof TaskRegistration || r instanceof PaperTaskRegistration) && r.isLive()) {
                n++;
            }
        }
        return n;
    }

    public void setModule(VibeModule module) {
        this.module = module;
    }

    public VibeModule module() {
        return module;
    }

    public Gen gen() {
        return gen;
    }

    public ModuleHost host() {
        return host;
    }

    long handlerBudget() {
        return host.handlerBudgetNanos();
    }

    /** Record a runtime error (ignores kill errors). */
    public void report(String where, Throwable t) {
        if (t == null || t instanceof ModuleKilledError || gen.killed()) {
            return;
        }
        host.runtimeError(gen, where, t);
    }

    /** Run module code on the current thread with the handler budget; errors are reported. */
    public boolean guarded(String where, ThrowingRunnable r) {
        Throwable t = Guard.invoke(gen, where, host.handlerBudgetNanos(), r);
        if (t != null) {
            report(where, t);
            return false;
        }
        return true;
    }

    // ================================================================== guarded runnables

    /** A Bukkit task body that runs module code under the guard and cancels itself once killed. */
    private final class GuardedTask implements Runnable {
        final String where;
        final ThrowingRunnable body;
        final boolean async;
        volatile BukkitTask task;

        GuardedTask(String where, ThrowingRunnable body, boolean async) {
            this.where = where;
            this.body = body;
            this.async = async;
        }

        @Override
        public void run() {
            if (gen.killed()) {
                BukkitTask t = task;
                if (t != null) {
                    t.cancel();
                }
                return;
            }
            if (!async) {
                guarded(where, body);
                return;
            }
            Thread th = Thread.currentThread();
            gen.trackThread(th);
            try {
                Throwable t = Guard.invoke(gen, where, Long.MAX_VALUE / 4, body);
                if (t != null) {
                    report(where, t);
                }
            } finally {
                gen.untrackThread(th);
                Thread.interrupted(); // never leak our kill interrupt into a pooled scheduler thread
            }
        }
    }

    public Runnable wrapTask(String where, Runnable r, boolean async) {
        Objects.requireNonNull(r, "task");
        return new GuardedTask(where, r::run, async);
    }

    /** Track a task created through raw Bukkit APIs (redirects) or the context. */
    public BukkitTask trackTask(BukkitTask t, Runnable wrapper) {
        if (t == null) {
            return null;
        }
        if (wrapper instanceof GuardedTask g) {
            g.task = t;
        }
        add(new TaskRegistration(t));
        return t;
    }

    // ================================================================== Paper schedulers (redirects)

    private final class PaperTaskRegistration extends Registration {
        final ScheduledTask task;

        PaperTaskRegistration(ScheduledTask task) {
            super("task", "paper task");
            this.task = task;
        }

        @Override
        void undo() {
            task.cancel();
        }

        @Override
        boolean isLive() {
            if (!active || task.isCancelled()) {
                return false;
            }
            ScheduledTask.ExecutionState st = task.getExecutionState();
            return st != ScheduledTask.ExecutionState.FINISHED && st != ScheduledTask.ExecutionState.CANCELLED;
        }
    }

    /** A Paper scheduler task body: module consumer run under the guard, self-cancelling once killed. */
    public Consumer<ScheduledTask> wrapPaperTask(String where, Consumer<? super ScheduledTask> body, boolean async) {
        Objects.requireNonNull(body, "task");
        ensureOpen();
        return task -> {
            if (gen.killed()) {
                task.cancel();
                return;
            }
            if (!async) {
                guarded(where, () -> body.accept(task));
                return;
            }
            Thread th = Thread.currentThread();
            gen.trackThread(th);
            try {
                Throwable t = Guard.invoke(gen, where, Long.MAX_VALUE / 4, () -> body.accept(task));
                if (t != null) {
                    report(where, t);
                }
            } finally {
                gen.untrackThread(th);
                Thread.interrupted();
            }
        };
    }

    public ScheduledTask trackPaperTask(ScheduledTask t) {
        if (t != null) {
            add(new PaperTaskRegistration(t));
        }
        return t;
    }

    // ================================================================== visual leftovers (redirects)

    /** Identity pair (dedupe of tracked resources). */
    private record IdPair(Object a, Object b) {
        @Override
        public boolean equals(Object o) {
            return o instanceof IdPair p && p.a == a && p.b == b;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(a) * 31 + System.identityHashCode(b);
        }
    }

    private final Set<IdPair> trackedOnce = new java.util.HashSet<>();

    private boolean firstTime(Object a, Object b) {
        synchronized (lock) {
            return trackedOnce.add(new IdPair(a, b));
        }
    }

    public Objective trackObjective(Objective o) {
        if (o != null && firstTime(o, null)) {
            add(new Registration("internal", "objective " + o.getName()) {
                @Override
                void undo() {
                    try {
                        o.unregister();
                    } catch (IllegalStateException ignored) {
                        // already unregistered
                    }
                }
            });
        }
        return o;
    }

    public Team trackTeam(Team t) {
        if (t != null && firstTime(t, null)) {
            add(new Registration("internal", "team " + t.getName()) {
                @Override
                void undo() {
                    try {
                        t.unregister();
                    } catch (IllegalStateException ignored) {
                        // already unregistered
                    }
                }
            });
        }
        return t;
    }

    public BossBar trackBossBar(BossBar b) {
        if (b != null && firstTime(b, null)) {
            add(new Registration("internal", "boss bar") {
                @Override
                void undo() {
                    b.removeAll();
                    b.setVisible(false);
                    if (b instanceof KeyedBossBar k) {
                        Bukkit.removeBossBar(k.getKey());
                    }
                }
            });
        }
        return b;
    }

    /** Adventure {@code audience.showBossBar(bar)}: hidden again on unload. */
    public void showBossBar(Audience audience, net.kyori.adventure.bossbar.BossBar bar) {
        audience.showBossBar(bar);
        if (firstTime(audience, bar)) {
            add(new Registration("internal", "adventure boss bar") {
                @Override
                void undo() {
                    audience.hideBossBar(bar);
                }
            });
        }
    }

    /** {@code player.setScoreboard(sb)}: the player gets the main scoreboard back on unload if still on sb. */
    public void setScoreboard(Player player, Scoreboard sb) {
        player.setScoreboard(sb);
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        if (sb != main && firstTime(player, sb)) {
            add(new Registration("internal", "scoreboard of " + player.getName()) {
                @Override
                void undo() {
                    if (player.isOnline() && player.getScoreboard() == sb) {
                        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                    }
                }
            });
        }
    }

    // ================================================================== ModuleContext: basics

    @Override
    public String id() {
        return host.id();
    }

    @Override
    public int generation() {
        return gen.generation;
    }

    @Override
    public Plugin plugin() {
        return host.plugin();
    }

    @Override
    public Server server() {
        return Bukkit.getServer();
    }

    @Override
    public Logger logger() {
        return host.logger();
    }

    @Override
    public File sourceFolder() {
        return host.sourceFolder();
    }

    @Override
    public File dataFolder() {
        return host.dataFolder();
    }

    @Override
    public YamlConfiguration config() {
        return config;
    }

    @Override
    public ModuleStore store() {
        return host.store();
    }

    @Override
    public Map<String, Object> shared() {
        return host.shared();
    }

    // ================================================================== events

    @Override
    public <E extends Event> void on(Class<E> type, Consumer<? super E> handler) {
        on(type, EventPriority.NORMAL, false, handler);
    }

    @Override
    public <E extends Event> void on(Class<E> type, EventPriority priority, boolean ignoreCancelled,
                                     Consumer<? super E> handler) {
        Objects.requireNonNull(type, "event type");
        Objects.requireNonNull(handler, "handler");
        registerEvent(type, priority, ignoreCancelled, null, ev -> handler.accept(type.cast(ev)));
    }

    /** Module code receiving an event. */
    @FunctionalInterface
    private interface EventBody {
        void call(Event e) throws Throwable;
    }

    private void registerEvent(Class<? extends Event> type, EventPriority priority, boolean ignoreCancelled,
                               Object ownerListener, EventBody body) {
        ensureOpen();
        HandlerList hl = handlerListOf(type);
        String where = "event " + type.getSimpleName();
        Listener token = new Listener() {
        };
        EventExecutor executor = (l, ev) -> {
            if (type.isInstance(ev) && !gen.killed()) {
                guarded(where, () -> body.call(ev));
            }
        };
        Registration r = new Registration("listener", type.getSimpleName()) {
            @Override
            void undo() {
                hl.unregister(token);
            }
        };
        Bukkit.getPluginManager().registerEvent(type, token, priority == null ? EventPriority.NORMAL : priority,
                executor, host.plugin(), ignoreCancelled);
        add(r);
        if (ownerListener != null) {
            addForListener(ownerListener, r);
        }
    }

    /** The HandlerList a registration for {@code type} goes to (same lookup as Bukkit). */
    static HandlerList handlerListOf(Class<? extends Event> type) {
        Class<?> c = type;
        while (c != null && Event.class.isAssignableFrom(c) && c != Event.class) {
            try {
                Method m = c.getDeclaredMethod("getHandlerList");
                if (Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    Object o = m.invoke(null);
                    if (o instanceof HandlerList hl) {
                        return hl;
                    }
                }
            } catch (NoSuchMethodException e) {
                // try the superclass
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalArgumentException("cannot get the handler list of " + type.getName() + ": " + e, e);
            }
            c = c.getSuperclass();
        }
        throw new IllegalArgumentException("cannot listen to " + type.getSimpleName()
                + ": it is an abstract event without a handler list (no static getHandlerList()). "
                + "Listen to a concrete event instead (e.g. PlayerJoinEvent, PlayerMoveEvent, EntityDamageEvent)");
    }

    @Override
    public void listen(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        ensureOpen();
        Set<Method> methods = new LinkedHashSet<>(Arrays.asList(listener.getClass().getMethods()));
        for (Class<?> k = listener.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            methods.addAll(Arrays.asList(k.getDeclaredMethods()));
        }
        record Handler(Class<? extends Event> type, EventHandler eh, MethodHandle mh) {
        }
        List<Handler> handlers = new ArrayList<>();
        for (Method m : methods) {
            EventHandler eh = m.getAnnotation(EventHandler.class);
            if (eh == null || m.isBridge() || m.isSynthetic()) {
                continue;
            }
            if (m.getParameterCount() != 1 || !Event.class.isAssignableFrom(m.getParameterTypes()[0])) {
                throw new IllegalArgumentException("@EventHandler method " + m.getName() + " of "
                        + listener.getClass().getSimpleName() + " must take exactly one Event parameter");
            }
            Class<? extends Event> type = m.getParameterTypes()[0].asSubclass(Event.class);
            handlerListOf(type); // validate before registering anything
            try {
                m.setAccessible(true);
                MethodHandle mh = MethodHandles.lookup().unreflect(m);
                if (Modifier.isStatic(m.getModifiers())) {
                    mh = MethodHandles.dropArguments(mh, 0, Object.class);
                }
                mh = mh.asType(MethodType.methodType(void.class, Object.class, Event.class));
                handlers.add(new Handler(type, eh, mh));
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalArgumentException("cannot access @EventHandler " + m.getName() + ": " + e, e);
            }
        }
        if (handlers.isEmpty()) {
            host.logger().warning("listen(" + listener.getClass().getSimpleName()
                    + "): no @EventHandler method found");
        }
        for (Handler h : handlers) {
            MethodHandle mh = h.mh();
            registerEvent(h.type(), h.eh().priority(), h.eh().ignoreCancelled(), listener, ev -> {
                // statement form: invokeExact must be compiled with a void return type
                mh.invokeExact((Object) listener, ev);
            });
        }
    }

    /** Raw {@code PluginManager.registerEvent(...)} from module code (redirect): guarded and tracked. */
    public void registerRaw(Class<? extends Event> type, Listener listener, EventPriority priority,
                            EventExecutor executor, boolean ignoreCancelled) {
        Objects.requireNonNull(type, "event type");
        Objects.requireNonNull(executor, "executor");
        registerEvent(type, priority, ignoreCancelled, listener, ev -> executor.execute(listener, ev));
    }

    /** Undo every registration made for {@code listener} (HandlerList.unregisterAll(listener) redirect). */
    public void unlisten(Listener listener) {
        List<Registration> rs;
        synchronized (lock) {
            rs = byListener.remove(listener);
        }
        if (rs == null) {
            return;
        }
        for (Registration r : rs) {
            if (r.active) {
                r.active = false;
                try {
                    r.undo();
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    // ================================================================== commands

    @Override
    public void command(String name, CommandHandler handler) {
        command(name, "", handler, null);
    }

    @Override
    public void command(String name, String description, CommandHandler handler, TabHandler tabHandler,
                        String... aliases) {
        Objects.requireNonNull(handler, "handler");
        ensureOpen();
        String n = normalizeCommand(name);
        List<String> al = new ArrayList<>();
        if (aliases != null) {
            for (String a : aliases) {
                if (a != null && !a.isBlank()) {
                    String na = normalizeCommand(a);
                    if (!na.equals(n) && !al.contains(na)) {
                        al.add(na);
                    }
                }
            }
        }
        if (!Guard.isMainThread() && !Bukkit.isPrimaryThread()) {
            // command map changes must happen on the main thread
            Bukkit.getScheduler().runTask(host.plugin(), () -> {
                if (!closed && !gen.killed()) {
                    try {
                        registerCommand(n, description, handler, tabHandler, al);
                    } catch (RuntimeException e) {
                        warn(String.valueOf(e.getMessage()));
                    }
                }
            });
            return;
        }
        registerCommand(n, description, handler, tabHandler, al);
    }

    private static String normalizeCommand(String name) {
        Objects.requireNonNull(name, "command name");
        String n = name.trim();
        while (n.startsWith("/")) {
            n = n.substring(1);
        }
        n = n.toLowerCase(Locale.ROOT);
        if (!COMMAND_NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("invalid command name '" + name
                    + "': use lowercase letters, digits, '_', '-' or '.' (no spaces)");
        }
        return n;
    }

    /** Who already owns {@code label} in the command map ("module 'x'", "the server"...), or null if free. */
    private String ownerOf(Map<String, Command> known, String label) {
        Command c = known.get(label);
        if (c == null) {
            return null;
        }
        Gen other = c instanceof ModuleCommand mc ? mc.gen() : c instanceof GuardedRawCommand gc ? gc.gen() : null;
        if (other != null) {
            if (other.killed()) {
                return null; // leftover of an unloaded generation (removed below)
            }
            return other == gen ? "this module (registered twice)" : "module '" + other.moduleId + "'";
        }
        return "the server (a vanilla or plugin command)";
    }

    private void registerCommand(String name, String description, CommandHandler handler, TabHandler tab,
                                 List<String> aliases) {
        CommandMap map = Bukkit.getCommandMap();
        Map<String, Command> known = map.getKnownCommands();
        String owner = ownerOf(known, name);
        if (owner != null) {
            throw new IllegalArgumentException("command /" + name + " is already used by " + owner
                    + ": pick another command name (other AI sessions may be building modules at the same time)");
        }
        List<String> free = new ArrayList<>();
        for (String a : aliases) {
            String o = ownerOf(known, a);
            if (o == null) {
                free.add(a);
            } else {
                warn("alias /" + a + " of /" + name + " ignored: already used by " + o);
            }
        }
        // leftovers of killed generations under our labels: drop them so they cannot shadow us
        for (String l : List.of(name, "vibe:" + name)) {
            Command c = known.get(l);
            if (c != null && ownerOf(known, l) == null) {
                known.remove(l);
            }
        }
        ModuleCommand cmd = new ModuleCommand(name, description, free, this, gen, handler, tab);
        boolean primary = map.register("vibe", cmd);
        if (!primary) {
            warn("/" + name + " could not be registered under its own name: use /vibe:" + name);
        }
        add(new Registration("command", name) {
            @Override
            void undo() {
                unregisterCommand(map, cmd);
            }
        });
        host.commandsChanged();
    }

    private void unregisterCommand(CommandMap map, Command cmd) {
        Map<String, Command> known = map.getKnownCommands();
        Set<String> labels = new LinkedHashSet<>();
        labels.add(cmd.getName());
        labels.add(cmd.getLabel());
        labels.addAll(cmd.getAliases());
        for (String l : new ArrayList<>(labels)) {
            labels.add("vibe:" + l);
        }
        for (String l : labels) {
            String key = l.toLowerCase(Locale.ROOT);
            try {
                if (known.get(key) == cmd) {
                    known.remove(key);
                }
            } catch (RuntimeException e) {
                host.logger().log(Level.FINE, "could not remove /" + key + ": " + e);
            }
        }
        try {
            cmd.unregister(map);
        } catch (RuntimeException ignored) {
            // already gone
        }
        host.commandsChanged();
    }

    /** Raw {@code CommandMap.register(...)} from module code (redirect): tracked for removal. */
    public boolean registerRawCommand(CommandMap map, String label, String fallbackPrefix, Command raw) {
        ensureOpen();
        String rawName = raw.getName() == null ? "" : raw.getName().toLowerCase(Locale.ROOT);
        Command existing = map.getKnownCommands().get(rawName);
        Gen other = existing instanceof ModuleCommand mc ? mc.gen() : existing instanceof GuardedRawCommand gc ? gc.gen() : null;
        if (other != null && other != gen && !other.killed()) {
            throw new IllegalArgumentException("command /" + rawName + " is already used by module '" + other.moduleId
                    + "': pick another command name");
        }
        // the module's own Command subclass runs under the guard through a delegating wrapper
        Command cmd = raw instanceof ModuleCommand || raw instanceof GuardedRawCommand ? raw
                : new GuardedRawCommand(raw, this, gen);
        boolean r = label == null ? map.register(fallbackPrefix, cmd) : map.register(label, fallbackPrefix, cmd);
        add(new Registration("command", cmd.getName()) {
            @Override
            void undo() {
                Map<String, Command> known = map.getKnownCommands();
                Iterator<Map.Entry<String, Command>> it = known.entrySet().iterator();
                List<String> remove = new ArrayList<>();
                while (it.hasNext()) {
                    Map.Entry<String, Command> e = it.next();
                    if (e.getValue() == cmd) {
                        remove.add(e.getKey());
                    }
                }
                for (String k : remove) {
                    known.remove(k);
                }
                cmd.unregister(map);
                host.commandsChanged();
            }
        });
        host.commandsChanged();
        return r;
    }

    // ================================================================== scheduling

    @Override
    public BukkitTask later(long delayTicks, Runnable task) {
        ensureOpen();
        Runnable w = wrapTask("task", task, false);
        return trackTask(Bukkit.getScheduler().runTaskLater(host.plugin(), w, Math.max(0, delayTicks)), w);
    }

    @Override
    public BukkitTask every(long periodTicks, Runnable task) {
        return every(periodTicks, periodTicks, task);
    }

    @Override
    public BukkitTask every(long delayTicks, long periodTicks, Runnable task) {
        ensureOpen();
        Runnable w = wrapTask("repeating task", task, false);
        return trackTask(Bukkit.getScheduler().runTaskTimer(host.plugin(), w, Math.max(0, delayTicks),
                Math.max(1, periodTicks)), w);
    }

    @Override
    public BukkitTask async(Runnable task) {
        ensureOpen();
        Runnable w = wrapTask("async task", task, true);
        return trackTask(Bukkit.getScheduler().runTaskAsynchronously(host.plugin(), w), w);
    }

    /** Executor for {@code BukkitScheduler.getMainThreadExecutor} redirects. */
    public Executor mainExecutor(BukkitScheduler s, Plugin p) {
        Executor real = s.getMainThreadExecutor(p);
        return command -> {
            ensureOpen();
            real.execute(wrapTask("main-thread executor task", command, false));
        };
    }

    /** {@code callSyncMethod} redirect: callable guarded, result null on error. */
    public <T> Future<T> callSync(BukkitScheduler s, Plugin p, Callable<T> c) {
        ensureOpen();
        return s.callSyncMethod(p, () -> {
            @SuppressWarnings("unchecked")
            T[] out = (T[]) new Object[1];
            Throwable t = Guard.invoke(gen, "callSyncMethod", host.handlerBudgetNanos(), () -> out[0] = c.call());
            if (t != null) {
                report("callSyncMethod", t);
                if (t instanceof Exception e) {
                    throw e;
                }
                throw new RuntimeException(t);
            }
            return out[0];
        });
    }

    // ================================================================== misc helpers

    @Override
    public NamespacedKey key(String path) {
        Objects.requireNonNull(path, "path");
        String p = path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
        return new NamespacedKey(host.plugin(), host.id() + "/" + p);
    }

    @Override
    public void recipe(Recipe recipe) {
        Objects.requireNonNull(recipe, "recipe");
        ensureOpen();
        if (!(recipe instanceof Keyed keyed)) {
            throw new IllegalArgumentException("recipe must be Keyed (ShapedRecipe, ShapelessRecipe, FurnaceRecipe...)");
        }
        NamespacedKey key = keyed.getKey();
        // (the 2-argument overloads are deprecated since 26.3: their resendRecipes flag is ignored)
        if (Bukkit.getRecipe(key) != null) {
            Bukkit.removeRecipe(key);
        }
        if (!Bukkit.addRecipe(recipe)) {
            throw new IllegalArgumentException("could not add recipe " + key);
        }
        add(new Registration("recipe", key.toString()) {
            @Override
            void undo() {
                Bukkit.removeRecipe(key);
                host.recipesChanged();
            }
        });
        host.recipesChanged();
    }

    @Override
    public <T extends Entity> T entity(T entity) {
        Objects.requireNonNull(entity, "entity");
        ensureOpen();
        if (entity instanceof Player) {
            return entity; // players are never removed
        }
        // Only the UUID is kept: the Bukkit object goes stale when its chunk unloads (a new object comes back
        // with the chunk) and would keep dead entities in memory. The tag lets the engine delete copies that
        // come back after this generation is gone (chunk loaded later, server crash / restart).
        java.util.UUID uuid = entity.getUniqueId();
        try {
            entity.getPersistentDataContainer().set(EntityTags.key(host.plugin()),
                    org.bukkit.persistence.PersistentDataType.STRING, EntityTags.value(host.id(), gen.id));
        } catch (RuntimeException ignored) {
            // entity without PDC support: still removed on unload while loaded
        }
        String type = entity.getType().name();
        add(new Registration("entity", type) {
            @Override
            void undo() {
                Entity e = Bukkit.getEntity(uuid);
                if (e != null && !(e instanceof Player)) {
                    e.remove();
                }
            }
        });
        return entity;
    }

    @Override
    public <T> T track(T resource, Consumer<? super T> cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        ensureOpen();
        add(new Registration("track", "track(" + (resource == null ? "null" : resource.getClass().getSimpleName()) + ")") {
            @Override
            void undo() throws Throwable {
                Throwable t = Guard.invoke(gen, "cleanup", host.enableBudgetNanos(), () -> cleanup.accept(resource));
                if (t != null) {
                    throw t;
                }
            }
        });
        return resource;
    }

    /** Track a host-side cleanup (redirects: executors, threads...). */
    public void trackInternal(String label, Runnable undo) {
        ensureOpen();
        add(new Registration("internal", label) {
            @Override
            void undo() {
                undo.run();
            }
        });
    }

    @Override
    public void onCleanup(Runnable cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        ensureOpen();
        add(new Registration("track", "onCleanup") {
            @Override
            void undo() throws Throwable {
                Throwable t = Guard.invoke(gen, "onCleanup", host.enableBudgetNanos(), cleanup::run);
                if (t != null) {
                    throw t;
                }
            }
        });
    }

    @Override
    public Component mm(String miniMessage) {
        return MiniMessage.miniMessage().deserialize(miniMessage == null ? "" : miniMessage);
    }

    @Override
    public void broadcast(String miniMessage) {
        if (!host.allowBroadcast()) {
            return;
        }
        Bukkit.getServer().broadcast(mm(miniMessage));
    }
}

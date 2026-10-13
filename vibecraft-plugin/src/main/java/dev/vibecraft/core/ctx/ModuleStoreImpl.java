package dev.vibecraft.core.ctx;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.vibecraft.api.ModuleStore;
import dev.vibecraft.core.load.ModuleClassLoader;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerializable;

/**
 * Persistent YAML store of one module ({@code data/<id>/store.yml}). One instance per module,
 * shared by all its generations. Thread-safe.
 *
 * <p>Only YAML-safe values are accepted (anything else would be written as a Java tag that Bukkit
 * refuses to load, wiping the whole store at the next start). Saving copies the value tree under the
 * lock and serializes it outside of it, on the engine's io thread when possible, so a big store
 * never blocks the main thread for long.
 */
public final class ModuleStoreImpl implements ModuleStore {

    private static final int WARN_SIZE = 1_000_000;

    private final File file;
    private final Logger log;
    private final Executor io;
    private final Object saveLock = new Object();
    private YamlConfiguration yaml;
    private boolean dirty;
    private boolean warnedSize;

    public ModuleStoreImpl(File file, Logger log, Executor io) {
        this.file = file;
        this.log = log;
        this.io = io;
    }

    /** Load the file now (call off the main thread before enable(), so enable() does not pay for it). */
    public synchronized void preload() {
        yaml();
    }

    private YamlConfiguration yaml() {
        if (yaml == null) {
            YamlConfiguration y = new YamlConfiguration();
            if (file.isFile()) {
                String text = null;
                try {
                    text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                    y.loadFromString(text);
                } catch (Exception e) {
                    y = lenient(text, e);
                }
            }
            yaml = y;
        }
        return yaml;
    }

    /** A store written by an older version with Java tags ({@code !!java.time.Instant}): keep everything else. */
    private YamlConfiguration lenient(String text, Exception first) {
        backup();
        if (text != null) {
            try {
                YamlConfiguration y = new YamlConfiguration();
                y.loadFromString(text.replaceAll("!![A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+", ""));
                log.log(Level.WARNING, "store.yml contained values that cannot be loaded (" + oneLine(first.getMessage())
                        + "): those values were dropped, everything else was kept (backup kept)");
                dirty = true;
                return y;
            } catch (InvalidConfigurationException | RuntimeException e) {
                // fall through
            }
        }
        log.log(Level.WARNING, "store.yml is corrupt, starting empty (backup kept): " + oneLine(first.getMessage()));
        return new YamlConfiguration();
    }

    private static String oneLine(String m) {
        String s = String.valueOf(m).replaceAll("\\s+", " ").strip();
        return s.length() <= 200 ? s : s.substring(0, 199) + "…";
    }

    private void backup() {
        try {
            Files.copy(file.toPath(), file.toPath().resolveSibling("store.yml.corrupt-" + System.currentTimeMillis()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }

    @Override
    public synchronized Object get(String path) {
        Object o = yaml().get(path);
        if (o instanceof ConfigurationSection s) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : s.getValues(true).entrySet()) {
                if (!(e.getValue() instanceof ConfigurationSection)) {
                    out.put(e.getKey(), copy(e.getValue()));
                }
            }
            return out;
        }
        return copy(o);
    }

    @Override
    public synchronized String getString(String path, String def) {
        return yaml().getString(path, def);
    }

    @Override
    public synchronized int getInt(String path, int def) {
        return yaml().getInt(path, def);
    }

    @Override
    public synchronized long getLong(String path, long def) {
        return yaml().getLong(path, def);
    }

    @Override
    public synchronized double getDouble(String path, double def) {
        return yaml().getDouble(path, def);
    }

    @Override
    public synchronized boolean getBoolean(String path, boolean def) {
        return yaml().getBoolean(path, def);
    }

    @Override
    public synchronized List<String> getStringList(String path) {
        return new ArrayList<>(yaml().getStringList(path));
    }

    @Override
    public synchronized boolean contains(String path) {
        return yaml().contains(path);
    }

    @Override
    public synchronized Set<String> keys(String path) {
        YamlConfiguration y = yaml();
        if (path == null || path.isEmpty()) {
            return new LinkedHashSet<>(y.getKeys(false));
        }
        ConfigurationSection s = y.getConfigurationSection(path);
        return s == null ? new LinkedHashSet<>() : new LinkedHashSet<>(s.getKeys(false));
    }

    @Override
    public void set(String path, Object value) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("store path must not be empty");
        }
        Object v = sanitize(value, 0); // outside the lock: may be big
        synchronized (this) {
            YamlConfiguration y = yaml();
            if (v instanceof Map<?, ?> m) {
                // a section, like after a restart (dotted reads, keys(...) work the same before and after)
                y.set(path, null);
                y.createSection(path, m);
            } else {
                y.set(path, v);
            }
            dirty = true;
        }
    }

    /** Only YAML-safe values (or Bukkit serializable objects); converts the usual suspects, rejects the rest. */
    static Object sanitize(Object v, int depth) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long
                || v instanceof Double || v instanceof Float || v instanceof Short || v instanceof Byte) {
            return v;
        }
        if (depth > 32) {
            throw new IllegalArgumentException("value nested too deeply");
        }
        if (v.getClass().getClassLoader() instanceof ModuleClassLoader) {
            throw new IllegalArgumentException("cannot store an instance of module class " + v.getClass().getName()
                    + ": store Strings, numbers, booleans, lists, maps or Bukkit objects (ItemStack, Location...)");
        }
        if (v instanceof Number n) {
            if (n instanceof BigInteger bi) {
                return bi.bitLength() < 64 ? (Object) bi.longValue() : bi.toString();
            }
            if (n instanceof BigDecimal bd) {
                return bd.doubleValue();
            }
            double d = n.doubleValue();
            long l = n.longValue();
            return d == (double) l ? (Object) l : (Object) d; // AtomicInteger, AtomicLong, LongAdder...
        }
        if (v instanceof Character c) {
            return String.valueOf(c);
        }
        if (v instanceof UUID u) {
            return u.toString();
        }
        if (v instanceof Enum<?> e) {
            return e.name();
        }
        if (v instanceof CharSequence cs) {
            return cs.toString();
        }
        if (v instanceof java.time.temporal.TemporalAccessor || v instanceof java.time.Duration
                || v instanceof java.time.ZoneId || v instanceof java.time.Period) {
            return v.toString();
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant().toString();
        }
        if (v instanceof Optional<?> o) {
            return sanitize(o.orElse(null), depth + 1);
        }
        if (v instanceof ConfigurationSerializable) {
            return v; // ItemStack, Location, Vector...: Bukkit knows how to save and load them
        }
        if (v instanceof ConfigurationSection s) {
            return sanitize(s.getValues(false), depth + 1);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), sanitize(e.getValue(), depth + 1));
            }
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object o : c) {
                out.add(sanitize(o, depth + 1));
            }
            return out;
        }
        if (v.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(v);
            List<Object> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(sanitize(java.lang.reflect.Array.get(v, i), depth + 1));
            }
            return out;
        }
        throw new IllegalArgumentException("cannot store a " + v.getClass().getName() + " in ctx.store(): use Strings, "
                + "numbers, booleans, lists, maps or Bukkit objects (ItemStack, Location...); e.g. store "
                + "an Instant as instant.toString() or a time as System.currentTimeMillis()");
    }

    /** Deep copy of lists / maps (store values must never be mutated behind the store's back). */
    private static Object copy(Object o) {
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object x : l) {
                out.add(copy(x));
            }
            return out;
        }
        if (o instanceof Map<?, ?> m) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(e.getKey(), copy(e.getValue()));
            }
            return out;
        }
        return o;
    }

    /** Copy of the whole tree (sections become maps), taken under the lock; serialized outside of it. */
    private static Map<String, Object> tree(ConfigurationSection s) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : s.getKeys(false)) {
            Object v = s.get(k);
            out.put(k, v instanceof ConfigurationSection c ? tree(c) : copy(v));
        }
        return out;
    }

    private static String dump(Map<String, Object> tree) {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Object> e : tree.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> m) {
                y.createSection(e.getKey(), m);
            } else {
                y.set(e.getKey(), e.getValue());
            }
        }
        return y.saveToString();
    }

    /** {@code ctx.store().save()}: written soon on the io thread (never blocks the main thread). */
    @Override
    public void save() {
        synchronized (this) {
            if (yaml == null) {
                return;
            }
            dirty = true;
        }
        saveAsync();
    }

    /** Save on the io thread if dirty (unload, reload). */
    public void saveAsync() {
        try {
            io.execute(this::saveIfDirty);
        } catch (RejectedExecutionException | NullPointerException e) {
            saveIfDirty();
        }
    }

    /** Save now (calling thread) if dirty. */
    public void saveIfDirty() {
        synchronized (saveLock) {
            Map<String, Object> snapshot;
            synchronized (this) {
                if (!dirty || yaml == null) {
                    return;
                }
                snapshot = tree(yaml);
                dirty = false;
            }
            try {
                String text = dump(snapshot);
                if (text.length() > WARN_SIZE && !warnedSize) {
                    warnedSize = true;
                    log.log(Level.WARNING, "store.yml is " + text.length() / 1024 + " KB: keep the store small "
                            + "(it is loaded and saved as a whole)");
                }
                write(text);
            } catch (Throwable t) {
                synchronized (this) {
                    dirty = true;
                }
                log.log(Level.WARNING, "could not save " + file + ": " + t);
            }
        }
    }

    private void write(String text) throws IOException {
        Path p = file.toPath();
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling("store.yml.tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

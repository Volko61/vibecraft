package dev.vibecraft.core.ctx;

import java.io.File;
import java.io.IOException;
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
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.vibecraft.api.ModuleStore;
import dev.vibecraft.core.load.ModuleClassLoader;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Persistent YAML store of one module ({@code data/<id>/store.yml}). One instance per module,
 * shared by all its generations. Thread-safe.
 */
public final class ModuleStoreImpl implements ModuleStore {

    private final File file;
    private final Logger log;
    private YamlConfiguration yaml;
    private boolean dirty;

    public ModuleStoreImpl(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    private YamlConfiguration yaml() {
        if (yaml == null) {
            YamlConfiguration y = new YamlConfiguration();
            if (file.isFile()) {
                try {
                    y.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
                } catch (Exception e) {
                    log.log(Level.WARNING, "store.yml is corrupt, starting empty (backup kept): " + e.getMessage());
                    try {
                        Files.copy(file.toPath(), file.toPath().resolveSibling("store.yml.corrupt-"
                                + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ignored) {
                        // best effort
                    }
                    y = new YamlConfiguration();
                }
            }
            yaml = y;
        }
        return yaml;
    }

    @Override
    public synchronized Object get(String path) {
        Object o = yaml().get(path);
        return o instanceof ConfigurationSection s ? s.getValues(true) : o;
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
    public synchronized void set(String path, Object value) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("store path must not be empty");
        }
        yaml().set(path, sanitize(value, 0));
        dirty = true;
    }

    /** Reject module-class instances (they would leak old class loaders and cannot be saved). */
    private static Object sanitize(Object v, int depth) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (depth > 32) {
            throw new IllegalArgumentException("value nested too deeply");
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
        if (v.getClass().getClassLoader() instanceof ModuleClassLoader) {
            throw new IllegalArgumentException("cannot store an instance of module class " + v.getClass().getName()
                    + ": store Strings, numbers, booleans, lists, maps or Bukkit objects (ItemStack, Location...)");
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
        if (v.getClass().isArray() && !v.getClass().getComponentType().isPrimitive()) {
            List<Object> out = new ArrayList<>();
            for (Object o : (Object[]) v) {
                out.add(sanitize(o, depth + 1));
            }
            return out;
        }
        return v;
    }

    @Override
    public synchronized void save() {
        if (yaml == null) {
            return;
        }
        dirty = false;
        write(yaml.saveToString());
    }

    public synchronized void saveIfDirty() {
        if (!dirty || yaml == null) {
            return;
        }
        dirty = false;
        write(yaml.saveToString());
    }

    private void write(String text) {
        try {
            Path p = file.toPath();
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling("store.yml.tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "could not save " + file + ": " + e);
            dirty = true;
        }
    }
}

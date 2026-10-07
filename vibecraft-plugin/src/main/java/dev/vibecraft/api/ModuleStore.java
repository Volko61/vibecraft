package dev.vibecraft.api;

import java.util.List;
import java.util.Set;

/**
 * Persistent YAML key/value store of a module ({@code workspace/data/<id>/store.yml}).
 * Survives reloads and server restarts. Saved automatically on unload and periodically when dirty.
 *
 * <p>Paths use dots for nesting ({@code "players.Steve.score"}). Values must be YAML-friendly:
 * String, numbers, booleans, List, Map, or Bukkit ConfigurationSerializable (ItemStack, Location...).
 * Never store instances of module classes.
 */
public interface ModuleStore {

    Object get(String path);

    String getString(String path, String def);

    int getInt(String path, int def);

    long getLong(String path, long def);

    double getDouble(String path, double def);

    boolean getBoolean(String path, boolean def);

    List<String> getStringList(String path);

    boolean contains(String path);

    /** Direct child keys of {@code path} ({@code ""} for the root). */
    Set<String> keys(String path);

    /** Set a value ({@code null} removes it). */
    void set(String path, Object value);

    /** Save to disk now (normally not needed). */
    void save();
}

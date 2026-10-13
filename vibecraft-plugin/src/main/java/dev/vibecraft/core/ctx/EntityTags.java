package dev.vibecraft.core.ctx;

import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Persistent tag put on every entity passed to {@code ctx.entity(...)}: {@code <module id>|<boot id>|<load id>}.
 * Entities that come back with a chunk (or after a crash / restart) while their generation is gone
 * are deleted by the engine when their chunk loads.
 */
public final class EntityTags {

    private EntityTags() {
    }

    /** Random id of this server run (load ids restart at 1 after a restart). */
    public static final String BOOT = UUID.randomUUID().toString().substring(0, 8);

    private static volatile NamespacedKey key;

    public static NamespacedKey key(Plugin plugin) {
        NamespacedKey k = key;
        if (k == null) {
            k = new NamespacedKey(plugin, "module-entity");
            key = k;
        }
        return k;
    }

    public static String value(String moduleId, int loadId) {
        return moduleId + "|" + BOOT + "|" + loadId;
    }
}

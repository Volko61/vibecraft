package dev.vibecraft.pack;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dev.vibecraft.pack.mesh.MeshCompiler;

/**
 * Manifests of the 3D objects compiled into the current resource pack (id -> parts, pivots, scales),
 * read by {@code dev.vibecraft.api.Models} to place them with display entities. Updated after each build.
 */
public final class ModelRegistry {

    private static volatile Map<String, MeshCompiler.ObjectInfo> objects = Map.of();

    private ModelRegistry() {
    }

    static void update(List<MeshCompiler.ObjectInfo> list) {
        Map<String, MeshCompiler.ObjectInfo> m = new TreeMap<>();
        for (MeshCompiler.ObjectInfo i : list) {
            m.putIfAbsent(i.id(), i);
        }
        objects = Map.copyOf(m);
    }

    public static MeshCompiler.ObjectInfo get(String id) {
        return objects.get(id);
    }

    public static Collection<MeshCompiler.ObjectInfo> all() {
        return new TreeMap<>(objects).values();
    }
}

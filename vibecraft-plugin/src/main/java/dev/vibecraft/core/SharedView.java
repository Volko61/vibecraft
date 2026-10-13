package dev.vibecraft.core;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * What {@code ctx.shared()} returns: the global shared map seen through one module, which records the
 * keys this module writes. Values survive reloads of the module (documented contract) but the keys a
 * module wrote are dropped when the module itself is removed (its folder deleted), so a removed
 * module cannot keep megabytes in memory until the next restart. {@code clear()} only clears the
 * module's own keys.
 */
final class SharedView implements Map<String, Object> {

    private final ConcurrentHashMap<String, Object> map;
    /** key -> id of the module that wrote it last (shared by every view). */
    private final ConcurrentHashMap<String, String> owners;
    private final String moduleId;

    SharedView(ConcurrentHashMap<String, Object> map, ConcurrentHashMap<String, String> owners, String moduleId) {
        this.map = map;
        this.owners = owners;
        this.moduleId = moduleId;
    }

    private void own(Object key) {
        if (key instanceof String k && map.containsKey(k)) {
            owners.put(k, moduleId);
        }
    }

    /** Drop every key whose last writer is {@code moduleId}. */
    static void dropOwnedBy(ConcurrentHashMap<String, Object> map, ConcurrentHashMap<String, String> owners, String moduleId) {
        for (Map.Entry<String, String> e : owners.entrySet()) {
            if (moduleId.equals(e.getValue()) && owners.remove(e.getKey(), moduleId)) {
                map.remove(e.getKey());
            }
        }
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return key != null && map.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return value != null && map.containsValue(value);
    }

    @Override
    public Object get(Object key) {
        return key == null ? null : map.get(key);
    }

    @Override
    public Object getOrDefault(Object key, Object def) {
        return key == null ? def : map.getOrDefault(key, def);
    }

    @Override
    public Object put(String key, Object value) {
        Object old = map.put(key, value);
        own(key);
        return old;
    }

    @Override
    public Object remove(Object key) {
        if (key == null) {
            return null;
        }
        Object old = map.remove(key);
        owners.remove(key);
        return old;
    }

    @Override
    public void putAll(Map<? extends String, ?> m) {
        for (Map.Entry<? extends String, ?> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    /** Clears only the keys this module wrote (other modules' data is not ours to wipe). */
    @Override
    public void clear() {
        dropOwnedBy(map, owners, moduleId);
    }

    @Override
    public Set<String> keySet() {
        return map.keySet();
    }

    @Override
    public Collection<Object> values() {
        return map.values();
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return map.entrySet();
    }

    @Override
    public void forEach(BiConsumer<? super String, ? super Object> action) {
        map.forEach(action);
    }

    @Override
    public Object putIfAbsent(String key, Object value) {
        Object old = map.putIfAbsent(key, value);
        if (old == null) {
            own(key);
        }
        return old;
    }

    @Override
    public boolean remove(Object key, Object value) {
        boolean r = key != null && value != null && map.remove(key, value);
        if (r) {
            owners.remove(key);
        }
        return r;
    }

    @Override
    public boolean replace(String key, Object oldValue, Object newValue) {
        boolean r = map.replace(key, oldValue, newValue);
        if (r) {
            own(key);
        }
        return r;
    }

    @Override
    public Object replace(String key, Object value) {
        Object old = map.replace(key, value);
        if (old != null) {
            own(key);
        }
        return old;
    }

    @Override
    public Object computeIfAbsent(String key, Function<? super String, ?> fn) {
        boolean had = map.containsKey(key);
        Object v = map.computeIfAbsent(key, fn);
        if (!had) {
            own(key);
        }
        return v;
    }

    @Override
    public Object computeIfPresent(String key, BiFunction<? super String, ? super Object, ?> fn) {
        Object v = map.computeIfPresent(key, fn);
        own(key);
        return v;
    }

    @Override
    public Object compute(String key, BiFunction<? super String, ? super Object, ?> fn) {
        Object v = map.compute(key, fn);
        own(key);
        return v;
    }

    @Override
    public Object merge(String key, Object value, BiFunction<? super Object, ? super Object, ?> fn) {
        Object v = map.merge(key, value, fn);
        own(key);
        return v;
    }

    @Override
    public boolean equals(Object o) {
        return o == this || map.equals(o);
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }

    @Override
    public String toString() {
        return map.toString();
    }
}

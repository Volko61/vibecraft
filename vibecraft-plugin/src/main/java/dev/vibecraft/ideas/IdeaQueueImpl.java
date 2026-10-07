package dev.vibecraft.ideas;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Audience idea queue persisted to {@code plugins/VibeCraft/ideas.yml}.
 *
 * <p>Thread-safe: all state is guarded by {@code this}. Saves are written atomically (temp file +
 * move) on a background thread, coalesced, and flushed synchronously when the plugin is disabled,
 * so the main thread never waits on disk I/O.
 */
public final class IdeaQueueImpl implements IdeaQueue {

    /** Oldest ideas beyond this are dropped (handled ones first). */
    private static final int MAX_IDEAS = 1000;
    private static final int MAX_TEXT = 500;
    private static final int MAX_AUTHOR = 32;

    private final JavaPlugin plugin;
    private final File file;
    /** Insertion order = id order (oldest first). */
    private final LinkedHashMap<Integer, Idea> ideas = new LinkedHashMap<>();
    private int nextId = 1;
    private long version;

    private final Object saveLock = new Object();
    private long savedVersion = -1;
    private final AtomicBoolean saveQueued = new AtomicBoolean();
    private final ExecutorService saver = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VibeCraft-ideas-save");
        t.setDaemon(true);
        return t;
    });

    public IdeaQueueImpl(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "ideas.yml");
        load();
        try {
            plugin.getServer().getPluginManager().registerEvents(new Listener() {
                @EventHandler(priority = EventPriority.MONITOR)
                public void onDisable(PluginDisableEvent event) {
                    if (event.getPlugin() == plugin) {
                        close();
                    }
                }
            }, plugin);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("ideas: cannot register shutdown flush: " + e);
        }
    }

    // ------------------------------------------------------------------ IdeaQueue

    @Override
    public Idea add(String author, String text) {
        String t = clean(text, MAX_TEXT);
        if (t.isEmpty()) {
            throw new IllegalArgumentException("empty idea");
        }
        String a = clean(author, MAX_AUTHOR);
        Idea idea;
        synchronized (this) {
            idea = new Idea(nextId++, a.isEmpty() ? "anonyme" : a, t, System.currentTimeMillis(), "NEW", null);
            ideas.put(idea.id(), idea);
            trim();
            version++;
        }
        requestSave();
        return idea;
    }

    @Override
    public synchronized List<Idea> list() {
        List<Idea> out = new ArrayList<>(ideas.values());
        java.util.Collections.reverse(out);
        return out;
    }

    @Override
    public synchronized Optional<Idea> get(int id) {
        return Optional.ofNullable(ideas.get(id));
    }

    @Override
    public Optional<Idea> accept(int id, String moduleId) {
        return update(id, "ACCEPTED", moduleId);
    }

    @Override
    public Optional<Idea> dismiss(int id) {
        return update(id, "DISMISSED", null);
    }

    private Optional<Idea> update(int id, String status, String moduleId) {
        Idea updated;
        synchronized (this) {
            Idea old = ideas.get(id);
            if (old == null) {
                return Optional.empty();
            }
            String module = moduleId != null ? moduleId : old.moduleId();
            updated = new Idea(old.id(), old.author(), old.text(), old.at(), status, module);
            ideas.put(id, updated);
            version++;
        }
        requestSave();
        return Optional.of(updated);
    }

    private void trim() {
        if (ideas.size() <= MAX_IDEAS) {
            return;
        }
        // drop handled ideas first, oldest first; then the oldest new ones
        var it = ideas.values().iterator();
        while (ideas.size() > MAX_IDEAS && it.hasNext()) {
            if (!"NEW".equals(it.next().status())) {
                it.remove();
            }
        }
        it = ideas.values().iterator();
        while (ideas.size() > MAX_IDEAS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").replace('\t', ' ').strip();
        return one.length() > max ? one.substring(0, max) : one;
    }

    // ------------------------------------------------------------------ persistence

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        try {
            yml.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.WARNING, "ideas: cannot read " + file + ", starting empty (file kept as ideas.yml.broken)", e);
            try {
                Files.copy(file.toPath(), file.toPath().resolveSibling("ideas.yml.broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // best effort
            }
            return;
        }
        int maxId = 0;
        ConfigurationSection sec = yml.getConfigurationSection("ideas");
        List<Idea> loaded = new ArrayList<>();
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                int id;
                try {
                    id = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    continue;
                }
                String status = s.getString("status", "NEW");
                if (!status.equals("NEW") && !status.equals("ACCEPTED") && !status.equals("DISMISSED")) {
                    status = "NEW";
                }
                loaded.add(new Idea(id, s.getString("author", "anonyme"), s.getString("text", ""), s.getLong("at", 0L),
                        status, s.getString("module", null)));
                maxId = Math.max(maxId, id);
            }
        }
        loaded.sort((x, y) -> Integer.compare(x.id(), y.id()));
        synchronized (this) {
            for (Idea i : loaded) {
                ideas.put(i.id(), i);
            }
            nextId = Math.max(maxId + 1, yml.getInt("next-id", 1));
            version = 0;
            savedVersion = 0;
        }
    }

    private void requestSave() {
        if (saveQueued.compareAndSet(false, true)) {
            try {
                saver.execute(() -> {
                    saveQueued.set(false);
                    saveNow();
                });
            } catch (RuntimeException e) {
                // executor closed (shutdown): write synchronously
                saveQueued.set(false);
                saveNow();
            }
        }
    }

    /** Write the current state if it changed since the last save. */
    private void saveNow() {
        synchronized (saveLock) {
            String data;
            long v;
            synchronized (this) {
                v = version;
                if (v == savedVersion) {
                    return;
                }
                YamlConfiguration yml = new YamlConfiguration();
                yml.set("next-id", nextId);
                for (Idea i : ideas.values()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("author", i.author());
                    m.put("text", i.text());
                    m.put("at", i.at());
                    m.put("status", i.status());
                    m.put("module", i.moduleId());
                    yml.createSection("ideas." + i.id(), m);
                }
                data = yml.saveToString();
            }
            try {
                Files.createDirectories(file.getParentFile().toPath());
                Path tmp = file.toPath().resolveSibling("ideas.yml.tmp");
                Files.writeString(tmp, data, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                savedVersion = v;
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "ideas: cannot save " + file, e);
            }
        }
    }

    /** Flush pending changes and stop the background saver. Called when the plugin is disabled. */
    private void close() {
        saver.shutdown();
        try {
            saver.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        saveNow();
    }
}

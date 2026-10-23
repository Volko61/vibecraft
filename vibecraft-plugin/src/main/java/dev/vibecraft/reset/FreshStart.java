package dev.vibecraft.reset;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

import dev.vibecraft.core.EngineConfig;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code /reset}: back to a blank server (new map with a new seed, no module, no AI conversation, no idea).
 *
 * <p>The main world cannot be unloaded while the server runs, so the reset is done in two steps:
 * <ol>
 *   <li>{@link #schedule}: the new seed is written to {@code server.properties} and a marker file
 *       ({@code plugins/VibeCraft/reset.pending}) records the world folders to delete; the caller then stops the
 *       server ({@code scripts/start-server.ps1} and {@code proxy/run-main-forever.ps1} start it again).</li>
 *   <li>{@link #applyPending}, from {@code onLoad} on the next start (before any world, module or store is
 *       loaded): the marker is consumed, then the world folders, {@code workspace/modules/*},
 *       {@code workspace/data/*}, the AI conversations / sessions and the idea queue are deleted.</li>
 * </ol>
 * Kept: config.yml, secrets.yml, ops / whitelist / bans and the rest of the server configuration.
 */
public final class FreshStart {

    public static final String MARKER = "reset.pending";

    /** Top-level folders of the server directory that are never a world. */
    private static final Set<String> NOT_WORLDS = Set.of("plugins", "config", "libraries", "versions", "logs", "cache",
            "crash-reports", "debug", "bundler");

    private FreshStart() {
    }

    public static File marker(JavaPlugin plugin) {
        return new File(plugin.getDataFolder(), MARKER);
    }

    /**
     * Writes the new seed to server.properties and the marker read by {@link #applyPending} on the next start.
     *
     * @param worlds names of the loaded worlds (their folders in the world container are deleted)
     */
    public static void schedule(JavaPlugin plugin, String seed, Collection<String> worlds, String requestedBy) throws IOException {
        setProperty(new File("server.properties"), "level-seed", seed);
        Properties p = new Properties();
        p.setProperty("seed", seed);
        p.setProperty("worlds", String.join(",", worlds));
        p.setProperty("requested-by", requestedBy == null ? "" : requestedBy);
        p.setProperty("requested-at", String.valueOf(System.currentTimeMillis()));
        File marker = marker(plugin);
        Files.createDirectories(marker.getAbsoluteFile().getParentFile().toPath());
        Path tmp = marker.toPath().resolveSibling(MARKER + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "VibeCraft /reset: applied (then deleted) on the next server start");
        }
        move(tmp, marker.toPath());
    }

    /** Cancels a reset that was scheduled but not applied yet (the new seed stays in server.properties). */
    public static boolean cancel(JavaPlugin plugin) throws IOException {
        return Files.deleteIfExists(marker(plugin).toPath());
    }

    /** If a reset is pending, wipes everything. Called from onLoad, before worlds and modules are loaded. */
    public static void applyPending(JavaPlugin plugin) {
        Logger log = plugin.getLogger();
        File marker = marker(plugin);
        if (!marker.isFile()) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(marker.toPath())) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            log.warning("reset: unreadable " + marker + " (" + e + "), wiping the default worlds only");
        }
        // Consume the marker first: if it cannot be deleted, wiping now would wipe again at every start.
        try {
            Files.delete(marker.toPath());
        } catch (IOException e) {
            log.severe("reset: cannot delete " + marker + " (" + e + "): reset NOT applied, delete the file by hand");
            return;
        }

        log.warning("Remise a zero demandee par " + p.getProperty("requested-by", "?") + " : effacement de la map, des"
                + " modules, des conversations IA et des idees...");
        File server = new File(".").getAbsoluteFile();
        File container = plugin.getServer().getWorldContainer().getAbsoluteFile();
        EngineConfig cfg = EngineConfig.from(plugin.getConfig());
        List<File> protectedDirs = List.of(server, container, plugin.getDataFolder().getAbsoluteFile());
        List<String> failed = new ArrayList<>();

        Set<String> worlds = new LinkedHashSet<>();
        String level = levelName(new File(server, "server.properties"));
        worlds.add(level);
        worlds.add(level + "_nether");
        worlds.add(level + "_the_end");
        for (String w : p.getProperty("worlds", "").split(",")) {
            if (!w.isBlank()) {
                worlds.add(w.strip());
            }
        }
        List<String> wiped = new ArrayList<>();
        for (String w : worlds) {
            File dir = new File(container, w);
            if (!isWorldName(w) || !dir.isDirectory() || isAncestorOrSame(dir, protectedDirs)
                    || isAncestorOrSame(dir, List.of(cfg.modulesDir(), cfg.dataDir()))) {
                continue;
            }
            if (deleteTree(dir.toPath(), log)) {
                wiped.add(w);
            } else {
                failed.add(w);
            }
        }

        int modules = wipeChildren(cfg.modulesDir(), protectedDirs, log, failed);
        wipeChildren(cfg.dataDir(), protectedDirs, log, failed);
        File data = plugin.getDataFolder();
        for (String name : List.of("conversations", "ai-sessions.yml", "ideas.yml", "ideas.yml.broken")) {
            File f = new File(data, name);
            if (f.exists() && !deleteTree(f.toPath(), log)) {
                failed.add(name);
            }
        }

        log.warning("Remise a zero faite : map effacee (" + String.join(", ", wiped) + "), " + modules + " module(s)"
                + " supprime(s), seed " + p.getProperty("seed", "(server.properties)")
                + (failed.isEmpty() ? "" : ". NON effaces (voir les erreurs ci-dessus) : " + String.join(", ", failed)));
    }

    // ------------------------------------------------------------------

    /** Deletes the entries of {@code dir} (not dot-files such as .gitkeep). Returns how many were deleted. */
    private static int wipeChildren(File dir, List<File> protectedDirs, Logger log, List<String> failed) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        if (isAncestorOrSame(dir, protectedDirs) || dir.toPath().toAbsolutePath().getParent() == null) {
            log.severe("reset: " + dir + " contains the server directory, not wiped (check modules-dir / data-dir)");
            failed.add(dir.getPath());
            return 0;
        }
        int n = 0;
        for (File c : children) {
            if (c.getName().startsWith(".")) {
                continue;
            }
            if (deleteTree(c.toPath(), log)) {
                n++;
            } else {
                failed.add(c.getPath());
            }
        }
        return n;
    }

    /** Deletes a file or a folder (symbolic links are deleted, never followed). */
    private static boolean deleteTree(Path root, Logger log) {
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            log.severe("reset: cannot delete " + root + ": " + e);
            return false;
        }
    }

    /** True if {@code dir} is one of {@code dirs} or contains one of them. */
    private static boolean isAncestorOrSame(File dir, List<File> dirs) {
        Path d = canonical(dir);
        for (File f : dirs) {
            if (canonical(f).startsWith(d)) {
                return true;
            }
        }
        return false;
    }

    private static Path canonical(File f) {
        try {
            return f.getCanonicalFile().toPath();
        } catch (IOException e) {
            return f.toPath().toAbsolutePath().normalize();
        }
    }

    private static boolean isWorldName(String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..") && name.indexOf('/') < 0 && name.indexOf('\\') < 0
                && name.indexOf(':') < 0 && !NOT_WORLDS.contains(name.toLowerCase(Locale.ROOT));
    }

    private static String levelName(File properties) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(properties.toPath())) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            // missing / broken: the server uses the default
        }
        String name = p.getProperty("level-name", "world").strip();
        return name.isEmpty() ? "world" : name;
    }

    /** Sets {@code key=value} in a .properties file, keeping the other lines (and comments) as they are. */
    static void setProperty(File file, String key, String value) throws IOException {
        List<String> lines = file.isFile() ? Files.readAllLines(file.toPath(), StandardCharsets.ISO_8859_1) : new ArrayList<>();
        String line = key + "=" + escape(value);
        boolean found = false;
        List<String> out = new ArrayList<>(lines.size() + 1);
        for (String l : lines) {
            String t = l.stripLeading();
            if (!found && (t.startsWith(key + "=") || t.startsWith(key + ":") || t.startsWith(key + " ") || t.equals(key))) {
                out.add(line);
                found = true;
            } else {
                out.add(l);
            }
        }
        if (!found) {
            out.add(line);
        }
        Path tmp = file.toPath().toAbsolutePath().resolveSibling(file.getName() + ".vibe-tmp");
        Files.write(tmp, out, StandardCharsets.ISO_8859_1);
        move(tmp, file.toPath());
    }

    /** .properties value escaping (backslash, leading space, non-ASCII as \\uXXXX). */
    private static String escape(String v) {
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == ' ' && i == 0) {
                sb.append("\\ ");
            } else if (c < 0x20 || c > 0x7e) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

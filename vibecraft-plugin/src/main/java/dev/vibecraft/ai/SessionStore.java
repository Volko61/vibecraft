package dev.vibecraft.ai;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Module id -> Claude Code session id, persisted in {@code plugins/VibeCraft/ai-sessions.yml}:
 * <pre>
 * sessions:
 *   fusee: 3f0c1d2e-....
 * </pre>
 * Written atomically (temp file + move) on every change. Thread-safe. Pure JDK.
 */
public final class SessionStore {

    private static final Pattern ENTRY = Pattern.compile("^\\s+['\"]?([a-z0-9][a-z0-9_-]{0,39})['\"]?\\s*:\\s*['\"]?([A-Za-z0-9._-]+)['\"]?\\s*(#.*)?$");

    private final File file;
    private final Logger log;
    private final Map<String, String> sessions = new TreeMap<>();

    public SessionStore(File file, Logger log) {
        this.file = file;
        this.log = log;
        load();
    }

    public synchronized Optional<String> get(String moduleId) {
        return Optional.ofNullable(sessions.get(moduleId));
    }

    public synchronized void put(String moduleId, String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.equals(sessions.get(moduleId))) {
            return;
        }
        sessions.put(moduleId, sessionId);
        save();
    }

    public synchronized boolean remove(String moduleId) {
        if (sessions.remove(moduleId) != null) {
            save();
            return true;
        }
        return false;
    }

    public synchronized Map<String, String> all() {
        return Map.copyOf(sessions);
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        try {
            boolean inSessions = false;
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.stripLeading().startsWith("#")) {
                    continue;
                }
                if (!Character.isWhitespace(line.charAt(0))) {
                    inSessions = line.strip().startsWith("sessions:");
                    continue;
                }
                Matcher m = ENTRY.matcher(line);
                if (inSessions && m.matches()) {
                    sessions.put(m.group(1), m.group(2));
                }
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Cannot read " + file + ": " + e);
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        sb.append("# VibeCraft: Claude Code conversation (session id) of each module, resumed by the next AI job.\n");
        sb.append("# Managed automatically. Delete a line (or use /vibe reset <id>) to start a fresh conversation.\n");
        sb.append("sessions:\n");
        for (Map.Entry<String, String> e : sessions.entrySet()) {
            sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        try {
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null) {
                Files.createDirectories(dir.toPath());
            }
            File tmp = new File(file.getPath() + ".tmp");
            Files.writeString(tmp.toPath(), sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Cannot write " + file + ": " + e);
        }
    }
}

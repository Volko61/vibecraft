package dev.vibecraft.ext;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Secrets (API keys) for modules and AI jobs. Never logged, never in status files, the dashboard or
 * the HTTP API: only names are ever shown.
 *
 * <p>Lookup order for {@code get(name)} (first non-blank wins; names are case-insensitive and
 * {@code -} == {@code _}):
 * <ol>
 *   <li>{@code plugins/VibeCraft/secrets.yml} (re-read automatically when the file changes: no restart needed);</li>
 *   <li>{@code secrets:} section of config.yml;</li>
 *   <li>environment variable {@code VIBECRAFT_SECRET_<NAME>} (upper case, {@code -} -> {@code _});</li>
 *   <li>for {@code openrouter}: environment variable {@code OPENROUTER_API_KEY}.</li>
 * </ol>
 */
public final class SecretStore {

    private static volatile SecretStore instance;

    private final File file;
    private final Map<String, String> fromConfig;
    private final Map<String, String> env;
    private final Logger log;

    private volatile Map<String, String> fromFile = Map.of();
    private volatile long fileStamp = -2;
    private volatile long lastCheck;
    private volatile List<String> redactValues = List.of();

    SecretStore(File file, ConfigurationSection configSecrets, Map<String, String> env, Logger log) {
        this.file = file;
        this.env = env;
        this.log = log;
        Map<String, String> c = new HashMap<>();
        if (configSecrets != null) {
            for (String k : configSecrets.getKeys(false)) {
                Object v = configSecrets.get(k);
                if (v != null && !(v instanceof ConfigurationSection) && !String.valueOf(v).isBlank()) {
                    c.put(norm(k), String.valueOf(v).strip());
                }
            }
        }
        this.fromConfig = Map.copyOf(c);
        ensureFile();
        refresh(true);
    }

    /** Installs the process-wide store (called once from onEnable). */
    public static SecretStore init(File dataFolder, ConfigurationSection configSecrets, Logger log) {
        SecretStore s = new SecretStore(new File(dataFolder, "secrets.yml"), configSecrets, System.getenv(), log);
        instance = s;
        return s;
    }

    /** The installed store, or null before init. */
    public static SecretStore get() {
        return instance;
    }

    public static String norm(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /** Secret value or null. Never log the result. */
    public String secret(String name) {
        String n = norm(name);
        if (n.isEmpty()) {
            return null;
        }
        refresh(false);
        String v = fromFile.get(n);
        if (blank(v)) {
            v = fromConfig.get(n);
        }
        if (blank(v)) {
            v = env.get("VIBECRAFT_SECRET_" + n.toUpperCase(Locale.ROOT));
        }
        if (blank(v) && n.equals("openrouter")) {
            v = env.get("OPENROUTER_API_KEY");
        }
        return blank(v) ? null : v.strip();
    }

    /** Names of the configured secrets (values never exposed). */
    public Set<String> names() {
        refresh(false);
        Set<String> out = new TreeSet<>();
        fromFile.forEach((k, v) -> { if (!blank(v)) out.add(k); });
        fromConfig.forEach((k, v) -> { if (!blank(v)) out.add(k); });
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().startsWith("VIBECRAFT_SECRET_") && !blank(e.getValue())) {
                out.add(norm(e.getKey().substring("VIBECRAFT_SECRET_".length())));
            }
        }
        if (!blank(env.get("OPENROUTER_API_KEY"))) {
            out.add("openrouter");
        }
        return out;
    }

    /** Replaces every secret value (>= 6 chars) occurring in {@code text} by {@code ***}. */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (String v : redactValues) {
            if (out.contains(v)) {
                out = out.replace(v, "***");
            }
        }
        return out;
    }

    /** Static convenience: redact with the installed store (identity if none). */
    public static String scrub(String text) {
        SecretStore s = instance;
        return s == null ? text : s.redact(text);
    }

    private void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastCheck < 2000) {
            return;
        }
        lastCheck = now;
        long stamp = file.isFile() ? file.lastModified() ^ (file.length() << 20) : -1;
        if (!force && stamp == fileStamp) {
            return;
        }
        synchronized (this) {
            fileStamp = stamp;
            Map<String, String> m = new HashMap<>();
            if (stamp != -1) {
                try {
                    YamlConfiguration y = new YamlConfiguration();
                    y.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
                    for (String k : y.getKeys(false)) {
                        Object v = y.get(k);
                        if (v != null && !(v instanceof ConfigurationSection) && !String.valueOf(v).isBlank()) {
                            m.put(norm(k), String.valueOf(v).strip());
                        }
                    }
                } catch (Exception e) {
                    // the message of a YAML error may quote the file: never log it
                    log.warning("secrets.yml could not be parsed (YAML syntax error); its secrets are ignored until it is fixed");
                }
            }
            fromFile = Map.copyOf(m);
            List<String> values = new ArrayList<>();
            for (Map<String, String> src : List.of(fromFile, fromConfig)) {
                for (String v : src.values()) {
                    if (v.length() >= 6) {
                        values.add(v);
                    }
                }
            }
            for (Map.Entry<String, String> e : env.entrySet()) {
                String k = e.getKey();
                if ((k.startsWith("VIBECRAFT_SECRET_") || k.equals("OPENROUTER_API_KEY")) && e.getValue() != null
                        && e.getValue().strip().length() >= 6) {
                    values.add(e.getValue().strip());
                }
            }
            values.sort(Comparator.comparingInt(String::length).reversed());
            redactValues = List.copyOf(values);
        }
    }

    private void ensureFile() {
        if (file.exists()) {
            return;
        }
        try {
            File dir = file.getParentFile();
            if (dir != null) {
                dir.mkdirs();
            }
            Files.writeString(file.toPath(), """
                    # VibeCraft secrets (API keys). This file is NEVER shown in logs, status files, the dashboard or the API.
                    # Changes are picked up automatically (no restart needed). Keep it out of git and screenshots.
                    #
                    # Modules read them with dev.vibecraft.api.Secrets.get("<name>"); AI coding jobs get
                    # OPENROUTER_API_KEY in their environment when "openrouter" is set.
                    # Environment variables also work: OPENROUTER_API_KEY, VIBECRAFT_SECRET_<NAME>.
                    #
                    # openrouter: "sk-or-v1-..."
                    """, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("cannot create " + file + ": " + e.getMessage());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

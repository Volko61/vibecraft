package dev.vibecraft.ai;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.vibecraft.core.ModuleManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bukkit adapter of {@link AiJobEngine}: reads {@code ai.*} / {@code api.*} from config.yml, stores
 * conversations in {@code plugins/VibeCraft/ai-sessions.yml}, scaffolds missing modules through the
 * {@link ModuleManager}. All the logic (queue, processes, stream parsing) lives in Bukkit-free classes.
 *
 * <p>Optional keys read in addition to the documented ones (defaults in brackets):
 * {@code ai.effort} [""] (low/medium/high: faster or smarter jobs), {@code ai.max-budget-usd} [0 = none],
 * {@code ai.restrict-tools} [true] (only the allowed tools exist for the agent), {@code ai.check-auth} [true]
 * (AI reported unavailable while {@code claude auth status} says "not logged in"), {@code ai.extra-args} [],
 * {@code ai.env} [{}] (extra environment variables for the CLI).
 */
public final class ClaudeJobService implements AiJobService {

    private final AiJobEngine engine;

    public ClaudeJobService(JavaPlugin plugin, ModuleManager modules) {
        FileConfiguration c = plugin.getConfig();
        File modulesDir = modulesDir(c, modules);
        File workspace = modulesDir.getParentFile() != null ? modulesDir.getParentFile() : modulesDir;

        Map<String, String> env = new HashMap<>();
        ConfigurationSection envSec = c.getConfigurationSection("ai.env");
        if (envSec != null) {
            for (String k : envSec.getKeys(false)) {
                env.put(k, String.valueOf(envSec.get(k)));
            }
        }
        List<String> allowed = c.getStringList("ai.allowed-tools");
        int timeout = c.getInt("ai.timeout-seconds", 900);
        AiJobEngine.Settings s = new AiJobEngine.Settings(
                c.getBoolean("ai.enabled", true),
                c.getString("ai.claude-path", "auto"),
                null,
                c.getString("ai.model", ""),
                c.getString("ai.permission-mode", "acceptEdits"),
                allowed.isEmpty() ? List.of("Read", "Write", "Edit", "MultiEdit", "Glob", "Grep", "Bash(curl:*)") : allowed,
                c.getBoolean("ai.restrict-tools", true),
                c.getInt("ai.max-concurrent-jobs", 4),
                Duration.ofSeconds(timeout > 0 ? timeout : 900),
                c.getBoolean("ai.resume-sessions", true),
                workspace,
                modulesDir,
                "http://127.0.0.1:" + c.getInt("api.port", 8765),
                c.getString("api.token", ""),
                c.getString("ai.effort", ""),
                c.getDouble("ai.max-budget-usd", 0),
                c.getBoolean("ai.check-auth", true),
                c.getStringList("ai.extra-args"),
                env);
        SessionStore sessions = new SessionStore(new File(plugin.getDataFolder(), "ai-sessions.yml"), plugin.getLogger());
        PromptTemplate template = PromptTemplate.load(ClaudeJobService.class.getClassLoader());
        engine = new AiJobEngine(s, plugin.getLogger(), sessions, template,
                (id, description) -> ensureModule(modules, modulesDir, id, description));
    }

    private static File modulesDir(FileConfiguration c, ModuleManager modules) {
        File dir = null;
        try {
            dir = modules.modulesDir();
        } catch (RuntimeException ignored) {
            // engine not ready / stub: fall back to the configuration
        }
        if (dir == null) {
            dir = new File(c.getString("modules-dir", "../workspace/modules"));
        }
        try {
            return dir.getCanonicalFile();
        } catch (IOException e) {
            return dir.getAbsoluteFile();
        }
    }

    private static void ensureModule(ModuleManager modules, File modulesDir, String id, String description) throws IOException {
        File dir = new File(modulesDir, id);
        if (dir.isDirectory()) {
            return;
        }
        try {
            modules.create(id, description);
        } catch (UnsupportedOperationException ignored) {
            // engine without scaffolding: the AI creates the files itself
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
    }

    /** The underlying engine (CLI info, auth state). */
    public AiJobEngine engine() {
        return engine;
    }

    @Override
    public boolean available() {
        return engine.available();
    }

    @Override
    public String unavailableReason() {
        return engine.unavailableReason();
    }

    @Override
    public AiJob start(String moduleId, String prompt, String requestedBy) {
        return engine.start(moduleId, prompt, requestedBy);
    }

    @Override
    public boolean stop(String moduleId) {
        return engine.stop(moduleId);
    }

    @Override
    public List<AiJob> jobs() {
        return engine.jobs();
    }

    @Override
    public Optional<AiJob> job(String moduleId) {
        return engine.job(moduleId);
    }

    @Override
    public void resetConversation(String moduleId) {
        engine.resetConversation(moduleId);
    }

    @Override
    public void addListener(AiJobListener listener) {
        engine.addListener(listener);
    }

    @Override
    public void shutdown() {
        engine.shutdown();
    }
}

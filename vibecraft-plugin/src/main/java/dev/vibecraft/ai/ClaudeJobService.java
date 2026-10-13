package dev.vibecraft.ai;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vibecraft.core.ModuleManager;
import dev.vibecraft.ext.SecretStore;
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
 * {@code ai.env} [{}] (extra environment variables for the CLI), {@code ai.mcp-config} [""] (Claude Code MCP
 * JSON config file passed with {@code --mcp-config}; every server in it is allowed as {@code mcp__<server>}).
 * The OpenRouter key of {@code secrets.yml} ("openrouter") is given to jobs as {@code OPENROUTER_API_KEY}.
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
        List<String> allowed = new ArrayList<>(c.getStringList("ai.allowed-tools"));
        if (allowed.isEmpty()) {
            allowed.addAll(List.of("Read", "Write", "Edit", "MultiEdit", "Glob", "Grep", "Bash(curl:*)"));
        }
        List<String> extraArgs = new ArrayList<>(c.getStringList("ai.extra-args"));
        addMcp(plugin, c.getString("ai.mcp-config", ""), allowed, extraArgs);
        AiJobEngine.setDynamicEnv(() -> {
            SecretStore st = SecretStore.get();
            String key = st == null ? null : st.secret("openrouter");
            return key == null ? Map.of() : Map.of("OPENROUTER_API_KEY", key);
        });
        AiJobEngine.setRedactor(SecretStore::scrub);
        int timeout = c.getInt("ai.timeout-seconds", 900);
        AiJobEngine.Settings s = new AiJobEngine.Settings(
                c.getBoolean("ai.enabled", true),
                c.getString("ai.claude-path", "auto"),
                null,
                c.getString("ai.model", ""),
                c.getString("ai.permission-mode", "acceptEdits"),
                List.copyOf(allowed),
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
                extraArgs,
                env);
        SessionStore sessions = new SessionStore(new File(plugin.getDataFolder(), "ai-sessions.yml"), plugin.getLogger());
        PromptTemplate template = PromptTemplate.load(ClaudeJobService.class.getClassLoader());
        engine = new AiJobEngine(s, plugin.getLogger(), sessions, template,
                (id, description) -> ensureModule(modules, modulesDir, id, description));
    }

    /**
     * {@code ai.mcp-config}: path (absolute, or relative to the server directory / plugins/VibeCraft/) of a
     * Claude Code MCP config ({@code {"mcpServers": {"name": {...}}}}). Adds {@code --mcp-config <abs>} and
     * allows every server's tools ({@code mcp__<name>}).
     */
    private static void addMcp(JavaPlugin plugin, String path, List<String> allowed, List<String> extraArgs) {
        if (path == null || path.isBlank()) {
            return;
        }
        File f = new File(path.strip());
        if (!f.isAbsolute() && !f.isFile()) {
            File alt = new File(plugin.getDataFolder(), path.strip());
            if (alt.isFile()) {
                f = alt;
            }
        }
        if (!f.isFile()) {
            plugin.getLogger().warning("[AI] ai.mcp-config: file not found: " + f.getAbsolutePath() + " (MCP disabled for AI jobs)");
            return;
        }
        List<String> names = new ArrayList<>();
        try {
            JsonElement el = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8));
            JsonObject servers = el.isJsonObject() && el.getAsJsonObject().has("mcpServers")
                    && el.getAsJsonObject().get("mcpServers").isJsonObject()
                    ? el.getAsJsonObject().getAsJsonObject("mcpServers") : null;
            if (servers == null) {
                plugin.getLogger().warning("[AI] ai.mcp-config: " + f + " has no \"mcpServers\" object (MCP disabled for AI jobs)");
                return;
            }
            names.addAll(servers.keySet());
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("[AI] ai.mcp-config: cannot read " + f + ": " + e.getMessage() + " (MCP disabled for AI jobs)");
            return;
        }
        String abs;
        try {
            abs = f.getCanonicalPath();
        } catch (IOException e) {
            abs = f.getAbsolutePath();
        }
        extraArgs.add("--mcp-config");
        extraArgs.add(abs);
        for (String n : names) {
            String rule = "mcp__" + n.replaceAll("[^A-Za-z0-9_-]", "_");
            if (!allowed.contains(rule)) {
                allowed.add(rule);
            }
        }
        plugin.getLogger().info("[AI] MCP servers for AI jobs: " + names + " (from " + abs + ")");
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

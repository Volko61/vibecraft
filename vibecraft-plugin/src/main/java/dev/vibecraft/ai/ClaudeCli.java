package dev.vibecraft.ai;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A located Claude Code CLI: how to launch it, which flags it supports, whether it is logged in,
 * and the environment its child processes get. Pure JDK + Gson (no Bukkit).
 */
public final class ClaudeCli {

    /** Authentication state reported by {@code claude auth status}. */
    public enum Auth { UNKNOWN, OK, NOT_LOGGED_IN }

    /** Result of {@link #probe(File, Duration)}. */
    public record Info(String version, Set<String> flags) {
        public boolean supports(String flag) {
            return flags.contains(flag);
        }
    }

    /** Result of {@link #authStatus(File, Duration)}. */
    public record AuthInfo(Auth state, String method, String detail) {
    }

    private final File binary;
    private final List<String> commandPrefix;
    private final Map<String, String> extraEnv;

    public ClaudeCli(File binary) {
        this(binary, ClaudeLocator.commandFor(binary), Map.of());
    }

    /**
     * @param binary        the executable (null when {@code commandPrefix} is a custom command)
     * @param commandPrefix full command used to run the CLI (tests: e.g. {@code java -cp dev FakeClaude})
     * @param extraEnv      extra environment variables for every process (ai.env)
     */
    public ClaudeCli(File binary, List<String> commandPrefix, Map<String, String> extraEnv) {
        this.binary = binary;
        this.commandPrefix = List.copyOf(commandPrefix);
        this.extraEnv = extraEnv == null ? Map.of() : Map.copyOf(extraEnv);
    }

    public File binary() {
        return binary;
    }

    public List<String> commandPrefix() {
        return commandPrefix;
    }

    public boolean exists() {
        return binary == null || binary.isFile();
    }

    // ------------------------------------------------------------------------------------------
    // Environment of child processes
    // ------------------------------------------------------------------------------------------

    /**
     * CLAUDE_CODE_* variables that are user configuration (kept). Every other CLAUDE_CODE_* variable
     * describes the session of a parent Claude Code process and is removed.
     */
    private static final Set<String> KEEP_CLAUDE_CODE = Set.of(
            "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY",
            "CLAUDE_CODE_SKIP_BEDROCK_AUTH", "CLAUDE_CODE_SKIP_VERTEX_AUTH", "CLAUDE_CODE_GIT_BASH_PATH",
            "CLAUDE_CODE_MAX_OUTPUT_TOKENS", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC",
            "CLAUDE_CODE_API_KEY_HELPER_TTL_MS", "CLAUDE_CODE_SUBAGENT_MODEL", "CLAUDE_CODE_SHELL");

    /** Exact names inherited from a parent Claude Code / desktop session that must not leak into jobs. */
    private static final Set<String> STRIP_EXACT = Set.of(
            "CLAUDECODE", "CLAUDE_PID", "CLAUDE_EFFORT", "AI_AGENT", "BAGGAGE", "SENTRY-TRACE", "SENTRY_TRACE",
            "MCP_CONNECTION_NONBLOCKING", "MCP_SERVER_CONNECTION_BATCH_SIZE", "DISABLE_MICROCOMPACT",
            "CHROME_CRASHPAD_PIPE_NAME", "API_TIMEOUT_MS");

    /**
     * Environment for a headless job: the server's environment minus everything that ties a process
     * to a parent Claude Code session (the server may itself have been started from one):
     * <ul>
     *   <li>{@code CLAUDECODE}, {@code CLAUDE_CODE_SESSION_ID}, {@code CLAUDE_CODE_ENTRYPOINT=claude-desktop},
     *       {@code CLAUDE_CODE_CHILD_SESSION}...: the child would behave as a nested desktop session
     *       (verified: it then loads desktop-only tools such as Artifact*);</li>
     *   <li>{@code CLAUDE_CODE_MESSAGING_SOCKET/_TOKEN}: the parent's message pipe and its secret;</li>
     *   <li>{@code CLAUDE_CODE_SDK_HAS_HOST_AUTH_REFRESH}: auth provided by an SDK host that does not exist here;</li>
     *   <li>{@code CLAUDE_EFFORT=xhigh}: would make every live job slow and expensive;</li>
     *   <li>telemetry propagation ({@code BAGGAGE}, {@code SENTRY-TRACE}) and PII ({@code CLAUDE_CODE_USER_EMAIL}).</li>
     * </ul>
     * Kept: {@code ANTHROPIC_*} (API key / base URL), {@code CLAUDE_CODE_OAUTH_TOKEN} and the other user
     * configuration variables of {@link #KEEP_CLAUDE_CODE}, PATH and everything else.
     * Added: {@code DISABLE_AUTOUPDATER=1} (no CLI self-update in the middle of a live demo).
     */
    public static Map<String, String> childEnv(Map<String, String> parent, Map<String, String> extra) {
        Map<String, String> env = new HashMap<>();
        for (Map.Entry<String, String> e : parent.entrySet()) {
            String k = e.getKey();
            String u = k.toUpperCase(Locale.ROOT);
            if (STRIP_EXACT.contains(u)
                    || u.startsWith("CLAUDE_AGENT_SDK_") || u.startsWith("CLAUDE_PREVIEW_")
                    || (u.startsWith("CLAUDE_CODE_") && !KEEP_CLAUDE_CODE.contains(u))) {
                continue;
            }
            env.put(k, e.getValue());
        }
        env.put("DISABLE_AUTOUPDATER", "1");
        if (extra != null) {
            env.putAll(extra);
        }
        return env;
    }

    /** True if the server environment provides credentials the CLI uses without a stored login. */
    public static boolean envProvidesAuth(Map<String, String> env) {
        for (String k : List.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN",
                "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY")) {
            String v = env.get(k);
            if (v != null && !v.isBlank()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Probes (short-lived processes, call off the main thread)
    // ------------------------------------------------------------------------------------------

    private static final Pattern FLAG = Pattern.compile("(?<![\\w-])--[a-zA-Z][a-zA-Z-]+");

    /** {@code claude --version} + {@code claude --help}: version string and supported long flags. */
    public Info probe(File workDir, Duration timeout) {
        String version = "?";
        Set<String> flags = new TreeSet<>();
        try {
            Quick v = runQuick(List.of("--version"), workDir, timeout);
            if (v.exit == 0 && !v.out.isBlank()) {
                version = v.out.strip().lines().findFirst().orElse("?");
            }
            Quick h = runQuick(List.of("--help"), workDir, timeout);
            Matcher m = FLAG.matcher(h.out);
            while (m.find()) {
                flags.add(m.group());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return new Info(version, Set.copyOf(flags));
    }

    /** {@code claude auth status --json}. UNKNOWN if the command fails or prints something unexpected. */
    public AuthInfo authStatus(File workDir, Duration timeout) {
        try {
            Quick q = runQuick(List.of("auth", "status", "--json"), workDir, timeout);
            String out = q.out.strip();
            int brace = out.indexOf('{');
            if (brace < 0) {
                return new AuthInfo(Auth.UNKNOWN, "", "auth status: " + firstLine(q.out + q.err));
            }
            JsonElement el = JsonParser.parseString(out.substring(brace));
            if (!el.isJsonObject()) {
                return new AuthInfo(Auth.UNKNOWN, "", "auth status: not an object");
            }
            JsonObject o = el.getAsJsonObject();
            String method = o.has("authMethod") && !o.get("authMethod").isJsonNull() ? o.get("authMethod").getAsString() : "";
            if (o.has("loggedIn") && o.get("loggedIn").isJsonPrimitive()) {
                boolean in = o.get("loggedIn").getAsBoolean();
                return new AuthInfo(in ? Auth.OK : Auth.NOT_LOGGED_IN, method, "");
            }
            return new AuthInfo(Auth.UNKNOWN, method, "auth status: no loggedIn field");
        } catch (IOException | RuntimeException | LinkageError e) {
            return new AuthInfo(Auth.UNKNOWN, "", "auth status failed: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AuthInfo(Auth.UNKNOWN, "", "interrupted");
        }
    }

    private record Quick(int exit, String out, String err) {
    }

    private Quick runQuick(List<String> args, File workDir, Duration timeout) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(commandPrefix);
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null && workDir.isDirectory()) {
            pb.directory(workDir);
        }
        pb.environment().clear();
        pb.environment().putAll(childEnv(System.getenv(), extraEnv));
        Process p = pb.start();
        p.getOutputStream().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread to = drain(p.getInputStream(), out);
        Thread te = drain(p.getErrorStream(), err);
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            ClaudeRunner.killTree(p.toHandle());
            throw new IOException("timeout after " + timeout.toSeconds() + " s: " + String.join(" ", args));
        }
        to.join(2000);
        te.join(2000);
        return new Quick(p.exitValue(), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (sink) {
                        if (sink.size() < 1_000_000) {
                            sink.write(buf, 0, n);
                        }
                    }
                }
            } catch (IOException ignored) {
                // process gone
            }
        }, "VibeCraft-AI-probe");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String firstLine(String s) {
        String t = s == null ? "" : s.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl);
    }
}

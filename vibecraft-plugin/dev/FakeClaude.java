import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dev fixture: a stand-in for the Claude Code CLI (2.1.289) used by AiRunnerHarness so the job runner can be
 * tested deterministically and for free. It reproduces the observable contract of the real binary:
 * <ul>
 *   <li>{@code --version}, {@code --help} (flag list), {@code auth status --json};</li>
 *   <li>{@code -p --output-format stream-json --verbose}: prompt read from stdin until EOF (hangs if stdin is
 *       never closed, like the real CLI), empty stdin -> same error as the real CLI;</li>
 *   <li>stream-json lines with the same shapes as the real CLI (system/init, assistant text/tool_use,
 *       user tool_result, result), see claude-stream-sample.jsonl for real captured lines;</li>
 *   <li>{@code --resume <id>}: continues a stored session (same id), unknown id -> the real error
 *       (single result line with errors[], stderr "No conversation found with session ID: ...", exit 1);</li>
 *   <li>not logged in (FAKE_CLAUDE_LOGGED_IN=0) -> the real synthetic "Not logged in · Please run /login" run.</li>
 * </ul>
 * It actually writes the module file into modules/&lt;id&gt;/ and really calls the reload endpoint found in the prompt.
 * Scenario keywords in the request: SLEEP n (spawns a child + grandchild process, then waits n s),
 * COMPILE_ERROR_FIRST (first version does not compile), TEST_COMMAND, CRASH (exit 3 without result).
 * State lives in $FAKE_CLAUDE_HOME (sessions/, calls.log, pids-&lt;module&gt;.txt).
 */
public final class FakeClaude {

    static final PrintStream OUT = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
    static File home;
    static String sessionId;
    static String model = "claude-haiku-4-5-20251001";
    static int turns;

    public static void main(String[] args) throws Exception {
        home = new File(System.getenv().getOrDefault("FAKE_CLAUDE_HOME", System.getProperty("java.io.tmpdir") + "/fake-claude"));
        home.mkdirs();
        List<String> a = List.of(args);
        if (a.contains("--sleeper")) {
            sleeper(a);
            return;
        }
        log("ARGS " + String.join(" ", a));
        logEnv();
        if (a.contains("--version")) {
            OUT.println("2.1.289 (Fake Claude)");
            return;
        }
        if (a.contains("--help")) {
            OUT.println("Usage: claude [options]\n  -p, --print\n  --output-format <format>\n  --verbose\n  --model <model>\n"
                    + "  --permission-mode <mode>\n  --permission-prompts <target>\n  --allowedTools, --allowed-tools <tools...>\n"
                    + "  --tools <tools...>\n  --strict-mcp-config\n  --effort <level>\n  --max-budget-usd <amount>\n  -r, --resume [value]");
            return;
        }
        if (a.size() >= 2 && a.get(0).equals("auth") && a.get(1).equals("status")) {
            boolean in = !"0".equals(System.getenv("FAKE_CLAUDE_AUTH_STATUS") != null ? System.getenv("FAKE_CLAUDE_AUTH_STATUS") : System.getenv("FAKE_CLAUDE_LOGGED_IN"));
            OUT.println("{\n  \"loggedIn\": " + in + ",\n  \"authMethod\": \"" + (in ? "claude.ai" : "none") + "\",\n  \"apiProvider\": \"firstParty\"\n}");
            System.exit(in ? 0 : 1);
        }
        if (!a.contains("-p") && !a.contains("--print")) {
            System.err.println("fake claude: only -p is supported");
            System.exit(2);
        }
        String fmt = opt(a, "--output-format");
        if ("stream-json".equals(fmt) && !a.contains("--verbose")) {
            System.err.println("Error: When using --print, --output-format=stream-json requires --verbose");
            System.exit(1);
        }
        if (opt(a, "--model") != null) {
            model = opt(a, "--model").equals("haiku") ? "claude-haiku-4-5-20251001" : opt(a, "--model");
        }
        String prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        log("PROMPT_CHARS " + prompt.length());
        if (prompt.isBlank()) {
            System.err.println("Error: Input must be provided either through stdin or as a prompt argument when using --print");
            System.exit(1);
        }
        String resume = opt(a, "--resume");
        File sessions = new File(home, "sessions");
        sessions.mkdirs();
        boolean resumed = false;
        if (resume != null) {
            File sf = new File(sessions, resume + ".txt");
            if (!sf.isFile()) {
                OUT.println("{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"duration_ms\":0,\"duration_api_ms\":0,\"is_error\":true,\"num_turns\":0,\"stop_reason\":null,\"session_id\":\""
                        + esc(resume) + "\",\"total_cost_usd\":0,\"usage\":{\"input_tokens\":0,\"output_tokens\":0},\"modelUsage\":{},\"permission_denials\":[],\"uuid\":\""
                        + UUID.randomUUID() + "\",\"errors\":[\"No conversation found with session ID: " + esc(resume) + "\"],\"result_index\":0}");
                System.err.println("No conversation found with session ID: " + resume);
                System.exit(1);
            }
            sessionId = resume;
            resumed = true;
        } else {
            sessionId = UUID.randomUUID().toString();
        }
        String cwd = new File(".").getCanonicalPath();
        String tools = opt(a, "--tools");
        String toolsJson = tools == null ? "[\"Task\",\"Bash\",\"Edit\",\"Glob\",\"Grep\",\"Read\",\"Write\"]"
                : "[" + String.join(",", java.util.Arrays.stream(tools.split("[, ]+")).map(t -> "\"" + esc(t) + "\"").toList()) + "]";
        String pm = opt(a, "--permission-mode") == null ? "default" : opt(a, "--permission-mode");
        OUT.println("{\"type\":\"system\",\"subtype\":\"init\",\"cwd\":\"" + esc(cwd) + "\",\"session_id\":\"" + sessionId
                + "\",\"tools\":" + toolsJson + ",\"mcp_servers\":[],\"model\":\"" + esc(model) + "\",\"permissionMode\":\"" + esc(pm)
                + "\",\"slash_commands\":[],\"apiKeySource\":\"none\",\"claude_code_version\":\"2.1.289\",\"output_style\":\"default\",\"agents\":[],\"skills\":[],\"plugins\":[],\"uuid\":\""
                + UUID.randomUUID() + "\"}");

        boolean loggedIn = !"0".equals(System.getenv("FAKE_CLAUDE_LOGGED_IN"));
        if (!loggedIn) {
            OUT.println("{\"type\":\"assistant\",\"message\":{\"id\":\"" + UUID.randomUUID() + "\",\"model\":\"<synthetic>\",\"role\":\"assistant\",\"stop_reason\":\"stop_sequence\",\"type\":\"message\",\"usage\":{\"input_tokens\":0,\"output_tokens\":0},\"content\":[{\"type\":\"text\",\"text\":\"Not logged in · Please run /login\"}]},\"parent_tool_use_id\":null,\"session_id\":\""
                    + sessionId + "\",\"uuid\":\"" + UUID.randomUUID() + "\",\"error\":\"authentication_failed\",\"is_api_error_message\":true}");
            OUT.println("{\"duration_api_ms\":0,\"stop_reason\":\"stop_sequence\",\"session_id\":\"" + sessionId
                    + "\",\"total_cost_usd\":0,\"permission_denials\":[],\"terminal_reason\":\"api_error\",\"is_error\":true,\"num_turns\":1,\"subtype\":\"success\",\"result\":\"Not logged in · Please run /login\",\"type\":\"result\",\"duration_ms\":181,\"uuid\":\""
                    + UUID.randomUUID() + "\"}");
            System.exit(1);
        }

        String request = between(prompt, "<demande>", "</demande>");
        Matcher mm = Pattern.compile("Module : `([a-z0-9_-]+)`").matcher(prompt);
        String module = mm.find() ? mm.group(1) : "unknown";
        Matcher am = Pattern.compile("curl -s -X POST (http://[^/\\s]+)/api/modules/").matcher(prompt);
        String api = am.find() ? am.group(1) : "http://127.0.0.1:8765";
        Files.writeString(new File(sessions, sessionId + ".txt").toPath(), "REQUEST " + request.strip() + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        log("SESSION " + sessionId + " resumed=" + resumed + " module=" + module);

        if (request.contains("CRASH")) {
            System.err.println("Fatal: something exploded inside the fake CLI");
            System.err.println("    at fake.Main.run(main.js:1:1)");
            System.exit(3);
        }

        long t0 = System.currentTimeMillis();
        text(resumed ? "Je reprends le module " + module + " là où on s'était arrêtés." : "Je crée le module " + module + " tout de suite.");
        pause(150);

        Matcher sm = Pattern.compile("SLEEP (\\d+)").matcher(request);
        if (sm.find()) {
            int secs = Integer.parseInt(sm.group(1));
            String java = ProcessHandle.current().info().command().orElse("java");
            Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "FakeClaude", "--sleeper",
                    String.valueOf(secs + 30), "--spawn-grandchild", module).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Files.writeString(new File(home, "pids-" + module + ".txt").toPath(), "parent " + ProcessHandle.current().pid()
                    + "\nchild " + child.pid() + "\n", StandardCharsets.UTF_8);
            String id = toolUse("Bash", "{\"command\":\"sleep " + secs + "\",\"description\":\"Attente\"}");
            for (int i = 0; i < secs * 4; i++) {
                pause(250);
            }
            toolResult(id, "", false);
        }

        String cls = pascal(module) + "Module";
        String pkg = "vibe." + module.replace('-', '_');
        File dir = new File(cwd, "modules/" + module);
        dir.mkdirs();
        File src = new File(dir, cls + ".java");
        boolean broken = request.contains("COMPILE_ERROR_FIRST");
        String wid = toolUse("Write", "{\"file_path\":\"" + esc(src.getPath()) + "\",\"content\":\"...\"}");
        writeModule(src, pkg, cls, broken);
        toolResult(wid, "File created successfully at: " + src.getPath(), false);
        String body = reload(api, module);
        if (broken || body.contains("\"ok\":false")) {
            text("Petite erreur de compilation, je corrige.");
            pause(100);
            String id = toolUse("Edit", "{\"file_path\":\"" + esc(src.getPath()) + "\",\"old_string\":\"BROKEN\",\"new_string\":\"\"}");
            writeModule(src, pkg, cls, false);
            toolResult(id, "The file " + src.getPath() + " has been updated successfully.", false);
            body = reload(api, module);
        }
        if (request.contains("TEST_COMMAND")) {
            String id = toolUse("Bash", "{\"command\":\"curl -s -X POST " + api + "/api/command -d \\\"" + module + " test\\\"\",\"description\":\"Test\"}");
            toolResult(id, http(api + "/api/command", module + " test"), false);
        }
        String fin = "Tapez /" + module + " pour essayer la nouveauté !" + (resumed ? " (suite de la conversation)" : "");
        text(fin);
        turns++;
        OUT.println("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"duration_ms\":" + (System.currentTimeMillis() - t0)
                + ",\"duration_api_ms\":0,\"num_turns\":" + turns + ",\"result\":\"" + esc(fin) + "\",\"stop_reason\":\"end_turn\",\"session_id\":\""
                + sessionId + "\",\"total_cost_usd\":0.0123,\"usage\":{\"input_tokens\":10,\"output_tokens\":20},\"modelUsage\":{},\"permission_denials\":[],\"terminal_reason\":\"completed\",\"uuid\":\""
                + UUID.randomUUID() + "\"}");
    }

    static void writeModule(File src, String pkg, String cls, boolean broken) throws IOException {
        String code = "package " + pkg + ";\n\nimport dev.vibecraft.api.*;\n\npublic final class " + cls + " implements VibeModule {\n"
                + "    @Override public void enable(ModuleContext ctx) { ctx.logger().info(\"enabled\")" + (broken ? " BROKEN" : ";") + " }\n}\n";
        Files.writeString(src.toPath(), code, StandardCharsets.UTF_8);
    }

    static String reload(String api, String module) {
        String id = toolUse("Bash", "{\"command\":\"curl -s -X POST " + api + "/api/modules/" + module + "/reload\",\"description\":\"Recharge le module\"}");
        String body = http(api + "/api/modules/" + module + "/reload", "");
        toolResult(id, body, false);
        return body;
    }

    static String http(String url, String body) {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            return r.body();
        } catch (Exception e) {
            return "curl: (7) Failed to connect: " + e;
        }
    }

    static void text(String t) {
        OUT.println("{\"type\":\"assistant\",\"message\":{\"model\":\"" + esc(model) + "\",\"id\":\"msg_" + UUID.randomUUID().toString().replace("-", "")
                + "\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"" + esc(t)
                + "\"}],\"stop_reason\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}},\"parent_tool_use_id\":null,\"session_id\":\""
                + sessionId + "\",\"uuid\":\"" + UUID.randomUUID() + "\"}");
    }

    static String toolUse(String name, String inputJson) {
        String id = "toolu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        OUT.println("{\"type\":\"assistant\",\"message\":{\"model\":\"" + esc(model) + "\",\"id\":\"msg_" + UUID.randomUUID().toString().replace("-", "")
                + "\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + name
                + "\",\"input\":" + inputJson + "}],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}},\"parent_tool_use_id\":null,\"session_id\":\""
                + sessionId + "\",\"uuid\":\"" + UUID.randomUUID() + "\"}");
        turns++;
        return id;
    }

    static void toolResult(String id, String content, boolean isError) {
        OUT.println("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"" + id + "\",\"type\":\"tool_result\",\"content\":\""
                + esc(content) + "\",\"is_error\":" + isError + "}]},\"parent_tool_use_id\":null,\"session_id\":\"" + sessionId + "\",\"uuid\":\""
                + UUID.randomUUID() + "\",\"tool_use_result\":{\"stdout\":\"" + esc(content) + "\",\"stderr\":\"\",\"interrupted\":false}}");
        pause(100);
    }

    /** Child process for the tree-kill tests: optionally spawns a grandchild, records pids, sleeps. */
    static void sleeper(List<String> a) throws Exception {
        int secs = Integer.parseInt(a.get(a.indexOf("--sleeper") + 1));
        if (a.contains("--spawn-grandchild")) {
            String module = a.get(a.size() - 1);
            String java = ProcessHandle.current().info().command().orElse("java");
            Process g = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "FakeClaude", "--sleeper", String.valueOf(secs))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Files.writeString(new File(home, "pids-" + module + ".txt").toPath(), "grandchild " + g.pid() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        Thread.sleep(secs * 1000L);
    }

    static void logEnv() {
        List<String> seen = new ArrayList<>();
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            String k = e.getKey().toUpperCase();
            if (k.startsWith("CLAUDE") || k.equals("AI_AGENT") || k.startsWith("VIBECRAFT") || k.equals("DISABLE_AUTOUPDATER")
                    || k.startsWith("MCP_") || k.equals("BAGGAGE") || k.startsWith("SENTRY") || k.startsWith("ANTHROPIC")) {
                seen.add(k);
            }
        }
        java.util.Collections.sort(seen);
        log("ENV " + String.join(",", seen));
    }

    static synchronized void log(String s) {
        try {
            Files.writeString(new File(home, "calls.log").toPath(), ProcessHandle.current().pid() + " " + s + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best effort
        }
    }

    static String opt(List<String> a, String name) {
        int i = a.indexOf(name);
        return i >= 0 && i + 1 < a.size() ? a.get(i + 1) : null;
    }

    static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b, i + 1);
        return i >= 0 && j > i ? s.substring(i + a.length(), j) : s;
    }

    static String pascal(String id) {
        StringBuilder sb = new StringBuilder();
        for (String p : id.split("[-_]")) {
            if (!p.isEmpty()) {
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
        }
        String r = sb.toString();
        return r.isEmpty() || Character.isDigit(r.charAt(0)) ? "M" + r : r;
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

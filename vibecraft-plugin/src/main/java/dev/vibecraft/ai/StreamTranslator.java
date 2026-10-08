package dev.vibecraft.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Turns Claude Code {@code --output-format stream-json --verbose} lines into short French progress
 * lines for the audience, and collects the session id and the final result. Not thread-safe (one
 * instance per process, fed by a single reader thread). Pure JDK + Gson.
 *
 * <p>Line shapes (verified with Claude Code 2.1.289, see {@code vibecraft-plugin/dev/claude-stream-sample.jsonl}):
 * <pre>
 * {"type":"system","subtype":"init","session_id":"…","model":"…","tools":[…],"permissionMode":"acceptEdits",…}
 * {"type":"assistant","message":{"model":"…","content":[{"type":"text","text":"…"} | {"type":"tool_use","id":"toolu_…","name":"Write","input":{…}}]},"session_id":"…","error":"authentication_failed"?}
 * {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_…","content":"…"|[{"type":"text","text":"…"}],"is_error":false}]},…}
 * {"type":"result","subtype":"success"|"error_during_execution"|…,"is_error":false,"result":"…","session_id":"…","total_cost_usd":0.01,"num_turns":3,"errors":[…]?,"permission_denials":[…]}
 * </pre>
 */
public final class StreamTranslator {

    /** Final {@code result} line. */
    public record Final(boolean isError, String subtype, String text, String sessionId, double costUsd,
                        int numTurns, long durationMs, List<String> errors, int permissionDenials) {
    }

    private enum Kind { RELOAD, COMMAND, OTHER }

    private record ToolCall(String name, Kind kind) {
    }

    private static final int TEXT_MAX = 90;
    private static final long TEXT_HOLD_MS = 1200;

    private final String moduleId;
    private final Consumer<String> sink;
    private final Map<String, ToolCall> calls = new HashMap<>();

    private String sessionId;
    private String model;
    private boolean realActivity;
    private boolean authFailed;
    private String apiError;
    private String pendingText;
    private long pendingAt;
    private Final fin;
    private int reloadOk;
    private int reloadFailed;
    private int malformed;

    public StreamTranslator(String moduleId, Consumer<String> sink) {
        this.moduleId = moduleId;
        this.sink = sink;
    }

    public synchronized String sessionId() {
        return sessionId;
    }

    public synchronized String model() {
        return model;
    }

    /** True once a real (non-synthetic) assistant message was seen, i.e. the model actually ran. */
    public synchronized boolean realActivity() {
        return realActivity;
    }

    /** True if the CLI reported {@code authentication_failed} (not logged in / bad key). */
    public synchronized boolean authFailed() {
        return authFailed;
    }

    /** The {@code error} field of the last synthetic API-error message (e.g. "rate_limit"), or null. */
    public synchronized String apiError() {
        return apiError;
    }

    public synchronized Final result() {
        return fin;
    }

    public synchronized int reloadOk() {
        return reloadOk;
    }

    public synchronized int reloadFailed() {
        return reloadFailed;
    }

    public synchronized int malformedLines() {
        return malformed;
    }

    /**
     * Assistant text is held back briefly so that the final answer (which is also the {@code result})
     * is not shown twice; call this periodically (e.g. every 500 ms) to release older text.
     */
    public synchronized void tick(long nowMs) {
        if (pendingText != null && nowMs - pendingAt >= TEXT_HOLD_MS) {
            flushText(null);
        }
    }

    /** Feed one stdout line. Never throws. */
    public synchronized void accept(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        JsonObject o;
        try {
            JsonElement el = JsonParser.parseString(line);
            if (!el.isJsonObject()) {
                return;
            }
            o = el.getAsJsonObject();
        } catch (RuntimeException e) {
            malformed++;
            return;
        }
        try {
            String sid = str(o, "session_id");
            if (sid != null && !sid.isBlank()) {
                sessionId = sid;
            }
            switch (String.valueOf(str(o, "type"))) {
                case "system" -> onSystem(o);
                case "assistant" -> onAssistant(o);
                case "user" -> onUser(o);
                case "result" -> onResult(o);
                default -> { /* stream_event, rate limits... ignored */ }
            }
        } catch (RuntimeException e) {
            malformed++;
        }
    }

    /** Flush the buffered assistant text (call when stdout is closed). */
    public synchronized void finish() {
        flushText(fin != null ? fin.text() : null);
    }

    // ------------------------------------------------------------------------------------------

    private void onSystem(JsonObject o) {
        String sub = String.valueOf(str(o, "subtype"));
        if (sub.equals("init")) {
            model = str(o, "model");
        } else if (sub.contains("retry")) {
            emit("⏳ API occupée, nouvel essai…");
        } else if (sub.contains("compact")) {
            emit("🗜 Compression de la conversation…");
        }
    }

    private void onAssistant(JsonObject o) {
        JsonObject msg = obj(o, "message");
        if (msg == null) {
            return;
        }
        String err = str(o, "error");
        boolean synthetic = "<synthetic>".equals(str(msg, "model")) || bool(o, "is_api_error_message");
        if (err != null) {
            apiError = err;
            if (err.equals("authentication_failed")) {
                authFailed = true;
            }
        }
        if (!synthetic) {
            realActivity = true;
        }
        if (o.has("parent_tool_use_id") && !o.get("parent_tool_use_id").isJsonNull()) {
            return; // sub-agent chatter: keep the feed focused
        }
        JsonArray content = arr(msg, "content");
        if (content == null) {
            return;
        }
        for (JsonElement el : content) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject b = el.getAsJsonObject();
            String type = String.valueOf(str(b, "type"));
            if (type.equals("text") && !synthetic) {
                String t = str(b, "text");
                if (t != null && !t.isBlank()) {
                    flushText(null);
                    pendingText = t;
                    pendingAt = System.currentTimeMillis();
                }
            } else if (type.equals("tool_use")) {
                flushText(null);
                onToolUse(b);
            }
        }
    }

    private void onToolUse(JsonObject b) {
        String id = str(b, "id");
        String name = String.valueOf(str(b, "name"));
        JsonObject in = obj(b, "input");
        Kind kind = Kind.OTHER;
        String line;
        switch (name) {
            case "Write" -> line = "✏ Écrit " + shortPath(str(in, "file_path"));
            case "Edit", "MultiEdit", "NotebookEdit" -> line = "✏ Modifie " + shortPath(firstNonNull(str(in, "file_path"), str(in, "notebook_path")));
            case "Read" -> line = "📖 Lit " + shortPath(str(in, "file_path"));
            case "Glob", "Grep" -> line = "🔎 Cherche " + oneLine(firstNonNull(str(in, "pattern"), ""), 50);
            case "TodoWrite", "TaskCreate", "TaskUpdate" -> line = null;
            case "Task", "Agent" -> line = "🤝 Délègue : " + oneLine(firstNonNull(str(in, "description"), "sous-tâche"), 60);
            case "Bash", "PowerShell" -> {
                String cmd = firstNonNull(str(in, "command"), "");
                if (cmd.contains("/reload")) {
                    kind = Kind.RELOAD;
                    line = "🔄 Rechargement à chaud…";
                } else if (cmd.contains("/api/command")) {
                    kind = Kind.COMMAND;
                    String c = curlData(cmd);
                    line = "🧪 Test : " + (c == null ? "commande" : "/" + oneLine(c.startsWith("/") ? c.substring(1) : c, 60));
                } else if (cmd.contains("/logs")) {
                    line = "📜 Lit les logs du module";
                } else if (cmd.contains("/api/")) {
                    line = "🌐 Interroge l'API du serveur";
                } else {
                    line = "💻 " + oneLine(cmd, 60);
                }
            }
            default -> line = "🔧 " + name;
        }
        if (id != null) {
            calls.put(id, new ToolCall(name, kind));
        }
        if (line != null) {
            emit(line);
        }
    }

    private void onUser(JsonObject o) {
        if (o.has("parent_tool_use_id") && !o.get("parent_tool_use_id").isJsonNull()) {
            return;
        }
        JsonObject msg = obj(o, "message");
        JsonArray content = msg == null ? null : arr(msg, "content");
        if (content == null) {
            return;
        }
        for (JsonElement el : content) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject b = el.getAsJsonObject();
            if (!"tool_result".equals(str(b, "type"))) {
                continue;
            }
            ToolCall call = calls.remove(String.valueOf(str(b, "tool_use_id")));
            String text = resultText(b.get("content"));
            boolean isError = bool(b, "is_error");
            onToolResult(call, text, isError);
        }
    }

    private void onToolResult(ToolCall call, String text, boolean isError) {
        Kind kind = call == null ? Kind.OTHER : call.kind();
        String lower = text.toLowerCase(Locale.ROOT);
        if (isError && (lower.contains("permission") || lower.contains("haven't granted") || lower.contains("not allowed"))) {
            emit("⛔ Action refusée (" + (call == null ? "outil" : call.name()) + ")");
            return;
        }
        if (kind == Kind.RELOAD) {
            JsonObject r = firstJsonObject(text);
            if (r != null && r.has("ok") && r.get("ok").isJsonPrimitive()) {
                if (r.get("ok").getAsBoolean()) {
                    reloadOk++;
                    int gen = r.has("generation") && r.get("generation").isJsonPrimitive() ? r.get("generation").getAsInt() : 0;
                    emit("✅ Rechargé à chaud" + (gen > 0 ? " (v" + gen + ")" : ""));
                } else {
                    reloadFailed++;
                    emit("⚠ Erreur de compilation" + describeFailure(r));
                }
            } else if (isError || lower.contains("failed to connect") || lower.contains("couldn't connect")) {
                emit("⚠ API du serveur injoignable");
            }
        } else if (kind == Kind.COMMAND) {
            JsonObject r = firstJsonObject(text);
            JsonArray out = r == null ? null : arr(r, "output");
            if (out != null && !out.isEmpty() && out.get(0).isJsonPrimitive()) {
                emit("🧪 → " + oneLine(stripFormatting(out.get(0).getAsString()), 70));
            }
        } else if (isError && call != null && (call.name().equals("Write") || call.name().equals("Edit"))) {
            emit("⚠ " + oneLine(text, 80));
        }
    }

    private static String describeFailure(JsonObject r) {
        JsonArray diags = arr(r, "diagnostics");
        if (diags != null) {
            for (JsonElement d : diags) {
                if (!d.isJsonObject()) {
                    continue;
                }
                JsonObject dj = d.getAsJsonObject();
                String kind = String.valueOf(str(dj, "kind"));
                if (kind.equalsIgnoreCase("ERROR") || kind.equals("null")) {
                    String msg = str(dj, "message");
                    String file = str(dj, "file");
                    String where = "";
                    if (file != null) {
                        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
                        where = " (" + file.substring(slash + 1)
                                + (dj.has("line") && dj.get("line").isJsonPrimitive() ? ":" + dj.get("line").getAsInt() : "") + ")";
                    }
                    if (msg != null) {
                        return " : " + oneLine(msg, 60) + where;
                    }
                }
            }
        }
        String err = str(r, "error");
        return err == null ? "" : " : " + oneLine(err, 70);
    }

    private void onResult(JsonObject o) {
        List<String> errors = new ArrayList<>();
        JsonArray ea = arr(o, "errors");
        if (ea != null) {
            for (JsonElement e : ea) {
                errors.add(e.isJsonPrimitive() ? e.getAsString() : e.toString());
            }
        }
        JsonArray denials = arr(o, "permission_denials");
        String text = str(o, "result");
        fin = new Final(bool(o, "is_error"), str(o, "subtype"), text, str(o, "session_id"),
                num(o, "total_cost_usd"), (int) num(o, "num_turns"), (long) num(o, "duration_ms"),
                List.copyOf(errors), denials == null ? 0 : denials.size());
        if (text != null && (text.startsWith("Not logged in") || text.contains("Invalid API key") || text.contains("/login"))) {
            authFailed = true;
        }
    }

    // ------------------------------------------------------------------------------------------

    private void flushText(String dropIfEquals) {
        String t = pendingText;
        pendingText = null;
        if (t == null) {
            return;
        }
        if (dropIfEquals != null && dropIfEquals.strip().equals(t.strip())) {
            return; // the final answer is delivered separately
        }
        emit("💬 " + oneLine(stripFormatting(t), TEXT_MAX));
    }

    private void emit(String line) {
        try {
            sink.accept(line);
        } catch (RuntimeException ignored) {
            // a broken sink must not stop parsing
        }
    }

    private String shortPath(String p) {
        if (p == null || p.isBlank()) {
            return "?";
        }
        String n = p.replace('\\', '/');
        String marker = "modules/" + moduleId + "/";
        int i = n.indexOf(marker);
        if (i >= 0) {
            return n.substring(i + marker.length());
        }
        int slash = n.lastIndexOf('/');
        return slash >= 0 && slash < n.length() - 1 ? n.substring(slash + 1) : n;
    }

    private static final Pattern DATA = Pattern.compile(
            "(?:^|\\s)(?:-d|--data|--data-raw|--data-binary)(?:\\s+|=)(?:\"((?:[^\"\\\\]|\\\\.)*)\"|'([^']*)'|(\\S+))");

    static String curlData(String cmd) {
        Matcher m = DATA.matcher(cmd);
        if (!m.find()) {
            return null;
        }
        String v = m.group(1) != null ? m.group(1).replace("\\\"", "\"") : m.group(2) != null ? m.group(2) : m.group(3);
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** Text of a tool_result content (string or array of text blocks). */
    static String resultText(JsonElement c) {
        if (c == null || c.isJsonNull()) {
            return "";
        }
        if (c.isJsonPrimitive()) {
            return c.getAsString();
        }
        if (c.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : c.getAsJsonArray()) {
                if (e.isJsonObject() && e.getAsJsonObject().has("text")) {
                    sb.append(e.getAsJsonObject().get("text").getAsString()).append('\n');
                } else if (e.isJsonPrimitive()) {
                    sb.append(e.getAsString()).append('\n');
                }
            }
            return sb.toString();
        }
        return c.toString();
    }

    /** First balanced {...} JSON object found in a text (curl output may be surrounded by other text). */
    static JsonObject firstJsonObject(String text) {
        int start = text.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean inStr = false;
            for (int i = start; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (inStr) {
                    if (ch == '\\') {
                        i++;
                    } else if (ch == '"') {
                        inStr = false;
                    }
                } else if (ch == '"') {
                    inStr = true;
                } else if (ch == '{') {
                    depth++;
                } else if (ch == '}' && --depth == 0) {
                    try {
                        JsonElement el = JsonParser.parseString(text.substring(start, i + 1));
                        if (el.isJsonObject()) {
                            return el.getAsJsonObject();
                        }
                    } catch (RuntimeException ignored) {
                        // not JSON, try the next brace
                    }
                    break;
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    /** Collapse whitespace, cut to {@code max} chars with an ellipsis. */
    public static String oneLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").strip();
        if (t.length() > max) {
            int cut = max - 1;
            if (Character.isHighSurrogate(t.charAt(cut - 1))) {
                cut--;
            }
            t = t.substring(0, cut).stripTrailing() + "…";
        }
        return t;
    }

    /** Remove the most common Markdown decorations (the chat is not a Markdown renderer). */
    public static String stripFormatting(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("**", "").replace("__", "").replace("`", "").replaceAll("(?m)^\\s*#+\\s*", "")
                .replaceAll("(?m)^\\s*[-*]\\s+", "• ");
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String str(JsonObject o, String k) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) {
            return null;
        }
        JsonElement e = o.get(k);
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    private static boolean bool(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean()
                && o.get(k).getAsBoolean();
    }

    private static double num(JsonObject o, String k) {
        try {
            return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : null;
    }
}

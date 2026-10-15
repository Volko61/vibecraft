package dev.vibecraft.ai;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The conversation of each module (= its Claude Code session), as a list of messages made of blocks:
 * the user requests, everything the agent streamed (text, thinking, tool calls, tool results) and status
 * lines (reload ok/failed, job finished/failed/stopped). Fed by {@link AiJobEngine} through
 * {@link AiJobEngine.Transcript} (raw {@code stream-json} lines), persisted in
 * {@code plugins/VibeCraft/conversations/<id>.json} (last {@value #MAX_MESSAGES} messages, written ~1 s after a
 * change and at the end of each job), secrets redacted before anything is stored. Thread-safe; pure JDK + Gson.
 *
 * <p>Block types: {@code text}, {@code thinking} (text), {@code tool_use} (name, input = short summary),
 * {@code tool_result} (name, ok, text = short excerpt), {@code status} (text, ok).
 */
public final class ConversationStore implements AiJobEngine.Transcript {

    public static final int MAX_MESSAGES = 300;
    static final int MAX_BLOCKS = 400;
    static final int MAX_TEXT = 20_000;
    static final int MAX_INPUT = 300;
    static final int MAX_RESULT = 400;

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Immutable view of a block (null fields are absent from the JSON). */
    public record BlockView(String type, String text, String name, String input, Boolean ok) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            if (text != null) {
                m.put("text", text);
            }
            if (name != null) {
                m.put("name", name);
            }
            if (input != null) {
                m.put("input", input);
            }
            if (ok != null) {
                m.put("ok", ok);
            }
            return m;
        }
    }

    /** Immutable view of a message. {@code open} = the assistant message of the job that is still running. */
    public record MessageView(String id, String role, long at, List<BlockView> blocks, boolean open) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("role", role);
            m.put("at", at);
            List<Map<String, Object>> b = new ArrayList<>(blocks.size());
            for (BlockView v : blocks) {
                b.add(v.toJson());
            }
            m.put("blocks", b);
            return m;
        }
    }

    /** Summary of a conversation (no messages). {@code lastState}: last job state name, "" if none. */
    public record Summary(String id, String title, long createdAt, long updatedAt, String lastLine, String lastState,
                          String imageProvider, String model, int messageCount, long rev) {
    }

    // ------------------------------------------------------------------ persisted model (Gson)

    static final class Block {
        String type;
        String text;
        String name;
        String input;
        Boolean ok;

        Block(String type, String text, String name, String input, Boolean ok) {
            this.type = type;
            this.text = text;
            this.name = name;
            this.input = input;
            this.ok = ok;
        }

        BlockView view() {
            return new BlockView(type, text, name, input, ok);
        }
    }

    static final class Message {
        int seq;
        String id;
        String role;
        long at;
        String jobId;
        String by;
        List<Block> blocks = new ArrayList<>();
        transient boolean open;

        MessageView view() {
            List<BlockView> b = new ArrayList<>(blocks.size());
            for (Block x : blocks) {
                b.add(x.view());
            }
            return new MessageView(id, role, at, List.copyOf(b), open);
        }
    }

    static final class Conv {
        String id;
        String title;
        long createdAt;
        long updatedAt;
        String lastState = "";
        String imageProvider = "auto";
        String model = "";
        int nextSeq = 1;
        List<Message> messages = new ArrayList<>();
        transient long rev;
        transient boolean dirty;
        transient Parser parser;
    }

    // ------------------------------------------------------------------

    private final File dir;
    private final Logger log;
    private final UnaryOperator<String> redactor;
    private final Map<String, Conv> convs = new TreeMap<>();
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VibeCraft-conversations");
        t.setDaemon(true);
        return t;
    });
    private boolean saveScheduled;
    private long revCounter;

    public ConversationStore(File dir, Logger log, UnaryOperator<String> redactor) {
        this.dir = dir;
        this.log = log;
        this.redactor = redactor == null ? UnaryOperator.identity() : redactor;
        load();
    }

    // ================================================================== reading

    public synchronized boolean exists(String id) {
        return convs.containsKey(id);
    }

    public synchronized List<Summary> list() {
        List<Summary> out = new ArrayList<>();
        for (Conv c : convs.values()) {
            out.add(summary(c));
        }
        out.sort(Comparator.comparingLong(Summary::updatedAt).reversed());
        return out;
    }

    public synchronized Optional<Summary> summary(String id) {
        Conv c = convs.get(id);
        return c == null ? Optional.empty() : Optional.of(summary(c));
    }

    /** All messages (oldest first). */
    public synchronized List<MessageView> messages(String id) {
        Conv c = convs.get(id);
        if (c == null) {
            return List.of();
        }
        List<MessageView> out = new ArrayList<>(c.messages.size());
        for (Message m : c.messages) {
            out.add(m.view());
        }
        return out;
    }

    /**
     * Messages from index {@code since - 1} on (the client's last message is sent again: it may be the
     * assistant message that is still being streamed), all of them when {@code since <= 0}.
     */
    public synchronized List<MessageView> since(String id, int since) {
        Conv c = convs.get(id);
        if (c == null) {
            return List.of();
        }
        int from = since <= 0 ? 0 : Math.max(0, Math.min(since, c.messages.size()) - 1);
        List<MessageView> out = new ArrayList<>();
        for (int i = from; i < c.messages.size(); i++) {
            out.add(c.messages.get(i).view());
        }
        return out;
    }

    public synchronized int count(String id) {
        Conv c = convs.get(id);
        return c == null ? 0 : c.messages.size();
    }

    /** Changes at every update of the conversation (0 if unknown). */
    public synchronized long rev(String id) {
        Conv c = convs.get(id);
        return c == null ? 0 : c.rev;
    }

    // ================================================================== writing

    /** Forget a conversation (file deleted). Returns false if there was none. */
    public boolean delete(String id) {
        boolean had;
        synchronized (this) {
            had = convs.remove(id) != null;
        }
        File f = file(id);
        if (f != null && f.isFile()) {
            try {
                Files.deleteIfExists(f.toPath());
                had = true;
            } catch (IOException e) {
                log.warning("[AI] cannot delete " + f + ": " + e.getMessage());
            }
        }
        return had;
    }

    @Override
    public synchronized void userMessage(String moduleId, String jobId, String prompt, String requestedBy, JobOptions options) {
        Conv c = conv(moduleId, true);
        if (c == null) {
            return;
        }
        String text = cut(redact(prompt), MAX_TEXT);
        if (c.title == null || c.title.isBlank()) {
            c.title = StreamTranslator.oneLine(text, 60);
        }
        Message m = add(c, "user", jobId);
        m.by = requestedBy;
        m.blocks.add(new Block("text", text, null, null, null));
        if (options != null) {
            c.imageProvider = options.imageProvider();
            c.model = options.model();
        }
        c.lastState = AiJobState.QUEUED.name();
        c.parser = new Parser(moduleId, jobId);
        touch(c);
    }

    @Override
    public synchronized void streamLine(String moduleId, String jobId, String line) {
        Conv c = conv(moduleId, true);
        if (c == null || line == null || line.isBlank()) {
            return;
        }
        if (c.parser == null || !jobId.equals(c.parser.jobId)) {
            c.parser = new Parser(moduleId, jobId);
        }
        if (!AiJobState.RUNNING.name().equals(c.lastState)) {
            c.lastState = AiJobState.RUNNING.name();
            touch(c);
        }
        JsonObject o;
        try {
            JsonElement el = JsonParser.parseString(line);
            if (!el.isJsonObject()) {
                return;
            }
            o = el.getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        try {
            if (c.parser.accept(c, o)) {
                touch(c);
            }
        } catch (RuntimeException e) {
            log.log(Level.FINE, "[AI] transcript parse failed", e);
        }
    }

    @Override
    public synchronized void jobFinished(String moduleId, String jobId, AiJobState state, String finalLine) {
        Conv c = conv(moduleId, true);
        if (c == null) {
            return;
        }
        for (Message m : c.messages) {
            m.open = false;
        }
        Message m = add(c, "system", jobId);
        String line = finalLine == null || finalLine.isBlank() ? switch (state) {
            case SUCCEEDED -> "✅ Terminé";
            case STOPPED -> "⏹ Arrêté";
            default -> "❌ Échec";
        } : finalLine;
        m.blocks.add(new Block("status", cut(redact(line), 2000), null, null, state == AiJobState.SUCCEEDED));
        c.lastState = state.name();
        c.parser = null;
        touch(c);
        flushSoon(0);
    }

    @Override
    public synchronized void conversationReset(String moduleId) {
        Conv c = convs.get(moduleId);
        if (c == null) {
            return;
        }
        Message m = add(c, "system", null);
        m.blocks.add(new Block("status", "🧹 Conversation oubliée : la prochaine demande repart de zéro", null, null, true));
        touch(c);
    }

    /** Write everything pending now (onDisable, after the AI jobs were stopped). */
    public void close() {
        flushNow();
        io.shutdown();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ================================================================== internals

    private Summary summary(Conv c) {
        String title = c.title == null || c.title.isBlank() ? c.id : c.title;
        return new Summary(c.id, title, c.createdAt, c.updatedAt, lastLine(c), c.lastState == null ? "" : c.lastState,
                c.imageProvider, c.model, c.messages.size(), c.rev);
    }

    /** One short line for lists: the last block of the last message. */
    static String lastLine(Conv c) {
        for (int i = c.messages.size() - 1; i >= 0; i--) {
            Message m = c.messages.get(i);
            for (int j = m.blocks.size() - 1; j >= 0; j--) {
                Block b = m.blocks.get(j);
                String s = switch (b.type) {
                    case "text" -> (m.role.equals("user") ? "👤 " : "💬 ") + StreamTranslator.stripFormatting(b.text);
                    case "thinking" -> "💭 réflexion…";
                    case "tool_use" -> "🔧 " + b.name + (b.input == null || b.input.isBlank() ? "" : " " + b.input);
                    case "tool_result" -> (Boolean.FALSE.equals(b.ok) ? "⚠ " : "↳ ") + (b.name == null ? "" : b.name + " ")
                            + (b.text == null ? "" : b.text);
                    default -> b.text;
                };
                if (s != null && !s.isBlank()) {
                    return StreamTranslator.oneLine(s, 90);
                }
            }
        }
        return "";
    }

    private Conv conv(String id, boolean create) {
        if (id == null || !ID.matcher(id).matches()) {
            return null;
        }
        Conv c = convs.get(id);
        if (c == null && create) {
            c = new Conv();
            c.id = id;
            c.createdAt = System.currentTimeMillis();
            c.updatedAt = c.createdAt;
            convs.put(id, c);
        }
        return c;
    }

    private Message add(Conv c, String role, String jobId) {
        Message m = new Message();
        m.seq = c.nextSeq++;
        m.id = "m" + m.seq;
        m.role = role;
        m.at = System.currentTimeMillis();
        m.jobId = jobId;
        c.messages.add(m);
        while (c.messages.size() > MAX_MESSAGES) {
            c.messages.remove(0);
        }
        return m;
    }

    private void touch(Conv c) {
        c.updatedAt = System.currentTimeMillis();
        c.rev = ++revCounter;
        c.dirty = true;
        flushSoon(1000);
    }

    private String redact(String s) {
        if (s == null) {
            return null;
        }
        try {
            return redactor.apply(s);
        } catch (RuntimeException e) {
            return s;
        }
    }

    static String cut(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        int c = max - 1;
        if (Character.isHighSurrogate(s.charAt(c - 1))) {
            c--;
        }
        return s.substring(0, c) + "…";
    }

    // ------------------------------------------------------------------ persistence

    private File file(String id) {
        return id != null && ID.matcher(id).matches() ? new File(dir, id + ".json") : null;
    }

    private synchronized void flushSoon(long delayMs) {
        if (saveScheduled && delayMs > 0) {
            return;
        }
        saveScheduled = true;
        try {
            io.schedule(this::flushNow, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            saveScheduled = false;
        }
    }

    private void flushNow() {
        Map<String, String> out = new HashMap<>();
        synchronized (this) {
            saveScheduled = false;
            for (Conv c : convs.values()) {
                if (c.dirty) {
                    c.dirty = false;
                    out.put(c.id, GSON.toJson(c));
                }
            }
        }
        for (Map.Entry<String, String> e : out.entrySet()) {
            write(e.getKey(), e.getValue());
        }
    }

    private void write(String id, String json) {
        File f = file(id);
        if (f == null) {
            return;
        }
        synchronized (this) {
            if (!convs.containsKey(id)) {
                return; // deleted meanwhile
            }
        }
        try {
            Files.createDirectories(dir.toPath());
            File tmp = new File(dir, id + ".json.tmp");
            Files.writeString(tmp.toPath(), json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warning("[AI] cannot write " + f + ": " + e.getMessage());
        }
    }

    private void load() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) {
            return;
        }
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 5);
            if (!ID.matcher(id).matches()) {
                continue;
            }
            try {
                Conv c = GSON.fromJson(Files.readString(f.toPath(), StandardCharsets.UTF_8), Conv.class);
                if (c == null) {
                    continue;
                }
                c.id = id;
                if (c.messages == null) {
                    c.messages = new ArrayList<>();
                }
                c.messages.removeIf(m -> m == null || m.role == null);
                for (Message m : c.messages) {
                    if (m.blocks == null) {
                        m.blocks = new ArrayList<>();
                    }
                    m.blocks.removeIf(b -> b == null || b.type == null);
                    if (m.id == null) {
                        m.id = "m" + m.seq;
                    }
                }
                if (c.nextSeq <= 0) {
                    c.nextSeq = c.messages.stream().mapToInt(m -> m.seq).max().orElse(0) + 1;
                }
                if (c.imageProvider == null) {
                    c.imageProvider = "auto";
                }
                if (c.model == null) {
                    c.model = "";
                }
                if (AiJobState.RUNNING.name().equals(c.lastState) || AiJobState.QUEUED.name().equals(c.lastState)) {
                    c.lastState = AiJobState.STOPPED.name(); // the server stopped while it ran
                }
                c.rev = ++revCounter;
                convs.put(id, c);
            } catch (IOException | RuntimeException e) {
                log.warning("[AI] cannot read conversation " + f.getName() + ": " + e.getClass().getSimpleName());
            }
        }
        if (!convs.isEmpty()) {
            log.info("[AI] " + convs.size() + " conversation(s) loaded from " + dir.getName() + "/");
        }
    }

    // ================================================================== stream-json -> blocks

    /** Per job: tool calls in flight and the job's open assistant message. */
    private final class Parser {
        final String moduleId;
        final String jobId;
        final Map<String, String[]> calls = new HashMap<>(); // tool_use id -> {name, kind}
        Message assistant;

        Parser(String moduleId, String jobId) {
            this.moduleId = moduleId;
            this.jobId = jobId;
        }

        Message assistant(Conv c) {
            if (assistant == null || !assistant.open || assistant.blocks.size() >= MAX_BLOCKS || !c.messages.contains(assistant)) {
                assistant = add(c, "assistant", jobId);
                assistant.open = true;
            }
            return assistant;
        }

        void block(Conv c, Block b) {
            assistant(c).blocks.add(b);
        }

        /** @return true if something was added */
        boolean accept(Conv c, JsonObject o) {
            String type = str(o, "type");
            if (type == null) {
                return false;
            }
            boolean sub = o.has("parent_tool_use_id") && !o.get("parent_tool_use_id").isJsonNull();
            switch (type) {
                case "assistant" -> {
                    if (sub) {
                        return false; // sub-agent chatter
                    }
                    JsonObject msg = obj(o, "message");
                    JsonArray content = msg == null ? null : arr(msg, "content");
                    if (content == null) {
                        return false;
                    }
                    boolean any = false;
                    for (JsonElement el : content) {
                        if (el.isJsonObject()) {
                            any |= assistantBlock(c, el.getAsJsonObject());
                        }
                    }
                    return any;
                }
                case "user" -> {
                    if (sub) {
                        return false;
                    }
                    JsonObject msg = obj(o, "message");
                    JsonArray content = msg == null ? null : arr(msg, "content");
                    if (content == null) {
                        return false;
                    }
                    boolean any = false;
                    for (JsonElement el : content) {
                        if (el.isJsonObject() && "tool_result".equals(str(el.getAsJsonObject(), "type"))) {
                            toolResult(c, el.getAsJsonObject());
                            any = true;
                        }
                    }
                    return any;
                }
                case "system" -> {
                    String st = String.valueOf(str(o, "subtype"));
                    if (st.contains("compact")) {
                        block(c, new Block("status", "🗜 Conversation compressée", null, null, true));
                        return true;
                    }
                    return false;
                }
                default -> {
                    return false;
                }
            }
        }

        private boolean assistantBlock(Conv c, JsonObject b) {
            String t = String.valueOf(str(b, "type"));
            switch (t) {
                case "text" -> {
                    String text = str(b, "text");
                    if (text == null || text.isBlank()) {
                        return false;
                    }
                    block(c, new Block("text", cut(redact(text.strip()), MAX_TEXT), null, null, null));
                    return true;
                }
                case "thinking" -> {
                    String th = str(b, "thinking");
                    block(c, new Block("thinking", th == null || th.isBlank() ? "(contenu de la réflexion non transmis)"
                            : cut(redact(th.strip()), MAX_TEXT), null, null, null));
                    return true;
                }
                case "redacted_thinking" -> {
                    block(c, new Block("thinking", "(réflexion chiffrée)", null, null, null));
                    return true;
                }
                case "tool_use" -> {
                    String name = String.valueOf(str(b, "name"));
                    JsonObject in = obj(b, "input");
                    String id = str(b, "id");
                    String cmd = in == null ? null : str(in, "command");
                    String kind = (name.equals("Bash") || name.equals("PowerShell")) && cmd != null && cmd.contains("/reload")
                            ? "reload" : "other";
                    if (id != null) {
                        calls.put(id, new String[] {name, kind});
                    }
                    block(c, new Block("tool_use", null, name, cut(redact(summarize(name, in)), MAX_INPUT), null));
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        private void toolResult(Conv c, JsonObject b) {
            String[] call = calls.remove(String.valueOf(str(b, "tool_use_id")));
            String name = call == null ? null : call[0];
            boolean isError = b.has("is_error") && b.get("is_error").isJsonPrimitive() && b.get("is_error").getAsBoolean();
            String text = StreamTranslator.resultText(b.get("content"));
            block(c, new Block("tool_result", cut(redact(StreamTranslator.oneLine(text, MAX_RESULT)), MAX_RESULT), name, null, !isError));
            if (call != null && call[1].equals("reload")) {
                JsonObject r = StreamTranslator.firstJsonObject(text);
                if (r != null && r.has("ok") && r.get("ok").isJsonPrimitive()) {
                    boolean ok = r.get("ok").getAsBoolean();
                    String line;
                    if (ok) {
                        int gen = r.has("generation") && r.get("generation").isJsonPrimitive() ? r.get("generation").getAsInt() : 0;
                        line = "✅ Rechargé à chaud" + (gen > 0 ? " (v" + gen + ")" : "");
                    } else {
                        String err = str(r, "error");
                        line = "⚠ Rechargement refusé" + (err == null ? "" : " : " + StreamTranslator.oneLine(err, 120));
                    }
                    block(c, new Block("status", redact(line), null, null, ok));
                }
            }
        }

        private String summarize(String name, JsonObject in) {
            if (in == null) {
                return "";
            }
            return switch (name) {
                case "Write", "Edit", "MultiEdit", "Read", "NotebookEdit" ->
                        shortPath(firstNonNull(str(in, "file_path"), str(in, "notebook_path")));
                case "Bash", "PowerShell" -> StreamTranslator.oneLine(firstNonNull(str(in, "command"), ""), MAX_INPUT);
                case "Glob", "Grep" -> StreamTranslator.oneLine(firstNonNull(str(in, "pattern"), "")
                        + (str(in, "path") != null ? " dans " + shortPath(str(in, "path")) : ""), MAX_INPUT);
                case "Task", "Agent" -> StreamTranslator.oneLine(firstNonNull(str(in, "description"), "sous-tâche"), MAX_INPUT);
                case "TodoWrite" -> {
                    JsonArray todos = arr(in, "todos");
                    yield (todos == null ? 0 : todos.size()) + " tâche(s)";
                }
                case "WebFetch" -> StreamTranslator.oneLine(firstNonNull(str(in, "url"), ""), MAX_INPUT);
                default -> StreamTranslator.oneLine(in.toString(), MAX_INPUT);
            };
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
            String ws = n.toLowerCase(Locale.ROOT);
            int w = ws.lastIndexOf("/workspace/");
            return w >= 0 ? n.substring(w + "/workspace/".length()) : n;
        }
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

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : null;
    }
}

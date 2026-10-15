package dev.vibecraft.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vibecraft.ai.AiJob;
import dev.vibecraft.ai.ConversationStore;
import dev.vibecraft.ai.Conversations;
import dev.vibecraft.ext.MeshyClient;
import dev.vibecraft.ext.OpenRouterClient;
import dev.vibecraft.web.ApiExtensions.Reply;

/**
 * Conversation API (one conversation per module = its Claude Code session), registered through {@link ApiExtensions}
 * (same token / Host / Origin checks as the rest of {@code /api}):
 * <pre>
 * GET    /api/conversations                       [{id, title, state, updatedAt, lastLine, moduleState, generation}] newest first
 * GET    /api/conversations/{id}                  {id, title, state, moduleState, generation, messages:[{id, role, at, blocks:[...]}]}
 * GET    /api/conversations/{id}/events?since=N   {messages:[new or updated], state, count}
 * POST   /api/conversations/{id}/messages         {text, imageProvider: auto|openrouter|meshy, model?} -> 201 {jobId}
 * POST   /api/conversations/{id}/stop             {ok}
 * DELETE /api/conversations/{id}                  {ok, deleted} (conversation + Claude session forgotten, module files kept)
 * GET    /api/providers                           {openrouter:{available, imageModel}, meshy:{available}, claude:{available, reason}}
 * </pre>
 * {@code state}: idle | running | queued | failed. Blocks: {type: text|thinking|tool_use|tool_result|status, text?, name?,
 * input?, ok?}. {@code events?since=N} (N = number of messages the client has) re-sends the client's last message
 * (the assistant message being streamed) and everything after it.
 */
public final class ConversationRoutes {

    private final Conversations conv;
    private final ApiExtensions.Route route = this::route;

    public ConversationRoutes(Conversations conv) {
        this.conv = conv;
    }

    public void register() {
        ApiExtensions.register(route);
    }

    public void unregister() {
        ApiExtensions.unregister(route);
    }

    private CompletableFuture<Reply> route(ApiExtensions.Request r) throws Exception {
        List<String> s = r.segments();
        int n = s.size();
        if (n == 2 && s.get(1).equals("providers")) {
            if (!r.method().equals("GET")) {
                return done(Reply.error(405, "use GET"));
            }
            return done(Reply.ok(providers()));
        }
        if (n < 2 || !s.get(1).equals("conversations")) {
            return null;
        }
        if (n == 2) {
            if (!r.method().equals("GET")) {
                return done(Reply.error(405, "use GET"));
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Conversations.Entry e : conv.list()) {
                out.add(e.toJson());
            }
            return done(Reply.ok(out));
        }
        String id = s.get(2);
        if (!conv.validId(id)) {
            return done(Reply.error(400, "invalid module id '" + cut(id) + "': must match [a-z0-9][a-z0-9_-]{0,39}"));
        }
        if (n == 3) {
            switch (r.method()) {
                case "GET" -> {
                    return done(conv.entry(id).map(e -> Reply.ok(detail(e)))
                            .orElse(Reply.error(404, "unknown conversation '" + id + "'")));
                }
                case "DELETE" -> {
                    try {
                        boolean deleted = conv.delete(id);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("ok", true);
                        m.put("deleted", deleted);
                        return done(Reply.ok(m));
                    } catch (IllegalStateException e) {
                        return done(Reply.error(409, e.getMessage()));
                    }
                }
                default -> {
                    return done(Reply.error(405, "use GET or DELETE"));
                }
            }
        }
        if (n == 4) {
            switch (s.get(3)) {
                case "events" -> {
                    if (!r.method().equals("GET")) {
                        return done(Reply.error(405, "use GET"));
                    }
                    int since;
                    try {
                        String q = r.query().get("since");
                        since = q == null || q.isBlank() ? 0 : Math.max(0, Integer.parseInt(q.strip()));
                    } catch (NumberFormatException e) {
                        return done(Reply.error(400, "invalid since (number of messages the client has)"));
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("messages", messages(conv.store().since(id, since)));
                    m.put("state", conv.state(id));
                    m.put("count", conv.store().count(id));
                    return done(Reply.ok(m));
                }
                case "messages" -> {
                    if (!r.method().equals("POST")) {
                        return done(Reply.error(405, "use POST with a JSON body {text, imageProvider, model}"));
                    }
                    return done(send(id, r));
                }
                case "stop" -> {
                    if (!r.method().equals("POST")) {
                        return done(Reply.error(405, "use POST"));
                    }
                    return done(Reply.ok(Map.of("ok", conv.stop(id))));
                }
                default -> {
                }
            }
        }
        return done(Reply.error(404, "no such endpoint: " + r.method() + " /" + String.join("/", s)));
    }

    private Reply send(String id, ApiExtensions.Request r) throws Exception {
        JsonObject o;
        try {
            String body = r.body().get();
            JsonElement el = JsonParser.parseString(body == null || body.isBlank() ? "{}" : body);
            if (!el.isJsonObject()) {
                return Reply.error(400, "body must be a JSON object {text, imageProvider, model}");
            }
            o = el.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            return Reply.error(400, "invalid JSON body (expected {\"text\": \"...\", \"imageProvider\": \"auto\"})");
        }
        String text = str(o, "text");
        String by = r.query().get("by");
        if (by == null || by.isBlank()) {
            by = "dashboard";
        } else if (by.length() > 32) {
            by = by.substring(0, 32);
        }
        if (text == null || text.isBlank()) {
            return Reply.error(400, "empty text: send {\"text\": \"...\"}");
        }
        if (text.strip().length() > Conversations.MAX_TEXT) {
            return Reply.error(413, "text too long (max " + Conversations.MAX_TEXT + " chars)");
        }
        if (!conv.ai().available()) {
            return Reply.error(503, "AI unavailable: " + conv.ai().unavailableReason());
        }
        try {
            AiJob job = conv.send(id, text, str(o, "imageProvider"), str(o, "model"), by);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", job.id());
            return new Reply(201, m);
        } catch (Conversations.TooLong e) {
            return Reply.error(413, e.getMessage());
        } catch (IllegalStateException e) {
            if (!conv.ai().available()) {
                return Reply.error(503, "AI unavailable: " + conv.ai().unavailableReason());
            }
            return Reply.error(409, e.getMessage() == null ? "a job is already running for '" + id + "'" : e.getMessage());
        } catch (IllegalArgumentException e) {
            return Reply.error(400, String.valueOf(e.getMessage()));
        }
    }

    private Map<String, Object> detail(Conversations.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("title", e.title());
        m.put("state", e.state());
        m.put("moduleState", e.moduleState());
        m.put("generation", e.generation());
        m.put("messages", messages(conv.store().messages(e.id())));
        return m;
    }

    private static List<Map<String, Object>> messages(List<ConversationStore.MessageView> list) {
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (ConversationStore.MessageView v : list) {
            out.add(v.toJson());
        }
        return out;
    }

    private Map<String, Object> providers() {
        Map<String, Object> out = new LinkedHashMap<>();
        OpenRouterClient or = OpenRouterClient.get();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("available", or != null && or.hasKey());
        o.put("imageModel", or == null ? null : or.imageModel());
        out.put("openrouter", o);
        MeshyClient me = MeshyClient.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", me != null && me.hasKey());
        out.put("meshy", m);
        Map<String, Object> c = new LinkedHashMap<>();
        boolean av = conv.ai().available();
        c.put("available", av);
        c.put("reason", av ? "" : conv.ai().unavailableReason());
        out.put("claude", c);
        return out;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static String cut(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }

    private static CompletableFuture<Reply> done(Reply r) {
        return CompletableFuture.completedFuture(r);
    }
}

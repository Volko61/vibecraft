package dev.vibecraft.ext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.ConfigurationSection;

/**
 * OpenRouter client (chat completions + image generation). Everything runs on a small dedicated
 * daemon pool ({@code VibeCraft-OpenRouter-N}): never on the server main thread. The API key comes
 * from {@link SecretStore} ("openrouter") and is never logged or put in an error message.
 */
public final class OpenRouterClient {

    /** One chat message. */
    public record Message(String role, String content) {
    }

    /** A generated image (raw encoded bytes, PNG/JPEG...) and the reported cost in USD (or -1). */
    public record Image(byte[] bytes, String mediaType, double cost, String model) {
    }

    /** A failure with a message safe to show (French, no secret). {@code status} = suggested HTTP status. */
    public static final class OpenRouterException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        public OpenRouterException(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public static final String NO_KEY = "Aucune clé OpenRouter configurée : ajoutez la ligne openrouter: \"sk-or-v1-...\" "
            + "dans plugins/VibeCraft/secrets.yml (pris en compte sans redémarrage) ou définissez la variable "
            + "d'environnement OPENROUTER_API_KEY avant de lancer le serveur.";

    private static volatile OpenRouterClient instance;

    private final SecretStore secrets;
    private final Logger log;
    private final String baseUrl;
    private final String model;
    private final String imageModel;
    private final String imageApi;
    private final int maxTokens;
    private final Duration timeout;
    private final Duration imageTimeout;
    private final int maxPerMinute;
    private final ThreadPoolExecutor pool;
    private final ThreadPoolExecutor imagePool;
    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private volatile HttpClient http;

    public OpenRouterClient(ConfigurationSection c, SecretStore secrets, Logger log) {
        this.secrets = secrets;
        this.log = log;
        String base = c == null ? null : c.getString("base-url");
        this.baseUrl = stripSlash(base == null || base.isBlank() ? "https://openrouter.ai/api/v1" : base.strip());
        this.model = str(c, "model", "google/gemini-2.5-flash-lite");
        this.imageModel = str(c, "image-model", "google/gemini-2.5-flash-image");
        this.imageApi = str(c, "image-api", "auto").toLowerCase(Locale.ROOT);
        this.maxTokens = c == null ? 400 : Math.max(16, Math.min(8000, c.getInt("max-tokens", 400)));
        this.timeout = Duration.ofSeconds(c == null ? 60 : Math.max(5, Math.min(600, c.getInt("timeout-seconds", 60))));
        this.imageTimeout = Duration.ofSeconds(c == null ? 120 : Math.max(10, Math.min(600, c.getInt("image-timeout-seconds", 120))));
        this.maxPerMinute = c == null ? 30 : Math.max(1, c.getInt("max-requests-per-minute", 30));
        int conc = c == null ? 4 : Math.max(1, Math.min(16, c.getInt("max-concurrent", 4)));
        int imgConc = c == null ? 2 : Math.max(1, Math.min(8, c.getInt("max-concurrent-images", 2)));
        this.pool = pool("VibeCraft-OpenRouter", conc, 32);
        this.imagePool = pool("VibeCraft-OpenRouter-img", imgConc, 4);
    }

    public static void install(OpenRouterClient c) {
        instance = c;
    }

    public static OpenRouterClient get() {
        return instance;
    }

    public boolean hasKey() {
        return secrets != null && secrets.secret("openrouter") != null;
    }

    public String defaultModel() {
        return model;
    }

    public String imageModel() {
        return imageModel;
    }

    public void shutdown() {
        pool.shutdownNow();
        imagePool.shutdownNow();
        if (instance == this) {
            instance = null;
        }
    }

    // ------------------------------------------------------------------ chat

    /** Chat completion; completes with the assistant text, on a VibeCraft-OpenRouter thread. */
    public CompletableFuture<String> chat(String modelOverride, List<Message> messages, int maxTokensOverride) {
        CompletableFuture<String> f = new CompletableFuture<>();
        if (messages == null || messages.isEmpty()) {
            f.completeExceptionally(new OpenRouterException(400, "aucun message"));
            return f;
        }
        List<Message> copy = List.copyOf(messages);
        try {
            pool.execute(() -> {
                try {
                    f.complete(chatBlocking(modelOverride, copy, maxTokensOverride));
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            f.completeExceptionally(new OpenRouterException(429, "trop de requêtes OpenRouter en attente, réessayez plus tard"));
        }
        return f;
    }

    private String chatBlocking(String modelOverride, List<Message> messages, int maxTok) throws Exception {
        String key = requireKey();
        rateLimit();
        JsonObject body = new JsonObject();
        body.addProperty("model", blank(modelOverride) ? model : modelOverride.strip());
        JsonArray arr = new JsonArray();
        for (Message m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", blank(m.role()) ? "user" : m.role());
            o.addProperty("content", m.content() == null ? "" : m.content());
            arr.add(o);
        }
        body.add("messages", arr);
        body.addProperty("max_tokens", maxTok > 0 ? Math.min(maxTok, 8000) : maxTokens);
        JsonObject resp = post(key, "/chat/completions", body, timeout);
        JsonObject msg = firstMessage(resp);
        if (msg == null) {
            throw new OpenRouterException(502, "réponse OpenRouter inattendue (pas de choices[0].message)");
        }
        JsonElement content = msg.get("content");
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (content.isJsonArray()) { // [{type:text,text:..}]
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : content.getAsJsonArray()) {
                if (e.isJsonObject() && e.getAsJsonObject().has("text")) {
                    sb.append(e.getAsJsonObject().get("text").getAsString());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    // ------------------------------------------------------------------ images

    /** Image generation (one image). Runs on the image pool (max-concurrent-images). */
    public CompletableFuture<Image> image(String modelOverride, String prompt, boolean transparent) {
        CompletableFuture<Image> f = new CompletableFuture<>();
        try {
            imagePool.execute(() -> {
                try {
                    f.complete(imageBlocking(modelOverride, prompt, transparent));
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            f.completeExceptionally(new OpenRouterException(429,
                    "trop de générations d'images en cours, réessayez dans quelques secondes"));
        }
        return f;
    }

    private Image imageBlocking(String modelOverride, String prompt, boolean transparent) throws Exception {
        String key = requireKey();
        rateLimit();
        String m = blank(modelOverride) ? imageModel : modelOverride.strip();
        if (!imageApi.equals("chat")) {
            JsonObject body = new JsonObject();
            body.addProperty("model", m);
            body.addProperty("prompt", prompt);
            body.addProperty("n", 1);
            body.addProperty("output_format", "png");
            body.addProperty("aspect_ratio", "1:1");
            if (transparent) {
                body.addProperty("background", "transparent");
            }
            try {
                return extractImage(post(key, "/images", body, imageTimeout), m);
            } catch (OpenRouterException e) {
                // "auto": endpoint/model combination not supported -> chat completions with image modality
                if (!imageApi.equals("auto") || !(e.status() == 404 || e.status() == 405 || e.status() == 400)) {
                    throw e;
                }
            }
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", m);
        JsonArray msgs = new JsonArray();
        JsonObject u = new JsonObject();
        u.addProperty("role", "user");
        u.addProperty("content", prompt);
        msgs.add(u);
        body.add("messages", msgs);
        JsonArray mod = new JsonArray();
        mod.add("image");
        mod.add("text");
        body.add("modalities", mod);
        JsonObject cfg = new JsonObject();
        cfg.addProperty("aspect_ratio", "1:1");
        body.add("image_config", cfg);
        return extractImage(post(key, "/chat/completions", body, imageTimeout), m);
    }

    private Image extractImage(JsonObject resp, String m) throws Exception {
        double cost = -1;
        JsonObject usage = obj(resp, "usage");
        if (usage != null && usage.has("cost") && usage.get("cost").isJsonPrimitive()) {
            try {
                cost = usage.get("cost").getAsDouble();
            } catch (RuntimeException ignored) {
                // not a number
            }
        }
        // 1) Images API: {data:[{b64_json, media_type} | {url}]}
        JsonElement data = resp.get("data");
        if (data != null && data.isJsonArray()) {
            for (JsonElement e : data.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                if (o.has("b64_json") && o.get("b64_json").isJsonPrimitive()) {
                    return new Image(decodeB64(o.get("b64_json").getAsString()), str(o, "media_type"), cost, m);
                }
                if (o.has("url") && o.get("url").isJsonPrimitive()) {
                    return new Image(fetchUrl(o.get("url").getAsString()), null, cost, m);
                }
            }
        }
        // 2) chat completions: choices[0].message.images[].image_url.url (data URL), or content parts
        JsonObject msg = firstMessage(resp);
        if (msg != null) {
            for (String field : List.of("images", "content")) {
                JsonElement imgs = msg.get(field);
                if (imgs == null || !imgs.isJsonArray()) {
                    continue;
                }
                for (JsonElement e : imgs.getAsJsonArray()) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject iu = obj(e.getAsJsonObject(), "image_url");
                    String url = iu != null ? str(iu, "url") : str(e.getAsJsonObject(), "url");
                    if (url != null) {
                        return new Image(fetchUrl(url), null, cost, m);
                    }
                }
            }
            String text = str(msg, "content");
            if (text != null && !text.isBlank()) {
                throw new OpenRouterException(502, "le modèle " + m + " a répondu du texte au lieu d'une image : "
                        + cut(text, 160));
            }
        }
        throw new OpenRouterException(502, "aucune image dans la réponse d'OpenRouter (modèle " + m
                + " : choisissez un modèle qui génère des images, ex. google/gemini-2.5-flash-image)");
    }

    private byte[] fetchUrl(String url) throws Exception {
        if (url.startsWith("data:")) {
            int comma = url.indexOf(',');
            if (comma < 0 || !url.substring(0, comma).contains(";base64")) {
                throw new OpenRouterException(502, "data URL d'image invalide");
            }
            return decodeB64(url.substring(comma + 1));
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new OpenRouterException(502, "URL d'image non supportée");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(imageTimeout).GET().build();
        HttpResponse<byte[]> r = client().send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() / 100 != 2) {
            throw new OpenRouterException(502, "téléchargement de l'image impossible (HTTP " + r.statusCode() + ")");
        }
        if (r.body().length > 25_000_000) {
            throw new OpenRouterException(502, "image trop grande");
        }
        return r.body();
    }

    private static byte[] decodeB64(String s) {
        String clean = s.strip();
        if (clean.length() > 34_000_000) {
            throw new OpenRouterException(502, "image trop grande");
        }
        try {
            return Base64.getMimeDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            throw new OpenRouterException(502, "image base64 invalide dans la réponse");
        }
    }

    // ------------------------------------------------------------------ http

    private JsonObject post(String key, String path, JsonObject body, Duration to) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(to)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", "https://github.com/vibecraft")
                .header("X-Title", "VibeCraft")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r;
        try {
            r = client().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new OpenRouterException(504, "OpenRouter n'a pas répondu à temps (" + to.toSeconds() + " s)");
        } catch (IOException e) {
            throw new OpenRouterException(502, "OpenRouter injoignable : " + cut(String.valueOf(e.getMessage()), 120));
        }
        String text = r.body() == null ? "" : r.body();
        JsonObject json = null;
        try {
            JsonElement el = JsonParser.parseString(text);
            if (el.isJsonObject()) {
                json = el.getAsJsonObject();
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        int sc = r.statusCode();
        if (sc / 100 != 2 || (json != null && json.has("error") && !json.has("choices") && !json.has("data"))) {
            String msg = null;
            if (json != null) {
                JsonObject err = obj(json, "error");
                msg = err != null ? str(err, "message") : str(json, "error");
            }
            int status = sc / 100 == 2 ? 502 : sc;
            String human = switch (status) {
                case 401, 403 -> "clé OpenRouter refusée (401/403) : vérifiez plugins/VibeCraft/secrets.yml";
                case 402 -> "crédit OpenRouter insuffisant (402)";
                case 429 -> "OpenRouter limite le débit (429), réessayez dans un moment";
                default -> "erreur OpenRouter HTTP " + status;
            };
            throw new OpenRouterException(status, secrets.redact(human + (msg == null ? "" : " : " + cut(msg, 300))));
        }
        if (json == null) {
            throw new OpenRouterException(502, "réponse OpenRouter non JSON");
        }
        return json;
    }

    private HttpClient client() {
        HttpClient c = http;
        if (c == null) {
            synchronized (this) {
                c = http;
                if (c == null) {
                    c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                            .followRedirects(HttpClient.Redirect.NORMAL).build();
                    http = c;
                }
            }
        }
        return c;
    }

    private String requireKey() {
        String key = secrets == null ? null : secrets.secret("openrouter");
        if (key == null) {
            throw new OpenRouterException(503, NO_KEY);
        }
        return key;
    }

    private void rateLimit() {
        long now = System.currentTimeMillis();
        synchronized (recent) {
            while (!recent.isEmpty() && now - recent.peekFirst() > 60_000) {
                recent.pollFirst();
            }
            if (recent.size() >= maxPerMinute) {
                throw new OpenRouterException(429, "limite de " + maxPerMinute
                        + " requêtes OpenRouter par minute atteinte (openrouter.max-requests-per-minute)");
            }
            recent.addLast(now);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject firstMessage(JsonObject resp) {
        JsonElement ch = resp.get("choices");
        if (ch == null || !ch.isJsonArray() || ch.getAsJsonArray().isEmpty()) {
            return null;
        }
        JsonElement c0 = ch.getAsJsonArray().get(0);
        return c0.isJsonObject() ? obj(c0.getAsJsonObject(), "message") : null;
    }

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static String str(ConfigurationSection c, String k, String def) {
        String v = c == null ? null : c.getString(k);
        return v == null || v.isBlank() ? def : v.strip();
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static String cut(String s, int n) {
        String t = s.replace('\n', ' ').strip();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    private static ThreadPoolExecutor pool(String name, int threads, int queue) {
        AtomicInteger n = new AtomicInteger();
        ThreadPoolExecutor p = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue), r -> {
                    Thread t = new Thread(r, name + "-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        p.allowCoreThreadTimeOut(true);
        return p;
    }
}

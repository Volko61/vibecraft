package dev.vibecraft.ext;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Meshy AI public API (https://docs.meshy.ai): text-to-image ({@code POST /openapi/v1/text-to-image}) and
 * text-to-3D ({@code POST /openapi/v2/text-to-3d} preview, then refine for the texture), asynchronous tasks
 * polled with {@code GET .../<id>} until {@code SUCCEEDED}, then the result files are downloaded. The key
 * ({@code meshy} in secrets.yml or {@code MESHY_API_KEY}) is read at each call and never logged. Everything runs
 * on VibeCraft-Meshy daemon threads; futures complete there (never the main thread).
 */
public final class MeshyClient {

    public static final String NO_KEY = "Aucune clé Meshy configurée : ajoutez la ligne meshy: \"msy_...\" dans "
            + "plugins/VibeCraft/secrets.yml (pris en compte sans redémarrage) ou définissez la variable d'environnement "
            + "MESHY_API_KEY avant de lancer le serveur.";

    private static final long MAX_DOWNLOAD = 64L * 1024 * 1024;

    /** HTTP / task error with a French message (status = HTTP-like code for the API answer). */
    public static final class MeshyException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        public MeshyException(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** Progress of a running task: stage ("preview", "refine", "image"), Meshy status and 0..100. */
    public record Progress(String stage, String status, int progress) {
    }

    /** Files of a finished text-to-3D task. {@code texture} may be null (untextured preview). */
    public record Model(byte[] obj, byte[] mtl, byte[] texture, String previewTaskId, String refineTaskId, String thumbnailUrl) {
    }

    /** Options of a text-to-3D generation. */
    public record ModelOptions(String aiModel, int targetPolycount, boolean texture, String texturePrompt) {
    }

    private static volatile MeshyClient instance;

    private final SecretStore secrets;
    private final Logger log;
    private final String baseUrl;
    private final URI base;
    private final String imageModel;
    private final String modelAi;
    private final int polycount;
    private final long pollMs;
    private final Duration timeout;
    private final ScheduledExecutorService exec;
    private final HttpClient http;

    public MeshyClient(ConfigurationSection c, SecretStore secrets, Logger log) {
        this.secrets = secrets;
        this.log = log;
        String b = c == null ? null : c.getString("base-url");
        b = b == null || b.isBlank() ? "https://api.meshy.ai" : b.strip();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        this.baseUrl = b;
        this.base = URI.create(b);
        this.imageModel = str(c, "image-model", "nano-banana");
        this.modelAi = str(c, "model-3d", "latest");
        this.polycount = c == null ? 8000 : Math.max(100, Math.min(300_000, c.getInt("target-polycount", 8000)));
        this.pollMs = c == null ? 3000 : Math.max(100, c.getLong("poll-interval-ms", 3000));
        this.timeout = Duration.ofSeconds(c == null ? 900 : Math.max(30, c.getInt("timeout-seconds", 900)));
        AtomicInteger n = new AtomicInteger();
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(3, r -> {
            Thread t = new Thread(r, "VibeCraft-Meshy-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        ex.setRemoveOnCancelPolicy(true);
        this.exec = ex;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public static void install(MeshyClient c) {
        instance = c;
    }

    public static MeshyClient get() {
        return instance;
    }

    public boolean hasKey() {
        return secrets != null && secrets.secret("meshy") != null;
    }

    public String imageModel() {
        return imageModel;
    }

    public void shutdown() {
        exec.shutdownNow();
        if (instance == this) {
            instance = null;
        }
    }

    // ================================================================== text to image

    /** Generates one image; completes with the PNG/JPEG bytes. */
    public CompletableFuture<byte[]> textToImage(String prompt, String aiModel, boolean removeBackground, Consumer<Progress> progress) {
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        submit(f, () -> {
            JsonObject body = new JsonObject();
            body.addProperty("ai_model", aiModel == null || aiModel.isBlank() ? imageModel : aiModel.strip());
            body.addProperty("prompt", cut(prompt, 2000));
            body.addProperty("aspect_ratio", "1:1");
            if (removeBackground) {
                body.addProperty("remove_background", true);
            }
            String id = create("/openapi/v1/text-to-image", body);
            poll("/openapi/v1/text-to-image/", id, "image", deadline, progress).thenAccept(task -> submit(f, () -> {
                JsonArray urls = arr(task, "image_urls");
                if (urls == null || urls.isEmpty() || !urls.get(0).isJsonPrimitive()) {
                    throw new MeshyException(502, "Meshy n'a renvoyé aucune image");
                }
                f.complete(download(urls.get(0).getAsString()));
            })).exceptionally(t -> {
                f.completeExceptionally(unwrap(t));
                return null;
            });
        });
        return f;
    }

    // ================================================================== text to 3D

    /** Preview (mesh) then refine (texture), then downloads OBJ (+ MTL + base colour texture). */
    public CompletableFuture<Model> textTo3d(String prompt, ModelOptions o, Consumer<Progress> progress) {
        CompletableFuture<Model> f = new CompletableFuture<>();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        submit(f, () -> {
            JsonObject body = new JsonObject();
            body.addProperty("mode", "preview");
            body.addProperty("prompt", cut(prompt, 800));
            body.addProperty("ai_model", o.aiModel() == null || o.aiModel().isBlank() ? modelAi : o.aiModel());
            body.addProperty("should_remesh", true);
            body.addProperty("topology", "triangle");
            body.addProperty("target_polycount", o.targetPolycount() > 0 ? o.targetPolycount() : polycount);
            JsonArray fmts = new JsonArray();
            fmts.add("obj");
            body.add("target_formats", fmts);
            String previewId = create("/openapi/v2/text-to-3d", body);
            poll("/openapi/v2/text-to-3d/", previewId, "preview", deadline, progress).thenAccept(preview -> submit(f, () -> {
                if (!o.texture()) {
                    f.complete(files(preview, previewId, null));
                    return;
                }
                JsonObject rb = new JsonObject();
                rb.addProperty("mode", "refine");
                rb.addProperty("preview_task_id", previewId);
                rb.addProperty("enable_pbr", false);
                if (o.texturePrompt() != null && !o.texturePrompt().isBlank()) {
                    rb.addProperty("texture_prompt", cut(o.texturePrompt(), 800));
                }
                rb.add("target_formats", fmts);
                String refineId = create("/openapi/v2/text-to-3d", rb);
                poll("/openapi/v2/text-to-3d/", refineId, "refine", deadline, progress).thenAccept(refined -> submit(f, () -> {
                    if (progress != null) {
                        progress.accept(new Progress("download", "SUCCEEDED", 100));
                    }
                    f.complete(files(refined, previewId, refineId));
                })).exceptionally(t -> {
                    f.completeExceptionally(unwrap(t));
                    return null;
                });
            })).exceptionally(t -> {
                f.completeExceptionally(unwrap(t));
                return null;
            });
        });
        return f;
    }

    private Model files(JsonObject task, String previewId, String refineId) throws IOException, InterruptedException {
        JsonObject urls = obj(task, "model_urls");
        String objUrl = str(urls, "obj");
        if (objUrl == null) {
            throw new MeshyException(502, "Meshy n'a pas fourni de fichier OBJ (model_urls.obj absent)");
        }
        byte[] objBytes = download(objUrl);
        byte[] mtl = null;
        String mtlUrl = str(urls, "mtl");
        if (mtlUrl != null) {
            try {
                mtl = download(mtlUrl);
            } catch (IOException | MeshyException e) {
                mtl = null;
            }
        }
        byte[] tex = null;
        JsonArray tu = arr(task, "texture_urls");
        if (tu != null && !tu.isEmpty() && tu.get(0).isJsonObject()) {
            String bc = str(tu.get(0).getAsJsonObject(), "base_color");
            if (bc != null) {
                tex = download(bc);
            }
        }
        return new Model(objBytes, mtl, tex, previewId, refineId, str(task, "thumbnail_url"));
    }

    // ================================================================== HTTP

    private String create(String path, JsonObject body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + requireKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        JsonObject o = json(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        String id = str(o, "result");
        if (id == null || id.isBlank()) {
            throw new MeshyException(502, "réponse Meshy inattendue (pas d'identifiant de tâche)");
        }
        log.info("Meshy: task " + id + " created (" + path + ")");
        return id;
    }

    /** Polls the task until SUCCEEDED (completes with the task), FAILED / CANCELED / timeout (fails). */
    private CompletableFuture<JsonObject> poll(String path, String id, String stage, long deadline, Consumer<Progress> progress) {
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            try {
                if (System.currentTimeMillis() > deadline) {
                    throw new MeshyException(504, "Meshy n'a pas fini à temps (" + timeout.toSeconds() + " s, meshy.timeout-seconds)");
                }
                HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path + id))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + requireKey())
                        .GET().build();
                JsonObject t = json(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
                String status = String.valueOf(str(t, "status")).toUpperCase(Locale.ROOT);
                int pr = t.has("progress") && t.get("progress").isJsonPrimitive() ? t.get("progress").getAsInt() : 0;
                if (progress != null) {
                    try {
                        progress.accept(new Progress(stage, status, pr));
                    } catch (RuntimeException ignored) {
                        // a broken progress sink must not stop the task
                    }
                }
                switch (status) {
                    case "SUCCEEDED" -> f.complete(t);
                    case "FAILED", "CANCELED", "EXPIRED" -> {
                        JsonObject err = obj(t, "task_error");
                        String msg = err == null ? null : str(err, "message");
                        throw new MeshyException(502, "tâche Meshy " + stage + " " + status.toLowerCase(Locale.ROOT)
                                + (msg == null || msg.isBlank() ? "" : " : " + cut(msg, 200)));
                    }
                    default -> exec.schedule(step[0], pollMs, TimeUnit.MILLISECONDS);
                }
            } catch (Throwable e) {
                f.completeExceptionally(e instanceof InterruptedException ? new MeshyException(503, "arrêt en cours") : e);
            }
        };
        try {
            exec.schedule(step[0], Math.min(pollMs, 1000), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            f.completeExceptionally(new MeshyException(503, "arrêt en cours"));
        }
        return f;
    }

    /** Downloads a result file: only https URLs or URLs on the configured API host (local test servers). */
    byte[] download(String url) throws IOException, InterruptedException {
        URI u;
        try {
            u = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new MeshyException(502, "URL de fichier Meshy invalide");
        }
        boolean sameHost = u.getHost() != null && u.getHost().equalsIgnoreCase(base.getHost())
                && u.getPort() == base.getPort() && String.valueOf(u.getScheme()).equalsIgnoreCase(base.getScheme());
        if (!"https".equalsIgnoreCase(u.getScheme()) && !sameHost) {
            throw new MeshyException(502, "URL de fichier Meshy refusée (https attendu)");
        }
        HttpRequest req = HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(120)).GET().build();
        HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            if (r.statusCode() / 100 != 2) {
                throw new MeshyException(502, "téléchargement Meshy impossible (HTTP " + r.statusCode() + ")");
            }
            byte[] data = in.readNBytes((int) MAX_DOWNLOAD + 1);
            if (data.length > MAX_DOWNLOAD) {
                throw new MeshyException(502, "fichier Meshy trop gros (> 64 Mo)");
            }
            return data;
        }
    }

    private JsonObject json(HttpResponse<String> r) {
        int st = r.statusCode();
        JsonObject o = null;
        try {
            JsonElement el = JsonParser.parseString(r.body() == null ? "" : r.body());
            o = el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            // not JSON
        }
        if (st / 100 != 2) {
            String msg = o == null ? null : str(o, "message");
            if (msg == null && o != null && o.has("error")) {
                JsonElement e = o.get("error");
                msg = e.isJsonPrimitive() ? e.getAsString() : e.isJsonObject() ? str(e.getAsJsonObject(), "message") : null;
            }
            String human = switch (st) {
                case 400 -> "requête refusée par Meshy";
                case 401, 403 -> "clé Meshy refusée (vérifiez meshy dans secrets.yml)";
                case 402 -> "crédits Meshy insuffisants";
                case 404 -> "tâche Meshy introuvable";
                case 429 -> "limite de requêtes Meshy atteinte, réessayez dans un moment";
                default -> "erreur Meshy (HTTP " + st + ")";
            };
            int out = st == 401 || st == 403 ? 502 : st == 402 ? 402 : st == 429 ? 429 : st >= 500 ? 502 : st;
            throw new MeshyException(out, secrets == null ? human : secrets.redact(human + (msg == null ? "" : " : " + cut(msg, 200))));
        }
        if (o == null) {
            throw new MeshyException(502, "réponse Meshy non JSON");
        }
        return o;
    }

    private String requireKey() {
        String key = secrets == null ? null : secrets.secret("meshy");
        if (key == null) {
            throw new MeshyException(503, NO_KEY);
        }
        return key;
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private <T> void submit(CompletableFuture<T> f, Step s) {
        try {
            exec.execute(() -> {
                try {
                    s.run();
                } catch (Throwable t) {
                    f.completeExceptionally(t instanceof InterruptedException ? new MeshyException(503, "arrêt en cours") : t);
                }
            });
        } catch (RejectedExecutionException e) {
            f.completeExceptionally(new MeshyException(503, "arrêt en cours"));
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while (c instanceof java.util.concurrent.CompletionException && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    private static String str(ConfigurationSection c, String k, String def) {
        String v = c == null ? null : c.getString(k);
        return v == null || v.isBlank() ? def : v.strip();
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String cut(String s, int n) {
        String t = s == null ? "" : s.strip();
        return t.length() <= n ? t : t.substring(0, n);
    }

}

package dev.vibecraft.web;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.vibecraft.ai.AiJob;
import dev.vibecraft.ai.AiJobService;
import dev.vibecraft.ai.AiJobState;
import dev.vibecraft.command.Msg;
import dev.vibecraft.command.Slug;
import dev.vibecraft.core.ModuleInfo;
import dev.vibecraft.core.ModuleManager;
import dev.vibecraft.ideas.Idea;
import dev.vibecraft.ideas.IdeaQueue;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Local HTTP API + dashboard ({@code com.sun.net.httpserver}), see ARCHITECTURE.md section 5.
 *
 * <p>Threading: requests are handled by a small daemon pool. Anything touching the Bukkit API is
 * hopped to the main thread with a timeout; long waits (reload, command) are asynchronous, so a
 * slow reload never pins an HTTP thread. Server metrics for {@code /api/status} come from a
 * snapshot refreshed every second on the main thread, so the dashboard keeps answering even if
 * the main thread is busy.
 */
public final class ApiServer {

    private static final int MAX_BODY = 64 * 1024;
    private static final long RELOAD_TIMEOUT_S = 120;
    private static final long DISABLE_TIMEOUT_S = 60;
    private static final long COMMAND_TIMEOUT_S = 10;
    private static final long SYNC_TIMEOUT_S = 5;
    private static final int MAX_IDEA_LENGTH = 500;
    private static final int MAX_PROMPT_LENGTH = 20_000;

    private final JavaPlugin plugin;
    private final ModuleManager modules;
    private final AiJobService ai;
    private final IdeaQueue ideas;

    private final Set<Call> pending = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> errorLogTimes = new ConcurrentHashMap<>();

    private HttpServer server;
    private ThreadPoolExecutor executor;
    private BukkitTask snapshotTask;
    private volatile ServerSnapshot snapshot;
    private volatile boolean stopping;
    private byte[] token = new byte[0];
    private String host;
    private int port;
    private boolean autoReload = true;

    public ApiServer(JavaPlugin plugin, ModuleManager modules, AiJobService ai, IdeaQueue ideas) {
        this.plugin = plugin;
        this.modules = modules;
        this.ai = ai;
        this.ideas = ideas;
    }

    /** Bind {@code api.host:api.port} and start serving. Called from onEnable (main thread). */
    public void start() throws IOException {
        host = plugin.getConfig().getString("api.host", "127.0.0.1");
        port = plugin.getConfig().getInt("api.port", 8765);
        autoReload = plugin.getConfig().getBoolean("auto-reload", true);
        String tok = plugin.getConfig().getString("api.token", "");
        token = tok == null ? new byte[0] : tok.strip().getBytes(StandardCharsets.UTF_8);

        // Slow / half-open clients must not hold the request threads forever (they would stall the dashboard
        // and the AI agents' curl calls): the JDK server closes connections whose request is not read within
        // maxReqTime seconds (read once per JVM, before the first HttpServer is created).
        setIfAbsent("sun.net.httpserver.maxReqTime", "3");
        setIfAbsent("sun.net.httpserver.maxReqHeaders", "100");
        AtomicInteger n = new AtomicInteger();
        executor = new ThreadPoolExecutor(32, 32, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "VibeCraft-API-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        executor.allowCoreThreadTimeOut(true);

        HttpServer s;
        try {
            s = HttpServer.create(new InetSocketAddress(host, port), 64);
        } catch (IOException e) {
            executor.shutdownNow();
            throw new IOException("cannot bind " + host + ":" + port + " (" + e.getMessage() + ")", e);
        }
        s.setExecutor(executor);
        s.createContext("/", this::handle);
        stopping = false;
        refreshSnapshot();
        s.start();
        server = s;
        try {
            snapshotTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshSnapshot, 20L, 20L);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("API: cannot schedule status snapshots: " + e);
        }
        plugin.getLogger().info("HTTP API + dashboard on http://" + displayHost() + ":" + port + "/"
                + (token.length > 0 ? " (token required)" : ""));
    }

    /** Stop accepting requests, answer in-flight ones with 503 and release the port. */
    public void stop() {
        stopping = true;
        if (snapshotTask != null) {
            try {
                snapshotTask.cancel();
            } catch (RuntimeException ignored) {
                // plugin already disabled
            }
            snapshotTask = null;
        }
        for (Call call : List.copyOf(pending)) {
            call.respond(503, Map.of("error", "server is stopping"));
        }
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    private static void setIfAbsent(String key, String value) {
        try {
            if (System.getProperty(key) == null) {
                System.setProperty(key, value);
            }
        } catch (SecurityException ignored) {
            // keep the JDK default
        }
    }

    private String displayHost() {
        return host == null || host.isBlank() || host.equals("0.0.0.0") ? "localhost" : host;
    }

    // ------------------------------------------------------------------ dispatch

    private void handle(HttpExchange exchange) {
        Call call = new Call(exchange);
        try {
            if (stopping) {
                call.respond(503, Map.of("error", "server is stopping"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path == null) {
                path = "/";
            }
            String refused = refusedOrigin(exchange);
            if (refused != null) {
                call.respond(403, Map.of("error", refused));
                return;
            }
            if (path.equals("/api") || path.startsWith("/api/")) {
                try {
                    call.body(); // read the whole request now (it is small): the connection leaves the "request" state
                } catch (ApiException e) {
                    call.respond(e.status(), Map.of("error", String.valueOf(e.getMessage())));
                    return;
                } catch (IOException e) {
                    call.respond(400, Map.of("error", "cannot read the request body"));
                    return;
                }
                if (!authorized(exchange)) {
                    call.respond(401, Map.of("error", "unauthorized: missing or invalid X-Vibe-Token header"));
                    return;
                }
                CompletableFuture<Resp> future;
                try {
                    future = route(exchange.getRequestMethod().toUpperCase(Locale.ROOT), segments(path), call);
                } catch (Throwable t) {
                    future = CompletableFuture.failedFuture(t);
                }
                CompletableFuture<Resp> f = future;
                if (f.isDone()) {
                    finish(call, f);
                } else {
                    pending.add(call);
                    f.whenCompleteAsync((r, t) -> finish(call, f), executor);
                }
            } else {
                serveStatic(call, path);
            }
        } catch (Throwable t) {
            call.respond(500, Map.of("error", "internal error: " + t));
        }
    }

    private void finish(Call call, CompletableFuture<Resp> future) {
        pending.remove(call);
        try {
            Resp r = future.join();
            call.respond(r.status(), r.body());
        } catch (Throwable t) {
            Throwable c = unwrap(t);
            if (c instanceof ApiException ae) {
                call.respond(ae.status(), Map.of("error", String.valueOf(ae.getMessage())));
            } else if (c instanceof TimeoutException) {
                call.respond(504, Map.of("error", "timed out waiting for the server"));
            } else if (c instanceof UnsupportedOperationException) {
                call.respond(501, Map.of("error", "not implemented: " + c.getMessage()));
            } else {
                logError(call, c);
                call.respond(500, Map.of("error", "internal error: " + c.getClass().getSimpleName()
                        + (c.getMessage() == null ? "" : ": " + c.getMessage())));
            }
        }
    }

    private void logError(Call call, Throwable t) {
        String key = t.getClass().getName();
        long now = System.currentTimeMillis();
        Long last = errorLogTimes.get(key);
        if (last == null || now - last > 10_000) {
            errorLogTimes.put(key, now);
            plugin.getLogger().log(Level.WARNING, "API error on " + call.exchange.getRequestMethod() + " "
                    + call.exchange.getRequestURI().getPath(), t);
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    /** Host names the API answers to (anything else: DNS rebinding). */
    private boolean allowedHostName(String h) {
        if (h == null) {
            return false;
        }
        String x = h.toLowerCase(Locale.ROOT);
        if (x.equals("127.0.0.1") || x.equals("localhost") || x.equals("[::1]") || x.equals("::1")) {
            return true;
        }
        String cfg = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        return !cfg.isEmpty() && !cfg.equals("0.0.0.0") && !cfg.equals("::") && (x.equals(cfg) || x.equals("[" + cfg + "]"));
    }

    private static String hostPart(String hostHeader) {
        String h = hostHeader.strip();
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(0, end + 1) : h;
        }
        int colon = h.lastIndexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }

    /**
     * Cross-site browser requests and DNS rebinding are refused: a foreign {@code Host} header, or an
     * {@code Origin} header that is not this server (curl and the agents send no Origin, the dashboard sends
     * its own). Returns the reason, or null if the request may go on.
     */
    private String refusedOrigin(HttpExchange exchange) {
        Headers h = exchange.getRequestHeaders();
        String hostHeader = h.getFirst("Host");
        if (hostHeader != null && !hostHeader.isBlank() && !allowedHostName(hostPart(hostHeader))) {
            return "forbidden host '" + Msg.cut(hostHeader, 60) + "': use http://127.0.0.1:" + port + "/";
        }
        // Fetch metadata (sent by every current browser, never by curl): a request made by another site's page
        // (or by another port of localhost, which browsers count as "same-site") is refused even when the
        // browser sends no Origin (simple GET / <img> / <script> requests)
        String site = h.getFirst("Sec-Fetch-Site");
        if (site != null && (site.equalsIgnoreCase("cross-site") || site.equalsIgnoreCase("same-site"))) {
            return "cross-site request refused (Sec-Fetch-Site: " + Msg.cut(site, 20) + ")";
        }
        String origin = h.getFirst("Origin");
        if (origin != null) {
            String o = origin.strip().toLowerCase(Locale.ROOT);
            boolean ok = false;
            if (o.startsWith("http://")) {
                String rest = o.substring("http://".length());
                if (rest.endsWith("/")) {
                    rest = rest.substring(0, rest.length() - 1);
                }
                ok = rest.endsWith(":" + port) && allowedHostName(hostPart(rest));
            }
            if (!ok) {
                return "cross-origin request refused (Origin " + Msg.cut(origin, 60) + ")";
            }
        }
        return null;
    }

    private boolean authorized(HttpExchange exchange) {
        if (token.length == 0) {
            return true;
        }
        String given = exchange.getRequestHeaders().getFirst("X-Vibe-Token");
        if (given == null) {
            return false;
        }
        return MessageDigest.isEqual(token, given.strip().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        for (String s : path.split("/")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ routing

    private CompletableFuture<Resp> route(String method, List<String> seg, Call call) throws IOException {
        // seg.get(0) == "api"
        int n = seg.size();
        if (n == 1) {
            return ok(Map.of("name", "VibeCraft API", "endpoints", "see docs/ARCHITECTURE.md section 5"));
        }
        CompletableFuture<Resp> ext = extension(method, seg, call);
        if (ext != null) {
            return ext;
        }
        String area = seg.get(1);
        switch (area) {
            case "status" -> {
                if (n == 2) {
                    requireMethod(method, "GET");
                    return ok(status());
                }
            }
            case "modules" -> {
                return routeModules(method, seg, call);
            }
            case "command" -> {
                if (n == 2) {
                    requireMethod(method, "POST");
                    return command(call.body());
                }
            }
            case "players" -> {
                if (n == 2) {
                    requireMethod(method, "GET");
                    return callSync(this::players, SYNC_TIMEOUT_S).thenApply(p -> new Resp(200, p));
                }
            }
            case "ai" -> {
                return routeAi(method, seg, call);
            }
            case "ideas" -> {
                return routeIdeas(method, seg, call);
            }
            default -> {
            }
        }
        throw new ApiException(404, "no such endpoint: " + method + " /" + String.join("/", seg));
    }

    /** Routes contributed through {@link ApiExtensions} (resource pack, textures...), or null. */
    private CompletableFuture<Resp> extension(String method, List<String> seg, Call call) {
        CompletableFuture<ApiExtensions.Reply> f;
        try {
            f = ApiExtensions.dispatch(new ApiExtensions.Request(method, List.copyOf(seg), call.query(), call::body));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        return f == null ? null : f.thenApply(r -> new Resp(r.status(), r.body()));
    }

    private CompletableFuture<Resp> routeModules(String method, List<String> seg, Call call) throws IOException {
        int n = seg.size();
        if (n == 2) {
            requireMethod(method, "GET");
            return ok(modules.list());
        }
        String id = validId(seg.get(2));
        if (n == 3) {
            requireMethod(method, "GET");
            ModuleInfo info = modules.info(id).orElseThrow(() -> unknownModule(id));
            return ok(info);
        }
        if (n == 4) {
            String action = seg.get(3);
            switch (action) {
                case "reload" -> {
                    requireMethod(method, "POST");
                    requireModule(id);
                    String force = call.query().get("force");
                    boolean forced = force != null && (force.isEmpty() || force.equals("1") || force.equalsIgnoreCase("true"));
                    CompletableFuture<dev.vibecraft.core.ReloadResult> f = forced
                            && modules instanceof dev.vibecraft.core.ModuleManagerImpl impl ? impl.reload(id, true) : modules.reload(id);
                    return f.copy().orTimeout(RELOAD_TIMEOUT_S, TimeUnit.SECONDS).thenApply(r -> new Resp(200, r));
                }
                case "enable" -> {
                    requireMethod(method, "POST");
                    requireModule(id);
                    return modules.enable(id).copy().orTimeout(RELOAD_TIMEOUT_S, TimeUnit.SECONDS)
                            .thenApply(r -> new Resp(200, r));
                }
                case "disable" -> {
                    requireMethod(method, "POST");
                    requireModule(id);
                    // the emergency stop must also stop the AI working on the module, or it reloads / re-enables it
                    boolean aiStopped = ai.stop(id);
                    return modules.disable(id).copy().orTimeout(DISABLE_TIMEOUT_S, TimeUnit.SECONDS)
                            .thenApply(okFlag -> new Resp(200, Map.of("ok", Boolean.TRUE.equals(okFlag), "aiStopped", aiStopped)));
                }
                case "create" -> {
                    requireMethod(method, "POST");
                    String description = Msg.cut(call.body(), 300);
                    boolean created = modules.create(id, description);
                    if (created && !autoReload) {
                        // no watcher to pick it up: start the first load ourselves (not awaited)
                        try {
                            modules.reload(id);
                        } catch (RuntimeException e) {
                            plugin.getLogger().warning("API create: first load of " + id + " failed: " + e);
                        }
                    }
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("ok", true);
                    body.put("created", created);
                    return ok(body);
                }
                case "logs" -> {
                    requireMethod(method, "GET");
                    requireModule(id);
                    int limit = intParam("limit", call.query().get("limit"), 200, 1, 5000);
                    return ok(Map.of("lines", modules.logs(id, limit)));
                }
                default -> {
                }
            }
        }
        throw new ApiException(404, "no such endpoint: " + method + " /" + String.join("/", seg));
    }

    private CompletableFuture<Resp> routeAi(String method, List<String> seg, Call call) throws IOException {
        int n = seg.size();
        if (n == 2) {
            requireMethod(method, "GET");
            return ok(ai.jobs());
        }
        String id = validId(seg.get(2));
        if (n == 3) {
            if (method.equals("GET")) {
                AiJob job = ai.job(id).orElseThrow(() -> new ApiException(404, "no AI job for module '" + id + "'"));
                return ok(job);
            }
            requireMethod(method, "POST");
            String by = Optional.ofNullable(call.query().get("by")).map(s -> Msg.cut(s, 32)).filter(s -> !s.isBlank())
                    .orElse("dashboard");
            return startJob(id, call.body(), by, 201);
        }
        if (n == 4) {
            switch (seg.get(3)) {
                case "stop" -> {
                    requireMethod(method, "POST");
                    return ok(Map.of("ok", ai.stop(id)));
                }
                case "reset" -> {
                    requireMethod(method, "POST");
                    if (ai.job(id).map(AiJob::state).filter(s -> s == AiJobState.RUNNING || s == AiJobState.QUEUED).isPresent()) {
                        // same rule as DELETE /api/conversations/{id}: the running job would go on with a forgotten session
                        throw new ApiException(409, "une IA travaille encore sur " + id + " : arrêtez-la d'abord");
                    }
                    ai.resetConversation(id);
                    return ok(Map.of("ok", true));
                }
                default -> {
                }
            }
        }
        throw new ApiException(404, "no such endpoint: " + method + " /" + String.join("/", seg));
    }

    private CompletableFuture<Resp> startJob(String id, String rawPrompt, String by, int status) {
        String prompt = rawPrompt == null ? "" : rawPrompt.strip();
        if (prompt.isEmpty()) {
            throw new ApiException(400, "empty prompt: send the request as the POST body");
        }
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            throw new ApiException(413, "prompt too long (max " + MAX_PROMPT_LENGTH + " chars)");
        }
        if (!ai.available()) {
            throw new ApiException(503, "AI unavailable: " + ai.unavailableReason());
        }
        try {
            return CompletableFuture.completedFuture(new Resp(status, ai.start(id, prompt, by)));
        } catch (IllegalStateException e) {
            if (!ai.available()) {
                throw new ApiException(503, "AI unavailable: " + ai.unavailableReason());
            }
            throw new ApiException(409, e.getMessage() == null ? "a job is already running for '" + id + "'" : e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, String.valueOf(e.getMessage()));
        }
    }

    private CompletableFuture<Resp> routeIdeas(String method, List<String> seg, Call call) throws IOException {
        int n = seg.size();
        if (n == 2) {
            if (method.equals("GET")) {
                return ok(ideas.list());
            }
            requireMethod(method, "POST");
            String text = call.body().strip();
            if (text.isEmpty()) {
                throw new ApiException(400, "empty idea: send the text as the POST body");
            }
            if (text.length() > MAX_IDEA_LENGTH) {
                throw new ApiException(413, "idea too long (max " + MAX_IDEA_LENGTH + " chars)");
            }
            String author = Optional.ofNullable(call.query().get("author")).map(s -> Msg.cut(s, 32))
                    .filter(s -> !s.isBlank()).orElse("dashboard");
            return ok(ideas.add(author, text));
        }
        if (n == 3) {
            requireMethod(method, "GET");
            int ideaId = ideaNumber(seg.get(2));
            return ok(ideas.get(ideaId).orElseThrow(() -> new ApiException(404, "unknown idea #" + ideaId)));
        }
        if (n == 4) {
            int ideaId = ideaNumber(seg.get(2));
            Idea idea =ideas.get(ideaId).orElseThrow(() -> new ApiException(404, "unknown idea #" + ideaId));
            switch (seg.get(3)) {
                case "accept" -> {
                    requireMethod(method, "POST");
                    if ("ACCEPTED".equals(idea.status())) {
                        throw new ApiException(409, "idea #" + ideaId + " already accepted (module '" + idea.moduleId() + "')");
                    }
                    String module = call.query().get("module");
                    String id;
                    if (module == null || module.isBlank()) {
                        // a fresh module: an idea worded like an existing module's name must not make the AI rewrite it
                        id = freeModuleId(Slug.of(idea.text(), "idee-" + ideaId));
                    } else {
                        id = validId(module.strip());
                    }
                    CompletableFuture<Resp> started = startJob(id, idea.text(), idea.author(), 200);
                    ideas.accept(ideaId, id);
                    return started;
                }
                case "dismiss" -> {
                    requireMethod(method, "POST");
                    return ok(ideas.dismiss(ideaId).orElseThrow(() -> new ApiException(404, "unknown idea #" + ideaId)));
                }
                default -> {
                }
            }
        }
        throw new ApiException(404, "no such endpoint: " + method + " /" + String.join("/", seg));
    }

    /** {@code base}, or {@code base-2}, {@code base-3}... if a module with that id already exists. */
    private String freeModuleId(String base) {
        String b = validId(base);
        for (int i = 1; i < 100; i++) {
            String suffix = i == 1 ? "" : "-" + i;
            String id = (b.length() + suffix.length() > 40 ? b.substring(0, 40 - suffix.length()) : b) + suffix;
            if (modules.info(id).isEmpty() && !new File(modules.modulesDir(), id).exists()) {
                return id;
            }
        }
        return b;
    }

    private static int ideaNumber(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "invalid idea number '" + Msg.cut(raw, 20) + "'");
        }
    }

    // ------------------------------------------------------------------ endpoints

    private Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("server", serverJson());
        out.put("modules", safe(modules::list, List.of(), "modules.list"));
        out.put("jobs", safe(ai::jobs, List.of(), "ai.jobs"));
        out.put("ideas", safe(ideas::list, List.of(), "ideas.list"));
        Map<String, Object> aiInfo = new LinkedHashMap<>();
        boolean available = safe(ai::available, false, "ai.available");
        aiInfo.put("available", available);
        aiInfo.put("reason", available ? "" : safe(ai::unavailableReason, "unknown", "ai.unavailableReason"));
        out.put("ai", aiInfo);
        return out;
    }

    private <T> T safe(Supplier<T> s, T fallback, String what) {
        try {
            T v = s.get();
            return v == null ? fallback : v;
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            Long last = errorLogTimes.get(what);
            if (last == null || now - last > 30_000) {
                errorLogTimes.put(what, now);
                plugin.getLogger().warning("API /status: " + what + " failed: " + t);
            }
            return fallback;
        }
    }

    private Map<String, Object> serverJson() {
        ServerSnapshot s = snapshot;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s == null ? "" : s.name());
        m.put("version", s == null ? "" : s.version());
        m.put("online", s == null ? 0 : s.online());
        m.put("max", s == null ? 0 : s.max());
        m.put("tps", s == null ? new double[] {0, 0, 0} : s.tps());
        m.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        m.put("mspt", s == null ? 0 : s.mspt());
        m.put("fullVersion", s == null ? "" : s.fullVersion());
        return m;
    }

    /** Main thread only. */
    private void refreshSnapshot() {
        try {
            var srv = plugin.getServer();
            double[] raw = srv.getTPS();
            double[] tps = new double[3];
            for (int i = 0; i < 3 && i < raw.length; i++) {
                tps[i] = Math.round(Math.min(raw[i], 20.0) * 100.0) / 100.0;
            }
            snapshot = new ServerSnapshot(srv.getName(), srv.getMinecraftVersion(), srv.getVersion(),
                    srv.getOnlinePlayers().size(), srv.getMaxPlayers(), tps,
                    Math.round(srv.getAverageTickTime() * 100.0) / 100.0);
        } catch (Throwable t) {
            plugin.getLogger().fine("API snapshot failed: " + t);
        }
    }

    /** Main thread only. */
    private List<Map<String, Object>> players() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            Location l = p.getLocation();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("uuid", p.getUniqueId().toString());
            m.put("world", l.getWorld() == null ? null : l.getWorld().getName());
            m.put("x", round2(l.getX()));
            m.put("y", round2(l.getY()));
            m.put("z", round2(l.getZ()));
            m.put("gameMode", p.getGameMode().name());
            m.put("health", round2(p.getHealth()));
            m.put("op", p.isOp());
            out.add(m);
        }
        return out;
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    /** Runs a console command on the main thread, capturing its feedback as plain text. */
    private CompletableFuture<Resp> command(String body) {
        String line = body.strip();
        while (line.startsWith("/")) {
            line = line.substring(1).strip();
        }
        if (line.isEmpty()) {
            throw new ApiException(400, "empty command: send the command line as the POST body");
        }
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new ApiException(400, "one command per request (no line breaks)");
        }
        String forbidden = dev.vibecraft.core.guard.Policy.forbiddenCommandLine(line, false);
        if (forbidden != null) {
            plugin.getLogger().warning("API command refused: /" + Msg.cut(line, 120) + " (" + forbidden + ")");
            throw new ApiException(403, forbidden + " (use the server console for administration)");
        }
        final String cmd = line;
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Resp> result = new CompletableFuture<>();
        runSync(result, () -> {
            CommandSender sender = plugin.getServer().createCommandSender(c -> addLines(output, Msg.plain(c)));
            boolean ok;
            try {
                plugin.getLogger().info("API command: /" + cmd);
                ok = plugin.getServer().dispatchCommand(sender, cmd);
            } catch (Throwable t) {
                ok = false;
                Throwable c = t.getCause() != null ? t.getCause() : t;
                output.add("error: " + (c.getMessage() != null ? c.getMessage() : c.toString()));
            }
            if (!ok && output.isEmpty()) {
                String label = cmd.split("\\s+", 2)[0];
                try {
                    if (plugin.getServer().getCommandMap().getCommand(label) == null) {
                        output.add("Unknown command: /" + label);
                    }
                } catch (RuntimeException ignored) {
                    // no command map access
                }
            }
            boolean finalOk = ok;
            // give the command one more tick to send late feedback
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", finalOk);
                synchronized (output) {
                    m.put("output", List.copyOf(output));
                }
                result.complete(new Resp(200, m));
            }, 1L);
        });
        return result.orTimeout(COMMAND_TIMEOUT_S, TimeUnit.SECONDS);
    }

    private static void addLines(List<String> out, String text) {
        if (out.size() > 2000) {
            return;
        }
        for (String l : text.split("\\R", -1)) {
            out.add(l);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Run {@code task} on the main thread; {@code target} fails with 503 if that is impossible. Skipped if target already completed (timeout). */
    private void runSync(CompletableFuture<?> target, Runnable task) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (target.isDone()) {
                    return;
                }
                try {
                    task.run();
                } catch (Throwable t) {
                    target.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            target.completeExceptionally(new ApiException(503, "server is not accepting tasks (stopping?)"));
        }
    }

    private <T> CompletableFuture<T> callSync(Supplier<T> supplier, long timeoutS) {
        CompletableFuture<T> cf = new CompletableFuture<>();
        runSync(cf, () -> cf.complete(supplier.get()));
        return cf.orTimeout(timeoutS, TimeUnit.SECONDS);
    }

    private static CompletableFuture<Resp> ok(Object body) {
        return CompletableFuture.completedFuture(new Resp(200, body));
    }

    private static void requireMethod(String method, String expected) {
        if (!method.equals(expected)) {
            throw new ApiException(405, "method " + method + " not allowed here (use " + expected + ")");
        }
    }

    private String validId(String id) {
        if (!modules.isValidId(id)) {
            throw new ApiException(400, "invalid module id '" + Msg.cut(id, 60) + "': must match [a-z0-9][a-z0-9_-]{0,39}");
        }
        return id;
    }

    private ApiException unknownModule(String id) {
        return new ApiException(404, "unknown module '" + id + "'");
    }

    /** 404 unless the manager knows the module or its directory exists (an AI may call reload before the watcher saw it). */
    private void requireModule(String id) {
        if (modules.info(id).isPresent()) {
            return;
        }
        try {
            File dir = new File(modules.modulesDir(), id);
            if (dir.isDirectory()) {
                return;
            }
        } catch (RuntimeException ignored) {
            // modulesDir unavailable
        }
        throw unknownModule(id);
    }

    /** {@code def} if absent; 400 if not a number or below {@code min}; capped at {@code max}. */
    private static int intParam(String name, String raw, int def, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        BigInteger v;
        try {
            v = new BigInteger(raw.strip());
        } catch (NumberFormatException e) {
            throw new ApiException(400, "invalid " + name + " '" + Msg.cut(raw, 20) + "': expected a whole number");
        }
        if (v.compareTo(BigInteger.valueOf(min)) < 0) {
            throw new ApiException(400, "invalid " + name + " " + Msg.cut(v.toString(), 20) + ": must be at least " + min);
        }
        return v.compareTo(BigInteger.valueOf(max)) > 0 ? max : v.intValue();
    }

    /**
     * The request body as text: in the charset of the {@code Content-Type} header if one is declared (e.g. Windows
     * PowerShell 5.1 sends Latin-1), else UTF-8. Bytes that are not valid in that charset are a 400, never silently
     * replaced by U+FFFD (the AI would get a corrupted request).
     */
    static String decode(byte[] bytes, String contentType) {
        Charset cs = StandardCharsets.UTF_8;
        String declared = charsetOf(contentType);
        if (declared != null) {
            try {
                cs = Charset.forName(declared);
            } catch (IllegalArgumentException e) {
                throw new ApiException(415, "unsupported charset '" + Msg.cut(declared, 30) + "' in Content-Type: send UTF-8");
            }
        }
        try {
            return cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new ApiException(400, declared == null
                    ? "request body is not valid UTF-8: encode it in UTF-8, or declare its charset"
                            + " (Content-Type: application/json; charset=...)"
                    : "request body is not valid " + cs.name() + " (charset declared in Content-Type)");
        }
    }

    /** The {@code charset} parameter of a Content-Type header, or null. */
    private static String charsetOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String p = part.strip();
            if (p.regionMatches(true, 0, "charset=", 0, 8)) {
                String v = p.substring(8).strip();
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1).strip();
                }
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ static files

    private void serveStatic(Call call, String path) {
        String method = call.exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("HEAD")) {
            call.respond(405, Map.of("error", "method " + method + " not allowed"));
            return;
        }
        String rel = path.startsWith("/") ? path.substring(1) : path;
        if (rel.isEmpty() || rel.endsWith("/")) {
            rel = rel + "index.html";
        }
        if (rel.contains("..") || rel.contains("\\") || rel.contains(":") || rel.startsWith("/")) {
            call.respond(404, Map.of("error", "not found"));
            return;
        }
        byte[] data;
        try (InputStream in = plugin.getResource("web/" + rel)) {
            if (in == null) {
                call.respond(404, Map.of("error", "not found: /" + rel));
                return;
            }
            data = in.readAllBytes();
        } catch (IOException e) {
            call.respond(500, Map.of("error", "cannot read /" + rel));
            return;
        }
        call.respondBytes(200, contentType(rel), data, method.equals("HEAD"));
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        return switch (ext) {
            case "html", "htm" -> "text/html; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "ico" -> "image/x-icon";
            case "woff2" -> "font/woff2";
            case "woff" -> "font/woff";
            case "txt", "md" -> "text/plain; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    // ------------------------------------------------------------------ types

    private record Resp(int status, Object body) {
    }

    private record ServerSnapshot(String name, String version, String fullVersion, int online, int max,
                                  double[] tps, double mspt) {
    }

    /** One exchange; guarantees exactly one response. */
    private final class Call {
        final HttpExchange exchange;
        private final AtomicBoolean done = new AtomicBoolean();
        private String body;
        private Map<String, String> query;

        Call(HttpExchange exchange) {
            this.exchange = exchange;
        }

        String body() throws IOException {
            if (body == null) {
                try (InputStream in = exchange.getRequestBody()) {
                    byte[] bytes = in.readNBytes(MAX_BODY + 1);
                    if (bytes.length > MAX_BODY) {
                        throw new ApiException(413, "request body too large (max " + MAX_BODY + " bytes)");
                    }
                    String text = decode(bytes, exchange.getRequestHeaders().getFirst("Content-Type"));
                    body = text.startsWith("﻿") ? text.substring(1) : text;
                }
            }
            return body;
        }

        Map<String, String> query() {
            if (query == null) {
                Map<String, String> q = new HashMap<>();
                String raw = exchange.getRequestURI().getRawQuery();
                if (raw != null && !raw.isEmpty()) {
                    for (String part : raw.split("&")) {
                        if (part.isEmpty()) {
                            continue;
                        }
                        int eq = part.indexOf('=');
                        try {
                            String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
                            String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                            q.putIfAbsent(k, v);
                        } catch (IllegalArgumentException e) {
                            throw new ApiException(400, "malformed query string");
                        }
                    }
                }
                query = q;
            }
            return query;
        }

        void respond(int status, Object json) {
            byte[] bytes;
            try {
                bytes = Json.GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "API: cannot serialize response", t);
                status = 500;
                bytes = "{\"error\":\"internal error: cannot serialize response\"}".getBytes(StandardCharsets.UTF_8);
            }
            respondBytes(status, "application/json; charset=utf-8", bytes, false);
        }

        void respondBytes(int status, String contentType, byte[] bytes, boolean headOnly) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            pending.remove(this);
            try {
                Headers h = exchange.getResponseHeaders();
                h.set("Content-Type", contentType);
                h.set("Cache-Control", "no-store");
                h.set("X-Content-Type-Options", "nosniff");
                if (status == 405) {
                    h.set("Allow", "GET, POST");
                }
                if (headOnly) {
                    exchange.sendResponseHeaders(status, -1);
                } else {
                    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                    if (bytes.length > 0) {
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(bytes);
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                // client went away
            } finally {
                exchange.close();
            }
        }
    }
}

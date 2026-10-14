package dev.vibecraft.pack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.vibecraft.core.ModuleEvent;
import dev.vibecraft.core.ModuleInfo;
import dev.vibecraft.core.ModuleManager;
import dev.vibecraft.core.ModuleState;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Hot resource pack: merges the {@code assets/} of every running module (see {@link PackBuilder}),
 * serves the zip over HTTP ({@code resource-pack.port}) and pushes it to every player on join and
 * whenever its content changes (asset edit, module reload/disable). One stable pack UUID, so a new
 * version replaces the previous one on the client, which reloads its resources in place (no
 * disconnect). Builds run on a background thread; only the push touches Bukkit (main thread).
 */
public final class PackService implements Listener {

    /** Stable id of the VibeCraft pack on clients (new versions replace the old one). */
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("vibecraft:hot-resource-pack".getBytes(StandardCharsets.UTF_8));

    private static final int KEEP_BUILDS = 4;

    private final JavaPlugin plugin;
    private final ModuleManager modules;
    private final Logger log;
    private final BiConsumer<String, String> moduleLog; // (module, line) -> module log ring

    private final boolean enabled;
    private final String bind;
    private final int port;
    private final String publicHost;
    private final boolean required;
    private final Component prompt;
    private final long debounceMs;
    private final int minFormat;
    private final int maxFormat;

    private ScheduledExecutorService exec;
    private HttpServer http;
    private ThreadPoolExecutor httpPool;
    private volatile String lanIp = "127.0.0.1";

    // build state (exec thread)
    private String observedFp = "";
    private long observedSince;
    private String builtFp = null;
    private final Map<String, String> lastIssueText = new TreeMap<>();

    private volatile PackBuilder.Result current;
    private volatile long builtAt;
    private volatile long buildMs;
    private volatile int pushes;
    private final Map<String, byte[]> served = Collections.synchronizedMap(new LinkedHashMap<>(8, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > KEEP_BUILDS;
        }
    });
    private final Map<UUID, String> playerStatus = new ConcurrentHashMap<>();
    private volatile String pushedSha = "";

    public PackService(JavaPlugin plugin, ModuleManager modules, BiConsumer<String, String> moduleLog) {
        this.plugin = plugin;
        this.modules = modules;
        this.log = plugin.getLogger();
        this.moduleLog = moduleLog;
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("resource-pack");
        this.enabled = c == null || c.getBoolean("enabled", true);
        this.bind = c == null ? "0.0.0.0" : c.getString("bind", "0.0.0.0");
        this.port = c == null ? 8766 : c.getInt("port", 8766);
        this.publicHost = c == null ? "auto" : c.getString("public-host", "auto").strip();
        this.required = c != null && c.getBoolean("required", false);
        String p = c == null ? null : c.getString("prompt");
        this.prompt = MiniMessage.miniMessage().deserialize(p == null || p.isBlank()
                ? "<gold>VibeCraft</gold> <gray>:</gray> <white>textures, modèles 3D et interfaces créés en direct par l'IA. "
                        + "<yellow>Acceptez pour les voir !"
                : p);
        this.debounceMs = c == null ? 1000 : Math.max(200, c.getLong("debounce-ms", 1000));
        int[] fmt = detectFormat();
        int cfgMin = c == null ? 0 : c.getInt("min-format", 0);
        int cfgMax = c == null ? 0 : c.getInt("max-format", 0);
        this.minFormat = cfgMin > 0 ? cfgMin : fmt[0];
        this.maxFormat = cfgMax > 0 ? cfgMax : fmt[0];
    }

    public boolean enabled() {
        return enabled;
    }

    // ================================================================== lifecycle

    public void start() {
        if (!enabled) {
            log.info("Resource pack: disabled (resource-pack.enabled=false)");
            return;
        }
        lanIp = detectLanIp();
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VibeCraft-pack");
            t.setDaemon(true);
            return t;
        });
        try {
            startHttp();
        } catch (IOException e) {
            log.severe("Resource pack: cannot bind " + bind + ":" + port + " (" + e.getMessage()
                    + "): players will not get module assets. Change resource-pack.port.");
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        modules.addListener(this::onModuleEvent);
        exec.scheduleWithFixedDelay(this::poll, 1500, 500, TimeUnit.MILLISECONDS);
        log.info("Resource pack: serving module assets on http://" + (publicHost.equals("auto") ? lanIp + " (auto)" : publicHost)
                + ":" + port + "/pack.zip (pack format " + minFormat + (maxFormat != minFormat ? "-" + maxFormat : "") + ")");
    }

    public void stop() {
        HandlerList.unregisterAll(this);
        if (exec != null) {
            exec.shutdownNow();
        }
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (httpPool != null) {
            httpPool.shutdownNow();
        }
    }

    private void onModuleEvent(ModuleEvent e) {
        // the set of running modules may have changed: re-check now (still debounced)
        ScheduledExecutorService ex = exec;
        if (ex != null && !ex.isShutdown()) {
            try {
                ex.execute(this::poll);
            } catch (RuntimeException ignored) {
                // shutting down
            }
        }
    }

    // ================================================================== building

    private void poll() {
        try {
            String fp = fingerprint();
            long now = System.currentTimeMillis();
            if (!fp.equals(observedFp)) {
                observedFp = fp;
                observedSince = now;
                return;
            }
            if (fp.equals(builtFp) || now - observedSince < debounceMs) {
                return;
            }
            rebuild(fp);
        } catch (Throwable t) {
            log.log(Level.WARNING, "Resource pack: build failed", t);
            builtFp = observedFp; // do not retry in a loop; the next change retries
        }
    }

    /** Rebuild now (off the main thread) and push if it changed. Completes with the status. */
    public CompletableFuture<Map<String, Object>> rebuildNow() {
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        if (exec == null) {
            f.complete(status());
            return f;
        }
        exec.execute(() -> {
            try {
                String fp = fingerprint();
                observedFp = fp;
                observedSince = 0;
                if (!fp.equals(builtFp)) {
                    rebuild(fp);
                }
                f.complete(status());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /** Ask for a rebuild check soon (e.g. after a texture was generated). */
    public void poke() {
        onModuleEvent(null);
    }

    private List<PackBuilder.Input> inputs() {
        List<PackBuilder.Input> in = new ArrayList<>();
        File root = modules.modulesDir();
        for (ModuleInfo m : modules.list()) {
            boolean running = m.state() == ModuleState.ENABLED || (m.state() == ModuleState.COMPILING && m.generation() > 0);
            if (running) {
                in.add(new PackBuilder.Input(m.id(), new File(root, m.id())));
            }
        }
        return in;
    }

    private String fingerprint() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (PackBuilder.Input in : inputs()) {
            Path assets = new File(in.moduleDir(), "assets").toPath();
            sb.append('#').append(in.moduleId()).append('\n');
            if (!Files.isDirectory(assets)) {
                continue;
            }
            try (var s = Files.walk(assets)) {
                List<String> lines = new ArrayList<>();
                s.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        lines.add(assets.relativize(p) + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis());
                    } catch (IOException ignored) {
                        // vanished
                    }
                });
                Collections.sort(lines);
                lines.forEach(l -> sb.append(l).append('\n'));
            } catch (IOException | java.io.UncheckedIOException e) {
                sb.append("error\n");
            }
        }
        return sb.toString();
    }

    private void rebuild(String fp) {
        long t0 = System.nanoTime();
        List<PackBuilder.Input> in = inputs();
        PackBuilder.Result r = PackBuilder.build(in, minFormat, maxFormat, "VibeCraft : contenu créé en direct");
        builtFp = fp;
        buildMs = (System.nanoTime() - t0) / 1_000_000;
        builtAt = System.currentTimeMillis();
        PackBuilder.Result prev = current;
        current = r;
        ModelRegistry.update(r.models());
        if (!r.empty()) {
            served.put(r.sha1(), r.zip());
        }
        writePreviews(in, r);
        reportIssues(in, r);
        boolean changed = prev == null ? !r.empty() : !prev.sha1().equals(r.sha1());
        if (changed) {
            log.info("Resource pack: " + (r.empty() ? "no module assets" : r.files() + " file(s) from " + r.modules()
                    + ", " + r.zip().length / 1024 + " KB, sha1 " + r.sha1().substring(0, 10)) + " (" + buildMs + " ms)");
            Bukkit.getScheduler().runTask(plugin, this::pushAll);
        }
    }

    /** "car:sports_car" -> "car_sports_car.png" (relative to modules/<id>/.vibe/previews/). */
    static String previewName(String id) {
        return id.replace(':', '_').replace('/', '_') + ".png";
    }

    private final Map<String, String> previewHashes = new ConcurrentHashMap<>();

    private void writePreviews(List<PackBuilder.Input> in, PackBuilder.Result r) {
        for (dev.vibecraft.pack.mesh.MeshCompiler.ObjectInfo o : r.models()) {
            byte[] png = r.previews().get(o.id());
            if (png == null) {
                continue;
            }
            for (PackBuilder.Input m : in) {
                if (!m.moduleId().equals(o.module())) {
                    continue;
                }
                File f = new File(m.moduleDir(), ".vibe/previews/" + previewName(o.id()));
                String h = PackBuilder.sha1Hex(png);
                if (h.equals(previewHashes.get(f.getPath())) && f.isFile()) {
                    continue;
                }
                try {
                    Files.createDirectories(f.getParentFile().toPath());
                    Path tmp = new File(f.getParentFile(), "." + f.getName() + ".tmp").toPath();
                    Files.write(tmp, png);
                    Files.move(tmp, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    previewHashes.put(f.getPath(), h);
                } catch (IOException ignored) {
                    // module directory vanished
                }
            }
        }
    }

    private void reportIssues(List<PackBuilder.Input> in, PackBuilder.Result r) {
        for (PackBuilder.Input m : in) {
            List<PackBuilder.Issue> issues = r.issuesOf(m.moduleId());
            boolean hasAssets = new File(m.moduleDir(), "assets").isDirectory();
            StringBuilder sb = new StringBuilder();
            for (PackBuilder.Issue i : issues) {
                sb.append(i).append('\n');
            }
            for (dev.vibecraft.pack.mesh.MeshCompiler.ObjectInfo o : r.models()) {
                if (o.module().equals(m.moduleId())) {
                    sb.append("3D ").append(o.id()).append(": ").append(o.elements()).append(" elements, ")
                            .append(o.parts().size()).append(" part(s), atlas ").append(o.atlasWidth()).append('x')
                            .append(o.atlasHeight()).append(", mode ").append(o.mode()).append(", preview .vibe/previews/")
                            .append(previewName(o.id())).append('\n');
                }
            }
            String text = sb.toString();
            String prev = lastIssueText.put(m.moduleId(), text);
            if (text.equals(prev) || (!hasAssets && issues.isEmpty())) {
                continue;
            }
            File vibe = new File(m.moduleDir(), ".vibe");
            try {
                vibe.mkdirs();
                Path tmp = new File(vibe, "assets.log.tmp").toPath();
                String header = "# Resource pack check of modules/" + m.moduleId() + "/assets (" + new java.util.Date() + ")\n"
                        + (issues.isEmpty() ? "OK: no problem found\n" : "");
                Files.writeString(tmp, header + text, StandardCharsets.UTF_8);
                Files.move(tmp, new File(vibe, "assets.log").toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // module directory vanished
            }
            int n = 0;
            for (PackBuilder.Issue i : issues) {
                if (n++ >= 20) {
                    moduleLog.accept(m.moduleId(), "WARNING assets: ... (" + (issues.size() - 20) + " more in .vibe/assets.log)");
                    break;
                }
                moduleLog.accept(m.moduleId(), i.level() + " assets: " + (i.file().isEmpty() ? "" : i.file() + ": ") + i.message());
            }
            if (issues.isEmpty()) {
                moduleLog.accept(m.moduleId(), "INFO assets: OK (resource pack rebuilt)");
            }
            for (String line : text.split("\n")) {
                if (line.startsWith("3D ")) {
                    moduleLog.accept(m.moduleId(), "INFO assets: " + line);
                }
            }
        }
    }

    // ================================================================== pushing (main thread)

    private void pushAll() {
        PackBuilder.Result r = current;
        if (r == null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            push(p, r);
        }
        pushedSha = r.empty() ? "" : r.sha1();
    }

    private void push(Player p, PackBuilder.Result r) {
        try {
            if (r.empty()) {
                if (!pushedSha.isEmpty()) {
                    p.removeResourcePacks(PACK_ID);
                    playerStatus.remove(p.getUniqueId());
                }
                return;
            }
            URI uri = URI.create("http://" + hostFor(p) + ":" + port + "/pack/" + r.sha1() + ".zip");
            ResourcePackRequest req = ResourcePackRequest.resourcePackRequest()
                    .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, uri, r.sha1()))
                    .required(required)
                    .prompt(prompt)
                    .replace(false)
                    .build();
            p.sendResourcePacks(req);
            playerStatus.put(p.getUniqueId(), "SENT " + r.sha1().substring(0, 10));
            pushes++;
        } catch (RuntimeException e) {
            log.warning("Resource pack: push to " + p.getName() + " failed: " + e);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PackBuilder.Result r = current;
            if (p.isOnline() && r != null && !r.empty()) {
                push(p, r);
            }
        }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        playerStatus.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        if (!PACK_ID.equals(e.getID())) {
            return;
        }
        String s = e.getStatus().name();
        playerStatus.put(e.getPlayer().getUniqueId(), s);
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> log.info("Resource pack loaded by " + e.getPlayer().getName());
            case DECLINED -> e.getPlayer().sendRichMessage("<gray>Pack de ressources refusé : les objets et créatures "
                    + "créés par l'IA s'afficheront comme des objets vanilla. Réactivez-le dans "
                    + "<white>Multijoueur > Modifier > Packs de ressources</white>.");
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                log.warning("Resource pack: " + e.getPlayer().getName() + " -> " + s + " (is http://"
                        + hostFor(e.getPlayer()) + ":" + port + "/pack.zip reachable from that player? see resource-pack.public-host)");
                e.getPlayer().sendRichMessage("<red>Le pack de ressources VibeCraft n'a pas pu être chargé (" + s + ").");
            }
            default -> {
            }
        }
    }

    // ================================================================== URL host

    String hostFor(Player p) {
        String h = publicHost;
        if (h == null || h.isBlank() || h.equalsIgnoreCase("auto")) {
            h = null;
            try {
                Object conn = p.getConnection();
                InetSocketAddress vh = conn instanceof io.papermc.paper.connection.PlayerConnection pc ? pc.getVirtualHost() : null;
                if (vh != null) {
                    String s = vh.getHostString();
                    int nul = s.indexOf('\0'); // Forge/BungeeCord markers
                    if (nul >= 0) {
                        s = s.substring(0, nul);
                    }
                    if (s.endsWith(".")) {
                        s = s.substring(0, s.length() - 1);
                    }
                    if (!s.isBlank()) {
                        h = s;
                    }
                }
            } catch (RuntimeException ignored) {
                // no virtual host
            }
            if (h == null) {
                h = lanIp;
            }
        }
        if (h.contains(":") && !h.startsWith("[")) {
            h = "[" + h + "]"; // IPv6 literal
        }
        return h;
    }

    static String detectLanIp() {
        String best = null;
        int bestScore = -1;
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) {
                        continue;
                    }
                } catch (IOException e) {
                    continue;
                }
                String name = (ni.getName() + " " + ni.getDisplayName()).toLowerCase(java.util.Locale.ROOT);
                boolean virtualish = name.contains("virtual") || name.contains("vmware") || name.contains("vbox")
                        || name.contains("hyper-v") || name.contains("vethernet") || name.contains("docker")
                        || name.contains("wsl") || name.contains("tailscale") || name.contains("zerotier");
                for (Enumeration<InetAddress> as = ni.getInetAddresses(); as.hasMoreElements(); ) {
                    InetAddress a = as.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    int score = ip.startsWith("192.168.") ? 30 : ip.startsWith("10.") ? 20 : a.isSiteLocalAddress() ? 10 : 5;
                    if (virtualish) {
                        score -= 8;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        best = ip;
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // fall back below
        }
        return best == null ? "127.0.0.1" : best;
    }

    // ================================================================== pack format

    /** {resource_major, resource_minor} from the server's version.json (fallback 97.1 = 26.3). */
    static int[] detectFormat() {
        List<ClassLoader> loaders = new ArrayList<>();
        try {
            loaders.add(Bukkit.getServer().getClass().getClassLoader());
        } catch (RuntimeException | LinkageError ignored) {
            // no server (tests)
        }
        loaders.add(ClassLoader.getSystemClassLoader());
        loaders.add(Thread.currentThread().getContextClassLoader());
        for (ClassLoader cl : loaders) {
            if (cl == null) {
                continue;
            }
            try (InputStream in = cl.getResourceAsStream("version.json")) {
                if (in == null) {
                    continue;
                }
                JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject pv = o.getAsJsonObject("pack_version");
                if (pv != null && pv.has("resource_major")) {
                    return new int[] {pv.get("resource_major").getAsInt(), pv.has("resource_minor") ? pv.get("resource_minor").getAsInt() : 0};
                }
                if (pv != null && pv.has("resource")) {
                    return new int[] {pv.get("resource").getAsInt(), 0};
                }
            } catch (IOException | RuntimeException ignored) {
                // try the next loader
            }
        }
        return new int[] {97, 1};
    }

    // ================================================================== status

    public PackBuilder.Result current() {
        return current;
    }

    /** JSON-friendly status (no secrets). */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        PackBuilder.Result r = current;
        m.put("enabled", enabled);
        m.put("url", "http://" + (publicHost.equalsIgnoreCase("auto") ? lanIp : publicHost) + ":" + port + "/pack.zip");
        m.put("publicHost", publicHost);
        m.put("port", port);
        m.put("packFormat", minFormat);
        m.put("required", required);
        m.put("sha1", r == null ? "" : r.sha1());
        m.put("sizeBytes", r == null || r.empty() ? 0 : r.zip().length);
        m.put("files", r == null ? 0 : r.files());
        m.put("modules", r == null ? List.of() : r.modules());
        m.put("builtAt", builtAt);
        m.put("buildMs", buildMs);
        m.put("pushes", pushes);
        Map<String, Set<String>> ns = r == null ? Map.of() : r.namespaces();
        m.put("namespaces", ns);
        List<Map<String, String>> issues = new ArrayList<>();
        if (r != null) {
            for (PackBuilder.Issue i : r.issues()) {
                Map<String, String> o = new LinkedHashMap<>();
                o.put("module", i.module());
                o.put("level", i.level());
                o.put("file", i.file());
                o.put("message", i.message());
                issues.add(o);
            }
        }
        m.put("issues", issues);
        List<Map<String, Object>> models = new ArrayList<>();
        if (r != null) {
            for (dev.vibecraft.pack.mesh.MeshCompiler.ObjectInfo o : r.models()) {
                Map<String, Object> d = dev.vibecraft.pack.mesh.MeshCompiler.describe(o);
                d.put("preview", "modules/" + o.module() + "/.vibe/previews/" + previewName(o.id()));
                models.add(d);
            }
        }
        m.put("models", models);
        Map<String, String> players = new TreeMap<>();
        playerStatus.forEach((u, s) -> {
            Player p = Bukkit.getPlayer(u);
            players.put(p != null ? p.getName() : u.toString(), s);
        });
        m.put("players", players);
        return m;
    }

    // ================================================================== HTTP

    private void startHttp() throws IOException {
        AtomicInteger n = new AtomicInteger();
        httpPool = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64), r -> {
            Thread t = new Thread(r, "VibeCraft-pack-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        httpPool.allowCoreThreadTimeOut(true);
        HttpServer s = HttpServer.create(new InetSocketAddress(bind, port), 32);
        s.setExecutor(httpPool);
        s.createContext("/", this::serve);
        s.start();
        http = s;
    }

    private void serve(HttpExchange ex) {
        try (ex) {
            String method = ex.getRequestMethod();
            boolean head = method.equalsIgnoreCase("HEAD");
            if (!head && !method.equalsIgnoreCase("GET")) {
                send(ex, 405, "text/plain", "method not allowed".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            String path = ex.getRequestURI().getPath();
            byte[] zip = null;
            PackBuilder.Result r = current;
            if (path.equals("/pack.zip") && r != null && !r.empty()) {
                zip = r.zip();
            } else if (path.startsWith("/pack/") && path.endsWith(".zip")) {
                String sha = path.substring(6, path.length() - 4);
                if (sha.matches("[0-9a-f]{40}")) {
                    zip = served.get(sha);
                }
            }
            if (zip == null) {
                send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), head);
                return;
            }
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"vibecraft-pack.zip\"");
            send(ex, 200, "application/zip", zip, head);
        } catch (IOException | RuntimeException ignored) {
            // client went away
        }
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body, boolean head) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        if (head) {
            ex.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}

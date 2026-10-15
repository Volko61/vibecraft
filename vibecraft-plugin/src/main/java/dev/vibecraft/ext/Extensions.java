package dev.vibecraft.ext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import dev.vibecraft.core.ModuleManager;
import dev.vibecraft.core.ModuleManagerImpl;
import dev.vibecraft.pack.Model3dService;
import dev.vibecraft.pack.PackService;
import dev.vibecraft.pack.TextureService;
import dev.vibecraft.web.ApiExtensions;
import dev.vibecraft.web.ApiExtensions.Reply;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * "Mod-like content" services: secrets, OpenRouter client, hot resource pack, texture generation,
 * and their HTTP routes:
 * <ul>
 *   <li>{@code GET /api/pack}: pack status (url, sha1, files, per-module issues, players' pack state);</li>
 *   <li>{@code POST /api/pack/rebuild}: rebuild now (and push if changed), answers the status;</li>
 *   <li>{@code POST /api/modules/<id>/textures}: generate a texture with an image model (JSON body, see ASSETS.md);</li>
 *   <li>{@code GET /api/openrouter}: {available, model, imageModel} (never the key);</li>
 *   <li>{@code POST /api/modules/<id>/models3d}, {@code GET /api/modules/<id>/models3d[/<taskId>[?wait=s]]}: Meshy
 *       text-to-3D (see {@link Model3dService}).</li>
 * </ul>
 * Textures use OpenRouter or Meshy ({@code "provider"}; default: the running job's choice, else
 * {@code images.default-provider}).
 */
public final class Extensions {

    private final JavaPlugin plugin;
    private final ModuleManager modules;
    private SecretStore secrets;
    private OpenRouterClient openRouter;
    private MeshyClient meshy;
    private Model3dService models3d;
    private volatile java.util.function.UnaryOperator<String> jobProvider = id -> null;
    private PackService pack;
    private TextureService textures;
    private ApiExtensions.Route route;

    public Extensions(JavaPlugin plugin, ModuleManager modules) {
        this.plugin = plugin;
        this.modules = modules;
        // secrets first: the AI job service reads OPENROUTER_API_KEY from here
        this.secrets = SecretStore.init(plugin.getDataFolder(), plugin.getConfig().getConfigurationSection("secrets"),
                plugin.getLogger());
    }

    /** Module id -> image provider of its running AI job ("auto"/"openrouter"/"meshy"), or null. */
    public void setJobProvider(java.util.function.UnaryOperator<String> lookup) {
        this.jobProvider = lookup == null ? id -> null : lookup;
    }

    /** Provider used by default for a module: its running job's choice, else images.default-provider. */
    private String defaultProvider(String moduleId) {
        String p = null;
        try {
            p = jobProvider.apply(moduleId);
        } catch (RuntimeException ignored) {
            // no job info
        }
        if (p == null || p.isBlank() || p.equals("auto")) {
            p = plugin.getConfig().getString("images.default-provider", "auto");
        }
        return p == null ? "auto" : p.strip().toLowerCase(java.util.Locale.ROOT);
    }

    public void start() {
        openRouter = new OpenRouterClient(plugin.getConfig().getConfigurationSection("openrouter"), secrets, plugin.getLogger());
        OpenRouterClient.install(openRouter);
        meshy = new MeshyClient(plugin.getConfig().getConfigurationSection("meshy"), secrets, plugin.getLogger());
        MeshyClient.install(meshy);
        BiConsumer<String, String> moduleLog = (id, line) -> {
            if (modules instanceof ModuleManagerImpl impl) {
                String clean = SecretStore.scrub(line);
                int sp = clean.indexOf(' ');
                impl.appendLog(id, sp > 0 ? clean.substring(0, sp) : "INFO", sp > 0 ? clean.substring(sp + 1) : clean);
            }
        };
        pack = new PackService(plugin, modules, moduleLog);
        pack.start();
        textures = new TextureService(openRouter, () -> meshy, this::defaultProvider, modules.modulesDir(), pack::poke, moduleLog);
        models3d = new Model3dService(() -> meshy, modules.modulesDir(), pack::rebuildNow, moduleLog,
                plugin.getConfig().getInt("meshy.max-concurrent-3d", 2));
        route = this::route;
        ApiExtensions.register(route);
        plugin.getLogger().info("OpenRouter: " + (openRouter.hasKey() ? "key configured" : "no key (plugins/VibeCraft/secrets.yml)")
                + ", chat model " + openRouter.defaultModel() + ", image model " + openRouter.imageModel()
                + "; Meshy: " + (meshy.hasKey() ? "key configured" : "no key")
                + "; secrets configured: " + secrets.names().size());
    }

    public void stop() {
        if (route != null) {
            ApiExtensions.unregister(route);
        }
        if (pack != null) {
            pack.stop();
        }
        if (openRouter != null) {
            openRouter.shutdown();
        }
        if (meshy != null) {
            meshy.shutdown();
        }
    }

    public PackService pack() {
        return pack;
    }

    private CompletableFuture<Reply> route(ApiExtensions.Request r) throws Exception {
        List<String> s = r.segments();
        int n = s.size();
        if (n >= 2 && s.get(1).equals("pack")) {
            if (n == 2 && r.method().equals("GET")) {
                return CompletableFuture.completedFuture(Reply.ok(pack.status()));
            }
            if (n == 3 && s.get(2).equals("rebuild") && r.method().equals("POST")) {
                return pack.rebuildNow().thenApply(Reply::ok);
            }
            return CompletableFuture.completedFuture(Reply.error(404, "use GET /api/pack or POST /api/pack/rebuild"));
        }
        if (n == 2 && s.get(1).equals("openrouter") && r.method().equals("GET")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("available", openRouter.hasKey());
            m.put("model", openRouter.defaultModel());
            m.put("imageModel", openRouter.imageModel());
            if (!openRouter.hasKey()) {
                m.put("hint", OpenRouterClient.NO_KEY);
            }
            return CompletableFuture.completedFuture(Reply.ok(m));
        }
        if (n == 4 && s.get(1).equals("modules") && s.get(3).equals("textures")) {
            if (!r.method().equals("POST")) {
                return CompletableFuture.completedFuture(Reply.error(405, "use POST with a JSON body"));
            }
            String id = s.get(2);
            if (!modules.isValidId(id)) {
                return CompletableFuture.completedFuture(Reply.error(400, "invalid module id"));
            }
            return textures.handle(id, r.body().get());
        }
        if (n >= 4 && n <= 5 && s.get(1).equals("modules") && s.get(3).equals("models3d")) {
            String id = s.get(2);
            if (!modules.isValidId(id)) {
                return CompletableFuture.completedFuture(Reply.error(400, "invalid module id"));
            }
            if (n == 4) {
                if (r.method().equals("GET")) {
                    return CompletableFuture.completedFuture(Reply.ok(models3d.list(id)));
                }
                if (!r.method().equals("POST")) {
                    return CompletableFuture.completedFuture(Reply.error(405, "use POST with a JSON body"));
                }
                String d = defaultProvider(id);
                return models3d.create(id, r.body().get(), d.equals("openrouter") ? "meshy" : d);
            }
            if (!r.method().equals("GET")) {
                return CompletableFuture.completedFuture(Reply.error(405, "use GET"));
            }
            return models3d.status(id, s.get(4), r.query().get("wait"));
        }
        return null;
    }
}

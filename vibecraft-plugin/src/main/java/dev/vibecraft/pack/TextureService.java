package dev.vibecraft.pack;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vibecraft.ext.OpenRouterClient;
import dev.vibecraft.ext.OpenRouterClient.OpenRouterException;
import dev.vibecraft.web.ApiExtensions.Reply;

/**
 * {@code POST /api/modules/<id>/textures}: generates a texture with an OpenRouter image model
 * (server side: the key never leaves the server), turns it into a Minecraft-looking PNG
 * ({@link TextureProcessor}) and writes it into the module's {@code assets/}; the normal asset
 * watcher then rebuilds and pushes the resource pack.
 */
public final class TextureService {

    private static final Pattern PATH = Pattern.compile("assets/[a-z0-9_.-]+/textures/[a-z0-9_./-]+\\.png");
    private static final int MAX_PROMPT = 1500;

    private final OpenRouterClient client;
    private final File modulesDir;
    private final Runnable onWritten;
    private final BiConsumer<String, String> moduleLog;

    public TextureService(OpenRouterClient client, File modulesDir, Runnable onWritten, BiConsumer<String, String> moduleLog) {
        this.client = client;
        this.modulesDir = modulesDir;
        this.onWritten = onWritten;
        this.moduleLog = moduleLog;
    }

    /** Parsed request. */
    record Spec(String path, String prompt, String kind, String style, String model, int width, int height,
                boolean transparent, boolean seamless, boolean crop, int colors) {
    }

    public CompletableFuture<Reply> handle(String moduleId, String body) {
        Spec spec;
        try {
            spec = parse(body);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(Reply.error(400, e.getMessage()));
        }
        File moduleDir = new File(modulesDir, moduleId);
        if (!moduleDir.isDirectory()) {
            return CompletableFuture.completedFuture(Reply.error(404, "unknown module '" + moduleId
                    + "' (create modules/" + moduleId + "/ first)"));
        }
        File target = new File(moduleDir, spec.path());
        try {
            Path t = target.getCanonicalFile().toPath();
            if (!t.startsWith(moduleDir.getCanonicalFile().toPath().resolve("assets"))) {
                return CompletableFuture.completedFuture(Reply.error(400, "path escapes the module's assets folder"));
            }
        } catch (IOException e) {
            return CompletableFuture.completedFuture(Reply.error(400, "invalid path: " + e.getMessage()));
        }
        if (client == null) {
            return CompletableFuture.completedFuture(Reply.error(503, "OpenRouter indisponible"));
        }
        if (!client.hasKey()) {
            return CompletableFuture.completedFuture(Reply.error(503, OpenRouterClient.NO_KEY
                    + " En attendant, écrivez la texture en pixel art (fichier .png.txt, voir ASSETS.md)."));
        }
        String fullPrompt = buildPrompt(spec);
        long t0 = System.currentTimeMillis();
        return client.image(spec.model(), fullPrompt, spec.transparent())
                .thenApply(img -> write(moduleId, moduleDir, target, spec, img, System.currentTimeMillis() - t0))
                .orTimeout(300, TimeUnit.SECONDS)
                .exceptionally(err -> {
                    Throwable c = err;
                    while (c instanceof CompletionException && c.getCause() != null) {
                        c = c.getCause();
                    }
                    if (c instanceof OpenRouterException oe) {
                        return Reply.error(oe.status() == 401 || oe.status() == 403 ? 502 : oe.status(), oe.getMessage());
                    }
                    if (c instanceof java.util.concurrent.TimeoutException) {
                        return Reply.error(504, "génération d'image trop longue (timeout)");
                    }
                    if (c instanceof IllegalArgumentException) {
                        return Reply.error(422, c.getMessage());
                    }
                    return Reply.error(500, "génération impossible : " + c.getClass().getSimpleName()
                            + (c.getMessage() == null ? "" : ": " + c.getMessage()));
                });
    }

    private Reply write(String moduleId, File moduleDir, File target, Spec spec, OpenRouterClient.Image img, long ms) {
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(img.bytes()));
        } catch (IOException e) {
            src = null;
        }
        if (src == null) {
            throw new IllegalArgumentException("le modèle a renvoyé une image illisible (format "
                    + (img.mediaType() == null ? "inconnu" : img.mediaType()) + " non supporté, PNG/JPEG attendus)");
        }
        if ((long) src.getWidth() * src.getHeight() > 25_000_000L) {
            throw new IllegalArgumentException("image générée trop grande");
        }
        BufferedImage out = TextureProcessor.process(src, new TextureProcessor.Options(spec.width(), spec.height(),
                spec.transparent(), spec.crop(), spec.seamless(), spec.colors()));
        byte[] png = TextureSources.png(out);
        try {
            Files.createDirectories(target.getParentFile().toPath());
            Path tmp = new File(target.getParentFile(), "." + target.getName() + ".tmp").toPath();
            Files.write(tmp, png);
            Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            // keep the full-size original for humans (not in the pack)
            File orig = new File(moduleDir, ".vibe/textures/" + spec.path().replace('/', '_').replace(".png", "") + ".orig.png");
            Files.createDirectories(orig.getParentFile().toPath());
            Files.write(orig.toPath(), img.bytes());
        } catch (IOException e) {
            throw new CompletionException(new IOException("écriture de " + spec.path() + " impossible : " + e.getMessage()));
        }
        onWritten.run();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("path", spec.path());
        r.put("width", out.getWidth());
        r.put("height", out.getHeight());
        r.put("colors", TextureProcessor.colorCount(out));
        r.put("kind", spec.kind());
        r.put("transparent", spec.transparent());
        r.put("seamless", spec.seamless());
        r.put("model", img.model());
        r.put("cost", img.cost() >= 0 ? img.cost() : null);
        r.put("sourceSize", src.getWidth() + "x" + src.getHeight());
        r.put("ms", ms);
        File txt = new File(target.getPath() + ".txt");
        File b64 = new File(target.getPath() + ".b64");
        if (txt.exists() || b64.exists()) {
            r.put("warning", "a text source (" + (txt.exists() ? txt.getName() : b64.getName())
                    + ") produces the same PNG: delete it, the generated PNG is used");
        }
        r.put("next", "resource pack rebuilt and pushed to players in ~1-2 s; reference it as "
                + resourceId(spec.path()) + " in your models");
        moduleLog.accept(moduleId, "INFO texture generated: " + spec.path() + " (" + out.getWidth() + "x" + out.getHeight()
                + ", " + img.model() + (img.cost() >= 0 ? String.format(Locale.ROOT, ", $%.4f", img.cost()) : "") + ")");
        return Reply.ok(r);
    }

    /** "assets/ns/textures/item/x.png" -> "ns:item/x". */
    static String resourceId(String path) {
        String[] p = path.split("/", 4);
        String rest = p[3];
        return p[1] + ":" + rest.substring(0, rest.length() - 4);
    }

    static Spec parse(String body) {
        JsonObject o;
        try {
            JsonElement el = JsonParser.parseString(body == null || body.isBlank() ? "{}" : body);
            if (!el.isJsonObject()) {
                throw new IllegalArgumentException("body must be a JSON object");
            }
            o = el.getAsJsonObject();
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException iae && !(e instanceof com.google.gson.JsonParseException)) {
                throw iae;
            }
            throw new IllegalArgumentException("invalid JSON body: " + PackBuilder.jsonMessage(e)
                    + " (tip: put the JSON in a file and use curl --data-binary @file.json, or escape the quotes)");
        }
        String path = str(o, "path");
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("missing \"path\", e.g. \"assets/<namespace>/textures/item/laser.png\"");
        }
        path = path.strip().replace('\\', '/');
        if (path.contains("..") || path.contains("//") || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("\"path\" must look like assets/<namespace>/textures/<folder>/<name>.png "
                    + "(lowercase a-z 0-9 _ . - only): " + path);
        }
        String prompt = str(o, "prompt");
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("missing \"prompt\" (what to draw, e.g. \"a glowing blue laser gun\")");
        }
        if (prompt.length() > MAX_PROMPT) {
            throw new IllegalArgumentException("\"prompt\" too long (max " + MAX_PROMPT + " chars)");
        }
        String kind = str(o, "kind");
        if (kind == null || kind.isBlank()) {
            String folder = path.split("/")[3];
            kind = switch (folder) {
                case "block" -> "block";
                case "entity" -> "entity";
                case "gui", "font" -> "gui";
                case "particle" -> "item";
                default -> "item";
            };
        }
        kind = kind.strip().toLowerCase(Locale.ROOT);
        if (!kind.matches("item|block|entity|gui|skin|other")) {
            throw new IllegalArgumentException("\"kind\" must be item, block, entity, skin, gui or other");
        }
        int defSize = switch (kind) {
            case "entity", "skin" -> 64;
            case "gui" -> 128;
            default -> 16;
        };
        int size = intVal(o, "size", defSize);
        int width = intVal(o, "width", size);
        int height = intVal(o, "height", size);
        if (width < 4 || height < 4 || width > 512 || height > 512) {
            throw new IllegalArgumentException("size/width/height must be between 4 and 512 (Minecraft items are 16, blocks 16, entities 64)");
        }
        boolean seamless = boolVal(o, "seamless", kind.equals("block"));
        boolean transparent = !seamless && boolVal(o, "transparent", kind.equals("item"));
        boolean crop = boolVal(o, "crop", kind.equals("item"));
        int colors = intVal(o, "colors", width * height <= 256 ? 16 : width * height <= 1024 ? 24 : 48);
        if (colors < 0 || colors > 256) {
            throw new IllegalArgumentException("\"colors\" must be between 0 (no palette reduction) and 256");
        }
        String style = str(o, "style");
        String model = str(o, "model");
        return new Spec(path, prompt.strip(), kind, style == null ? null : style.strip(), model == null ? null : model.strip(),
                width, height, transparent, seamless, crop, colors);
    }

    static String buildPrompt(Spec s) {
        String style = s.style() != null && !s.style().isBlank() ? s.style() : switch (s.kind()) {
            case "item" -> "Minecraft-style pixel-art game item icon, single object, centered, whole object visible, "
                    + "bold readable silhouette, dark outline, simple flat shading, limited color palette";
            case "block" -> "Minecraft block texture, pixel art, flat front view filling the entire square edge to edge, "
                    + "seamless tileable pattern, no border, no perspective, no shadows, even lighting";
            case "entity", "skin" -> "flat texture sheet for a Minecraft mob, pixel art, simple flat shading, fills the whole frame";
            case "gui" -> "flat Minecraft-style user interface panel, pixel art, beveled gray frame, readable at small size";
            default -> "Minecraft style pixel art, flat shading";
        };
        StringBuilder p = new StringBuilder();
        p.append(s.prompt()).append(". Style: ").append(style).append(". No text, no letters, no watermark.");
        if (s.transparent()) {
            p.append(" Isolated on a plain, perfectly uniform solid magenta (#FF00FF) background, no shadow, no ground.");
        }
        p.append(" The result will be downscaled to ").append(s.width()).append('x').append(s.height())
                .append(" pixels: use large clear shapes and strong contrast.");
        return p.toString();
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int intVal(JsonObject o, String k, int def) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        try {
            return e.getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("\"" + k + "\" must be an integer");
        }
    }

    private static boolean boolVal(JsonObject o, String k, boolean def) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("\"" + k + "\" must be true or false");
        }
    }
}

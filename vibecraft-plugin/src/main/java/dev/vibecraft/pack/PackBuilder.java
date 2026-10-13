package dev.vibecraft.pack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

/**
 * Merges the {@code assets/} folders of modules into one resource pack zip (deterministic bytes,
 * SHA-1), converting text texture sources and validating what the client would reject.
 * Pure JDK + Gson: no Bukkit, safe to run on any thread.
 */
public final class PackBuilder {

    /** Module input: id + module directory (its {@code assets/} sub folder is read). */
    public record Input(String moduleId, File moduleDir) {
    }

    /** A validation finding. level = "ERROR" (file left out / will look broken) or "WARNING". */
    public record Issue(String module, String file, String level, String message) {
        @Override
        public String toString() {
            return level + " " + (file.isEmpty() ? "" : file + ": ") + message;
        }
    }

    /**
     * @param zip        pack bytes (null when there are no assets at all)
     * @param sha1       hex SHA-1 of {@code zip} ("" when empty)
     * @param files      asset files in the pack (pack.mcmeta excluded)
     * @param modules    modules that contributed at least one file
     * @param namespaces namespace -> modules using it
     */
    public record Result(byte[] zip, String sha1, int files, List<String> modules, List<Issue> issues,
                         Map<String, Set<String>> namespaces) {
        public boolean empty() {
            return zip == null;
        }

        public List<Issue> issuesOf(String module) {
            List<Issue> out = new ArrayList<>();
            for (Issue i : issues) {
                if (i.module().equals(module)) {
                    out.add(i);
                }
            }
            return out;
        }
    }

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");
    private static final long FIXED_TIME = 1_262_304_000_000L; // 2010-01-01, any constant works
    private static final int MAX_FILE = 8 * 1024 * 1024;
    private static final int MAX_FILES_PER_MODULE = 2000;

    private record Entry(String module, String source, byte[] data) {
    }

    private PackBuilder() {
    }

    /**
     * @param minFormat resource pack format (major) the pack declares as minimum
     * @param maxFormat declared maximum (major)
     */
    public static Result build(List<Input> inputs, int minFormat, int maxFormat, String description) {
        Map<String, Entry> files = new TreeMap<>();
        List<Issue> issues = new ArrayList<>();
        Map<String, Set<String>> namespaces = new TreeMap<>();
        Set<String> contributing = new LinkedHashSet<>();
        List<Input> sorted = new ArrayList<>(inputs);
        sorted.sort((a, b) -> a.moduleId().compareTo(b.moduleId()));

        for (Input in : sorted) {
            Path assets = new File(in.moduleDir(), "assets").toPath();
            if (!Files.isDirectory(assets)) {
                continue;
            }
            Map<String, byte[]> raw = readTree(in, assets, issues);
            for (Map.Entry<String, byte[]> f : raw.entrySet()) {
                String rel = f.getKey(); // "assets/ns/..."
                String[] parts = rel.split("/");
                if (parts.length < 3) {
                    issues.add(new Issue(in.moduleId(), rel, "WARNING", "files must be inside assets/<namespace>/<type>/... (ignored)"));
                    continue;
                }
                String ns = parts[1];
                String rest = rel.substring(("assets/" + ns + "/").length());
                if (!NAMESPACE.matcher(ns).matches()) {
                    issues.add(new Issue(in.moduleId(), rel, "ERROR", "invalid namespace '" + ns
                            + "': only lowercase a-z, 0-9, _ . - (file ignored)"));
                    continue;
                }
                if (!PATH.matcher(rest).matches()) {
                    issues.add(new Issue(in.moduleId(), rel, "ERROR", "invalid path: only lowercase a-z, 0-9, _ . - / are allowed"
                            + " in resource paths (no uppercase, no spaces) (file ignored)"));
                    continue;
                }
                namespaces.computeIfAbsent(ns, k -> new TreeSet<>()).add(in.moduleId());
                List<Map.Entry<String, byte[]>> outputs;
                try {
                    outputs = convert(rel, f.getValue(), issues, in.moduleId());
                } catch (TextureSources.SourceException e) {
                    issues.add(new Issue(in.moduleId(), rel, "ERROR", e.getMessage() + " (file left out of the pack)"));
                    continue;
                }
                for (Map.Entry<String, byte[]> o : outputs) {
                    Entry prev = files.get(o.getKey());
                    if (prev != null) {
                        if (prev.module.equals(in.moduleId())) {
                            issues.add(new Issue(in.moduleId(), rel, "WARNING", o.getKey() + " is produced twice (by "
                                    + prev.source + " and " + rel + "); keeping " + prev.source));
                        } else {
                            issues.add(new Issue(in.moduleId(), rel, "WARNING", o.getKey() + " is already provided by module '"
                                    + prev.module + "' (kept); rename your file or use your own namespace"));
                            issues.add(new Issue(prev.module, prev.source, "WARNING", o.getKey()
                                    + " is also provided by module '" + in.moduleId() + "' (yours is kept)"));
                        }
                        continue;
                    }
                    files.put(o.getKey(), new Entry(in.moduleId(), rel, o.getValue()));
                    contributing.add(in.moduleId());
                }
            }
        }
        for (Map.Entry<String, Set<String>> ns : namespaces.entrySet()) {
            if (ns.getValue().size() > 1 && !ns.getKey().equals("minecraft")) {
                for (String m : ns.getValue()) {
                    issues.add(new Issue(m, "", "WARNING", "namespace '" + ns.getKey() + "' is shared by modules "
                            + ns.getValue() + ": use a namespace of your own (e.g. your module id) to avoid clashes"));
                }
            }
        }
        validateReferences(files, issues);
        if (files.isEmpty()) {
            return new Result(null, "", 0, List.of(), List.copyOf(issues), namespaces);
        }
        addAtlases(files);
        byte[] zip = zip(files, packMeta(minFormat, maxFormat, description));
        return new Result(zip, sha1Hex(zip), files.size(), List.copyOf(contributing), List.copyOf(issues), namespaces);
    }

    // ------------------------------------------------------------------ reading & converting

    private static Map<String, byte[]> readTree(Input in, Path assets, List<Issue> issues) {
        Map<String, byte[]> out = new TreeMap<>();
        Path root = in.moduleDir().toPath();
        try {
            Files.walkFileTree(assets, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    String n = d.getFileName().toString();
                    return !d.equals(assets) && n.startsWith(".") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                    String name = f.getFileName().toString();
                    if (!attrs.isRegularFile() || name.startsWith(".") || name.endsWith("~") || name.endsWith(".tmp")) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = root.relativize(f).toString().replace('\\', '/');
                    if (attrs.size() > MAX_FILE) {
                        issues.add(new Issue(in.moduleId(), rel, "ERROR", "file too large (" + attrs.size() / 1024 + " KB, max "
                                + MAX_FILE / 1024 / 1024 + " MB): left out"));
                        return FileVisitResult.CONTINUE;
                    }
                    if (out.size() >= MAX_FILES_PER_MODULE) {
                        issues.add(new Issue(in.moduleId(), "", "ERROR", "more than " + MAX_FILES_PER_MODULE + " asset files: the rest is ignored"));
                        return FileVisitResult.TERMINATE;
                    }
                    try {
                        out.put(rel, Files.readAllBytes(f));
                    } catch (IOException e) {
                        // being written right now: the next build will pick it up
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            issues.add(new Issue(in.moduleId(), "assets", "ERROR", "cannot read assets: " + e.getMessage()));
        }
        return out;
    }

    private static List<Map.Entry<String, byte[]>> convert(String rel, byte[] data, List<Issue> issues, String module)
            throws TextureSources.SourceException {
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        String out = TextureSources.outputName(name);
        if (out != null && (name.endsWith(".png.txt") || name.endsWith(".png.b64") || rel.contains("/textures/"))) {
            TextureSources.Compiled c = TextureSources.compile(name, data);
            String target = rel.substring(0, rel.length() - name.length()) + out;
            List<Map.Entry<String, byte[]>> list = new ArrayList<>();
            list.add(Map.entry(target, c.png()));
            if (c.mcmeta() != null) {
                list.add(Map.entry(target + ".mcmeta", c.mcmeta().getBytes(StandardCharsets.UTF_8)));
            }
            return list;
        }
        if (name.endsWith(".png")) {
            TextureSources.checkPng(data);
        } else if (name.endsWith(".json") || name.endsWith(".mcmeta")) {
            parseJson(new String(data, StandardCharsets.UTF_8), rel, issues, module);
        } else if (name.endsWith(".ogg")) {
            if (data.length < 4 || data[0] != 'O' || data[1] != 'g' || data[2] != 'g' || data[3] != 'S') {
                throw new TextureSources.SourceException("not an Ogg Vorbis file");
            }
        } else if (name.endsWith(".java") || name.endsWith(".md")) {
            issues.add(new Issue(module, rel, "WARNING", "not a resource pack file (ignored)"));
            return List.of();
        }
        return List.of(Map.entry(rel, data));
    }

    /** Strict JSON parse; lenient-only syntax is accepted with a warning. Throws on invalid JSON. */
    static JsonElement parseJson(String text, String rel, List<Issue> issues, String module) throws TextureSources.SourceException {
        String t = text.startsWith("﻿") ? text.substring(1) : text;
        try {
            JsonReader r = new JsonReader(new StringReader(t));
            r.setStrictness(Strictness.STRICT);
            JsonElement el = JsonParser.parseReader(r);
            if (r.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) {
                throw new TextureSources.SourceException("invalid JSON: unexpected content after the end of the document");
            }
            return el;
        } catch (TextureSources.SourceException e) {
            throw e;
        } catch (Exception strict) {
            try {
                JsonElement el = JsonParser.parseString(t);
                if (issues != null) {
                    issues.add(new Issue(module, rel, "WARNING", "non-standard JSON (" + jsonMessage(strict)
                            + "): the client may reject it, write plain JSON (no comments, no trailing commas)"));
                }
                return el;
            } catch (Exception lenient) {
                throw new TextureSources.SourceException("invalid JSON: " + jsonMessage(lenient));
            }
        }
    }

    static String jsonMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = String.valueOf(c.getMessage());
        int see = m.indexOf("\nSee ");
        if (see > 0) {
            m = m.substring(0, see);
        }
        return m.replace('\n', ' ').strip();
    }

    // ------------------------------------------------------------------ references

    private static void validateReferences(Map<String, Entry> files, List<Issue> issues) {
        for (Map.Entry<String, Entry> f : files.entrySet()) {
            String path = f.getKey();
            Entry e = f.getValue();
            String[] parts = path.split("/", 4);
            if (parts.length < 4 || !path.endsWith(".json")) {
                continue;
            }
            String type = parts[2];
            JsonElement json;
            try {
                json = parseJson(new String(e.data, StandardCharsets.UTF_8), path, null, e.module);
            } catch (TextureSources.SourceException ex) {
                continue; // reported already
            }
            try {
                switch (type) {
                    case "models" -> checkModel(json, files, e, issues);
                    case "items" -> checkItem(json, files, e, issues);
                    case "font" -> checkFont(json, files, e, issues);
                    default -> {
                    }
                }
            } catch (RuntimeException ex) {
                issues.add(new Issue(e.module, e.source, "WARNING", "could not validate: " + ex));
            }
        }
    }

    /** "ns:path" or "path" (minecraft) -> {ns, path}. */
    private static String[] id(String s) {
        String v = s.startsWith("#") ? s.substring(1) : s;
        int c = v.indexOf(':');
        return c < 0 ? new String[] {"minecraft", v} : new String[] {v.substring(0, c), v.substring(c + 1)};
    }

    /** Missing check only for non-vanilla namespaces (vanilla files are in the client). */
    private static boolean missing(Map<String, Entry> files, String ns, String file) {
        return !ns.equals("minecraft") && !files.containsKey("assets/" + ns + "/" + file);
    }

    private static void checkModel(JsonElement json, Map<String, Entry> files, Entry e, List<Issue> issues) {
        if (!json.isJsonObject()) {
            issues.add(new Issue(e.module, e.source, "ERROR", "a model must be a JSON object"));
            return;
        }
        JsonObject o = json.getAsJsonObject();
        if (o.has("parent") && o.get("parent").isJsonPrimitive()) {
            String[] p = id(o.get("parent").getAsString());
            if (!p[1].startsWith("builtin/") && missing(files, p[0], "models/" + p[1] + ".json")) {
                issues.add(new Issue(e.module, e.source, "ERROR", "parent model '" + o.get("parent").getAsString()
                        + "' not found (expected assets/" + p[0] + "/models/" + p[1] + ".json)"));
            }
        }
        if (o.has("textures") && o.get("textures").isJsonObject()) {
            for (Map.Entry<String, JsonElement> t : o.getAsJsonObject("textures").entrySet()) {
                if (!t.getValue().isJsonPrimitive()) {
                    continue;
                }
                String v = t.getValue().getAsString();
                if (v.startsWith("#")) {
                    continue; // reference to another texture variable
                }
                String[] p = id(v);
                if (missing(files, p[0], "textures/" + p[1] + ".png")) {
                    issues.add(new Issue(e.module, e.source, "ERROR", "texture '" + v + "' (\"" + t.getKey()
                            + "\") not found: expected assets/" + p[0] + "/textures/" + p[1]
                            + ".png (or a .png.txt / .png.b64 source); it will render purple-black"));
                }
            }
        }
        if (o.has("elements") && o.get("elements").isJsonArray()) {
            Set<String> vars = new LinkedHashSet<>();
            if (o.has("textures") && o.get("textures").isJsonObject()) {
                vars.addAll(o.getAsJsonObject("textures").keySet());
            }
            int i = 0;
            for (JsonElement el : o.getAsJsonArray("elements")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject c = el.getAsJsonObject();
                for (String k : List.of("from", "to")) {
                    JsonElement v = c.get(k);
                    if (v == null || !v.isJsonArray() || v.getAsJsonArray().size() != 3) {
                        issues.add(new Issue(e.module, e.source, "ERROR", "elements[" + i + "]." + k + " must be [x, y, z]"));
                        continue;
                    }
                    for (JsonElement n : v.getAsJsonArray()) {
                        double d = n.getAsDouble();
                        if (d < -16 || d > 32) {
                            issues.add(new Issue(e.module, e.source, "ERROR", "elements[" + i + "]." + k
                                    + " is outside -16..32 (the client rejects the whole model): " + v));
                            break;
                        }
                    }
                }
                if (c.has("rotation") && c.get("rotation").isJsonObject()) {
                    JsonElement a = c.getAsJsonObject("rotation").get("angle");
                    if (a != null && a.isJsonPrimitive() && Math.abs(a.getAsDouble()) > 45) {
                        issues.add(new Issue(e.module, e.source, "ERROR", "elements[" + i
                                + "].rotation.angle must be between -45 and 45"));
                    }
                }
                if (c.has("faces") && c.get("faces").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> face : c.getAsJsonObject("faces").entrySet()) {
                        if (face.getValue().isJsonObject() && face.getValue().getAsJsonObject().has("texture")) {
                            String tex = face.getValue().getAsJsonObject().get("texture").getAsString();
                            if (tex.startsWith("#") && !vars.isEmpty() && !vars.contains(tex.substring(1))
                                    && !o.has("parent")) {
                                issues.add(new Issue(e.module, e.source, "ERROR", "elements[" + i + "].faces." + face.getKey()
                                        + " uses texture " + tex + " which is not defined in \"textures\""));
                            }
                        }
                    }
                }
                i++;
            }
        }
    }

    private static void checkItem(JsonElement json, Map<String, Entry> files, Entry e, List<Issue> issues) {
        if (!json.isJsonObject() || !json.getAsJsonObject().has("model")) {
            issues.add(new Issue(e.module, e.source, "ERROR", "an item definition (assets/<ns>/items/<name>.json) needs a "
                    + "\"model\" object, e.g. {\"model\": {\"type\": \"minecraft:model\", \"model\": \"<ns>:item/<name>\"}}"));
            return;
        }
        walkItemModels(json.getAsJsonObject().get("model"), files, e, issues, 0);
    }

    private static void walkItemModels(JsonElement el, Map<String, Entry> files, Entry e, List<Issue> issues, int depth) {
        if (depth > 32 || el == null) {
            return;
        }
        if (el.isJsonArray()) {
            for (JsonElement c : el.getAsJsonArray()) {
                walkItemModels(c, files, e, issues, depth + 1);
            }
            return;
        }
        if (!el.isJsonObject()) {
            return;
        }
        JsonObject o = el.getAsJsonObject();
        String type = o.has("type") && o.get("type").isJsonPrimitive() ? o.get("type").getAsString() : "";
        if ((type.equals("minecraft:model") || type.equals("model")) && o.has("model") && o.get("model").isJsonPrimitive()) {
            String m = o.get("model").getAsString();
            String[] p = id(m);
            if (missing(files, p[0], "models/" + p[1] + ".json")) {
                issues.add(new Issue(e.module, e.source, "ERROR", "model '" + m + "' not found (expected assets/" + p[0]
                        + "/models/" + p[1] + ".json)"));
            }
        } else if (type.isEmpty() && o.has("model") && o.get("model").isJsonPrimitive()) {
            issues.add(new Issue(e.module, e.source, "ERROR", "item model object without \"type\": use "
                    + "{\"type\": \"minecraft:model\", \"model\": \"" + o.get("model").getAsString() + "\"}"));
        }
        for (Map.Entry<String, JsonElement> c : o.entrySet()) {
            if (c.getValue().isJsonObject() || c.getValue().isJsonArray()) {
                walkItemModels(c.getValue(), files, e, issues, depth + 1);
            }
        }
    }

    private static void checkFont(JsonElement json, Map<String, Entry> files, Entry e, List<Issue> issues) {
        if (!json.isJsonObject() || !json.getAsJsonObject().has("providers")) {
            issues.add(new Issue(e.module, e.source, "ERROR", "a font needs {\"providers\": [...]}"));
            return;
        }
        JsonArray providers = json.getAsJsonObject().getAsJsonArray("providers");
        for (JsonElement pe : providers) {
            if (!pe.isJsonObject()) {
                continue;
            }
            JsonObject p = pe.getAsJsonObject();
            String type = p.has("type") ? p.get("type").getAsString() : "";
            if (!(type.equals("bitmap") || type.equals("minecraft:bitmap"))) {
                continue;
            }
            if (!p.has("file") || !p.has("chars")) {
                issues.add(new Issue(e.module, e.source, "ERROR", "bitmap provider needs \"file\" and \"chars\""));
                continue;
            }
            String file = p.get("file").getAsString();
            String[] id = id(file);
            if (!id[1].endsWith(".png")) {
                issues.add(new Issue(e.module, e.source, "ERROR", "bitmap \"file\" must end with .png (it is relative to textures/): " + file));
            } else if (missing(files, id[0], "textures/" + id[1])) {
                issues.add(new Issue(e.module, e.source, "ERROR", "font texture '" + file + "' not found (expected assets/"
                        + id[0] + "/textures/" + id[1] + ")"));
            }
            int height = p.has("height") ? p.get("height").getAsInt() : 8;
            int ascent = p.has("ascent") ? p.get("ascent").getAsInt() : 0;
            if (ascent > height) {
                issues.add(new Issue(e.module, e.source, "ERROR", "bitmap provider: ascent (" + ascent
                        + ") must be <= height (" + height + "), the font fails to load otherwise"));
            }
            if (height > 512 || height < -512) {
                issues.add(new Issue(e.module, e.source, "WARNING", "bitmap height " + height + " looks wrong"));
            }
        }
    }

    // ------------------------------------------------------------------ atlases

    /**
     * The client only stitches textures/block/** and textures/item/** into the atlases models use. Any other
     * texture a model references (textures/entity/..., textures/custom/...) would render purple-black, so each
     * one is added as a single source to the blocks and items atlases (atlas files merge across packs).
     */
    private static void addAtlases(Map<String, Entry> files) {
        java.util.TreeSet<String> extra = new java.util.TreeSet<>();
        for (Map.Entry<String, Entry> f : files.entrySet()) {
            String key = f.getKey();
            if (!key.startsWith("assets/") || !key.contains("/models/") || !key.endsWith(".json")) {
                continue;
            }
            try {
                JsonElement json = JsonParser.parseString(new String(f.getValue().data, StandardCharsets.UTF_8));
                if (!json.isJsonObject() || !json.getAsJsonObject().has("textures")
                        || !json.getAsJsonObject().get("textures").isJsonObject()) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> t : json.getAsJsonObject().getAsJsonObject("textures").entrySet()) {
                    if (!t.getValue().isJsonPrimitive() || t.getValue().getAsString().startsWith("#")) {
                        continue;
                    }
                    String[] p = id(t.getValue().getAsString());
                    if (p[1].startsWith("block/") || p[1].startsWith("item/")) {
                        continue; // already stitched by the vanilla directory sources
                    }
                    if (files.containsKey("assets/" + p[0] + "/textures/" + p[1] + ".png")) {
                        extra.add(p[0] + ":" + p[1]);
                    }
                }
            } catch (RuntimeException ignored) {
                // invalid JSON is reported by the validator
            }
        }
        if (extra.isEmpty()) {
            return;
        }
        JsonArray sources = new JsonArray();
        for (String res : extra) {
            JsonObject src = new JsonObject();
            src.addProperty("type", "minecraft:single");
            src.addProperty("resource", res);
            sources.add(src);
        }
        JsonObject atlas = new JsonObject();
        atlas.add("sources", sources);
        byte[] data = atlas.toString().getBytes(StandardCharsets.UTF_8);
        files.put("assets/minecraft/atlases/blocks.json", new Entry("vibecraft", "(generated atlas)", data));
        files.put("assets/minecraft/atlases/items.json", new Entry("vibecraft", "(generated atlas)", data));
    }

    // ------------------------------------------------------------------ zip

    static String packMeta(int minFormat, int maxFormat, String description) {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", description);
        pack.addProperty("min_format", minFormat);
        pack.addProperty("max_format", Math.max(minFormat, maxFormat));
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root.toString();
    }

    private static byte[] zip(Map<String, Entry> files, String mcmeta) {
        Map<String, byte[]> all = new LinkedHashMap<>();
        all.put("pack.mcmeta", mcmeta.getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, Entry> f : files.entrySet()) {
            all.put(f.getKey(), f.getValue().data);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.setLevel(Deflater.BEST_COMPRESSION);
            for (Map.Entry<String, byte[]> f : all.entrySet()) {
                ZipEntry ze = new ZipEntry(f.getKey());
                ze.setTime(FIXED_TIME);
                zos.putNextEntry(ze);
                zos.write(f.getValue());
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    public static String sha1Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

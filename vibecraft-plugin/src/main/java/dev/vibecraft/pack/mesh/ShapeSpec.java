package dev.vibecraft.pack.mesh;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vibecraft.pack.TextureSources;
import dev.vibecraft.pack.TextureSources.SourceException;

/**
 * Parsed {@code *.shape.json} (or a bare {@code *.obj} with options in a {@code # vibecraft {...}} comment):
 * global options, materials and parts (each a list of primitive shapes), then generation of the elements
 * of every part in object space (units = blocks, y up, front = +z).
 */
final class ShapeSpec {

    static final boolean DEBUG = Boolean.getBoolean("vibecraft.mesh.debug");
    static final Pattern PART_NAME = Pattern.compile("[a-z0-9_]{1,32}");
    static final Map<String, Integer> DETAIL = Map.of("low", 12, "medium", 20, "high", 32, "ultra", 48);

    record PartSrc(String name, V3 pivot, List<JsonObject> shapes, List<String> wheres, ObjParser.Obj obj, String objPath) {
    }

    double size;
    int segments = 20;
    double light = 0.6;
    String mode = "exact";
    int resolution = 48;
    String origin = "keep";
    int maxElements = 4000;
    int maxTexture = 1024;
    boolean doubleSided;
    JsonObject display;
    final Map<String, Material> materials = new LinkedHashMap<>();
    final List<PartSrc> parts = new ArrayList<>();

    private final String sourcePath; // assets/ns/.../x.shape.json
    private final MeshCompiler.Resolver resolver;
    private final List<String> warnings;
    private final Map<String, BufferedImage> imageCache = new LinkedHashMap<>();

    private ShapeSpec(String sourcePath, MeshCompiler.Resolver resolver, List<String> warnings) {
        this.sourcePath = sourcePath;
        this.resolver = resolver;
        this.warnings = warnings;
    }

    // ================================================================== parsing

    static ShapeSpec parseShape(String sourcePath, String text, MeshCompiler.Resolver resolver, List<String> warnings)
            throws SourceException {
        ShapeSpec s = new ShapeSpec(sourcePath, resolver, warnings);
        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(text);
            root = Json.obj(el, "shape file");
        } catch (RuntimeException e) {
            throw new SourceException("invalid JSON: " + e.getMessage());
        }
        s.options(root, "");
        if (Json.has(root, "materials")) {
            JsonObject mats = Json.obj(root.get("materials"), "materials");
            for (Map.Entry<String, JsonElement> m : mats.entrySet()) {
                s.materials.put(m.getKey(), s.material(m.getKey(), m.getValue(), "materials." + m.getKey()));
            }
        }
        if (Json.has(root, "parts")) {
            JsonObject parts = Json.obj(root.get("parts"), "parts");
            if (parts.size() == 0 || parts.size() > 32) {
                throw new SourceException("\"parts\" must have 1 to 32 entries");
            }
            for (Map.Entry<String, JsonElement> p : parts.entrySet()) {
                String name = p.getKey();
                if (!PART_NAME.matcher(name).matches()) {
                    throw new SourceException("part name \"" + name + "\" must match [a-z0-9_]{1,32}");
                }
                String where = "parts." + name;
                JsonElement pe = p.getValue();
                JsonArray shapes;
                V3 pivot = null;
                if (pe.isJsonArray()) {
                    shapes = pe.getAsJsonArray();
                } else {
                    JsonObject po = Json.obj(pe, where);
                    pivot = Json.vec(po, "pivot", null, where);
                    if (!Json.has(po, "shapes") || !po.get("shapes").isJsonArray()) {
                        throw new SourceException(where + " needs \"shapes\": [ ... ]");
                    }
                    shapes = po.getAsJsonArray("shapes");
                }
                s.addPart(name, pivot, shapes, where + ".shapes");
            }
            if (Json.has(root, "shapes")) {
                throw new SourceException("use either \"shapes\" (one part) or \"parts\" (several), not both");
            }
        } else {
            if (!Json.has(root, "shapes") || !root.get("shapes").isJsonArray()) {
                throw new SourceException("a shape file needs \"shapes\": [ {\"type\": \"box\", ...}, ... ] (or \"parts\")");
            }
            s.addPart("main", Json.vec(root, "pivot", null, ""), root.getAsJsonArray("shapes"), "shapes");
        }
        return s;
    }

    static ShapeSpec parseObj(String sourcePath, String text, MeshCompiler.Resolver resolver, List<String> warnings)
            throws SourceException {
        ShapeSpec s = new ShapeSpec(sourcePath, resolver, warnings);
        ObjParser.Obj obj = ObjParser.parse(text);
        s.origin = "bottom";
        s.mode = "auto";
        if (obj.optionsJson() != null && !obj.optionsJson().isBlank()) {
            try {
                s.options(Json.obj(JsonParser.parseString(obj.optionsJson()), "# vibecraft options"), "");
            } catch (RuntimeException e) {
                throw new SourceException("invalid JSON in the \"# vibecraft {...}\" comment: " + e.getMessage());
            }
        }
        s.parts.add(new PartSrc("main", null, List.of(), List.of(), obj, sourcePath));
        return s;
    }

    private void addPart(String name, V3 pivot, JsonArray shapes, String where) throws SourceException {
        List<JsonObject> list = new ArrayList<>();
        List<String> wheres = new ArrayList<>();
        int i = 0;
        for (JsonElement e : shapes) {
            String w = where + "[" + i++ + "]";
            list.add(Json.obj(e, w));
            wheres.add(w);
        }
        if (list.isEmpty()) {
            throw new SourceException(where + " is empty");
        }
        if (list.size() > 2000) {
            throw new SourceException(where + ": too many shapes (max 2000)");
        }
        parts.add(new PartSrc(name, pivot, list, wheres, null, null));
    }

    private void options(JsonObject o, String where) throws SourceException {
        size = Json.num(o, "size", 0, where.isEmpty() ? "options" : where);
        if (size < 0 || size > 256) {
            throw new SourceException("\"size\" (largest dimension in blocks) must be between 0 and 256");
        }
        if (Json.has(o, "detail")) {
            JsonElement d = o.get("detail");
            if (d.isJsonPrimitive() && d.getAsJsonPrimitive().isNumber()) {
                segments = Json.integer(o, "detail", 20, 4, 96, "options");
            } else {
                Integer v = DETAIL.get(d.getAsString().toLowerCase(Locale.ROOT));
                if (v == null) {
                    throw new SourceException("\"detail\" must be low, medium, high, ultra or a number of segments (4..96)");
                }
                segments = v;
            }
        }
        segments += segments % 2;
        light = Json.num(o, "light", light, "options");
        if (light < 0 || light > 1) {
            throw new SourceException("\"light\" (baked shading strength) must be between 0 and 1");
        }
        mode = Json.str(o, "mode", mode, "options").toLowerCase(Locale.ROOT);
        if (!mode.matches("exact|voxel|auto")) {
            throw new SourceException("\"mode\" must be \"exact\" (oriented cuboids), \"voxel\" or \"auto\"");
        }
        resolution = Json.integer(o, "resolution", resolution, 8, 160, "options");
        origin = Json.str(o, "origin", origin, "options").toLowerCase(Locale.ROOT);
        if (!origin.matches("keep|bottom|center")) {
            throw new SourceException("\"origin\" must be keep, bottom or center");
        }
        maxElements = Json.integer(o, "max_elements", maxElements, 16, 16000, "options");
        maxTexture = Json.integer(o, "max_texture", maxTexture, 64, 2048, "options");
        doubleSided = Json.bool(o, "double_sided", false, "options");
        if (Json.has(o, "display")) {
            display = Json.obj(o.get("display"), "display");
        }
    }

    // ================================================================== materials

    Material material(String name, JsonElement e, String where) throws SourceException {
        if (e.isJsonPrimitive()) {
            return Material.color(name, TextureSources.color(e.getAsString(), where));
        }
        JsonObject o = Json.obj(e, where);
        int argb = Json.has(o, "color") ? TextureSources.color(Json.str(o, "color", "#ffffff", where), where + ".color") : 0xffffffff;
        String tex = Json.str(o, "texture", null, where);
        BufferedImage img = null;
        if (tex != null) {
            img = texture(tex, where + ".texture");
            if (!Json.has(o, "color")) {
                argb = 0xffffffff;
            } else if (img != null) {
                // colour = tint of the texture
            }
            if (img == null && !Json.has(o, "color")) {
                argb = 0xff9a9a9a;
            }
        }
        String mapping = Json.str(o, "mapping", "box", where).toLowerCase(Locale.ROOT);
        if (!mapping.equals("box") && !mapping.equals("face")) {
            throw new SourceException(where + ".mapping must be \"box\" (world projection, tiles) or \"face\" (whole texture on each face)");
        }
        double tile = Json.positive(o, "tile", 1, where);
        double defDensity = img == null ? 0 : Math.min(64, Math.max(4, img.getWidth() / tile));
        double density = Json.num(o, "texel", defDensity, where);
        if (img != null && (density < 1 || density > 256)) {
            throw new SourceException(where + ".texel (atlas texels per block) must be between 1 and 256");
        }
        int glow = Json.integer(o, "glow", 0, 0, 15, where);
        boolean ds = Json.bool(o, "double_sided", false, where);
        boolean shade = Json.bool(o, "shade", true, where);
        return new Material(name, argb, img == null ? null : tex, img, mapping, tile, density, glow, ds, shade);
    }

    /** Resolves a material reference of a shape: a name, "#rrggbb", or an inline material object. */
    Material ref(JsonElement e, Material def, String where) throws SourceException {
        if (e == null || e.isJsonNull()) {
            return def;
        }
        if (e.isJsonPrimitive()) {
            String s = e.getAsString();
            if (s.equalsIgnoreCase("none")) {
                return null;
            }
            Material m = materials.get(s);
            if (m != null) {
                return m;
            }
            if (s.startsWith("#")) {
                Material c = Material.color(s, TextureSources.color(s, where));
                materials.put(s, c);
                return c;
            }
            throw new SourceException(where + ": unknown material \"" + s + "\" (define it in \"materials\" or use a colour \"#rrggbb\")");
        }
        return material("inline", e, where);
    }

    BufferedImage texture(String ref, String where) throws SourceException {
        String path;
        if (ref.contains(":")) {
            String ns = ref.substring(0, ref.indexOf(':'));
            String p = ref.substring(ref.indexOf(':') + 1);
            if (ns.equals("minecraft")) {
                warnings.add(where + ": vanilla texture \"" + ref + "\" cannot be baked (the server has no client textures): "
                        + "copy it into your module or generate one with POST /textures; using the material colour");
                return null;
            }
            path = "assets/" + ns + "/textures/" + p + (p.endsWith(".png") ? "" : ".png");
        } else {
            path = resolveRelative(ref);
        }
        if (imageCache.containsKey(path)) {
            return imageCache.get(path);
        }
        byte[] bytes = resolver.file(path);
        if (bytes == null) {
            warnings.add(where + ": texture \"" + ref + "\" not found (expected " + path + "); using the material colour");
            imageCache.put(path, null);
            return null;
        }
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            img = null;
        }
        if (img == null) {
            warnings.add(where + ": texture \"" + ref + "\" is not a readable PNG; using the material colour");
        }
        imageCache.put(path, img);
        return img;
    }

    /** "x.obj" / "../textures/a.png" relative to the source file's folder -> "assets/ns/...". */
    String resolveRelative(String rel) throws SourceException {
        String dir = sourcePath.substring(0, sourcePath.lastIndexOf('/') + 1);
        String[] segs = (dir + rel.replace('\\', '/')).split("/");
        List<String> out = new ArrayList<>();
        for (String s : segs) {
            if (s.isEmpty() || s.equals(".")) {
                continue;
            }
            if (s.equals("..")) {
                if (out.size() <= 2) {
                    throw new SourceException("path \"" + rel + "\" leaves the namespace folder");
                }
                out.remove(out.size() - 1);
            } else {
                out.add(s);
            }
        }
        return String.join("/", out);
    }

    // ================================================================== generation

    /** Elements of every part, in object space (before size normalisation). */
    Map<String, List<Elem>> generate(double tol) throws SourceException {
        Map<String, List<Elem>> out = new LinkedHashMap<>();
        Material def = Material.color("default", 0xff9a9a9a);
        for (PartSrc p : parts) {
            List<Elem> list = new ArrayList<>();
            if (p.obj() != null) {
                addObj(list, p.obj(), p.objPath(), V3.ZERO, M3.I, new V3(1, 1, 1), null, doubleSided, tol, "obj");
            } else {
                for (int i = 0; i < p.shapes().size(); i++) {
                    shape(list, p.shapes().get(i), p.wheres().get(i), def, tol);
                }
            }
            out.put(p.name(), list);
        }
        return out;
    }

    private void shape(List<Elem> out, JsonObject s, String where, Material def, double tol) throws SourceException {
        String type = Json.str(s, "type", null, where);
        if (type == null) {
            throw new SourceException(where + " needs a \"type\" (box, cylinder, cone, sphere, capsule, torus, lathe, extrude, polygon, mesh)");
        }
        type = type.toLowerCase(Locale.ROOT);
        boolean ds = Json.bool(s, "double_sided", doubleSided, where);
        Material mat = ref(s.get("material"), def, where + ".material");
        if (mat != null) {
            mat = mat.withDoubleSided(ds || mat.doubleSided);
        }
        boolean explicitSegments = Json.has(s, "segments");
        int n = Json.integer(s, "segments", segments, 4, 96, where);
        n += n % 2;
        if (!explicitSegments) {
            // small round shapes do not need the global segment count: keep the sagitta below the tolerance
            double rr = Math.max(Json.num(s, "radius", 0, where), Math.max(Json.num(s, "radius_bottom", 0, where),
                    Json.num(s, "radius_top", 0, where)));
            if (Json.has(s, "radii")) {
                rr = Math.max(rr, Json.vec(s, "radii", V3.ZERO, where).max());
            }
            if (Json.has(s, "tube")) {
                rr = Json.num(s, "radius", 0, where) + Json.num(s, "tube", 0, where);
            }
            if (rr > 0 && tol < 1e8) {
                n = Math.min(n, segmentsFor(rr, tol, n));
            }
        }
        V3 at = Json.vec(s, "at", V3.ZERO, where);
        V3 rot = Json.vec(s, "rotate", V3.ZERO, where);
        M3 r = M3.euler(rot.x(), rot.y(), rot.z());
        String axis = Json.str(s, "axis", "y", where).toLowerCase(Locale.ROOT);
        M3 ax = switch (axis) {
            case "y" -> M3.I;
            case "x" -> M3.rotZ(-Math.PI / 2);
            case "z" -> M3.rotX(Math.PI / 2);
            default -> throw new SourceException(where + ".axis must be \"x\", \"y\" or \"z\"");
        };
        List<Elem> gen = new ArrayList<>();
        switch (type) {
            case "box", "cube" -> {
                V3 size;
                V3 c = at;
                if (Json.has(s, "from") || Json.has(s, "to")) {
                    V3 f = Json.vec(s, "from", null, where);
                    V3 t = Json.vec(s, "to", null, where);
                    if (f == null || t == null) {
                        throw new SourceException(where + ": a box needs both \"from\" and \"to\" (or \"size\" + \"at\")");
                    }
                    size = t.sub(f).abs();
                    c = f.add(t).mul(0.5);
                } else {
                    size = Json.vec(s, "size", null, where);
                    if (size == null) {
                        throw new SourceException(where + ": a box needs \"size\": [w, h, d] (blocks) or \"from\"/\"to\"");
                    }
                }
                if (size.x() <= 0 || size.y() <= 0 || size.z() <= 0) {
                    throw new SourceException(where + ": box size must be > 0 on every axis");
                }
                Material[] faces = new Material[6];
                java.util.Arrays.fill(faces, mat);
                if (Json.has(s, "faces")) {
                    JsonObject fo = Json.obj(s.get("faces"), where + ".faces");
                    for (Map.Entry<String, JsonElement> f : fo.entrySet()) {
                        int d = java.util.Arrays.asList(Elem.DIR).indexOf(f.getKey());
                        if (d < 0) {
                            throw new SourceException(where + ".faces: unknown face \"" + f.getKey() + "\" (down, up, north, south, west, east)");
                        }
                        Material fm = ref(f.getValue(), mat, where + ".faces." + f.getKey());
                        faces[d] = fm == null ? null : fm.withDoubleSided(ds || fm.doubleSided);
                    }
                }
                double round = Json.num(s, "round", 0, where);
                if (round > 0) {
                    Geometry.roundedBox(gen, c, r, size, round, tol < 1e8 ? segmentsFor(round, tol, 16) : 4, faces);
                } else {
                    gen.add(Geometry.box(c, r, size, faces));
                }
            }
            case "cylinder", "cone" -> {
                double h = Json.positive(s, "height", 1, where);
                double rad = Json.num(s, "radius", 0.5, where);
                double rb = Json.num(s, "radius_bottom", rad, where);
                double rt = Json.num(s, "radius_top", type.equals("cone") ? 0 : rad, where);
                if (rb < 0 || rt < 0 || (rb <= 0 && rt <= 0)) {
                    throw new SourceException(where + ": radius must be > 0");
                }
                String caps = Json.str(s, "caps", "both", where).toLowerCase(Locale.ROOT);
                Material capMat = ref(s.get("cap_material"), mat, where + ".cap_material");
                Material top = caps.equals("both") || caps.equals("top") ? ref(s.get("top_material"), capMat, where + ".top_material") : null;
                Material bottom = caps.equals("both") || caps.equals("bottom") ? ref(s.get("bottom_material"), capMat, where + ".bottom_material") : null;
                M3 frame = r.mul(ax);
                if (Math.abs(rb - rt) < 1e-9) {
                    Geometry.prism(gen, at, frame, rb, h, n, mat, top, bottom);
                } else {
                    Geometry.lathe(gen, at, frame, new double[][] {{rb, -h / 2}, {rt, h / 2}}, n, new V3(1, 1, 1), mat, top, bottom, tol);
                }
            }
            case "sphere", "ellipsoid" -> {
                V3 radii = Json.has(s, "radii") ? Json.vec(s, "radii", null, where)
                        : new V3(1, 1, 1).mul(Json.positive(s, "radius", 0.5, where));
                if (radii.x() <= 0 || radii.y() <= 0 || radii.z() <= 0) {
                    throw new SourceException(where + ".radii must be > 0");
                }
                int rings = Json.integer(s, "rings", Math.max(4, n / 2), 2, 64, where);
                double from = Json.num(s, "from_angle", 0, where); // 0 = bottom pole, 180 = top pole
                double to = Json.num(s, "to_angle", 180, where);
                double[][] prof = new double[rings + 1][];
                for (int i = 0; i <= rings; i++) {
                    double th = Math.toRadians(from + (to - from) * i / rings);
                    prof[i] = new double[] {StrictMath.sin(th), -StrictMath.cos(th)};
                }
                Material capMat = ref(s.get("cap_material"), mat, where + ".cap_material");
                Geometry.lathe(gen, at, r.mul(ax), prof, n, radii, mat, capMat, capMat, tol);
            }
            case "capsule" -> {
                double rad = Json.positive(s, "radius", 0.5, where);
                double h = Json.positive(s, "height", 2 * rad, where);
                double mid = Math.max(0, h / 2 - rad);
                int q = Math.max(2, n / 4);
                List<double[]> prof = new ArrayList<>();
                for (int i = 0; i <= q; i++) {
                    double th = Math.PI / 2 * i / q;
                    prof.add(new double[] {rad * StrictMath.sin(th), -mid - rad * StrictMath.cos(th)});
                }
                for (int i = 0; i <= q; i++) {
                    double th = Math.PI / 2 * i / q;
                    double[] p = {rad * StrictMath.cos(th), mid + rad * StrictMath.sin(th)};
                    if (i == 0 && mid < 1e-9) {
                        continue;
                    }
                    prof.add(p);
                }
                Geometry.lathe(gen, at, r.mul(ax), prof.toArray(new double[0][]), n, new V3(1, 1, 1), mat, null, null, tol);
            }
            case "torus" -> {
                double major = Json.positive(s, "radius", 0.5, where);
                double tube = Json.positive(s, "tube", 0.15, where);
                int ts = Json.integer(s, "tube_segments", Math.max(6, n / 2), 4, 32, where);
                ts += ts % 2;
                Geometry.torus(gen, at, r.mul(ax), major, tube, n, ts, mat);
            }
            case "lathe" -> {
                double[][] prof = Json.points(s, "profile", 2, 2, where);
                for (double[] p : prof) {
                    if (p[0] < 0) {
                        throw new SourceException(where + ".profile: radius (first value of each point) must be >= 0");
                    }
                }
                boolean caps = Json.bool(s, "caps", true, where);
                Material capMat = caps ? ref(s.get("cap_material"), mat, where + ".cap_material") : null;
                Geometry.lathe(gen, at, r.mul(ax), prof, n, Json.vec(s, "scale", new V3(1, 1, 1), where), mat, capMat, capMat, tol);
            }
            case "extrude" -> {
                double[][] pts = Json.points(s, "points", 2, 3, where);
                double depth = Json.positive(s, "depth", 0.1, where);
                Material side = ref(s.get("side_material"), mat, where + ".side_material");
                if (side != null) {
                    side = side.withDoubleSided(ds || side.doubleSided);
                }
                Geometry.extrude(gen, at, r.mul(ax), pts, depth, mat, side, tol);
            }
            case "polygon", "quad" -> {
                double[][] pts = Json.points(s, "points", 3, 3, where);
                List<V3> poly = new ArrayList<>();
                for (double[] p : pts) {
                    poly.add(new V3(p[0], p[1], p[2]));
                }
                Geometry.polygon(gen, Geometry.transform(poly, new V3(1, 1, 1), r, at), mat, tol);
            }
            case "mesh", "obj" -> {
                String file = Json.str(s, "obj", null, where);
                if (file == null) {
                    throw new SourceException(where + ": a mesh needs \"obj\": \"file.obj\" (relative to this file)");
                }
                String path = resolveRelative(file);
                byte[] data = resolver.file(path);
                if (data == null) {
                    throw new SourceException(where + ".obj: file not found: " + path);
                }
                ObjParser.Obj obj = ObjParser.parse(new String(data, StandardCharsets.UTF_8));
                V3 sc = Json.vec(s, "scale", new V3(1, 1, 1), where);
                addObj(gen, obj, path, at, r, sc, Json.has(s, "material") ? mat : null, ds, tol, where);
            }
            default -> throw new SourceException(where + ": unknown type \"" + type
                    + "\" (box, cylinder, cone, sphere, capsule, torus, lathe, extrude, polygon, mesh)");
        }
        int lightEmission = Json.integer(s, "glow", -1, -1, 15, where);
        if (lightEmission >= 0) {
            for (Elem e : gen) {
                for (int i = 0; i < 6; i++) {
                    if (e.faces[i] != null) {
                        Material m = e.faces[i];
                        e.faces[i] = new Material(m.name, m.argb, m.texture, m.image, m.mapping, m.tile, m.density, lightEmission,
                                m.doubleSided, m.shade);
                    }
                }
            }
        }
        if (DEBUG && tol < 1e8) {
            System.out.println("  [mesh] " + where + " " + type + ": " + gen.size() + " elements");
        }
        out.addAll(gen);
        for (String m : Json.axes(s, "mirror", where)) {
            int axisIdx = m.equals("x") ? 0 : m.equals("y") ? 1 : 2;
            int size = out.size();
            for (int i = size - gen.size(); i < size; i++) {
                out.add(out.get(i).mirrored(axisIdx));
            }
            // mirrored copies are mirrored again by the next axis (x + z = 4 copies)
            List<Elem> all = new ArrayList<>(out.subList(size - gen.size(), out.size()));
            gen = all;
        }
    }

    /** Even number of sides (>= 8, <= max) keeping the polygon within tol of a circle of radius r (x1.5 margin). */
    static int segmentsFor(double r, double tol, int max) {
        double c = 1 - Math.min(1, tol / r);
        double needed = Math.PI / StrictMath.acos(Math.max(-1, c));
        int n = (int) Math.ceil(needed * 1.5);
        n += n % 2;
        return Math.max(Math.min(8, max), Math.min(max, n));
    }

    private void addObj(List<Elem> out, ObjParser.Obj obj, String objPath, V3 at, M3 r, V3 scale, Material override,
                        boolean ds, double tol, String where) throws SourceException {
        Map<String, ObjParser.Mtl> mtls = new LinkedHashMap<>();
        for (String lib : obj.mtllibs()) {
            String dir = objPath.substring(0, objPath.lastIndexOf('/') + 1);
            String p = resolveRelativeTo(dir, lib);
            byte[] data = resolver.file(p);
            if (data == null) {
                warnings.add(where + ": material library " + lib + " not found (" + p + "): using grey");
                continue;
            }
            mtls.putAll(ObjParser.parseMtl(new String(data, StandardCharsets.UTF_8)));
        }
        Map<String, Material> cache = new LinkedHashMap<>();
        Material def = override != null ? override : Material.color("default", 0xffb0b0b0);
        // closed mesh written clockwise (inside out): back faces would be the visible ones -> flip it
        double vol = 0;
        V3 mn = new V3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
        V3 mx = mn.mul(-1);
        for (ObjParser.Face f : obj.faces()) {
            List<V3> p = f.pts();
            for (int i = 1; i + 1 < p.size(); i++) {
                vol += p.get(0).dot(p.get(i).cross(p.get(i + 1))) / 6;
            }
            for (V3 v : p) {
                mn = V3.min(mn, v);
                mx = V3.max(mx, v);
            }
        }
        V3 ext = mx.sub(mn);
        boolean flip = vol < -1e-3 * Math.max(1e-12, ext.x() * ext.y() * ext.z());
        if (flip) {
            warnings.add(where + ": the mesh is inside out (faces clockwise seen from outside): flipped automatically; "
                    + "write faces counter-clockwise seen from outside");
        }
        for (ObjParser.Face f0 : obj.faces()) {
            ObjParser.Face f = f0;
            if (flip) {
                List<V3> rev = new ArrayList<>(f0.pts());
                java.util.Collections.reverse(rev);
                f = new ObjParser.Face(rev, f0.material(), f0.group());
            }
            Material m = def;
            if (override == null && f.material() != null) {
                m = cache.get(f.material());
                if (m == null) {
                    ObjParser.Mtl mtl = mtls.get(f.material());
                    if (mtl == null) {
                        Material named = materials.get(f.material());
                        m = named != null ? named : def;
                    } else {
                        BufferedImage img = null;
                        if (mtl.mapKd() != null) {
                            String dir = objPath.substring(0, objPath.lastIndexOf('/') + 1);
                            String tp = mtl.mapKd().contains(":") ? mtl.mapKd() : resolveRelativeTo(dir, mtl.mapKd());
                            img = tp.contains(":") ? texture(tp, where + " map_Kd") : textureByPath(tp, where + " map_Kd");
                        }
                        m = new Material(mtl.name(), img != null ? (mtl.argb() & 0xff000000) | 0xffffff : mtl.argb(),
                                img == null ? null : mtl.mapKd(), img, "box", 1,
                                img == null ? 0 : Math.min(64, Math.max(4, img.getWidth())), mtl.glow(), ds, true);
                    }
                    cache.put(f.material(), m);
                }
            }
            if (ds && !m.doubleSided) {
                m = m.withDoubleSided(true);
            }
            int added = Geometry.polygon(out, Geometry.transform(f.pts(), scale, r, at), m, tol);
            if (DEBUG && tol < 1e8 && added > 3) {
                System.out.println("  [mesh] obj face (" + f.group() + ", " + f.pts().size() + " pts): " + added + " plates " + f.pts());
            }
        }
    }

    private BufferedImage textureByPath(String path, String where) throws SourceException {
        if (imageCache.containsKey(path)) {
            return imageCache.get(path);
        }
        byte[] bytes = resolver.file(path);
        BufferedImage img = null;
        if (bytes != null) {
            try {
                img = ImageIO.read(new ByteArrayInputStream(bytes));
            } catch (Exception ignored) {
                img = null;
            }
        }
        if (img == null) {
            warnings.add(where + ": texture " + path + " not found or unreadable; using the material colour");
        }
        imageCache.put(path, img);
        return img;
    }

    private String resolveRelativeTo(String dir, String rel) throws SourceException {
        String[] segs = (dir + rel.replace('\\', '/')).split("/");
        List<String> out = new ArrayList<>();
        for (String s : segs) {
            if (s.isEmpty() || s.equals(".")) {
                continue;
            }
            if (s.equals("..")) {
                if (out.size() <= 2) {
                    throw new SourceException("path \"" + rel + "\" leaves the namespace folder");
                }
                out.remove(out.size() - 1);
            } else {
                out.add(s);
            }
        }
        return String.join("/", out);
    }
}

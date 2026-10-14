package dev.vibecraft.pack.mesh;

import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.internal.LazilyParsedNumber;
import dev.vibecraft.pack.TextureSources;
import dev.vibecraft.pack.TextureSources.SourceException;

/**
 * Compiles text 3D sources of a module's assets into vanilla 26.3 item models:
 * {@code assets/<ns>/.../<name>.shape.json} (primitive scene) and {@code assets/<ns>/.../<name>.obj}
 * (+ .mtl). Output for object {@code <ns>:<name>}:
 * <ul>
 *   <li>{@code models/<name>.json} + {@code items/<name>.json}: the whole object as one item model, fitted in
 *       one block with display transforms (held item, item frame, GUI);</li>
 *   <li>{@code models/<name>/<part>.json} + {@code items/<name>/<part>.json}: one model per part (split into
 *       {@code <part>.2}, {@code .3}... above {@link #CHUNK} elements) in a frame centred on the part's pivot,
 *       for display entities ({@code dev.vibecraft.api.Models} places them, scale and offset from the
 *       manifest {@link ObjectInfo});</li>
 *   <li>{@code textures/item/mesh/<name>.png}: the baked atlas (colours, baked light, textures).</li>
 * </ul>
 * Pure JDK + Gson, deterministic, cached by content.
 */
public final class MeshCompiler {

    /** Elements per part model; bigger parts are split into several models (one display entity each). */
    public static final int CHUNK = 1024;
    /** Hard cap of elements per object. */
    public static final int HARD_MAX = 16000;
    static final double DEV = 23.5; // model coordinates stay within 8 +- 23.5 (client limit -16..32)
    static final String VERSION = "mesh-1";

    /** Reads a file of the pack being built ("assets/ns/..."), sources and compiled outputs; null if absent. */
    public interface Resolver {
        byte[] file(String assetPath);
    }

    /** Per-texel colour of a face, at object point p with object normal n. */
    public interface TexelSource {
        int argb(V3 p, V3 n);
    }

    /**
     * @param models item model ids of this part (several when split), e.g. {@code car:sports_car/wheel_fl}
     * @param pivot  pivot in object coordinates (blocks): the display entity's transformation translation
     * @param unit   display scale making the model 1:1 in blocks
     */
    public record PartInfo(String name, List<String> models, double[] pivot, double unit, int elements) {
    }

    /** Manifest of a compiled object (also shown in GET /api/pack). */
    public record ObjectInfo(String id, String module, String source, String mode, List<PartInfo> parts, String item,
                             double[] min, double[] max, int elements, int quads, int atlasWidth, int atlasHeight,
                             String atlas, List<String> warnings) {
        public double[] size() {
            return new double[] {max[0] - min[0], max[1] - min[1], max[2] - min[2]};
        }
    }

    public record Output(Map<String, byte[]> files, ObjectInfo info, byte[] preview) {
    }

    private record CacheEntry(String sourceHash, Map<String, String> deps, Output out) {
    }

    private static final Map<String, CacheEntry> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> e) {
            return size() > 64;
        }
    };

    private MeshCompiler() {
    }

    public static boolean isSource(String name) {
        return name.endsWith(".shape.json") || name.endsWith(".obj");
    }

    /** Files only read by sources (not put in the pack). */
    public static boolean isAuxiliary(String name) {
        return name.endsWith(".mtl");
    }

    /** "assets/ns/shapes/car.shape.json" -> {"ns", "car"} ("shapes/" or "models/" prefix dropped). */
    public static String[] objectId(String rel) {
        String[] p = rel.split("/", 3);
        String rest = p[2];
        if (rest.startsWith("shapes/")) {
            rest = rest.substring(7);
        } else if (rest.startsWith("models/")) {
            rest = rest.substring(7);
        }
        rest = rest.endsWith(".shape.json") ? rest.substring(0, rest.length() - 11) : rest.substring(0, rest.length() - 4);
        return new String[] {p[1], rest};
    }

    // ================================================================== entry

    public static Output compile(String module, String rel, byte[] data, Resolver resolver) throws SourceException {
        String key = module + "|" + rel;
        String srcHash = sha(data);
        synchronized (CACHE) {
            CacheEntry c = CACHE.get(key);
            if (c != null && c.sourceHash.equals(srcHash) && depsUnchanged(c.deps, resolver)) {
                return c.out;
            }
        }
        Map<String, String> deps = new LinkedHashMap<>();
        Resolver recording = p -> {
            byte[] b = resolver.file(p);
            deps.put(p, b == null ? "-" : sha(b));
            return b;
        };
        Output out = doCompile(module, rel, data, recording);
        synchronized (CACHE) {
            CACHE.put(key, new CacheEntry(srcHash, deps, out));
        }
        return out;
    }

    private static boolean depsUnchanged(Map<String, String> deps, Resolver r) {
        for (Map.Entry<String, String> d : deps.entrySet()) {
            byte[] b = r.file(d.getKey());
            if (!(b == null ? "-" : sha(b)).equals(d.getValue())) {
                return false;
            }
        }
        return true;
    }

    static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ================================================================== pipeline

    private static Output doCompile(String module, String rel, byte[] data, Resolver resolver) throws SourceException {
        List<String> warnings = new ArrayList<>();
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        ShapeSpec spec = rel.endsWith(".obj") ? ShapeSpec.parseObj(rel, text, resolver, warnings)
                : ShapeSpec.parseShape(rel, text, resolver, warnings);
        String[] oid = objectId(rel);
        String ns = oid[0];
        String name = oid[1];

        // pass 1: bounds (coarse), pass 2: real tolerance relative to the object size
        Map<String, List<Elem>> parts = spec.generate(1e9);
        double[] b = bounds(parts.values());
        if (b == null) {
            throw new SourceException("no geometry generated (every shape empty or without faces)");
        }
        double maxDim = Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
        if (maxDim < 1e-6) {
            throw new SourceException("object is empty (zero size)");
        }
        double tol = maxDim / (spec.segments * 4.0);
        parts = spec.generate(tol);
        b = bounds(parts.values());

        // origin and size normalisation
        V3 shift = switch (spec.origin) {
            case "bottom" -> new V3(-(b[0] + b[3]) / 2, -b[1], -(b[2] + b[5]) / 2);
            case "center" -> new V3(-(b[0] + b[3]) / 2, -(b[1] + b[4]) / 2, -(b[2] + b[5]) / 2);
            default -> V3.ZERO;
        };
        double k = spec.size > 0 ? spec.size / maxDim : 1;
        Map<String, V3> pivots = new LinkedHashMap<>();
        for (ShapeSpec.PartSrc p : spec.parts) {
            if (p.pivot() != null) {
                pivots.put(p.name(), p.pivot().add(shift).mul(k));
            }
        }
        if (!shift.equals(V3.ZERO) || k != 1) {
            for (List<Elem> list : parts.values()) {
                for (Elem e : list) {
                    e.c = e.c.add(shift).mul(k);
                    e.h = e.h.mul(k);
                }
            }
            tol *= k;
        }
        double objDim = maxDim * k;

        // hidden faces
        String mode = spec.mode;
        for (List<Elem> list : parts.values()) {
            cull(list, objDim * 1e-6);
        }
        int total = count(parts);
        if (mode.equals("auto")) {
            mode = total <= spec.maxElements ? "exact" : "voxel";
        }
        if (mode.equals("exact")) {
            for (int attempt = 0; attempt < 3 && total > spec.maxElements; attempt++) {
                tol *= 2;
                double kk = k;
                V3 sh = shift;
                parts = spec.generate(tol / kk);
                for (List<Elem> list : parts.values()) {
                    for (Elem e : list) {
                        e.c = e.c.add(sh).mul(kk);
                        e.h = e.h.mul(kk);
                    }
                    cull(list, objDim * 1e-6);
                }
                total = count(parts);
            }
            if (total > spec.maxElements) {
                warnings.add(total + " elements (more than max_elements " + spec.maxElements
                        + "): heavy to render; use \"detail\": \"low\", fewer segments, or \"mode\": \"voxel\"");
            }
        } else {
            double vs = objDim / spec.resolution;
            Map<String, List<Elem>> vox = new LinkedHashMap<>();
            for (Map.Entry<String, List<Elem>> p : parts.entrySet()) {
                vox.put(p.getKey(), Voxelizer.voxelize(p.getValue(), vs));
            }
            parts = vox;
            total = count(parts);
            if (total > spec.maxElements) {
                warnings.add(total + " voxel faces (more than max_elements " + spec.maxElements + "): lower \"resolution\"");
            }
        }
        if (total > HARD_MAX) {
            throw new SourceException(total + " elements: too many (max " + HARD_MAX + "); lower \"detail\"/\"resolution\" or simplify");
        }
        if (total == 0) {
            throw new SourceException("no visible face left");
        }
        b = bounds(parts.values());

        // frames
        M3 flip = M3.diag(-1, 1, -1); // the item display renderer turns items by 180 degrees around y: undo it
        Map<String, double[]> frames = new LinkedHashMap<>(); // part -> {px, py, pz, s}
        for (Map.Entry<String, List<Elem>> p : parts.entrySet()) {
            double[] pb = bounds(List.of(p.getValue()));
            if (pb == null) {
                continue;
            }
            V3 pivot = pivots.getOrDefault(p.getKey(), new V3((pb[0] + pb[3]) / 2, (pb[1] + pb[4]) / 2, (pb[2] + pb[5]) / 2));
            double dev = deviation(p.getValue(), pivot, flip);
            frames.put(p.getKey(), new double[] {pivot.x(), pivot.y(), pivot.z(), DEV / Math.max(dev, 1e-9)});
        }
        V3 centre = new V3((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
        double ext = Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
        List<Elem> all = new ArrayList<>();
        parts.values().forEach(all::addAll);
        double sMerged = Math.min(16 / Math.max(ext, 1e-9), DEV / Math.max(deviation(all, centre, flip), 1e-9));

        // bake + write (textured densities halved until the atlas fits)
        String atlasId = ns + ":item/mesh/" + name;
        for (double factor = 1; ; factor /= 2) {
            Baker baker = new Baker(spec.light);
            Map<String, List<ModelElem>> partModels = new LinkedHashMap<>();
            for (Map.Entry<String, List<Elem>> p : parts.entrySet()) {
                double[] f = frames.get(p.getKey());
                if (f == null) {
                    continue;
                }
                partModels.put(p.getKey(), toModel(p.getValue(), new V3(f[0], f[1], f[2]), f[3], flip, baker, factor));
            }
            List<ModelElem> merged = toModel(all, centre, sMerged, flip, baker, factor);
            if (!baker.pack(spec.maxTexture)) {
                if (factor > 0.1 && hasTextured(all)) {
                    continue;
                }
                throw new SourceException("baked texture atlas too large (" + baker.width + "x" + baker.height + ", max "
                        + spec.maxTexture + "): lower \"texel\" of textured materials, \"resolution\", or raise \"max_texture\"");
            }
            if (factor < 1) {
                warnings.add("texture density of textured materials reduced to " + Math.round(factor * 100)
                        + "% to fit the atlas in " + spec.maxTexture + "x" + spec.maxTexture);
            }
            return write(module, rel, ns, name, mode, spec, parts, partModels, merged, frames, baker, atlasId, b, warnings);
        }
    }

    private static boolean hasTextured(List<Elem> all) {
        for (Elem e : all) {
            for (Material m : e.faces) {
                if (m != null && m.textured()) {
                    return true;
                }
            }
        }
        return false;
    }

    static int count(Map<String, List<Elem>> parts) {
        int n = 0;
        for (List<Elem> l : parts.values()) {
            n += l.size();
        }
        return n;
    }

    /** {minx, miny, minz, maxx, maxy, maxz} over element corners, null if empty. */
    static double[] bounds(Iterable<List<Elem>> lists) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        boolean any = false;
        for (List<Elem> l : lists) {
            for (Elem e : l) {
                for (V3 c : e.corners()) {
                    any = true;
                    for (int i = 0; i < 3; i++) {
                        b[i] = Math.min(b[i], c.get(i));
                        b[i + 3] = Math.max(b[i + 3], c.get(i));
                    }
                }
            }
        }
        return any ? b : null;
    }

    /** Max distance (per axis) of from/to and rotated corners from the pivot, in object units. */
    private static double deviation(List<Elem> elems, V3 pivot, M3 flip) {
        double dev = 0;
        for (Elem e : elems) {
            V3 c = flip.apply(e.c.sub(pivot)).abs();
            M3 rm = flip.mul(e.r);
            V3 h = rm.isSignedPermutation() ? permuted(rm, e.h) : e.h;
            dev = Math.max(dev, c.max() + h.max());
            for (V3 k : e.corners()) {
                dev = Math.max(dev, k.sub(pivot).abs().max());
            }
        }
        return dev;
    }

    private static V3 permuted(M3 r, V3 h) {
        double[] o = new double[3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                o[i] += Math.abs(r.get(i, j)) * h.get(j);
            }
        }
        return new V3(o[0], o[1], o[2]);
    }

    /** Removes faces fully inside a solid element of the same list (and elements left without faces). */
    static void cull(List<Elem> list, double margin) {
        List<Elem> solids = new ArrayList<>();
        List<double[]> boxes = new ArrayList<>();
        for (Elem e : list) {
            if (e.solid) {
                solids.add(e);
                double[] bb = bounds(List.of(List.of(e)));
                boxes.add(bb);
            }
        }
        if (solids.isEmpty() || (long) list.size() * solids.size() > 40_000_000L) {
            return;
        }
        for (Elem e : list) {
            for (int d = 0; d < 6; d++) {
                if (e.faces[d] == null) {
                    continue;
                }
                V3[] pts = facePoints(e, d);
                for (int s = 0; s < solids.size(); s++) {
                    Elem o = solids.get(s);
                    if (o == e) {
                        continue;
                    }
                    double[] bb = boxes.get(s);
                    V3 p0 = pts[0];
                    if (p0.x() < bb[0] || p0.y() < bb[1] || p0.z() < bb[2] || p0.x() > bb[3] || p0.y() > bb[4] || p0.z() > bb[5]) {
                        continue;
                    }
                    boolean inside = true;
                    for (V3 p : pts) {
                        if (!o.containsStrict(p, margin)) {
                            inside = false;
                            break;
                        }
                    }
                    if (inside) {
                        e.faces[d] = null;
                        e.texels[d] = null;
                        break;
                    }
                }
            }
        }
        list.removeIf(e -> !e.hasFaces());
    }

    /** Centre + 4 corners (pulled 2% towards the centre) of a face. */
    private static V3[] facePoints(Elem e, int d) {
        int ax = Elem.axisOf(d);
        int a1 = (ax + 1) % 3;
        int a2 = (ax + 2) % 3;
        double sg = Elem.signOf(d);
        V3[] out = new V3[5];
        out[0] = e.toWorld(V3.ZERO.with(ax, sg * e.h.get(ax)));
        int i = 1;
        for (int s1 = -1; s1 <= 1; s1 += 2) {
            for (int s2 = -1; s2 <= 1; s2 += 2) {
                out[i++] = e.toWorld(V3.ZERO.with(ax, sg * e.h.get(ax)).with(a1, s1 * 0.98 * e.h.get(a1)).with(a2, s2 * 0.98 * e.h.get(a2)));
            }
        }
        return out;
    }

    // ================================================================== model space

    /** Output element: axis-aligned box in model pixels, optional Euler rotation, faces -> atlas tiles. */
    record ModelElem(double[] from, double[] to, double[] origin, double[] euler, int[] tiles, int light) {
    }

    private static List<ModelElem> toModel(List<Elem> elems, V3 pivot, double s, M3 flip, Baker baker, double factor) {
        List<ModelElem> out = new ArrayList<>();
        M3 flipT = flip.transpose();
        for (Elem e : elems) {
            V3 cm = new V3(8, 8, 8).add(flip.apply(e.c.sub(pivot)).mul(s));
            M3 rm = flip.mul(e.r);
            V3 hm = e.h.mul(s);
            M3 reff;
            V3 heff;
            int[] dirMap = new int[6]; // source dir -> model dir
            if (rm.isSignedPermutation()) {
                reff = M3.I;
                heff = permuted(rm, hm);
                for (int d = 0; d < 6; d++) {
                    V3 n = rm.apply(Elem.localNormal(d));
                    int ax = Math.abs(n.x()) > 0.5 ? 0 : Math.abs(n.y()) > 0.5 ? 1 : 2;
                    dirMap[d] = Elem.dirOf(ax, n.get(ax) > 0 ? 1 : -1);
                }
            } else {
                reff = rm;
                heff = hm;
                for (int d = 0; d < 6; d++) {
                    dirMap[d] = d;
                }
            }
            int[] tiles = new int[6];
            Arrays.fill(tiles, -1);
            int light = 0;
            for (int d = 0; d < 6; d++) {
                Material m = e.faces[d];
                if (m == null) {
                    continue;
                }
                light = Math.max(light, m.glow);
                int md = dirMap[d];
                tiles[md] = bakeFace(baker, m, e.texels[d], md, cm, reff, heff, pivot, s, flipT, factor);
            }
            double[] from = {cm.x() - heff.x(), cm.y() - heff.y(), cm.z() - heff.z()};
            double[] to = {cm.x() + heff.x(), cm.y() + heff.y(), cm.z() + heff.z()};
            double[] euler = reff == M3.I ? null : reff.toEuler();
            out.add(new ModelElem(from, to, new double[] {cm.x(), cm.y(), cm.z()}, euler, tiles, light));
        }
        return out;
    }

    /** Local face point (relative to the centre) for face parameters (a, b) in 0..1, Minecraft UV convention. */
    static V3 faceLocal(int dir, V3 h, double a, double b) {
        double hx = h.x();
        double hy = h.y();
        double hz = h.z();
        return switch (dir) {
            case Elem.SOUTH -> new V3(-hx + a * 2 * hx, hy - b * 2 * hy, hz);
            case Elem.NORTH -> new V3(hx - a * 2 * hx, hy - b * 2 * hy, -hz);
            case Elem.EAST -> new V3(hx, hy - b * 2 * hy, hz - a * 2 * hz);
            case Elem.WEST -> new V3(-hx, hy - b * 2 * hy, -hz + a * 2 * hz);
            case Elem.UP -> new V3(-hx + a * 2 * hx, hy, -hz + b * 2 * hz);
            default -> new V3(-hx + a * 2 * hx, -hy, hz - b * 2 * hz);
        };
    }

    /** {u size, v size} of a face (model units). */
    static double[] faceDims(int dir, V3 h) {
        return switch (dir) {
            case Elem.SOUTH, Elem.NORTH -> new double[] {2 * h.x(), 2 * h.y()};
            case Elem.EAST, Elem.WEST -> new double[] {2 * h.z(), 2 * h.y()};
            default -> new double[] {2 * h.x(), 2 * h.z()};
        };
    }

    private static int bakeFace(Baker baker, Material m, TexelSource src, int md, V3 cm, M3 reff, V3 heff, V3 pivot, double s,
                                M3 flipT, double factor) {
        V3 nObj = flipT.apply(reff.apply(Elem.localNormal(md))).norm();
        double f = baker.shade(nObj, m);
        if (src == null && !m.textured()) {
            return baker.cell(Baker.applyShade(m.argb, f));
        }
        double[] dims = faceDims(md, heff);
        double density = src != null ? m.density : m.density * factor; // texels per block
        int w = (int) Math.max(1, Math.min(Baker.MAX_PATCH, Math.round(dims[0] / s * density)));
        int h = (int) Math.max(1, Math.min(Baker.MAX_PATCH, Math.round(dims[1] / s * density)));
        if (m.textured() && m.mapping.equals("face")) {
            w = Math.max(w, 1);
            h = Math.max(h, 1);
        }
        int[] px = new int[w * h];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                double a = (i + 0.5) / w;
                double bb = (j + 0.5) / h;
                V3 mp = cm.add(reff.apply(faceLocal(md, heff, a, bb)));
                V3 op = pivot.add(flipT.apply(mp.sub(new V3(8, 8, 8)).mul(1 / s)));
                int c = src != null ? src.argb(op, nObj) : Baker.sample(m, op, nObj, a, bb);
                px[j * w + i] = Baker.applyShade(c, f);
            }
        }
        return baker.patch(px, w, h, src == null);
    }

    // ================================================================== output

    private static Output write(String module, String rel, String ns, String name, String mode, ShapeSpec spec,
                                Map<String, List<Elem>> parts, Map<String, List<ModelElem>> partModels, List<ModelElem> merged,
                                Map<String, double[]> frames, Baker baker, String atlasId, double[] b, List<String> warnings) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String atlasPath = "assets/" + ns + "/textures/item/mesh/" + name + ".png";
        BufferedImage atlas = baker.image();
        files.put(atlasPath, TextureSources.png(atlas));
        List<PartInfo> infos = new ArrayList<>();
        int quads = 0;
        for (Map.Entry<String, List<ModelElem>> p : partModels.entrySet()) {
            List<ModelElem> els = p.getValue();
            List<String> ids = new ArrayList<>();
            for (int c = 0; c * CHUNK < els.size(); c++) {
                String sub = name + "/" + p.getKey() + (c == 0 ? "" : "." + (c + 1));
                List<ModelElem> chunk = els.subList(c * CHUNK, Math.min(els.size(), (c + 1) * CHUNK));
                JsonObject model = modelJson(chunk, baker, atlasId, null);
                files.put("assets/" + ns + "/models/" + sub + ".json", bytes(model));
                files.put("assets/" + ns + "/items/" + sub + ".json", bytes(itemJson(ns + ":" + sub)));
                ids.add(ns + ":" + sub);
            }
            double[] f = frames.get(p.getKey());
            for (ModelElem me : els) {
                for (int t : me.tiles) {
                    if (t >= 0) {
                        quads++;
                    }
                }
            }
            infos.add(new PartInfo(p.getKey(), List.copyOf(ids), new double[] {round(f[0], 5), round(f[1], 5), round(f[2], 5)},
                    16 / f[3], els.size()));
        }
        JsonObject mergedModel = modelJson(merged, baker, atlasId, display(spec.display, warnings));
        files.put("assets/" + ns + "/models/" + name + ".json", bytes(mergedModel));
        files.put("assets/" + ns + "/items/" + name + ".json", bytes(itemJson(ns + ":" + name)));
        int total = 0;
        for (List<Elem> l : parts.values()) {
            total += l.size();
        }
        ObjectInfo info = new ObjectInfo(ns + ":" + name, module, rel, mode, List.copyOf(infos), ns + ":" + name,
                new double[] {round(b[0], 4), round(b[1], 4), round(b[2], 4)},
                new double[] {round(b[3], 4), round(b[4], 4), round(b[5], 4)},
                total, quads, baker.width, baker.height, atlasId, List.copyOf(warnings));
        byte[] preview = null;
        try {
            preview = Preview.render(mergedModel, atlas, ns + ":" + name + "  " + total + " elements, " + mode);
        } catch (RuntimeException | LinkageError e) {
            warnings.add("preview not rendered: " + e);
        }
        return new Output(files, info, preview);
    }

    private static JsonObject modelJson(List<ModelElem> els, Baker baker, String atlasId, JsonObject display) {
        JsonObject root = new JsonObject();
        JsonObject tex = new JsonObject();
        tex.addProperty("0", atlasId);
        tex.addProperty("particle", atlasId);
        root.add("textures", tex);
        JsonArray arr = new JsonArray();
        for (ModelElem e : els) {
            JsonObject o = new JsonObject();
            o.add("from", nums(e.from, 4));
            o.add("to", nums(e.to, 4));
            if (e.euler != null) {
                JsonObject r = new JsonObject();
                r.add("origin", nums(e.origin, 4));
                String[] k = {"x", "y", "z"};
                boolean any = false;
                for (int i = 0; i < 3; i++) {
                    double v = round(e.euler[i], 4);
                    if (v != 0) {
                        r.add(k[i], num(v));
                        any = true;
                    }
                }
                if (any) {
                    o.add("rotation", r);
                }
            }
            JsonObject faces = new JsonObject();
            for (int d = 0; d < 6; d++) {
                if (e.tiles[d] < 0) {
                    continue;
                }
                JsonObject f = new JsonObject();
                f.add("uv", nums(baker.uv(e.tiles[d]), 5));
                f.addProperty("texture", "#0");
                faces.add(Elem.DIR[d], f);
            }
            o.add("faces", faces);
            if (e.light > 0) {
                o.addProperty("light_emission", e.light);
            }
            arr.add(o);
        }
        root.add("elements", arr);
        if (display != null) {
            root.add("display", display);
        }
        return root;
    }

    /**
     * Default display transforms of the whole-object model (fitted in one block), overridable by "display":
     * those of vanilla block items (block/block.json). The object's front (+z of the source) is the model's north
     * face, like a furnace front, so it shows the same way.
     */
    private static JsonObject display(JsonObject override, List<String> warnings) {
        JsonObject d = new JsonObject();
        d.add("gui", transform(new double[] {30, 225, 0}, new double[] {0, 0, 0}, 0.625));
        d.add("ground", transform(new double[] {0, 0, 0}, new double[] {0, 3, 0}, 0.25));
        d.add("fixed", transform(new double[] {0, 0, 0}, new double[] {0, 0, 0}, 0.5));
        d.add("head", transform(new double[] {0, 0, 0}, new double[] {0, 0, 0}, 0.6));
        d.add("thirdperson_righthand", transform(new double[] {75, 45, 0}, new double[] {0, 2.5, 0}, 0.375));
        d.add("thirdperson_lefthand", transform(new double[] {75, 45, 0}, new double[] {0, 2.5, 0}, 0.375));
        d.add("firstperson_righthand", transform(new double[] {0, 45, 0}, new double[] {0, 0, 0}, 0.4));
        d.add("firstperson_lefthand", transform(new double[] {0, 225, 0}, new double[] {0, 0, 0}, 0.4));
        if (override != null) {
            for (Map.Entry<String, JsonElement> e : override.entrySet()) {
                d.add(e.getKey(), e.getValue());
                if (e.getValue().isJsonObject()) {
                    JsonObject t = e.getValue().getAsJsonObject();
                    checkClamp(t, "translation", 80, e.getKey(), warnings);
                    checkClamp(t, "scale", 4, e.getKey(), warnings);
                }
            }
        }
        return d;
    }

    private static void checkClamp(JsonObject t, String k, double lim, String ctx, List<String> warnings) {
        if (t.has(k) && t.get(k).isJsonArray()) {
            for (JsonElement v : t.getAsJsonArray(k)) {
                if (v.isJsonPrimitive() && Math.abs(v.getAsDouble()) > lim) {
                    warnings.add("display." + ctx + "." + k + " is clamped by the client to +-" + lim);
                    return;
                }
            }
        }
    }

    private static JsonObject transform(double[] rot, double[] tr, double scale) {
        JsonObject o = new JsonObject();
        o.add("rotation", nums(rot, 3));
        o.add("translation", nums(tr, 3));
        o.add("scale", nums(new double[] {scale, scale, scale}, 4));
        return o;
    }

    private static JsonObject itemJson(String model) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "minecraft:model");
        m.addProperty("model", model);
        JsonObject root = new JsonObject();
        root.add("model", m);
        return root;
    }

    private static byte[] bytes(JsonObject o) {
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    static double round(double v, int digits) {
        if (!Double.isFinite(v)) {
            return 0;
        }
        double r = new BigDecimal(v).setScale(digits, RoundingMode.HALF_UP).doubleValue();
        return r == 0 ? 0 : r; // no -0
    }

    static JsonPrimitive num(double v) {
        String s = new BigDecimal(Double.toString(v)).stripTrailingZeros().toPlainString();
        if (s.equals("-0")) {
            s = "0";
        }
        return new JsonPrimitive(new LazilyParsedNumber(s));
    }

    private static JsonArray nums(double[] v, int digits) {
        JsonArray a = new JsonArray();
        for (double d : v) {
            a.add(num(round(d, digits)));
        }
        return a;
    }

    /** Object manifest as JSON (GET /api/pack). */
    public static Map<String, Object> describe(ObjectInfo i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("module", i.module());
        m.put("source", i.source());
        m.put("mode", i.mode());
        m.put("elements", i.elements());
        m.put("quads", i.quads());
        double[] s = i.size();
        m.put("sizeBlocks", new double[] {round(s[0], 3), round(s[1], 3), round(s[2], 3)});
        m.put("atlas", i.atlasWidth() + "x" + i.atlasHeight());
        Map<String, Object> parts = new LinkedHashMap<>();
        for (PartInfo p : i.parts()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("elements", p.elements());
            pm.put("models", p.models());
            pm.put("pivot", p.pivot());
            parts.put(p.name(), pm);
        }
        m.put("parts", parts);
        m.put("item", i.item());
        if (!i.warnings().isEmpty()) {
            m.put("warnings", i.warnings());
        }
        return m;
    }
}

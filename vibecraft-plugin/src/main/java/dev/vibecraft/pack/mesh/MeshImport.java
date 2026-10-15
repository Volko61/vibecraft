package dev.vibecraft.pack.mesh;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Turns a dense, UV-textured mesh (e.g. a Meshy AI OBJ + base colour texture) into an OBJ the
 * {@link MeshCompiler} handles well: the texture is baked into per-face colours (sampled at the UVs, the
 * compiler ignores UVs), the mesh is decimated by vertex clustering until it has at most {@code maxFaces}
 * triangles, colours are reduced to a small palette (one MTL material per colour) and the options comment
 * {@code # vibecraft {"size":..,"mode":"voxel",...}} is written. Pure JDK.
 */
public final class MeshImport {

    /** Options: size = largest dimension in blocks, resolution = voxel grid of the compiler. */
    public record Options(String name, int maxFaces, int maxColors, double size, int resolution, String comment) {
    }

    /** Output files and statistics. */
    public record Result(String obj, String mtl, int inFaces, int outFaces, int colors, boolean textured, int grid) {
    }

    private record Tri(int a, int b, int c, int argb, double area) {
    }

    private MeshImport() {
    }

    public static Result convert(byte[] objBytes, byte[] mtlBytes, byte[] texture, Options o) {
        String text = new String(objBytes, StandardCharsets.UTF_8);
        List<double[]> v = new ArrayList<>();
        List<int[]> vColor = new ArrayList<>();
        List<double[]> vt = new ArrayList<>();
        Map<String, Integer> kd = mtlBytes == null ? Map.of() : parseKd(new String(mtlBytes, StandardCharsets.UTF_8));
        BufferedImage img = null;
        if (texture != null) {
            try {
                img = ImageIO.read(new ByteArrayInputStream(texture));
            } catch (Exception ignored) {
                img = null;
            }
        }
        List<Tri> tris = new ArrayList<>();
        String mat = null;
        for (String raw : text.split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] t = line.split("\\s+");
            try {
                switch (t[0]) {
                    case "v" -> {
                        v.add(new double[] {d(t[1]), d(t[2]), d(t[3])});
                        if (t.length >= 7) {
                            vColor.add(new int[] {c255(d(t[4])), c255(d(t[5])), c255(d(t[6]))});
                        } else {
                            vColor.add(null);
                        }
                    }
                    case "vt" -> vt.add(new double[] {d(t[1]), t.length > 2 ? d(t[2]) : 0});
                    case "usemtl" -> mat = t.length > 1 ? t[1] : null;
                    case "f" -> {
                        int n = t.length - 1;
                        if (n < 3) {
                            continue;
                        }
                        int[] vi = new int[n];
                        int[] ti = new int[n];
                        for (int i = 0; i < n; i++) {
                            String[] p = t[i + 1].split("/");
                            vi[i] = idx(p[0], v.size());
                            ti[i] = p.length > 1 && !p[1].isEmpty() ? idx(p[1], vt.size()) : -1;
                        }
                        for (int i = 1; i + 1 < n; i++) {
                            int a = vi[0], b = vi[i], c = vi[i + 1];
                            if (a < 0 || b < 0 || c < 0 || a >= v.size() || b >= v.size() || c >= v.size()) {
                                continue;
                            }
                            double area = area(v.get(a), v.get(b), v.get(c));
                            int col = color(img, vt, new int[] {ti[0], ti[i], ti[i + 1]}, vColor, new int[] {a, b, c},
                                    mat == null ? null : kd.get(mat));
                            tris.add(new Tri(a, b, c, col, area));
                        }
                    }
                    default -> {
                    }
                }
            } catch (RuntimeException ignored) {
                // malformed line: skip it
            }
        }
        if (tris.isEmpty()) {
            throw new IllegalArgumentException("le modèle OBJ ne contient aucune face");
        }
        // ---- bounds
        double[] mn = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] mx = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (double[] p : v) {
            for (int k = 0; k < 3; k++) {
                mn[k] = Math.min(mn[k], p[k]);
                mx[k] = Math.max(mx[k], p[k]);
            }
        }
        double maxDim = Math.max(mx[0] - mn[0], Math.max(mx[1] - mn[1], mx[2] - mn[2]));
        if (!(maxDim > 0)) {
            throw new IllegalArgumentException("le modèle OBJ est vide (taille nulle)");
        }
        // ---- decimation by vertex clustering
        int target = Math.max(100, o.maxFaces());
        List<double[]> outV = v;
        List<Tri> outT = tris;
        int usedGrid = 0;
        if (tris.size() > target) {
            int[] grids = {160, 128, 96, 80, 64, 56, 48, 40, 32, 28, 24, 20, 16, 12, 8};
            for (int g : grids) {
                List<double[]> cv = new ArrayList<>();
                List<Tri> ct = cluster(v, tris, mn, maxDim / g, cv);
                outV = cv;
                outT = ct;
                usedGrid = g;
                if (ct.size() <= target) {
                    break;
                }
            }
        }
        // ---- palette
        List<Integer> palette = palette(outT, Math.max(2, Math.min(64, o.maxColors())));
        Map<Integer, Integer> nearestCache = new HashMap<>();
        int[] matOf = new int[outT.size()];
        for (int i = 0; i < outT.size(); i++) {
            int c = outT.get(i).argb();
            matOf[i] = nearestCache.computeIfAbsent(c, x -> nearest(palette, x));
        }
        // ---- write
        String name = o.name();
        StringBuilder obj = new StringBuilder(outV.size() * 32 + outT.size() * 20);
        if (o.comment() != null && !o.comment().isBlank()) {
            for (String l : o.comment().split("\n")) {
                obj.append("# ").append(l.replace('\r', ' ')).append('\n');
            }
        }
        obj.append(String.format(Locale.ROOT, "# vibecraft {\"size\": %.3f, \"mode\": \"voxel\", \"resolution\": %d, \"light\": 0.5}%n",
                o.size(), o.resolution()));
        obj.append("mtllib ").append(name).append(".mtl\n");
        // only referenced vertices, renumbered
        int[] remap = new int[outV.size()];
        java.util.Arrays.fill(remap, -1);
        int nv = 0;
        for (Tri t : outT) {
            for (int idx : new int[] {t.a(), t.b(), t.c()}) {
                if (remap[idx] < 0) {
                    remap[idx] = ++nv;
                    double[] p = outV.get(idx);
                    obj.append(String.format(Locale.ROOT, "v %.5f %.5f %.5f%n", p[0], p[1], p[2]));
                }
            }
        }
        Map<Integer, List<Integer>> byMat = new LinkedHashMap<>();
        for (int i = 0; i < outT.size(); i++) {
            byMat.computeIfAbsent(matOf[i], k -> new ArrayList<>()).add(i);
        }
        StringBuilder mtl = new StringBuilder();
        mtl.append("# VibeCraft: colours baked from the texture (one material per palette colour)\n");
        for (Map.Entry<Integer, List<Integer>> e : byMat.entrySet()) {
            int c = palette.get(e.getKey());
            String m = "c" + e.getKey();
            mtl.append("newmtl ").append(m).append('\n').append(String.format(Locale.ROOT, "Kd %.4f %.4f %.4f%n%n",
                    ((c >> 16) & 255) / 255.0, ((c >> 8) & 255) / 255.0, (c & 255) / 255.0));
            obj.append("usemtl ").append(m).append('\n');
            for (int i : e.getValue()) {
                Tri t = outT.get(i);
                obj.append("f ").append(remap[t.a()]).append(' ').append(remap[t.b()]).append(' ').append(remap[t.c()]).append('\n');
            }
        }
        return new Result(obj.toString(), mtl.toString(), tris.size(), outT.size(), byMat.size(), img != null, usedGrid);
    }

    // ------------------------------------------------------------------ clustering

    private static List<Tri> cluster(List<double[]> v, List<Tri> tris, double[] mn, double cell, List<double[]> outV) {
        Map<Long, Integer> cellIndex = new HashMap<>();
        int[] map = new int[v.size()];
        List<double[]> sums = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < v.size(); i++) {
            double[] p = v.get(i);
            long ix = (long) Math.floor((p[0] - mn[0]) / cell);
            long iy = (long) Math.floor((p[1] - mn[1]) / cell);
            long iz = (long) Math.floor((p[2] - mn[2]) / cell);
            long key = (ix * 2_097_152L + iy) * 2_097_152L + iz;
            Integer ci = cellIndex.get(key);
            if (ci == null) {
                ci = sums.size();
                cellIndex.put(key, ci);
                sums.add(new double[3]);
                counts.add(0);
            }
            double[] s = sums.get(ci);
            s[0] += p[0];
            s[1] += p[1];
            s[2] += p[2];
            counts.set(ci, counts.get(ci) + 1);
            map[i] = ci;
        }
        for (int i = 0; i < sums.size(); i++) {
            double[] s = sums.get(i);
            int n = counts.get(i);
            outV.add(new double[] {s[0] / n, s[1] / n, s[2] / n});
        }
        // merge triangles that collapse onto the same cluster triple; colour = area-weighted mean
        Map<String, double[]> acc = new LinkedHashMap<>(); // key -> {a,b,c, wsum, r, g, b}
        for (Tri t : tris) {
            int a = map[t.a()], b = map[t.b()], c = map[t.c()];
            if (a == b || b == c || a == c) {
                continue;
            }
            int[] s = {a, b, c};
            java.util.Arrays.sort(s);
            String key = s[0] + ":" + s[1] + ":" + s[2];
            double w = Math.max(1e-12, t.area());
            double[] x = acc.get(key);
            if (x == null) {
                x = new double[] {a, b, c, 0, 0, 0, 0};
                acc.put(key, x);
            }
            x[3] += w;
            x[4] += w * ((t.argb() >> 16) & 255);
            x[5] += w * ((t.argb() >> 8) & 255);
            x[6] += w * (t.argb() & 255);
        }
        List<Tri> out = new ArrayList<>(acc.size());
        for (double[] x : acc.values()) {
            int r = (int) Math.round(x[4] / x[3]), g = (int) Math.round(x[5] / x[3]), bl = (int) Math.round(x[6] / x[3]);
            int a = (int) x[0], b = (int) x[1], c = (int) x[2];
            out.add(new Tri(a, b, c, 0xff000000 | r << 16 | g << 8 | bl, area(outV.get(a), outV.get(b), outV.get(c))));
        }
        return out;
    }

    // ------------------------------------------------------------------ colours

    private static int color(BufferedImage img, List<double[]> vt, int[] ti, List<int[]> vColor, int[] vi, Integer kd) {
        if (img != null && ti[0] >= 0 && ti[1] >= 0 && ti[2] >= 0 && ti[0] < vt.size() && ti[1] < vt.size() && ti[2] < vt.size()) {
            double[] a = vt.get(ti[0]), b = vt.get(ti[1]), c = vt.get(ti[2]);
            double[][] samples = {
                    {(a[0] + b[0] + c[0]) / 3, (a[1] + b[1] + c[1]) / 3},
                    {(4 * a[0] + b[0] + c[0]) / 6, (4 * a[1] + b[1] + c[1]) / 6},
                    {(a[0] + 4 * b[0] + c[0]) / 6, (a[1] + 4 * b[1] + c[1]) / 6},
                    {(a[0] + b[0] + 4 * c[0]) / 6, (a[1] + b[1] + 4 * c[1]) / 6},
            };
            double r = 0, g = 0, bl = 0;
            for (double[] uv : samples) {
                int p = sample(img, uv[0], uv[1]);
                r += (p >> 16) & 255;
                g += (p >> 8) & 255;
                bl += p & 255;
            }
            return 0xff000000 | (int) Math.round(r / 4) << 16 | (int) Math.round(g / 4) << 8 | (int) Math.round(bl / 4);
        }
        if (vColor.get(vi[0]) != null && vColor.get(vi[1]) != null && vColor.get(vi[2]) != null) {
            int[] s = new int[3];
            for (int i : vi) {
                int[] c = vColor.get(i);
                s[0] += c[0];
                s[1] += c[1];
                s[2] += c[2];
            }
            return 0xff000000 | (s[0] / 3) << 16 | (s[1] / 3) << 8 | (s[2] / 3);
        }
        return kd != null ? kd : 0xffb0b0b0;
    }

    private static int sample(BufferedImage img, double u, double v) {
        double uu = u - Math.floor(u);
        double vv = v - Math.floor(v);
        int x = Math.min(img.getWidth() - 1, Math.max(0, (int) (uu * img.getWidth())));
        int y = Math.min(img.getHeight() - 1, Math.max(0, (int) ((1 - vv) * img.getHeight())));
        return img.getRGB(x, y);
    }

    /** Area-weighted median cut. */
    static List<Integer> palette(List<Tri> tris, int max) {
        Map<Integer, Double> w = new HashMap<>();
        for (Tri t : tris) {
            w.merge(t.argb() & 0xffffff, Math.max(1e-9, t.area()), Double::sum);
        }
        List<int[]> colors = new ArrayList<>(); // rgb + weight index
        List<Double> weights = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : w.entrySet()) {
            int c = e.getKey();
            colors.add(new int[] {(c >> 16) & 255, (c >> 8) & 255, c & 255, weights.size()});
            weights.add(e.getValue());
        }
        List<List<int[]>> boxes = new ArrayList<>();
        boxes.add(colors);
        while (boxes.size() < max) {
            int best = -1;
            int bestRange = 0;
            int bestCh = 0;
            for (int i = 0; i < boxes.size(); i++) {
                List<int[]> b = boxes.get(i);
                if (b.size() < 2) {
                    continue;
                }
                for (int ch = 0; ch < 3; ch++) {
                    int lo = 255, hi = 0;
                    for (int[] c : b) {
                        lo = Math.min(lo, c[ch]);
                        hi = Math.max(hi, c[ch]);
                    }
                    if (hi - lo > bestRange) {
                        bestRange = hi - lo;
                        best = i;
                        bestCh = ch;
                    }
                }
            }
            if (best < 0 || bestRange < 6) {
                break;
            }
            List<int[]> b = boxes.remove(best);
            int ch = bestCh;
            b.sort(Comparator.comparingInt(c -> c[ch]));
            double total = 0;
            for (int[] c : b) {
                total += weights.get(c[3]);
            }
            double acc = 0;
            int cut = 1;
            for (int i = 0; i < b.size() - 1; i++) {
                acc += weights.get(b.get(i)[3]);
                if (acc >= total / 2) {
                    cut = i + 1;
                    break;
                }
                cut = i + 1;
            }
            boxes.add(new ArrayList<>(b.subList(0, cut)));
            boxes.add(new ArrayList<>(b.subList(cut, b.size())));
        }
        List<Integer> out = new ArrayList<>();
        for (List<int[]> b : boxes) {
            double r = 0, g = 0, bl = 0, t = 0;
            for (int[] c : b) {
                double x = weights.get(c[3]);
                r += c[0] * x;
                g += c[1] * x;
                bl += c[2] * x;
                t += x;
            }
            if (t > 0) {
                out.add(0xff000000 | (int) Math.round(r / t) << 16 | (int) Math.round(g / t) << 8 | (int) Math.round(bl / t));
            }
        }
        if (out.isEmpty()) {
            out.add(0xffb0b0b0);
        }
        return out;
    }

    private static int nearest(List<Integer> palette, int c) {
        int best = 0;
        long bd = Long.MAX_VALUE;
        for (int i = 0; i < palette.size(); i++) {
            int p = palette.get(i);
            long dr = ((p >> 16) & 255) - ((c >> 16) & 255);
            long dg = ((p >> 8) & 255) - ((c >> 8) & 255);
            long db = (p & 255) - (c & 255);
            long dd = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
            if (dd < bd) {
                bd = dd;
                best = i;
            }
        }
        return best;
    }

    private static Map<String, Integer> parseKd(String mtl) {
        Map<String, Integer> out = new HashMap<>();
        String name = null;
        for (String raw : mtl.split("\r?\n")) {
            String[] t = raw.strip().split("\\s+");
            try {
                if (t[0].equals("newmtl") && t.length > 1) {
                    name = t[1];
                } else if (t[0].equals("Kd") && name != null && t.length >= 4) {
                    out.put(name, 0xff000000 | c255(d(t[1])) << 16 | c255(d(t[2])) << 8 | c255(d(t[3])));
                }
            } catch (RuntimeException ignored) {
                // skip
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static int idx(String s, int size) {
        int i = Integer.parseInt(s);
        return i < 0 ? size + i : i - 1;
    }

    private static double d(String s) {
        double x = Double.parseDouble(s);
        if (!Double.isFinite(x)) {
            throw new NumberFormatException("not finite");
        }
        return x;
    }

    private static int c255(double x) {
        return (int) Math.round(Math.max(0, Math.min(1, x)) * 255);
    }

    private static double area(double[] a, double[] b, double[] c) {
        double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        double vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        double cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
        return 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
    }
}

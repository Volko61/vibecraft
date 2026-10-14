package dev.vibecraft.pack.mesh;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.vibecraft.pack.TextureSources.SourceException;

/**
 * Minimal Wavefront OBJ/MTL reader: {@code v}, {@code f} (v, v/vt, v//vn, v/vt/vn, negative indices),
 * {@code o}/{@code g} (group names), {@code usemtl}, {@code mtllib}; MTL: {@code newmtl}, {@code Kd},
 * {@code d}/{@code Tr}, {@code map_Kd}, {@code Ke} (glow). Texture coordinates and normals are ignored
 * (materials are box-projected); faces keep their winding (counter-clockwise = front).
 */
final class ObjParser {

    record Face(List<V3> pts, String material, String group) {
    }

    record Mtl(String name, int argb, String mapKd, int glow) {
    }

    record Obj(List<Face> faces, List<String> mtllibs, String optionsJson) {
    }

    static final int MAX_VERTICES = 200_000;
    static final int MAX_FACES = 100_000;

    private ObjParser() {
    }

    static Obj parse(String text) throws SourceException {
        List<V3> v = new ArrayList<>();
        List<Face> faces = new ArrayList<>();
        List<String> libs = new ArrayList<>();
        String mat = null;
        String group = "main";
        String options = null;
        int lineNo = 0;
        for (String raw : text.split("\r?\n")) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                String c = line.substring(1).strip();
                if (c.startsWith("vibecraft") && options == null) {
                    options = c.substring("vibecraft".length()).strip();
                }
                continue;
            }
            String[] t = line.split("\\s+");
            try {
                switch (t[0]) {
                    case "v" -> {
                        if (v.size() >= MAX_VERTICES) {
                            throw new SourceException("OBJ too big (more than " + MAX_VERTICES + " vertices)");
                        }
                        v.add(new V3(Double.parseDouble(t[1]), Double.parseDouble(t[2]), Double.parseDouble(t[3])));
                    }
                    case "f" -> {
                        if (faces.size() >= MAX_FACES) {
                            throw new SourceException("OBJ too big (more than " + MAX_FACES + " faces)");
                        }
                        List<V3> pts = new ArrayList<>();
                        for (int i = 1; i < t.length; i++) {
                            String idx = t[i];
                            int slash = idx.indexOf('/');
                            int vi = Integer.parseInt(slash < 0 ? idx : idx.substring(0, slash));
                            int k = vi < 0 ? v.size() + vi : vi - 1;
                            if (k < 0 || k >= v.size()) {
                                throw new SourceException("line " + lineNo + ": vertex index " + vi + " out of range (" + v.size()
                                        + " vertices defined so far)");
                            }
                            pts.add(v.get(k));
                        }
                        if (pts.size() >= 3) {
                            faces.add(new Face(pts, mat, group));
                        }
                    }
                    case "o", "g" -> group = t.length > 1 ? t[1].toLowerCase(Locale.ROOT) : "main";
                    case "usemtl" -> mat = t.length > 1 ? t[1] : null;
                    case "mtllib" -> {
                        if (t.length > 1) {
                            libs.add(line.substring(line.indexOf(' ') + 1).strip());
                        }
                    }
                    default -> {
                        // vt, vn, s, l, p...: ignored
                    }
                }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                throw new SourceException("OBJ line " + lineNo + ": cannot parse \"" + abbreviate(line) + "\"");
            }
        }
        if (faces.isEmpty()) {
            throw new SourceException("OBJ has no faces (f lines)");
        }
        return new Obj(faces, libs, options);
    }

    static Map<String, Mtl> parseMtl(String text) {
        Map<String, Mtl> out = new LinkedHashMap<>();
        String name = null;
        double[] kd = {0.8, 0.8, 0.8};
        double d = 1;
        String map = null;
        int glow = 0;
        for (String raw : (text + "\nnewmtl \u0000").split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] t = line.split("\\s+");
            try {
                switch (t[0]) {
                    case "newmtl" -> {
                        if (name != null) {
                            int a = (int) Math.round(Math.max(0, Math.min(1, d)) * 255);
                            int argb = a << 24 | c255(kd[0]) << 16 | c255(kd[1]) << 8 | c255(kd[2]);
                            out.put(name, new Mtl(name, argb, map, glow));
                        }
                        name = t.length > 1 ? t[1] : null;
                        kd = new double[] {0.8, 0.8, 0.8};
                        d = 1;
                        map = null;
                        glow = 0;
                    }
                    case "Kd" -> kd = new double[] {Double.parseDouble(t[1]), Double.parseDouble(t[2]), Double.parseDouble(t[3])};
                    case "d" -> d = Double.parseDouble(t[1]);
                    case "Tr" -> d = 1 - Double.parseDouble(t[1]);
                    case "map_Kd" -> map = t[t.length - 1];
                    case "Ke" -> {
                        double m = Math.max(Double.parseDouble(t[1]), Math.max(Double.parseDouble(t[2]), Double.parseDouble(t[3])));
                        glow = (int) Math.round(Math.max(0, Math.min(1, m)) * 15);
                    }
                    default -> {
                    }
                }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
                // tolerate odd MTL lines
            }
        }
        return out;
    }

    private static int c255(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    private static String abbreviate(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}

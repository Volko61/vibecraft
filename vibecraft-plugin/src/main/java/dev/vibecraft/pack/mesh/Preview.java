package dev.vibecraft.pack.mesh;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.vibecraft.pack.TextureSources;

/**
 * Software rasteriser of a generated item model (JSON elements + atlas): 4 orthographic views
 * (front 3/4, rear 3/4, side, top) in a 640x640 PNG, z-buffered, nearest texture sampling, back faces
 * culled like the client, simple directional light. Lets humans and AI coders judge a shape without a
 * client ({@code modules/<id>/.vibe/previews/}).
 */
public final class Preview {

    static final int VIEW = 320;

    private Preview() {
    }

    private record Quad(double[][] p, double[][] uv, V3 n) {
    }

    public static byte[] render(JsonObject model, BufferedImage atlas, String label) {
        List<Quad> quads = quads(model);
        BufferedImage img = new BufferedImage(VIEW * 2, VIEW * 2, BufferedImage.TYPE_INT_RGB);
        double[][] views = {{215, 22}, {35, 22}, {270, 0}, {180, 89.9}};
        String[] names = {"front 3/4", "rear 3/4", "side", "top"};
        double radius = 1e-6;
        for (Quad q : quads) {
            for (double[] p : q.p) {
                radius = Math.max(radius, Math.sqrt((p[0] - 8) * (p[0] - 8) + (p[1] - 8) * (p[1] - 8) + (p[2] - 8) * (p[2] - 8)));
            }
        }
        double scale = VIEW * 0.46 / radius;
        for (int v = 0; v < 4; v++) {
            renderView(img, (v % 2) * VIEW, (v / 2) * VIEW, quads, atlas, views[v][0], views[v][1], scale);
        }
        try {
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.setColor(new Color(255, 255, 255, 170));
            for (int v = 0; v < 4; v++) {
                g.drawString(names[v], (v % 2) * VIEW + 6, (v / 2) * VIEW + VIEW - 6);
            }
            if (label != null) {
                g.drawString(label, 6, 14);
            }
            g.setColor(new Color(0, 0, 0, 90));
            g.drawLine(VIEW, 0, VIEW, 2 * VIEW);
            g.drawLine(0, VIEW, 2 * VIEW, VIEW);
            g.dispose();
        } catch (RuntimeException | LinkageError ignored) {
            // no fonts (headless without fontconfig): the image is still useful
        }
        return TextureSources.png(img);
    }

    static List<Quad> quads(JsonObject model) {
        List<Quad> out = new ArrayList<>();
        JsonArray els = model.getAsJsonArray("elements");
        if (els == null) {
            return out;
        }
        for (JsonElement el : els) {
            JsonObject e = el.getAsJsonObject();
            double[] from = arr(e.get("from"));
            double[] to = arr(e.get("to"));
            V3 c = new V3((from[0] + to[0]) / 2, (from[1] + to[1]) / 2, (from[2] + to[2]) / 2);
            V3 h = new V3((to[0] - from[0]) / 2, (to[1] - from[1]) / 2, (to[2] - from[2]) / 2);
            M3 r = M3.I;
            V3 origin = c;
            if (e.has("rotation")) {
                JsonObject ro = e.getAsJsonObject("rotation");
                double[] o = arr(ro.get("origin"));
                origin = new V3(o[0], o[1], o[2]);
                if (ro.has("axis")) {
                    double ang = Math.toRadians(ro.get("angle").getAsDouble());
                    r = switch (ro.get("axis").getAsString()) {
                        case "x" -> M3.rotX(ang);
                        case "y" -> M3.rotY(ang);
                        default -> M3.rotZ(ang);
                    };
                } else {
                    r = M3.euler(get(ro, "x"), get(ro, "y"), get(ro, "z"));
                }
            }
            JsonObject faces = e.getAsJsonObject("faces");
            for (int d = 0; d < 6; d++) {
                if (faces == null || !faces.has(Elem.DIR[d])) {
                    continue;
                }
                double[] uv = arr(faces.getAsJsonObject(Elem.DIR[d]).get("uv"));
                double[][] ab = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
                double[][] p = new double[4][];
                double[][] t = new double[4][];
                for (int i = 0; i < 4; i++) {
                    V3 local = MeshCompiler.faceLocal(d, h, ab[i][0], ab[i][1]);
                    V3 w = origin.add(r.apply(c.add(local).sub(origin)));
                    p[i] = new double[] {w.x(), w.y(), w.z()};
                    t[i] = new double[] {uv[0] + (uv[2] - uv[0]) * ab[i][0], uv[1] + (uv[3] - uv[1]) * ab[i][1]};
                }
                out.add(new Quad(p, t, r.apply(Elem.localNormal(d))));
            }
        }
        return out;
    }

    private static double get(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsDouble() : 0;
    }

    private static double[] arr(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.get(i).getAsDouble();
        }
        return out;
    }

    private static void renderView(BufferedImage img, int ox, int oy, List<Quad> quads, BufferedImage atlas, double yaw, double pitch,
                                   double scale) {
        M3 view = M3.rotX(Math.toRadians(pitch)).mul(M3.rotY(Math.toRadians(yaw)));
        double[] z = new double[VIEW * VIEW];
        java.util.Arrays.fill(z, -Double.MAX_VALUE);
        for (int y = 0; y < VIEW; y++) {
            int g = 58 + (int) (34.0 * y / VIEW);
            int bg = 0xff000000 | (g << 16) | ((g + 4) << 8) | (g + 12);
            for (int x = 0; x < VIEW; x++) {
                img.setRGB(ox + x, oy + y, bg);
            }
        }
        V3 lightDir = new V3(0.45, 0.8, 0.6).norm();
        for (Quad q : quads) {
            V3 n = view.apply(q.n);
            if (n.z() <= 1e-6) {
                continue; // back face (culled by the client too)
            }
            double lambert = 0.5 + 0.5 * Math.max(0, n.dot(lightDir));
            double[][] s = new double[4][];
            for (int i = 0; i < 4; i++) {
                V3 p = view.apply(new V3(q.p[i][0] - 8, q.p[i][1] - 8, q.p[i][2] - 8));
                s[i] = new double[] {VIEW / 2.0 + p.x() * scale, VIEW / 2.0 - p.y() * scale, p.z()};
            }
            tri(img, ox, oy, z, s[0], s[1], s[2], q.uv[0], q.uv[1], q.uv[2], atlas, lambert);
            tri(img, ox, oy, z, s[0], s[2], s[3], q.uv[0], q.uv[2], q.uv[3], atlas, lambert);
        }
    }

    private static void tri(BufferedImage img, int ox, int oy, double[] zb, double[] a, double[] b, double[] c,
                            double[] ta, double[] tb, double[] tc, BufferedImage atlas, double lambert) {
        double area = (b[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (b[1] - a[1]);
        if (Math.abs(area) < 1e-9) {
            return;
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int maxX = Math.min(VIEW - 1, (int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int minY = Math.max(0, (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int maxY = Math.min(VIEW - 1, (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        int aw = atlas.getWidth();
        int ah = atlas.getHeight();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5;
                double py = y + 0.5;
                double w0 = ((b[0] - px) * (c[1] - py) - (c[0] - px) * (b[1] - py)) / area;
                double w1 = ((c[0] - px) * (a[1] - py) - (a[0] - px) * (c[1] - py)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < -1e-9 || w1 < -1e-9 || w2 < -1e-9) {
                    continue;
                }
                double depth = w0 * a[2] + w1 * b[2] + w2 * c[2];
                int zi = y * VIEW + x;
                if (depth <= zb[zi]) {
                    continue;
                }
                double u = w0 * ta[0] + w1 * tb[0] + w2 * tc[0];
                double v = w0 * ta[1] + w1 * tb[1] + w2 * tc[1];
                int tx = Math.max(0, Math.min(aw - 1, (int) Math.floor(u / 16 * aw)));
                int ty = Math.max(0, Math.min(ah - 1, (int) Math.floor(v / 16 * ah)));
                int col = atlas.getRGB(tx, ty);
                if ((col >>> 24) < 26) {
                    continue;
                }
                zb[zi] = depth;
                int alpha = col >>> 24;
                int r = (int) (((col >> 16) & 255) * lambert);
                int g = (int) (((col >> 8) & 255) * lambert);
                int bl = (int) ((col & 255) * lambert);
                if (alpha < 250) {
                    int under = img.getRGB(x + ox, y + oy);
                    double k = alpha / 255.0;
                    r = (int) (r * k + ((under >> 16) & 255) * (1 - k));
                    g = (int) (g * k + ((under >> 8) & 255) * (1 - k));
                    bl = (int) (bl * k + (under & 255) * (1 - k));
                }
                img.setRGB(x + ox, y + oy, 0xff000000 | r << 16 | g << 8 | bl);
            }
        }
    }
}

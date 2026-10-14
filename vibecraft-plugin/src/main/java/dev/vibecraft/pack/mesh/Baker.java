package dev.vibecraft.pack.mesh;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Texture atlas of one object. Solid-colour faces share 16x16 colour cells (mip-map safe, sampled in
 * their centre); textured / per-voxel faces get their own patch (with a 1-texel replicated gutter for
 * textured materials), deduplicated by content. Shelf packing, deterministic; width a power of two,
 * height a multiple of 16 (the client lowers the mip level of the whole atlas for other sizes).
 */
final class Baker {

    static final int CELL = 16;
    static final int MAX_PATCH = 256;
    /** Fixed light direction for baked shading (from the upper front left). */
    static final V3 LIGHT = new V3(-0.35, 1.0, 0.55).norm();

    private static final class Tile {
        final int[] px;
        final int w;
        final int h;
        final int gutter;
        final boolean cell;
        int x;
        int y;

        Tile(int[] px, int w, int h, int gutter, boolean cell) {
            this.px = px;
            this.w = w;
            this.h = h;
            this.gutter = gutter;
            this.cell = cell;
        }

        int fw() {
            return w + 2 * gutter;
        }

        int fh() {
            return h + 2 * gutter;
        }
    }

    private record Key(int w, int h, int hash) {
    }

    private final List<Tile> tiles = new ArrayList<>();
    private final Map<Integer, Integer> cells = new HashMap<>();
    private final Map<Key, List<Integer>> patchIndex = new HashMap<>();
    private final double light;
    int width;
    int height;
    long patchTexels;

    Baker(double light) {
        this.light = light;
    }

    // ------------------------------------------------------------------ sampling

    /** Colour of material m at object point p with normal n ((a, b) = position on the face for "face" mapping). */
    static int sample(Material m, V3 p, V3 n, double a, double b) {
        if (!m.textured()) {
            return m.argb;
        }
        BufferedImage img = m.image;
        double s;
        double t;
        if (m.mapping.equals("face")) {
            s = a;
            t = b;
        } else {
            V3 an = n.abs();
            if (an.x() >= an.y() && an.x() >= an.z()) {
                s = -p.z() * Math.signum(n.x() == 0 ? 1 : n.x());
                t = -p.y();
            } else if (an.y() >= an.z()) {
                s = p.x();
                t = p.z() * Math.signum(n.y() == 0 ? 1 : n.y());
            } else {
                s = p.x() * Math.signum(n.z() == 0 ? 1 : n.z());
                t = -p.y();
            }
            s /= m.tile;
            t /= m.tile;
        }
        s -= Math.floor(s);
        t -= Math.floor(t);
        int x = Math.min(img.getWidth() - 1, (int) (s * img.getWidth()));
        int y = Math.min(img.getHeight() - 1, (int) (t * img.getHeight()));
        int c = img.getRGB(x, y);
        return multiply(c, m.argb);
    }

    static int multiply(int c, int tint) {
        if (tint == 0xffffffff) {
            return c;
        }
        int a = ((c >>> 24) * (tint >>> 24)) / 255;
        int r = (((c >> 16) & 255) * ((tint >> 16) & 255)) / 255;
        int g = (((c >> 8) & 255) * ((tint >> 8) & 255)) / 255;
        int bl = ((c & 255) * (tint & 255)) / 255;
        return a << 24 | r << 16 | g << 8 | bl;
    }

    /** Baked shading factor for a face normal, quantised to 1/16 steps (fewer distinct colour cells). */
    double shade(V3 n, Material m) {
        if (light <= 0 || m.glow > 0 || !m.shade) {
            return 1;
        }
        double d = (n.norm().dot(LIGHT) + 1) / 2;
        double f = 1 - light * 0.45 * (1 - d);
        return Math.round(f * 16) / 16.0;
    }

    static int applyShade(int c, double f) {
        if (f >= 0.9999) {
            return c;
        }
        int r = (int) Math.round(((c >> 16) & 255) * f);
        int g = (int) Math.round(((c >> 8) & 255) * f);
        int b = (int) Math.round((c & 255) * f);
        return (c & 0xff000000) | r << 16 | g << 8 | b;
    }

    // ------------------------------------------------------------------ requests

    /** Uniform colour: returns the tile id. */
    int cell(int argb) {
        Integer id = cells.get(argb);
        if (id != null) {
            return id;
        }
        int[] px = new int[CELL * CELL];
        Arrays.fill(px, argb);
        tiles.add(new Tile(px, CELL, CELL, 0, true));
        cells.put(argb, tiles.size() - 1);
        return tiles.size() - 1;
    }

    /** Patch of w x h texels (row major, row 0 = v0). Uniform patches become cells. Returns the tile id. */
    int patch(int[] px, int w, int h, boolean gutter) {
        boolean uniform = true;
        for (int i = 1; i < px.length && uniform; i++) {
            uniform = px[i] == px[0];
        }
        if (uniform) {
            return cell(px[0]);
        }
        Key k = new Key(w, h, Arrays.hashCode(px));
        List<Integer> same = patchIndex.computeIfAbsent(k, x -> new ArrayList<>());
        for (int id : same) {
            if (Arrays.equals(tiles.get(id).px, px)) {
                return id;
            }
        }
        tiles.add(new Tile(px, w, h, gutter ? 1 : 0, false));
        same.add(tiles.size() - 1);
        patchTexels += (long) w * h;
        return tiles.size() - 1;
    }

    // ------------------------------------------------------------------ packing

    /** Packs every tile; returns false if the atlas would exceed max x max. */
    boolean pack(int max) {
        long area = 0;
        for (Tile t : tiles) {
            area += (long) t.fw() * t.fh();
        }
        int w = 16;
        while ((long) w * w < area && w < 8192) {
            w *= 2;
        }
        Integer[] order = new Integer[tiles.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            Tile ta = tiles.get(a);
            Tile tb = tiles.get(b);
            if (ta.fh() != tb.fh()) {
                return Integer.compare(tb.fh(), ta.fh());
            }
            if (ta.fw() != tb.fw()) {
                return Integer.compare(tb.fw(), ta.fw());
            }
            return Integer.compare(a, b);
        });
        while (true) {
            int x = 0;
            int y = 0;
            int shelf = 0;
            boolean fits = true;
            for (int i : order) {
                Tile t = tiles.get(i);
                if (t.fw() > w) {
                    fits = false;
                    break;
                }
                if (x + t.fw() > w) {
                    x = 0;
                    y += shelf;
                    shelf = 0;
                }
                t.x = x;
                t.y = y;
                x += t.fw();
                shelf = Math.max(shelf, t.fh());
            }
            int used = y + shelf;
            if (fits && used <= w) {
                width = w;
                height = Math.max(16, (used + 15) / 16 * 16);
                return width <= max && height <= max;
            }
            if (w >= 8192) {
                width = w;
                height = used;
                return false;
            }
            w *= 2;
        }
    }

    BufferedImage image() {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (Tile t : tiles) {
            int g = t.gutter;
            for (int yy = -g; yy < t.h + g; yy++) {
                for (int xx = -g; xx < t.w + g; xx++) {
                    int sx = Math.max(0, Math.min(t.w - 1, xx));
                    int sy = Math.max(0, Math.min(t.h - 1, yy));
                    img.setRGB(t.x + g + xx, t.y + g + yy, t.px[sy * t.w + sx]);
                }
            }
        }
        return img;
    }

    /** UV rectangle {u0, v0, u1, v1} in the model's 0..16 units for a tile. */
    double[] uv(int id) {
        Tile t = tiles.get(id);
        double x0;
        double y0;
        double x1;
        double y1;
        if (t.cell) {
            x0 = t.x + CELL / 2.0 - 1;
            y0 = t.y + CELL / 2.0 - 1;
            x1 = x0 + 2;
            y1 = y0 + 2;
        } else {
            x0 = t.x + t.gutter;
            y0 = t.y + t.gutter;
            x1 = x0 + t.w;
            y1 = y0 + t.h;
        }
        return new double[] {16 * x0 / width, 16 * y0 / height, 16 * x1 / width, 16 * y1 / height};
    }

    int tileCount() {
        return tiles.size();
    }
}

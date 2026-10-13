package dev.vibecraft.pack;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns a big AI-generated picture into a Minecraft-looking texture: background keyed out to
 * transparency, cropped to the subject, area-downsampled to the target size, binary alpha,
 * palette-quantised (median cut), optionally made seamless (tiling block textures).
 * Pure JDK (java.awt.image only, headless-safe).
 */
public final class TextureProcessor {

    private TextureProcessor() {
    }

    /**
     * @param transparent key out the background (and crop to the subject when {@code crop})
     * @param crop        crop to the opaque bounding box and centre it in a square (items/icons)
     * @param seamless    make the result tile seamlessly (blocks); implies opaque
     * @param colors      max palette size (0 = no quantisation)
     */
    public record Options(int width, int height, boolean transparent, boolean crop, boolean seamless, int colors) {
    }

    public static BufferedImage process(BufferedImage src, Options o) {
        BufferedImage img = toArgb(src);
        if (o.seamless()) {
            img = centerSquare(img);
            int work = Math.max(o.width(), o.height()) * 4;
            img = areaResize(img, Math.max(o.width() * 4, Math.min(work, img.getWidth())),
                    Math.max(o.height() * 4, Math.min(work, img.getHeight())));
            img = makeSeamless(img);
            img = areaResize(img, o.width(), o.height());
            opaque(img);
        } else {
            if (o.transparent()) {
                if (!hasRealAlpha(img)) {
                    keyBackground(img);
                }
                if (o.crop()) {
                    img = cropToSubject(img, o.width(), o.height());
                }
            } else if (o.crop()) {
                img = centerSquare(img);
            }
            img = areaResize(img, o.width(), o.height());
            if (o.transparent()) {
                binaryAlpha(img);
            } else {
                opaque(img);
            }
        }
        if (o.colors() > 1) {
            quantize(img, o.colors());
        }
        return img;
    }

    static BufferedImage toArgb(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(src, 0, 0, null);
        return out;
    }

    /** True if a good share of border pixels is already transparent (model honoured "transparent background"). */
    static boolean hasRealAlpha(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int total = 0;
        int clear = 0;
        for (int x = 0; x < w; x += Math.max(1, w / 64)) {
            for (int y : new int[] {0, h - 1}) {
                total++;
                if ((img.getRGB(x, y) >>> 24) < 32) {
                    clear++;
                }
            }
        }
        return total > 0 && clear * 2 > total;
    }

    /** Flood-fills from the borders every pixel close to the dominant border colour -> transparent. */
    static void keyBackground(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        // dominant border colour (median per channel of border pixels)
        List<Integer> border = new ArrayList<>();
        for (int x = 0; x < w; x++) {
            border.add(px[x]);
            border.add(px[(h - 1) * w + x]);
        }
        for (int y = 0; y < h; y++) {
            border.add(px[y * w]);
            border.add(px[y * w + w - 1]);
        }
        int bg = medianColor(border);
        int tol = 70;
        boolean[] seen = new boolean[w * h];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            q.add(x);
            q.add((h - 1) * w + x);
        }
        for (int y = 0; y < h; y++) {
            q.add(y * w);
            q.add(y * w + w - 1);
        }
        while (!q.isEmpty()) {
            int i = q.poll();
            if (seen[i]) {
                continue;
            }
            seen[i] = true;
            if (dist(px[i], bg) > tol) {
                continue;
            }
            px[i] = 0;
            int x = i % w;
            int y = i / w;
            if (x > 0) {
                q.add(i - 1);
            }
            if (x < w - 1) {
                q.add(i + 1);
            }
            if (y > 0) {
                q.add(i - w);
            }
            if (y < h - 1) {
                q.add(i + w);
            }
        }
        // remove the halo: semi-close pixels next to the keyed area
        for (int pass = 0; pass < 2; pass++) {
            int[] copy = px.clone();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    if (copy[i] == 0 || dist(copy[i], bg) > tol * 1.6) {
                        continue;
                    }
                    if ((x > 0 && copy[i - 1] == 0) || (x < w - 1 && copy[i + 1] == 0)
                            || (y > 0 && copy[i - w] == 0) || (y < h - 1 && copy[i + w] == 0)) {
                        px[i] = 0;
                    }
                }
            }
        }
        img.setRGB(0, 0, w, h, px, 0, w);
    }

    private static int medianColor(List<Integer> cols) {
        int n = cols.size();
        int[] r = new int[n];
        int[] g = new int[n];
        int[] b = new int[n];
        for (int i = 0; i < n; i++) {
            int c = cols.get(i);
            r[i] = c >> 16 & 0xff;
            g[i] = c >> 8 & 0xff;
            b[i] = c & 0xff;
        }
        Arrays.sort(r);
        Arrays.sort(g);
        Arrays.sort(b);
        return 0xff000000 | r[n / 2] << 16 | g[n / 2] << 8 | b[n / 2];
    }

    private static double dist(int a, int b) {
        int dr = (a >> 16 & 0xff) - (b >> 16 & 0xff);
        int dg = (a >> 8 & 0xff) - (b >> 8 & 0xff);
        int db = (a & 0xff) - (b & 0xff);
        return Math.sqrt(dr * dr * 0.3 + dg * dg * 0.59 + db * db * 0.11) * 1.7;
    }

    /** Bounding box of opaque pixels, padded to the target aspect ratio with a one-texel margin. */
    static BufferedImage cropToSubject(BufferedImage img, int tw, int th) {
        int w = img.getWidth();
        int h = img.getHeight();
        int minX = w;
        int minY = h;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((img.getRGB(x, y) >>> 24) >= 128) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            return img; // nothing opaque: keep as is
        }
        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        double aspect = (double) tw / th;
        // region with target aspect containing the box, + margin of 1 target texel on each side
        double rw = Math.max(bw, bh * aspect);
        double rh = rw / aspect;
        double margin = rw / tw; // one texel
        rw += 2 * margin;
        rh += 2 * margin / aspect;
        int cw = (int) Math.ceil(rw);
        int ch = (int) Math.ceil(rh);
        int cx = minX + bw / 2 - cw / 2;
        int cy = minY + bh / 2 - ch / 2;
        BufferedImage out = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(img, -cx, -cy, null);
        return out;
    }

    static BufferedImage centerSquare(BufferedImage img) {
        int s = Math.min(img.getWidth(), img.getHeight());
        int x = (img.getWidth() - s) / 2;
        int y = (img.getHeight() - s) / 2;
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(img, -x, -y, null);
        return out;
    }

    /** Box filter (area average, alpha-premultiplied): each target texel = mean of its source area. */
    static BufferedImage areaResize(BufferedImage img, int tw, int th) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w == tw && h == th) {
            return img;
        }
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        double sx = (double) w / tw;
        double sy = (double) h / th;
        for (int ty = 0; ty < th; ty++) {
            int y0 = (int) Math.floor(ty * sy);
            int y1 = Math.max(y0 + 1, (int) Math.floor((ty + 1) * sy));
            for (int tx = 0; tx < tw; tx++) {
                int x0 = (int) Math.floor(tx * sx);
                int x1 = Math.max(x0 + 1, (int) Math.floor((tx + 1) * sx));
                double a = 0;
                double r = 0;
                double g = 0;
                double b = 0;
                int n = 0;
                for (int y = y0; y < Math.min(y1, h); y++) {
                    for (int x = x0; x < Math.min(x1, w); x++) {
                        int c = px[y * w + x];
                        double al = (c >>> 24) / 255.0;
                        a += al;
                        r += (c >> 16 & 0xff) * al;
                        g += (c >> 8 & 0xff) * al;
                        b += (c & 0xff) * al;
                        n++;
                    }
                }
                if (n == 0 || a <= 0) {
                    out.setRGB(tx, ty, 0);
                    continue;
                }
                int ia = (int) Math.round(a / n * 255);
                out.setRGB(tx, ty, ia << 24 | clamp(r / a) << 16 | clamp(g / a) << 8 | clamp(b / a));
            }
        }
        return out;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    static void binaryAlpha(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = img.getRGB(x, y);
                img.setRGB(x, y, (c >>> 24) >= 110 ? (c | 0xff000000) : 0);
            }
        }
    }

    static void opaque(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, img.getRGB(x, y) | 0xff000000);
            }
        }
    }

    /**
     * Classic offset blend: blend the image with a copy shifted by half its size; the weight of the
     * original falls to 0 at the borders, where the shifted copy is continuous across the wrap.
     */
    static BufferedImage makeSeamless(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = Math.min(x + 0.5, w - x - 0.5) / (w / 2.0); // 0 at border, 1 at centre
                double dy = Math.min(y + 0.5, h - y - 0.5) / (h / 2.0);
                double wt = Math.min(1, Math.min(dx, dy) * 2.2);
                int a = img.getRGB(x, y);
                int b = img.getRGB((x + w / 2) % w, (y + h / 2) % h);
                out.setRGB(x, y, 0xff000000 | mix(a >> 16 & 0xff, b >> 16 & 0xff, wt) << 16
                        | mix(a >> 8 & 0xff, b >> 8 & 0xff, wt) << 8 | mix(a & 0xff, b & 0xff, wt));
            }
        }
        return out;
    }

    private static int mix(int a, int b, double wa) {
        return clamp(a * wa + b * (1 - wa));
    }

    /** Median-cut palette of at most {@code n} colours over opaque pixels, then nearest-colour mapping. */
    static void quantize(BufferedImage img, int n) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        List<int[]> opaque = new ArrayList<>();
        for (int c : px) {
            if ((c >>> 24) != 0) {
                opaque.add(new int[] {c >> 16 & 0xff, c >> 8 & 0xff, c & 0xff});
            }
        }
        if (opaque.size() <= n) {
            return;
        }
        List<List<int[]>> boxes = new ArrayList<>();
        boxes.add(opaque);
        while (boxes.size() < n) {
            int bi = -1;
            int bestRange = -1;
            int bestCh = 0;
            for (int i = 0; i < boxes.size(); i++) {
                List<int[]> b = boxes.get(i);
                if (b.size() < 2) {
                    continue;
                }
                for (int ch = 0; ch < 3; ch++) {
                    int mn = 255;
                    int mx = 0;
                    for (int[] c : b) {
                        mn = Math.min(mn, c[ch]);
                        mx = Math.max(mx, c[ch]);
                    }
                    if (mx - mn > bestRange) {
                        bestRange = mx - mn;
                        bi = i;
                        bestCh = ch;
                    }
                }
            }
            if (bi < 0 || bestRange <= 0) {
                break;
            }
            List<int[]> b = boxes.remove(bi);
            int ch = bestCh;
            b.sort((p, q) -> Integer.compare(p[ch], q[ch]));
            boxes.add(new ArrayList<>(b.subList(0, b.size() / 2)));
            boxes.add(new ArrayList<>(b.subList(b.size() / 2, b.size())));
        }
        int[][] pal = new int[boxes.size()][3];
        for (int i = 0; i < boxes.size(); i++) {
            long r = 0;
            long g = 0;
            long b = 0;
            for (int[] c : boxes.get(i)) {
                r += c[0];
                g += c[1];
                b += c[2];
            }
            int s = Math.max(1, boxes.get(i).size());
            pal[i] = new int[] {(int) (r / s), (int) (g / s), (int) (b / s)};
        }
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            if ((c >>> 24) == 0) {
                continue;
            }
            int r = c >> 16 & 0xff;
            int g = c >> 8 & 0xff;
            int b = c & 0xff;
            int best = 0;
            long bd = Long.MAX_VALUE;
            for (int k = 0; k < pal.length; k++) {
                long d = (long) (r - pal[k][0]) * (r - pal[k][0]) + (long) (g - pal[k][1]) * (g - pal[k][1])
                        + (long) (b - pal[k][2]) * (b - pal[k][2]);
                if (d < bd) {
                    bd = d;
                    best = k;
                }
            }
            px[i] = (c & 0xff000000) | pal[best][0] << 16 | pal[best][1] << 8 | pal[best][2];
        }
        img.setRGB(0, 0, w, h, px, 0, w);
    }

    /** Number of distinct opaque colours (for the API answer). */
    public static int colorCount(BufferedImage img) {
        java.util.HashSet<Integer> s = new java.util.HashSet<>();
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = img.getRGB(x, y);
                if ((c >>> 24) != 0) {
                    s.add(c);
                }
            }
        }
        return s.size();
    }
}

package dev.vibecraft.pack;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Text sources of textures, for AI agents that can only write text files:
 * <ul>
 *   <li>{@code foo.png.txt} / {@code foo.texture.json}: pixel art (palette + rows of characters), see ASSETS.md;</li>
 *   <li>{@code foo.png.b64}: a real PNG encoded in base64 (a {@code data:image/png;base64,} prefix is allowed).</li>
 * </ul>
 * Both produce {@code foo.png} (+ {@code foo.png.mcmeta} for animated pixel art).
 */
public final class TextureSources {

    private TextureSources() {
    }

    /** Compiled texture: PNG bytes and optional animation metadata (JSON text) for {@code .png.mcmeta}. */
    public record Compiled(byte[] png, String mcmeta, int width, int height) {
    }

    /** A source error with a human/AI-friendly message. */
    public static final class SourceException extends Exception {
        private static final long serialVersionUID = 1L;

        public SourceException(String message) {
            super(message, null, false, false);
        }
    }

    public static final int MAX_DIM = 1024;

    /** Output name for a text source, or null if {@code name} is not one. */
    public static String outputName(String name) {
        if (name.endsWith(".png.txt")) {
            return name.substring(0, name.length() - 4);
        }
        if (name.endsWith(".texture.json")) {
            return name.substring(0, name.length() - ".texture.json".length()) + ".png";
        }
        if (name.endsWith(".png.b64")) {
            return name.substring(0, name.length() - 4);
        }
        return null;
    }

    public static Compiled compile(String name, byte[] content) throws SourceException {
        if (name.endsWith(".png.b64")) {
            return fromBase64(new String(content, StandardCharsets.UTF_8));
        }
        return pixelArt(new String(content, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ base64

    static Compiled fromBase64(String text) throws SourceException {
        String t = text.strip();
        if (t.startsWith("data:")) {
            int comma = t.indexOf(',');
            if (comma < 0) {
                throw new SourceException("invalid data URL (no comma)");
            }
            t = t.substring(comma + 1);
        }
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(t.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new SourceException("invalid base64: " + e.getMessage());
        }
        int[] wh = checkPng(bytes);
        return new Compiled(bytes, null, wh[0], wh[1]);
    }

    /** Validates PNG bytes; returns {width, height}. */
    public static int[] checkPng(byte[] bytes) throws SourceException {
        if (bytes.length < 8 || (bytes[0] & 0xff) != 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') {
            throw new SourceException("not a PNG file (bad signature)");
        }
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            throw new SourceException("corrupt PNG: " + e.getMessage());
        }
        if (img == null) {
            throw new SourceException("corrupt PNG (cannot decode)");
        }
        if (img.getWidth() > 4096 || img.getHeight() > 4096) {
            throw new SourceException("PNG too large (" + img.getWidth() + "x" + img.getHeight() + ", max 4096)");
        }
        return new int[] {img.getWidth(), img.getHeight()};
    }

    // ------------------------------------------------------------------ pixel art

    static Compiled pixelArt(String text) throws SourceException {
        JsonObject o;
        try {
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) {
                throw new SourceException("pixel art must be a JSON object {palette, pixels}");
            }
            o = el.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new SourceException("invalid JSON: " + PackBuilder.jsonMessage(e));
        }
        if (!o.has("palette") && (o.has("pixels") || o.has("frames"))) {
            throw new SourceException("missing \"palette\" object, e.g. {\".\": \"transparent\", \"r\": \"#ff0000\"}");
        }
        Map<Integer, Integer> palette = o.has("palette") ? palette(o) : new HashMap<>();
        List<List<String>> frames = new ArrayList<>();
        if (o.has("frames")) {
            JsonElement fr = o.get("frames");
            if (!fr.isJsonArray() || fr.getAsJsonArray().isEmpty()) {
                throw new SourceException("\"frames\" must be a non-empty array of pixel row arrays");
            }
            for (JsonElement f : fr.getAsJsonArray()) {
                frames.add(rows(f, "frames[" + frames.size() + "]"));
            }
        } else if (o.has("pixels")) {
            frames.add(rows(o.get("pixels"), "pixels"));
        }
        int[] size = null;
        if (o.has("size")) {
            JsonElement s = o.get("size");
            try {
                size = s.isJsonArray() ? new int[] {s.getAsJsonArray().get(0).getAsInt(), s.getAsJsonArray().get(1).getAsInt()}
                        : new int[] {s.getAsInt(), s.getAsInt()};
            } catch (RuntimeException e) {
                throw new SourceException("\"size\" must be [width, height] or a number");
            }
            if (size[0] < 1 || size[1] < 1 || size[0] > MAX_DIM || size[1] > MAX_DIM) {
                throw new SourceException("\"size\" must be between 1 and " + MAX_DIM);
            }
        }
        int w;
        int h;
        if (!frames.isEmpty()) {
            h = frames.get(0).size();
            w = frames.get(0).get(0).codePointCount(0, frames.get(0).get(0).length());
            if (size != null && (size[0] != w || size[1] != h)) {
                throw new SourceException("\"size\" says " + size[0] + "x" + size[1] + " but the pixel rows are " + w + "x" + h);
            }
        } else if (size != null && (o.has("draw") || o.has("fill"))) {
            w = size[0];
            h = size[1];
            frames.add(null); // one frame made of fill + draw operations only
        } else {
            throw new SourceException("missing \"pixels\" (array of strings), \"frames\", or \"size\" + \"fill\"/\"draw\"");
        }
        int scale = 1;
        if (o.has("scale")) {
            try {
                scale = o.get("scale").getAsInt();
            } catch (RuntimeException e) {
                throw new SourceException("\"scale\" must be an integer");
            }
            if (scale < 1 || scale > 32) {
                throw new SourceException("\"scale\" must be between 1 and 32");
            }
        }
        int outW = w * scale;
        int outH = h * scale * frames.size();
        if (outW > MAX_DIM || outH > MAX_DIM * 8) {
            throw new SourceException("texture too large: " + outW + "x" + outH);
        }
        int fill = o.has("fill") ? colorOrKey(o.get("fill"), palette, "fill") : 0;
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
        for (int fi = 0; fi < frames.size(); fi++) {
            int[] canvas = new int[w * h];
            java.util.Arrays.fill(canvas, fill);
            List<String> rows = frames.get(fi);
            if (rows != null) {
                if (rows.size() != h) {
                    throw new SourceException("frame " + fi + " has " + rows.size() + " rows, expected " + h);
                }
                stamp(canvas, w, h, rows, 0, 0, palette, false, frames.size() > 1 ? "frame " + fi + " " : "");
            }
            if (o.has("draw")) {
                draw(canvas, w, h, o.get("draw"), palette);
            }
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = canvas[y * w + x];
                    for (int dy = 0; dy < scale; dy++) {
                        for (int dx = 0; dx < scale; dx++) {
                            img.setRGB(x * scale + dx, (fi * h + y) * scale + dy, argb);
                        }
                    }
                }
            }
        }
        String mcmeta = null;
        if (frames.size() > 1 || o.has("frametime") || o.has("animation")) {
            JsonObject anim = new JsonObject();
            if (o.has("animation") && o.get("animation").isJsonObject()) {
                anim = o.getAsJsonObject("animation").deepCopy();
            }
            if (o.has("frametime")) {
                anim.add("frametime", o.get("frametime"));
            }
            if (o.has("interpolate")) {
                anim.add("interpolate", o.get("interpolate"));
            }
            if (frames.size() > 1 && outW != h * scale) {
                anim.addProperty("width", outW);
                anim.addProperty("height", h * scale);
            }
            JsonObject root = new JsonObject();
            root.add("animation", anim);
            mcmeta = root.toString();
        }
        return new Compiled(png(img), mcmeta, outW, outH);
    }

    /** Copies palette rows at (ox, oy); with {@code keepUnderTransparent}, transparent characters leave the canvas as is. */
    private static void stamp(int[] canvas, int w, int h, List<String> rows, int ox, int oy, Map<Integer, Integer> palette,
                              boolean keepUnderTransparent, String where) throws SourceException {
        int rw = rows.get(0).codePointCount(0, rows.get(0).length());
        for (int y = 0; y < rows.size(); y++) {
            String row = rows.get(y);
            int[] cps = row.codePoints().toArray();
            if (cps.length != rw) {
                throw new SourceException(where + "row " + y + " has " + cps.length + " pixels, expected " + rw + ": \"" + row + "\"");
            }
            for (int x = 0; x < cps.length; x++) {
                Integer argb = palette.get(cps[x]);
                if (argb == null) {
                    throw new SourceException(where + "row " + y + " column " + x + ": character '"
                            + new String(Character.toChars(cps[x])) + "' is not in the palette");
                }
                int cx = ox + x;
                int cy = oy + y;
                if (cx < 0 || cy < 0 || cx >= w || cy >= h || (keepUnderTransparent && (argb >>> 24) == 0)) {
                    continue;
                }
                canvas[cy * w + cx] = argb;
            }
        }
    }

    /** "draw": [{rect|frame|line|pixels, color, at, repeat, step, thickness}] */
    private static void draw(int[] canvas, int w, int h, JsonElement ops, Map<Integer, Integer> palette) throws SourceException {
        if (!ops.isJsonArray()) {
            throw new SourceException("\"draw\" must be an array of operations");
        }
        int i = 0;
        for (JsonElement e : ops.getAsJsonArray()) {
            String where = "draw[" + i++ + "]: ";
            if (!e.isJsonObject()) {
                throw new SourceException(where + "must be an object like {\"rect\": [x, y, w, h], \"color\": \"#ff0000\"}");
            }
            JsonObject op = e.getAsJsonObject();
            int[] rep = ints(op, "repeat", 2, new int[] {1, 1}, where);
            int[] step = ints(op, "step", 2, new int[] {0, 0}, where);
            if (rep[0] < 1 || rep[1] < 1 || rep[0] * rep[1] > 4096) {
                throw new SourceException(where + "\"repeat\" must be [nx, ny] with nx, ny >= 1");
            }
            for (int ry = 0; ry < rep[1]; ry++) {
                for (int rx = 0; rx < rep[0]; rx++) {
                    int dx = rx * step[0];
                    int dy = ry * step[1];
                    if (op.has("rect") || op.has("frame")) {
                        boolean filled = op.has("rect");
                        int[] r = ints(op, filled ? "rect" : "frame", 4, null, where);
                        int color = colorOrKey(op.get("color"), palette, where + "color");
                        int t = op.has("thickness") ? op.get("thickness").getAsInt() : 1;
                        for (int y = r[1]; y < r[1] + r[3]; y++) {
                            for (int x = r[0]; x < r[0] + r[2]; x++) {
                                boolean edge = x < r[0] + t || x >= r[0] + r[2] - t || y < r[1] + t || y >= r[1] + r[3] - t;
                                if (filled || edge) {
                                    put(canvas, w, h, x + dx, y + dy, color);
                                }
                            }
                        }
                    } else if (op.has("line")) {
                        int[] l = ints(op, "line", 4, null, where);
                        int color = colorOrKey(op.get("color"), palette, where + "color");
                        int n = Math.max(Math.abs(l[2] - l[0]), Math.abs(l[3] - l[1]));
                        for (int k = 0; k <= n; k++) {
                            int x = n == 0 ? l[0] : Math.round(l[0] + (l[2] - l[0]) * (float) k / n);
                            int y = n == 0 ? l[1] : Math.round(l[1] + (l[3] - l[1]) * (float) k / n);
                            put(canvas, w, h, x + dx, y + dy, color);
                        }
                    } else if (op.has("pixels")) {
                        int[] at = ints(op, "at", 2, new int[] {0, 0}, where);
                        stamp(canvas, w, h, rows(op.get("pixels"), where + "pixels"), at[0] + dx, at[1] + dy, palette, true, where);
                    } else {
                        throw new SourceException(where + "unknown operation (use rect, frame, line or pixels)");
                    }
                }
            }
        }
    }

    private static void put(int[] canvas, int w, int h, int x, int y, int color) {
        if (x >= 0 && y >= 0 && x < w && y < h) {
            canvas[y * w + x] = color;
        }
    }

    private static int[] ints(JsonObject op, String key, int n, int[] def, String where) throws SourceException {
        JsonElement e = op.get(key);
        if (e == null) {
            if (def == null) {
                throw new SourceException(where + "missing \"" + key + "\"");
            }
            return def;
        }
        if (!e.isJsonArray() || e.getAsJsonArray().size() != n) {
            throw new SourceException(where + "\"" + key + "\" must be an array of " + n + " integers");
        }
        int[] out = new int[n];
        for (int k = 0; k < n; k++) {
            try {
                out[k] = e.getAsJsonArray().get(k).getAsInt();
            } catch (RuntimeException ex) {
                throw new SourceException(where + "\"" + key + "\" must contain integers");
            }
        }
        return out;
    }

    /** A "#rrggbb"-style colour, "transparent", or a single palette character. */
    private static int colorOrKey(JsonElement e, Map<Integer, Integer> palette, String where) throws SourceException {
        if (e == null || !e.isJsonPrimitive()) {
            throw new SourceException(where + ": missing colour (\"#rrggbb\", \"transparent\" or a palette character)");
        }
        String s = e.getAsString();
        if (s.codePointCount(0, s.length()) == 1 && palette.containsKey(s.codePointAt(0))) {
            return palette.get(s.codePointAt(0));
        }
        return color(s, where);
    }

    private static List<String> rows(JsonElement e, String what) throws SourceException {
        if (!e.isJsonArray() || e.getAsJsonArray().isEmpty()) {
            throw new SourceException("\"" + what + "\" must be a non-empty array of strings (one per row)");
        }
        List<String> rows = new ArrayList<>();
        for (JsonElement r : e.getAsJsonArray()) {
            if (!r.isJsonPrimitive()) {
                throw new SourceException("\"" + what + "\" must contain strings only");
            }
            rows.add(r.getAsString());
        }
        if (rows.get(0).isEmpty()) {
            throw new SourceException("\"" + what + "\": empty row");
        }
        return rows;
    }

    private static Map<Integer, Integer> palette(JsonObject o) throws SourceException {
        if (!o.has("palette") || !o.get("palette").isJsonObject()) {
            throw new SourceException("missing \"palette\" object, e.g. {\".\": \"transparent\", \"r\": \"#ff0000\"}");
        }
        Map<Integer, Integer> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("palette").entrySet()) {
            String k = e.getKey();
            if (k.codePointCount(0, k.length()) != 1) {
                throw new SourceException("palette keys must be single characters, got \"" + k + "\"");
            }
            if (!e.getValue().isJsonPrimitive()) {
                throw new SourceException("palette value of '" + k + "' must be a string like \"#rrggbb\"");
            }
            out.put(k.codePointAt(0), color(e.getValue().getAsString(), k));
        }
        return out;
    }

    /** "#rgb", "#rrggbb", "#rrggbbaa", "transparent"/"none"/"clear" -> ARGB. */
    static int color(String s, String key) throws SourceException {
        String v = s.strip().toLowerCase(Locale.ROOT);
        if (v.equals("transparent") || v.equals("none") || v.equals("clear") || v.isEmpty()) {
            return 0;
        }
        if (v.startsWith("#")) {
            v = v.substring(1);
        }
        try {
            switch (v.length()) {
                case 3 -> {
                    int r = Integer.parseInt(v.substring(0, 1), 16) * 17;
                    int g = Integer.parseInt(v.substring(1, 2), 16) * 17;
                    int b = Integer.parseInt(v.substring(2, 3), 16) * 17;
                    return 0xff000000 | r << 16 | g << 8 | b;
                }
                case 6 -> {
                    return 0xff000000 | Integer.parseInt(v, 16);
                }
                case 8 -> {
                    long rgba = Long.parseLong(v, 16);
                    int a = (int) (rgba & 0xff);
                    return a << 24 | (int) (rgba >>> 8);
                }
                default -> {
                }
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new SourceException("palette color of '" + key + "' is not #rgb, #rrggbb, #rrggbbaa or \"transparent\": \"" + s + "\"");
    }

    public static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

package dev.vibecraft.pack.mesh;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.vibecraft.pack.TextureSources.SourceException;

/** Small typed accessors for shape sources, with error messages that name the JSON path. */
final class Json {

    private Json() {
    }

    static boolean has(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull();
    }

    static double num(JsonObject o, String k, double def, String where) throws SourceException {
        if (!has(o, k)) {
            return def;
        }
        JsonElement e = o.get(k);
        try {
            double d = e.getAsDouble();
            if (!Double.isFinite(d)) {
                throw new NumberFormatException();
            }
            return d;
        } catch (RuntimeException ex) {
            throw new SourceException(where + "." + k + " must be a number, got " + abbreviate(e));
        }
    }

    static double positive(JsonObject o, String k, double def, String where) throws SourceException {
        double d = num(o, k, def, where);
        if (!(d > 0)) {
            throw new SourceException(where + "." + k + " must be > 0, got " + d);
        }
        return d;
    }

    static int integer(JsonObject o, String k, int def, int min, int max, String where) throws SourceException {
        double d = num(o, k, def, where);
        int i = (int) Math.round(d);
        if (i < min || i > max) {
            throw new SourceException(where + "." + k + " must be between " + min + " and " + max + ", got " + abbreviate(o.get(k)));
        }
        return i;
    }

    static String str(JsonObject o, String k, String def, String where) throws SourceException {
        if (!has(o, k)) {
            return def;
        }
        JsonElement e = o.get(k);
        if (!e.isJsonPrimitive()) {
            throw new SourceException(where + "." + k + " must be a string, got " + abbreviate(e));
        }
        return e.getAsString();
    }

    static boolean bool(JsonObject o, String k, boolean def, String where) throws SourceException {
        if (!has(o, k)) {
            return def;
        }
        JsonElement e = o.get(k);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new SourceException(where + "." + k + " must be true or false, got " + abbreviate(e));
        }
        return e.getAsBoolean();
    }

    static V3 vec(JsonObject o, String k, V3 def, String where) throws SourceException {
        if (!has(o, k)) {
            return def;
        }
        JsonElement e = o.get(k);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            double d = e.getAsDouble();
            return new V3(d, d, d);
        }
        double[] a = nums(e, 3, where + "." + k);
        return new V3(a[0], a[1], a[2]);
    }

    static double[] nums(JsonElement e, int n, String where) throws SourceException {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != n) {
            throw new SourceException(where + " must be an array of " + n + " numbers, got " + abbreviate(e));
        }
        double[] out = new double[n];
        JsonArray a = e.getAsJsonArray();
        for (int i = 0; i < n; i++) {
            try {
                out[i] = a.get(i).getAsDouble();
                if (!Double.isFinite(out[i])) {
                    throw new NumberFormatException();
                }
            } catch (RuntimeException ex) {
                throw new SourceException(where + "[" + i + "] must be a number, got " + abbreviate(a.get(i)));
            }
        }
        return out;
    }

    static double[][] points(JsonObject o, String k, int dim, int min, String where) throws SourceException {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < min) {
            throw new SourceException(where + "." + k + " must be an array of at least " + min + " points [" + (dim == 2 ? "x, y" : "x, y, z")
                    + "], got " + abbreviate(e));
        }
        JsonArray a = e.getAsJsonArray();
        if (a.size() > 4096) {
            throw new SourceException(where + "." + k + ": too many points (max 4096)");
        }
        double[][] out = new double[a.size()][];
        for (int i = 0; i < a.size(); i++) {
            out[i] = nums(a.get(i), dim, where + "." + k + "[" + i + "]");
        }
        return out;
    }

    static JsonObject obj(JsonElement e, String where) throws SourceException {
        if (e == null || !e.isJsonObject()) {
            throw new SourceException(where + " must be a JSON object {...}, got " + abbreviate(e));
        }
        return e.getAsJsonObject();
    }

    static List<String> axes(JsonObject o, String k, String where) throws SourceException {
        List<String> out = new ArrayList<>();
        if (!has(o, k)) {
            return out;
        }
        JsonElement e = o.get(k);
        List<JsonElement> items = new ArrayList<>();
        if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(items::add);
        } else {
            items.add(e);
        }
        for (JsonElement i : items) {
            String s = i.isJsonPrimitive() ? i.getAsString().toLowerCase(Locale.ROOT) : "";
            if (!s.equals("x") && !s.equals("y") && !s.equals("z")) {
                throw new SourceException(where + "." + k + " must be \"x\", \"y\", \"z\" or a list of them, got " + abbreviate(e));
            }
            out.add(s);
        }
        return out;
    }

    static String abbreviate(JsonElement e) {
        String s = String.valueOf(e);
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}

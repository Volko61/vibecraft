package dev.vibecraft.web;

import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * Shared Gson instance of the HTTP API.
 *
 * <p>Records (ModuleInfo, ReloadResult, AiJob, Idea, Diagnostic) are serialized natively by Gson
 * 2.10+ as plain objects; enums as their name. Nulls are kept ({@code "lastError": null}) and
 * non-finite doubles (NaN / Infinity, e.g. a not-yet-computed msPerTick) become {@code null}
 * instead of making Gson throw.
 */
public final class Json {

    private static final TypeAdapter<Double> SAFE_DOUBLE = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, Double value) throws IOException {
            if (value == null || !Double.isFinite(value)) {
                out.nullValue();
            } else {
                out.value(value.doubleValue());
            }
        }

        @Override
        public Double read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return in.nextDouble();
        }
    };

    public static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .registerTypeAdapter(Double.class, SAFE_DOUBLE)
            .registerTypeAdapter(double.class, SAFE_DOUBLE)
            .create();

    private Json() {
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }
}

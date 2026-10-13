package dev.vibecraft.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Extra {@code /api/...} routes contributed by optional services (resource pack, texture
 * generation...) without growing {@link ApiServer}. Routes are tried in registration order before the
 * built-in ones; a route returns null when the request is not for it. Authentication (X-Vibe-Token)
 * is checked by ApiServer before any route runs.
 */
public final class ApiExtensions {

    private ApiExtensions() {
    }

    @FunctionalInterface
    public interface Body {
        String get() throws IOException;
    }

    /** @param segments path segments, {@code segments.get(0) == "api"} */
    public record Request(String method, List<String> segments, Map<String, String> query, Body body) {
    }

    /** status + JSON body (serialized with the API's Gson). */
    public record Reply(int status, Object body) {
        public static Reply ok(Object body) {
            return new Reply(200, body);
        }

        public static Reply error(int status, String message) {
            return new Reply(status, Map.of("error", message));
        }
    }

    @FunctionalInterface
    public interface Route {
        /** @return null if not handled by this route */
        CompletableFuture<Reply> handle(Request request) throws Exception;
    }

    private static final List<Route> ROUTES = new CopyOnWriteArrayList<>();

    public static void register(Route route) {
        ROUTES.add(route);
    }

    public static void unregister(Route route) {
        ROUTES.remove(route);
    }

    /** First non-null answer of the registered routes, or null. */
    static CompletableFuture<Reply> dispatch(Request request) throws Exception {
        for (Route r : ROUTES) {
            CompletableFuture<Reply> f = r.handle(request);
            if (f != null) {
                return f;
            }
        }
        return null;
    }
}

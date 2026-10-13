package dev.vibecraft.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.vibecraft.ext.OpenRouterClient;

/**
 * Asynchronous OpenRouter chat completions (LLM calls) for modules: AI NPCs, quiz masters, poets...
 * The server's key ({@code Secrets.get("openrouter")}) is used automatically and never exposed.
 *
 * <p>Every call returns immediately; the HTTP request runs on a VibeCraft background thread
 * (never the main thread). The future completes on that background thread, so <b>hop back to the
 * main thread with {@code ctx.later(0, ...)} before touching players or the world</b>:
 * <pre>{@code
 * OpenRouter.chat("Tu es un forgeron grincheux. Réponds en une phrase.", question)
 *     .thenAccept(answer -> ctx.later(0, () -> p.sendMessage(ctx.mm("<gold>Forgeron : <white>" + answer))))
 *     .exceptionally(err -> { ctx.later(0, () -> p.sendRichMessage("<red>" + OpenRouter.errorMessage(err))); return null; });
 * }</pre>
 * Failures (no key configured, rate limit, timeout, HTTP error) complete the future exceptionally
 * with a French message suitable for players ({@link #errorMessage(Throwable)}).
 * Limits: {@code openrouter.max-requests-per-minute} (whole server), short answers by default
 * ({@code openrouter.max-tokens}).
 */
public final class OpenRouter {

    private OpenRouter() {
    }

    /** A chat message: role is {@code "system"}, {@code "user"} or {@code "assistant"}. */
    public record Message(String role, String content) {
        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }

    /** True if an OpenRouter key is configured (calls would otherwise fail with a clear message). */
    public static boolean available() {
        OpenRouterClient c = OpenRouterClient.get();
        return c != null && c.hasKey();
    }

    /** One user prompt, default model; completes with the answer text. */
    public static CompletableFuture<String> chat(String prompt) {
        return chat(List.of(Message.user(prompt)));
    }

    /** System instructions (persona, rules, length) + one user prompt. */
    public static CompletableFuture<String> chat(String system, String prompt) {
        List<Message> m = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            m.add(Message.system(system));
        }
        m.add(Message.user(prompt));
        return chat(m);
    }

    /** Full conversation (keep the history yourself, e.g. a per-player {@code List<Message>}). */
    public static CompletableFuture<String> chat(List<Message> messages) {
        return chat(null, messages, 0);
    }

    /**
     * @param model     OpenRouter model id (e.g. {@code "openai/gpt-4o-mini"}), null = server default ({@code openrouter.model})
     * @param maxTokens max answer tokens, 0 = server default
     */
    public static CompletableFuture<String> chat(String model, List<Message> messages, int maxTokens) {
        OpenRouterClient c = OpenRouterClient.get();
        if (c == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("OpenRouter indisponible (serveur en cours d'arrêt)"));
        }
        List<OpenRouterClient.Message> list = new ArrayList<>();
        if (messages != null) {
            for (Message m : messages) {
                if (m != null) {
                    list.add(new OpenRouterClient.Message(m.role(), m.content()));
                }
            }
        }
        return c.chat(model, list, maxTokens);
    }

    /** Short French message describing a failure of one of the futures above. */
    public static String errorMessage(Throwable error) {
        Throwable t = error;
        while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        String m = t == null ? null : t.getMessage();
        return m == null || m.isBlank() ? "erreur OpenRouter (" + (t == null ? "?" : t.getClass().getSimpleName()) + ")" : m;
    }
}

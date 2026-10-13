package dev.vibecraft.api;

import dev.vibecraft.ext.SecretStore;

/**
 * Read-only access to the server's secrets (API keys), configured by the server owner in
 * {@code plugins/VibeCraft/secrets.yml}, the {@code secrets:} section of config.yml, or environment
 * variables {@code VIBECRAFT_SECRET_<NAME>} / {@code OPENROUTER_API_KEY} (name {@code "openrouter"}).
 *
 * <p>Never log, broadcast or store a secret value (module log lines containing one are redacted
 * anyway). For OpenRouter prefer {@link OpenRouter}, which uses the key for you.
 */
public final class Secrets {

    private Secrets() {
    }

    /** The secret value, or {@code null} if it is not configured. Names are case-insensitive ({@code -} == {@code _}). */
    public static String get(String name) {
        SecretStore s = SecretStore.get();
        return s == null ? null : s.secret(name);
    }

    /** True if the secret is configured (non-blank). */
    public static boolean has(String name) {
        return get(name) != null;
    }
}

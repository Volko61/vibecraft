package dev.vibecraft.core;

/**
 * A compiler (or loader / bytecode-policy) diagnostic.
 *
 * @param kind       "ERROR", "WARNING" or "NOTE"
 * @param file       path relative to the module directory (e.g. "PigRain.java"), or "" if not file-specific
 * @param line       1-based line, or -1
 * @param column     1-based column, or -1
 * @param message    the message
 * @param sourceLine the offending source line (trimmed), or ""
 */
public record Diagnostic(String kind, String file, long line, long column, String message, String sourceLine) {
}

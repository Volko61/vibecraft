package dev.vibecraft.core;

import java.util.List;

/**
 * Outcome of a reload/enable request.
 *
 * @param id          module id
 * @param ok          true if the NEW code is now running
 * @param state       state after the attempt
 * @param generation  generation now running (0 if none)
 * @param error       short human-readable error if !ok (compile failed, enable threw, policy violation...), else null
 * @param diagnostics compiler / policy diagnostics (may contain warnings even when ok)
 * @param durationMs  total time (compile + swap)
 * @param rolledBack  true if the attempt failed and the previous good generation was restored
 */
public record ReloadResult(String id, boolean ok, ModuleState state, int generation, String error,
                           List<Diagnostic> diagnostics, long durationMs, boolean rolledBack) {
}

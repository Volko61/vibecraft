package dev.vibecraft.core;

import java.util.List;

/**
 * Immutable snapshot of a module's status (serialized as-is to JSON by the HTTP API).
 *
 * @param id                module id (directory name)
 * @param state             current state
 * @param generation        successful loads so far (0 if never loaded)
 * @param description       from module.yml "description" (or "")
 * @param lastReloadAt      epoch ms of the last successful enable (0 if never)
 * @param lastAttemptAt     epoch ms of the last load attempt (0 if never)
 * @param lastError         last failure message (compile/enable/policy/trip), null if the last attempt succeeded
 * @param diagnostics       diagnostics of the last compile
 * @param runtimeErrorCount runtime errors since the last successful load
 * @param recentErrors      up to 10 most recent runtime errors, newest last ("HH:mm:ss where: Exception: msg (File.java:12)")
 * @param commands          command names registered by the running generation
 * @param listeners         number of event registrations of the running generation
 * @param tasks             number of active scheduled tasks of the running generation
 * @param rolledBack        true if the last attempt failed and the previous generation is still running
 * @param msPerTick         average main-thread time spent in this module's code per tick (exp. moving average)
 */
public record ModuleInfo(String id, ModuleState state, int generation, String description,
                         long lastReloadAt, long lastAttemptAt, String lastError,
                         List<Diagnostic> diagnostics, int runtimeErrorCount, List<String> recentErrors,
                         List<String> commands, int listeners, int tasks, boolean rolledBack, double msPerTick) {
}

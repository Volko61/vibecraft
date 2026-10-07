package dev.vibecraft.ai;

import java.util.List;

/**
 * Immutable snapshot of an AI coding job (serialized as-is to JSON by the HTTP API).
 *
 * @param id          unique job id (e.g. "job-7")
 * @param moduleId    module the job works on (one job per module at a time)
 * @param prompt      the request, as typed by the user
 * @param requestedBy player name / "console" / "dashboard"
 * @param state       current state
 * @param startedAt   epoch ms (0 while queued)
 * @param endedAt     epoch ms (0 while not finished)
 * @param progress    human-readable progress lines, oldest first (at most the last 200)
 * @param result      final answer of the AI (null until finished)
 * @param sessionId   Claude Code session id (used to resume the module's conversation next time)
 * @param costUsd     reported cost (0 if unknown)
 */
public record AiJob(String id, String moduleId, String prompt, String requestedBy, AiJobState state,
                    long startedAt, long endedAt, List<String> progress, String result,
                    String sessionId, double costUsd) {
}

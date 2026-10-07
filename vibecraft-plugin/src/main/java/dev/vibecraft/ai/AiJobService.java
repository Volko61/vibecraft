package dev.vibecraft.ai;

import java.util.List;
import java.util.Optional;

/**
 * Runs headless Claude Code sessions that implement features inside a module directory.
 * One conversation per module (resumed across requests), several modules in parallel.
 * Thread-safe.
 */
public interface AiJobService {

    /** True if the Claude Code CLI was found and ai.enabled is true. */
    boolean available();

    /** Why {@link #available()} is false (or "" if available). */
    String unavailableReason();

    /**
     * Start (or queue, if max-concurrent-jobs is reached) a job for {@code moduleId}.
     * Creates the module (scaffold) if it does not exist.
     *
     * @throws IllegalStateException    if the AI is unavailable or a job is already queued/running for this module
     * @throws IllegalArgumentException if the module id is invalid or the prompt is blank
     */
    AiJob start(String moduleId, String prompt, String requestedBy);

    /** Stop the running/queued job of this module. Returns false if none. */
    boolean stop(String moduleId);

    /** Running + queued jobs, then the last 20 finished ones (newest first). */
    List<AiJob> jobs();

    /** Current job of a module, or its most recent finished one. */
    Optional<AiJob> job(String moduleId);

    /** Forget the saved Claude conversation of a module (next job starts a fresh conversation). */
    void resetConversation(String moduleId);

    void addListener(AiJobListener listener);

    /** Kill all running processes. Called from onDisable. */
    void shutdown();
}

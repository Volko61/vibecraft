package dev.vibecraft.ai;

/** Receives AI job notifications. Called from background threads: must not block, must not touch the Bukkit API directly. */
public interface AiJobListener {
    /** A new progress line (already human-readable, short, may contain emoji). */
    void onProgress(AiJob job, String line);

    /** Job reached a terminal state (SUCCEEDED / FAILED / STOPPED). */
    void onFinished(AiJob job);
}

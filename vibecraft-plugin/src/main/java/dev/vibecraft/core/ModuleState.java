package dev.vibecraft.core;

/** Lifecycle state of a module. */
public enum ModuleState {
    /** Found on disk, never loaded yet. */
    DISCOVERED,
    /** A compile/load is in progress. */
    COMPILING,
    /** Running (see ModuleInfo.generation). May have rolledBack=true if the latest attempt failed and the previous good version was restored. */
    ENABLED,
    /** Disabled on purpose (marker file modules/<id>/.vibe/disabled). */
    DISABLED,
    /** Last attempt failed and no good version is running. */
    FAILED,
    /** Automatically disabled by the circuit breaker (error burst / time budget abuse). A file change or reload re-arms it. */
    TRIPPED
}

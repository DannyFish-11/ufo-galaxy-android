package com.ufo.galaxy.local

/**
 * Represents the lifecycle state of the local closed-loop inference pipeline.
 *
 * Derived from [LocalLoopReadiness.state] so the UI and telemetry layers share a
 * single state vocabulary without depending directly on readiness field booleans.
 */
enum class LocalLoopState {

    /** Pipeline not yet initialised or explicitly torn down. */
    UNINITIALIZED,

    /** All required subsystems are ready; the pipeline can accept execution requests. */
    READY,

    /** The pipeline is actively executing a goal. */
    RUNNING,

    /**
     * One or more non-critical subsystems are unavailable; execution may proceed in a
     * degraded capacity (e.g., rule-based planning when the planner model is absent).
     */
    DEGRADED,

    /**
     * One or more critical subsystems (accessibility service, screenshot capture) are
     * unavailable. Execution is blocked until the blocker is resolved.
     */
    UNAVAILABLE;
}

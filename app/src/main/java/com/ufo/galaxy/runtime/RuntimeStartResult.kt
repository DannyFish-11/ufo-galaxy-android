package com.ufo.galaxy.runtime

/**
 * Structured result from a [LocalInferenceRuntimeManager.start],
 * [LocalInferenceRuntimeManager.startPlanner], or
 * [LocalInferenceRuntimeManager.startGrounding] call.
 *
 * Replaces the bare boolean returned by [loadModel]/[prewarm] with a value that carries
 * the failure stage and human-readable message so the app can make recovery decisions
 * (e.g., enter safe mode, show a specific error, or fall back to rule-based planning).
 */
sealed class RuntimeStartResult {

    /** Both (or the targeted) runtimes started and passed all warmup stages. */
    object Success : RuntimeStartResult()

    /**
     * The runtime failed at [stage] with [message].
     * [cause] is set when an unexpected exception was the root cause.
     */
    data class Failure(
        val stage: StartStage,
        val message: String,
        val cause: Throwable? = null
    ) : RuntimeStartResult()

    /**
     * At least one runtime is partially operational but full readiness was not achieved.
     * The app may continue in a limited-capability mode.
     */
    data class Degraded(val message: String) : RuntimeStartResult()

    // ── Convenience ──────────────────────────────────────────────────────────

    /** True only for [Success]. */
    val isSuccess: Boolean get() = this is Success

    /**
     * True when execution may proceed in some capacity: [Success] or [Degraded].
     * False for [Failure] — callers should not attempt inference.
     */
    val isUsable: Boolean get() = this is Success || this is Degraded

    // ── Start pipeline stages ────────────────────────────────────────────────

    /**
     * The pipeline stage at which a runtime start failed.
     *
     * The ordering reflects the start sequence; failures are reported at the
     * first stage that did not complete successfully.
     */
    enum class StartStage {
        /** Model weight files are missing or corrupted on disk. */
        MODEL_FILES,

        /** Inference server health endpoint not reachable or returned non-2xx. */
        HEALTH_CHECK,

        /** Dry-run inference request failed. */
        DRY_RUN,

        /** Server response was missing required fields or had wrong shape. */
        RESPONSE_VALIDATION,

        /** Runtime is incompatible with the loaded model manifest. */
        COMPATIBILITY_CHECK
    }
}

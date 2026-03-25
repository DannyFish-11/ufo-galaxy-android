package com.ufo.galaxy.inference

/**
 * Structured result from a [LocalPlannerService.warmupWithResult] or
 * [LocalGroundingService.warmupWithResult] call.
 *
 * Provides stage-level failure information so the runtime manager and diagnostics
 * layer can distinguish between endpoint liveness failures, inference failures, and
 * response-shape failures — rather than treating all warmup failures as a single opaque
 * boolean.
 *
 * @property success  True when the warmup completed all stages successfully.
 * @property stage    The pipeline stage at which warmup succeeded or failed.
 * @property error    Human-readable failure description; null on [success].
 */
data class WarmupResult(
    val success: Boolean,
    val stage: WarmupStage,
    val error: String? = null
) {

    /**
     * Stages in the warmup pipeline.
     *
     * Failures are reported at the earliest failing stage so the caller knows
     * exactly where the warmup contract was violated.
     */
    enum class WarmupStage {
        /** Health endpoint did not return a 2xx response. */
        HEALTH_CHECK,

        /** Dry-run inference request failed (network or server error). */
        DRY_RUN_INFERENCE,

        /** Inference server responded but the response shape was invalid. */
        RESPONSE_VALIDATION,

        /** Response content was present but could not be parsed into expected types. */
        PARSE_VALIDATION,

        /** All warmup stages completed successfully. */
        SUCCESS
    }

    companion object {
        /** Returns a successful [WarmupResult]. */
        fun success(): WarmupResult = WarmupResult(success = true, stage = WarmupStage.SUCCESS)

        /** Returns a failed [WarmupResult] at the given [stage] with [error] message. */
        fun failure(stage: WarmupStage, error: String): WarmupResult =
            WarmupResult(success = false, stage = stage, error = error)
    }
}

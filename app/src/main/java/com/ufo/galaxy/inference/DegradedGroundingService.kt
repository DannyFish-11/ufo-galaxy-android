package com.ufo.galaxy.inference

import com.ufo.galaxy.runtime.LocalInferenceRuntimeManager

/**
 * Runtime-state-aware fallback implementation of [LocalGroundingService].
 *
 * **Replaces [NoOpGroundingService] as the canonical fallback** when the SeeClick grounding
 * runtime is unavailable. Unlike [NoOpGroundingService], this implementation:
 *  - Carries the precise [runtimeState] that caused the degradation.
 *  - Returns [LocalGroundingService.GroundingResult] values whose [LocalGroundingService.GroundingResult.error]
 *    field includes a `DEGRADED:` prefix and an explicit human-readable reason, allowing
 *    consumers to distinguish a degraded-runtime result from a transient inference error.
 *  - Exposes a [degradedReason] property so diagnostics layers can surface the root cause.
 *
 * ## When to use
 * | Condition                                    | Service to use                                  |
 * |----------------------------------------------|-------------------------------------------------|
 * | Runtime `Running` (both components healthy)  | [com.ufo.galaxy.grounding.SeeClickGroundingEngine] |
 * | Runtime `Degraded` (one component healthy)   | [com.ufo.galaxy.grounding.SeeClickGroundingEngine] (degraded mode) |
 * | Runtime `Failed`, `Stopped`, or `SafeMode`   | **[DegradedGroundingService]**                  |
 * | Model files missing before first start       | **[DegradedGroundingService]**                  |
 *
 * ## Constructing from ManagerState
 * Use the [DegradedGroundingService.forState] factory to build an instance whose
 * [degradedReason] is derived automatically from the runtime manager's current state:
 * ```kotlin
 * val fallbackGrounding = DegradedGroundingService.forState(manager.state.value)
 * ```
 *
 * @param degradedReason  Human-readable explanation of why the grounding engine is degraded.
 * @param runtimeState    The [LocalInferenceRuntimeManager.ManagerState] at the time of
 *                        construction. Used to populate [LocalIntelligenceCapabilityStatus]
 *                        in diagnostics flows.
 */
class DegradedGroundingService(
    val degradedReason: String,
    val runtimeState: LocalInferenceRuntimeManager.ManagerState = LocalInferenceRuntimeManager.ManagerState.Stopped
) : LocalGroundingService {

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Always returns false: a degraded grounding engine cannot load a model. */
    override fun loadModel(): Boolean = false

    /** No-op: no model resources to release in degraded state. */
    override fun unloadModel() {}

    /** Always false: model is not loaded when the runtime is degraded. */
    override fun isModelLoaded(): Boolean = false

    // ── Warmup ────────────────────────────────────────────────────────────────

    /** Returns a structured [WarmupResult.WarmupStage.HEALTH_CHECK] failure with [degradedReason]. */
    override fun warmupWithResult(): WarmupResult =
        WarmupResult.failure(
            WarmupResult.WarmupStage.HEALTH_CHECK,
            "DEGRADED: grounding runtime unavailable — $degradedReason"
        )

    // ── Inference ────────────────────────────────────────────────────────────

    /**
     * Returns a [LocalGroundingService.GroundingResult] with an explicit `DEGRADED:` error.
     * Coordinates are zero and confidence is 0.0. Callers should check
     * [LocalGroundingService.GroundingResult.error] for a `DEGRADED:` prefix to distinguish
     * this from a transient inference failure.
     */
    override fun ground(
        intent: String,
        screenshotBase64: String,
        width: Int,
        height: Int
    ): LocalGroundingService.GroundingResult = LocalGroundingService.GroundingResult(
        x = 0,
        y = 0,
        confidence = 0f,
        element_description = "",
        error = "DEGRADED: grounding runtime unavailable — $degradedReason"
    )

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        /**
         * Builds a [DegradedGroundingService] whose [degradedReason] is derived from [state].
         *
         * See [DegradedPlannerService.forState] for the full state-to-reason mapping table.
         */
        fun forState(state: LocalInferenceRuntimeManager.ManagerState): DegradedGroundingService {
            val reason = DegradedPlannerService.stateToReason(state)
            return DegradedGroundingService(degradedReason = reason, runtimeState = state)
        }
    }
}

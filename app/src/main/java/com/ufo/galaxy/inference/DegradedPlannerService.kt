package com.ufo.galaxy.inference

import com.ufo.galaxy.runtime.LocalInferenceRuntimeManager

/**
 * Runtime-state-aware fallback implementation of [LocalPlannerService].
 *
 * **Replaces [NoOpPlannerService] as the canonical fallback** when the
 * MobileVLM planner runtime is unavailable. Unlike [NoOpPlannerService],
 * this implementation:
 *  - Carries the precise [runtimeState] that caused the degradation.
 *  - Returns [LocalPlannerService.PlanResult] values whose [LocalPlannerService.PlanResult.error]
 *    field includes a `DEGRADED:` prefix and an explicit human-readable reason, allowing
 *    consumers to distinguish a degraded-runtime result from a transient inference error.
 *  - Exposes a [degradedReason] property so diagnostics layers can surface the root cause.
 *
 * ## When to use
 * | Condition                                    | Service to use                          |
 * |----------------------------------------------|-----------------------------------------|
 * | Runtime `Running` (both components healthy)  | [com.ufo.galaxy.planner.MobileVlmPlanner] |
 * | Runtime `Degraded` (one component healthy)   | [com.ufo.galaxy.planner.MobileVlmPlanner] (degraded mode) |
 * | Runtime `Failed`, `Stopped`, or `SafeMode`   | **[DegradedPlannerService]**            |
 * | Model files missing before first start       | **[DegradedPlannerService]**            |
 *
 * ## Constructing from ManagerState
 * Use the [DegradedPlannerService.forState] factory to build an instance whose
 * [degradedReason] is derived automatically from the runtime manager's current state:
 * ```kotlin
 * val fallbackPlanner = DegradedPlannerService.forState(manager.state.value)
 * ```
 *
 * @param degradedReason  Human-readable explanation of why the planner is degraded.
 * @param runtimeState    The [LocalInferenceRuntimeManager.ManagerState] at the time of
 *                        construction. Used to populate [LocalIntelligenceCapabilityStatus]
 *                        in diagnostics flows.
 */
class DegradedPlannerService(
    val degradedReason: String,
    val runtimeState: LocalInferenceRuntimeManager.ManagerState = LocalInferenceRuntimeManager.ManagerState.Stopped
) : LocalPlannerService {

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Always returns false: a degraded planner cannot load a model. */
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
            "DEGRADED: planner runtime unavailable — $degradedReason"
        )

    // ── Inference ────────────────────────────────────────────────────────────

    /**
     * Returns a [LocalPlannerService.PlanResult] with an explicit `DEGRADED:` error.
     * The `steps` list is always empty. Callers should check [LocalPlannerService.PlanResult.error]
     * for a `DEGRADED:` prefix to distinguish this from a transient inference failure.
     */
    override fun plan(
        goal: String,
        constraints: List<String>,
        screenshotBase64: String?
    ): LocalPlannerService.PlanResult = LocalPlannerService.PlanResult(
        steps = emptyList(),
        error = "DEGRADED: planner runtime unavailable — $degradedReason"
    )

    /**
     * Returns a [LocalPlannerService.PlanResult] with an explicit `DEGRADED:` error.
     * Replan is not possible when the planner runtime is unavailable.
     */
    override fun replan(
        goal: String,
        constraints: List<String>,
        failedStep: LocalPlannerService.PlanStep,
        error: String,
        screenshotBase64: String?
    ): LocalPlannerService.PlanResult = LocalPlannerService.PlanResult(
        steps = emptyList(),
        error = "DEGRADED: planner runtime unavailable — $degradedReason"
    )

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        /**
         * Builds a [DegradedPlannerService] whose [degradedReason] is derived from [state].
         *
         * | [LocalInferenceRuntimeManager.ManagerState] | Reason string                           |
         * |---------------------------------------------|-----------------------------------------|
         * | [LocalInferenceRuntimeManager.ManagerState.Stopped]   | "runtime not started"                   |
         * | [LocalInferenceRuntimeManager.ManagerState.Starting]  | "runtime is starting"                   |
         * | [LocalInferenceRuntimeManager.ManagerState.Failed]    | "runtime failed to start: <reason>"     |
         * | [LocalInferenceRuntimeManager.ManagerState.SafeMode]  | "runtime is in safe mode"               |
         * | [LocalInferenceRuntimeManager.ManagerState.Degraded]  | "runtime degraded: <reason>"            |
         * | [LocalInferenceRuntimeManager.ManagerState.Running]   | "runtime is running (should not degrade)" |
         */
        fun forState(state: LocalInferenceRuntimeManager.ManagerState): DegradedPlannerService {
            val reason = stateToReason(state)
            return DegradedPlannerService(degradedReason = reason, runtimeState = state)
        }

        internal fun stateToReason(state: LocalInferenceRuntimeManager.ManagerState): String =
            when (state) {
                is LocalInferenceRuntimeManager.ManagerState.Stopped -> "runtime not started"
                is LocalInferenceRuntimeManager.ManagerState.Starting -> "runtime is starting"
                is LocalInferenceRuntimeManager.ManagerState.Recovering -> "runtime is recovering"
                is LocalInferenceRuntimeManager.ManagerState.Failed -> "runtime failed to start: ${state.reason}"
                is LocalInferenceRuntimeManager.ManagerState.SafeMode -> "runtime is in safe mode"
                is LocalInferenceRuntimeManager.ManagerState.Degraded -> "runtime degraded: ${state.reason}"
                is LocalInferenceRuntimeManager.ManagerState.Running ->
                    "runtime is running (unexpected degraded state)"
            }
    }
}

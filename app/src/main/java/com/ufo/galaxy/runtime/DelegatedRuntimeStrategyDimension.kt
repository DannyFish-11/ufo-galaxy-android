package com.ufo.galaxy.runtime

/**
 * PR-12 (Android) — Enum representing the five device-side delegation strategy / evolution
 * dimensions that collectively determine the overall strategic posture of the Android
 * delegated runtime for V2 program strategy / evolution control layer participation.
 *
 * Each dimension corresponds to a distinct class of strategic concern that the Android
 * delegated runtime must continuously evaluate to produce a meaningful program-level
 * evolution posture signal.  Unlike readiness ([DelegatedRuntimeReadinessDimension]),
 * acceptance ([DelegatedRuntimeAcceptanceDimension]), or post-graduation governance
 * ([DelegatedRuntimeGovernanceDimension]), strategy dimensions are evaluated at the
 * program level to answer whether the device-side delegated runtime is on track to
 * meet V2 long-term evolution goals.
 *
 * ## Dimension overview
 *
 * | Dimension                          | Wire value                          | Strategic concern                                                                       |
 * |------------------------------------|-------------------------------------|-----------------------------------------------------------------------------------------|
 * | [CANONICAL_CONTRACT_STABILITY]     | `canonical_contract_stability`      | Contract stability on device; contract drift risk for canonical delegated runtime path. |
 * | [GOVERNANCE_TREND]                 | `governance_trend`                  | Truth / result / execution / compat / continuity governance trend on device.            |
 * | [ROLLOUT_MATURITY]                 | `rollout_maturity`                  | Rollout maturity / default-on posture on device.                                        |
 * | [REGRESSION_PRESSURE]              | `regression_pressure`               | Accumulated regression pressure or strategic risk on device runtime.                    |
 * | [RUNTIME_COUPLING_DRIFT]           | `runtime_coupling_drift`            | Cross-module coupling drift within the delegated runtime stack.                         |
 *
 * @property wireValue Stable lowercase string used in JSON payloads and strategy reports.
 *
 * @see DelegatedRuntimeStrategyEvaluator
 * @see DeviceStrategyArtifact
 * @see DelegatedRuntimeStrategySnapshot
 */
enum class DelegatedRuntimeStrategyDimension(val wireValue: String) {

    /**
     * Canonical contract stability dimension.
     *
     * Covers whether the canonical delegated runtime contract on this device remains
     * stable — i.e. there is no detectable contract drift, interface contract breakage,
     * or cross-boundary semantic divergence relative to the V2 canonical contract
     * baseline.
     *
     * Primary evidence sources: [DelegatedRuntimeAcceptanceEvaluator],
     * [DelegatedRuntimePostGraduationGovernanceEvaluator], [AndroidDelegatedFlowBridge].
     */
    CANONICAL_CONTRACT_STABILITY("canonical_contract_stability"),

    /**
     * Governance trend dimension.
     *
     * Covers whether the overall truth / result / execution / compat / continuity
     * governance trend on this device is moving in a positive direction.  A negative
     * trend — e.g., accumulating governance regression signals across PR-11 dimensions —
     * constitutes a strategy-level risk even if no single regression has yet triggered
     * a governance violation.
     *
     * Primary evidence sources: [DelegatedRuntimePostGraduationGovernanceEvaluator],
     * [AndroidLocalTruthOwnershipCoordinator], [AndroidFlowAwareResultConvergenceParticipant],
     * [AndroidCanonicalExecutionEventOwner], [AndroidCompatLegacyBlockingParticipant],
     * [AndroidRecoveryParticipationOwner].
     */
    GOVERNANCE_TREND("governance_trend"),

    /**
     * Rollout maturity dimension.
     *
     * Covers whether the device-side rollout posture — including default-on status,
     * enforcement tightening progress, and long-tail compat retirement progress — has
     * reached the expected stage for the current V2 program phase.
     *
     * Primary evidence sources: [RolloutControlSnapshot], [CompatibilityRetirementFence],
     * [LongTailCompatibilityRegistry], [CompatibilitySurfaceRetirementRegistry].
     */
    ROLLOUT_MATURITY("rollout_maturity"),

    /**
     * Regression pressure dimension.
     *
     * Covers whether the accumulated volume of regression signals — across readiness,
     * acceptance, and governance layers — constitutes a strategic risk to the delegated
     * runtime's long-term evolution trajectory.  Elevated regression pressure indicates
     * that the device-side runtime may be drifting away from canonical targets before
     * formal violations are detected.
     *
     * Primary evidence sources: [DelegatedRuntimeReadinessEvaluator],
     * [DelegatedRuntimeAcceptanceEvaluator],
     * [DelegatedRuntimePostGraduationGovernanceEvaluator].
     */
    REGRESSION_PRESSURE("regression_pressure"),

    /**
     * Runtime coupling drift dimension.
     *
     * Covers whether cross-module coupling within the Android delegated runtime stack —
     * e.g. between receiver, activation record, pipeline, loop controller, and canonical
     * capability modules — has drifted beyond acceptable cohesion bounds in ways that
     * introduce program-level evolution risk.
     *
     * Primary evidence sources: [AndroidDelegatedFlowBridge], [DelegatedActivationRecord],
     * [DelegatedRuntimeReceiver], [RuntimeController].
     */
    RUNTIME_COUPLING_DRIFT("runtime_coupling_drift");

    companion object {

        /**
         * Returns the [DelegatedRuntimeStrategyDimension] with the given [wireValue],
         * or `null` if no match is found.
         *
         * @param value  The wire value string to look up.
         */
        fun fromValue(value: String?): DelegatedRuntimeStrategyDimension? =
            entries.firstOrNull { it.wireValue == value }
    }
}

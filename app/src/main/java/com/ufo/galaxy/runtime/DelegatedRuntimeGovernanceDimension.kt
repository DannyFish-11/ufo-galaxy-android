package com.ufo.galaxy.runtime

/**
 * PR-11 (Android) — Enum representing the five device-side post-graduation governance
 * dimensions that collectively determine ongoing canonical compliance of the Android
 * delegated runtime after acceptance / graduation.
 *
 * Each dimension corresponds to a distinct class of regression that can surface after the
 * delegated runtime has graduated through the V2 final acceptance gate.  Unlike readiness
 * ([DelegatedRuntimeReadinessDimension]) or acceptance ([DelegatedRuntimeAcceptanceDimension]),
 * governance dimensions are evaluated continuously during the post-graduation runtime phase
 * to answer whether the device still complies with the canonical path.
 *
 * ## Dimension overview
 *
 * | Dimension                                    | Wire value                                  | Regression class                                                         |
 * |----------------------------------------------|---------------------------------------------|--------------------------------------------------------------------------|
 * | [TRUTH_ALIGNMENT_REGRESSION]                 | `truth_alignment_regression`                | Truth drift / V2 central truth alignment regression.                     |
 * | [RESULT_CONVERGENCE_REGRESSION]              | `result_convergence_regression`             | Result convergence divergence or late-partial gating regression.         |
 * | [CANONICAL_EXECUTION_VISIBILITY_REGRESSION]  | `canonical_execution_visibility_regression` | Operator visibility / execution event emission regression.               |
 * | [COMPAT_BYPASS_REINTRODUCTION]               | `compat_bypass_reintroduction`              | Compat / legacy bypass reintroduction after blocking was established.    |
 * | [CONTINUITY_CONTRACT_REGRESSION]             | `continuity_contract_regression`            | Continuity / replay / reconnect contract regression.                     |
 *
 * @property wireValue Stable lowercase string used in JSON payloads and governance reports.
 *
 * @see DelegatedRuntimePostGraduationGovernanceEvaluator
 * @see DeviceGovernanceArtifact
 * @see DelegatedRuntimeGovernanceSnapshot
 */
enum class DelegatedRuntimeGovernanceDimension(val wireValue: String) {

    /**
     * Truth alignment regression dimension.
     *
     * Covers whether the Android delegated runtime has experienced truth drift or
     * V2 central truth alignment regression since graduation.  A violation in this
     * dimension means local truth ownership assertions are no longer reliably aligned
     * with the V2 canonical truth state.
     *
     * Primary module: [AndroidLocalTruthOwnershipCoordinator].
     */
    TRUTH_ALIGNMENT_REGRESSION("truth_alignment_regression"),

    /**
     * Result convergence regression dimension.
     *
     * Covers whether the Android result convergence participation layer has experienced
     * divergence, late-partial leakage after finals, or duplicate-suppression regression
     * since graduation.
     *
     * Primary module: [AndroidFlowAwareResultConvergenceParticipant].
     */
    RESULT_CONVERGENCE_REGRESSION("result_convergence_regression"),

    /**
     * Canonical execution visibility regression dimension.
     *
     * Covers whether the Android canonical execution event / operator visibility layer
     * has experienced post-terminal noise reintroduction, reconnect alignment gaps, or
     * uncontrolled noise-gate suppression since graduation.
     *
     * Primary module: [AndroidCanonicalExecutionEventOwner].
     */
    CANONICAL_EXECUTION_VISIBILITY_REGRESSION("canonical_execution_visibility_regression"),

    /**
     * Compat / legacy bypass reintroduction dimension.
     *
     * Covers whether blocked legacy paths or compat bypass risk has been reintroduced
     * since graduation.  A violation in this dimension means compat or legacy emit
     * semantics are again reaching V2 canonical surfaces.
     *
     * Primary module: [AndroidCompatLegacyBlockingParticipant].
     */
    COMPAT_BYPASS_REINTRODUCTION("compat_bypass_reintroduction"),

    /**
     * Continuity / replay / reconnect contract regression dimension.
     *
     * Covers whether the Android runtime has experienced replay-safety, session
     * continuity, or reconnect-contract regression since graduation.  A violation
     * in this dimension means cross-reconnect canonical state is no longer reliably
     * preserved.
     *
     * Primary module: [AndroidRecoveryParticipationOwner], [AndroidContinuityIntegration].
     */
    CONTINUITY_CONTRACT_REGRESSION("continuity_contract_regression");

    companion object {

        /**
         * Returns the [DelegatedRuntimeGovernanceDimension] with the given [wireValue],
         * or `null` if no match is found.
         *
         * @param value  The wire value string to look up.
         */
        fun fromValue(value: String?): DelegatedRuntimeGovernanceDimension? =
            entries.firstOrNull { it.wireValue == value }
    }
}

package com.ufo.galaxy.runtime

import java.util.UUID

/**
 * PR-11 (Android) — Android delegated runtime post-graduation governance / enforcement
 * participation owner / evaluator / reporting layer for V2 post-graduation governance
 * layer participation.
 *
 * [DelegatedRuntimePostGraduationGovernanceEvaluator] is the unified Android-side entry
 * point for post-graduation canonical compliance judgment and structured governance
 * artifact reporting.  It answers the following questions continuously after the delegated
 * runtime has graduated:
 *
 *  - Is there a truth alignment regression on this device since graduation?
 *  - Is there a result convergence regression on this device since graduation?
 *  - Is there a canonical execution visibility regression on this device since graduation?
 *  - Has compat / legacy bypass been reintroduced on this device since graduation?
 *  - Is there a continuity / reconnect contract regression on this device since graduation?
 *  - What [DeviceGovernanceArtifact] should be reported to the V2 post-graduation governance layer?
 *  - What [DelegatedRuntimeGovernanceSnapshot] can be produced for the V2 layer to absorb?
 *
 * ## Background and motivation
 *
 * PR-10 established Android delegated runtime final acceptance / graduation participation.
 * However, acceptance only determines whether the device has sufficient evidence to graduate;
 * it does not provide ongoing assurance that canonical compliance is maintained after
 * graduation.
 *
 * [DelegatedRuntimePostGraduationGovernanceEvaluator] closes this gap by providing a
 * continuous governance observation layer that:
 *
 *  - Monitors five [DelegatedRuntimeGovernanceDimension] values for post-graduation regression.
 *  - Produces structured [DeviceGovernanceArtifact] verdicts that distinguish compliant,
 *    violation, and unknown states.
 *  - Generates [DelegatedRuntimeGovernanceSnapshot] reports that the V2 PR-11
 *    post-graduation governance layer can absorb as ongoing canonical compliance signals.
 *
 * ## Governance dimensions
 *
 * Five dimensions govern the overall governance verdict ([DelegatedRuntimeGovernanceDimension]):
 *
 * | Dimension                                          | Description                                                                       |
 * |----------------------------------------------------|-----------------------------------------------------------------------------------|
 * | [DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION]                | Truth drift / V2 central truth alignment regression.          |
 * | [DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION]             | Result convergence divergence or late-partial gating regression. |
 * | [DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION] | Operator visibility / execution event emission regression.    |
 * | [DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION]              | Compat / legacy bypass reintroduction.                        |
 * | [DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION]            | Continuity / replay / reconnect contract regression.          |
 *
 * ## Evaluation logic
 *
 * [evaluateGovernance] applies the following precedence order:
 *
 * 1. **Missing signal** — if any dimension has status
 *    [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN] →
 *    [DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal].
 *
 * 2. **Truth regression** — if [DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION]
 *    has status [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION] →
 *    [DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression].
 *
 * 3. **Result regression** — if [DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION]
 *    has status [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION] →
 *    [DeviceGovernanceArtifact.DeviceGovernanceViolationDueToResultRegression].
 *
 * 4. **Execution visibility regression** — if
 *    [DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION]
 *    has status [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION] →
 *    [DeviceGovernanceArtifact.DeviceGovernanceViolationDueToExecutionVisibilityRegression].
 *
 * 5. **Compat bypass** — if [DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION]
 *    has status [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION] →
 *    [DeviceGovernanceArtifact.DeviceGovernanceViolationDueToCompatBypass].
 *
 * 6. **Continuity regression** — if [DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION]
 *    has status [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION] →
 *    [DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression] with the
 *    continuity dimension (continuity contract regressions directly surface as truth
 *    regressions in the post-graduation governance model).
 *
 * 7. **Compliant** — all five dimensions are [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.COMPLIANT]
 *    → [DeviceGovernanceArtifact.DeviceGovernanceCompliant].
 *
 * ## Dimension observation API
 *
 * Each governance dimension is governed by an internal observation gate that can be in one
 * of three states:
 *
 * | Gate state     | [DelegatedRuntimeGovernanceSnapshot.DimensionStatus] produced       |
 * |----------------|---------------------------------------------------------------------|
 * | Compliant      | [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.COMPLIANT]      |
 * | Regression     | [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION]     |
 * | Unknown        | [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN]        |
 *
 * Use [markDimensionCompliant], [markDimensionRegression], [markDimensionUnknown], and
 * [getDimensionStatus] to manage dimension observation gates.
 *
 * ## Integration points
 *
 * [DelegatedRuntimePostGraduationGovernanceEvaluator] establishes clear integration
 * boundaries with the following existing runtime modules:
 *
 * | Integration point constant                              | Module                                                                    | Role                                                                                              |
 * |---------------------------------------------------------|---------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|
 * | [INTEGRATION_ACCEPTANCE_EVALUATOR]                      | [DelegatedRuntimeAcceptanceEvaluator]                                     | Acceptance prerequisite: governance is only meaningful after DeviceAcceptedForGraduation.         |
 * | [INTEGRATION_TRUTH_OWNER]                               | [AndroidLocalTruthOwnershipCoordinator]                                   | Truth alignment regression observation source.                                                    |
 * | [INTEGRATION_RESULT_CONVERGENCE]                        | [AndroidFlowAwareResultConvergenceParticipant]                            | Result convergence regression observation source.                                                 |
 * | [INTEGRATION_EXECUTION_EVENT_OWNER]                     | [AndroidCanonicalExecutionEventOwner]                                     | Canonical execution visibility regression observation source.                                     |
 * | [INTEGRATION_COMPAT_BLOCKING]                           | [AndroidCompatLegacyBlockingParticipant]                                  | Compat bypass reintroduction observation source.                                                  |
 * | [INTEGRATION_RECOVERY_OWNER]                            | [AndroidRecoveryParticipationOwner]                                       | Continuity contract regression observation source.                                                |
 * | [INTEGRATION_RUNTIME_CONTROLLER]                        | [RuntimeController]                                                       | Governance snapshot emission; artifacts forwarded via reconciliation signal.                      |
 * | [INTEGRATION_DELEGATED_FLOW_BRIDGE]                     | [AndroidDelegatedFlowBridge]                                              | Flow-level governance alignment; governance snapshot consumed by the post-graduation flow bridge. |
 *
 * ## Thread safety
 *
 * All dimension observation operations ([markDimensionCompliant], [markDimensionRegression],
 * [markDimensionUnknown], [getDimensionStatus], [getDimensionRegressionReason],
 * [clearAllDimensionStates]) use [synchronized] blocks for safe cross-thread access.
 * [evaluateGovernance] and [buildSnapshot] are pure functions that rely only on the
 * synchronized accessors above.
 *
 * ## Relationship to sibling PR layers
 *
 * [DelegatedRuntimePostGraduationGovernanceEvaluator] is the *post-graduation governance
 * participation* layer; it builds on top of the PR-10 *final acceptance participation* layer
 * ([DelegatedRuntimeAcceptanceEvaluator]) and aggregates continuous regression signals from
 * the same per-dimension evidence modules (PR-4 through PR-8) into a unified device-side
 * governance verdict that the V2 PR-11 post-graduation governance / enforcement layer can
 * consume as ongoing canonical compliance signals.
 *
 * @see DelegatedRuntimeGovernanceDimension
 * @see DeviceGovernanceArtifact
 * @see DelegatedRuntimeGovernanceSnapshot
 * @see DelegatedRuntimeAcceptanceEvaluator
 * @see AndroidLocalTruthOwnershipCoordinator
 * @see AndroidFlowAwareResultConvergenceParticipant
 * @see AndroidCanonicalExecutionEventOwner
 * @see AndroidCompatLegacyBlockingParticipant
 * @see AndroidRecoveryParticipationOwner
 */
class DelegatedRuntimePostGraduationGovernanceEvaluator {

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * Per-dimension governance observation gate state.
     *
     * Map key is [DelegatedRuntimeGovernanceDimension]; value is a pair of
     * ([DelegatedRuntimeGovernanceSnapshot.DimensionStatus], optional regression reason).
     * All dimensions start as [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN]
     * until an observation signal is received.
     */
    private val _dimensionStates: MutableMap<
        DelegatedRuntimeGovernanceDimension,
        Pair<DelegatedRuntimeGovernanceSnapshot.DimensionStatus, String?>
    > = mutableMapOf()

    // ── Core API — dimension observation gate management ──────────────────────

    /**
     * Marks [dimension] as compliant — no regression detected on this dimension.
     *
     * After this call, [getDimensionStatus] returns
     * [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.COMPLIANT] for [dimension].
     *
     * @param dimension  The governance dimension to mark as compliant.
     */
    fun markDimensionCompliant(dimension: DelegatedRuntimeGovernanceDimension) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                DelegatedRuntimeGovernanceSnapshot.DimensionStatus.COMPLIANT to null
        }
    }

    /**
     * Marks [dimension] as having a detected regression.
     *
     * After this call, [getDimensionStatus] returns
     * [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION] for [dimension].
     *
     * @param dimension         The governance dimension to mark as having a regression.
     * @param regressionReason  Human-readable explanation of the regression.
     */
    fun markDimensionRegression(
        dimension: DelegatedRuntimeGovernanceDimension,
        regressionReason: String
    ) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION to regressionReason
        }
    }

    /**
     * Marks [dimension] as having no observation signal (unknown).
     *
     * After this call, [getDimensionStatus] returns
     * [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN] for [dimension].
     *
     * @param dimension  The governance dimension to mark as unknown.
     * @param reason     Optional explanation of why the signal is missing.
     */
    fun markDimensionUnknown(
        dimension: DelegatedRuntimeGovernanceDimension,
        reason: String? = null
    ) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN to reason
        }
    }

    /**
     * Returns the current [DelegatedRuntimeGovernanceSnapshot.DimensionStatus] for [dimension].
     *
     * Returns [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN] if no signal has
     * been received for [dimension].
     *
     * @param dimension  The governance dimension to query.
     */
    fun getDimensionStatus(
        dimension: DelegatedRuntimeGovernanceDimension
    ): DelegatedRuntimeGovernanceSnapshot.DimensionStatus =
        synchronized(_dimensionStates) {
            _dimensionStates[dimension]?.first
                ?: DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN
        }

    /**
     * Returns the regression reason registered for [dimension], or `null` if the dimension
     * is not in [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION] or
     * [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN] state.
     *
     * @param dimension  The governance dimension to query.
     */
    fun getDimensionRegressionReason(dimension: DelegatedRuntimeGovernanceDimension): String? =
        synchronized(_dimensionStates) { _dimensionStates[dimension]?.second }

    /**
     * Resets all dimension states to [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN].
     *
     * Use only when the full post-graduation governance era is being reset (e.g. session
     * close / process recreation) and all prior governance conclusions are no longer
     * applicable.
     */
    fun clearAllDimensionStates() {
        synchronized(_dimensionStates) { _dimensionStates.clear() }
    }

    // ── Core API — governance evaluation ─────────────────────────────────────

    /**
     * Evaluates the current post-graduation governance of the Android delegated runtime
     * and returns the appropriate [DeviceGovernanceArtifact].
     *
     * ## Evaluation logic
     *
     * 1. **Missing signal** — any dimension with [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN]
     *    produces [DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal].
     *
     * 2. **Truth regression** — [DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION]
     *    with [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION] produces
     *    [DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression].
     *
     * 3. **Result regression** — [DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION]
     *    with [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION] produces
     *    [DeviceGovernanceArtifact.DeviceGovernanceViolationDueToResultRegression].
     *
     * 4. **Execution visibility regression** — [DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION]
     *    with [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION] produces
     *    [DeviceGovernanceArtifact.DeviceGovernanceViolationDueToExecutionVisibilityRegression].
     *
     * 5. **Compat bypass** — [DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION]
     *    with [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION] produces
     *    [DeviceGovernanceArtifact.DeviceGovernanceViolationDueToCompatBypass].
     *
     * 6. **Continuity regression** — [DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION]
     *    with [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION] produces
     *    [DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression] with the
     *    continuity dimension (continuity contract regressions directly surface as truth
     *    regressions in the post-graduation governance model).
     *
     * 7. **Compliant** — all five dimensions are [DelegatedRuntimeGovernanceSnapshot.DimensionStatus.COMPLIANT]
     *    → [DeviceGovernanceArtifact.DeviceGovernanceCompliant].
     *
     * @param deviceId    The device identifier to embed in the artifact.
     * @param snapshotId  The snapshot identifier to embed in the artifact.
     * @return The [DeviceGovernanceArtifact] for the current dimension states.
     */
    fun evaluateGovernance(deviceId: String, snapshotId: String): DeviceGovernanceArtifact {
        val states = synchronized(_dimensionStates) { HashMap(_dimensionStates) }

        // ── 1. Missing signal ──────────────────────────────────────────────────
        val unknownDimensions = DelegatedRuntimeGovernanceDimension.entries.filter { dim ->
            (states[dim]?.first ?: DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN) ==
                DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN
        }.toSet()

        if (unknownDimensions.isNotEmpty()) {
            return DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal(
                deviceId = deviceId,
                snapshotId = snapshotId,
                missingDimensions = unknownDimensions
            )
        }

        // ── 2. Truth regression ────────────────────────────────────────────────
        val truthState =
            states[DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION]
        if (truthState?.first == DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION) {
            return DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression(
                deviceId = deviceId,
                snapshotId = snapshotId,
                regressionReason = truthState.second ?: REASON_TRUTH_REGRESSION_DEFAULT
            )
        }

        // ── 3. Result regression ───────────────────────────────────────────────
        val resultState =
            states[DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION]
        if (resultState?.first == DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION) {
            return DeviceGovernanceArtifact.DeviceGovernanceViolationDueToResultRegression(
                deviceId = deviceId,
                snapshotId = snapshotId,
                regressionReason = resultState.second ?: REASON_RESULT_REGRESSION_DEFAULT
            )
        }

        // ── 4. Execution visibility regression ────────────────────────────────
        val executionState =
            states[DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION]
        if (executionState?.first == DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION) {
            return DeviceGovernanceArtifact.DeviceGovernanceViolationDueToExecutionVisibilityRegression(
                deviceId = deviceId,
                snapshotId = snapshotId,
                regressionReason = executionState.second
                    ?: REASON_EXECUTION_VISIBILITY_REGRESSION_DEFAULT
            )
        }

        // ── 5. Compat bypass ───────────────────────────────────────────────────
        val compatState =
            states[DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION]
        if (compatState?.first == DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION) {
            return DeviceGovernanceArtifact.DeviceGovernanceViolationDueToCompatBypass(
                deviceId = deviceId,
                snapshotId = snapshotId,
                regressionReason = compatState.second ?: REASON_COMPAT_BYPASS_DEFAULT
            )
        }

        // ── 6. Continuity regression ───────────────────────────────────────────
        val continuityState =
            states[DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION]
        if (continuityState?.first == DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION) {
            return DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression(
                deviceId = deviceId,
                snapshotId = snapshotId,
                regressionReason = continuityState.second ?: REASON_CONTINUITY_REGRESSION_DEFAULT,
                dimension = DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION
            )
        }

        // ── 7. All dimensions compliant ────────────────────────────────────────
        return DeviceGovernanceArtifact.DeviceGovernanceCompliant(
            deviceId = deviceId,
            snapshotId = snapshotId
        )
    }

    // ── Core API — snapshot builder ───────────────────────────────────────────

    /**
     * Builds a full [DelegatedRuntimeGovernanceSnapshot] for the current dimension states.
     *
     * The snapshot embeds:
     *  - A freshly generated [snapshotId] (UUID).
     *  - The [DeviceGovernanceArtifact] from [evaluateGovernance].
     *  - Per-dimension [DelegatedRuntimeGovernanceSnapshot.DimensionObservationState] entries.
     *  - The current wall-clock timestamp in [DelegatedRuntimeGovernanceSnapshot.reportedAtMs].
     *
     * @param deviceId  The device identifier to embed in the snapshot.
     * @return The [DelegatedRuntimeGovernanceSnapshot] for the current state.
     */
    fun buildSnapshot(deviceId: String): DelegatedRuntimeGovernanceSnapshot {
        val snapshotId = UUID.randomUUID().toString()
        val artifact = evaluateGovernance(deviceId = deviceId, snapshotId = snapshotId)

        val dimensionStates = DelegatedRuntimeGovernanceDimension.entries.associate { dim ->
            val status = getDimensionStatus(dim)
            val regressionReason = getDimensionRegressionReason(dim)
            dim to DelegatedRuntimeGovernanceSnapshot.DimensionObservationState(
                dimension = dim,
                status = status,
                regressionReason = regressionReason
            )
        }

        return DelegatedRuntimeGovernanceSnapshot(
            snapshotId = snapshotId,
            deviceId = deviceId,
            artifact = artifact,
            dimensionStates = dimensionStates,
            reportedAtMs = System.currentTimeMillis()
        )
    }

    // ── Companion — constants ─────────────────────────────────────────────────

    companion object {

        // ── Artifact semantic tag constants ───────────────────────────────────

        /**
         * Canonical wire value for the [DeviceGovernanceArtifact.DeviceGovernanceCompliant]
         * artifact.
         *
         * All five governance dimensions are observed without regression; the device remains
         * compliant with the canonical runtime path.
         */
        const val ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT = "device_governance_compliant"

        /**
         * Canonical wire value for the
         * [DeviceGovernanceArtifact.DeviceGovernanceViolationDueToTruthRegression] artifact.
         *
         * Truth drift or V2 central truth alignment regression has been detected since
         * graduation (also used for continuity contract regressions in the governance model).
         */
        const val ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_TRUTH_REGRESSION =
            "device_governance_violation_due_to_truth_regression"

        /**
         * Canonical wire value for the
         * [DeviceGovernanceArtifact.DeviceGovernanceViolationDueToResultRegression] artifact.
         *
         * Result convergence divergence or late-partial gating regression has been detected
         * since graduation.
         */
        const val ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_RESULT_REGRESSION =
            "device_governance_violation_due_to_result_regression"

        /**
         * Canonical wire value for the
         * [DeviceGovernanceArtifact.DeviceGovernanceViolationDueToExecutionVisibilityRegression]
         * artifact.
         *
         * Canonical execution event / operator visibility regression has been detected
         * since graduation.
         */
        const val ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_EXECUTION_VISIBILITY_REGRESSION =
            "device_governance_violation_due_to_execution_visibility_regression"

        /**
         * Canonical wire value for the
         * [DeviceGovernanceArtifact.DeviceGovernanceViolationDueToCompatBypass] artifact.
         *
         * Compat / legacy bypass reintroduction has been detected since graduation.
         */
        const val ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_COMPAT_BYPASS =
            "device_governance_violation_due_to_compat_bypass"

        /**
         * Canonical wire value for the
         * [DeviceGovernanceArtifact.DeviceGovernanceUnknownDueToMissingSignal] artifact.
         *
         * One or more governance dimensions have no observation signal; the governance
         * verdict cannot be determined.
         */
        const val ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL =
            "device_governance_unknown_due_to_missing_signal"

        // ── Integration point constants ───────────────────────────────────────

        /**
         * Integration point identifier for [DelegatedRuntimeAcceptanceEvaluator].
         *
         * Acceptance evaluator: provides the [DeviceAcceptanceArtifact.DeviceAcceptedForGraduation]
         * prerequisite; post-graduation governance is only meaningful after graduation.
         */
        const val INTEGRATION_ACCEPTANCE_EVALUATOR = "DelegatedRuntimeAcceptanceEvaluator"

        /**
         * Integration point identifier for [AndroidLocalTruthOwnershipCoordinator].
         *
         * Truth owner: supplies the truth alignment regression observation signal for
         * [DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION].
         */
        const val INTEGRATION_TRUTH_OWNER = "AndroidLocalTruthOwnershipCoordinator"

        /**
         * Integration point identifier for [AndroidFlowAwareResultConvergenceParticipant].
         *
         * Result convergence participant: supplies the result convergence regression
         * observation signal for [DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION].
         */
        const val INTEGRATION_RESULT_CONVERGENCE = "AndroidFlowAwareResultConvergenceParticipant"

        /**
         * Integration point identifier for [AndroidCanonicalExecutionEventOwner].
         *
         * Execution event owner: supplies the canonical execution visibility regression
         * observation signal for
         * [DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION].
         */
        const val INTEGRATION_EXECUTION_EVENT_OWNER = "AndroidCanonicalExecutionEventOwner"

        /**
         * Integration point identifier for [AndroidCompatLegacyBlockingParticipant].
         *
         * Compat blocking participant: supplies the compat bypass reintroduction observation
         * signal for [DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION].
         */
        const val INTEGRATION_COMPAT_BLOCKING = "AndroidCompatLegacyBlockingParticipant"

        /**
         * Integration point identifier for [AndroidRecoveryParticipationOwner].
         *
         * Recovery owner: supplies the continuity contract regression observation signal
         * for [DelegatedRuntimeGovernanceDimension.CONTINUITY_CONTRACT_REGRESSION].
         */
        const val INTEGRATION_RECOVERY_OWNER = "AndroidRecoveryParticipationOwner"

        /**
         * Integration point identifier for [RuntimeController].
         *
         * Runtime controller: consumes [DelegatedRuntimeGovernanceSnapshot] and forwards
         * the device governance artifact to V2 via the reconciliation signal channel.
         */
        const val INTEGRATION_RUNTIME_CONTROLLER = "RuntimeController"

        /**
         * Integration point identifier for [AndroidDelegatedFlowBridge].
         *
         * Delegated flow bridge: aligns flow-level governance before enforcement decisions;
         * governance snapshot is consumed at the post-graduation flow bridge.
         */
        const val INTEGRATION_DELEGATED_FLOW_BRIDGE = "AndroidDelegatedFlowBridge"

        // ── Default regression reason constants ───────────────────────────────

        /**
         * Default regression reason used when the truth alignment dimension has a regression
         * but no explicit reason was registered.
         */
        const val REASON_TRUTH_REGRESSION_DEFAULT =
            "truth_alignment_regression_detected"

        /**
         * Default regression reason used when the result convergence dimension has a
         * regression but no explicit reason was registered.
         */
        const val REASON_RESULT_REGRESSION_DEFAULT =
            "result_convergence_regression_detected"

        /**
         * Default regression reason used when the canonical execution visibility dimension
         * has a regression but no explicit reason was registered.
         */
        const val REASON_EXECUTION_VISIBILITY_REGRESSION_DEFAULT =
            "canonical_execution_visibility_regression_detected"

        /**
         * Default regression reason used when the compat bypass reintroduction dimension
         * has a regression but no explicit reason was registered.
         */
        const val REASON_COMPAT_BYPASS_DEFAULT =
            "compat_bypass_reintroduction_detected"

        /**
         * Default regression reason used when the continuity contract dimension has a
         * regression but no explicit reason was registered.
         */
        const val REASON_CONTINUITY_REGRESSION_DEFAULT =
            "continuity_contract_regression_detected"

        // ── PR designation ────────────────────────────────────────────────────

        /**
         * The PR designation for this component.
         */
        const val INTRODUCED_PR = 11

        /**
         * Human-readable description of this component's role in the delegated runtime
         * post-graduation governance / enforcement participation model.
         */
        const val DESCRIPTION =
            "Android delegated runtime post-graduation governance / enforcement participation " +
                "owner / evaluator / reporting layer: continuously observes five governance " +
                "dimensions (truth alignment regression, result convergence regression, " +
                "canonical execution visibility regression, compat bypass reintroduction, " +
                "continuity contract regression) and produces structured DeviceGovernanceArtifact " +
                "and DelegatedRuntimeGovernanceSnapshot outputs for V2 post-graduation " +
                "governance / enforcement layer participation."
    }
}

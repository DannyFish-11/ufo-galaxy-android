package com.ufo.galaxy.runtime

import java.util.UUID

/**
 * PR-12 (Android) — Android delegated runtime strategy / evolution participation
 * owner / evaluator / reporting layer for V2 program strategy / evolution control
 * layer participation.
 *
 * [DelegatedRuntimeStrategyEvaluator] is the unified Android-side entry point for
 * delegated canonical runtime program-level posture judgment and structured strategy
 * artifact reporting.  It answers the following questions for every device-side
 * strategy evaluation:
 *
 *  - Is the Android delegated runtime's overall evolution posture still aligned with
 *    the canonical strategy?
 *  - Which strategy dimensions carry strategic risk — contract instability, governance
 *    trend, rollout maturity gap, or runtime coupling drift?
 *  - Are there dimensions with no program signal, making the strategy posture unknown?
 *  - What [DeviceStrategyArtifact] should be reported to the V2 program strategy /
 *    evolution control layer?
 *  - What [DelegatedRuntimeStrategySnapshot] can be produced for V2 to absorb as a
 *    structured Android strategy posture signal?
 *
 * ## Background and motivation
 *
 * PR-11 established Android delegated runtime post-graduation governance / enforcement
 * participation.  However, governance only determines whether the device has maintained
 * canonical compliance since graduation; it does not provide a program-level view of
 * whether the device-side delegated runtime's long-term evolution trajectory is aligned
 * with the V2 canonical strategy.
 *
 * [DelegatedRuntimeStrategyEvaluator] closes this gap by providing a program-level
 * strategy observation layer that:
 *
 *  - Monitors five [DelegatedRuntimeStrategyDimension] values for strategy-level risk.
 *  - Produces structured [DeviceStrategyArtifact] verdicts that distinguish on-track,
 *    risk-by-category, and unknown-signal states.
 *  - Generates [DelegatedRuntimeStrategySnapshot] reports that the V2 PR-12 program
 *    strategy / evolution control layer can absorb as structured Android strategy
 *    posture signals.
 *
 * ## Strategy dimensions
 *
 * Five dimensions govern the overall strategy verdict ([DelegatedRuntimeStrategyDimension]):
 *
 * | Dimension                                      | Description                                                                        |
 * |------------------------------------------------|------------------------------------------------------------------------------------|
 * | [DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY] | Canonical contract stability / contract drift risk on device.   |
 * | [DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND]             | Truth / result / execution / compat / continuity governance trend. |
 * | [DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY]             | Rollout maturity / default-on posture on device.                |
 * | [DelegatedRuntimeStrategyDimension.REGRESSION_PRESSURE]          | Accumulated regression pressure or strategic risk on device.    |
 * | [DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT]       | Cross-module coupling drift within the delegated runtime stack. |
 *
 * ## Evaluation logic
 *
 * [evaluateStrategy] applies the following precedence order:
 *
 * 1. **Missing signal** — if any dimension has status
 *    [DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN] →
 *    [DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal].
 *
 * 2. **Contract instability risk** — if [DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY]
 *    has status [DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK] →
 *    [DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability].
 *
 * 3. **Governance trend risk** — if [DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND]
 *    has status [DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK] →
 *    [DeviceStrategyArtifact.DeviceStrategyRiskDueToGovernanceRegressionTrend].
 *
 * 4. **Rollout maturity gap** — if [DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY]
 *    has status [DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK] →
 *    [DeviceStrategyArtifact.DeviceStrategyRiskDueToRolloutMaturityGap].
 *
 * 5. **Runtime coupling drift** — if [DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT]
 *    has status [DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK] →
 *    [DeviceStrategyArtifact.DeviceStrategyRiskDueToRuntimeCouplingDrift].
 *
 * 6. **Regression pressure** — if [DelegatedRuntimeStrategyDimension.REGRESSION_PRESSURE]
 *    has status [DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK] →
 *    [DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability] with the
 *    [DelegatedRuntimeStrategyDimension.REGRESSION_PRESSURE] dimension (accumulated
 *    regression pressure surfaces as contract instability risk in the strategy model).
 *
 * 7. **On track** — all five dimensions are [DelegatedRuntimeStrategySnapshot.DimensionStatus.ON_TRACK]
 *    → [DeviceStrategyArtifact.DeviceStrategyOnTrack].
 *
 * ## Dimension posture API
 *
 * Each strategy dimension is governed by an internal posture gate that can be in one
 * of three states:
 *
 * | Gate state  | [DelegatedRuntimeStrategySnapshot.DimensionStatus] produced      |
 * |-------------|------------------------------------------------------------------|
 * | On track    | [DelegatedRuntimeStrategySnapshot.DimensionStatus.ON_TRACK]      |
 * | At risk     | [DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK]       |
 * | Unknown     | [DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN]       |
 *
 * Use [markDimensionOnTrack], [markDimensionAtRisk], [markDimensionUnknown], and
 * [getDimensionStatus] to manage dimension posture gates.
 *
 * ## Integration points
 *
 * [DelegatedRuntimeStrategyEvaluator] establishes clear integration boundaries with
 * the following existing runtime modules:
 *
 * | Integration point constant                              | Module                                                                    | Role                                                                                                          |
 * |---------------------------------------------------------|---------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
 * | [INTEGRATION_GOVERNANCE_EVALUATOR]                      | [DelegatedRuntimePostGraduationGovernanceEvaluator]                       | Governance prerequisite: strategy builds on post-graduation governance signals.                               |
 * | [INTEGRATION_ACCEPTANCE_EVALUATOR]                      | [DelegatedRuntimeAcceptanceEvaluator]                                     | Acceptance evidence source: contract stability draws from acceptance layer signals.                           |
 * | [INTEGRATION_TRUTH_OWNER]                               | [AndroidLocalTruthOwnershipCoordinator]                                   | Truth governance trend observation source.                                                                    |
 * | [INTEGRATION_RESULT_CONVERGENCE]                        | [AndroidFlowAwareResultConvergenceParticipant]                            | Result governance trend observation source.                                                                   |
 * | [INTEGRATION_EXECUTION_EVENT_OWNER]                     | [AndroidCanonicalExecutionEventOwner]                                     | Execution governance trend observation source.                                                                |
 * | [INTEGRATION_COMPAT_BLOCKING]                           | [AndroidCompatLegacyBlockingParticipant]                                  | Compat governance trend and rollout maturity observation source.                                              |
 * | [INTEGRATION_RECOVERY_OWNER]                            | [AndroidRecoveryParticipationOwner]                                       | Continuity governance trend observation source.                                                               |
 * | [INTEGRATION_RUNTIME_CONTROLLER]                        | [RuntimeController]                                                       | Strategy snapshot emission; artifacts forwarded via reconciliation signal.                                    |
 * | [INTEGRATION_DELEGATED_FLOW_BRIDGE]                     | [AndroidDelegatedFlowBridge]                                              | Flow-level strategy alignment; strategy snapshot consumed at the program strategy bridge.                     |
 *
 * ## Thread safety
 *
 * All dimension posture operations ([markDimensionOnTrack], [markDimensionAtRisk],
 * [markDimensionUnknown], [getDimensionStatus], [getDimensionRiskReason],
 * [clearAllDimensionStates]) use [synchronized] blocks for safe cross-thread access.
 * [evaluateStrategy] and [buildSnapshot] are pure functions that rely only on the
 * synchronized accessors above.
 *
 * ## Relationship to sibling PR layers
 *
 * [DelegatedRuntimeStrategyEvaluator] is the *program strategy / evolution participation*
 * layer; it builds on top of the PR-11 *post-graduation governance participation* layer
 * ([DelegatedRuntimePostGraduationGovernanceEvaluator]) and aggregates program-level
 * posture signals from the same per-dimension evidence modules (PR-4 through PR-11) into
 * a unified device-side strategy verdict that the V2 PR-12 program strategy / evolution
 * control layer can consume as structured Android strategy posture signals.
 *
 * @see DelegatedRuntimeStrategyDimension
 * @see DeviceStrategyArtifact
 * @see DelegatedRuntimeStrategySnapshot
 * @see DelegatedRuntimePostGraduationGovernanceEvaluator
 * @see DelegatedRuntimeAcceptanceEvaluator
 * @see AndroidLocalTruthOwnershipCoordinator
 * @see AndroidFlowAwareResultConvergenceParticipant
 * @see AndroidCanonicalExecutionEventOwner
 * @see AndroidCompatLegacyBlockingParticipant
 * @see AndroidRecoveryParticipationOwner
 */
class DelegatedRuntimeStrategyEvaluator {

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * Per-dimension strategy posture gate state.
     *
     * Map key is [DelegatedRuntimeStrategyDimension]; value is a pair of
     * ([DelegatedRuntimeStrategySnapshot.DimensionStatus], optional risk reason).
     * All dimensions start as [DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN]
     * until a program signal is received.
     */
    private val _dimensionStates: MutableMap<
        DelegatedRuntimeStrategyDimension,
        Pair<DelegatedRuntimeStrategySnapshot.DimensionStatus, String?>
    > = mutableMapOf()

    // ── Core API — dimension posture gate management ──────────────────────────

    /**
     * Marks [dimension] as on track — no strategic risk detected on this dimension.
     *
     * After this call, [getDimensionStatus] returns
     * [DelegatedRuntimeStrategySnapshot.DimensionStatus.ON_TRACK] for [dimension].
     *
     * @param dimension  The strategy dimension to mark as on track.
     */
    fun markDimensionOnTrack(dimension: DelegatedRuntimeStrategyDimension) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                DelegatedRuntimeStrategySnapshot.DimensionStatus.ON_TRACK to null
        }
    }

    /**
     * Marks [dimension] as having a detected strategic risk.
     *
     * After this call, [getDimensionStatus] returns
     * [DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK] for [dimension].
     *
     * @param dimension   The strategy dimension to mark as at risk.
     * @param riskReason  Human-readable explanation of the strategic risk.
     */
    fun markDimensionAtRisk(
        dimension: DelegatedRuntimeStrategyDimension,
        riskReason: String
    ) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK to riskReason
        }
    }

    /**
     * Marks [dimension] as having no program signal (unknown).
     *
     * After this call, [getDimensionStatus] returns
     * [DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN] for [dimension].
     *
     * @param dimension  The strategy dimension to mark as unknown.
     * @param reason     Optional explanation of why the program signal is missing.
     */
    fun markDimensionUnknown(
        dimension: DelegatedRuntimeStrategyDimension,
        reason: String? = null
    ) {
        synchronized(_dimensionStates) {
            _dimensionStates[dimension] =
                DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN to reason
        }
    }

    /**
     * Returns the current [DelegatedRuntimeStrategySnapshot.DimensionStatus] for [dimension].
     *
     * Returns [DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN] if no program
     * signal has been received for [dimension].
     *
     * @param dimension  The strategy dimension to query.
     */
    fun getDimensionStatus(
        dimension: DelegatedRuntimeStrategyDimension
    ): DelegatedRuntimeStrategySnapshot.DimensionStatus =
        synchronized(_dimensionStates) {
            _dimensionStates[dimension]?.first
                ?: DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN
        }

    /**
     * Returns the risk reason registered for [dimension], or `null` if the dimension
     * is not in [DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK] or
     * [DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN] state.
     *
     * @param dimension  The strategy dimension to query.
     */
    fun getDimensionRiskReason(dimension: DelegatedRuntimeStrategyDimension): String? =
        synchronized(_dimensionStates) { _dimensionStates[dimension]?.second }

    /**
     * Resets all dimension states to [DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN].
     *
     * Use only when the full strategy evaluation era is being reset (e.g. session close /
     * process recreation) and all prior strategy conclusions are no longer applicable.
     */
    fun clearAllDimensionStates() {
        synchronized(_dimensionStates) { _dimensionStates.clear() }
    }

    // ── Core API — strategy evaluation ───────────────────────────────────────

    /**
     * Evaluates the current program-level strategy posture of the Android delegated
     * runtime and returns the appropriate [DeviceStrategyArtifact].
     *
     * ## Evaluation logic
     *
     * 1. **Missing signal** — any dimension with [DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN]
     *    produces [DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal].
     *
     * 2. **Contract instability risk** — [DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY]
     *    with [DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK] produces
     *    [DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability].
     *
     * 3. **Governance trend risk** — [DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND]
     *    with [DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK] produces
     *    [DeviceStrategyArtifact.DeviceStrategyRiskDueToGovernanceRegressionTrend].
     *
     * 4. **Rollout maturity gap** — [DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY]
     *    with [DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK] produces
     *    [DeviceStrategyArtifact.DeviceStrategyRiskDueToRolloutMaturityGap].
     *
     * 5. **Runtime coupling drift** — [DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT]
     *    with [DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK] produces
     *    [DeviceStrategyArtifact.DeviceStrategyRiskDueToRuntimeCouplingDrift].
     *
     * 6. **Regression pressure** — [DelegatedRuntimeStrategyDimension.REGRESSION_PRESSURE]
     *    with [DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK] produces
     *    [DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability] with the
     *    [DelegatedRuntimeStrategyDimension.REGRESSION_PRESSURE] dimension (accumulated
     *    regression pressure surfaces as contract instability risk in the strategy model).
     *
     * 7. **On track** — all five dimensions are [DelegatedRuntimeStrategySnapshot.DimensionStatus.ON_TRACK]
     *    → [DeviceStrategyArtifact.DeviceStrategyOnTrack].
     *
     * @param deviceId    The device identifier to embed in the artifact.
     * @param snapshotId  The snapshot identifier to embed in the artifact.
     * @return The [DeviceStrategyArtifact] for the current dimension states.
     */
    fun evaluateStrategy(deviceId: String, snapshotId: String): DeviceStrategyArtifact {
        val states = synchronized(_dimensionStates) { HashMap(_dimensionStates) }

        // ── 1. Missing signal ──────────────────────────────────────────────────
        val unknownDimensions = DelegatedRuntimeStrategyDimension.entries.filter { dim ->
            (states[dim]?.first ?: DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN) ==
                DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN
        }.toSet()

        if (unknownDimensions.isNotEmpty()) {
            return DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal(
                deviceId = deviceId,
                snapshotId = snapshotId,
                missingDimensions = unknownDimensions
            )
        }

        // ── 2. Contract instability risk ───────────────────────────────────────
        val contractState =
            states[DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY]
        if (contractState?.first == DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK) {
            return DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability(
                deviceId = deviceId,
                snapshotId = snapshotId,
                riskReason = contractState.second ?: REASON_CONTRACT_INSTABILITY_DEFAULT
            )
        }

        // ── 3. Governance trend risk ───────────────────────────────────────────
        val governanceState =
            states[DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND]
        if (governanceState?.first == DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK) {
            return DeviceStrategyArtifact.DeviceStrategyRiskDueToGovernanceRegressionTrend(
                deviceId = deviceId,
                snapshotId = snapshotId,
                riskReason = governanceState.second ?: REASON_GOVERNANCE_TREND_DEFAULT
            )
        }

        // ── 4. Rollout maturity gap ────────────────────────────────────────────
        val rolloutState =
            states[DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY]
        if (rolloutState?.first == DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK) {
            return DeviceStrategyArtifact.DeviceStrategyRiskDueToRolloutMaturityGap(
                deviceId = deviceId,
                snapshotId = snapshotId,
                riskReason = rolloutState.second ?: REASON_ROLLOUT_MATURITY_DEFAULT
            )
        }

        // ── 5. Runtime coupling drift ──────────────────────────────────────────
        val couplingState =
            states[DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT]
        if (couplingState?.first == DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK) {
            return DeviceStrategyArtifact.DeviceStrategyRiskDueToRuntimeCouplingDrift(
                deviceId = deviceId,
                snapshotId = snapshotId,
                riskReason = couplingState.second ?: REASON_RUNTIME_COUPLING_DRIFT_DEFAULT
            )
        }

        // ── 6. Regression pressure ─────────────────────────────────────────────
        val regressionState =
            states[DelegatedRuntimeStrategyDimension.REGRESSION_PRESSURE]
        if (regressionState?.first == DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK) {
            return DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability(
                deviceId = deviceId,
                snapshotId = snapshotId,
                riskReason = regressionState.second ?: REASON_REGRESSION_PRESSURE_DEFAULT,
                dimension = DelegatedRuntimeStrategyDimension.REGRESSION_PRESSURE
            )
        }

        // ── 7. All dimensions on track ─────────────────────────────────────────
        return DeviceStrategyArtifact.DeviceStrategyOnTrack(
            deviceId = deviceId,
            snapshotId = snapshotId
        )
    }

    // ── Core API — snapshot builder ───────────────────────────────────────────

    /**
     * Builds a full [DelegatedRuntimeStrategySnapshot] for the current dimension states.
     *
     * The snapshot embeds:
     *  - A freshly generated [snapshotId] (UUID).
     *  - The [DeviceStrategyArtifact] from [evaluateStrategy].
     *  - Per-dimension [DelegatedRuntimeStrategySnapshot.DimensionPostureState] entries.
     *  - The current wall-clock timestamp in [DelegatedRuntimeStrategySnapshot.reportedAtMs].
     *
     * @param deviceId  The device identifier to embed in the snapshot.
     * @return The [DelegatedRuntimeStrategySnapshot] for the current state.
     */
    fun buildSnapshot(deviceId: String): DelegatedRuntimeStrategySnapshot {
        val snapshotId = UUID.randomUUID().toString()
        val artifact = evaluateStrategy(deviceId = deviceId, snapshotId = snapshotId)

        val dimensionStates = DelegatedRuntimeStrategyDimension.entries.associate { dim ->
            val status = getDimensionStatus(dim)
            val riskReason = getDimensionRiskReason(dim)
            dim to DelegatedRuntimeStrategySnapshot.DimensionPostureState(
                dimension = dim,
                status = status,
                riskReason = riskReason
            )
        }

        return DelegatedRuntimeStrategySnapshot(
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
         * Canonical wire value for the [DeviceStrategyArtifact.DeviceStrategyOnTrack] artifact.
         *
         * All five strategy dimensions are on track; the device evolution posture is
         * aligned with the V2 canonical strategy.
         */
        const val ARTIFACT_DEVICE_STRATEGY_ON_TRACK = "device_strategy_on_track"

        /**
         * Canonical wire value for the
         * [DeviceStrategyArtifact.DeviceStrategyRiskDueToContractInstability] artifact.
         *
         * Canonical contract stability or accumulated regression pressure risk has been
         * detected on this device (also used when regression pressure surfaces as contract
         * instability in the strategy model).
         */
        const val ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_CONTRACT_INSTABILITY =
            "device_strategy_risk_due_to_contract_instability"

        /**
         * Canonical wire value for the
         * [DeviceStrategyArtifact.DeviceStrategyRiskDueToGovernanceRegressionTrend] artifact.
         *
         * A negative governance trend across truth / result / execution / compat /
         * continuity dimensions has been detected as a strategy-level risk.
         */
        const val ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_GOVERNANCE_REGRESSION_TREND =
            "device_strategy_risk_due_to_governance_regression_trend"

        /**
         * Canonical wire value for the
         * [DeviceStrategyArtifact.DeviceStrategyRiskDueToRolloutMaturityGap] artifact.
         *
         * The rollout maturity / default-on posture on this device has not reached the
         * expected stage for the current V2 program phase.
         */
        const val ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_ROLLOUT_MATURITY_GAP =
            "device_strategy_risk_due_to_rollout_maturity_gap"

        /**
         * Canonical wire value for the
         * [DeviceStrategyArtifact.DeviceStrategyRiskDueToRuntimeCouplingDrift] artifact.
         *
         * Cross-module coupling drift within the Android delegated runtime stack has been
         * detected as a strategy-level risk.
         */
        const val ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_RUNTIME_COUPLING_DRIFT =
            "device_strategy_risk_due_to_runtime_coupling_drift"

        /**
         * Canonical wire value for the
         * [DeviceStrategyArtifact.DeviceStrategyUnknownDueToMissingProgramSignal] artifact.
         *
         * One or more strategy dimensions have no program signal; the strategy posture
         * verdict cannot be determined.
         */
        const val ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL =
            "device_strategy_unknown_due_to_missing_program_signal"

        // ── Integration point constants ───────────────────────────────────────

        /**
         * Integration point identifier for [DelegatedRuntimePostGraduationGovernanceEvaluator].
         *
         * Governance evaluator: provides post-graduation governance signals that feed into
         * the governance trend and contract stability strategy dimensions.
         */
        const val INTEGRATION_GOVERNANCE_EVALUATOR =
            "DelegatedRuntimePostGraduationGovernanceEvaluator"

        /**
         * Integration point identifier for [DelegatedRuntimeAcceptanceEvaluator].
         *
         * Acceptance evaluator: provides acceptance evidence signals that feed into the
         * canonical contract stability and regression pressure strategy dimensions.
         */
        const val INTEGRATION_ACCEPTANCE_EVALUATOR = "DelegatedRuntimeAcceptanceEvaluator"

        /**
         * Integration point identifier for [AndroidLocalTruthOwnershipCoordinator].
         *
         * Truth owner: supplies the truth governance trend observation signal for
         * [DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND].
         */
        const val INTEGRATION_TRUTH_OWNER = "AndroidLocalTruthOwnershipCoordinator"

        /**
         * Integration point identifier for [AndroidFlowAwareResultConvergenceParticipant].
         *
         * Result convergence participant: supplies the result governance trend observation
         * signal for [DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND].
         */
        const val INTEGRATION_RESULT_CONVERGENCE = "AndroidFlowAwareResultConvergenceParticipant"

        /**
         * Integration point identifier for [AndroidCanonicalExecutionEventOwner].
         *
         * Execution event owner: supplies the canonical execution governance trend
         * observation signal for [DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND].
         */
        const val INTEGRATION_EXECUTION_EVENT_OWNER = "AndroidCanonicalExecutionEventOwner"

        /**
         * Integration point identifier for [AndroidCompatLegacyBlockingParticipant].
         *
         * Compat blocking participant: supplies the compat governance trend and rollout
         * maturity observation signals for [DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND]
         * and [DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY].
         */
        const val INTEGRATION_COMPAT_BLOCKING = "AndroidCompatLegacyBlockingParticipant"

        /**
         * Integration point identifier for [AndroidRecoveryParticipationOwner].
         *
         * Recovery owner: supplies the continuity governance trend observation signal
         * for [DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND].
         */
        const val INTEGRATION_RECOVERY_OWNER = "AndroidRecoveryParticipationOwner"

        /**
         * Integration point identifier for [RuntimeController].
         *
         * Runtime controller: consumes [DelegatedRuntimeStrategySnapshot] and forwards
         * the device strategy artifact to V2 via the reconciliation signal channel.
         */
        const val INTEGRATION_RUNTIME_CONTROLLER = "RuntimeController"

        /**
         * Integration point identifier for [AndroidDelegatedFlowBridge].
         *
         * Delegated flow bridge: aligns flow-level strategy before program-level evolution
         * control decisions; strategy snapshot is consumed at the program strategy bridge.
         */
        const val INTEGRATION_DELEGATED_FLOW_BRIDGE = "AndroidDelegatedFlowBridge"

        // ── Default risk reason constants ─────────────────────────────────────

        /**
         * Default risk reason used when the canonical contract stability dimension has a
         * risk but no explicit reason was registered.
         */
        const val REASON_CONTRACT_INSTABILITY_DEFAULT =
            "canonical_contract_stability_risk_detected"

        /**
         * Default risk reason used when the governance trend dimension has a risk but
         * no explicit reason was registered.
         */
        const val REASON_GOVERNANCE_TREND_DEFAULT =
            "governance_regression_trend_detected"

        /**
         * Default risk reason used when the rollout maturity dimension has a risk but
         * no explicit reason was registered.
         */
        const val REASON_ROLLOUT_MATURITY_DEFAULT =
            "rollout_maturity_gap_detected"

        /**
         * Default risk reason used when the runtime coupling drift dimension has a risk
         * but no explicit reason was registered.
         */
        const val REASON_RUNTIME_COUPLING_DRIFT_DEFAULT =
            "runtime_coupling_drift_detected"

        /**
         * Default risk reason used when the regression pressure dimension has a risk but
         * no explicit reason was registered.
         */
        const val REASON_REGRESSION_PRESSURE_DEFAULT =
            "regression_pressure_risk_detected"

        // ── PR designation ────────────────────────────────────────────────────

        /**
         * The PR designation for this component.
         */
        const val INTRODUCED_PR = 12

        /**
         * Human-readable description of this component's role in the delegated runtime
         * strategy / evolution participation model.
         */
        const val DESCRIPTION =
            "Android delegated runtime strategy / evolution participation owner / evaluator / " +
                "reporting layer: monitors five strategy dimensions (canonical contract " +
                "stability, governance trend, rollout maturity, regression pressure, runtime " +
                "coupling drift) and produces structured DeviceStrategyArtifact and " +
                "DelegatedRuntimeStrategySnapshot outputs for V2 program strategy / evolution " +
                "control layer participation."
    }
}

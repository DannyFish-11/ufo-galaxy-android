package com.ufo.galaxy.runtime

/**
 * PR-12 (Android) — Sealed class representing the Android device-side strategy / evolution
 * posture artifact for V2 delegated runtime program strategy / evolution control layer
 * participation.
 *
 * [DeviceStrategyArtifact] is the typed output of
 * [DelegatedRuntimeStrategyEvaluator.evaluateStrategy].  Every V2 program strategy /
 * evolution control layer that needs to absorb Android device-side strategy posture must
 * inspect the returned artifact before forming a program-level evolution conclusion.
 *
 * ## Artifact semantics
 *
 * Each concrete subtype corresponds to a named device-side strategy posture outcome:
 *
 * | Subtype                                             | Semantic tag                                                   | Description                                                                                     |
 * |-----------------------------------------------------|----------------------------------------------------------------|-------------------------------------------------------------------------------------------------|
 * | [DeviceStrategyOnTrack]                             | `device_strategy_on_track`                                     | All five strategy dimensions are on track; device evolution posture is aligned.                 |
 * | [DeviceStrategyRiskDueToContractInstability]        | `device_strategy_risk_due_to_contract_instability`             | Canonical contract stability or accumulated regression pressure risk detected on device.        |
 * | [DeviceStrategyRiskDueToGovernanceRegressionTrend]  | `device_strategy_risk_due_to_governance_regression_trend`      | Governance trend risk: truth / result / execution / compat / continuity trend is negative.     |
 * | [DeviceStrategyRiskDueToRolloutMaturityGap]         | `device_strategy_risk_due_to_rollout_maturity_gap`             | Rollout maturity / default-on posture has not reached the expected stage.                       |
 * | [DeviceStrategyRiskDueToRuntimeCouplingDrift]       | `device_strategy_risk_due_to_runtime_coupling_drift`           | Cross-module coupling drift within the delegated runtime stack detected.                        |
 * | [DeviceStrategyUnknownDueToMissingProgramSignal]    | `device_strategy_unknown_due_to_missing_program_signal`        | One or more strategy dimensions have no program signal; posture cannot be determined.           |
 *
 * ## Strategy contract
 *
 * Only [DeviceStrategyOnTrack] signals that the device-side evolution posture is aligned
 * with the V2 canonical strategy.  Risk artifacts indicate which strategic dimension
 * requires attention; they feed directly into V2 program strategy / evolution control
 * inputs.  [DeviceStrategyUnknownDueToMissingProgramSignal] is a blocking condition that
 * prevents the V2 strategy layer from forming a program-level evolution conclusion.
 *
 * @see DelegatedRuntimeStrategyEvaluator
 * @see DelegatedRuntimeStrategyDimension
 * @see DelegatedRuntimeStrategySnapshot
 */
sealed class DeviceStrategyArtifact {

    /**
     * Stable wire tag identifying the strategy semantic for this artifact.
     *
     * Matches one of the [DelegatedRuntimeStrategyEvaluator] `ARTIFACT_*` constants.
     */
    abstract val semanticTag: String

    // ── DeviceStrategyOnTrack ─────────────────────────────────────────────────

    /**
     * All five strategy dimensions are on track; the device evolution posture is aligned
     * with the V2 canonical strategy.
     *
     * Android-side semantic:
     * [DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_ON_TRACK].
     *
     * @property deviceId    The device identifier for which strategy was evaluated.
     * @property snapshotId  Stable identifier for the strategy snapshot that produced this artifact.
     */
    data class DeviceStrategyOnTrack(
        val deviceId: String,
        val snapshotId: String
    ) : DeviceStrategyArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_ON_TRACK
    }

    // ── DeviceStrategyRiskDueToContractInstability ────────────────────────────

    /**
     * Canonical contract stability or accumulated regression pressure risk has been
     * detected on this device.
     *
     * The device's canonical delegated runtime contract is not reliably stable, or
     * accumulated regression pressure has risen to a strategy-level risk threshold.
     * This risk must be reported to the V2 program strategy layer for evolution control
     * consideration.
     *
     * Android-side semantic:
     * [DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_CONTRACT_INSTABILITY].
     *
     * @property deviceId    The device identifier for which strategy was evaluated.
     * @property snapshotId  Stable identifier for the strategy snapshot.
     * @property riskReason  Human-readable explanation of the contract instability risk.
     * @property dimension   The [DelegatedRuntimeStrategyDimension] that triggered this artifact;
     *                       either [DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY]
     *                       or [DelegatedRuntimeStrategyDimension.REGRESSION_PRESSURE].
     */
    data class DeviceStrategyRiskDueToContractInstability(
        val deviceId: String,
        val snapshotId: String,
        val riskReason: String,
        val dimension: DelegatedRuntimeStrategyDimension =
            DelegatedRuntimeStrategyDimension.CANONICAL_CONTRACT_STABILITY
    ) : DeviceStrategyArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_CONTRACT_INSTABILITY
    }

    // ── DeviceStrategyRiskDueToGovernanceRegressionTrend ─────────────────────

    /**
     * A negative governance trend — across truth / result / execution / compat /
     * continuity dimensions — has been detected as a strategy-level risk on this device.
     *
     * The overall governance trend is moving in a direction that threatens long-term
     * canonical alignment, even if no single dimension has yet triggered a formal
     * post-graduation governance violation.  This risk must be reported to the V2
     * program strategy layer.
     *
     * Android-side semantic:
     * [DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_GOVERNANCE_REGRESSION_TREND].
     *
     * @property deviceId    The device identifier for which strategy was evaluated.
     * @property snapshotId  Stable identifier for the strategy snapshot.
     * @property riskReason  Human-readable explanation of the governance trend risk.
     * @property dimension   Always [DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND].
     */
    data class DeviceStrategyRiskDueToGovernanceRegressionTrend(
        val deviceId: String,
        val snapshotId: String,
        val riskReason: String,
        val dimension: DelegatedRuntimeStrategyDimension =
            DelegatedRuntimeStrategyDimension.GOVERNANCE_TREND
    ) : DeviceStrategyArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_GOVERNANCE_REGRESSION_TREND
    }

    // ── DeviceStrategyRiskDueToRolloutMaturityGap ─────────────────────────────

    /**
     * The rollout maturity / default-on posture on this device has not reached the
     * expected stage for the current V2 program phase.
     *
     * The device's rollout progression — including default-on status, enforcement
     * tightening, and long-tail compat retirement — lags behind program expectations.
     * This gap must be reported to the V2 program strategy layer for evolution control.
     *
     * Android-side semantic:
     * [DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_ROLLOUT_MATURITY_GAP].
     *
     * @property deviceId    The device identifier for which strategy was evaluated.
     * @property snapshotId  Stable identifier for the strategy snapshot.
     * @property riskReason  Human-readable explanation of the rollout maturity gap.
     * @property dimension   Always [DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY].
     */
    data class DeviceStrategyRiskDueToRolloutMaturityGap(
        val deviceId: String,
        val snapshotId: String,
        val riskReason: String,
        val dimension: DelegatedRuntimeStrategyDimension =
            DelegatedRuntimeStrategyDimension.ROLLOUT_MATURITY
    ) : DeviceStrategyArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_ROLLOUT_MATURITY_GAP
    }

    // ── DeviceStrategyRiskDueToRuntimeCouplingDrift ───────────────────────────

    /**
     * Cross-module coupling drift within the Android delegated runtime stack has been
     * detected as a strategy-level risk on this device.
     *
     * The coupling between delegated runtime modules has diverged beyond acceptable
     * cohesion bounds in ways that threaten the long-term evolution trajectory.  This
     * risk must be reported to the V2 program strategy layer.
     *
     * Android-side semantic:
     * [DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_RUNTIME_COUPLING_DRIFT].
     *
     * @property deviceId    The device identifier for which strategy was evaluated.
     * @property snapshotId  Stable identifier for the strategy snapshot.
     * @property riskReason  Human-readable explanation of the coupling drift risk.
     * @property dimension   Always [DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT].
     */
    data class DeviceStrategyRiskDueToRuntimeCouplingDrift(
        val deviceId: String,
        val snapshotId: String,
        val riskReason: String,
        val dimension: DelegatedRuntimeStrategyDimension =
            DelegatedRuntimeStrategyDimension.RUNTIME_COUPLING_DRIFT
    ) : DeviceStrategyArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_RISK_DUE_TO_RUNTIME_COUPLING_DRIFT
    }

    // ── DeviceStrategyUnknownDueToMissingProgramSignal ────────────────────────

    /**
     * One or more strategy dimensions have no program signal; the strategy posture
     * verdict cannot be determined.
     *
     * The V2 program strategy / evolution control layer must treat this as a blocking
     * condition until the missing signals are received.
     *
     * Android-side semantic:
     * [DelegatedRuntimeStrategyEvaluator.ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL].
     *
     * @property deviceId           The device identifier for which strategy was evaluated.
     * @property snapshotId         Stable identifier for the strategy snapshot.
     * @property missingDimensions  The set of [DelegatedRuntimeStrategyDimension] values
     *                              for which no program signal was available.
     */
    data class DeviceStrategyUnknownDueToMissingProgramSignal(
        val deviceId: String,
        val snapshotId: String,
        val missingDimensions: Set<DelegatedRuntimeStrategyDimension>
    ) : DeviceStrategyArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeStrategyEvaluator
                .ARTIFACT_DEVICE_STRATEGY_UNKNOWN_DUE_TO_MISSING_PROGRAM_SIGNAL
    }
}

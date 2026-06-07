package com.ufo.galaxy.runtime

/**
 * PR-11 (Android) — Sealed class representing the Android device-side post-graduation
 * governance verdict artifact for V2 delegated runtime post-graduation governance /
 * enforcement layer participation.
 *
 * [DeviceGovernanceArtifact] is the typed output of
 * [DelegatedRuntimePostGraduationGovernanceEvaluator.evaluateGovernance].  Every V2
 * post-graduation governance / enforcement layer that needs to absorb Android device-side
 * governance signals must inspect the returned artifact before forming a governance
 * conclusion.
 *
 * ## Artifact semantics
 *
 * Each concrete subtype corresponds to a named device-side governance verdict:
 *
 * | Subtype                                                        | Semantic tag                                                              | Description                                                                                 |
 * |----------------------------------------------------------------|---------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
 * | [DeviceGovernanceCompliant]                                    | `device_governance_compliant`                                             | All five governance dimensions are observed without regression; device remains canonical.   |
 * | [DeviceGovernanceViolationDueToTruthRegression]                | `device_governance_violation_due_to_truth_regression`                     | Truth alignment regression has been detected since graduation.                              |
 * | [DeviceGovernanceViolationDueToResultRegression]               | `device_governance_violation_due_to_result_regression`                    | Result convergence regression has been detected since graduation.                           |
 * | [DeviceGovernanceViolationDueToExecutionVisibilityRegression]  | `device_governance_violation_due_to_execution_visibility_regression`      | Canonical execution visibility regression has been detected since graduation.               |
 * | [DeviceGovernanceViolationDueToCompatBypass]                   | `device_governance_violation_due_to_compat_bypass`                        | Compat / legacy bypass has been reintroduced since graduation.                              |
 * | [DeviceGovernanceUnknownDueToMissingSignal]                    | `device_governance_unknown_due_to_missing_signal`                         | One or more governance dimensions have no signal; verdict cannot be determined.             |
 *
 * ## Governance contract
 *
 * Only [DeviceGovernanceCompliant] signals ongoing canonical compliance.  Violation
 * artifacts indicate that the identified regression dimension must be investigated and
 * resolved; they feed directly into V2 post-graduation enforcement / escalation inputs.
 * [DeviceGovernanceUnknownDueToMissingSignal] is a blocking condition that prevents the
 * V2 governance layer from forming a compliance conclusion.
 *
 * @see DelegatedRuntimePostGraduationGovernanceEvaluator
 * @see DelegatedRuntimeGovernanceDimension
 * @see DelegatedRuntimeGovernanceSnapshot
 */
sealed class DeviceGovernanceArtifact {

    /**
     * Stable wire tag identifying the governance semantic for this artifact.
     *
     * Matches one of the [DelegatedRuntimePostGraduationGovernanceEvaluator] `ARTIFACT_*`
     * constants.
     */
    abstract val semanticTag: String

    // ── DeviceGovernanceCompliant ─────────────────────────────────────────────

    /**
     * All five post-graduation governance dimensions are observed without regression;
     * the device remains compliant with the canonical runtime path.
     *
     * Android-side semantic:
     * [DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT].
     *
     * @property deviceId    The device identifier for which governance was evaluated.
     * @property snapshotId  Stable identifier for the governance snapshot that produced this artifact.
     */
    data class DeviceGovernanceCompliant(
        val deviceId: String,
        val snapshotId: String
    ) : DeviceGovernanceArtifact() {
        override val semanticTag: String =
            DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT
    }

    // ── DeviceGovernanceViolationDueToTruthRegression ─────────────────────────

    /**
     * Truth alignment regression has been detected since graduation.
     *
     * The device's delegated runtime truth ownership / alignment is no longer reliably
     * consistent with the V2 canonical truth state.  This violation must be reported to
     * the V2 post-graduation governance layer for enforcement consideration.
     *
     * Android-side semantic:
     * [DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_TRUTH_REGRESSION].
     *
     * @property deviceId          The device identifier for which governance was evaluated.
     * @property snapshotId        Stable identifier for the governance snapshot.
     * @property regressionReason  Human-readable explanation of the truth regression.
     * @property dimension         Always [DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION].
     */
    data class DeviceGovernanceViolationDueToTruthRegression(
        val deviceId: String,
        val snapshotId: String,
        val regressionReason: String,
        val dimension: DelegatedRuntimeGovernanceDimension =
            DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION
    ) : DeviceGovernanceArtifact() {
        override val semanticTag: String =
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_TRUTH_REGRESSION
    }

    // ── DeviceGovernanceViolationDueToResultRegression ────────────────────────

    /**
     * Result convergence regression has been detected since graduation.
     *
     * The device's result convergence participation layer is no longer reliably canonical.
     * This violation must be reported to the V2 post-graduation governance layer for
     * enforcement consideration.
     *
     * Android-side semantic:
     * [DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_RESULT_REGRESSION].
     *
     * @property deviceId          The device identifier for which governance was evaluated.
     * @property snapshotId        Stable identifier for the governance snapshot.
     * @property regressionReason  Human-readable explanation of the result regression.
     * @property dimension         Always [DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION].
     */
    data class DeviceGovernanceViolationDueToResultRegression(
        val deviceId: String,
        val snapshotId: String,
        val regressionReason: String,
        val dimension: DelegatedRuntimeGovernanceDimension =
            DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION
    ) : DeviceGovernanceArtifact() {
        override val semanticTag: String =
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_RESULT_REGRESSION
    }

    // ── DeviceGovernanceViolationDueToExecutionVisibilityRegression ───────────

    /**
     * Canonical execution visibility regression has been detected since graduation.
     *
     * The device's canonical execution event / operator visibility layer has regressed.
     * This violation must be reported to the V2 post-graduation governance layer for
     * enforcement consideration.
     *
     * Android-side semantic:
     * [DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_EXECUTION_VISIBILITY_REGRESSION].
     *
     * @property deviceId          The device identifier for which governance was evaluated.
     * @property snapshotId        Stable identifier for the governance snapshot.
     * @property regressionReason  Human-readable explanation of the execution visibility regression.
     * @property dimension         Always [DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION].
     */
    data class DeviceGovernanceViolationDueToExecutionVisibilityRegression(
        val deviceId: String,
        val snapshotId: String,
        val regressionReason: String,
        val dimension: DelegatedRuntimeGovernanceDimension =
            DelegatedRuntimeGovernanceDimension.CANONICAL_EXECUTION_VISIBILITY_REGRESSION
    ) : DeviceGovernanceArtifact() {
        override val semanticTag: String =
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_EXECUTION_VISIBILITY_REGRESSION
    }

    // ── DeviceGovernanceViolationDueToCompatBypass ────────────────────────────

    /**
     * Compat / legacy bypass reintroduction has been detected since graduation.
     *
     * The device's compat blocking is no longer reliably preventing legacy emit semantics
     * from reaching V2 canonical surfaces.  This violation must be reported to the V2
     * post-graduation governance layer for enforcement consideration.
     *
     * Android-side semantic:
     * [DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_COMPAT_BYPASS].
     *
     * @property deviceId          The device identifier for which governance was evaluated.
     * @property snapshotId        Stable identifier for the governance snapshot.
     * @property regressionReason  Human-readable explanation of the compat bypass.
     * @property dimension         Always [DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION].
     */
    data class DeviceGovernanceViolationDueToCompatBypass(
        val deviceId: String,
        val snapshotId: String,
        val regressionReason: String,
        val dimension: DelegatedRuntimeGovernanceDimension =
            DelegatedRuntimeGovernanceDimension.COMPAT_BYPASS_REINTRODUCTION
    ) : DeviceGovernanceArtifact() {
        override val semanticTag: String =
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_VIOLATION_DUE_TO_COMPAT_BYPASS
    }

    // ── DeviceGovernanceUnknownDueToMissingSignal ─────────────────────────────

    /**
     * One or more governance dimensions have no signal; the governance verdict cannot
     * be determined.
     *
     * The V2 post-graduation governance layer must treat this as a blocking condition
     * equivalent to a violation until the missing signals are received.
     *
     * Android-side semantic:
     * [DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL].
     *
     * @property deviceId           The device identifier for which governance was evaluated.
     * @property snapshotId         Stable identifier for the governance snapshot.
     * @property missingDimensions  The set of [DelegatedRuntimeGovernanceDimension] values
     *                              for which no signal was available.
     */
    data class DeviceGovernanceUnknownDueToMissingSignal(
        val deviceId: String,
        val snapshotId: String,
        val missingDimensions: Set<DelegatedRuntimeGovernanceDimension>
    ) : DeviceGovernanceArtifact() {
        override val semanticTag: String =
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL
    }
}

package com.ufo.galaxy.runtime

/**
 * PR-10 (Android) — Sealed class representing the Android device-side acceptance artifact
 * for V2 delegated runtime final acceptance / graduation gating.
 *
 * [DeviceAcceptanceArtifact] is the typed output of
 * [DelegatedRuntimeAcceptanceEvaluator.evaluateAcceptance].  Every V2 final acceptance /
 * graduation gate that needs to absorb Android device-side acceptance must inspect the
 * returned artifact before making a graduation participation decision.
 *
 * ## Artifact semantics
 *
 * Each concrete subtype corresponds to a named device-side acceptance outcome:
 *
 * | Subtype                                            | Semantic tag                                             | Description                                                                                    |
 * |----------------------------------------------------|----------------------------------------------------------|------------------------------------------------------------------------------------------------|
 * | [DeviceAcceptedForGraduation]                      | `device_accepted_for_graduation`                         | All six acceptance dimensions are evidenced; device may graduate.                              |
 * | [DeviceRejectedDueToMissingEvidence]               | `device_rejected_due_to_missing_evidence`                | Readiness prerequisite has not been established or a dimension lacks explicit evidence.        |
 * | [DeviceRejectedDueToTruthGap]                      | `device_rejected_due_to_truth_gap`                       | Truth ownership / alignment evidence dimension has an unresolved gap.                          |
 * | [DeviceRejectedDueToResultGap]                     | `device_rejected_due_to_result_gap`                      | Result convergence evidence dimension has an unresolved gap.                                   |
 * | [DeviceRejectedDueToExecutionEventGap]             | `device_rejected_due_to_execution_event_gap`             | Canonical execution event evidence dimension has an unresolved gap.                            |
 * | [DeviceRejectedDueToCompatBypassRisk]              | `device_rejected_due_to_compat_bypass_risk`              | Compat / legacy blocking evidence dimension has an unresolved bypass risk.                     |
 * | [DeviceAcceptanceUnknownDueToIncompleteSignal]     | `device_acceptance_unknown_due_to_incomplete_signal`     | One or more dimensions lack a signal; acceptance cannot be determined.                         |
 *
 * ## Evaluation contract
 *
 * Only [DeviceAcceptedForGraduation] signals that the device may participate in the V2
 * final acceptance / graduation gate.  All rejection and unknown artifacts indicate that
 * the graduation gate must not proceed until the identified dimension gap is resolved.
 *
 * @see DelegatedRuntimeAcceptanceEvaluator
 * @see DelegatedRuntimeAcceptanceDimension
 * @see DelegatedRuntimeAcceptanceSnapshot
 */
sealed class DeviceAcceptanceArtifact {

    /**
     * Stable wire tag identifying the acceptance semantic for this artifact.
     *
     * Matches one of the [DelegatedRuntimeAcceptanceEvaluator] `ARTIFACT_*` constants.
     */
    abstract val semanticTag: String

    // ── DeviceAcceptedForGraduation ───────────────────────────────────────────

    /**
     * All six acceptance evidence dimensions are satisfied; the device may participate
     * in the V2 final acceptance / graduation gate.
     *
     * Android-side semantic:
     * [DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION].
     *
     * @property deviceId   The device identifier for which acceptance was evaluated.
     * @property snapshotId Stable identifier for the acceptance snapshot that produced this artifact.
     */
    data class DeviceAcceptedForGraduation(
        val deviceId: String,
        val snapshotId: String
    ) : DeviceAcceptanceArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTED_FOR_GRADUATION
    }

    // ── DeviceRejectedDueToMissingEvidence ────────────────────────────────────

    /**
     * The readiness prerequisite has not been established, or a required evidence
     * dimension is explicitly missing.
     *
     * The device must not participate in the V2 graduation gate until the missing
     * evidence is established.  Typical causes: readiness has not reached
     * [DeviceReadinessArtifact.DeviceReadyForRelease], or the
     * [DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE] dimension is in
     * [DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP] state.
     *
     * Android-side semantic:
     * [DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_MISSING_EVIDENCE].
     *
     * @property deviceId     The device identifier for which acceptance was evaluated.
     * @property snapshotId   Stable identifier for the acceptance snapshot.
     * @property gapReason    Human-readable explanation of the missing evidence.
     * @property dimension    The dimension whose gap produced this artifact; typically
     *                        [DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE].
     */
    data class DeviceRejectedDueToMissingEvidence(
        val deviceId: String,
        val snapshotId: String,
        val gapReason: String,
        val dimension: DelegatedRuntimeAcceptanceDimension =
            DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE
    ) : DeviceAcceptanceArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_MISSING_EVIDENCE
    }

    // ── DeviceRejectedDueToTruthGap ───────────────────────────────────────────

    /**
     * The truth ownership / alignment evidence dimension has an unresolved gap.
     *
     * The device must not participate in the V2 graduation gate until the truth evidence
     * gap is resolved.  Typical causes: truth assertions have not been confirmed by V2
     * central alignment, posture conflicts are suppressing authoritative assertions, or
     * continuity gaps are preventing stable truth ownership evidence.
     *
     * Android-side semantic:
     * [DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_TRUTH_GAP].
     *
     * @property deviceId     The device identifier for which acceptance was evaluated.
     * @property snapshotId   Stable identifier for the acceptance snapshot.
     * @property gapReason    Human-readable explanation of the truth evidence gap.
     * @property dimension    The dimension whose gap produced this artifact: either
     *                        [DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE]
     *                        (truth evidence gap) or
     *                        [DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE]
     *                        (continuity gap, which directly produces a truth-gap artifact
     *                        in the canonical acceptance model).
     */
    data class DeviceRejectedDueToTruthGap(
        val deviceId: String,
        val snapshotId: String,
        val gapReason: String,
        val dimension: DelegatedRuntimeAcceptanceDimension =
            DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE
    ) : DeviceAcceptanceArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_TRUTH_GAP
    }

    // ── DeviceRejectedDueToResultGap ──────────────────────────────────────────

    /**
     * The result convergence evidence dimension has an unresolved gap.
     *
     * The device must not participate in the V2 graduation gate until the result evidence
     * gap is resolved.  Typical causes: convergence evidence is absent across observed
     * flows, late partials after finals have not been gated, or parallel sub-result
     * binding evidence is missing.
     *
     * Android-side semantic:
     * [DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_RESULT_GAP].
     *
     * @property deviceId     The device identifier for which acceptance was evaluated.
     * @property snapshotId   Stable identifier for the acceptance snapshot.
     * @property gapReason    Human-readable explanation of the result evidence gap.
     * @property dimension    Always [DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE].
     */
    data class DeviceRejectedDueToResultGap(
        val deviceId: String,
        val snapshotId: String,
        val gapReason: String,
        val dimension: DelegatedRuntimeAcceptanceDimension =
            DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE
    ) : DeviceAcceptanceArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_RESULT_GAP
    }

    // ── DeviceRejectedDueToExecutionEventGap ──────────────────────────────────

    /**
     * The canonical execution event evidence dimension has an unresolved gap.
     *
     * The device must not participate in the V2 graduation gate until the execution event
     * evidence gap is resolved.  Typical causes: execution event evidence across flows is
     * absent or post-terminal noise evidence has not been suppressed, or reconnect
     * alignment event evidence is missing.
     *
     * Android-side semantic:
     * [DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_EXECUTION_EVENT_GAP].
     *
     * @property deviceId     The device identifier for which acceptance was evaluated.
     * @property snapshotId   Stable identifier for the acceptance snapshot.
     * @property gapReason    Human-readable explanation of the execution event evidence gap.
     * @property dimension    Always [DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE].
     */
    data class DeviceRejectedDueToExecutionEventGap(
        val deviceId: String,
        val snapshotId: String,
        val gapReason: String,
        val dimension: DelegatedRuntimeAcceptanceDimension =
            DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE
    ) : DeviceAcceptanceArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_EXECUTION_EVENT_GAP
    }

    // ── DeviceRejectedDueToCompatBypassRisk ───────────────────────────────────

    /**
     * The compat / legacy blocking evidence dimension has an unresolved compat bypass risk.
     *
     * The device must not participate in the V2 graduation gate until the compat bypass
     * risk is resolved.  Typical causes: quarantined units have not been confirmed
     * released after V2 alignment, blocked legacy paths are still active and not
     * retired, or compat bypass evidence shows legacy emit semantics are still
     * influencing canonical surfaces.
     *
     * Android-side semantic:
     * [DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_COMPAT_BYPASS_RISK].
     *
     * @property deviceId     The device identifier for which acceptance was evaluated.
     * @property snapshotId   Stable identifier for the acceptance snapshot.
     * @property gapReason    Human-readable explanation of the compat bypass risk.
     * @property dimension    Always [DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE].
     */
    data class DeviceRejectedDueToCompatBypassRisk(
        val deviceId: String,
        val snapshotId: String,
        val gapReason: String,
        val dimension: DelegatedRuntimeAcceptanceDimension =
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE
    ) : DeviceAcceptanceArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_REJECTED_DUE_TO_COMPAT_BYPASS_RISK
    }

    // ── DeviceAcceptanceUnknownDueToIncompleteSignal ──────────────────────────

    /**
     * One or more evidence dimensions lack the signal required to determine acceptance.
     *
     * The device's acceptance for the V2 graduation gate cannot be confirmed or denied
     * until the missing evidence signals are received.  V2 graduation gate must treat
     * this as a blocking condition equivalent to rejection.
     *
     * Android-side semantic:
     * [DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL].
     *
     * @property deviceId            The device identifier for which acceptance was evaluated.
     * @property snapshotId          Stable identifier for the acceptance snapshot.
     * @property missingDimensions   The set of [DelegatedRuntimeAcceptanceDimension] values
     *                               for which no signal was available.
     */
    data class DeviceAcceptanceUnknownDueToIncompleteSignal(
        val deviceId: String,
        val snapshotId: String,
        val missingDimensions: Set<DelegatedRuntimeAcceptanceDimension>
    ) : DeviceAcceptanceArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeAcceptanceEvaluator.ARTIFACT_DEVICE_ACCEPTANCE_UNKNOWN_DUE_TO_INCOMPLETE_SIGNAL
    }
}

package com.ufo.galaxy.runtime

/**
 * PR-9 (Android) — Sealed class representing the Android device-side readiness artifact
 * for V2 delegated runtime release gating.
 *
 * [DeviceReadinessArtifact] is the typed output of
 * [DelegatedRuntimeReadinessEvaluator.evaluateReadiness].  Every V2 release gate that
 * needs to absorb Android device-side readiness must inspect the returned artifact before
 * making a release participation decision.
 *
 * ## Artifact semantics
 *
 * Each concrete subtype corresponds to a named device-side readiness outcome:
 *
 * | Subtype                                        | Semantic tag                                            | Description                                                                        |
 * |------------------------------------------------|---------------------------------------------------------|------------------------------------------------------------------------------------|
 * | [DeviceReadyForRelease]                        | `device_ready_for_release`                              | All five readiness dimensions are satisfied; device may participate in release.    |
 * | [DeviceNotReadyDueToTruthGap]                  | `device_not_ready_due_to_truth_gap`                     | Local truth ownership / central alignment dimension has an unresolved gap.         |
 * | [DeviceNotReadyDueToResultGap]                 | `device_not_ready_due_to_result_gap`                    | Result convergence participation dimension has an unresolved gap.                  |
 * | [DeviceNotReadyDueToExecutionEventGap]         | `device_not_ready_due_to_execution_event_gap`           | Canonical execution event dimension has an unresolved gap.                         |
 * | [DeviceNotReadyDueToCompatGap]                 | `device_not_ready_due_to_compat_gap`                    | Compat / legacy blocking dimension has an unresolved gap.                          |
 * | [DeviceReadinessUnknownDueMissingSignal]        | `device_readiness_unknown_due_to_missing_signal`        | One or more dimensions lack a signal; readiness cannot be determined.              |
 *
 * ## Evaluation contract
 *
 * Only [DeviceReadyForRelease] signals that the device may participate in a V2 canonical
 * release gate.  All gap and unknown artifacts indicate that the release gate must not
 * proceed until the identified dimension is resolved.
 *
 * @see DelegatedRuntimeReadinessEvaluator
 * @see DelegatedRuntimeReadinessDimension
 * @see DelegatedRuntimeReadinessSnapshot
 */
sealed class DeviceReadinessArtifact {

    /**
     * Stable wire tag identifying the readiness semantic for this artifact.
     *
     * Matches one of the [DelegatedRuntimeReadinessEvaluator] `ARTIFACT_*` constants.
     */
    abstract val semanticTag: String

    // ── DeviceReadyForRelease ─────────────────────────────────────────────────

    /**
     * All five readiness dimensions are satisfied; the device may participate in the
     * V2 canonical release gate.
     *
     * Android-side semantic:
     * [DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READY_FOR_RELEASE].
     *
     * @property deviceId   The device identifier for which readiness was evaluated.
     * @property snapshotId Stable identifier for the readiness snapshot that produced this artifact.
     */
    data class DeviceReadyForRelease(
        val deviceId: String,
        val snapshotId: String
    ) : DeviceReadinessArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READY_FOR_RELEASE
    }

    // ── DeviceNotReadyDueToTruthGap ───────────────────────────────────────────

    /**
     * The local truth ownership / central alignment dimension has an unresolved gap.
     *
     * The device must not participate in the V2 release gate until the truth gap is
     * resolved.  Typical causes: truth emit gate is closed for too many units, too many
     * units have reached local terminal state without central alignment, or posture
     * conflicts are suppressing authoritative assertions.
     *
     * Android-side semantic:
     * [DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_TRUTH_GAP].
     *
     * @property deviceId     The device identifier for which readiness was evaluated.
     * @property snapshotId   Stable identifier for the readiness snapshot.
     * @property gapReason    Human-readable explanation of the truth gap.
     * @property dimension    Always [DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT].
     */
    data class DeviceNotReadyDueToTruthGap(
        val deviceId: String,
        val snapshotId: String,
        val gapReason: String,
        val dimension: DelegatedRuntimeReadinessDimension =
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT
    ) : DeviceReadinessArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_TRUTH_GAP
    }

    // ── DeviceNotReadyDueToResultGap ──────────────────────────────────────────

    /**
     * The result convergence participation dimension has an unresolved gap.
     *
     * The device must not participate in the V2 release gate until the result gap is
     * resolved.  Typical causes: convergence gate is closed for too many flows, late
     * partials after finals are being emitted unchecked, or parallel sub-results are
     * not being correctly bound to parent flows.
     *
     * Android-side semantic:
     * [DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_RESULT_GAP].
     *
     * @property deviceId     The device identifier for which readiness was evaluated.
     * @property snapshotId   Stable identifier for the readiness snapshot.
     * @property gapReason    Human-readable explanation of the result gap.
     * @property dimension    Always [DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION].
     */
    data class DeviceNotReadyDueToResultGap(
        val deviceId: String,
        val snapshotId: String,
        val gapReason: String,
        val dimension: DelegatedRuntimeReadinessDimension =
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION
    ) : DeviceReadinessArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_RESULT_GAP
    }

    // ── DeviceNotReadyDueToExecutionEventGap ──────────────────────────────────

    /**
     * The canonical execution event dimension has an unresolved gap.
     *
     * The device must not participate in the V2 release gate until the execution event
     * gap is resolved.  Typical causes: too many flows are event-gated (reconnect
     * alignment pending), noise gate suppression is masking real flow progress, or
     * post-terminal suppression is hiding stale-event issues.
     *
     * Android-side semantic:
     * [DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_EXECUTION_EVENT_GAP].
     *
     * @property deviceId     The device identifier for which readiness was evaluated.
     * @property snapshotId   Stable identifier for the readiness snapshot.
     * @property gapReason    Human-readable explanation of the execution event gap.
     * @property dimension    Always [DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT].
     */
    data class DeviceNotReadyDueToExecutionEventGap(
        val deviceId: String,
        val snapshotId: String,
        val gapReason: String,
        val dimension: DelegatedRuntimeReadinessDimension =
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT
    ) : DeviceReadinessArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_EXECUTION_EVENT_GAP
    }

    // ── DeviceNotReadyDueToCompatGap ──────────────────────────────────────────

    /**
     * The compat / legacy blocking dimension has an unresolved gap.
     *
     * The device must not participate in the V2 release gate until the compat gap is
     * resolved.  Typical causes: quarantined units have not been released after V2
     * alignment, blocked legacy paths are still active and not retired, or legacy emit
     * semantics are still influencing canonical surfaces.
     *
     * Android-side semantic:
     * [DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_COMPAT_GAP].
     *
     * @property deviceId     The device identifier for which readiness was evaluated.
     * @property snapshotId   Stable identifier for the readiness snapshot.
     * @property gapReason    Human-readable explanation of the compat gap.
     * @property dimension    Always [DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING].
     */
    data class DeviceNotReadyDueToCompatGap(
        val deviceId: String,
        val snapshotId: String,
        val gapReason: String,
        val dimension: DelegatedRuntimeReadinessDimension =
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING
    ) : DeviceReadinessArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_COMPAT_GAP
    }

    // ── DeviceReadinessUnknownDueMissingSignal ────────────────────────────────

    /**
     * One or more dimensions lack the signal required to determine readiness.
     *
     * The device's readiness for the V2 release gate cannot be confirmed or denied until
     * the missing signals are received.  V2 release gate must treat this as a blocking
     * condition equivalent to not-ready.
     *
     * Android-side semantic:
     * [DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL].
     *
     * @property deviceId          The device identifier for which readiness was evaluated.
     * @property snapshotId        Stable identifier for the readiness snapshot.
     * @property missingDimensions The set of [DelegatedRuntimeReadinessDimension] values for which
     *                             no signal was available.
     */
    data class DeviceReadinessUnknownDueMissingSignal(
        val deviceId: String,
        val snapshotId: String,
        val missingDimensions: Set<DelegatedRuntimeReadinessDimension>
    ) : DeviceReadinessArtifact() {
        override val semanticTag: String =
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL
    }
}

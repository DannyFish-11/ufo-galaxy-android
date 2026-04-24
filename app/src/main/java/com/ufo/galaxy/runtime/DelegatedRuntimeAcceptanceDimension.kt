package com.ufo.galaxy.runtime

/**
 * PR-10 (Android) — Enum representing the six device-side acceptance evidence dimensions
 * that collectively determine whether the Android delegated runtime can graduate to
 * canonical rollout / default-on / release enforcement.
 *
 * Each dimension corresponds to a distinct class of cross-dimensional device evidence that
 * must be present and sufficient before the device can be considered accepted for
 * graduation in the V2 final acceptance gate.  [READINESS_PREREQUISITE] acts as the
 * foundational gate: readiness ([DelegatedRuntimeReadinessEvaluator]) must already have
 * produced a [DeviceReadinessArtifact.DeviceReadyForRelease] before acceptance evaluation
 * is meaningful.
 *
 * ## Dimension overview
 *
 * | Dimension                              | Wire value                                 | Evidence source                                                          |
 * |----------------------------------------|--------------------------------------------|--------------------------------------------------------------------------|
 * | [READINESS_PREREQUISITE]               | `readiness_prerequisite`                   | [DelegatedRuntimeReadinessEvaluator]                                     |
 * | [CONTINUITY_REPLAY_RECONNECT_EVIDENCE] | `continuity_replay_reconnect_evidence`     | [AndroidRecoveryParticipationOwner], [AndroidContinuityIntegration]      |
 * | [TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE]   | `truth_ownership_alignment_evidence`       | [AndroidLocalTruthOwnershipCoordinator]                                  |
 * | [RESULT_CONVERGENCE_EVIDENCE]          | `result_convergence_evidence`              | [AndroidFlowAwareResultConvergenceParticipant]                           |
 * | [CANONICAL_EXECUTION_EVENT_EVIDENCE]   | `canonical_execution_event_evidence`       | [AndroidCanonicalExecutionEventOwner]                                    |
 * | [COMPAT_LEGACY_BLOCKING_EVIDENCE]      | `compat_legacy_blocking_evidence`          | [AndroidCompatLegacyBlockingParticipant]                                 |
 *
 * @property wireValue Stable lowercase string used in JSON payloads and acceptance reports.
 *
 * @see DelegatedRuntimeAcceptanceEvaluator
 * @see DeviceAcceptanceArtifact
 * @see DelegatedRuntimeAcceptanceSnapshot
 */
enum class DelegatedRuntimeAcceptanceDimension(val wireValue: String) {

    /**
     * Readiness prerequisite evidence.
     *
     * Covers whether the Android delegated runtime has already produced a passing
     * [DeviceReadinessArtifact.DeviceReadyForRelease] outcome from
     * [DelegatedRuntimeReadinessEvaluator].  Readiness is a prerequisite for acceptance:
     * if readiness has not been established, no further acceptance dimensions are
     * meaningful.
     *
     * Primary module: [DelegatedRuntimeReadinessEvaluator].
     */
    READINESS_PREREQUISITE("readiness_prerequisite"),

    /**
     * Continuity / replay / reconnect evidence.
     *
     * Covers whether the Android delegated runtime has cross-dimensional evidence
     * demonstrating that reconnect, replay, and resume semantics are sufficiently stable
     * beyond the readiness gate — e.g. replay-safe signal identity has been observed
     * across reconnect boundaries, session continuity has been validated at runtime, and
     * recovery participation events have been emitted and confirmed.
     *
     * Primary module: [AndroidRecoveryParticipationOwner], [AndroidContinuityIntegration].
     */
    CONTINUITY_REPLAY_RECONNECT_EVIDENCE("continuity_replay_reconnect_evidence"),

    /**
     * Truth ownership / alignment evidence.
     *
     * Covers whether the Android delegated runtime has evidence that local truth
     * ownership assertions are stable, V2 central truth alignment has been confirmed,
     * and posture-conflict suppression is not silently hiding authoritative assertions.
     *
     * Primary module: [AndroidLocalTruthOwnershipCoordinator].
     */
    TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE("truth_ownership_alignment_evidence"),

    /**
     * Result convergence participation evidence.
     *
     * Covers whether Android's flow-aware result convergence layer has produced
     * evidence of reliable classification, gate compliance, duplicate suppression, and
     * canonical emit behaviour across observed flows.
     *
     * Primary module: [AndroidFlowAwareResultConvergenceParticipant].
     */
    RESULT_CONVERGENCE_EVIDENCE("result_convergence_evidence"),

    /**
     * Canonical execution event evidence.
     *
     * Covers whether Android's canonical execution event emission layer has produced
     * evidence of correct flow-level operator visibility without post-terminal noise,
     * reconnect alignment gaps, or uncontrolled noise-gate suppression.
     *
     * Primary module: [AndroidCanonicalExecutionEventOwner].
     */
    CANONICAL_EXECUTION_EVENT_EVIDENCE("canonical_execution_event_evidence"),

    /**
     * Compat / legacy blocking evidence.
     *
     * Covers whether Android's compat and legacy path blocking layer has produced
     * evidence that quarantine, suppression, and blocking of legacy emit semantics and
     * non-canonical runtime paths is reliably active, without compat bypass risk reaching
     * V2 canonical surfaces.
     *
     * Primary module: [AndroidCompatLegacyBlockingParticipant].
     */
    COMPAT_LEGACY_BLOCKING_EVIDENCE("compat_legacy_blocking_evidence");

    companion object {

        /**
         * Returns the [DelegatedRuntimeAcceptanceDimension] with the given [wireValue],
         * or `null` if no match is found.
         *
         * @param value  The wire value string to look up.
         */
        fun fromValue(value: String?): DelegatedRuntimeAcceptanceDimension? =
            entries.firstOrNull { it.wireValue == value }
    }
}

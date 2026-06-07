package com.ufo.galaxy.runtime

/**
 * PR-9 (Android) — Enum representing the five device-side readiness dimensions that
 * collectively determine Android delegated runtime readiness for V2 release gating.
 *
 * Each dimension corresponds to a distinct Android-side canonical capability that must
 * reach a sufficient maturity level before the device can be considered ready for
 * participation in a V2 canonical release gate.
 *
 * ## Dimension overview
 *
 * | Dimension                                  | Wire value                                    | Primary owner module                                                     |
 * |--------------------------------------------|-----------------------------------------------|--------------------------------------------------------------------------|
 * | [CONTINUITY_REPLAY_RECONNECT]              | `continuity_replay_reconnect`                 | [AndroidRecoveryParticipationOwner], [AndroidContinuityIntegration]      |
 * | [LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT]  | `local_truth_ownership_central_alignment`     | [AndroidLocalTruthOwnershipCoordinator]                                  |
 * | [RESULT_CONVERGENCE_PARTICIPATION]         | `result_convergence_participation`            | [AndroidFlowAwareResultConvergenceParticipant]                           |
 * | [CANONICAL_EXECUTION_EVENT]                | `canonical_execution_event`                   | [AndroidCanonicalExecutionEventOwner]                                    |
 * | [COMPAT_LEGACY_BLOCKING]                   | `compat_legacy_blocking`                      | [AndroidCompatLegacyBlockingParticipant]                                 |
 *
 * @property wireValue Stable lowercase string used in JSON payloads and readiness reports.
 *
 * @see DelegatedRuntimeReadinessEvaluator
 * @see DeviceReadinessArtifact
 * @see DelegatedRuntimeReadinessSnapshot
 */
enum class DelegatedRuntimeReadinessDimension(val wireValue: String) {

    /**
     * Continuity / replay / reconnect readiness.
     *
     * Covers whether the Android delegated runtime's reconnect, replay, and resume
     * semantics are sufficiently stable to participate in a canonical release gate.
     * Includes replay-safe signal identity, session continuity, recovery participation,
     * and post-resume emit gating.
     *
     * Primary module: [AndroidRecoveryParticipationOwner], [AndroidContinuityIntegration].
     */
    CONTINUITY_REPLAY_RECONNECT("continuity_replay_reconnect"),

    /**
     * Local truth ownership / central alignment readiness.
     *
     * Covers whether Android's local truth ownership layer is stable enough to produce
     * authoritative truth assertions, gate compat-influenced truth, and participate in
     * V2 central truth alignment without corrupting canonical truth state.
     *
     * Primary module: [AndroidLocalTruthOwnershipCoordinator].
     */
    LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT("local_truth_ownership_central_alignment"),

    /**
     * Result convergence participation readiness.
     *
     * Covers whether Android's flow-aware result convergence layer can reliably classify,
     * gate, suppress duplicates, and emit results that V2 canonical convergence can safely
     * consume.
     *
     * Primary module: [AndroidFlowAwareResultConvergenceParticipant].
     */
    RESULT_CONVERGENCE_PARTICIPATION("result_convergence_participation"),

    /**
     * Canonical execution event readiness.
     *
     * Covers whether Android's canonical execution event emission layer is stable enough
     * to provide flow-level operator visibility without flooding, post-terminal noise, or
     * reconnect alignment gaps.
     *
     * Primary module: [AndroidCanonicalExecutionEventOwner].
     */
    CANONICAL_EXECUTION_EVENT("canonical_execution_event"),

    /**
     * Compat / legacy blocking readiness.
     *
     * Covers whether Android's compat and legacy path blocking layer can reliably
     * quarantine, suppress, or block legacy emit semantics, non-canonical runtime paths,
     * and ambiguous legacy execution states from reaching V2 canonical surfaces.
     *
     * Primary module: [AndroidCompatLegacyBlockingParticipant].
     */
    COMPAT_LEGACY_BLOCKING("compat_legacy_blocking");

    companion object {

        /**
         * Returns the [DelegatedRuntimeReadinessDimension] with the given [wireValue], or
         * `null` if no match is found.
         *
         * @param value  The wire value string to look up.
         */
        fun fromValue(value: String?): DelegatedRuntimeReadinessDimension? =
            entries.firstOrNull { it.wireValue == value }
    }
}

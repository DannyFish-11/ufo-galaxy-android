package com.ufo.galaxy.runtime

/**
 * PR-8 (Android) — Sealed class representing the Android-side compat / legacy path
 * blocking decision.
 *
 * [CompatLegacyBlockingDecision] is the typed output of
 * [AndroidCompatLegacyBlockingParticipant.evaluateBlockingDecision].  Every compat or
 * legacy path context that reaches a blocking evaluation point on Android must inspect
 * the returned decision before allowing execution to continue.
 *
 * ## Decision semantics
 *
 * Each concrete subtype corresponds to a named Android-side compat blocking semantic:
 *
 * | Subtype                                | Semantic tag                               | Description                                                                              |
 * |----------------------------------------|--------------------------------------------|------------------------------------------------------------------------------------------|
 * | [SuppressLegacyEmit]                   | `suppress_legacy_emit`                     | A legacy compat contract is influencing emission; suppress the emit.                     |
 * | [BlockNoncanonicalRuntimeTransition]   | `block_noncanonical_runtime_transition`    | A non-canonical runtime path was detected; block the runtime transition.                 |
 * | [QuarantineLegacyExecutionState]       | `quarantine_legacy_execution_state`        | Ambiguous legacy execution state must be quarantined pending center-side alignment.      |
 * | [AllowObservationOnly]                 | `allow_observation_only`                   | Legacy path is retained for passive observation only; must not influence canonical state.|
 * | [ConfirmCanonicalRuntimeTransition]    | `confirm_canonical_runtime_transition`     | Path is confirmed canonical; execution may proceed through canonical flow controls.      |
 *
 * ## Canonical boundary contract
 *
 * Only [ConfirmCanonicalRuntimeTransition] authorises execution to continue through
 * canonical runtime flow controls.  [AllowObservationOnly] permits read-only observation
 * but must not write to any canonical state.  All blocking and quarantine decisions must
 * prevent further execution progress on the affected path.
 *
 * @see AndroidCompatLegacyBlockingParticipant
 * @see CompatLegacyInfluenceClass
 */
sealed class CompatLegacyBlockingDecision {

    /**
     * Stable wire tag identifying the blocking semantic for this decision.
     *
     * Matches one of the [AndroidCompatLegacyBlockingParticipant] `DECISION_*` constants.
     */
    abstract val semanticTag: String

    // ── SuppressLegacyEmit ────────────────────────────────────────────────────

    /**
     * A legacy compat contract is actively influencing signal, result, or truth emission;
     * the emission must be suppressed.
     *
     * This decision applies when the influence class is
     * [CompatLegacyInfluenceClass.BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE].  Old validator
     * acceptance, old emit semantics, old contract adapter remapping, or legacy result
     * pass-through that bypasses canonical truth gating must all be suppressed via this
     * decision.
     *
     * Android-side semantic:
     * [AndroidCompatLegacyBlockingParticipant.DECISION_SUPPRESS_LEGACY_EMIT].
     *
     * @property unitId   The delegated unit or flow ID affected.
     * @property reason   Human-readable explanation of why the emission is suppressed.
     */
    data class SuppressLegacyEmit(
        val unitId: String,
        val reason: String
    ) : CompatLegacyBlockingDecision() {
        override val semanticTag: String =
            AndroidCompatLegacyBlockingParticipant.DECISION_SUPPRESS_LEGACY_EMIT
    }

    // ── BlockNoncanonicalRuntimeTransition ────────────────────────────────────

    /**
     * A non-canonical runtime path was detected; the runtime transition must be blocked.
     *
     * This decision applies when the influence class is
     * [CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH].  Legacy executor,
     * legacy receiver contract, old pipeline activation path, or any delegated runtime
     * routing that bypasses V2 canonical flow controls must be blocked via this decision.
     *
     * Android-side semantic:
     * [AndroidCompatLegacyBlockingParticipant.DECISION_BLOCK_NONCANONICAL_RUNTIME_TRANSITION].
     *
     * @property unitId         The delegated unit or flow ID affected.
     * @property legacyPathTag  Stable identifier of the legacy runtime path that was detected.
     * @property reason         Human-readable explanation of why the transition is blocked.
     */
    data class BlockNoncanonicalRuntimeTransition(
        val unitId: String,
        val legacyPathTag: String,
        val reason: String
    ) : CompatLegacyBlockingDecision() {
        override val semanticTag: String =
            AndroidCompatLegacyBlockingParticipant.DECISION_BLOCK_NONCANONICAL_RUNTIME_TRANSITION
    }

    // ── QuarantineLegacyExecutionState ────────────────────────────────────────

    /**
     * Ambiguous legacy execution state has been detected; the state must be quarantined
     * pending center-side alignment.
     *
     * This decision applies when the influence class is
     * [CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE].  Legacy
     * execution state from a prior reconnect era, interrupted recovery, or incomplete
     * replay sequence that cannot be resolved locally must be quarantined and held for
     * V2 coordination.  Silent pass-through would corrupt canonical truth.
     *
     * Android-side semantic:
     * [AndroidCompatLegacyBlockingParticipant.DECISION_QUARANTINE_LEGACY_EXECUTION_STATE].
     *
     * @property unitId          The delegated unit or flow ID affected.
     * @property legacyStateTag  Stable identifier describing the ambiguous legacy state.
     * @property reason          Human-readable explanation of why quarantine is required.
     */
    data class QuarantineLegacyExecutionState(
        val unitId: String,
        val legacyStateTag: String,
        val reason: String
    ) : CompatLegacyBlockingDecision() {
        override val semanticTag: String =
            AndroidCompatLegacyBlockingParticipant.DECISION_QUARANTINE_LEGACY_EXECUTION_STATE
    }

    // ── AllowObservationOnly ──────────────────────────────────────────────────

    /**
     * The legacy or compat path is permitted for passive observation only.
     *
     * This decision applies when the influence class is
     * [CompatLegacyInfluenceClass.ALLOW_FOR_OBSERVATION_ONLY].  The path may be read
     * for diagnostics, logging, or audit surfaces, but must not write canonical state,
     * emit authoritative results, influence signal semantics, or alter runtime transitions.
     *
     * Android-side semantic:
     * [AndroidCompatLegacyBlockingParticipant.DECISION_ALLOW_OBSERVATION_ONLY].
     *
     * @property unitId  The delegated unit or flow ID affected.
     * @property note    Optional human-readable note describing what is being observed.
     */
    data class AllowObservationOnly(
        val unitId: String,
        val note: String? = null
    ) : CompatLegacyBlockingDecision() {
        override val semanticTag: String =
            AndroidCompatLegacyBlockingParticipant.DECISION_ALLOW_OBSERVATION_ONLY
    }

    // ── ConfirmCanonicalRuntimeTransition ─────────────────────────────────────

    /**
     * The runtime path has been confirmed as canonical; execution may proceed through
     * canonical flow controls.
     *
     * This decision applies when the influence class is
     * [CompatLegacyInfluenceClass.CANONICAL_RUNTIME_PATH_CONFIRMED].  All dispatch,
     * emission, result, and signal semantics for this context are verified to route
     * through V2 canonical flow controls.  No blocking, quarantine, or observation-only
     * restrictions apply.
     *
     * Android-side semantic:
     * [AndroidCompatLegacyBlockingParticipant.DECISION_CONFIRM_CANONICAL_RUNTIME_TRANSITION].
     *
     * @property unitId  The delegated unit or flow ID whose path is confirmed canonical.
     */
    data class ConfirmCanonicalRuntimeTransition(
        val unitId: String
    ) : CompatLegacyBlockingDecision() {
        override val semanticTag: String =
            AndroidCompatLegacyBlockingParticipant.DECISION_CONFIRM_CANONICAL_RUNTIME_TRANSITION
    }
}

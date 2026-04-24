package com.ufo.galaxy.runtime

/**
 * PR-5 (Android) — Sealed class representing the Android-side local truth emit decision.
 *
 * [LocalTruthEmitDecision] is the typed output of
 * [AndroidLocalTruthOwnershipCoordinator.evaluateEmit].  Every truth emission point on
 * Android must evaluate a decision before forwarding any execution fact outward.
 * Callers must handle all variants and must not emit without inspecting the decision.
 *
 * ## Emit decision semantics
 *
 * Each concrete subtype corresponds to a named Android-side truth emit semantic:
 *
 * | Subtype                         | Semantic tag                          | Description                                                       |
 * |---------------------------------|---------------------------------------|-------------------------------------------------------------------|
 * | [EmitAsAuthoritativeLocalTruth] | `emit_as_authoritative_local_truth`   | Truth is owned locally and may be sent as an authoritative assertion. |
 * | [EmitAsExecutionEvidence]       | `emit_as_execution_evidence`          | Truth is factual execution evidence but not a canonical claim.    |
 * | [EmitAsAdvisoryOnly]            | `emit_as_advisory_only`               | Truth is advisory; V2 may disregard without breaking invariants.  |
 * | [SuppressDueToLocalTerminal]    | `suppress_due_to_local_terminal`      | Local execution is already terminal; duplicate emit suppressed.   |
 * | [SuppressDueToPostureConflict]  | `suppress_due_to_posture_conflict`    | Posture has changed and the truth is no longer coherent.          |
 * | [HoldForCentralAlignment]       | `hold_for_central_alignment`          | Truth must not be emitted until V2 canonical alignment completes. |
 *
 * ## Android authority boundary
 *
 * Only [EmitAsAuthoritativeLocalTruth] carries the claim that Android is the primary
 * truth source for the given execution fact.  All other decisions either downgrade the
 * truth to advisory/evidence or suppress/defer it entirely.  V2 is the canonical
 * orchestration authority for all cross-device and cross-flow decisions; Android MUST
 * NOT override V2 canonical truth unilaterally.
 *
 * @see AndroidLocalTruthOwnershipCoordinator
 * @see AndroidLocalTruthOwnershipCoordinator.TruthClass
 */
sealed class LocalTruthEmitDecision {

    /**
     * Stable wire tag that identifies the emit semantic for this decision.
     *
     * Matches one of the [AndroidLocalTruthOwnershipCoordinator] `DECISION_*` constants.
     */
    abstract val semanticTag: String

    // ── EmitAsAuthoritativeLocalTruth ─────────────────────────────────────────

    /**
     * Android owns this truth and may emit it as an authoritative local assertion.
     *
     * Applies when the truth is classified as
     * [AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_AUTHORITATIVE_ASSERTION] or
     * [AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_TERMINAL_CLAIM], the local
     * execution is not yet terminal, and no posture conflict or central alignment hold is
     * in effect.
     *
     * Android-side semantic: [AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_AUTHORITATIVE_LOCAL_TRUTH].
     *
     * @property unitId        The delegated unit ID whose truth is being emitted.
     * @property truthClass    The [AndroidLocalTruthOwnershipCoordinator.TruthClass]
     *                         classification that triggered this decision.
     * @property reason        Human-readable description of why this truth is authoritative.
     */
    data class EmitAsAuthoritativeLocalTruth(
        val unitId: String,
        val truthClass: AndroidLocalTruthOwnershipCoordinator.TruthClass,
        val reason: String
    ) : LocalTruthEmitDecision() {
        override val semanticTag: String =
            AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_AUTHORITATIVE_LOCAL_TRUTH
    }

    // ── EmitAsExecutionEvidence ───────────────────────────────────────────────

    /**
     * Android may emit this truth as execution evidence — factual but not a canonical claim.
     *
     * Applies when the truth is classified as
     * [AndroidLocalTruthOwnershipCoordinator.TruthClass.EXECUTION_EVIDENCE] or
     * [AndroidLocalTruthOwnershipCoordinator.TruthClass.PARTIAL_RESULT_TRUTH].
     * V2 may incorporate the evidence into its canonical model without treating it as an
     * authoritative override.
     *
     * Android-side semantic: [AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_EXECUTION_EVIDENCE].
     *
     * @property unitId        The delegated unit ID whose truth is being emitted.
     * @property truthClass    The [AndroidLocalTruthOwnershipCoordinator.TruthClass]
     *                         classification that triggered this decision.
     */
    data class EmitAsExecutionEvidence(
        val unitId: String,
        val truthClass: AndroidLocalTruthOwnershipCoordinator.TruthClass
    ) : LocalTruthEmitDecision() {
        override val semanticTag: String =
            AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_EXECUTION_EVIDENCE
    }

    // ── EmitAsAdvisoryOnly ────────────────────────────────────────────────────

    /**
     * Android may emit this truth as advisory — V2 may disregard it without breaking
     * invariants.
     *
     * Applies when the truth is classified as
     * [AndroidLocalTruthOwnershipCoordinator.TruthClass.ADVISORY_LOCAL_TRUTH] or
     * [AndroidLocalTruthOwnershipCoordinator.TruthClass.POSTURE_BOUND_TRUTH] with a valid
     * current posture (no posture conflict, but truth is posture-scoped).
     * V2 treats this as soft informational input rather than a binding claim.
     *
     * Android-side semantic: [AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_ADVISORY_ONLY].
     *
     * @property unitId        The delegated unit ID whose truth is being emitted.
     * @property truthClass    The [AndroidLocalTruthOwnershipCoordinator.TruthClass]
     *                         classification that triggered this decision.
     * @property advisoryNote  Optional human-readable note about the advisory context.
     */
    data class EmitAsAdvisoryOnly(
        val unitId: String,
        val truthClass: AndroidLocalTruthOwnershipCoordinator.TruthClass,
        val advisoryNote: String? = null
    ) : LocalTruthEmitDecision() {
        override val semanticTag: String =
            AndroidLocalTruthOwnershipCoordinator.DECISION_EMIT_AS_ADVISORY_ONLY
    }

    // ── SuppressDueToLocalTerminal ────────────────────────────────────────────

    /**
     * The local execution for this unit has already reached a terminal state; duplicate
     * emission must be suppressed.
     *
     * Applies when [AndroidLocalTruthOwnershipCoordinator.isLocalTerminal] returns `true`
     * for the given unit.  After a local terminal, Android MUST NOT emit any further truth
     * for this unit — doing so would break the invariant that the terminal signal is the
     * final authoritative truth.
     *
     * Android-side semantic: [AndroidLocalTruthOwnershipCoordinator.DECISION_SUPPRESS_DUE_TO_LOCAL_TERMINAL].
     *
     * @property unitId          The delegated unit ID that is already terminal.
     * @property terminalKind    The kind of terminal event that was previously recorded for
     *                           this unit (e.g. "completed", "failed", "cancelled").
     */
    data class SuppressDueToLocalTerminal(
        val unitId: String,
        val terminalKind: String
    ) : LocalTruthEmitDecision() {
        override val semanticTag: String =
            AndroidLocalTruthOwnershipCoordinator.DECISION_SUPPRESS_DUE_TO_LOCAL_TERMINAL
    }

    // ── SuppressDueToPostureConflict ──────────────────────────────────────────

    /**
     * The current posture has changed since this truth was generated; it is no longer
     * coherent and must be suppressed.
     *
     * Applies when the truth is classified as
     * [AndroidLocalTruthOwnershipCoordinator.TruthClass.POSTURE_BOUND_TRUTH] and the
     * posture recorded at truth generation time differs from the current posture.
     * Stale posture-bound truth MUST NOT be forwarded as if it were current, as it could
     * mislead V2's canonical model.
     *
     * Android-side semantic: [AndroidLocalTruthOwnershipCoordinator.DECISION_SUPPRESS_DUE_TO_POSTURE_CONFLICT].
     *
     * @property unitId           The delegated unit ID whose truth is affected.
     * @property generatedPosture The [SourceRuntimePosture] at the time the truth was
     *                            generated.
     * @property currentPosture   The current [SourceRuntimePosture] at the time of evaluation.
     */
    data class SuppressDueToPostureConflict(
        val unitId: String,
        val generatedPosture: SourceRuntimePosture,
        val currentPosture: SourceRuntimePosture
    ) : LocalTruthEmitDecision() {
        override val semanticTag: String =
            AndroidLocalTruthOwnershipCoordinator.DECISION_SUPPRESS_DUE_TO_POSTURE_CONFLICT
    }

    // ── HoldForCentralAlignment ───────────────────────────────────────────────

    /**
     * This truth must be held and not emitted until V2 central alignment completes.
     *
     * Applies when the truth emit gate is closed
     * ([AndroidLocalTruthOwnershipCoordinator.isTruthEmitGated] returns `true`), indicating
     * that a reconnect, rebind, or resume is in progress and the alignment state is not yet
     * known.  Android must buffer or discard the truth until the gate opens.
     *
     * Android-side semantic: [AndroidLocalTruthOwnershipCoordinator.DECISION_HOLD_FOR_CENTRAL_ALIGNMENT].
     *
     * @property unitId   The delegated unit ID whose truth is being held.
     * @property reason   Human-readable explanation of why the hold is in effect.
     */
    data class HoldForCentralAlignment(
        val unitId: String,
        val reason: String
    ) : LocalTruthEmitDecision() {
        override val semanticTag: String =
            AndroidLocalTruthOwnershipCoordinator.DECISION_HOLD_FOR_CENTRAL_ALIGNMENT
    }
}

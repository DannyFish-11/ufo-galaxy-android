package com.ufo.galaxy.runtime

/**
 * PR-4 (Android) — Sealed class representing the Android-side local recovery decision.
 *
 * [LocalRecoveryDecision] is the typed output of
 * [AndroidRecoveryParticipationOwner.evaluateRecovery].  Every recovery entry point
 * on Android produces exactly one decision; callers must handle all variants and must
 * not proceed to execution without inspecting the decision.
 *
 * ## Recovery semantics
 *
 * Each concrete subtype corresponds to a named Android-side recovery semantic:
 *
 * | Subtype                         | Semantic tag                          | Description                                                    |
 * |---------------------------------|---------------------------------------|----------------------------------------------------------------|
 * | [ResumeLocalExecution]          | `resume_local_execution`              | Session / pipeline is intact; Android may resume without waiting for V2. |
 * | [RehydrateThenContinue]         | `rehydrate_then_continue`             | Prior context rehydrated; Android presents it to V2 and continues after V2 authorisation. |
 * | [WaitForV2ReplayDecision]       | `wait_for_v2_replay_decision`         | Android must wait for V2 replay / re-dispatch decision before proceeding. |
 * | [SuppressDuplicateLocalRecovery]| `suppress_duplicate_local_recovery`   | A recovery attempt for the same key is already in progress; suppress. |
 * | [NoRecoveryContext]             | `no_recovery_context`                 | No prior continuity context is available; fresh start is required. |
 *
 * ## Android authority boundary
 *
 * Only [ResumeLocalExecution] permits Android to proceed without explicit V2 authorisation,
 * and only when the session is intact and the recovery is limited to a receiver / pipeline
 * rebind (no interrupted execution, no replayed context).  All other decisions require
 * presenting context to V2 or waiting for a V2 replay/re-dispatch signal.
 *
 * @see AndroidRecoveryParticipationOwner
 * @see RecoveryActivationCheckpoint
 */
sealed class LocalRecoveryDecision {

    /**
     * Stable wire tag that identifies the recovery semantic for this decision.
     *
     * Matches one of the [AndroidRecoveryParticipationOwner] `SEMANTIC_*` constants.
     */
    abstract val semanticTag: String

    // ── ResumeLocalExecution ──────────────────────────────────────────────────

    /**
     * Android can resume local execution immediately without waiting for V2.
     *
     * Applies when the session is intact and only the receiver / pipeline binding
     * is being restored (e.g. after a configuration change or component restart).
     * No execution was interrupted; the session owns the current execution context.
     *
     * Android-side semantic: [AndroidRecoveryParticipationOwner.SEMANTIC_RESUME_LOCAL_EXECUTION].
     *
     * @property activeSessionId  The [AttachedRuntimeSession.sessionId] of the current
     *                            intact session.
     * @property checkpoint       An optional [RecoveryActivationCheckpoint] carrying the
     *                            last checkpointed state, if a prior flow record was
     *                            available.  May be `null` for pure rebind events where no
     *                            prior flow was in progress.
     */
    data class ResumeLocalExecution(
        val activeSessionId: String,
        val checkpoint: RecoveryActivationCheckpoint? = null
    ) : LocalRecoveryDecision() {
        override val semanticTag: String =
            AndroidRecoveryParticipationOwner.SEMANTIC_RESUME_LOCAL_EXECUTION
    }

    // ── RehydrateThenContinue ─────────────────────────────────────────────────

    /**
     * Android has rehydrated prior execution context and must present it to V2 before
     * continuing.
     *
     * Applies when a persisted [DelegatedFlowContinuityRecord] is available (from a
     * continuity resume or process recreation re-attach with recovered context).  Android
     * rehydrates the record into the [localContext] snapshot and forwards it to V2 on
     * re-attach.  Android MUST NOT resume execution locally until V2 authorises continuation.
     *
     * Android-side semantic: [AndroidRecoveryParticipationOwner.SEMANTIC_REHYDRATE_THEN_CONTINUE].
     *
     * @property localContext  The rehydrated [LocalExecutionContextSnapshot] to forward to V2.
     * @property checkpoint    The [RecoveryActivationCheckpoint] derived from [localContext],
     *                         indicating the execution phase and continuity token at the time
     *                         the context was persisted.
     */
    data class RehydrateThenContinue(
        val localContext: LocalExecutionContextSnapshot,
        val checkpoint: RecoveryActivationCheckpoint
    ) : LocalRecoveryDecision() {
        override val semanticTag: String =
            AndroidRecoveryParticipationOwner.SEMANTIC_REHYDRATE_THEN_CONTINUE
    }

    // ── WaitForV2ReplayDecision ───────────────────────────────────────────────

    /**
     * Android must wait for V2's replay / re-dispatch decision before proceeding.
     *
     * Applies when the recovery requires V2 to decide whether to replay, resume, or
     * start fresh — typically after a transport reconnect or a process recreation without
     * a recoverable local context.  Android MUST NOT enter any execution pipeline or
     * emit any signals until V2 delivers a new dispatch or an explicit "start fresh" signal.
     *
     * Android-side semantic: [AndroidRecoveryParticipationOwner.SEMANTIC_WAIT_FOR_V2_REPLAY_DECISION].
     *
     * @property continuityToken   The V2-managed continuity token from the prior context, if
     *                             available.  Android echoes this in the reconnect / re-attach
     *                             event so V2 can correlate with the original session.
     * @property durableSessionId  The durable session identifier from the prior era, if
     *                             available.  Helps V2 identify the participant's prior session.
     * @property reason            Human-readable explanation of why Android is waiting for V2.
     */
    data class WaitForV2ReplayDecision(
        val continuityToken: String?,
        val durableSessionId: String?,
        val reason: String
    ) : LocalRecoveryDecision() {
        override val semanticTag: String =
            AndroidRecoveryParticipationOwner.SEMANTIC_WAIT_FOR_V2_REPLAY_DECISION
    }

    // ── SuppressDuplicateLocalRecovery ────────────────────────────────────────

    /**
     * A recovery attempt for the same key is already in progress; this attempt must be
     * suppressed.
     *
     * Applies when [AndroidRecoveryParticipationOwner.suppressDuplicateLocalRecovery]
     * returns `true` for the recovery attempt key.  Callers must drop the current attempt
     * without entering any execution pipeline.  The in-progress attempt will complete
     * independently and the caller should wait for its outcome.
     *
     * Android-side semantic: [AndroidRecoveryParticipationOwner.SEMANTIC_SUPPRESS_DUPLICATE_LOCAL_RECOVERY].
     *
     * @property attemptKey  The recovery attempt key that was found to be already in progress.
     */
    data class SuppressDuplicateLocalRecovery(
        val attemptKey: String
    ) : LocalRecoveryDecision() {
        override val semanticTag: String =
            AndroidRecoveryParticipationOwner.SEMANTIC_SUPPRESS_DUPLICATE_LOCAL_RECOVERY
    }

    // ── NoRecoveryContext ─────────────────────────────────────────────────────

    /**
     * No prior continuity context is available; a fresh start is required.
     *
     * Applies when the attach intent is a [AndroidContinuityIntegration.ContinuityDecision.FreshAttach]
     * or any other scenario where no durable session, prior flow record, or process
     * recreation hint is available.  Android starts a new execution era without any
     * recovery overhead.
     *
     * Android-side semantic: no [AndroidRecoveryParticipationOwner] semantic constant;
     * the caller proceeds with normal fresh-attach initialisation.
     */
    object NoRecoveryContext : LocalRecoveryDecision() {
        override val semanticTag: String = "no_recovery_context"
    }
}

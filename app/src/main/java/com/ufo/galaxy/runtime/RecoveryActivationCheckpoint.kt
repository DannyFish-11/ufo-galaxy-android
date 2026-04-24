package com.ufo.galaxy.runtime

/**
 * PR-4 (Android) — Checkpoint model for checkpoint-aware activation continuation.
 *
 * [RecoveryActivationCheckpoint] captures the minimal durable state needed to resume or
 * re-dispatch a delegated activation from the point at which it was last checkpointed.
 * It is produced by [AndroidRecoveryParticipationOwner.buildRecoveryCheckpoint] from a
 * rehydrated [LocalExecutionContextSnapshot] and consumed by the recovery decision path
 * when Android determines it can perform [LocalRecoveryDecision.ResumeLocalExecution] or
 * [LocalRecoveryDecision.RehydrateThenContinue].
 *
 * ## Purpose
 *
 * Before PR-4, Android had no structured checkpoint model for activation continuation.
 * [LocalExecutionContextSnapshot] carried the raw rehydrated view of the persisted
 * [DelegatedFlowContinuityRecord], but it was not scoped specifically to the "should I
 * resume?" decision boundary.  [RecoveryActivationCheckpoint] fills that gap by
 * providing a purpose-built model that:
 *
 *  1. **Captures the execution phase** at checkpoint time so the recovery owner can
 *     immediately determine whether resumption is structurally valid.
 *  2. **Carries the continuity token** so V2 correlation on re-dispatch is possible
 *     without re-parsing the original [LocalExecutionContextSnapshot].
 *  3. **Records the checkpoint timestamp** for diagnostics and latency measurement.
 *
 * ## Resumability
 *
 * A checkpoint is resumable when [isCheckpointResumable] is `true`, meaning
 * [lastKnownPhase] is non-terminal.  Terminal checkpoints (COMPLETED, FAILED, REJECTED)
 * MUST NOT be used to self-authorise re-execution.  They are retained as recovery
 * artifacts for diagnostics and V2 correlation.
 *
 * ## V2 authority boundary
 *
 * [RecoveryActivationCheckpoint] is an **Android-side advisory model**.  Even when
 * [isCheckpointResumable] is `true`, Android MUST NOT autonomously resume execution
 * without a [LocalRecoveryDecision] from [AndroidRecoveryParticipationOwner] that
 * explicitly authorises resumption (either [LocalRecoveryDecision.ResumeLocalExecution]
 * or a [LocalRecoveryDecision.RehydrateThenContinue] confirmed by V2).
 *
 * @property flowId              Stable V2 canonical flow identifier from the prior activation.
 * @property attachedSessionId   Attached session identifier under which the prior activation ran.
 * @property durableSessionId    Durable session identifier from the prior activation era.
 * @property lastKnownPhase      The [AndroidFlowExecutionPhase] when the checkpoint was last
 *                               persisted.  Used to determine [isCheckpointResumable].
 * @property continuityToken     V2-managed durable execution continuity token; null when not
 *                               present.  Echoed back in re-dispatch or re-attach events so V2
 *                               can correlate the resumed activation with its originating session.
 * @property stepCount           Number of execution steps completed before the checkpoint.
 * @property checkpointAtMs      Epoch-ms timestamp when this checkpoint was produced.
 *
 * @see AndroidRecoveryParticipationOwner
 * @see LocalRecoveryDecision
 * @see LocalExecutionContextSnapshot
 */
data class RecoveryActivationCheckpoint(

    // ── Flow / session binding ────────────────────────────────────────────────
    val flowId: String,
    val attachedSessionId: String,
    val durableSessionId: String,

    // ── Execution state at checkpoint ─────────────────────────────────────────
    val lastKnownPhase: AndroidFlowExecutionPhase,
    val continuityToken: String?,
    val stepCount: Int,

    // ── Checkpoint metadata ───────────────────────────────────────────────────
    val checkpointAtMs: Long = System.currentTimeMillis()
) {

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * `true` when this checkpoint represents a non-terminal execution phase and is
     * structurally eligible for local resumption.
     *
     * A resumable checkpoint means the prior execution was interrupted at an active or
     * in-flight phase — it had not yet completed, failed, or been rejected.  Android
     * MUST NOT use this flag alone to self-authorise re-execution; the
     * [AndroidRecoveryParticipationOwner] decision must also authorise the resume.
     *
     * Always `false` for [AndroidFlowExecutionPhase.COMPLETED],
     * [AndroidFlowExecutionPhase.FAILED], [AndroidFlowExecutionPhase.REJECTED], and
     * [AndroidFlowExecutionPhase.UNKNOWN].
     */
    val isCheckpointResumable: Boolean
        get() = !lastKnownPhase.isTerminal && lastKnownPhase != AndroidFlowExecutionPhase.UNKNOWN

    /**
     * `true` when this checkpoint was produced with a V2-managed [continuityToken].
     *
     * Checkpoints with a continuity token can be directly correlated with V2's canonical
     * session registry on re-dispatch, enabling precise V2 replay decision alignment.
     */
    val hasContinuityToken: Boolean
        get() = !continuityToken.isNullOrBlank()

    /**
     * Builds the canonical checkpoint metadata map for inclusion in V2 lifecycle events
     * or diagnostic payloads.
     *
     * Keys always present: [KEY_FLOW_ID], [KEY_ATTACHED_SESSION_ID],
     * [KEY_DURABLE_SESSION_ID], [KEY_LAST_KNOWN_PHASE], [KEY_STEP_COUNT],
     * [KEY_IS_RESUMABLE], [KEY_CHECKPOINT_AT_MS].
     *
     * Keys present when non-null: [KEY_CONTINUITY_TOKEN].
     *
     * @return An immutable [Map] suitable for merging into re-dispatch metadata payloads.
     */
    fun toCheckpointMetadataMap(): Map<String, Any> = buildMap {
        put(KEY_FLOW_ID, flowId)
        put(KEY_ATTACHED_SESSION_ID, attachedSessionId)
        put(KEY_DURABLE_SESSION_ID, durableSessionId)
        put(KEY_LAST_KNOWN_PHASE, lastKnownPhase.wireValue)
        put(KEY_STEP_COUNT, stepCount)
        put(KEY_IS_RESUMABLE, isCheckpointResumable)
        put(KEY_CHECKPOINT_AT_MS, checkpointAtMs)
        continuityToken?.let { put(KEY_CONTINUITY_TOKEN, it) }
    }

    // ── Companion / factory ───────────────────────────────────────────────────

    companion object {

        // ── Wire key constants ────────────────────────────────────────────────

        /** Wire key for [flowId]. */
        const val KEY_FLOW_ID = "recovery_checkpoint_flow_id"

        /** Wire key for [attachedSessionId]. */
        const val KEY_ATTACHED_SESSION_ID = "recovery_checkpoint_attached_session_id"

        /** Wire key for [durableSessionId]. */
        const val KEY_DURABLE_SESSION_ID = "recovery_checkpoint_durable_session_id"

        /** Wire key for [lastKnownPhase] wire value. */
        const val KEY_LAST_KNOWN_PHASE = "recovery_checkpoint_last_known_phase"

        /** Wire key for [continuityToken]. Present only when non-null. */
        const val KEY_CONTINUITY_TOKEN = "recovery_checkpoint_continuity_token"

        /** Wire key for [stepCount]. */
        const val KEY_STEP_COUNT = "recovery_checkpoint_step_count"

        /** Wire key for [isCheckpointResumable]. */
        const val KEY_IS_RESUMABLE = "recovery_checkpoint_is_resumable"

        /** Wire key for [checkpointAtMs]. */
        const val KEY_CHECKPOINT_AT_MS = "recovery_checkpoint_at_ms"

        /**
         * Set of all mandatory wire key constants (always present in
         * [toCheckpointMetadataMap]).
         */
        val ALL_MANDATORY_KEYS: Set<String> = setOf(
            KEY_FLOW_ID,
            KEY_ATTACHED_SESSION_ID,
            KEY_DURABLE_SESSION_ID,
            KEY_LAST_KNOWN_PHASE,
            KEY_STEP_COUNT,
            KEY_IS_RESUMABLE,
            KEY_CHECKPOINT_AT_MS
        )

        /**
         * Constructs a [RecoveryActivationCheckpoint] from a rehydrated
         * [LocalExecutionContextSnapshot].
         *
         * @param snapshot        The rehydrated local execution context.
         * @param checkpointAtMs  Epoch-ms timestamp for this checkpoint; defaults to the current
         *                        wall clock.
         * @return A [RecoveryActivationCheckpoint] reflecting the execution state in [snapshot].
         */
        fun fromSnapshot(
            snapshot: LocalExecutionContextSnapshot,
            checkpointAtMs: Long = System.currentTimeMillis()
        ): RecoveryActivationCheckpoint = RecoveryActivationCheckpoint(
            flowId = snapshot.flowId,
            attachedSessionId = snapshot.attachedSessionId,
            durableSessionId = snapshot.durableSessionId,
            lastKnownPhase = snapshot.lastKnownPhase,
            continuityToken = snapshot.continuityToken,
            stepCount = snapshot.stepCount,
            checkpointAtMs = checkpointAtMs
        )

        /**
         * PR number that introduced this recovery activation checkpoint model.
         */
        const val INTRODUCED_PR: Int = 4
    }
}

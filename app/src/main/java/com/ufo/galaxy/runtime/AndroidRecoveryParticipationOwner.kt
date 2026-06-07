package com.ufo.galaxy.runtime

/**
 * PR-4 (Android) — Android-side recovery participation owner for replay / resume /
 * re-dispatch orchestration.
 *
 * [AndroidRecoveryParticipationOwner] is the unified Android-side entry point for all
 * local recovery participation decisions.  It extends the continuity-awareness established
 * by [AndroidContinuityIntegration] (PR-3) into a full **recovery ownership layer** that
 * can act as the local coordination point for:
 *
 *  - Replay / resume from a persisted or rehydrated execution context.
 *  - Checkpoint-aware activation continuation.
 *  - Receiver / pipeline rebind continuation after session restore.
 *  - Suppression of duplicate local recovery attempts.
 *  - Local emit gating after reconnect or resume (post-resume emit gate).
 *  - Re-dispatch handoff readiness signaling.
 *
 * ## Background and motivation
 *
 * Before PR-4, Android was continuity-aware (PR-3) but not recovery-owning.  It could
 * participate in attach / re-attach / reconnect decisions through
 * [AndroidContinuityIntegration], but had no unified orchestration layer that answered:
 *
 *  - Should Android resume locally or wait for V2 to drive replay?
 *  - Where (at which activation checkpoint) should local execution continue?
 *  - Is this a duplicate recovery attempt that should be suppressed?
 *  - Should signal emission be gated while the recovery sequence is in progress?
 *  - Which downstream components (receiver, pipeline, loop, takeover, collaboration)
 *    should be notified once recovery is confirmed?
 *
 * [AndroidRecoveryParticipationOwner] answers all of these questions through a single,
 * composable API.
 *
 * ## Recovery semantics
 *
 * Five named recovery semantics are established by this class:
 *
 * | Semantic constant                         | Wire value                          | Description                                                    |
 * |-------------------------------------------|-------------------------------------|----------------------------------------------------------------|
 * | [SEMANTIC_RESUME_LOCAL_EXECUTION]         | `resume_local_execution`            | Session / pipeline intact; Android may resume immediately.     |
 * | [SEMANTIC_REHYDRATE_THEN_CONTINUE]        | `rehydrate_then_continue`           | Prior context rehydrated; present to V2 then continue.         |
 * | [SEMANTIC_WAIT_FOR_V2_REPLAY_DECISION]    | `wait_for_v2_replay_decision`       | Android must wait for V2 replay/re-dispatch decision.          |
 * | [SEMANTIC_SUPPRESS_DUPLICATE_LOCAL_RECOVERY] | `suppress_duplicate_local_recovery` | Duplicate recovery attempt; suppress.                      |
 * | [SEMANTIC_GATE_POST_RESUME_EMIT]          | `gate_post_resume_emit`             | Emission gated while recovery is in progress.                  |
 *
 * ## Integration points
 *
 * [AndroidRecoveryParticipationOwner] establishes clear integration boundaries with the
 * following existing modules:
 *
 * | Integration point constant               | Module                                                      | Role                                                        |
 * |------------------------------------------|-------------------------------------------------------------|-------------------------------------------------------------|
 * | [INTEGRATION_RECEIVER]                   | [com.ufo.galaxy.agent.DelegatedRuntimeReceiver]             | Recovery owner gates receipt until recovery decision is made. |
 * | [INTEGRATION_UNIT]                       | [com.ufo.galaxy.agent.DelegatedRuntimeUnit]                 | Unit session ID is validated against the recovery checkpoint. |
 * | [INTEGRATION_ACTIVATION_RECORD]          | [com.ufo.galaxy.runtime.DelegatedActivationRecord]          | Activation record is produced after recovery decision allows. |
 * | [INTEGRATION_PIPELINE]                   | [com.ufo.galaxy.agent.AutonomousExecutionPipeline]          | Pipeline entry gated by recovery decision kind.              |
 * | [INTEGRATION_LOOP_CONTROLLER]            | [com.ufo.galaxy.loop.LoopController]                        | Loop entry gated by recovery decision kind.                  |
 * | [INTEGRATION_TAKEOVER_EXECUTOR]          | [com.ufo.galaxy.agent.DelegatedTakeoverExecutor]            | Takeover execution gated; emit gate checked before each signal. |
 * | [INTEGRATION_COLLABORATION_AGENT]        | [com.ufo.galaxy.agent.LocalCollaborationAgent]              | Subtask dispatch gated by recovery decision kind.            |
 *
 * ## Core API
 *
 * ```
 * val owner = AndroidRecoveryParticipationOwner()
 *
 * // Close the emit gate while recovery is in progress
 * owner.closePostResumeEmitGate()
 *
 * // Evaluate what kind of recovery this attach event requires
 * val decision = owner.evaluateRecovery(RecoveryInput(
 *     continuityDecision = continuityIntegration.evaluateAttachIntent(...),
 *     recoveryAttemptKey = flowId
 * ))
 *
 * when (decision) {
 *     is LocalRecoveryDecision.ResumeLocalExecution -> { /* rebind + continue */ }
 *     is LocalRecoveryDecision.RehydrateThenContinue -> { /* present to V2, then continue */ }
 *     is LocalRecoveryDecision.WaitForV2ReplayDecision -> { /* wait for V2 */ }
 *     is LocalRecoveryDecision.SuppressDuplicateLocalRecovery -> { /* drop this attempt */ }
 *     LocalRecoveryDecision.NoRecoveryContext -> { /* fresh start */ }
 * }
 *
 * // Open the gate once recovery confirms the first valid signal
 * owner.openPostResumeEmitGate()
 * ```
 *
 * ## Thread safety
 *
 * [suppressDuplicateLocalRecovery], [markRecoveryAttemptStarted],
 * [markRecoveryAttemptFinished], [clearRecoveryAttemptRegistry], and
 * [inProgressRecoveryCount] use a [synchronized] block on the internal attempt-key set.
 * [closePostResumeEmitGate], [openPostResumeEmitGate], and [isPostResumeEmitGated] use
 * `@Volatile` for safe cross-thread visibility without locking.
 * All other methods are pure functions with no shared mutable state.
 *
 * @see AndroidContinuityIntegration
 * @see LocalRecoveryDecision
 * @see RecoveryActivationCheckpoint
 */
class AndroidRecoveryParticipationOwner {

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * In-progress recovery attempt key registry for duplicate suppression.
     *
     * Keys are stable attempt identifiers supplied by callers via
     * [RecoveryInput.recoveryAttemptKey] (typically a flow ID or durable session ID).
     * A key is added by [markRecoveryAttemptStarted] and removed by
     * [markRecoveryAttemptFinished] or [clearRecoveryAttemptRegistry].
     */
    private val _inProgressAttemptKeys: MutableSet<String> = mutableSetOf()

    /**
     * Post-resume emit gate flag.
     *
     * `true` when emission is currently gated (blocked); `false` when emission is open.
     * Callers check this via [gatePostResumeEmit].  Set to `true` by
     * [closePostResumeEmitGate] (call at the start of recovery) and reset to `false` by
     * [openPostResumeEmitGate] (call when the recovery sequence completes or confirms the
     * first valid signal).
     */
    @Volatile
    private var _postResumeEmitGated: Boolean = false

    // ── RecoveryInput ─────────────────────────────────────────────────────────

    /**
     * Input model for [evaluateRecovery].
     *
     * @property continuityDecision  The [AndroidContinuityIntegration.ContinuityDecision]
     *                               produced by [AndroidContinuityIntegration.evaluateAttachIntent]
     *                               for the current attach / re-attach event.
     * @property recoveryAttemptKey  Stable caller-supplied key used for duplicate recovery
     *                               suppression.  Should be a stable identifier scoped to
     *                               the current flow or session (e.g. flow ID, durable
     *                               session ID, or a compound key).
     * @property nowMs               Epoch-ms reference time; defaults to the current wall clock.
     *                               Supply a fixed value in tests.
     */
    data class RecoveryInput(
        val continuityDecision: AndroidContinuityIntegration.ContinuityDecision,
        val recoveryAttemptKey: String,
        val nowMs: Long = System.currentTimeMillis()
    )

    // ── Core API — recovery decision ──────────────────────────────────────────

    /**
     * Evaluates the current attach / reconnect / re-attach event and returns a typed
     * [LocalRecoveryDecision] classifying the required Android-side recovery action.
     *
     * ## Decision logic
     *
     * 1. **Duplicate suppression** — if [suppressDuplicateLocalRecovery] returns `true`
     *    for [input.recoveryAttemptKey][RecoveryInput.recoveryAttemptKey] →
     *    [LocalRecoveryDecision.SuppressDuplicateLocalRecovery].
     *
     * 2. **ContinuityDecision dispatch** — the outer decision is driven by the
     *    [AndroidContinuityIntegration.ContinuityDecision] from PR-3:
     *
     *    | ContinuityDecision variant        | LocalRecoveryDecision returned                        |
     *    |-----------------------------------|-------------------------------------------------------|
     *    | [FreshAttach]                     | [LocalRecoveryDecision.NoRecoveryContext]              |
     *    | [ReceiverPipelineRebind]          | [LocalRecoveryDecision.ResumeLocalExecution] (session intact; rebind continuation) |
     *    | [TransportReconnect]              | [LocalRecoveryDecision.WaitForV2ReplayDecision] (V2 must decide replay) |
     *    | [ContinuityResume]                | [LocalRecoveryDecision.RehydrateThenContinue] (prior context rehydrated) |
     *    | [ProcessRecreationReattach] with context  | [LocalRecoveryDecision.RehydrateThenContinue] |
     *    | [ProcessRecreationReattach] without context | [LocalRecoveryDecision.WaitForV2ReplayDecision] |
     *
     * @param input The [RecoveryInput] describing the current attach / reconnect event.
     * @return The [LocalRecoveryDecision] classifying the required Android-side recovery action.
     */
    fun evaluateRecovery(input: RecoveryInput): LocalRecoveryDecision {

        // ── 1. Duplicate suppression ──────────────────────────────────────────
        if (suppressDuplicateLocalRecovery(input.recoveryAttemptKey)) {
            return LocalRecoveryDecision.SuppressDuplicateLocalRecovery(
                attemptKey = input.recoveryAttemptKey
            )
        }

        // ── 2. Dispatch on ContinuityDecision ─────────────────────────────────
        return when (val decision = input.continuityDecision) {

            // Fresh attach — no prior context, fresh start required
            is AndroidContinuityIntegration.ContinuityDecision.FreshAttach ->
                LocalRecoveryDecision.NoRecoveryContext

            // Receiver / pipeline rebind — session is intact; Android resumes locally
            is AndroidContinuityIntegration.ContinuityDecision.ReceiverPipelineRebind ->
                LocalRecoveryDecision.ResumeLocalExecution(
                    activeSessionId = decision.activeSessionId,
                    checkpoint = null
                )

            // Transport reconnect — Android must wait for V2 to decide replay
            is AndroidContinuityIntegration.ContinuityDecision.TransportReconnect ->
                LocalRecoveryDecision.WaitForV2ReplayDecision(
                    continuityToken = null,
                    durableSessionId = decision.durableSession.durableSessionId,
                    reason = REASON_TRANSPORT_RECONNECT_AWAITING_V2
                )

            // Continuity resume — prior flow record rehydrated; present to V2 then continue
            is AndroidContinuityIntegration.ContinuityDecision.ContinuityResume -> {
                val checkpoint = buildRecoveryCheckpoint(
                    snapshot = decision.localContext,
                    nowMs = input.nowMs
                )
                LocalRecoveryDecision.RehydrateThenContinue(
                    localContext = decision.localContext,
                    checkpoint = checkpoint
                )
            }

            // Process recreation re-attach — depends on whether local context survived
            is AndroidContinuityIntegration.ContinuityDecision.ProcessRecreationReattach -> {
                val localContext = decision.localContext
                if (localContext != null) {
                    val checkpoint = buildRecoveryCheckpoint(
                        snapshot = localContext,
                        nowMs = input.nowMs
                    )
                    LocalRecoveryDecision.RehydrateThenContinue(
                        localContext = localContext,
                        checkpoint = checkpoint
                    )
                } else {
                    LocalRecoveryDecision.WaitForV2ReplayDecision(
                        continuityToken = null,
                        durableSessionId = null,
                        reason = REASON_PROCESS_RECREATION_NO_LOCAL_CONTEXT
                    )
                }
            }

            // Stale identity rejection — treat as no recovery context (session mismatch)
            is AndroidContinuityIntegration.ContinuityDecision.RejectStaleIdentity ->
                LocalRecoveryDecision.NoRecoveryContext
        }
    }

    // ── Duplicate local recovery suppression ──────────────────────────────────

    /**
     * Returns `true` when a recovery attempt for [attemptKey] is already in progress,
     * indicating that the current attempt should be suppressed.
     *
     * This is the canonical entry point for the
     * [SEMANTIC_SUPPRESS_DUPLICATE_LOCAL_RECOVERY] guard.  It prevents a reconnect or
     * process recreation event from triggering multiple concurrent recovery sequences for
     * the same flow or session.
     *
     * **Usage pattern:**
     * ```kotlin
     * if (recoveryOwner.suppressDuplicateLocalRecovery(flowId)) {
     *     return // suppress — already recovering
     * }
     * recoveryOwner.markRecoveryAttemptStarted(flowId)
     * try {
     *     // ... perform recovery ...
     * } finally {
     *     recoveryOwner.markRecoveryAttemptFinished(flowId)
     * }
     * ```
     *
     * @param attemptKey The stable attempt key to check (e.g. flow ID, durable session ID).
     * @return `true` when a recovery for [attemptKey] is already in progress (suppress).
     */
    fun suppressDuplicateLocalRecovery(attemptKey: String): Boolean {
        synchronized(_inProgressAttemptKeys) {
            return _inProgressAttemptKeys.contains(attemptKey)
        }
    }

    /**
     * Registers [attemptKey] as an in-progress recovery attempt.
     *
     * Call this immediately **before** starting the recovery sequence so that concurrent
     * callers see the key and suppress their own attempts.
     *
     * @param attemptKey The stable attempt key to register.
     */
    fun markRecoveryAttemptStarted(attemptKey: String) {
        synchronized(_inProgressAttemptKeys) {
            _inProgressAttemptKeys.add(attemptKey)
        }
    }

    /**
     * Removes [attemptKey] from the in-progress registry.
     *
     * Call this when the recovery sequence completes (successfully or with failure) so
     * that future recovery attempts for the same key are not suppressed.
     *
     * @param attemptKey The stable attempt key to remove.
     */
    fun markRecoveryAttemptFinished(attemptKey: String) {
        synchronized(_inProgressAttemptKeys) {
            _inProgressAttemptKeys.remove(attemptKey)
        }
    }

    /**
     * Clears the in-progress recovery attempt registry.
     *
     * Call this when a new execution era begins (e.g. after an explicit stop/restart or
     * a session invalidation) so that keys from a prior era are not mistakenly treated
     * as in-progress in the new era.
     */
    fun clearRecoveryAttemptRegistry() {
        synchronized(_inProgressAttemptKeys) {
            _inProgressAttemptKeys.clear()
        }
    }

    /**
     * Returns the number of recovery attempts currently tracked as in-progress.
     *
     * Exposed for diagnostic and test inspection; callers MUST NOT use this count to
     * make recovery or suppression decisions.
     */
    val inProgressRecoveryCount: Int
        get() = synchronized(_inProgressAttemptKeys) { _inProgressAttemptKeys.size }

    // ── Post-resume emit gate ─────────────────────────────────────────────────

    /**
     * Returns `true` when the post-resume emit gate is currently closed (emission is
     * blocked) and the caller's signal or result MUST be suppressed.
     *
     * This is the canonical entry point for the [SEMANTIC_GATE_POST_RESUME_EMIT] guard.
     * Call this before every signal / result emission after a reconnect or resume event.
     * When `true`, suppress the emission and queue or discard the signal according to the
     * caller's policy.
     *
     * **Usage pattern with [AndroidContinuityIntegration.suppressDuplicateLocalEmit]:**
     * ```kotlin
     * // 1. Check cross-execution dedup (PR-3)
     * if (continuityIntegration.suppressDuplicateLocalEmit(signal.signalId)) return
     * // 2. Check post-resume gate (PR-4)
     * if (recoveryOwner.gatePostResumeEmit()) return
     * // 3. Mark seen and emit
     * continuityIntegration.markLocalEmitSeen(signal.signalId)
     * signalSink.onSignal(signal)
     * ```
     *
     * @return `true` when emission is currently gated (should suppress); `false` when open.
     */
    fun gatePostResumeEmit(): Boolean = _postResumeEmitGated

    /**
     * Opens the post-resume emit gate, allowing signal / result emission.
     *
     * Call this once the recovery sequence has confirmed that emission is safe — either
     * after a [LocalRecoveryDecision.ResumeLocalExecution] decision is acted upon, or
     * after V2 has authorised continuation following
     * [LocalRecoveryDecision.RehydrateThenContinue] or
     * [LocalRecoveryDecision.WaitForV2ReplayDecision].
     */
    fun openPostResumeEmitGate() {
        _postResumeEmitGated = false
    }

    /**
     * Closes the post-resume emit gate, blocking signal / result emission.
     *
     * Call this at the beginning of a reconnect / resume / recovery sequence before any
     * potentially re-dispatched or replayed signals could be emitted.  The gate should
     * remain closed until the recovery sequence completes and the decision is confirmed
     * by [openPostResumeEmitGate].
     */
    fun closePostResumeEmitGate() {
        _postResumeEmitGated = true
    }

    /**
     * `true` when the post-resume emit gate is currently closed (emission is blocked).
     *
     * Equivalent to calling [gatePostResumeEmit]; exposed as a property for diagnostic
     * and test inspection.
     */
    val isPostResumeEmitGated: Boolean
        get() = _postResumeEmitGated

    // ── Checkpoint construction ───────────────────────────────────────────────

    /**
     * Constructs a [RecoveryActivationCheckpoint] from a rehydrated
     * [LocalExecutionContextSnapshot].
     *
     * This is the canonical entry point for checkpoint-aware activation continuation.
     * The resulting checkpoint captures the execution phase, continuity token, and step
     * count from [snapshot] so that the recovery decision path can determine
     * [RecoveryActivationCheckpoint.isCheckpointResumable] and include it in the
     * [LocalRecoveryDecision].
     *
     * @param snapshot  The rehydrated [LocalExecutionContextSnapshot] to checkpoint from.
     * @param nowMs     Epoch-ms timestamp for the checkpoint; defaults to the current wall clock.
     * @return A [RecoveryActivationCheckpoint] derived from [snapshot].
     */
    fun buildRecoveryCheckpoint(
        snapshot: LocalExecutionContextSnapshot,
        nowMs: Long = System.currentTimeMillis()
    ): RecoveryActivationCheckpoint =
        RecoveryActivationCheckpoint.fromSnapshot(snapshot = snapshot, checkpointAtMs = nowMs)

    // ── Companion — constants ─────────────────────────────────────────────────

    companion object {

        // ── Recovery semantic constants ───────────────────────────────────────

        /**
         * Canonical wire value for the "resume local execution" recovery semantic.
         *
         * Android can resume local execution without waiting for V2.  Applies to receiver
         * / pipeline rebind scenarios where the session is intact and no execution was
         * interrupted.
         *
         * V2 recovery contract alignment: "receiver / pipeline rebind continuation".
         */
        const val SEMANTIC_RESUME_LOCAL_EXECUTION = "resume_local_execution"

        /**
         * Canonical wire value for the "rehydrate then continue" recovery semantic.
         *
         * Android has rehydrated prior execution context from the durable store and must
         * present it to V2 before continuing.  V2 authorises whether to restore, replay,
         * or start fresh.
         *
         * V2 recovery contract alignment: "continuity resume / process recreation rehydrate".
         */
        const val SEMANTIC_REHYDRATE_THEN_CONTINUE = "rehydrate_then_continue"

        /**
         * Canonical wire value for the "wait for V2 replay decision" recovery semantic.
         *
         * Android must hold all execution and emission pending a V2 replay / re-dispatch
         * decision.  Applies after a transport reconnect or process recreation without
         * recoverable local context.
         *
         * V2 recovery contract alignment: "replay / re-dispatch orchestration".
         */
        const val SEMANTIC_WAIT_FOR_V2_REPLAY_DECISION = "wait_for_v2_replay_decision"

        /**
         * Canonical wire value for the "suppress duplicate local recovery" semantic.
         *
         * A recovery attempt for the same key is already in progress.  The current
         * attempt must be dropped to avoid concurrent duplicate recovery sequences.
         *
         * V2 recovery contract alignment: "duplicate recovery suppression".
         */
        const val SEMANTIC_SUPPRESS_DUPLICATE_LOCAL_RECOVERY = "suppress_duplicate_local_recovery"

        /**
         * Canonical wire value for the "gate post-resume emit" recovery semantic.
         *
         * Signal / result emission is blocked while a recovery sequence is in progress.
         * Callers check [gatePostResumeEmit] before emitting and suppress the emission
         * when the gate is closed.
         *
         * V2 recovery contract alignment: "post-resume emit gating".
         */
        const val SEMANTIC_GATE_POST_RESUME_EMIT = "gate_post_resume_emit"

        // ── Integration point constants ───────────────────────────────────────

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedRuntimeReceiver].
         *
         * The recovery owner gates new receipt acceptance until the recovery decision is
         * resolved.  [LocalRecoveryDecision.ResumeLocalExecution] allows the receiver to
         * rebind immediately; [LocalRecoveryDecision.WaitForV2ReplayDecision] defers receipt.
         */
        const val INTEGRATION_RECEIVER = "DelegatedRuntimeReceiver"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedRuntimeUnit].
         *
         * A unit's session ID should be validated against the recovery checkpoint's
         * [RecoveryActivationCheckpoint.attachedSessionId] before proceeding.
         */
        const val INTEGRATION_UNIT = "DelegatedRuntimeUnit"

        /**
         * Integration point identifier for [com.ufo.galaxy.runtime.DelegatedActivationRecord].
         *
         * New activation records are produced only after the recovery decision permits
         * entry to the execution pipeline.
         */
        const val INTEGRATION_ACTIVATION_RECORD = "DelegatedActivationRecord"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.AutonomousExecutionPipeline].
         *
         * Pipeline entry is gated by the recovery decision kind.
         * [LocalRecoveryDecision.WaitForV2ReplayDecision] must not enter the pipeline
         * until V2 issues a new dispatch.
         */
        const val INTEGRATION_PIPELINE = "AutonomousExecutionPipeline"

        /**
         * Integration point identifier for [com.ufo.galaxy.loop.LoopController].
         *
         * Loop entry is gated by the recovery decision kind.  A
         * [LocalRecoveryDecision.WaitForV2ReplayDecision] decision indicates that the loop
         * was interrupted and V2 must decide whether to replay.
         */
        const val INTEGRATION_LOOP_CONTROLLER = "LoopController"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedTakeoverExecutor].
         *
         * The takeover executor must check [gatePostResumeEmit] before each signal
         * emission to prevent duplicate ACK / PROGRESS / RESULT signals after a reconnect
         * or resume event.
         */
        const val INTEGRATION_TAKEOVER_EXECUTOR = "DelegatedTakeoverExecutor"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.LocalCollaborationAgent].
         *
         * Subtask dispatch is gated by the recovery decision kind.  A
         * [LocalRecoveryDecision.WaitForV2ReplayDecision] decision indicates that the
         * collaboration context was interrupted and V2 re-coordination is needed before
         * any subtask can be re-dispatched.
         */
        const val INTEGRATION_COLLABORATION_AGENT = "LocalCollaborationAgent"

        // ── Reason constants ──────────────────────────────────────────────────

        /**
         * Human-readable reason used in [LocalRecoveryDecision.WaitForV2ReplayDecision]
         * when a transport reconnect requires V2 to drive the replay / re-dispatch decision.
         */
        const val REASON_TRANSPORT_RECONNECT_AWAITING_V2 =
            "transport_reconnect_awaiting_v2_replay_decision"

        /**
         * Human-readable reason used in [LocalRecoveryDecision.WaitForV2ReplayDecision]
         * when a process recreation re-attach has no recoverable local execution context.
         */
        const val REASON_PROCESS_RECREATION_NO_LOCAL_CONTEXT =
            "process_recreation_no_local_context_available"

        // ── PR designation ────────────────────────────────────────────────────

        /**
         * The PR designation for this recovery participation owner.
         */
        const val INTRODUCED_PR = 4

        /**
         * Human-readable description of this component's role.
         */
        const val DESCRIPTION =
            "Android-side recovery participation owner for replay / resume / " +
                "re-dispatch orchestration in delegated runtime flows."
    }
}

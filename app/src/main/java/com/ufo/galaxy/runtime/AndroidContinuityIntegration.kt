package com.ufo.galaxy.runtime

/**
 * PR-3 (Android) — Android-side continuity integration skeleton for delegated runtime
 * attach / re-attach / resume decision making.
 *
 * [AndroidContinuityIntegration] is the unified Android-side entry point for all
 * delegated runtime continuity decisions.  It closes the gap where continuity behaviour
 * was previously scattered across attached session restoration, local execution context,
 * receiver rebind, loop recovery, and takeover recovery paths.
 *
 * ## Design intent
 *
 * Before PR-3 Android was a passive receiver: it executed whatever the V2 dispatcher
 * sent, with continuity checks spread across:
 *  - [com.ufo.galaxy.runtime.RuntimeController] — session-level attach / detach
 *  - [com.ufo.galaxy.agent.DelegatedRuntimeReceiver] — receipt gate
 *  - [com.ufo.galaxy.runtime.DelegatedFlowContinuityRecord] — durable persistence (PR-2)
 *  - [ContinuityRecoveryContext] — recovery-participant vocabulary (PR-F)
 *
 * [AndroidContinuityIntegration] unifies these dispersed check points into a single
 * **continuity-aware coordination layer** that:
 *
 *  1. **Evaluates the attach intent** for every attach/re-attach event, classifying it
 *     as a [AttachIntentKind] and producing a [ContinuityDecision] (fresh_attach vs.
 *     continuity_resume vs. identity-rejected etc.).
 *  2. **Rehydrates the local execution context** from the durable
 *     [DelegatedFlowContinuityRecord] when prior state is available, producing a
 *     [LocalExecutionContextSnapshot] that can be forwarded to V2 on re-attach.
 *  3. **Validates runtime identity** at receipt time, rejecting envelopes whose
 *     attached session identifier does not match the current active session
 *     (stale-identity guard).
 *  4. **Suppresses duplicate local signal/result emission** via a lightweight in-memory
 *     seen-set keyed on [com.ufo.galaxy.runtime.DelegatedExecutionSignal.signalId].
 *     This complements the per-execution [EmittedSignalLedger] (PR-18) with a
 *     cross-execution idempotency guard for reconnect / rebind scenarios.
 *
 * ## Integration points
 *
 * [AndroidContinuityIntegration] does not own execution — it is a decision-making and
 * context-resolution component.  Callers must wire it into the following components:
 *
 * | Integration point                | How to integrate                                             |
 * |----------------------------------|--------------------------------------------------------------|
 * | [INTEGRATION_RECEIVER]           | Call [evaluateAttachIntent] before [com.ufo.galaxy.agent.DelegatedRuntimeReceiver.receive]. |
 * | [INTEGRATION_UNIT]               | Pass the session from [ContinuityDecision] to the receiver.  |
 * | [INTEGRATION_ACTIVATION_RECORD]  | Use [validateRuntimeIdentity] when checking inbound unit identity. |
 * | [INTEGRATION_PIPELINE]           | Check [ContinuityDecision.kind] before entering the pipeline; skip fresh pipeline init for CONTINUITY_RESUME. |
 * | [INTEGRATION_LOOP_CONTROLLER]    | Check [ContinuityDecision.kind] before entering the loop; propagate recovered context. |
 * | [INTEGRATION_TAKEOVER_EXECUTOR]  | Use [suppressDuplicateLocalEmit] after every signal emission.  |
 * | [INTEGRATION_COLLABORATION_AGENT]| Check [ContinuityDecision.kind] before subtask dispatch.      |
 *
 * ## Android-side continuity semantics
 *
 * The five canonical Android-side continuity semantics established by this class:
 *
 * | Semantic                        | Constant                            | Description                           |
 * |---------------------------------|-------------------------------------|---------------------------------------|
 * | Fresh attach                    | [SEMANTIC_FRESH_ATTACH]             | No prior continuity context; Android starts a new execution era. |
 * | Continuity resume               | [SEMANTIC_CONTINUITY_RESUME]        | Prior continuity context available; Android presents it to V2 for coordination. |
 * | Rehydrate local context         | [SEMANTIC_REHYDRATE_LOCAL_CONTEXT]  | Android loads persisted [DelegatedFlowContinuityRecord] into a [LocalExecutionContextSnapshot]. |
 * | Reject stale runtime identity   | [SEMANTIC_REJECT_STALE_IDENTITY]    | Inbound unit carries a session ID that does not match the current active session. |
 * | Suppress duplicate local emit   | [SEMANTIC_SUPPRESS_DUPLICATE_EMIT]  | Signal/result already emitted in this era; suppress re-emission. |
 *
 * ## V2 continuity contract alignment
 *
 * The [AttachIntentKind] enum values are named to align with the V2 continuity contract
 * scenario vocabulary:
 *  - [AttachIntentKind.FRESH_ATTACH] → V2 "fresh attach"
 *  - [AttachIntentKind.CONTINUITY_RESUME] → V2 "transport reconnect" or "continuity resume"
 *  - [AttachIntentKind.PROCESS_RECREATION_REATTACH] → V2 "process recreation re-attach"
 *  - [AttachIntentKind.TRANSPORT_RECONNECT] → V2 "transport reconnect"
 *  - [AttachIntentKind.RECEIVER_PIPELINE_REBIND] → V2 "receiver / pipeline rebind"
 *
 * ## Extension points
 *
 * The following extension seams are reserved for future replay / resume / truth alignment
 * / operator diagnostics work:
 *  - [evaluateAttachIntent] may be extended to return richer replay intent metadata.
 *  - [rehydrateLocalContext] is the seam for injecting a live persistence store reference.
 *  - [suppressDuplicateLocalEmit] may be backed by a durable store in a future PR to
 *    survive process recreation.
 *
 * ## Thread safety
 *
 * [suppressDuplicateLocalEmit] and [markLocalEmitSeen] use a [synchronized] block on the
 * internal seen-set.  All other methods are pure functions with no shared mutable state.
 */
class AndroidContinuityIntegration {

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * In-memory seen-set of signal IDs that have been emitted in the current era.
     *
     * Keyed on [com.ufo.galaxy.runtime.DelegatedExecutionSignal.signalId] (UUID string).
     * This set is process-scoped: it is cleared on process recreation.  A future PR may
     * back it with [DelegatedFlowContinuityRecord] to survive process kills.
     */
    private val _emittedSignalIds: MutableSet<String> = mutableSetOf()

    // ── AttachIntentKind ──────────────────────────────────────────────────────

    /**
     * Classification of an attach / re-attach intent evaluated by [evaluateAttachIntent].
     *
     * Each variant corresponds to a V2 continuity contract scenario.  Callers must handle
     * all variants; future V2 versions may introduce additional kinds.
     *
     * @property wireValue Stable lowercase string for wire transmission and diagnostics.
     */
    enum class AttachIntentKind(val wireValue: String) {

        /**
         * No prior continuity context is available.  Android is attaching for the first
         * time, or after an explicit session invalidation that cleared prior state.
         *
         * Android-side semantic: [SEMANTIC_FRESH_ATTACH].
         *
         * V2 contract alignment: "fresh attach".
         */
        FRESH_ATTACH("fresh_attach"),

        /**
         * A persisted [DelegatedFlowContinuityRecord] is available for the current
         * durable session, indicating that Android has prior execution context to
         * present to V2 for session / flow resumption decisions.
         *
         * Android-side semantic: [SEMANTIC_CONTINUITY_RESUME].
         *
         * V2 contract alignment: "continuity resume".
         */
        CONTINUITY_RESUME("continuity_resume"),

        /**
         * Android is re-attaching after a process kill / recreation.  A
         * [ProcessRecreatedReattachHint] carrying the prior durable session ID is
         * available.  Prior in-memory state (including live [DelegatedExecutionTracker])
         * is gone; only the persisted [DelegatedFlowContinuityRecord] may survive.
         *
         * Android-side semantic: [SEMANTIC_REHYDRATE_LOCAL_CONTEXT].
         *
         * V2 contract alignment: "process recreation re-attach".
         */
        PROCESS_RECREATION_REATTACH("process_recreation_reattach"),

        /**
         * Android is reconnecting after a transport disruption (WebSocket reconnect)
         * within the same durable session era.  The [DurableSessionContinuityRecord]
         * is intact in memory; no process recreation occurred.
         *
         * V2 contract alignment: "transport reconnect".
         */
        TRANSPORT_RECONNECT("transport_reconnect"),

        /**
         * A receiver or pipeline component (e.g. [com.ufo.galaxy.agent.DelegatedRuntimeReceiver],
         * [com.ufo.galaxy.agent.AutonomousExecutionPipeline]) is being re-bound to the
         * current session after a configuration change, service restart, or rebind event.
         * The in-memory session state is intact; only the receiver binding is being restored.
         *
         * V2 contract alignment: "receiver / pipeline rebind".
         */
        RECEIVER_PIPELINE_REBIND("receiver_pipeline_rebind")
    }

    // ── ContinuityDecision ────────────────────────────────────────────────────

    /**
     * Decision produced by [evaluateAttachIntent].
     *
     * Callers must pattern-match on the concrete subtype to determine the correct
     * attach / re-attach action.
     */
    sealed class ContinuityDecision {

        /** The [AttachIntentKind] classification of this decision. */
        abstract val kind: AttachIntentKind

        /**
         * Android is performing a fresh first-time attach.  No prior continuity context
         * is available; the runtime should initialise a new execution era.
         *
         * Android-side semantic: [SEMANTIC_FRESH_ATTACH].
         */
        object FreshAttach : ContinuityDecision() {
            override val kind: AttachIntentKind = AttachIntentKind.FRESH_ATTACH
        }

        /**
         * Android is resuming with prior continuity context.  A
         * [LocalExecutionContextSnapshot] is available and SHOULD be forwarded to V2 on
         * re-attach so V2 can decide whether to restore, replay, or start fresh.
         *
         * Android-side semantic: [SEMANTIC_CONTINUITY_RESUME].
         *
         * @property localContext The rehydrated local execution context snapshot.
         */
        data class ContinuityResume(
            val localContext: LocalExecutionContextSnapshot
        ) : ContinuityDecision() {
            override val kind: AttachIntentKind = AttachIntentKind.CONTINUITY_RESUME
        }

        /**
         * Android is re-attaching after process recreation.  A
         * [ProcessRecreatedReattachHint] SHOULD be included in the `DeviceConnected`
         * event metadata.  A [LocalExecutionContextSnapshot] from the persisted
         * [DelegatedFlowContinuityRecord] MAY be available.
         *
         * Android-side semantic: [SEMANTIC_REHYDRATE_LOCAL_CONTEXT].
         *
         * @property reattachHint        The prior-session hint to forward to V2.
         * @property localContext        The rehydrated local execution context snapshot;
         *                               null when no prior flow record was persisted.
         */
        data class ProcessRecreationReattach(
            val reattachHint: ProcessRecreatedReattachHint,
            val localContext: LocalExecutionContextSnapshot?
        ) : ContinuityDecision() {
            override val kind: AttachIntentKind = AttachIntentKind.PROCESS_RECREATION_REATTACH
        }

        /**
         * Android is reconnecting within the same durable session era after a transport
         * disruption.  The in-memory [DurableSessionContinuityRecord] is intact.
         *
         * @property durableSession The current durable session continuity record.
         */
        data class TransportReconnect(
            val durableSession: DurableSessionContinuityRecord
        ) : ContinuityDecision() {
            override val kind: AttachIntentKind = AttachIntentKind.TRANSPORT_RECONNECT
        }

        /**
         * A receiver or pipeline component is being re-bound.  The current session is
         * intact; only the component binding is being restored.
         *
         * @property activeSessionId The [AttachedRuntimeSession.sessionId] of the
         *                           currently active session.
         */
        data class ReceiverPipelineRebind(
            val activeSessionId: String
        ) : ContinuityDecision() {
            override val kind: AttachIntentKind = AttachIntentKind.RECEIVER_PIPELINE_REBIND
        }

        /**
         * Inbound unit carries a session ID that does not match the current active
         * session.  Android MUST NOT activate the unit.
         *
         * Android-side semantic: [SEMANTIC_REJECT_STALE_IDENTITY].
         *
         * @property expectedSessionId The session ID currently active on Android.
         * @property receivedSessionId The session ID carried by the inbound unit.
         * @property reason            Human-readable description of the mismatch.
         */
        data class RejectStaleIdentity(
            val expectedSessionId: String,
            val receivedSessionId: String,
            val reason: String = REASON_STALE_IDENTITY
        ) : ContinuityDecision() {
            override val kind: AttachIntentKind = AttachIntentKind.FRESH_ATTACH
        }
    }

    // ── IdentityValidationResult ──────────────────────────────────────────────

    /**
     * Result of a [validateRuntimeIdentity] call.
     */
    sealed class IdentityValidationResult {

        /** The runtime identity is valid for the current session. */
        object Valid : IdentityValidationResult()

        /**
         * The runtime identity is stale: the inbound session ID does not match the
         * current active session.
         *
         * @property expectedSessionId The session ID currently active on Android.
         * @property receivedSessionId The session ID in the inbound envelope / unit.
         */
        data class StaleIdentity(
            val expectedSessionId: String,
            val receivedSessionId: String
        ) : IdentityValidationResult()

        /**
         * No active session is present; the runtime identity cannot be validated.
         */
        object NoActiveSession : IdentityValidationResult()
    }

    // ── Core API ──────────────────────────────────────────────────────────────

    /**
     * Evaluates the attach intent for an attach or re-attach event and returns a
     * [ContinuityDecision] classifying the scenario.
     *
     * ## Decision logic
     *
     * 1. If [processRecreatedHint] is non-null → [ContinuityDecision.ProcessRecreationReattach];
     *    includes a [LocalExecutionContextSnapshot] if [priorFlowRecord] is also non-null.
     * 2. Else if [durableSession] is non-null and its session epoch > 0 (i.e. at least one
     *    prior reconnect) → [ContinuityDecision.TransportReconnect].
     * 3. Else if [priorFlowRecord] is non-null → [ContinuityDecision.ContinuityResume];
     *    includes the rehydrated [LocalExecutionContextSnapshot].
     * 4. Else if [activeSession] is non-null but no other continuity context → marks this as
     *    a [ContinuityDecision.ReceiverPipelineRebind] (session exists, component re-binding).
     * 5. Otherwise → [ContinuityDecision.FreshAttach].
     *
     * @param activeSession         The current [AttachedRuntimeSession]; null when no session
     *                              is active.
     * @param durableSession        The current [DurableSessionContinuityRecord]; null when not
     *                              yet established.
     * @param priorFlowRecord       The persisted [DelegatedFlowContinuityRecord] from the prior
     *                              activation era; null when not available.
     * @param processRecreatedHint  The [ProcessRecreatedReattachHint] present when Android is
     *                              re-attaching after a process kill; null otherwise.
     * @param nowMs                 Epoch-ms reference time for rehydration timestamps; defaults
     *                              to the current wall clock.
     * @return The [ContinuityDecision] classifying the current attach scenario.
     */
    fun evaluateAttachIntent(
        activeSession: AttachedRuntimeSession?,
        durableSession: DurableSessionContinuityRecord?,
        priorFlowRecord: DelegatedFlowContinuityRecord?,
        processRecreatedHint: ProcessRecreatedReattachHint?,
        nowMs: Long = System.currentTimeMillis()
    ): ContinuityDecision {

        // ── 1. Process recreation re-attach ───────────────────────────────────
        if (processRecreatedHint != null) {
            val localContext = priorFlowRecord?.let {
                LocalExecutionContextSnapshot.fromContinuityRecord(it, rehydratedAtMs = nowMs)
            }
            return ContinuityDecision.ProcessRecreationReattach(
                reattachHint = processRecreatedHint,
                localContext = localContext
            )
        }

        // ── 2. Transport reconnect within the same era ─────────────────────────
        if (durableSession != null && durableSession.sessionContinuityEpoch > 0) {
            return ContinuityDecision.TransportReconnect(durableSession = durableSession)
        }

        // ── 3. Continuity resume — prior flow record available ────────────────
        if (priorFlowRecord != null) {
            val localContext = LocalExecutionContextSnapshot.fromContinuityRecord(
                record = priorFlowRecord,
                rehydratedAtMs = nowMs
            )
            return ContinuityDecision.ContinuityResume(localContext = localContext)
        }

        // ── 4. Receiver / pipeline rebind — session intact, no flow record ────
        if (activeSession != null && activeSession.state == AttachedRuntimeSession.State.ATTACHED) {
            return ContinuityDecision.ReceiverPipelineRebind(
                activeSessionId = activeSession.sessionId
            )
        }

        // ── 5. Fresh attach — no continuity context available ─────────────────
        return ContinuityDecision.FreshAttach
    }

    /**
     * Rehydrates the local execution context from a persisted [DelegatedFlowContinuityRecord].
     *
     * This is the canonical entry point for the [SEMANTIC_REHYDRATE_LOCAL_CONTEXT] operation.
     * The returned [LocalExecutionContextSnapshot] carries all four continuity dimensions
     * (flow identity, session continuity, execution context, execution history) as recovered
     * from the durable store.
     *
     * The snapshot MUST be forwarded to V2 on re-attach so V2 can decide whether to restore,
     * replay, or start fresh.  Android MUST NOT use the snapshot to self-authorise flow
     * resumption.
     *
     * @param record    The persisted [DelegatedFlowContinuityRecord] to rehydrate from.
     * @param nowMs     Epoch-ms rehydration timestamp; defaults to the current wall clock.
     * @return A [LocalExecutionContextSnapshot] populated from [record].
     */
    fun rehydrateLocalContext(
        record: DelegatedFlowContinuityRecord,
        nowMs: Long = System.currentTimeMillis()
    ): LocalExecutionContextSnapshot =
        LocalExecutionContextSnapshot.fromContinuityRecord(record = record, rehydratedAtMs = nowMs)

    /**
     * Validates the runtime identity of an inbound [com.ufo.galaxy.agent.DelegatedRuntimeUnit]
     * against the current active session.
     *
     * This is the canonical entry point for the [SEMANTIC_REJECT_STALE_IDENTITY] check.  A
     * unit whose [com.ufo.galaxy.agent.DelegatedRuntimeUnit.attachedSessionId] does not match
     * the current [AttachedRuntimeSession.sessionId] MUST be rejected before execution begins.
     *
     * ## Decision logic
     *
     * 1. If [activeSession] is null → [IdentityValidationResult.NoActiveSession].
     * 2. If [unitAttachedSessionId] matches [activeSession.sessionId] →
     *    [IdentityValidationResult.Valid].
     * 3. Otherwise → [IdentityValidationResult.StaleIdentity].
     *
     * @param unitAttachedSessionId  The [com.ufo.galaxy.agent.DelegatedRuntimeUnit.attachedSessionId]
     *                               of the unit being validated.
     * @param activeSession          The current [AttachedRuntimeSession]; null when no session
     *                               is active.
     * @return [IdentityValidationResult] indicating whether the identity is valid, stale, or
     *         unverifiable.
     */
    fun validateRuntimeIdentity(
        unitAttachedSessionId: String,
        activeSession: AttachedRuntimeSession?
    ): IdentityValidationResult {
        if (activeSession == null) {
            return IdentityValidationResult.NoActiveSession
        }
        return if (unitAttachedSessionId == activeSession.sessionId) {
            IdentityValidationResult.Valid
        } else {
            IdentityValidationResult.StaleIdentity(
                expectedSessionId = activeSession.sessionId,
                receivedSessionId = unitAttachedSessionId
            )
        }
    }

    /**
     * Returns `true` when the signal identified by [signalId] has already been seen in
     * this era and SHOULD be suppressed.
     *
     * This is the canonical entry point for the [SEMANTIC_SUPPRESS_DUPLICATE_EMIT] guard.
     * It complements the per-execution [EmittedSignalLedger] with a cross-execution
     * idempotency guard for reconnect / rebind scenarios where the same signal might be
     * emitted more than once (e.g. after a transport reconnect where a previous send was
     * not confirmed).
     *
     * **Usage pattern:**
     * ```kotlin
     * if (continuityIntegration.suppressDuplicateLocalEmit(signal.signalId)) {
     *     // suppress — already emitted in this era
     * } else {
     *     continuityIntegration.markLocalEmitSeen(signal.signalId)
     *     signalSink.onSignal(signal)
     * }
     * ```
     *
     * @param signalId The [com.ufo.galaxy.runtime.DelegatedExecutionSignal.signalId] to check.
     * @return `true` when [signalId] is already in the seen-set (suppress); `false` otherwise.
     */
    fun suppressDuplicateLocalEmit(signalId: String): Boolean {
        synchronized(_emittedSignalIds) {
            return _emittedSignalIds.contains(signalId)
        }
    }

    /**
     * Records [signalId] as seen in the local emit seen-set.
     *
     * Call this immediately **before** (or immediately after) emitting a signal via the
     * [com.ufo.galaxy.runtime.DelegatedExecutionSignalSink] so that subsequent
     * [suppressDuplicateLocalEmit] calls for the same [signalId] return `true`.
     *
     * @param signalId The [com.ufo.galaxy.runtime.DelegatedExecutionSignal.signalId] to record.
     */
    fun markLocalEmitSeen(signalId: String) {
        synchronized(_emittedSignalIds) {
            _emittedSignalIds.add(signalId)
        }
    }

    /**
     * Clears the local emit seen-set.
     *
     * Call this when a new execution era begins (e.g. after a session invalidation or an
     * explicit stop/restart) so that signal IDs from a prior era are not mistakenly
     * suppressed in the new era.
     */
    fun clearEmitSeenSet() {
        synchronized(_emittedSignalIds) {
            _emittedSignalIds.clear()
        }
    }

    /**
     * Returns the number of signal IDs currently tracked in the local emit seen-set.
     *
     * Exposed for diagnostic and test inspection; callers MUST NOT use this count to make
     * continuity or suppression decisions.
     */
    val emitSeenCount: Int
        get() = synchronized(_emittedSignalIds) { _emittedSignalIds.size }

    // ── Companion — constants ─────────────────────────────────────────────────

    companion object {

        // ── Android-side continuity semantic constants ────────────────────────

        /**
         * Canonical wire value for the "fresh attach" semantic.
         *
         * Android is attaching for the first time (or after a clean state reset).
         * No prior continuity context is available.  A new execution era begins.
         *
         * V2 contract alignment: "fresh attach".
         */
        const val SEMANTIC_FRESH_ATTACH = "fresh_attach"

        /**
         * Canonical wire value for the "continuity resume" semantic.
         *
         * Android is re-attaching with prior continuity context available.  The
         * [LocalExecutionContextSnapshot] is forwarded to V2, which decides whether
         * to restore, replay, or start fresh.
         *
         * V2 contract alignment: "continuity resume".
         */
        const val SEMANTIC_CONTINUITY_RESUME = "continuity_resume"

        /**
         * Canonical wire value for the "rehydrate local context" semantic.
         *
         * Android loads the persisted [DelegatedFlowContinuityRecord] and converts it
         * to a [LocalExecutionContextSnapshot] for V2 correlation on re-attach.
         *
         * V2 contract alignment: "process recreation re-attach" / "local context rehydrate".
         */
        const val SEMANTIC_REHYDRATE_LOCAL_CONTEXT = "rehydrate_local_context"

        /**
         * Canonical wire value for the "reject stale runtime identity" semantic.
         *
         * Inbound unit or envelope carries a session ID that does not match the current
         * active session.  Android MUST NOT activate the unit.
         *
         * V2 contract alignment: "stale identity" rejection.
         */
        const val SEMANTIC_REJECT_STALE_IDENTITY = "reject_stale_runtime_identity"

        /**
         * Canonical wire value for the "suppress duplicate local emit" semantic.
         *
         * Signal or result has already been emitted in this era; suppress re-emission to
         * preserve host-side idempotency.
         *
         * V2 contract alignment: "duplicate signal" suppression.
         */
        const val SEMANTIC_SUPPRESS_DUPLICATE_EMIT = "suppress_duplicate_local_emit"

        // ── Integration point constants ───────────────────────────────────────

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedRuntimeReceiver].
         *
         * Callers should invoke [evaluateAttachIntent] before
         * [com.ufo.galaxy.agent.DelegatedRuntimeReceiver.receive] to determine the attach
         * scenario.
         */
        const val INTEGRATION_RECEIVER = "DelegatedRuntimeReceiver"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedRuntimeUnit].
         *
         * Callers should invoke [validateRuntimeIdentity] using the unit's
         * [com.ufo.galaxy.agent.DelegatedRuntimeUnit.attachedSessionId] to detect stale units.
         */
        const val INTEGRATION_UNIT = "DelegatedRuntimeUnit"

        /**
         * Integration point identifier for [com.ufo.galaxy.runtime.DelegatedActivationRecord].
         *
         * The activation record is produced after [validateRuntimeIdentity] passes; its
         * [com.ufo.galaxy.runtime.DelegatedActivationRecord.attachedSessionId] should be
         * verified to match the current active session.
         */
        const val INTEGRATION_ACTIVATION_RECORD = "DelegatedActivationRecord"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.AutonomousExecutionPipeline].
         *
         * Callers should check [ContinuityDecision.kind] before entering the pipeline.
         * A [AttachIntentKind.CONTINUITY_RESUME] decision indicates that prior partial
         * results may be available in V2 for alignment.
         */
        const val INTEGRATION_PIPELINE = "AutonomousExecutionPipeline"

        /**
         * Integration point identifier for [com.ufo.galaxy.loop.LoopController].
         *
         * Callers should check [ContinuityDecision.kind] before entering the loop.
         * A [AttachIntentKind.PROCESS_RECREATION_REATTACH] or [AttachIntentKind.TRANSPORT_RECONNECT]
         * decision indicates that loop state was interrupted; V2 decides whether to resume.
         */
        const val INTEGRATION_LOOP_CONTROLLER = "LoopController"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedTakeoverExecutor].
         *
         * Callers should invoke [suppressDuplicateLocalEmit] before each
         * [com.ufo.galaxy.runtime.DelegatedExecutionSignalSink.onSignal] call to prevent
         * duplicate ACK / PROGRESS / RESULT signals after a transport reconnect.
         */
        const val INTEGRATION_TAKEOVER_EXECUTOR = "DelegatedTakeoverExecutor"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.LocalCollaborationAgent].
         *
         * Callers should check [ContinuityDecision.kind] before dispatching parallel
         * subtasks.  A [AttachIntentKind.TRANSPORT_RECONNECT] or
         * [AttachIntentKind.RECEIVER_PIPELINE_REBIND] decision may indicate that the
         * subtask group needs V2 re-coordination rather than local restart.
         */
        const val INTEGRATION_COLLABORATION_AGENT = "LocalCollaborationAgent"

        // ── Rejection reason constants ────────────────────────────────────────

        /**
         * Human-readable rejection reason used in [ContinuityDecision.RejectStaleIdentity]
         * when the inbound unit's session ID does not match the current active session.
         */
        const val REASON_STALE_IDENTITY = "stale_runtime_identity"

        // ── PR designation ────────────────────────────────────────────────────

        /**
         * The PR designation for this continuity integration skeleton.
         */
        const val INTRODUCED_PR = 3

        /**
         * Human-readable description of this component's role.
         */
        const val DESCRIPTION =
            "Android-side continuity integration skeleton for delegated runtime " +
                "attach / re-attach / resume decision making."
    }
}

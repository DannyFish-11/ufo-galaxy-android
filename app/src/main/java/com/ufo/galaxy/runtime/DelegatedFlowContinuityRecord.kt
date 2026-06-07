package com.ufo.galaxy.runtime

/**
 * PR-2 (Android) — Minimal durable serialisable record for delegated flow continuity.
 *
 * [DelegatedFlowContinuityRecord] captures the smallest stable set of local metadata
 * that Android needs to reconstruct delegated flow continuity context after a process
 * kill, app restart, or reconnect.  It is the Android-side durable counterpart to the
 * in-memory [DelegatedExecutionTracker] + [DurableSessionContinuityRecord] combination
 * and is designed to survive process recreation without depending on V2 to re-deliver
 * the full execution envelope.
 *
 * ## Purpose
 *
 * Before PR-2 Android held all in-flight delegated flow state in process-scoped memory:
 *
 *  - [DurableSessionContinuityRecord] — in-memory only (DURABLE_IN_MEMORY tier); lost on
 *    process kill.
 *  - [DelegatedActivationRecord] / [DelegatedExecutionTracker] — in-memory only; lost on
 *    process kill.
 *  - Only [com.ufo.galaxy.data.AppSettings.lastDurableSessionId] survived (thin session-id hint).
 *
 * This meant that after a process kill Android could tell V2 "I was here before" (via
 * [ProcessRecreatedReattachHint]) but had no local record of *which* flow it was
 * executing, at which phase, or what continuity tokens were in play.  V2 could not
 * reliably resume the flow on the same Android device because Android itself had
 * forgotten the relevant context.
 *
 * [DelegatedFlowContinuityRecord] closes that gap by providing a **durable, file-backed**
 * snapshot of the four continuity dimensions identified in the PR-2 problem statement:
 *
 * 1. **Attached session continuity metadata** — [durableSessionId], [attachedSessionId].
 * 2. **Delegated flow identity and lineage** — [flowId], [flowLineageId], [taskId],
 *    [traceId].
 * 3. **Activation / execution continuity context** — [executionPhase],
 *    [continuityToken], [continuationToken], [activatedAtMs], [executionStartedAtMs].
 * 4. **Local execution history** — [stepCount], [lastStepAtMs].
 *
 * ## Durability tier
 *
 * [DelegatedFlowContinuityRecord] is stored in
 * [com.ufo.galaxy.history.DelegatedFlowContinuityStore], a SharedPreferences-backed
 * store that survives process kill, device restart, and app update.  This elevates the
 * fields from the DURABLE_IN_MEMORY tier to the SETTINGS_PERSISTED tier described in
 * [ParticipantRecoveryReadinessSnapshot.DurabilityTier].
 *
 * ## V2 authority boundary
 *
 * This record is an **advisory persistence aid**, not a canonical source of truth.
 * V2 remains the authoritative session and flow coordinator:
 *
 *  - Android uses this record to recognise its own prior execution context when
 *    re-attaching after process recreation.
 *  - Android MUST NOT self-authorise session or flow continuation based solely on this
 *    record.  V2 decides whether to restore, replay, or start fresh.
 *  - [continuityToken] is V2-managed; Android echoes it back from the inbound envelope
 *    and persists it so that the re-attach event can include it for V2 correlation.
 *    Android MUST NOT generate or mutate the continuity token.
 *
 * ## Serialisation
 *
 * All fields are JVM primitives or nullable strings so that Gson can round-trip
 * instances without a custom adapter (same contract as
 * [com.ufo.galaxy.history.SessionHistorySummary]).
 *
 * ## Relationship to other continuity surfaces
 *
 * | Surface | Survives process kill? | Owner |
 * |---|---|---|
 * | [DurableSessionContinuityRecord] | ❌ DURABLE_IN_MEMORY | Android |
 * | [com.ufo.galaxy.data.AppSettings.lastDurableSessionId] | ✅ SETTINGS_PERSISTED | Android |
 * | [ProcessRecreatedReattachHint] | ✅ (reads from AppSettings) | Android |
 * | [DelegatedFlowContinuityRecord] (this class) | ✅ SETTINGS_PERSISTED | Android |
 * | V2 session / task / flow snapshot | ✅ V2-side | V2 coordinator |
 *
 * @property flowId               Stable identifier for the V2 canonical delegated flow
 *                                entity this record belongs to.  Sourced from
 *                                [com.ufo.galaxy.agent.DelegatedRuntimeUnit.delegatedFlowId];
 *                                falls back to [unitId] for legacy senders.
 * @property flowLineageId        Lineage identity shared by all Android-side flows in
 *                                the same V2 canonical flow family.  Sourced from
 *                                [com.ufo.galaxy.agent.DelegatedRuntimeUnit.flowLineageId];
 *                                falls back to [taskId] for legacy senders.
 * @property unitId               Stable delegated-unit identifier echoed from
 *                                [com.ufo.galaxy.agent.DelegatedRuntimeUnit.unitId].
 * @property taskId               Task identifier echoed from
 *                                [com.ufo.galaxy.agent.DelegatedRuntimeUnit.taskId].
 * @property traceId              End-to-end observability identifier propagated from
 *                                the originating request.
 * @property durableSessionId     The [DurableSessionContinuityRecord.durableSessionId]
 *                                that was active when this flow was activated.  Provides
 *                                the session-era anchor for V2 correlation.
 * @property attachedSessionId    The [com.ufo.galaxy.runtime.AttachedRuntimeSession.sessionId]
 *                                under which this flow was activated.
 * @property executionPhase       Wire value of [AndroidFlowExecutionPhase] at the time
 *                                this record was last written.  Allows Android (and V2) to
 *                                understand at which execution stage the process died.
 * @property continuityToken      V2-managed durable execution continuity token echoed
 *                                from the inbound envelope; null when not provided.
 *                                Android MUST NOT generate or modify this value.
 * @property continuationToken    Executor-managed delegated handoff continuation state
 *                                token; null when the dispatch was a pure reconnect
 *                                recovery (not a handoff).
 * @property activatedAtMs        Epoch-millisecond timestamp when Android accepted this
 *                                delegated unit for local activation.
 * @property executionStartedAtMs Epoch-millisecond timestamp when local execution began;
 *                                null when the process was killed before execution started.
 * @property stepCount            Number of execution steps completed at the time of last
 *                                persistence; zero before execution begins.
 * @property lastStepAtMs         Epoch-millisecond timestamp of the most recently recorded
 *                                step; null when no steps have been completed.
 * @property savedAtMs            Wall-clock epoch-millisecond time when this record was
 *                                written to the store.  Used for TTL eviction.
 */
data class DelegatedFlowContinuityRecord(
    // ── Flow identity / lineage ───────────────────────────────────────────────
    val flowId: String,
    val flowLineageId: String,
    val unitId: String,
    val taskId: String,
    val traceId: String,

    // ── Session continuity ────────────────────────────────────────────────────
    val durableSessionId: String,
    val attachedSessionId: String,

    // ── Execution continuity context ──────────────────────────────────────────
    val executionPhase: String,
    val continuityToken: String?,
    val continuationToken: String?,
    val activatedAtMs: Long,
    val executionStartedAtMs: Long?,

    // ── Local execution history ───────────────────────────────────────────────
    val stepCount: Int,
    val lastStepAtMs: Long?,

    // ── Store management ──────────────────────────────────────────────────────
    val savedAtMs: Long = System.currentTimeMillis()
) {

    // ── Wire serialisation ────────────────────────────────────────────────────

    /**
     * Builds the canonical metadata map for wire transmission or diagnostic logging.
     *
     * Keys always present:
     *  - [KEY_FLOW_ID]
     *  - [KEY_FLOW_LINEAGE_ID]
     *  - [KEY_UNIT_ID]
     *  - [KEY_TASK_ID]
     *  - [KEY_TRACE_ID]
     *  - [KEY_DURABLE_SESSION_ID]
     *  - [KEY_ATTACHED_SESSION_ID]
     *  - [KEY_EXECUTION_PHASE]
     *  - [KEY_ACTIVATED_AT_MS]
     *  - [KEY_STEP_COUNT]
     *  - [KEY_SAVED_AT_MS]
     *
     * Keys present only when non-null:
     *  - [KEY_CONTINUITY_TOKEN]
     *  - [KEY_CONTINUATION_TOKEN]
     *  - [KEY_EXECUTION_STARTED_AT_MS]
     *  - [KEY_LAST_STEP_AT_MS]
     *
     * @return An immutable [Map] suitable for merging into AIP v3 metadata payloads or for
     *         inclusion in `DeviceConnected` lifecycle event metadata on re-attach.
     */
    fun toMetadataMap(): Map<String, Any> = buildMap {
        put(KEY_FLOW_ID, flowId)
        put(KEY_FLOW_LINEAGE_ID, flowLineageId)
        put(KEY_UNIT_ID, unitId)
        put(KEY_TASK_ID, taskId)
        put(KEY_TRACE_ID, traceId)
        put(KEY_DURABLE_SESSION_ID, durableSessionId)
        put(KEY_ATTACHED_SESSION_ID, attachedSessionId)
        put(KEY_EXECUTION_PHASE, executionPhase)
        put(KEY_ACTIVATED_AT_MS, activatedAtMs)
        put(KEY_STEP_COUNT, stepCount)
        put(KEY_SAVED_AT_MS, savedAtMs)
        continuityToken?.let { put(KEY_CONTINUITY_TOKEN, it) }
        continuationToken?.let { put(KEY_CONTINUATION_TOKEN, it) }
        executionStartedAtMs?.let { put(KEY_EXECUTION_STARTED_AT_MS, it) }
        lastStepAtMs?.let { put(KEY_LAST_STEP_AT_MS, it) }
    }

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * `true` when [executionPhase] represents a terminal execution state (completed,
     * failed, or rejected).
     *
     * Terminal records are retained in the store for post-mortem continuity diagnosis
     * but MUST NOT be used to self-authorise flow resumption.
     */
    val isTerminalPhase: Boolean
        get() = AndroidFlowExecutionPhase.fromValue(executionPhase).isTerminal

    /**
     * `true` when this record was produced with a V2-provided [continuityToken].
     *
     * Records with a continuity token can be correlated with V2's canonical session
     * and flow registry on re-attach.
     */
    val hasContinuityToken: Boolean
        get() = !continuityToken.isNullOrBlank()

    // ── Companion / factory ───────────────────────────────────────────────────

    companion object {

        // ── Wire key constants ────────────────────────────────────────────────

        /** Wire key for [flowId]. */
        const val KEY_FLOW_ID = "flow_continuity_flow_id"

        /** Wire key for [flowLineageId]. */
        const val KEY_FLOW_LINEAGE_ID = "flow_continuity_flow_lineage_id"

        /** Wire key for [unitId]. */
        const val KEY_UNIT_ID = "flow_continuity_unit_id"

        /** Wire key for [taskId]. */
        const val KEY_TASK_ID = "flow_continuity_task_id"

        /** Wire key for [traceId]. */
        const val KEY_TRACE_ID = "flow_continuity_trace_id"

        /** Wire key for [durableSessionId]. */
        const val KEY_DURABLE_SESSION_ID = "flow_continuity_durable_session_id"

        /** Wire key for [attachedSessionId]. */
        const val KEY_ATTACHED_SESSION_ID = "flow_continuity_attached_session_id"

        /** Wire key for the [AndroidFlowExecutionPhase.wireValue] at persistence time. */
        const val KEY_EXECUTION_PHASE = "flow_continuity_execution_phase"

        /**
         * Wire key for [continuityToken].
         *
         * This value is V2-managed; Android only echoes it.  Present only when
         * [continuityToken] is non-null.
         */
        const val KEY_CONTINUITY_TOKEN = "flow_continuity_continuity_token"

        /**
         * Wire key for [continuationToken].
         *
         * Present only when [continuationToken] is non-null (handoff dispatches only).
         */
        const val KEY_CONTINUATION_TOKEN = "flow_continuity_continuation_token"

        /** Wire key for [activatedAtMs]. */
        const val KEY_ACTIVATED_AT_MS = "flow_continuity_activated_at_ms"

        /** Wire key for [executionStartedAtMs]. Absent when execution had not yet started. */
        const val KEY_EXECUTION_STARTED_AT_MS = "flow_continuity_execution_started_at_ms"

        /** Wire key for [stepCount]. */
        const val KEY_STEP_COUNT = "flow_continuity_step_count"

        /** Wire key for [lastStepAtMs]. Absent when no steps have been recorded. */
        const val KEY_LAST_STEP_AT_MS = "flow_continuity_last_step_at_ms"

        /** Wire key for [savedAtMs]. */
        const val KEY_SAVED_AT_MS = "flow_continuity_saved_at_ms"

        // ── All keys — useful in tests ────────────────────────────────────────

        /**
         * All wire key constants defined on this record.
         *
         * Useful for verifying that [toMetadataMap] outputs no unexpected keys and that
         * all expected keys are stable.
         */
        val ALL_MANDATORY_KEYS: Set<String> = setOf(
            KEY_FLOW_ID,
            KEY_FLOW_LINEAGE_ID,
            KEY_UNIT_ID,
            KEY_TASK_ID,
            KEY_TRACE_ID,
            KEY_DURABLE_SESSION_ID,
            KEY_ATTACHED_SESSION_ID,
            KEY_EXECUTION_PHASE,
            KEY_ACTIVATED_AT_MS,
            KEY_STEP_COUNT,
            KEY_SAVED_AT_MS
        )

        // ── Factory ───────────────────────────────────────────────────────────

        /**
         * Creates a [DelegatedFlowContinuityRecord] from a [DelegatedExecutionTracker]
         * snapshot and a [DurableSessionContinuityRecord].
         *
         * This is the **primary production factory**: call it whenever the tracker state
         * changes in a way that should be durably checkpointed (e.g. on phase transition,
         * on step completion, on terminal outcome).
         *
         * The [flowId] is resolved as [DelegatedExecutionTracker.delegatedFlowId] when
         * non-empty, falling back to [DelegatedExecutionTracker.unitId].  Similarly
         * [flowLineageId] falls back to [DelegatedExecutionTracker.taskId] for pre-bridge
         * senders.
         *
         * @param tracker        Current execution tracker snapshot.
         * @param durableSession Current durable session continuity record.
         * @param executionPhase Current [AndroidFlowExecutionPhase] for this flow.
         * @param continuityToken V2-managed continuity token echoed from the inbound
         *                        envelope; null when not present.
         * @param savedAtMs      Epoch-ms persistence timestamp; defaults to the current time.
         * @return A fully populated [DelegatedFlowContinuityRecord] ready for persistence.
         */
        fun fromTracker(
            tracker: DelegatedExecutionTracker,
            durableSession: DurableSessionContinuityRecord,
            executionPhase: AndroidFlowExecutionPhase,
            continuityToken: String? = null,
            savedAtMs: Long = System.currentTimeMillis()
        ): DelegatedFlowContinuityRecord {
            val resolvedFlowId = tracker.delegatedFlowId.ifEmpty { tracker.unitId }
            val resolvedLineageId = tracker.flowLineageId.ifEmpty { tracker.taskId }
            return DelegatedFlowContinuityRecord(
                flowId = resolvedFlowId,
                flowLineageId = resolvedLineageId,
                unitId = tracker.unitId,
                taskId = tracker.taskId,
                traceId = tracker.traceId,
                durableSessionId = durableSession.durableSessionId,
                attachedSessionId = tracker.attachedSessionId,
                executionPhase = executionPhase.wireValue,
                continuityToken = continuityToken,
                continuationToken = tracker.record.unit.continuationToken,
                activatedAtMs = tracker.record.activatedAtMs,
                executionStartedAtMs = tracker.executionStartedAtMs,
                stepCount = tracker.stepCount,
                lastStepAtMs = tracker.lastStepAtMs,
                savedAtMs = savedAtMs
            )
        }
    }
}

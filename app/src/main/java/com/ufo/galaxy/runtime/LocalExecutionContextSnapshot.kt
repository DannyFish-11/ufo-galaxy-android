package com.ufo.galaxy.runtime

/**
 * PR-3 (Android) — Rehydrated local execution context snapshot.
 *
 * [LocalExecutionContextSnapshot] is the Android-side representation of a delegated
 * flow's local execution context as reconstructed from a persisted
 * [DelegatedFlowContinuityRecord] after a process kill, app restart, or transport
 * reconnect.
 *
 * ## Purpose
 *
 * After Android's process is recreated or a transport reconnect occurs, the in-memory
 * [DelegatedExecutionTracker] and [DelegatedActivationRecord] are gone.  Before PR-3
 * Android could only present V2 with the thin prior-session hint from
 * [ProcessRecreatedReattachHint] — it had no structured local record of *which* flow
 * was active, at which execution phase, or what continuity tokens were in play.
 *
 * [LocalExecutionContextSnapshot] fills that gap by providing a typed, read-only view of
 * the four continuity dimensions recovered from [DelegatedFlowContinuityRecord]:
 *
 *  1. **Flow identity / lineage** — [flowId], [flowLineageId], [unitId], [taskId], [traceId].
 *  2. **Session continuity** — [durableSessionId], [attachedSessionId].
 *  3. **Execution context** — [lastKnownPhase], [continuityToken], [continuationToken].
 *  4. **Execution history** — [stepCount], [lastStepAtMs].
 *
 * ## Authority boundary
 *
 * This snapshot is an **advisory read-only view** derived from Android's persisted
 * continuity store.  Android MUST NOT use it to self-authorise flow resumption.  V2
 * remains the canonical coordinator; the snapshot is presented to V2 on re-attach so V2
 * can decide whether to restore, replay, or start fresh.
 *
 * ## Non-goals
 *
 * - Does not replace [DelegatedFlowContinuityRecord] for persistence.
 * - Does not constitute a live execution tracker; execution tracking is managed by
 *   [DelegatedExecutionTracker] once a new execution begins.
 *
 * @property flowId               Stable identifier for the V2 canonical delegated flow entity.
 * @property flowLineageId        Lineage identity shared by all flows in the same V2 canonical
 *                                flow family.
 * @property unitId               Stable delegated-unit identifier from the prior activation.
 * @property taskId               Task identifier from the prior activation.
 * @property traceId              End-to-end observability identifier from the prior activation.
 * @property durableSessionId     Durable session identifier from the prior activation era.
 * @property attachedSessionId    Attached session identifier under which the prior flow ran.
 * @property lastKnownPhase       The [AndroidFlowExecutionPhase] at the time the continuity
 *                                record was last persisted.  Indicates the execution stage at
 *                                which the process was killed or the connection was lost.
 * @property continuityToken      V2-managed durable execution continuity token echoed from the
 *                                prior activation; null when not present.  Android echoes this
 *                                back on re-attach so V2 can correlate the restored execution
 *                                with its originating session.
 * @property continuationToken    Executor-managed delegated handoff continuation token from the
 *                                prior activation; null for non-handoff dispatches.
 * @property stepCount            Number of execution steps completed before the prior context
 *                                was lost.
 * @property lastStepAtMs         Epoch-ms timestamp of the last recorded step; null when no
 *                                steps had been completed.
 * @property rehydratedAtMs       Epoch-ms timestamp when this snapshot was constructed from the
 *                                persisted record.
 */
data class LocalExecutionContextSnapshot(

    // ── Flow identity / lineage ───────────────────────────────────────────────
    val flowId: String,
    val flowLineageId: String,
    val unitId: String,
    val taskId: String,
    val traceId: String,

    // ── Session continuity ────────────────────────────────────────────────────
    val durableSessionId: String,
    val attachedSessionId: String,

    // ── Execution context ─────────────────────────────────────────────────────
    val lastKnownPhase: AndroidFlowExecutionPhase,
    val continuityToken: String?,
    val continuationToken: String?,

    // ── Execution history ─────────────────────────────────────────────────────
    val stepCount: Int,
    val lastStepAtMs: Long?,

    // ── Snapshot metadata ─────────────────────────────────────────────────────
    val rehydratedAtMs: Long = System.currentTimeMillis()
) {

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * `true` when [lastKnownPhase] represents a terminal execution state (completed,
     * failed, or rejected) at the time the context was last persisted.
     *
     * Terminal snapshots MUST NOT be used to self-authorise flow resumption; they are
     * retained for post-mortem continuity diagnosis and V2 correlation only.
     */
    val wasTerminalWhenPersisted: Boolean
        get() = lastKnownPhase.isTerminal

    /**
     * `true` when this snapshot was produced with a V2-provided [continuityToken].
     *
     * Snapshots with a continuity token can be correlated with V2's canonical session
     * and flow registry on re-attach.
     */
    val hasContinuityToken: Boolean
        get() = !continuityToken.isNullOrBlank()

    /**
     * Builds the canonical re-attach metadata map for inclusion in a `DeviceConnected`
     * lifecycle event or diagnostic payload.
     *
     * Keys always present:
     *  - [KEY_FLOW_ID]
     *  - [KEY_FLOW_LINEAGE_ID]
     *  - [KEY_UNIT_ID]
     *  - [KEY_TASK_ID]
     *  - [KEY_TRACE_ID]
     *  - [KEY_DURABLE_SESSION_ID]
     *  - [KEY_ATTACHED_SESSION_ID]
     *  - [KEY_LAST_KNOWN_PHASE]
     *  - [KEY_STEP_COUNT]
     *  - [KEY_REHYDRATED_AT_MS]
     *
     * Keys present when non-null:
     *  - [KEY_CONTINUITY_TOKEN]
     *  - [KEY_CONTINUATION_TOKEN]
     *  - [KEY_LAST_STEP_AT_MS]
     *
     * @return An immutable [Map] suitable for merging into re-attach metadata payloads.
     */
    fun toReattachMetadataMap(): Map<String, Any> = buildMap {
        put(KEY_FLOW_ID, flowId)
        put(KEY_FLOW_LINEAGE_ID, flowLineageId)
        put(KEY_UNIT_ID, unitId)
        put(KEY_TASK_ID, taskId)
        put(KEY_TRACE_ID, traceId)
        put(KEY_DURABLE_SESSION_ID, durableSessionId)
        put(KEY_ATTACHED_SESSION_ID, attachedSessionId)
        put(KEY_LAST_KNOWN_PHASE, lastKnownPhase.wireValue)
        put(KEY_STEP_COUNT, stepCount)
        put(KEY_REHYDRATED_AT_MS, rehydratedAtMs)
        continuityToken?.let { put(KEY_CONTINUITY_TOKEN, it) }
        continuationToken?.let { put(KEY_CONTINUATION_TOKEN, it) }
        lastStepAtMs?.let { put(KEY_LAST_STEP_AT_MS, it) }
    }

    // ── Companion / factory ───────────────────────────────────────────────────

    companion object {

        // ── Wire key constants ────────────────────────────────────────────────

        /** Wire key for [flowId]. */
        const val KEY_FLOW_ID = "local_ctx_flow_id"

        /** Wire key for [flowLineageId]. */
        const val KEY_FLOW_LINEAGE_ID = "local_ctx_flow_lineage_id"

        /** Wire key for [unitId]. */
        const val KEY_UNIT_ID = "local_ctx_unit_id"

        /** Wire key for [taskId]. */
        const val KEY_TASK_ID = "local_ctx_task_id"

        /** Wire key for [traceId]. */
        const val KEY_TRACE_ID = "local_ctx_trace_id"

        /** Wire key for [durableSessionId]. */
        const val KEY_DURABLE_SESSION_ID = "local_ctx_durable_session_id"

        /** Wire key for [attachedSessionId]. */
        const val KEY_ATTACHED_SESSION_ID = "local_ctx_attached_session_id"

        /** Wire key for [lastKnownPhase] wire value. */
        const val KEY_LAST_KNOWN_PHASE = "local_ctx_last_known_phase"

        /** Wire key for [continuityToken]. Present only when non-null. */
        const val KEY_CONTINUITY_TOKEN = "local_ctx_continuity_token"

        /** Wire key for [continuationToken]. Present only when non-null. */
        const val KEY_CONTINUATION_TOKEN = "local_ctx_continuation_token"

        /** Wire key for [stepCount]. */
        const val KEY_STEP_COUNT = "local_ctx_step_count"

        /** Wire key for [lastStepAtMs]. Present only when non-null. */
        const val KEY_LAST_STEP_AT_MS = "local_ctx_last_step_at_ms"

        /** Wire key for [rehydratedAtMs]. */
        const val KEY_REHYDRATED_AT_MS = "local_ctx_rehydrated_at_ms"

        /**
         * Set of all mandatory wire key constants (always present in
         * [toReattachMetadataMap]).
         *
         * Useful for test assertions and schema-stability checks.
         */
        val ALL_MANDATORY_KEYS: Set<String> = setOf(
            KEY_FLOW_ID,
            KEY_FLOW_LINEAGE_ID,
            KEY_UNIT_ID,
            KEY_TASK_ID,
            KEY_TRACE_ID,
            KEY_DURABLE_SESSION_ID,
            KEY_ATTACHED_SESSION_ID,
            KEY_LAST_KNOWN_PHASE,
            KEY_STEP_COUNT,
            KEY_REHYDRATED_AT_MS
        )

        // ── Factory ───────────────────────────────────────────────────────────

        /**
         * Constructs a [LocalExecutionContextSnapshot] from a persisted
         * [DelegatedFlowContinuityRecord].
         *
         * [lastKnownPhase] is resolved via [AndroidFlowExecutionPhase.fromValue]; unknown
         * phase wire values resolve to [AndroidFlowExecutionPhase.UNKNOWN].
         *
         * @param record          The persisted [DelegatedFlowContinuityRecord] to rehydrate from.
         * @param rehydratedAtMs  Epoch-ms timestamp for this rehydration; defaults to the
         *                        current wall clock.
         * @return A fully populated [LocalExecutionContextSnapshot] ready for inclusion in
         *         re-attach metadata payloads.
         */
        fun fromContinuityRecord(
            record: DelegatedFlowContinuityRecord,
            rehydratedAtMs: Long = System.currentTimeMillis()
        ): LocalExecutionContextSnapshot = LocalExecutionContextSnapshot(
            flowId = record.flowId,
            flowLineageId = record.flowLineageId,
            unitId = record.unitId,
            taskId = record.taskId,
            traceId = record.traceId,
            durableSessionId = record.durableSessionId,
            attachedSessionId = record.attachedSessionId,
            lastKnownPhase = AndroidFlowExecutionPhase.fromValue(record.executionPhase),
            continuityToken = record.continuityToken,
            continuationToken = record.continuationToken,
            stepCount = record.stepCount,
            lastStepAtMs = record.lastStepAtMs,
            rehydratedAtMs = rehydratedAtMs
        )
    }
}

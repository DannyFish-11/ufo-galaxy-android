package com.ufo.galaxy.agent

import com.ufo.galaxy.runtime.SourceRuntimePosture

/**
 * **Canonical Android-internal representation of a delegated runtime work unit** (PR-8,
 * post-#533 dual-repo runtime unification master plan — Delegated Runtime
 * Receipt/Activation Foundations, Android side).
 *
 * This class is the Android-side parsed and validated form of a work unit that the
 * main-repo OpenClawd host has dispatched for local execution on this device.  It is
 * produced by [DelegatedRuntimeReceiver] after the inbound [TakeoverRequestEnvelope] has
 * been accepted under an active [com.ufo.galaxy.runtime.AttachedRuntimeSession].
 *
 * ## Purpose
 *
 * Before PR-8 Android had no explicit intermediate representation between:
 *  - The raw inbound wire envelope ([TakeoverRequestEnvelope]), and
 *  - The execution call to [com.ufo.galaxy.agent.AutonomousExecutionPipeline].
 *
 * [DelegatedRuntimeUnit] fills that gap.  It carries the activation inputs that are
 * stable and necessary for local takeover/continuation — goal, posture, checkpoint,
 * session binding — in one typed, immutable model that both dispatch and observability
 * paths can depend on without re-parsing the raw JSON.
 *
 * ## Relationship to [TakeoverRequestEnvelope]
 *
 * [TakeoverRequestEnvelope] is the **wire** envelope (parsed from raw JSON).
 * [DelegatedRuntimeUnit] is the **domain** model produced after session gating.
 *
 * Differences:
 *  - `attachedSessionId` binds this unit to the precise [AttachedRuntimeSession] that
 *    was active when the receipt was accepted.  This linkage is absent from the wire
 *    envelope, which only knows about the host's session context.
 *  - `resolvedPosture` is already normalised via [SourceRuntimePosture.fromValue]; callers
 *    do not need to null-check or parse the raw posture string.
 *  - `receivedAtMs` is set by [DelegatedRuntimeReceiver] at the moment of local receipt
 *    acceptance, providing a stable activation timestamp independent of transport timing.
 *
 * ## Immutability
 * [DelegatedRuntimeUnit] is immutable.  Callers must not mutate it after creation.
 *
 * ## Obtaining an instance
 * Use [DelegatedRuntimeReceiver.receive]; do **not** construct directly in production code.
 *
 * @property unitId           Stable identifier for this delegated unit.  Echoes
 *                            [TakeoverRequestEnvelope.takeover_id] so the main-runtime can
 *                            correlate its dispatch record with Android's activation record.
 * @property taskId           The task being delegated; echoed from
 *                            [TakeoverRequestEnvelope.task_id].
 * @property traceId          End-to-end observability identifier propagated from the
 *                            originating request.
 * @property goal             Natural-language objective that Android should execute.
 * @property attachedSessionId The [com.ufo.galaxy.runtime.AttachedRuntimeSession.sessionId]
 *                            that was active when this unit was received.  Ties delegated
 *                            work to the explicit cross-device runtime session rather than
 *                            ad-hoc transport state.
 * @property sourceDeviceId   Identifier of the device that dispatched this unit; may be
 *                            `null` for legacy callers.
 * @property resolvedPosture  Canonical source-device posture string — already resolved
 *                            via [SourceRuntimePosture.fromValue]; always either
 *                            [SourceRuntimePosture.CONTROL_ONLY] or
 *                            [SourceRuntimePosture.JOIN_RUNTIME].
 * @property checkpoint       Optional progress checkpoint forwarded from the originating
 *                            executor; describes how far the task had progressed.
 * @property constraints               Constraint strings from the original task_assign.
 * @property receivedAtMs              Epoch-millisecond timestamp when Android accepted this unit
 *                                     (set by [DelegatedRuntimeReceiver] at receipt time).
 * @property handoffReason             (PR-9) Wire value of [DelegatedHandoffContract.HandoffReason]
 *                                     describing why the main-repo delegated this unit; null from
 *                                     legacy senders (resolved to
 *                                     [DelegatedHandoffContract.HandoffReason.DEFAULT] by
 *                                     [DelegatedHandoffContract]).
 * @property originatingHostId         (PR-9) [com.ufo.galaxy.runtime.RuntimeHostDescriptor.hostId]
 *                                     of the dispatching device; null for legacy senders.
 * @property originatingFormationRole  (PR-9) Wire value of the originating device's
 *                                     [com.ufo.galaxy.runtime.RuntimeHostDescriptor.FormationRole];
 *                                     null for legacy senders.
 * @property requiredCapabilityDimensions (PR-9) List of
 *                                     [com.ufo.galaxy.capability.AndroidCapabilityVector.ExecutionDimension]
 *                                     wire values the delegated task requires; empty from legacy senders.
 * @property continuationToken         (PR-9) Opaque, stable machine-readable continuation state token
 *                                     from the originating executor; null when not provided.
 */
data class DelegatedRuntimeUnit(
    val unitId: String,
    val taskId: String,
    val traceId: String,
    val goal: String,
    val attachedSessionId: String,
    val sourceDeviceId: String? = null,
    val resolvedPosture: String = SourceRuntimePosture.DEFAULT,
    val checkpoint: String? = null,
    val constraints: List<String> = emptyList(),
    val receivedAtMs: Long = System.currentTimeMillis(),
    // ── PR-9: Handoff contract fields ─────────────────────────────────────────
    val handoffReason: String? = null,
    val originatingHostId: String? = null,
    val originatingFormationRole: String? = null,
    val requiredCapabilityDimensions: List<String> = emptyList(),
    val continuationToken: String? = null
) {

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * `true` when the source device joined the runtime as a co-executor
     * ([SourceRuntimePosture.JOIN_RUNTIME]).
     */
    val isSourceJoinRuntime: Boolean
        get() = SourceRuntimePosture.isJoinRuntime(resolvedPosture)

    /**
     * `true` when the source device acted as a pure controller
     * ([SourceRuntimePosture.CONTROL_ONLY]).
     */
    val isSourceControlOnly: Boolean
        get() = SourceRuntimePosture.isControlOnly(resolvedPosture)

    /**
     * Builds a canonical metadata map for wire transmission or diagnostic logging.
     *
     * Keys present:
     *  - [KEY_UNIT_ID]             — stable delegated-unit identifier.
     *  - [KEY_TASK_ID]             — task identifier.
     *  - [KEY_TRACE_ID]            — end-to-end trace identifier.
     *  - [KEY_ATTACHED_SESSION_ID] — session this unit was received under.
     *  - [KEY_RESOLVED_POSTURE]    — normalised posture string.
     *  - [KEY_RECEIVED_AT_MS]      — receipt timestamp.
     *  - [KEY_SOURCE_DEVICE_ID]    — present only when [sourceDeviceId] is non-null.
     *  - [KEY_CHECKPOINT]          — present only when [checkpoint] is non-null.
     *  - [KEY_HANDOFF_REASON]      — (PR-9) present only when [handoffReason] is non-null.
     *  - [KEY_ORIGINATING_HOST_ID] — (PR-9) present only when [originatingHostId] is non-null.
     *  - [KEY_ORIGINATING_FORMATION_ROLE] — (PR-9) present only when
     *    [originatingFormationRole] is non-null.
     *  - [KEY_REQUIRED_CAPABILITY_DIMENSIONS] — (PR-9) comma-separated dimension wire values;
     *    present only when [requiredCapabilityDimensions] is non-empty.
     *  - [KEY_CONTINUATION_TOKEN]  — (PR-9) present only when [continuationToken] is non-null.
     *
     * @return An immutable [Map] suitable for merging into AIP v3 metadata payloads.
     */
    fun toMetadataMap(): Map<String, Any> = buildMap {
        put(KEY_UNIT_ID, unitId)
        put(KEY_TASK_ID, taskId)
        put(KEY_TRACE_ID, traceId)
        put(KEY_ATTACHED_SESSION_ID, attachedSessionId)
        put(KEY_RESOLVED_POSTURE, resolvedPosture)
        put(KEY_RECEIVED_AT_MS, receivedAtMs)
        sourceDeviceId?.let { put(KEY_SOURCE_DEVICE_ID, it) }
        checkpoint?.let { put(KEY_CHECKPOINT, it) }
        // ── PR-9: Handoff contract metadata ──────────────────────────────────
        handoffReason?.let { put(KEY_HANDOFF_REASON, it) }
        originatingHostId?.let { put(KEY_ORIGINATING_HOST_ID, it) }
        originatingFormationRole?.let { put(KEY_ORIGINATING_FORMATION_ROLE, it) }
        if (requiredCapabilityDimensions.isNotEmpty()) {
            put(KEY_REQUIRED_CAPABILITY_DIMENSIONS, requiredCapabilityDimensions.joinToString(","))
        }
        continuationToken?.let { put(KEY_CONTINUATION_TOKEN, it) }
    }

    // ── Companion / constants ─────────────────────────────────────────────────

    companion object {

        /** Metadata key for the delegated-unit identifier. */
        const val KEY_UNIT_ID = "delegated_unit_id"

        /** Metadata key for the task identifier. */
        const val KEY_TASK_ID = "delegated_unit_task_id"

        /** Metadata key for the end-to-end trace identifier. */
        const val KEY_TRACE_ID = "delegated_unit_trace_id"

        /** Metadata key for the attached session identifier. */
        const val KEY_ATTACHED_SESSION_ID = "delegated_unit_attached_session_id"

        /** Metadata key for the resolved source posture string. */
        const val KEY_RESOLVED_POSTURE = "delegated_unit_resolved_posture"

        /** Metadata key for the epoch-ms receipt timestamp. */
        const val KEY_RECEIVED_AT_MS = "delegated_unit_received_at_ms"

        /** Metadata key for the source device identifier. */
        const val KEY_SOURCE_DEVICE_ID = "delegated_unit_source_device_id"

        /** Metadata key for the optional progress checkpoint. */
        const val KEY_CHECKPOINT = "delegated_unit_checkpoint"

        // ── PR-9: Handoff contract metadata key constants ─────────────────────

        /**
         * Metadata key for the handoff reason wire value.
         *
         * Absent from [toMetadataMap] when [handoffReason] is null (legacy senders).
         * Value type: String — wire value of [DelegatedHandoffContract.HandoffReason].
         */
        const val KEY_HANDOFF_REASON = "delegated_unit_handoff_reason"

        /**
         * Metadata key for the originating host identifier.
         *
         * Absent from [toMetadataMap] when [originatingHostId] is null.
         * Value type: String — echoes [com.ufo.galaxy.runtime.RuntimeHostDescriptor.hostId]
         * of the dispatching device.
         */
        const val KEY_ORIGINATING_HOST_ID = "delegated_unit_originating_host_id"

        /**
         * Metadata key for the originating device's formation-role wire value.
         *
         * Absent from [toMetadataMap] when [originatingFormationRole] is null.
         * Value type: String — wire value of
         * [com.ufo.galaxy.runtime.RuntimeHostDescriptor.FormationRole].
         */
        const val KEY_ORIGINATING_FORMATION_ROLE = "delegated_unit_originating_formation_role"

        /**
         * Metadata key for the comma-separated list of required capability-dimension wire values.
         *
         * Absent from [toMetadataMap] when [requiredCapabilityDimensions] is empty.
         * Value type: String — comma-separated
         * [com.ufo.galaxy.capability.AndroidCapabilityVector.ExecutionDimension.wireValue] tokens.
         */
        const val KEY_REQUIRED_CAPABILITY_DIMENSIONS = "delegated_unit_required_capability_dimensions"

        /**
         * Metadata key for the opaque machine-readable continuation state token.
         *
         * Absent from [toMetadataMap] when [continuationToken] is null.
         * Value type: String — opaque token produced by the originating executor.
         */
        const val KEY_CONTINUATION_TOKEN = "delegated_unit_continuation_token"

        /**
         * Creates a [DelegatedRuntimeUnit] from a [TakeoverRequestEnvelope] and an
         * [attachedSessionId].
         *
         * Resolves [TakeoverRequestEnvelope.source_runtime_posture] via
         * [SourceRuntimePosture.fromValue] and captures [receivedAtMs] at call time.
         * PR-9 handoff-contract fields ([handoffReason], [originatingHostId],
         * [originatingFormationRole], [requiredCapabilityDimensions], [continuationToken])
         * are mapped directly from the envelope; they default to `null` / empty for
         * pre-PR-9 senders.
         *
         * @param envelope         Inbound takeover request envelope.
         * @param attachedSessionId Session ID from the active
         *                         [com.ufo.galaxy.runtime.AttachedRuntimeSession].
         * @param receivedAtMs     Epoch-ms receipt timestamp; defaults to the current time.
         * @return A fully populated [DelegatedRuntimeUnit].
         */
        fun fromEnvelope(
            envelope: TakeoverRequestEnvelope,
            attachedSessionId: String,
            receivedAtMs: Long = System.currentTimeMillis()
        ): DelegatedRuntimeUnit = DelegatedRuntimeUnit(
            unitId = envelope.takeover_id,
            taskId = envelope.task_id,
            traceId = envelope.trace_id,
            goal = envelope.goal,
            attachedSessionId = attachedSessionId,
            sourceDeviceId = envelope.source_device_id,
            resolvedPosture = SourceRuntimePosture.fromValue(envelope.source_runtime_posture),
            checkpoint = envelope.checkpoint,
            constraints = envelope.constraints,
            receivedAtMs = receivedAtMs,
            // ── PR-9: Handoff contract fields ──────────────────────────────────
            handoffReason = envelope.handoff_reason,
            originatingHostId = envelope.originating_host_id,
            originatingFormationRole = envelope.originating_formation_role,
            requiredCapabilityDimensions = envelope.required_capability_dimensions,
            continuationToken = envelope.continuation_token
        )
    }
}

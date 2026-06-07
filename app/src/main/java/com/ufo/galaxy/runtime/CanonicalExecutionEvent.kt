package com.ufo.galaxy.runtime

/**
 * PR-7 (Android) — Canonical Android-side execution event payload for flow-level
 * operator visibility.
 *
 * [CanonicalExecutionEvent] is the authoritative structured representation of a single
 * canonical execution event emitted by the Android delegated runtime pipeline.  Every
 * event carries a complete set of flow lineage, phase, and rationale fields so that V2
 * operator surfaces and flow inspect systems can reconstruct the full execution trace of
 * a delegated flow from a sequence of [CanonicalExecutionEvent] instances alone.
 *
 * ## Design intent
 *
 * Before PR-7, Android delegated runtime execution phase transitions were surfaced only
 * through ad-hoc [com.ufo.galaxy.observability.GalaxyLogger] log entries and partial
 * state inspections of individual runtime modules.  This made it impossible for V2
 * operator tooling to answer questions like:
 *
 *  - "Is this delegated flow currently in planning, grounding, or execution?"
 *  - "Has LoopController triggered a replan, and why?"
 *  - "Which gate blocked execution, and what was the rationale?"
 *  - "Has stagnation been detected, and at which step?"
 *
 * [CanonicalExecutionEvent] closes this gap by providing a **first-class typed carrier**
 * that every Android runtime module can produce (via
 * [AndroidCanonicalExecutionEventOwner]) and that V2 can ingest as stable, structured
 * device-side event input for its flow-level operator surface.
 *
 * ## Payload fields
 *
 * ### Identity / lineage binding
 *
 * | Field                  | Description                                                                    |
 * |------------------------|--------------------------------------------------------------------------------|
 * | [eventId]              | UUID identifying this specific emission.  Idempotency key for dedup.          |
 * | [eventType]            | [CanonicalExecutionEventType] discriminator; the primary event classification. |
 * | [flowId]               | Delegated flow identifier; stable across all events for the same flow.        |
 * | [taskId]               | Task identifier; echoed from the originating delegated unit.                  |
 * | [traceId]              | End-to-end trace identifier; may be `null` for flows without trace context.   |
 * | [delegatedLineageId]   | Lineage ID grouping all Android flows belonging to the same V2 canonical flow. |
 * | [attachedSessionId]    | Session identifier this event is scoped to; may be `null`.                    |
 *
 * ### Phase / step context
 *
 * | Field         | Description                                                                           |
 * |---------------|---------------------------------------------------------------------------------------|
 * | [phase]       | Human-readable name of the current execution phase (e.g. `"planning"`, `"grounding"`,|
 * |               | `"execution"`, `"replan"`, `"stagnation"`, `"gate"`).                                 |
 * | [stepIndex]   | Zero-based index of the current execution step within this execution era.             |
 * |               | `null` for events that are not step-bound (e.g. phase transitions).                   |
 * | [reason]      | Human-readable description of why this event was emitted.  Required for milestone     |
 * |               | events; optional for progress events.                                                 |
 *
 * ### Blocking / gate / replan / stagnation rationale
 *
 * | Field                 | Description                                                                   |
 * |-----------------------|-------------------------------------------------------------------------------|
 * | [blockingRationale]   | Non-null for [CanonicalExecutionEventType.PLANNING_BLOCKED],                  |
 * |                       | [CanonicalExecutionEventType.GROUNDING_BLOCKED], and                          |
 * |                       | [CanonicalExecutionEventType.EXECUTION_BLOCKED].  Names the blocking cause.   |
 * | [gateRationale]       | Non-null for [CanonicalExecutionEventType.GATE_DECISION_EMITTED] and          |
 * |                       | [CanonicalExecutionEventType.EXECUTION_BLOCKED] when a gate is responsible.   |
 * | [replanReason]        | Non-null for [CanonicalExecutionEventType.REPLAN_TRIGGERED].  Names the       |
 * |                       | replanning trigger.                                                            |
 * | [stagnationReason]    | Non-null for [CanonicalExecutionEventType.STAGNATION_DETECTED].  Names the    |
 * |                       | stagnation pattern.                                                            |
 *
 * ### Execution context hints
 *
 * | Field                    | Description                                                               |
 * |--------------------------|---------------------------------------------------------------------------|
 * | [postureHint]            | Wire value of the current [SourceRuntimePosture] when the event was       |
 * |                          | produced; `null` if not available.                                        |
 * | [executionContextHint]   | Optional free-form string describing the execution context at the time    |
 * |                          | of emission (e.g. component name, pipeline stage).                        |
 *
 * ### Emission metadata
 *
 * | Field           | Description                                                                    |
 * |-----------------|--------------------------------------------------------------------------------|
 * | [sourceComponent] | Name of the Android runtime component that produced this event.              |
 * |                   | Should match one of the [AndroidCanonicalExecutionEventOwner] `INTEGRATION_*` |
 * |                   | constants (e.g. `"LoopController"`, `"AutonomousExecutionPipeline"`).          |
 * | [timestampMs]   | Epoch-ms timestamp when this event was produced.                               |
 * | [emissionSeq]   | Monotonically increasing emission sequence number within this flow's event     |
 * |                 | stream.  Allows V2 to detect out-of-order delivery and reconstruct total order.|
 *
 * ## Obtaining an instance
 *
 * Use the companion factory methods:
 *  - [planning] — for planning phase events
 *  - [grounding] — for grounding phase events
 *  - [execution] — for execution phase events
 *  - [replan] — for replan trigger events
 *  - [stagnation] — for stagnation detection events
 *  - [gate] — for gate decision events
 *  - [milestone] — for takeover / collaboration / parallel milestone events
 *
 * @property eventId              UUID idempotency key for this specific emission.
 * @property eventType            [CanonicalExecutionEventType] discriminator.
 * @property flowId               Delegated flow identifier.
 * @property taskId               Task identifier.
 * @property traceId              End-to-end trace identifier; may be `null`.
 * @property delegatedLineageId   Lineage identity shared by all flows of the same V2 canonical flow.
 * @property attachedSessionId    Session identifier; may be `null`.
 * @property phase                Human-readable name of the current execution phase.
 * @property stepIndex            Zero-based current step index; `null` for phase-transition events.
 * @property reason               Human-readable description of why this event was emitted.
 * @property blockingRationale    Blocking cause for BLOCKED events; `null` otherwise.
 * @property gateRationale        Gate outcome for GATE_DECISION_EMITTED / EXECUTION_BLOCKED; `null` otherwise.
 * @property replanReason         Replanning trigger for REPLAN_TRIGGERED; `null` otherwise.
 * @property stagnationReason     Stagnation pattern for STAGNATION_DETECTED; `null` otherwise.
 * @property postureHint          Current posture wire value at emission time; `null` if unavailable.
 * @property executionContextHint Optional execution context description at emission time.
 * @property sourceComponent      Name of the Android component that produced this event.
 * @property timestampMs          Epoch-ms production timestamp.
 * @property emissionSeq          Monotonically increasing per-flow emission sequence number.
 *
 * @see CanonicalExecutionEventType
 * @see AndroidCanonicalExecutionEventOwner
 */
data class CanonicalExecutionEvent(
    val eventId: String,
    val eventType: CanonicalExecutionEventType,
    val flowId: String,
    val taskId: String,
    val traceId: String?,
    val delegatedLineageId: String?,
    val attachedSessionId: String?,
    val phase: String,
    val stepIndex: Int?,
    val reason: String?,
    val blockingRationale: String?,
    val gateRationale: String?,
    val replanReason: String?,
    val stagnationReason: String?,
    val postureHint: String?,
    val executionContextHint: String?,
    val sourceComponent: String,
    val timestampMs: Long,
    val emissionSeq: Long
) {

    // ── Derived helpers ───────────────────────────────────────────────────────

    /** `true` when this event signals a blocked condition that prevents forward progress. */
    val isBlocked: Boolean
        get() = eventType == CanonicalExecutionEventType.PLANNING_BLOCKED
            || eventType == CanonicalExecutionEventType.GROUNDING_BLOCKED
            || eventType == CanonicalExecutionEventType.EXECUTION_BLOCKED

    /** `true` when this event signals the start of a new execution phase. */
    val isPhaseStart: Boolean
        get() = eventType == CanonicalExecutionEventType.PLANNING_STARTED
            || eventType == CanonicalExecutionEventType.GROUNDING_STARTED
            || eventType == CanonicalExecutionEventType.EXECUTION_STARTED

    /** `true` when this event signals a recovery trigger (replan or stagnation). */
    val isRecoveryTrigger: Boolean
        get() = eventType == CanonicalExecutionEventType.REPLAN_TRIGGERED
            || eventType == CanonicalExecutionEventType.STAGNATION_DETECTED

    /**
     * Produces a map of the canonical payload fields suitable for structured telemetry
     * or V2 uplink metadata.
     *
     * The map is deterministic and includes all identity, phase, rationale, and metadata
     * fields.  Fields with `null` values are omitted from the output so callers can use
     * the map directly as a sparse metadata carrier without special-casing nulls.
     */
    fun toMetadataMap(): Map<String, Any> = buildMap {
        put("event_id", eventId)
        put("event_type", eventType.wireValue)
        put("flow_id", flowId)
        put("task_id", taskId)
        traceId?.let { put("trace_id", it) }
        delegatedLineageId?.let { put("delegated_lineage_id", it) }
        attachedSessionId?.let { put("attached_session_id", it) }
        put("phase", phase)
        stepIndex?.let { put("step_index", it) }
        reason?.let { put("reason", it) }
        blockingRationale?.let { put("blocking_rationale", it) }
        gateRationale?.let { put("gate_rationale", it) }
        replanReason?.let { put("replan_reason", it) }
        stagnationReason?.let { put("stagnation_reason", it) }
        postureHint?.let { put("posture_hint", it) }
        executionContextHint?.let { put("execution_context_hint", it) }
        put("source_component", sourceComponent)
        put("timestamp_ms", timestampMs)
        put("emission_seq", emissionSeq)
    }

    // ── Companion factory methods ─────────────────────────────────────────────

    companion object {

        /**
         * Produces a planning-phase [CanonicalExecutionEvent].
         *
         * @param eventType      Must be one of [CanonicalExecutionEventType.PLANNING_STARTED],
         *                       [CanonicalExecutionEventType.PLANNING_PROGRESS], or
         *                       [CanonicalExecutionEventType.PLANNING_BLOCKED].
         * @param flowId         Delegated flow identifier.
         * @param taskId         Task identifier.
         * @param traceId        End-to-end trace identifier; may be `null`.
         * @param delegatedLineageId Lineage identity; may be `null`.
         * @param attachedSessionId  Session identifier; may be `null`.
         * @param reason         Human-readable reason for this event.
         * @param blockingRationale Blocking cause; required for [CanonicalExecutionEventType.PLANNING_BLOCKED].
         * @param postureHint    Current posture hint; may be `null`.
         * @param sourceComponent Name of the emitting component.
         * @param timestampMs    Epoch-ms production timestamp.
         * @param emissionSeq    Per-flow monotonic emission sequence number.
         * @param eventId        UUID for this emission; auto-generated if not supplied.
         */
        fun planning(
            eventType: CanonicalExecutionEventType,
            flowId: String,
            taskId: String,
            traceId: String?,
            delegatedLineageId: String?,
            attachedSessionId: String?,
            reason: String?,
            blockingRationale: String? = null,
            postureHint: String? = null,
            sourceComponent: String,
            timestampMs: Long = System.currentTimeMillis(),
            emissionSeq: Long,
            eventId: String = java.util.UUID.randomUUID().toString()
        ): CanonicalExecutionEvent = CanonicalExecutionEvent(
            eventId = eventId,
            eventType = eventType,
            flowId = flowId,
            taskId = taskId,
            traceId = traceId,
            delegatedLineageId = delegatedLineageId,
            attachedSessionId = attachedSessionId,
            phase = PHASE_PLANNING,
            stepIndex = null,
            reason = reason,
            blockingRationale = blockingRationale,
            gateRationale = null,
            replanReason = null,
            stagnationReason = null,
            postureHint = postureHint,
            executionContextHint = sourceComponent,
            sourceComponent = sourceComponent,
            timestampMs = timestampMs,
            emissionSeq = emissionSeq
        )

        /**
         * Produces a grounding-phase [CanonicalExecutionEvent].
         *
         * @param eventType       Must be one of [CanonicalExecutionEventType.GROUNDING_STARTED],
         *                        [CanonicalExecutionEventType.GROUNDING_PROGRESS], or
         *                        [CanonicalExecutionEventType.GROUNDING_BLOCKED].
         * @param flowId          Delegated flow identifier.
         * @param taskId          Task identifier.
         * @param traceId         End-to-end trace identifier; may be `null`.
         * @param delegatedLineageId Lineage identity; may be `null`.
         * @param attachedSessionId  Session identifier; may be `null`.
         * @param stepIndex       Current step index.
         * @param reason          Human-readable reason for this event.
         * @param blockingRationale Blocking cause; required for [CanonicalExecutionEventType.GROUNDING_BLOCKED].
         * @param postureHint     Current posture hint; may be `null`.
         * @param sourceComponent Name of the emitting component.
         * @param timestampMs     Epoch-ms production timestamp.
         * @param emissionSeq     Per-flow monotonic emission sequence number.
         * @param eventId         UUID for this emission; auto-generated if not supplied.
         */
        fun grounding(
            eventType: CanonicalExecutionEventType,
            flowId: String,
            taskId: String,
            traceId: String?,
            delegatedLineageId: String?,
            attachedSessionId: String?,
            stepIndex: Int,
            reason: String?,
            blockingRationale: String? = null,
            postureHint: String? = null,
            sourceComponent: String,
            timestampMs: Long = System.currentTimeMillis(),
            emissionSeq: Long,
            eventId: String = java.util.UUID.randomUUID().toString()
        ): CanonicalExecutionEvent = CanonicalExecutionEvent(
            eventId = eventId,
            eventType = eventType,
            flowId = flowId,
            taskId = taskId,
            traceId = traceId,
            delegatedLineageId = delegatedLineageId,
            attachedSessionId = attachedSessionId,
            phase = PHASE_GROUNDING,
            stepIndex = stepIndex,
            reason = reason,
            blockingRationale = blockingRationale,
            gateRationale = null,
            replanReason = null,
            stagnationReason = null,
            postureHint = postureHint,
            executionContextHint = sourceComponent,
            sourceComponent = sourceComponent,
            timestampMs = timestampMs,
            emissionSeq = emissionSeq
        )

        /**
         * Produces an execution-phase [CanonicalExecutionEvent].
         *
         * @param eventType       Must be one of [CanonicalExecutionEventType.EXECUTION_STARTED],
         *                        [CanonicalExecutionEventType.EXECUTION_PROGRESS], or
         *                        [CanonicalExecutionEventType.EXECUTION_BLOCKED].
         * @param flowId          Delegated flow identifier.
         * @param taskId          Task identifier.
         * @param traceId         End-to-end trace identifier; may be `null`.
         * @param delegatedLineageId Lineage identity; may be `null`.
         * @param attachedSessionId  Session identifier; may be `null`.
         * @param stepIndex       Current step index; may be `null` for EXECUTION_STARTED.
         * @param reason          Human-readable reason for this event.
         * @param blockingRationale Blocking cause; non-null for [CanonicalExecutionEventType.EXECUTION_BLOCKED].
         * @param gateRationale   Gate outcome; non-null when a gate triggered the block.
         * @param postureHint     Current posture hint; may be `null`.
         * @param sourceComponent Name of the emitting component.
         * @param timestampMs     Epoch-ms production timestamp.
         * @param emissionSeq     Per-flow monotonic emission sequence number.
         * @param eventId         UUID for this emission; auto-generated if not supplied.
         */
        fun execution(
            eventType: CanonicalExecutionEventType,
            flowId: String,
            taskId: String,
            traceId: String?,
            delegatedLineageId: String?,
            attachedSessionId: String?,
            stepIndex: Int?,
            reason: String?,
            blockingRationale: String? = null,
            gateRationale: String? = null,
            postureHint: String? = null,
            sourceComponent: String,
            timestampMs: Long = System.currentTimeMillis(),
            emissionSeq: Long,
            eventId: String = java.util.UUID.randomUUID().toString()
        ): CanonicalExecutionEvent = CanonicalExecutionEvent(
            eventId = eventId,
            eventType = eventType,
            flowId = flowId,
            taskId = taskId,
            traceId = traceId,
            delegatedLineageId = delegatedLineageId,
            attachedSessionId = attachedSessionId,
            phase = PHASE_EXECUTION,
            stepIndex = stepIndex,
            reason = reason,
            blockingRationale = blockingRationale,
            gateRationale = gateRationale,
            replanReason = null,
            stagnationReason = null,
            postureHint = postureHint,
            executionContextHint = sourceComponent,
            sourceComponent = sourceComponent,
            timestampMs = timestampMs,
            emissionSeq = emissionSeq
        )

        /**
         * Produces a [CanonicalExecutionEventType.REPLAN_TRIGGERED] event.
         *
         * @param flowId          Delegated flow identifier.
         * @param taskId          Task identifier.
         * @param traceId         End-to-end trace identifier; may be `null`.
         * @param delegatedLineageId Lineage identity; may be `null`.
         * @param attachedSessionId  Session identifier; may be `null`.
         * @param stepIndex       Step index at which replan was triggered.
         * @param replanReason    Human-readable description of the replan trigger.
         * @param postureHint     Current posture hint; may be `null`.
         * @param sourceComponent Name of the emitting component.
         * @param timestampMs     Epoch-ms production timestamp.
         * @param emissionSeq     Per-flow monotonic emission sequence number.
         * @param eventId         UUID for this emission; auto-generated if not supplied.
         */
        fun replan(
            flowId: String,
            taskId: String,
            traceId: String?,
            delegatedLineageId: String?,
            attachedSessionId: String?,
            stepIndex: Int?,
            replanReason: String,
            postureHint: String? = null,
            sourceComponent: String,
            timestampMs: Long = System.currentTimeMillis(),
            emissionSeq: Long,
            eventId: String = java.util.UUID.randomUUID().toString()
        ): CanonicalExecutionEvent = CanonicalExecutionEvent(
            eventId = eventId,
            eventType = CanonicalExecutionEventType.REPLAN_TRIGGERED,
            flowId = flowId,
            taskId = taskId,
            traceId = traceId,
            delegatedLineageId = delegatedLineageId,
            attachedSessionId = attachedSessionId,
            phase = PHASE_REPLAN,
            stepIndex = stepIndex,
            reason = replanReason,
            blockingRationale = null,
            gateRationale = null,
            replanReason = replanReason,
            stagnationReason = null,
            postureHint = postureHint,
            executionContextHint = sourceComponent,
            sourceComponent = sourceComponent,
            timestampMs = timestampMs,
            emissionSeq = emissionSeq
        )

        /**
         * Produces a [CanonicalExecutionEventType.STAGNATION_DETECTED] event.
         *
         * @param flowId          Delegated flow identifier.
         * @param taskId          Task identifier.
         * @param traceId         End-to-end trace identifier; may be `null`.
         * @param delegatedLineageId Lineage identity; may be `null`.
         * @param attachedSessionId  Session identifier; may be `null`.
         * @param stepIndex       Step index at which stagnation was detected.
         * @param stagnationReason Human-readable description of the stagnation pattern.
         * @param postureHint     Current posture hint; may be `null`.
         * @param sourceComponent Name of the emitting component.
         * @param timestampMs     Epoch-ms production timestamp.
         * @param emissionSeq     Per-flow monotonic emission sequence number.
         * @param eventId         UUID for this emission; auto-generated if not supplied.
         */
        fun stagnation(
            flowId: String,
            taskId: String,
            traceId: String?,
            delegatedLineageId: String?,
            attachedSessionId: String?,
            stepIndex: Int?,
            stagnationReason: String,
            postureHint: String? = null,
            sourceComponent: String,
            timestampMs: Long = System.currentTimeMillis(),
            emissionSeq: Long,
            eventId: String = java.util.UUID.randomUUID().toString()
        ): CanonicalExecutionEvent = CanonicalExecutionEvent(
            eventId = eventId,
            eventType = CanonicalExecutionEventType.STAGNATION_DETECTED,
            flowId = flowId,
            taskId = taskId,
            traceId = traceId,
            delegatedLineageId = delegatedLineageId,
            attachedSessionId = attachedSessionId,
            phase = PHASE_STAGNATION,
            stepIndex = stepIndex,
            reason = stagnationReason,
            blockingRationale = null,
            gateRationale = null,
            replanReason = null,
            stagnationReason = stagnationReason,
            postureHint = postureHint,
            executionContextHint = sourceComponent,
            sourceComponent = sourceComponent,
            timestampMs = timestampMs,
            emissionSeq = emissionSeq
        )

        /**
         * Produces a [CanonicalExecutionEventType.GATE_DECISION_EMITTED] event.
         *
         * @param flowId          Delegated flow identifier.
         * @param taskId          Task identifier.
         * @param traceId         End-to-end trace identifier; may be `null`.
         * @param delegatedLineageId Lineage identity; may be `null`.
         * @param attachedSessionId  Session identifier; may be `null`.
         * @param gateRationale   Human-readable description of the gate and its outcome.
         * @param postureHint     Current posture hint; may be `null`.
         * @param sourceComponent Name of the emitting component.
         * @param timestampMs     Epoch-ms production timestamp.
         * @param emissionSeq     Per-flow monotonic emission sequence number.
         * @param eventId         UUID for this emission; auto-generated if not supplied.
         */
        fun gate(
            flowId: String,
            taskId: String,
            traceId: String?,
            delegatedLineageId: String?,
            attachedSessionId: String?,
            gateRationale: String,
            postureHint: String? = null,
            sourceComponent: String,
            timestampMs: Long = System.currentTimeMillis(),
            emissionSeq: Long,
            eventId: String = java.util.UUID.randomUUID().toString()
        ): CanonicalExecutionEvent = CanonicalExecutionEvent(
            eventId = eventId,
            eventType = CanonicalExecutionEventType.GATE_DECISION_EMITTED,
            flowId = flowId,
            taskId = taskId,
            traceId = traceId,
            delegatedLineageId = delegatedLineageId,
            attachedSessionId = attachedSessionId,
            phase = PHASE_GATE,
            stepIndex = null,
            reason = gateRationale,
            blockingRationale = null,
            gateRationale = gateRationale,
            replanReason = null,
            stagnationReason = null,
            postureHint = postureHint,
            executionContextHint = sourceComponent,
            sourceComponent = sourceComponent,
            timestampMs = timestampMs,
            emissionSeq = emissionSeq
        )

        /**
         * Produces a takeover / collaboration / parallel milestone [CanonicalExecutionEvent].
         *
         * @param eventType       Must be one of [CanonicalExecutionEventType.TAKEOVER_MILESTONE],
         *                        [CanonicalExecutionEventType.COLLABORATION_MILESTONE], or
         *                        [CanonicalExecutionEventType.PARALLEL_MILESTONE].
         * @param flowId          Delegated flow identifier.
         * @param taskId          Task identifier.
         * @param traceId         End-to-end trace identifier; may be `null`.
         * @param delegatedLineageId Lineage identity; may be `null`.
         * @param attachedSessionId  Session identifier; may be `null`.
         * @param milestoneName   Human-readable name of the milestone reached.
         * @param postureHint     Current posture hint; may be `null`.
         * @param executionContextHint Optional execution context description.
         * @param sourceComponent Name of the emitting component.
         * @param timestampMs     Epoch-ms production timestamp.
         * @param emissionSeq     Per-flow monotonic emission sequence number.
         * @param eventId         UUID for this emission; auto-generated if not supplied.
         */
        fun milestone(
            eventType: CanonicalExecutionEventType,
            flowId: String,
            taskId: String,
            traceId: String?,
            delegatedLineageId: String?,
            attachedSessionId: String?,
            milestoneName: String,
            postureHint: String? = null,
            executionContextHint: String? = null,
            sourceComponent: String,
            timestampMs: Long = System.currentTimeMillis(),
            emissionSeq: Long,
            eventId: String = java.util.UUID.randomUUID().toString()
        ): CanonicalExecutionEvent = CanonicalExecutionEvent(
            eventId = eventId,
            eventType = eventType,
            flowId = flowId,
            taskId = taskId,
            traceId = traceId,
            delegatedLineageId = delegatedLineageId,
            attachedSessionId = attachedSessionId,
            phase = when (eventType) {
                CanonicalExecutionEventType.TAKEOVER_MILESTONE      -> PHASE_TAKEOVER
                CanonicalExecutionEventType.COLLABORATION_MILESTONE -> PHASE_COLLABORATION
                CanonicalExecutionEventType.PARALLEL_MILESTONE      -> PHASE_PARALLEL
                else                                                -> PHASE_EXECUTION
            },
            stepIndex = null,
            reason = milestoneName,
            blockingRationale = null,
            gateRationale = null,
            replanReason = null,
            stagnationReason = null,
            postureHint = postureHint,
            executionContextHint = executionContextHint ?: sourceComponent,
            sourceComponent = sourceComponent,
            timestampMs = timestampMs,
            emissionSeq = emissionSeq
        )

        // ── Phase name constants ──────────────────────────────────────────────

        /** Phase name for planning events. */
        const val PHASE_PLANNING = "planning"

        /** Phase name for grounding events. */
        const val PHASE_GROUNDING = "grounding"

        /** Phase name for execution events. */
        const val PHASE_EXECUTION = "execution"

        /** Phase name for replan events. */
        const val PHASE_REPLAN = "replan"

        /** Phase name for stagnation events. */
        const val PHASE_STAGNATION = "stagnation"

        /** Phase name for gate decision events. */
        const val PHASE_GATE = "gate"

        /** Phase name for takeover milestone events. */
        const val PHASE_TAKEOVER = "takeover"

        /** Phase name for collaboration milestone events. */
        const val PHASE_COLLABORATION = "collaboration"

        /** Phase name for parallel milestone events. */
        const val PHASE_PARALLEL = "parallel"
    }
}

package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.DelegatedRuntimeUnit
import com.ufo.galaxy.protocol.GoalExecutionPayload

/**
 * **Android-side delegated flow bridge model** (PR-bridge, post-#533 dual-repo runtime
 * unification master plan — Android-Side Delegated Flow Bridge Model, Android side).
 *
 * [AndroidDelegatedFlowBridge] is the authoritative Android-side representation of a
 * **single canonical delegated flow** received from the V2 central orchestration network.
 * It establishes the unified identity, lineage, and execution-phase model that ties
 * together all Android-side execution components under the same flow context.
 *
 * ## Problem addressed
 *
 * Before this model, Android handled delegated work through several well-defined but
 * fragmented components:
 *
 *  - [com.ufo.galaxy.agent.DelegatedRuntimeReceiver] — gates and accepts inbound takeover envelopes
 *  - [com.ufo.galaxy.agent.DelegatedRuntimeUnit] — domain model for a takeover work unit
 *  - [com.ufo.galaxy.runtime.DelegatedActivationRecord] — lifecycle record for a takeover unit
 *  - [com.ufo.galaxy.agent.AutonomousExecutionPipeline] — gate + pipeline for goal_execution / parallel_subtask
 *  - [com.ufo.galaxy.loop.LoopController] — closed-loop planning/execution engine
 *  - [com.ufo.galaxy.agent.LocalCollaborationAgent] — parallel subtask coordinator
 *  - [com.ufo.galaxy.agent.DelegatedTakeoverExecutor] — takeover lifecycle manager + signal emitter
 *
 * Each component carried its own partial identity context (task_id, trace_id,
 * attached_session_id, etc.) but there was no **single first-class object** that:
 *
 *  1. Expressed which V2 canonical delegated flow Android is receiving.
 *  2. Expressed the current Android-side execution phase of that flow.
 *  3. Grouped goal_execution / parallel_subtask / takeover_request under the same delegated
 *     flow family when they share the same V2 flow lineage.
 *  4. Ensured that every outbound signal / result / progress message carries a stable,
 *     replayable flow identity attributed to the correct V2 canonical flow entity.
 *
 * [AndroidDelegatedFlowBridge] fills this gap.
 *
 * ## Flow identity fields
 *
 * | Field              | Description                                                                |
 * |--------------------|----------------------------------------------------------------------------|
 * | [delegatedFlowId]  | Stable identifier for this specific delegated flow instance on Android.    |
 * |                    | Sourced from the V2 wire envelope ([GoalExecutionPayload.delegated_flow_id]|
 * |                    | or [com.ufo.galaxy.agent.TakeoverRequestEnvelope.delegated_flow_id]) when  |
 * |                    | present; otherwise derived from the primary correlation key (unit_id /      |
 * |                    | task_id) of the triggering command so the bridge is always non-null.       |
 * | [flowLineageId]    | Lineage identity that groups all Android-side flows belonging to the same   |
 * |                    | V2 canonical delegated flow entity.  Sourced from the V2 wire envelope     |
 * |                    | ([GoalExecutionPayload.flow_lineage_id] / envelope equivalent) when present;|
 * |                    | otherwise defaults to [taskId] so all components handling the same task     |
 * |                    | share the same lineage.                                                    |
 *
 * ## Entry kinds
 *
 * All four V2 command types that create delegated work on Android are represented by
 * [DelegatedFlowEntryKind].  When two bridges share the same [flowLineageId] they belong
 * to the same **delegated flow family**, even if they arrived via different entry paths
 * (e.g. a `goal_execution` and a subsequent `takeover_request` for the same task).
 *
 * ## Execution phases
 *
 * [androidExecutionPhase] expresses "where in the Android execution chain is this flow now"
 * as one of the [AndroidFlowExecutionPhase] values.  Components advance the phase by calling
 * [transition], which returns a new immutable bridge in the updated phase.  See
 * [AndroidFlowExecutionPhase] for the full phase graph.
 *
 * ## Execution truth ownership
 *
 * [executionTruthOwnershipBoundary] names the Android component that currently owns
 * execution truth for this flow (i.e. the component that holds the authoritative state about
 * what the device is doing right now).  The ownership boundary shifts with [androidExecutionPhase]:
 *
 * | Phase                          | Canonical owner                          |
 * |--------------------------------|------------------------------------------|
 * | [AndroidFlowExecutionPhase.RECEIVED]              | Android side (post-gate acceptance)  |
 * | [AndroidFlowExecutionPhase.ACTIVATING]            | Android side (pre-pipeline)           |
 * | [AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION] | [com.ufo.galaxy.agent.AutonomousExecutionPipeline] |
 * | [AndroidFlowExecutionPhase.ACTIVE_LOOP]           | [com.ufo.galaxy.loop.LoopController]  |
 * | [AndroidFlowExecutionPhase.ACTIVE_COLLABORATION]  | [com.ufo.galaxy.agent.LocalCollaborationAgent] |
 * | [AndroidFlowExecutionPhase.ACTIVE_TAKEOVER]       | [com.ufo.galaxy.agent.DelegatedTakeoverExecutor] |
 * | [AndroidFlowExecutionPhase.COMPLETED] / [AndroidFlowExecutionPhase.FAILED] / [AndroidFlowExecutionPhase.REJECTED] | Result returned to V2 |
 *
 * ## Immutability
 *
 * [AndroidDelegatedFlowBridge] is immutable.  Use [transition] to produce a new instance
 * with an updated [androidExecutionPhase].  All other fields are stable across the lifecycle.
 *
 * ## Obtaining an instance
 *
 * Use the factory methods in the companion object:
 *  - [fromTakeoverUnit] — for the `takeover_request` path
 *  - [fromGoalExecution] — for the `goal_execution` path
 *  - [fromParallelSubtask] — for the `parallel_subtask` path
 *  - [fromTaskAssign] — for the `task_assign` path
 *
 * @property delegatedFlowId          Stable identifier for this delegated flow instance on Android.
 *                                     Sourced from V2 when available; derived locally otherwise.
 * @property flowLineageId            Lineage identity shared by all flows of the same V2 canonical
 *                                     delegated flow entity.  Defaults to [taskId] for legacy paths.
 * @property entryKind                Which V2 command type initiated this flow.
 * @property androidExecutionPhase    Current execution phase on Android.
 * @property taskId                   Task identifier; stable across all signals emitted for this flow.
 * @property traceId                  End-to-end trace identifier; may be null for task_assign flows.
 * @property attachedSessionId        Runtime session this flow runs under; null for task_assign flows.
 * @property unit                     The [DelegatedRuntimeUnit] produced by
 *                                     [com.ufo.galaxy.agent.DelegatedRuntimeReceiver] for
 *                                     [DelegatedFlowEntryKind.TAKEOVER_REQUEST] paths; null for
 *                                     goal_execution / parallel_subtask / task_assign paths.
 * @property activationRecord         The [DelegatedActivationRecord] tracking lifecycle status for
 *                                     [DelegatedFlowEntryKind.TAKEOVER_REQUEST] paths; null for
 *                                     goal_execution / parallel_subtask / task_assign paths.
 * @property executionTruthOwnershipBoundary  Human-readable name of the component that currently
 *                                     owns execution truth for this flow; derived from
 *                                     [androidExecutionPhase].
 */
data class AndroidDelegatedFlowBridge(
    val delegatedFlowId: String,
    val flowLineageId: String,
    val entryKind: DelegatedFlowEntryKind,
    val androidExecutionPhase: AndroidFlowExecutionPhase,
    val taskId: String,
    val traceId: String?,
    val attachedSessionId: String?,
    val unit: DelegatedRuntimeUnit? = null,
    val activationRecord: DelegatedActivationRecord? = null
) {

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * Human-readable name of the Android component that currently owns execution truth
     * for this delegated flow.
     *
     * Derived deterministically from [androidExecutionPhase]; no external state is needed.
     */
    val executionTruthOwnershipBoundary: String
        get() = when (androidExecutionPhase) {
            AndroidFlowExecutionPhase.RECEIVED       -> "android_reception_gate"
            AndroidFlowExecutionPhase.ACTIVATING     -> "android_activation_pre_pipeline"
            AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION -> "AutonomousExecutionPipeline"
            AndroidFlowExecutionPhase.ACTIVE_LOOP    -> "LoopController"
            AndroidFlowExecutionPhase.ACTIVE_COLLABORATION -> "LocalCollaborationAgent"
            AndroidFlowExecutionPhase.ACTIVE_TAKEOVER -> "DelegatedTakeoverExecutor"
            AndroidFlowExecutionPhase.COMPLETED      -> "result_returned_to_v2"
            AndroidFlowExecutionPhase.FAILED         -> "result_returned_to_v2"
            AndroidFlowExecutionPhase.REJECTED       -> "rejection_returned_to_v2"
            AndroidFlowExecutionPhase.UNKNOWN        -> "unknown"
        }

    /** `true` when this flow is in a terminal phase (COMPLETED, FAILED, or REJECTED). */
    val isTerminal: Boolean
        get() = androidExecutionPhase.isTerminal

    /** `true` when this flow is currently in one of the four active execution phases. */
    val isActiveExecution: Boolean
        get() = androidExecutionPhase.isActiveExecution

    // ── Phase transition ──────────────────────────────────────────────────────

    /**
     * Returns a new [AndroidDelegatedFlowBridge] with [androidExecutionPhase] advanced to
     * [newPhase].
     *
     * When the current phase is already [isTerminal], the original bridge is returned
     * unchanged — terminal phases are final.
     *
     * An optional [updatedActivationRecord] may be supplied when the underlying
     * [activationRecord] has also been advanced (e.g. by
     * [DelegatedActivationRecord.transition]).  If omitted, the existing record is preserved.
     *
     * @param newPhase               Target [AndroidFlowExecutionPhase].
     * @param updatedActivationRecord Optional new [DelegatedActivationRecord] to carry in the
     *                               returned bridge; if null, the current [activationRecord]
     *                               is preserved.
     * @return A new [AndroidDelegatedFlowBridge] with [androidExecutionPhase] = [newPhase];
     *         or this instance unchanged when the current phase is terminal.
     */
    fun transition(
        newPhase: AndroidFlowExecutionPhase,
        updatedActivationRecord: DelegatedActivationRecord? = null
    ): AndroidDelegatedFlowBridge {
        if (isTerminal) return this
        return copy(
            androidExecutionPhase = newPhase,
            activationRecord = updatedActivationRecord ?: activationRecord
        )
    }

    // ── Wire serialisation / metadata ─────────────────────────────────────────

    /**
     * Builds the canonical flow-attribution metadata map for wire transmission, signal
     * tagging, or diagnostic logging.
     *
     * This map is designed to be **merged** into any outbound signal, result, or progress
     * payload so that every message emitted for this flow carries stable, replayable flow
     * attribution that V2 can use to correlate signals with its canonical delegated flow entity.
     *
     * Keys always present:
     *  - [KEY_DELEGATED_FLOW_ID]     — stable flow instance identifier.
     *  - [KEY_FLOW_LINEAGE_ID]       — lineage identity grouping this flow's family.
     *  - [KEY_ENTRY_KIND]            — wire value of [DelegatedFlowEntryKind].
     *  - [KEY_ANDROID_EXECUTION_PHASE] — wire value of [AndroidFlowExecutionPhase].
     *  - [KEY_TASK_ID]               — task identifier.
     *  - [KEY_EXECUTION_TRUTH_OWNER] — current execution truth ownership boundary.
     *
     * Keys present when non-null:
     *  - [KEY_TRACE_ID]              — end-to-end trace identifier.
     *  - [KEY_ATTACHED_SESSION_ID]   — attached runtime session identifier.
     *
     * @return An immutable [Map] suitable for merging into AIP v3 metadata payloads or
     *         structured telemetry entries.
     */
    fun toFlowMetadataMap(): Map<String, Any> = buildMap {
        put(KEY_DELEGATED_FLOW_ID, delegatedFlowId)
        put(KEY_FLOW_LINEAGE_ID, flowLineageId)
        put(KEY_ENTRY_KIND, entryKind.wireValue)
        put(KEY_ANDROID_EXECUTION_PHASE, androidExecutionPhase.wireValue)
        put(KEY_TASK_ID, taskId)
        put(KEY_EXECUTION_TRUTH_OWNER, executionTruthOwnershipBoundary)
        traceId?.let { put(KEY_TRACE_ID, it) }
        attachedSessionId?.let { put(KEY_ATTACHED_SESSION_ID, it) }
    }

    // ── Companion / constants + factory methods ───────────────────────────────

    companion object {

        // ── Metadata keys ─────────────────────────────────────────────────────

        /** Metadata key for the stable delegated flow instance identifier. */
        const val KEY_DELEGATED_FLOW_ID = "flow_bridge_delegated_flow_id"

        /** Metadata key for the flow lineage identity. */
        const val KEY_FLOW_LINEAGE_ID = "flow_bridge_flow_lineage_id"

        /** Metadata key for the [DelegatedFlowEntryKind.wireValue] discriminator. */
        const val KEY_ENTRY_KIND = "flow_bridge_entry_kind"

        /** Metadata key for the [AndroidFlowExecutionPhase.wireValue] current phase. */
        const val KEY_ANDROID_EXECUTION_PHASE = "flow_bridge_android_execution_phase"

        /** Metadata key for the task identifier. */
        const val KEY_TASK_ID = "flow_bridge_task_id"

        /** Metadata key for the end-to-end trace identifier. Absent when null. */
        const val KEY_TRACE_ID = "flow_bridge_trace_id"

        /** Metadata key for the attached runtime session identifier. Absent when null. */
        const val KEY_ATTACHED_SESSION_ID = "flow_bridge_attached_session_id"

        /** Metadata key for the execution truth ownership boundary label. */
        const val KEY_EXECUTION_TRUTH_OWNER = "flow_bridge_execution_truth_owner"

        // ── Factory methods ───────────────────────────────────────────────────

        /**
         * Creates an [AndroidDelegatedFlowBridge] from an accepted
         * [com.ufo.galaxy.agent.DelegatedRuntimeUnit] and its initial
         * [DelegatedActivationRecord].
         *
         * This factory is the canonical entry point for the `takeover_request` path.
         * The initial phase is [AndroidFlowExecutionPhase.RECEIVED] (the gate has passed
         * but no execution pipeline has been entered yet).
         *
         * Flow identity resolution:
         *  - [delegatedFlowId] = [DelegatedRuntimeUnit.delegatedFlowId] when set by V2;
         *    otherwise falls back to [DelegatedRuntimeUnit.unitId].
         *  - [flowLineageId] = [DelegatedRuntimeUnit.flowLineageId] when set by V2;
         *    otherwise falls back to [DelegatedRuntimeUnit.taskId].
         *
         * @param unit           Accepted [DelegatedRuntimeUnit] from
         *                       [com.ufo.galaxy.agent.DelegatedRuntimeReceiver.receive].
         * @param activationRecord Initial [DelegatedActivationRecord] in
         *                       [DelegatedActivationRecord.ActivationStatus.PENDING].
         * @return A new [AndroidDelegatedFlowBridge] in [AndroidFlowExecutionPhase.RECEIVED].
         */
        fun fromTakeoverUnit(
            unit: DelegatedRuntimeUnit,
            activationRecord: DelegatedActivationRecord
        ): AndroidDelegatedFlowBridge = AndroidDelegatedFlowBridge(
            delegatedFlowId = unit.delegatedFlowId.ifEmpty { unit.unitId },
            flowLineageId = unit.flowLineageId.ifEmpty { unit.taskId },
            entryKind = DelegatedFlowEntryKind.TAKEOVER_REQUEST,
            androidExecutionPhase = AndroidFlowExecutionPhase.RECEIVED,
            taskId = unit.taskId,
            traceId = unit.traceId,
            attachedSessionId = unit.attachedSessionId,
            unit = unit,
            activationRecord = activationRecord
        )

        /**
         * Creates an [AndroidDelegatedFlowBridge] from a
         * [com.ufo.galaxy.protocol.GoalExecutionPayload] for the `goal_execution` path.
         *
         * This factory is the canonical entry point for the `goal_execution` path.
         * The initial phase is [AndroidFlowExecutionPhase.RECEIVED].
         *
         * Flow identity resolution:
         *  - [delegatedFlowId] = [GoalExecutionPayload.delegated_flow_id] when present;
         *    otherwise derived as `"goal_exec:${payload.task_id}"`.
         *  - [flowLineageId] = [GoalExecutionPayload.flow_lineage_id] when present;
         *    otherwise defaults to [GoalExecutionPayload.task_id].
         *
         * @param payload The inbound [GoalExecutionPayload] from the V2 `goal_execution` command.
         * @param attachedSessionId The current attached runtime session identifier;
         *                          null when the cross-device runtime is not active.
         * @return A new [AndroidDelegatedFlowBridge] in [AndroidFlowExecutionPhase.RECEIVED].
         */
        fun fromGoalExecution(
            payload: GoalExecutionPayload,
            attachedSessionId: String? = null
        ): AndroidDelegatedFlowBridge = AndroidDelegatedFlowBridge(
            delegatedFlowId = payload.delegated_flow_id
                ?: "goal_exec:${payload.task_id}",
            flowLineageId = payload.flow_lineage_id ?: payload.task_id,
            entryKind = DelegatedFlowEntryKind.GOAL_EXECUTION,
            androidExecutionPhase = AndroidFlowExecutionPhase.RECEIVED,
            taskId = payload.task_id,
            traceId = payload.dispatch_trace_id,
            attachedSessionId = attachedSessionId,
            unit = null,
            activationRecord = null
        )

        /**
         * Creates an [AndroidDelegatedFlowBridge] from a
         * [com.ufo.galaxy.protocol.GoalExecutionPayload] for the `parallel_subtask` path.
         *
         * This factory is the canonical entry point for the `parallel_subtask` path.
         * The initial phase is [AndroidFlowExecutionPhase.RECEIVED].
         *
         * Flow identity resolution:
         *  - [delegatedFlowId] = [GoalExecutionPayload.delegated_flow_id] when present;
         *    otherwise derived as `"subtask:${payload.group_id}:${payload.subtask_index}:${payload.task_id}"`.
         *  - [flowLineageId] = [GoalExecutionPayload.flow_lineage_id] when present;
         *    otherwise defaults to [GoalExecutionPayload.task_id].
         *
         * @param payload The inbound [GoalExecutionPayload] from the V2 `parallel_subtask` command.
         * @param attachedSessionId The current attached runtime session identifier;
         *                          null when the cross-device runtime is not active.
         * @return A new [AndroidDelegatedFlowBridge] in [AndroidFlowExecutionPhase.RECEIVED].
         */
        fun fromParallelSubtask(
            payload: GoalExecutionPayload,
            attachedSessionId: String? = null
        ): AndroidDelegatedFlowBridge = AndroidDelegatedFlowBridge(
            delegatedFlowId = payload.delegated_flow_id
                ?: "subtask:${payload.group_id}:${payload.subtask_index}:${payload.task_id}",
            flowLineageId = payload.flow_lineage_id ?: payload.task_id,
            entryKind = DelegatedFlowEntryKind.PARALLEL_SUBTASK,
            androidExecutionPhase = AndroidFlowExecutionPhase.RECEIVED,
            taskId = payload.task_id,
            traceId = payload.dispatch_trace_id,
            attachedSessionId = attachedSessionId,
            unit = null,
            activationRecord = null
        )

        /**
         * Creates an [AndroidDelegatedFlowBridge] from a task identifier for the
         * `task_assign` path.
         *
         * This factory is the canonical entry point for the legacy `task_assign` path.
         * The initial phase is [AndroidFlowExecutionPhase.RECEIVED].
         *
         * Flow identity resolution:
         *  - [delegatedFlowId] = `"task_assign:${taskId}"` (derived locally).
         *  - [flowLineageId] = [taskId] (the task is its own lineage root for task_assign).
         *
         * @param taskId    Task identifier from [com.ufo.galaxy.protocol.TaskAssignPayload.task_id].
         * @param traceId   End-to-end trace identifier when available; null for legacy senders.
         * @return A new [AndroidDelegatedFlowBridge] in [AndroidFlowExecutionPhase.RECEIVED].
         */
        fun fromTaskAssign(
            taskId: String,
            traceId: String? = null
        ): AndroidDelegatedFlowBridge = AndroidDelegatedFlowBridge(
            delegatedFlowId = "task_assign:$taskId",
            flowLineageId = taskId,
            entryKind = DelegatedFlowEntryKind.TASK_ASSIGN,
            androidExecutionPhase = AndroidFlowExecutionPhase.RECEIVED,
            taskId = taskId,
            traceId = traceId,
            attachedSessionId = null,
            unit = null,
            activationRecord = null
        )
    }
}

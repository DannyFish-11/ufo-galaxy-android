package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.DelegatedRuntimeUnit
import com.ufo.galaxy.agent.TakeoverRequestEnvelope
import com.ufo.galaxy.protocol.GoalExecutionPayload
import org.junit.Assert.*
import org.junit.Test

/**
 * **Unit tests for [AndroidDelegatedFlowBridge]** (PR-bridge, post-#533 dual-repo runtime
 * unification master plan — Android-Side Delegated Flow Bridge Model, Android side).
 *
 * ## Test matrix
 *
 * ### fromTakeoverUnit factory — flow identity
 *  - delegatedFlowId uses DelegatedRuntimeUnit.delegatedFlowId when non-empty.
 *  - delegatedFlowId falls back to unit.unitId when delegatedFlowId is empty.
 *  - flowLineageId uses DelegatedRuntimeUnit.flowLineageId when non-empty.
 *  - flowLineageId falls back to unit.taskId when flowLineageId is empty.
 *  - entryKind is TAKEOVER_REQUEST.
 *  - androidExecutionPhase is RECEIVED on creation.
 *  - taskId echoes unit.taskId.
 *  - traceId echoes unit.traceId.
 *  - attachedSessionId echoes unit.attachedSessionId.
 *  - unit reference is preserved.
 *  - activationRecord reference is preserved.
 *
 * ### fromGoalExecution factory — flow identity
 *  - delegatedFlowId uses GoalExecutionPayload.delegated_flow_id when non-null.
 *  - delegatedFlowId is derived from task_id when delegated_flow_id is null.
 *  - flowLineageId uses GoalExecutionPayload.flow_lineage_id when non-null.
 *  - flowLineageId defaults to task_id when flow_lineage_id is null.
 *  - entryKind is GOAL_EXECUTION.
 *  - androidExecutionPhase is RECEIVED.
 *  - taskId echoes payload.task_id.
 *  - unit is null.
 *  - activationRecord is null.
 *
 * ### fromParallelSubtask factory — flow identity
 *  - delegatedFlowId uses GoalExecutionPayload.delegated_flow_id when non-null.
 *  - delegatedFlowId is derived from group_id + subtask_index + task_id when null.
 *  - flowLineageId uses GoalExecutionPayload.flow_lineage_id when non-null.
 *  - flowLineageId defaults to task_id when null.
 *  - entryKind is PARALLEL_SUBTASK.
 *  - androidExecutionPhase is RECEIVED.
 *  - unit is null.
 *  - activationRecord is null.
 *
 * ### fromTaskAssign factory — flow identity
 *  - delegatedFlowId is derived as "task_assign:<taskId>".
 *  - flowLineageId equals taskId.
 *  - entryKind is TASK_ASSIGN.
 *  - androidExecutionPhase is RECEIVED.
 *  - attachedSessionId is null.
 *  - traceId is set when provided.
 *  - traceId is null when not provided.
 *
 * ### Flow lineage grouping — same flow family
 *  - Two bridges with the same flowLineageId belong to the same flow family.
 *  - Two bridges with different flowLineageId values are distinct flow families.
 *
 * ### transition() — phase advancement
 *  - transition() returns a new instance with updated androidExecutionPhase.
 *  - transition() to ACTIVE_GOAL_EXECUTION works from RECEIVED.
 *  - transition() to ACTIVE_LOOP works from ACTIVATING.
 *  - transition() to ACTIVE_COLLABORATION works from RECEIVED.
 *  - transition() to ACTIVE_TAKEOVER works from ACTIVATING.
 *  - transition() to COMPLETED works from ACTIVE_GOAL_EXECUTION.
 *  - transition() to FAILED works from ACTIVE_TAKEOVER.
 *  - transition() from a terminal phase (COMPLETED) returns the same instance.
 *  - transition() from a terminal phase (FAILED) returns the same instance.
 *  - transition() from a terminal phase (REJECTED) returns the same instance.
 *  - updatedActivationRecord is applied when provided.
 *  - updatedActivationRecord is preserved from original when not provided.
 *  - delegatedFlowId is unchanged after transition.
 *  - flowLineageId is unchanged after transition.
 *
 * ### executionTruthOwnershipBoundary
 *  - RECEIVED phase maps to "android_reception_gate".
 *  - ACTIVATING phase maps to "android_activation_pre_pipeline".
 *  - ACTIVE_GOAL_EXECUTION phase maps to "AutonomousExecutionPipeline".
 *  - ACTIVE_LOOP phase maps to "LoopController".
 *  - ACTIVE_COLLABORATION phase maps to "LocalCollaborationAgent".
 *  - ACTIVE_TAKEOVER phase maps to "DelegatedTakeoverExecutor".
 *  - COMPLETED phase maps to "result_returned_to_v2".
 *  - FAILED phase maps to "result_returned_to_v2".
 *  - REJECTED phase maps to "rejection_returned_to_v2".
 *
 * ### isTerminal / isActiveExecution
 *  - isTerminal is false for RECEIVED / ACTIVATING / ACTIVE_* phases.
 *  - isTerminal is true for COMPLETED / FAILED / REJECTED phases.
 *  - isActiveExecution is true for all four ACTIVE_* phases.
 *  - isActiveExecution is false for RECEIVED / ACTIVATING / terminal phases.
 *
 * ### toFlowMetadataMap
 *  - Contains KEY_DELEGATED_FLOW_ID, KEY_FLOW_LINEAGE_ID, KEY_ENTRY_KIND.
 *  - Contains KEY_ANDROID_EXECUTION_PHASE, KEY_TASK_ID, KEY_EXECUTION_TRUTH_OWNER.
 *  - Contains KEY_TRACE_ID when traceId is non-null.
 *  - Contains KEY_ATTACHED_SESSION_ID when attachedSessionId is non-null.
 *  - Absent KEY_TRACE_ID when traceId is null.
 *  - Absent KEY_ATTACHED_SESSION_ID when attachedSessionId is null.
 *  - Values match the bridge fields exactly.
 *
 * ### Immutability
 *  - AndroidDelegatedFlowBridge is a data class; original unchanged after transition.
 *  - Multiple transitions produce distinct instances.
 */
class AndroidDelegatedFlowBridgeTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeUnit(
        unitId: String = "unit-1",
        taskId: String = "task-1",
        traceId: String = "trace-1",
        sessionId: String = "session-1",
        delegatedFlowId: String = "",
        flowLineageId: String = ""
    ): DelegatedRuntimeUnit = DelegatedRuntimeUnit.fromEnvelope(
        envelope = TakeoverRequestEnvelope(
            takeover_id = unitId,
            task_id = taskId,
            trace_id = traceId,
            goal = "test goal",
            delegated_flow_id = delegatedFlowId.ifEmpty { null },
            flow_lineage_id = flowLineageId.ifEmpty { null }
        ),
        attachedSessionId = sessionId,
        receivedAtMs = 1_000L
    )

    private fun makeRecord(unit: DelegatedRuntimeUnit = makeUnit()): DelegatedActivationRecord =
        DelegatedActivationRecord.create(unit)

    private fun makePayload(
        taskId: String = "task-1",
        groupId: String? = null,
        subtaskIndex: Int? = null,
        delegatedFlowId: String? = null,
        flowLineageId: String? = null,
        dispatchTraceId: String? = null
    ) = GoalExecutionPayload(
        task_id = taskId,
        goal = "test goal",
        group_id = groupId,
        subtask_index = subtaskIndex,
        delegated_flow_id = delegatedFlowId,
        flow_lineage_id = flowLineageId,
        dispatch_trace_id = dispatchTraceId
    )

    // ── fromTakeoverUnit factory ───────────────────────────────────────────────

    @Test
    fun `fromTakeoverUnit - delegatedFlowId uses unit delegatedFlowId when non-empty`() {
        val unit = makeUnit(delegatedFlowId = "flow-v2-explicit")
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
        assertEquals("flow-v2-explicit", bridge.delegatedFlowId)
    }

    @Test
    fun `fromTakeoverUnit - delegatedFlowId falls back to unitId when delegatedFlowId is empty`() {
        val unit = makeUnit(unitId = "unit-xyz", delegatedFlowId = "")
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
        assertEquals("unit-xyz", bridge.delegatedFlowId)
    }

    @Test
    fun `fromTakeoverUnit - flowLineageId uses unit flowLineageId when non-empty`() {
        val unit = makeUnit(flowLineageId = "lineage-v2-explicit")
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
        assertEquals("lineage-v2-explicit", bridge.flowLineageId)
    }

    @Test
    fun `fromTakeoverUnit - flowLineageId falls back to taskId when flowLineageId is empty`() {
        val unit = makeUnit(taskId = "task-abc", flowLineageId = "")
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
        assertEquals("task-abc", bridge.flowLineageId)
    }

    @Test
    fun `fromTakeoverUnit - entryKind is TAKEOVER_REQUEST`() {
        val unit = makeUnit()
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
        assertEquals(DelegatedFlowEntryKind.TAKEOVER_REQUEST, bridge.entryKind)
    }

    @Test
    fun `fromTakeoverUnit - androidExecutionPhase is RECEIVED on creation`() {
        val unit = makeUnit()
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
        assertEquals(AndroidFlowExecutionPhase.RECEIVED, bridge.androidExecutionPhase)
    }

    @Test
    fun `fromTakeoverUnit - taskId echoes unit taskId`() {
        val unit = makeUnit(taskId = "task-42")
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
        assertEquals("task-42", bridge.taskId)
    }

    @Test
    fun `fromTakeoverUnit - traceId echoes unit traceId`() {
        val unit = makeUnit(traceId = "trace-42")
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
        assertEquals("trace-42", bridge.traceId)
    }

    @Test
    fun `fromTakeoverUnit - attachedSessionId echoes unit attachedSessionId`() {
        val unit = makeUnit(sessionId = "session-42")
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
        assertEquals("session-42", bridge.attachedSessionId)
    }

    @Test
    fun `fromTakeoverUnit - unit reference is preserved`() {
        val unit = makeUnit()
        val record = makeRecord(unit)
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, record)
        assertSame(unit, bridge.unit)
    }

    @Test
    fun `fromTakeoverUnit - activationRecord reference is preserved`() {
        val unit = makeUnit()
        val record = makeRecord(unit)
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, record)
        assertSame(record, bridge.activationRecord)
    }

    // ── fromGoalExecution factory ─────────────────────────────────────────────

    @Test
    fun `fromGoalExecution - delegatedFlowId uses payload delegated_flow_id when non-null`() {
        val payload = makePayload(delegatedFlowId = "flow-goal-explicit")
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(payload)
        assertEquals("flow-goal-explicit", bridge.delegatedFlowId)
    }

    @Test
    fun `fromGoalExecution - delegatedFlowId derived from task_id when null`() {
        val payload = makePayload(taskId = "task-goal-99", delegatedFlowId = null)
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(payload)
        assertEquals("goal_exec:task-goal-99", bridge.delegatedFlowId)
    }

    @Test
    fun `fromGoalExecution - flowLineageId uses payload flow_lineage_id when non-null`() {
        val payload = makePayload(flowLineageId = "lineage-goal-explicit")
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(payload)
        assertEquals("lineage-goal-explicit", bridge.flowLineageId)
    }

    @Test
    fun `fromGoalExecution - flowLineageId defaults to task_id when null`() {
        val payload = makePayload(taskId = "task-goal-88", flowLineageId = null)
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(payload)
        assertEquals("task-goal-88", bridge.flowLineageId)
    }

    @Test
    fun `fromGoalExecution - entryKind is GOAL_EXECUTION`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
        assertEquals(DelegatedFlowEntryKind.GOAL_EXECUTION, bridge.entryKind)
    }

    @Test
    fun `fromGoalExecution - androidExecutionPhase is RECEIVED`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
        assertEquals(AndroidFlowExecutionPhase.RECEIVED, bridge.androidExecutionPhase)
    }

    @Test
    fun `fromGoalExecution - taskId echoes payload task_id`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload(taskId = "task-goal-55"))
        assertEquals("task-goal-55", bridge.taskId)
    }

    @Test
    fun `fromGoalExecution - unit is null`() {
        assertNull(AndroidDelegatedFlowBridge.fromGoalExecution(makePayload()).unit)
    }

    @Test
    fun `fromGoalExecution - activationRecord is null`() {
        assertNull(AndroidDelegatedFlowBridge.fromGoalExecution(makePayload()).activationRecord)
    }

    @Test
    fun `fromGoalExecution - traceId is dispatch_trace_id from payload`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(
            makePayload(dispatchTraceId = "trace-dispatch-77")
        )
        assertEquals("trace-dispatch-77", bridge.traceId)
    }

    @Test
    fun `fromGoalExecution - attachedSessionId is provided value`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(
            makePayload(), attachedSessionId = "attached-session-55"
        )
        assertEquals("attached-session-55", bridge.attachedSessionId)
    }

    // ── fromParallelSubtask factory ───────────────────────────────────────────

    @Test
    fun `fromParallelSubtask - delegatedFlowId uses delegated_flow_id when non-null`() {
        val payload = makePayload(delegatedFlowId = "flow-subtask-explicit")
        val bridge = AndroidDelegatedFlowBridge.fromParallelSubtask(payload)
        assertEquals("flow-subtask-explicit", bridge.delegatedFlowId)
    }

    @Test
    fun `fromParallelSubtask - delegatedFlowId derived from group+index+taskId when null`() {
        val payload = makePayload(
            taskId = "task-sub-1",
            groupId = "grp-A",
            subtaskIndex = 2,
            delegatedFlowId = null
        )
        val bridge = AndroidDelegatedFlowBridge.fromParallelSubtask(payload)
        assertEquals("subtask:grp-A:2:task-sub-1", bridge.delegatedFlowId)
    }

    @Test
    fun `fromParallelSubtask - flowLineageId uses flow_lineage_id when non-null`() {
        val payload = makePayload(flowLineageId = "lineage-subtask-explicit")
        val bridge = AndroidDelegatedFlowBridge.fromParallelSubtask(payload)
        assertEquals("lineage-subtask-explicit", bridge.flowLineageId)
    }

    @Test
    fun `fromParallelSubtask - flowLineageId defaults to task_id when null`() {
        val payload = makePayload(taskId = "task-sub-7", flowLineageId = null)
        val bridge = AndroidDelegatedFlowBridge.fromParallelSubtask(payload)
        assertEquals("task-sub-7", bridge.flowLineageId)
    }

    @Test
    fun `fromParallelSubtask - entryKind is PARALLEL_SUBTASK`() {
        val bridge = AndroidDelegatedFlowBridge.fromParallelSubtask(makePayload())
        assertEquals(DelegatedFlowEntryKind.PARALLEL_SUBTASK, bridge.entryKind)
    }

    @Test
    fun `fromParallelSubtask - androidExecutionPhase is RECEIVED`() {
        val bridge = AndroidDelegatedFlowBridge.fromParallelSubtask(makePayload())
        assertEquals(AndroidFlowExecutionPhase.RECEIVED, bridge.androidExecutionPhase)
    }

    @Test
    fun `fromParallelSubtask - unit is null`() {
        assertNull(AndroidDelegatedFlowBridge.fromParallelSubtask(makePayload()).unit)
    }

    @Test
    fun `fromParallelSubtask - activationRecord is null`() {
        assertNull(AndroidDelegatedFlowBridge.fromParallelSubtask(makePayload()).activationRecord)
    }

    // ── fromTaskAssign factory ────────────────────────────────────────────────

    @Test
    fun `fromTaskAssign - delegatedFlowId is derived as task_assign prefix`() {
        val bridge = AndroidDelegatedFlowBridge.fromTaskAssign("task-assign-99")
        assertEquals("task_assign:task-assign-99", bridge.delegatedFlowId)
    }

    @Test
    fun `fromTaskAssign - flowLineageId equals taskId`() {
        val bridge = AndroidDelegatedFlowBridge.fromTaskAssign("task-assign-99")
        assertEquals("task-assign-99", bridge.flowLineageId)
    }

    @Test
    fun `fromTaskAssign - entryKind is TASK_ASSIGN`() {
        val bridge = AndroidDelegatedFlowBridge.fromTaskAssign("t-1")
        assertEquals(DelegatedFlowEntryKind.TASK_ASSIGN, bridge.entryKind)
    }

    @Test
    fun `fromTaskAssign - androidExecutionPhase is RECEIVED`() {
        val bridge = AndroidDelegatedFlowBridge.fromTaskAssign("t-1")
        assertEquals(AndroidFlowExecutionPhase.RECEIVED, bridge.androidExecutionPhase)
    }

    @Test
    fun `fromTaskAssign - attachedSessionId is null`() {
        assertNull(AndroidDelegatedFlowBridge.fromTaskAssign("t-1").attachedSessionId)
    }

    @Test
    fun `fromTaskAssign - traceId is set when provided`() {
        val bridge = AndroidDelegatedFlowBridge.fromTaskAssign("t-1", traceId = "trace-99")
        assertEquals("trace-99", bridge.traceId)
    }

    @Test
    fun `fromTaskAssign - traceId is null when not provided`() {
        assertNull(AndroidDelegatedFlowBridge.fromTaskAssign("t-1").traceId)
    }

    // ── Flow lineage grouping ─────────────────────────────────────────────────

    @Test
    fun `flow bridges with same flowLineageId are in the same flow family`() {
        val sharedLineage = "lineage-canonical-42"
        val payload = makePayload(taskId = "task-1", flowLineageId = sharedLineage)
        val unit = makeUnit(taskId = "task-1", flowLineageId = sharedLineage)

        val goalBridge = AndroidDelegatedFlowBridge.fromGoalExecution(payload)
        val takeoverBridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))

        assertEquals(goalBridge.flowLineageId, takeoverBridge.flowLineageId)
        assertEquals(sharedLineage, goalBridge.flowLineageId)
    }

    @Test
    fun `flow bridges with different flowLineageId values are distinct flow families`() {
        val bridge1 = AndroidDelegatedFlowBridge.fromTaskAssign("task-A")
        val bridge2 = AndroidDelegatedFlowBridge.fromTaskAssign("task-B")
        assertNotEquals(bridge1.flowLineageId, bridge2.flowLineageId)
    }

    // ── transition() ─────────────────────────────────────────────────────────

    @Test
    fun `transition returns new instance with updated androidExecutionPhase`() {
        val unit = makeUnit()
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
        val advanced = bridge.transition(AndroidFlowExecutionPhase.ACTIVE_TAKEOVER)
        assertEquals(AndroidFlowExecutionPhase.ACTIVE_TAKEOVER, advanced.androidExecutionPhase)
    }

    @Test
    fun `transition to ACTIVE_GOAL_EXECUTION from RECEIVED works`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
        val advanced = bridge.transition(AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION)
        assertEquals(AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION, advanced.androidExecutionPhase)
    }

    @Test
    fun `transition to ACTIVE_LOOP from ACTIVATING works`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.ACTIVATING)
        val advanced = bridge.transition(AndroidFlowExecutionPhase.ACTIVE_LOOP)
        assertEquals(AndroidFlowExecutionPhase.ACTIVE_LOOP, advanced.androidExecutionPhase)
    }

    @Test
    fun `transition to ACTIVE_COLLABORATION from RECEIVED works`() {
        val bridge = AndroidDelegatedFlowBridge.fromParallelSubtask(makePayload())
        val advanced = bridge.transition(AndroidFlowExecutionPhase.ACTIVE_COLLABORATION)
        assertEquals(AndroidFlowExecutionPhase.ACTIVE_COLLABORATION, advanced.androidExecutionPhase)
    }

    @Test
    fun `transition to ACTIVE_TAKEOVER from ACTIVATING works`() {
        val unit = makeUnit()
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
            .transition(AndroidFlowExecutionPhase.ACTIVATING)
        val advanced = bridge.transition(AndroidFlowExecutionPhase.ACTIVE_TAKEOVER)
        assertEquals(AndroidFlowExecutionPhase.ACTIVE_TAKEOVER, advanced.androidExecutionPhase)
    }

    @Test
    fun `transition to COMPLETED from ACTIVE_GOAL_EXECUTION works`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION)
        val terminal = bridge.transition(AndroidFlowExecutionPhase.COMPLETED)
        assertEquals(AndroidFlowExecutionPhase.COMPLETED, terminal.androidExecutionPhase)
    }

    @Test
    fun `transition to FAILED from ACTIVE_TAKEOVER works`() {
        val unit = makeUnit()
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
            .transition(AndroidFlowExecutionPhase.ACTIVE_TAKEOVER)
        val terminal = bridge.transition(AndroidFlowExecutionPhase.FAILED)
        assertEquals(AndroidFlowExecutionPhase.FAILED, terminal.androidExecutionPhase)
    }

    @Test
    fun `transition from COMPLETED returns the same instance`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.COMPLETED)
        val attempted = bridge.transition(AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION)
        assertSame(bridge, attempted)
    }

    @Test
    fun `transition from FAILED returns the same instance`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.FAILED)
        val attempted = bridge.transition(AndroidFlowExecutionPhase.ACTIVE_LOOP)
        assertSame(bridge, attempted)
    }

    @Test
    fun `transition from REJECTED returns the same instance`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.REJECTED)
        val attempted = bridge.transition(AndroidFlowExecutionPhase.RECEIVED)
        assertSame(bridge, attempted)
    }

    @Test
    fun `transition with updatedActivationRecord replaces the record`() {
        val unit = makeUnit()
        val record = makeRecord(unit)
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, record)
        val updatedRecord = record.transition(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
        val advanced = bridge.transition(
            AndroidFlowExecutionPhase.ACTIVATING,
            updatedActivationRecord = updatedRecord
        )
        assertSame(updatedRecord, advanced.activationRecord)
    }

    @Test
    fun `transition without updatedActivationRecord preserves original record`() {
        val unit = makeUnit()
        val record = makeRecord(unit)
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, record)
        val advanced = bridge.transition(AndroidFlowExecutionPhase.ACTIVATING)
        assertSame(record, advanced.activationRecord)
    }

    @Test
    fun `transition delegatedFlowId is unchanged after phase advance`() {
        val unit = makeUnit(delegatedFlowId = "flow-stable-123")
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
        val advanced = bridge.transition(AndroidFlowExecutionPhase.ACTIVE_TAKEOVER)
        assertEquals("flow-stable-123", advanced.delegatedFlowId)
    }

    @Test
    fun `transition flowLineageId is unchanged after phase advance`() {
        val unit = makeUnit(flowLineageId = "lineage-stable-456")
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
        val advanced = bridge.transition(AndroidFlowExecutionPhase.ACTIVE_TAKEOVER)
        assertEquals("lineage-stable-456", advanced.flowLineageId)
    }

    // ── executionTruthOwnershipBoundary ───────────────────────────────────────

    @Test
    fun `RECEIVED phase maps to android_reception_gate`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
        assertEquals("android_reception_gate", bridge.executionTruthOwnershipBoundary)
    }

    @Test
    fun `ACTIVATING phase maps to android_activation_pre_pipeline`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.ACTIVATING)
        assertEquals("android_activation_pre_pipeline", bridge.executionTruthOwnershipBoundary)
    }

    @Test
    fun `ACTIVE_GOAL_EXECUTION phase maps to AutonomousExecutionPipeline`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION)
        assertEquals("AutonomousExecutionPipeline", bridge.executionTruthOwnershipBoundary)
    }

    @Test
    fun `ACTIVE_LOOP phase maps to LoopController`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.ACTIVE_LOOP)
        assertEquals("LoopController", bridge.executionTruthOwnershipBoundary)
    }

    @Test
    fun `ACTIVE_COLLABORATION phase maps to LocalCollaborationAgent`() {
        val bridge = AndroidDelegatedFlowBridge.fromParallelSubtask(makePayload())
            .transition(AndroidFlowExecutionPhase.ACTIVE_COLLABORATION)
        assertEquals("LocalCollaborationAgent", bridge.executionTruthOwnershipBoundary)
    }

    @Test
    fun `ACTIVE_TAKEOVER phase maps to DelegatedTakeoverExecutor`() {
        val unit = makeUnit()
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
            .transition(AndroidFlowExecutionPhase.ACTIVE_TAKEOVER)
        assertEquals("DelegatedTakeoverExecutor", bridge.executionTruthOwnershipBoundary)
    }

    @Test
    fun `COMPLETED phase maps to result_returned_to_v2`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.COMPLETED)
        assertEquals("result_returned_to_v2", bridge.executionTruthOwnershipBoundary)
    }

    @Test
    fun `FAILED phase maps to result_returned_to_v2`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.FAILED)
        assertEquals("result_returned_to_v2", bridge.executionTruthOwnershipBoundary)
    }

    @Test
    fun `REJECTED phase maps to rejection_returned_to_v2`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.REJECTED)
        assertEquals("rejection_returned_to_v2", bridge.executionTruthOwnershipBoundary)
    }

    // ── isTerminal / isActiveExecution ────────────────────────────────────────

    @Test
    fun `isTerminal is false for RECEIVED`() {
        assertFalse(AndroidDelegatedFlowBridge.fromGoalExecution(makePayload()).isTerminal)
    }

    @Test
    fun `isTerminal is false for ACTIVATING`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.ACTIVATING)
        assertFalse(bridge.isTerminal)
    }

    @Test
    fun `isTerminal is false for ACTIVE_GOAL_EXECUTION`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION)
        assertFalse(bridge.isTerminal)
    }

    @Test
    fun `isTerminal is true for COMPLETED`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.COMPLETED)
        assertTrue(bridge.isTerminal)
    }

    @Test
    fun `isTerminal is true for FAILED`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.FAILED)
        assertTrue(bridge.isTerminal)
    }

    @Test
    fun `isTerminal is true for REJECTED`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.REJECTED)
        assertTrue(bridge.isTerminal)
    }

    @Test
    fun `isActiveExecution is true for ACTIVE_GOAL_EXECUTION`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION)
        assertTrue(bridge.isActiveExecution)
    }

    @Test
    fun `isActiveExecution is true for ACTIVE_LOOP`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.ACTIVE_LOOP)
        assertTrue(bridge.isActiveExecution)
    }

    @Test
    fun `isActiveExecution is true for ACTIVE_COLLABORATION`() {
        val bridge = AndroidDelegatedFlowBridge.fromParallelSubtask(makePayload())
            .transition(AndroidFlowExecutionPhase.ACTIVE_COLLABORATION)
        assertTrue(bridge.isActiveExecution)
    }

    @Test
    fun `isActiveExecution is true for ACTIVE_TAKEOVER`() {
        val unit = makeUnit()
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
            .transition(AndroidFlowExecutionPhase.ACTIVE_TAKEOVER)
        assertTrue(bridge.isActiveExecution)
    }

    @Test
    fun `isActiveExecution is false for RECEIVED`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
        assertFalse(bridge.isActiveExecution)
    }

    @Test
    fun `isActiveExecution is false for ACTIVATING`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.ACTIVATING)
        assertFalse(bridge.isActiveExecution)
    }

    @Test
    fun `isActiveExecution is false for COMPLETED`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.COMPLETED)
        assertFalse(bridge.isActiveExecution)
    }

    // ── toFlowMetadataMap ─────────────────────────────────────────────────────

    @Test
    fun `toFlowMetadataMap contains KEY_DELEGATED_FLOW_ID`() {
        val unit = makeUnit(delegatedFlowId = "flow-meta-1")
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
        val map = bridge.toFlowMetadataMap()
        assertTrue(map.containsKey(AndroidDelegatedFlowBridge.KEY_DELEGATED_FLOW_ID))
        assertEquals("flow-meta-1", map[AndroidDelegatedFlowBridge.KEY_DELEGATED_FLOW_ID])
    }

    @Test
    fun `toFlowMetadataMap contains KEY_FLOW_LINEAGE_ID`() {
        val unit = makeUnit(flowLineageId = "lineage-meta-1")
        val bridge = AndroidDelegatedFlowBridge.fromTakeoverUnit(unit, makeRecord(unit))
        val map = bridge.toFlowMetadataMap()
        assertTrue(map.containsKey(AndroidDelegatedFlowBridge.KEY_FLOW_LINEAGE_ID))
        assertEquals("lineage-meta-1", map[AndroidDelegatedFlowBridge.KEY_FLOW_LINEAGE_ID])
    }

    @Test
    fun `toFlowMetadataMap contains KEY_ENTRY_KIND`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
        val map = bridge.toFlowMetadataMap()
        assertEquals(
            DelegatedFlowEntryKind.GOAL_EXECUTION.wireValue,
            map[AndroidDelegatedFlowBridge.KEY_ENTRY_KIND]
        )
    }

    @Test
    fun `toFlowMetadataMap contains KEY_ANDROID_EXECUTION_PHASE`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION)
        val map = bridge.toFlowMetadataMap()
        assertEquals(
            AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION.wireValue,
            map[AndroidDelegatedFlowBridge.KEY_ANDROID_EXECUTION_PHASE]
        )
    }

    @Test
    fun `toFlowMetadataMap contains KEY_TASK_ID`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload(taskId = "task-map-7"))
        val map = bridge.toFlowMetadataMap()
        assertEquals("task-map-7", map[AndroidDelegatedFlowBridge.KEY_TASK_ID])
    }

    @Test
    fun `toFlowMetadataMap contains KEY_EXECUTION_TRUTH_OWNER`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
            .transition(AndroidFlowExecutionPhase.ACTIVE_LOOP)
        val map = bridge.toFlowMetadataMap()
        assertEquals("LoopController", map[AndroidDelegatedFlowBridge.KEY_EXECUTION_TRUTH_OWNER])
    }

    @Test
    fun `toFlowMetadataMap contains KEY_TRACE_ID when traceId is non-null`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(
            makePayload(dispatchTraceId = "trace-map-8")
        )
        val map = bridge.toFlowMetadataMap()
        assertTrue(map.containsKey(AndroidDelegatedFlowBridge.KEY_TRACE_ID))
        assertEquals("trace-map-8", map[AndroidDelegatedFlowBridge.KEY_TRACE_ID])
    }

    @Test
    fun `toFlowMetadataMap absent KEY_TRACE_ID when traceId is null`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload(dispatchTraceId = null))
        val map = bridge.toFlowMetadataMap()
        assertFalse(map.containsKey(AndroidDelegatedFlowBridge.KEY_TRACE_ID))
    }

    @Test
    fun `toFlowMetadataMap contains KEY_ATTACHED_SESSION_ID when present`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(
            makePayload(), attachedSessionId = "session-map-5"
        )
        val map = bridge.toFlowMetadataMap()
        assertTrue(map.containsKey(AndroidDelegatedFlowBridge.KEY_ATTACHED_SESSION_ID))
        assertEquals("session-map-5", map[AndroidDelegatedFlowBridge.KEY_ATTACHED_SESSION_ID])
    }

    @Test
    fun `toFlowMetadataMap absent KEY_ATTACHED_SESSION_ID when null`() {
        val bridge = AndroidDelegatedFlowBridge.fromTaskAssign("t-1")
        val map = bridge.toFlowMetadataMap()
        assertFalse(map.containsKey(AndroidDelegatedFlowBridge.KEY_ATTACHED_SESSION_ID))
    }

    // ── Immutability ──────────────────────────────────────────────────────────

    @Test
    fun `original bridge is unchanged after transition`() {
        val bridge = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
        val original = bridge.copy()
        bridge.transition(AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION)
        assertEquals(AndroidFlowExecutionPhase.RECEIVED, bridge.androidExecutionPhase)
        assertEquals(original, bridge)
    }

    @Test
    fun `multiple transitions produce distinct instances`() {
        val bridge0 = AndroidDelegatedFlowBridge.fromGoalExecution(makePayload())
        val bridge1 = bridge0.transition(AndroidFlowExecutionPhase.ACTIVATING)
        val bridge2 = bridge1.transition(AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION)
        assertNotSame(bridge0, bridge1)
        assertNotSame(bridge1, bridge2)
        assertNotSame(bridge0, bridge2)
        assertEquals(AndroidFlowExecutionPhase.RECEIVED, bridge0.androidExecutionPhase)
        assertEquals(AndroidFlowExecutionPhase.ACTIVATING, bridge1.androidExecutionPhase)
        assertEquals(AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION, bridge2.androidExecutionPhase)
    }
}

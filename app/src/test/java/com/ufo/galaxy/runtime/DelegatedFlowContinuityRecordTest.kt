package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.DelegatedRuntimeUnit
import com.ufo.galaxy.runtime.DelegatedActivationRecord
import org.junit.Assert.*
import org.junit.Test

/**
 * PR-2 (Android) — Unit tests for [DelegatedFlowContinuityRecord].
 *
 * ## Test matrix
 *
 * ### [DelegatedFlowContinuityRecord.fromTracker] factory
 *  - flowId resolves to delegatedFlowId when non-empty.
 *  - flowId falls back to unitId when delegatedFlowId is empty.
 *  - flowLineageId resolves to flowLineageId when non-empty.
 *  - flowLineageId falls back to taskId when flowLineageId is empty.
 *  - All identity fields are propagated correctly.
 *  - durableSessionId is taken from the DurableSessionContinuityRecord.
 *  - executionPhase wire value is stored.
 *  - continuityToken is stored when provided.
 *  - continuationToken is taken from the tracker's unit.
 *  - activatedAtMs, executionStartedAtMs, stepCount, lastStepAtMs are propagated.
 *  - savedAtMs defaults to a reasonable timestamp.
 *
 * ### [DelegatedFlowContinuityRecord.toMetadataMap]
 *  - All mandatory keys are present in the map.
 *  - Optional keys (continuityToken, continuationToken, executionStartedAtMs,
 *    lastStepAtMs) are absent when null.
 *  - Optional keys are present when non-null.
 *  - Values match the record fields.
 *
 * ### [DelegatedFlowContinuityRecord.isTerminalPhase]
 *  - Returns true for COMPLETED, FAILED, REJECTED phases.
 *  - Returns false for non-terminal phases.
 *
 * ### [DelegatedFlowContinuityRecord.hasContinuityToken]
 *  - Returns true when continuityToken is non-null and non-blank.
 *  - Returns false when continuityToken is null.
 *  - Returns false when continuityToken is blank.
 *
 * ### Wire key constants
 *  - All KEY_* constants have the expected stable string values.
 *  - ALL_MANDATORY_KEYS contains exactly the expected set.
 */
class DelegatedFlowContinuityRecordTest {

    // ── Test helpers ──────────────────────────────────────────────────────────

    private fun makeUnit(
        unitId: String = "unit-1",
        taskId: String = "task-1",
        traceId: String = "trace-1",
        sessionId: String = "session-abc",
        delegatedFlowId: String = "flow-xyz",
        flowLineageId: String = "lineage-lmn",
        continuationToken: String? = null
    ) = DelegatedRuntimeUnit(
        unitId = unitId,
        taskId = taskId,
        traceId = traceId,
        goal = "open gallery",
        attachedSessionId = sessionId,
        delegatedFlowId = delegatedFlowId,
        flowLineageId = flowLineageId,
        continuationToken = continuationToken
    )

    private fun pendingRecord(
        unit: DelegatedRuntimeUnit = makeUnit(),
        activatedAtMs: Long = 1_000L
    ) = DelegatedActivationRecord.create(unit = unit, activatedAtMs = activatedAtMs)

    private fun freshTracker(
        unit: DelegatedRuntimeUnit = makeUnit(),
        activatedAtMs: Long = 1_000L
    ) = DelegatedExecutionTracker.create(record = pendingRecord(unit = unit, activatedAtMs = activatedAtMs))

    private fun durableSession(
        durableSessionId: String = "durable-session-uuid",
        activationSource: String = "user_activation"
    ) = DurableSessionContinuityRecord(
        durableSessionId = durableSessionId,
        sessionContinuityEpoch = 0,
        activationEpochMs = 500L,
        activationSource = activationSource
    )

    // ── fromTracker: flow identity resolution ─────────────────────────────────

    @Test
    fun `fromTracker uses delegatedFlowId when non-empty`() {
        val tracker = freshTracker(unit = makeUnit(delegatedFlowId = "flow-explicit"))
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.RECEIVED
        )
        assertEquals("flow-explicit", record.flowId)
    }

    @Test
    fun `fromTracker falls back to unitId when delegatedFlowId is empty`() {
        val tracker = freshTracker(unit = makeUnit(unitId = "unit-fallback", delegatedFlowId = ""))
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.RECEIVED
        )
        assertEquals("unit-fallback", record.flowId)
    }

    @Test
    fun `fromTracker uses flowLineageId when non-empty`() {
        val tracker = freshTracker(unit = makeUnit(flowLineageId = "lineage-explicit"))
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.RECEIVED
        )
        assertEquals("lineage-explicit", record.flowLineageId)
    }

    @Test
    fun `fromTracker falls back to taskId when flowLineageId is empty`() {
        val tracker = freshTracker(unit = makeUnit(taskId = "task-lineage-fallback", flowLineageId = ""))
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.RECEIVED
        )
        assertEquals("task-lineage-fallback", record.flowLineageId)
    }

    // ── fromTracker: identity fields ──────────────────────────────────────────

    @Test
    fun `fromTracker propagates unitId`() {
        val tracker = freshTracker(unit = makeUnit(unitId = "u-99"))
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.ACTIVATING
        )
        assertEquals("u-99", record.unitId)
    }

    @Test
    fun `fromTracker propagates taskId`() {
        val tracker = freshTracker(unit = makeUnit(taskId = "t-88"))
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.ACTIVATING
        )
        assertEquals("t-88", record.taskId)
    }

    @Test
    fun `fromTracker propagates traceId`() {
        val tracker = freshTracker(unit = makeUnit(traceId = "trace-77"))
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.ACTIVATING
        )
        assertEquals("trace-77", record.traceId)
    }

    @Test
    fun `fromTracker propagates attachedSessionId`() {
        val tracker = freshTracker(unit = makeUnit(sessionId = "session-66"))
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.ACTIVATING
        )
        assertEquals("session-66", record.attachedSessionId)
    }

    @Test
    fun `fromTracker takes durableSessionId from DurableSessionContinuityRecord`() {
        val tracker = freshTracker()
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(durableSessionId = "durable-uuid-abc"),
            executionPhase = AndroidFlowExecutionPhase.RECEIVED
        )
        assertEquals("durable-uuid-abc", record.durableSessionId)
    }

    @Test
    fun `fromTracker stores execution phase wire value`() {
        val tracker = freshTracker()
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION
        )
        assertEquals("active_goal_execution", record.executionPhase)
    }

    @Test
    fun `fromTracker stores continuityToken when provided`() {
        val tracker = freshTracker()
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.RECEIVED,
            continuityToken = "ct-v2-token"
        )
        assertEquals("ct-v2-token", record.continuityToken)
    }

    @Test
    fun `fromTracker continuityToken is null when not provided`() {
        val tracker = freshTracker()
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.RECEIVED
        )
        assertNull(record.continuityToken)
    }

    @Test
    fun `fromTracker takes continuationToken from tracker unit`() {
        val tracker = freshTracker(unit = makeUnit(continuationToken = "ct-exec-handoff"))
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.RECEIVED
        )
        assertEquals("ct-exec-handoff", record.continuationToken)
    }

    @Test
    fun `fromTracker propagates activatedAtMs from activation record`() {
        val tracker = freshTracker(activatedAtMs = 42_000L)
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.RECEIVED
        )
        assertEquals(42_000L, record.activatedAtMs)
    }

    @Test
    fun `fromTracker propagates executionStartedAtMs when execution has started`() {
        val tracker = freshTracker().markExecutionStarted(startedAtMs = 50_000L)
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.ACTIVATING
        )
        assertEquals(50_000L, record.executionStartedAtMs)
    }

    @Test
    fun `fromTracker executionStartedAtMs is null before execution starts`() {
        val tracker = freshTracker()
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.RECEIVED
        )
        assertNull(record.executionStartedAtMs)
    }

    @Test
    fun `fromTracker propagates stepCount`() {
        val tracker = freshTracker().recordStep(1000L).recordStep(2000L)
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.ACTIVE_LOOP
        )
        assertEquals(2, record.stepCount)
    }

    @Test
    fun `fromTracker propagates lastStepAtMs`() {
        val tracker = freshTracker().recordStep(1000L).recordStep(9000L)
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.ACTIVE_LOOP
        )
        assertEquals(9000L, record.lastStepAtMs)
    }

    @Test
    fun `fromTracker lastStepAtMs is null when no steps recorded`() {
        val tracker = freshTracker()
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.RECEIVED
        )
        assertNull(record.lastStepAtMs)
    }

    @Test
    fun `fromTracker savedAtMs uses provided value`() {
        val tracker = freshTracker()
        val record = DelegatedFlowContinuityRecord.fromTracker(
            tracker = tracker,
            durableSession = durableSession(),
            executionPhase = AndroidFlowExecutionPhase.RECEIVED,
            savedAtMs = 77_000L
        )
        assertEquals(77_000L, record.savedAtMs)
    }

    // ── toMetadataMap — mandatory keys ────────────────────────────────────────

    @Test
    fun `toMetadataMap contains all mandatory keys`() {
        val record = makeRecord()
        val map = record.toMetadataMap()
        for (key in DelegatedFlowContinuityRecord.ALL_MANDATORY_KEYS) {
            assertTrue("Mandatory key '$key' must be present in toMetadataMap", map.containsKey(key))
        }
    }

    @Test
    fun `toMetadataMap flowId value matches field`() {
        val record = makeRecord(flowId = "map-flow-1")
        assertEquals("map-flow-1", record.toMetadataMap()[DelegatedFlowContinuityRecord.KEY_FLOW_ID])
    }

    @Test
    fun `toMetadataMap flowLineageId value matches field`() {
        val record = makeRecord(flowLineageId = "map-lineage-1")
        assertEquals("map-lineage-1", record.toMetadataMap()[DelegatedFlowContinuityRecord.KEY_FLOW_LINEAGE_ID])
    }

    @Test
    fun `toMetadataMap executionPhase value matches field`() {
        val record = makeRecord(executionPhase = "active_loop")
        assertEquals("active_loop", record.toMetadataMap()[DelegatedFlowContinuityRecord.KEY_EXECUTION_PHASE])
    }

    // ── toMetadataMap — optional keys absent when null ────────────────────────

    @Test
    fun `toMetadataMap continuityToken absent when null`() {
        val record = makeRecord(continuityToken = null)
        assertFalse(record.toMetadataMap().containsKey(DelegatedFlowContinuityRecord.KEY_CONTINUITY_TOKEN))
    }

    @Test
    fun `toMetadataMap continuationToken absent when null`() {
        val record = makeRecord(continuationToken = null)
        assertFalse(record.toMetadataMap().containsKey(DelegatedFlowContinuityRecord.KEY_CONTINUATION_TOKEN))
    }

    @Test
    fun `toMetadataMap executionStartedAtMs absent when null`() {
        val record = makeRecord(executionStartedAtMs = null)
        assertFalse(record.toMetadataMap().containsKey(DelegatedFlowContinuityRecord.KEY_EXECUTION_STARTED_AT_MS))
    }

    @Test
    fun `toMetadataMap lastStepAtMs absent when null`() {
        val record = makeRecord(lastStepAtMs = null)
        assertFalse(record.toMetadataMap().containsKey(DelegatedFlowContinuityRecord.KEY_LAST_STEP_AT_MS))
    }

    // ── toMetadataMap — optional keys present when non-null ──────────────────

    @Test
    fun `toMetadataMap continuityToken present when non-null`() {
        val record = makeRecord(continuityToken = "ct-present")
        assertEquals("ct-present", record.toMetadataMap()[DelegatedFlowContinuityRecord.KEY_CONTINUITY_TOKEN])
    }

    @Test
    fun `toMetadataMap continuationToken present when non-null`() {
        val record = makeRecord(continuationToken = "ctoken-present")
        assertEquals("ctoken-present", record.toMetadataMap()[DelegatedFlowContinuityRecord.KEY_CONTINUATION_TOKEN])
    }

    @Test
    fun `toMetadataMap executionStartedAtMs present when non-null`() {
        val record = makeRecord(executionStartedAtMs = 12_000L)
        assertEquals(12_000L, record.toMetadataMap()[DelegatedFlowContinuityRecord.KEY_EXECUTION_STARTED_AT_MS])
    }

    @Test
    fun `toMetadataMap lastStepAtMs present when non-null`() {
        val record = makeRecord(lastStepAtMs = 15_000L)
        assertEquals(15_000L, record.toMetadataMap()[DelegatedFlowContinuityRecord.KEY_LAST_STEP_AT_MS])
    }

    // ── isTerminalPhase ───────────────────────────────────────────────────────

    @Test
    fun `isTerminalPhase true for completed phase`() {
        assertTrue(makeRecord(executionPhase = AndroidFlowExecutionPhase.COMPLETED.wireValue).isTerminalPhase)
    }

    @Test
    fun `isTerminalPhase true for failed phase`() {
        assertTrue(makeRecord(executionPhase = AndroidFlowExecutionPhase.FAILED.wireValue).isTerminalPhase)
    }

    @Test
    fun `isTerminalPhase true for rejected phase`() {
        assertTrue(makeRecord(executionPhase = AndroidFlowExecutionPhase.REJECTED.wireValue).isTerminalPhase)
    }

    @Test
    fun `isTerminalPhase false for received phase`() {
        assertFalse(makeRecord(executionPhase = AndroidFlowExecutionPhase.RECEIVED.wireValue).isTerminalPhase)
    }

    @Test
    fun `isTerminalPhase false for active_goal_execution phase`() {
        assertFalse(makeRecord(executionPhase = AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION.wireValue).isTerminalPhase)
    }

    @Test
    fun `isTerminalPhase false for active_loop phase`() {
        assertFalse(makeRecord(executionPhase = AndroidFlowExecutionPhase.ACTIVE_LOOP.wireValue).isTerminalPhase)
    }

    // ── hasContinuityToken ────────────────────────────────────────────────────

    @Test
    fun `hasContinuityToken true when continuityToken is non-null and non-blank`() {
        assertTrue(makeRecord(continuityToken = "ct-abc").hasContinuityToken)
    }

    @Test
    fun `hasContinuityToken false when continuityToken is null`() {
        assertFalse(makeRecord(continuityToken = null).hasContinuityToken)
    }

    @Test
    fun `hasContinuityToken false when continuityToken is blank`() {
        assertFalse(makeRecord(continuityToken = "  ").hasContinuityToken)
    }

    @Test
    fun `hasContinuityToken false when continuityToken is empty string`() {
        assertFalse(makeRecord(continuityToken = "").hasContinuityToken)
    }

    // ── Wire key constants ────────────────────────────────────────────────────

    @Test
    fun `KEY_FLOW_ID has stable value`() {
        assertEquals("flow_continuity_flow_id", DelegatedFlowContinuityRecord.KEY_FLOW_ID)
    }

    @Test
    fun `KEY_FLOW_LINEAGE_ID has stable value`() {
        assertEquals("flow_continuity_flow_lineage_id", DelegatedFlowContinuityRecord.KEY_FLOW_LINEAGE_ID)
    }

    @Test
    fun `KEY_UNIT_ID has stable value`() {
        assertEquals("flow_continuity_unit_id", DelegatedFlowContinuityRecord.KEY_UNIT_ID)
    }

    @Test
    fun `KEY_TASK_ID has stable value`() {
        assertEquals("flow_continuity_task_id", DelegatedFlowContinuityRecord.KEY_TASK_ID)
    }

    @Test
    fun `KEY_TRACE_ID has stable value`() {
        assertEquals("flow_continuity_trace_id", DelegatedFlowContinuityRecord.KEY_TRACE_ID)
    }

    @Test
    fun `KEY_DURABLE_SESSION_ID has stable value`() {
        assertEquals("flow_continuity_durable_session_id", DelegatedFlowContinuityRecord.KEY_DURABLE_SESSION_ID)
    }

    @Test
    fun `KEY_ATTACHED_SESSION_ID has stable value`() {
        assertEquals("flow_continuity_attached_session_id", DelegatedFlowContinuityRecord.KEY_ATTACHED_SESSION_ID)
    }

    @Test
    fun `KEY_EXECUTION_PHASE has stable value`() {
        assertEquals("flow_continuity_execution_phase", DelegatedFlowContinuityRecord.KEY_EXECUTION_PHASE)
    }

    @Test
    fun `KEY_CONTINUITY_TOKEN has stable value`() {
        assertEquals("flow_continuity_continuity_token", DelegatedFlowContinuityRecord.KEY_CONTINUITY_TOKEN)
    }

    @Test
    fun `KEY_CONTINUATION_TOKEN has stable value`() {
        assertEquals("flow_continuity_continuation_token", DelegatedFlowContinuityRecord.KEY_CONTINUATION_TOKEN)
    }

    @Test
    fun `KEY_ACTIVATED_AT_MS has stable value`() {
        assertEquals("flow_continuity_activated_at_ms", DelegatedFlowContinuityRecord.KEY_ACTIVATED_AT_MS)
    }

    @Test
    fun `KEY_EXECUTION_STARTED_AT_MS has stable value`() {
        assertEquals("flow_continuity_execution_started_at_ms", DelegatedFlowContinuityRecord.KEY_EXECUTION_STARTED_AT_MS)
    }

    @Test
    fun `KEY_STEP_COUNT has stable value`() {
        assertEquals("flow_continuity_step_count", DelegatedFlowContinuityRecord.KEY_STEP_COUNT)
    }

    @Test
    fun `KEY_LAST_STEP_AT_MS has stable value`() {
        assertEquals("flow_continuity_last_step_at_ms", DelegatedFlowContinuityRecord.KEY_LAST_STEP_AT_MS)
    }

    @Test
    fun `KEY_SAVED_AT_MS has stable value`() {
        assertEquals("flow_continuity_saved_at_ms", DelegatedFlowContinuityRecord.KEY_SAVED_AT_MS)
    }

    @Test
    fun `ALL_MANDATORY_KEYS contains exactly the expected 11 keys`() {
        val expected = setOf(
            "flow_continuity_flow_id",
            "flow_continuity_flow_lineage_id",
            "flow_continuity_unit_id",
            "flow_continuity_task_id",
            "flow_continuity_trace_id",
            "flow_continuity_durable_session_id",
            "flow_continuity_attached_session_id",
            "flow_continuity_execution_phase",
            "flow_continuity_activated_at_ms",
            "flow_continuity_step_count",
            "flow_continuity_saved_at_ms"
        )
        assertEquals(expected, DelegatedFlowContinuityRecord.ALL_MANDATORY_KEYS)
    }

    // ── Private builder helper ────────────────────────────────────────────────

    private fun makeRecord(
        flowId: String = "flow-test-1",
        flowLineageId: String = "lineage-test-1",
        unitId: String = "unit-test-1",
        taskId: String = "task-test-1",
        traceId: String = "trace-test-1",
        durableSessionId: String = "durable-test-1",
        attachedSessionId: String = "session-test-1",
        executionPhase: String = AndroidFlowExecutionPhase.RECEIVED.wireValue,
        continuityToken: String? = "ct-token",
        continuationToken: String? = null,
        activatedAtMs: Long = 1_000L,
        executionStartedAtMs: Long? = null,
        stepCount: Int = 0,
        lastStepAtMs: Long? = null,
        savedAtMs: Long = 2_000L
    ) = DelegatedFlowContinuityRecord(
        flowId = flowId,
        flowLineageId = flowLineageId,
        unitId = unitId,
        taskId = taskId,
        traceId = traceId,
        durableSessionId = durableSessionId,
        attachedSessionId = attachedSessionId,
        executionPhase = executionPhase,
        continuityToken = continuityToken,
        continuationToken = continuationToken,
        activatedAtMs = activatedAtMs,
        executionStartedAtMs = executionStartedAtMs,
        stepCount = stepCount,
        lastStepAtMs = lastStepAtMs,
        savedAtMs = savedAtMs
    )
}

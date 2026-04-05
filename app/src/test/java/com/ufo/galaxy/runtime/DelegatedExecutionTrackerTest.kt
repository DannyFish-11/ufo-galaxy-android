package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.DelegatedHandoffContract
import com.ufo.galaxy.agent.DelegatedRuntimeUnit
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [DelegatedExecutionTracker] — the canonical Android-side
 * delegated-runtime execution-tracking state (PR-10, post-#533 dual-repo runtime
 * unification master plan — Android-Side Delegated-Runtime Execution-Tracking and
 * Acknowledgment Basis, Android side).
 *
 * ## Test matrix
 *
 * ### [DelegatedExecutionTracker.create] factory
 *  - stepCount starts at zero.
 *  - executionStartedAtMs starts null.
 *  - lastStepAtMs starts null.
 *  - record is preserved exactly.
 *  - handoffContractVersion equals DelegatedHandoffContract.CURRENT_CONTRACT_VERSION.
 *
 * ### Convenience accessors
 *  - unitId, taskId, traceId, attachedSessionId echo the underlying record/unit.
 *  - isTerminal / isActive delegate to the underlying record.
 *
 * ### [DelegatedExecutionTracker.elapsedMs]
 *  - Returns null when executionStartedAtMs is null.
 *  - Returns the correct difference when set.
 *
 * ### [DelegatedExecutionTracker.advance]
 *  - Returns new tracker with updated record status.
 *  - Does not mutate original.
 *  - Returns original unchanged when record is terminal.
 *
 * ### [DelegatedExecutionTracker.recordStep]
 *  - Increments stepCount by one.
 *  - Updates lastStepAtMs.
 *  - Is a no-op on terminal trackers.
 *  - Multiple calls accumulate correctly.
 *
 * ### [DelegatedExecutionTracker.markExecutionStarted]
 *  - Sets executionStartedAtMs on first call.
 *  - Does not overwrite executionStartedAtMs on subsequent calls.
 *  - Is a no-op on terminal trackers.
 *
 * ### [DelegatedExecutionTracker.toMetadataMap]
 *  - Contains all required keys.
 *  - executionStartedAtMs key absent when null.
 *  - lastStepAtMs key absent when null.
 *  - activation_status reflects current record status.
 *  - step_count reflects accumulated steps.
 *
 * ### Metadata key constants
 *  - All KEY_* constants have stable string values.
 */
class DelegatedExecutionTrackerTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeUnit(
        unitId: String = "unit-1",
        taskId: String = "task-1",
        traceId: String = "trace-1",
        sessionId: String = "session-abc"
    ) = DelegatedRuntimeUnit(
        unitId = unitId,
        taskId = taskId,
        traceId = traceId,
        goal = "open gallery",
        attachedSessionId = sessionId
    )

    private fun pendingRecord(
        unit: DelegatedRuntimeUnit = makeUnit(),
        activatedAtMs: Long = 1_000L
    ) = DelegatedActivationRecord.create(unit = unit, activatedAtMs = activatedAtMs)

    private fun freshTracker(
        unit: DelegatedRuntimeUnit = makeUnit()
    ) = DelegatedExecutionTracker.create(record = pendingRecord(unit = unit))

    // ── create() factory ──────────────────────────────────────────────────────

    @Test
    fun `create factory stepCount starts at zero`() {
        assertEquals(0, freshTracker().stepCount)
    }

    @Test
    fun `create factory executionStartedAtMs starts null`() {
        assertNull(freshTracker().executionStartedAtMs)
    }

    @Test
    fun `create factory lastStepAtMs starts null`() {
        assertNull(freshTracker().lastStepAtMs)
    }

    @Test
    fun `create factory record is preserved exactly`() {
        val record = pendingRecord(unit = makeUnit(taskId = "preserved-task"))
        val tracker = DelegatedExecutionTracker.create(record = record)
        assertEquals(record, tracker.record)
    }

    @Test
    fun `create factory handoffContractVersion equals CURRENT_CONTRACT_VERSION`() {
        assertEquals(
            DelegatedHandoffContract.CURRENT_CONTRACT_VERSION,
            freshTracker().handoffContractVersion
        )
    }

    // ── Convenience accessors ─────────────────────────────────────────────────

    @Test
    fun `unitId echoes unit unitId`() {
        val tracker = freshTracker(unit = makeUnit(unitId = "u-42"))
        assertEquals("u-42", tracker.unitId)
    }

    @Test
    fun `taskId echoes unit taskId`() {
        val tracker = freshTracker(unit = makeUnit(taskId = "t-99"))
        assertEquals("t-99", tracker.taskId)
    }

    @Test
    fun `traceId echoes unit traceId`() {
        val tracker = freshTracker(unit = makeUnit(traceId = "tr-88"))
        assertEquals("tr-88", tracker.traceId)
    }

    @Test
    fun `attachedSessionId echoes session`() {
        val tracker = freshTracker(unit = makeUnit(sessionId = "sess-xyz"))
        assertEquals("sess-xyz", tracker.attachedSessionId)
    }

    @Test
    fun `isActive is true for fresh PENDING tracker`() {
        assertTrue(freshTracker().isActive)
    }

    @Test
    fun `isTerminal is false for fresh PENDING tracker`() {
        assertFalse(freshTracker().isTerminal)
    }

    @Test
    fun `isTerminal is true after advance to COMPLETED`() {
        val tracker = freshTracker()
            .advance(DelegatedActivationRecord.ActivationStatus.COMPLETED)
        assertTrue(tracker.isTerminal)
    }

    @Test
    fun `isActive is false after advance to FAILED`() {
        val tracker = freshTracker()
            .advance(DelegatedActivationRecord.ActivationStatus.FAILED)
        assertFalse(tracker.isActive)
    }

    // ── elapsedMs ─────────────────────────────────────────────────────────────

    @Test
    fun `elapsedMs returns null when executionStartedAtMs is null`() {
        assertNull(freshTracker().elapsedMs())
    }

    @Test
    fun `elapsedMs returns correct difference`() {
        val tracker = freshTracker().markExecutionStarted(startedAtMs = 1_000L)
        assertEquals(500L, tracker.elapsedMs(nowMs = 1_500L))
    }

    @Test
    fun `elapsedMs returns zero when nowMs equals executionStartedAtMs`() {
        val tracker = freshTracker().markExecutionStarted(startedAtMs = 5_000L)
        assertEquals(0L, tracker.elapsedMs(nowMs = 5_000L))
    }

    // ── advance ───────────────────────────────────────────────────────────────

    @Test
    fun `advance returns new tracker with updated record status`() {
        val tracker = freshTracker()
        val advanced = tracker.advance(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
        assertEquals(DelegatedActivationRecord.ActivationStatus.ACTIVATING, advanced.record.activationStatus)
    }

    @Test
    fun `advance does not mutate original`() {
        val original = freshTracker()
        original.advance(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
        assertEquals(DelegatedActivationRecord.ActivationStatus.PENDING, original.record.activationStatus)
    }

    @Test
    fun `advance on terminal tracker returns original unchanged`() {
        val terminal = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.COMPLETED)
        val attempted = terminal.advance(DelegatedActivationRecord.ActivationStatus.ACTIVE)
        assertSame(terminal, attempted)
    }

    @Test
    fun `advance preserves stepCount across status transitions`() {
        val tracker = freshTracker()
            .recordStep(stepAtMs = 100L)
            .recordStep(stepAtMs = 200L)
            .advance(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
        assertEquals(2, tracker.stepCount)
    }

    // ── recordStep ────────────────────────────────────────────────────────────

    @Test
    fun `recordStep increments stepCount by one`() {
        val tracker = freshTracker().recordStep(stepAtMs = 1_000L)
        assertEquals(1, tracker.stepCount)
    }

    @Test
    fun `recordStep updates lastStepAtMs`() {
        val tracker = freshTracker().recordStep(stepAtMs = 3_000L)
        assertEquals(3_000L, tracker.lastStepAtMs)
    }

    @Test
    fun `recordStep does not mutate original`() {
        val original = freshTracker()
        original.recordStep(stepAtMs = 1_000L)
        assertEquals(0, original.stepCount)
        assertNull(original.lastStepAtMs)
    }

    @Test
    fun `recordStep is a no-op on terminal tracker`() {
        val terminal = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.FAILED)
        val attempted = terminal.recordStep(stepAtMs = 9_000L)
        assertSame(terminal, attempted)
        assertEquals(0, terminal.stepCount)
    }

    @Test
    fun `multiple recordStep calls accumulate correctly`() {
        val tracker = freshTracker()
            .recordStep(stepAtMs = 1_000L)
            .recordStep(stepAtMs = 2_000L)
            .recordStep(stepAtMs = 3_000L)
        assertEquals(3, tracker.stepCount)
        assertEquals(3_000L, tracker.lastStepAtMs)
    }

    @Test
    fun `recordStep preserves other fields`() {
        val unit = makeUnit(taskId = "preserved-in-step")
        val tracker = freshTracker(unit = unit).recordStep(stepAtMs = 1_000L)
        assertEquals("preserved-in-step", tracker.taskId)
    }

    // ── markExecutionStarted ──────────────────────────────────────────────────

    @Test
    fun `markExecutionStarted sets executionStartedAtMs on first call`() {
        val tracker = freshTracker().markExecutionStarted(startedAtMs = 7_000L)
        assertEquals(7_000L, tracker.executionStartedAtMs)
    }

    @Test
    fun `markExecutionStarted does not overwrite on subsequent calls`() {
        val tracker = freshTracker()
            .markExecutionStarted(startedAtMs = 7_000L)
            .markExecutionStarted(startedAtMs = 8_000L)
        assertEquals(7_000L, tracker.executionStartedAtMs)
    }

    @Test
    fun `markExecutionStarted is a no-op on terminal tracker`() {
        val terminal = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.COMPLETED)
        val attempted = terminal.markExecutionStarted(startedAtMs = 9_000L)
        assertSame(terminal, attempted)
        assertNull(terminal.executionStartedAtMs)
    }

    @Test
    fun `markExecutionStarted does not mutate original`() {
        val original = freshTracker()
        original.markExecutionStarted(startedAtMs = 5_000L)
        assertNull(original.executionStartedAtMs)
    }

    // ── toMetadataMap ─────────────────────────────────────────────────────────

    @Test
    fun `toMetadataMap contains KEY_UNIT_ID`() {
        val tracker = freshTracker(unit = makeUnit(unitId = "u-map-1"))
        val map = tracker.toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionTracker.KEY_UNIT_ID))
        assertEquals("u-map-1", map[DelegatedExecutionTracker.KEY_UNIT_ID])
    }

    @Test
    fun `toMetadataMap contains KEY_TASK_ID`() {
        val tracker = freshTracker(unit = makeUnit(taskId = "t-map-2"))
        assertTrue(tracker.toMetadataMap().containsKey(DelegatedExecutionTracker.KEY_TASK_ID))
    }

    @Test
    fun `toMetadataMap contains KEY_TRACE_ID`() {
        val tracker = freshTracker(unit = makeUnit(traceId = "tr-map-3"))
        assertTrue(tracker.toMetadataMap().containsKey(DelegatedExecutionTracker.KEY_TRACE_ID))
    }

    @Test
    fun `toMetadataMap contains KEY_ATTACHED_SESSION_ID`() {
        val tracker = freshTracker(unit = makeUnit(sessionId = "sess-map-4"))
        val map = tracker.toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionTracker.KEY_ATTACHED_SESSION_ID))
        assertEquals("sess-map-4", map[DelegatedExecutionTracker.KEY_ATTACHED_SESSION_ID])
    }

    @Test
    fun `toMetadataMap contains KEY_ACTIVATION_STATUS`() {
        val tracker = freshTracker()
        assertTrue(tracker.toMetadataMap().containsKey(DelegatedExecutionTracker.KEY_ACTIVATION_STATUS))
    }

    @Test
    fun `toMetadataMap activation_status matches current wireValue`() {
        val tracker = freshTracker()
            .advance(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
        assertEquals("activating", tracker.toMetadataMap()[DelegatedExecutionTracker.KEY_ACTIVATION_STATUS])
    }

    @Test
    fun `toMetadataMap step_count reflects accumulated steps`() {
        val tracker = freshTracker()
            .recordStep(1_000L)
            .recordStep(2_000L)
        assertEquals(2, tracker.toMetadataMap()[DelegatedExecutionTracker.KEY_STEP_COUNT])
    }

    @Test
    fun `toMetadataMap executionStartedAtMs absent when null`() {
        val tracker = freshTracker()
        assertFalse(tracker.toMetadataMap().containsKey(DelegatedExecutionTracker.KEY_EXECUTION_STARTED_AT_MS))
    }

    @Test
    fun `toMetadataMap executionStartedAtMs present when set`() {
        val tracker = freshTracker().markExecutionStarted(startedAtMs = 4_000L)
        val map = tracker.toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionTracker.KEY_EXECUTION_STARTED_AT_MS))
        assertEquals(4_000L, map[DelegatedExecutionTracker.KEY_EXECUTION_STARTED_AT_MS])
    }

    @Test
    fun `toMetadataMap lastStepAtMs absent when no steps recorded`() {
        assertFalse(freshTracker().toMetadataMap().containsKey(DelegatedExecutionTracker.KEY_LAST_STEP_AT_MS))
    }

    @Test
    fun `toMetadataMap lastStepAtMs present after step recorded`() {
        val tracker = freshTracker().recordStep(stepAtMs = 6_000L)
        val map = tracker.toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionTracker.KEY_LAST_STEP_AT_MS))
        assertEquals(6_000L, map[DelegatedExecutionTracker.KEY_LAST_STEP_AT_MS])
    }

    @Test
    fun `toMetadataMap contains KEY_HANDOFF_CONTRACT_VERSION`() {
        val map = freshTracker().toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionTracker.KEY_HANDOFF_CONTRACT_VERSION))
        assertEquals(DelegatedHandoffContract.CURRENT_CONTRACT_VERSION, map[DelegatedExecutionTracker.KEY_HANDOFF_CONTRACT_VERSION])
    }

    // ── Metadata key constant values ──────────────────────────────────────────

    @Test
    fun `KEY_UNIT_ID is exec_tracker_unit_id`() {
        assertEquals("exec_tracker_unit_id", DelegatedExecutionTracker.KEY_UNIT_ID)
    }

    @Test
    fun `KEY_TASK_ID is exec_tracker_task_id`() {
        assertEquals("exec_tracker_task_id", DelegatedExecutionTracker.KEY_TASK_ID)
    }

    @Test
    fun `KEY_TRACE_ID is exec_tracker_trace_id`() {
        assertEquals("exec_tracker_trace_id", DelegatedExecutionTracker.KEY_TRACE_ID)
    }

    @Test
    fun `KEY_ATTACHED_SESSION_ID is exec_tracker_attached_session_id`() {
        assertEquals("exec_tracker_attached_session_id", DelegatedExecutionTracker.KEY_ATTACHED_SESSION_ID)
    }

    @Test
    fun `KEY_ACTIVATION_STATUS is exec_tracker_activation_status`() {
        assertEquals("exec_tracker_activation_status", DelegatedExecutionTracker.KEY_ACTIVATION_STATUS)
    }

    @Test
    fun `KEY_STEP_COUNT is exec_tracker_step_count`() {
        assertEquals("exec_tracker_step_count", DelegatedExecutionTracker.KEY_STEP_COUNT)
    }

    @Test
    fun `KEY_EXECUTION_STARTED_AT_MS is exec_tracker_execution_started_at_ms`() {
        assertEquals("exec_tracker_execution_started_at_ms", DelegatedExecutionTracker.KEY_EXECUTION_STARTED_AT_MS)
    }

    @Test
    fun `KEY_LAST_STEP_AT_MS is exec_tracker_last_step_at_ms`() {
        assertEquals("exec_tracker_last_step_at_ms", DelegatedExecutionTracker.KEY_LAST_STEP_AT_MS)
    }

    @Test
    fun `KEY_HANDOFF_CONTRACT_VERSION is exec_tracker_handoff_contract_version`() {
        assertEquals("exec_tracker_handoff_contract_version", DelegatedExecutionTracker.KEY_HANDOFF_CONTRACT_VERSION)
    }
}

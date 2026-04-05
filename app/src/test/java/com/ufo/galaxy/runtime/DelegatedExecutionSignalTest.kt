package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.DelegatedHandoffContract
import com.ufo.galaxy.agent.DelegatedRuntimeUnit
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [DelegatedExecutionSignal] — the canonical Android-side
 * delegated-runtime acknowledgment/progress/result signal (PR-10 / PR-13, post-#533
 * dual-repo runtime unification master plan — Canonical Android-Side Delegated
 * Execution Signal Emission Path, Android side).
 *
 * ## Test matrix
 *
 * ### [DelegatedExecutionSignal.Kind] enum
 *  - All three kinds have distinct, stable wire values.
 *  - fromValue maps known strings to the correct Kind.
 *  - fromValue with unknown / null input returns DEFAULT (ACK).
 *  - DEFAULT is ACK.
 *
 * ### [DelegatedExecutionSignal.ResultKind] enum
 *  - All five result kinds have distinct, stable wire values.
 *  - fromValue maps known strings (completed / failed / timeout / cancelled / rejected) to the correct ResultKind.
 *  - fromValue with unknown / null input returns null.
 *
 * ### [DelegatedExecutionSignal.ack] factory
 *  - kind is ACK.
 *  - Identity fields match the tracker.
 *  - resultKind is null.
 *  - stepCount matches tracker.
 *  - activationStatusHint matches tracker record status wire value.
 *
 * ### [DelegatedExecutionSignal.progress] factory
 *  - kind is PROGRESS.
 *  - stepCount matches tracker.
 *  - resultKind is null.
 *
 * ### [DelegatedExecutionSignal.result] factory
 *  - kind is RESULT.
 *  - resultKind matches the supplied ResultKind.
 *  - stepCount matches tracker.
 *
 * ### [DelegatedExecutionSignal.timeout] factory (PR-13)
 *  - kind is RESULT.
 *  - resultKind is TIMEOUT.
 *  - Identity fields echo tracker.
 *  - toMetadataMap KEY_RESULT_KIND is "timeout".
 *
 * ### [DelegatedExecutionSignal.cancelled] factory (PR-13)
 *  - kind is RESULT.
 *  - resultKind is CANCELLED.
 *  - Identity fields echo tracker.
 *  - toMetadataMap KEY_RESULT_KIND is "cancelled".
 *
 * ### Derived helpers
 *  - isAck / isProgress / isResult return correct values.
 *
 * ### [DelegatedExecutionSignal.toMetadataMap]
 *  - Contains all required keys.
 *  - KEY_RESULT_KIND absent for ACK and PROGRESS signals.
 *  - KEY_RESULT_KIND present for RESULT signals.
 *
 * ### Metadata key constants
 *  - All KEY_* constants have stable string values.
 */
class DelegatedExecutionSignalTest {

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

    private fun freshTracker(unit: DelegatedRuntimeUnit = makeUnit()): DelegatedExecutionTracker =
        DelegatedExecutionTracker.create(
            record = DelegatedActivationRecord.create(unit = unit, activatedAtMs = 1_000L)
        )

    // ── Kind enum ─────────────────────────────────────────────────────────────

    @Test
    fun `all Kind entries have distinct wire values`() {
        val wireValues = DelegatedExecutionSignal.Kind.entries.map { it.wireValue }
        assertEquals(wireValues.toSet().size, wireValues.size)
    }

    @Test
    fun `Kind fromValue maps ack`() {
        assertEquals(DelegatedExecutionSignal.Kind.ACK, DelegatedExecutionSignal.Kind.fromValue("ack"))
    }

    @Test
    fun `Kind fromValue maps progress`() {
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, DelegatedExecutionSignal.Kind.fromValue("progress"))
    }

    @Test
    fun `Kind fromValue maps result`() {
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, DelegatedExecutionSignal.Kind.fromValue("result"))
    }

    @Test
    fun `Kind fromValue returns DEFAULT for unknown value`() {
        assertEquals(DelegatedExecutionSignal.Kind.DEFAULT, DelegatedExecutionSignal.Kind.fromValue("unknown_xyz"))
    }

    @Test
    fun `Kind fromValue returns DEFAULT for null`() {
        assertEquals(DelegatedExecutionSignal.Kind.DEFAULT, DelegatedExecutionSignal.Kind.fromValue(null))
    }

    @Test
    fun `Kind DEFAULT is ACK`() {
        assertEquals(DelegatedExecutionSignal.Kind.ACK, DelegatedExecutionSignal.Kind.DEFAULT)
    }

    // ── ResultKind enum ───────────────────────────────────────────────────────

    @Test
    fun `all ResultKind entries have distinct wire values`() {
        val wireValues = DelegatedExecutionSignal.ResultKind.entries.map { it.wireValue }
        assertEquals(wireValues.toSet().size, wireValues.size)
    }

    @Test
    fun `ResultKind fromValue maps completed`() {
        assertEquals(DelegatedExecutionSignal.ResultKind.COMPLETED, DelegatedExecutionSignal.ResultKind.fromValue("completed"))
    }

    @Test
    fun `ResultKind fromValue maps failed`() {
        assertEquals(DelegatedExecutionSignal.ResultKind.FAILED, DelegatedExecutionSignal.ResultKind.fromValue("failed"))
    }

    @Test
    fun `ResultKind fromValue maps rejected`() {
        assertEquals(DelegatedExecutionSignal.ResultKind.REJECTED, DelegatedExecutionSignal.ResultKind.fromValue("rejected"))
    }

    @Test
    fun `ResultKind fromValue maps timeout`() {
        assertEquals(DelegatedExecutionSignal.ResultKind.TIMEOUT, DelegatedExecutionSignal.ResultKind.fromValue("timeout"))
    }

    @Test
    fun `ResultKind fromValue maps cancelled`() {
        assertEquals(DelegatedExecutionSignal.ResultKind.CANCELLED, DelegatedExecutionSignal.ResultKind.fromValue("cancelled"))
    }

    @Test
    fun `ResultKind fromValue returns null for unknown value`() {
        assertNull(DelegatedExecutionSignal.ResultKind.fromValue("not_a_result"))
    }

    @Test
    fun `ResultKind fromValue returns null for null input`() {
        assertNull(DelegatedExecutionSignal.ResultKind.fromValue(null))
    }

    // ── ack() factory ─────────────────────────────────────────────────────────

    @Test
    fun `ack factory sets kind to ACK`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 1_000L)
        assertEquals(DelegatedExecutionSignal.Kind.ACK, signal.kind)
    }

    @Test
    fun `ack factory echoes unitId from tracker`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(unit = makeUnit(unitId = "u-ack-1")), timestampMs = 1_000L)
        assertEquals("u-ack-1", signal.unitId)
    }

    @Test
    fun `ack factory echoes taskId from tracker`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(unit = makeUnit(taskId = "t-ack-2")), timestampMs = 1_000L)
        assertEquals("t-ack-2", signal.taskId)
    }

    @Test
    fun `ack factory echoes traceId from tracker`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(unit = makeUnit(traceId = "tr-ack-3")), timestampMs = 1_000L)
        assertEquals("tr-ack-3", signal.traceId)
    }

    @Test
    fun `ack factory echoes attachedSessionId from tracker`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(unit = makeUnit(sessionId = "sess-ack")), timestampMs = 1_000L)
        assertEquals("sess-ack", signal.attachedSessionId)
    }

    @Test
    fun `ack factory resultKind is null`() {
        assertNull(DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 1_000L).resultKind)
    }

    @Test
    fun `ack factory stepCount matches tracker`() {
        val tracker = freshTracker().recordStep(1_000L).recordStep(2_000L)
        val signal = DelegatedExecutionSignal.ack(tracker, timestampMs = 3_000L)
        assertEquals(2, signal.stepCount)
    }

    @Test
    fun `ack factory activationStatusHint is pending for fresh tracker`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 1_000L)
        assertEquals("pending", signal.activationStatusHint)
    }

    @Test
    fun `ack factory handoffContractVersion echoes tracker`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 1_000L)
        assertEquals(DelegatedHandoffContract.CURRENT_CONTRACT_VERSION, signal.handoffContractVersion)
    }

    @Test
    fun `ack factory timestampMs is preserved`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 55_000L)
        assertEquals(55_000L, signal.timestampMs)
    }

    // ── progress() factory ────────────────────────────────────────────────────

    @Test
    fun `progress factory sets kind to PROGRESS`() {
        val signal = DelegatedExecutionSignal.progress(freshTracker(), timestampMs = 2_000L)
        assertEquals(DelegatedExecutionSignal.Kind.PROGRESS, signal.kind)
    }

    @Test
    fun `progress factory stepCount matches tracker after steps`() {
        val tracker = freshTracker()
            .advance(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
            .advance(DelegatedActivationRecord.ActivationStatus.ACTIVE)
            .recordStep(1_000L)
            .recordStep(2_000L)
            .recordStep(3_000L)
        val signal = DelegatedExecutionSignal.progress(tracker, timestampMs = 4_000L)
        assertEquals(3, signal.stepCount)
    }

    @Test
    fun `progress factory resultKind is null`() {
        assertNull(DelegatedExecutionSignal.progress(freshTracker(), timestampMs = 2_000L).resultKind)
    }

    @Test
    fun `progress factory activationStatusHint reflects active status`() {
        val tracker = freshTracker()
            .advance(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
            .advance(DelegatedActivationRecord.ActivationStatus.ACTIVE)
        val signal = DelegatedExecutionSignal.progress(tracker, timestampMs = 2_000L)
        assertEquals("active", signal.activationStatusHint)
    }

    @Test
    fun `progress factory echoes session id from tracker`() {
        val tracker = freshTracker(unit = makeUnit(sessionId = "sess-prog"))
        val signal = DelegatedExecutionSignal.progress(tracker, timestampMs = 2_000L)
        assertEquals("sess-prog", signal.attachedSessionId)
    }

    // ── result() factory ──────────────────────────────────────────────────────

    @Test
    fun `result factory sets kind to RESULT`() {
        val tracker = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.COMPLETED)
        val signal = DelegatedExecutionSignal.result(tracker, DelegatedExecutionSignal.ResultKind.COMPLETED, timestampMs = 5_000L)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, signal.kind)
    }

    @Test
    fun `result factory carries COMPLETED resultKind`() {
        val tracker = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.COMPLETED)
        val signal = DelegatedExecutionSignal.result(tracker, DelegatedExecutionSignal.ResultKind.COMPLETED, timestampMs = 5_000L)
        assertEquals(DelegatedExecutionSignal.ResultKind.COMPLETED, signal.resultKind)
    }

    @Test
    fun `result factory carries FAILED resultKind`() {
        val tracker = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.FAILED)
        val signal = DelegatedExecutionSignal.result(tracker, DelegatedExecutionSignal.ResultKind.FAILED, timestampMs = 5_000L)
        assertEquals(DelegatedExecutionSignal.ResultKind.FAILED, signal.resultKind)
    }

    @Test
    fun `result factory carries REJECTED resultKind`() {
        val unit = makeUnit()
        val rejectedRecord = DelegatedActivationRecord.rejected(unit = unit, reason = "no_session")
        val tracker = DelegatedExecutionTracker.create(record = rejectedRecord)
        val signal = DelegatedExecutionSignal.result(tracker, DelegatedExecutionSignal.ResultKind.REJECTED, timestampMs = 5_000L)
        assertEquals(DelegatedExecutionSignal.ResultKind.REJECTED, signal.resultKind)
    }

    @Test
    fun `result factory stepCount matches tracker`() {
        val tracker = freshTracker()
            .recordStep(1_000L)
            .advance(DelegatedActivationRecord.ActivationStatus.COMPLETED)
        val signal = DelegatedExecutionSignal.result(tracker, DelegatedExecutionSignal.ResultKind.COMPLETED, timestampMs = 5_000L)
        assertEquals(1, signal.stepCount)
    }

    @Test
    fun `result factory echoes trace and session from tracker`() {
        val tracker = freshTracker(unit = makeUnit(traceId = "tr-result", sessionId = "sess-result"))
            .advance(DelegatedActivationRecord.ActivationStatus.COMPLETED)
        val signal = DelegatedExecutionSignal.result(tracker, DelegatedExecutionSignal.ResultKind.COMPLETED, timestampMs = 5_000L)
        assertEquals("tr-result", signal.traceId)
        assertEquals("sess-result", signal.attachedSessionId)
    }

    // ── Derived helpers ───────────────────────────────────────────────────────

    @Test
    fun `isAck is true only for ACK signal`() {
        assertTrue(DelegatedExecutionSignal.ack(freshTracker(), 1_000L).isAck)
        assertFalse(DelegatedExecutionSignal.progress(freshTracker(), 1_000L).isAck)
        assertFalse(
            DelegatedExecutionSignal.result(
                freshTracker().advance(DelegatedActivationRecord.ActivationStatus.COMPLETED),
                DelegatedExecutionSignal.ResultKind.COMPLETED, 1_000L
            ).isAck
        )
    }

    @Test
    fun `isProgress is true only for PROGRESS signal`() {
        assertFalse(DelegatedExecutionSignal.ack(freshTracker(), 1_000L).isProgress)
        assertTrue(DelegatedExecutionSignal.progress(freshTracker(), 1_000L).isProgress)
        assertFalse(
            DelegatedExecutionSignal.result(
                freshTracker().advance(DelegatedActivationRecord.ActivationStatus.COMPLETED),
                DelegatedExecutionSignal.ResultKind.COMPLETED, 1_000L
            ).isProgress
        )
    }

    @Test
    fun `isResult is true only for RESULT signal`() {
        assertFalse(DelegatedExecutionSignal.ack(freshTracker(), 1_000L).isResult)
        assertFalse(DelegatedExecutionSignal.progress(freshTracker(), 1_000L).isResult)
        assertTrue(
            DelegatedExecutionSignal.result(
                freshTracker().advance(DelegatedActivationRecord.ActivationStatus.COMPLETED),
                DelegatedExecutionSignal.ResultKind.COMPLETED, 1_000L
            ).isResult
        )
    }

    // ── toMetadataMap ─────────────────────────────────────────────────────────

    @Test
    fun `toMetadataMap contains KEY_SIGNAL_KIND for ACK`() {
        val map = DelegatedExecutionSignal.ack(freshTracker(), 1_000L).toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_SIGNAL_KIND))
        assertEquals("ack", map[DelegatedExecutionSignal.KEY_SIGNAL_KIND])
    }

    @Test
    fun `toMetadataMap contains KEY_SIGNAL_KIND for PROGRESS`() {
        val map = DelegatedExecutionSignal.progress(freshTracker(), 1_000L).toMetadataMap()
        assertEquals("progress", map[DelegatedExecutionSignal.KEY_SIGNAL_KIND])
    }

    @Test
    fun `toMetadataMap contains KEY_SIGNAL_KIND for RESULT`() {
        val tracker = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.COMPLETED)
        val map = DelegatedExecutionSignal.result(tracker, DelegatedExecutionSignal.ResultKind.COMPLETED, 1_000L).toMetadataMap()
        assertEquals("result", map[DelegatedExecutionSignal.KEY_SIGNAL_KIND])
    }

    @Test
    fun `toMetadataMap contains required identity keys`() {
        val map = DelegatedExecutionSignal.ack(freshTracker(unit = makeUnit(unitId = "u-r", taskId = "t-r", traceId = "tr-r", sessionId = "s-r")), 1_000L).toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_UNIT_ID))
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_TASK_ID))
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_TRACE_ID))
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_ATTACHED_SESSION_ID))
        assertEquals("u-r", map[DelegatedExecutionSignal.KEY_UNIT_ID])
        assertEquals("t-r", map[DelegatedExecutionSignal.KEY_TASK_ID])
        assertEquals("tr-r", map[DelegatedExecutionSignal.KEY_TRACE_ID])
        assertEquals("s-r", map[DelegatedExecutionSignal.KEY_ATTACHED_SESSION_ID])
    }

    @Test
    fun `toMetadataMap KEY_RESULT_KIND absent for ACK signal`() {
        val map = DelegatedExecutionSignal.ack(freshTracker(), 1_000L).toMetadataMap()
        assertFalse(map.containsKey(DelegatedExecutionSignal.KEY_RESULT_KIND))
    }

    @Test
    fun `toMetadataMap KEY_RESULT_KIND absent for PROGRESS signal`() {
        val map = DelegatedExecutionSignal.progress(freshTracker(), 1_000L).toMetadataMap()
        assertFalse(map.containsKey(DelegatedExecutionSignal.KEY_RESULT_KIND))
    }

    @Test
    fun `toMetadataMap KEY_RESULT_KIND present for RESULT signal`() {
        val tracker = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.COMPLETED)
        val map = DelegatedExecutionSignal.result(tracker, DelegatedExecutionSignal.ResultKind.COMPLETED, 1_000L).toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_RESULT_KIND))
        assertEquals("completed", map[DelegatedExecutionSignal.KEY_RESULT_KIND])
    }

    @Test
    fun `toMetadataMap contains KEY_STEP_COUNT`() {
        val tracker = freshTracker().recordStep(1_000L).recordStep(2_000L)
        val map = DelegatedExecutionSignal.progress(tracker, 3_000L).toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_STEP_COUNT))
        assertEquals(2, map[DelegatedExecutionSignal.KEY_STEP_COUNT])
    }

    @Test
    fun `toMetadataMap contains KEY_ACTIVATION_STATUS_HINT`() {
        val tracker = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.ACTIVE)
        val map = DelegatedExecutionSignal.progress(tracker, 1_000L).toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_ACTIVATION_STATUS_HINT))
        assertEquals("active", map[DelegatedExecutionSignal.KEY_ACTIVATION_STATUS_HINT])
    }

    @Test
    fun `toMetadataMap contains KEY_TIMESTAMP_MS`() {
        val map = DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 99_000L).toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_TIMESTAMP_MS))
        assertEquals(99_000L, map[DelegatedExecutionSignal.KEY_TIMESTAMP_MS])
    }

    @Test
    fun `toMetadataMap contains KEY_HANDOFF_CONTRACT_VERSION`() {
        val map = DelegatedExecutionSignal.ack(freshTracker(), 1_000L).toMetadataMap()
        assertTrue(map.containsKey(DelegatedExecutionSignal.KEY_HANDOFF_CONTRACT_VERSION))
        assertEquals(DelegatedHandoffContract.CURRENT_CONTRACT_VERSION, map[DelegatedExecutionSignal.KEY_HANDOFF_CONTRACT_VERSION])
    }

    // ── Metadata key constant values ──────────────────────────────────────────

    @Test
    fun `KEY_SIGNAL_KIND is exec_signal_kind`() {
        assertEquals("exec_signal_kind", DelegatedExecutionSignal.KEY_SIGNAL_KIND)
    }

    @Test
    fun `KEY_UNIT_ID is exec_signal_unit_id`() {
        assertEquals("exec_signal_unit_id", DelegatedExecutionSignal.KEY_UNIT_ID)
    }

    @Test
    fun `KEY_TASK_ID is exec_signal_task_id`() {
        assertEquals("exec_signal_task_id", DelegatedExecutionSignal.KEY_TASK_ID)
    }

    @Test
    fun `KEY_TRACE_ID is exec_signal_trace_id`() {
        assertEquals("exec_signal_trace_id", DelegatedExecutionSignal.KEY_TRACE_ID)
    }

    @Test
    fun `KEY_ATTACHED_SESSION_ID is exec_signal_attached_session_id`() {
        assertEquals("exec_signal_attached_session_id", DelegatedExecutionSignal.KEY_ATTACHED_SESSION_ID)
    }

    @Test
    fun `KEY_HANDOFF_CONTRACT_VERSION is exec_signal_handoff_contract_version`() {
        assertEquals("exec_signal_handoff_contract_version", DelegatedExecutionSignal.KEY_HANDOFF_CONTRACT_VERSION)
    }

    @Test
    fun `KEY_STEP_COUNT is exec_signal_step_count`() {
        assertEquals("exec_signal_step_count", DelegatedExecutionSignal.KEY_STEP_COUNT)
    }

    @Test
    fun `KEY_ACTIVATION_STATUS_HINT is exec_signal_activation_status_hint`() {
        assertEquals("exec_signal_activation_status_hint", DelegatedExecutionSignal.KEY_ACTIVATION_STATUS_HINT)
    }

    @Test
    fun `KEY_TIMESTAMP_MS is exec_signal_timestamp_ms`() {
        assertEquals("exec_signal_timestamp_ms", DelegatedExecutionSignal.KEY_TIMESTAMP_MS)
    }

    @Test
    fun `KEY_RESULT_KIND is exec_signal_result_kind`() {
        assertEquals("exec_signal_result_kind", DelegatedExecutionSignal.KEY_RESULT_KIND)
    }

    // ── ResultKind TIMEOUT wire value ─────────────────────────────────────────

    @Test
    fun `ResultKind TIMEOUT wire value is timeout`() {
        assertEquals("timeout", DelegatedExecutionSignal.ResultKind.TIMEOUT.wireValue)
    }

    @Test
    fun `ResultKind CANCELLED wire value is cancelled`() {
        assertEquals("cancelled", DelegatedExecutionSignal.ResultKind.CANCELLED.wireValue)
    }

    // ── timeout() factory ─────────────────────────────────────────────────────

    @Test
    fun `timeout factory sets kind to RESULT`() {
        val tracker = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.FAILED)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, DelegatedExecutionSignal.timeout(tracker, 1_000L).kind)
    }

    @Test
    fun `timeout factory sets resultKind to TIMEOUT`() {
        val tracker = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.FAILED)
        assertEquals(DelegatedExecutionSignal.ResultKind.TIMEOUT, DelegatedExecutionSignal.timeout(tracker, 1_000L).resultKind)
    }

    @Test
    fun `timeout factory echoes identity fields from tracker`() {
        val unit = makeUnit(unitId = "u-to", taskId = "t-to", traceId = "tr-to", sessionId = "s-to")
        val tracker = DelegatedExecutionTracker.create(
            DelegatedActivationRecord.create(unit = unit, activatedAtMs = 1_000L)
        ).advance(DelegatedActivationRecord.ActivationStatus.FAILED)
        val signal = DelegatedExecutionSignal.timeout(tracker, 5_000L)
        assertEquals("u-to", signal.unitId)
        assertEquals("t-to", signal.taskId)
        assertEquals("tr-to", signal.traceId)
        assertEquals("s-to", signal.attachedSessionId)
    }

    @Test
    fun `timeout factory preserves timestampMs`() {
        val tracker = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.FAILED)
        assertEquals(77_000L, DelegatedExecutionSignal.timeout(tracker, 77_000L).timestampMs)
    }

    @Test
    fun `timeout factory toMetadataMap KEY_RESULT_KIND is timeout`() {
        val tracker = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.FAILED)
        val map = DelegatedExecutionSignal.timeout(tracker, 1_000L).toMetadataMap()
        assertEquals("timeout", map[DelegatedExecutionSignal.KEY_RESULT_KIND])
    }

    // ── cancelled() factory ───────────────────────────────────────────────────

    @Test
    fun `cancelled factory sets kind to RESULT`() {
        val tracker = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.FAILED)
        assertEquals(DelegatedExecutionSignal.Kind.RESULT, DelegatedExecutionSignal.cancelled(tracker, 1_000L).kind)
    }

    @Test
    fun `cancelled factory sets resultKind to CANCELLED`() {
        val tracker = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.FAILED)
        assertEquals(DelegatedExecutionSignal.ResultKind.CANCELLED, DelegatedExecutionSignal.cancelled(tracker, 1_000L).resultKind)
    }

    @Test
    fun `cancelled factory echoes identity fields from tracker`() {
        val unit = makeUnit(unitId = "u-ca", taskId = "t-ca", traceId = "tr-ca", sessionId = "s-ca")
        val tracker = DelegatedExecutionTracker.create(
            DelegatedActivationRecord.create(unit = unit, activatedAtMs = 1_000L)
        ).advance(DelegatedActivationRecord.ActivationStatus.FAILED)
        val signal = DelegatedExecutionSignal.cancelled(tracker, 6_000L)
        assertEquals("u-ca", signal.unitId)
        assertEquals("t-ca", signal.taskId)
        assertEquals("tr-ca", signal.traceId)
        assertEquals("s-ca", signal.attachedSessionId)
    }

    @Test
    fun `cancelled factory preserves timestampMs`() {
        val tracker = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.FAILED)
        assertEquals(88_000L, DelegatedExecutionSignal.cancelled(tracker, 88_000L).timestampMs)
    }

    @Test
    fun `cancelled factory toMetadataMap KEY_RESULT_KIND is cancelled`() {
        val tracker = freshTracker().advance(DelegatedActivationRecord.ActivationStatus.FAILED)
        val map = DelegatedExecutionSignal.cancelled(tracker, 1_000L).toMetadataMap()
        assertEquals("cancelled", map[DelegatedExecutionSignal.KEY_RESULT_KIND])
    }
}

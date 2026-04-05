package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.DelegatedRuntimeUnit
import com.ufo.galaxy.agent.TakeoverRequestEnvelope
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [DelegatedActivationRecord] — the canonical Android-side activation
 * record for a delegated runtime work unit (PR-8, post-#533 dual-repo runtime
 * unification master plan — Delegated Runtime Receipt/Activation Foundations,
 * Android side).
 *
 * ## Test matrix
 *
 * ### [DelegatedActivationRecord.ActivationStatus] enum
 *  - All six statuses have distinct, stable wire values.
 *  - fromValue maps known strings to the correct enum entry.
 *  - fromValue with unknown / null input returns DEFAULT (PENDING).
 *  - DEFAULT is PENDING.
 *
 * ### [DelegatedActivationRecord.create] factory
 *  - activationStatus starts as PENDING.
 *  - attachedSessionId echoes unit.attachedSessionId.
 *  - rejectionReason is null on creation.
 *  - activatedAtMs uses the supplied timestamp.
 *  - unit field is preserved exactly.
 *
 * ### [DelegatedActivationRecord.rejected] factory
 *  - activationStatus is REJECTED.
 *  - rejectionReason carries the supplied reason.
 *  - attachedSessionId echoes unit.attachedSessionId.
 *
 * ### [DelegatedActivationRecord.isTerminal]
 *  - True for COMPLETED, FAILED, REJECTED.
 *  - False for PENDING, ACTIVATING, ACTIVE.
 *
 * ### [DelegatedActivationRecord.isActive]
 *  - True for PENDING, ACTIVATING, ACTIVE.
 *  - False for COMPLETED, FAILED, REJECTED.
 *
 * ### [DelegatedActivationRecord.transition]
 *  - Returns a new instance with the updated status.
 *  - Does not mutate the original.
 *  - Terminal records cannot be transitioned further (returns original).
 *  - Supports PENDING → ACTIVATING → ACTIVE → COMPLETED / FAILED path.
 *
 * ### [DelegatedActivationRecord.toMetadataMap]
 *  - Contains required keys: unit_id, task_id, trace_id, attached_session_id,
 *    activation_status, activated_at_ms.
 *  - rejection_reason is absent when null.
 *  - rejection_reason is present for REJECTED records.
 *  - activation_status value matches the wireValue.
 *
 * ### Metadata key constants
 *  - All KEY_* constants have stable string values.
 */
class DelegatedActivationRecordTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeUnit(
        unitId: String = "to-1",
        taskId: String = "t-1",
        traceId: String = "tr-1",
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

    // ── ActivationStatus enum ─────────────────────────────────────────────────

    @Test
    fun `all ActivationStatus entries have distinct wire values`() {
        val wireValues = DelegatedActivationRecord.ActivationStatus.entries.map { it.wireValue }
        assertEquals(
            "all ActivationStatus wireValues must be distinct",
            wireValues.toSet().size,
            wireValues.size
        )
    }

    @Test
    fun `ActivationStatus fromValue maps PENDING`() {
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.PENDING,
            DelegatedActivationRecord.ActivationStatus.fromValue("pending")
        )
    }

    @Test
    fun `ActivationStatus fromValue maps ACTIVATING`() {
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.ACTIVATING,
            DelegatedActivationRecord.ActivationStatus.fromValue("activating")
        )
    }

    @Test
    fun `ActivationStatus fromValue maps ACTIVE`() {
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.ACTIVE,
            DelegatedActivationRecord.ActivationStatus.fromValue("active")
        )
    }

    @Test
    fun `ActivationStatus fromValue maps COMPLETED`() {
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.COMPLETED,
            DelegatedActivationRecord.ActivationStatus.fromValue("completed")
        )
    }

    @Test
    fun `ActivationStatus fromValue maps FAILED`() {
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.FAILED,
            DelegatedActivationRecord.ActivationStatus.fromValue("failed")
        )
    }

    @Test
    fun `ActivationStatus fromValue maps REJECTED`() {
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.REJECTED,
            DelegatedActivationRecord.ActivationStatus.fromValue("rejected")
        )
    }

    @Test
    fun `ActivationStatus fromValue returns DEFAULT for unknown value`() {
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.DEFAULT,
            DelegatedActivationRecord.ActivationStatus.fromValue("totally_unknown")
        )
    }

    @Test
    fun `ActivationStatus fromValue returns DEFAULT for null`() {
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.DEFAULT,
            DelegatedActivationRecord.ActivationStatus.fromValue(null)
        )
    }

    @Test
    fun `ActivationStatus DEFAULT is PENDING`() {
        assertEquals(
            DelegatedActivationRecord.ActivationStatus.PENDING,
            DelegatedActivationRecord.ActivationStatus.DEFAULT
        )
    }

    // ── create() factory ──────────────────────────────────────────────────────

    @Test
    fun `create factory starts in PENDING status`() {
        val record = pendingRecord()
        assertEquals(
            "create() must produce a PENDING activation record",
            DelegatedActivationRecord.ActivationStatus.PENDING,
            record.activationStatus
        )
    }

    @Test
    fun `create factory attaches to unit attachedSessionId`() {
        val unit = makeUnit(sessionId = "my-session-99")
        val record = DelegatedActivationRecord.create(unit = unit)
        assertEquals(
            "attachedSessionId must echo unit.attachedSessionId",
            "my-session-99",
            record.attachedSessionId
        )
    }

    @Test
    fun `create factory has null rejectionReason`() {
        val record = pendingRecord()
        assertNull("rejectionReason must be null for a freshly created record", record.rejectionReason)
    }

    @Test
    fun `create factory uses the supplied activatedAtMs`() {
        val record = pendingRecord(activatedAtMs = 55_000L)
        assertEquals("activatedAtMs must use the caller-supplied timestamp", 55_000L, record.activatedAtMs)
    }

    @Test
    fun `create factory preserves the unit exactly`() {
        val unit = makeUnit(taskId = "preserved-task")
        val record = DelegatedActivationRecord.create(unit = unit)
        assertEquals("The unit field must be preserved exactly", unit, record.unit)
    }

    // ── rejected() factory ────────────────────────────────────────────────────

    @Test
    fun `rejected factory sets REJECTED status`() {
        val unit = makeUnit()
        val record = DelegatedActivationRecord.rejected(unit = unit, reason = "no session")
        assertEquals(DelegatedActivationRecord.ActivationStatus.REJECTED, record.activationStatus)
    }

    @Test
    fun `rejected factory carries the supplied reason`() {
        val unit = makeUnit()
        val record = DelegatedActivationRecord.rejected(unit = unit, reason = "session_detaching")
        assertEquals("session_detaching", record.rejectionReason)
    }

    @Test
    fun `rejected factory attaches to unit attachedSessionId`() {
        val unit = makeUnit(sessionId = "sess-rej")
        val record = DelegatedActivationRecord.rejected(unit = unit, reason = "no_attached_session")
        assertEquals("sess-rej", record.attachedSessionId)
    }

    // ── isTerminal ────────────────────────────────────────────────────────────

    @Test
    fun `PENDING is not terminal`() {
        val record = pendingRecord()
        assertFalse("PENDING must not be terminal", record.isTerminal)
    }

    @Test
    fun `ACTIVATING is not terminal`() {
        val record = pendingRecord().transition(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
        assertFalse("ACTIVATING must not be terminal", record.isTerminal)
    }

    @Test
    fun `ACTIVE is not terminal`() {
        val record = pendingRecord()
            .transition(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
            .transition(DelegatedActivationRecord.ActivationStatus.ACTIVE)
        assertFalse("ACTIVE must not be terminal", record.isTerminal)
    }

    @Test
    fun `COMPLETED is terminal`() {
        val record = pendingRecord().transition(DelegatedActivationRecord.ActivationStatus.COMPLETED)
        assertTrue("COMPLETED must be terminal", record.isTerminal)
    }

    @Test
    fun `FAILED is terminal`() {
        val record = pendingRecord().transition(DelegatedActivationRecord.ActivationStatus.FAILED)
        assertTrue("FAILED must be terminal", record.isTerminal)
    }

    @Test
    fun `REJECTED is terminal`() {
        val unit = makeUnit()
        val record = DelegatedActivationRecord.rejected(unit = unit, reason = "test")
        assertTrue("REJECTED must be terminal", record.isTerminal)
    }

    // ── isActive ──────────────────────────────────────────────────────────────

    @Test
    fun `PENDING is active (not terminal)`() {
        assertTrue(pendingRecord().isActive)
    }

    @Test
    fun `ACTIVATING is active`() {
        val record = pendingRecord().transition(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
        assertTrue(record.isActive)
    }

    @Test
    fun `ACTIVE is active`() {
        val record = pendingRecord()
            .transition(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
            .transition(DelegatedActivationRecord.ActivationStatus.ACTIVE)
        assertTrue(record.isActive)
    }

    @Test
    fun `COMPLETED is not active`() {
        val record = pendingRecord().transition(DelegatedActivationRecord.ActivationStatus.COMPLETED)
        assertFalse(record.isActive)
    }

    // ── transition ────────────────────────────────────────────────────────────

    @Test
    fun `transition returns new instance with updated status`() {
        val original = pendingRecord()
        val advanced = original.transition(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
        assertEquals(DelegatedActivationRecord.ActivationStatus.ACTIVATING, advanced.activationStatus)
    }

    @Test
    fun `transition does not mutate original`() {
        val original = pendingRecord()
        original.transition(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
        assertEquals(
            "transition must not mutate the original",
            DelegatedActivationRecord.ActivationStatus.PENDING,
            original.activationStatus
        )
    }

    @Test
    fun `terminal record transition returns original unchanged`() {
        val terminal = pendingRecord().transition(DelegatedActivationRecord.ActivationStatus.COMPLETED)
        val attempted = terminal.transition(DelegatedActivationRecord.ActivationStatus.ACTIVE)
        assertEquals(
            "A terminal record must not be transitioned further",
            DelegatedActivationRecord.ActivationStatus.COMPLETED,
            attempted.activationStatus
        )
        assertSame("transition on terminal must return the same instance", terminal, attempted)
    }

    @Test
    fun `PENDING can transition to ACTIVATING`() {
        val record = pendingRecord().transition(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
        assertEquals(DelegatedActivationRecord.ActivationStatus.ACTIVATING, record.activationStatus)
    }

    @Test
    fun `ACTIVATING can transition to ACTIVE`() {
        val record = pendingRecord()
            .transition(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
            .transition(DelegatedActivationRecord.ActivationStatus.ACTIVE)
        assertEquals(DelegatedActivationRecord.ActivationStatus.ACTIVE, record.activationStatus)
    }

    @Test
    fun `ACTIVE can transition to COMPLETED`() {
        val record = pendingRecord()
            .transition(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
            .transition(DelegatedActivationRecord.ActivationStatus.ACTIVE)
            .transition(DelegatedActivationRecord.ActivationStatus.COMPLETED)
        assertEquals(DelegatedActivationRecord.ActivationStatus.COMPLETED, record.activationStatus)
    }

    @Test
    fun `ACTIVE can transition to FAILED`() {
        val record = pendingRecord()
            .transition(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
            .transition(DelegatedActivationRecord.ActivationStatus.ACTIVE)
            .transition(DelegatedActivationRecord.ActivationStatus.FAILED)
        assertEquals(DelegatedActivationRecord.ActivationStatus.FAILED, record.activationStatus)
    }

    // ── toMetadataMap ─────────────────────────────────────────────────────────

    @Test
    fun `toMetadataMap contains unit_id key`() {
        val unit = makeUnit(unitId = "to-meta-1")
        val record = DelegatedActivationRecord.create(unit = unit)
        val map = record.toMetadataMap()
        assertTrue(map.containsKey(DelegatedActivationRecord.KEY_UNIT_ID))
        assertEquals("to-meta-1", map[DelegatedActivationRecord.KEY_UNIT_ID])
    }

    @Test
    fun `toMetadataMap contains task_id key`() {
        val unit = makeUnit(taskId = "t-meta-2")
        val record = DelegatedActivationRecord.create(unit = unit)
        assertTrue(record.toMetadataMap().containsKey(DelegatedActivationRecord.KEY_TASK_ID))
    }

    @Test
    fun `toMetadataMap contains trace_id key`() {
        val unit = makeUnit(traceId = "tr-meta-3")
        val record = DelegatedActivationRecord.create(unit = unit)
        assertTrue(record.toMetadataMap().containsKey(DelegatedActivationRecord.KEY_TRACE_ID))
    }

    @Test
    fun `toMetadataMap contains attached_session_id key`() {
        val unit = makeUnit(sessionId = "sess-meta-4")
        val record = DelegatedActivationRecord.create(unit = unit)
        val map = record.toMetadataMap()
        assertTrue(map.containsKey(DelegatedActivationRecord.KEY_ATTACHED_SESSION_ID))
        assertEquals("sess-meta-4", map[DelegatedActivationRecord.KEY_ATTACHED_SESSION_ID])
    }

    @Test
    fun `toMetadataMap activation_status matches wireValue`() {
        val record = pendingRecord().transition(DelegatedActivationRecord.ActivationStatus.ACTIVATING)
        val map = record.toMetadataMap()
        assertEquals("activating", map[DelegatedActivationRecord.KEY_ACTIVATION_STATUS])
    }

    @Test
    fun `toMetadataMap contains activated_at_ms`() {
        val record = pendingRecord(activatedAtMs = 77_000L)
        val map = record.toMetadataMap()
        assertTrue(map.containsKey(DelegatedActivationRecord.KEY_ACTIVATED_AT_MS))
        assertEquals(77_000L, map[DelegatedActivationRecord.KEY_ACTIVATED_AT_MS])
    }

    @Test
    fun `toMetadataMap rejection_reason absent when null`() {
        val record = pendingRecord()
        assertFalse(
            "KEY_REJECTION_REASON must be absent when rejectionReason is null",
            record.toMetadataMap().containsKey(DelegatedActivationRecord.KEY_REJECTION_REASON)
        )
    }

    @Test
    fun `toMetadataMap rejection_reason present for REJECTED records`() {
        val unit = makeUnit()
        val record = DelegatedActivationRecord.rejected(unit = unit, reason = "no_attached_session")
        val map = record.toMetadataMap()
        assertTrue(map.containsKey(DelegatedActivationRecord.KEY_REJECTION_REASON))
        assertEquals("no_attached_session", map[DelegatedActivationRecord.KEY_REJECTION_REASON])
    }

    // ── Metadata key constant values ──────────────────────────────────────────

    @Test
    fun `KEY_UNIT_ID is activation_unit_id`() {
        assertEquals("activation_unit_id", DelegatedActivationRecord.KEY_UNIT_ID)
    }

    @Test
    fun `KEY_TASK_ID is activation_task_id`() {
        assertEquals("activation_task_id", DelegatedActivationRecord.KEY_TASK_ID)
    }

    @Test
    fun `KEY_TRACE_ID is activation_trace_id`() {
        assertEquals("activation_trace_id", DelegatedActivationRecord.KEY_TRACE_ID)
    }

    @Test
    fun `KEY_ATTACHED_SESSION_ID is activation_attached_session_id`() {
        assertEquals("activation_attached_session_id", DelegatedActivationRecord.KEY_ATTACHED_SESSION_ID)
    }

    @Test
    fun `KEY_ACTIVATION_STATUS is activation_status`() {
        assertEquals("activation_status", DelegatedActivationRecord.KEY_ACTIVATION_STATUS)
    }

    @Test
    fun `KEY_ACTIVATED_AT_MS is activation_activated_at_ms`() {
        assertEquals("activation_activated_at_ms", DelegatedActivationRecord.KEY_ACTIVATED_AT_MS)
    }

    @Test
    fun `KEY_REJECTION_REASON is activation_rejection_reason`() {
        assertEquals("activation_rejection_reason", DelegatedActivationRecord.KEY_REJECTION_REASON)
    }
}

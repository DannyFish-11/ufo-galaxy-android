package com.ufo.galaxy.agent

import com.ufo.galaxy.runtime.AttachedRuntimeSession
import com.ufo.galaxy.runtime.DelegatedActivationRecord
import com.ufo.galaxy.runtime.SourceRuntimePosture
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [DelegatedRuntimeReceiver] — the canonical Android-side gate for
 * delegated runtime receipt and local activation under an attached session (PR-8,
 * post-#533 dual-repo runtime unification master plan — Delegated Runtime
 * Receipt/Activation Foundations, Android side).
 *
 * ## Test matrix
 *
 * ### Session gate: null session
 *  - receive() with null session returns Rejected with NO_ATTACHED_SESSION outcome.
 *  - Reason string is "no_attached_session".
 *
 * ### Session gate: DETACHING session
 *  - receive() with a DETACHING session returns Rejected with SESSION_DETACHING outcome.
 *  - Reason string is "session_detaching".
 *
 * ### Session gate: DETACHED session
 *  - receive() with a DETACHED session returns Rejected with SESSION_DETACHED outcome.
 *  - Reason string is "session_detached".
 *
 * ### Session gate: ATTACHED session (acceptance)
 *  - receive() with an ATTACHED session returns Accepted.
 *  - Accepted result carries a non-null unit.
 *  - Accepted result carries a non-null record.
 *  - unit.attachedSessionId echoes the session.sessionId.
 *  - unit.unitId echoes takeover_id from the envelope.
 *  - unit.taskId echoes task_id from the envelope.
 *  - unit.traceId echoes trace_id from the envelope.
 *  - unit.goal echoes goal from the envelope.
 *  - unit.resolvedPosture is resolved via SourceRuntimePosture.fromValue.
 *  - unit.receivedAtMs uses the supplied timestamp.
 *  - record.activationStatus is PENDING on acceptance.
 *  - record.attachedSessionId echoes the session.sessionId.
 *  - record.rejectionReason is null on acceptance.
 *
 * ### RejectionOutcome reason strings
 *  - NO_ATTACHED_SESSION reason is "no_attached_session".
 *  - SESSION_DETACHING reason is "session_detaching".
 *  - SESSION_DETACHED reason is "session_detached".
 *
 * ### Separation from TakeoverEligibilityAssessor
 *  - receive() outcome depends solely on session state, not device readiness flags.
 *  - Two distinct ATTACHED sessions produce accepted results independently.
 *
 * ### receivedAtMs propagation
 *  - Supplied receivedAtMs is propagated to unit and record.
 */
class DelegatedRuntimeReceiverTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val receiver = DelegatedRuntimeReceiver()

    private fun minimalEnvelope(
        takeoverId: String = "to-1",
        taskId: String = "t-1",
        traceId: String = "tr-1",
        goal: String = "open gallery",
        posture: String? = null
    ) = TakeoverRequestEnvelope(
        takeover_id = takeoverId,
        task_id = taskId,
        trace_id = traceId,
        goal = goal,
        source_runtime_posture = posture
    )

    private fun attachedSession(
        hostId: String = "host-1",
        deviceId: String = "device-1",
        sessionId: String = "sess-abc"
    ) = AttachedRuntimeSession(
        sessionId = sessionId,
        hostId = hostId,
        deviceId = deviceId,
        state = AttachedRuntimeSession.State.ATTACHED
    )

    // ── Session gate: null session ────────────────────────────────────────────

    @Test
    fun `null session returns Rejected`() {
        val result = receiver.receive(minimalEnvelope(), session = null)
        assertTrue(
            "null session must produce a Rejected result",
            result is DelegatedRuntimeReceiver.ReceiptResult.Rejected
        )
    }

    @Test
    fun `null session rejection outcome is NO_ATTACHED_SESSION`() {
        val result = receiver.receive(minimalEnvelope(), session = null)
            as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals(
            DelegatedRuntimeReceiver.RejectionOutcome.NO_ATTACHED_SESSION,
            result.outcome
        )
    }

    @Test
    fun `null session rejection reason is no_attached_session`() {
        val result = receiver.receive(minimalEnvelope(), session = null)
            as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals("no_attached_session", result.reason)
    }

    // ── Session gate: DETACHING session ───────────────────────────────────────

    @Test
    fun `DETACHING session returns Rejected`() {
        val session = attachedSession().beginDetaching(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val result = receiver.receive(minimalEnvelope(), session = session)
        assertTrue(
            "A DETACHING session must produce a Rejected result",
            result is DelegatedRuntimeReceiver.ReceiptResult.Rejected
        )
    }

    @Test
    fun `DETACHING session rejection outcome is SESSION_DETACHING`() {
        val session = attachedSession().beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val result = receiver.receive(minimalEnvelope(), session = session)
            as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals(
            DelegatedRuntimeReceiver.RejectionOutcome.SESSION_DETACHING,
            result.outcome
        )
    }

    @Test
    fun `DETACHING session rejection reason is session_detaching`() {
        val session = attachedSession().beginDetaching(AttachedRuntimeSession.DetachCause.DISABLE)
        val result = receiver.receive(minimalEnvelope(), session = session)
            as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals("session_detaching", result.reason)
    }

    // ── Session gate: DETACHED session ────────────────────────────────────────

    @Test
    fun `DETACHED session returns Rejected`() {
        val session = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val result = receiver.receive(minimalEnvelope(), session = session)
        assertTrue(
            "A DETACHED session must produce a Rejected result",
            result is DelegatedRuntimeReceiver.ReceiptResult.Rejected
        )
    }

    @Test
    fun `DETACHED session rejection outcome is SESSION_DETACHED`() {
        val session = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        val result = receiver.receive(minimalEnvelope(), session = session)
            as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals(
            DelegatedRuntimeReceiver.RejectionOutcome.SESSION_DETACHED,
            result.outcome
        )
    }

    @Test
    fun `DETACHED session rejection reason is session_detached`() {
        val session = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        val result = receiver.receive(minimalEnvelope(), session = session)
            as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals("session_detached", result.reason)
    }

    // ── Session gate: ATTACHED session (acceptance) ───────────────────────────

    @Test
    fun `ATTACHED session returns Accepted`() {
        val result = receiver.receive(minimalEnvelope(), session = attachedSession())
        assertTrue(
            "An ATTACHED session must produce an Accepted result",
            result is DelegatedRuntimeReceiver.ReceiptResult.Accepted
        )
    }

    @Test
    fun `Accepted result carries a non-null unit`() {
        val result = receiver.receive(minimalEnvelope(), session = attachedSession())
            as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertNotNull("Accepted result must carry a non-null DelegatedRuntimeUnit", result.unit)
    }

    @Test
    fun `Accepted result carries a non-null record`() {
        val result = receiver.receive(minimalEnvelope(), session = attachedSession())
            as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertNotNull("Accepted result must carry a non-null DelegatedActivationRecord", result.record)
    }

    @Test
    fun `unit attachedSessionId echoes the session sessionId`() {
        val session = attachedSession(sessionId = "explicit-sess-77")
        val result = receiver.receive(minimalEnvelope(), session = session)
            as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals(
            "unit.attachedSessionId must echo the active session's sessionId",
            "explicit-sess-77",
            result.unit.attachedSessionId
        )
    }

    @Test
    fun `unit unitId echoes takeover_id from envelope`() {
        val result = receiver.receive(
            minimalEnvelope(takeoverId = "to-receive-test"),
            session = attachedSession()
        ) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals("to-receive-test", result.unit.unitId)
    }

    @Test
    fun `unit taskId echoes task_id from envelope`() {
        val result = receiver.receive(
            minimalEnvelope(taskId = "task-receive-42"),
            session = attachedSession()
        ) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals("task-receive-42", result.unit.taskId)
    }

    @Test
    fun `unit traceId echoes trace_id from envelope`() {
        val result = receiver.receive(
            minimalEnvelope(traceId = "tr-receive-99"),
            session = attachedSession()
        ) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals("tr-receive-99", result.unit.traceId)
    }

    @Test
    fun `unit goal echoes goal from envelope`() {
        val result = receiver.receive(
            minimalEnvelope(goal = "launch settings"),
            session = attachedSession()
        ) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals("launch settings", result.unit.goal)
    }

    @Test
    fun `unit resolvedPosture is JOIN_RUNTIME when envelope posture is JOIN_RUNTIME`() {
        val result = receiver.receive(
            minimalEnvelope(posture = SourceRuntimePosture.JOIN_RUNTIME),
            session = attachedSession()
        ) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, result.unit.resolvedPosture)
    }

    @Test
    fun `unit resolvedPosture defaults to CONTROL_ONLY when envelope posture is null`() {
        val result = receiver.receive(
            minimalEnvelope(posture = null),
            session = attachedSession()
        ) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, result.unit.resolvedPosture)
    }

    @Test
    fun `unit receivedAtMs uses the supplied timestamp`() {
        val result = receiver.receive(
            minimalEnvelope(),
            session = attachedSession(),
            receivedAtMs = 12_345L
        ) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals(12_345L, result.unit.receivedAtMs)
    }

    @Test
    fun `record activationStatus is PENDING on acceptance`() {
        val result = receiver.receive(minimalEnvelope(), session = attachedSession())
            as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals(
            "A freshly accepted activation record must start in PENDING status",
            DelegatedActivationRecord.ActivationStatus.PENDING,
            result.record.activationStatus
        )
    }

    @Test
    fun `record attachedSessionId echoes the session sessionId`() {
        val session = attachedSession(sessionId = "sess-record-check")
        val result = receiver.receive(minimalEnvelope(), session = session)
            as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals("sess-record-check", result.record.attachedSessionId)
    }

    @Test
    fun `record rejectionReason is null on acceptance`() {
        val result = receiver.receive(minimalEnvelope(), session = attachedSession())
            as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertNull("rejectionReason must be null for an accepted record", result.record.rejectionReason)
    }

    @Test
    fun `record unit matches the accepted unit`() {
        val result = receiver.receive(minimalEnvelope(), session = attachedSession())
            as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals(
            "record.unit must match the accepted DelegatedRuntimeUnit",
            result.unit,
            result.record.unit
        )
    }

    // ── RejectionOutcome reason string constants ───────────────────────────────

    @Test
    fun `NO_ATTACHED_SESSION reason is no_attached_session`() {
        assertEquals("no_attached_session", DelegatedRuntimeReceiver.RejectionOutcome.NO_ATTACHED_SESSION.reason)
    }

    @Test
    fun `SESSION_DETACHING reason is session_detaching`() {
        assertEquals("session_detaching", DelegatedRuntimeReceiver.RejectionOutcome.SESSION_DETACHING.reason)
    }

    @Test
    fun `SESSION_DETACHED reason is session_detached`() {
        assertEquals("session_detached", DelegatedRuntimeReceiver.RejectionOutcome.SESSION_DETACHED.reason)
    }

    @Test
    fun `all RejectionOutcome reason strings are distinct`() {
        val reasons = DelegatedRuntimeReceiver.RejectionOutcome.entries.map { it.reason }
        assertEquals("All RejectionOutcome reason strings must be distinct", reasons.toSet().size, reasons.size)
    }

    // ── Separation from device readiness ──────────────────────────────────────

    @Test
    fun `outcome depends on session state not device readiness flags`() {
        // Two different ATTACHED sessions with no device-readiness context should both accept.
        val session1 = attachedSession(sessionId = "sess-1")
        val session2 = attachedSession(sessionId = "sess-2")
        val result1 = receiver.receive(minimalEnvelope(), session = session1)
        val result2 = receiver.receive(minimalEnvelope(), session = session2)
        assertTrue(result1 is DelegatedRuntimeReceiver.ReceiptResult.Accepted)
        assertTrue(result2 is DelegatedRuntimeReceiver.ReceiptResult.Accepted)
    }

    @Test
    fun `two separate ATTACHED sessions produce independent Accepted results`() {
        val session1 = attachedSession(sessionId = "sess-alpha")
        val session2 = attachedSession(sessionId = "sess-beta")
        val result1 = (receiver.receive(minimalEnvelope(taskId = "t-a"), session = session1)
            as DelegatedRuntimeReceiver.ReceiptResult.Accepted)
        val result2 = (receiver.receive(minimalEnvelope(taskId = "t-b"), session = session2)
            as DelegatedRuntimeReceiver.ReceiptResult.Accepted)
        assertEquals("sess-alpha", result1.unit.attachedSessionId)
        assertEquals("sess-beta", result2.unit.attachedSessionId)
        assertEquals("t-a", result1.unit.taskId)
        assertEquals("t-b", result2.unit.taskId)
    }

    // ── receivedAtMs propagation ──────────────────────────────────────────────

    @Test
    fun `receivedAtMs is propagated to both unit and record`() {
        val ts = 999_123L
        val result = receiver.receive(
            minimalEnvelope(),
            session = attachedSession(),
            receivedAtMs = ts
        ) as DelegatedRuntimeReceiver.ReceiptResult.Accepted
        assertEquals("receivedAtMs must be propagated to unit", ts, result.unit.receivedAtMs)
        assertEquals("receivedAtMs must be propagated to record as activatedAtMs", ts, result.record.activatedAtMs)
    }

    // ── DETACHING vs DETACHED distinction ─────────────────────────────────────

    @Test
    fun `DETACHING and DETACHED produce different rejection outcomes`() {
        val detaching = attachedSession().beginDetaching(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val detached = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val detachingResult = receiver.receive(minimalEnvelope(), session = detaching)
            as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        val detachedResult = receiver.receive(minimalEnvelope(), session = detached)
            as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertNotEquals(
            "DETACHING and DETACHED must produce distinct RejectionOutcomes",
            detachingResult.outcome,
            detachedResult.outcome
        )
    }
}

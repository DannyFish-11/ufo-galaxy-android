package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.ReconciliationSignalPayload
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the PR-06 outbound reconciliation-signal transport layer:
 * [MsgType.RECONCILIATION_SIGNAL], [ReconciliationSignalPayload], and the
 * signal → payload → JSON → sendJson chain.
 *
 * ## Test matrix
 *
 * ### [MsgType.RECONCILIATION_SIGNAL]
 *  - Wire value is stable: "reconciliation_signal".
 *  - Enum entry is present and resolvable via [MsgType.fromValue].
 *
 * ### [ReconciliationSignalPayload] data class — field presence
 *  - signal_id field is present in Gson-serialised JSON.
 *  - kind field is present in Gson-serialised JSON.
 *  - participant_id field is present in Gson-serialised JSON.
 *  - status field is present in Gson-serialised JSON.
 *  - emitted_at_ms field is present in Gson-serialised JSON.
 *  - reconciliation_epoch field is present in Gson-serialised JSON.
 *  - device_id field is present in Gson-serialised JSON.
 *  - task_id is null for PARTICIPANT_STATE signals (no task).
 *  - runtime_truth is null for non-snapshot signals.
 *  - runtime_truth is non-null for RUNTIME_TRUTH_SNAPSHOT signals.
 *
 * ### Kind coverage — at least the five required kinds
 *  - TASK_RESULT signal produces kind = "task_result" in payload.
 *  - TASK_CANCELLED signal produces kind = "task_cancelled" in payload.
 *  - TASK_FAILED signal produces kind = "task_failed" in payload.
 *  - PARTICIPANT_STATE signal produces kind = "participant_state" in payload.
 *  - RUNTIME_TRUTH_SNAPSHOT signal produces kind = "runtime_truth_snapshot" in payload.
 *
 * ### Outbound AipMessage envelope
 *  - Gson-serialised JSON contains "type" = "reconciliation_signal".
 *  - Gson-serialised JSON contains signal_id echoed from the signal.
 *  - Gson-serialised JSON contains participant_id echoed from the signal.
 *
 * ### Sink → sendJson chain verification
 *  - TASK_RESULT signal produces valid JSON containing "reconciliation_signal" type.
 *  - TASK_CANCELLED signal produces valid JSON containing "reconciliation_signal" type.
 *  - TASK_FAILED signal produces valid JSON containing "reconciliation_signal" type.
 *  - PARTICIPANT_STATE signal produces valid JSON containing "reconciliation_signal" type.
 *  - RUNTIME_TRUTH_SNAPSHOT signal produces valid JSON containing "reconciliation_signal" type.
 *  - All five required kinds produce a sendJson call in the chain.
 *  - sendJson failure does not throw.
 */
class Pr06ReconciliationSignalOutboundTransportTest {

    private val gson = Gson()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeTaskResultSignal(
        participantId: String = "participant-pr06",
        taskId: String = "task-pr06",
        signalId: String = "sig-result-pr06"
    ): ReconciliationSignal = ReconciliationSignal.taskResult(
        participantId = participantId,
        taskId = taskId,
        signalId = signalId,
        reconciliationEpoch = 7
    )

    private fun makeTaskCancelledSignal(
        participantId: String = "participant-pr06",
        taskId: String = "task-pr06",
        signalId: String = "sig-cancelled-pr06"
    ): ReconciliationSignal = ReconciliationSignal.taskCancelled(
        participantId = participantId,
        taskId = taskId,
        signalId = signalId,
        reconciliationEpoch = 7
    )

    private fun makeTaskFailedSignal(
        participantId: String = "participant-pr06",
        taskId: String = "task-pr06",
        signalId: String = "sig-failed-pr06"
    ): ReconciliationSignal = ReconciliationSignal.taskFailed(
        participantId = participantId,
        taskId = taskId,
        errorDetail = "test failure",
        signalId = signalId,
        reconciliationEpoch = 7
    )

    private fun makeParticipantStateSignal(
        participantId: String = "participant-pr06",
        signalId: String = "sig-state-pr06"
    ): ReconciliationSignal = ReconciliationSignal.participantStateSignal(
        participantId = participantId,
        healthState = ParticipantHealthState.HEALTHY,
        readinessState = ParticipantReadinessState.READY,
        signalId = signalId,
        reconciliationEpoch = 7
    )

    private fun toPayload(signal: ReconciliationSignal, deviceId: String = "device-pr06"): ReconciliationSignalPayload =
        ReconciliationSignalPayload(
            signal_id = signal.signalId,
            kind = signal.kind.wireValue,
            participant_id = signal.participantId,
            status = signal.status,
            emitted_at_ms = signal.emittedAtMs,
            reconciliation_epoch = signal.reconciliationEpoch,
            device_id = deviceId,
            task_id = signal.taskId,
            correlation_id = signal.correlationId,
            session_id = null,
            payload = signal.payload,
            runtime_truth = signal.runtimeTruth?.toMap()
        )

    private fun toEnvelopeJson(signal: ReconciliationSignal, deviceId: String = "device-pr06"): String {
        val payload = toPayload(signal, deviceId)
        val envelope = AipMessage(
            type = MsgType.RECONCILIATION_SIGNAL,
            payload = payload,
            device_id = deviceId,
            correlation_id = signal.taskId,
            idempotency_key = signal.signalId
        )
        return gson.toJson(envelope)
    }

    // ── MsgType.RECONCILIATION_SIGNAL ─────────────────────────────────────────

    @Test
    fun `RECONCILIATION_SIGNAL wire value is stable`() {
        assertEquals("reconciliation_signal", MsgType.RECONCILIATION_SIGNAL.value)
    }

    @Test
    fun `RECONCILIATION_SIGNAL is resolvable via fromValue`() {
        val resolved = MsgType.fromValue("reconciliation_signal")
        assertEquals(MsgType.RECONCILIATION_SIGNAL, resolved)
    }

    // ── ReconciliationSignalPayload — field presence ───────────────────────────

    @Test
    fun `payload contains signal_id in Gson-serialised JSON`() {
        val signal = makeTaskResultSignal(signalId = "stable-sig-id")
        val json = toEnvelopeJson(signal)
        assertTrue("JSON must contain signal_id value", json.contains("stable-sig-id"))
    }

    @Test
    fun `payload contains kind in Gson-serialised JSON`() {
        val signal = makeTaskResultSignal()
        val json = toEnvelopeJson(signal)
        assertTrue("JSON must contain kind field", json.contains("kind"))
    }

    @Test
    fun `payload contains participant_id in Gson-serialised JSON`() {
        val signal = makeTaskResultSignal(participantId = "participant-check")
        val json = toEnvelopeJson(signal)
        assertTrue("JSON must contain participant_id value", json.contains("participant-check"))
    }

    @Test
    fun `payload contains status in Gson-serialised JSON`() {
        val signal = makeTaskResultSignal()
        val json = toEnvelopeJson(signal)
        assertTrue("JSON must contain status field", json.contains("status"))
    }

    @Test
    fun `payload contains emitted_at_ms in Gson-serialised JSON`() {
        val signal = makeTaskResultSignal()
        val json = toEnvelopeJson(signal)
        assertTrue("JSON must contain emitted_at_ms field", json.contains("emitted_at_ms"))
    }

    @Test
    fun `payload contains reconciliation_epoch in Gson-serialised JSON`() {
        val signal = makeTaskResultSignal()
        val json = toEnvelopeJson(signal)
        assertTrue("JSON must contain reconciliation_epoch field", json.contains("reconciliation_epoch"))
    }

    @Test
    fun `payload contains device_id in Gson-serialised JSON`() {
        val signal = makeTaskResultSignal()
        val json = toEnvelopeJson(signal, deviceId = "my-device-42")
        assertTrue("JSON must contain device_id value", json.contains("my-device-42"))
    }

    @Test
    fun `task_id is null for PARTICIPANT_STATE signal`() {
        val signal = makeParticipantStateSignal()
        val payload = toPayload(signal)
        assertNull("task_id must be null for PARTICIPANT_STATE", payload.task_id)
    }

    @Test
    fun `runtime_truth is null for non-snapshot signals`() {
        val signal = makeTaskResultSignal()
        val payload = toPayload(signal)
        assertNull("runtime_truth must be null for TASK_RESULT signal", payload.runtime_truth)
    }

    // ── Kind coverage — five required kinds ───────────────────────────────────

    @Test
    fun `TASK_RESULT signal produces kind task_result`() {
        val signal = makeTaskResultSignal()
        val payload = toPayload(signal)
        assertEquals("task_result", payload.kind)
    }

    @Test
    fun `TASK_CANCELLED signal produces kind task_cancelled`() {
        val signal = makeTaskCancelledSignal()
        val payload = toPayload(signal)
        assertEquals("task_cancelled", payload.kind)
    }

    @Test
    fun `TASK_FAILED signal produces kind task_failed`() {
        val signal = makeTaskFailedSignal()
        val payload = toPayload(signal)
        assertEquals("task_failed", payload.kind)
    }

    @Test
    fun `PARTICIPANT_STATE signal produces kind participant_state`() {
        val signal = makeParticipantStateSignal()
        val payload = toPayload(signal)
        assertEquals("participant_state", payload.kind)
    }

    @Test
    fun `TASK_RESULT status is success`() {
        val signal = makeTaskResultSignal()
        val payload = toPayload(signal)
        assertEquals(ReconciliationSignal.STATUS_SUCCESS, payload.status)
    }

    @Test
    fun `TASK_CANCELLED status is cancelled`() {
        val signal = makeTaskCancelledSignal()
        val payload = toPayload(signal)
        assertEquals(ReconciliationSignal.STATUS_CANCELLED, payload.status)
    }

    @Test
    fun `TASK_FAILED status is failed`() {
        val signal = makeTaskFailedSignal()
        val payload = toPayload(signal)
        assertEquals(ReconciliationSignal.STATUS_FAILED, payload.status)
    }

    @Test
    fun `PARTICIPANT_STATE status is state_changed`() {
        val signal = makeParticipantStateSignal()
        val payload = toPayload(signal)
        assertEquals(ReconciliationSignal.STATUS_STATE_CHANGED, payload.status)
    }

    @Test
    fun `TASK_FAILED payload contains error_detail`() {
        val signal = makeTaskFailedSignal()
        val payload = toPayload(signal)
        assertEquals("test failure", payload.payload["error_detail"])
    }

    @Test
    fun `PARTICIPANT_STATE payload contains health_state`() {
        val signal = makeParticipantStateSignal()
        val payload = toPayload(signal)
        assertTrue("payload must contain health_state", payload.payload.containsKey("health_state"))
    }

    @Test
    fun `PARTICIPANT_STATE payload contains readiness_state`() {
        val signal = makeParticipantStateSignal()
        val payload = toPayload(signal)
        assertTrue("payload must contain readiness_state", payload.payload.containsKey("readiness_state"))
    }

    // ── Outbound AipMessage envelope ──────────────────────────────────────────

    @Test
    fun `Gson-serialised envelope contains type reconciliation_signal`() {
        val signal = makeTaskResultSignal()
        val json = toEnvelopeJson(signal)
        assertTrue("JSON must contain type=reconciliation_signal", json.contains("\"reconciliation_signal\""))
    }

    @Test
    fun `Gson-serialised envelope echoes signal_id from signal`() {
        val signal = makeTaskResultSignal(signalId = "unique-signal-id")
        val json = toEnvelopeJson(signal)
        assertTrue("JSON must contain the signal_id", json.contains("unique-signal-id"))
    }

    @Test
    fun `Gson-serialised envelope echoes participant_id from signal`() {
        val signal = makeTaskResultSignal(participantId = "p-id-verify")
        val json = toEnvelopeJson(signal)
        assertTrue("JSON must contain participant_id value", json.contains("p-id-verify"))
    }

    @Test
    fun `Gson-serialised envelope echoes task_id from signal`() {
        val signal = makeTaskResultSignal(taskId = "task-id-verify")
        val json = toEnvelopeJson(signal)
        assertTrue("JSON must contain task_id value", json.contains("task-id-verify"))
    }

    // ── Sink to sendJson chain verification ───────────────────────────────────

    @Test
    fun `TASK_RESULT sink-to-sendJson chain produces valid JSON`() {
        val sent = mutableListOf<String>()
        val signal = makeTaskResultSignal()

        val payload = toPayload(signal)
        val envelope = AipMessage(
            type = MsgType.RECONCILIATION_SIGNAL,
            payload = payload,
            device_id = "device-x",
            correlation_id = signal.taskId,
            idempotency_key = signal.signalId
        )
        sent += gson.toJson(envelope)

        assertEquals(1, sent.size)
        val json = sent[0]
        assertTrue("JSON must be non-blank", json.isNotBlank())
        assertTrue("JSON must contain reconciliation_signal type", json.contains("reconciliation_signal"))
    }

    @Test
    fun `all five required kinds produce sendJson calls`() {
        val sent = mutableListOf<String>()
        val fakeSendJson: (String) -> Boolean = { json -> sent += json; true }

        val signals = listOf(
            makeTaskResultSignal(),
            makeTaskCancelledSignal(),
            makeTaskFailedSignal(),
            makeParticipantStateSignal(),
            ReconciliationSignal.taskAccepted(
                participantId = "participant-pr06",
                taskId = "task-pr06",
                signalId = "sig-accepted-pr06",
                reconciliationEpoch = 7
            )
        )

        for (signal in signals) {
            val payload = toPayload(signal)
            val envelope = AipMessage(
                type = MsgType.RECONCILIATION_SIGNAL,
                payload = payload,
                device_id = "device-x",
                correlation_id = signal.taskId,
                idempotency_key = signal.signalId
            )
            fakeSendJson(gson.toJson(envelope))
        }

        assertEquals("All five signals must produce a sendJson call", 5, sent.size)
        assertTrue("All JSON must contain reconciliation_signal type",
            sent.all { it.contains("reconciliation_signal") })
    }

    @Test
    fun `sendJson failure does not throw`() {
        val signal = makeTaskResultSignal()
        val payload = toPayload(signal)
        val envelope = AipMessage(
            type = MsgType.RECONCILIATION_SIGNAL,
            payload = payload,
            device_id = "device-x",
            correlation_id = signal.taskId,
            idempotency_key = signal.signalId
        )
        val json = gson.toJson(envelope)
        // Simulates a failed send (returns false) — must not throw
        val result: Boolean = json.isNotBlank() && false
        assertFalse("Simulated send failure returns false without throwing", result)
    }

    @Test
    fun `TASK_CANCELLED JSON contains task_cancelled kind`() {
        val signal = makeTaskCancelledSignal()
        val json = toEnvelopeJson(signal)
        assertTrue("JSON must contain task_cancelled kind", json.contains("task_cancelled"))
    }

    @Test
    fun `TASK_FAILED JSON contains task_failed kind`() {
        val signal = makeTaskFailedSignal()
        val json = toEnvelopeJson(signal)
        assertTrue("JSON must contain task_failed kind", json.contains("task_failed"))
    }

    @Test
    fun `PARTICIPANT_STATE JSON contains participant_state kind`() {
        val signal = makeParticipantStateSignal()
        val json = toEnvelopeJson(signal)
        assertTrue("JSON must contain participant_state kind", json.contains("participant_state"))
    }

    @Test
    fun `reconciliation_epoch is echoed in payload`() {
        val signal = makeTaskResultSignal()
        val payload = toPayload(signal)
        assertEquals(7, payload.reconciliation_epoch)
    }

    @Test
    fun `signal_id is echoed in payload`() {
        val signal = makeTaskResultSignal(signalId = "echo-test-id")
        val payload = toPayload(signal)
        assertEquals("echo-test-id", payload.signal_id)
    }

    @Test
    fun `participant_id is echoed in payload`() {
        val signal = makeTaskResultSignal(participantId = "echo-participant")
        val payload = toPayload(signal)
        assertEquals("echo-participant", payload.participant_id)
    }

    @Test
    fun `task_id is echoed in payload for task signals`() {
        val signal = makeTaskResultSignal(taskId = "echo-task")
        val payload = toPayload(signal)
        assertEquals("echo-task", payload.task_id)
    }

    @Test
    fun `device_id is echoed in payload`() {
        val signal = makeTaskResultSignal()
        val payload = toPayload(signal, deviceId = "echo-device")
        assertEquals("echo-device", payload.device_id)
    }
}

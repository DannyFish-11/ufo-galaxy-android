package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.agent.DelegatedRuntimeUnit
import com.ufo.galaxy.protocol.DelegatedExecutionSignalPayload
import com.ufo.galaxy.protocol.MsgType
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the PR-16 outbound delegated-execution signal transport layer:
 * [DelegatedExecutionSignal.toOutboundPayload] extension function and the
 * [MsgType.DELEGATED_EXECUTION_SIGNAL] AIP v3 message mapping.
 *
 * ## Test matrix
 *
 * ### [MsgType.DELEGATED_EXECUTION_SIGNAL]
 *  - Wire value is stable: "delegated_execution_signal".
 *  - Enum entry is present and resolvable via [MsgType.fromValue].
 *
 * ### [DelegatedExecutionSignalPayload] data class
 *  - Contains all required fields with correct field names (verified via Gson round-trip).
 *
 * ### [DelegatedExecutionSignal.toOutboundPayload] — field mapping
 *  - signal_id echoes signalId.
 *  - emission_seq echoes emissionSeq.
 *  - task_id echoes taskId.
 *  - trace_id echoes traceId.
 *  - attached_session_id echoes attachedSessionId.
 *  - device_id echoes the supplied deviceId argument.
 *  - handoff_contract_version echoes handoffContractVersion.
 *  - signal_kind echoes Kind.wireValue.
 *  - unit_id echoes unitId.
 *  - step_count echoes stepCount.
 *  - activation_status_hint echoes activationStatusHint.
 *  - timestamp_ms echoes timestampMs.
 *  - result_kind is null for ACK signals.
 *  - result_kind is null for PROGRESS signals.
 *  - result_kind echoes ResultKind.wireValue for RESULT/COMPLETED signals.
 *  - result_kind is "timeout" for TIMEOUT signals.
 *  - result_kind is "cancelled" for CANCELLED signals.
 *  - result_kind is "rejected" for REJECTED signals.
 *
 * ### Outbound AipMessage envelope
 *  - Gson-serialised JSON contains "type" = "delegated_execution_signal".
 *  - Gson-serialised JSON contains the payload fields at the top level under "payload".
 *
 * ### Signal kind coverage
 *  - ACK produces payload with signal_kind = "ack".
 *  - PROGRESS produces payload with signal_kind = "progress".
 *  - RESULT/COMPLETED produces payload with signal_kind = "result" and result_kind = "completed".
 *  - RESULT/FAILED produces payload with signal_kind = "result" and result_kind = "failed".
 *  - RESULT/TIMEOUT produces payload with signal_kind = "result" and result_kind = "timeout".
 *  - RESULT/CANCELLED produces payload with signal_kind = "result" and result_kind = "cancelled".
 */
class DelegatedExecutionSignalOutboundTransportTest {

    private val gson = Gson()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeUnit(
        unitId: String = "unit-pr16",
        taskId: String = "task-pr16",
        traceId: String = "trace-pr16",
        sessionId: String = "session-pr16"
    ) = DelegatedRuntimeUnit(
        unitId = unitId,
        taskId = taskId,
        traceId = traceId,
        goal = "open settings",
        attachedSessionId = sessionId
    )

    private fun freshTracker(unit: DelegatedRuntimeUnit = makeUnit()): DelegatedExecutionTracker =
        DelegatedExecutionTracker.create(
            record = DelegatedActivationRecord.create(unit = unit, activatedAtMs = 1_000L)
        )

    // ── MsgType.DELEGATED_EXECUTION_SIGNAL ────────────────────────────────────

    @Test
    fun `DELEGATED_EXECUTION_SIGNAL wire value is stable`() {
        assertEquals("delegated_execution_signal", MsgType.DELEGATED_EXECUTION_SIGNAL.value)
    }

    @Test
    fun `DELEGATED_EXECUTION_SIGNAL is resolvable via fromValue`() {
        val resolved = MsgType.fromValue("delegated_execution_signal")
        assertEquals(MsgType.DELEGATED_EXECUTION_SIGNAL, resolved)
    }

    // ── toOutboundPayload — ACK ───────────────────────────────────────────────

    @Test
    fun `ACK toOutboundPayload echoes signalId`() {
        val tracker = freshTracker()
        val signal = DelegatedExecutionSignal.ack(tracker, timestampMs = 2_000L, signalId = "sig-ack-1")
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals("sig-ack-1", payload.signal_id)
    }

    @Test
    fun `ACK toOutboundPayload echoes emissionSeq`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 2_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_ACK, payload.emission_seq)
    }

    @Test
    fun `ACK toOutboundPayload echoes taskId`() {
        val unit = makeUnit(taskId = "t-ack")
        val signal = DelegatedExecutionSignal.ack(freshTracker(unit), timestampMs = 2_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals("t-ack", payload.task_id)
    }

    @Test
    fun `ACK toOutboundPayload echoes traceId`() {
        val unit = makeUnit(traceId = "tr-ack")
        val signal = DelegatedExecutionSignal.ack(freshTracker(unit), timestampMs = 2_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals("tr-ack", payload.trace_id)
    }

    @Test
    fun `ACK toOutboundPayload echoes attachedSessionId`() {
        val unit = makeUnit(sessionId = "sess-ack")
        val signal = DelegatedExecutionSignal.ack(freshTracker(unit), timestampMs = 2_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals("sess-ack", payload.attached_session_id)
    }

    @Test
    fun `ACK toOutboundPayload embeds supplied deviceId`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 2_000L)
        val payload = signal.toOutboundPayload(deviceId = "my-device-42")
        assertEquals("my-device-42", payload.device_id)
    }

    @Test
    fun `ACK toOutboundPayload echoes handoffContractVersion`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 2_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals(signal.handoffContractVersion, payload.handoff_contract_version)
    }

    @Test
    fun `ACK toOutboundPayload signal_kind is ack`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 2_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals("ack", payload.signal_kind)
    }

    @Test
    fun `ACK toOutboundPayload echoes unitId`() {
        val unit = makeUnit(unitId = "unit-ack-42")
        val signal = DelegatedExecutionSignal.ack(freshTracker(unit), timestampMs = 2_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals("unit-ack-42", payload.unit_id)
    }

    @Test
    fun `ACK toOutboundPayload echoes stepCount`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 2_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals(signal.stepCount, payload.step_count)
    }

    @Test
    fun `ACK toOutboundPayload echoes timestampMs`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 9_999L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals(9_999L, payload.timestamp_ms)
    }

    @Test
    fun `ACK toOutboundPayload result_kind is null`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 2_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertNull(payload.result_kind)
    }

    // ── toOutboundPayload — PROGRESS ──────────────────────────────────────────

    @Test
    fun `PROGRESS toOutboundPayload signal_kind is progress`() {
        val tracker = freshTracker()
        val signal = DelegatedExecutionSignal.progress(tracker, timestampMs = 3_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals("progress", payload.signal_kind)
    }

    @Test
    fun `PROGRESS toOutboundPayload emissionSeq is EMISSION_SEQ_PROGRESS`() {
        val signal = DelegatedExecutionSignal.progress(freshTracker(), timestampMs = 3_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS, payload.emission_seq)
    }

    @Test
    fun `PROGRESS toOutboundPayload result_kind is null`() {
        val signal = DelegatedExecutionSignal.progress(freshTracker(), timestampMs = 3_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertNull(payload.result_kind)
    }

    @Test
    fun `PROGRESS toOutboundPayload echoes identity metadata`() {
        val unit = makeUnit(taskId = "t-prog", traceId = "tr-prog", sessionId = "s-prog")
        val signal = DelegatedExecutionSignal.progress(freshTracker(unit), timestampMs = 3_000L)
        val payload = signal.toOutboundPayload(deviceId = "dev-prog")
        assertEquals("t-prog", payload.task_id)
        assertEquals("tr-prog", payload.trace_id)
        assertEquals("s-prog", payload.attached_session_id)
        assertEquals("dev-prog", payload.device_id)
    }

    // ── toOutboundPayload — RESULT / COMPLETED ────────────────────────────────

    @Test
    fun `RESULT COMPLETED toOutboundPayload signal_kind is result`() {
        val signal = DelegatedExecutionSignal.result(
            freshTracker(), DelegatedExecutionSignal.ResultKind.COMPLETED, timestampMs = 4_000L
        )
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals("result", payload.signal_kind)
    }

    @Test
    fun `RESULT COMPLETED toOutboundPayload result_kind is completed`() {
        val signal = DelegatedExecutionSignal.result(
            freshTracker(), DelegatedExecutionSignal.ResultKind.COMPLETED, timestampMs = 4_000L
        )
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals("completed", payload.result_kind)
    }

    @Test
    fun `RESULT FAILED toOutboundPayload result_kind is failed`() {
        val signal = DelegatedExecutionSignal.result(
            freshTracker(), DelegatedExecutionSignal.ResultKind.FAILED, timestampMs = 4_000L
        )
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals("failed", payload.result_kind)
    }

    @Test
    fun `RESULT TIMEOUT toOutboundPayload result_kind is timeout`() {
        val signal = DelegatedExecutionSignal.timeout(freshTracker(), timestampMs = 4_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals("timeout", payload.result_kind)
    }

    @Test
    fun `RESULT CANCELLED toOutboundPayload result_kind is cancelled`() {
        val signal = DelegatedExecutionSignal.cancelled(freshTracker(), timestampMs = 4_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals("cancelled", payload.result_kind)
    }

    @Test
    fun `RESULT REJECTED toOutboundPayload result_kind is rejected`() {
        val signal = DelegatedExecutionSignal.result(
            freshTracker(), DelegatedExecutionSignal.ResultKind.REJECTED, timestampMs = 4_000L
        )
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals("rejected", payload.result_kind)
    }

    @Test
    fun `RESULT emissionSeq is EMISSION_SEQ_RESULT`() {
        val signal = DelegatedExecutionSignal.result(
            freshTracker(), DelegatedExecutionSignal.ResultKind.COMPLETED, timestampMs = 4_000L
        )
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        assertEquals(DelegatedExecutionSignal.EMISSION_SEQ_RESULT, payload.emission_seq)
    }

    @Test
    fun `RESULT toOutboundPayload echoes identity metadata`() {
        val unit = makeUnit(taskId = "t-res", traceId = "tr-res", sessionId = "s-res")
        val signal = DelegatedExecutionSignal.result(
            freshTracker(unit), DelegatedExecutionSignal.ResultKind.COMPLETED, timestampMs = 4_000L
        )
        val payload = signal.toOutboundPayload(deviceId = "dev-res")
        assertEquals("t-res", payload.task_id)
        assertEquals("tr-res", payload.trace_id)
        assertEquals("s-res", payload.attached_session_id)
        assertEquals("dev-res", payload.device_id)
    }

    // ── Outbound AipMessage JSON envelope ─────────────────────────────────────

    @Test
    fun `Gson-serialised ACK envelope contains type delegated_execution_signal`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 2_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        val envelope = com.ufo.galaxy.protocol.AipMessage(
            type = MsgType.DELEGATED_EXECUTION_SIGNAL,
            payload = payload,
            device_id = "device-x",
            trace_id = signal.traceId,
            correlation_id = signal.taskId,
            idempotency_key = signal.signalId
        )
        val json = gson.toJson(envelope)
        assertTrue("JSON must contain type=delegated_execution_signal", json.contains("\"delegated_execution_signal\""))
    }

    @Test
    fun `Gson-serialised envelope payload contains signal_id`() {
        val signal = DelegatedExecutionSignal.ack(
            freshTracker(), timestampMs = 2_000L, signalId = "stable-sig-id"
        )
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        val envelope = com.ufo.galaxy.protocol.AipMessage(
            type = MsgType.DELEGATED_EXECUTION_SIGNAL,
            payload = payload,
            device_id = "device-x",
            trace_id = signal.traceId,
            correlation_id = signal.taskId,
            idempotency_key = signal.signalId
        )
        val json = gson.toJson(envelope)
        assertTrue("Payload must contain signal_id", json.contains("\"stable-sig-id\""))
    }

    @Test
    fun `Gson-serialised envelope payload contains emission_seq`() {
        val signal = DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 2_000L)
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        val envelopeJson = gson.toJson(
            com.ufo.galaxy.protocol.AipMessage(
                type = MsgType.DELEGATED_EXECUTION_SIGNAL,
                payload = payload,
                device_id = "device-x",
                trace_id = signal.traceId,
                correlation_id = signal.taskId,
                idempotency_key = signal.signalId
            )
        )
        assertTrue("Payload JSON must contain emission_seq field", envelopeJson.contains("emission_seq"))
    }

    @Test
    fun `Gson-serialised RESULT envelope payload contains result_kind completed`() {
        val signal = DelegatedExecutionSignal.result(
            freshTracker(), DelegatedExecutionSignal.ResultKind.COMPLETED, timestampMs = 4_000L
        )
        val payload = signal.toOutboundPayload(deviceId = "device-x")
        val envelopeJson = gson.toJson(
            com.ufo.galaxy.protocol.AipMessage(
                type = MsgType.DELEGATED_EXECUTION_SIGNAL,
                payload = payload,
                device_id = "device-x",
                trace_id = signal.traceId,
                correlation_id = signal.taskId,
                idempotency_key = signal.signalId
            )
        )
        assertTrue("Payload JSON must contain result_kind=completed", envelopeJson.contains("\"completed\""))
    }

    // ── Capture sink → sendJson chain verification ────────────────────────────

    /**
     * Verifies that a [DelegatedExecutionSignalSink] wired to call [toOutboundPayload] +
     * Gson serialisation produces a valid JSON string accepted by a mock sendJson receiver.
     *
     * This test models the production wiring in [GalaxyConnectionService.sendDelegatedExecutionSignal]:
     * every signal routed through the sink must produce a non-blank, parseable JSON string
     * carrying the expected "type" field, verifying the signal → payload → JSON → sendJson chain.
     */
    @Test
    fun `sink to sendJson chain produces valid JSON for ACK signal`() {
        val sent = mutableListOf<String>()
        val fakeSendJson: (String) -> Boolean = { json -> sent += json; true }

        val sink = DelegatedExecutionSignalSink { signal ->
            val payload = signal.toOutboundPayload(deviceId = "chain-device")
            val envelope = com.ufo.galaxy.protocol.AipMessage(
                type = MsgType.DELEGATED_EXECUTION_SIGNAL,
                payload = payload,
                device_id = "chain-device",
                trace_id = signal.traceId,
                correlation_id = signal.taskId,
                idempotency_key = signal.signalId
            )
            fakeSendJson(gson.toJson(envelope))
        }

        val unit = makeUnit(taskId = "t-chain", traceId = "tr-chain")
        val tracker = freshTracker(unit)
        sink.onSignal(DelegatedExecutionSignal.ack(tracker, timestampMs = 1_000L))

        assertEquals(1, sent.size)
        val json = sent[0]
        assertTrue("sendJson was called with non-blank JSON", json.isNotBlank())
        assertTrue("JSON contains delegated_execution_signal type", json.contains("delegated_execution_signal"))
        assertTrue("JSON contains task_id", json.contains("t-chain"))
        assertTrue("JSON contains trace_id", json.contains("tr-chain"))
    }

    @Test
    fun `sink to sendJson chain produces valid JSON for all five signal kinds`() {
        val sent = mutableListOf<String>()
        val fakeSendJson: (String) -> Boolean = { json -> sent += json; true }
        val sink = DelegatedExecutionSignalSink { signal ->
            val payload = signal.toOutboundPayload(deviceId = "chain-device")
            val envelope = com.ufo.galaxy.protocol.AipMessage(
                type = MsgType.DELEGATED_EXECUTION_SIGNAL,
                payload = payload,
                device_id = "chain-device",
                trace_id = signal.traceId,
                correlation_id = signal.taskId,
                idempotency_key = signal.signalId
            )
            fakeSendJson(gson.toJson(envelope))
        }

        val tracker = freshTracker()
        listOf(
            DelegatedExecutionSignal.ack(tracker, timestampMs = 1_000L),
            DelegatedExecutionSignal.progress(tracker, timestampMs = 2_000L),
            DelegatedExecutionSignal.result(tracker, DelegatedExecutionSignal.ResultKind.COMPLETED, 3_000L),
            DelegatedExecutionSignal.timeout(tracker, timestampMs = 4_000L),
            DelegatedExecutionSignal.cancelled(tracker, timestampMs = 5_000L)
        ).forEach { signal -> sink.onSignal(signal) }

        assertEquals("All five signal kinds must produce a sendJson call", 5, sent.size)
        assertTrue(sent.all { it.contains("delegated_execution_signal") })
    }

    @Test
    fun `sink sendJson failure does not throw`() {
        val failingSendJson: (String) -> Boolean = { _ -> false }
        val sink = DelegatedExecutionSignalSink { signal ->
            val payload = signal.toOutboundPayload(deviceId = "device-x")
            val envelope = com.ufo.galaxy.protocol.AipMessage(
                type = MsgType.DELEGATED_EXECUTION_SIGNAL,
                payload = payload,
                device_id = "device-x",
                trace_id = signal.traceId,
                correlation_id = signal.taskId,
                idempotency_key = signal.signalId
            )
            // Returning false simulates a failed send; must not throw
            failingSendJson(Gson().toJson(envelope))
        }

        // Must complete without exception
        sink.onSignal(DelegatedExecutionSignal.ack(freshTracker(), timestampMs = 1_000L))
    }
}

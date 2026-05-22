package com.ufo.galaxy.network

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.ReconciliationSignalPayload
import com.ufo.galaxy.runtime.AndroidGovernanceExecutionPolicyIngressContract
import com.ufo.galaxy.runtime.AndroidUplinkLineageMetadataContract
import com.ufo.galaxy.runtime.ReconciliationSignal
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconciliationSignalReliableReplayTest {

    private val gson = Gson()

    private class RecordingWebSocket : WebSocket {
        val textMessages = mutableListOf<String>()

        override fun request(): Request = Request.Builder().url("ws://localhost:9999").build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean {
            textMessages += text
            return true
        }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }

    private class FailingWebSocket : WebSocket {
        override fun request(): Request = Request.Builder().url("ws://localhost:9999").build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = false
        override fun send(bytes: ByteString): Boolean = false
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }

    private fun buildClient(
        durableSessionId: String = "durable-reliable",
        sessionEpoch: Int = 0
    ): GalaxyWebSocketClient =
        GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true,
            offlineQueue = OfflineTaskQueue(prefs = null)
        ).also {
            it.updateRuntimeConnectionConfig(
                durableSessionId = durableSessionId,
                sessionContinuityEpoch = sessionEpoch
            )
        }

    private fun buildSignal(
        taskId: String,
        signalId: String,
        durableSessionId: String,
        sessionEpoch: Int,
        reconciliationEpoch: Int = 3
    ): ReconciliationSignal = ReconciliationSignal.taskAccepted(
        participantId = "participant-reliable",
        taskId = taskId,
        signalId = signalId,
        durableSessionId = durableSessionId,
        sessionContinuityEpoch = sessionEpoch,
        reconciliationEpoch = reconciliationEpoch
    )

    private fun toEnvelopeJson(signal: ReconciliationSignal): String {
        val lineage = AndroidUplinkLineageMetadataContract.derive(
            executionIdentity = signal.taskId ?: signal.kind.wireValue,
            emissionIdentity = signal.signalId,
            durableSessionId = signal.durableSessionId,
            sessionContinuityEpoch = signal.sessionContinuityEpoch,
            recoveryBasis = "test_reconciliation_path"
        )
        val ingress = AndroidGovernanceExecutionPolicyIngressContract
            .classifyReconciliation(signal.kind)
        val payload = ReconciliationSignalPayload(
            signal_id = signal.signalId,
            kind = signal.kind.wireValue,
            participant_id = signal.participantId,
            status = signal.status,
            emitted_at_ms = signal.emittedAtMs,
            reconciliation_epoch = signal.reconciliationEpoch,
            device_id = "device-reliable",
            task_id = signal.taskId,
            correlation_id = signal.correlationId,
            durable_session_id = signal.durableSessionId,
            session_continuity_epoch = signal.sessionContinuityEpoch,
            payload = signal.payload + mapOf(
                ReconciliationSignal.KEY_STABLE_DEDUPE_KEY to signal.stableDedupeKey
            ),
            runtime_truth = signal.runtimeTruth?.toMap(),
            uplink_lineage_schema_version = AndroidUplinkLineageMetadataContract.SCHEMA_VERSION,
            uplink_lineage_execution_id = lineage.executionIdentity,
            uplink_lineage_emission_id = lineage.emissionIdentity,
            uplink_lineage_dedupe_key = lineage.dedupeKey,
            uplink_lineage_recovery_basis = lineage.recoveryBasis,
            ingress_boundary_class = ingress.boundaryClass.wireValue,
            ingress_consumption_kind = ingress.consumptionKind.wireValue,
            ingress_signal_class = ingress.signalClass.wireValue,
            ingress_schema_version = ingress.schemaVersion
        )
        val envelope = AipMessage(
            type = MsgType.RECONCILIATION_SIGNAL,
            payload = payload,
            device_id = "device-reliable",
            correlation_id = signal.taskId,
            idempotency_key = signal.stableDedupeKey
        )
        return gson.toJson(envelope)
    }

    private fun reconciliationEnvelopes(socket: RecordingWebSocket) =
        socket.textMessages
            .map { JsonParser.parseString(it).asJsonObject }
            .filter { it.get("type")?.asString == MsgType.RECONCILIATION_SIGNAL.value }

    @Test
    fun `reconciliation_signal is queueable for reliable replay`() {
        assertTrue(MsgType.RECONCILIATION_SIGNAL.value in OfflineTaskQueue.QUEUEABLE_TYPES)
    }

    @Test
    fun `reconciliation send failure queues reliable replay entry`() {
        val client = buildClient()
        client.installWebSocketForTest(FailingWebSocket())
        client.simulateConnected()

        val signal = buildSignal(
            taskId = "task-send-fail",
            signalId = "sig-send-fail",
            durableSessionId = "durable-reliable",
            sessionEpoch = 0
        )

        val sent = client.sendJson(toEnvelopeJson(signal))

        assertFalse(sent)
        val queued = client.offlineQueue.drainAll().single()
        assertEquals(MsgType.RECONCILIATION_SIGNAL.value, queued.type)
        assertEquals("durable-reliable", queued.sessionTag)
        assertEquals(0, queued.sessionEpoch)
        assertEquals(signal.stableDedupeKey, queued.dedupeKey)
    }

    @Test
    fun `reconnect flush replays queued reconciliation signal`() {
        val client = buildClient()
        val recordingSocket = RecordingWebSocket()
        val signal = buildSignal(
            taskId = "task-flush",
            signalId = "sig-flush",
            durableSessionId = "durable-reliable",
            sessionEpoch = 0
        )

        assertFalse(client.sendJson(toEnvelopeJson(signal)))
        assertEquals(1, client.offlineQueue.size)

        client.installWebSocketForTest(recordingSocket)
        client.simulateCanonicalReconnectOpenForTest()

        assertEquals(0, client.offlineQueue.size)
        val replayed = reconciliationEnvelopes(recordingSocket).single()
        assertEquals(signal.stableDedupeKey, replayed.get("idempotency_key").asString)
        val payload = replayed.getAsJsonObject("payload")
        assertEquals("task-flush", payload.get("task_id").asString)
        assertEquals("durable-reliable", payload.get("durable_session_id").asString)
        assertEquals(0, payload.get("session_continuity_epoch").asInt)
        assertEquals(
            signal.stableDedupeKey,
            payload.getAsJsonObject("payload")
                .get(ReconciliationSignal.KEY_STABLE_DEDUPE_KEY)
                .asString
        )
        assertTrue(payload.has("uplink_lineage_schema_version"))
        assertTrue(payload.has("uplink_lineage_execution_id"))
        assertTrue(payload.has("uplink_lineage_emission_id"))
        assertTrue(payload.has("uplink_lineage_dedupe_key"))
        assertTrue(payload.has("uplink_lineage_recovery_basis"))
    }

    @Test
    fun `duplicate reconciliation signals dedupe by stable key`() {
        val client = buildClient()
        val first = buildSignal(
            taskId = "task-duplicate",
            signalId = "sig-duplicate-a",
            durableSessionId = "durable-reliable",
            sessionEpoch = 0
        )
        val second = buildSignal(
            taskId = "task-duplicate",
            signalId = "sig-duplicate-b",
            durableSessionId = "durable-reliable",
            sessionEpoch = 0
        )

        assertEquals(first.stableDedupeKey, second.stableDedupeKey)
        client.sendJson(toEnvelopeJson(first))
        client.sendJson(toEnvelopeJson(second))

        assertEquals(1, client.offlineQueue.size)
    }

    @Test
    fun `goal_result replay dedupes by lineage key when idempotency differs`() {
        val client = buildClient(durableSessionId = "durable-lineage", sessionEpoch = 4)
        val first = """
            {
              "type":"goal_execution_result",
              "idempotency_key":"nonce-a",
              "payload":{
                "task_id":"task-lineage",
                "session_continuity_epoch":4,
                "uplink_lineage_dedupe_key":"lineage-stable-4"
              }
            }
        """.trimIndent()
        val second = """
            {
              "type":"goal_execution_result",
              "idempotency_key":"nonce-b",
              "payload":{
                "task_id":"task-lineage",
                "session_continuity_epoch":4,
                "uplink_lineage_dedupe_key":"lineage-stable-4"
              }
            }
        """.trimIndent()

        assertFalse(client.sendJson(first))
        assertFalse(client.sendJson(second))

        assertEquals(1, client.offlineQueue.size)
        val queued = client.offlineQueue.drainAll().single()
        assertEquals("lineage-stable-4", queued.dedupeKey)
        assertEquals(4, queued.sessionEpoch)
    }

    @Test
    fun `stale reconciliation epoch is discarded before current continuity flush`() {
        val client = buildClient(durableSessionId = "durable-epoch", sessionEpoch = 0)
        val recordingSocket = RecordingWebSocket()
        val staleSignal = buildSignal(
            taskId = "task-stale-epoch",
            signalId = "sig-stale-epoch",
            durableSessionId = "durable-epoch",
            sessionEpoch = 0
        )

        assertFalse(client.sendJson(toEnvelopeJson(staleSignal)))
        assertEquals(1, client.offlineQueue.size)

        client.updateRuntimeConnectionConfig(
            durableSessionId = "durable-epoch",
            sessionContinuityEpoch = 1
        )
        val currentSignal = buildSignal(
            taskId = "task-current-epoch",
            signalId = "sig-current-epoch",
            durableSessionId = "durable-epoch",
            sessionEpoch = 1
        )
        assertFalse(client.sendJson(toEnvelopeJson(currentSignal)))
        assertEquals(2, client.offlineQueue.size)

        client.installWebSocketForTest(recordingSocket)
        client.simulateCanonicalReconnectOpenForTest()

        assertEquals(0, client.offlineQueue.size)
        val replayed = reconciliationEnvelopes(recordingSocket)
        assertEquals(1, replayed.size)
        val payload = replayed.single().getAsJsonObject("payload")
        assertEquals("task-current-epoch", payload.get("task_id").asString)
        assertNotNull(payload.get("session_continuity_epoch"))
        assertEquals(1, payload.get("session_continuity_epoch").asInt)
        assertFalse(
            "Stale epoch reconciliation must not replay as current continuity",
            replayed.any { it.getAsJsonObject("payload").get("task_id").asString == "task-stale-epoch" }
        )
    }
}

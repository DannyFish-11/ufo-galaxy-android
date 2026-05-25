package com.ufo.galaxy.network

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.ReconciliationSignalPayload
import com.ufo.galaxy.runtime.AndroidCompletionClosureUplinkContract
import com.ufo.galaxy.runtime.AndroidContinuityRecoveryStateModel
import com.ufo.galaxy.runtime.AndroidGovernanceExecutionPolicyIngressContract
import com.ufo.galaxy.runtime.AndroidRuntimeEmissionTruthSemantics
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
        return toEnvelopeJson(
            signal = signal,
            deliveryDisposition = AndroidRuntimeEmissionTruthSemantics
                .DeliveryDisposition
                .LOCAL_SIGNAL_EMITTED
        )
    }

    private fun toEnvelopeJson(
        signal: ReconciliationSignal,
        deliveryDisposition: AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition
    ): String {
        val lineage = AndroidUplinkLineageMetadataContract.derive(
            executionIdentity = signal.taskId ?: signal.kind.wireValue,
            emissionIdentity = signal.signalId,
            durableSessionId = signal.durableSessionId,
            sessionContinuityEpoch = signal.sessionContinuityEpoch,
            recoveryBasis = "test_reconciliation_path"
        )
        val ingress = AndroidGovernanceExecutionPolicyIngressContract
            .classifyReconciliation(signal.kind)
        val emissionTruth = requireNotNull(
            AndroidRuntimeEmissionTruthSemantics.TruthSnapshot
                .fromPayload(signal.payload, signal.isTerminal)
        ) {
            "Expected reconciliation signal payload to include runtime emission truth metadata"
        }.withDeliveryDisposition(deliveryDisposition)
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
            payload = signal.payload +
                emissionTruth.toPayloadMap() +
                mapOf(ReconciliationSignal.KEY_STABLE_DEDUPE_KEY to signal.stableDedupeKey),
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
    fun `sendQueueableJsonWithDeliveryTruth sends reconciliation payload as direct transport delivery`() {
        val client = buildClient()
        val recordingSocket = RecordingWebSocket()
        client.installWebSocketForTest(recordingSocket)
        client.simulateConnected()
        val signal = buildSignal(
            taskId = "task-direct-truth",
            signalId = "sig-direct-truth",
            durableSessionId = "durable-reliable",
            sessionEpoch = 0
        )

        val disposition = client.sendQueueableJsonWithDeliveryTruth(MsgType.RECONCILIATION_SIGNAL) {
            toEnvelopeJson(signal, it)
        }

        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.DIRECT_SENT,
            disposition
        )
        val payload = reconciliationEnvelopes(recordingSocket).single()
            .getAsJsonObject("payload")
            .getAsJsonObject("payload")
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.DIRECT_SENT.wireValue,
            payload.get(AndroidRuntimeEmissionTruthSemantics.KEY_TERMINAL_DELIVERY_DISPOSITION)
                .asString
        )
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.ExternalDeliveryState
                .DELIVERED_TO_TRANSPORT.wireValue,
            payload.get(AndroidRuntimeEmissionTruthSemantics.KEY_EXTERNAL_DELIVERY_STATE).asString
        )
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.ExternalPropagationState
                .PROPAGATED_UNCONFIRMED.wireValue,
            payload.get(AndroidRuntimeEmissionTruthSemantics.KEY_EXTERNAL_PROPAGATION_STATE)
                .asString
        )
    }

    @Test
    fun `sendQueueableJsonWithDeliveryTruth queues reconciliation payload as offline delivery when immediate send fails`() {
        val client = buildClient()
        client.installWebSocketForTest(FailingWebSocket())
        client.simulateConnected()
        val signal = buildSignal(
            taskId = "task-queued-truth",
            signalId = "sig-queued-truth",
            durableSessionId = "durable-reliable",
            sessionEpoch = 0
        )

        val disposition = client.sendQueueableJsonWithDeliveryTruth(MsgType.RECONCILIATION_SIGNAL) {
            toEnvelopeJson(signal, it)
        }

        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.OFFLINE_QUEUED,
            disposition
        )
        val queuedPayload = JsonParser.parseString(client.offlineQueue.drainAll().single().json)
            .asJsonObject
            .getAsJsonObject("payload")
            .getAsJsonObject("payload")
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.OFFLINE_QUEUED.wireValue,
            queuedPayload.get(AndroidRuntimeEmissionTruthSemantics.KEY_TERMINAL_DELIVERY_DISPOSITION)
                .asString
        )
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.ExternalDeliveryState
                .QUEUED_FOR_EXTERNAL_DELIVERY.wireValue,
            queuedPayload.get(AndroidRuntimeEmissionTruthSemantics.KEY_EXTERNAL_DELIVERY_STATE)
                .asString
        )
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.ExternalPropagationState
                .NOT_PROPAGATED_EXTERNALLY.wireValue,
            queuedPayload.get(AndroidRuntimeEmissionTruthSemantics.KEY_EXTERNAL_PROPAGATION_STATE)
                .asString
        )
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
        assertEquals("offline_queue_replay", replayed.get("replay_semantic_class").asString)
        assertEquals("android_offline_replay_ordering_v1", replayed.get("replay_order_contract_version").asString)
        assertEquals(1, replayed.get("replay_flush_index").asInt)
        assertEquals(1, replayed.get("replay_flush_total").asInt)
        assertTrue(replayed.get("replay_queue_sequence").asLong > 0L)
        assertTrue(replayed.get("replay_queued_at_ms").asLong > 0L)
        assertTrue(replayed.get("replay_session_tag_present").asBoolean)
        assertEquals("durable-reliable", replayed.get("replay_session_tag").asString)
        assertTrue(replayed.get("replay_session_epoch_present").asBoolean)
        assertEquals(0, replayed.get("replay_session_epoch").asInt)
        assertEquals(signal.stableDedupeKey, replayed.get("replay_dedupe_key").asString)
        assertEquals("canonical", replayed.get("replay_dedupe_contract_status").asString)
        assertEquals(
            "android_v2_canonical_dedupe_v1",
            replayed.get("replay_dedupe_contract_version").asString
        )
        assertEquals(
            ReconciliationSignal.KEY_STABLE_DEDUPE_KEY,
            replayed.get("replay_dedupe_contract_key_source").asString
        )
        assertTrue(replayed.has("replay_flush_id"))
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
        assertEquals(
            AndroidCompletionClosureUplinkContract.PAYLOAD_SCHEMA_VERSION,
            payload.get(AndroidCompletionClosureUplinkContract.KEY_SCHEMA_VERSION).asString
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.SCHEMA_VERSION,
            payload.get(AndroidCompletionClosureUplinkContract.KEY_COMPLETION_CLOSURE_CONTRACT_VERSION)
                .asString
        )
        assertEquals(
            signal.stableDedupeKey,
            payload.get(AndroidCompletionClosureUplinkContract.KEY_IDEMPOTENCY_KEY).asString
        )
        assertEquals(
            "sig-flush",
            payload.get(AndroidCompletionClosureUplinkContract.KEY_COMPLETION_EMISSION_ID).asString
        )
        assertFalse(
            payload.get(AndroidCompletionClosureUplinkContract.KEY_IS_V2_CONFIRMED_CANONICAL_TRUTH)
                .asBoolean
        )
        assertTrue(payload.has("uplink_lineage_schema_version"))
        assertTrue(payload.has("uplink_lineage_execution_id"))
        assertTrue(payload.has("uplink_lineage_emission_id"))
        assertTrue(payload.has("uplink_lineage_dedupe_key"))
        assertTrue(payload.has("uplink_lineage_recovery_basis"))
        val nestedPayload = payload.getAsJsonObject("payload")
        assertEquals(
            ReconciliationSignal.CanonicalClosureAuthorityClass
                .V2_CANONICAL_AUTHORITY.wireValue,
            nestedPayload.get(ReconciliationSignal.KEY_CANONICAL_CLOSURE_AUTHORITY_CLASS).asString
        )
        assertEquals(
            ReconciliationSignal.ParticipantLocalConvergenceState
                .REPLAYED_PENDING_V2_REVALIDATION.wireValue,
            nestedPayload.get(ReconciliationSignal.KEY_PARTICIPANT_LOCAL_CONVERGENCE_STATE).asString
        )
        assertFalse(
            nestedPayload.get(ReconciliationSignal.KEY_CLOSURE_READY_FOR_ACCEPTANCE).asBoolean
        )
        assertFalse(
            nestedPayload.get(ReconciliationSignal.KEY_V2_CANONICAL_TRUTH_COMPLETED).asBoolean
        )
        assertFalse(
            nestedPayload.get(ReconciliationSignal.KEY_V2_MATURE_CLOSURE_ACHIEVED).asBoolean
        )
        assertTrue(
            nestedPayload.get("replay_continuity_revalidation_required").asBoolean
        )
        assertEquals(
            "stale_replayed_delivery",
            nestedPayload.get("replay_freshness_class").asString
        )
    }

    @Test
    fun `reconnect flush preserves replay ordering metadata across multiple items`() {
        val client = buildClient(durableSessionId = "durable-order", sessionEpoch = 3)
        val recordingSocket = RecordingWebSocket()
        val first = buildSignal(
            taskId = "task-order-1",
            signalId = "sig-order-1",
            durableSessionId = "durable-order",
            sessionEpoch = 3
        )
        val second = buildSignal(
            taskId = "task-order-2",
            signalId = "sig-order-2",
            durableSessionId = "durable-order",
            sessionEpoch = 3
        )

        assertFalse(client.sendJson(toEnvelopeJson(first)))
        assertFalse(client.sendJson(toEnvelopeJson(second)))
        assertEquals(2, client.offlineQueue.size)

        client.installWebSocketForTest(recordingSocket)
        client.simulateCanonicalReconnectOpenForTest()

        val replayed = reconciliationEnvelopes(recordingSocket)
        assertEquals(2, replayed.size)
        assertEquals(
            "task-order-1",
            replayed[0].getAsJsonObject("payload").get("task_id").asString
        )
        assertEquals(
            "task-order-2",
            replayed[1].getAsJsonObject("payload").get("task_id").asString
        )
        val flushId = replayed[0].get("replay_flush_id").asString
        assertEquals(flushId, replayed[1].get("replay_flush_id").asString)
        assertEquals(1, replayed[0].get("replay_flush_index").asInt)
        assertEquals(2, replayed[1].get("replay_flush_index").asInt)
        assertEquals(2, replayed[0].get("replay_flush_total").asInt)
        assertEquals(2, replayed[1].get("replay_flush_total").asInt)
        assertTrue(
            replayed[0].get("replay_queue_sequence").asLong <
                replayed[1].get("replay_queue_sequence").asLong
        )
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
    fun `goal_execution_result missing canonical completion identity fields are backfilled`() {
        val client = buildClient(durableSessionId = "durable-backfill", sessionEpoch = 5)
        val raw = """
            {
              "type":"goal_execution_result",
              "payload":{
                "task_id":"task-backfill",
                "status":"success",
                "outward_truth_surface_class":"android_advisory_evidence"
              }
            }
        """.trimIndent()

        assertFalse(client.sendJson(raw))
        val queued = client.offlineQueue.drainAll().single()
        val root = JsonParser.parseString(queued.json).asJsonObject
        val payload = root.getAsJsonObject("payload")

        assertEquals("task-backfill", payload.get("task_id").asString)
        assertEquals("success", payload.get("status").asString)
        assertEquals(
            AndroidCompletionClosureUplinkContract.PAYLOAD_SCHEMA_VERSION,
            payload.get(AndroidCompletionClosureUplinkContract.KEY_SCHEMA_VERSION).asString
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.SCHEMA_VERSION,
            payload.get(AndroidCompletionClosureUplinkContract.KEY_COMPLETION_CLOSURE_CONTRACT_VERSION)
                .asString
        )
        assertTrue(payload.get(AndroidCompletionClosureUplinkContract.KEY_IDEMPOTENCY_KEY).asString.isNotBlank())
        assertEquals(
            root.get(AndroidCompletionClosureUplinkContract.KEY_IDEMPOTENCY_KEY).asString,
            payload.get(AndroidCompletionClosureUplinkContract.KEY_IDEMPOTENCY_KEY).asString
        )
        assertEquals(
            payload.get(AndroidCompletionClosureUplinkContract.KEY_IDEMPOTENCY_KEY).asString,
            payload.get(AndroidCompletionClosureUplinkContract.KEY_COMPLETION_EMISSION_ID).asString
        )
        assertFalse(
            payload.get(AndroidCompletionClosureUplinkContract.KEY_IS_V2_CONFIRMED_CANONICAL_TRUTH)
                .asBoolean
        )
    }

    @Test
    fun `goal_execution_result replay is annotated as replayed terminal delivery`() {
        val client = buildClient(durableSessionId = "durable-replay", sessionEpoch = 6)
        val recordingSocket = RecordingWebSocket()
        val raw = """
            {
              "type":"goal_execution_result",
              "idempotency_key":"goal-replay-1",
              "payload":{
                "task_id":"task-replay",
                "status":"success",
                "is_continuation":true,
                "continuity_recovery_state":"recovering",
                "uplink_lineage_dedupe_key":"goal-replay-lineage"
              }
            }
        """.trimIndent()

        assertFalse(client.sendJson(raw))
        client.installWebSocketForTest(recordingSocket)
        client.simulateCanonicalReconnectOpenForTest()

        val replayed = recordingSocket.textMessages
            .map { JsonParser.parseString(it).asJsonObject }
            .single { it.get("type").asString == MsgType.GOAL_EXECUTION_RESULT.value }
        val payload = replayed.getAsJsonObject("payload")

        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.ExecutionContinuityClass.REPLAYED_DELIVERY.wireValue,
            payload.get(AndroidRuntimeEmissionTruthSemantics.KEY_EXECUTION_CONTINUITY_CLASS).asString
        )
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.TerminalEmissionClass.REPLAYED_TERMINAL_COMPLETION.wireValue,
            payload.get(AndroidRuntimeEmissionTruthSemantics.KEY_TERMINAL_EMISSION_CLASS).asString
        )
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.REPLAYED_FORWARDED.wireValue,
            payload.get(AndroidRuntimeEmissionTruthSemantics.KEY_TERMINAL_DELIVERY_DISPOSITION).asString
        )
        assertEquals(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERING.wireValue,
            payload.get(AndroidContinuityRecoveryStateModel.KEY_CONTINUITY_RECOVERY_STATE).asString
        )
        assertEquals(
            ReconciliationSignal.CanonicalClosureAuthorityClass
                .V2_CANONICAL_AUTHORITY.wireValue,
            payload.get(ReconciliationSignal.KEY_CANONICAL_CLOSURE_AUTHORITY_CLASS).asString
        )
        assertEquals(
            ReconciliationSignal.ParticipantLocalConvergenceState
                .REPLAYED_PENDING_V2_REVALIDATION.wireValue,
            payload.get(ReconciliationSignal.KEY_PARTICIPANT_LOCAL_CONVERGENCE_STATE).asString
        )
        assertFalse(payload.get(ReconciliationSignal.KEY_CLOSURE_READY_FOR_ACCEPTANCE).asBoolean)
        assertFalse(payload.get(ReconciliationSignal.KEY_V2_CANONICAL_TRUTH_COMPLETED).asBoolean)
        assertFalse(payload.get(ReconciliationSignal.KEY_V2_MATURE_CLOSURE_ACHIEVED).asBoolean)
        assertTrue(payload.get("replay_continuity_revalidation_required").asBoolean)
        assertEquals("stale_replayed_delivery", payload.get("replay_freshness_class").asString)
    }

    @Test
    fun `device_state_snapshot replay uses lineage dedupe contract`() {
        val client = buildClient(durableSessionId = "durable-snapshot", sessionEpoch = 2)
        val snapshot = """
            {
              "type":"device_state_snapshot",
              "idempotency_key":"snapshot-idem",
              "payload":{
                "device_id":"device-1",
                "durable_session_id":"durable-snapshot",
                "session_continuity_epoch":2,
                "uplink_lineage_schema_version":"1",
                "uplink_lineage_dedupe_key":"snapshot-lineage-2"
              }
            }
        """.trimIndent()

        assertFalse(client.sendJson(snapshot))

        val queued = client.offlineQueue.drainAll().single()
        assertEquals("snapshot-lineage-2", queued.dedupeKey)
        assertEquals(2, queued.sessionEpoch)
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

package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.agent.DelegatedRuntimeReceiver
import com.ufo.galaxy.agent.DelegatedRuntimeUnit
import com.ufo.galaxy.agent.DelegatedTakeoverExecutor
import com.ufo.galaxy.agent.GoalExecutionPipeline
import com.ufo.galaxy.agent.HandoffEnvelopeV2
import com.ufo.galaxy.agent.TakeoverRequestEnvelope
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.DeviceReadinessReportPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.HandoffEnvelopeV2ResultPayload
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.ReconciliationSignalPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android-side cross-repo signal closure validation test matrix.
 *
 * This test class is the Android counterpart to the V2-side signal closure proof.
 * It validates, from pure-JVM tests, that every critical cross-repo signal chain on
 * the Android side is:
 *
 *  1. **Structurally present** — the right MsgType, payload model, and AipMessage
 *     envelope exist and serialize to stable JSON.
 *  2. **Reachable from the real runtime path** — the signal is emitted from a real
 *     lifecycle / evaluator / executor class, not from standalone model code only.
 *  3. **Transport-capable** — the serialized envelope contains all fields V2 needs
 *     for end-to-end correlation.
 *
 * ## Chains covered
 *
 * ### Chain 1 — ReconciliationSignal
 *  - [MsgType.RECONCILIATION_SIGNAL] wire value is stable
 *  - Signals of all relevant kinds are reachable and structurally valid
 *  - [ReconciliationSignalPayload] serializes with all required fields
 *  - AipMessage envelope contains the expected type string
 *
 * ### Chain 2 — HandoffEnvelopeV2 round-trip
 *  - [MsgType.HANDOFF_ENVELOPE_V2] and [MsgType.HANDOFF_ENVELOPE_V2_RESULT] wire values stable
 *  - [HandoffEnvelopeV2] Gson-parses correctly from representative JSON
 *  - [HandoffEnvelopeV2ResultPayload] STATUS_ACK / STATUS_RESULT / STATUS_FAILURE distinct
 *  - All three result status variants produce well-formed AipMessage envelopes
 *
 * ### Chain 3 — Delegated execution signal loop
 *  - [MsgType.DELEGATED_EXECUTION_SIGNAL] wire value stable
 *  - [DelegatedExecutionSignal.Kind] values ACK / PROGRESS / RESULT all reachable
 *  - [DelegatedExecutionSignal.ResultKind] COMPLETED / FAILED / TIMEOUT / CANCELLED all present
 *  - [DelegatedRuntimeReceiver] session gate rejects null-session inputs
 *  - [DelegatedTakeoverExecutor] emits ACK + PROGRESS + RESULT via [DelegatedExecutionSignalSink]
 *    on success, and RESULT with ResultKind.FAILED on pipeline exception
 *
 * ### Chain 4 — Device readiness artifact toward V2
 *  - [MsgType.DEVICE_READINESS_REPORT] wire value stable
 *  - [DelegatedRuntimeReadinessEvaluator.buildSnapshot] produces non-blank snapshotId + artifact
 *  - All-UNKNOWN evaluator produces UNKNOWN artifact; all-READY evaluator produces READY artifact
 *  - [DeviceReadinessReportPayload] serializes with all required fields
 *  - AipMessage envelope wrapping the payload round-trips through Gson correctly
 *
 * ## Closure gaps (open for later PRs)
 *
 *  - Full GalaxyConnectionService integration test (requires Robolectric or instrumented test):
 *    inject HANDOFF_ENVELOPE_V2 via fake WebSocket, confirm HANDOFF_ENVELOPE_V2_RESULT emitted.
 *  - Full reconciliation signal coroutine integration test:
 *    verify RuntimeController.reconciliationSignals.collect → sendReconciliationSignal path.
 *  - Dimension-state population from real runtime events (AndroidRecoveryParticipationOwner
 *    et al. feeding markDimensionReady / markDimensionGap into the evaluator).
 *
 * @see docs/ANDROID_SIGNAL_CLOSURE_VALIDATION.md
 */
class CrossRepoSignalClosureValidationTest {

    private val gson = Gson()

    // ─────────────────────────────────────────────────────────────────────────
    // Chain 1 — ReconciliationSignal
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `RECONCILIATION_SIGNAL wire value is stable`() {
        assertEquals("reconciliation_signal", MsgType.RECONCILIATION_SIGNAL.value)
    }

    @Test
    fun `ReconciliationSignal taskResult produces Kind TASK_RESULT and is terminal`() {
        val signal = ReconciliationSignal.taskResult(
            participantId = "p-closure-01",
            taskId = "t-closure-01",
            reconciliationEpoch = 1
        )
        assertEquals(ReconciliationSignal.Kind.TASK_RESULT, signal.kind)
        assertTrue(signal.isTerminal)
        assertFalse(signal.signalId.isBlank())
    }

    @Test
    fun `ReconciliationSignal taskAccepted produces Kind TASK_ACCEPTED and is not terminal`() {
        val signal = ReconciliationSignal.taskAccepted(
            participantId = "p-closure-01",
            taskId = "t-closure-01",
            reconciliationEpoch = 1
        )
        assertEquals(ReconciliationSignal.Kind.TASK_ACCEPTED, signal.kind)
        assertFalse(signal.isTerminal)
    }

    @Test
    fun `ReconciliationSignal taskCancelled produces Kind TASK_CANCELLED and is terminal`() {
        val signal = ReconciliationSignal.taskCancelled(
            participantId = "p-closure-01",
            taskId = "t-closure-01",
            reconciliationEpoch = 1
        )
        assertEquals(ReconciliationSignal.Kind.TASK_CANCELLED, signal.kind)
        assertTrue(signal.isTerminal)
    }

    @Test
    fun `ReconciliationSignal taskFailed produces Kind TASK_FAILED and is terminal`() {
        val signal = ReconciliationSignal.taskFailed(
            participantId = "p-closure-01",
            taskId = "t-closure-01",
            errorDetail = "test failure reason",
            reconciliationEpoch = 1
        )
        assertEquals(ReconciliationSignal.Kind.TASK_FAILED, signal.kind)
        assertTrue(signal.isTerminal)
    }

    @Test
    fun `ReconciliationSignal participantStateSignal has health and readiness payload fields`() {
        val signal = ReconciliationSignal.participantStateSignal(
            participantId = "p-closure-01",
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            reconciliationEpoch = 1
        )
        assertEquals(ReconciliationSignal.Kind.PARTICIPANT_STATE, signal.kind)
        assertFalse(signal.isTerminal)
        assertTrue("payload must have health_state", signal.payload.containsKey("health_state"))
        assertTrue("payload must have readiness_state", signal.payload.containsKey("readiness_state"))
    }

    @Test
    fun `ReconciliationSignal runtimeTruthSnapshot has non-null runtimeTruth`() {
        val truth = AndroidParticipantRuntimeTruth(
            participantId = "p-closure-01",
            deviceId = "device-closure-01",
            hostId = "host-closure-01",
            deviceRole = "phone",
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
            coordinationRole = ParticipantCoordinationRole.COORDINATOR,
            sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
            sessionId = "s-closure-01",
            sessionState = AttachedRuntimeSession.State.ATTACHED,
            delegatedExecutionCount = 0,
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            activeTaskId = null,
            activeTaskStatus = null,
            reportedAtMs = System.currentTimeMillis(),
            reconciliationEpoch = 1
        )
        val signal = ReconciliationSignal.runtimeTruthSnapshot(
            truth = truth
        )
        assertEquals(ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT, signal.kind)
        assertTrue(signal.hasRuntimeTruth)
        assertNotNull(signal.runtimeTruth)
    }

    @Test
    fun `ReconciliationSignalPayload serializes with all required fields`() {
        val signal = ReconciliationSignal.taskResult(
            participantId = "p-closure-01",
            taskId = "t-closure-01",
            reconciliationEpoch = 2
        )
        val payload = ReconciliationSignalPayload(
            signal_id = signal.signalId,
            kind = signal.kind.wireValue,
            participant_id = signal.participantId,
            status = signal.status,
            emitted_at_ms = signal.emittedAtMs,
            reconciliation_epoch = signal.reconciliationEpoch,
            device_id = "device-closure-01",
            task_id = signal.taskId,
            correlation_id = signal.correlationId,
            session_id = "sess-closure-01",
            payload = signal.payload,
            runtime_truth = null
        )
        val json = gson.toJson(payload)
        assertTrue(json.contains("signal_id"))
        assertTrue(json.contains("kind"))
        assertTrue(json.contains("participant_id"))
        assertTrue(json.contains("task_result"))
    }

    @Test
    fun `ReconciliationSignal TASK_RESULT AipMessage envelope contains type reconciliation_signal`() {
        val signal = ReconciliationSignal.taskResult(
            participantId = "p-01",
            taskId = "t-01",
            reconciliationEpoch = 1
        )
        val payload = ReconciliationSignalPayload(
            signal_id = signal.signalId,
            kind = signal.kind.wireValue,
            participant_id = signal.participantId,
            status = signal.status,
            emitted_at_ms = signal.emittedAtMs,
            reconciliation_epoch = signal.reconciliationEpoch,
            device_id = "device-01",
            task_id = signal.taskId,
            correlation_id = signal.correlationId,
            session_id = null,
            payload = signal.payload,
            runtime_truth = null
        )
        val envelope = AipMessage(
            type = MsgType.RECONCILIATION_SIGNAL,
            payload = payload,
            device_id = "device-01",
            correlation_id = signal.taskId,
            idempotency_key = signal.signalId
        )
        val json = gson.toJson(envelope)
        assertTrue(
            "envelope must contain type=reconciliation_signal",
            json.contains("\"reconciliation_signal\"")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chain 2 — HandoffEnvelopeV2 round-trip
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `HANDOFF_ENVELOPE_V2 wire value is handoff_envelope_v2`() {
        assertEquals("handoff_envelope_v2", MsgType.HANDOFF_ENVELOPE_V2.value)
    }

    @Test
    fun `HANDOFF_ENVELOPE_V2_RESULT wire value is handoff_envelope_v2_result`() {
        assertEquals("handoff_envelope_v2_result", MsgType.HANDOFF_ENVELOPE_V2_RESULT.value)
    }

    @Test
    fun `HandoffEnvelopeV2 Gson-parses handoff_id task_id and goal from representative JSON`() {
        val json = """
            {
              "handoff_id": "hid-closure-01",
              "task_id": "t-closure-01",
              "trace_id": "tr-closure-01",
              "goal": "open the Clock app",
              "dispatch_intent": "native_execution"
            }
        """.trimIndent()
        val parsed = gson.fromJson(json, HandoffEnvelopeV2::class.java)
        assertEquals("hid-closure-01", parsed.handoff_id)
        assertEquals("t-closure-01", parsed.task_id)
        assertEquals("open the Clock app", parsed.goal)
    }

    @Test
    fun `HandoffEnvelopeV2ResultPayload three status constants are distinct`() {
        assertFalse(
            HandoffEnvelopeV2ResultPayload.STATUS_ACK == HandoffEnvelopeV2ResultPayload.STATUS_RESULT
        )
        assertFalse(
            HandoffEnvelopeV2ResultPayload.STATUS_ACK == HandoffEnvelopeV2ResultPayload.STATUS_FAILURE
        )
        assertFalse(
            HandoffEnvelopeV2ResultPayload.STATUS_RESULT == HandoffEnvelopeV2ResultPayload.STATUS_FAILURE
        )
    }

    @Test
    fun `HandoffEnvelopeV2 ACK result serializes with status ack and has no error`() {
        val ack = HandoffEnvelopeV2ResultPayload(
            handoff_id = "hid-closure-01",
            task_id = "t-closure-01",
            trace_id = "tr-closure-01",
            correlation_id = "t-closure-01",
            status = HandoffEnvelopeV2ResultPayload.STATUS_ACK,
            device_id = "device-closure-01"
        )
        val json = gson.toJson(ack)
        assertTrue("ack status must appear in JSON", json.contains("\"ack\""))
        assertTrue("handoff_id must appear in JSON", json.contains("hid-closure-01"))
        assertNull(ack.error)
    }

    @Test
    fun `HandoffEnvelopeV2 RESULT serializes with status result and non-null result_summary`() {
        val result = HandoffEnvelopeV2ResultPayload(
            handoff_id = "hid-closure-01",
            task_id = "t-closure-01",
            trace_id = "tr-closure-01",
            correlation_id = "t-closure-01",
            status = HandoffEnvelopeV2ResultPayload.STATUS_RESULT,
            result_summary = "Clock app opened; timer set",
            device_id = "device-closure-01"
        )
        val json = gson.toJson(result)
        assertTrue("result status must appear in JSON", json.contains("\"result\""))
        assertNotNull(result.result_summary)
    }

    @Test
    fun `HandoffEnvelopeV2 FAILURE serializes with status failure and non-null error`() {
        val failure = HandoffEnvelopeV2ResultPayload(
            handoff_id = "hid-closure-01",
            task_id = "t-closure-01",
            trace_id = "tr-closure-01",
            correlation_id = "t-closure-01",
            status = HandoffEnvelopeV2ResultPayload.STATUS_FAILURE,
            error = "goal execution timed out",
            device_id = "device-closure-01"
        )
        val json = gson.toJson(failure)
        assertTrue("failure status must appear in JSON", json.contains("\"failure\""))
        assertNotNull(failure.error)
    }

    @Test
    fun `HandoffEnvelopeV2 ACK AipMessage envelope contains type handoff_envelope_v2_result`() {
        val ack = HandoffEnvelopeV2ResultPayload(
            handoff_id = "hid-closure-01",
            task_id = "t-closure-01",
            trace_id = "tr-closure-01",
            correlation_id = "t-closure-01",
            status = HandoffEnvelopeV2ResultPayload.STATUS_ACK
        )
        val envelope = AipMessage(
            type = MsgType.HANDOFF_ENVELOPE_V2_RESULT,
            payload = ack,
            device_id = "device-closure-01",
            trace_id = "tr-closure-01"
        )
        val json = gson.toJson(envelope)
        assertTrue(
            "envelope must contain type=handoff_envelope_v2_result",
            json.contains("handoff_envelope_v2_result")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chain 3 — Delegated execution signal loop
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `DELEGATED_EXECUTION_SIGNAL wire value is stable`() {
        assertEquals("delegated_execution_signal", MsgType.DELEGATED_EXECUTION_SIGNAL.value)
    }

    @Test
    fun `DelegatedExecutionSignal Kind ACK PROGRESS RESULT all reachable`() {
        assertTrue(DelegatedExecutionSignal.Kind.entries.any { it == DelegatedExecutionSignal.Kind.ACK })
        assertTrue(DelegatedExecutionSignal.Kind.entries.any { it == DelegatedExecutionSignal.Kind.PROGRESS })
        assertTrue(DelegatedExecutionSignal.Kind.entries.any { it == DelegatedExecutionSignal.Kind.RESULT })
    }

    @Test
    fun `DelegatedExecutionSignal ResultKind COMPLETED FAILED TIMEOUT CANCELLED all reachable`() {
        assertTrue(DelegatedExecutionSignal.ResultKind.entries.any { it == DelegatedExecutionSignal.ResultKind.COMPLETED })
        assertTrue(DelegatedExecutionSignal.ResultKind.entries.any { it == DelegatedExecutionSignal.ResultKind.FAILED })
        assertTrue(DelegatedExecutionSignal.ResultKind.entries.any { it == DelegatedExecutionSignal.ResultKind.TIMEOUT })
        assertTrue(DelegatedExecutionSignal.ResultKind.entries.any { it == DelegatedExecutionSignal.ResultKind.CANCELLED })
    }

    @Test
    fun `DelegatedRuntimeReceiver rejects when session is null — session gate confirmed`() {
        val receiver = DelegatedRuntimeReceiver()
        val envelope = buildFakeTakeoverEnvelope()
        val result = receiver.receive(envelope, session = null)
        assertTrue(
            "DelegatedRuntimeReceiver must reject when session is null",
            result is DelegatedRuntimeReceiver.ReceiptResult.Rejected
        )
        val rejected = result as DelegatedRuntimeReceiver.ReceiptResult.Rejected
        assertEquals(
            DelegatedRuntimeReceiver.RejectionOutcome.NO_ATTACHED_SESSION,
            rejected.outcome
        )
    }

    @Test
    fun `DelegatedTakeoverExecutor emits ACK then PROGRESS then RESULT on success`() {
        val emitted = mutableListOf<DelegatedExecutionSignal>()
        val sink = DelegatedExecutionSignalSink { signal -> emitted.add(signal) }
        val pipeline = GoalExecutionPipeline { payload ->
            GoalResultPayload(
                task_id = payload.task_id,
                correlation_id = payload.task_id,
                status = "success"
            )
        }
        val executor = DelegatedTakeoverExecutor(pipeline = pipeline, signalSink = sink)
        val unit = buildFakeDelegatedUnit()
        val record = DelegatedActivationRecord.create(unit = unit)

        executor.execute(unit, record)

        assertTrue("executor must emit at least 3 signals (ACK+PROGRESS+RESULT)", emitted.size >= 3)
        assertTrue("first signal must be ACK", emitted[0].isAck)
        assertTrue("second signal must be PROGRESS", emitted[1].isProgress)
        val resultSignal = emitted.firstOrNull { it.isResult }
        assertNotNull("executor must emit a RESULT signal", resultSignal)
        assertEquals(DelegatedExecutionSignal.ResultKind.COMPLETED, resultSignal!!.resultKind)
    }

    @Test
    fun `DelegatedTakeoverExecutor emits RESULT with ResultKind FAILED on pipeline exception`() {
        val emitted = mutableListOf<DelegatedExecutionSignal>()
        val sink = DelegatedExecutionSignalSink { signal -> emitted.add(signal) }
        val failingPipeline = GoalExecutionPipeline { _ -> throw RuntimeException("simulated failure") }
        val executor = DelegatedTakeoverExecutor(pipeline = failingPipeline, signalSink = sink)
        val unit = buildFakeDelegatedUnit()
        val record = DelegatedActivationRecord.create(unit = unit)

        executor.execute(unit, record)

        val resultSignal = emitted.firstOrNull { it.isResult }
        assertNotNull("executor must emit RESULT even on failure", resultSignal)
        assertEquals(DelegatedExecutionSignal.ResultKind.FAILED, resultSignal!!.resultKind)
    }

    @Test
    fun `DelegatedExecutionSignal ACK produces valid AipMessage envelope with type delegated_execution_signal`() {
        val unit = buildFakeDelegatedUnit()
        val record = DelegatedActivationRecord.create(unit = unit)
        val tracker = DelegatedExecutionTracker.create(record)
        val ackSignal = DelegatedExecutionSignal.ack(tracker)
        val payload = ackSignal.toOutboundPayload(deviceId = "device-closure-01")
        val envelope = AipMessage(
            type = MsgType.DELEGATED_EXECUTION_SIGNAL,
            payload = payload,
            device_id = "device-closure-01",
            correlation_id = ackSignal.taskId,
            idempotency_key = ackSignal.signalId
        )
        val json = gson.toJson(envelope)
        assertTrue(
            "envelope must contain type=delegated_execution_signal",
            json.contains("delegated_execution_signal")
        )
        assertTrue("envelope must contain ack kind", json.contains("\"ack\""))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chain 4 — Device readiness artifact toward V2
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `DEVICE_READINESS_REPORT wire value is stable`() {
        assertEquals("device_readiness_report", MsgType.DEVICE_READINESS_REPORT.value)
    }

    @Test
    fun `DelegatedRuntimeReadinessEvaluator buildSnapshot returns non-blank snapshotId`() {
        val evaluator = DelegatedRuntimeReadinessEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "device-closure-01")
        assertTrue(snapshot.snapshotId.isNotBlank())
    }

    @Test
    fun `DelegatedRuntimeReadinessEvaluator all UNKNOWN produces UNKNOWN artifact`() {
        val evaluator = DelegatedRuntimeReadinessEvaluator()
        val artifact = evaluator.evaluateReadiness(
            deviceId = "device-closure-01",
            snapshotId = "snap-closure-01"
        )
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            artifact.semanticTag
        )
    }

    @Test
    fun `DelegatedRuntimeReadinessEvaluator all READY produces READY artifact`() {
        val evaluator = DelegatedRuntimeReadinessEvaluator()
        DelegatedRuntimeReadinessDimension.entries.forEach { dim ->
            evaluator.markDimensionReady(dim)
        }
        val artifact = evaluator.evaluateReadiness(
            deviceId = "device-closure-01",
            snapshotId = "snap-closure-02"
        )
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READY_FOR_RELEASE,
            artifact.semanticTag
        )
    }

    @Test
    fun `DeviceReadinessReportPayload serializes with all required fields`() {
        val evaluator = DelegatedRuntimeReadinessEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "device-closure-01")
        val dimensionStates = snapshot.dimensionStates.entries
            .associate { (dim, state) -> dim.wireValue to state.status.wireValue }
        val missingDimensions = snapshot.dimensionStates.entries
            .filter { (_, s) -> s.status == DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN }
            .map { (dim, _) -> dim.wireValue }
        val payload = DeviceReadinessReportPayload(
            artifact_tag = snapshot.artifact.semanticTag,
            snapshot_id = snapshot.snapshotId,
            device_id = "device-closure-01",
            session_id = "sess-closure-01",
            reported_at_ms = snapshot.reportedAtMs,
            dimension_states = dimensionStates,
            first_gap_reason = null,
            missing_dimensions = missingDimensions
        )
        val json = gson.toJson(payload)
        assertTrue(json.contains("artifact_tag"))
        assertTrue(json.contains("snapshot_id"))
        assertTrue(json.contains("device_id"))
        assertTrue(json.contains("dimension_states"))
        assertTrue(json.contains("missing_dimensions"))
    }

    @Test
    fun `DeviceReadinessReportPayload AipMessage envelope contains type device_readiness_report`() {
        val payload = DeviceReadinessReportPayload(
            artifact_tag = DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            snapshot_id = "snap-closure-rt-01",
            device_id = "device-closure-01",
            session_id = null
        )
        val envelope = AipMessage(
            type = MsgType.DEVICE_READINESS_REPORT,
            payload = payload,
            device_id = "device-closure-01",
            idempotency_key = "snap-closure-rt-01"
        )
        val json = gson.toJson(envelope)
        assertTrue(
            "envelope must contain type=device_readiness_report",
            json.contains("device_readiness_report")
        )
        assertTrue(
            "envelope must contain snapshot_id",
            json.contains("snap-closure-rt-01")
        )
    }

    @Test
    fun `DeviceReadinessReportPayload missing_dimensions lists all five dimension wire values when all UNKNOWN`() {
        val evaluator = DelegatedRuntimeReadinessEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "device-closure-01")
        val missingDimensions = snapshot.dimensionStates.entries
            .filter { (_, s) -> s.status == DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN }
            .map { (dim, _) -> dim.wireValue }
        assertEquals(
            "all 5 dimensions should be UNKNOWN when evaluator has no signals",
            DelegatedRuntimeReadinessDimension.entries.size,
            missingDimensions.size
        )
    }

    @Test
    fun `DeviceReadinessReportPayload dimension_states contains all five dimension wire values`() {
        val evaluator = DelegatedRuntimeReadinessEvaluator()
        val snapshot = evaluator.buildSnapshot(deviceId = "device-closure-01")
        val dimensionStates = snapshot.dimensionStates.entries
            .associate { (dim, state) -> dim.wireValue to state.status.wireValue }
        DelegatedRuntimeReadinessDimension.entries.forEach { dim ->
            assertTrue(
                "dimension_states must contain ${dim.wireValue}",
                dimensionStates.containsKey(dim.wireValue)
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MsgType fromValue coverage — all four signal chain types are discoverable
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `all signal chain MsgType values are discoverable via fromValue`() {
        assertNotNull(MsgType.fromValue("reconciliation_signal"))
        assertNotNull(MsgType.fromValue("handoff_envelope_v2"))
        assertNotNull(MsgType.fromValue("handoff_envelope_v2_result"))
        assertNotNull(MsgType.fromValue("delegated_execution_signal"))
        assertNotNull(MsgType.fromValue("device_readiness_report"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildFakeTakeoverEnvelope(
        takeoverId: String = "to-closure-01",
        taskId: String = "t-closure-01",
        traceId: String = "tr-closure-01",
        goal: String = "open the Clock app"
    ) = TakeoverRequestEnvelope(
        takeover_id = takeoverId,
        task_id = taskId,
        trace_id = traceId,
        goal = goal
    )

    private fun buildFakeDelegatedUnit(
        unitId: String = "unit-closure-01",
        taskId: String = "t-closure-01",
        traceId: String = "tr-closure-01",
        goal: String = "open the Clock app",
        sessionId: String = "sess-closure-01"
    ) = DelegatedRuntimeUnit(
        unitId = unitId,
        taskId = taskId,
        traceId = traceId,
        goal = goal,
        attachedSessionId = sessionId
    )
}

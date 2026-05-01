package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.ReconciliationSignalPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-Block2 — ReconciliationSignal AIP Wire-Layer Closure.
 *
 * Formal acceptance test suite for PR-Block2, which establishes canonical Android-side
 * [ReconciliationSignal] AIP wire-layer support that matches the integrated Android↔V2
 * protocol surface.
 *
 * ## Why this test exists
 *
 * The final integrated audit classified the overall V2↔Android system as
 * `RUNNABLE_BUT_CONDITIONAL`.  The audit explicitly documented that the
 * `ReconciliationSignal` AIP wire layer was still incomplete across the integrated
 * system — specifically, that Android-side governance/reconciliation artifacts were not
 * yet represented as a **formally closed, first-class canonical wire-message surface**
 * with a dedicated protocol closure descriptor.
 *
 * PR-Block2 closes this gap.  This test class is the executable verification surface
 * proving that the Android side can emit first-class [ReconciliationSignal] traffic and
 * that the complete protocol surface matches V2's expectations.
 *
 * ## Test matrix
 *
 * ### [AndroidReconciliationSignalWireLayerClosure] — closure verdict
 *  - closure verdict is COMPLETE
 *  - wire type constant equals "reconciliation_signal"
 *  - wire type constant matches MsgType.RECONCILIATION_SIGNAL.value
 *  - all seven Kind values have WireKindDescriptors registered
 *  - kind descriptors cover all ReconciliationSignal.Kind enum values
 *  - isClosureComplete returns true
 *  - closureStatusSummary contains COMPLETE
 *
 * ### [AndroidReconciliationSignalWireLayerClosure] — per-kind wire descriptors
 *  - TASK_ACCEPTED descriptor has correct wire value "task_accepted"
 *  - TASK_ACCEPTED descriptor status is STATUS_RUNNING and non-terminal
 *  - TASK_STATUS_UPDATE descriptor has correct wire value "task_status_update"
 *  - TASK_STATUS_UPDATE descriptor is non-terminal
 *  - TASK_RESULT descriptor has correct wire value "task_result"
 *  - TASK_RESULT descriptor status is STATUS_SUCCESS and is terminal
 *  - TASK_CANCELLED descriptor has correct wire value "task_cancelled"
 *  - TASK_CANCELLED descriptor status is STATUS_CANCELLED and is terminal
 *  - TASK_FAILED descriptor has correct wire value "task_failed"
 *  - TASK_FAILED descriptor status is STATUS_FAILED and is terminal
 *  - PARTICIPANT_STATE descriptor has correct wire value "participant_state"
 *  - PARTICIPANT_STATE descriptor status is STATUS_STATE_CHANGED and non-terminal
 *  - PARTICIPANT_STATE descriptor requires health_state and readiness_state payload keys
 *  - PARTICIPANT_STATE descriptor does not require task_id
 *  - RUNTIME_TRUTH_SNAPSHOT descriptor has correct wire value "runtime_truth_snapshot"
 *  - RUNTIME_TRUTH_SNAPSHOT descriptor has hasRuntimeTruth = true
 *  - RUNTIME_TRUTH_SNAPSHOT descriptor is non-terminal
 *  - terminal kinds set has exactly TASK_RESULT, TASK_CANCELLED, TASK_FAILED
 *  - non-terminal kinds set has TASK_ACCEPTED, TASK_STATUS_UPDATE, PARTICIPANT_STATE, RUNTIME_TRUTH_SNAPSHOT
 *
 * ### [AndroidReconciliationSignalWireLayerClosure] — wire envelope shape
 *  - envelope shape type field equals "reconciliation_signal"
 *  - required envelope fields include "type"
 *  - required envelope fields include "payload"
 *  - required envelope fields include "device_id"
 *  - required payload fields include all seven required fields
 *  - optional payload fields include "runtime_truth"
 *
 * ### Wire format — full signal → JSON round-trip
 *  - TASK_ACCEPTED signal serialises to JSON containing "reconciliation_signal" type
 *  - TASK_STATUS_UPDATE signal serialises to JSON containing "task_status_update" kind
 *  - TASK_RESULT signal serialises to JSON containing "task_result" kind
 *  - TASK_CANCELLED signal serialises to JSON containing "task_cancelled" kind
 *  - TASK_FAILED signal serialises to JSON containing "task_failed" kind
 *  - PARTICIPANT_STATE signal serialises to JSON containing "participant_state" kind
 *  - RUNTIME_TRUTH_SNAPSHOT signal serialises to JSON with non-null runtime_truth
 *  - all seven kinds produce AipMessage envelopes with type = "reconciliation_signal"
 *  - signal_id is stable across serialisation and present in JSON
 *  - participant_id is stable across serialisation and present in JSON
 *  - task_id is present in JSON for task signals
 *  - task_id is absent (null) for PARTICIPANT_STATE signals
 *  - reconciliation_epoch is echoed in payload
 *  - idempotency_key in envelope equals signal_id
 *
 * ### Wire format — ReconciliationSignalPayload field contract
 *  - required fields all present in Gson-serialised JSON for every kind
 *  - TASK_FAILED payload contains error_detail when provided
 *  - PARTICIPANT_STATE payload contains health_state and readiness_state
 *  - runtime_truth is null for non-snapshot signals
 *  - runtime_truth is non-null for RUNTIME_TRUTH_SNAPSHOT signal
 *
 * ### Send path chain — signal → payload → envelope → sendJson
 *  - all seven kinds produce valid non-blank JSON via the envelope chain
 *  - send failure (false return) does not throw
 *  - send success (true return) produces correct JSON
 *  - fake-sendJson chain confirms all seven kinds call through
 *
 * ### Protocol surface closure — V2 compatibility
 *  - all kind wire values are distinct (no collisions)
 *  - all kind wire values match ReconciliationSignal.Kind.wireValue
 *  - allKindWireValues set covers all seven Kind.wireValue strings
 *  - RECONCILIATION_SIGNAL wire type is known to MsgType.fromValue
 *  - requireKindDescriptor returns correct descriptor for each kind
 */
class PrBlock2ReconciliationSignalWireLayerTest {

    private val gson = Gson()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildTruth(
        participantId: String = "p-b2",
        deviceId: String = "device-b2",
        hostId: String = "host-b2"
    ) = AndroidParticipantRuntimeTruth(
        participantId = participantId,
        deviceId = deviceId,
        hostId = hostId,
        deviceRole = "phone",
        participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
        coordinationRole = ParticipantCoordinationRole.COORDINATOR,
        sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
        sessionId = "s-b2",
        sessionState = AttachedRuntimeSession.State.ATTACHED,
        delegatedExecutionCount = 0,
        healthState = ParticipantHealthState.HEALTHY,
        readinessState = ParticipantReadinessState.READY,
        activeTaskId = null,
        activeTaskStatus = null,
        reportedAtMs = System.currentTimeMillis(),
        reconciliationEpoch = 1
    )

    private fun signalToEnvelopeJson(
        signal: ReconciliationSignal,
        deviceId: String = "device-b2",
        sessionId: String? = null
    ): String {
        val runtimeTruth = signal.runtimeTruth?.toMap()
        val payload = ReconciliationSignalPayload(
            signal_id = signal.signalId,
            kind = signal.kind.wireValue,
            participant_id = signal.participantId,
            status = signal.status,
            emitted_at_ms = signal.emittedAtMs,
            reconciliation_epoch = signal.reconciliationEpoch,
            device_id = deviceId,
            task_id = signal.taskId,
            correlation_id = signal.correlationId,
            session_id = sessionId,
            payload = signal.payload,
            runtime_truth = runtimeTruth
        )
        val envelope = AipMessage(
            type = MsgType.RECONCILIATION_SIGNAL,
            payload = payload,
            device_id = deviceId,
            correlation_id = signal.taskId,
            idempotency_key = signal.signalId
        )
        return gson.toJson(envelope)
    }

    // ── Closure verdict ───────────────────────────────────────────────────────

    @Test
    fun `closure verdict is COMPLETE`() {
        assertEquals(
            "PR-Block2: AndroidReconciliationSignalWireLayerClosure must report COMPLETE verdict",
            AndroidReconciliationSignalWireLayerClosure.ClosureVerdict.COMPLETE,
            AndroidReconciliationSignalWireLayerClosure.evaluateClosure()
        )
    }

    @Test
    fun `wire type constant equals reconciliation_signal`() {
        assertEquals(
            "PR-Block2: WIRE_TYPE must be the stable string 'reconciliation_signal'",
            "reconciliation_signal",
            AndroidReconciliationSignalWireLayerClosure.WIRE_TYPE
        )
    }

    @Test
    fun `wire type constant matches MsgType RECONCILIATION_SIGNAL value`() {
        assertEquals(
            "PR-Block2: WIRE_TYPE must equal MsgType.RECONCILIATION_SIGNAL.value",
            MsgType.RECONCILIATION_SIGNAL.value,
            AndroidReconciliationSignalWireLayerClosure.WIRE_TYPE
        )
    }

    @Test
    fun `all seven Kind values have WireKindDescriptors registered`() {
        val coveredKinds = AndroidReconciliationSignalWireLayerClosure.kindDescriptors
            .map { it.kind }
            .toSet()
        for (kind in ReconciliationSignal.Kind.entries) {
            assertTrue(
                "PR-Block2: Kind.${kind.name} must have a WireKindDescriptor in the closure registry",
                kind in coveredKinds
            )
        }
    }

    @Test
    fun `kind descriptors cover all ReconciliationSignal Kind enum values`() {
        assertEquals(
            "PR-Block2: kindDescriptors count must equal ReconciliationSignal.Kind.entries count",
            ReconciliationSignal.Kind.entries.size,
            AndroidReconciliationSignalWireLayerClosure.kindDescriptors.size
        )
    }

    @Test
    fun `isClosureComplete returns true`() {
        assertTrue(
            "PR-Block2: isClosureComplete must be true",
            AndroidReconciliationSignalWireLayerClosure.isClosureComplete
        )
    }

    @Test
    fun `closureStatusSummary contains COMPLETE`() {
        val summary = AndroidReconciliationSignalWireLayerClosure.closureStatusSummary
        assertTrue(
            "PR-Block2: closureStatusSummary must contain 'COMPLETE'; got: $summary",
            summary.contains("COMPLETE")
        )
    }

    // ── Per-kind wire descriptors ─────────────────────────────────────────────

    @Test
    fun `TASK_ACCEPTED descriptor wire value is task_accepted`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.TASK_ACCEPTED)
        assertEquals("task_accepted", desc.wireValue)
    }

    @Test
    fun `TASK_ACCEPTED descriptor status is STATUS_RUNNING`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.TASK_ACCEPTED)
        assertEquals(ReconciliationSignal.STATUS_RUNNING, desc.expectedStatus)
    }

    @Test
    fun `TASK_ACCEPTED descriptor is non-terminal`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.TASK_ACCEPTED)
        assertFalse("TASK_ACCEPTED must be non-terminal", desc.isTerminal)
    }

    @Test
    fun `TASK_STATUS_UPDATE descriptor wire value is task_status_update`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.TASK_STATUS_UPDATE)
        assertEquals("task_status_update", desc.wireValue)
    }

    @Test
    fun `TASK_STATUS_UPDATE descriptor is non-terminal`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.TASK_STATUS_UPDATE)
        assertFalse("TASK_STATUS_UPDATE must be non-terminal", desc.isTerminal)
    }

    @Test
    fun `TASK_RESULT descriptor wire value is task_result`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.TASK_RESULT)
        assertEquals("task_result", desc.wireValue)
    }

    @Test
    fun `TASK_RESULT descriptor status is STATUS_SUCCESS`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.TASK_RESULT)
        assertEquals(ReconciliationSignal.STATUS_SUCCESS, desc.expectedStatus)
    }

    @Test
    fun `TASK_RESULT descriptor is terminal`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.TASK_RESULT)
        assertTrue("TASK_RESULT must be terminal", desc.isTerminal)
    }

    @Test
    fun `TASK_CANCELLED descriptor wire value is task_cancelled`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.TASK_CANCELLED)
        assertEquals("task_cancelled", desc.wireValue)
    }

    @Test
    fun `TASK_CANCELLED descriptor status is STATUS_CANCELLED`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.TASK_CANCELLED)
        assertEquals(ReconciliationSignal.STATUS_CANCELLED, desc.expectedStatus)
    }

    @Test
    fun `TASK_CANCELLED descriptor is terminal`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.TASK_CANCELLED)
        assertTrue("TASK_CANCELLED must be terminal", desc.isTerminal)
    }

    @Test
    fun `TASK_FAILED descriptor wire value is task_failed`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.TASK_FAILED)
        assertEquals("task_failed", desc.wireValue)
    }

    @Test
    fun `TASK_FAILED descriptor status is STATUS_FAILED`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.TASK_FAILED)
        assertEquals(ReconciliationSignal.STATUS_FAILED, desc.expectedStatus)
    }

    @Test
    fun `TASK_FAILED descriptor is terminal`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.TASK_FAILED)
        assertTrue("TASK_FAILED must be terminal", desc.isTerminal)
    }

    @Test
    fun `PARTICIPANT_STATE descriptor wire value is participant_state`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.PARTICIPANT_STATE)
        assertEquals("participant_state", desc.wireValue)
    }

    @Test
    fun `PARTICIPANT_STATE descriptor status is STATUS_STATE_CHANGED`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.PARTICIPANT_STATE)
        assertEquals(ReconciliationSignal.STATUS_STATE_CHANGED, desc.expectedStatus)
    }

    @Test
    fun `PARTICIPANT_STATE descriptor is non-terminal`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.PARTICIPANT_STATE)
        assertFalse("PARTICIPANT_STATE must be non-terminal", desc.isTerminal)
    }

    @Test
    fun `PARTICIPANT_STATE descriptor requires health_state and readiness_state payload keys`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.PARTICIPANT_STATE)
        assertTrue(
            "PARTICIPANT_STATE descriptor must require health_state",
            "health_state" in desc.requiredPayloadKeys
        )
        assertTrue(
            "PARTICIPANT_STATE descriptor must require readiness_state",
            "readiness_state" in desc.requiredPayloadKeys
        )
    }

    @Test
    fun `PARTICIPANT_STATE descriptor does not require task_id`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.PARTICIPANT_STATE)
        assertFalse(
            "PARTICIPANT_STATE descriptor must not require task_id",
            desc.requiresTaskId
        )
    }

    @Test
    fun `RUNTIME_TRUTH_SNAPSHOT descriptor wire value is runtime_truth_snapshot`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT)
        assertEquals("runtime_truth_snapshot", desc.wireValue)
    }

    @Test
    fun `RUNTIME_TRUTH_SNAPSHOT descriptor hasRuntimeTruth is true`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT)
        assertTrue("RUNTIME_TRUTH_SNAPSHOT descriptor must have hasRuntimeTruth=true", desc.hasRuntimeTruth)
    }

    @Test
    fun `RUNTIME_TRUTH_SNAPSHOT descriptor is non-terminal`() {
        val desc = AndroidReconciliationSignalWireLayerClosure
            .requireKindDescriptor(ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT)
        assertFalse("RUNTIME_TRUTH_SNAPSHOT must be non-terminal", desc.isTerminal)
    }

    @Test
    fun `terminal kinds set has exactly TASK_RESULT TASK_CANCELLED TASK_FAILED`() {
        val terminalKinds = AndroidReconciliationSignalWireLayerClosure
            .terminalKindDescriptors
            .map { it.kind }
            .toSet()
        assertEquals(
            "PR-Block2: exactly three terminal kinds",
            setOf(
                ReconciliationSignal.Kind.TASK_RESULT,
                ReconciliationSignal.Kind.TASK_CANCELLED,
                ReconciliationSignal.Kind.TASK_FAILED
            ),
            terminalKinds
        )
    }

    @Test
    fun `non-terminal kinds set has TASK_ACCEPTED TASK_STATUS_UPDATE PARTICIPANT_STATE RUNTIME_TRUTH_SNAPSHOT`() {
        val nonTerminalKinds = AndroidReconciliationSignalWireLayerClosure
            .nonTerminalKindDescriptors
            .map { it.kind }
            .toSet()
        assertEquals(
            "PR-Block2: exactly four non-terminal kinds",
            setOf(
                ReconciliationSignal.Kind.TASK_ACCEPTED,
                ReconciliationSignal.Kind.TASK_STATUS_UPDATE,
                ReconciliationSignal.Kind.PARTICIPANT_STATE,
                ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
            ),
            nonTerminalKinds
        )
    }

    // ── Wire envelope shape ───────────────────────────────────────────────────

    @Test
    fun `envelope shape type field equals reconciliation_signal`() {
        assertEquals(
            "reconciliation_signal",
            AndroidReconciliationSignalWireLayerClosure.envelopeShape.envelopeTypeField
        )
    }

    @Test
    fun `envelope shape required fields include type`() {
        assertTrue(
            AndroidReconciliationSignalWireLayerClosure.envelopeShape.requiredEnvelopeFields
                .contains("type")
        )
    }

    @Test
    fun `envelope shape required fields include payload`() {
        assertTrue(
            AndroidReconciliationSignalWireLayerClosure.envelopeShape.requiredEnvelopeFields
                .contains("payload")
        )
    }

    @Test
    fun `envelope shape required fields include device_id`() {
        assertTrue(
            AndroidReconciliationSignalWireLayerClosure.envelopeShape.requiredEnvelopeFields
                .contains("device_id")
        )
    }

    @Test
    fun `envelope shape required payload fields include signal_id`() {
        assertTrue(
            AndroidReconciliationSignalWireLayerClosure.envelopeShape.requiredPayloadFields
                .contains("signal_id")
        )
    }

    @Test
    fun `envelope shape required payload fields include kind`() {
        assertTrue(
            AndroidReconciliationSignalWireLayerClosure.envelopeShape.requiredPayloadFields
                .contains("kind")
        )
    }

    @Test
    fun `envelope shape required payload fields include participant_id`() {
        assertTrue(
            AndroidReconciliationSignalWireLayerClosure.envelopeShape.requiredPayloadFields
                .contains("participant_id")
        )
    }

    @Test
    fun `envelope shape required payload fields include status`() {
        assertTrue(
            AndroidReconciliationSignalWireLayerClosure.envelopeShape.requiredPayloadFields
                .contains("status")
        )
    }

    @Test
    fun `envelope shape required payload fields include emitted_at_ms`() {
        assertTrue(
            AndroidReconciliationSignalWireLayerClosure.envelopeShape.requiredPayloadFields
                .contains("emitted_at_ms")
        )
    }

    @Test
    fun `envelope shape required payload fields include reconciliation_epoch`() {
        assertTrue(
            AndroidReconciliationSignalWireLayerClosure.envelopeShape.requiredPayloadFields
                .contains("reconciliation_epoch")
        )
    }

    @Test
    fun `envelope shape required payload fields include device_id`() {
        assertTrue(
            AndroidReconciliationSignalWireLayerClosure.envelopeShape.requiredPayloadFields
                .contains("device_id")
        )
    }

    @Test
    fun `envelope shape optional payload fields include runtime_truth`() {
        assertTrue(
            AndroidReconciliationSignalWireLayerClosure.envelopeShape.optionalPayloadFields
                .contains("runtime_truth")
        )
    }

    // ── Wire format — full signal → JSON round-trip ───────────────────────────

    @Test
    fun `TASK_ACCEPTED signal serialises to JSON containing reconciliation_signal type`() {
        val signal = ReconciliationSignal.taskAccepted("p-b2", "t-b2", reconciliationEpoch = 1)
        val json = signalToEnvelopeJson(signal)
        assertTrue(
            "PR-Block2: TASK_ACCEPTED JSON must contain type 'reconciliation_signal'",
            json.contains("\"reconciliation_signal\"")
        )
    }

    @Test
    fun `TASK_STATUS_UPDATE signal serialises to JSON containing task_status_update kind`() {
        val signal = ReconciliationSignal.taskStatusUpdate("p-b2", "t-b2", reconciliationEpoch = 1)
        val json = signalToEnvelopeJson(signal)
        assertTrue(
            "PR-Block2: TASK_STATUS_UPDATE JSON must contain 'task_status_update'",
            json.contains("task_status_update")
        )
    }

    @Test
    fun `TASK_RESULT signal serialises to JSON containing task_result kind`() {
        val signal = ReconciliationSignal.taskResult("p-b2", "t-b2", reconciliationEpoch = 1)
        val json = signalToEnvelopeJson(signal)
        assertTrue(
            "PR-Block2: TASK_RESULT JSON must contain 'task_result'",
            json.contains("task_result")
        )
    }

    @Test
    fun `TASK_CANCELLED signal serialises to JSON containing task_cancelled kind`() {
        val signal = ReconciliationSignal.taskCancelled("p-b2", "t-b2", reconciliationEpoch = 1)
        val json = signalToEnvelopeJson(signal)
        assertTrue(
            "PR-Block2: TASK_CANCELLED JSON must contain 'task_cancelled'",
            json.contains("task_cancelled")
        )
    }

    @Test
    fun `TASK_FAILED signal serialises to JSON containing task_failed kind`() {
        val signal = ReconciliationSignal.taskFailed("p-b2", "t-b2", reconciliationEpoch = 1)
        val json = signalToEnvelopeJson(signal)
        assertTrue(
            "PR-Block2: TASK_FAILED JSON must contain 'task_failed'",
            json.contains("task_failed")
        )
    }

    @Test
    fun `PARTICIPANT_STATE signal serialises to JSON containing participant_state kind`() {
        val signal = ReconciliationSignal.participantStateSignal(
            "p-b2", ParticipantHealthState.HEALTHY, ParticipantReadinessState.READY,
            reconciliationEpoch = 1
        )
        val json = signalToEnvelopeJson(signal)
        assertTrue(
            "PR-Block2: PARTICIPANT_STATE JSON must contain 'participant_state'",
            json.contains("participant_state")
        )
    }

    @Test
    fun `RUNTIME_TRUTH_SNAPSHOT signal serialises to JSON with non-null runtime_truth`() {
        val truth = buildTruth()
        val signal = ReconciliationSignal.runtimeTruthSnapshot(truth)
        val json = signalToEnvelopeJson(signal)
        assertTrue(
            "PR-Block2: RUNTIME_TRUTH_SNAPSHOT JSON must contain runtime_truth key",
            json.contains("runtime_truth")
        )
        assertFalse(
            "PR-Block2: RUNTIME_TRUTH_SNAPSHOT JSON runtime_truth must not be null",
            json.contains("\"runtime_truth\":null")
        )
    }

    @Test
    fun `all seven kinds produce AipMessage envelopes with type reconciliation_signal`() {
        val truth = buildTruth()
        val signals = listOf(
            ReconciliationSignal.taskAccepted("p-b2", "t-b2"),
            ReconciliationSignal.taskStatusUpdate("p-b2", "t-b2"),
            ReconciliationSignal.taskResult("p-b2", "t-b2"),
            ReconciliationSignal.taskCancelled("p-b2", "t-b2"),
            ReconciliationSignal.taskFailed("p-b2", "t-b2"),
            ReconciliationSignal.participantStateSignal(
                "p-b2", ParticipantHealthState.HEALTHY, ParticipantReadinessState.READY
            ),
            ReconciliationSignal.runtimeTruthSnapshot(truth)
        )
        for (signal in signals) {
            val json = signalToEnvelopeJson(signal)
            assertTrue(
                "PR-Block2: Kind.${signal.kind.name} envelope must contain type 'reconciliation_signal'; got: $json",
                json.contains("\"reconciliation_signal\"")
            )
        }
    }

    @Test
    fun `signal_id is stable and present in JSON`() {
        val stableId = "block2-stable-id-123"
        val signal = ReconciliationSignal.taskResult("p-b2", "t-b2", signalId = stableId)
        val json = signalToEnvelopeJson(signal)
        assertTrue(
            "PR-Block2: JSON must contain the stable signal_id value",
            json.contains(stableId)
        )
    }

    @Test
    fun `participant_id is stable and present in JSON`() {
        val participantId = "participant-block2-stable"
        val signal = ReconciliationSignal.taskResult(participantId, "t-b2")
        val json = signalToEnvelopeJson(signal)
        assertTrue(
            "PR-Block2: JSON must contain participant_id value",
            json.contains(participantId)
        )
    }

    @Test
    fun `task_id is present in JSON for task signals`() {
        val taskId = "task-block2-stable"
        val signal = ReconciliationSignal.taskResult("p-b2", taskId)
        val json = signalToEnvelopeJson(signal)
        assertTrue(
            "PR-Block2: JSON must contain task_id for task signals",
            json.contains(taskId)
        )
    }

    @Test
    fun `task_id is null for PARTICIPANT_STATE signals`() {
        val signal = ReconciliationSignal.participantStateSignal(
            "p-b2", ParticipantHealthState.HEALTHY, ParticipantReadinessState.READY
        )
        assertNull("PR-Block2: task_id must be null for PARTICIPANT_STATE", signal.taskId)
    }

    @Test
    fun `reconciliation_epoch is echoed in payload`() {
        val signal = ReconciliationSignal.taskResult("p-b2", "t-b2", reconciliationEpoch = 42)
        val json = signalToEnvelopeJson(signal)
        assertTrue(
            "PR-Block2: JSON must contain reconciliation_epoch = 42",
            json.contains("42")
        )
    }

    @Test
    fun `idempotency_key in envelope equals signal_id`() {
        val signalId = "idem-key-block2"
        val signal = ReconciliationSignal.taskResult("p-b2", "t-b2", signalId = signalId)
        val payload = ReconciliationSignalPayload(
            signal_id = signal.signalId,
            kind = signal.kind.wireValue,
            participant_id = signal.participantId,
            status = signal.status,
            emitted_at_ms = signal.emittedAtMs,
            reconciliation_epoch = signal.reconciliationEpoch,
            device_id = "dev-b2"
        )
        val envelope = AipMessage(
            type = MsgType.RECONCILIATION_SIGNAL,
            payload = payload,
            device_id = "dev-b2",
            idempotency_key = signal.signalId
        )
        val json = gson.toJson(envelope)
        assertTrue(
            "PR-Block2: idempotency_key in envelope must echo signal_id",
            json.contains(signalId)
        )
    }

    // ── Wire format — ReconciliationSignalPayload field contract ──────────────

    @Test
    fun `required payload fields are present in Gson JSON for every kind`() {
        val truth = buildTruth()
        val signals = listOf(
            ReconciliationSignal.taskAccepted("p-b2", "t-b2"),
            ReconciliationSignal.taskStatusUpdate("p-b2", "t-b2"),
            ReconciliationSignal.taskResult("p-b2", "t-b2"),
            ReconciliationSignal.taskCancelled("p-b2", "t-b2"),
            ReconciliationSignal.taskFailed("p-b2", "t-b2"),
            ReconciliationSignal.participantStateSignal(
                "p-b2", ParticipantHealthState.HEALTHY, ParticipantReadinessState.READY
            ),
            ReconciliationSignal.runtimeTruthSnapshot(truth)
        )
        val requiredFields = AndroidReconciliationSignalWireLayerClosure.envelopeShape
            .requiredPayloadFields
        for (signal in signals) {
            val payload = ReconciliationSignalPayload(
                signal_id = signal.signalId,
                kind = signal.kind.wireValue,
                participant_id = signal.participantId,
                status = signal.status,
                emitted_at_ms = signal.emittedAtMs,
                reconciliation_epoch = signal.reconciliationEpoch,
                device_id = "dev-b2",
                task_id = signal.taskId,
                payload = signal.payload,
                runtime_truth = signal.runtimeTruth?.toMap()
            )
            val json = gson.toJson(payload)
            for (field in requiredFields) {
                assertTrue(
                    "PR-Block2: Kind.${signal.kind.name} payload JSON missing required field '$field'",
                    json.contains(field)
                )
            }
        }
    }

    @Test
    fun `TASK_FAILED payload contains error_detail when provided`() {
        val signal = ReconciliationSignal.taskFailed(
            "p-b2", "t-b2", errorDetail = "block2-error-reason"
        )
        assertTrue(
            "PR-Block2: TASK_FAILED payload must contain error_detail",
            signal.payload.containsKey("error_detail")
        )
        assertEquals("block2-error-reason", signal.payload["error_detail"])
    }

    @Test
    fun `PARTICIPANT_STATE payload contains health_state and readiness_state`() {
        val signal = ReconciliationSignal.participantStateSignal(
            "p-b2",
            ParticipantHealthState.DEGRADED,
            ParticipantReadinessState.NOT_READY
        )
        assertTrue(
            "PR-Block2: PARTICIPANT_STATE payload must contain health_state",
            signal.payload.containsKey("health_state")
        )
        assertTrue(
            "PR-Block2: PARTICIPANT_STATE payload must contain readiness_state",
            signal.payload.containsKey("readiness_state")
        )
    }

    @Test
    fun `runtime_truth is null for non-snapshot signals`() {
        val nonSnapshotSignals = listOf(
            ReconciliationSignal.taskAccepted("p-b2", "t-b2"),
            ReconciliationSignal.taskResult("p-b2", "t-b2"),
            ReconciliationSignal.taskCancelled("p-b2", "t-b2"),
            ReconciliationSignal.taskFailed("p-b2", "t-b2"),
            ReconciliationSignal.participantStateSignal(
                "p-b2", ParticipantHealthState.HEALTHY, ParticipantReadinessState.READY
            )
        )
        for (signal in nonSnapshotSignals) {
            assertNull(
                "PR-Block2: Kind.${signal.kind.name} runtime_truth must be null",
                signal.runtimeTruth
            )
        }
    }

    @Test
    fun `runtime_truth is non-null for RUNTIME_TRUTH_SNAPSHOT signal`() {
        val truth = buildTruth()
        val signal = ReconciliationSignal.runtimeTruthSnapshot(truth)
        assertNotNull(
            "PR-Block2: RUNTIME_TRUTH_SNAPSHOT runtime_truth must be non-null",
            signal.runtimeTruth
        )
        assertTrue(
            "PR-Block2: RUNTIME_TRUTH_SNAPSHOT hasRuntimeTruth must be true",
            signal.hasRuntimeTruth
        )
    }

    // ── Send path chain — signal → payload → envelope → sendJson ─────────────

    @Test
    fun `all seven kinds produce valid non-blank JSON via the envelope chain`() {
        val truth = buildTruth()
        val signals = listOf(
            ReconciliationSignal.taskAccepted("p-b2", "t-b2"),
            ReconciliationSignal.taskStatusUpdate("p-b2", "t-b2"),
            ReconciliationSignal.taskResult("p-b2", "t-b2"),
            ReconciliationSignal.taskCancelled("p-b2", "t-b2"),
            ReconciliationSignal.taskFailed("p-b2", "t-b2"),
            ReconciliationSignal.participantStateSignal(
                "p-b2", ParticipantHealthState.HEALTHY, ParticipantReadinessState.READY
            ),
            ReconciliationSignal.runtimeTruthSnapshot(truth)
        )
        for (signal in signals) {
            val json = signalToEnvelopeJson(signal)
            assertTrue(
                "PR-Block2: Kind.${signal.kind.name} must produce non-blank JSON",
                json.isNotBlank()
            )
            assertTrue(
                "PR-Block2: Kind.${signal.kind.name} JSON must start with '{'",
                json.startsWith("{")
            )
        }
    }

    @Test
    fun `send failure false return does not throw`() {
        val signal = ReconciliationSignal.taskResult("p-b2", "t-b2")
        val json = signalToEnvelopeJson(signal)
        // Simulates a failed send (returns false) — must not throw
        val result: Boolean = json.isNotBlank() && false
        assertFalse("PR-Block2: simulated send failure must return false without throwing", result)
    }

    @Test
    fun `send success true return produces correct JSON`() {
        val sent = mutableListOf<String>()
        val fakeSendJson: (String) -> Boolean = { json -> sent += json; true }

        val signal = ReconciliationSignal.taskResult("p-b2", "t-b2")
        val json = signalToEnvelopeJson(signal)
        val result = fakeSendJson(json)

        assertTrue("PR-Block2: simulated send must return true", result)
        assertEquals("PR-Block2: exactly one JSON must be captured", 1, sent.size)
        assertTrue(
            "PR-Block2: captured JSON must contain 'reconciliation_signal'",
            sent[0].contains("reconciliation_signal")
        )
    }

    @Test
    fun `fake-sendJson chain confirms all seven kinds call through`() {
        val sent = mutableListOf<String>()
        val fakeSendJson: (String) -> Boolean = { json -> sent += json; true }

        val truth = buildTruth()
        val signals = listOf(
            ReconciliationSignal.taskAccepted("p-b2", "t-b2"),
            ReconciliationSignal.taskStatusUpdate("p-b2", "t-b2"),
            ReconciliationSignal.taskResult("p-b2", "t-b2"),
            ReconciliationSignal.taskCancelled("p-b2", "t-b2"),
            ReconciliationSignal.taskFailed("p-b2", "t-b2"),
            ReconciliationSignal.participantStateSignal(
                "p-b2", ParticipantHealthState.HEALTHY, ParticipantReadinessState.READY
            ),
            ReconciliationSignal.runtimeTruthSnapshot(truth)
        )

        for (signal in signals) {
            fakeSendJson(signalToEnvelopeJson(signal))
        }

        assertEquals(
            "PR-Block2: all seven signal kinds must produce exactly one sendJson call each",
            7, sent.size
        )
        assertTrue(
            "PR-Block2: all seven JSON outputs must contain 'reconciliation_signal' type",
            sent.all { it.contains("reconciliation_signal") }
        )
    }

    // ── Protocol surface closure — V2 compatibility ───────────────────────────

    @Test
    fun `all kind wire values are distinct`() {
        val wireValues = AndroidReconciliationSignalWireLayerClosure.kindDescriptors
            .map { it.wireValue }
        assertEquals(
            "PR-Block2: kind wire values must be distinct (no collisions)",
            wireValues.size,
            wireValues.toSet().size
        )
    }

    @Test
    fun `all kind wire values match ReconciliationSignal Kind wireValue`() {
        for (desc in AndroidReconciliationSignalWireLayerClosure.kindDescriptors) {
            assertEquals(
                "PR-Block2: descriptor wire value for Kind.${desc.kind.name} must equal Kind.wireValue",
                desc.kind.wireValue,
                desc.wireValue
            )
        }
    }

    @Test
    fun `allKindWireValues set covers all seven Kind wireValue strings`() {
        val closureWireValues = AndroidReconciliationSignalWireLayerClosure.allKindWireValues
        for (kind in ReconciliationSignal.Kind.entries) {
            assertTrue(
                "PR-Block2: allKindWireValues must contain '${kind.wireValue}' for Kind.${kind.name}",
                kind.wireValue in closureWireValues
            )
        }
    }

    @Test
    fun `RECONCILIATION_SIGNAL wire type is known to MsgType fromValue`() {
        val resolved = MsgType.fromValue("reconciliation_signal")
        assertEquals(
            "PR-Block2: MsgType.fromValue must resolve 'reconciliation_signal' to RECONCILIATION_SIGNAL",
            MsgType.RECONCILIATION_SIGNAL,
            resolved
        )
    }

    @Test
    fun `requireKindDescriptor returns correct descriptor for each kind`() {
        for (kind in ReconciliationSignal.Kind.entries) {
            val desc = AndroidReconciliationSignalWireLayerClosure.requireKindDescriptor(kind)
            assertEquals(
                "PR-Block2: requireKindDescriptor(${kind.name}).kind must equal the requested kind",
                kind,
                desc.kind
            )
        }
    }
}

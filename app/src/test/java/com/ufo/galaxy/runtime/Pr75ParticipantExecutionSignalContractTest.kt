package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.TakeoverResponseEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-75 (Android PR-A3) — Participant Execution Signal Contract: acceptance and contract tests.
 *
 * Validates the complete unified participant execution signal contract introduced by PR-75.
 * This contract canonicalizes delegated, takeover, and reconciliation signals under a
 * single [ParticipantExecutionSignalContract.ExecSignalClass] classification, eliminating
 * the parallel delegated lifecycle reality outside the center-side canonical execution chain.
 *
 * ## Test matrix
 *
 * ### ExecSignalClass — enum values and wire values
 *  - ACK wireValue is "ack"
 *  - NON_TERMINAL wireValue is "non_terminal"
 *  - TERMINAL wireValue is "terminal"
 *  - RECONCILE_ONLY wireValue is "reconcile_only"
 *  - all four wire values are distinct
 *  - ALL_WIRE_VALUES has exactly four entries
 *  - fromWireValue round-trips all four values
 *  - fromWireValue returns null for unknown value
 *
 * ### ExecSignalClass — isXxx flags
 *  - ACK: isAck=true, isNonTerminal=false, isTerminal=false, isReconcileOnly=false
 *  - NON_TERMINAL: isAck=false, isNonTerminal=true, isTerminal=false, isReconcileOnly=false
 *  - TERMINAL: isAck=false, isNonTerminal=false, isTerminal=true, isReconcileOnly=false
 *  - RECONCILE_ONLY: isAck=false, isNonTerminal=false, isTerminal=false, isReconcileOnly=true
 *
 * ### ExecSignalClass — participatesInExecutionLifecycle
 *  - ACK participates in execution lifecycle
 *  - NON_TERMINAL participates in execution lifecycle
 *  - TERMINAL participates in execution lifecycle
 *  - RECONCILE_ONLY does NOT participate in execution lifecycle
 *
 * ### DelegatedExecutionSignal — participantExecSignalClass mapping
 *  - Kind.ACK maps to ExecSignalClass.ACK
 *  - Kind.PROGRESS maps to ExecSignalClass.NON_TERMINAL
 *  - Kind.RESULT maps to ExecSignalClass.TERMINAL (regardless of ResultKind)
 *  - ResultKind.COMPLETED produces TERMINAL
 *  - ResultKind.FAILED produces TERMINAL
 *  - ResultKind.TIMEOUT produces TERMINAL
 *  - ResultKind.CANCELLED produces TERMINAL
 *  - ResultKind.REJECTED produces TERMINAL
 *  - ACK signal: participantExecSignalClass.isAck = true
 *  - PROGRESS signal: participantExecSignalClass.isNonTerminal = true
 *  - RESULT signal: participantExecSignalClass.isTerminal = true
 *
 * ### ReconciliationSignal — participantExecSignalClass mapping
 *  - Kind.TASK_ACCEPTED maps to ExecSignalClass.ACK
 *  - Kind.TASK_STATUS_UPDATE maps to ExecSignalClass.NON_TERMINAL
 *  - Kind.TASK_RESULT maps to ExecSignalClass.TERMINAL
 *  - Kind.TASK_CANCELLED maps to ExecSignalClass.TERMINAL
 *  - Kind.TASK_FAILED maps to ExecSignalClass.TERMINAL
 *  - Kind.PARTICIPANT_STATE maps to ExecSignalClass.RECONCILE_ONLY
 *  - Kind.RUNTIME_TRUTH_SNAPSHOT maps to ExecSignalClass.RECONCILE_ONLY
 *  - isTerminal consistency: reconciliation TERMINAL class ↔ isTerminal property
 *  - RECONCILE_ONLY signals: participatesInExecutionLifecycle = false
 *
 * ### TakeoverResponseEnvelope — participantExecSignalClass mapping
 *  - accepted=true maps to ExecSignalClass.ACK
 *  - accepted=false maps to ExecSignalClass.TERMINAL
 *  - accepted response: participantExecSignalClass.isAck = true
 *  - rejected response: participantExecSignalClass.isTerminal = true
 *
 * ### Cross-type consistency
 *  - DelegatedExecutionSignal ACK and ReconciliationSignal TASK_ACCEPTED share ExecSignalClass.ACK
 *  - DelegatedExecutionSignal PROGRESS and ReconciliationSignal TASK_STATUS_UPDATE share NON_TERMINAL
 *  - DelegatedExecutionSignal RESULT and ReconciliationSignal TASK_RESULT share TERMINAL
 *  - DelegatedExecutionSignal RESULT and ReconciliationSignal TASK_CANCELLED share TERMINAL
 *  - DelegatedExecutionSignal RESULT and ReconciliationSignal TASK_FAILED share TERMINAL
 *  - TakeoverResponseEnvelope accepted=true shares ACK class with DelegatedExecutionSignal ACK
 *  - TakeoverResponseEnvelope accepted=false shares TERMINAL class with DelegatedExecutionSignal RESULT
 *
 * ### Contract wire map
 *  - buildContractWireMap returns non-null map
 *  - INTRODUCED_PR = 75
 *  - terminal_is_only_task_closer = true
 *  - ack_must_not_imply_terminal_completion = true
 *  - reconcile_only_has_no_lifecycle_semantics = true
 *  - android_must_not_assign_independent_lifecycle_meaning = true
 *  - signal_classes contains all four ExecSignalClass wire values
 *
 * @see ParticipantExecutionSignalContract
 * @see DelegatedExecutionSignal
 * @see ReconciliationSignal
 * @see TakeoverResponseEnvelope
 */
class Pr75ParticipantExecutionSignalContractTest {

    // ── ExecSignalClass — wire values ─────────────────────────────────────────

    @Test
    fun `ExecSignalClass ACK wireValue is ack`() {
        assertEquals("ack", ParticipantExecutionSignalContract.ExecSignalClass.ACK.wireValue)
    }

    @Test
    fun `ExecSignalClass NON_TERMINAL wireValue is non_terminal`() {
        assertEquals(
            "non_terminal",
            ParticipantExecutionSignalContract.ExecSignalClass.NON_TERMINAL.wireValue
        )
    }

    @Test
    fun `ExecSignalClass TERMINAL wireValue is terminal`() {
        assertEquals(
            "terminal",
            ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL.wireValue
        )
    }

    @Test
    fun `ExecSignalClass RECONCILE_ONLY wireValue is reconcile_only`() {
        assertEquals(
            "reconcile_only",
            ParticipantExecutionSignalContract.ExecSignalClass.RECONCILE_ONLY.wireValue
        )
    }

    @Test
    fun `all four ExecSignalClass wire values are distinct`() {
        val values = ParticipantExecutionSignalContract.ExecSignalClass.entries.map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `ALL_WIRE_VALUES has exactly four entries`() {
        assertEquals(4, ParticipantExecutionSignalContract.ExecSignalClass.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `fromWireValue round-trips all four ExecSignalClass values`() {
        ParticipantExecutionSignalContract.ExecSignalClass.entries.forEach { cls ->
            assertEquals(cls, ParticipantExecutionSignalContract.ExecSignalClass.fromWireValue(cls.wireValue))
        }
    }

    @Test
    fun `fromWireValue returns null for unknown value`() {
        assertNull(ParticipantExecutionSignalContract.ExecSignalClass.fromWireValue("unknown_class"))
    }

    @Test
    fun `fromWireValue returns null for null input`() {
        assertNull(ParticipantExecutionSignalContract.ExecSignalClass.fromWireValue(null))
    }

    // ── ExecSignalClass — isXxx flags ─────────────────────────────────────────

    @Test
    fun `ACK has isAck true and all other flags false`() {
        val cls = ParticipantExecutionSignalContract.ExecSignalClass.ACK
        assertTrue(cls.isAck)
        assertFalse(cls.isNonTerminal)
        assertFalse(cls.isTerminal)
        assertFalse(cls.isReconcileOnly)
    }

    @Test
    fun `NON_TERMINAL has isNonTerminal true and all other flags false`() {
        val cls = ParticipantExecutionSignalContract.ExecSignalClass.NON_TERMINAL
        assertFalse(cls.isAck)
        assertTrue(cls.isNonTerminal)
        assertFalse(cls.isTerminal)
        assertFalse(cls.isReconcileOnly)
    }

    @Test
    fun `TERMINAL has isTerminal true and all other flags false`() {
        val cls = ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL
        assertFalse(cls.isAck)
        assertFalse(cls.isNonTerminal)
        assertTrue(cls.isTerminal)
        assertFalse(cls.isReconcileOnly)
    }

    @Test
    fun `RECONCILE_ONLY has isReconcileOnly true and all other flags false`() {
        val cls = ParticipantExecutionSignalContract.ExecSignalClass.RECONCILE_ONLY
        assertFalse(cls.isAck)
        assertFalse(cls.isNonTerminal)
        assertFalse(cls.isTerminal)
        assertTrue(cls.isReconcileOnly)
    }

    // ── ExecSignalClass — participatesInExecutionLifecycle ────────────────────

    @Test
    fun `ACK participates in execution lifecycle`() {
        assertTrue(ParticipantExecutionSignalContract.ExecSignalClass.ACK.participatesInExecutionLifecycle)
    }

    @Test
    fun `NON_TERMINAL participates in execution lifecycle`() {
        assertTrue(
            ParticipantExecutionSignalContract.ExecSignalClass.NON_TERMINAL.participatesInExecutionLifecycle
        )
    }

    @Test
    fun `TERMINAL participates in execution lifecycle`() {
        assertTrue(
            ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL.participatesInExecutionLifecycle
        )
    }

    @Test
    fun `RECONCILE_ONLY does NOT participate in execution lifecycle`() {
        assertFalse(
            ParticipantExecutionSignalContract.ExecSignalClass.RECONCILE_ONLY.participatesInExecutionLifecycle
        )
    }

    // ── DelegatedExecutionSignal — participantExecSignalClass ─────────────────

    private fun makeDelegatedSignal(
        kind: DelegatedExecutionSignal.Kind,
        resultKind: DelegatedExecutionSignal.ResultKind? = null
    ): DelegatedExecutionSignal = DelegatedExecutionSignal(
        kind = kind,
        unitId = "unit-75",
        taskId = "task-75",
        traceId = "trace-75",
        attachedSessionId = "session-75",
        handoffContractVersion = 1,
        stepCount = 0,
        activationStatusHint = "pending",
        resultKind = resultKind,
        timestampMs = 1_000L,
        signalId = "sig-75",
        emissionSeq = when (kind) {
            DelegatedExecutionSignal.Kind.ACK      -> DelegatedExecutionSignal.EMISSION_SEQ_ACK
            DelegatedExecutionSignal.Kind.PROGRESS -> DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS
            DelegatedExecutionSignal.Kind.RESULT   -> DelegatedExecutionSignal.EMISSION_SEQ_RESULT
        }
    )

    @Test
    fun `DelegatedExecutionSignal Kind ACK maps to ExecSignalClass ACK`() {
        val signal = makeDelegatedSignal(DelegatedExecutionSignal.Kind.ACK)
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.ACK,
            signal.participantExecSignalClass
        )
    }

    @Test
    fun `DelegatedExecutionSignal Kind PROGRESS maps to ExecSignalClass NON_TERMINAL`() {
        val signal = makeDelegatedSignal(DelegatedExecutionSignal.Kind.PROGRESS)
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.NON_TERMINAL,
            signal.participantExecSignalClass
        )
    }

    @Test
    fun `DelegatedExecutionSignal Kind RESULT maps to ExecSignalClass TERMINAL`() {
        val signal = makeDelegatedSignal(
            DelegatedExecutionSignal.Kind.RESULT,
            DelegatedExecutionSignal.ResultKind.COMPLETED
        )
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL,
            signal.participantExecSignalClass
        )
    }

    @Test
    fun `DelegatedExecutionSignal RESULT COMPLETED produces TERMINAL`() {
        val signal = makeDelegatedSignal(DelegatedExecutionSignal.Kind.RESULT, DelegatedExecutionSignal.ResultKind.COMPLETED)
        assertTrue(signal.participantExecSignalClass.isTerminal)
    }

    @Test
    fun `DelegatedExecutionSignal RESULT FAILED produces TERMINAL`() {
        val signal = makeDelegatedSignal(DelegatedExecutionSignal.Kind.RESULT, DelegatedExecutionSignal.ResultKind.FAILED)
        assertTrue(signal.participantExecSignalClass.isTerminal)
    }

    @Test
    fun `DelegatedExecutionSignal RESULT TIMEOUT produces TERMINAL`() {
        val signal = makeDelegatedSignal(DelegatedExecutionSignal.Kind.RESULT, DelegatedExecutionSignal.ResultKind.TIMEOUT)
        assertTrue(signal.participantExecSignalClass.isTerminal)
    }

    @Test
    fun `DelegatedExecutionSignal RESULT CANCELLED produces TERMINAL`() {
        val signal = makeDelegatedSignal(DelegatedExecutionSignal.Kind.RESULT, DelegatedExecutionSignal.ResultKind.CANCELLED)
        assertTrue(signal.participantExecSignalClass.isTerminal)
    }

    @Test
    fun `DelegatedExecutionSignal RESULT REJECTED produces TERMINAL`() {
        val signal = makeDelegatedSignal(DelegatedExecutionSignal.Kind.RESULT, DelegatedExecutionSignal.ResultKind.REJECTED)
        assertTrue(signal.participantExecSignalClass.isTerminal)
    }

    @Test
    fun `DelegatedExecutionSignal ACK participantExecSignalClass isAck is true`() {
        val signal = makeDelegatedSignal(DelegatedExecutionSignal.Kind.ACK)
        assertTrue(signal.participantExecSignalClass.isAck)
    }

    @Test
    fun `DelegatedExecutionSignal PROGRESS participantExecSignalClass isNonTerminal is true`() {
        val signal = makeDelegatedSignal(DelegatedExecutionSignal.Kind.PROGRESS)
        assertTrue(signal.participantExecSignalClass.isNonTerminal)
    }

    @Test
    fun `DelegatedExecutionSignal RESULT participantExecSignalClass isTerminal is true`() {
        val signal = makeDelegatedSignal(DelegatedExecutionSignal.Kind.RESULT, DelegatedExecutionSignal.ResultKind.COMPLETED)
        assertTrue(signal.participantExecSignalClass.isTerminal)
    }

    // ── ReconciliationSignal — participantExecSignalClass ─────────────────────

    @Test
    fun `ReconciliationSignal TASK_ACCEPTED maps to ExecSignalClass ACK`() {
        val signal = ReconciliationSignal.taskAccepted("pid-75", "tid-75")
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.ACK,
            signal.participantExecSignalClass
        )
    }

    @Test
    fun `ReconciliationSignal TASK_STATUS_UPDATE maps to ExecSignalClass NON_TERMINAL`() {
        val signal = ReconciliationSignal.taskStatusUpdate("pid-75", "tid-75")
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.NON_TERMINAL,
            signal.participantExecSignalClass
        )
    }

    @Test
    fun `ReconciliationSignal TASK_RESULT maps to ExecSignalClass TERMINAL`() {
        val signal = ReconciliationSignal.taskResult("pid-75", "tid-75")
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL,
            signal.participantExecSignalClass
        )
    }

    @Test
    fun `ReconciliationSignal TASK_CANCELLED maps to ExecSignalClass TERMINAL`() {
        val signal = ReconciliationSignal.taskCancelled("pid-75", "tid-75")
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL,
            signal.participantExecSignalClass
        )
    }

    @Test
    fun `ReconciliationSignal TASK_FAILED maps to ExecSignalClass TERMINAL`() {
        val signal = ReconciliationSignal.taskFailed("pid-75", "tid-75")
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL,
            signal.participantExecSignalClass
        )
    }

    @Test
    fun `ReconciliationSignal PARTICIPANT_STATE maps to ExecSignalClass RECONCILE_ONLY`() {
        val signal = ReconciliationSignal.participantStateSignal(
            participantId = "pid-75",
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY
        )
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.RECONCILE_ONLY,
            signal.participantExecSignalClass
        )
    }

    @Test
    fun `ReconciliationSignal RUNTIME_TRUTH_SNAPSHOT maps to ExecSignalClass RECONCILE_ONLY`() {
        val truth = AndroidParticipantRuntimeTruth(
            participantId = "pid-75",
            deviceId = "device-75",
            hostId = "host-75",
            deviceRole = "phone",
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
            coordinationRole = ParticipantCoordinationRole.PARTICIPANT,
            sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
            sessionId = null,
            sessionState = null,
            delegatedExecutionCount = 0,
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            activeTaskId = null,
            activeTaskStatus = null,
            reportedAtMs = 1_000L,
            reconciliationEpoch = 1
        )
        val signal = ReconciliationSignal.runtimeTruthSnapshot(truth)
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.RECONCILE_ONLY,
            signal.participantExecSignalClass
        )
    }

    @Test
    fun `ReconciliationSignal isTerminal and participantExecSignalClass TERMINAL are consistent`() {
        val terminalSignals = listOf(
            ReconciliationSignal.taskResult("pid-75", "tid-75"),
            ReconciliationSignal.taskCancelled("pid-75", "tid-75"),
            ReconciliationSignal.taskFailed("pid-75", "tid-75")
        )
        terminalSignals.forEach { signal ->
            assertTrue(
                "isTerminal should be true for ${signal.kind}",
                signal.isTerminal
            )
            assertTrue(
                "participantExecSignalClass.isTerminal should be true for ${signal.kind}",
                signal.participantExecSignalClass.isTerminal
            )
        }
    }

    @Test
    fun `ReconciliationSignal RECONCILE_ONLY signals do not participate in execution lifecycle`() {
        val truth = AndroidParticipantRuntimeTruth(
            participantId = "pid-75",
            deviceId = "device-75",
            hostId = "host-75",
            deviceRole = "phone",
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
            coordinationRole = ParticipantCoordinationRole.PARTICIPANT,
            sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
            sessionId = null,
            sessionState = null,
            delegatedExecutionCount = 0,
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            activeTaskId = null,
            activeTaskStatus = null,
            reportedAtMs = 1_000L,
            reconciliationEpoch = 1
        )
        val reconcileOnlySignals = listOf(
            ReconciliationSignal.participantStateSignal(
                participantId = "pid-75",
                healthState = ParticipantHealthState.HEALTHY,
                readinessState = ParticipantReadinessState.READY
            ),
            ReconciliationSignal.runtimeTruthSnapshot(truth)
        )
        reconcileOnlySignals.forEach { signal ->
            assertFalse(
                "participatesInExecutionLifecycle should be false for RECONCILE_ONLY signal ${signal.kind}",
                signal.participantExecSignalClass.participatesInExecutionLifecycle
            )
        }
    }

    // ── TakeoverResponseEnvelope — participantExecSignalClass ─────────────────

    @Test
    fun `TakeoverResponseEnvelope accepted=true maps to ExecSignalClass ACK`() {
        val response = TakeoverResponseEnvelope(
            takeover_id = "to-75",
            task_id = "t-75",
            trace_id = "tr-75",
            accepted = true
        )
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.ACK,
            response.participantExecSignalClass
        )
    }

    @Test
    fun `TakeoverResponseEnvelope accepted=false maps to ExecSignalClass TERMINAL`() {
        val response = TakeoverResponseEnvelope(
            takeover_id = "to-75",
            task_id = "t-75",
            trace_id = "tr-75",
            accepted = false,
            rejection_reason = "not_eligible"
        )
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL,
            response.participantExecSignalClass
        )
    }

    @Test
    fun `TakeoverResponseEnvelope accepted response participantExecSignalClass isAck is true`() {
        val response = TakeoverResponseEnvelope(
            takeover_id = "to-75",
            task_id = "t-75",
            trace_id = "tr-75",
            accepted = true
        )
        assertTrue(response.participantExecSignalClass.isAck)
    }

    @Test
    fun `TakeoverResponseEnvelope rejected response participantExecSignalClass isTerminal is true`() {
        val response = TakeoverResponseEnvelope(
            takeover_id = "to-75",
            task_id = "t-75",
            trace_id = "tr-75",
            accepted = false,
            rejection_reason = "capacity_full"
        )
        assertTrue(response.participantExecSignalClass.isTerminal)
    }

    // ── Cross-type consistency ────────────────────────────────────────────────

    @Test
    fun `DelegatedExecutionSignal ACK and ReconciliationSignal TASK_ACCEPTED share ExecSignalClass ACK`() {
        val delegated = makeDelegatedSignal(DelegatedExecutionSignal.Kind.ACK)
        val reconciliation = ReconciliationSignal.taskAccepted("pid-75", "tid-75")
        assertEquals(delegated.participantExecSignalClass, reconciliation.participantExecSignalClass)
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.ACK,
            delegated.participantExecSignalClass
        )
    }

    @Test
    fun `DelegatedExecutionSignal PROGRESS and ReconciliationSignal TASK_STATUS_UPDATE share NON_TERMINAL`() {
        val delegated = makeDelegatedSignal(DelegatedExecutionSignal.Kind.PROGRESS)
        val reconciliation = ReconciliationSignal.taskStatusUpdate("pid-75", "tid-75")
        assertEquals(delegated.participantExecSignalClass, reconciliation.participantExecSignalClass)
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.NON_TERMINAL,
            delegated.participantExecSignalClass
        )
    }

    @Test
    fun `DelegatedExecutionSignal RESULT and ReconciliationSignal TASK_RESULT share TERMINAL`() {
        val delegated = makeDelegatedSignal(DelegatedExecutionSignal.Kind.RESULT, DelegatedExecutionSignal.ResultKind.COMPLETED)
        val reconciliation = ReconciliationSignal.taskResult("pid-75", "tid-75")
        assertEquals(delegated.participantExecSignalClass, reconciliation.participantExecSignalClass)
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL,
            delegated.participantExecSignalClass
        )
    }

    @Test
    fun `DelegatedExecutionSignal RESULT and ReconciliationSignal TASK_CANCELLED share TERMINAL`() {
        val delegated = makeDelegatedSignal(DelegatedExecutionSignal.Kind.RESULT, DelegatedExecutionSignal.ResultKind.CANCELLED)
        val reconciliation = ReconciliationSignal.taskCancelled("pid-75", "tid-75")
        assertEquals(delegated.participantExecSignalClass, reconciliation.participantExecSignalClass)
        assertTrue(delegated.participantExecSignalClass.isTerminal)
    }

    @Test
    fun `DelegatedExecutionSignal RESULT and ReconciliationSignal TASK_FAILED share TERMINAL`() {
        val delegated = makeDelegatedSignal(DelegatedExecutionSignal.Kind.RESULT, DelegatedExecutionSignal.ResultKind.FAILED)
        val reconciliation = ReconciliationSignal.taskFailed("pid-75", "tid-75")
        assertEquals(delegated.participantExecSignalClass, reconciliation.participantExecSignalClass)
        assertTrue(delegated.participantExecSignalClass.isTerminal)
    }

    @Test
    fun `TakeoverResponseEnvelope accepted=true shares ACK class with DelegatedExecutionSignal ACK`() {
        val delegated = makeDelegatedSignal(DelegatedExecutionSignal.Kind.ACK)
        val takeover = TakeoverResponseEnvelope(
            takeover_id = "to-75",
            task_id = "t-75",
            trace_id = "tr-75",
            accepted = true
        )
        assertEquals(delegated.participantExecSignalClass, takeover.participantExecSignalClass)
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.ACK,
            takeover.participantExecSignalClass
        )
    }

    @Test
    fun `TakeoverResponseEnvelope accepted=false shares TERMINAL class with DelegatedExecutionSignal RESULT`() {
        val delegated = makeDelegatedSignal(DelegatedExecutionSignal.Kind.RESULT, DelegatedExecutionSignal.ResultKind.REJECTED)
        val takeover = TakeoverResponseEnvelope(
            takeover_id = "to-75",
            task_id = "t-75",
            trace_id = "tr-75",
            accepted = false,
            rejection_reason = "not_ready"
        )
        assertEquals(delegated.participantExecSignalClass, takeover.participantExecSignalClass)
        assertEquals(
            ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL,
            takeover.participantExecSignalClass
        )
    }

    // ── Contract wire map ─────────────────────────────────────────────────────

    @Test
    fun `buildContractWireMap returns non-null map`() {
        assertNotNull(ParticipantExecutionSignalContract.buildContractWireMap())
    }

    @Test
    fun `buildContractWireMap INTRODUCED_PR is 75`() {
        val map = ParticipantExecutionSignalContract.buildContractWireMap()
        assertEquals(75, map["introduced_pr"])
    }

    @Test
    fun `buildContractWireMap terminal_is_only_task_closer is true`() {
        val map = ParticipantExecutionSignalContract.buildContractWireMap()
        assertEquals(true, map["terminal_is_only_task_closer"])
    }

    @Test
    fun `buildContractWireMap ack_must_not_imply_terminal_completion is true`() {
        val map = ParticipantExecutionSignalContract.buildContractWireMap()
        assertEquals(true, map["ack_must_not_imply_terminal_completion"])
    }

    @Test
    fun `buildContractWireMap reconcile_only_has_no_lifecycle_semantics is true`() {
        val map = ParticipantExecutionSignalContract.buildContractWireMap()
        assertEquals(true, map["reconcile_only_has_no_lifecycle_semantics"])
    }

    @Test
    fun `buildContractWireMap android_must_not_assign_independent_lifecycle_meaning is true`() {
        val map = ParticipantExecutionSignalContract.buildContractWireMap()
        assertEquals(true, map["android_must_not_assign_independent_lifecycle_meaning"])
    }

    @Test
    fun `buildContractWireMap signal_classes contains all four ExecSignalClass wire values`() {
        val map = ParticipantExecutionSignalContract.buildContractWireMap()
        @Suppress("UNCHECKED_CAST")
        val signalClasses = map["signal_classes"] as List<String>
        val expected = ParticipantExecutionSignalContract.ExecSignalClass.ALL_WIRE_VALUES
        assertEquals(expected.size, signalClasses.size)
        assertTrue(signalClasses.containsAll(expected))
    }

    @Test
    fun `INTRODUCED_PR constant is 75`() {
        assertEquals(75, ParticipantExecutionSignalContract.INTRODUCED_PR)
    }
}

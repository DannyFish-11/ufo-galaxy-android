package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr122AndroidRuntimeEmissionTruthSemanticsTest {

    @Test
    fun `clean terminal emission is classified as fresh completion`() {
        val truth = AndroidRuntimeEmissionTruthSemantics.derive(
            recoveryPhase = AndroidContinuityRecoveryStateModel.RecoveryPhase.RESUMED_CLEANLY,
            isContinuation = false,
            interruptionReason = null,
            isTerminal = true,
            deliveryDisposition = AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.DIRECT_SENT,
            resultConvergenceDecision =
                AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_FINAL_FOR_FLOW
        )

        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.ExecutionContinuityClass.FRESH_EXECUTION,
            truth.executionContinuityClass
        )
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.TerminalEmissionClass.TERMINAL_COMPLETION,
            truth.terminalEmissionClass
        )
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.DIRECT_SENT,
            truth.deliveryDisposition
        )
    }

    @Test
    fun `recovering terminal emission is classified as resumed completion`() {
        val truth = AndroidRuntimeEmissionTruthSemantics.derive(
            recoveryPhase = AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERING,
            isContinuation = true,
            interruptionReason = "disconnect_during_execution",
            isTerminal = true,
            deliveryDisposition = AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.DIRECT_SENT,
            resultConvergenceDecision =
                AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_FINAL_FOR_FLOW
        )

        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.ExecutionContinuityClass.RESUMED_EXECUTION,
            truth.executionContinuityClass
        )
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.TerminalEmissionClass.RESUMED_TERMINAL_COMPLETION,
            truth.terminalEmissionClass
        )
    }

    @Test
    fun `recovered inflight terminal emission is classified as recovered completion`() {
        val truth = AndroidRuntimeEmissionTruthSemantics.derive(
            recoveryPhase = AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERED_INFLIGHT,
            isContinuation = false,
            interruptionReason = null,
            isTerminal = true,
            deliveryDisposition = AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.DIRECT_SENT
        )

        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.ExecutionContinuityClass.RECOVERED_EXECUTION,
            truth.executionContinuityClass
        )
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.TerminalEmissionClass.RECOVERED_TERMINAL_COMPLETION,
            truth.terminalEmissionClass
        )
    }

    @Test
    fun `replayed delivery is never classified as fresh completion`() {
        val truth = AndroidRuntimeEmissionTruthSemantics.derive(
            recoveryPhase = AndroidContinuityRecoveryStateModel.RecoveryPhase.RESUMED_CLEANLY,
            isContinuation = false,
            interruptionReason = null,
            isTerminal = true,
            deliveryDisposition = AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.REPLAYED_FORWARDED
        )

        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.ExecutionContinuityClass.REPLAYED_DELIVERY,
            truth.executionContinuityClass
        )
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.TerminalEmissionClass.REPLAYED_TERMINAL_COMPLETION,
            truth.terminalEmissionClass
        )
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.REPLAYED_FORWARDED,
            truth.deliveryDisposition
        )
    }

    @Test
    fun `offline queued or failed terminal delivery is classified as degraded output`() {
        val queued = AndroidRuntimeEmissionTruthSemantics.derive(
            recoveryPhase = AndroidContinuityRecoveryStateModel.RecoveryPhase.RESUMED_CLEANLY,
            isContinuation = false,
            interruptionReason = null,
            isTerminal = true,
            deliveryDisposition = AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.OFFLINE_QUEUED
        )
        val failed = AndroidRuntimeEmissionTruthSemantics.derive(
            recoveryPhase = AndroidContinuityRecoveryStateModel.RecoveryPhase.RESUMED_CLEANLY,
            isContinuation = false,
            interruptionReason = null,
            isTerminal = true,
            deliveryDisposition = AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.SEND_FAILED
        )

        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.TerminalEmissionClass.DEGRADED_TERMINAL_OUTPUT,
            queued.terminalEmissionClass
        )
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.TerminalEmissionClass.DEGRADED_TERMINAL_OUTPUT,
            failed.terminalEmissionClass
        )
    }

    @Test
    fun `degraded recovery phase is not downgraded to resumed by continuation hints`() {
        val truth = AndroidRuntimeEmissionTruthSemantics.derive(
            recoveryPhase = AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERY_FAILED,
            isContinuation = true,
            interruptionReason = "reconnect_exhausted",
            isTerminal = true,
            deliveryDisposition = AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.DIRECT_SENT
        )

        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.ExecutionContinuityClass.DEGRADED_CONTINUITY,
            truth.executionContinuityClass
        )
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.TerminalEmissionClass.DEGRADED_TERMINAL_OUTPUT,
            truth.terminalEmissionClass
        )
    }

    @Test
    fun `non terminal emission remains active in progress`() {
        val truth = AndroidRuntimeEmissionTruthSemantics.derive(
            recoveryPhase = AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERING,
            isContinuation = true,
            interruptionReason = "socket_rebind",
            isTerminal = false,
            deliveryDisposition = AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.LOCAL_SIGNAL_EMITTED,
            resultConvergenceDecision =
                AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_PARTIAL_FOR_FLOW
        )

        val payload = truth.toPayloadMap()
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.TerminalEmissionClass.ACTIVE_IN_PROGRESS.wireValue,
            payload[AndroidRuntimeEmissionTruthSemantics.KEY_TERMINAL_EMISSION_CLASS]
        )
        assertEquals(
            AndroidRuntimeEmissionTruthSemantics.ExecutionContinuityClass.RESUMED_EXECUTION.wireValue,
            payload[AndroidRuntimeEmissionTruthSemantics.KEY_EXECUTION_CONTINUITY_CLASS]
        )
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_PARTIAL_FOR_FLOW,
            payload[AndroidRuntimeEmissionTruthSemantics.KEY_RESULT_CONVERGENCE_DECISION]
        )
        assertTrue(payload.containsKey(AndroidRuntimeEmissionTruthSemantics.KEY_RUNTIME_EMISSION_TRUTH_SCHEMA_VERSION))
        assertFalse(payload.isEmpty())
    }
}

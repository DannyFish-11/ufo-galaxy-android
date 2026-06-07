package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.agent.DelegatedRuntimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Pr11BAndroidTakeoverOwnershipTransferClosureTest {

    private fun tracker(): DelegatedExecutionTracker {
        val unit = DelegatedRuntimeUnit(
            unitId = "unit-11b",
            taskId = "task-11b",
            traceId = "trace-11b",
            goal = "resume ownership transfer",
            attachedSessionId = "session-11b"
        )
        return DelegatedExecutionTracker.create(
            DelegatedActivationRecord.create(unit, activatedAtMs = 1_000L)
        )
    }

    @Test
    fun `classify ACK as pending ownership held and partially observed`() {
        val signal = DelegatedExecutionSignal.ack(tracker(), signalId = "sig-ack-11b")
        val semantics = AndroidTakeoverOwnershipTransferContract.classify(signal, takeoverResultUplinkAttempt = 1)
        assertEquals(
            AndroidTakeoverOwnershipTransferContract.TakeoverCompletionKind.PENDING,
            semantics.takeoverCompletionKind
        )
        assertEquals(
            AndroidTakeoverOwnershipTransferContract.OwnershipReturnState.OWNERSHIP_HELD_BY_ANDROID,
            semantics.ownershipReturnState
        )
        assertEquals(
            AndroidTakeoverOwnershipTransferContract.TakeoverOutcomeVisibility.PARTIALLY_OBSERVED,
            semantics.takeoverOutcomeVisibility
        )
        assertEquals(1, semantics.takeoverResultUplinkAttempt)
    }

    @Test
    fun `classify completed RESULT as terminal ownership return signalled`() {
        val signal = DelegatedExecutionSignal.result(
            tracker = tracker(),
            resultKind = DelegatedExecutionSignal.ResultKind.COMPLETED,
            signalId = "sig-result-completed-11b"
        )
        val semantics = AndroidTakeoverOwnershipTransferContract.classify(signal, takeoverResultUplinkAttempt = 1)
        assertEquals(
            AndroidTakeoverOwnershipTransferContract.TakeoverCompletionKind.COMPLETED,
            semantics.takeoverCompletionKind
        )
        assertEquals(
            AndroidTakeoverOwnershipTransferContract.OwnershipReturnState.OWNERSHIP_RETURN_SIGNALLED_TO_V2,
            semantics.ownershipReturnState
        )
        assertEquals(
            AndroidTakeoverOwnershipTransferContract.TakeoverOutcomeVisibility.TERMINAL_OBSERVED,
            semantics.takeoverOutcomeVisibility
        )
    }

    @Test
    fun `classify timeout RESULT as interrupted terminal`() {
        val signal = DelegatedExecutionSignal.timeout(
            tracker = tracker(),
            signalId = "sig-result-timeout-11b"
        )
        val semantics = AndroidTakeoverOwnershipTransferContract.classify(signal, takeoverResultUplinkAttempt = 1)
        assertEquals(
            AndroidTakeoverOwnershipTransferContract.TakeoverCompletionKind.INTERRUPTED,
            semantics.takeoverCompletionKind
        )
        assertEquals(
            AndroidTakeoverOwnershipTransferContract.TakeoverOutcomeVisibility.INTERRUPTED_TERMINAL_OBSERVED,
            semantics.takeoverOutcomeVisibility
        )
    }

    @Test
    fun `classify retried completed RESULT as retried terminal observation`() {
        val signal = DelegatedExecutionSignal.result(
            tracker = tracker(),
            resultKind = DelegatedExecutionSignal.ResultKind.COMPLETED,
            signalId = "sig-result-retry-11b"
        )
        val semantics = AndroidTakeoverOwnershipTransferContract.classify(signal, takeoverResultUplinkAttempt = 3)
        assertEquals(
            AndroidTakeoverOwnershipTransferContract.OwnershipReturnState.OWNERSHIP_RETURN_SIGNALLED_TO_V2_RETRIED,
            semantics.ownershipReturnState
        )
        assertEquals(
            AndroidTakeoverOwnershipTransferContract.TakeoverOutcomeVisibility.RETRIED_TERMINAL_OBSERVED,
            semantics.takeoverOutcomeVisibility
        )
        assertEquals(3, semantics.takeoverResultUplinkAttempt)
    }

    @Test
    fun `classify retried cancelled RESULT as retried interrupted terminal observation`() {
        val signal = DelegatedExecutionSignal.cancelled(
            tracker = tracker(),
            signalId = "sig-result-retry-interrupted-11b"
        )
        val semantics = AndroidTakeoverOwnershipTransferContract.classify(signal, takeoverResultUplinkAttempt = 2)
        assertEquals(
            AndroidTakeoverOwnershipTransferContract.TakeoverCompletionKind.INTERRUPTED,
            semantics.takeoverCompletionKind
        )
        assertEquals(
            AndroidTakeoverOwnershipTransferContract.TakeoverOutcomeVisibility.RETRIED_INTERRUPTED_TERMINAL_OBSERVED,
            semantics.takeoverOutcomeVisibility
        )
    }

    @Test
    fun `toOutboundPayload includes takeover closure semantics`() {
        val signal = DelegatedExecutionSignal.result(
            tracker = tracker(),
            resultKind = DelegatedExecutionSignal.ResultKind.COMPLETED,
            signalId = "sig-payload-11b"
        )
        val semantics = AndroidTakeoverOwnershipTransferContract.classify(signal, takeoverResultUplinkAttempt = 2)
        val payload = signal.toOutboundPayload(
            deviceId = "device-11b",
            takeoverClosureSemantics = semantics
        )

        assertEquals("completed", payload.takeover_completion_kind)
        assertEquals("ownership_return_signalled_to_v2_retried", payload.ownership_return_state)
        assertEquals("retried_terminal_observed", payload.takeover_outcome_visibility)
        assertEquals(2, payload.takeover_result_uplink_attempt)
    }

    @Test
    fun `toOutboundPayload leaves takeover closure fields null when semantics absent`() {
        val payload = DelegatedExecutionSignal.ack(
            tracker = tracker(),
            signalId = "sig-payload-null-11b"
        ).toOutboundPayload(deviceId = "device-11b")

        assertNull(payload.takeover_completion_kind)
        assertNull(payload.ownership_return_state)
        assertNull(payload.takeover_outcome_visibility)
        assertNull(payload.takeover_result_uplink_attempt)
    }

    @Test
    fun `takeover closure payload fields survive gson round trip`() {
        val signal = DelegatedExecutionSignal.timeout(
            tracker = tracker(),
            signalId = "sig-payload-roundtrip-11b"
        )
        val semantics = AndroidTakeoverOwnershipTransferContract.classify(signal, takeoverResultUplinkAttempt = 4)
        val payload = signal.toOutboundPayload(
            deviceId = "device-11b",
            takeoverClosureSemantics = semantics
        )
        val json = Gson().toJsonTree(payload).asJsonObject

        assertEquals("interrupted", json.get("takeover_completion_kind").asString)
        assertEquals("ownership_return_signalled_to_v2_retried", json.get("ownership_return_state").asString)
        assertEquals("retried_interrupted_terminal_observed", json.get("takeover_outcome_visibility").asString)
        assertEquals(4, json.get("takeover_result_uplink_attempt").asInt)
    }
}

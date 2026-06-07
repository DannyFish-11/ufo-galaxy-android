package com.ufo.galaxy.runtime

import com.ufo.galaxy.history.SessionHistorySummary
import com.ufo.galaxy.trace.LocalLoopTrace
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLayerAliasSemanticsTest {

    @Test
    fun `conversation aliases keep local-loop and history session identity unchanged`() {
        val trace = LocalLoopTrace(
            sessionId = "conversation-1",
            originalGoal = "goal"
        )
        val summary = SessionHistorySummary(
            sessionId = "conversation-2",
            originalGoal = "goal",
            startTimeMs = 1L,
            endTimeMs = 2L,
            durationMs = 1L,
            stepCount = 0,
            status = "success",
            stopReason = null,
            error = null,
            planCount = 0,
            actionCount = 0
        )

        assertEquals(trace.sessionId, trace.conversationSessionId)
        assertEquals(summary.sessionId, summary.conversationSessionId)
    }

    @Test
    fun `runtime attachment alias keeps attached runtime identity unchanged`() {
        val session = AttachedRuntimeSession.create(hostId = "host-1", deviceId = "device-1")
        assertEquals(session.sessionId, session.runtimeAttachmentSessionId)
    }

    @Test
    fun `delegation transfer alias keeps delegated signal session context unchanged`() {
        val signal = DelegatedExecutionSignal(
            kind = DelegatedExecutionSignal.Kind.ACK,
            unitId = "u-1",
            taskId = "t-1",
            traceId = "tr-1",
            attachedSessionId = "attached-1",
            handoffContractVersion = 1,
            stepCount = 0,
            activationStatusHint = DelegatedActivationRecord.ActivationStatus.PENDING.wireValue,
            resultKind = null,
            timestampMs = 1L,
            signalId = "sig-1",
            emissionSeq = DelegatedExecutionSignal.EMISSION_SEQ_ACK
        )

        assertEquals(signal.attachedSessionId, signal.delegationTransferSessionId)
    }
}

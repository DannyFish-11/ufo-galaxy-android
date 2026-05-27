package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReconciliationSignalUnifiedLifecycleRecoveryStageTest {

    @Test
    fun `runtime truth snapshot maps recovering phase to recovery replaying lifecycle stage`() {
        val signal = ReconciliationSignal.runtimeTruthSnapshot(
            truth = buildTruth(AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERING.wireValue)
        )

        val lifecycle = signal.payload[ReconciliationSignal.KEY_UNIFIED_ACTION_LIFECYCLE_SURFACE] as Map<*, *>
        assertEquals("recovery_replaying", lifecycle["stage"])
        val recovery = lifecycle["recovery"] as Map<*, *>
        assertEquals(AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERING.wireValue, recovery["phase"])
    }

    @Test
    fun `runtime truth snapshot maps recovered-inflight phase to recovery recovered lifecycle stage`() {
        val signal = ReconciliationSignal.runtimeTruthSnapshot(
            truth = buildTruth(AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERED_INFLIGHT.wireValue)
        )

        val lifecycle = signal.payload[ReconciliationSignal.KEY_UNIFIED_ACTION_LIFECYCLE_SURFACE] as Map<*, *>
        assertEquals("recovery_recovered", lifecycle["stage"])
    }

    @Test
    fun `runtime truth snapshot maps recovery-failed phase to recovery failed lifecycle stage`() {
        val signal = ReconciliationSignal.runtimeTruthSnapshot(
            truth = buildTruth(AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERY_FAILED.wireValue)
        )

        val lifecycle = signal.payload[ReconciliationSignal.KEY_UNIFIED_ACTION_LIFECYCLE_SURFACE] as Map<*, *>
        assertEquals("recovery_failed", lifecycle["stage"])
        val recovery = lifecycle["recovery"] as Map<*, *>
        assertNotNull(recovery["source"])
    }

    private fun buildTruth(inflightContinuityState: String): AndroidParticipantRuntimeTruth =
        AndroidParticipantRuntimeTruth(
            participantId = "participant-1",
            deviceId = "device-1",
            hostId = "host-1",
            deviceRole = "phone",
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
            coordinationRole = ParticipantCoordinationRole.PARTICIPANT,
            sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
            sessionId = "session-1",
            sessionState = AttachedRuntimeSession.State.ATTACHED,
            delegatedExecutionCount = 1,
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            activeTaskId = "task-1",
            activeTaskStatus = ActiveTaskStatus.RUNNING,
            inflightContinuityState = inflightContinuityState,
            inflightContinuitySource = "test_recovery",
            reportedAtMs = System.currentTimeMillis(),
            reconciliationEpoch = 1
        )
}

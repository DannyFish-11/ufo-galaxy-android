package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr12AndroidCompletionClosureUplinkContractTest {

    @Test
    fun `goal result authority completion is explicit and done summary remains projection`() {
        val snapshot = AndroidCompletionClosureUplinkContract.deriveForGoalResult(
            isLifecycleTerminalPhase = true,
            resultSignalClass = AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT,
            acceptanceCandidateClass = AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass
                .ELIGIBLE_FOR_ACCEPTANCE,
            resultReturned = true,
            completionSignaled = true,
            closureReadyForAcceptance = true,
            operatorProjectionClass = AndroidDiagnosticsFailureExplanationUplinkContract
                .OperatorProjectionClass.OPERATOR_VISIBLE_SUMMARY
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.AuthorityRuntimeCompletionSignalClass
                .AUTHORITY_RUNTIME_COMPLETION,
            snapshot.authorityRuntimeCompletionSignalClass
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.ResultCompletionSignalClass.RESULT_COMPLETED,
            snapshot.resultCompletionSignalClass
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.ClosureFinalizationSignalClass
                .SESSION_FINALIZATION_READY,
            snapshot.closureFinalizationSignalClass
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.OperatorDoneProjectionClass
                .OPERATOR_VISIBLE_DONE_SUMMARY,
            snapshot.operatorDoneProjectionClass
        )
    }

    @Test
    fun `goal result with blocked acceptance maps to finalization blocked`() {
        val snapshot = AndroidCompletionClosureUplinkContract.deriveForGoalResult(
            isLifecycleTerminalPhase = true,
            resultSignalClass = AndroidResultUplinkBoundaryContract.ResultSignalClass
                .ACCEPTANCE_CLOSURE_SIGNAL,
            acceptanceCandidateClass = AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass
                .ACCEPTANCE_BLOCKED,
            resultReturned = true,
            completionSignaled = true,
            closureReadyForAcceptance = true,
            operatorProjectionClass = AndroidDiagnosticsFailureExplanationUplinkContract
                .OperatorProjectionClass.POST_RUN_EXPLANATION
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.ClosureFinalizationSignalClass
                .SESSION_FINALIZATION_BLOCKED,
            snapshot.closureFinalizationSignalClass
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.OperatorDoneProjectionClass
                .POST_RUN_COMPLETION_EXPLANATION,
            snapshot.operatorDoneProjectionClass
        )
    }

    @Test
    fun `device state snapshot is not runtime completion and not finalization`() {
        val snapshot = AndroidCompletionClosureUplinkContract.deriveForDeviceStateSnapshot()
        assertEquals(
            AndroidCompletionClosureUplinkContract.AuthorityRuntimeCompletionSignalClass
                .NOT_RUNTIME_COMPLETION,
            snapshot.authorityRuntimeCompletionSignalClass
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.ResultCompletionSignalClass.RESULT_INFORMATIONAL,
            snapshot.resultCompletionSignalClass
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.ClosureFinalizationSignalClass
                .SESSION_FINALIZATION_NOT_APPLICABLE,
            snapshot.closureFinalizationSignalClass
        )
    }

    @Test
    fun `reconciliation terminal signal is runtime completion evidence not authority truth`() {
        val snapshot = AndroidCompletionClosureUplinkContract.deriveForReconciliationSignal(
            isTerminalSignal = true,
            resultReturned = true,
            completionSignaled = true,
            closureReadyForAcceptance = false
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.AuthorityRuntimeCompletionSignalClass
                .RUNTIME_COMPLETION_EVIDENCE,
            snapshot.authorityRuntimeCompletionSignalClass
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.ClosureFinalizationSignalClass
                .SESSION_FINALIZATION_PENDING,
            snapshot.closureFinalizationSignalClass
        )
    }

    @Test
    fun `wire map includes schema and all completion closure keys`() {
        val wire = AndroidCompletionClosureUplinkContract.deriveForDeviceStateSnapshot().toWireMap()
        assertEquals(
            AndroidCompletionClosureUplinkContract.SCHEMA_VERSION,
            wire[AndroidCompletionClosureUplinkContract.KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION]
        )
        assertTrue(
            wire.containsKey(
                AndroidCompletionClosureUplinkContract
                    .KEY_AUTHORITY_RUNTIME_COMPLETION_SIGNAL_CLASS
            )
        )
        assertTrue(
            wire.containsKey(
                AndroidCompletionClosureUplinkContract.KEY_RESULT_COMPLETION_SIGNAL_CLASS
            )
        )
        assertTrue(
            wire.containsKey(
                AndroidCompletionClosureUplinkContract.KEY_CLOSURE_FINALIZATION_SIGNAL_CLASS
            )
        )
        assertTrue(
            wire.containsKey(
                AndroidCompletionClosureUplinkContract.KEY_OPERATOR_DONE_PROJECTION_CLASS
            )
        )
        val boundaryWire = AndroidCompletionClosureUplinkContract
            .deriveV2CanonicalBoundary(localExecutionCompleted = false)
            .toWireMap()
        assertTrue(
            boundaryWire.containsKey(
                AndroidCompletionClosureUplinkContract.KEY_V2_CANONICAL_TRUTH_COMPLETED
            )
        )
        assertTrue(
            boundaryWire.containsKey(
                AndroidCompletionClosureUplinkContract.KEY_V2_MATURE_CLOSURE_ACHIEVED
            )
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.OutwardTruthSurfaceClass
                .ANDROID_ADVISORY_EVIDENCE,
            AndroidCompletionClosureUplinkContract
                .deriveV2CanonicalBoundary(localExecutionCompleted = false)
                .outwardTruthSurfaceClass
        )
    }

    @Test
    fun `local execution completion remains non canonical until V2 confirms`() {
        val boundary = AndroidCompletionClosureUplinkContract.deriveV2CanonicalBoundary(
            localExecutionCompleted = true,
            advisoryEvidenceSent = true
        )
        assertTrue(boundary.localExecutionCompleted)
        assertTrue(boundary.advisoryEvidenceSent)
        assertFalse(boundary.v2UplinkAcknowledged)
        assertFalse(boundary.v2ReconciliationAcknowledged)
        assertFalse(boundary.v2CanonicalTruthCompleted)
        assertFalse(boundary.v2MatureClosureAchieved)
        assertEquals(
            AndroidCompletionClosureUplinkContract.OutwardTruthSurfaceClass
                .ANDROID_ADVISORY_EVIDENCE,
            boundary.outwardTruthSurfaceClass
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non canonical outward class cannot self-claim canonical truth completion`() {
        AndroidCompletionClosureUplinkContract.V2CanonicalBoundarySnapshot(
            localExecutionCompleted = true,
            advisoryEvidenceSent = true,
            v2UplinkAcknowledged = false,
            v2ReconciliationAcknowledged = false,
            v2CanonicalTruthCompleted = true,
            v2MatureClosureAchieved = false,
            outwardTruthSurfaceClass = AndroidCompletionClosureUplinkContract
                .OutwardTruthSurfaceClass.ANDROID_RUNTIME_VISIBLE_STATE
        )
    }

    @Test
    fun `reconciliation task result payload keeps V2 canonical fields false`() {
        val signal = ReconciliationSignal.taskResult("pid-pr12", "task-pr12")
        assertEquals(true, signal.payload[ReconciliationSignal.KEY_LOCAL_EXECUTION_COMPLETED])
        assertEquals(true, signal.payload[ReconciliationSignal.KEY_ADVISORY_EVIDENCE_SENT])
        assertEquals(false, signal.payload[ReconciliationSignal.KEY_V2_UPLINK_ACKNOWLEDGED])
        assertEquals(false, signal.payload[ReconciliationSignal.KEY_V2_RECONCILIATION_ACKNOWLEDGED])
        assertEquals(false, signal.payload[ReconciliationSignal.KEY_V2_CANONICAL_TRUTH_COMPLETED])
        assertEquals(false, signal.payload[ReconciliationSignal.KEY_V2_MATURE_CLOSURE_ACHIEVED])
        assertEquals(
            AndroidCompletionClosureUplinkContract.OutwardTruthSurfaceClass
                .ANDROID_ADVISORY_EVIDENCE.wireValue,
            signal.payload[ReconciliationSignal.KEY_OUTWARD_TRUTH_SURFACE_CLASS]
        )
    }

    @Test
    fun `non terminal reporting payload is explicitly non canonical`() {
        val signal = ReconciliationSignal.taskStatusUpdate(
            participantId = "pid-pr12",
            taskId = "task-pr12",
            progressDetail = "replay_flush_observed"
        )
        assertEquals(false, signal.payload[ReconciliationSignal.KEY_LOCAL_EXECUTION_COMPLETED])
        assertEquals(true, signal.payload[ReconciliationSignal.KEY_ADVISORY_EVIDENCE_SENT])
        assertEquals(false, signal.payload[ReconciliationSignal.KEY_V2_CANONICAL_TRUTH_COMPLETED])
        assertEquals(false, signal.payload[ReconciliationSignal.KEY_V2_MATURE_CLOSURE_ACHIEVED])
        assertEquals(
            AndroidCompletionClosureUplinkContract.OutwardTruthSurfaceClass
                .ANDROID_ADVISORY_EVIDENCE.wireValue,
            signal.payload[ReconciliationSignal.KEY_OUTWARD_TRUTH_SURFACE_CLASS]
        )
    }

    @Test
    fun `runtime truth snapshot is marked as runtime-visible not canonical truth`() {
        val signal = ReconciliationSignal.runtimeTruthSnapshot(
            truth = AndroidParticipantRuntimeTruth(
                participantId = "pid-pr12",
                deviceId = "device-pr12",
                hostId = "host-pr12",
                deviceRole = "phone",
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
                coordinationRole = ParticipantCoordinationRole.PARTICIPANT,
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME.wireValue,
                sessionId = "session-pr12",
                sessionState = AttachedRuntimeSession.State.ATTACHED,
                delegatedExecutionCount = 0,
                healthState = ParticipantHealthState.HEALTHY,
                readinessState = ParticipantReadinessState.READY,
                activeTaskId = null,
                activeTaskStatus = null,
                inflightContinuityState = AndroidContinuityRecoveryStateModel
                    .RecoveryPhase.RESUMED_CLEANLY.wireValue,
                inflightContinuityTaskId = null,
                inflightContinuitySource = "test",
                inflightContinuityObservedAtMs = 0L,
                authoritativeParticipationState = "active",
                authoritativeParticipationTransitionSequence = 1L,
                authoritativeParticipationTransitionTrigger = "test",
                authoritativeParticipationTransitionHistory = listOf("test"),
                reportedAtMs = 0L,
                reconciliationEpoch = 1
            )
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.OutwardTruthSurfaceClass
                .ANDROID_RUNTIME_VISIBLE_STATE.wireValue,
            signal.payload[ReconciliationSignal.KEY_OUTWARD_TRUTH_SURFACE_CLASS]
        )
    }

    @Test
    fun `stabilization baseline registers completion closure uplink contract`() {
        val entry = StabilizationBaseline.forId("android-completion-closure-uplink-contract")
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry?.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry?.extensionGuidance)
    }
}

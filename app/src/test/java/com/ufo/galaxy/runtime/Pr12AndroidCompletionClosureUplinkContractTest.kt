package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
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
    }

    @Test
    fun `stabilization baseline registers completion closure uplink contract`() {
        val entry = StabilizationBaseline.forId("android-completion-closure-uplink-contract")
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry?.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry?.extensionGuidance)
    }
}

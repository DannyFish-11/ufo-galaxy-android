package com.ufo.galaxy.runtime

import com.ufo.galaxy.shared.protocol.MsgType
import org.junit.Assert.assertEquals
import org.junit.Test

class Pr04v2AndroidGovernanceExecutionPolicyIngressContractTest {

    @Test
    fun `readiness governance acceptance strategy and snapshot classify as canonical ingress`() {
        val readiness = AndroidGovernanceExecutionPolicyIngressContract
            .classifyMsgType(MsgType.DEVICE_READINESS_REPORT)
        val governance = AndroidGovernanceExecutionPolicyIngressContract
            .classifyMsgType(MsgType.DEVICE_GOVERNANCE_REPORT)
        val acceptance = AndroidGovernanceExecutionPolicyIngressContract
            .classifyMsgType(MsgType.DEVICE_ACCEPTANCE_REPORT)
        val strategy = AndroidGovernanceExecutionPolicyIngressContract
            .classifyMsgType(MsgType.DEVICE_STRATEGY_REPORT)
        val snapshot = AndroidGovernanceExecutionPolicyIngressContract
            .classifyMsgType(MsgType.DEVICE_STATE_SNAPSHOT)

        assertEquals(
            AndroidGovernanceExecutionPolicyIngressContract.IngressBoundaryClass
                .CANONICAL_GOVERNANCE_EXECUTION_POLICY,
            readiness.boundaryClass
        )
        assertEquals(readiness.boundaryClass, governance.boundaryClass)
        assertEquals(readiness.boundaryClass, acceptance.boundaryClass)
        assertEquals(readiness.boundaryClass, strategy.boundaryClass)
        assertEquals(readiness.boundaryClass, snapshot.boundaryClass)
    }

    @Test
    fun `participant posture and runtime snapshot reconciliation are canonical while task lifecycle is transport`() {
        val participantState = AndroidGovernanceExecutionPolicyIngressContract
            .classifyReconciliation(ReconciliationSignal.Kind.PARTICIPANT_STATE)
        val runtimeTruth = AndroidGovernanceExecutionPolicyIngressContract
            .classifyReconciliation(ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT)
        val taskResult = AndroidGovernanceExecutionPolicyIngressContract
            .classifyReconciliation(ReconciliationSignal.Kind.TASK_RESULT)

        assertEquals(
            AndroidGovernanceExecutionPolicyIngressContract.IngressBoundaryClass
                .CANONICAL_GOVERNANCE_EXECUTION_POLICY,
            participantState.boundaryClass
        )
        assertEquals(participantState.boundaryClass, runtimeTruth.boundaryClass)
        assertEquals(
            AndroidGovernanceExecutionPolicyIngressContract.IngressBoundaryClass
                .GENERIC_TRANSPORT_REPORTING,
            taskResult.boundaryClass
        )
    }

    @Test
    fun `diagnostics and audit are classified as diagnostics audit summary lane`() {
        val diagnostics = AndroidGovernanceExecutionPolicyIngressContract
            .classifyMsgType(MsgType.DIAGNOSTICS_PAYLOAD)
        val audit = AndroidGovernanceExecutionPolicyIngressContract
            .classifyMsgType(MsgType.DEVICE_AUDIT_REPORT)

        assertEquals(
            AndroidGovernanceExecutionPolicyIngressContract.IngressBoundaryClass
                .DIAGNOSTICS_AUDIT_SUMMARY,
            diagnostics.boundaryClass
        )
        assertEquals(diagnostics.boundaryClass, audit.boundaryClass)
    }

    @Test
    fun `result and execution event classify as canonical backend ingress instead of generic transport`() {
        val goalResult = AndroidGovernanceExecutionPolicyIngressContract
            .classifyMsgType(MsgType.GOAL_EXECUTION_RESULT)
        val executionEvent = AndroidGovernanceExecutionPolicyIngressContract
            .classifyMsgType(MsgType.DEVICE_EXECUTION_EVENT)

        assertEquals(
            AndroidGovernanceExecutionPolicyIngressContract.IngressBoundaryClass
                .CANONICAL_GOVERNANCE_EXECUTION_POLICY,
            goalResult.boundaryClass
        )
        assertEquals(goalResult.boundaryClass, executionEvent.boundaryClass)
        assertEquals(
            AndroidGovernanceExecutionPolicyIngressContract.IngressSignalClass.RESULT_TRUTH_ARTIFACT,
            goalResult.signalClass
        )
        assertEquals(
            AndroidGovernanceExecutionPolicyIngressContract.IngressSignalClass.EXECUTION_RUNTIME_EVENT,
            executionEvent.signalClass
        )
    }
}

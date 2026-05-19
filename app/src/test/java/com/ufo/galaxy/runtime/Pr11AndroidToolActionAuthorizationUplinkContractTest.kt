package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.OperatorActionResultPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr11AndroidToolActionAuthorizationUplinkContractTest {

    @Test
    fun `decision rejected reports authority rejected and no execution truth`() {
        val snapshot = AndroidToolActionAuthorizationUplinkContract.derive(
            AndroidToolActionAuthorizationUplinkContract.DerivationInput(
                actionKind = AndroidOperatorActionGovernanceContract.ActionKind.FINALIZE_CLOSURE.wireValue,
                phase = OperatorActionResultPayload.PHASE_DECISION,
                decisionStatus = OperatorActionResultPayload.DECISION_REJECTED,
                executionStatus = OperatorActionResultPayload.EXECUTION_REJECTED,
                error = "operator_action_blocked:missing_task_id",
                details = emptyMap()
            )
        )
        assertEquals(
            AndroidToolActionAuthorizationUplinkContract.RuntimeAuthorityClass.RUNTIME_AUTHORITY_REJECTED,
            snapshot.runtimeAuthorityClass
        )
        assertEquals(
            AndroidToolActionAuthorizationUplinkContract.ActualExecutionSignalClass.NOT_EXECUTED,
            snapshot.actualExecutionSignalClass
        )
        assertEquals(
            AndroidToolActionAuthorizationUplinkContract.ResultReportingSignalClass.DECISION_SIGNAL,
            snapshot.resultReportingSignalClass
        )
    }

    @Test
    fun `executed closure action confirms side effect and tool invocation`() {
        val snapshot = AndroidToolActionAuthorizationUplinkContract.derive(
            AndroidToolActionAuthorizationUplinkContract.DerivationInput(
                actionKind = AndroidOperatorActionGovernanceContract.ActionKind.FINALIZE_CLOSURE.wireValue,
                phase = OperatorActionResultPayload.PHASE_EXECUTION,
                decisionStatus = OperatorActionResultPayload.DECISION_ACCEPTED,
                executionStatus = OperatorActionResultPayload.EXECUTION_EXECUTED,
                error = null,
                details = mapOf("task_id" to "task-1")
            )
        )
        assertEquals(
            AndroidToolActionAuthorizationUplinkContract.ActualExecutionSignalClass.LOCAL_SIDE_EFFECT_COMMITTED,
            snapshot.actualExecutionSignalClass
        )
        assertEquals(
            AndroidToolActionAuthorizationUplinkContract.ToolInvocationSignalClass.TOOL_INVOCATION_CONFIRMED,
            snapshot.toolInvocationSignalClass
        )
        assertEquals(
            AndroidToolActionAuthorizationUplinkContract.ResultReportingSignalClass.EXECUTION_RESULT_SIGNAL,
            snapshot.resultReportingSignalClass
        )
        assertEquals(
            AndroidToolActionAuthorizationUplinkContract.PostActionExplanationClass.FACTUAL_RESULT_SUMMARY,
            snapshot.postActionExplanationClass
        )
    }

    @Test
    fun `execution failed keeps explanation separate from execution truth`() {
        val snapshot = AndroidToolActionAuthorizationUplinkContract.derive(
            AndroidToolActionAuthorizationUplinkContract.DerivationInput(
                actionKind = AndroidOperatorActionGovernanceContract.ActionKind.RETRY_DELEGATED_EXECUTION.wireValue,
                phase = OperatorActionResultPayload.PHASE_EXECUTION,
                decisionStatus = OperatorActionResultPayload.DECISION_ACCEPTED,
                executionStatus = OperatorActionResultPayload.EXECUTION_FAILED,
                error = "operator_action_failed:missing_active_task",
                details = emptyMap()
            )
        )
        assertEquals(
            AndroidToolActionAuthorizationUplinkContract.ActualExecutionSignalClass.EXECUTION_ATTEMPTED_NO_SIDE_EFFECT,
            snapshot.actualExecutionSignalClass
        )
        assertEquals(
            AndroidToolActionAuthorizationUplinkContract.ToolInvocationSignalClass.TOOL_NOT_INVOKED,
            snapshot.toolInvocationSignalClass
        )
        assertEquals(
            AndroidToolActionAuthorizationUplinkContract.PostActionExplanationClass.FAILURE_EXPLANATION,
            snapshot.postActionExplanationClass
        )
    }

    @Test
    fun `read only revalidate action does not claim local side effect`() {
        val snapshot = AndroidToolActionAuthorizationUplinkContract.derive(
            AndroidToolActionAuthorizationUplinkContract.DerivationInput(
                actionKind = AndroidOperatorActionGovernanceContract.ActionKind.REVALIDATE_PARTICIPATION.wireValue,
                phase = OperatorActionResultPayload.PHASE_EXECUTION,
                decisionStatus = OperatorActionResultPayload.DECISION_ACCEPTED,
                executionStatus = OperatorActionResultPayload.EXECUTION_EXECUTED,
                error = null,
                details = emptyMap()
            )
        )
        assertEquals(
            AndroidToolActionAuthorizationUplinkContract.ActualExecutionSignalClass.EXECUTION_ATTEMPTED_NO_SIDE_EFFECT,
            snapshot.actualExecutionSignalClass
        )
        assertEquals(
            AndroidToolActionAuthorizationUplinkContract.ToolInvocationSignalClass.TOOL_NOT_INVOKED,
            snapshot.toolInvocationSignalClass
        )
    }

    @Test
    fun `wire map includes schema and all semantic boundary keys`() {
        val wire = AndroidToolActionAuthorizationUplinkContract.derive(
            AndroidToolActionAuthorizationUplinkContract.DerivationInput(
                actionKind = "unknown",
                phase = OperatorActionResultPayload.PHASE_DECISION,
                decisionStatus = OperatorActionResultPayload.DECISION_REJECTED,
                executionStatus = OperatorActionResultPayload.EXECUTION_REJECTED,
                error = "parse_error",
                details = emptyMap()
            )
        ).toWireMap()
        assertEquals(
            AndroidToolActionAuthorizationUplinkContract.SCHEMA_VERSION,
            wire["tool_action_authorization_schema_version"]
        )
        assertTrue(wire.containsKey("operator_intent_capture_class"))
        assertTrue(wire.containsKey("runtime_authority_class"))
        assertTrue(wire.containsKey("actual_execution_signal_class"))
        assertTrue(wire.containsKey("tool_invocation_signal_class"))
        assertTrue(wire.containsKey("result_reporting_signal_class"))
        assertTrue(wire.containsKey("post_action_explanation_class"))
    }

    @Test
    fun `non empty success details do not escalate to failure explanation`() {
        val snapshot = AndroidToolActionAuthorizationUplinkContract.derive(
            AndroidToolActionAuthorizationUplinkContract.DerivationInput(
                actionKind = AndroidOperatorActionGovernanceContract.ActionKind.RETRY_DELEGATED_EXECUTION.wireValue,
                phase = OperatorActionResultPayload.PHASE_EXECUTION,
                decisionStatus = OperatorActionResultPayload.DECISION_ACCEPTED,
                executionStatus = OperatorActionResultPayload.EXECUTION_EXECUTED,
                error = null,
                details = mapOf("task_id" to "task-2", "status" to "updated")
            )
        )
        assertEquals(
            AndroidToolActionAuthorizationUplinkContract.PostActionExplanationClass.FACTUAL_RESULT_SUMMARY,
            snapshot.postActionExplanationClass
        )
    }

    @Test
    fun `v2 canonical chain path map covers all boundary categories`() {
        val map = AndroidToolActionAuthorizationUplinkContract.V2_CANONICAL_CHAIN_PATH_MAP
        assertEquals(6, map.size)
        map.values.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun `stabilization baseline registers tool action authorization uplink contract`() {
        val entry = StabilizationBaseline.forId("android-tool-action-authorization-uplink-contract")
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry?.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry?.extensionGuidance)
    }
}

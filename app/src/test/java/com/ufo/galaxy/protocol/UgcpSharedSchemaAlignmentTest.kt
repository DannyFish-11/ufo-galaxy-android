package com.ufo.galaxy.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UgcpSharedSchemaAlignmentTest {

    @Test
    fun `runtime ws profile declaration is explicit and stable`() {
        assertEquals("ugcp.runtime_ws_profile.android", UgcpSharedSchemaAlignment.runtimeWsProfileName)
        assertEquals("aip_ws", UgcpSharedSchemaAlignment.runtimeWsProfileTransport)
        assertEquals("incremental_alignment", UgcpSharedSchemaAlignment.runtimeWsProfileStatus)
        assertEquals("ugcp.control_transfer_profile.android", UgcpSharedSchemaAlignment.controlTransferProfileName)
        assertEquals("incremental_alignment", UgcpSharedSchemaAlignment.controlTransferProfileStatus)
        assertEquals("ugcp.coordination_profile.android", UgcpSharedSchemaAlignment.coordinationProfileName)
        assertEquals("incremental_alignment", UgcpSharedSchemaAlignment.coordinationProfileStatus)
    }

    @Test
    fun `identity alignment includes canonical UGCP lineage`() {
        val identities = UgcpSharedSchemaAlignment.identityAlignments.map { it.canonicalIdentity }.toSet()
        assertTrue(identities.contains("TaskId"))
        assertTrue(identities.contains("TraceId"))
        assertTrue(identities.contains("ControlSessionId"))
        assertTrue(identities.contains("RuntimeSessionId"))
        assertTrue(identities.contains("MeshSessionId"))
        assertTrue(identities.contains("ExecutionInstanceId"))
    }

    @Test
    fun `message types map to canonical schema families`() {
        assertEquals(UgcpSchemaFamily.IDENTITY, UgcpSharedSchemaAlignment.familyFor(MsgType.DEVICE_REGISTER))
        assertEquals(UgcpSchemaFamily.CONTROL, UgcpSharedSchemaAlignment.familyFor(MsgType.TAKEOVER_REQUEST))
        assertEquals(UgcpSchemaFamily.RUNTIME, UgcpSharedSchemaAlignment.familyFor(MsgType.DELEGATED_EXECUTION_SIGNAL))
        assertEquals(UgcpSchemaFamily.COORDINATION, UgcpSharedSchemaAlignment.familyFor(MsgType.MESH_RESULT))
        assertEquals(UgcpSchemaFamily.TRUTH, UgcpSharedSchemaAlignment.familyFor(MsgType.GOAL_EXECUTION_RESULT))
    }

    @Test
    fun `alignment contains readiness transfer mesh and terminal terms`() {
        assertTrue(UgcpSharedSchemaAlignment.readinessCapabilityTerms.contains("source_runtime_posture"))
        assertTrue(UgcpSharedSchemaAlignment.readinessCapabilityTerms.contains("model_ready"))
        assertTrue(UgcpSharedSchemaAlignment.transferTerms.contains("takeover_response"))
        assertTrue(UgcpSharedSchemaAlignment.transferLifecycleTerms.contains("transfer_accept"))
        assertTrue(UgcpSharedSchemaAlignment.transferLifecycleTerms.contains("transfer_reject"))
        assertTrue(UgcpSharedSchemaAlignment.transferLifecycleTerms.contains("transfer_cancel"))
        assertTrue(UgcpSharedSchemaAlignment.transferLifecycleTerms.contains("transfer_expire"))
        assertTrue(UgcpSharedSchemaAlignment.transferLifecycleTerms.contains("transfer_adopt"))
        assertTrue(UgcpSharedSchemaAlignment.transferLifecycleTerms.contains("transfer_resume"))
        assertTrue(UgcpSharedSchemaAlignment.meshTerms.contains("mesh_join"))
        assertTrue(UgcpSharedSchemaAlignment.meshParticipantRoleTerms.contains("coordinator"))
        assertTrue(UgcpSharedSchemaAlignment.meshParticipationOutcomeTerms.contains("task_complete"))
        assertTrue(UgcpSharedSchemaAlignment.coordinationReadinessPostureTerms.contains("mesh_join.capabilities"))
        assertTrue(UgcpSharedSchemaAlignment.terminalVocabulary.contains("failed"))
        assertTrue(UgcpSharedSchemaAlignment.terminalVocabulary.contains("partial"))
    }

    @Test
    fun `mesh event mapping aligns Android coordination events with canonical coordination vocabulary`() {
        val mapping = UgcpSharedSchemaAlignment.meshCoordinationEventAlignments.associate {
            it.androidEvent to it.canonicalCoordinationSemantic
        }
        assertEquals("coordination_participant_join", mapping["mesh_join"])
        assertEquals("coordination_participant_leave", mapping["mesh_leave"])
        assertEquals("coordination_outcome_success", mapping["mesh_result.status=success"])
        assertEquals("coordination_outcome_partial", mapping["mesh_result.status=partial"])
        assertEquals("coordination_outcome_error", mapping["mesh_result.status=error"])
    }

    @Test
    fun `transfer event mapping aligns Android events with canonical control transfer vocabulary`() {
        val mapping = UgcpSharedSchemaAlignment.transferEventAlignments.associate {
            it.androidEvent to it.canonicalTransferSemantic
        }
        assertEquals("transfer_accept", mapping["takeover_response.accepted=true"])
        assertEquals("transfer_reject", mapping["takeover_response.accepted=false"])
        assertEquals("transfer_cancel", mapping["delegated_execution_signal.result_kind=cancelled"])
        assertEquals("transfer_expire", mapping["delegated_execution_signal.result_kind=timeout"])
        assertEquals("transfer_reject", mapping["delegated_execution_signal.result_kind=rejected"])
        assertEquals("transfer_adopt", mapping["delegated_handoff_contract.continuation_token"])
        assertEquals("transfer_resume", mapping["delegated_handoff_contract.handoff_reason=continuation"])
    }

    @Test
    fun `runtime ws profile message families cover runtime and transfer scope`() {
        val families = UgcpSharedSchemaAlignment.runtimeWsProfileMessageFamilies
        assertTrue(families.contains(MsgType.DEVICE_REGISTER))
        assertTrue(families.contains(MsgType.CAPABILITY_REPORT))
        assertTrue(families.contains(MsgType.TASK_SUBMIT))
        assertTrue(families.contains(MsgType.TASK_ASSIGN))
        assertTrue(families.contains(MsgType.TASK_RESULT))
        assertTrue(families.contains(MsgType.TAKEOVER_REQUEST))
        assertTrue(families.contains(MsgType.TAKEOVER_RESPONSE))
        assertTrue(families.contains(MsgType.DELEGATED_EXECUTION_SIGNAL))
        assertTrue(families.contains(MsgType.MESH_JOIN))
        assertTrue(families.contains(MsgType.MESH_LEAVE))
        assertTrue(families.contains(MsgType.MESH_RESULT))
    }

    @Test
    fun `session continuity terms include reconnect and attached-session semantics`() {
        val terms = UgcpSharedSchemaAlignment.sessionContinuityTerms
        assertTrue(terms.contains("runtime_session_id"))
        assertTrue(terms.contains("attached_session_id"))
        assertTrue(terms.contains("reconnect_recovery_state: idle|recovering|recovered|failed"))
        assertTrue(terms.contains("attached_session_state: attached|detaching|detached"))
        assertTrue(terms.contains("detach_cause: explicit_detach|disconnect|disable|invalidation"))
    }
}

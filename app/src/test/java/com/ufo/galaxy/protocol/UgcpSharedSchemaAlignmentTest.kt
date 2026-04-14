package com.ufo.galaxy.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("ugcp.truth_event_model.android", UgcpSharedSchemaAlignment.truthEventModelName)
        assertEquals("incremental_alignment", UgcpSharedSchemaAlignment.truthEventModelStatus)
        assertEquals("ugcp.conformance_surface.android", UgcpSharedSchemaAlignment.conformanceSurfaceName)
        assertEquals("incremental_alignment", UgcpSharedSchemaAlignment.conformanceSurfaceStatus)
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
        assertTrue(UgcpSharedSchemaAlignment.coordinationRoleTerms.contains("participant"))
        assertTrue(UgcpSharedSchemaAlignment.coordinationRoleTerms.contains("coordinator"))
        assertTrue(UgcpSharedSchemaAlignment.coordinationReadinessTerms.contains("source_runtime_posture=join_runtime"))
        assertTrue(UgcpSharedSchemaAlignment.coordinationReadinessTerms.contains("model_ready"))
        assertTrue(UgcpSharedSchemaAlignment.coordinationLifecycleTerms.contains("coordination_execution_terminal"))
        assertTrue(UgcpSharedSchemaAlignment.coordinationOutcomeVocabulary.contains("partial"))
        assertTrue(UgcpSharedSchemaAlignment.terminalVocabulary.contains("failed"))
        assertTrue(UgcpSharedSchemaAlignment.terminalVocabulary.contains("partial"))
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
    fun `coordination event mapping aligns mesh lifecycle and outcomes with canonical coordination semantics`() {
        val mapping = UgcpSharedSchemaAlignment.coordinationEventAlignments.associate {
            it.androidEvent to it.canonicalCoordinationSemantic
        }
        assertEquals("coordination_participant_joined", mapping["mesh_join"])
        assertEquals("coordination_participant_left", mapping["mesh_leave"])
        assertEquals("coordination_execution_result_reported", mapping["mesh_result"])
        assertEquals(
            "coordination_execution_terminal",
            mapping["mesh_result.status in {success,partial,error}"]
        )
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

    @Test
    fun `truth event model distinguishes authoritative truth surfaces from observational notifications`() {
        assertTrue(UgcpSharedSchemaAlignment.authoritativeTruthSurfaces.contains("RuntimeController.state"))
        assertTrue(UgcpSharedSchemaAlignment.authoritativeTruthSurfaces.contains("RuntimeController.hostSessionSnapshot"))
        assertTrue(
            UgcpSharedSchemaAlignment.authoritativeTruthSurfaces.contains(
                "task_result|command_result|goal_result|goal_execution_result terminal status"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.observationalNotificationSurfaces.contains(
                "RuntimeController.takeoverFailure"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.observationalNotificationSurfaces.contains(
                "delegated_execution_signal.ack/progress/result"
            )
        )
    }

    @Test
    fun `truth event mapping aligns lifecycle status result and recovery semantics`() {
        val mapping = UgcpSharedSchemaAlignment.truthEventAlignments.associateBy { it.androidSignal }
        assertEquals(
            UgcpTruthEventSemanticClass.AUTHORITATIVE_STATE_TRANSITION,
            mapping["RuntimeController.state transition"]?.semanticClass
        )
        assertEquals(
            "runtime_state_truth_updated",
            mapping["RuntimeController.state transition"]?.canonicalTruthEventSemantic
        )
        assertEquals(
            UgcpTruthEventSemanticClass.AUTHORITATIVE_RESULT_REPORT,
            mapping["task_result|command_result|goal_result|goal_execution_result"]?.semanticClass
        )
        assertEquals(
            UgcpTruthEventSemanticClass.OBSERVATIONAL_EVENT_EMISSION,
            mapping["RuntimeController.takeoverFailure emission"]?.semanticClass
        )
        assertEquals(
            "delegated_execution_lifecycle_notified",
            mapping["delegated_execution_signal.signal_kind=ack|progress|result"]?.canonicalTruthEventSemantic
        )
        assertEquals(
            UgcpTruthEventSemanticClass.AUTHORITATIVE_STATE_TRANSITION,
            mapping["RuntimeController.reconnectRecoveryState transition"]?.semanticClass
        )
    }

    @Test
    fun `conformance tiers distinguish canonical runtime scope from transitional compatibility scope`() {
        assertEquals(
            UgcpProtocolSemanticTier.CANONICAL,
            UgcpSharedSchemaAlignment.protocolTierFor(MsgType.TAKEOVER_REQUEST)
        )
        assertEquals(
            UgcpProtocolSemanticTier.CANONICAL,
            UgcpSharedSchemaAlignment.protocolTierFor(MsgType.MESH_RESULT)
        )
        assertEquals(
            UgcpProtocolSemanticTier.TRANSITIONAL_COMPATIBILITY,
            UgcpSharedSchemaAlignment.protocolTierFor(MsgType.RELAY)
        )
        assertFalse(
            UgcpSharedSchemaAlignment.transitionalCompatibilityMessageFamilies.contains(MsgType.TAKEOVER_RESPONSE)
        )
    }

    @Test
    fun `normalization boundaries freeze legacy alias and lifecycle status canonicalization`() {
        assertEquals("device_register", UgcpSharedSchemaAlignment.normalizeMessageType("registration"))
        assertEquals("task_assign", UgcpSharedSchemaAlignment.normalizeMessageType("task_execute"))
        assertEquals("success", UgcpSharedSchemaAlignment.normalizeLifecycleStatus("completed"))
        assertEquals("error", UgcpSharedSchemaAlignment.normalizeLifecycleStatus("failed"))
        assertEquals("error", UgcpSharedSchemaAlignment.normalizeLifecycleStatus("failure"))
        assertEquals("disabled", UgcpSharedSchemaAlignment.normalizeLifecycleStatus("disabled"))
        assertEquals("disabled", UgcpSharedSchemaAlignment.normalizeLifecycleStatus("no_op"))
        assertEquals("unknown_status", UgcpSharedSchemaAlignment.normalizeLifecycleStatus("unknown_status"))
    }

    @Test
    fun `conformance invariants and retirement foundations are explicit and reviewable`() {
        assertTrue(
            UgcpSharedSchemaAlignment.conformanceInvariants.contains(
                "legacy aliases MUST normalize via MsgType.toV3Type before canonical routing"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.conformanceInvariants.contains(
                "observational lifecycle emissions MUST NOT replace authoritative truth surfaces"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.compatibilityRetirementFoundations.contains(
                "centralized legacy alias map: MsgType.LEGACY_TYPE_MAP"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.compatibilityRetirementFoundations.contains(
                "deprecation execution phases: deprecationExecutionPhases"
            )
        )
    }

    @Test
    fun `message type handling classification distinguishes canonical normalized transitional and reject-candidate paths`() {
        val canonical = UgcpSharedSchemaAlignment.classifyMessageTypeHandling("takeover_request")
        assertEquals(UgcpEnforcementDisposition.CANONICAL_ACCEPT, canonical.disposition)
        assertEquals(UgcpDeprecationStage.ACTIVE_CANONICAL, canonical.deprecationStage)

        val normalized = UgcpSharedSchemaAlignment.classifyMessageTypeHandling("task_execute")
        assertEquals("task_assign", normalized.normalizedInput)
        assertEquals(UgcpEnforcementDisposition.NORMALIZE_AND_ACCEPT, normalized.disposition)
        assertEquals(UgcpDeprecationStage.NORMALIZED_LEGACY_ALIAS, normalized.deprecationStage)

        val transitional = UgcpSharedSchemaAlignment.classifyMessageTypeHandling("relay")
        assertEquals(UgcpEnforcementDisposition.TOLERATE_TRANSITIONAL, transitional.disposition)
        assertEquals(
            UgcpDeprecationStage.TRANSITIONAL_COMPATIBILITY,
            transitional.deprecationStage
        )

        val rejectCandidate = UgcpSharedSchemaAlignment.classifyMessageTypeHandling("totally_unknown_type")
        assertEquals(
            UgcpEnforcementDisposition.FUTURE_REJECT_CANDIDATE,
            rejectCandidate.disposition
        )
        assertEquals(
            UgcpDeprecationStage.DEPRECATION_CANDIDATE,
            rejectCandidate.deprecationStage
        )
    }

    @Test
    fun `lifecycle status handling classification keeps current behavior while exposing future tightening boundaries`() {
        val canonical = UgcpSharedSchemaAlignment.classifyLifecycleStatusHandling("success")
        assertEquals(UgcpEnforcementDisposition.CANONICAL_ACCEPT, canonical.disposition)

        val normalized = UgcpSharedSchemaAlignment.classifyLifecycleStatusHandling("completed")
        assertEquals("success", normalized.normalizedInput)
        assertEquals(UgcpEnforcementDisposition.NORMALIZE_AND_ACCEPT, normalized.disposition)

        val rejectCandidate = UgcpSharedSchemaAlignment.classifyLifecycleStatusHandling("legacy_unknown_status")
        assertEquals(
            UgcpEnforcementDisposition.FUTURE_REJECT_CANDIDATE,
            rejectCandidate.disposition
        )
    }

    @Test
    fun `enforcement hooks and deprecation phases are explicit and reviewable`() {
        assertTrue(
            UgcpSharedSchemaAlignment.enforcementHookSurfaces.contains(
                "runtime_ingress.type_normalization_and_tier_classification"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.enforcementHookSurfaces.contains(
                "truth_event_authoritative_vs_observational_boundary_review"
            )
        )
        assertEquals(
            listOf(
                "phase_1_warn_and_observe",
                "phase_2_normalize_and_report",
                "phase_3_migration_gate_candidate",
                "phase_4_reject_after_explicit_rollout"
            ),
            UgcpSharedSchemaAlignment.deprecationExecutionPhases
        )
    }
}

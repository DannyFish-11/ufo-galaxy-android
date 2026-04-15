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
    fun `canonical concept vocabulary distinguishes participant device runtime host and session boundaries`() {
        val byConcept = UgcpSharedSchemaAlignment.canonicalConceptVocabulary.associateBy { it.concept }
        assertEquals("participant_node_id", byConcept["participant_node"]?.canonicalTerm)
        assertEquals("device_id", byConcept["device"]?.canonicalTerm)
        assertEquals("device_category", byConcept["device_category"]?.canonicalTerm)
        assertEquals("runtime_host_participant", byConcept["runtime_host"]?.canonicalTerm)
        assertEquals("runtime_capability_report", byConcept["capability_reporting"]?.canonicalTerm)
        assertEquals("capability_provider_ref", byConcept["capability_provider"]?.canonicalTerm)
        assertEquals("conversation_session_id", byConcept["conversation_history_session"]?.canonicalTerm)
        assertEquals("attached_runtime_session_id", byConcept["runtime_attachment_session"]?.canonicalTerm)
        assertEquals("transfer_session_context", byConcept["delegation_transfer_session"]?.canonicalTerm)
        assertEquals("source_runtime_posture", byConcept["posture"]?.canonicalTerm)
        assertEquals("coordination_role", byConcept["coordination_role"]?.canonicalTerm)
        assertEquals("canonical_participant_model", byConcept["participant_model"]?.canonicalTerm)
        assertTrue(
            byConcept["participant_model"]?.androidMapping?.contains("CanonicalParticipantModel")
                ?: false
        )
        assertTrue(
            byConcept["capability_provider"]?.boundary?.contains("does not own runtime identity")
                ?: false
        )
        assertTrue(
            byConcept["conversation_history_session"]?.boundary?.contains("not runtime attachment identity")
                ?: false
        )
        assertTrue(
            byConcept["device_category"]?.boundary?.contains("not a default assumption for all devices")
                ?: false
        )
        assertTrue(
            byConcept["runtime_attachment_session"]?.androidMapping?.contains("attached_session_id")
                ?: false
        )
        assertEquals(
            AndroidSessionLayerKind.CONVERSATION_SESSION,
            UgcpSharedSchemaAlignment.sessionLayerForCarrier("LocalLoopTrace.sessionId")
        )
        assertEquals(
            AndroidSessionLayerKind.RUNTIME_ATTACHMENT_SESSION,
            UgcpSharedSchemaAlignment.sessionLayerForCarrier("AttachedRuntimeSession.sessionId")
        )
        assertEquals(
            AndroidSessionLayerKind.DELEGATION_TRANSFER_SESSION,
            UgcpSharedSchemaAlignment.sessionLayerForCarrier("takeover_request.session_id")
        )
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
            UgcpSharedSchemaAlignment.conformanceInvariants.contains(
                "session-layer split is explicit: ConversationSession vs RuntimeAttachmentSession vs DelegationTransferSession"
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

    @Test
    fun `migration readiness surfaces make staged tightening boundaries explicit`() {
        val bySurface = UgcpSharedSchemaAlignment.migrationReadinessSurfaces.associateBy { it.surface }
        assertEquals(
            UgcpMigrationReadinessTier.READY_FOR_STAGED_TIGHTENING,
            bySurface["runtime_ingress.type_normalization_and_tier_classification"]?.readinessTier
        )
        assertEquals(
            UgcpMigrationReadinessTier.REQUIRES_PHASED_TOLERANCE,
            bySurface["transfer.result_kind_lifecycle_status_normalization"]?.readinessTier
        )
        assertEquals(
            UgcpMigrationReadinessTier.REQUIRES_PHASED_TOLERANCE,
            bySurface["coordination.mesh_result_status_normalization"]?.readinessTier
        )
        assertEquals(
            UgcpMigrationReadinessTier.READY_FOR_STAGED_TIGHTENING,
            bySurface["truth_event_authoritative_vs_observational_boundary_review"]?.readinessTier
        )
    }

    @Test
    fun `retirement sequencing guidance maps message-type handling to phased rollout`() {
        val canonical = UgcpSharedSchemaAlignment.retirementSequencingForMessageType("takeover_request")
        assertEquals(UgcpDeprecationStage.ACTIVE_CANONICAL, canonical.deprecationStage)
        assertEquals("phase_1_warn_and_observe", canonical.sequencingPhase)

        val normalized = UgcpSharedSchemaAlignment.retirementSequencingForMessageType("task_execute")
        assertEquals(UgcpDeprecationStage.NORMALIZED_LEGACY_ALIAS, normalized.deprecationStage)
        assertEquals("phase_2_normalize_and_report", normalized.sequencingPhase)

        val transitional = UgcpSharedSchemaAlignment.retirementSequencingForMessageType("relay")
        assertEquals(UgcpDeprecationStage.TRANSITIONAL_COMPATIBILITY, transitional.deprecationStage)
        assertEquals("phase_3_migration_gate_candidate", transitional.sequencingPhase)

        val rejectCandidate =
            UgcpSharedSchemaAlignment.retirementSequencingForMessageType("totally_unknown_type")
        assertEquals(UgcpDeprecationStage.DEPRECATION_CANDIDATE, rejectCandidate.deprecationStage)
        assertEquals("phase_4_reject_after_explicit_rollout", rejectCandidate.sequencingPhase)
    }

    @Test
    fun `runtime to canonical pathway inventory distinguishes canonical transitional and compatibility pathways`() {
        val byPathway = UgcpSharedSchemaAlignment.runtimeToCanonicalPathwayInventory.associateBy { it.pathway }
        assertEquals(
            UgcpRuntimePathwayClass.CANONICAL,
            byPathway["runtime_lifecycle_state_truth"]?.pathwayClass
        )
        assertEquals(
            UgcpRuntimePathwayClass.TRANSITIONAL,
            byPathway["transfer_lifecycle_result_mapping"]?.pathwayClass
        )
        assertEquals(
            UgcpRuntimePathwayClass.COMPATIBILITY_WORKAROUND,
            byPathway["runtime_ingress_legacy_alias_normalization"]?.pathwayClass
        )
        assertTrue(
            UgcpSharedSchemaAlignment.runtimeNormalizationBoundarySurfaces.contains(
                "runtime_ingress.type_normalization_and_tier_classification"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.runtimeNormalizationBoundarySurfaces.contains(
                "truth_event_authoritative_vs_observational_boundary_review"
            )
        )
    }

    @Test
    fun `runtime pathway visibility surfaces expose verification and retirement candidates without behavior changes`() {
        assertTrue(
            UgcpSharedSchemaAlignment.runtimeCanonicalPathways.contains(
                "runtime_posture_capability_signal"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.runtimeTransitionalPathways.contains(
                "coordination_result_mapping"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.runtimeCompatibilityWorkaroundPathways.contains(
                "connectivity_recovery_and_local_fallback_observability"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.runtimeVerificationCandidatePathways.contains(
                "runtime_session_continuity_truth"
            )
        )
        assertFalse(
            UgcpSharedSchemaAlignment.runtimeVerificationCandidatePathways.contains(
                "runtime_ingress_legacy_alias_normalization"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.runtimeNormalizeFirstPathways.contains(
                "runtime_ingress_legacy_alias_normalization"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.runtimeWarnAndDiagnosePathways.contains(
                "transfer_lifecycle_result_mapping"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.runtimeCanonicalPreferredPathways.contains(
                "runtime_session_continuity_truth"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.runtimeRejectReadyCandidatePathways.contains(
                "runtime_lifecycle_state_truth"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.runtimeEarlyAndroidTighteningPathways.contains(
                "runtime_session_continuity_truth"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.runtimeEvidenceGatedPathways.contains(
                "runtime_lifecycle_state_truth"
            )
        )
        assertTrue(
            UgcpSharedSchemaAlignment.runtimeCoordinationGatedPathways.contains(
                "runtime_ingress_legacy_alias_normalization"
            )
        )
    }

    @Test
    fun `runtime to shared contract verification report is explicit and pass-oriented by default`() {
        val checks = UgcpSharedSchemaAlignment.verifyRuntimeToSharedContractConsistency()
        val checkById = checks.associateBy { it.checkId }
        assertTrue(checkById.containsKey("transfer_lifecycle_semantics_coverage"))
        assertTrue(checkById.containsKey("truth_reconnect_recovery_authoritative_alignment"))
        assertTrue(checkById.containsKey("truth_fallback_observational_alignment"))
        assertEquals(
            checks
                .filter { it.status == UgcpRuntimeContractVerificationStatus.REPORT_ONLY_DIVERGENCE }
                .map { it.checkId }
                .toSet(),
            UgcpSharedSchemaAlignment.runtimeContractReportOnlyDivergenceCheckIds(checks)
        )
    }

    @Test
    fun `runtime to shared contract verification surfaces report-only divergence without changing behavior`() {
        val divergentChecks = UgcpSharedSchemaAlignment.verifyRuntimeToSharedContractConsistency(
            pathwayInventory = listOf(
                UgcpRuntimeCanonicalPathwayAudit(
                    pathway = "runtime_lifecycle_state_truth",
                    runtimeSurface = "RuntimeController.state",
                    canonicalSemantic = "runtime_state_truth_updated",
                    pathwayClass = UgcpRuntimePathwayClass.CANONICAL,
                    normalizationBoundary = "runtime_state_projection",
                    fallbackOrWorkaround = null,
                    verificationReadiness = UgcpMigrationReadinessTier.REQUIRES_PHASED_TOLERANCE,
                    strictnessStage = UgcpRuntimeStrictnessStage.NORMALIZE_FIRST,
                    rolloutGate = UgcpRuntimeRolloutGate.ANDROID_EVIDENCE_REQUIRED
                ),
                UgcpRuntimeCanonicalPathwayAudit(
                    pathway = "transfer_lifecycle_result_mapping",
                    runtimeSurface = "delegated_execution_signal.result_kind + takeover_response.accepted",
                    canonicalSemantic = "transfer_accept|reject|cancel|expire",
                    pathwayClass = UgcpRuntimePathwayClass.TRANSITIONAL,
                    normalizationBoundary = "missing.enforcement.boundary",
                    fallbackOrWorkaround = null,
                    verificationReadiness = UgcpMigrationReadinessTier.REQUIRES_PHASED_TOLERANCE,
                    strictnessStage = UgcpRuntimeStrictnessStage.REJECT_READY_CANDIDATE,
                    rolloutGate = UgcpRuntimeRolloutGate.ANDROID_LOCAL_TIGHTENING_READY
                )
            ),
            transferMappings = listOf(
                UgcpTransferEventAlignment(
                    androidEvent = "takeover_response.accepted=true",
                    canonicalTransferSemantic = "transfer_accept"
                )
            ),
            truthMappings = listOf(
                UgcpTruthEventAlignment(
                    androidSignal = "RuntimeController.takeoverFailure emission",
                    canonicalTruthEventSemantic = "transfer_fallback_notified",
                    semanticClass = UgcpTruthEventSemanticClass.AUTHORITATIVE_STATE_TRANSITION
                )
            ),
            authoritativeSurfaces = emptySet(),
            observationalSurfaces = emptySet(),
            enforcementSurfaces = emptySet()
        ).filter { it.status == UgcpRuntimeContractVerificationStatus.REPORT_ONLY_DIVERGENCE }
            .associateBy { it.checkId }

        assertTrue(divergentChecks.containsKey("pathway_canonical_readiness:runtime_lifecycle_state_truth"))
        assertTrue(divergentChecks.containsKey("pathway_compatibility_note:transfer_lifecycle_result_mapping"))
        assertTrue(divergentChecks.containsKey("pathway_normalization_boundary:transfer_lifecycle_result_mapping"))
        assertTrue(divergentChecks.containsKey("pathway_strictness_stage_alignment:runtime_lifecycle_state_truth"))
        assertTrue(divergentChecks.containsKey("pathway_strictness_stage_alignment:transfer_lifecycle_result_mapping"))
        assertTrue(divergentChecks.containsKey("pathway_reject_ready_rollout_gate:transfer_lifecycle_result_mapping"))
        assertTrue(divergentChecks.containsKey("transfer_lifecycle_semantics_coverage"))
        assertTrue(divergentChecks.containsKey("truth_reconnect_recovery_authoritative_alignment"))
        assertTrue(divergentChecks.containsKey("truth_fallback_observational_alignment"))
    }
}

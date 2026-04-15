package com.ufo.galaxy.protocol

/**
 * Lightweight Android-side UGCP shared-schema alignment registry.
 *
 * This is intentionally additive and non-disruptive: it does not alter current AIP wire fields
 * or runtime behavior. It freezes canonical mapping terms used for cross-repo convergence work.
 */
enum class UgcpSchemaFamily {
    IDENTITY,
    CONTROL,
    RUNTIME,
    COORDINATION,
    TRUTH
}

data class UgcpIdentityAlignment(
    val canonicalIdentity: String,
    val androidCarrier: String
)

data class UgcpTransferEventAlignment(
    val androidEvent: String,
    val canonicalTransferSemantic: String
)

data class UgcpCoordinationEventAlignment(
    val androidEvent: String,
    val canonicalCoordinationSemantic: String
)

enum class UgcpTruthEventSemanticClass {
    AUTHORITATIVE_STATE_TRANSITION,
    AUTHORITATIVE_RESULT_REPORT,
    OBSERVATIONAL_EVENT_EMISSION
}

enum class UgcpProtocolSemanticTier {
    CANONICAL,
    TRANSITIONAL_COMPATIBILITY
}

enum class UgcpEnforcementDisposition {
    CANONICAL_ACCEPT,
    NORMALIZE_AND_ACCEPT,
    TOLERATE_TRANSITIONAL,
    FUTURE_REJECT_CANDIDATE
}

enum class UgcpDeprecationStage {
    ACTIVE_CANONICAL,
    NORMALIZED_LEGACY_ALIAS,
    TRANSITIONAL_COMPATIBILITY,
    DEPRECATION_CANDIDATE
}

enum class UgcpMigrationReadinessTier {
    READY_FOR_STAGED_TIGHTENING,
    REQUIRES_PHASED_TOLERANCE
}

data class UgcpMigrationReadinessSurface(
    val surface: String,
    val canonicalScope: String,
    val readinessTier: UgcpMigrationReadinessTier,
    val rationale: String
)

data class UgcpRetirementSequencingGuidance(
    val pathway: String,
    val deprecationStage: UgcpDeprecationStage,
    val sequencingPhase: String,
    val rationale: String
)

data class UgcpTruthEventAlignment(
    val androidSignal: String,
    val canonicalTruthEventSemantic: String,
    val semanticClass: UgcpTruthEventSemanticClass
)

enum class UgcpRuntimePathwayClass {
    CANONICAL,
    TRANSITIONAL,
    COMPATIBILITY_WORKAROUND
}

data class UgcpRuntimeCanonicalPathwayAudit(
    val pathway: String,
    val runtimeSurface: String,
    val canonicalSemantic: String,
    val pathwayClass: UgcpRuntimePathwayClass,
    val normalizationBoundary: String,
    val fallbackOrWorkaround: String?,
    val verificationReadiness: UgcpMigrationReadinessTier
)

enum class UgcpRuntimeContractVerificationStatus {
    PASS,
    REPORT_ONLY_DIVERGENCE
}

data class UgcpRuntimeContractVerificationResult(
    val checkId: String,
    val status: UgcpRuntimeContractVerificationStatus,
    val pathway: String?,
    val expectation: String,
    val details: String
)

/**
 * Additive Android-side UGCP handling decision for protocol/lifecycle inputs.
 *
 * This model is intentionally non-breaking scaffolding: it captures how an input is currently
 * classified (canonical/normalized/transitional/reject-candidate), while preserving existing
 * runtime behavior. It is used to make enforcement/deprecation boundaries explicit and reviewable.
 */
data class UgcpInputHandlingDecision(
    val rawInput: String,
    val normalizedInput: String,
    val protocolTier: UgcpProtocolSemanticTier,
    val disposition: UgcpEnforcementDisposition,
    val deprecationStage: UgcpDeprecationStage,
    val rationale: String
)

object UgcpSharedSchemaAlignment {
    private val requiredTransferLifecycleVerificationSemantics: Set<String> = setOf(
        "transfer_accept",
        "transfer_reject",
        "transfer_cancel",
        "transfer_expire"
    )

    private val canonicalLifecycleStatusVocabulary: Set<String> = setOf(
        "success",
        "error",
        "cancelled",
        "timeout",
        "rejected",
        "partial",
        "disabled"
    )

    const val runtimeWsProfileName: String = "ugcp.runtime_ws_profile.android"

    const val runtimeWsProfileTransport: String = "aip_ws"

    const val runtimeWsProfileStatus: String = "incremental_alignment"

    const val controlTransferProfileName: String = "ugcp.control_transfer_profile.android"

    const val controlTransferProfileStatus: String = "incremental_alignment"

    const val coordinationProfileName: String = "ugcp.coordination_profile.android"

    const val coordinationProfileStatus: String = "incremental_alignment"

    const val truthEventModelName: String = "ugcp.truth_event_model.android"

    const val truthEventModelStatus: String = "incremental_alignment"

    const val conformanceSurfaceName: String = "ugcp.conformance_surface.android"

    const val conformanceSurfaceStatus: String = "incremental_alignment"

    val identityAlignments: List<UgcpIdentityAlignment> = listOf(
        UgcpIdentityAlignment("TaskId", "task_id"),
        UgcpIdentityAlignment("TraceId", "trace_id"),
        UgcpIdentityAlignment("ControlSessionId", "session_id"),
        UgcpIdentityAlignment("RuntimeSessionId", "runtime_session_id"),
        UgcpIdentityAlignment("MeshSessionId", "mesh_id"),
        UgcpIdentityAlignment("NodeId(source)", "device_id"),
        UgcpIdentityAlignment("NodeId(target)", "target_device_id / target_node"),
        UgcpIdentityAlignment("ExecutionInstanceId", "signal_id or idempotency_key")
    )

    val messageFamilyAlignments: Map<MsgType, UgcpSchemaFamily> = mapOf(
        MsgType.DEVICE_REGISTER to UgcpSchemaFamily.IDENTITY,
        MsgType.HEARTBEAT to UgcpSchemaFamily.RUNTIME,
        MsgType.HEARTBEAT_ACK to UgcpSchemaFamily.RUNTIME,
        MsgType.CAPABILITY_REPORT to UgcpSchemaFamily.RUNTIME,
        MsgType.TASK_SUBMIT to UgcpSchemaFamily.CONTROL,
        MsgType.TASK_ASSIGN to UgcpSchemaFamily.CONTROL,
        MsgType.TAKEOVER_REQUEST to UgcpSchemaFamily.CONTROL,
        MsgType.TAKEOVER_RESPONSE to UgcpSchemaFamily.CONTROL,
        MsgType.DELEGATED_EXECUTION_SIGNAL to UgcpSchemaFamily.RUNTIME,
        MsgType.MESH_JOIN to UgcpSchemaFamily.COORDINATION,
        MsgType.MESH_LEAVE to UgcpSchemaFamily.COORDINATION,
        MsgType.MESH_RESULT to UgcpSchemaFamily.COORDINATION,
        MsgType.TASK_RESULT to UgcpSchemaFamily.TRUTH,
        MsgType.COMMAND_RESULT to UgcpSchemaFamily.TRUTH,
        MsgType.GOAL_RESULT to UgcpSchemaFamily.TRUTH,
        MsgType.GOAL_EXECUTION_RESULT to UgcpSchemaFamily.TRUTH
    )

    val runtimeWsProfileMessageFamilies: Set<MsgType> = setOf(
        MsgType.DEVICE_REGISTER,
        MsgType.CAPABILITY_REPORT,
        MsgType.HEARTBEAT,
        MsgType.HEARTBEAT_ACK,
        MsgType.TASK_SUBMIT,
        MsgType.TASK_ASSIGN,
        MsgType.TASK_RESULT,
        MsgType.COMMAND_RESULT,
        MsgType.GOAL_RESULT,
        MsgType.GOAL_EXECUTION_RESULT,
        MsgType.TAKEOVER_REQUEST,
        MsgType.TAKEOVER_RESPONSE,
        MsgType.DELEGATED_EXECUTION_SIGNAL,
        MsgType.MESH_JOIN,
        MsgType.MESH_LEAVE,
        MsgType.MESH_RESULT
    )

    val canonicalRuntimeMessageFamilies: Set<MsgType> = runtimeWsProfileMessageFamilies

    val transitionalCompatibilityMessageFamilies: Set<MsgType> =
        MsgType.ADVANCED_TYPES -
            setOf(
                MsgType.TAKEOVER_REQUEST,
                MsgType.TAKEOVER_RESPONSE
            )

    val compatibilityAliasNormalizations: Map<String, String> = MsgType.LEGACY_TYPE_MAP

    val lifecycleStatusNormalizations: Map<String, String> = mapOf(
        "completed" to "success",
        "failed" to "error",
        "failure" to "error",
        "cancelled" to "cancelled",
        "timeout" to "timeout",
        "rejected" to "rejected",
        "success" to "success",
        "error" to "error",
        "partial" to "partial",
        "disabled" to "disabled",
        "no_op" to "disabled"
    )

    val conformanceInvariants: Set<String> = setOf(
        "legacy aliases MUST normalize via MsgType.toV3Type before canonical routing",
        "RuntimeController.hostSessionSnapshot and RuntimeController.targetReadinessProjection remain canonical runtime truth projections",
        "takeover/delegated/mesh canonical semantics remain runtime-profile first-class and are not classified as transitional compatibility-only",
        "observational lifecycle emissions MUST NOT replace authoritative truth surfaces",
        "compatibility pathways remain additive and bounded until explicit retirement"
    )

    val compatibilityRetirementFoundations: Set<String> = setOf(
        "centralized legacy alias map: MsgType.LEGACY_TYPE_MAP",
        "explicit transitional message-family set: transitionalCompatibilityMessageFamilies",
        "explicit lifecycle/status normalization map: lifecycleStatusNormalizations",
        "tier classification boundary: protocolTierFor(MsgType)",
        "input handling boundary: classifyMessageTypeHandling(rawType)",
        "lifecycle handling boundary: classifyLifecycleStatusHandling(rawStatus)",
        "deprecation execution phases: deprecationExecutionPhases"
    )

    val runtimeToCanonicalPathwayInventory: List<UgcpRuntimeCanonicalPathwayAudit> = listOf(
        UgcpRuntimeCanonicalPathwayAudit(
            pathway = "runtime_lifecycle_state_truth",
            runtimeSurface = "RuntimeController.state",
            canonicalSemantic = "runtime_state_truth_updated",
            pathwayClass = UgcpRuntimePathwayClass.CANONICAL,
            normalizationBoundary = "runtime_state_projection",
            fallbackOrWorkaround = null,
            verificationReadiness = UgcpMigrationReadinessTier.READY_FOR_STAGED_TIGHTENING
        ),
        UgcpRuntimeCanonicalPathwayAudit(
            pathway = "runtime_session_continuity_truth",
            runtimeSurface = "RuntimeController.hostSessionSnapshot",
            canonicalSemantic = "attached_runtime_session_truth_updated",
            pathwayClass = UgcpRuntimePathwayClass.CANONICAL,
            normalizationBoundary = "host_session_projection",
            fallbackOrWorkaround = null,
            verificationReadiness = UgcpMigrationReadinessTier.READY_FOR_STAGED_TIGHTENING
        ),
        UgcpRuntimeCanonicalPathwayAudit(
            pathway = "runtime_readiness_selection_truth",
            runtimeSurface = "RuntimeController.targetReadinessProjection",
            canonicalSemantic = "delegated_target_selection_truth_updated",
            pathwayClass = UgcpRuntimePathwayClass.CANONICAL,
            normalizationBoundary = "target_readiness_projection",
            fallbackOrWorkaround = null,
            verificationReadiness = UgcpMigrationReadinessTier.READY_FOR_STAGED_TIGHTENING
        ),
        UgcpRuntimeCanonicalPathwayAudit(
            pathway = "runtime_posture_capability_signal",
            runtimeSurface = "AipMessage.source_runtime_posture + capability_report readiness flags",
            canonicalSemantic = "source_posture_and_runtime_readiness_capability",
            pathwayClass = UgcpRuntimePathwayClass.CANONICAL,
            normalizationBoundary = "capability_and_handshake_reporting",
            fallbackOrWorkaround = null,
            verificationReadiness = UgcpMigrationReadinessTier.READY_FOR_STAGED_TIGHTENING
        ),
        UgcpRuntimeCanonicalPathwayAudit(
            pathway = "transfer_lifecycle_result_mapping",
            runtimeSurface = "delegated_execution_signal.result_kind + takeover_response.accepted",
            canonicalSemantic = "transfer_accept|reject|cancel|expire",
            pathwayClass = UgcpRuntimePathwayClass.TRANSITIONAL,
            normalizationBoundary = "transfer.result_kind_lifecycle_status_normalization",
            fallbackOrWorkaround = "delegated result variants are normalized for canonical transfer lifecycle",
            verificationReadiness = UgcpMigrationReadinessTier.REQUIRES_PHASED_TOLERANCE
        ),
        UgcpRuntimeCanonicalPathwayAudit(
            pathway = "coordination_result_mapping",
            runtimeSurface = "mesh_result.status",
            canonicalSemantic = "coordination_execution_terminal_reported",
            pathwayClass = UgcpRuntimePathwayClass.TRANSITIONAL,
            normalizationBoundary = "coordination.mesh_result_status_normalization",
            fallbackOrWorkaround = "mesh status terms remain tolerance-first until phased retirement",
            verificationReadiness = UgcpMigrationReadinessTier.REQUIRES_PHASED_TOLERANCE
        ),
        UgcpRuntimeCanonicalPathwayAudit(
            pathway = "runtime_ingress_legacy_alias_normalization",
            runtimeSurface = "MsgType.toV3Type + classifyMessageTypeHandling(rawType)",
            canonicalSemantic = "canonical_message_type_routing",
            pathwayClass = UgcpRuntimePathwayClass.COMPATIBILITY_WORKAROUND,
            normalizationBoundary = "runtime_ingress.type_normalization_and_tier_classification",
            fallbackOrWorkaround = "legacy message aliases tolerated via compatibilityAliasNormalizations",
            verificationReadiness = UgcpMigrationReadinessTier.REQUIRES_PHASED_TOLERANCE
        ),
        UgcpRuntimeCanonicalPathwayAudit(
            pathway = "runtime_lifecycle_legacy_status_normalization",
            runtimeSurface = "classifyLifecycleStatusHandling(rawStatus)",
            canonicalSemantic = "canonical_lifecycle_status_handling",
            pathwayClass = UgcpRuntimePathwayClass.COMPATIBILITY_WORKAROUND,
            normalizationBoundary = "transfer.result_kind_lifecycle_status_normalization",
            fallbackOrWorkaround = "legacy lifecycle/status terms tolerated via lifecycleStatusNormalizations",
            verificationReadiness = UgcpMigrationReadinessTier.REQUIRES_PHASED_TOLERANCE
        ),
        UgcpRuntimeCanonicalPathwayAudit(
            pathway = "connectivity_recovery_and_local_fallback_observability",
            runtimeSurface = "RuntimeController.reconnectRecoveryState + RuntimeController.takeoverFailure",
            canonicalSemantic = "runtime_reconnect_recovery_truth_updated + transfer_fallback_notified",
            pathwayClass = UgcpRuntimePathwayClass.COMPATIBILITY_WORKAROUND,
            normalizationBoundary = "truth_event_authoritative_vs_observational_boundary_review",
            fallbackOrWorkaround = "reconnect and fallback paths are retained for runtime resilience while canonical truth surfaces remain authoritative",
            verificationReadiness = UgcpMigrationReadinessTier.REQUIRES_PHASED_TOLERANCE
        )
    )

    val runtimeCanonicalPathways: Set<String> =
        runtimeToCanonicalPathwayInventory
            .filter { it.pathwayClass == UgcpRuntimePathwayClass.CANONICAL }
            .map { it.pathway }
            .toSet()

    val runtimeTransitionalPathways: Set<String> =
        runtimeToCanonicalPathwayInventory
            .filter { it.pathwayClass == UgcpRuntimePathwayClass.TRANSITIONAL }
            .map { it.pathway }
            .toSet()

    val runtimeCompatibilityWorkaroundPathways: Set<String> =
        runtimeToCanonicalPathwayInventory
            .filter { it.pathwayClass == UgcpRuntimePathwayClass.COMPATIBILITY_WORKAROUND }
            .map { it.pathway }
            .toSet()

    val runtimeNormalizationBoundarySurfaces: Set<String> =
        runtimeToCanonicalPathwayInventory
            .map { it.normalizationBoundary }
            .toSet()

    val runtimeVerificationCandidatePathways: Set<String> =
        runtimeToCanonicalPathwayInventory
            .filter { it.verificationReadiness == UgcpMigrationReadinessTier.READY_FOR_STAGED_TIGHTENING }
            .map { it.pathway }
            .toSet()

    val deprecationExecutionPhases: List<String> = listOf(
        "phase_1_warn_and_observe",
        "phase_2_normalize_and_report",
        "phase_3_migration_gate_candidate",
        "phase_4_reject_after_explicit_rollout"
    )

    val enforcementHookSurfaces: Set<String> = setOf(
        "runtime_ingress.type_normalization_and_tier_classification",
        "transfer.result_kind_lifecycle_status_normalization",
        "coordination.mesh_result_status_normalization",
        "truth_event_authoritative_vs_observational_boundary_review"
    )

    val migrationReadinessSurfaces: List<UgcpMigrationReadinessSurface> = listOf(
        UgcpMigrationReadinessSurface(
            surface = "runtime_ingress.type_normalization_and_tier_classification",
            canonicalScope = "runtime_message_type_handling",
            readinessTier = UgcpMigrationReadinessTier.READY_FOR_STAGED_TIGHTENING,
            rationale = "canonical_and_normalized_paths_are_explicitly_classified"
        ),
        UgcpMigrationReadinessSurface(
            surface = "transfer.result_kind_lifecycle_status_normalization",
            canonicalScope = "transfer_lifecycle_status_handling",
            readinessTier = UgcpMigrationReadinessTier.REQUIRES_PHASED_TOLERANCE,
            rationale = "legacy_and_runtime_variant_status_terms_remain_tolerated_via_normalization"
        ),
        UgcpMigrationReadinessSurface(
            surface = "coordination.mesh_result_status_normalization",
            canonicalScope = "coordination_result_status_handling",
            readinessTier = UgcpMigrationReadinessTier.REQUIRES_PHASED_TOLERANCE,
            rationale = "mesh_result_compatibility_terms_require_gradual_retirement"
        ),
        UgcpMigrationReadinessSurface(
            surface = "truth_event_authoritative_vs_observational_boundary_review",
            canonicalScope = "truth_event_surface_boundary",
            readinessTier = UgcpMigrationReadinessTier.READY_FOR_STAGED_TIGHTENING,
            rationale = "authoritative_vs_observational_boundaries_are_explicit_and_reviewable"
        )
    )

    val sessionContinuityTerms: Set<String> = setOf(
        "session_id (control_session_id alias)",
        "runtime_session_id",
        "attached_session_id",
        "mesh_id (mesh_session_id alias)",
        "reconnect_recovery_state: idle|recovering|recovered|failed",
        "attached_session_state: attached|detaching|detached",
        "detach_cause: explicit_detach|disconnect|disable|invalidation"
    )

    val readinessCapabilityTerms: Set<String> = setOf(
        "source_runtime_posture",
        "model_ready",
        "accessibility_ready",
        "overlay_ready",
        "degraded_mode"
    )

    val transferTerms: Set<String> = setOf(
        "takeover_request",
        "takeover_response",
        "delegated_execution_signal"
    )

    val transferLifecycleTerms: Set<String> = setOf(
        "transfer_accept",
        "transfer_reject",
        "transfer_cancel",
        "transfer_expire",
        "transfer_adopt",
        "transfer_resume"
    )

    val transferEventAlignments: List<UgcpTransferEventAlignment> = listOf(
        UgcpTransferEventAlignment(
            androidEvent = "takeover_response.accepted=true",
            canonicalTransferSemantic = "transfer_accept"
        ),
        UgcpTransferEventAlignment(
            androidEvent = "takeover_response.accepted=false",
            canonicalTransferSemantic = "transfer_reject"
        ),
        UgcpTransferEventAlignment(
            androidEvent = "delegated_execution_signal.result_kind=cancelled",
            canonicalTransferSemantic = "transfer_cancel"
        ),
        UgcpTransferEventAlignment(
            androidEvent = "delegated_execution_signal.result_kind=timeout",
            canonicalTransferSemantic = "transfer_expire"
        ),
        UgcpTransferEventAlignment(
            androidEvent = "delegated_execution_signal.result_kind=rejected",
            canonicalTransferSemantic = "transfer_reject"
        ),
        UgcpTransferEventAlignment(
            androidEvent = "delegated_handoff_contract.continuation_token",
            canonicalTransferSemantic = "transfer_adopt"
        ),
        UgcpTransferEventAlignment(
            androidEvent = "delegated_handoff_contract.handoff_reason=continuation",
            canonicalTransferSemantic = "transfer_resume"
        )
    )

    val meshTerms: Set<String> = setOf(
        "mesh_join",
        "mesh_leave",
        "mesh_result"
    )

    val coordinationRoleTerms: Set<String> = setOf(
        "participant",
        "coordinator"
    )

    val coordinationReadinessTerms: Set<String> = setOf(
        "source_runtime_posture=join_runtime",
        "source_runtime_posture=control_only",
        "model_ready",
        "accessibility_ready",
        "overlay_ready",
        "degraded_mode"
    )

    val coordinationLifecycleTerms: Set<String> = setOf(
        "coordination_participant_joined",
        "coordination_participant_left",
        "coordination_execution_result_reported",
        "coordination_execution_terminal"
    )

    val coordinationEventAlignments: List<UgcpCoordinationEventAlignment> = listOf(
        UgcpCoordinationEventAlignment(
            androidEvent = "mesh_join",
            canonicalCoordinationSemantic = "coordination_participant_joined"
        ),
        UgcpCoordinationEventAlignment(
            androidEvent = "mesh_leave",
            canonicalCoordinationSemantic = "coordination_participant_left"
        ),
        UgcpCoordinationEventAlignment(
            androidEvent = "mesh_result",
            canonicalCoordinationSemantic = "coordination_execution_result_reported"
        ),
        UgcpCoordinationEventAlignment(
            androidEvent = "mesh_result.status in {success,partial,error}",
            canonicalCoordinationSemantic = "coordination_execution_terminal"
        )
    )

    val terminalVocabulary: Set<String> = setOf(
        "success",
        "error",
        "cancelled",
        "completed",
        "failed",
        "timeout",
        "rejected",
        "partial"
    )

    val coordinationOutcomeVocabulary: Set<String> = terminalVocabulary.intersect(
        setOf("success", "partial", "error")
    )

    val authoritativeTruthSurfaces: Set<String> = setOf(
        "RuntimeController.state",
        "RuntimeController.hostSessionSnapshot",
        "RuntimeController.targetReadinessProjection",
        "RuntimeController.reconnectRecoveryState",
        "task_result|command_result|goal_result|goal_execution_result terminal status"
    )

    val observationalNotificationSurfaces: Set<String> = setOf(
        "RuntimeController.takeoverFailure",
        "delegated_execution_signal.ack/progress/result",
        "mesh_join|mesh_leave informational participation emission"
    )

    val truthEventAlignments: List<UgcpTruthEventAlignment> = listOf(
        UgcpTruthEventAlignment(
            androidSignal = "RuntimeController.state transition",
            canonicalTruthEventSemantic = "runtime_state_truth_updated",
            semanticClass = UgcpTruthEventSemanticClass.AUTHORITATIVE_STATE_TRANSITION
        ),
        UgcpTruthEventAlignment(
            androidSignal = "RuntimeController.hostSessionSnapshot update",
            canonicalTruthEventSemantic = "attached_runtime_session_truth_updated",
            semanticClass = UgcpTruthEventSemanticClass.AUTHORITATIVE_STATE_TRANSITION
        ),
        UgcpTruthEventAlignment(
            androidSignal = "RuntimeController.targetReadinessProjection update",
            canonicalTruthEventSemantic = "delegated_target_selection_truth_updated",
            semanticClass = UgcpTruthEventSemanticClass.AUTHORITATIVE_STATE_TRANSITION
        ),
        UgcpTruthEventAlignment(
            androidSignal = "RuntimeController.reconnectRecoveryState transition",
            canonicalTruthEventSemantic = "runtime_reconnect_recovery_truth_updated",
            semanticClass = UgcpTruthEventSemanticClass.AUTHORITATIVE_STATE_TRANSITION
        ),
        UgcpTruthEventAlignment(
            androidSignal = "task_result|command_result|goal_result|goal_execution_result",
            canonicalTruthEventSemantic = "execution_terminal_truth_reported",
            semanticClass = UgcpTruthEventSemanticClass.AUTHORITATIVE_RESULT_REPORT
        ),
        UgcpTruthEventAlignment(
            androidSignal = "RuntimeController.takeoverFailure emission",
            canonicalTruthEventSemantic = "transfer_fallback_notified",
            semanticClass = UgcpTruthEventSemanticClass.OBSERVATIONAL_EVENT_EMISSION
        ),
        UgcpTruthEventAlignment(
            androidSignal = "delegated_execution_signal.signal_kind=ack|progress|result",
            canonicalTruthEventSemantic = "delegated_execution_lifecycle_notified",
            semanticClass = UgcpTruthEventSemanticClass.OBSERVATIONAL_EVENT_EMISSION
        ),
        UgcpTruthEventAlignment(
            androidSignal = "mesh_result.status in {success,partial,error}",
            canonicalTruthEventSemantic = "coordination_execution_terminal_reported",
            semanticClass = UgcpTruthEventSemanticClass.AUTHORITATIVE_RESULT_REPORT
        )
    )

    fun verifyRuntimeToSharedContractConsistency(
        pathwayInventory: List<UgcpRuntimeCanonicalPathwayAudit> = runtimeToCanonicalPathwayInventory,
        transferMappings: List<UgcpTransferEventAlignment> = transferEventAlignments,
        truthMappings: List<UgcpTruthEventAlignment> = truthEventAlignments,
        authoritativeSurfaces: Set<String> = authoritativeTruthSurfaces,
        observationalSurfaces: Set<String> = observationalNotificationSurfaces,
        enforcementSurfaces: Set<String> = enforcementHookSurfaces
    ): List<UgcpRuntimeContractVerificationResult> {
        val checks = mutableListOf<UgcpRuntimeContractVerificationResult>()
        pathwayInventory.forEach { pathway ->
            val isNonCanonicalPathway =
                pathway.pathwayClass != UgcpRuntimePathwayClass.CANONICAL
            val canonicalReadinessViolation =
                pathway.pathwayClass == UgcpRuntimePathwayClass.CANONICAL &&
                    pathway.verificationReadiness != UgcpMigrationReadinessTier.READY_FOR_STAGED_TIGHTENING
            checks += UgcpRuntimeContractVerificationResult(
                checkId = "pathway_canonical_readiness:${pathway.pathway}",
                status = if (canonicalReadinessViolation) {
                    UgcpRuntimeContractVerificationStatus.REPORT_ONLY_DIVERGENCE
                } else {
                    UgcpRuntimeContractVerificationStatus.PASS
                },
                pathway = pathway.pathway,
                expectation = "canonical_pathways_should_be_ready_for_staged_tightening",
                details = "pathway_class=${pathway.pathwayClass} readiness=${pathway.verificationReadiness}"
            )

            val missingCompatibilityNote =
                isNonCanonicalPathway && pathway.fallbackOrWorkaround.isNullOrBlank()
            checks += UgcpRuntimeContractVerificationResult(
                checkId = "pathway_compatibility_note:${pathway.pathway}",
                status = if (missingCompatibilityNote) {
                    UgcpRuntimeContractVerificationStatus.REPORT_ONLY_DIVERGENCE
                } else {
                    UgcpRuntimeContractVerificationStatus.PASS
                },
                pathway = pathway.pathway,
                expectation = "transitional_or_workaround_pathways_should_carry_reportable_compatibility_notes",
                details = "pathway_class=${pathway.pathwayClass} fallback_or_workaround=${pathway.fallbackOrWorkaround ?: "none"}"
            )

            val missingEnforcementBoundary =
                isNonCanonicalPathway &&
                    pathway.normalizationBoundary !in enforcementSurfaces
            checks += UgcpRuntimeContractVerificationResult(
                checkId = "pathway_normalization_boundary:${pathway.pathway}",
                status = if (missingEnforcementBoundary) {
                    UgcpRuntimeContractVerificationStatus.REPORT_ONLY_DIVERGENCE
                } else {
                    UgcpRuntimeContractVerificationStatus.PASS
                },
                pathway = pathway.pathway,
                expectation = "transitional_or_workaround_pathways_should_map_to_known_enforcement_boundaries",
                details = "normalization_boundary=${pathway.normalizationBoundary}"
            )
        }

        val mappedTransferSemantics = mutableSetOf<String>()
        transferMappings.forEach { mapping ->
            mappedTransferSemantics += mapping.canonicalTransferSemantic
        }
        val missingTransferSemantics =
            requiredTransferLifecycleVerificationSemantics - mappedTransferSemantics
        checks += UgcpRuntimeContractVerificationResult(
            checkId = "transfer_lifecycle_semantics_coverage",
            status = if (missingTransferSemantics.isEmpty()) {
                UgcpRuntimeContractVerificationStatus.PASS
            } else {
                UgcpRuntimeContractVerificationStatus.REPORT_ONLY_DIVERGENCE
            },
            pathway = "transfer_lifecycle_result_mapping",
            expectation = "transfer_lifecycle_mapping_should_cover_all_required_semantics",
            details = if (missingTransferSemantics.isEmpty()) {
                "all_required_transfer_lifecycle_semantics_are_mapped"
            } else {
                "missing_transfer_semantics=${missingTransferSemantics.sorted().joinToString(", ")}"
            }
        )

        val reconnectTruthMapping = truthMappings.find {
            it.androidSignal == "RuntimeController.reconnectRecoveryState transition"
        }
        val reconnectAuthoritativeSurfacePresent =
            "RuntimeController.reconnectRecoveryState" in authoritativeSurfaces
        checks += UgcpRuntimeContractVerificationResult(
            checkId = "truth_reconnect_recovery_authoritative_alignment",
            status = if (
                reconnectTruthMapping?.semanticClass ==
                    UgcpTruthEventSemanticClass.AUTHORITATIVE_STATE_TRANSITION &&
                    reconnectAuthoritativeSurfacePresent
            ) {
                UgcpRuntimeContractVerificationStatus.PASS
            } else {
                UgcpRuntimeContractVerificationStatus.REPORT_ONLY_DIVERGENCE
            },
            pathway = "connectivity_recovery_and_local_fallback_observability",
            expectation = "reconnect_recovery_truth_should_remain_authoritative_and_surface_bound",
            details = "semantic_class=${reconnectTruthMapping?.semanticClass ?: "missing"} " +
                "authoritative_surface_present=$reconnectAuthoritativeSurfacePresent"
        )

        val fallbackTruthMapping = truthMappings.find {
            it.androidSignal == "RuntimeController.takeoverFailure emission"
        }
        val fallbackObservationalSurfacePresent =
            "RuntimeController.takeoverFailure" in observationalSurfaces
        checks += UgcpRuntimeContractVerificationResult(
            checkId = "truth_fallback_observational_alignment",
            status = if (
                fallbackTruthMapping?.semanticClass ==
                    UgcpTruthEventSemanticClass.OBSERVATIONAL_EVENT_EMISSION &&
                    fallbackObservationalSurfacePresent
            ) {
                UgcpRuntimeContractVerificationStatus.PASS
            } else {
                UgcpRuntimeContractVerificationStatus.REPORT_ONLY_DIVERGENCE
            },
            pathway = "connectivity_recovery_and_local_fallback_observability",
            expectation = "fallback_notifications_should_remain_observational_and_non_authoritative",
            details = "semantic_class=${fallbackTruthMapping?.semanticClass ?: "missing"} " +
                "observational_surface_present=$fallbackObservationalSurfacePresent"
        )

        return checks
    }

    fun runtimeContractReportOnlyDivergenceCheckIds(
        checks: List<UgcpRuntimeContractVerificationResult> = verifyRuntimeToSharedContractConsistency()
    ): Set<String> = checks
        .filter { it.status == UgcpRuntimeContractVerificationStatus.REPORT_ONLY_DIVERGENCE }
        .map { it.checkId }
        .toSet()

    fun familyFor(type: MsgType): UgcpSchemaFamily? = messageFamilyAlignments[type]

    fun protocolTierFor(type: MsgType): UgcpProtocolSemanticTier =
        if (type in transitionalCompatibilityMessageFamilies) {
            UgcpProtocolSemanticTier.TRANSITIONAL_COMPATIBILITY
        } else {
            UgcpProtocolSemanticTier.CANONICAL
        }

    fun normalizeMessageType(rawType: String): String = MsgType.toV3Type(rawType)

    fun normalizeLifecycleStatus(rawStatus: String): String =
        lifecycleStatusNormalizations[rawStatus] ?: rawStatus

    fun classifyMessageTypeHandling(rawType: String): UgcpInputHandlingDecision {
        val normalizedType = normalizeMessageType(rawType)
        val normalizedMsgType = MsgType.fromValue(normalizedType)
        if (normalizedMsgType == null) {
            return UgcpInputHandlingDecision(
                rawInput = rawType,
                normalizedInput = normalizedType,
                protocolTier = UgcpProtocolSemanticTier.TRANSITIONAL_COMPATIBILITY,
                disposition = UgcpEnforcementDisposition.FUTURE_REJECT_CANDIDATE,
                deprecationStage = UgcpDeprecationStage.DEPRECATION_CANDIDATE,
                rationale = "unknown_type_tolerated_for_now_review_for_future_rejection"
            )
        }

        return classifyKnownMessageTypeHandling(
            rawType = rawType,
            normalizedType = normalizedType,
            tier = protocolTierFor(normalizedMsgType)
        )
    }

    fun classifyLifecycleStatusHandling(rawStatus: String): UgcpInputHandlingDecision {
        val normalizedStatus = normalizeLifecycleStatus(rawStatus)
        val isCanonical = normalizedStatus in canonicalLifecycleStatusVocabulary

        if (!isCanonical) {
            return UgcpInputHandlingDecision(
                rawInput = rawStatus,
                normalizedInput = normalizedStatus,
                protocolTier = UgcpProtocolSemanticTier.TRANSITIONAL_COMPATIBILITY,
                disposition = UgcpEnforcementDisposition.FUTURE_REJECT_CANDIDATE,
                deprecationStage = UgcpDeprecationStage.DEPRECATION_CANDIDATE,
                rationale = "noncanonical_lifecycle_status_tolerated_for_now_review_for_future_rejection"
            )
        }

        return if (rawStatus != normalizedStatus) {
            UgcpInputHandlingDecision(
                rawInput = rawStatus,
                normalizedInput = normalizedStatus,
                protocolTier = UgcpProtocolSemanticTier.CANONICAL,
                disposition = UgcpEnforcementDisposition.NORMALIZE_AND_ACCEPT,
                deprecationStage = UgcpDeprecationStage.NORMALIZED_LEGACY_ALIAS,
                rationale = "legacy_lifecycle_term_normalized_to_canonical_status"
            )
        } else {
            UgcpInputHandlingDecision(
                rawInput = rawStatus,
                normalizedInput = normalizedStatus,
                protocolTier = UgcpProtocolSemanticTier.CANONICAL,
                disposition = UgcpEnforcementDisposition.CANONICAL_ACCEPT,
                deprecationStage = UgcpDeprecationStage.ACTIVE_CANONICAL,
                rationale = "canonical_lifecycle_status_accepted"
            )
        }
    }

    fun retirementSequencingForMessageType(rawType: String): UgcpRetirementSequencingGuidance {
        val handling = classifyMessageTypeHandling(rawType)
        val sequencingPhase = when (handling.deprecationStage) {
            UgcpDeprecationStage.ACTIVE_CANONICAL -> "phase_1_warn_and_observe"
            UgcpDeprecationStage.NORMALIZED_LEGACY_ALIAS -> "phase_2_normalize_and_report"
            UgcpDeprecationStage.TRANSITIONAL_COMPATIBILITY -> "phase_3_migration_gate_candidate"
            UgcpDeprecationStage.DEPRECATION_CANDIDATE -> "phase_4_reject_after_explicit_rollout"
        }
        return UgcpRetirementSequencingGuidance(
            pathway = rawType,
            deprecationStage = handling.deprecationStage,
            sequencingPhase = sequencingPhase,
            rationale = handling.rationale
        )
    }

    private fun classifyKnownMessageTypeHandling(
        rawType: String,
        normalizedType: String,
        tier: UgcpProtocolSemanticTier
    ): UgcpInputHandlingDecision {
        return when {
            rawType != normalizedType -> UgcpInputHandlingDecision(
                rawInput = rawType,
                normalizedInput = normalizedType,
                protocolTier = tier,
                disposition = UgcpEnforcementDisposition.NORMALIZE_AND_ACCEPT,
                deprecationStage = UgcpDeprecationStage.NORMALIZED_LEGACY_ALIAS,
                rationale = "legacy_alias_normalized_before_canonical_routing"
            )

            tier == UgcpProtocolSemanticTier.TRANSITIONAL_COMPATIBILITY -> UgcpInputHandlingDecision(
                rawInput = rawType,
                normalizedInput = normalizedType,
                protocolTier = tier,
                disposition = UgcpEnforcementDisposition.TOLERATE_TRANSITIONAL,
                deprecationStage = UgcpDeprecationStage.TRANSITIONAL_COMPATIBILITY,
                rationale = "transitional_pathway_tolerated_until_explicit_retirement"
            )

            else -> UgcpInputHandlingDecision(
                rawInput = rawType,
                normalizedInput = normalizedType,
                protocolTier = tier,
                disposition = UgcpEnforcementDisposition.CANONICAL_ACCEPT,
                deprecationStage = UgcpDeprecationStage.ACTIVE_CANONICAL,
                rationale = "canonical_type_accepted"
            )
        }
    }
}

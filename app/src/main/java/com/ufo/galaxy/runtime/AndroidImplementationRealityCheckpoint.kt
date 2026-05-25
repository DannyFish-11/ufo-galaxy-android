package com.ufo.galaxy.runtime

/**
 * PR-126 — Implementation-grounded Android system reality checkpoint.
 *
 * Produces one structured snapshot that summarizes Android's real participation boundaries
 * across node identity, capability semantics, runtime freshness, panel/system truth,
 * model-topology participation, task allocation visibility, support matrix, and autonomy evidence.
 */
object AndroidImplementationRealityCheckpoint {

    const val SCHEMA_VERSION = "1"
    const val KEY_CHECKPOINT = "android_implementation_reality_checkpoint"

    fun build(truth: AndroidParticipantRuntimeTruth): Map<String, Any?> = mapOf(
        "schema_version" to SCHEMA_VERSION,
        "generated_at_ms" to truth.reportedAtMs,
        "node_system_truth" to buildNodeSystemTruth(truth),
        "runtime_semantics_truth" to buildRuntimeSemanticsTruth(truth),
        "runtime_state_truth" to buildRuntimeStateTruth(truth),
        "panel_system_truth" to buildPanelSystemTruth(truth),
        "model_topology_truth" to buildModelTopologyTruth(truth),
        "task_allocation_truth" to buildTaskAllocationTruth(truth),
        "device_support_truth" to buildDeviceSupportTruth(truth),
        "autonomy_truth" to buildAutonomyTruth(truth)
    )

    private fun buildNodeSystemTruth(truth: AndroidParticipantRuntimeTruth): Map<String, Any?> {
        val nodeIdentity = truth.runtimeNodeIdentity
        return mapOf(
            "android_node_identity_kind" to "android_participant_runtime_node",
            "participant_id" to truth.participantId,
            "node_id" to (nodeIdentity?.nodeId ?: truth.participantId),
            "device_id" to truth.deviceId,
            "runtime_host_id" to truth.hostId,
            "local_authority_boundary_class" to (
                nodeIdentity?.authorityBoundaryClass?.wireValue
                    ?: RuntimeNodeAuthorityBoundaryClass.ANDROID_PARTICIPANT_RUNTIME_ONLY.wireValue
                ),
            "canonical_authority_boundary_class" to (
                nodeIdentity?.canonicalAuthorityBoundaryClass?.wireValue
                    ?: RuntimeNodeAuthorityBoundaryClass.V2_CANONICAL_AUTHORITY_EXTERNAL.wireValue
                ),
            "session_owner_scope" to "runtime_attachment_session",
            "execution_owner_scope" to "android_participant_runtime"
        )
    }

    private fun buildRuntimeSemanticsTruth(truth: AndroidParticipantRuntimeTruth): Map<String, Any?> =
        mapOf(
            "formal_mcp_support_state" to "not_declared_on_android_runtime",
            "tool_runtime_semantic_class" to
                "android_local_execution_and_hybrid_capability_boundary_classification",
            "fully_wired_runtime_capabilities" to HybridParticipantCapabilityBoundary
                .fullyWiredCapabilities
                .map { it.wireValue },
            "contract_first_runtime_capabilities" to HybridParticipantCapabilityBoundary
                .contractFirstCapabilities
                .map { it.wireValue },
            "execution_capability_available_now" to (
                truth.capabilityTruthLevel == RuntimeNodeCapabilityTruthLevel.EXECUTION_CAPABLE ||
                    truth.capabilityTruthLevel == RuntimeNodeCapabilityTruthLevel.EXECUTION_DEGRADED
                )
        )

    private fun buildRuntimeStateTruth(truth: AndroidParticipantRuntimeTruth): Map<String, Any?> =
        mapOf(
            "runtime_availability_truth_state" to truth.runtimeAvailabilityTruthState.wireValue,
            "runtime_freshness_class" to runtimeFreshnessClass(truth.inflightContinuityState),
            "continuity_recovery_state" to (
                truth.inflightContinuityState
                    ?: AndroidContinuityRecoveryStateModel.RecoveryPhase.UNKNOWN.wireValue
                ),
            "continuity_recovery_source" to truth.inflightContinuitySource
        )

    private fun runtimeFreshnessClass(inflightContinuityState: String?): String {
        return when (AndroidContinuityRecoveryStateModel.RecoveryPhase.fromWireValue(inflightContinuityState)) {
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERING ->
                "recovering_not_fresh"
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERY_FAILED ->
                "degraded_unavailable"
            AndroidContinuityRecoveryStateModel.RecoveryPhase.REQUIRES_RECONCILIATION,
            AndroidContinuityRecoveryStateModel.RecoveryPhase.STALE_RECOVERY_ARTIFACT,
            AndroidContinuityRecoveryStateModel.RecoveryPhase.LOST_INFLIGHT ->
                "stale_requires_reconciliation"
            else -> "fresh_projection"
        }
    }

    private fun buildPanelSystemTruth(truth: AndroidParticipantRuntimeTruth): Map<String, Any?> =
        mapOf(
            "outward_truth_surface_class" to truth.outwardTruthSurfaceClass,
            "truth_tier" to truth.truthTier,
            "source_authority_class" to truth.sourceAuthorityClass,
            "is_v2_confirmed_canonical_truth" to truth.isV2ConfirmedCanonicalTruth
        )

    private fun buildModelTopologyTruth(truth: AndroidParticipantRuntimeTruth): Map<String, Any?> =
        mapOf(
            "android_model_selection_authority" to "not_authoritative",
            "android_model_topology_role" to if (
                truth.capabilityTruthLevel == RuntimeNodeCapabilityTruthLevel.EXECUTION_CAPABLE ||
                truth.capabilityTruthLevel == RuntimeNodeCapabilityTruthLevel.EXECUTION_DEGRADED
            ) {
                "execution_participant_without_provider_selection_authority"
            } else {
                "observability_or_participant_only_without_provider_selection_authority"
            }
        )

    private fun buildTaskAllocationTruth(truth: AndroidParticipantRuntimeTruth): Map<String, Any?> =
        buildMap {
            put("task_execution_visibility_state", truth.taskExecutionVisibilityState.wireValue)
            put("active_task_id", truth.activeTaskId)
            put("active_task_status", truth.activeTaskStatus?.wireValue)
            put(
                "fallback_path_active",
                truth.readinessState == ParticipantReadinessState.READY_WITH_FALLBACK ||
                    truth.taskExecutionVisibilityState ==
                    TaskExecutionVisibilityState.LOCAL_COMPLETION_PENDING_CANONICAL_RECONCILIATION
            )
            truth.taskAllocationTruth?.let {
                put("allocation_truth_substrate", it.toMap())
            }
        }

    private fun buildDeviceSupportTruth(truth: AndroidParticipantRuntimeTruth): Map<String, Any?> =
        mapOf(
            "declared_capabilities" to HybridParticipantCapabilityBoundary.entries.map {
                it.capability.wireValue
            },
            "execution_capable_capabilities" to listOf(
                HybridParticipantCapabilityBoundary.HybridCapability.STAGED_MESH_EXECUTION.wireValue
            ),
            "control_capable_capabilities" to listOf(
                HybridParticipantCapabilityBoundary.HybridCapability.BARRIER_PARTICIPATION.wireValue,
                HybridParticipantCapabilityBoundary.HybridCapability.FORMATION_REBALANCE.wireValue
            ),
            "unsupported_but_declared_capabilities" to HybridParticipantCapabilityBoundary
                .contractFirstCapabilities
                .map { it.wireValue },
            "current_capability_truth_level" to truth.capabilityTruthLevel.wireValue,
            "feature_readiness_truth_state" to truth.featureReadinessTruthState.wireValue
        )

    private fun buildAutonomyTruth(truth: AndroidParticipantRuntimeTruth): Map<String, Any?> {
        val executionEvidenceCount = truth.delegatedExecutionCount + if (truth.activeTaskId != null) 1 else 0
        val evidenceClass = when {
            truth.capabilityTruthLevel != RuntimeNodeCapabilityTruthLevel.EXECUTION_CAPABLE &&
                truth.capabilityTruthLevel != RuntimeNodeCapabilityTruthLevel.EXECUTION_DEGRADED ->
                "insufficient_execution_capability"
            executionEvidenceCount >= 3 -> "meaningful_runtime_execution_evidence"
            executionEvidenceCount > 0 -> "limited_runtime_execution_evidence"
            else -> "self_report_only_no_execution_evidence"
        }
        val effectiveAutonomyClass = when {
            evidenceClass == "meaningful_runtime_execution_evidence" ->
                "meaningfully_autonomous_operator_runtime_capable"
            truth.autonomyTruthLevel == RuntimeNodeAutonomyTruthLevel.SEMI_AUTONOMOUS_EXECUTION ->
                "semi_autonomous_runtime_capable"
            truth.autonomyTruthLevel == RuntimeNodeAutonomyTruthLevel.ASSISTED_PARTICIPANT ->
                "assisted_participant"
            else -> "observation_only"
        }
        return mapOf(
            "autonomy_truth_level" to truth.autonomyTruthLevel.wireValue,
            "execution_evidence_count" to executionEvidenceCount,
            "evidence_class" to evidenceClass,
            "effective_autonomy_class" to effectiveAutonomyClass
        )
    }
}

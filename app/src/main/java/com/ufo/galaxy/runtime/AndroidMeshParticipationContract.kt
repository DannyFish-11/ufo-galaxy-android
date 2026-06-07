package com.ufo.galaxy.runtime

/**
 * PR-8 (Android) — Explicit mesh collaboration participation contract.
 *
 * Declares Android's current mesh-runtime participation posture in a structured, testable way.
 * The goal is to avoid over-claiming full mesh runtime support while making current
 * participant-level execution capability and limits explicit for V2 and reviewers.
 */
object AndroidMeshParticipationContract {

    /**
     * High-level readiness level for Android mesh runtime participation.
     *
     * READY means Android can participate and no deferred mandatory capability remains.
     * PARTIAL means Android can participate in some mesh paths but still has deferred limits.
     * DEFERRED means Android cannot currently participate in runtime mesh execution.
     */
    enum class ReadinessLevel(val wireValue: String) {
        READY("ready"),
        PARTIAL("partial"),
        DEFERRED("deferred")
    }

    /**
     * Runtime continuity posture for Android collaboration participation.
     */
    enum class ContinuityLevel(val wireValue: String) {
        STABLE("stable"),
        RECOVERING("recovering"),
        DETACHED("detached")
    }

    /**
     * Runtime relationship edge between Android collaboration entry points.
     */
    data class RuntimeRelationship(
        val from: String,
        val to: String,
        val semantic: String
    )

    /**
     * Structured participation report for observability and verification.
     */
    data class ParticipationReport(
        val readinessLevel: ReadinessLevel,
        val continuityLevel: ContinuityLevel,
        val meshSubtaskExecutable: Boolean,
        val delegatedTakeoverExecutable: Boolean,
        val fullMeshRuntimeExecutable: Boolean,
        val constrainedReasons: List<String>,
        val proofQuality: MeshRuntimeProofQuality = MeshRuntimeProofQuality.MISSING
    ) {
        fun toWireMap(): Map<String, Any> = mapOf(
            KEY_READINESS_LEVEL to readinessLevel.wireValue,
            KEY_CONTINUITY_LEVEL to continuityLevel.wireValue,
            KEY_MESH_SUBTASK_EXECUTABLE to meshSubtaskExecutable,
            KEY_DELEGATED_TAKEOVER_EXECUTABLE to delegatedTakeoverExecutable,
            KEY_FULL_MESH_RUNTIME_EXECUTABLE to fullMeshRuntimeExecutable,
            KEY_CONSTRAINED_REASONS to constrainedReasons,
            KEY_RELATIONSHIP_GRAPH_VERSION to RELATIONSHIP_GRAPH_VERSION,
            KEY_RUNTIME_RELATIONSHIPS to RUNTIME_RELATIONSHIP_WIRE_MAPS,
            KEY_LOCAL_COLLABORATION_AGENT_SCOPE to LOCAL_COLLABORATION_AGENT_SCOPE,
            KEY_MESH_STATE_SEMANTICS to MESH_STATE_SEMANTICS,
            KEY_MESH_RESULT_SEMANTICS to MESH_RESULT_SEMANTICS,
            KEY_MESH_PARTIAL_DEFERRED_SCOPE to MESH_PARTIAL_DEFERRED_SCOPE,
            KEY_PROOF_QUALITY to proofQuality.wireValue
        )
    }

    /**
     * Relationship registry among LocalCollaborationAgent / parallel-subtask /
     * delegated execution / takeover runtime paths.
     */
    val RUNTIME_RELATIONSHIPS: List<RuntimeRelationship> = listOf(
        RuntimeRelationship(
            from = "LocalCollaborationAgent",
            to = "parallel_subtask",
            semantic = "executes assigned parallel_subtask through LocalGoalExecutor"
        ),
        RuntimeRelationship(
            from = "parallel_subtask",
            to = "StagedMeshExecutionTarget",
            semantic = "staged-mesh subtask path reuses goal execution pipeline under cross-device gate"
        ),
        RuntimeRelationship(
            from = "takeover_request",
            to = "DelegatedTakeoverExecutor",
            semantic = "delegated execution path emits ACK/PROGRESS/RESULT lifecycle"
        ),
        RuntimeRelationship(
            from = "active_takeover",
            to = "parallel_subtask",
            semantic = "governance conflict rule rejects parallel_subtask while takeover is active"
        ),
        RuntimeRelationship(
            from = "takeover_interrupted_disconnect",
            to = "delegated_result_failed",
            semantic = "disconnect while delegated takeover is active emits RESULT/FAILED and clears active takeover"
        ),
        RuntimeRelationship(
            from = "reconnect_recovery",
            to = "offline_replay_authority_filtering",
            semantic = "reconnect recovery runs authority filtering before replay to block stale-session execution results"
        ),
        RuntimeRelationship(
            from = "participant_degraded",
            to = "local_fallback_execution",
            semantic = "degradation routes execution to fallback-limited local path while blocking delegated takeover acceptance"
        ),
        RuntimeRelationship(
            from = "recovered_participant",
            to = "hybrid_mesh_participation",
            semantic = "recovered healthy participant re-enters staged mesh and delegated execution eligibility"
        )
    )

    /**
     * Explicit runtime ownership boundary for LocalCollaborationAgent.
     *
     * Android executes assigned subtask payloads and echoes result identity fields,
     * and participates as a barrier responder via [BarrierCoordinationParticipant].
     * Android does not own mesh session lifecycle or final mesh convergence authority.
     *
     * Updated in PR-A1: "barrier_coordination" moved from "does_not_own" to
     * "owns" as a participant-role responsibility backed by [BarrierCoordinationParticipant].
     */
    val LOCAL_COLLABORATION_AGENT_SCOPE: Map<String, Any> = mapOf(
        "owner" to "LocalCollaborationAgent",
        "responsibility" to "execute_assigned_parallel_subtask_via_local_goal_executor",
        "owns" to listOf(
            "parallel_subtask_payload_execution",
            "group_id_subtask_index_result_echo",
            "local_pipeline_terminal_status_emission",
            "barrier_coordination_participant_response"
        ),
        "does_not_own" to listOf(
            "mesh_session_join_leave_lifecycle",
            "barrier_coordination_authority",
            "global_mesh_result_convergence_authority"
        )
    )

    /**
     * Canonical mesh participation state semantics exported to V2-facing evidence.
     */
    val MESH_STATE_SEMANTICS: Map<String, String> = mapOf(
        ReadinessLevel.READY.wireValue to
            "android_can_execute_mesh_subtask_and_delegated_takeover_without_capability_gaps",
        ReadinessLevel.PARTIAL.wireValue to
            "android_can_execute_some_mesh_paths_but_full_mesh_runtime_is_scope_constrained",
        ReadinessLevel.DEFERRED.wireValue to
            "android_mesh_runtime_execution_is_not_currently_available"
    )

    /**
     * Canonical result semantics for Android-side mesh collaboration paths.
     */
    val MESH_RESULT_SEMANTICS: Map<String, String> = mapOf(
        "parallel_subtask_success" to
            "returns_status_success_with_task_id_group_id_subtask_index_echo",
        "parallel_subtask_error" to
            "returns_status_error_with_task_id_group_id_subtask_index_echo",
        "staged_mesh_blocked" to
            "returns_status_blocked_with_explicit_reason_cross_device_disabled"
    )

    /**
     * Explicit partial/deferred scope constraints when full mesh runtime is not closed.
     *
     * Updated in PR-A1: `HYBRID_EXECUTE_FULL` and `BARRIER_COORDINATION` are now
     * [HybridParticipantCapability.SupportLevel.AVAILABLE], so the previous deferred
     * scope entries for those capabilities have been removed.  The remaining constraint
     * reflects the permanent ownership boundary: Android is a mesh execution participant,
     * not a mesh coordinator.
     */
    val MESH_PARTIAL_DEFERRED_SCOPE: List<String> = listOf(
        "android_participates_as_execution_participant_not_mesh_coordinator"
    )

    private val RUNTIME_RELATIONSHIP_WIRE_MAPS: List<Map<String, String>> = RUNTIME_RELATIONSHIPS.map { relationship ->
        mapOf(
            "from" to relationship.from,
            "to" to relationship.to,
            "semantic" to relationship.semantic
        )
    }

    private val REQUIRED_FULL_MESH_CAPABILITIES = setOf(
        HybridParticipantCapability.HYBRID_EXECUTE_FULL,
        HybridParticipantCapability.BARRIER_COORDINATION
    )

    /**
     * Evaluates Android's current mesh collaboration participation posture.
     */
    fun evaluate(
        orchestration: MultiDeviceParticipantOrchestrationState.StateRecord,
        rollout: RolloutControlSnapshot
    ): ParticipationReport {
        val meshSubtaskExecutable =
            rollout.crossDeviceAllowed &&
                MultiDeviceParticipantOrchestrationState.shouldAdvertiseFullCapabilities(orchestration) &&
                HybridParticipantCapability.STAGED_MESH_SUBTASK.supportLevel ==
                HybridParticipantCapability.SupportLevel.AVAILABLE &&
                HybridParticipantCapability.PARALLEL_SUBTASK.supportLevel ==
                HybridParticipantCapability.SupportLevel.AVAILABLE

        val delegatedTakeoverExecutable =
            rollout.delegatedExecutionAllowed &&
                MultiDeviceParticipantOrchestrationState.eligibleForDelegatedTakeover(orchestration)

        val deferredRequiredCapabilities = REQUIRED_FULL_MESH_CAPABILITIES
            .filter { it.supportLevel != HybridParticipantCapability.SupportLevel.AVAILABLE }

        val continuityLevel = when (orchestration.orchestrationState) {
            MultiDeviceParticipantOrchestrationState.OrchestrationState.RECONNECTING,
            MultiDeviceParticipantOrchestrationState.OrchestrationState.RECOVERING ->
                ContinuityLevel.RECOVERING
            MultiDeviceParticipantOrchestrationState.OrchestrationState.DISCONNECTED ->
                ContinuityLevel.DETACHED
            else ->
                ContinuityLevel.STABLE
        }

        val fullMeshRuntimeExecutable = meshSubtaskExecutable &&
            delegatedTakeoverExecutable &&
            deferredRequiredCapabilities.isEmpty()

        val constrainedReasons = buildList {
            if (!meshSubtaskExecutable) add(REASON_MESH_SUBTASK_NOT_EXECUTABLE)
            if (!delegatedTakeoverExecutable) add(REASON_DELEGATED_TAKEOVER_NOT_EXECUTABLE)
            if (continuityLevel == ContinuityLevel.RECOVERING) add(REASON_CONTINUITY_RECOVERING)
            if (continuityLevel == ContinuityLevel.DETACHED) add(REASON_CONTINUITY_DETACHED)
            deferredRequiredCapabilities.forEach { capability ->
                // Always serialize deferred capability identity with stable wireValue (not enum name).
                add("$REASON_DEFERRED_CAPABILITY_PREFIX:${capability.wireValue}")
            }
        }

        val readinessLevel = when {
            fullMeshRuntimeExecutable -> ReadinessLevel.READY
            meshSubtaskExecutable || delegatedTakeoverExecutable -> ReadinessLevel.PARTIAL
            else -> ReadinessLevel.DEFERRED
        }

        // Derive proof quality from the orchestration health + continuity + participation signals.
        // Orchestration health drives the primary health-state input; continuity drives whether
        // evidence is stale (RECOVERING) or missing (DISCONNECTED / INACTIVE).
        val proofHealthState = orchestration.healthState
        val proofParticipationState =
            if (orchestration.participationState == RuntimeHostDescriptor.HostParticipationState.ACTIVE)
                RuntimeHostDescriptor.HostParticipationState.ACTIVE
            else
                RuntimeHostDescriptor.HostParticipationState.INACTIVE
        val proofFallbackActive = !meshSubtaskExecutable && !delegatedTakeoverExecutable &&
            continuityLevel == ContinuityLevel.STABLE
        val proofQuality = MeshRuntimeProofQuality.derive(
            healthState = proofHealthState,
            participationState = proofParticipationState,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            fallbackActive = proofFallbackActive,
            crossDeviceAllowed = rollout.crossDeviceAllowed,
            delegatedExecutionAllowed = rollout.delegatedExecutionAllowed
        )

        return ParticipationReport(
            readinessLevel = readinessLevel,
            continuityLevel = continuityLevel,
            meshSubtaskExecutable = meshSubtaskExecutable,
            delegatedTakeoverExecutable = delegatedTakeoverExecutable,
            fullMeshRuntimeExecutable = fullMeshRuntimeExecutable,
            constrainedReasons = constrainedReasons,
            proofQuality = proofQuality
        )
    }

    const val RELATIONSHIP_GRAPH_VERSION: Int = 1

    const val REASON_MESH_SUBTASK_NOT_EXECUTABLE = "mesh_subtask_not_executable"
    const val REASON_DELEGATED_TAKEOVER_NOT_EXECUTABLE = "delegated_takeover_not_executable"
    const val REASON_CONTINUITY_RECOVERING = "collaboration_continuity_recovering"
    const val REASON_CONTINUITY_DETACHED = "collaboration_continuity_detached"
    const val REASON_DEFERRED_CAPABILITY_PREFIX = "deferred_capability"

    const val KEY_READINESS_LEVEL = "mesh_participation_readiness_level"
    const val KEY_CONTINUITY_LEVEL = "mesh_participation_continuity_level"
    const val KEY_MESH_SUBTASK_EXECUTABLE = "mesh_subtask_executable"
    const val KEY_DELEGATED_TAKEOVER_EXECUTABLE = "delegated_takeover_executable"
    const val KEY_FULL_MESH_RUNTIME_EXECUTABLE = "full_mesh_runtime_executable"
    const val KEY_CONSTRAINED_REASONS = "mesh_participation_constrained_reasons"
    const val KEY_RELATIONSHIP_GRAPH_VERSION = "mesh_runtime_relationship_graph_version"
    const val KEY_RUNTIME_RELATIONSHIPS = "mesh_runtime_relationships"
    const val KEY_LOCAL_COLLABORATION_AGENT_SCOPE = "local_collaboration_agent_scope"
    const val KEY_MESH_STATE_SEMANTICS = "mesh_state_semantics"
    const val KEY_MESH_RESULT_SEMANTICS = "mesh_result_semantics"
    const val KEY_MESH_PARTIAL_DEFERRED_SCOPE = "mesh_partial_deferred_scope"
    const val KEY_PROOF_QUALITY = MeshRuntimeProofQuality.WIRE_KEY
}

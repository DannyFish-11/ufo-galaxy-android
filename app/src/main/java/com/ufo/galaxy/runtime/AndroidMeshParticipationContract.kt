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
        val meshSubtaskExecutable: Boolean,
        val delegatedTakeoverExecutable: Boolean,
        val fullMeshRuntimeExecutable: Boolean,
        val constrainedReasons: List<String>
    ) {
        fun toWireMap(): Map<String, Any> = mapOf(
            KEY_READINESS_LEVEL to readinessLevel.wireValue,
            KEY_MESH_SUBTASK_EXECUTABLE to meshSubtaskExecutable,
            KEY_DELEGATED_TAKEOVER_EXECUTABLE to delegatedTakeoverExecutable,
            KEY_FULL_MESH_RUNTIME_EXECUTABLE to fullMeshRuntimeExecutable,
            KEY_CONSTRAINED_REASONS to constrainedReasons,
            KEY_RELATIONSHIP_GRAPH_VERSION to RELATIONSHIP_GRAPH_VERSION
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
        )
    )

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

        val fullMeshRuntimeExecutable = meshSubtaskExecutable &&
            delegatedTakeoverExecutable &&
            deferredRequiredCapabilities.isEmpty()

        val constrainedReasons = buildList {
            if (!meshSubtaskExecutable) add(REASON_MESH_SUBTASK_NOT_EXECUTABLE)
            if (!delegatedTakeoverExecutable) add(REASON_DELEGATED_TAKEOVER_NOT_EXECUTABLE)
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

        return ParticipationReport(
            readinessLevel = readinessLevel,
            meshSubtaskExecutable = meshSubtaskExecutable,
            delegatedTakeoverExecutable = delegatedTakeoverExecutable,
            fullMeshRuntimeExecutable = fullMeshRuntimeExecutable,
            constrainedReasons = constrainedReasons
        )
    }

    const val RELATIONSHIP_GRAPH_VERSION: Int = 1

    const val REASON_MESH_SUBTASK_NOT_EXECUTABLE = "mesh_subtask_not_executable"
    const val REASON_DELEGATED_TAKEOVER_NOT_EXECUTABLE = "delegated_takeover_not_executable"
    const val REASON_DEFERRED_CAPABILITY_PREFIX = "deferred_capability"

    const val KEY_READINESS_LEVEL = "mesh_participation_readiness_level"
    const val KEY_MESH_SUBTASK_EXECUTABLE = "mesh_subtask_executable"
    const val KEY_DELEGATED_TAKEOVER_EXECUTABLE = "delegated_takeover_executable"
    const val KEY_FULL_MESH_RUNTIME_EXECUTABLE = "full_mesh_runtime_executable"
    const val KEY_CONSTRAINED_REASONS = "mesh_participation_constrained_reasons"
    const val KEY_RELATIONSHIP_GRAPH_VERSION = "mesh_runtime_relationship_graph_version"
}

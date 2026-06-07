package com.ufo.galaxy.runtime

enum class RuntimeTopologyNodeKind(val wireValue: String) {
    RUNTIME_HOST("runtime_host"),
    DEVICE("device"),
    PARTICIPANT("participant"),
    TASK_ALLOCATION("task_allocation")
}

data class RuntimeTopologyNode(
    val nodeId: String,
    val kind: RuntimeTopologyNodeKind,
    val attributes: Map<String, Any?>
) {
    fun toMap(): Map<String, Any?> = mapOf(
        KEY_NODE_ID to nodeId,
        KEY_KIND to kind.wireValue,
        KEY_ATTRIBUTES to attributes
    )

    companion object {
        private const val KEY_NODE_ID = "node_id"
        private const val KEY_KIND = "kind"
        private const val KEY_ATTRIBUTES = "attributes"
    }
}

data class RuntimeTopologyEdge(
    val fromNodeId: String,
    val toNodeId: String,
    val relation: String,
    val attributes: Map<String, Any?> = emptyMap()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        KEY_FROM_NODE_ID to fromNodeId,
        KEY_TO_NODE_ID to toNodeId,
        KEY_RELATION to relation,
        KEY_ATTRIBUTES to attributes
    )

    companion object {
        private const val KEY_FROM_NODE_ID = "from_node_id"
        private const val KEY_TO_NODE_ID = "to_node_id"
        private const val KEY_RELATION = "relation"
        private const val KEY_ATTRIBUTES = "attributes"
    }
}

data class AndroidRuntimeParticipationTopology(
    val generatedAtMs: Long,
    val nodes: List<RuntimeTopologyNode>,
    val edges: List<RuntimeTopologyEdge>
) {
    fun toMap(): Map<String, Any?> = mapOf(
        KEY_SCHEMA_VERSION to SCHEMA_VERSION,
        KEY_GENERATED_AT_MS to generatedAtMs,
        KEY_NODES to nodes.map { it.toMap() },
        KEY_EDGES to edges.map { it.toMap() }
    )

    companion object {
        private const val SCHEMA_VERSION = "1"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_GENERATED_AT_MS = "generated_at_ms"
        private const val KEY_NODES = "nodes"
        private const val KEY_EDGES = "edges"

        fun from(truth: AndroidParticipantRuntimeTruth): AndroidRuntimeParticipationTopology {
            val nodeIdentity = truth.runtimeNodeIdentity
            val participantNodeId = nodeIdentity?.nodeId ?: truth.participantId
            val hostNodeId = "host:${truth.hostId}"
            val deviceNodeId = "device:${truth.deviceId}"
            val activeAllocation = truth.taskAllocationTruth?.activeTask
            val allocationNodeId = activeAllocation?.let { "allocation:${it.taskId}" }
            val nodes = buildList {
                add(
                    RuntimeTopologyNode(
                        nodeId = hostNodeId,
                        kind = RuntimeTopologyNodeKind.RUNTIME_HOST,
                        attributes = mapOf(
                            "host_id" to truth.hostId,
                            "participation_state" to truth.participationState.wireValue,
                            "runtime_availability_truth_state" to truth.runtimeAvailabilityTruthState.wireValue
                        )
                    )
                )
                add(
                    RuntimeTopologyNode(
                        nodeId = deviceNodeId,
                        kind = RuntimeTopologyNodeKind.DEVICE,
                        attributes = mapOf(
                            "device_id" to truth.deviceId,
                            "device_role" to truth.deviceRole,
                            "feature_readiness_truth_state" to truth.featureReadinessTruthState.wireValue
                        )
                    )
                )
                add(
                    RuntimeTopologyNode(
                        nodeId = participantNodeId,
                        kind = RuntimeTopologyNodeKind.PARTICIPANT,
                        attributes = buildMap {
                            put("participant_id", truth.participantId)
                            put("coordination_role", truth.coordinationRole.wireValue)
                            put("source_runtime_posture", truth.sourceRuntimePosture)
                            put("capability_truth_level", truth.capabilityTruthLevel.wireValue)
                            put("autonomy_truth_level", truth.autonomyTruthLevel.wireValue)
                            nodeIdentity?.let {
                                put("execution_participation_state", it.executionParticipationState.wireValue)
                                put("collaboration_participation_state", it.collaborationParticipationState.wireValue)
                                put("autonomy_evidence", it.autonomyEvidence.toMap())
                            }
                        }
                    )
                )
                if (activeAllocation != null && allocationNodeId != null) {
                    add(
                        RuntimeTopologyNode(
                            nodeId = allocationNodeId,
                            kind = RuntimeTopologyNodeKind.TASK_ALLOCATION,
                            attributes = activeAllocation.toMap()
                        )
                    )
                }
            }
            val edges = buildList {
                add(
                    RuntimeTopologyEdge(
                        fromNodeId = hostNodeId,
                        toNodeId = participantNodeId,
                        relation = "hosts_participant_runtime"
                    )
                )
                add(
                    RuntimeTopologyEdge(
                        fromNodeId = deviceNodeId,
                        toNodeId = hostNodeId,
                        relation = "backs_runtime_host"
                    )
                )
                if (allocationNodeId != null) {
                    add(
                        RuntimeTopologyEdge(
                            fromNodeId = participantNodeId,
                            toNodeId = allocationNodeId,
                            relation = "executes_allocation",
                            attributes = mapOf(
                                "task_execution_visibility_state" to truth.taskExecutionVisibilityState.wireValue
                            )
                        )
                    )
                }
            }
            return AndroidRuntimeParticipationTopology(
                generatedAtMs = truth.reportedAtMs,
                nodes = nodes,
                edges = edges
            )
        }
    }
}

package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr8AndroidMeshParticipationContractTest {

    @Test
    fun `evaluate returns PARTIAL when mesh subtask and delegated takeover are executable but full mesh is deferred`() {
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = createHealthyOrchestrationRecord(),
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true)
        )

        assertEquals(AndroidMeshParticipationContract.ReadinessLevel.PARTIAL, report.readinessLevel)
        assertTrue(report.meshSubtaskExecutable)
        assertTrue(report.delegatedTakeoverExecutable)
        assertFalse(report.fullMeshRuntimeExecutable)
        assertTrue(
            report.constrainedReasons.contains(
                "${AndroidMeshParticipationContract.REASON_DEFERRED_CAPABILITY_PREFIX}:hybrid_execute_full"
            )
        )
        assertTrue(
            report.constrainedReasons.contains(
                "${AndroidMeshParticipationContract.REASON_DEFERRED_CAPABILITY_PREFIX}:barrier_coordination"
            )
        )
    }

    @Test
    fun `evaluate returns DEFERRED when orchestration is reconnecting and rollout gates are closed`() {
        val reconnecting = MultiDeviceParticipantOrchestrationState.from(
            healthState = ParticipantHealthState.RECOVERING,
            reconnectState = ReconnectRecoveryState.RECOVERING,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = reconnecting,
            rollout = rollout(crossDeviceAllowed = false, delegatedExecutionAllowed = false)
        )

        assertEquals(AndroidMeshParticipationContract.ReadinessLevel.DEFERRED, report.readinessLevel)
        assertFalse(report.meshSubtaskExecutable)
        assertFalse(report.delegatedTakeoverExecutable)
        assertFalse(report.fullMeshRuntimeExecutable)
        assertTrue(report.constrainedReasons.contains(AndroidMeshParticipationContract.REASON_MESH_SUBTASK_NOT_EXECUTABLE))
        assertTrue(
            report.constrainedReasons.contains(
                AndroidMeshParticipationContract.REASON_DELEGATED_TAKEOVER_NOT_EXECUTABLE
            )
        )
    }

    @Test
    fun `runtime relationships include LocalCollaborationAgent, parallel subtask, delegated execution, and takeover conflict gate`() {
        val relationships = AndroidMeshParticipationContract.RUNTIME_RELATIONSHIPS

        assertTrue(
            relationships.any {
                it.from == "LocalCollaborationAgent" && it.to == "parallel_subtask"
            }
        )
        assertTrue(
            relationships.any {
                it.from == "parallel_subtask" && it.to == "StagedMeshExecutionTarget"
            }
        )
        assertTrue(
            relationships.any {
                it.from == "takeover_request" && it.to == "DelegatedTakeoverExecutor"
            }
        )
        assertTrue(
            relationships.any {
                it.from == "active_takeover" && it.to == "parallel_subtask"
            }
        )
    }

    @Test
    fun `LocalCollaborationAgent scope explicitly declares ownership and non ownership boundaries`() {
        val scope = AndroidMeshParticipationContract.LOCAL_COLLABORATION_AGENT_SCOPE

        assertEquals("LocalCollaborationAgent", scope["owner"])
        val owns = scope["owns"] as List<*>
        val doesNotOwn = scope["does_not_own"] as List<*>
        assertTrue(owns.contains("parallel_subtask_payload_execution"))
        assertTrue(owns.contains("group_id_subtask_index_result_echo"))
        assertTrue(doesNotOwn.contains("mesh_session_join_leave_lifecycle"))
        assertTrue(doesNotOwn.contains("barrier_coordination"))
    }

    @Test
    fun `mesh state and result semantics define partial deferred boundary explicitly`() {
        val stateSemantics = AndroidMeshParticipationContract.MESH_STATE_SEMANTICS
        val resultSemantics = AndroidMeshParticipationContract.MESH_RESULT_SEMANTICS
        val deferredScope = AndroidMeshParticipationContract.MESH_PARTIAL_DEFERRED_SCOPE

        assertNotEquals(
            stateSemantics[AndroidMeshParticipationContract.ReadinessLevel.PARTIAL.wireValue],
            stateSemantics[AndroidMeshParticipationContract.ReadinessLevel.READY.wireValue]
        )
        assertTrue(resultSemantics.containsKey("parallel_subtask_success"))
        assertTrue(resultSemantics.containsKey("parallel_subtask_error"))
        assertTrue(resultSemantics.containsKey("staged_mesh_blocked"))
        assertTrue(
            deferredScope.contains(
                "android_participates_as_execution_participant_not_mesh_coordinator"
            )
        )
    }

    @Test
    fun `toWireMap exposes readiness and constrained observability keys`() {
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = createHealthyOrchestrationRecord(),
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true)
        )

        val wireMap = report.toWireMap()
        assertEquals("partial", wireMap[AndroidMeshParticipationContract.KEY_READINESS_LEVEL])
        assertEquals(true, wireMap[AndroidMeshParticipationContract.KEY_MESH_SUBTASK_EXECUTABLE])
        assertEquals(true, wireMap[AndroidMeshParticipationContract.KEY_DELEGATED_TAKEOVER_EXECUTABLE])
        assertEquals(false, wireMap[AndroidMeshParticipationContract.KEY_FULL_MESH_RUNTIME_EXECUTABLE])
        assertEquals(
            AndroidMeshParticipationContract.RELATIONSHIP_GRAPH_VERSION,
            wireMap[AndroidMeshParticipationContract.KEY_RELATIONSHIP_GRAPH_VERSION]
        )
        assertNotNull(wireMap[AndroidMeshParticipationContract.KEY_CONSTRAINED_REASONS])
        assertEquals(
            AndroidMeshParticipationContract.LOCAL_COLLABORATION_AGENT_SCOPE,
            wireMap[AndroidMeshParticipationContract.KEY_LOCAL_COLLABORATION_AGENT_SCOPE]
        )
        assertEquals(
            AndroidMeshParticipationContract.MESH_STATE_SEMANTICS,
            wireMap[AndroidMeshParticipationContract.KEY_MESH_STATE_SEMANTICS]
        )
        assertEquals(
            AndroidMeshParticipationContract.MESH_RESULT_SEMANTICS,
            wireMap[AndroidMeshParticipationContract.KEY_MESH_RESULT_SEMANTICS]
        )
        assertEquals(
            AndroidMeshParticipationContract.MESH_PARTIAL_DEFERRED_SCOPE,
            wireMap[AndroidMeshParticipationContract.KEY_MESH_PARTIAL_DEFERRED_SCOPE]
        )
    }

    private fun createHealthyOrchestrationRecord(): MultiDeviceParticipantOrchestrationState.StateRecord =
        MultiDeviceParticipantOrchestrationState.from(
            healthState = ParticipantHealthState.HEALTHY,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )

    private fun rollout(
        crossDeviceAllowed: Boolean,
        delegatedExecutionAllowed: Boolean
    ): RolloutControlSnapshot = RolloutControlSnapshot(
        crossDeviceAllowed = crossDeviceAllowed,
        delegatedExecutionAllowed = delegatedExecutionAllowed,
        fallbackToLocalAllowed = true,
        goalExecutionAllowed = true
    )
}

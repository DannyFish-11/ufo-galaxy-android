package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr8AndroidMeshParticipationContractTest {

    @Test
    fun `evaluate returns PARTIAL when mesh subtask and delegated takeover are executable but full mesh is deferred`() {
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = connectedRecord(),
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
    fun `toWireMap exposes readiness and constrained observability keys`() {
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = connectedRecord(),
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
    }

    private fun connectedRecord(): MultiDeviceParticipantOrchestrationState.StateRecord =
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

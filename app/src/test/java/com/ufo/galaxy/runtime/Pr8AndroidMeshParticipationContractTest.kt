package com.ufo.galaxy.runtime

import com.ufo.galaxy.network.OfflineTaskQueue
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
        assertEquals(AndroidMeshParticipationContract.ContinuityLevel.STABLE, report.continuityLevel)
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
        assertEquals(AndroidMeshParticipationContract.ContinuityLevel.RECOVERING, report.continuityLevel)
        assertFalse(report.meshSubtaskExecutable)
        assertFalse(report.delegatedTakeoverExecutable)
        assertFalse(report.fullMeshRuntimeExecutable)
        assertTrue(report.constrainedReasons.contains(AndroidMeshParticipationContract.REASON_MESH_SUBTASK_NOT_EXECUTABLE))
        assertTrue(
            report.constrainedReasons.contains(
                AndroidMeshParticipationContract.REASON_DELEGATED_TAKEOVER_NOT_EXECUTABLE
            )
        )
        assertTrue(
            report.constrainedReasons.contains(AndroidMeshParticipationContract.REASON_CONTINUITY_RECOVERING)
        )
    }

    @Test
    fun `evaluate returns DETACHED continuity when orchestration is disconnected`() {
        val disconnected = MultiDeviceParticipantOrchestrationState.from(
            healthState = ParticipantHealthState.UNKNOWN,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
        )
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = disconnected,
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true)
        )

        assertEquals(AndroidMeshParticipationContract.ReadinessLevel.DEFERRED, report.readinessLevel)
        assertEquals(AndroidMeshParticipationContract.ContinuityLevel.DETACHED, report.continuityLevel)
        assertTrue(report.constrainedReasons.contains(AndroidMeshParticipationContract.REASON_CONTINUITY_DETACHED))
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
        assertTrue(
            relationships.any {
                it.from == "takeover_interrupted_disconnect" && it.to == "delegated_result_failed"
            }
        )
        assertTrue(
            relationships.any {
                it.from == "reconnect_recovery" && it.to == "offline_replay_authority_filtering"
            }
        )
        assertTrue(
            relationships.any {
                it.from == "participant_degraded" && it.to == "local_fallback_execution"
            }
        )
        assertTrue(
            relationships.any {
                it.from == "recovered_participant" && it.to == "hybrid_mesh_participation"
            }
        )
    }

    @Test
    fun `LocalCollaborationAgent scope explicitly declares ownership and non-ownership boundaries`() {
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
        assertEquals("stable", wireMap[AndroidMeshParticipationContract.KEY_CONTINUITY_LEVEL])
        assertEquals(true, wireMap[AndroidMeshParticipationContract.KEY_MESH_SUBTASK_EXECUTABLE])
        assertEquals(true, wireMap[AndroidMeshParticipationContract.KEY_DELEGATED_TAKEOVER_EXECUTABLE])
        assertEquals(false, wireMap[AndroidMeshParticipationContract.KEY_FULL_MESH_RUNTIME_EXECUTABLE])
        assertEquals(
            AndroidMeshParticipationContract.RELATIONSHIP_GRAPH_VERSION,
            wireMap[AndroidMeshParticipationContract.KEY_RELATIONSHIP_GRAPH_VERSION]
        )
        @Suppress("UNCHECKED_CAST")
        val relationships = wireMap[AndroidMeshParticipationContract.KEY_RUNTIME_RELATIONSHIPS] as List<Map<String, String>>
        assertEquals(AndroidMeshParticipationContract.RUNTIME_RELATIONSHIPS.size, relationships.size)
        assertTrue(
            relationships.any {
                it["from"] == "reconnect_recovery" &&
                    it["to"] == "offline_replay_authority_filtering"
            }
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

    @Test
    fun `reconnect recovery transitions through constrained states to stable`() {
        val disconnected = MultiDeviceParticipantOrchestrationState.from(
            healthState = ParticipantHealthState.UNKNOWN,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
        )
        val reconnecting = MultiDeviceParticipantOrchestrationState.from(
            healthState = ParticipantHealthState.RECOVERING,
            reconnectState = ReconnectRecoveryState.RECOVERING,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        val reconnectedStable = createHealthyOrchestrationRecord()

        val disconnectedReport = AndroidMeshParticipationContract.evaluate(
            orchestration = disconnected,
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true)
        )
        val reconnectingReport = AndroidMeshParticipationContract.evaluate(
            orchestration = reconnecting,
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true)
        )
        val reconnectedReport = AndroidMeshParticipationContract.evaluate(
            orchestration = reconnectedStable,
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true)
        )

        assertEquals(AndroidMeshParticipationContract.ReadinessLevel.DEFERRED, disconnectedReport.readinessLevel)
        assertEquals(AndroidMeshParticipationContract.ContinuityLevel.DETACHED, disconnectedReport.continuityLevel)
        assertEquals(AndroidMeshParticipationContract.ReadinessLevel.DEFERRED, reconnectingReport.readinessLevel)
        assertEquals(AndroidMeshParticipationContract.ContinuityLevel.RECOVERING, reconnectingReport.continuityLevel)
        assertEquals(AndroidMeshParticipationContract.ReadinessLevel.PARTIAL, reconnectedReport.readinessLevel)
        assertEquals(AndroidMeshParticipationContract.ContinuityLevel.STABLE, reconnectedReport.continuityLevel)

        val phaseSequence = UnifiedReplayRecoveryContract.canonicalPhaseSequence
        assertTrue(
            phaseSequence.indexOf(UnifiedReplayRecoveryContract.RecoveryPhase.AUTHORITY_FILTERING) <
                phaseSequence.indexOf(UnifiedReplayRecoveryContract.RecoveryPhase.REPLAYING)
        )
        assertEquals(
            UnifiedReplayRecoveryContract.RecoveryPhase.RECOVERED,
            phaseSequence.last()
        )
    }

    @Test
    fun `degraded state blocks takeover and routes to fallback execution`() {
        val degradedFallback = MultiDeviceParticipantOrchestrationState.from(
            healthState = ParticipantHealthState.DEGRADED,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY_WITH_FALLBACK,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        val degradedReport = AndroidMeshParticipationContract.evaluate(
            orchestration = degradedFallback,
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true)
        )
        assertEquals(AndroidMeshParticipationContract.ReadinessLevel.DEFERRED, degradedReport.readinessLevel)
        assertEquals(AndroidMeshParticipationContract.ContinuityLevel.STABLE, degradedReport.continuityLevel)
        assertTrue(
            degradedReport.constrainedReasons.contains(
                AndroidMeshParticipationContract.REASON_DELEGATED_TAKEOVER_NOT_EXECUTABLE
            )
        )
        assertTrue(
            degradedReport.constrainedReasons.contains(
                AndroidMeshParticipationContract.REASON_MESH_SUBTASK_NOT_EXECUTABLE
            )
        )

        val degradedTakeoverScenario = DelegatedTakeoverRecoveryContract.scenarioFor(
            MultiDeviceParticipantOrchestrationState.OrchestrationState.CONNECTED,
            MultiDeviceParticipantOrchestrationState.OrchestrationState.DEGRADED
        )
        assertNotNull(degradedTakeoverScenario)
        assertEquals(
            DelegatedTakeoverRecoveryContract.TakeoverInterruptionOutcome.DRAIN_THEN_TERMINATE,
            degradedTakeoverScenario!!.interruptionOutcome
        )
        assertEquals(
            DelegatedTakeoverRecoveryContract.REASON_PARTICIPANT_DEGRADED,
            degradedTakeoverScenario.requiredReasonPrefix
        )

        val recoveredReport = AndroidMeshParticipationContract.evaluate(
            orchestration = createHealthyOrchestrationRecord(),
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true)
        )
        assertEquals(AndroidMeshParticipationContract.ReadinessLevel.PARTIAL, recoveredReport.readinessLevel)
        assertTrue(recoveredReport.delegatedTakeoverExecutable)
        assertTrue(recoveredReport.meshSubtaskExecutable)
    }

    @Test
    fun `disconnect interruption blocks stale delegated replay result`() {
        val disconnectTakeoverScenario = DelegatedTakeoverRecoveryContract.scenarioFor(
            MultiDeviceParticipantOrchestrationState.OrchestrationState.CONNECTED,
            MultiDeviceParticipantOrchestrationState.OrchestrationState.RECONNECTING
        )
        assertNotNull(disconnectTakeoverScenario)
        assertEquals(
            DelegatedTakeoverRecoveryContract.REASON_PARTICIPANT_DISCONNECTED,
            disconnectTakeoverScenario!!.requiredReasonPrefix
        )
        assertFalse(disconnectTakeoverScenario.mayResumeAfterReconnect)

        val staleReplayDecision = UnifiedReplayRecoveryContract.evaluateMessageAuthority(
            message = OfflineTaskQueue.QueuedMessage(
                type = "goal_execution_result",
                json = staleDelegatedResultPayload(staleDelegatedTaskId),
                sessionTag = staleSessionId
            ),
            currentDurableSessionId = currentSessionId
        )
        assertEquals(
            UnifiedReplayRecoveryContract.MessageAuthorityDecision.STALE_SESSION_BLOCKED,
            staleReplayDecision
        )
        assertFalse(staleReplayDecision.isReplayAllowed)
    }

    companion object {
        private const val staleDelegatedTaskId = "delegated-old-session"
        private const val staleSessionId = "session-old"
        private const val currentSessionId = "session-new"
        private const val payloadKey = "payload"
        private const val taskIdKey = "task_id"

        private fun staleDelegatedResultPayload(taskId: String): String =
            """{"$payloadKey":{"$taskIdKey":"$taskId"}}"""
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

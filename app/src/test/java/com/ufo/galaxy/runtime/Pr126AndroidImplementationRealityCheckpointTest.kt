package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr126AndroidImplementationRealityCheckpointTest {

    private fun descriptor() = RuntimeHostDescriptor(
        hostId = "host-126",
        deviceId = "device-126",
        deviceRole = "phone",
        formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
    )

    private fun attachedSnapshot(delegatedCount: Int) = AttachedRuntimeHostSessionSnapshot(
        sessionId = "sess-126",
        deviceId = "device-126",
        runtimeSessionId = "rt-126",
        attachmentState = AttachedRuntimeSession.State.ATTACHED.wireValue,
        isReuseValid = true,
        delegatedExecutionCount = delegatedCount,
        invalidationReason = null,
        hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue,
        posture = SourceRuntimePosture.JOIN_RUNTIME
    )

    private fun allocationTruthWithStableCompletions(): AndroidTaskAllocationTruthSnapshot {
        val recordA = TaskAllocationTruthRecord(
            taskId = "task-a",
            requestedAllocationClass = "delegated_takeover_request",
            selectedExecutorRef = "android:participant:device-126:host-126",
            inFlightOwnerRef = "android:participant:device-126:host-126",
            executionLocation = "android_participant_runtime",
            allocationPathClass = TaskAllocationPathClass.CANONICAL_DELEGATED_DISPATCH,
            fallbackPathClass = null,
            participantLocalPhase = TaskAllocationPhase.CLOSED,
            inFlightOwnership = false,
            requestedAtMs = 1_000L,
            selectedAtMs = 1_000L,
            inFlightAtMs = 1_100L,
            closedAtMs = 1_300L,
            closureClass = TaskAllocationClosureClass.RESULT,
            lastUpdatedAtMs = 1_300L,
            transitions = emptyList()
        )
        val recordB = recordA.copy(
            taskId = "task-b",
            requestedAtMs = 2_000L,
            selectedAtMs = 2_000L,
            inFlightAtMs = 2_100L,
            closedAtMs = 2_300L,
            lastUpdatedAtMs = 2_300L
        )
        return AndroidTaskAllocationTruthSnapshot(
            activeTaskId = null,
            activeTask = null,
            recentTaskAllocations = listOf(recordA, recordB),
            generatedAtMs = 2_400L
        )
    }

    @Test
    fun `runtime truth snapshot payload includes implementation reality checkpoint`() {
        val truth = AndroidParticipantRuntimeTruth.from(
            descriptor = descriptor(),
            sessionSnapshot = attachedSnapshot(delegatedCount = 2),
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            reconciliationEpoch = 12
        )

        val signal = ReconciliationSignal.runtimeTruthSnapshot(truth)

        @Suppress("UNCHECKED_CAST")
        val checkpoint = signal.payload[ReconciliationSignal.KEY_IMPLEMENTATION_REALITY_CHECKPOINT]
            as Map<String, Any?>
        assertEquals("1", checkpoint["schema_version"])
        assertTrue(checkpoint.containsKey("node_system_truth"))
        assertTrue(checkpoint.containsKey("runtime_semantics_truth"))
        assertTrue(checkpoint.containsKey("task_allocation_truth"))
        assertTrue(checkpoint.containsKey("device_support_truth"))
        assertTrue(checkpoint.containsKey("autonomy_truth"))
        @Suppress("UNCHECKED_CAST")
        val taskAllocationTruth = checkpoint["task_allocation_truth"] as Map<String, Any?>
        assertTrue(taskAllocationTruth.containsKey("allocation_truth_substrate"))
        assertTrue(signal.payload.containsKey(ReconciliationSignal.KEY_TASK_ALLOCATION_TRUTH))
    }

    @Test
    fun `checkpoint support truth reports contract-first capabilities as unsupported-but-declared`() {
        val truth = AndroidParticipantRuntimeTruth.from(
            descriptor = descriptor(),
            sessionSnapshot = attachedSnapshot(delegatedCount = 2),
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            reconciliationEpoch = 13
        )

        @Suppress("UNCHECKED_CAST")
        val supportTruth = AndroidImplementationRealityCheckpoint.build(truth)["device_support_truth"]
            as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val unsupported = supportTruth["unsupported_but_declared_capabilities"] as List<String>
        assertTrue(unsupported.contains(HybridParticipantCapabilityBoundary.HybridCapability.HYBRID_EXECUTE.wireValue))
        assertTrue(unsupported.contains(HybridParticipantCapabilityBoundary.HybridCapability.RAG_QUERY.wireValue))
        assertTrue(unsupported.contains(HybridParticipantCapabilityBoundary.HybridCapability.CODE_EXECUTE.wireValue))
    }

    @Test
    fun `checkpoint autonomy truth promotes to meaningful autonomy only with stronger execution evidence`() {
        val truth = AndroidParticipantRuntimeTruth.from(
            descriptor = descriptor(),
            sessionSnapshot = attachedSnapshot(delegatedCount = 3),
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            taskAllocationTruth = allocationTruthWithStableCompletions(),
            inflightContinuityState = AndroidContinuityRecoveryStateModel
                .RecoveryPhase.RESUMED_CLEANLY.wireValue,
            reconciliationEpoch = 14
        )

        @Suppress("UNCHECKED_CAST")
        val autonomyTruth = AndroidImplementationRealityCheckpoint.build(truth)["autonomy_truth"]
            as Map<String, Any?>
        assertEquals("meaningful_runtime_execution_evidence", autonomyTruth["evidence_class"])
        assertEquals(
            "meaningfully_autonomous_operator_runtime_capable",
            autonomyTruth["effective_autonomy_class"]
        )
    }
}

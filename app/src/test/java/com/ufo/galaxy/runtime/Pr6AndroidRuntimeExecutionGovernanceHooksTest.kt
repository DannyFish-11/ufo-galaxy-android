package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.GoalExecutionPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr6AndroidRuntimeExecutionGovernanceHooksTest {

    private fun buildDescriptor(
        deviceId: String = "device-pr6",
        hostId: String = "host-pr6",
        participationState: RuntimeHostDescriptor.HostParticipationState =
            RuntimeHostDescriptor.HostParticipationState.ACTIVE
    ) = RuntimeHostDescriptor(
        hostId = hostId,
        deviceId = deviceId,
        deviceRole = "phone",
        formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState = participationState
    )

    private fun makePayload(
        taskId: String = "task-pr6",
        delegatedFlowId: String = "flow-pr6",
        flowLineageId: String = "lineage-pr6",
        dispatchTraceId: String = "trace-pr6"
    ) = GoalExecutionPayload(
        task_id = taskId,
        goal = "Validate Android governance hooks",
        delegated_flow_id = delegatedFlowId,
        flow_lineage_id = flowLineageId,
        dispatch_trace_id = dispatchTraceId
    )

    @Test
    fun `goal execution lifecycle and governance hooks produce correlated closed-loop evidence`() {
        val lifecycleReport = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.READY,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE,
            registrationStatus = RegistrationTruthStatus.REGISTERED,
            capabilityAlignmentStatus = CapabilityAlignmentStatus.FULLY_ALIGNED,
            reportEpoch = 6
        )
        val harness = DualRepoE2EVerificationHarness(
            deviceId = lifecycleReport.deviceId,
            participantId = lifecycleReport.participantId,
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )
        val flowBridge = AndroidDelegatedFlowBridge
            .fromGoalExecution(makePayload(), attachedSessionId = "session-pr6")
            .transition(AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION)

        harness.recordLifecycleTruth(lifecycleReport)
        harness.recordStageOutcome(
            DualRepoE2EVerificationStage.CAPABILITY_REPORT,
            ScenarioOutcomeStatus.PASSED
        )
        harness.recordGoalExecutionReceipt(
            flowBridge = flowBridge,
            acceptanceDecision = AndroidExecutionGovernanceContract.evaluateAcceptance(
                executionType = AndroidExecutionGovernanceContract.ExecutionType.GOAL_EXECUTION,
                context = AndroidExecutionGovernanceContract.AcceptanceContext(activeTakeoverId = null)
            )
        )

        val emitDecision = AndroidCanonicalExecutionEventOwner().evaluateEventEmit(
            CanonicalExecutionEvent.execution(
                eventType = CanonicalExecutionEventType.EXECUTION_STARTED,
                flowId = flowBridge.delegatedFlowId,
                taskId = flowBridge.taskId,
                traceId = flowBridge.traceId,
                delegatedLineageId = flowBridge.flowLineageId,
                attachedSessionId = flowBridge.attachedSessionId,
                stepIndex = null,
                reason = "goal execution started",
                sourceComponent = AndroidCanonicalExecutionEventOwner.INTEGRATION_PIPELINE,
                emissionSeq = 1L
            )
        )
        harness.recordGoalExecutionRuntimeAvailable(flowBridge, emitDecision)
        harness.recordGoalExecutionResult(
            flowBridge = flowBridge.transition(AndroidFlowExecutionPhase.COMPLETED),
            status = ScenarioOutcomeStatus.PASSED
        )

        val evaluator = DelegatedRuntimePostGraduationGovernanceEvaluator().apply {
            DelegatedRuntimeGovernanceDimension.entries.forEach { markDimensionCompliant(it) }
        }
        harness.recordGovernanceSnapshot(
            snapshot = evaluator.buildSnapshot(deviceId = lifecycleReport.deviceId),
            flowBridge = flowBridge
        )

        val report = harness.buildReport()
        val wire = report.toWireMap()

        assertTrue(report.isRealDeviceE2EVerified)
        assertTrue(report.hasCanonicalRoundTripHooks)
        assertTrue(report.isIdentityCorrelated)
        assertTrue(report.hasGovernanceSignal)
        assertEquals("trace-pr6", report.correlatedTraceId)
        assertEquals("session-pr6", report.correlatedRuntimeSessionId)
        assertEquals("task-pr6", report.correlatedTaskId)
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator.ARTIFACT_DEVICE_GOVERNANCE_COMPLIANT,
            wire["governance_artifact_tag"]
        )
        assertEquals(true, wire["is_runtime_closed_evidence"])
        assertEquals(true, wire["has_governance_signal"])
    }

    @Test
    fun `goal execution rejection due to active takeover is recorded as failed receipt evidence`() {
        val harness = DualRepoE2EVerificationHarness(
            deviceId = "device-pr6",
            participantId = "participant-pr6",
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )
        val flowBridge = AndroidDelegatedFlowBridge.fromGoalExecution(
            makePayload(taskId = "task-reject-pr6"),
            attachedSessionId = "session-reject-pr6"
        )

        harness.recordGoalExecutionReceipt(
            flowBridge = flowBridge,
            acceptanceDecision = AndroidExecutionGovernanceContract.evaluateAcceptance(
                executionType = AndroidExecutionGovernanceContract.ExecutionType.GOAL_EXECUTION,
                context = AndroidExecutionGovernanceContract.AcceptanceContext(
                    activeTakeoverId = "takeover-pr6"
                )
            )
        )

        assertEquals(
            ScenarioOutcomeStatus.FAILED,
            harness.getStageStatus(DualRepoE2EVerificationStage.TASK_ASSIGNMENT_RECEPTION)
        )
        assertEquals(
            ScenarioOutcomeStatus.FAILED,
            harness.getVerificationHooks()[DualRepoE2EVerificationHookKind.EXECUTION_RECEIVED]
                ?.outcomeStatus
        )
        assertTrue(
            harness.getVerificationHooks()[DualRepoE2EVerificationHookKind.EXECUTION_RECEIVED]
                ?.reason
                ?.contains(AndroidExecutionGovernanceContract.SEMANTIC_REJECT_ACTIVE_TAKEOVER_CONFLICT)
                == true
        )
    }

    @Test
    fun `lifecycle truth hook records reconnect recovery and degraded outcome stages`() {
        val lifecycleReport = ParticipantLifecycleTruthReportBuilder.build(
            descriptor = buildDescriptor(),
            formalLifecycleState = FormalParticipantLifecycleState.DEGRADED,
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERED,
            registrationStatus = RegistrationTruthStatus.REGISTERED,
            reRegistrationOutcome = ReRegistrationOutcome.COMPLETED,
            capabilityAlignmentStatus = CapabilityAlignmentStatus.FULLY_ALIGNED,
            recoveredButDegraded = true,
            reportEpoch = 7
        )
        val harness = DualRepoE2EVerificationHarness(
            deviceId = lifecycleReport.deviceId,
            participantId = lifecycleReport.participantId,
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )

        harness.recordLifecycleTruth(lifecycleReport)

        assertEquals(
            ParticipantLifecycleTruthState.CAPABILITY_RE_ALIGNED,
            harness.lifecycleTruthState
        )
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            harness.getStageStatus(DualRepoE2EVerificationStage.DEVICE_REGISTER)
        )
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            harness.getStageStatus(DualRepoE2EVerificationStage.RECONNECT_RECOVERY)
        )
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            harness.getStageStatus(DualRepoE2EVerificationStage.DEGRADED_OUTCOME_RECORDING)
        )
    }

    @Test
    fun `unknown governance snapshot fails state correlation hook and exports missing dimensions`() {
        val harness = DualRepoE2EVerificationHarness(
            deviceId = "device-pr6",
            participantId = "participant-pr6",
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )
        val flowBridge = AndroidDelegatedFlowBridge.fromGoalExecution(
            makePayload(taskId = "task-governance-pr6"),
            attachedSessionId = "session-governance-pr6"
        )
        val evaluator = DelegatedRuntimePostGraduationGovernanceEvaluator().apply {
            markDimensionCompliant(DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION)
        }

        harness.recordGovernanceSnapshot(
            snapshot = evaluator.buildSnapshot(deviceId = "device-pr6"),
            flowBridge = flowBridge
        )

        val report = harness.buildReport()
        val wire = report.toWireMap()

        assertNotNull(report.governanceSnapshot)
        assertFalse(
            report.hasCanonicalRoundTripHooks
        )
        assertEquals(
            ScenarioOutcomeStatus.FAILED,
            harness.getVerificationHooks()[DualRepoE2EVerificationHookKind.STATE_CORRELATED]
                ?.outcomeStatus
        )
        assertEquals(
            DelegatedRuntimePostGraduationGovernanceEvaluator
                .ARTIFACT_DEVICE_GOVERNANCE_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            wire["governance_artifact_tag"]
        )
        @Suppress("UNCHECKED_CAST")
        val missingDimensions = wire["governance_missing_dimensions"] as List<String>
        assertTrue(missingDimensions.contains(DelegatedRuntimeGovernanceDimension.RESULT_CONVERGENCE_REGRESSION.wireValue))
        @Suppress("UNCHECKED_CAST")
        val dimensionStates = wire["governance_dimension_states"] as Map<String, String>
        assertEquals(
            DelegatedRuntimeGovernanceSnapshot.DimensionStatus.COMPLIANT.wireValue,
            dimensionStates[DelegatedRuntimeGovernanceDimension.TRUTH_ALIGNMENT_REGRESSION.wireValue]
        )
    }
}

package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-12 (Android) — final governance-alignment verification for lifecycle/reporting semantics.
 *
 * Cross-scenario verification focused on canonicalization-friendly behavior under active,
 * interrupted, degraded, partial, delayed, timeout, fallback, recovery, and terminal paths.
 */
class Pr12AndroidGovernanceAlignmentVerificationTest {

    private data class GovernanceScenario(
        val name: String,
        val phase: AndroidExecutionLifecycleContract.ExecutionLifecyclePhase,
        val priorPhaseWasInterruption: Boolean = false,
        val expectedStateSemantic: AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass,
        val expectedDegradedSemantic: AndroidCanonicalRuntimeTruthContract.DegradedConditionClass,
        val expectedResultSemantic: AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass,
        val expectedResultRule: ExecutionUplinkDiscipline.ResultUplinkRule,
        val expectedStateRule: ExecutionUplinkDiscipline.StateUplinkRule,
        val carrierRuntimeState: String? = null,
        val reconnectRecoveryState: String? = null,
        val executionModeState: String? = null,
        val executionBusy: Boolean? = null,
        val plannerFallbackTier: String? = null,
        val groundingFallbackTier: String? = null,
        val degradedReasons: List<String> = emptyList(),
        val meshConstrainedReasons: List<String> = emptyList(),
        val plannerReady: Boolean? = null,
        val groundingReady: Boolean? = null,
        val offlineQueueDepth: Int? = null
    )

    @Test
    fun `governance scenario matrix preserves lifecycle reporting and uplink alignment`() {
        val scenarios = listOf(
            GovernanceScenario(
                name = "capability-idle",
                phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE,
                expectedStateSemantic = AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.CAPABILITY,
                expectedDegradedSemantic = AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.NOMINAL,
                expectedResultSemantic = AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL,
                expectedResultRule = ExecutionUplinkDiscipline.ResultUplinkRule.PROHIBITED,
                expectedStateRule = ExecutionUplinkDiscipline.StateUplinkRule.OPTIONAL
            ),
            GovernanceScenario(
                name = "active-runtime",
                phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE,
                expectedStateSemantic = AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME,
                expectedDegradedSemantic = AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.NOMINAL,
                expectedResultSemantic = AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL,
                expectedResultRule = ExecutionUplinkDiscipline.ResultUplinkRule.PROHIBITED,
                expectedStateRule = ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED,
                carrierRuntimeState = "active",
                executionModeState = "cross_device_active",
                executionBusy = true
            ),
            GovernanceScenario(
                name = "degraded-fallback",
                phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED,
                expectedStateSemantic = AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME,
                expectedDegradedSemantic = AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.DEGRADED,
                expectedResultSemantic = AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL,
                expectedResultRule = ExecutionUplinkDiscipline.ResultUplinkRule.PROHIBITED,
                expectedStateRule = ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED,
                executionModeState = "cross_device_degraded",
                executionBusy = true,
                plannerFallbackTier = AndroidExecutionLifecycleContract.FALLBACK_TIER_DEGRADED_PLANNER,
                degradedReasons = listOf("planner_unavailable")
            ),
            GovernanceScenario(
                name = "partial-completion-surface",
                phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVATING,
                expectedStateSemantic = AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.DERIVED_LOCAL,
                expectedDegradedSemantic = AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.PARTIAL,
                expectedResultSemantic = AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL,
                expectedResultRule = ExecutionUplinkDiscipline.ResultUplinkRule.PROHIBITED,
                expectedStateRule = ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED,
                plannerFallbackTier = "tier-1",
                plannerReady = true,
                groundingReady = false
            ),
            GovernanceScenario(
                name = "delayed-reporting",
                phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.PENDING,
                expectedStateSemantic = AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.CAPABILITY,
                expectedDegradedSemantic = AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.DELAYED,
                expectedResultSemantic = AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL,
                expectedResultRule = ExecutionUplinkDiscipline.ResultUplinkRule.PROHIBITED,
                expectedStateRule = ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED,
                offlineQueueDepth = 3
            ),
            GovernanceScenario(
                name = "interrupted",
                phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED,
                expectedStateSemantic = AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME,
                expectedDegradedSemantic = AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.NOMINAL,
                expectedResultSemantic = AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_INTERRUPTION,
                expectedResultRule = ExecutionUplinkDiscipline.ResultUplinkRule.REQUIRED,
                expectedStateRule = ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED,
                executionModeState = "cross_device_active",
                executionBusy = true
            ),
            GovernanceScenario(
                name = "recovered-completion",
                phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED,
                priorPhaseWasInterruption = true,
                expectedStateSemantic = AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.CAPABILITY,
                expectedDegradedSemantic = AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.RECOVERED,
                expectedResultSemantic = AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_RECOVERY,
                expectedResultRule = ExecutionUplinkDiscipline.ResultUplinkRule.REQUIRED,
                expectedStateRule = ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED,
                reconnectRecoveryState = "recovered"
            ),
            GovernanceScenario(
                name = "terminal-timeout",
                phase = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TIMED_OUT,
                expectedStateSemantic = AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.TERMINAL_REPORTING,
                expectedDegradedSemantic = AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.DELAYED,
                expectedResultSemantic = AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL,
                expectedResultRule = ExecutionUplinkDiscipline.ResultUplinkRule.REQUIRED,
                expectedStateRule = ExecutionUplinkDiscipline.StateUplinkRule.REQUIRED,
                carrierRuntimeState = "failed",
                offlineQueueDepth = 1
            )
        )

        scenarios.forEach { scenario ->
            val stateSemantic = AndroidCanonicalRuntimeTruthContract.classifySnapshot(
                carrierRuntimeState = scenario.carrierRuntimeState,
                reconnectRecoveryState = scenario.reconnectRecoveryState,
                executionModeState = scenario.executionModeState,
                executionBusy = scenario.executionBusy,
                plannerFallbackTier = scenario.plannerFallbackTier,
                groundingFallbackTier = scenario.groundingFallbackTier
            )
            assertEquals("${scenario.name}: state semantic", scenario.expectedStateSemantic, stateSemantic)

            val degradedSemantic = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
                reconnectRecoveryState = scenario.reconnectRecoveryState,
                degradedReasons = scenario.degradedReasons,
                executionModeState = scenario.executionModeState,
                plannerFallbackTier = scenario.plannerFallbackTier,
                groundingFallbackTier = scenario.groundingFallbackTier,
                meshConstrainedReasons = scenario.meshConstrainedReasons,
                plannerReady = scenario.plannerReady,
                groundingReady = scenario.groundingReady,
                offlineQueueDepth = scenario.offlineQueueDepth
            )
            assertEquals("${scenario.name}: degraded semantic", scenario.expectedDegradedSemantic, degradedSemantic)

            val resultSemantic = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
                phase = scenario.phase,
                priorPhaseWasInterruption = scenario.priorPhaseWasInterruption
            )
            assertEquals("${scenario.name}: result semantic", scenario.expectedResultSemantic, resultSemantic)

            val uplinkDecision = ExecutionUplinkDiscipline.classify(scenario.phase)
            assertEquals("${scenario.name}: result rule", scenario.expectedResultRule, uplinkDecision.resultUplinkRule)
            assertEquals("${scenario.name}: state rule", scenario.expectedStateRule, uplinkDecision.stateUplinkRule)
        }
    }

    @Test
    fun `interruption retry recovery lifecycle remains valid and semantically distinct`() {
        val P = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase
        assertTrue(AndroidExecutionLifecycleContract.isValidTransition(P.ACTIVE, P.INTERRUPTED))
        assertTrue(AndroidExecutionLifecycleContract.isValidTransition(P.INTERRUPTED, P.RETRYING))
        assertTrue(AndroidExecutionLifecycleContract.isValidTransition(P.RETRYING, P.ACTIVATING))
        assertTrue(AndroidExecutionLifecycleContract.isValidTransition(P.ACTIVATING, P.ACTIVE))
        assertTrue(AndroidExecutionLifecycleContract.isValidTransition(P.ACTIVE, P.COMPLETED))

        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_INTERRUPTION,
            AndroidCanonicalRuntimeTruthContract.classifyResultUplink(P.INTERRUPTED)
        )
        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_RECOVERY,
            AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
                P.COMPLETED,
                priorPhaseWasInterruption = true
            )
        )
    }

    @Test
    fun `mission terminal observation and reported result semantics stay aligned for interruption recovery timeout`() {
        val interruption = AndroidMissionCompletionSemanticsContract.classifyLocalTerminalOutcome(
            phase = DeviceExecutionEventPayload.PHASE_CANCELLED,
            interruptionReason = AndroidExecutionLifecycleContract.INTERRUPTION_CAUSE_WS_DISCONNECT
        )
        val recovery = AndroidMissionCompletionSemanticsContract.classifyLocalTerminalOutcome(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            details = "recover after retry"
        )
        val timeout = AndroidMissionCompletionSemanticsContract.classifyLocalTerminalOutcome(
            phase = DeviceExecutionEventPayload.PHASE_FAILED,
            status = "timeout"
        )

        assertEquals(AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION, interruption)
        assertEquals(AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.RECOVERY, recovery)
        assertEquals(AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.TIMEOUT, timeout)

        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_INTERRUPTION,
            AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(interruption)
        )
        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_RECOVERY,
            AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(recovery)
        )
        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL,
            AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(timeout)
        )
    }

    @Test
    fun `payload semantic fields remain deterministic and canonicalization-friendly`() {
        val snapshotSemantic = AndroidCanonicalRuntimeTruthContract.classifySnapshot(
            carrierRuntimeState = "active",
            executionModeState = "cross_device_degraded",
            executionBusy = true
        )
        val degradedSemantic = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            degradedReasons = listOf("network_jitter"),
            executionModeState = "cross_device_degraded"
        )
        val eventResultSemantic = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED
        )

        val snapshotA = DeviceStateSnapshotPayload(
            device_id = "device-1",
            llama_cpp_available = true,
            ncnn_available = true,
            active_runtime_type = "CENTER",
            model_ready = true,
            accessibility_ready = true,
            overlay_ready = true,
            local_loop_ready = true,
            model_id = "m1",
            runtime_type = "rt",
            checksum_ok = true,
            mobilevlm_present = false,
            mobilevlm_checksum_ok = false,
            seeclick_present = false,
            pending_first_download = false,
            warmup_result = "ok",
            offline_queue_depth = 0,
            current_fallback_tier = null,
            reported_state_semantic_class = snapshotSemantic.wireValue,
            degraded_condition_class = degradedSemantic.wireValue,
            local_observation_basis = AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.LIVE_RUNTIME.wireValue
        )
        val snapshotB = snapshotA.copy()
        assertEquals(snapshotA, snapshotB)
        assertTrue(snapshotA.reported_state_semantic_class in AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ALL_WIRE_VALUES)
        assertTrue(snapshotA.degraded_condition_class in AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.ALL_WIRE_VALUES)

        val eventPayload = DeviceExecutionEventPayload(
            flow_id = "flow-1",
            task_id = "task-1",
            phase = DeviceExecutionEventPayload.PHASE_CANCELLED,
            reported_state_semantic_class = AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME.wireValue,
            result_uplink_semantic_class = eventResultSemantic.wireValue,
            terminal_outcome_kind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION.wireValue
        )
        assertTrue(eventPayload.result_uplink_semantic_class in AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.ALL_WIRE_VALUES)
        assertEquals(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION.wireValue,
            eventPayload.terminal_outcome_kind
        )
    }
}

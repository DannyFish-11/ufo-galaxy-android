package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PR-6 (Android) — Unit tests for [AndroidFlowAwareResultConvergenceParticipant],
 * [FlowAwareResultConvergenceDecision], and related result convergence contracts.
 *
 * ## Test matrix
 *
 * ### AndroidFlowAwareResultConvergenceParticipant — constants
 *  - DECISION_EMIT_PARTIAL_FOR_FLOW wire value is "emit_partial_for_flow"
 *  - DECISION_EMIT_FINAL_FOR_FLOW wire value is "emit_final_for_flow"
 *  - DECISION_BIND_PARALLEL_RESULT_TO_PARENT_FLOW wire value is "bind_parallel_result_to_parent_flow"
 *  - DECISION_SUPPRESS_DUPLICATE_RESULT_EMIT wire value is "suppress_duplicate_result_emit"
 *  - DECISION_SUPPRESS_LATE_PARTIAL_AFTER_FINAL wire value is "suppress_late_partial_after_final"
 *  - DECISION_HOLD_RESULT_FOR_CONVERGENCE_ALIGNMENT wire value is "hold_result_for_convergence_alignment"
 *  - All six decision constants are distinct
 *  - INTEGRATION_RECEIVER is "DelegatedRuntimeReceiver"
 *  - INTEGRATION_UNIT is "DelegatedRuntimeUnit"
 *  - INTEGRATION_ACTIVATION_RECORD is "DelegatedActivationRecord"
 *  - INTEGRATION_PIPELINE is "AutonomousExecutionPipeline"
 *  - INTEGRATION_LOOP_CONTROLLER is "LoopController"
 *  - INTEGRATION_TAKEOVER_EXECUTOR is "DelegatedTakeoverExecutor"
 *  - INTEGRATION_COLLABORATION_AGENT is "LocalCollaborationAgent"
 *  - INTRODUCED_PR is 6
 *  - DESCRIPTION is non-blank
 *
 * ### FlowResultKind — wire values
 *  - PARTIAL wireValue is "partial"
 *  - FINAL wireValue is "final"
 *  - FAILURE_TERMINAL wireValue is "failure_terminal"
 *  - CANCEL_TERMINAL wireValue is "cancel_terminal"
 *  - PARALLEL_SUB_RESULT wireValue is "parallel_sub_result"
 *  - REPLAYED_RESENT wireValue is "replayed_resent"
 *  - All six FlowResultKind wire values are distinct
 *
 * ### classifyResultKind — kind to class mapping
 *  - "partial" → PARTIAL
 *  - "final" → FINAL
 *  - "failure_terminal" → FAILURE_TERMINAL
 *  - "cancel_terminal" → CANCEL_TERMINAL
 *  - "parallel_sub_result" → PARALLEL_SUB_RESULT
 *  - "replayed_resent" → REPLAYED_RESENT
 *  - unknown kind falls back to PARTIAL
 *
 * ### evaluateResultEmit — EmitPartialForFlow (no suppression)
 *  - Returns EmitPartialForFlow for PARTIAL kind when flow not final and gate open
 *  - EmitPartialForFlow.semanticTag is DECISION_EMIT_PARTIAL_FOR_FLOW
 *  - EmitPartialForFlow.flowId matches context flowId
 *  - EmitPartialForFlow.resultKey matches context resultKey
 *
 * ### evaluateResultEmit — EmitFinalForFlow (no suppression)
 *  - Returns EmitFinalForFlow for FINAL kind
 *  - EmitFinalForFlow.semanticTag is DECISION_EMIT_FINAL_FOR_FLOW
 *  - EmitFinalForFlow.flowId matches context flowId
 *  - EmitFinalForFlow.resultKey matches context resultKey
 *  - EmitFinalForFlow.resultKind is FINAL
 *  - Returns EmitFinalForFlow for FAILURE_TERMINAL kind
 *  - EmitFinalForFlow.resultKind is FAILURE_TERMINAL for failure terminal
 *  - Returns EmitFinalForFlow for CANCEL_TERMINAL kind
 *  - EmitFinalForFlow.resultKind is CANCEL_TERMINAL for cancel terminal
 *  - Returns EmitFinalForFlow for REPLAYED_RESENT kind
 *  - EmitFinalForFlow.resultKind is REPLAYED_RESENT for replayed resent
 *
 * ### evaluateResultEmit — BindParallelResultToParentFlow
 *  - Returns BindParallelResultToParentFlow for PARALLEL_SUB_RESULT with all binding fields
 *  - BindParallelResultToParentFlow.semanticTag is DECISION_BIND_PARALLEL_RESULT_TO_PARENT_FLOW
 *  - BindParallelResultToParentFlow.flowId matches context flowId
 *  - BindParallelResultToParentFlow.resultKey matches context resultKey
 *  - BindParallelResultToParentFlow.groupId matches context groupId
 *  - BindParallelResultToParentFlow.subtaskIndex matches context subtaskIndex
 *  - BindParallelResultToParentFlow.parentFlowId matches context parentFlowId
 *  - Returns EmitFinalForFlow for PARALLEL_SUB_RESULT when groupId is null
 *  - Returns EmitFinalForFlow for PARALLEL_SUB_RESULT when subtaskIndex is null
 *  - Returns EmitFinalForFlow for PARALLEL_SUB_RESULT when parentFlowId is null
 *
 * ### evaluateResultEmit — SuppressDuplicateResultEmit
 *  - Returns SuppressDuplicateResultEmit after markResultEmitted
 *  - SuppressDuplicateResultEmit.semanticTag is DECISION_SUPPRESS_DUPLICATE_RESULT_EMIT
 *  - SuppressDuplicateResultEmit.flowId matches context flowId
 *  - SuppressDuplicateResultEmit.resultKey matches the previously emitted key
 *  - Duplicate suppression takes priority over late-partial suppression
 *  - Duplicate suppression takes priority over convergence gate hold
 *  - Duplicate suppression takes priority over partial emit
 *  - Duplicate suppression takes priority over final emit
 *  - Different resultKey is not suppressed after marking another key emitted
 *
 * ### evaluateResultEmit — SuppressLatePartialAfterFinal
 *  - Returns SuppressLatePartialAfterFinal for PARTIAL after markFlowFinal
 *  - SuppressLatePartialAfterFinal.semanticTag is DECISION_SUPPRESS_LATE_PARTIAL_AFTER_FINAL
 *  - SuppressLatePartialAfterFinal.flowId matches context flowId
 *  - SuppressLatePartialAfterFinal.resultKey matches context resultKey
 *  - Late partial suppression takes priority over convergence gate hold
 *  - FINAL result kind is not suppressed after markFlowFinal (final can re-emit)
 *  - Different flowId is not suppressed after marking another flow final
 *
 * ### evaluateResultEmit — HoldResultForConvergenceAlignment
 *  - Returns HoldResultForConvergenceAlignment after closeConvergenceGate
 *  - HoldResultForConvergenceAlignment.semanticTag is DECISION_HOLD_RESULT_FOR_CONVERGENCE_ALIGNMENT
 *  - HoldResultForConvergenceAlignment.flowId matches the gated flow
 *  - After openConvergenceGate, evaluateResultEmit returns normal decision
 *  - Convergence gate does not affect PARTIAL after markFlowFinal (late partial suppressed first)
 *  - Different flowId is not held after gating another flow
 *
 * ### Finalized flow registry
 *  - isFlowFinal is false initially
 *  - isFlowFinal is true after markFlowFinal
 *  - isFlowFinal is false after clearFlowFinal
 *  - flowFinalResultKey is null when not final
 *  - flowFinalResultKey returns the registered key after markFlowFinal
 *  - finalizedFlowCount is 0 initially
 *  - finalizedFlowCount increments with markFlowFinal
 *  - finalizedFlowCount decrements with clearFlowFinal
 *  - clearAllFinalizedFlows resets count to 0
 *  - Multiple distinct flowIds can all be marked final simultaneously
 *
 * ### Emitted result key registry
 *  - isResultEmitted is false initially
 *  - isResultEmitted is true after markResultEmitted
 *  - emittedResultCount is 0 initially
 *  - emittedResultCount increments with markResultEmitted
 *  - clearAllEmittedResults resets count to 0
 *  - Multiple distinct result keys can all be marked emitted simultaneously
 *
 * ### Convergence gate
 *  - isConvergenceGated is false initially
 *  - isConvergenceGated is true after closeConvergenceGate
 *  - isConvergenceGated is false after openConvergenceGate
 *  - convergenceGatedFlowCount is 0 initially
 *  - convergenceGatedFlowCount increments with closeConvergenceGate
 *  - convergenceGatedFlowCount decrements with openConvergenceGate
 *  - clearAllConvergenceGates resets count to 0
 *  - Multiple distinct flowIds can all be gated simultaneously
 *
 * ### FlowAwareResultConvergenceDecision — semanticTag values
 *  - EmitPartialForFlow.semanticTag matches DECISION_EMIT_PARTIAL_FOR_FLOW
 *  - EmitFinalForFlow.semanticTag matches DECISION_EMIT_FINAL_FOR_FLOW
 *  - BindParallelResultToParentFlow.semanticTag matches DECISION_BIND_PARALLEL_RESULT_TO_PARENT_FLOW
 *  - SuppressDuplicateResultEmit.semanticTag matches DECISION_SUPPRESS_DUPLICATE_RESULT_EMIT
 *  - SuppressLatePartialAfterFinal.semanticTag matches DECISION_SUPPRESS_LATE_PARTIAL_AFTER_FINAL
 *  - HoldResultForConvergenceAlignment.semanticTag matches DECISION_HOLD_RESULT_FOR_CONVERGENCE_ALIGNMENT
 *  - All six semanticTag values are distinct
 */
class Pr6AndroidFlowAwareResultConvergenceTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private lateinit var participant: AndroidFlowAwareResultConvergenceParticipant

    @Before
    fun setUp() {
        participant = AndroidFlowAwareResultConvergenceParticipant()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun context(
        flowId: String = "flow-1",
        resultKind: AndroidFlowAwareResultConvergenceParticipant.FlowResultKind =
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.PARTIAL,
        resultKey: String = "rk-1",
        groupId: String? = null,
        subtaskIndex: Int? = null,
        parentFlowId: String? = null,
        reason: String = ""
    ) = AndroidFlowAwareResultConvergenceParticipant.ResultEmitContext(
        flowId = flowId,
        resultKind = resultKind,
        resultKey = resultKey,
        groupId = groupId,
        subtaskIndex = subtaskIndex,
        parentFlowId = parentFlowId,
        reason = reason
    )

    private fun partialContext(flowId: String = "flow-1", resultKey: String = "rk-partial") =
        context(
            flowId = flowId,
            resultKind = AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.PARTIAL,
            resultKey = resultKey
        )

    private fun finalContext(flowId: String = "flow-1", resultKey: String = "rk-final") =
        context(
            flowId = flowId,
            resultKind = AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.FINAL,
            resultKey = resultKey
        )

    private fun failureContext(flowId: String = "flow-1", resultKey: String = "rk-failure") =
        context(
            flowId = flowId,
            resultKind = AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.FAILURE_TERMINAL,
            resultKey = resultKey
        )

    private fun cancelContext(flowId: String = "flow-1", resultKey: String = "rk-cancel") =
        context(
            flowId = flowId,
            resultKind = AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.CANCEL_TERMINAL,
            resultKey = resultKey
        )

    private fun replayedContext(flowId: String = "flow-1", resultKey: String = "rk-replayed") =
        context(
            flowId = flowId,
            resultKind = AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.REPLAYED_RESENT,
            resultKey = resultKey
        )

    private fun parallelContext(
        flowId: String = "flow-sub-1",
        resultKey: String = "rk-parallel",
        groupId: String? = "grp-1",
        subtaskIndex: Int? = 0,
        parentFlowId: String? = "flow-parent-1"
    ) = context(
        flowId = flowId,
        resultKind = AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.PARALLEL_SUB_RESULT,
        resultKey = resultKey,
        groupId = groupId,
        subtaskIndex = subtaskIndex,
        parentFlowId = parentFlowId
    )

    // ══════════════════════════════════════════════════════════════════════════
    // Constants
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DECISION_EMIT_PARTIAL_FOR_FLOW wire value is emit_partial_for_flow`() {
        assertEquals(
            "emit_partial_for_flow",
            AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_PARTIAL_FOR_FLOW
        )
    }

    @Test
    fun `DECISION_EMIT_FINAL_FOR_FLOW wire value is emit_final_for_flow`() {
        assertEquals(
            "emit_final_for_flow",
            AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_FINAL_FOR_FLOW
        )
    }

    @Test
    fun `DECISION_BIND_PARALLEL_RESULT_TO_PARENT_FLOW wire value is bind_parallel_result_to_parent_flow`() {
        assertEquals(
            "bind_parallel_result_to_parent_flow",
            AndroidFlowAwareResultConvergenceParticipant.DECISION_BIND_PARALLEL_RESULT_TO_PARENT_FLOW
        )
    }

    @Test
    fun `DECISION_SUPPRESS_DUPLICATE_RESULT_EMIT wire value is suppress_duplicate_result_emit`() {
        assertEquals(
            "suppress_duplicate_result_emit",
            AndroidFlowAwareResultConvergenceParticipant.DECISION_SUPPRESS_DUPLICATE_RESULT_EMIT
        )
    }

    @Test
    fun `DECISION_SUPPRESS_LATE_PARTIAL_AFTER_FINAL wire value is suppress_late_partial_after_final`() {
        assertEquals(
            "suppress_late_partial_after_final",
            AndroidFlowAwareResultConvergenceParticipant.DECISION_SUPPRESS_LATE_PARTIAL_AFTER_FINAL
        )
    }

    @Test
    fun `DECISION_HOLD_RESULT_FOR_CONVERGENCE_ALIGNMENT wire value is hold_result_for_convergence_alignment`() {
        assertEquals(
            "hold_result_for_convergence_alignment",
            AndroidFlowAwareResultConvergenceParticipant.DECISION_HOLD_RESULT_FOR_CONVERGENCE_ALIGNMENT
        )
    }

    @Test
    fun `All six decision constants are distinct`() {
        val constants = listOf(
            AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_PARTIAL_FOR_FLOW,
            AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_FINAL_FOR_FLOW,
            AndroidFlowAwareResultConvergenceParticipant.DECISION_BIND_PARALLEL_RESULT_TO_PARENT_FLOW,
            AndroidFlowAwareResultConvergenceParticipant.DECISION_SUPPRESS_DUPLICATE_RESULT_EMIT,
            AndroidFlowAwareResultConvergenceParticipant.DECISION_SUPPRESS_LATE_PARTIAL_AFTER_FINAL,
            AndroidFlowAwareResultConvergenceParticipant.DECISION_HOLD_RESULT_FOR_CONVERGENCE_ALIGNMENT
        )
        assertEquals("All six decision constants must be distinct", constants.size, constants.toSet().size)
    }

    @Test
    fun `INTEGRATION_RECEIVER is DelegatedRuntimeReceiver`() {
        assertEquals(
            "DelegatedRuntimeReceiver",
            AndroidFlowAwareResultConvergenceParticipant.INTEGRATION_RECEIVER
        )
    }

    @Test
    fun `INTEGRATION_UNIT is DelegatedRuntimeUnit`() {
        assertEquals(
            "DelegatedRuntimeUnit",
            AndroidFlowAwareResultConvergenceParticipant.INTEGRATION_UNIT
        )
    }

    @Test
    fun `INTEGRATION_ACTIVATION_RECORD is DelegatedActivationRecord`() {
        assertEquals(
            "DelegatedActivationRecord",
            AndroidFlowAwareResultConvergenceParticipant.INTEGRATION_ACTIVATION_RECORD
        )
    }

    @Test
    fun `INTEGRATION_PIPELINE is AutonomousExecutionPipeline`() {
        assertEquals(
            "AutonomousExecutionPipeline",
            AndroidFlowAwareResultConvergenceParticipant.INTEGRATION_PIPELINE
        )
    }

    @Test
    fun `INTEGRATION_LOOP_CONTROLLER is LoopController`() {
        assertEquals(
            "LoopController",
            AndroidFlowAwareResultConvergenceParticipant.INTEGRATION_LOOP_CONTROLLER
        )
    }

    @Test
    fun `INTEGRATION_TAKEOVER_EXECUTOR is DelegatedTakeoverExecutor`() {
        assertEquals(
            "DelegatedTakeoverExecutor",
            AndroidFlowAwareResultConvergenceParticipant.INTEGRATION_TAKEOVER_EXECUTOR
        )
    }

    @Test
    fun `INTEGRATION_COLLABORATION_AGENT is LocalCollaborationAgent`() {
        assertEquals(
            "LocalCollaborationAgent",
            AndroidFlowAwareResultConvergenceParticipant.INTEGRATION_COLLABORATION_AGENT
        )
    }

    @Test
    fun `INTRODUCED_PR is 6`() {
        assertEquals(6, AndroidFlowAwareResultConvergenceParticipant.INTRODUCED_PR)
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(
            "DESCRIPTION must be non-blank",
            AndroidFlowAwareResultConvergenceParticipant.DESCRIPTION.isNotBlank()
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FlowResultKind wire values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `PARTIAL wireValue is partial`() {
        assertEquals(
            "partial",
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.PARTIAL.wireValue
        )
    }

    @Test
    fun `FINAL wireValue is final`() {
        assertEquals(
            "final",
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.FINAL.wireValue
        )
    }

    @Test
    fun `FAILURE_TERMINAL wireValue is failure_terminal`() {
        assertEquals(
            "failure_terminal",
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.FAILURE_TERMINAL.wireValue
        )
    }

    @Test
    fun `CANCEL_TERMINAL wireValue is cancel_terminal`() {
        assertEquals(
            "cancel_terminal",
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.CANCEL_TERMINAL.wireValue
        )
    }

    @Test
    fun `PARALLEL_SUB_RESULT wireValue is parallel_sub_result`() {
        assertEquals(
            "parallel_sub_result",
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.PARALLEL_SUB_RESULT.wireValue
        )
    }

    @Test
    fun `REPLAYED_RESENT wireValue is replayed_resent`() {
        assertEquals(
            "replayed_resent",
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.REPLAYED_RESENT.wireValue
        )
    }

    @Test
    fun `All six FlowResultKind wire values are distinct`() {
        val wireValues = AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.entries
            .map { it.wireValue }
        assertEquals(
            "All FlowResultKind wire values must be distinct",
            wireValues.size,
            wireValues.toSet().size
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // classifyResultKind
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `partial maps to PARTIAL`() {
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.PARTIAL,
            participant.classifyResultKind("partial")
        )
    }

    @Test
    fun `final maps to FINAL`() {
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.FINAL,
            participant.classifyResultKind("final")
        )
    }

    @Test
    fun `failure_terminal maps to FAILURE_TERMINAL`() {
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.FAILURE_TERMINAL,
            participant.classifyResultKind("failure_terminal")
        )
    }

    @Test
    fun `cancel_terminal maps to CANCEL_TERMINAL`() {
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.CANCEL_TERMINAL,
            participant.classifyResultKind("cancel_terminal")
        )
    }

    @Test
    fun `parallel_sub_result maps to PARALLEL_SUB_RESULT`() {
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.PARALLEL_SUB_RESULT,
            participant.classifyResultKind("parallel_sub_result")
        )
    }

    @Test
    fun `replayed_resent maps to REPLAYED_RESENT`() {
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.REPLAYED_RESENT,
            participant.classifyResultKind("replayed_resent")
        )
    }

    @Test
    fun `unknown kind falls back to PARTIAL`() {
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.PARTIAL,
            participant.classifyResultKind("unknown_future_kind")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateResultEmit — EmitPartialForFlow
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns EmitPartialForFlow for PARTIAL kind when flow not final and gate open`() {
        val decision = participant.evaluateResultEmit(partialContext())
        assertTrue(
            "Expected EmitPartialForFlow",
            decision is FlowAwareResultConvergenceDecision.EmitPartialForFlow
        )
    }

    @Test
    fun `EmitPartialForFlow semanticTag is DECISION_EMIT_PARTIAL_FOR_FLOW`() {
        val decision = participant.evaluateResultEmit(partialContext()) as
            FlowAwareResultConvergenceDecision.EmitPartialForFlow
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_PARTIAL_FOR_FLOW,
            decision.semanticTag
        )
    }

    @Test
    fun `EmitPartialForFlow flowId matches context flowId`() {
        val decision = participant.evaluateResultEmit(partialContext(flowId = "flow-abc")) as
            FlowAwareResultConvergenceDecision.EmitPartialForFlow
        assertEquals("flow-abc", decision.flowId)
    }

    @Test
    fun `EmitPartialForFlow resultKey matches context resultKey`() {
        val decision = participant.evaluateResultEmit(partialContext(resultKey = "rk-xyz")) as
            FlowAwareResultConvergenceDecision.EmitPartialForFlow
        assertEquals("rk-xyz", decision.resultKey)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateResultEmit — EmitFinalForFlow
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns EmitFinalForFlow for FINAL kind`() {
        val decision = participant.evaluateResultEmit(finalContext())
        assertTrue(
            "Expected EmitFinalForFlow",
            decision is FlowAwareResultConvergenceDecision.EmitFinalForFlow
        )
    }

    @Test
    fun `EmitFinalForFlow semanticTag is DECISION_EMIT_FINAL_FOR_FLOW`() {
        val decision = participant.evaluateResultEmit(finalContext()) as
            FlowAwareResultConvergenceDecision.EmitFinalForFlow
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_FINAL_FOR_FLOW,
            decision.semanticTag
        )
    }

    @Test
    fun `EmitFinalForFlow flowId matches context flowId`() {
        val decision = participant.evaluateResultEmit(finalContext(flowId = "flow-final")) as
            FlowAwareResultConvergenceDecision.EmitFinalForFlow
        assertEquals("flow-final", decision.flowId)
    }

    @Test
    fun `EmitFinalForFlow resultKey matches context resultKey`() {
        val decision = participant.evaluateResultEmit(finalContext(resultKey = "rk-final-1")) as
            FlowAwareResultConvergenceDecision.EmitFinalForFlow
        assertEquals("rk-final-1", decision.resultKey)
    }

    @Test
    fun `EmitFinalForFlow resultKind is FINAL`() {
        val decision = participant.evaluateResultEmit(finalContext()) as
            FlowAwareResultConvergenceDecision.EmitFinalForFlow
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.FINAL,
            decision.resultKind
        )
    }

    @Test
    fun `Returns EmitFinalForFlow for FAILURE_TERMINAL kind`() {
        val decision = participant.evaluateResultEmit(failureContext())
        assertTrue(
            "Expected EmitFinalForFlow for FAILURE_TERMINAL",
            decision is FlowAwareResultConvergenceDecision.EmitFinalForFlow
        )
    }

    @Test
    fun `EmitFinalForFlow resultKind is FAILURE_TERMINAL for failure terminal`() {
        val decision = participant.evaluateResultEmit(failureContext()) as
            FlowAwareResultConvergenceDecision.EmitFinalForFlow
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.FAILURE_TERMINAL,
            decision.resultKind
        )
    }

    @Test
    fun `Returns EmitFinalForFlow for CANCEL_TERMINAL kind`() {
        val decision = participant.evaluateResultEmit(cancelContext())
        assertTrue(
            "Expected EmitFinalForFlow for CANCEL_TERMINAL",
            decision is FlowAwareResultConvergenceDecision.EmitFinalForFlow
        )
    }

    @Test
    fun `EmitFinalForFlow resultKind is CANCEL_TERMINAL for cancel terminal`() {
        val decision = participant.evaluateResultEmit(cancelContext()) as
            FlowAwareResultConvergenceDecision.EmitFinalForFlow
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.CANCEL_TERMINAL,
            decision.resultKind
        )
    }

    @Test
    fun `Returns EmitFinalForFlow for REPLAYED_RESENT kind`() {
        val decision = participant.evaluateResultEmit(replayedContext())
        assertTrue(
            "Expected EmitFinalForFlow for REPLAYED_RESENT",
            decision is FlowAwareResultConvergenceDecision.EmitFinalForFlow
        )
    }

    @Test
    fun `EmitFinalForFlow resultKind is REPLAYED_RESENT for replayed resent`() {
        val decision = participant.evaluateResultEmit(replayedContext()) as
            FlowAwareResultConvergenceDecision.EmitFinalForFlow
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.REPLAYED_RESENT,
            decision.resultKind
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateResultEmit — BindParallelResultToParentFlow
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns BindParallelResultToParentFlow for PARALLEL_SUB_RESULT with all binding fields`() {
        val decision = participant.evaluateResultEmit(parallelContext())
        assertTrue(
            "Expected BindParallelResultToParentFlow",
            decision is FlowAwareResultConvergenceDecision.BindParallelResultToParentFlow
        )
    }

    @Test
    fun `BindParallelResultToParentFlow semanticTag is DECISION_BIND_PARALLEL_RESULT_TO_PARENT_FLOW`() {
        val decision = participant.evaluateResultEmit(parallelContext()) as
            FlowAwareResultConvergenceDecision.BindParallelResultToParentFlow
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.DECISION_BIND_PARALLEL_RESULT_TO_PARENT_FLOW,
            decision.semanticTag
        )
    }

    @Test
    fun `BindParallelResultToParentFlow flowId matches context flowId`() {
        val decision = participant.evaluateResultEmit(parallelContext(flowId = "flow-sub-xyz")) as
            FlowAwareResultConvergenceDecision.BindParallelResultToParentFlow
        assertEquals("flow-sub-xyz", decision.flowId)
    }

    @Test
    fun `BindParallelResultToParentFlow resultKey matches context resultKey`() {
        val decision = participant.evaluateResultEmit(parallelContext(resultKey = "rk-par-1")) as
            FlowAwareResultConvergenceDecision.BindParallelResultToParentFlow
        assertEquals("rk-par-1", decision.resultKey)
    }

    @Test
    fun `BindParallelResultToParentFlow groupId matches context groupId`() {
        val decision = participant.evaluateResultEmit(parallelContext(groupId = "grp-abc")) as
            FlowAwareResultConvergenceDecision.BindParallelResultToParentFlow
        assertEquals("grp-abc", decision.groupId)
    }

    @Test
    fun `BindParallelResultToParentFlow subtaskIndex matches context subtaskIndex`() {
        val decision = participant.evaluateResultEmit(parallelContext(subtaskIndex = 2)) as
            FlowAwareResultConvergenceDecision.BindParallelResultToParentFlow
        assertEquals(2, decision.subtaskIndex)
    }

    @Test
    fun `BindParallelResultToParentFlow parentFlowId matches context parentFlowId`() {
        val decision = participant.evaluateResultEmit(parallelContext(parentFlowId = "flow-parent-xyz")) as
            FlowAwareResultConvergenceDecision.BindParallelResultToParentFlow
        assertEquals("flow-parent-xyz", decision.parentFlowId)
    }

    @Test
    fun `Returns EmitFinalForFlow for PARALLEL_SUB_RESULT when groupId is null`() {
        val decision = participant.evaluateResultEmit(
            parallelContext(groupId = null)
        )
        assertTrue(
            "Expected EmitFinalForFlow when groupId is null",
            decision is FlowAwareResultConvergenceDecision.EmitFinalForFlow
        )
    }

    @Test
    fun `Returns EmitFinalForFlow for PARALLEL_SUB_RESULT when subtaskIndex is null`() {
        val decision = participant.evaluateResultEmit(
            parallelContext(subtaskIndex = null)
        )
        assertTrue(
            "Expected EmitFinalForFlow when subtaskIndex is null",
            decision is FlowAwareResultConvergenceDecision.EmitFinalForFlow
        )
    }

    @Test
    fun `Returns EmitFinalForFlow for PARALLEL_SUB_RESULT when parentFlowId is null`() {
        val decision = participant.evaluateResultEmit(
            parallelContext(parentFlowId = null)
        )
        assertTrue(
            "Expected EmitFinalForFlow when parentFlowId is null",
            decision is FlowAwareResultConvergenceDecision.EmitFinalForFlow
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateResultEmit — SuppressDuplicateResultEmit
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns SuppressDuplicateResultEmit after markResultEmitted`() {
        participant.markResultEmitted("rk-dup")
        val decision = participant.evaluateResultEmit(partialContext(resultKey = "rk-dup"))
        assertTrue(
            "Expected SuppressDuplicateResultEmit",
            decision is FlowAwareResultConvergenceDecision.SuppressDuplicateResultEmit
        )
    }

    @Test
    fun `SuppressDuplicateResultEmit semanticTag is DECISION_SUPPRESS_DUPLICATE_RESULT_EMIT`() {
        participant.markResultEmitted("rk-dup")
        val decision = participant.evaluateResultEmit(partialContext(resultKey = "rk-dup")) as
            FlowAwareResultConvergenceDecision.SuppressDuplicateResultEmit
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.DECISION_SUPPRESS_DUPLICATE_RESULT_EMIT,
            decision.semanticTag
        )
    }

    @Test
    fun `SuppressDuplicateResultEmit flowId matches context flowId`() {
        participant.markResultEmitted("rk-dup")
        val decision = participant.evaluateResultEmit(
            partialContext(flowId = "flow-dup", resultKey = "rk-dup")
        ) as FlowAwareResultConvergenceDecision.SuppressDuplicateResultEmit
        assertEquals("flow-dup", decision.flowId)
    }

    @Test
    fun `SuppressDuplicateResultEmit resultKey matches the previously emitted key`() {
        participant.markResultEmitted("rk-dup-xyz")
        val decision = participant.evaluateResultEmit(finalContext(resultKey = "rk-dup-xyz")) as
            FlowAwareResultConvergenceDecision.SuppressDuplicateResultEmit
        assertEquals("rk-dup-xyz", decision.resultKey)
    }

    @Test
    fun `Duplicate suppression takes priority over late-partial suppression`() {
        participant.markFlowFinal("flow-1", "rk-final")
        participant.markResultEmitted("rk-late-dup")
        val decision = participant.evaluateResultEmit(
            partialContext(flowId = "flow-1", resultKey = "rk-late-dup")
        )
        assertTrue(
            "Duplicate suppression must take priority over late-partial suppression",
            decision is FlowAwareResultConvergenceDecision.SuppressDuplicateResultEmit
        )
    }

    @Test
    fun `Duplicate suppression takes priority over convergence gate hold`() {
        participant.closeConvergenceGate("flow-1")
        participant.markResultEmitted("rk-gate-dup")
        val decision = participant.evaluateResultEmit(
            partialContext(flowId = "flow-1", resultKey = "rk-gate-dup")
        )
        assertTrue(
            "Duplicate suppression must take priority over convergence gate hold",
            decision is FlowAwareResultConvergenceDecision.SuppressDuplicateResultEmit
        )
    }

    @Test
    fun `Duplicate suppression takes priority over partial emit`() {
        participant.markResultEmitted("rk-partial-dup")
        val decision = participant.evaluateResultEmit(partialContext(resultKey = "rk-partial-dup"))
        assertTrue(
            "Duplicate suppression must take priority over partial emit",
            decision is FlowAwareResultConvergenceDecision.SuppressDuplicateResultEmit
        )
    }

    @Test
    fun `Duplicate suppression takes priority over final emit`() {
        participant.markResultEmitted("rk-final-dup")
        val decision = participant.evaluateResultEmit(finalContext(resultKey = "rk-final-dup"))
        assertTrue(
            "Duplicate suppression must take priority over final emit",
            decision is FlowAwareResultConvergenceDecision.SuppressDuplicateResultEmit
        )
    }

    @Test
    fun `Different resultKey is not suppressed after marking another key emitted`() {
        participant.markResultEmitted("rk-other")
        val decision = participant.evaluateResultEmit(partialContext(resultKey = "rk-new"))
        assertTrue(
            "A different resultKey must not be suppressed",
            decision is FlowAwareResultConvergenceDecision.EmitPartialForFlow
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateResultEmit — SuppressLatePartialAfterFinal
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns SuppressLatePartialAfterFinal for PARTIAL after markFlowFinal`() {
        participant.markFlowFinal("flow-1", "rk-final")
        val decision = participant.evaluateResultEmit(partialContext(flowId = "flow-1"))
        assertTrue(
            "Expected SuppressLatePartialAfterFinal",
            decision is FlowAwareResultConvergenceDecision.SuppressLatePartialAfterFinal
        )
    }

    @Test
    fun `SuppressLatePartialAfterFinal semanticTag is DECISION_SUPPRESS_LATE_PARTIAL_AFTER_FINAL`() {
        participant.markFlowFinal("flow-1", "rk-final")
        val decision = participant.evaluateResultEmit(partialContext(flowId = "flow-1")) as
            FlowAwareResultConvergenceDecision.SuppressLatePartialAfterFinal
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.DECISION_SUPPRESS_LATE_PARTIAL_AFTER_FINAL,
            decision.semanticTag
        )
    }

    @Test
    fun `SuppressLatePartialAfterFinal flowId matches context flowId`() {
        participant.markFlowFinal("flow-late", "rk-final")
        val decision = participant.evaluateResultEmit(
            partialContext(flowId = "flow-late", resultKey = "rk-new-partial")
        ) as FlowAwareResultConvergenceDecision.SuppressLatePartialAfterFinal
        assertEquals("flow-late", decision.flowId)
    }

    @Test
    fun `SuppressLatePartialAfterFinal resultKey matches context resultKey`() {
        participant.markFlowFinal("flow-1", "rk-final")
        val decision = participant.evaluateResultEmit(
            partialContext(flowId = "flow-1", resultKey = "rk-late-partial")
        ) as FlowAwareResultConvergenceDecision.SuppressLatePartialAfterFinal
        assertEquals("rk-late-partial", decision.resultKey)
    }

    @Test
    fun `Late partial suppression takes priority over convergence gate hold`() {
        participant.markFlowFinal("flow-1", "rk-final")
        participant.closeConvergenceGate("flow-1")
        val decision = participant.evaluateResultEmit(
            partialContext(flowId = "flow-1", resultKey = "rk-late-partial")
        )
        assertTrue(
            "Late partial suppression must take priority over convergence gate hold",
            decision is FlowAwareResultConvergenceDecision.SuppressLatePartialAfterFinal
        )
    }

    @Test
    fun `FINAL result kind is not suppressed after markFlowFinal`() {
        participant.markFlowFinal("flow-1", "rk-final")
        val decision = participant.evaluateResultEmit(finalContext(flowId = "flow-1", resultKey = "rk-final-2"))
        assertTrue(
            "FINAL result kind must not be suppressed by late-partial check",
            decision is FlowAwareResultConvergenceDecision.EmitFinalForFlow
        )
    }

    @Test
    fun `Different flowId is not suppressed after marking another flow final`() {
        participant.markFlowFinal("flow-other", "rk-final")
        val decision = participant.evaluateResultEmit(partialContext(flowId = "flow-new"))
        assertTrue(
            "A different flowId must not be suppressed by late-partial check",
            decision is FlowAwareResultConvergenceDecision.EmitPartialForFlow
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateResultEmit — HoldResultForConvergenceAlignment
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns HoldResultForConvergenceAlignment after closeConvergenceGate`() {
        participant.closeConvergenceGate("flow-1")
        val decision = participant.evaluateResultEmit(partialContext(flowId = "flow-1"))
        assertTrue(
            "Expected HoldResultForConvergenceAlignment",
            decision is FlowAwareResultConvergenceDecision.HoldResultForConvergenceAlignment
        )
    }

    @Test
    fun `HoldResultForConvergenceAlignment semanticTag is DECISION_HOLD_RESULT_FOR_CONVERGENCE_ALIGNMENT`() {
        participant.closeConvergenceGate("flow-1")
        val decision = participant.evaluateResultEmit(partialContext(flowId = "flow-1")) as
            FlowAwareResultConvergenceDecision.HoldResultForConvergenceAlignment
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.DECISION_HOLD_RESULT_FOR_CONVERGENCE_ALIGNMENT,
            decision.semanticTag
        )
    }

    @Test
    fun `HoldResultForConvergenceAlignment flowId matches the gated flow`() {
        participant.closeConvergenceGate("flow-gated")
        val decision = participant.evaluateResultEmit(finalContext(flowId = "flow-gated")) as
            FlowAwareResultConvergenceDecision.HoldResultForConvergenceAlignment
        assertEquals("flow-gated", decision.flowId)
    }

    @Test
    fun `After openConvergenceGate evaluateResultEmit returns normal decision`() {
        participant.closeConvergenceGate("flow-1")
        participant.openConvergenceGate("flow-1")
        val decision = participant.evaluateResultEmit(partialContext(flowId = "flow-1"))
        assertTrue(
            "After opening gate, normal decision expected",
            decision is FlowAwareResultConvergenceDecision.EmitPartialForFlow
        )
    }

    @Test
    fun `Convergence gate does not affect PARTIAL after markFlowFinal`() {
        participant.markFlowFinal("flow-1", "rk-final")
        participant.closeConvergenceGate("flow-1")
        val decision = participant.evaluateResultEmit(
            partialContext(flowId = "flow-1", resultKey = "rk-late-partial")
        )
        // Late partial suppression is checked before convergence gate
        assertTrue(
            "Late partial suppression must precede convergence gate check",
            decision is FlowAwareResultConvergenceDecision.SuppressLatePartialAfterFinal
        )
    }

    @Test
    fun `Different flowId is not held after gating another flow`() {
        participant.closeConvergenceGate("flow-gated")
        val decision = participant.evaluateResultEmit(partialContext(flowId = "flow-free"))
        assertTrue(
            "A different flowId must not be held",
            decision is FlowAwareResultConvergenceDecision.EmitPartialForFlow
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Finalized flow registry
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isFlowFinal is false initially`() {
        assertFalse("isFlowFinal must be false initially", participant.isFlowFinal("flow-1"))
    }

    @Test
    fun `isFlowFinal is true after markFlowFinal`() {
        participant.markFlowFinal("flow-1", "rk-final")
        assertTrue("isFlowFinal must be true after markFlowFinal", participant.isFlowFinal("flow-1"))
    }

    @Test
    fun `isFlowFinal is false after clearFlowFinal`() {
        participant.markFlowFinal("flow-1", "rk-final")
        participant.clearFlowFinal("flow-1")
        assertFalse("isFlowFinal must be false after clearFlowFinal", participant.isFlowFinal("flow-1"))
    }

    @Test
    fun `flowFinalResultKey is null when not final`() {
        assertNull("flowFinalResultKey must be null when not final", participant.flowFinalResultKey("flow-1"))
    }

    @Test
    fun `flowFinalResultKey returns the registered key after markFlowFinal`() {
        participant.markFlowFinal("flow-1", "rk-final-abc")
        assertEquals("rk-final-abc", participant.flowFinalResultKey("flow-1"))
    }

    @Test
    fun `finalizedFlowCount is 0 initially`() {
        assertEquals(0, participant.finalizedFlowCount)
    }

    @Test
    fun `finalizedFlowCount increments with markFlowFinal`() {
        participant.markFlowFinal("flow-1", "rk-1")
        participant.markFlowFinal("flow-2", "rk-2")
        assertEquals(2, participant.finalizedFlowCount)
    }

    @Test
    fun `finalizedFlowCount decrements with clearFlowFinal`() {
        participant.markFlowFinal("flow-1", "rk-1")
        participant.markFlowFinal("flow-2", "rk-2")
        participant.clearFlowFinal("flow-1")
        assertEquals(1, participant.finalizedFlowCount)
    }

    @Test
    fun `clearAllFinalizedFlows resets count to 0`() {
        participant.markFlowFinal("flow-1", "rk-1")
        participant.markFlowFinal("flow-2", "rk-2")
        participant.clearAllFinalizedFlows()
        assertEquals(0, participant.finalizedFlowCount)
    }

    @Test
    fun `Multiple distinct flowIds can all be marked final simultaneously`() {
        val ids = listOf("flow-a", "flow-b", "flow-c")
        ids.forEachIndexed { idx, id -> participant.markFlowFinal(id, "rk-$idx") }
        ids.forEach { id ->
            assertTrue("$id must be final", participant.isFlowFinal(id))
        }
        assertEquals(3, participant.finalizedFlowCount)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Emitted result key registry
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isResultEmitted is false initially`() {
        assertFalse("isResultEmitted must be false initially", participant.isResultEmitted("rk-new"))
    }

    @Test
    fun `isResultEmitted is true after markResultEmitted`() {
        participant.markResultEmitted("rk-abc")
        assertTrue("isResultEmitted must be true after markResultEmitted", participant.isResultEmitted("rk-abc"))
    }

    @Test
    fun `emittedResultCount is 0 initially`() {
        assertEquals(0, participant.emittedResultCount)
    }

    @Test
    fun `emittedResultCount increments with markResultEmitted`() {
        participant.markResultEmitted("rk-1")
        participant.markResultEmitted("rk-2")
        assertEquals(2, participant.emittedResultCount)
    }

    @Test
    fun `clearAllEmittedResults resets count to 0`() {
        participant.markResultEmitted("rk-1")
        participant.markResultEmitted("rk-2")
        participant.clearAllEmittedResults()
        assertEquals(0, participant.emittedResultCount)
    }

    @Test
    fun `Multiple distinct result keys can all be marked emitted simultaneously`() {
        val keys = listOf("rk-x", "rk-y", "rk-z")
        keys.forEach { participant.markResultEmitted(it) }
        keys.forEach { key ->
            assertTrue("$key must be marked emitted", participant.isResultEmitted(key))
        }
        assertEquals(3, participant.emittedResultCount)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Convergence gate
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isConvergenceGated is false initially`() {
        assertFalse("isConvergenceGated must be false initially", participant.isConvergenceGated("flow-1"))
    }

    @Test
    fun `isConvergenceGated is true after closeConvergenceGate`() {
        participant.closeConvergenceGate("flow-1")
        assertTrue("isConvergenceGated must be true after closeConvergenceGate", participant.isConvergenceGated("flow-1"))
    }

    @Test
    fun `isConvergenceGated is false after openConvergenceGate`() {
        participant.closeConvergenceGate("flow-1")
        participant.openConvergenceGate("flow-1")
        assertFalse("isConvergenceGated must be false after openConvergenceGate", participant.isConvergenceGated("flow-1"))
    }

    @Test
    fun `convergenceGatedFlowCount is 0 initially`() {
        assertEquals(0, participant.convergenceGatedFlowCount)
    }

    @Test
    fun `convergenceGatedFlowCount increments with closeConvergenceGate`() {
        participant.closeConvergenceGate("flow-1")
        participant.closeConvergenceGate("flow-2")
        assertEquals(2, participant.convergenceGatedFlowCount)
    }

    @Test
    fun `convergenceGatedFlowCount decrements with openConvergenceGate`() {
        participant.closeConvergenceGate("flow-1")
        participant.closeConvergenceGate("flow-2")
        participant.openConvergenceGate("flow-1")
        assertEquals(1, participant.convergenceGatedFlowCount)
    }

    @Test
    fun `clearAllConvergenceGates resets count to 0`() {
        participant.closeConvergenceGate("flow-1")
        participant.closeConvergenceGate("flow-2")
        participant.clearAllConvergenceGates()
        assertEquals(0, participant.convergenceGatedFlowCount)
    }

    @Test
    fun `Multiple distinct flowIds can all be gated simultaneously`() {
        val ids = listOf("flow-a", "flow-b", "flow-c")
        ids.forEach { participant.closeConvergenceGate(it) }
        ids.forEach { id ->
            assertTrue("$id must be gated", participant.isConvergenceGated(id))
        }
        assertEquals(3, participant.convergenceGatedFlowCount)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FlowAwareResultConvergenceDecision — semanticTag values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `EmitPartialForFlow semanticTag matches DECISION_EMIT_PARTIAL_FOR_FLOW`() {
        val decision = FlowAwareResultConvergenceDecision.EmitPartialForFlow("f1", "rk1")
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_PARTIAL_FOR_FLOW,
            decision.semanticTag
        )
    }

    @Test
    fun `EmitFinalForFlow semanticTag matches DECISION_EMIT_FINAL_FOR_FLOW`() {
        val decision = FlowAwareResultConvergenceDecision.EmitFinalForFlow(
            "f1", "rk1",
            AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.FINAL
        )
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_FINAL_FOR_FLOW,
            decision.semanticTag
        )
    }

    @Test
    fun `BindParallelResultToParentFlow semanticTag matches DECISION_BIND_PARALLEL_RESULT_TO_PARENT_FLOW`() {
        val decision = FlowAwareResultConvergenceDecision.BindParallelResultToParentFlow(
            "f1", "rk1", "grp-1", 0, "flow-parent"
        )
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.DECISION_BIND_PARALLEL_RESULT_TO_PARENT_FLOW,
            decision.semanticTag
        )
    }

    @Test
    fun `SuppressDuplicateResultEmit semanticTag matches DECISION_SUPPRESS_DUPLICATE_RESULT_EMIT`() {
        val decision = FlowAwareResultConvergenceDecision.SuppressDuplicateResultEmit("f1", "rk1")
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.DECISION_SUPPRESS_DUPLICATE_RESULT_EMIT,
            decision.semanticTag
        )
    }

    @Test
    fun `SuppressLatePartialAfterFinal semanticTag matches DECISION_SUPPRESS_LATE_PARTIAL_AFTER_FINAL`() {
        val decision = FlowAwareResultConvergenceDecision.SuppressLatePartialAfterFinal("f1", "rk1")
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.DECISION_SUPPRESS_LATE_PARTIAL_AFTER_FINAL,
            decision.semanticTag
        )
    }

    @Test
    fun `HoldResultForConvergenceAlignment semanticTag matches DECISION_HOLD_RESULT_FOR_CONVERGENCE_ALIGNMENT`() {
        val decision = FlowAwareResultConvergenceDecision.HoldResultForConvergenceAlignment("f1", "reason")
        assertEquals(
            AndroidFlowAwareResultConvergenceParticipant.DECISION_HOLD_RESULT_FOR_CONVERGENCE_ALIGNMENT,
            decision.semanticTag
        )
    }

    @Test
    fun `All six semanticTag values are distinct`() {
        val tags = listOf(
            FlowAwareResultConvergenceDecision.EmitPartialForFlow("f", "r").semanticTag,
            FlowAwareResultConvergenceDecision.EmitFinalForFlow(
                "f", "r", AndroidFlowAwareResultConvergenceParticipant.FlowResultKind.FINAL
            ).semanticTag,
            FlowAwareResultConvergenceDecision.BindParallelResultToParentFlow("f", "r", "g", 0, "p").semanticTag,
            FlowAwareResultConvergenceDecision.SuppressDuplicateResultEmit("f", "r").semanticTag,
            FlowAwareResultConvergenceDecision.SuppressLatePartialAfterFinal("f", "r").semanticTag,
            FlowAwareResultConvergenceDecision.HoldResultForConvergenceAlignment("f", "reason").semanticTag
        )
        assertEquals("All six semanticTag values must be distinct", tags.size, tags.toSet().size)
    }
}

package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class Pr13AndroidClosedLoopGovernanceConsolidationTest {

    @Test
    fun `activation progress event is canonicalized as active runtime informational non terminal`() {
        val event = DeviceExecutionEventPayload(
            flow_id = "flow-a",
            task_id = "task-a",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_PROGRESS
        )

        val canonical = AndroidClosedLoopGovernanceContract.canonicalizeExecutionEvent(event)

        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME.wireValue,
            canonical.reported_state_semantic_class
        )
        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL.wireValue,
            canonical.result_uplink_semantic_class
        )
        assertEquals(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.NON_TERMINAL.wireValue,
            canonical.terminal_outcome_kind
        )
    }

    @Test
    fun `fallback transition keeps active runtime state while carrying terminal fallback result semantics`() {
        val event = DeviceExecutionEventPayload(
            flow_id = "flow-fb",
            task_id = "task-fb",
            phase = DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION,
            blocking_reason = "bridge_handoff_failed",
            fallback_tier = "local_fallback"
        )

        val canonical = AndroidClosedLoopGovernanceContract.canonicalizeExecutionEvent(event)

        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME.wireValue,
            canonical.reported_state_semantic_class
        )
        // 生产端有意区分:FALLBACK/PARTIAL_COMPLETION 终态映射为 AUTHORITATIVE_DEGRADED_TERMINAL
        // (仍为 terminal,但不得按干净的 AUTHORITATIVE_TERMINAL 全额记账)。
        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_DEGRADED_TERMINAL.wireValue,
            canonical.result_uplink_semantic_class
        )
        assertEquals(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FALLBACK.wireValue,
            canonical.terminal_outcome_kind
        )
    }

    @Test
    fun `cancelled disconnect event is canonicalized as interruption terminal reporting`() {
        val event = DeviceExecutionEventPayload(
            flow_id = "flow-int",
            task_id = "task-int",
            phase = DeviceExecutionEventPayload.PHASE_CANCELLED,
            blocking_reason = "ws_disconnect"
        )

        val canonical = AndroidClosedLoopGovernanceContract.canonicalizeExecutionEvent(event)

        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.TERMINAL_REPORTING.wireValue,
            canonical.reported_state_semantic_class
        )
        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_INTERRUPTION.wireValue,
            canonical.result_uplink_semantic_class
        )
        assertEquals(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION.wireValue,
            canonical.terminal_outcome_kind
        )
    }

    @Test
    fun `timeout failure event is canonicalized as authoritative terminal timeout`() {
        val event = DeviceExecutionEventPayload(
            flow_id = "flow-to",
            task_id = "task-to",
            phase = DeviceExecutionEventPayload.PHASE_FAILED,
            blocking_reason = "timeout waiting for execution"
        )

        val canonical = AndroidClosedLoopGovernanceContract.canonicalizeExecutionEvent(event)

        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.TERMINAL_REPORTING.wireValue,
            canonical.reported_state_semantic_class
        )
        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL.wireValue,
            canonical.result_uplink_semantic_class
        )
        assertEquals(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.TIMEOUT.wireValue,
            canonical.terminal_outcome_kind
        )
    }

    @Test
    fun `explicit semantic fields are preserved`() {
        val event = DeviceExecutionEventPayload(
            flow_id = "flow-explicit",
            task_id = "task-explicit",
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            reported_state_semantic_class = AndroidCanonicalRuntimeTruthContract
                .ReportedStateSemanticClass.ACTIVE_RUNTIME.wireValue,
            result_uplink_semantic_class = AndroidCanonicalRuntimeTruthContract
                .ResultUplinkSemanticClass.INFORMATIONAL.wireValue,
            terminal_outcome_kind = AndroidMissionCompletionSemanticsContract
                .TerminalOutcomeKind.NON_TERMINAL.wireValue
        )

        val canonical = AndroidClosedLoopGovernanceContract.canonicalizeExecutionEvent(event)

        assertEquals(event.reported_state_semantic_class, canonical.reported_state_semantic_class)
        assertEquals(event.result_uplink_semantic_class, canonical.result_uplink_semantic_class)
        assertEquals(event.terminal_outcome_kind, canonical.terminal_outcome_kind)
    }
}

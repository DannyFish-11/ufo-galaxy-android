package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.DeviceExecutionEventPayload

/**
 * PR-13 (Android) — closed-loop governance consolidation for execution-event semantics.
 *
 * Centralizes Android-side execution-event canonicalization so activation/runtime/fallback/
 * interruption/completion reporting uses one deterministic projection before uplink.
 */
object AndroidClosedLoopGovernanceContract {

    private fun terminalOutcomeFromWireOrClassify(
        payload: DeviceExecutionEventPayload
    ): AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind {
        val fromWire = payload.terminal_outcome_kind?.let { wire ->
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.entries
                .firstOrNull { it.wireValue == wire }
        }
        return fromWire ?: AndroidMissionCompletionSemanticsContract.classifyLocalTerminalOutcome(
            phase = payload.phase,
            blockingReason = payload.blocking_reason,
            fallbackTier = payload.fallback_tier
        )
    }

    private fun canonicalReportedStateSemantic(
        payload: DeviceExecutionEventPayload,
        terminalOutcome: AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind
    ): String {
        payload.reported_state_semantic_class?.let { return it }
        return when {
            payload.phase == DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION ->
                AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME.wireValue
            terminalOutcome.isTerminal ->
                AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.TERMINAL_REPORTING.wireValue
            else ->
                AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME.wireValue
        }
    }

    /**
     * Canonicalizes execution-event semantic fields for a coherent Android closed loop.
     *
     * Existing explicit semantic fields are preserved; missing fields are deterministically filled.
     */
    fun canonicalizeExecutionEvent(
        payload: DeviceExecutionEventPayload
    ): DeviceExecutionEventPayload {
        val terminalOutcome = terminalOutcomeFromWireOrClassify(payload)
        val resultSemantic = payload.result_uplink_semantic_class
            ?: AndroidMissionCompletionSemanticsContract
                .classifyReportedResultSemantic(terminalOutcome)
                .wireValue
        return payload.copy(
            reported_state_semantic_class = canonicalReportedStateSemantic(payload, terminalOutcome),
            result_uplink_semantic_class = resultSemantic,
            terminal_outcome_kind = payload.terminal_outcome_kind ?: terminalOutcome.wireValue
        )
    }
}

package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.protocol.DeviceExecutionEventPayload

/**
 * PR-10 (Android) — Mission completion semantics hardening contract.
 *
 * Provides a deterministic, canonicalization-friendly classification for Android terminal
 * outcomes so local observations and center-facing reports can remain distinct and stable.
 */
object AndroidMissionCompletionSemanticsContract {

    enum class TerminalOutcomeKind(val wireValue: String, val isTerminal: Boolean) {
        COMPLETION("completion", true),
        PARTIAL_COMPLETION("partial_completion", true),
        FAILURE("failure", true),
        INTERRUPTION("interruption", true),
        ABORT("abort", true),
        TIMEOUT("timeout", true),
        FALLBACK("fallback", true),
        RECOVERY("recovery", true),
        NON_TERMINAL("non_terminal", false)
    }

    /**
     * Classifies the local terminal observation from execution-event/result facts.
     */
    fun classifyLocalTerminalOutcome(
        phase: String,
        status: String? = null,
        blockingReason: String? = null,
        details: String? = null,
        interruptionReason: String? = null,
        fallbackTier: String? = null
    ): TerminalOutcomeKind {
        val normalizedStatus = status?.trim()?.lowercase()
        val reasonText = listOfNotNull(blockingReason, details, interruptionReason)
            .joinToString(" ")
            .lowercase()
        val hasFallback = !fallbackTier.isNullOrBlank() || reasonText.contains("fallback")
        val hasInterruption =
            !interruptionReason.isNullOrBlank() ||
                reasonText.contains("interrupt") ||
                reasonText.contains("disconnect") ||
                reasonText.contains("session_invalid")
        val hasPartial = reasonText.contains("partial")
        val hasRecovery = reasonText.contains("recover")

        return when (phase) {
            DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            DeviceExecutionEventPayload.PHASE_EXECUTION_PROGRESS,
            DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE ->
                TerminalOutcomeKind.NON_TERMINAL

            DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION ->
                TerminalOutcomeKind.FALLBACK

            DeviceExecutionEventPayload.PHASE_COMPLETED -> when {
                hasRecovery -> TerminalOutcomeKind.RECOVERY
                hasPartial -> TerminalOutcomeKind.PARTIAL_COMPLETION
                hasFallback -> TerminalOutcomeKind.FALLBACK
                else -> TerminalOutcomeKind.COMPLETION
            }

            DeviceExecutionEventPayload.PHASE_CANCELLED -> when {
                hasInterruption -> TerminalOutcomeKind.INTERRUPTION
                else -> TerminalOutcomeKind.ABORT
            }

            DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED,
            DeviceExecutionEventPayload.PHASE_FAILED -> when {
                normalizedStatus == EdgeExecutor.STATUS_TIMEOUT || reasonText.contains("timeout") ->
                    TerminalOutcomeKind.TIMEOUT
                hasInterruption ->
                    TerminalOutcomeKind.INTERRUPTION
                hasFallback ->
                    TerminalOutcomeKind.FALLBACK
                else ->
                    TerminalOutcomeKind.FAILURE
            }

            else -> when {
                normalizedStatus == EdgeExecutor.STATUS_SUCCESS -> TerminalOutcomeKind.COMPLETION
                normalizedStatus == EdgeExecutor.STATUS_CANCELLED -> TerminalOutcomeKind.ABORT
                normalizedStatus == EdgeExecutor.STATUS_TIMEOUT -> TerminalOutcomeKind.TIMEOUT
                normalizedStatus == EdgeExecutor.STATUS_ERROR -> TerminalOutcomeKind.FAILURE
                else -> TerminalOutcomeKind.NON_TERMINAL
            }
        }
    }

    /**
     * Maps local terminal observation into center-facing result-uplink semantics.
     */
    fun classifyReportedResultSemantic(
        localOutcome: TerminalOutcomeKind
    ): AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass =
        when (localOutcome) {
            TerminalOutcomeKind.NON_TERMINAL ->
                AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL
            TerminalOutcomeKind.INTERRUPTION ->
                AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_INTERRUPTION
            TerminalOutcomeKind.RECOVERY ->
                AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_RECOVERY
            TerminalOutcomeKind.COMPLETION,
            TerminalOutcomeKind.PARTIAL_COMPLETION,
            TerminalOutcomeKind.FAILURE,
            TerminalOutcomeKind.ABORT,
            TerminalOutcomeKind.TIMEOUT,
            TerminalOutcomeKind.FALLBACK ->
                AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL
        }
}

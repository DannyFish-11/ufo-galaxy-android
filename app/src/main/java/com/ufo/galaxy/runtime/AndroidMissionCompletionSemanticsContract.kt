package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import java.util.Locale

/**
 * PR-10 (Android) — Mission completion semantics hardening contract.
 *
 * Provides a deterministic, canonicalization-friendly classification for Android terminal
 * outcomes so local observations and center-facing reports can remain distinct and stable.
 */
object AndroidMissionCompletionSemanticsContract {
    private const val KEYWORD_TIMEOUT = EdgeExecutor.STATUS_TIMEOUT
    private const val KEYWORD_INTERRUPT = "interrupt"
    private const val KEYWORD_DISCONNECT = "disconnect"
    private const val KEYWORD_SESSION_INVALID = "session_invalid"
    private const val KEYWORD_FALLBACK = "fallback"
    private const val KEYWORD_PARTIAL = "partial"
    private const val KEYWORD_RECOVER = "recover"
    private val TERMINAL_EXECUTION_PHASES: Set<String> = setOf(
        DeviceExecutionEventPayload.PHASE_COMPLETED,
        DeviceExecutionEventPayload.PHASE_FAILED,
        DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED,
        DeviceExecutionEventPayload.PHASE_CANCELLED
    )

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

    data class CompletionVisibility(
        val resultReturned: Boolean,
        val completionSignaled: Boolean,
        val closureReadyForAcceptance: Boolean
    )

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
        val normalizedStatus = status?.trim()?.toLowerCase(Locale.ROOT)
        val reasonText = listOfNotNull(blockingReason, details, interruptionReason)
            .joinToString(" ")
            .toLowerCase(Locale.ROOT)
        val hasFallback = !fallbackTier.isNullOrBlank() || reasonText.contains(KEYWORD_FALLBACK)
        val hasInterruption =
            !interruptionReason.isNullOrBlank() ||
                reasonText.contains(KEYWORD_INTERRUPT) ||
                reasonText.contains(KEYWORD_DISCONNECT) ||
                reasonText.contains(KEYWORD_SESSION_INVALID)
        val hasPartial = reasonText.contains(KEYWORD_PARTIAL)
        val hasRecovery = reasonText.contains(KEYWORD_RECOVER)

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
                normalizedStatus == EdgeExecutor.STATUS_TIMEOUT || reasonText.contains(KEYWORD_TIMEOUT) ->
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
            TerminalOutcomeKind.PARTIAL_COMPLETION,
            TerminalOutcomeKind.FALLBACK ->
                AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_DEGRADED_TERMINAL
            TerminalOutcomeKind.COMPLETION,
            TerminalOutcomeKind.FAILURE,
            TerminalOutcomeKind.ABORT,
            TerminalOutcomeKind.TIMEOUT ->
                AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL
        }

    fun deriveExecutionCompletionVisibility(
        phase: String,
        lifecycleTerminalPhase: Boolean?,
        terminalOutcomeKind: TerminalOutcomeKind? = null
    ): CompletionVisibility {
        val terminalByPhase = TERMINAL_EXECUTION_PHASES.contains(phase)
        val isTerminal = lifecycleTerminalPhase == true || terminalByPhase
        val isDegradedOutcome = terminalOutcomeKind in setOf(
            TerminalOutcomeKind.PARTIAL_COMPLETION,
            TerminalOutcomeKind.FALLBACK,
            TerminalOutcomeKind.RECOVERY
        )
        return CompletionVisibility(
            resultReturned = isTerminal,
            completionSignaled = isTerminal,
            closureReadyForAcceptance = isTerminal && !isDegradedOutcome
        )
    }
}

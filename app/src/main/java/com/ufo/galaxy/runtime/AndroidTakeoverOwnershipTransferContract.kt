package com.ufo.galaxy.runtime

/**
 * PR-11B (Android) — takeover completion + ownership-return semantics for V2 adjudication.
 *
 * Classifies Android delegated takeover signals into:
 *  1) takeover completion truth
 *  2) ownership-return status
 *  3) observation visibility (partial / interrupted / retried-delayed)
 */
object AndroidTakeoverOwnershipTransferContract {

    enum class TakeoverCompletionKind(val wireValue: String) {
        PENDING("pending"),
        COMPLETED("completed"),
        FAILED("failed"),
        INTERRUPTED("interrupted"),
        REJECTED("rejected"),
        UNKNOWN("unknown");

        companion object {
            fun fromWireValue(value: String?): TakeoverCompletionKind =
                entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
        }
    }

    enum class OwnershipReturnState(val wireValue: String) {
        OWNERSHIP_HELD_BY_ANDROID("ownership_held_android"),
        OWNERSHIP_RETURN_PENDING_UPLINK("ownership_return_pending_uplink"),
        OWNERSHIP_RETURN_SIGNALLED_TO_V2("ownership_return_signalled_to_v2"),
        OWNERSHIP_RETURN_SIGNALLED_TO_V2_RETRIED("ownership_return_signalled_to_v2_retried");

        companion object {
            fun fromWireValue(value: String?): OwnershipReturnState? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    enum class TakeoverOutcomeVisibility(val wireValue: String) {
        PARTIALLY_OBSERVED("partially_observed"),
        TERMINAL_OBSERVED("terminal_observed"),
        INTERRUPTED_TERMINAL_OBSERVED("interrupted_terminal_observed"),
        RETRIED_TERMINAL_OBSERVED("retried_terminal_observed"),
        RETRIED_INTERRUPTED_TERMINAL_OBSERVED("retried_interrupted_terminal_observed");

        companion object {
            fun fromWireValue(value: String?): TakeoverOutcomeVisibility? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    data class TakeoverClosureSemantics(
        val takeoverCompletionKind: TakeoverCompletionKind,
        val ownershipReturnState: OwnershipReturnState,
        val takeoverOutcomeVisibility: TakeoverOutcomeVisibility,
        val takeoverResultUplinkAttempt: Int
    )

    fun classify(
        signal: DelegatedExecutionSignal,
        takeoverResultUplinkAttempt: Int
    ): TakeoverClosureSemantics {
        val attempt = takeoverResultUplinkAttempt.coerceAtLeast(1)
        val completionKind = classifyCompletion(signal)
        val interrupted = completionKind == TakeoverCompletionKind.INTERRUPTED
        val terminal = signal.isResult

        val ownershipReturnState = when {
            !terminal -> OwnershipReturnState.OWNERSHIP_HELD_BY_ANDROID
            attempt > 1 -> OwnershipReturnState.OWNERSHIP_RETURN_SIGNALLED_TO_V2_RETRIED
            else -> OwnershipReturnState.OWNERSHIP_RETURN_SIGNALLED_TO_V2
        }

        val visibility = when {
            !terminal -> TakeoverOutcomeVisibility.PARTIALLY_OBSERVED
            interrupted && attempt > 1 ->
                TakeoverOutcomeVisibility.RETRIED_INTERRUPTED_TERMINAL_OBSERVED
            interrupted -> TakeoverOutcomeVisibility.INTERRUPTED_TERMINAL_OBSERVED
            attempt > 1 -> TakeoverOutcomeVisibility.RETRIED_TERMINAL_OBSERVED
            else -> TakeoverOutcomeVisibility.TERMINAL_OBSERVED
        }

        return TakeoverClosureSemantics(
            takeoverCompletionKind = completionKind,
            ownershipReturnState = ownershipReturnState,
            takeoverOutcomeVisibility = visibility,
            takeoverResultUplinkAttempt = attempt
        )
    }

    private fun classifyCompletion(signal: DelegatedExecutionSignal): TakeoverCompletionKind {
        if (!signal.isResult) return TakeoverCompletionKind.PENDING
        return when (signal.resultKind) {
            DelegatedExecutionSignal.ResultKind.COMPLETED -> TakeoverCompletionKind.COMPLETED
            DelegatedExecutionSignal.ResultKind.FAILED -> TakeoverCompletionKind.FAILED
            DelegatedExecutionSignal.ResultKind.TIMEOUT,
            DelegatedExecutionSignal.ResultKind.CANCELLED -> TakeoverCompletionKind.INTERRUPTED
            DelegatedExecutionSignal.ResultKind.REJECTED -> TakeoverCompletionKind.REJECTED
            null -> TakeoverCompletionKind.UNKNOWN
        }
    }
}

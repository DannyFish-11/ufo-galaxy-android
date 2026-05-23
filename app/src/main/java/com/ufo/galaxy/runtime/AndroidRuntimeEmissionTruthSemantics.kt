package com.ufo.galaxy.runtime

/**
 * Canonical Android-side runtime truth semantics for execution continuity and terminal emission.
 *
 * This contract gives Android one shared vocabulary for distinguishing:
 *  - fresh execution vs resumed / recovered execution
 *  - locally emitted in-progress state vs terminal result output
 *  - direct terminal delivery vs queued / failed / replayed delivery
 *
 * The same fields are used across result uplinks and reconciliation signals so reconnect,
 * replay-like re-delivery, and recovered execution do not look like fresh canonical closure.
 */
object AndroidRuntimeEmissionTruthSemantics {

    const val SCHEMA_VERSION = "android_runtime_emission_truth_v1"

    const val KEY_EXECUTION_CONTINUITY_CLASS = "execution_continuity_class"
    const val KEY_TERMINAL_EMISSION_CLASS = "terminal_emission_class"
    const val KEY_TERMINAL_DELIVERY_DISPOSITION = "terminal_delivery_disposition"
    const val KEY_RESULT_CONVERGENCE_DECISION = "result_convergence_decision"
    const val KEY_RUNTIME_EMISSION_TRUTH_SCHEMA_VERSION = "runtime_emission_truth_schema_version"

    enum class ExecutionContinuityClass(val wireValue: String) {
        FRESH_EXECUTION("fresh_execution"),
        RESUMED_EXECUTION("resumed_execution"),
        RECOVERED_EXECUTION("recovered_execution"),
        REPLAYED_DELIVERY("replayed_delivery"),
        DEGRADED_CONTINUITY("degraded_continuity")
    }

    enum class TerminalEmissionClass(val wireValue: String) {
        ACTIVE_IN_PROGRESS("active_in_progress"),
        TERMINAL_COMPLETION("terminal_completion"),
        RESUMED_TERMINAL_COMPLETION("resumed_terminal_completion"),
        RECOVERED_TERMINAL_COMPLETION("recovered_terminal_completion"),
        REPLAYED_TERMINAL_COMPLETION("replayed_terminal_completion"),
        DEGRADED_TERMINAL_OUTPUT("degraded_terminal_output")
    }

    enum class DeliveryDisposition(val wireValue: String) {
        LOCAL_SIGNAL_EMITTED("local_signal_emitted"),
        DIRECT_SENT("direct_sent"),
        OFFLINE_QUEUED("offline_queued"),
        SEND_FAILED("send_failed"),
        REPLAYED_FORWARDED("replayed_forwarded")
    }

    data class TruthSnapshot(
        val executionContinuityClass: ExecutionContinuityClass,
        val terminalEmissionClass: TerminalEmissionClass,
        val deliveryDisposition: DeliveryDisposition,
        val resultConvergenceDecision: String? = null
    ) {
        fun toPayloadMap(): Map<String, Any?> = buildMap {
            put(KEY_EXECUTION_CONTINUITY_CLASS, executionContinuityClass.wireValue)
            put(KEY_TERMINAL_EMISSION_CLASS, terminalEmissionClass.wireValue)
            put(KEY_TERMINAL_DELIVERY_DISPOSITION, deliveryDisposition.wireValue)
            resultConvergenceDecision?.let {
                put(KEY_RESULT_CONVERGENCE_DECISION, it)
            }
            put(KEY_RUNTIME_EMISSION_TRUTH_SCHEMA_VERSION, SCHEMA_VERSION)
        }
    }

    fun derive(
        recoveryPhase: AndroidContinuityRecoveryStateModel.RecoveryPhase?,
        isContinuation: Boolean,
        interruptionReason: String?,
        isTerminal: Boolean,
        deliveryDisposition: DeliveryDisposition,
        resultConvergenceDecision: String? = null
    ): TruthSnapshot {
        val continuityClass = when {
            deliveryDisposition == DeliveryDisposition.REPLAYED_FORWARDED ->
                ExecutionContinuityClass.REPLAYED_DELIVERY
            recoveryPhase == AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERED_INFLIGHT ->
                ExecutionContinuityClass.RECOVERED_EXECUTION
            recoveryPhase in setOf(
                AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERING,
                AndroidContinuityRecoveryStateModel.RecoveryPhase.REQUIRES_RECONCILIATION
            ) || isContinuation || !interruptionReason.isNullOrBlank() ->
                ExecutionContinuityClass.RESUMED_EXECUTION
            recoveryPhase in setOf(
                AndroidContinuityRecoveryStateModel.RecoveryPhase.LOST_INFLIGHT,
                AndroidContinuityRecoveryStateModel.RecoveryPhase.STALE_RECOVERY_ARTIFACT,
                AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERY_FAILED
            ) ->
                ExecutionContinuityClass.DEGRADED_CONTINUITY
            else ->
                ExecutionContinuityClass.FRESH_EXECUTION
        }

        val terminalClass = when {
            !isTerminal ->
                TerminalEmissionClass.ACTIVE_IN_PROGRESS
            deliveryDisposition == DeliveryDisposition.REPLAYED_FORWARDED ->
                TerminalEmissionClass.REPLAYED_TERMINAL_COMPLETION
            continuityClass == ExecutionContinuityClass.RECOVERED_EXECUTION ->
                TerminalEmissionClass.RECOVERED_TERMINAL_COMPLETION
            continuityClass == ExecutionContinuityClass.RESUMED_EXECUTION ->
                TerminalEmissionClass.RESUMED_TERMINAL_COMPLETION
            continuityClass == ExecutionContinuityClass.DEGRADED_CONTINUITY ||
                deliveryDisposition == DeliveryDisposition.OFFLINE_QUEUED ||
                deliveryDisposition == DeliveryDisposition.SEND_FAILED ->
                TerminalEmissionClass.DEGRADED_TERMINAL_OUTPUT
            else ->
                TerminalEmissionClass.TERMINAL_COMPLETION
        }

        return TruthSnapshot(
            executionContinuityClass = continuityClass,
            terminalEmissionClass = terminalClass,
            deliveryDisposition = deliveryDisposition,
            resultConvergenceDecision = resultConvergenceDecision
        )
    }
}

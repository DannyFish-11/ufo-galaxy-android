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
    const val KEY_LOCAL_RUNTIME_STATE_CLASS = "local_runtime_state_class"
    const val KEY_EXTERNAL_DELIVERY_STATE = "external_delivery_state"
    const val KEY_EXTERNAL_PROPAGATION_STATE = "external_propagation_state"
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
        REPLAYED_FORWARDED("replayed_forwarded"),
        DUPLICATE_SUPPRESSED("duplicate_suppressed")
    }

    enum class LocalRuntimeStateClass(val wireValue: String) {
        LOCALLY_OBSERVED_STATE("locally_observed_state"),
        LOCALLY_COMPLETED_STATE("locally_completed_state")
    }

    enum class ExternalDeliveryState(val wireValue: String) {
        NOT_EXTERNALLY_DELIVERED("not_externally_delivered"),
        DELIVERED_TO_TRANSPORT("delivered_to_transport"),
        QUEUED_FOR_EXTERNAL_DELIVERY("queued_for_external_delivery"),
        EXTERNAL_DELIVERY_FAILED("external_delivery_failed")
    }

    enum class ExternalPropagationState(val wireValue: String) {
        NOT_PROPAGATED_EXTERNALLY("not_propagated_externally"),
        PROPAGATED_UNCONFIRMED("propagated_unconfirmed"),
        EXTERNALLY_CONFIRMED("externally_confirmed")
    }

    data class TruthSnapshot(
        val executionContinuityClass: ExecutionContinuityClass,
        val terminalEmissionClass: TerminalEmissionClass,
        val deliveryDisposition: DeliveryDisposition,
        val localRuntimeStateClass: LocalRuntimeStateClass,
        val externalDeliveryState: ExternalDeliveryState,
        val externalPropagationState: ExternalPropagationState,
        val resultConvergenceDecision: String? = null
    ) {
        fun toPayloadMap(): Map<String, Any?> = buildMap {
            put(KEY_EXECUTION_CONTINUITY_CLASS, executionContinuityClass.wireValue)
            put(KEY_TERMINAL_EMISSION_CLASS, terminalEmissionClass.wireValue)
            put(KEY_TERMINAL_DELIVERY_DISPOSITION, deliveryDisposition.wireValue)
            put(KEY_LOCAL_RUNTIME_STATE_CLASS, localRuntimeStateClass.wireValue)
            put(KEY_EXTERNAL_DELIVERY_STATE, externalDeliveryState.wireValue)
            put(KEY_EXTERNAL_PROPAGATION_STATE, externalPropagationState.wireValue)
            resultConvergenceDecision?.let {
                put(KEY_RESULT_CONVERGENCE_DECISION, it)
            }
            put(KEY_RUNTIME_EMISSION_TRUTH_SCHEMA_VERSION, SCHEMA_VERSION)
        }

        fun withDeliveryDisposition(deliveryDisposition: DeliveryDisposition): TruthSnapshot =
            fromDerivedState(
                executionContinuityClass = executionContinuityClass,
                isTerminal = localRuntimeStateClass == LocalRuntimeStateClass.LOCALLY_COMPLETED_STATE,
                deliveryDisposition = deliveryDisposition,
                resultConvergenceDecision = resultConvergenceDecision
            )

        companion object {
            fun fromPayload(
                payload: Map<String, Any?>,
                isTerminal: Boolean
            ): TruthSnapshot? {
                val continuityClass = payload[KEY_EXECUTION_CONTINUITY_CLASS]
                    ?.toString()
                    ?.let(::parseExecutionContinuityClass)
                    ?: return null
                val deliveryDisposition = payload[KEY_TERMINAL_DELIVERY_DISPOSITION]
                    ?.toString()
                    ?.let(::parseDeliveryDisposition)
                    ?: DeliveryDisposition.LOCAL_SIGNAL_EMITTED
                return fromDerivedState(
                    executionContinuityClass = continuityClass,
                    isTerminal = isTerminal,
                    deliveryDisposition = deliveryDisposition,
                    resultConvergenceDecision = payload[KEY_RESULT_CONVERGENCE_DECISION]?.toString()
                )
            }
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

        return fromDerivedState(
            executionContinuityClass = continuityClass,
            isTerminal = isTerminal,
            deliveryDisposition = deliveryDisposition,
            resultConvergenceDecision = resultConvergenceDecision
        )
    }

    private fun fromDerivedState(
        executionContinuityClass: ExecutionContinuityClass,
        isTerminal: Boolean,
        deliveryDisposition: DeliveryDisposition,
        resultConvergenceDecision: String?
    ): TruthSnapshot = TruthSnapshot(
        executionContinuityClass = executionContinuityClass,
        terminalEmissionClass = deriveTerminalEmissionClass(
            executionContinuityClass = executionContinuityClass,
            isTerminal = isTerminal,
            deliveryDisposition = deliveryDisposition
        ),
        deliveryDisposition = deliveryDisposition,
        localRuntimeStateClass = if (isTerminal) {
            LocalRuntimeStateClass.LOCALLY_COMPLETED_STATE
        } else {
            LocalRuntimeStateClass.LOCALLY_OBSERVED_STATE
        },
        externalDeliveryState = deriveExternalDeliveryState(deliveryDisposition),
        externalPropagationState = deriveExternalPropagationState(deliveryDisposition),
        resultConvergenceDecision = resultConvergenceDecision
    )

    private fun deriveTerminalEmissionClass(
        executionContinuityClass: ExecutionContinuityClass,
        isTerminal: Boolean,
        deliveryDisposition: DeliveryDisposition
    ): TerminalEmissionClass = when {
        !isTerminal ->
            TerminalEmissionClass.ACTIVE_IN_PROGRESS
        deliveryDisposition == DeliveryDisposition.REPLAYED_FORWARDED ->
            TerminalEmissionClass.REPLAYED_TERMINAL_COMPLETION
        executionContinuityClass == ExecutionContinuityClass.RECOVERED_EXECUTION ->
            TerminalEmissionClass.RECOVERED_TERMINAL_COMPLETION
        executionContinuityClass == ExecutionContinuityClass.RESUMED_EXECUTION ->
            TerminalEmissionClass.RESUMED_TERMINAL_COMPLETION
        executionContinuityClass == ExecutionContinuityClass.DEGRADED_CONTINUITY ||
            deliveryDisposition == DeliveryDisposition.OFFLINE_QUEUED ||
            deliveryDisposition == DeliveryDisposition.SEND_FAILED ||
            deliveryDisposition == DeliveryDisposition.DUPLICATE_SUPPRESSED ->
            TerminalEmissionClass.DEGRADED_TERMINAL_OUTPUT
        else ->
            TerminalEmissionClass.TERMINAL_COMPLETION
    }

    private fun deriveExternalDeliveryState(
        deliveryDisposition: DeliveryDisposition
    ): ExternalDeliveryState = when (deliveryDisposition) {
        DeliveryDisposition.DIRECT_SENT,
        DeliveryDisposition.REPLAYED_FORWARDED ->
            ExternalDeliveryState.DELIVERED_TO_TRANSPORT
        DeliveryDisposition.OFFLINE_QUEUED ->
            ExternalDeliveryState.QUEUED_FOR_EXTERNAL_DELIVERY
        DeliveryDisposition.SEND_FAILED ->
            ExternalDeliveryState.EXTERNAL_DELIVERY_FAILED
        DeliveryDisposition.LOCAL_SIGNAL_EMITTED,
        DeliveryDisposition.DUPLICATE_SUPPRESSED ->
            ExternalDeliveryState.NOT_EXTERNALLY_DELIVERED
    }

    private fun deriveExternalPropagationState(
        deliveryDisposition: DeliveryDisposition
    ): ExternalPropagationState = when (deliveryDisposition) {
        DeliveryDisposition.DIRECT_SENT,
        DeliveryDisposition.REPLAYED_FORWARDED ->
            ExternalPropagationState.PROPAGATED_UNCONFIRMED
        DeliveryDisposition.LOCAL_SIGNAL_EMITTED,
        DeliveryDisposition.OFFLINE_QUEUED,
        DeliveryDisposition.SEND_FAILED,
        DeliveryDisposition.DUPLICATE_SUPPRESSED ->
            ExternalPropagationState.NOT_PROPAGATED_EXTERNALLY
    }

    private fun parseExecutionContinuityClass(value: String): ExecutionContinuityClass? =
        ExecutionContinuityClass.entries.firstOrNull { it.wireValue == value }

    private fun parseDeliveryDisposition(value: String): DeliveryDisposition? =
        DeliveryDisposition.entries.firstOrNull { it.wireValue == value }
}

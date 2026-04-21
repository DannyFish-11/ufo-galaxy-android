package com.ufo.galaxy.runtime

/**
 * PR-52 — Testable sink for V2-facing reconciliation signals.
 *
 * [ReconciliationSignalSink] is a single-method functional interface that decouples
 * the [ReconciliationSignal] emission path from the concrete outbound-transmission
 * mechanism (WebSocket send, in-process bus, structured log, etc.).  This allows
 * any component that emits [ReconciliationSignal] events to be fully tested in JVM
 * unit tests without an Android framework or a live WebSocket connection.
 *
 * ## Relationship to [DelegatedExecutionSignalSink]
 *
 * [DelegatedExecutionSignalSink] carries Android-internal delegated-execution signals
 * (ACK, PROGRESS, RESULT) that are Android-specific and lifecycle-tracking oriented.
 *
 * [ReconciliationSignalSink] carries **V2-facing** [ReconciliationSignal] events
 * (TASK_ACCEPTED, TASK_CANCELLED, TASK_FAILED, TASK_RESULT, PARTICIPANT_STATE,
 * RUNTIME_TRUTH_SNAPSHOT) that are structured for canonical V2 reconciliation.
 *
 * Both sinks coexist: [DelegatedExecutionSignalSink] feeds the existing Android-to-host
 * execution tracking path; [ReconciliationSignalSink] feeds the V2 reconciliation loop.
 *
 * ## Production wiring
 *
 * In production the sink should forward each [ReconciliationSignal] to V2 via the
 * established transport (e.g. [com.ufo.galaxy.network.GalaxyWebSocketClient]):
 *
 * ```kotlin
 * private val reconciliationSink = ReconciliationSignalSink { signal ->
 *     GalaxyLogger.log(TAG, signal.toWireMap())
 *     webSocketClient.sendReconciliationSignal(signal)
 * }
 * ```
 *
 * ## Test wiring
 *
 * In unit tests a capturing lambda records emitted signals for assertion:
 *
 * ```kotlin
 * val emitted = mutableListOf<ReconciliationSignal>()
 * val sink = ReconciliationSignalSink { signal -> emitted += signal }
 * ```
 *
 * @see ReconciliationSignal
 * @see DelegatedExecutionSignalSink
 */
fun interface ReconciliationSignalSink {

    /**
     * Receives a [signal] emitted as part of the Android→V2 reconciliation protocol.
     *
     * Implementations must not throw: any error handling (e.g. logging a failed send)
     * must be done internally so the caller's lifecycle progression is not interrupted.
     *
     * @param signal The [ReconciliationSignal] to forward to V2.
     */
    fun onSignal(signal: ReconciliationSignal)
}

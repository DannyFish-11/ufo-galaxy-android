package com.ufo.galaxy.runtime

/**
 * **Testable sink for delegated-execution signals** (PR-12,
 * post-#533 dual-repo runtime unification master plan — Canonical Android-Side
 * Delegated Receipt-to-Local-Takeover Executor Binding, Android side).
 *
 * [DelegatedExecutionSignalSink] is a single-method functional interface that decouples
 * [com.ufo.galaxy.agent.DelegatedTakeoverExecutor] from the concrete outbound-transmission
 * mechanism (structured logging, WebSocket send, in-process bus, etc.).  This allows the
 * executor to be fully exercised in JVM unit tests without an Android framework or a live
 * WebSocket connection.
 *
 * ## Production wiring
 *
 * In [com.ufo.galaxy.service.GalaxyConnectionService] the sink is wired as a lambda that
 * logs the signal via [com.ufo.galaxy.observability.GalaxyLogger]:
 *
 * ```kotlin
 * private val delegatedSignalSink = DelegatedExecutionSignalSink { signal ->
 *     GalaxyLogger.log(TAG, signal.toMetadataMap().mapValues { it.value.toString() })
 * }
 * ```
 *
 * ## Test wiring
 *
 * In unit tests a capturing lambda records emitted signals for assertion:
 *
 * ```kotlin
 * val emitted = mutableListOf<DelegatedExecutionSignal>()
 * val sink = DelegatedExecutionSignalSink { signal -> emitted += signal }
 * ```
 *
 * @see DelegatedExecutionSignal
 * @see com.ufo.galaxy.agent.DelegatedTakeoverExecutor
 */
fun interface DelegatedExecutionSignalSink {

    /**
     * Receives a [signal] emitted by the local execution pipeline.
     *
     * Implementations must not throw: any error handling (e.g. logging a failed send)
     * must be done internally so the executor's lifecycle progression is not interrupted.
     *
     * @param signal The [DelegatedExecutionSignal] emitted by the executor.
     */
    fun onSignal(signal: DelegatedExecutionSignal)
}

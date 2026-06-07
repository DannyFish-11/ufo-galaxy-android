package com.ufo.galaxy.observability

import java.util.concurrent.ThreadLocalRandom

/**
 * Sampling configuration for [GalaxyLogger].
 *
 * Controls which log events are actually recorded, enabling noise reduction in
 * high-frequency production environments while preserving full fidelity in debug
 * builds.
 *
 * ## Design
 * Each log event category has an independent **sample rate** in [0.0, 1.0]:
 * - `1.0` → log every event (default for errors and task events).
 * - `0.5` → log approximately 50 % of events.
 * - `0.0` → never log events in this category (effectively disabled).
 *
 * The [shouldSample] method makes a per-event random decision using
 * [ThreadLocalRandom] for low-overhead, thread-safe sampling.
 *
 * ## Predefined configurations
 * | Factory | Intended use |
 * |---------|-------------|
 * | [debug] | All events sampled at 100 % — maximum fidelity. |
 * | [production] | Errors/tasks at 100 %; connection/signaling at 10 %; verbose metrics at 0 %. |
 * | [custom] | Caller provides per-category rates. |
 *
 * ## Usage
 * ```kotlin
 * // In Application.onCreate():
 * GalaxyLogger.samplingConfig = if (BuildConfig.DEBUG) SamplingConfig.debug()
 *                               else SamplingConfig.production()
 *
 * // Anywhere:
 * if (GalaxyLogger.samplingConfig.shouldSample(GalaxyLogger.TAG_RECONNECT)) {
 *     GalaxyLogger.log(GalaxyLogger.TAG_RECONNECT, fields)
 * }
 * // Or use the convenience wrapper:
 * GalaxyLogger.logSampled(GalaxyLogger.TAG_RECONNECT, fields)
 * ```
 *
 * @param errorRate         Sample rate for [GalaxyLogger.TAG_ERROR] events.
 * @param taskRate          Sample rate for task lifecycle events (RECV, EXEC, RETURN, TIMEOUT, CANCEL).
 * @param connectionRate    Sample rate for connection events (CONNECT, DISCONNECT, RECONNECT).
 * @param signalingRate     Sample rate for WebRTC signaling events (SIGNAL:START, SIGNAL:STOP).
 * @param dispatcherRate    Sample rate for dispatcher / bridge events (DISPATCHER:SELECT, BRIDGE:HANDOFF).
 * @param turnRate          Sample rate for TURN/ICE events (WEBRTC:TURN).
 * @param metricsFlushRate  Sample rate for periodic metrics flush events.
 * @param defaultRate       Fallback sample rate for any tag not explicitly mapped above.
 */
data class SamplingConfig(
    val errorRate: Double = 1.0,
    val taskRate: Double = 1.0,
    val connectionRate: Double = 1.0,
    val signalingRate: Double = 1.0,
    val dispatcherRate: Double = 1.0,
    val turnRate: Double = 1.0,
    val metricsFlushRate: Double = 1.0,
    val defaultRate: Double = 1.0
) {

    init {
        require(errorRate in 0.0..1.0)      { "errorRate must be in [0,1]: $errorRate" }
        require(taskRate in 0.0..1.0)       { "taskRate must be in [0,1]: $taskRate" }
        require(connectionRate in 0.0..1.0) { "connectionRate must be in [0,1]: $connectionRate" }
        require(signalingRate in 0.0..1.0)  { "signalingRate must be in [0,1]: $signalingRate" }
        require(dispatcherRate in 0.0..1.0) { "dispatcherRate must be in [0,1]: $dispatcherRate" }
        require(turnRate in 0.0..1.0)       { "turnRate must be in [0,1]: $turnRate" }
        require(metricsFlushRate in 0.0..1.0) { "metricsFlushRate must be in [0,1]: $metricsFlushRate" }
        require(defaultRate in 0.0..1.0)    { "defaultRate must be in [0,1]: $defaultRate" }
    }

    /**
     * Returns `true` when [tag] should be logged, based on its category's sample rate.
     *
     * A rate of `1.0` always returns `true`; a rate of `0.0` always returns `false`;
     * intermediate values produce probabilistic results.
     */
    fun shouldSample(tag: String): Boolean {
        val rate = rateForTag(tag)
        return when {
            rate >= 1.0 -> true
            rate <= 0.0 -> false
            else -> ThreadLocalRandom.current().nextDouble() < rate
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private fun rateForTag(tag: String): Double = when {
        tag == GalaxyLogger.TAG_ERROR                                  -> errorRate
        tag.startsWith("GALAXY:TASK")                                  -> taskRate
        tag == GalaxyLogger.TAG_CONNECT ||
            tag == GalaxyLogger.TAG_DISCONNECT ||
            tag == GalaxyLogger.TAG_RECONNECT                          -> connectionRate
        tag == GalaxyLogger.TAG_SIGNAL_START ||
            tag == GalaxyLogger.TAG_SIGNAL_STOP                        -> signalingRate
        tag == GalaxyLogger.TAG_DISPATCHER_SELECT ||
            tag == GalaxyLogger.TAG_BRIDGE_HANDOFF                     -> dispatcherRate
        tag == GalaxyLogger.TAG_WEBRTC_TURN                            -> turnRate
        tag == "MetricsRecorder" && tag.contains("metrics_flush")      -> metricsFlushRate
        else                                                           -> defaultRate
    }

    companion object {

        /**
         * All events sampled at 100 % — use in debug / dev builds for full
         * observability without noise reduction.
         */
        fun debug(): SamplingConfig = SamplingConfig(
            errorRate        = 1.0,
            taskRate         = 1.0,
            connectionRate   = 1.0,
            signalingRate    = 1.0,
            dispatcherRate   = 1.0,
            turnRate         = 1.0,
            metricsFlushRate = 1.0,
            defaultRate      = 1.0
        )

        /**
         * Production-safe defaults:
         * - Errors and task lifecycle events: 100 % (always captured).
         * - Connection / signaling / TURN events: 10 % (reduce high-frequency noise).
         * - Periodic metrics flush: 0 % (metrics are exported via [MetricsRecorder]).
         * - Everything else: 10 %.
         */
        fun production(): SamplingConfig = SamplingConfig(
            errorRate        = 1.0,
            taskRate         = 1.0,
            connectionRate   = 0.1,
            signalingRate    = 0.1,
            dispatcherRate   = 1.0,
            turnRate         = 0.1,
            metricsFlushRate = 0.0,
            defaultRate      = 0.1
        )
    }
}

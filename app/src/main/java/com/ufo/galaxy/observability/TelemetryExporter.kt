package com.ufo.galaxy.observability

/**
 * Pluggable telemetry export interface.
 *
 * Implement this interface to forward metrics to an external observability
 * pipeline (e.g. OpenTelemetry, StatsD, Prometheus push-gateway, or a custom
 * HTTP endpoint).
 *
 * A no-op default implementation ([NoOpTelemetryExporter]) is provided so that
 * callers compile without an external pipeline.  Replace it in
 * `UFOGalaxyApplication.onCreate` when a real pipeline is available:
 *
 * ```kotlin
 * metricsRecorder.telemetryExporter = MyStatsDExporter(host = "100.64.0.1", port = 8125)
 * ```
 *
 * ## Method contract
 * All methods are **fire-and-forget**: implementations MUST NOT throw exceptions;
 * errors should be swallowed or logged locally.  Callers do not handle exceptions
 * from exporters.
 */
interface TelemetryExporter {

    /**
     * Increment a named counter by [delta] (default 1).
     *
     * @param name  Metric name, e.g. `"galaxy.signaling.success"`.
     * @param delta Increment amount (must be ≥ 1).
     * @param tags  Optional dimension key-value pairs, e.g. `mapOf("route_mode" to "cross_device")`.
     */
    fun incrementCounter(name: String, delta: Int = 1, tags: Map<String, String> = emptyMap())

    /**
     * Record a latency observation (histogram / timing metric).
     *
     * @param name      Metric name, e.g. `"galaxy.signaling.latency_ms"`.
     * @param valueMs   Observed duration in milliseconds.
     * @param tags      Optional dimension key-value pairs.
     */
    fun recordLatency(name: String, valueMs: Long, tags: Map<String, String> = emptyMap())

    /**
     * Flush any buffered data to the backend.
     *
     * For exporters that buffer locally (e.g. UDP-based StatsD), this signals
     * that buffered metrics should be sent immediately.  May be a no-op for
     * streaming exporters.
     */
    fun flush()
}

/**
 * No-op [TelemetryExporter] used as the safe default.
 *
 * All methods are empty; no external connections are made.  Replace this
 * with a real implementation when an observability pipeline is available.
 */
object NoOpTelemetryExporter : TelemetryExporter {
    override fun incrementCounter(name: String, delta: Int, tags: Map<String, String>) = Unit
    override fun recordLatency(name: String, valueMs: Long, tags: Map<String, String>) = Unit
    override fun flush() = Unit
}

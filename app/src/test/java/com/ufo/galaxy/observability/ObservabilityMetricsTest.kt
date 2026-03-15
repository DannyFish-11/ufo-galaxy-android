package com.ufo.galaxy.observability

import com.ufo.galaxy.data.InMemoryAppSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for Round-7 metric counters and [TelemetryExporter] integration.
 *
 * Covers:
 *  - New counters: [MetricsRecorder.signalingSuccesses], [signalingFailures],
 *    [turnUsages], [turnFallbacks].
 *  - Latency recording: [MetricsRecorder.signalingLatencyMs] bounded to 256 entries.
 *  - [TelemetryExporter] wiring: every record call invokes the exporter.
 *  - [MetricsRecorder.snapshot] includes all new counter fields.
 *  - Metric name constants are stable and correctly prefixed.
 */
class ObservabilityMetricsTest {

    private fun buildMetrics(): MetricsRecorder =
        MetricsRecorder(InMemoryAppSettings())

    // ── Signaling counters ────────────────────────────────────────────────────

    @Test
    fun `signalingSuccesses starts at 0`() {
        assertEquals(0, buildMetrics().signalingSuccesses.get())
    }

    @Test
    fun `recordSignalingSuccess increments signalingSuccesses`() {
        val m = buildMetrics()
        m.recordSignalingSuccess()
        assertEquals(1, m.signalingSuccesses.get())
    }

    @Test
    fun `signalingFailures starts at 0`() {
        assertEquals(0, buildMetrics().signalingFailures.get())
    }

    @Test
    fun `recordSignalingFailure increments signalingFailures`() {
        val m = buildMetrics()
        m.recordSignalingFailure()
        assertEquals(1, m.signalingFailures.get())
    }

    // ── TURN counters ─────────────────────────────────────────────────────────

    @Test
    fun `turnUsages starts at 0`() {
        assertEquals(0, buildMetrics().turnUsages.get())
    }

    @Test
    fun `recordTurnUsage increments turnUsages`() {
        val m = buildMetrics()
        m.recordTurnUsage()
        assertEquals(1, m.turnUsages.get())
    }

    @Test
    fun `turnFallbacks starts at 0`() {
        assertEquals(0, buildMetrics().turnFallbacks.get())
    }

    @Test
    fun `recordTurnFallback increments turnFallbacks`() {
        val m = buildMetrics()
        m.recordTurnFallback()
        assertEquals(1, m.turnFallbacks.get())
    }

    // ── Latency recording ─────────────────────────────────────────────────────

    @Test
    fun `recordSignalingLatency stores sample`() {
        val m = buildMetrics()
        m.recordSignalingLatency(120L)
        assertEquals(1, m.signalingLatencyMs.size)
        assertEquals(120L, m.signalingLatencyMs[0])
    }

    @Test
    fun `recordSignalingSuccess with latency stores sample`() {
        val m = buildMetrics()
        m.recordSignalingSuccess(latencyMs = 250L)
        assertEquals(1, m.signalingSuccesses.get())
        assertEquals(1, m.signalingLatencyMs.size)
        assertEquals(250L, m.signalingLatencyMs[0])
    }

    @Test
    fun `recordSignalingSuccess with zero latency does not add sample`() {
        val m = buildMetrics()
        m.recordSignalingSuccess(latencyMs = 0L)
        assertEquals(0, m.signalingLatencyMs.size)
    }

    @Test
    fun `signalingLatencyMs is bounded to 256 entries`() {
        val m = buildMetrics()
        repeat(300) { m.recordSignalingLatency(it.toLong()) }
        assertTrue(
            "latency buffer must not exceed 256 entries",
            m.signalingLatencyMs.size <= 256
        )
    }

    // ── TelemetryExporter wiring ──────────────────────────────────────────────

    /** Counting fake exporter for verifying calls. */
    private inner class CountingExporter : TelemetryExporter {
        val counters = mutableMapOf<String, AtomicInteger>()
        val latencies = mutableListOf<Pair<String, Long>>()
        var flushCount = 0

        override fun incrementCounter(name: String, delta: Int, tags: Map<String, String>) {
            counters.getOrPut(name) { AtomicInteger(0) }.addAndGet(delta)
        }

        override fun recordLatency(name: String, valueMs: Long, tags: Map<String, String>) {
            latencies.add(name to valueMs)
        }

        override fun flush() { flushCount++ }
    }

    @Test
    fun `recordWsReconnect calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordWsReconnect()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_WS_RECONNECTS]?.get())
    }

    @Test
    fun `recordTaskSuccess calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordTaskSuccess()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_TASK_SUCCESSES]?.get())
    }

    @Test
    fun `recordTaskFailure calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordTaskFailure()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_TASK_FAILURES]?.get())
    }

    @Test
    fun `recordSignalingSuccess calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordSignalingSuccess()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_SIGNALING_SUCCESSES]?.get())
    }

    @Test
    fun `recordSignalingFailure calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordSignalingFailure()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_SIGNALING_FAILURES]?.get())
    }

    @Test
    fun `recordSignalingLatency calls exporter recordLatency`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordSignalingLatency(350L)
        assertEquals(1, exp.latencies.size)
        assertEquals(MetricsRecorder.METRIC_SIGNALING_LATENCY_MS, exp.latencies[0].first)
        assertEquals(350L, exp.latencies[0].second)
    }

    @Test
    fun `recordTurnUsage calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordTurnUsage()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_TURN_USAGES]?.get())
    }

    @Test
    fun `recordTurnFallback calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordTurnFallback()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_TURN_FALLBACKS]?.get())
    }

    @Test
    fun `recordHandoffSuccess calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordHandoffSuccess()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_HANDOFF_SUCCESSES]?.get())
    }

    @Test
    fun `recordHandoffFailure calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordHandoffFailure()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_HANDOFF_FAILURES]?.get())
    }

    @Test
    fun `recordHandoffFallback calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordHandoffFallback()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_HANDOFF_FALLBACKS]?.get())
    }

    // ── Snapshot completeness ─────────────────────────────────────────────────

    @Test
    fun `snapshot includes all new Round-7 counter fields`() {
        val m = buildMetrics()
        m.recordSignalingSuccess()
        m.recordSignalingFailure()
        m.recordTurnUsage()
        m.recordTurnFallback()
        val snap = m.snapshot()
        assertTrue(snap.has("signaling_successes"))
        assertTrue(snap.has("signaling_failures"))
        assertTrue(snap.has("turn_usages"))
        assertTrue(snap.has("turn_fallbacks"))
        assertEquals(1, snap.getInt("signaling_successes"))
        assertEquals(1, snap.getInt("signaling_failures"))
        assertEquals(1, snap.getInt("turn_usages"))
        assertEquals(1, snap.getInt("turn_fallbacks"))
    }

    @Test
    fun `snapshot includes pre-existing counter fields`() {
        val snap = buildMetrics().snapshot()
        assertTrue(snap.has("ws_reconnects"))
        assertTrue(snap.has("registration_failures"))
        assertTrue(snap.has("task_successes"))
        assertTrue(snap.has("task_failures"))
        assertTrue(snap.has("handoff_successes"))
        assertTrue(snap.has("handoff_failures"))
        assertTrue(snap.has("handoff_fallbacks"))
    }

    // ── Metric name constants ─────────────────────────────────────────────────

    @Test
    fun `METRIC_SIGNALING_SUCCESSES has galaxy prefix`() {
        assertTrue(MetricsRecorder.METRIC_SIGNALING_SUCCESSES.startsWith("galaxy."))
    }

    @Test
    fun `METRIC_SIGNALING_FAILURES has galaxy prefix`() {
        assertTrue(MetricsRecorder.METRIC_SIGNALING_FAILURES.startsWith("galaxy."))
    }

    @Test
    fun `METRIC_TURN_USAGES has galaxy prefix`() {
        assertTrue(MetricsRecorder.METRIC_TURN_USAGES.startsWith("galaxy."))
    }

    @Test
    fun `METRIC_TURN_FALLBACKS has galaxy prefix`() {
        assertTrue(MetricsRecorder.METRIC_TURN_FALLBACKS.startsWith("galaxy."))
    }

    @Test
    fun `METRIC_SIGNALING_LATENCY_MS has galaxy prefix`() {
        assertTrue(MetricsRecorder.METRIC_SIGNALING_LATENCY_MS.startsWith("galaxy."))
    }

    // ── NoOpTelemetryExporter ─────────────────────────────────────────────────

    @Test
    fun `NoOpTelemetryExporter does not throw`() {
        val noop = NoOpTelemetryExporter
        noop.incrementCounter("galaxy.test", 1, emptyMap())
        noop.recordLatency("galaxy.test", 100L, emptyMap())
        noop.flush()
    }
}

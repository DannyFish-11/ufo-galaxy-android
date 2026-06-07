package com.ufo.galaxy.observability

import com.ufo.galaxy.data.InMemoryAppSettings
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for local-loop metric counters and latency samples added in PR-E.
 *
 * Covers:
 * - All 7 new local-loop counters.
 * - 3 new local-loop latency sample lists.
 * - [TelemetryExporter] wiring for every new counter and latency method.
 * - [MetricsRecorder.snapshot] includes all new local-loop fields.
 * - Metric name constants use the `local_loop.*` prefix.
 */
class LocalLoopMetricsTest {

    private fun buildMetrics(): MetricsRecorder = MetricsRecorder(InMemoryAppSettings())

    /** Counting fake exporter for verifying calls. */
    private inner class CountingExporter : TelemetryExporter {
        val counters = mutableMapOf<String, AtomicInteger>()
        val latencies = mutableListOf<Pair<String, Long>>()

        override fun incrementCounter(name: String, delta: Int, tags: Map<String, String>) {
            counters.getOrPut(name) { AtomicInteger(0) }.addAndGet(delta)
        }

        override fun recordLatency(name: String, valueMs: Long, tags: Map<String, String>) {
            latencies.add(name to valueMs)
        }

        override fun flush() = Unit
    }

    // ── Counter initial values ────────────────────────────────────────────────

    @Test
    fun `localLoopGoalTotal starts at 0`() {
        assertEquals(0, buildMetrics().localLoopGoalTotal.get())
    }

    @Test
    fun `localLoopGoalSuccess starts at 0`() {
        assertEquals(0, buildMetrics().localLoopGoalSuccess.get())
    }

    @Test
    fun `localLoopGoalFailure starts at 0`() {
        assertEquals(0, buildMetrics().localLoopGoalFailure.get())
    }

    @Test
    fun `localLoopReplanCount starts at 0`() {
        assertEquals(0, buildMetrics().localLoopReplanCount.get())
    }

    @Test
    fun `localLoopFallbackCount starts at 0`() {
        assertEquals(0, buildMetrics().localLoopFallbackCount.get())
    }

    @Test
    fun `localLoopNoUiChangeCount starts at 0`() {
        assertEquals(0, buildMetrics().localLoopNoUiChangeCount.get())
    }

    @Test
    fun `localLoopTimeoutCount starts at 0`() {
        assertEquals(0, buildMetrics().localLoopTimeoutCount.get())
    }

    // ── Counter increments ────────────────────────────────────────────────────

    @Test
    fun `recordLocalLoopGoalStart increments localLoopGoalTotal`() {
        val m = buildMetrics()
        m.recordLocalLoopGoalStart()
        assertEquals(1, m.localLoopGoalTotal.get())
    }

    @Test
    fun `recordLocalLoopGoalSuccess increments localLoopGoalSuccess`() {
        val m = buildMetrics()
        m.recordLocalLoopGoalSuccess()
        assertEquals(1, m.localLoopGoalSuccess.get())
    }

    @Test
    fun `recordLocalLoopGoalFailure increments localLoopGoalFailure`() {
        val m = buildMetrics()
        m.recordLocalLoopGoalFailure()
        assertEquals(1, m.localLoopGoalFailure.get())
    }

    @Test
    fun `recordLocalLoopReplan increments localLoopReplanCount`() {
        val m = buildMetrics()
        m.recordLocalLoopReplan()
        assertEquals(1, m.localLoopReplanCount.get())
    }

    @Test
    fun `recordLocalLoopFallback increments localLoopFallbackCount`() {
        val m = buildMetrics()
        m.recordLocalLoopFallback()
        assertEquals(1, m.localLoopFallbackCount.get())
    }

    @Test
    fun `recordLocalLoopNoUiChange increments localLoopNoUiChangeCount`() {
        val m = buildMetrics()
        m.recordLocalLoopNoUiChange()
        assertEquals(1, m.localLoopNoUiChangeCount.get())
    }

    @Test
    fun `recordLocalLoopTimeout increments localLoopTimeoutCount`() {
        val m = buildMetrics()
        m.recordLocalLoopTimeout()
        assertEquals(1, m.localLoopTimeoutCount.get())
    }

    // ── Latency recording ─────────────────────────────────────────────────────

    @Test
    fun `recordLocalLoopStepLatency stores sample`() {
        val m = buildMetrics()
        m.recordLocalLoopStepLatency(100L)
        assertEquals(1, m.localLoopStepLatencyMs.size)
        assertEquals(100L, m.localLoopStepLatencyMs[0])
    }

    @Test
    fun `recordLocalLoopPlannerLatency stores sample`() {
        val m = buildMetrics()
        m.recordLocalLoopPlannerLatency(200L)
        assertEquals(1, m.localLoopPlannerLatencyMs.size)
        assertEquals(200L, m.localLoopPlannerLatencyMs[0])
    }

    @Test
    fun `recordLocalLoopGroundingLatency stores sample`() {
        val m = buildMetrics()
        m.recordLocalLoopGroundingLatency(50L)
        assertEquals(1, m.localLoopGroundingLatencyMs.size)
        assertEquals(50L, m.localLoopGroundingLatencyMs[0])
    }

    @Test
    fun `localLoopStepLatencyMs is bounded to 256 entries`() {
        val m = buildMetrics()
        repeat(300) { m.recordLocalLoopStepLatency(it.toLong()) }
        assertTrue(
            "step latency buffer must not exceed 256 entries",
            m.localLoopStepLatencyMs.size <= 256
        )
    }

    @Test
    fun `localLoopPlannerLatencyMs is bounded to 256 entries`() {
        val m = buildMetrics()
        repeat(300) { m.recordLocalLoopPlannerLatency(it.toLong()) }
        assertTrue(
            "planner latency buffer must not exceed 256 entries",
            m.localLoopPlannerLatencyMs.size <= 256
        )
    }

    @Test
    fun `localLoopGroundingLatencyMs is bounded to 256 entries`() {
        val m = buildMetrics()
        repeat(300) { m.recordLocalLoopGroundingLatency(it.toLong()) }
        assertTrue(
            "grounding latency buffer must not exceed 256 entries",
            m.localLoopGroundingLatencyMs.size <= 256
        )
    }

    // ── TelemetryExporter wiring ──────────────────────────────────────────────

    @Test
    fun `recordLocalLoopGoalStart calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordLocalLoopGoalStart()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_LOCAL_LOOP_GOAL_TOTAL]?.get())
    }

    @Test
    fun `recordLocalLoopGoalSuccess calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordLocalLoopGoalSuccess()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_LOCAL_LOOP_GOAL_SUCCESS]?.get())
    }

    @Test
    fun `recordLocalLoopGoalFailure calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordLocalLoopGoalFailure()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_LOCAL_LOOP_GOAL_FAILURE]?.get())
    }

    @Test
    fun `recordLocalLoopReplan calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordLocalLoopReplan()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_LOCAL_LOOP_REPLAN_COUNT]?.get())
    }

    @Test
    fun `recordLocalLoopFallback calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordLocalLoopFallback()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_LOCAL_LOOP_FALLBACK_COUNT]?.get())
    }

    @Test
    fun `recordLocalLoopNoUiChange calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordLocalLoopNoUiChange()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_LOCAL_LOOP_NO_UI_CHANGE_COUNT]?.get())
    }

    @Test
    fun `recordLocalLoopTimeout calls exporter incrementCounter`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordLocalLoopTimeout()
        assertEquals(1, exp.counters[MetricsRecorder.METRIC_LOCAL_LOOP_TIMEOUT_COUNT]?.get())
    }

    @Test
    fun `recordLocalLoopStepLatency calls exporter recordLatency`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordLocalLoopStepLatency(75L)
        assertEquals(1, exp.latencies.size)
        assertEquals(MetricsRecorder.METRIC_LOCAL_LOOP_STEP_LATENCY_MS, exp.latencies[0].first)
        assertEquals(75L, exp.latencies[0].second)
    }

    @Test
    fun `recordLocalLoopPlannerLatency calls exporter recordLatency`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordLocalLoopPlannerLatency(300L)
        assertEquals(1, exp.latencies.size)
        assertEquals(MetricsRecorder.METRIC_LOCAL_LOOP_PLANNER_LATENCY_MS, exp.latencies[0].first)
        assertEquals(300L, exp.latencies[0].second)
    }

    @Test
    fun `recordLocalLoopGroundingLatency calls exporter recordLatency`() {
        val m = buildMetrics()
        val exp = CountingExporter()
        m.telemetryExporter = exp
        m.recordLocalLoopGroundingLatency(120L)
        assertEquals(1, exp.latencies.size)
        assertEquals(MetricsRecorder.METRIC_LOCAL_LOOP_GROUNDING_LATENCY_MS, exp.latencies[0].first)
        assertEquals(120L, exp.latencies[0].second)
    }

    // ── Snapshot completeness ─────────────────────────────────────────────────

    @Test
    fun `snapshot includes all local-loop counter fields`() {
        val m = buildMetrics()
        m.recordLocalLoopGoalStart()
        m.recordLocalLoopGoalSuccess()
        m.recordLocalLoopGoalFailure()
        m.recordLocalLoopReplan()
        m.recordLocalLoopFallback()
        m.recordLocalLoopNoUiChange()
        m.recordLocalLoopTimeout()
        val snap = m.snapshot()
        assertTrue(snap.has("local_loop_goal_total"))
        assertTrue(snap.has("local_loop_goal_success"))
        assertTrue(snap.has("local_loop_goal_failure"))
        assertTrue(snap.has("local_loop_replan_count"))
        assertTrue(snap.has("local_loop_fallback_count"))
        assertTrue(snap.has("local_loop_no_ui_change_count"))
        assertTrue(snap.has("local_loop_timeout_count"))
        assertEquals(1, snap.getInt("local_loop_goal_total"))
        assertEquals(1, snap.getInt("local_loop_goal_success"))
        assertEquals(1, snap.getInt("local_loop_goal_failure"))
        assertEquals(1, snap.getInt("local_loop_replan_count"))
        assertEquals(1, snap.getInt("local_loop_fallback_count"))
        assertEquals(1, snap.getInt("local_loop_no_ui_change_count"))
        assertEquals(1, snap.getInt("local_loop_timeout_count"))
    }

    // ── Metric name constants ─────────────────────────────────────────────────

    @Test
    fun `METRIC_LOCAL_LOOP_GOAL_TOTAL uses local_loop prefix`() {
        assertTrue(MetricsRecorder.METRIC_LOCAL_LOOP_GOAL_TOTAL.startsWith("local_loop."))
    }

    @Test
    fun `METRIC_LOCAL_LOOP_GOAL_SUCCESS uses local_loop prefix`() {
        assertTrue(MetricsRecorder.METRIC_LOCAL_LOOP_GOAL_SUCCESS.startsWith("local_loop."))
    }

    @Test
    fun `METRIC_LOCAL_LOOP_GOAL_FAILURE uses local_loop prefix`() {
        assertTrue(MetricsRecorder.METRIC_LOCAL_LOOP_GOAL_FAILURE.startsWith("local_loop."))
    }

    @Test
    fun `METRIC_LOCAL_LOOP_STEP_LATENCY_MS uses local_loop prefix`() {
        assertTrue(MetricsRecorder.METRIC_LOCAL_LOOP_STEP_LATENCY_MS.startsWith("local_loop."))
    }

    @Test
    fun `METRIC_LOCAL_LOOP_PLANNER_LATENCY_MS uses local_loop prefix`() {
        assertTrue(MetricsRecorder.METRIC_LOCAL_LOOP_PLANNER_LATENCY_MS.startsWith("local_loop."))
    }

    @Test
    fun `METRIC_LOCAL_LOOP_GROUNDING_LATENCY_MS uses local_loop prefix`() {
        assertTrue(MetricsRecorder.METRIC_LOCAL_LOOP_GROUNDING_LATENCY_MS.startsWith("local_loop."))
    }

    @Test
    fun `METRIC_LOCAL_LOOP_REPLAN_COUNT uses local_loop prefix`() {
        assertTrue(MetricsRecorder.METRIC_LOCAL_LOOP_REPLAN_COUNT.startsWith("local_loop."))
    }

    @Test
    fun `METRIC_LOCAL_LOOP_FALLBACK_COUNT uses local_loop prefix`() {
        assertTrue(MetricsRecorder.METRIC_LOCAL_LOOP_FALLBACK_COUNT.startsWith("local_loop."))
    }

    @Test
    fun `METRIC_LOCAL_LOOP_NO_UI_CHANGE_COUNT uses local_loop prefix`() {
        assertTrue(MetricsRecorder.METRIC_LOCAL_LOOP_NO_UI_CHANGE_COUNT.startsWith("local_loop."))
    }

    @Test
    fun `METRIC_LOCAL_LOOP_TIMEOUT_COUNT uses local_loop prefix`() {
        assertTrue(MetricsRecorder.METRIC_LOCAL_LOOP_TIMEOUT_COUNT.startsWith("local_loop."))
    }
}

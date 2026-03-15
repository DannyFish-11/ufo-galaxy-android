package com.ufo.galaxy.observability

import android.util.Log
import com.ufo.galaxy.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 指标记录器（Metrics Recorder）
 *
 * 追踪以下计数器：
 * - [wsReconnects]          — WebSocket 重连次数
 * - [registrationFailures]  — 跨设备注册失败次数
 * - [taskSuccesses]         — 任务成功完成次数
 * - [taskFailures]          — 任务失败次数
 * - [signalingSuccesses]    — WebRTC signaling sessions completed successfully
 * - [signalingFailures]     — WebRTC signaling sessions that ended in error/timeout
 * - [turnUsages]            — Times TURN relay was used for a WebRTC connection
 * - [turnFallbacks]         — Times ICE failed and TURN-only fallback was triggered
 * - [handoffSuccesses]      — Bridge handoffs accepted by Agent Runtime
 * - [handoffFailures]       — Bridge handoffs that exhausted all retries
 * - [handoffFallbacks]      — Task executions that fell back to local after handoff failure
 *
 * Latency histograms (cumulative buckets, in ms):
 * - [signalingLatencyMs]    — Observed signaling round-trip latencies
 *
 * 每 [LOG_INTERVAL_MS]（5 分钟）在本地写一次日志。
 * 若 [AppSettings.metricsEndpoint] 非空，同时 POST 一份 JSON 报告到该端点。
 *
 * An optional [TelemetryExporter] forwards all counter increments and latency
 * observations to an external pipeline (OTel, StatsD, etc.).  Defaults to
 * [NoOpTelemetryExporter] when no exporter is configured.
 *
 * 使用方式：
 * 1. 在 Application 中构造并调用 [start]。
 * 2. 在对应事件处调用记录方法。
 * 3. 应用退出时调用 [stop]。
 */
class MetricsRecorder(private val settings: AppSettings) {

    companion object {
        private const val TAG = "MetricsRecorder"

        /** 本地日志和可选上报的间隔（5 分钟）。 */
        const val LOG_INTERVAL_MS = 5 * 60 * 1_000L

        // ── Telemetry metric names (stable, use in TelemetryExporter calls) ──

        const val METRIC_WS_RECONNECTS           = "galaxy.ws.reconnects"
        const val METRIC_REGISTRATION_FAILURES   = "galaxy.registration.failures"
        const val METRIC_TASK_SUCCESSES          = "galaxy.task.successes"
        const val METRIC_TASK_FAILURES           = "galaxy.task.failures"
        const val METRIC_SIGNALING_SUCCESSES     = "galaxy.signaling.successes"
        const val METRIC_SIGNALING_FAILURES      = "galaxy.signaling.failures"
        const val METRIC_SIGNALING_LATENCY_MS    = "galaxy.signaling.latency_ms"
        const val METRIC_TURN_USAGES             = "galaxy.turn.usages"
        const val METRIC_TURN_FALLBACKS          = "galaxy.turn.fallbacks"
        const val METRIC_HANDOFF_SUCCESSES       = "galaxy.handoff.successes"
        const val METRIC_HANDOFF_FAILURES        = "galaxy.handoff.failures"
        const val METRIC_HANDOFF_FALLBACKS       = "galaxy.handoff.fallbacks"
    }

    // ── Counters ──────────────────────────────────────────────────────────────

    val wsReconnects = AtomicInteger(0)
    val registrationFailures = AtomicInteger(0)
    val taskSuccesses = AtomicInteger(0)
    val taskFailures = AtomicInteger(0)

    /** Count of WebRTC signaling sessions completed successfully. */
    val signalingSuccesses = AtomicInteger(0)

    /** Count of WebRTC signaling sessions that ended in error or timeout. */
    val signalingFailures = AtomicInteger(0)

    /** Count of WebRTC connections that used TURN relay. */
    val turnUsages = AtomicInteger(0)

    /** Count of times ICE failed and TURN-only fallback was triggered. */
    val turnFallbacks = AtomicInteger(0)

    /** Count of tasks successfully handed off to Agent Runtime via the bridge. */
    val handoffSuccesses = AtomicInteger(0)

    /** Count of bridge handoff attempts that exhausted all retries without success. */
    val handoffFailures = AtomicInteger(0)

    /**
     * Count of times task execution fell back to local after a failed bridge handoff.
     * Distinct from [handoffFailures]: a fallback implies local execution ran as a
     * substitute, whereas a failure with no local fallback is also counted here.
     */
    val handoffFallbacks = AtomicInteger(0)

    // ── Latency samples ────────────────────────────────────────────────────────

    /**
     * Observed signaling round-trip latencies in milliseconds.
     * Bounded to [MAX_LATENCY_SAMPLES] entries; oldest are dropped when full.
     */
    val signalingLatencyMs: MutableList<Long> = java.util.Collections.synchronizedList(
        java.util.LinkedList()
    )
    private val MAX_LATENCY_SAMPLES = 256

    // ── Telemetry exporter ────────────────────────────────────────────────────

    /**
     * Optional external telemetry pipeline.  Defaults to [NoOpTelemetryExporter].
     * Assign a real implementation (StatsD, OTel, etc.) in
     * `UFOGalaxyApplication.onCreate` when a pipeline is available.
     */
    var telemetryExporter: TelemetryExporter = NoOpTelemetryExporter

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var periodicJob: Job? = null
    private val startTimeMs = System.currentTimeMillis()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** 启动定期日志/上报任务。 */
    fun start() {
        periodicJob = scope.launch {
            while (true) {
                delay(LOG_INTERVAL_MS)
                flushMetrics()
            }
        }
        Log.i(TAG, "MetricsRecorder 已启动，上报间隔=${LOG_INTERVAL_MS / 1000}s")
    }

    /** 停止定期任务（立即执行最后一次 flush）。 */
    fun stop() {
        periodicJob?.cancel()
        periodicJob = null
        flushMetrics()
    }

    // ── Recording helpers ─────────────────────────────────────────────────────

    /** 记录一次 WebSocket 重连。 */
    fun recordWsReconnect() {
        wsReconnects.incrementAndGet()
        telemetryExporter.incrementCounter(METRIC_WS_RECONNECTS)
    }

    /** 记录一次跨设备注册失败。 */
    fun recordRegistrationFailure() {
        registrationFailures.incrementAndGet()
        telemetryExporter.incrementCounter(METRIC_REGISTRATION_FAILURES)
    }

    /** 记录一次任务成功。 */
    fun recordTaskSuccess() {
        taskSuccesses.incrementAndGet()
        telemetryExporter.incrementCounter(METRIC_TASK_SUCCESSES)
    }

    /** 记录一次任务失败。 */
    fun recordTaskFailure() {
        taskFailures.incrementAndGet()
        telemetryExporter.incrementCounter(METRIC_TASK_FAILURES)
    }

    /** Records a successful bridge handoff to Agent Runtime. */
    fun recordHandoffSuccess() {
        handoffSuccesses.incrementAndGet()
        telemetryExporter.incrementCounter(METRIC_HANDOFF_SUCCESSES)
    }

    /** Records a bridge handoff that failed (all retries exhausted). */
    fun recordHandoffFailure() {
        handoffFailures.incrementAndGet()
        telemetryExporter.incrementCounter(METRIC_HANDOFF_FAILURES)
    }

    /** Records a fallback to local execution after a failed bridge handoff. */
    fun recordHandoffFallback() {
        handoffFallbacks.incrementAndGet()
        telemetryExporter.incrementCounter(METRIC_HANDOFF_FALLBACKS)
    }

    /** Records a successful WebRTC signaling session. */
    fun recordSignalingSuccess(latencyMs: Long = 0L) {
        signalingSuccesses.incrementAndGet()
        telemetryExporter.incrementCounter(METRIC_SIGNALING_SUCCESSES)
        if (latencyMs > 0L) recordSignalingLatency(latencyMs)
    }

    /** Records a WebRTC signaling session that ended in error or timeout. */
    fun recordSignalingFailure() {
        signalingFailures.incrementAndGet()
        telemetryExporter.incrementCounter(METRIC_SIGNALING_FAILURES)
    }

    /** Records a latency observation for a signaling round-trip. */
    fun recordSignalingLatency(latencyMs: Long) {
        synchronized(signalingLatencyMs) {
            if (signalingLatencyMs.size >= MAX_LATENCY_SAMPLES) {
                (signalingLatencyMs as java.util.LinkedList).removeFirst()
            }
            signalingLatencyMs.add(latencyMs)
        }
        telemetryExporter.recordLatency(METRIC_SIGNALING_LATENCY_MS, latencyMs)
    }

    /** Records a WebRTC connection that used TURN relay. */
    fun recordTurnUsage() {
        turnUsages.incrementAndGet()
        telemetryExporter.incrementCounter(METRIC_TURN_USAGES)
    }

    /** Records an ICE connectivity failure that triggered TURN-only fallback. */
    fun recordTurnFallback() {
        turnFallbacks.incrementAndGet()
        telemetryExporter.incrementCounter(METRIC_TURN_FALLBACKS)
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    /**
     * 构造当前指标快照的 JSON 对象（可用于上报或显示）。
     */
    fun snapshot(): JSONObject = JSONObject().apply {
        put("ws_reconnects", wsReconnects.get())
        put("registration_failures", registrationFailures.get())
        put("task_successes", taskSuccesses.get())
        put("task_failures", taskFailures.get())
        put("signaling_successes", signalingSuccesses.get())
        put("signaling_failures", signalingFailures.get())
        put("turn_usages", turnUsages.get())
        put("turn_fallbacks", turnFallbacks.get())
        put("handoff_successes", handoffSuccesses.get())
        put("handoff_failures", handoffFailures.get())
        put("handoff_fallbacks", handoffFallbacks.get())
        put("uptime_ms", System.currentTimeMillis() - startTimeMs)
        put("ts", System.currentTimeMillis())
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun flushMetrics() {
        val snap = snapshot()
        Log.i(
            TAG,
            "[METRICS] ws_reconnects=${wsReconnects.get()} " +
                "reg_failures=${registrationFailures.get()} " +
                "task_ok=${taskSuccesses.get()} " +
                "task_fail=${taskFailures.get()} " +
                "sig_ok=${signalingSuccesses.get()} " +
                "sig_fail=${signalingFailures.get()} " +
                "turn_used=${turnUsages.get()} " +
                "turn_fallback=${turnFallbacks.get()} " +
                "handoff_ok=${handoffSuccesses.get()} " +
                "handoff_fail=${handoffFailures.get()} " +
                "handoff_fallback=${handoffFallbacks.get()} " +
                "uptime_ms=${snap.optLong("uptime_ms")}"
        )
        GalaxyLogger.log(TAG, mapOf(
            "event" to "metrics_flush",
            "ws_reconnects" to wsReconnects.get(),
            "registration_failures" to registrationFailures.get(),
            "task_successes" to taskSuccesses.get(),
            "task_failures" to taskFailures.get(),
            "signaling_successes" to signalingSuccesses.get(),
            "signaling_failures" to signalingFailures.get(),
            "turn_usages" to turnUsages.get(),
            "turn_fallbacks" to turnFallbacks.get(),
            "handoff_successes" to handoffSuccesses.get(),
            "handoff_failures" to handoffFailures.get(),
            "handoff_fallbacks" to handoffFallbacks.get()
        ))
        val endpoint = settings.metricsEndpoint
        if (endpoint.isNotBlank()) {
            postMetrics(endpoint, snap.toString())
        }
        telemetryExporter.flush()
    }

    private fun postMetrics(endpoint: String, json: String) {
        scope.launch {
            try {
                val body = json.toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(endpoint).post(body).build()
                val response = httpClient.newCall(req).execute()
                val code = response.code
                response.close()
                Log.d(TAG, "Metrics POST $endpoint → HTTP $code")
            } catch (e: Exception) {
                Log.w(TAG, "Metrics POST failed: ${e.message}")
            }
        }
    }
}

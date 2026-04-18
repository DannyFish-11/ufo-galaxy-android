package com.ufo.galaxy.agent

import android.util.Log
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.network.GatewayClient
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.observability.MetricsRecorder
import com.ufo.galaxy.observability.TraceContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * AgentRuntimeBridge — client-side bridge that delegates eligible tasks to
 * Agent Runtime / OpenClawd when the cross-device switch is ON.
 *
 * ## Handoff contract
 * An **eligible** task is one where:
 *  - [AppSettings.crossDeviceEnabled] is `true`, AND
 *  - [HandoffRequest.execMode] is [EXEC_MODE_REMOTE] or [EXEC_MODE_BOTH]
 *    (i.e. the task does NOT require local-only execution).
 *
 * When eligible, the bridge sends a `bridge_handoff` AIP v3 message via
 * [GatewayClient] with the following metadata:
 *  - `trace_id`   — end-to-end trace identifier for log correlation.
 *  - `task_id`    — echoed from the incoming task_assign.
 *  - `exec_mode`  — [EXEC_MODE_REMOTE] / [EXEC_MODE_BOTH] / [EXEC_MODE_LOCAL].
 *  - `route_mode` — [ROUTE_MODE_CROSS_DEVICE] when cross-device is ON.
 *  - `capability` — optional capability name required for execution.
 *  - `session_id` — session-level identifier.
 *  - `context`    — additional key-value context map.
 *
 * ## Idempotency
 * Repeated calls with the same [HandoffRequest.traceId] return the cached
 * [HandoffResult] immediately without re-sending. The registry is bounded
 * to [IDEMPOTENCY_CACHE_MAX] entries; oldest entries are evicted when the
 * limit is reached.
 *
 * ## Retry / backoff
 * The bridge retries up to [MAX_RETRY_ATTEMPTS] times on transient failure
 * (WS send failure or send timeout).  Delays between attempts follow
 * [RETRY_DELAYS_MS] (exponential backoff: 1 s → 2 s → 4 s).
 *
 * ## Fallback
 * When cross-device is OFF or all retry attempts are exhausted, [handoff]
 * returns [HandoffResult.isHandoff] = `false` so that the caller can fall
 * back to local execution. Telemetry is emitted for every outcome via
 * [MetricsRecorder] and [GalaxyLogger].
 *
 * @param gatewayClient   Live gateway transport; used to send bridge messages.
 * @param settings        Persistent settings; [AppSettings.crossDeviceEnabled] gates the bridge.
 * @param metricsRecorder Metrics recorder for handoff telemetry.
 */
class AgentRuntimeBridge(
    private val gatewayClient: GatewayClient,
    private val settings: AppSettings,
    private val metricsRecorder: MetricsRecorder
) {

    // ── Idempotency registry ──────────────────────────────────────────────────

    private val idempotencyRegistry = ConcurrentHashMap<String, HandoffResult>()

    // ── Public data types ─────────────────────────────────────────────────────

    /**
     * Describes a task to be delegated to Agent Runtime.
     *
     * @param traceId    End-to-end trace identifier; used as the idempotency key.
     *                   Callers should generate this once per task and propagate it
     *                   unchanged through every message in the same execution chain.
     * @param taskId     Unique task identifier echoed from the originating task_assign.
     * @param goal       Natural-language objective for the Agent Runtime to execute.
     * @param execMode   Execution mode: [EXEC_MODE_LOCAL] / [EXEC_MODE_REMOTE] / [EXEC_MODE_BOTH].
     * @param routeMode  Routing path: [ROUTE_MODE_CROSS_DEVICE] or [ROUTE_MODE_LOCAL].
     * @param capability Optional capability name required for execution (e.g. "task_execution").
     * @param sessionId  Optional session-level identifier for grouping steps.
     * @param context    Arbitrary additional context forwarded to the Agent Runtime.
     * @param constraints Natural-language constraint strings from the task_assign payload.
     * @param sourceRuntimePosture Canonical source-device participation posture, aligned with the
     *                   server-side semantics from PR #533. Valid values are defined in
     *                   [com.ufo.galaxy.runtime.SourceRuntimePosture]: `"control_only"` (source
     *                   acts purely as a control/initiator) or `"join_runtime"` (source also
     *                   participates as a runtime executor). Defaults to `null`; the gateway
     *                   and Agent Runtime treat `null` as `"control_only"` for backwards
     *                   compatibility. Propagated unchanged into [HandoffEnvelopeV2] and the
     *                   `bridge_handoff` JSON payload.
     *
     * ## V2 source dispatch metadata (PR-D compatibility)
     * The following fields carry richer source-dispatch orchestration metadata forwarded
     * from the inbound [com.ufo.galaxy.protocol.TaskAssignPayload]. All fields are
     * optional so that pre-V2 callers remain compatible. `null` / empty values are
     * omitted from the outbound bridge JSON.
     *
     * @param dispatchIntent    Optional dispatch intent label forwarded from the V2
     *                          orchestrator (e.g. `"task_execute"`, `"staged_handoff"`).
     * @param dispatchOrigin    Optional identifier of the originating orchestrator or
     *                          device that initiated this dispatch.
     * @param orchestrationStage Optional orchestration stage label for multi-stage
     *                          dispatch sequences.
     * @param executionContext  Optional key-value execution context forwarded from the
     *                          V2 orchestrator. Merged into the bridge JSON `context` map.
     */
    data class HandoffRequest(
        val traceId: String,
        val taskId: String,
        val goal: String,
        val execMode: String = EXEC_MODE_REMOTE,
        val routeMode: String = ROUTE_MODE_CROSS_DEVICE,
        val capability: String? = null,
        val sessionId: String? = null,
        val context: Map<String, String> = emptyMap(),
        val constraints: List<String> = emptyList(),
        /**
         * Optional child span identifier.  When blank, a new span is started
         * via [TraceContext.startSpan] at the beginning of [handoff] and ended in the
         * finally block.  Pass a pre-existing span ID to nest this handoff under an
         * outer span (e.g. a task execution span).
         */
        val spanId: String = "",
        val sourceRuntimePosture: String? = null,
        // ── PR-D: V2 source dispatch metadata (optional; null/empty-safe) ────────────
        val dispatchIntent: String? = null,
        val dispatchOrigin: String? = null,
        val orchestrationStage: String? = null,
        val executionContext: Map<String, String> = emptyMap(),
        // ── PR-E: V2 explicit executor target typing (optional; null-safe) ───────────
        val executorTargetType: String? = null
    )

    /**
     * Outcome of a [handoff] call.
     *
     * @param traceId   Echoed from [HandoffRequest.traceId].
     * @param taskId    Echoed from [HandoffRequest.taskId].
     * @param status    `"handoff_sent"` when the message was accepted; `"fallback"` or
     *                  `"local"` when the bridge was skipped.
     * @param isHandoff `true` when the bridge message was successfully sent to the runtime;
     *                  `false` when the caller must fall back to local execution.
     * @param error     Human-readable failure description when [isHandoff] is `false`.
     */
    data class HandoffResult(
        val traceId: String,
        val taskId: String,
        val status: String,
        val isHandoff: Boolean,
        val error: String? = null
    )

    // ── Bridge entry point ────────────────────────────────────────────────────

    /**
     * Attempts to hand off [request] to Agent Runtime / OpenClawd.
     *
     * Decision logic:
     *  1. If cross-device is OFF → return `isHandoff=false` immediately (local path).
     *  2. If [request.execMode] is [EXEC_MODE_LOCAL] → skip bridge; local execution required.
     *  3. If [request.traceId] is already in the idempotency cache → return cached result.
     *  4. Otherwise, attempt to send the bridge message with up to [MAX_RETRY_ATTEMPTS]
     *     retries and exponential backoff. Cache and return the final outcome.
     *
     * This function is **suspend** and must be called from a coroutine.
     *
     * @return [HandoffResult.isHandoff] `true` → bridge message sent; caller should await
     *         the async callback. `false` → caller must execute locally.
     */
    suspend fun handoff(request: HandoffRequest): HandoffResult {
        // ── Span lifecycle ────────────────────────────────────────────────────
        val spanId = request.spanId.ifBlank { TraceContext.startSpan() }

        // ── Dispatcher selection log ──────────────────────────────────────────
        GalaxyLogger.log(
            GalaxyLogger.TAG_DISPATCHER_SELECT, buildMap {
                put("trace_id", request.traceId)
                put("span_id", spanId)
                put("task_id", request.taskId)
                put("exec_mode", request.execMode)
                put("route_mode", request.routeMode)
                if (!request.capability.isNullOrBlank()) put("capability", request.capability)
                if (!request.sessionId.isNullOrBlank()) put("session_id", request.sessionId)
                put("cross_device_on", settings.crossDeviceEnabled)
                if (!request.sourceRuntimePosture.isNullOrBlank()) put("source_runtime_posture", request.sourceRuntimePosture)
            }
        )

        try {
        // ── OFF guard: cross-device switch must be ON ─────────────────────────
        if (!settings.crossDeviceEnabled) {
            Log.d(TAG, "[BRIDGE] cross_device=OFF — skipping handoff trace_id=${request.traceId}")
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "handoff_skipped",
                    "reason" to "cross_device_off",
                    "trace_id" to request.traceId,
                    "span_id" to spanId,
                    "task_id" to request.taskId
                )
            )
            return localResult(request, "cross_device_off")
        }

        // ── exec_mode guard: LOCAL tasks never leave the device ───────────────
        if (request.execMode == EXEC_MODE_LOCAL) {
            Log.d(TAG, "[BRIDGE] exec_mode=local — skipping handoff trace_id=${request.traceId}")
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "handoff_skipped",
                    "reason" to "exec_mode_local",
                    "trace_id" to request.traceId,
                    "span_id" to spanId,
                    "task_id" to request.taskId
                )
            )
            return localResult(request, "exec_mode_local")
        }

        // ── Idempotency: return cached result if already processed ────────────
        idempotencyRegistry[request.traceId]?.let { cached ->
            Log.d(TAG, "[BRIDGE] idempotent hit — returning cached result trace_id=${request.traceId}")
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "handoff_idempotent",
                    "trace_id" to request.traceId,
                    "span_id" to spanId,
                    "task_id" to request.taskId,
                    "status" to cached.status
                )
            )
            return cached
        }

        // ── Bridge handoff log ────────────────────────────────────────────────
        GalaxyLogger.log(
            GalaxyLogger.TAG_BRIDGE_HANDOFF, buildMap {
                put("trace_id", request.traceId)
                put("span_id", spanId)
                put("task_id", request.taskId)
                put("exec_mode", request.execMode)
                put("route_mode", request.routeMode)
                if (!request.capability.isNullOrBlank()) put("capability", request.capability)
                if (!request.sessionId.isNullOrBlank()) put("session_id", request.sessionId)
                if (!request.sourceRuntimePosture.isNullOrBlank()) put("source_runtime_posture", request.sourceRuntimePosture)
            }
        )

        // ── Retry loop with exponential backoff ───────────────────────────────
        var lastError: String? = null
        for (attempt in 0 until MAX_RETRY_ATTEMPTS) {
            if (attempt > 0) {
                val delayMs = RETRY_DELAYS_MS[(attempt - 1).coerceAtMost(RETRY_DELAYS_MS.size - 1)]
                Log.d(
                    TAG,
                    "[BRIDGE] retry attempt=$attempt delay_ms=$delayMs trace_id=${request.traceId}"
                )
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "handoff_retry",
                        "trace_id" to request.traceId,
                        "span_id" to spanId,
                        "attempt" to attempt,
                        "delay_ms" to delayMs
                    )
                )
                delay(delayMs)
            }

            try {
                val result = withTimeout(DEFAULT_HANDOFF_TIMEOUT_MS) {
                    sendBridgeMessage(request)
                }
                // Success: cache and return
                cacheResult(request.traceId, result)
                metricsRecorder.recordHandoffSuccess()
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "handoff_success",
                        "trace_id" to request.traceId,
                        "span_id" to spanId,
                        "task_id" to request.taskId,
                        "attempt" to attempt
                    )
                )
                Log.i(
                    TAG,
                    "[BRIDGE] handoff_success trace_id=${request.traceId} task_id=${request.taskId} attempt=$attempt"
                )
                return result
            } catch (e: TimeoutCancellationException) {
                lastError = "timeout_attempt_$attempt (${DEFAULT_HANDOFF_TIMEOUT_MS}ms)"
                Log.w(TAG, "[BRIDGE] timeout trace_id=${request.traceId} attempt=$attempt error=$lastError")
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "handoff_timeout",
                        "trace_id" to request.traceId,
                        "span_id" to spanId,
                        "attempt" to attempt,
                        "timeout_ms" to DEFAULT_HANDOFF_TIMEOUT_MS
                    )
                )
            } catch (e: Exception) {
                lastError = e.message ?: "unknown_error"
                Log.e(TAG, "[BRIDGE] error trace_id=${request.traceId} attempt=$attempt: $lastError", e)
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "handoff_error",
                        "trace_id" to request.traceId,
                        "span_id" to spanId,
                        "attempt" to attempt,
                        "error" to lastError
                    )
                )
            }
        }

        // ── All retries exhausted: fall back ──────────────────────────────────
        metricsRecorder.recordHandoffFailure()
        metricsRecorder.recordHandoffFallback()
        val fallback = HandoffResult(
            traceId = request.traceId,
            taskId = request.taskId,
            status = STATUS_FALLBACK,
            isHandoff = false,
            error = lastError
        )
        cacheResult(request.traceId, fallback)
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "handoff_fallback",
                "trace_id" to request.traceId,
                "span_id" to spanId,
                "task_id" to request.taskId,
                "error" to lastError
            )
        )
        Log.w(
            TAG,
            "[BRIDGE] handoff_fallback trace_id=${request.traceId} task_id=${request.taskId} error=$lastError"
        )
        return fallback
        } finally {
            // End the span only when we opened it ourselves (spanId was blank in the request).
            if (request.spanId.isBlank()) {
                TraceContext.endSpan()
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Builds and sends the `bridge_handoff` JSON message to the Gateway.
     *
     * The message is a self-describing JSON object (not wrapped in an AIP v3
     * [com.ufo.galaxy.protocol.AipMessage] envelope) so that the gateway bridge
     * endpoint can parse it independently of the standard AIP pipeline.
     *
     * Required fields match the server contract:
     *  - `type`: always `"bridge_handoff"`.
     *  - `trace_id`, `task_id`, `exec_mode`, `route_mode` (always present).
     *  - `capability`, `session_id`, `context`, `constraints` (present when non-null/non-empty).
     *
     * @throws IllegalStateException when [GatewayClient.sendJson] returns false.
     */
    private fun sendBridgeMessage(request: HandoffRequest): HandoffResult {
        val json = buildBridgeJson(request)
        val sent = gatewayClient.sendJson(json)
        if (!sent) {
            throw IllegalStateException(
                "bridge_handoff send failed (WS not connected or send error)"
            )
        }
        return HandoffResult(
            traceId = request.traceId,
            taskId = request.taskId,
            status = STATUS_HANDOFF_SENT,
            isHandoff = true
        )
    }

    /**
     * Builds the JSON payload for a `bridge_handoff` message.
     *
     * The payload is serialised as a [HandoffEnvelopeV2] (H4) so that the gateway
     * bridge endpoint receives the full server-contract structure, including
     * [HandoffEnvelopeV2.runtime_session_id] and [HandoffEnvelopeV2.idempotency_key].
     * All required fields are always present; optional fields are omitted when null/blank/empty.
     *
     * PR-D dispatch metadata fields ([dispatch_intent], [dispatch_origin],
     * [orchestration_stage], [execution_context]) are included when present so that V2
     * source dispatch metadata is forwarded to the gateway bridge endpoint.
     */
    internal fun buildBridgeJson(request: HandoffRequest): String {
        val env = request.toEnvelopeV2()
        return JSONObject().apply {
            put("type", MSG_TYPE_BRIDGE_HANDOFF)
            put("trace_id", env.trace_id)
            put("task_id", env.task_id)
            put("exec_mode", env.exec_mode)
            put("route_mode", env.route_mode)
            put("goal", env.goal)
            if (!env.capability.isNullOrBlank()) put("capability", env.capability)
            if (!env.session_id.isNullOrBlank()) put("session_id", env.session_id)
            if (!env.runtime_session_id.isNullOrBlank()) put("runtime_session_id", env.runtime_session_id)
            if (!env.idempotency_key.isNullOrBlank()) put("idempotency_key", env.idempotency_key)
            if (!env.source_runtime_posture.isNullOrBlank()) put("source_runtime_posture", env.source_runtime_posture)
            if (env.context.isNotEmpty()) put("context", JSONObject(env.context as Map<*, *>))
            if (env.constraints.isNotEmpty()) {
                val arr = org.json.JSONArray()
                env.constraints.forEach { arr.put(it) }
                put("constraints", arr)
            }
            // ── PR-D: V2 source dispatch metadata (omitted when null/empty) ──────────
            if (!env.dispatch_intent.isNullOrBlank()) put("dispatch_intent", env.dispatch_intent)
            if (!env.dispatch_origin.isNullOrBlank()) put("dispatch_origin", env.dispatch_origin)
            if (!env.orchestration_stage.isNullOrBlank()) put("orchestration_stage", env.orchestration_stage)
            if (env.execution_context.isNotEmpty()) put("execution_context", JSONObject(env.execution_context as Map<*, *>))
            // ── PR-E: V2 explicit executor target typing (omitted when null) ──────────
            if (!env.executor_target_type.isNullOrBlank()) put("executor_target_type", env.executor_target_type)
        }.toString()
    }

    /** Returns an [HandoffResult] that indicates local execution should be used. */
    private fun localResult(request: HandoffRequest, reason: String) = HandoffResult(
        traceId = request.traceId,
        taskId = request.taskId,
        status = STATUS_LOCAL,
        isHandoff = false,
        error = reason
    )

    /**
     * Stores [result] in the idempotency cache, evicting an arbitrary entry when the
     * cache has reached [IDEMPOTENCY_CACHE_MAX] entries to prevent unbounded growth.
     *
     * **Eviction policy**: [ConcurrentHashMap] has no defined iteration order, so the
     * entry removed is non-deterministic — this is an approximation of a bounded cache,
     * not a strict FIFO or LRU. This is intentional: the cache is bounded purely to
     * avoid memory growth; strict ordering is not required for correctness. In the worst
     * case a recently-used entry is evicted, causing one redundant bridge send for that
     * trace_id — an acceptable trade-off for operational simplicity.
     */
    private fun cacheResult(traceId: String, result: HandoffResult) {
        if (idempotencyRegistry.size >= IDEMPOTENCY_CACHE_MAX) {
            idempotencyRegistry.keys.firstOrNull()?.let { idempotencyRegistry.remove(it) }
        }
        idempotencyRegistry[traceId] = result
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "GALAXY:BRIDGE"

        // ── Message type ──────────────────────────────────────────────────────

        /** AIP v3 message type for bridge handoff messages. */
        const val MSG_TYPE_BRIDGE_HANDOFF = "bridge_handoff"

        // ── exec_mode constants ───────────────────────────────────────────────

        /** Task must execute locally; bridge delegation is skipped. */
        const val EXEC_MODE_LOCAL = "local"

        /** Task should execute remotely via Agent Runtime; bridge delegation is preferred. */
        const val EXEC_MODE_REMOTE = "remote"

        /** Task can execute either locally or remotely; bridge delegation is attempted first. */
        const val EXEC_MODE_BOTH = "both"

        // ── route_mode constants ──────────────────────────────────────────────

        /** Cross-device routing via Gateway / Agent Runtime. */
        const val ROUTE_MODE_CROSS_DEVICE = "cross_device"

        /** Local-only routing (cross-device OFF). */
        const val ROUTE_MODE_LOCAL = "local"

        // ── HandoffResult status values ───────────────────────────────────────

        /** Bridge message sent successfully; Agent Runtime is handling the task. */
        const val STATUS_HANDOFF_SENT = "handoff_sent"

        /** All retries failed; caller should fall back to local execution. */
        const val STATUS_FALLBACK = "fallback"

        /** Bridge was skipped; local execution should be used. */
        const val STATUS_LOCAL = "local"

        // ── Retry / timeout config ────────────────────────────────────────────

        /** Per-attempt handoff timeout in milliseconds. Default: 30 s. */
        const val DEFAULT_HANDOFF_TIMEOUT_MS = 30_000L

        /** Maximum number of send attempts before falling back to local execution. */
        const val MAX_RETRY_ATTEMPTS = 3

        /** Exponential backoff delays between retry attempts (in milliseconds). */
        val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)

        // ── Idempotency ───────────────────────────────────────────────────────

        /** Maximum number of cached [HandoffResult] entries in the idempotency registry. */
        const val IDEMPOTENCY_CACHE_MAX = 200
    }
}

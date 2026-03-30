package com.ufo.galaxy.input

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.local.LocalLoopExecutor
import com.ufo.galaxy.local.LocalLoopOptions
import com.ufo.galaxy.local.LocalLoopResult
import com.ufo.galaxy.network.GatewayClient
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.TaskSubmitPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Unified task/input dispatch authority** for both [com.ufo.galaxy.ui.viewmodel.MainViewModel]
 * and [com.ufo.galaxy.service.EnhancedFloatingService].
 *
 * This class is the **single point where the local-vs-cross-device mode decision is made**.
 * All user input — text and voice — must pass through [route]; no component may bypass it
 * to call [LocalLoopExecutor.execute] or [GatewayClient.sendJson] directly for user-initiated
 * task submission.
 *
 * Routing rules (P2 strong-consistency):
 *  - [AppSettings.crossDeviceEnabled] = false →
 *    **always** local; [LocalLoopExecutor.execute] is launched in [coroutineScope];
 *    result delivered to [onLocalResult]. Task-submit uplink is strictly forbidden.
 *  - [AppSettings.crossDeviceEnabled] = true **and** WS connected →
 *    wraps [text] in a [TaskSubmitPayload] AIP v3 envelope and sends it uplink via
 *    [GatewayClient]. Result arrives later via the WS task_assign/goal_result flow.
 *  - [AppSettings.crossDeviceEnabled] = true **and** WS NOT connected →
 *    [onError] is invoked with a human-readable reason; does NOT silently fall back to local.
 *
 * Both entry points must route all user input (text and voice) through **this single class**
 * to guarantee consistent behaviour and avoid duplicate remote-handoff logic.
 *
 * @param settings           Persistent settings; [AppSettings.crossDeviceEnabled] gates the WS path.
 * @param webSocketClient    Live gateway client; used to check connectivity and send messages.
 *                           [com.ufo.galaxy.network.GalaxyWebSocketClient] implements this interface;
 *                           tests inject a lightweight fake.
 * @param localLoopExecutor  Canonical local execution entrypoint; invoked when cross-device is OFF.
 *                           The production implementation is [com.ufo.galaxy.local.DefaultLocalLoopExecutor]
 *                           (wired in [com.ufo.galaxy.UFOGalaxyApplication]).
 * @param coroutineScope     Scope in which local [LocalLoopExecutor.execute] is launched
 *                           (e.g. viewModelScope).
 * @param onLocalResult      Called on the IO thread when a local task completes or fails.
 *                           Use to update UI or log results. Optional.
 * @param onError            Called with a human-readable reason when cross-device routing fails
 *                           (WS unavailable or send error). Callers must surface this in the UI.
 */
class InputRouter(
    private val settings: AppSettings,
    private val webSocketClient: GatewayClient,
    private val localLoopExecutor: LocalLoopExecutor,
    private val coroutineScope: CoroutineScope,
    private val onLocalResult: ((LocalLoopResult) -> Unit)? = null,
    private val onError: ((reason: String) -> Unit)? = null
) {
    private val gson = Gson()

    /**
     * Thread-safety / double-submit guard.
     *
     * [route] uses [AtomicBoolean.compareAndSet] so that a concurrent or re-entrant call
     * while a synchronous routing decision is in progress is detected and dropped immediately
     * rather than racing on [AppSettings.crossDeviceEnabled] or [GatewayClient.sendJson].
     *
     * The guard is held only for the duration of the synchronous routing logic; the async
     * [LocalLoopExecutor.execute] coroutine launched for LOCAL paths runs outside the guard so it
     * does not prevent the callers' own in-flight check (e.g. [MainViewModel] `isLoading`)
     * from working correctly.
     */
    private val _isRouting = AtomicBoolean(false)

    /**
     * Describes which routing path was taken by [route].
     */
    enum class RouteMode {
        /** Input was dispatched to the canonical [LocalLoopExecutor] (crossDeviceEnabled=false). */
        LOCAL,
        /** Input was sent uplink via WebSocket as an AIP v3 task_submit (crossDeviceEnabled=true, WS connected). */
        CROSS_DEVICE,
        /** Cross-device was requested but WS was unavailable; error surfaced via [onError]. */
        ERROR
    }

    /**
     * Routes [text] to the correct execution path.
     *
     * - `crossDeviceEnabled=false` → local only; launches [LocalLoopExecutor.execute] in [coroutineScope];
     *   returns [RouteMode.LOCAL].
     * - `crossDeviceEnabled=true && connected` → AIP v3 task_submit WS uplink; returns [RouteMode.CROSS_DEVICE].
     * - `crossDeviceEnabled=true && !connected` → [onError] invoked; returns [RouteMode.ERROR].
     *   Does NOT fall back to local execution.
     *
     * Blank / whitespace-only input is ignored and [RouteMode.LOCAL] is returned without
     * invoking any callbacks.
     *
     * @param text     Natural-language input from the user (text or voice transcript).
     * @param deviceId Stable device identifier. Defaults to the Android build identity.
     * @return The [RouteMode] describing which path was taken.
     */
    fun route(
        text: String,
        deviceId: String = "${Build.MANUFACTURER}_${Build.MODEL}"
    ): RouteMode {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return RouteMode.LOCAL

        // Thread-safe double-submit guard: if a concurrent synchronous routing call is already
        // in progress on another thread, drop the duplicate immediately instead of racing.
        if (!_isRouting.compareAndSet(false, true)) {
            Log.d(TAG, "[ROUTE] Concurrent route() call dropped (double-submit guard)")
            return RouteMode.LOCAL
        }

        return try {
            routeInternal(trimmed, deviceId)
        } finally {
            _isRouting.set(false)
        }
    }

    private fun routeInternal(trimmed: String, deviceId: String): RouteMode {
        val taskId = UUID.randomUUID().toString()
        val crossDevice = settings.crossDeviceEnabled
        val wsConnected = webSocketClient.isConnected()

        return when {
            !crossDevice -> {
                Log.i(TAG, "[ROUTE] route_mode=local task_id=$taskId device_id=$deviceId")
                GalaxyLogger.log(TAG, mapOf("event" to "route_local", "task_id" to taskId))
                launchLocal(trimmed)
                RouteMode.LOCAL
            }
            wsConnected -> {
                sendViaWebSocket(trimmed, deviceId, taskId)
            }
            else -> {
                // crossDeviceEnabled=true but WS not connected → explicit error, no silent fallback.
                val reason = "跨设备模式已开启，但 WebSocket 未连接，无法发送任务。请检查网络或关闭跨设备模式。"
                Log.e(TAG, "[ROUTE] route_mode=error task_id=$taskId device_id=$deviceId reason=$reason")
                GalaxyLogger.log(TAG, mapOf("event" to "route_error", "task_id" to taskId, "reason" to reason))
                onError?.invoke(reason)
                RouteMode.ERROR
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Launches [LocalLoopExecutor.execute] for [text] in [coroutineScope] on [Dispatchers.IO].
     * On completion, [onLocalResult] is invoked with the [LocalLoopResult].
     */
    private fun launchLocal(text: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val result = localLoopExecutor.execute(LocalLoopOptions(instruction = text))
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "local_done",
                        "session_id" to result.sessionId,
                        "status" to result.status,
                        "steps" to result.stepCount
                    )
                )
                onLocalResult?.invoke(result)
            } catch (e: Exception) {
                Log.e(TAG, "[ROUTE] local execution error: ${e.message}", e)
                GalaxyLogger.log(TAG, mapOf("event" to "local_error", "error" to (e.message ?: "unknown")))
            }
        }
    }

    /**
     * Wraps [text] in an AIP v3 [TaskSubmitPayload] envelope and sends it via [webSocketClient].
     *
     * Two distinct identifiers are used (matching the AIP v3 schema):
     *  - [taskId]: the message-level correlation identifier included in [AipMessage.correlation_id]
     *    so that the reply (`task_assign`) can be matched to this submission.  [taskId] is also
     *    copied into [TaskSubmitPayload.task_id] so the gateway can see it in the payload.
     *  - `sessionId`: the session-level identifier in [TaskSubmitPayload.session_id] that the
     *    Gateway uses to group steps within a single user request.
     *
     * The payload is validated via [TaskSubmitPayload.validate] before sending; a validation
     * failure is treated as an internal error and surfaced via [onError].
     */
    private fun sendViaWebSocket(text: String, deviceId: String, taskId: String): RouteMode {
        val sessionId = UUID.randomUUID().toString() // session-level ID within the payload
        val payload = TaskSubmitPayload(
            task_text = text,
            device_id = deviceId,
            session_id = sessionId,
            task_id = taskId
        )
        if (!payload.validate()) {
            val fieldError = payload.validationError() ?: "unknown field"
            val reason = "TaskSubmitPayload 验证失败：$fieldError。"
            Log.e(TAG, "[ROUTE] route_mode=error task_id=$taskId reason=payload_validation_failed ($fieldError)")
            GalaxyLogger.log(TAG, mapOf("event" to "route_error", "task_id" to taskId, "reason" to "payload_validation_failed", "field" to fieldError))
            onError?.invoke(reason)
            return RouteMode.ERROR
        }
        val envelope = AipMessage(
            type = MsgType.TASK_SUBMIT,
            payload = payload,
            correlation_id = taskId,
            device_id = deviceId,
            trace_id = taskId,      // use task_id as the initial trace_id for this submission
            route_mode = "cross_device"
        )
        val json = gson.toJson(envelope)
        val sent = webSocketClient.sendJson(json)
        return if (sent) {
            Log.i(TAG, "[ROUTE] route_mode=cross_device task_id=$taskId device_id=$deviceId text=${text.take(60)}")
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "route_cross_device",
                    "task_id" to taskId,
                    "session_id" to sessionId
                )
            )
            RouteMode.CROSS_DEVICE
        } else {
            val reason = "跨设备发送失败（连接已断开），请重试或关闭跨设备模式。"
            Log.e(TAG, "[ROUTE] route_mode=error task_id=$taskId reason=send_failed")
            GalaxyLogger.log(TAG, mapOf("event" to "route_error", "task_id" to taskId, "reason" to "send_failed"))
            onError?.invoke(reason)
            RouteMode.ERROR
        }
    }

    companion object {
        private const val TAG = "InputRouter"
    }
}

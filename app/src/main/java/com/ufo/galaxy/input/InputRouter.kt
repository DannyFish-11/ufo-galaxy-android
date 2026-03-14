package com.ufo.galaxy.input

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.loop.LoopResult
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.TaskSubmitPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Unified input router used by both [com.ufo.galaxy.ui.viewmodel.MainViewModel] and
 * [com.ufo.galaxy.service.EnhancedFloatingService] to dispatch text and voice input.
 *
 * Routing rules (P2 strong-consistency):
 *  - [AppSettings.crossDeviceEnabled] = false →
 *    **always** local; [LoopController.execute] is launched in [coroutineScope];
 *    result delivered to [onLocalResult]. Task-submit uplink is strictly forbidden.
 *  - [AppSettings.crossDeviceEnabled] = true **and** WS connected →
 *    wraps [text] in a [TaskSubmitPayload] AIP v3 envelope and sends it uplink via
 *    [GalaxyWebSocketClient]. Result arrives later via the WS task_assign/goal_result flow.
 *  - [AppSettings.crossDeviceEnabled] = true **and** WS NOT connected →
 *    [onError] is invoked with a human-readable reason; does NOT silently fall back to local.
 *
 * Both entry points must route all user input (text and voice) through **this single class**
 * to guarantee consistent behaviour and avoid duplicate remote-handoff logic.
 *
 * @param settings        Persistent settings; [AppSettings.crossDeviceEnabled] gates the WS path.
 * @param webSocketClient Live WebSocket client; used to check connectivity and send messages.
 * @param loopController  Local closed-loop automation controller; invoked when cross-device is OFF.
 * @param coroutineScope  Scope in which local [LoopController.execute] is launched (e.g. viewModelScope).
 * @param onLocalResult   Called on the IO thread when a local task completes or fails.
 *                        Use to update UI or log results. Optional.
 * @param onError         Called with a human-readable reason when cross-device routing fails
 *                        (WS unavailable or send error). Callers must surface this in the UI.
 */
class InputRouter(
    private val settings: AppSettings,
    private val webSocketClient: GalaxyWebSocketClient,
    private val loopController: LoopController,
    private val coroutineScope: CoroutineScope,
    private val onLocalResult: ((LoopResult) -> Unit)? = null,
    private val onError: ((reason: String) -> Unit)? = null
) {
    private val gson = Gson()

    /**
     * Describes which routing path was taken by [route].
     */
    enum class RouteMode {
        /** Input was dispatched to local [LoopController] (crossDeviceEnabled=false). */
        LOCAL,
        /** Input was sent uplink via WebSocket as an AIP v3 task_submit (crossDeviceEnabled=true, WS connected). */
        CROSS_DEVICE,
        /** Cross-device was requested but WS was unavailable; error surfaced via [onError]. */
        ERROR
    }

    /**
     * Routes [text] to the correct execution path.
     *
     * - `crossDeviceEnabled=false` → local only; launches [LoopController.execute] in [coroutineScope];
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
     * Launches [LoopController.execute] for [text] in [coroutineScope] on [Dispatchers.IO].
     * On completion, [onLocalResult] is invoked with the [LoopResult].
     */
    private fun launchLocal(text: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val result = loopController.execute(text)
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "local_done",
                        "session_id" to result.sessionId,
                        "status" to result.status,
                        "steps" to result.steps.size
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
     *    so that the reply (`task_assign`) can be matched to this submission.
     *  - `sessionId`: the session-level identifier in [TaskSubmitPayload.session_id] that the
     *    Gateway uses to group steps within a single user request.
     */
    private fun sendViaWebSocket(text: String, deviceId: String, taskId: String): RouteMode {
        val sessionId = UUID.randomUUID().toString() // session-level ID within the payload
        val payload = TaskSubmitPayload(
            task_text = text,
            device_id = deviceId,
            session_id = sessionId
        )
        val envelope = AipMessage(
            type = MsgType.TASK_SUBMIT,
            payload = payload,
            correlation_id = taskId,
            device_id = deviceId
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

package com.ufo.galaxy.network

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.TaskSubmitPayload
import java.util.UUID

/**
 * Unified message router for all user input (text/voice from MainActivity and floating window).
 *
 * Routing rules (P2 strong-consistency):
 *  - [AppSettings.crossDeviceEnabled] = false →
 *    **always** local; task_submit uplink is strictly forbidden in this mode.
 *  - [AppSettings.crossDeviceEnabled] = true **and** WS connected →
 *    wraps [text] in a [TaskSubmitPayload] envelope and sends it uplink via [GalaxyWebSocketClient].
 *  - [AppSettings.crossDeviceEnabled] = true **and** WS NOT connected →
 *    explicit error via [onError]; does NOT silently fall back to local execution.
 *
 * All routing decisions are logged with the structured fields
 * `route_mode`, `task_id`, and `device_id` for full-chain traceability.
 *
 * Both [com.ufo.galaxy.ui.viewmodel.MainViewModel] and
 * [com.ufo.galaxy.service.EnhancedFloatingService] (and any future input surface) must
 * route user input through this single class to guarantee consistent behaviour.
 *
 * @param settings        Persistent settings; [AppSettings.crossDeviceEnabled] gates the WS path.
 * @param webSocketClient Live WebSocket client; used to check connectivity and send messages.
 * @param onError         Called with a human-readable reason when cross-device routing fails
 *                        (WS unavailable). Callers should surface this in the UI.
 * @param localFallback   Invoked with the raw text string when the **local** execution path
 *                        is chosen (i.e., [AppSettings.crossDeviceEnabled] is false).
 */
class MessageRouter(
    private val settings: AppSettings,
    private val webSocketClient: GalaxyWebSocketClient,
    private val onError: ((reason: String) -> Unit)? = null,
    private val localFallback: (text: String) -> Unit
) {
    private val gson = Gson()

    /**
     * Routes [text] to the correct execution path.
     *
     * - `crossDeviceEnabled=false` → local only; returns `RouteMode.LOCAL`.
     * - `crossDeviceEnabled=true && connected` → WS uplink; returns `RouteMode.CROSS_DEVICE`.
     * - `crossDeviceEnabled=true && !connected` → error surfaced; returns `RouteMode.ERROR`.
     *   Does NOT fall back to local execution – the caller must decide how to proceed.
     *
     * @param text     Natural-language input from the user.
     * @param deviceId Stable device identifier (e.g., "$Manufacturer_$Model").
     *                 Defaults to the Android build identity.
     * @return The [RouteMode] that describes which path was taken.
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
                localFallback(trimmed)
                RouteMode.LOCAL
            }
            wsConnected -> {
                sendViaWebSocket(trimmed, deviceId, taskId)
            }
            else -> {
                // crossDeviceEnabled=true but WS is not available → explicit error, no fallback
                val reason = "跨设备模式已开启，但 WebSocket 未连接，无法发送任务。请检查网络或关闭跨设备模式。"
                Log.e(TAG, "[ROUTE] route_mode=error task_id=$taskId device_id=$deviceId reason=$reason")
                onError?.invoke(reason)
                RouteMode.ERROR
            }
        }
    }

    private fun sendViaWebSocket(text: String, deviceId: String, taskId: String): RouteMode {
        val sessionId = UUID.randomUUID().toString()
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
        if (sent) {
            Log.i(TAG, "[ROUTE] route_mode=cross_device task_id=$taskId device_id=$deviceId text=${text.take(60)}")
            return RouteMode.CROSS_DEVICE
        } else {
            // WS send failed after connectivity check (e.g., connection dropped between check and send)
            val reason = "跨设备发送失败（连接已断开），请重试或关闭跨设备模式。"
            Log.e(TAG, "[ROUTE] route_mode=error task_id=$taskId device_id=$deviceId reason=send_failed")
            onError?.invoke(reason)
            return RouteMode.ERROR
        }
    }

    /** Describes which routing path was taken by [route]. */
    enum class RouteMode {
        /** Message was sent to local execution pipeline (crossDeviceEnabled=false). */
        LOCAL,
        /** Message was sent uplink via WebSocket (crossDeviceEnabled=true, WS connected). */
        CROSS_DEVICE,
        /** Cross-device was requested but WS was unavailable; error surfaced to caller. */
        ERROR
    }

    companion object {
        private const val TAG = "MessageRouter"
    }
}

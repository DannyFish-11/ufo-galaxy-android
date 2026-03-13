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
 * Routing rules:
 *  - [AppSettings.crossDeviceEnabled] = true **and** WS connected →
 *    wraps [text] in a [TaskSubmitPayload] envelope and sends it uplink via [GalaxyWebSocketClient].
 *  - Otherwise → delegates to [localFallback] for on-device execution.
 *
 * Both [com.ufo.galaxy.ui.viewmodel.MainViewModel] and
 * [com.ufo.galaxy.service.EnhancedFloatingService] (and any future input surface) must
 * route user input through this single class to guarantee consistent behaviour.
 *
 * @param settings        Persistent settings; [AppSettings.crossDeviceEnabled] gates the WS path.
 * @param webSocketClient Live WebSocket client; used to check connectivity and send messages.
 * @param localFallback   Invoked with the raw text string when the local execution path is chosen.
 */
class MessageRouter(
    private val settings: AppSettings,
    private val webSocketClient: GalaxyWebSocketClient,
    private val localFallback: (text: String) -> Unit
) {
    private val gson = Gson()

    /**
     * Routes [text] to the correct execution path:
     * cross-device WS uplink or local fallback.
     *
     * @param text     Natural-language input from the user.
     * @param deviceId Stable device identifier (e.g., "$Manufacturer_$Model").
     *                 Defaults to the Android build identity.
     * @return `true` if the message was sent via WebSocket; `false` if local fallback was used.
     */
    fun route(
        text: String,
        deviceId: String = "${Build.MANUFACTURER}_${Build.MODEL}"
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        val crossDevice = settings.crossDeviceEnabled
        val wsConnected = webSocketClient.isConnected()

        return if (crossDevice && wsConnected) {
            sendViaWebSocket(trimmed, deviceId)
        } else {
            Log.d(TAG, "[ROUTE] local (crossDevice=$crossDevice connected=$wsConnected)")
            localFallback(trimmed)
            false
        }
    }

    private fun sendViaWebSocket(text: String, deviceId: String): Boolean {
        val sessionId = UUID.randomUUID().toString()
        val payload = TaskSubmitPayload(
            task_text = text,
            device_id = deviceId,
            session_id = sessionId
        )
        val envelope = AipMessage(
            type = MsgType.TASK_SUBMIT,
            payload = payload,
            device_id = deviceId
        )
        val json = gson.toJson(envelope)
        val sent = webSocketClient.sendJson(json)
        Log.i(TAG, "[ROUTE] ws sent=$sent text=${text.take(60)}")
        if (!sent) {
            // WS send failed (e.g., connection dropped between check and send) → local fallback
            Log.w(TAG, "[ROUTE] WS send failed; falling back to local execution")
            localFallback(text)
        }
        return sent
    }

    companion object {
        private const val TAG = "MessageRouter"
    }
}

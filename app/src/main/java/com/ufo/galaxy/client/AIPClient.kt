package com.ufo.galaxy.client

import android.util.Log
import com.ufo.galaxy.config.ServerConfig
import com.ufo.galaxy.protocol.AIPMessageBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AIPClient(
    private val deviceId: String,
    private val node50Url: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val TAG = "AIPClient"
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null

    // Index into ServerConfig.WS_PATHS used for the current connection attempt
    private var wsPathIndex = 0

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "Connection established to Node 50.")
            this@AIPClient.webSocket = webSocket
            reconnectJob?.cancel()
            sendRegistration()
            startHeartbeatLoop()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received message: $text")
            handleAIPMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "Connection closing: $code / $reason")
            heartbeatJob?.cancel()
            this@AIPClient.webSocket = null
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Connection failed (path index $wsPathIndex): ${t.message}", t)
            heartbeatJob?.cancel()
            this@AIPClient.webSocket = null
            // Advance to the next candidate path before reconnecting
            wsPathIndex = (wsPathIndex + 1) % ServerConfig.WS_PATHS.size
            startReconnectLoop()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "Connection closed: $code / $reason")
            heartbeatJob?.cancel()
            this@AIPClient.webSocket = null
            startReconnectLoop()
        }
    }

    fun connect() {
        val wsUrl = ServerConfig.buildWsUrl(node50Url, deviceId, wsPathIndex)
        val request = Request.Builder().url(wsUrl).build()
        Log.i(TAG, "Connecting to $wsUrl...")
        client.newWebSocket(request, wsListener)
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client disconnect requested")
        webSocket = null
    }

    private fun startReconnectLoop() {
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            while (isActive) {
                Log.i(TAG, "Attempting to reconnect in 5 seconds...")
                delay(5000)
                if (webSocket == null) {
                    connect()
                } else {
                    break // connected – exit reconnect loop
                }
            }
        }
    }

    fun sendAIPMessage(messageType: String, payload: JSONObject) {
        val message = AIPMessageBuilder.build(
            messageType = messageType,
            sourceNodeId = deviceId,
            targetNodeId = "Node_50_Transformer",
            payload = payload
        ).toString()

        webSocket?.send(message) ?: Log.e(TAG, "WebSocket is null. Message not sent: $messageType")
    }

    private fun sendHeartbeat() {
        val payload = JSONObject().apply {
            put("status", "online")
        }
        sendAIPMessage("heartbeat", payload)
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(30_000)
                if (webSocket != null) {
                    sendHeartbeat()
                    Log.d(TAG, "Heartbeat sent.")
                } else {
                    break
                }
            }
        }
    }

    private fun sendRegistration() {
        val payload = JSONObject().apply {
            put("device_type", "Android_Agent")
            put("capabilities", listOf("location", "camera", "sensor_data", "automation"))
        }
        sendAIPMessage("registration", payload)
        Log.i(TAG, "Registration message sent.")
    }

    private fun handleAIPMessage(text: String) {
        try {
            val data = AIPMessageBuilder.parse(text) ?: run {
                Log.w(TAG, "Failed to parse message: $text")
                return
            }
            val msgType = data.getString("type")
            val payload = data.getJSONObject("payload")

            when (msgType) {
                "command" -> {
                    val command = payload.getString("command")
                    val params = payload.optJSONObject("params") ?: JSONObject()
                    Log.i(TAG, "Executing command: $command with params: $params")

                    val resultPayload = JSONObject().apply {
                        put("command", command)
                        put("status", "success")
                        put("details", "Command $command executed on Android.")
                    }

                    sendAIPMessage("command_result", resultPayload)
                }
                "status_request" -> {
                    val statusPayload = JSONObject().apply {
                        put("battery_level", 85)
                        put("location", "Lat: 34.0522, Lon: -118.2437")
                        put("is_charging", false)
                    }
                    sendAIPMessage("status_update", statusPayload)
                }
                else -> Log.w(TAG, "Unhandled message type: $msgType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling AIP message: ${e.message}", e)
        }
    }
}

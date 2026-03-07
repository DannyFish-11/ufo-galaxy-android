package com.ufo.galaxy.webrtc

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebRTC signaling client that connects to the Galaxy Gateway proxy via
 * `ws://<host>/ws/webrtc/{device_id}` and exchanges JSON signaling messages
 * (offer / answer / ice_candidate).
 *
 * Follows the same OkHttp WebSocket pattern used by the rest of the application.
 *
 * Usage:
 * ```kotlin
 * val url = ServerConfig.buildWebRtcWsUrl(baseUrl, deviceId)
 * val client = WebRTCSignalingClient(
 *     url        = url,
 *     onMessage  = { msg -> webRtcManager.handleSignalingMessage(msg) },
 *     onConnected    = { /* ready to send offer */ },
 *     onDisconnected = { /* handle loss */ }
 * )
 * client.connect()
 * ```
 */
class WebRTCSignalingClient(
    private val url: String,
    private val onMessage: (SignalingMessage) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    private val TAG = "WebRTCSignalingClient"

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "Signaling WS connected: $url")
            isConnected = true
            webSocket = ws
            onConnected()
        }

        override fun onMessage(ws: WebSocket, text: String) {
            Log.d(TAG, "Signaling message received: $text")
            val message = SignalingMessage.fromJsonString(text)
            if (message != null) {
                onMessage(message)
            } else {
                Log.w(TAG, "Failed to parse signaling message: $text")
            }
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "Signaling WS closing: $code $reason")
            ws.close(1000, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "Signaling WS closed: $code $reason")
            isConnected = false
            webSocket = null
            onDisconnected()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Signaling WS failure: ${t.message}", t)
            isConnected = false
            webSocket = null
            onDisconnected()
        }
    }

    /**
     * Open the WebSocket connection to the gateway signaling endpoint.
     * Safe to call from any thread.
     */
    fun connect() {
        Log.i(TAG, "Connecting to WebRTC signaling endpoint: $url")
        val request = Request.Builder().url(url).build()
        httpClient.newWebSocket(request, wsListener)
    }

    /**
     * Send a typed [SignalingMessage] to the gateway.
     */
    fun send(message: SignalingMessage) {
        val json = message.toJson().toString()
        Log.d(TAG, "Sending signaling message type=${message.type}")
        webSocket?.send(json) ?: Log.w(TAG, "Cannot send: signaling WS not connected")
    }

    /**
     * Send an arbitrary JSON object to the gateway (e.g., for extensions).
     */
    fun sendJson(json: JSONObject) {
        Log.d(TAG, "Sending raw signaling JSON: ${json.optString("type")}")
        webSocket?.send(json.toString()) ?: Log.w(TAG, "Cannot send: signaling WS not connected")
    }

    /**
     * Close the WebSocket connection gracefully.
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting signaling WS")
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isConnected = false
    }
}

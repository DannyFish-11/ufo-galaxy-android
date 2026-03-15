package com.ufo.galaxy.webrtc

import android.util.Log
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.observability.TraceContext
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
 * (offer / answer / ice_candidate / ice_candidates / error).
 *
 * Follows the same OkHttp WebSocket pattern used by the rest of the application.
 *
 * ### Round-6 multi-candidate support
 * - Accepts both legacy `ice_candidate` (single) and `ice_candidates` (batch/trickle) messages.
 * - Forwards all candidates to an [IceCandidateManager] which handles deduplication,
 *   priority ordering (relay → srflx → host), and TURN fallback.
 * - TURN server configuration delivered alongside candidates is stored and accessible via
 *   [lastTurnConfig].
 * - Error messages from the gateway are surfaced via [onError] and tagged with [traceId].
 *
 * ### Retry / backoff
 * The client does **not** automatically reconnect the signaling socket (the peer-connection
 * lifecycle already handles recovery). However [IceCandidateManager.startTurnFallback] is
 * called automatically when ICE connectivity fails and relay candidates are available.
 *
 * ### Backward compatibility
 * Legacy single-candidate paths (`ice_candidate` type) still work without any change at
 * the call-site.
 *
 * Usage:
 * ```kotlin
 * val manager = IceCandidateManager(traceId = "abc-123") { c -> peerConn.addIceCandidate(c) }
 * val url = "ws://100.64.0.1:8765/ws/webrtc/$deviceId"
 * val client = WebRTCSignalingClient(
 *     url = url,
 *     traceId = "abc-123",
 *     candidateManager = manager,
 *     onMessage  = { msg -> handleSdp(msg) },
 *     onConnected    = { sendOffer() },
 *     onDisconnected = { /* handle loss */ },
 *     onError        = { err -> showError(err) }
 * )
 * client.connect()
 * ```
 *
 * @param url              WebSocket URL for the signaling endpoint.
 * @param traceId          Trace identifier propagated to [IceCandidateManager] and error
 *                         callbacks for full-chain log correlation.  When blank, [TraceContext]
 *                         is consulted so that all events in the same WS session share a trace.
 * @param candidateManager [IceCandidateManager] that receives and manages ICE candidates.
 *                         Defaults to a no-op manager (useful for unit tests or when
 *                         candidate application is handled externally).
 * @param deviceId         Optional stable device identifier included in structured log events.
 * @param routeMode        Route mode for this signaling session (`"local"` or `"cross_device"`).
 * @param onMessage        Callback invoked for offer/answer messages (and any unhandled types).
 * @param onConnected      Callback invoked when the WebSocket connection is established.
 * @param onDisconnected   Callback invoked when the WebSocket connection is lost.
 * @param onError          Callback invoked for gateway error messages or WebSocket failures.
 *                         The error string always includes the [traceId] prefix.
 */
class WebRTCSignalingClient(
    private val url: String,
    val traceId: String = "",
    private val candidateManager: IceCandidateManager = IceCandidateManager(traceId),
    private val deviceId: String = "",
    private val routeMode: String = "",
    private val onMessage: (SignalingMessage) -> Unit = {},
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private val TAG = "WebRTCSignalingClient"

    /** Effective trace ID: explicit [traceId] when set; otherwise the session trace from [TraceContext]. */
    private val effectiveTraceId: String
        get() = traceId.ifBlank { TraceContext.currentTraceId() }

    /** Timestamp (epoch ms) when [connect] was last called; used for latency measurement. */
    @Volatile private var connectStartMs: Long = 0L

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    /**
     * The most recent [TurnConfig] received from the gateway, or `null` if none
     * has been delivered yet. Updated whenever an `ice_candidates` message carrying
     * a `turn_config` object is received.
     */
    @Volatile
    var lastTurnConfig: TurnConfig? = null
        private set

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "[trace=$effectiveTraceId] Signaling WS connected: $url")
            isConnected = true
            webSocket = ws
            GalaxyLogger.logSampled(
                GalaxyLogger.TAG_SIGNAL_START, buildMap {
                    put("trace_id", effectiveTraceId)
                    put("url", url)
                    if (deviceId.isNotBlank()) put("device_id", deviceId)
                    if (routeMode.isNotBlank()) put("route_mode", routeMode)
                }
            )
            onConnected()
        }

        override fun onMessage(ws: WebSocket, text: String) {
            Log.d(TAG, "[trace=$effectiveTraceId] Signaling message received: $text")
            val message = SignalingMessage.fromJsonString(text)
            if (message == null) {
                Log.w(TAG, "[trace=$effectiveTraceId] Failed to parse signaling message: $text")
                GalaxyLogger.logError(
                    traceId = effectiveTraceId,
                    cause   = "signaling_parse_error",
                    extraFields = mapOf("raw" to text.take(200))
                )
                return
            }
            // Accept server-provided trace_id when present in the incoming message envelope.
            if (!message.traceId.isNullOrBlank()) {
                TraceContext.acceptServerTraceId(message.traceId)
            }
            handleMessage(message)
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "[trace=$effectiveTraceId] Signaling WS closing: $code $reason")
            ws.close(1000, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "[trace=$effectiveTraceId] Signaling WS closed: $code $reason")
            isConnected = false
            webSocket = null
            val latencyMs = if (connectStartMs > 0L) System.currentTimeMillis() - connectStartMs else 0L
            GalaxyLogger.logSampled(
                GalaxyLogger.TAG_SIGNAL_STOP, buildMap {
                    put("trace_id", effectiveTraceId)
                    put("code", code)
                    put("reason", reason)
                    put("normal", code == 1000)
                    if (latencyMs > 0L) put("session_ms", latencyMs)
                    if (deviceId.isNotBlank()) put("device_id", deviceId)
                    if (routeMode.isNotBlank()) put("route_mode", routeMode)
                }
            )
            onDisconnected()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            val msg = "[trace=$effectiveTraceId] Signaling WS failure: ${t.message}"
            Log.e(TAG, msg, t)
            isConnected = false
            webSocket = null
            GalaxyLogger.logError(
                traceId = effectiveTraceId,
                cause   = t.message ?: "signaling_ws_failure",
                extraFields = buildMap {
                    if (deviceId.isNotBlank()) put("device_id", deviceId)
                    if (routeMode.isNotBlank()) put("route_mode", routeMode)
                }
            )
            onError(msg)
            onDisconnected()
        }
    }

    /**
     * Dispatch an incoming [SignalingMessage] to the appropriate handler.
     *
     * ICE candidate messages (single or batch) are forwarded to [candidateManager].
     * Offer/answer messages are forwarded to [onMessage].
     * Error messages trigger [onError].
     */
    private fun handleMessage(message: SignalingMessage) {
        when (message.type) {
            SignalingMessage.TYPE_ICE_CANDIDATE -> {
                // Legacy single-candidate path – backward compatible.
                val candidates = message.allCandidates
                if (candidates.isNotEmpty()) {
                    val applied = candidateManager.addCandidates(candidates)
                    Log.d(TAG, "[trace=$effectiveTraceId] Applied $applied/${candidates.size} legacy candidate(s)")
                } else {
                    Log.w(TAG, "[trace=$effectiveTraceId] ice_candidate message had no candidate data")
                }
            }
            SignalingMessage.TYPE_ICE_CANDIDATES -> {
                // Round-6 batch/trickle path.
                message.turnConfig?.let { tc ->
                    lastTurnConfig = tc
                    Log.i(TAG, "[trace=$effectiveTraceId] Received TURN config: ${tc.urls}")
                    GalaxyLogger.logSampled(
                        GalaxyLogger.TAG_WEBRTC_TURN, buildMap {
                            put("trace_id", effectiveTraceId)
                            put("event", "turn_config_received")
                            put("urls", tc.urls.size)
                            if (deviceId.isNotBlank()) put("device_id", deviceId)
                            if (routeMode.isNotBlank()) put("route_mode", routeMode)
                        }
                    )
                }
                val candidates = message.allCandidates
                if (candidates.isNotEmpty()) {
                    val applied = candidateManager.addCandidates(candidates)
                    Log.i(TAG, "[trace=$effectiveTraceId] Applied $applied/${candidates.size} candidate(s) " +
                            "(relay=${candidateManager.getTurnCandidates().size})")
                    val relayCount = candidateManager.getTurnCandidates().size
                    if (relayCount > 0) {
                        GalaxyLogger.logSampled(
                            GalaxyLogger.TAG_WEBRTC_TURN, buildMap {
                                put("trace_id", effectiveTraceId)
                                put("event", "relay_candidates_added")
                                put("relay_count", relayCount)
                                put("total_applied", applied)
                                if (deviceId.isNotBlank()) put("device_id", deviceId)
                            }
                        )
                    }
                } else {
                    Log.w(TAG, "[trace=$effectiveTraceId] ice_candidates message contained no candidates")
                }
            }
            SignalingMessage.TYPE_ERROR -> {
                val errMsg = "[trace=$effectiveTraceId] Gateway error: ${message.error ?: "unknown"}"
                Log.e(TAG, errMsg)
                GalaxyLogger.logError(
                    traceId = effectiveTraceId,
                    cause   = message.error ?: "gateway_error",
                    extraFields = buildMap {
                        if (deviceId.isNotBlank()) put("device_id", deviceId)
                        if (routeMode.isNotBlank()) put("route_mode", routeMode)
                    }
                )
                onError(errMsg)
            }
            else -> {
                // offer / answer and any future extension types
                onMessage(message)
            }
        }
    }

    /**
     * Open the WebSocket connection to the gateway signaling endpoint.
     * Safe to call from any thread.
     */
    fun connect() {
        connectStartMs = System.currentTimeMillis()
        Log.i(TAG, "[trace=$effectiveTraceId] Connecting to WebRTC signaling endpoint: $url")
        val request = Request.Builder().url(url).build()
        httpClient.newWebSocket(request, wsListener)
    }

    /**
     * Send a typed [SignalingMessage] to the gateway.
     */
    fun send(message: SignalingMessage) {
        val json = message.toJson().toString()
        Log.d(TAG, "[trace=$effectiveTraceId] Sending signaling message type=${message.type}")
        webSocket?.send(json) ?: run {
            Log.w(TAG, "[trace=$effectiveTraceId] Cannot send: signaling WS not connected")
            GalaxyLogger.logError(
                traceId = effectiveTraceId,
                cause   = "signaling_send_failed_not_connected",
                extraFields = mapOf("msg_type" to message.type)
            )
        }
    }

    /**
     * Send an arbitrary JSON object to the gateway (e.g., for extensions).
     */
    fun sendJson(json: JSONObject) {
        Log.d(TAG, "[trace=$effectiveTraceId] Sending raw signaling JSON: ${json.optString("type")}")
        webSocket?.send(json.toString()) ?: run {
            Log.w(TAG, "[trace=$effectiveTraceId] Cannot send: signaling WS not connected")
            GalaxyLogger.logError(
                traceId = effectiveTraceId,
                cause   = "signaling_send_failed_not_connected",
                extraFields = mapOf("msg_type" to json.optString("type", "unknown"))
            )
        }
    }

    /**
     * Trigger TURN-only fallback when direct connectivity fails.
     *
     * Delegates to [IceCandidateManager.startTurnFallback]; if no relay candidates
     * are available the [onError] callback is invoked.
     */
    fun triggerTurnFallback() {
        Log.w(TAG, "[trace=$effectiveTraceId] ICE connectivity failed – triggering TURN fallback")
        GalaxyLogger.logSampled(
            GalaxyLogger.TAG_WEBRTC_TURN, buildMap {
                put("trace_id", effectiveTraceId)
                put("event", "turn_fallback_triggered")
                if (deviceId.isNotBlank()) put("device_id", deviceId)
                if (routeMode.isNotBlank()) put("route_mode", routeMode)
            }
        )
        candidateManager.startTurnFallback()
    }

    /**
     * Close the WebSocket connection gracefully and reset the candidate manager.
     */
    fun disconnect() {
        Log.i(TAG, "[trace=$effectiveTraceId] Disconnecting signaling WS")
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isConnected = false
        candidateManager.reset()
    }
}

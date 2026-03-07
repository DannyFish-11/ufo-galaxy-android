package com.ufo.galaxy.webrtc

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ufo.galaxy.config.ServerConfig
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebRTC Manager
 *
 * Responsibilities:
 * 1. Manage the WebRTC connection lifecycle (start / stop streaming).
 * 2. Connect to the Galaxy Gateway signaling proxy via
 *    `ws://<host>/ws/webrtc/{device_id}` using [WebRTCSignalingClient].
 * 3. Handle signaling message exchange (offer / answer / ice_candidate).
 * 4. Discover the signaling endpoint dynamically via
 *    `GET /api/v1/webrtc/endpoint`; fall back to the default WS path when
 *    the endpoint is unavailable.
 * 5. Start / stop [ScreenCaptureService] in lock-step with the streaming
 *    session.
 *
 * Note: A full peer-to-peer WebRTC media pipeline (PeerConnectionFactory,
 * VideoTrack, etc.) requires the `org.webrtc` native library which is not
 * bundled in this project. The signaling layer is fully functional; encoded
 * frames from [ScreenCaptureService] are available to be plumbed into a
 * WebRTC track when the native library is added.  See docs/WEBRTC_ANDROID.md
 * for details.
 */
class WebRTCManager(private val context: Context) {

    private val TAG = "WebRTCManager"
    // All work in this manager is network I/O or state bookkeeping; no UI interactions.
    // Dispatcher.IO is intentional. Callbacks passed into WebRTCSignalingClient and
    // ScreenCaptureService are also thread-safe (they only log or toggle flags).
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var signalingClient: WebRTCSignalingClient? = null
    private var deviceId: String = "unknown_device"
    private var gatewayWsBase: String = ServerConfig.DEFAULT_BASE_URL
    @Volatile private var isStreaming: Boolean = false

    companion object {
        @Volatile
        private var instance: WebRTCManager? = null

        fun getInstance(context: Context): WebRTCManager {
            return instance ?: synchronized(this) {
                instance ?: WebRTCManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Initialize the manager with the gateway base URL and device identifier.
     *
     * @param gatewayWsBase WebSocket base URL, e.g. `"ws://100.123.215.126:8050"`.
     * @param deviceId      Identifier for this device; substituted into the WS path.
     */
    fun initialize(gatewayWsBase: String = ServerConfig.DEFAULT_BASE_URL, deviceId: String = "android_device") {
        this.gatewayWsBase = gatewayWsBase
        this.deviceId = deviceId
        Log.i(TAG, "WebRTCManager initialized — gateway=$gatewayWsBase device=$deviceId")
    }

    /**
     * Start a screen-sharing / streaming session.
     *
     * 1. Starts [ScreenCaptureService] to capture and encode the screen.
     * 2. Resolves the signaling endpoint (REST discovery → default fallback).
     * 3. Connects [WebRTCSignalingClient] to the gateway WS.
     * 4. Sends an SDP offer once the WS is open.
     *
     * @param resultCode MediaProjection permission result code.
     * @param data       MediaProjection permission Intent data.
     */
    fun startScreenSharing(resultCode: Int, data: Intent) {
        if (isStreaming) {
            Log.w(TAG, "Screen sharing already in progress")
            return
        }
        Log.i(TAG, "Starting screen sharing session")
        isStreaming = true

        // 1. Start the screen capture foreground service
        startCaptureService(resultCode, data)

        // 2. Resolve signaling WS URL and connect
        scope.launch {
            val signalingUrl = resolveSignalingUrl()
            Log.i(TAG, "Using signaling URL: $signalingUrl")
            connectSignaling(signalingUrl)
        }
    }

    /**
     * Stop the current streaming session.
     *
     * Disconnects the signaling WebSocket and stops [ScreenCaptureService].
     */
    fun stopScreenSharing() {
        if (!isStreaming) {
            Log.w(TAG, "Screen sharing is not running")
            return
        }
        Log.i(TAG, "Stopping screen sharing session")
        isStreaming = false

        signalingClient?.disconnect()
        signalingClient = null

        stopCaptureService()
    }

    /**
     * Handle an incoming [SignalingMessage] from the gateway.
     */
    fun handleSignalingMessage(message: SignalingMessage) {
        Log.i(TAG, "Received signaling message: ${message.type}")
        when (message.type) {
            "offer"         -> handleOffer(message)
            "answer"        -> handleAnswer(message)
            "ice_candidate" -> handleIceCandidate(message)
            else            -> Log.w(TAG, "Unknown signaling message type: ${message.type}")
        }
    }

    /**
     * Handle an incoming signaling message delivered as a raw [JSONObject]
     * (e.g., routed from the main agent WebSocket).
     */
    fun handleSignalingMessage(message: JSONObject) {
        val msg = SignalingMessage.fromJsonString(message.toString())
        if (msg != null) {
            handleSignalingMessage(msg)
        } else {
            Log.w(TAG, "Could not parse JSONObject signaling message")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Attempt to discover the signaling WS URL by querying the gateway REST
     * endpoint `GET /api/v1/webrtc/endpoint`.  Falls back to the default
     * path constructed via [ServerConfig.buildWebRtcWsUrl] if the endpoint
     * returns an error or is unreachable.
     */
    private suspend fun resolveSignalingUrl(): String = withContext(Dispatchers.IO) {
        val httpBase = ServerConfig.wsToHttpBase(gatewayWsBase)
        val endpointUrl = ServerConfig.buildWebRtcEndpointUrl(httpBase)
        Log.d(TAG, "Querying signaling endpoint: $endpointUrl")

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(endpointUrl).get().build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    val wsUrl = json.optString("ws_url").takeIf { it.isNotEmpty() }
                    if (wsUrl != null) {
                        Log.i(TAG, "Signaling endpoint discovered: $wsUrl")
                        return@withContext wsUrl
                    }
                }
            }
            Log.w(TAG, "Endpoint discovery returned ${response.code}; using default path")
        } catch (e: Exception) {
            Log.w(TAG, "Endpoint discovery failed (${e.message}); using default path")
        }

        // Fallback: build URL from ServerConfig
        ServerConfig.buildWebRtcWsUrl(gatewayWsBase, deviceId)
    }

    /** Connect [WebRTCSignalingClient] to [signalingUrl] and send an offer on open. */
    private fun connectSignaling(signalingUrl: String) {
        signalingClient?.disconnect()
        signalingClient = WebRTCSignalingClient(
            url           = signalingUrl,
            onMessage     = { msg -> handleSignalingMessage(msg) },
            onConnected   = { sendOffer() },
            onDisconnected = {
                Log.w(TAG, "Signaling WS disconnected")
                if (isStreaming) {
                    // Session was active — clean up capture
                    isStreaming = false
                    stopCaptureService()
                }
            }
        )
        signalingClient!!.connect()
    }

    /**
     * Build and send an SDP offer to the gateway.
     *
     * A minimal SDP offer is constructed here to initiate the signaling
     * exchange.  When the `org.webrtc` native library is integrated,
     * PeerConnectionFactory should generate the real SDP instead.
     */
    private fun sendOffer() {
        val sdp = buildMinimalSdpOffer()
        val offer = SignalingMessage(
            type     = "offer",
            sdp      = sdp,
            deviceId = deviceId
        )
        Log.i(TAG, "Sending SDP offer to gateway")
        signalingClient?.send(offer)
    }

    /**
     * Handle an incoming SDP offer (when the gateway initiates).
     * Creates and sends an SDP answer.
     */
    private fun handleOffer(message: SignalingMessage) {
        Log.i(TAG, "Handling incoming SDP offer")
        val sdp = message.sdp ?: run {
            Log.w(TAG, "Offer missing SDP body")
            return
        }
        Log.d(TAG, "Remote SDP offer: ${sdp.take(120)}…")
        // When org.webrtc is available: peerConnection.setRemoteDescription(…)
        // Then create and send an answer:
        val answer = SignalingMessage(
            type     = "answer",
            sdp      = buildMinimalSdpAnswer(sdp),
            deviceId = deviceId
        )
        signalingClient?.send(answer)
        Log.i(TAG, "SDP answer sent")
    }

    /**
     * Handle an incoming SDP answer from the remote peer.
     */
    private fun handleAnswer(message: SignalingMessage) {
        val sdp = message.sdp ?: run {
            Log.w(TAG, "Answer missing SDP body")
            return
        }
        Log.i(TAG, "Received SDP answer (${sdp.length} chars)")
        // When org.webrtc is available: peerConnection.setRemoteDescription(…)
    }

    /**
     * Handle an incoming ICE candidate from the remote peer.
     */
    private fun handleIceCandidate(message: SignalingMessage) {
        val candidate = message.candidate ?: run {
            Log.w(TAG, "ice_candidate message missing candidate body")
            return
        }
        Log.i(TAG, "Received ICE candidate: ${candidate.candidate.take(60)}…")
        // When org.webrtc is available: peerConnection.addIceCandidate(…)
    }

    /** Start the [ScreenCaptureService] foreground service. */
    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }
        context.startService(intent)
        Log.i(TAG, "ScreenCaptureService start requested")
    }

    /** Stop the [ScreenCaptureService] foreground service. */
    private fun stopCaptureService() {
        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        context.startService(intent)
        Log.i(TAG, "ScreenCaptureService stop requested")
    }

    /**
     * Build a minimal SDP offer string describing a video-only session.
     *
     * This placeholder SDP is sufficient for initiating the signaling exchange
     * with the gateway proxy.  Once `org.webrtc` is integrated, replace this
     * with the SDP generated by `PeerConnection.createOffer()`.
     */
    private fun buildMinimalSdpOffer(): String {
        val ts = System.currentTimeMillis() / 1000
        return """
            v=0
            o=- $ts $ts IN IP4 127.0.0.1
            s=UFO Galaxy Android Screen Share
            t=0 0
            a=group:BUNDLE 0
            m=video 9 UDP/TLS/RTP/SAVPF 96
            c=IN IP4 0.0.0.0
            a=rtpmap:96 H264/90000
            a=fmtp:96 profile-level-id=42e01f
            a=sendonly
            a=mid:0
        """.trimIndent()
    }

    /**
     * Build a minimal SDP answer in response to the given [remoteSdp].
     *
     * Replace with `PeerConnection.createAnswer()` when org.webrtc is available.
     */
    private fun buildMinimalSdpAnswer(remoteSdp: String): String {
        val ts = System.currentTimeMillis() / 1000
        return """
            v=0
            o=- $ts $ts IN IP4 127.0.0.1
            s=UFO Galaxy Android Screen Share
            t=0 0
            a=group:BUNDLE 0
            m=video 9 UDP/TLS/RTP/SAVPF 96
            c=IN IP4 0.0.0.0
            a=rtpmap:96 H264/90000
            a=fmtp:96 profile-level-id=42e01f
            a=recvonly
            a=mid:0
        """.trimIndent()
    }

    /**
     * Cancel all coroutines and disconnect signaling.
     */
    fun cleanup() {
        signalingClient?.disconnect()
        signalingClient = null
        isStreaming = false
        scope.cancel()
        Log.i(TAG, "WebRTCManager cleaned up")
    }
}

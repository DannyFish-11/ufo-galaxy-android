package com.ufo.galaxy.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import com.ufo.galaxy.config.ServerConfig
import com.ufo.galaxy.protocol.AIPMessageBuilder
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.TimeUnit

/**
 * WebRTC Manager
 *
 * Responsibilities:
 * 1. Manage the WebRTC connection lifecycle (start / stop streaming).
 * 2. Initialise [PeerConnectionFactory] with hardware video encoder/decoder.
 * 3. Create a [PeerConnection] with STUN ICE servers from [ServerConfig].
 * 4. Build a [VideoSource] + [VideoTrack] fed by a [ScreenCapturerAndroid].
 * 5. Connect to the Galaxy Gateway signaling proxy via
 *    `ws://<host>/ws/webrtc/{device_id}` using [WebRTCSignalingClient].
 * 6. Handle SDP offer/answer and ICE candidate exchange with the real
 *    `org.webrtc` API.
 * 7. Integrate with [ScreenCaptureService] which holds the foreground-service
 *    notification required on Android 14+ for MediaProjection.
 */
class WebRTCManager(private val context: Context) {

    private val TAG = "WebRTCManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── Configuration ────────────────────────────────────────────────────────
    private var deviceId: String = "unknown_device"
    private var gatewayWsBase: String = ServerConfig.DEFAULT_BASE_URL

    /**
     * Trace identifier for the current WebRTC session.
     *
     * Set via [initialize] from the caller (e.g. [GalaxyWebSocketClient.getTraceId])
     * so that signaling frames share the same trace ID as the main AIP WS session.
     */
    private var sessionTraceId: String = AIPMessageBuilder.generateTraceId()

    /**
     * Route mode for outbound signaling frames.
     * Mirrors the cross-device switch state; set via [initialize].
     */
    private var routeMode: String = AIPMessageBuilder.ROUTE_MODE_LOCAL

    // ─── Signaling ────────────────────────────────────────────────────────────
    private var signalingClient: WebRTCSignalingClient? = null

    // ─── WebRTC objects ───────────────────────────────────────────────────────
    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenCapturer: ScreenCapturerAndroid? = null

    @Volatile private var isStreaming: Boolean = false
    @Volatile private var factoryInitialized: Boolean = false

    companion object {
        private const val CAPTURE_WIDTH = 1280
        private const val CAPTURE_HEIGHT = 720
        private const val CAPTURE_FPS = 30
        private const val VIDEO_TRACK_ID = "video0"
        private const val STREAM_ID = "stream0"

        @Volatile
        private var instance: WebRTCManager? = null

        fun getInstance(context: Context): WebRTCManager {
            return instance ?: synchronized(this) {
                instance ?: WebRTCManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Initialise the manager with the gateway base URL, device identifier, and
     * optional AIP v3 session metadata.
     *
     * @param gatewayWsBase WebSocket base URL, e.g. `"ws://100.123.215.126:8050"`.
     * @param deviceId      Identifier for this device; substituted into the WS path.
     * @param traceId       AIP v3 trace identifier for the session.  Pass the value
     *                      from [GalaxyWebSocketClient.getTraceId] so that signaling
     *                      frames share the same trace ID as the main WS session.
     *                      A fresh ID is auto-generated when omitted.
     * @param routeMode     AIP v3 route mode (`"local"` or `"cross_device"`).
     */
    fun initialize(
        gatewayWsBase: String = ServerConfig.DEFAULT_BASE_URL,
        deviceId: String = "android_device",
        traceId: String = AIPMessageBuilder.generateTraceId(),
        routeMode: String = AIPMessageBuilder.ROUTE_MODE_LOCAL
    ) {
        this.gatewayWsBase = gatewayWsBase
        this.deviceId = deviceId
        this.sessionTraceId = traceId
        this.routeMode = routeMode
        initializeFactory()
        Log.i(TAG, "WebRTCManager initialized — gateway=$gatewayWsBase device=$deviceId trace_id=$traceId route_mode=$routeMode")
    }

    /**
     * Start a screen-sharing / streaming session.
     *
     * 1. Ensures [PeerConnectionFactory] is initialised.
     * 2. Creates a [PeerConnection] with STUN ICE servers.
     * 3. Attaches a [VideoTrack] to the peer connection.
     * 4. Starts [ScreenCaptureService] as a foreground service; the service
     *    calls back via [onCaptureServiceReady] once foreground is active,
     *    at which point [ScreenCapturerAndroid] is created and started.
     * 5. Resolves the signaling URL and connects [WebRTCSignalingClient].
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

        initializeFactory()
        createPeerConnection()

        // ScreenCaptureService starts the foreground notification and then
        // calls onCaptureServiceReady(), which starts the screen capturer.
        startCaptureService(resultCode, data)

        scope.launch {
            val signalingUrl = resolveSignalingUrl()
            Log.i(TAG, "Using signaling URL: $signalingUrl")
            connectSignaling(signalingUrl)
        }
    }

    /**
     * Called by [ScreenCaptureService] once the foreground service is active
     * and it is safe to call [android.media.projection.MediaProjectionManager.getMediaProjection].
     *
     * Creates a [ScreenCapturerAndroid] and starts screen capture into the
     * [VideoSource] attached to the peer connection.
     *
     * @param data The MediaProjection permission Intent originally received in
     *             `onActivityResult` (passed through from [startScreenSharing]).
     */
    fun onCaptureServiceReady(data: Intent) {
        Log.i(TAG, "Capture service ready; starting ScreenCapturerAndroid")
        val helper = surfaceTextureHelper ?: run {
            Log.e(TAG, "SurfaceTextureHelper not ready — was initialize() called?")
            return
        }
        val source = videoSource ?: run {
            Log.e(TAG, "VideoSource not ready — was initialize() called?")
            return
        }

        val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system")
                if (isStreaming) {
                    isStreaming = false
                    stopCaptureService()
                }
            }
        })
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
        screenCapturer = capturer
        Log.i(TAG, "Screen capturer started at ${CAPTURE_WIDTH}x${CAPTURE_HEIGHT}@${CAPTURE_FPS}fps")
    }

    /**
     * Stop the current streaming session.
     *
     * Stops the screen capturer, closes the peer connection, disconnects
     * the signaling WebSocket, and stops [ScreenCaptureService].
     */
    fun stopScreenSharing() {
        if (!isStreaming) {
            Log.w(TAG, "Screen sharing is not running")
            return
        }
        Log.i(TAG, "Stopping screen sharing session")
        isStreaming = false

        stopScreenCapturer()
        closePeerConnection()

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
    // Factory / peer-connection setup
    // ──────────────────────────────────────────────────────────────────────────

    private fun initializeFactory() {
        if (factoryInitialized) return
        factoryInitialized = true

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()
        val eglContext = eglBase!!.eglBaseContext

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()

        surfaceTextureHelper = SurfaceTextureHelper.create("VideoCapture", eglContext)
        videoSource = peerConnectionFactory!!.createVideoSource(/* isScreencast = */ true)
        videoTrack = peerConnectionFactory!!.createVideoTrack(VIDEO_TRACK_ID, videoSource!!)

        Log.i(TAG, "PeerConnectionFactory initialised")
    }

    private fun createPeerConnection() {
        val factory = peerConnectionFactory ?: return

        val iceServers = ServerConfig.effectiveIceServers.map { url ->
            PeerConnection.IceServer.builder(url).createIceServer()
        }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

        peerConnection = factory.createPeerConnection(rtcConfig, peerConnectionObserver)
            ?: run {
                Log.e(TAG, "Failed to create PeerConnection")
                return
            }

        val stream = factory.createLocalMediaStream(STREAM_ID)
        stream.addTrack(videoTrack)
        peerConnection!!.addStream(stream)

        Log.i(TAG, "PeerConnection created")
    }

    private fun closePeerConnection() {
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Signaling helpers
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
                    isStreaming = false
                    stopCaptureService()
                }
            }
        )
        signalingClient!!.connect()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SDP / ICE handling
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Create a real SDP offer via [PeerConnection.createOffer], set it as the
     * local description, and send it to the gateway via [WebRTCSignalingClient].
     */
    private fun sendOffer() {
        val pc = peerConnection ?: run {
            Log.e(TAG, "PeerConnection not ready for offer")
            return
        }
        val constraints = MediaConstraints()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val offer = SignalingMessage(
                            type      = "offer",
                            sdp       = sdp.description,
                            deviceId  = deviceId,
                            traceId   = sessionTraceId,
                            routeMode = routeMode
                        )
                        signalingClient?.send(offer)
                        Log.i(TAG, "SDP offer sent (${sdp.description.length} chars)")
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "setLocalDescription failed: $error")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createOffer failed: $error")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    /**
     * Handle an incoming SDP offer (when the gateway initiates).
     * Sets it as the remote description and creates + sends an SDP answer.
     */
    private fun handleOffer(message: SignalingMessage) {
        val sdp = message.sdp ?: run {
            Log.w(TAG, "Offer missing SDP body")
            return
        }
        Log.i(TAG, "Handling incoming SDP offer (${sdp.length} chars)")
        val pc = peerConnection ?: run {
            Log.e(TAG, "PeerConnection not ready")
            return
        }

        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.i(TAG, "Remote description set (offer); creating answer")
                val constraints = MediaConstraints()
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerSdp: SessionDescription) {
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                val answer = SignalingMessage(
                                    type      = "answer",
                                    sdp       = answerSdp.description,
                                    deviceId  = deviceId,
                                    traceId   = sessionTraceId,
                                    routeMode = routeMode
                                )
                                signalingClient?.send(answer)
                                Log.i(TAG, "SDP answer sent")
                            }
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "setLocalDescription (answer) failed: $error")
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, answerSdp)
                    }
                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG, "createAnswer failed: $error")
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) {}
                }, constraints)
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription (offer) failed: $error")
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remoteDesc)
    }

    /**
     * Handle an incoming SDP answer from the remote peer by setting it as the
     * remote description on the [PeerConnection].
     */
    private fun handleAnswer(message: SignalingMessage) {
        val sdp = message.sdp ?: run {
            Log.w(TAG, "Answer missing SDP body")
            return
        }
        Log.i(TAG, "Handling SDP answer (${sdp.length} chars)")
        val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.i(TAG, "Remote description set (answer) — media negotiation complete")
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription (answer) failed: $error")
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remoteDesc)
    }

    /**
     * Handle an incoming ICE candidate from the remote peer by adding it to the
     * [PeerConnection].
     */
    private fun handleIceCandidate(message: SignalingMessage) {
        val candidate = message.candidate ?: run {
            Log.w(TAG, "ice_candidate message missing candidate body")
            return
        }
        Log.i(TAG, "Adding remote ICE candidate: ${candidate.candidate.take(60)}…")
        val iceCandidate = IceCandidate(
            candidate.sdpMid ?: "",
            candidate.sdpMLineIndex,
            candidate.candidate
        )
        peerConnection?.addIceCandidate(iceCandidate)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PeerConnection observer
    // ──────────────────────────────────────────────────────────────────────────

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            Log.d(TAG, "Local ICE candidate: ${candidate.sdp.take(60)}…")
            val msg = SignalingMessage(
                type      = "ice_candidate",
                candidate = SignalingMessage.IceCandidate(
                    candidate     = candidate.sdp,
                    sdpMid        = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex
                ),
                deviceId  = deviceId,
                traceId   = sessionTraceId,
                routeMode = routeMode
            )
            signalingClient?.send(msg)
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            Log.i(TAG, "ICE connection state: $newState")
            if (newState == PeerConnection.IceConnectionState.FAILED ||
                newState == PeerConnection.IceConnectionState.DISCONNECTED) {
                Log.w(TAG, "ICE connection failed/disconnected")
            }
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            Log.d(TAG, "ICE gathering state: $newState")
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState) {
            Log.d(TAG, "Signaling state: $newState")
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
            Log.d(TAG, "ICE candidates removed: ${candidates.size}")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onAddStream(stream: MediaStream) {
            Log.d(TAG, "Remote stream added: ${stream.id}")
        }

        override fun onRemoveStream(stream: MediaStream) {
            Log.d(TAG, "Remote stream removed: ${stream.id}")
        }

        override fun onDataChannel(dataChannel: DataChannel) {
            Log.d(TAG, "Data channel opened: ${dataChannel.label()}")
        }

        override fun onRenegotiationNeeded() {
            Log.d(TAG, "Renegotiation needed")
        }

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            Log.d(TAG, "Track added: ${receiver.id()}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Capture service control
    // ──────────────────────────────────────────────────────────────────────────

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

    private fun stopScreenCapturer() {
        try {
            screenCapturer?.stopCapture()
        } catch (e: InterruptedException) {
            Log.w(TAG, "stopCapture interrupted", e)
        }
        screenCapturer?.dispose()
        screenCapturer = null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Release all WebRTC resources, disconnect signaling, and cancel coroutines.
     * Call this when the application is finishing to prevent leaks.
     */
    fun cleanup() {
        isStreaming = false
        stopScreenCapturer()
        closePeerConnection()

        videoTrack?.dispose()
        videoTrack = null

        videoSource?.dispose()
        videoSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        factoryInitialized = false

        eglBase?.release()
        eglBase = null

        signalingClient?.disconnect()
        signalingClient = null

        scope.cancel()
        Log.i(TAG, "WebRTCManager cleaned up")
    }
}

package com.ufo.galaxy.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ufo.galaxy.BuildConfig
import com.ufo.galaxy.data.AIPMessage
import com.ufo.galaxy.data.AIPMessageType
import com.ufo.galaxy.data.CapabilityReport
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.runtime.RuntimeHostDescriptor
import com.ufo.galaxy.protocol.DiagnosticsPayload
import com.ufo.galaxy.protocol.MeshJoinPayload
import com.ufo.galaxy.protocol.MeshLeavePayload
import com.ufo.galaxy.protocol.MeshResultPayload
import com.ufo.galaxy.protocol.MeshSubtaskResult
import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import com.ufo.galaxy.protocol.MsgType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.min
import kotlin.random.Random

/**
 * **The sole cross-device uplink and session transport backbone** for the Android runtime.
 *
 * All outbound cross-device messages flow through this class:
 *  - **Device registration / capability report**: sent automatically on [onOpen] via
 *    [sendHandshake], carrying platform, device_id, supported_actions, capability schema,
 *    and metadata flags.
 *  - **Heartbeats**: emitted every [HEARTBEAT_INTERVAL_MS] (30 s) with device_id,
 *    route_mode, and reconnect_attempts for liveness monitoring.
 *  - **Task / goal uplink**: [sendJson] is the single write path for `task_submit`,
 *    `goal_execution_result`, and `cancel_result` envelopes.  All production result
 *    paths (task_assign, goal_execution, parallel_subtask, error/parse-failure) emit
 *    `goal_execution_result` as the canonical result type.  Callers must use
 *    [InputRouter] (or [GalaxyConnectionService] for inbound dispatch) — never bypass
 *    [sendJson] with a separate WebSocket connection.
 *  - **Offline replay**: `goal_execution_result` envelopes (and legacy `task_result` /
 *    `goal_result` envelopes retained for backward compatibility) are buffered in
 *    [offlineQueue] when disconnected and replayed in FIFO order on reconnect.
 *
 * **Lifecycle ownership**: [RuntimeController] is the sole authority for connect /
 * disconnect decisions and cross-device enable/disable toggling. No other component
 * should call [connect], [disconnect], or [setCrossDeviceEnabled] directly.
 *
 * **Reconnect strategy** — exponential backoff with jitter + perpetual watchdog:
 *   base delays 1 s → 2 s → 4 s → 8 s → 16 s → 30 s (capped), plus up to 1 s
 *   of random jitter.  Attempt counter is reset to 0 on a successful [onOpen].
 *   Calling [connect] while already connected or connecting is a no-op.
 *   Calling [connect] after an explicit [disconnect] restarts the attempt counter.
 *   Once the attempt ceiling ([MAX_RECONNECT_ATTEMPTS]) is reached, [Listener.onError]
 *   is emitted (for UI / recovery-state updates) and the counter resets to 0;
 *   reconnect attempts then continue indefinitely at the capped 30 s interval.
 *   The device **never** stops reconnecting while [shouldReconnect] is `true`.
 *
 * **Cross-device gate**: [sendJson] is hard-blocked when [crossDeviceEnabled] is `false`,
 *   regardless of connection state, to enforce the local-only mode invariant.
 *
 * **Offline task queue** — when [sendJson] is called while disconnected and the
 *   message type matches [OfflineTaskQueue.QUEUEABLE_TYPES] ("goal_execution_result",
 *   or legacy "task_result" / "goal_result" for backward compatibility), the payload
 *   is enqueued in [offlineQueue] instead of being dropped.  The queue is flushed
 *   automatically when the connection is (re-)established.  Observable via [queueSize].
 *
 * **Log tags** — all log lines use the [TAG] constant ("GalaxyWebSocket") which
 *   is prefixed with a contextual marker:
 *   - `[WS:CONNECT]`    — connection lifecycle (open / close / disable)
 *   - `[WS:DISCONNECT]` — explicit disconnect
 *   - `[WS:RETRY]`      — reconnect scheduling / attempts
 *
 * @param serverUrl          WebSocket server URL.
 * @param crossDeviceEnabled When false, [connect] is a no-op and no device_register or
 *                           capability_report messages are sent. Defaults to true for
 *                           backward compatibility.
 * @param offlineQueue       Offline task queue used to buffer outgoing task results
 *                           while disconnected.  Pass a pre-configured [OfflineTaskQueue]
 *                           (with [android.content.SharedPreferences]) for persistence;
 *                           defaults to an in-memory-only queue.
 * @param allowSelfSigned    When true, OkHttp trusts self-signed TLS certificates.
 *                           Only relevant when using `wss://` / `https://` URLs.
 *                           **Debug/dev only** — never use on public networks.
 */
class GalaxyWebSocketClient(
    private var serverUrl: String,
    crossDeviceEnabled: Boolean = true,
    val offlineQueue: OfflineTaskQueue = OfflineTaskQueue(),
    private val allowSelfSigned: Boolean = false,
    private var gatewayToken: String = "",
    private var runtimeSessionId: String? = null,
    private var configuredDeviceId: String = ""
) : GatewayClient {
    // ── Runtime attachment session identity fields (PR-G) ─────────────────────

    /**
     * The stable `runtime_attachment_session_id` for the current attached session era.
     *
     * Set by [RuntimeController] via [updateRuntimeConnectionConfig] each time a new
     * [com.ufo.galaxy.runtime.AttachedRuntimeSession] is created or preserved on reconnect.
     *
     * - On first connect / user activation: set to a freshly generated UUID v4.
     * - On transparent reconnect (RECONNECT_RECOVERY): **same value as before**, enabling
     *   the V2 continuity consumer to recognise the returning device as restoring the same
     *   attachment rather than starting a brand-new one.
     * - On explicit stop or invalidation: cleared to `null`; the next activation generates
     *   a fresh ID.
     *
     * Included in both `device_register` and `capability_report` handshake messages as the
     * `runtime_attachment_session_id` wire field.
     */
    @Volatile private var runtimeAttachmentSessionId: String? = null

    /**
     * The durable session ID for the current activation era (PR-1 / PR-G).
     *
     * Sourced from [com.ufo.galaxy.runtime.DurableSessionContinuityRecord.durableSessionId].
     * Stable across all reconnects within the same activation era; cleared on stop/invalidate.
     *
     * Included in handshake messages as `durable_session_id` when non-null.
     */
    @Volatile private var durableSessionId: String? = null

    /**
     * The session continuity epoch for the current activation era (PR-1 / PR-G).
     *
     * Sourced from [com.ufo.galaxy.runtime.DurableSessionContinuityRecord.sessionContinuityEpoch].
     * `0` at first attach; increments on each transparent reconnect.
     *
     * Included in handshake messages as `session_continuity_epoch` when [durableSessionId]
     * is non-null.
     */
    @Volatile private var sessionContinuityEpoch: Int? = null
    companion object {
        private const val TAG = "GalaxyWebSocket"
        private const val HEARTBEAT_INTERVAL_MS = 30000L

        // Exponential backoff base delays (ms); last value is the cap.
        private val RECONNECT_BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
        private const val RECONNECT_JITTER_MAX_MS = 1_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10

        /**
         * Builds an OkHttpClient configured for WebSocket connections.
         *
         * When [allowSelfSigned] is `true` the client accepts all TLS certificates,
         * including self-signed ones. Use this **only** in debug/dev environments on
         * private (e.g. Tailscale) networks — it disables hostname verification.
         */
        fun buildOkHttpClient(
            allowSelfSigned: Boolean = false,
            isDebugBuild: Boolean = BuildConfig.DEBUG
        ): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
            val effectiveAllowSelfSigned = resolveAllowSelfSigned(
                requested = allowSelfSigned,
                isDebugBuild = isDebugBuild
            )
            if (effectiveAllowSelfSigned) {
                try {
                    val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    })
                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(null, trustAllCerts, SecureRandom())
                    builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                    builder.hostnameVerifier { _, _ -> true }
                    Log.w(TAG, "[WS:CONNECT] allowSelfSigned=true — TLS certificate validation is DISABLED (debug only)")
                } catch (e: Exception) {
                    Log.e(TAG, "[WS:CONNECT] Failed to configure self-signed trust: ${e.message}")
                }
            }
            return builder.build()
        }
    }
    
    /**
     * WebSocket 监听器接口
     */
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onMessage(message: String)
        fun onError(error: String)

        /**
         * Called when a strong-typed task_assign message is received.
         *
         * [taskId] is the task_id from the payload and should be used as the
         * correlation_id when sending the task_result reply.
         * [taskAssignPayloadJson] is the raw JSON of the payload object, ready for
         * deserialization into [com.ufo.galaxy.protocol.TaskAssignPayload].
         * [traceId] is the trace_id from the inbound AIP envelope; null if absent.
         * Receivers should use [traceId] as-is in the reply envelope and in the
         * [com.ufo.galaxy.protocol.TaskResultPayload.trace_id] field, generating a
         * new UUID only when [traceId] is null or blank.
         *
         * Default implementation is a no-op to maintain backward compatibility
         * with existing [Listener] implementations.
         */
        fun onTaskAssign(taskId: String, taskAssignPayloadJson: String, traceId: String? = null) = Unit

        /**
         * Called when a [com.ufo.galaxy.protocol.MsgType.GOAL_EXECUTION] message is received.
         *
         * [taskId] is the task_id from the payload.
         * [goalPayloadJson] is the raw JSON of the payload, ready for deserialization
         * into [com.ufo.galaxy.protocol.GoalExecutionPayload].
         * [traceId] is the trace_id from the inbound AIP envelope; null if absent.
         * Receivers should echo [traceId] in the reply envelope for full-chain correlation.
         *
         * Default implementation is a no-op for backward compatibility.
         */
        fun onGoalExecution(taskId: String, goalPayloadJson: String, traceId: String? = null) = Unit

        /**
         * Called when a [com.ufo.galaxy.protocol.MsgType.PARALLEL_SUBTASK] message is received.
         *
         * [taskId] is the task_id from the payload.
         * [subtaskPayloadJson] is the raw JSON of the payload, ready for deserialization
         * into [com.ufo.galaxy.protocol.GoalExecutionPayload].
         * [traceId] is the trace_id from the inbound AIP envelope; null if absent.
         * Receivers should echo [traceId] in the reply envelope for full-chain correlation.
         *
         * Default implementation is a no-op for backward compatibility.
         */
        fun onParallelSubtask(taskId: String, subtaskPayloadJson: String, traceId: String? = null) = Unit

        /**
         * Called when a [com.ufo.galaxy.protocol.MsgType.TASK_CANCEL] message is received.
         *
         * [taskId] is the task_id from the cancel payload.
         * [cancelPayloadJson] is the raw JSON of the payload, ready for deserialization
         * into [com.ufo.galaxy.protocol.TaskCancelPayload].
         *
         * Default implementation is a no-op for backward compatibility.
         */
        fun onTaskCancel(taskId: String, cancelPayloadJson: String) = Unit

        /**
         * Called for any inbound message whose type belongs to
         * [com.ufo.galaxy.protocol.MsgType.ADVANCED_TYPES] (PR-4 minimal-compat channels).
         *
         * These channels are recognised by the AIP v3 model and will not be silently
         * dropped, but their full business logic is not yet implemented.
         * The default implementation is a no-op; override to add custom handling.
         *
         * @param type       The parsed [MsgType] of the inbound message.
         * @param messageId  Optional `message_id` field from the AIP v3 envelope.
         * @param rawJson    Full raw JSON of the inbound envelope (for logging/debugging).
         */
        fun onAdvancedMessage(type: com.ufo.galaxy.protocol.MsgType, messageId: String?, rawJson: String) = Unit

        /**
         * Called when a completely unrecognised message type string is received.
         *
         * This is the last-resort fallback to prevent silent failures. The default
         * implementation is a no-op; override to add structured error reporting.
         *
         * @param rawType  The raw `type` string from the inbound JSON.
         * @param rawJson  Full raw JSON of the inbound envelope.
         */
        fun onUnknownMessage(rawType: String?, rawJson: String) = Unit

        /**
         * Called when a [com.ufo.galaxy.protocol.MsgType.HANDOFF_ENVELOPE_V2] message is
         * received (PR-H native consumption path).
         *
         * [taskId] is the task_id extracted from the envelope payload.
         * [envelopePayloadJson] is the raw JSON of the payload object, ready for
         * deserialization into [com.ufo.galaxy.agent.HandoffEnvelopeV2].
         * [traceId] is the trace_id from the inbound AIP envelope; null if absent.
         * Receivers should echo [traceId] in the result reply for full-chain correlation.
         *
         * Default implementation is a no-op for backward compatibility with existing
         * [Listener] implementations that pre-date PR-H.
         */
        fun onHandoffEnvelopeV2(taskId: String, envelopePayloadJson: String, traceId: String? = null) = Unit
    }
    
    private val client = buildOkHttpClient(allowSelfSigned)

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private val listeners: CopyOnWriteArrayList<Listener> = CopyOnWriteArrayList()
    private var isConnected = false

    /** Set to false by [disconnect] to suppress automatic reconnect scheduling. */
    @Volatile private var shouldReconnect = false

    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Session-level trace identifier regenerated on each successful [onOpen].
     * All outbound messages within a session reuse this ID for end-to-end correlation.
     * Exposed via [getTraceId] for callers that need to propagate the current session trace.
     */
    @Volatile private var sessionTraceId: String = java.util.UUID.randomUUID().toString()

    /** Returns the trace identifier for the current WS session. */
    fun getTraceId(): String = sessionTraceId

    /** Mesh session ID currently joined; null if not participating in any mesh. Set by [sendMeshJoin]. */
    @Volatile private var currentMeshId: String? = null

    /** Derives route_mode from the cross-device switch state. */
    private fun currentRouteMode(): String =
        if (crossDeviceEnabled) "cross_device" else "local"

    // ── Observable state ──────────────────────────────────────────────────────

    private val _reconnectAttemptCount = MutableStateFlow(0)
    /**
     * Number of consecutive reconnect attempts since the last successful [onOpen].
     * Resets to 0 on connect; increments before each scheduled retry.
     * Useful for debug UI display.
     */
    val reconnectAttemptCount: StateFlow<Int> = _reconnectAttemptCount.asStateFlow()

    /** Current depth of the offline task queue (updated reactively). */
    val queueSize: StateFlow<Int> = offlineQueue.sizeFlow

    /**
     * Runtime cross-device collaboration switch.
     *
     * When set to [false] the client operates in local-only mode: any subsequent
     * [connect] call is a no-op and no registration/capability_report messages are
     * emitted. Changing this to [true] will enable connections; call [connect] to
     * actually establish the WebSocket.
     *
     * NOTE: Toggling while already connected does NOT disconnect; call [disconnect]
     * first if a live connection should be torn down.
     */
    @Volatile
    var crossDeviceEnabled: Boolean
        private set

    init {
        // Use `this.crossDeviceEnabled` explicitly to avoid ambiguity with the constructor param.
        this.crossDeviceEnabled = crossDeviceEnabled
    }

    /**
     * Updates [crossDeviceEnabled] at runtime.
     *
     * When toggled to false while connected, the connection stays alive until the
     * caller explicitly calls [disconnect]. When toggled to true the caller should
     * call [connect] to initiate the connection — [MainViewModel.toggleCrossDeviceEnabled]
     * already does this, which provides the "immediate reconnect on toggle enable"
     * behaviour required by PR14.
     */
    fun setCrossDeviceEnabled(enabled: Boolean) {
        if (crossDeviceEnabled == enabled) return
        crossDeviceEnabled = enabled
        Log.i(TAG, "[WS:CONNECT] crossDeviceEnabled changed to $enabled")
    }

    /**
     * Applies live runtime connection settings used by future (or immediate) WS connections.
     *
     * Also accepts the runtime attachment session identity fields added in PR-G:
     * [runtimeAttachmentSessionId], [durableSessionId], and [sessionContinuityEpoch].
     * These are included in subsequent [sendHandshake] invocations so both the initial
     * registration and every reconnect registration carry the stable attachment identity
     * the V2 continuity consumer needs.
     *
     * Pass `null` for any field that should remain unchanged.
     *
     * @return true when either URL or token changed.
     */
    fun updateRuntimeConnectionConfig(
        serverUrl: String? = null,
        gatewayToken: String? = null,
        runtimeSessionId: String? = null,
        deviceId: String? = null,
        runtimeAttachmentSessionId: String? = null,
        durableSessionId: String? = null,
        sessionContinuityEpoch: Int? = null
    ): Boolean {
        var changed = false
        if (serverUrl != null && serverUrl != this.serverUrl) {
            this.serverUrl = serverUrl
            changed = true
        }
        if (gatewayToken != null && gatewayToken != this.gatewayToken) {
            this.gatewayToken = gatewayToken
            changed = true
        }
        if (runtimeSessionId != null && runtimeSessionId != this.runtimeSessionId) {
            this.runtimeSessionId = runtimeSessionId
        }
        if (deviceId != null && deviceId != this.configuredDeviceId) {
            this.configuredDeviceId = deviceId
        }
        if (runtimeAttachmentSessionId != null) {
            this.runtimeAttachmentSessionId = runtimeAttachmentSessionId
        }
        if (durableSessionId != null) {
            this.durableSessionId = durableSessionId
        }
        if (sessionContinuityEpoch != null) {
            this.sessionContinuityEpoch = sessionContinuityEpoch
        }
        return changed
    }

    /**
     * Clears the runtime attachment session identity fields (PR-G).
     *
     * Called by [RuntimeController] on explicit stop or session invalidation to ensure
     * the next activation generates a fresh identity and does not accidentally carry a
     * stale session ID from a previous era into the new connection handshake.
     */
    fun clearRuntimeAttachmentSessionIdentity() {
        runtimeAttachmentSessionId = null
        durableSessionId = null
        sessionContinuityEpoch = null
    }

    /**
     * Returns the current [runtimeAttachmentSessionId] held by this client (PR-G).
     *
     * Exposed for testing so assertions can verify that [RuntimeController] pushed the
     * correct identity before the WS handshake was sent.
     */
    internal fun getRuntimeAttachmentSessionId(): String? = runtimeAttachmentSessionId

    /**
     * Additional action capabilities reported when models are loaded.
     * Update via [setModelCapabilities] when planner/grounding services become ready.
     */
    private var modelCapabilities: List<String> = emptyList()

    /**
     * High-level autonomous capability names included in the capability_report.
     * Updated via [setHighLevelCapabilities] when the device role is determined.
     */
    private var highLevelCapabilities: List<String> = emptyList()

    /**
     * Device metadata flags included in the capability_report.
     * Updated via [setDeviceMetadata].
     *
     * Default initialised to the full 8-field canonical runtime identity map so that
     * a capability_report sent before [setDeviceMetadata] is ever called (e.g. in tests)
     * still satisfies [CapabilityReport.REQUIRED_METADATA_KEYS].
     */
    private var deviceMetadata: Map<String, Any> = mapOf(
        "goal_execution_enabled" to false,
        "local_model_enabled" to false,
        "cross_device_enabled" to crossDeviceEnabled,
        "parallel_execution_enabled" to false,
        "device_role" to "phone",
        "model_ready" to false,
        "accessibility_ready" to false,
        "overlay_ready" to false
    )
    
    /**
     * 设置监听器（向后兼容；清除已有监听器后添加新的）
     */
    fun setListener(listener: Listener) {
        listeners.clear()
        listeners.add(listener)
    }

    /**
     * Adds [listener] to the set of active listeners.
     * Multiple listeners may be registered simultaneously; each receives every event.
     */
    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    /**
     * Removes a previously registered [listener]. No-op if not present.
     */
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Updates the model-specific capabilities included in the capability_report.
     * Call this when [LocalPlannerService] or [LocalGroundingService] become ready
     * so the gateway receives an accurate list of available on-device actions.
     *
     * @param capabilities Additional action names to include (e.g., "local_planning",
     *                     "local_grounding"). These are appended to the base action list
     *                     and deduplicated before reporting. Pass an empty list to clear.
     */
    fun setModelCapabilities(capabilities: List<String>) {
        modelCapabilities = capabilities
    }

    /**
     * Updates the high-level autonomous capabilities advertised in the capability_report.
     *
     * @param capabilities High-level capability names, e.g.:
     *   "autonomous_goal_execution", "local_task_planning", "local_ui_reasoning",
     *   "cross_device_coordination", "local_model_inference".
     */
    fun setHighLevelCapabilities(capabilities: List<String>) {
        highLevelCapabilities = capabilities
    }

    /**
     * Updates the device metadata reported in the capability_report.
     *
     * The supplied map must contain all keys defined in
     * [CapabilityReport.REQUIRED_METADATA_KEYS] so that the gateway can treat the
     * device as a fully-identified runtime peer:
     *   - `goal_execution_enabled`     (Boolean)
     *   - `local_model_enabled`        (Boolean)
     *   - `cross_device_enabled`       (Boolean) — overwritten at send-time from the live flag
     *   - `parallel_execution_enabled` (Boolean)
     *   - `device_role`                (String)
     *   - `model_ready`                (Boolean)
     *   - `accessibility_ready`        (Boolean)
     *   - `overlay_ready`              (Boolean)
     *
     * Use [AppSettings.toMetadataMap] to build a conforming map. Unrecognised extra keys
     * are forwarded as-is to the gateway and ignored by servers that do not know them.
     */
    fun setDeviceMetadata(metadata: Map<String, Any>) {
        deviceMetadata = metadata
    }

    /**
     * The canonical runtime-host descriptor for this Android instance.
     *
     * When set, its metadata (host_id, formation_role, participation_state,
     * registered_at_ms) is merged into both the `device_register` and `capability_report`
     * payloads sent during [sendHandshake], allowing the gateway to treat this device
     * as a first-class runtime host rather than a generic connected endpoint.
     *
     * Call this once at startup via [com.ufo.galaxy.UFOGalaxyApplication] before the
     * WebSocket connects; the descriptor is immutable after construction.
     */
    @Volatile
    private var runtimeHostDescriptor: RuntimeHostDescriptor? = null

    /**
     * Registers the [RuntimeHostDescriptor] that represents this Android instance as
     * a first-class runtime host in the Galaxy formation.
     *
     * The descriptor metadata is merged into subsequent [sendHandshake] payloads.
     * Must be called before [connect] to ensure the initial registration message
     * carries complete host identity.
     *
     * @param descriptor Canonical host identity for this Android runtime instance.
     */
    fun setRuntimeHostDescriptor(descriptor: RuntimeHostDescriptor) {
        runtimeHostDescriptor = descriptor
        Log.i(TAG, "[WS:HOST] RuntimeHostDescriptor set: host_id=${descriptor.hostId} " +
                "role=${descriptor.formationRole.wireValue} " +
                "state=${descriptor.participationState.wireValue}")
    }
    
    /**
     * 连接到服务器
     * No-op when [crossDeviceEnabled] is false (local-only mode).
     */
    fun connect() {
        if (!crossDeviceEnabled) {
            Log.i(TAG, "[WS:CONNECT] Cross-device disabled (crossDeviceEnabled=false); skipping WS connection")
            return
        }
        if (isConnected) {
            Log.d(TAG, "[WS:CONNECT] Already connected, skipping")
            return
        }

        shouldReconnect = true
        reconnectJob?.cancel()

        Log.i(TAG, "[WS:CONNECT] Connecting to $serverUrl (attempt ${reconnectAttempts + 1})")
        
        val request = Request.Builder()
            .url(serverUrl)
            .apply { if (gatewayToken.isNotBlank()) addHeader("Authorization", "Bearer $gatewayToken") }
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Regenerate session trace ID for fresh end-to-end correlation.
                sessionTraceId = java.util.UUID.randomUUID().toString()
                Log.i(TAG, "[WS:CONNECT] WebSocket open — resetting backoff counter trace_id=$sessionTraceId")
                GalaxyLogger.log(GalaxyLogger.TAG_CONNECT, mapOf("url" to serverUrl, "attempt" to reconnectAttempts, "trace_id" to sessionTraceId))
                isConnected = true
                reconnectAttempts = 0
                _reconnectAttemptCount.value = 0
                listeners.forEach { it.onConnected() }
                startHeartbeat()
                
                // 发送握手消息
                sendHandshake()

                // Flush any messages queued while we were offline
                flushOfflineQueue()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "收到消息: ${text.take(100)}...")
                handleMessage(text)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "[WS:DISCONNECT] WebSocket closing: code=$code reason=$reason")
                webSocket.close(1000, null)
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "[WS:DISCONNECT] WebSocket closed: code=$code reason=$reason")
                GalaxyLogger.log(GalaxyLogger.TAG_DISCONNECT, mapOf("code" to code, "reason" to reason, "type" to "closed"))
                isConnected = false
                stopHeartbeat()
                listeners.forEach { it.onDisconnected() }
                
                // Reconnect unless the close was intentional (code 1000)
                if (shouldReconnect && code != 1000) {
                    scheduleReconnect()
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "[WS:DISCONNECT] WebSocket failure: ${t.message}", t)
                GalaxyLogger.log(GalaxyLogger.TAG_DISCONNECT, mapOf("type" to "failure", "error" to (t.message ?: "unknown")))
                isConnected = false
                stopHeartbeat()
                listeners.forEach { it.onError(t.message ?: "连接失败") }
                
                // Always attempt reconnect on failure
                if (shouldReconnect) {
                    scheduleReconnect()
                }
            }
        })
    }
    
    /**
     * 断开连接
     * Sets [shouldReconnect] to false so the automatic backoff loop stops.
     * If the device is currently participating in a mesh session, a [MsgType.MESH_LEAVE]
     * message is sent before the connection is torn down.
     */
    fun disconnect() {
        // Notify the mesh coordinator before closing the WebSocket.
        // Capture into a local val to avoid a TOCTOU race between the null-check and the call.
        val meshId = currentMeshId
        if (meshId != null) sendMeshLeave(meshId, "disconnect")
        Log.i(TAG, "[WS:DISCONNECT] Explicit disconnect requested")
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        stopHeartbeat()
        webSocket?.close(1000, "用户断开")
        webSocket = null
        isConnected = false
    }
    
    /**
     * 发送文本消息
     *
     * **DEPRECATED**: Use [sendJson] with a fully-serialised AIP v3 envelope.
     * This method bypasses the cross-device gate and is retained only for legacy
     * compatibility during migration.
     */
    @Deprecated(
        message = "Use sendJson() with a fully-serialised AIP v3 envelope. " +
            "sendJson() enforces the cross-device gate and offline queuing.",
        replaceWith = ReplaceWith("sendJson(json)")
    )
    fun send(text: String): Boolean {
        if (!crossDeviceEnabled) {
            Log.w(TAG, "[WS:BLOCKED] send() rejected: cross_device=off reason=cross_device_disabled")
            return false
        }
        if (!isConnected) {
            Log.w(TAG, "未连接，无法发送")
            return false
        }
        
        val message = AIPMessage(
            type = AIPMessageType.TEXT,
            payload = mapOf("content" to text)
        )
        
        return sendAIPMessage(message)
    }
    
    /**
     * 发送 AIP 消息
     *
     * **DEPRECATED**: Use [sendJson] with a fully-serialised AIP v3 envelope.
     * This method bypasses the cross-device gate and is retained only for legacy
     * compatibility during migration.
     */
    @Deprecated(
        message = "Use sendJson() with a fully-serialised AIP v3 envelope. " +
            "sendJson() enforces the cross-device gate and offline queuing.",
        replaceWith = ReplaceWith("sendJson(json)")
    )
    fun sendAIPMessage(message: AIPMessage): Boolean {
        if (!crossDeviceEnabled) {
            Log.w(TAG, "[WS:BLOCKED] sendAIPMessage() rejected: cross_device=off reason=cross_device_disabled")
            return false
        }
        if (!isConnected) {
            Log.w(TAG, "未连接，无法发送")
            return false
        }
        
        val json = gson.toJson(message)
        Log.d(TAG, "发送消息: ${json.take(100)}...")
        
        return webSocket?.send(json) ?: false
    }
    
    /**
     * Sends a pre-serialized JSON string directly over the WebSocket.
     * Used for AIP v3 protocol messages ([com.ufo.galaxy.protocol.AipMessage]) that
     * use the strong-typed model classes rather than the legacy [AIPMessage] envelope.
     *
     * **Offline queuing**: if the socket is currently disconnected and the message
     * `type` field belongs to [OfflineTaskQueue.QUEUEABLE_TYPES] ("task_result" /
     * "goal_result" / "goal_execution_result"), the payload is enqueued in [offlineQueue] for delivery on the
     * next successful reconnect.  The method returns `false` in this case (the message
     * was not sent *now*) but will not be lost.
     *
     * @return true if the message was transmitted immediately; false if disconnected
     *         (queued or dropped depending on type).
     */
    override fun sendJson(json: String): Boolean {
        // Hard constraint: cross-device switch OFF must block ALL outbound WS sends,
        // even if a stale connection is somehow still open.
        if (!crossDeviceEnabled) {
            val traceId = java.util.UUID.randomUUID().toString()
            val msgType = tryExtractType(json)
            Log.w(TAG, "[WS:BLOCKED] sendJson rejected: cross_device=off trace_id=$traceId type=$msgType reason=cross_device_disabled")
            GalaxyLogger.log(TAG, mapOf(
                "event" to "send_blocked",
                "trace_id" to traceId,
                "type" to (msgType ?: "unknown"),
                "reason" to "cross_device_disabled"
            ))
            return false
        }
        if (!isConnected) {
            // Attempt to queue queueable message types for later delivery.
            // Tag each queued message with the current durableSessionId so that
            // flushOfflineQueue's authority filter (discardForDifferentSession) can
            // block stale-session messages if the session changes before reconnect.
            val msgType = tryExtractType(json)
            if (msgType != null && msgType in OfflineTaskQueue.QUEUEABLE_TYPES) {
                offlineQueue.enqueue(msgType, json, sessionTag = durableSessionId)
                Log.i(TAG, "[WS:OfflineQueue] Queued offline message type=$msgType queue_size=${offlineQueue.size}")
            } else {
                Log.w(TAG, "Not connected and message type not queueable (type=$msgType); dropping")
            }
            return false
        }
        // Log key fields for full-chain traceability
        val msgType = tryExtractType(json)
        if (msgType in listOf("task_submit", "task_result", "goal_result", "cancel_result")) {
            val correlationId = tryExtractField(json, "correlation_id")
            val taskId = tryExtractField(json, "task_id") ?: tryExtractFieldNested(json, "payload", "task_id")
            val deviceId = tryExtractField(json, "device_id")
            Log.i(TAG, "[WS:UPLINK] type=$msgType task_id=$taskId correlation_id=$correlationId device_id=$deviceId")
        } else {
            Log.d(TAG, "发送 JSON: ${json.take(100)}...")
        }
        return webSocket?.send(json) ?: false
    }

    /** Extracts the `type` field from a JSON string without full deserialization. Returns null on error. */
    private fun tryExtractType(json: String): String? = try {
        gson.fromJson(json, JsonObject::class.java)?.get("type")?.asString
    } catch (_: Exception) {
        null
    }

    /** Extracts a top-level string field from a JSON string. Returns null on error. */
    private fun tryExtractField(json: String, field: String): String? = try {
        gson.fromJson(json, JsonObject::class.java)?.get(field)?.asString
    } catch (_: Exception) {
        null
    }

    /** Extracts a nested string field from a JSON string (e.g., payload.task_id). Returns null on error. */
    private fun tryExtractFieldNested(json: String, outerField: String, innerField: String): String? = try {
        gson.fromJson(json, JsonObject::class.java)
            ?.getAsJsonObject(outerField)?.get(innerField)?.asString
    } catch (_: Exception) {
        null
    }

    /**
     * Sends the AIP v3.0 capability_report handshake immediately after the WebSocket opens.
     *
     * The payload is the **canonical runtime identity** of this device. It includes:
     *  - `platform` / `device_id` / `version` — stable device identity fields.
     *  - `device_type` — always `"Android_Agent"`.
     *  - `trace_id` / `route_mode` — per-session observability fields.
     *  - `supported_actions` — merged low-level action list from [baseActions] and
     *    [modelCapabilities] (set via [setModelCapabilities]).
     *  - `capabilities` — high-level autonomous capability names from [highLevelCapabilities]
     *    (set via [setHighLevelCapabilities]), defaulting to the standard 5-capability set.
     *  - `metadata` — the full 8-field canonical runtime identity map (see
     *    [CapabilityReport.REQUIRED_METADATA_KEYS]): `goal_execution_enabled`,
     *    `local_model_enabled`, `parallel_execution_enabled`, `device_role`,
     *    `model_ready`, `accessibility_ready`, `overlay_ready`, and `cross_device_enabled`
     *    (always overwritten from the live [crossDeviceEnabled] flag at send-time).
     *
     * Update [deviceMetadata] via [setDeviceMetadata] (using [AppSettings.toMetadataMap])
     * before the WS opens to ensure the handshake reflects current device state.
     */
    private fun sendHandshake() {
        val deviceId = getDeviceId()
        val hostDescriptor = runtimeHostDescriptor
        val hostMeta: Map<String, Any> = hostDescriptor?.toMetadataMap() ?: emptyMap()
        // Snapshot PR-G identity fields atomically so both messages carry the same values.
        val attachmentSessionId = runtimeAttachmentSessionId
        val durableId = durableSessionId
        val continuityEpoch = sessionContinuityEpoch

        // Step 1: device_register — server expects this before capability_report (H2)
        val register = JsonObject().apply {
            addProperty("type", MsgType.DEVICE_REGISTER.value)
            addProperty("protocol", "AIP/1.0")
            addProperty("version", "3.0")
            addProperty("device_id", deviceId)
            addProperty("device_type", "Android_Agent")
            addProperty("trace_id", sessionTraceId)
            addProperty("timestamp", System.currentTimeMillis())
            if (!runtimeSessionId.isNullOrBlank()) addProperty("runtime_session_id", runtimeSessionId)
            // PR-G: stable attachment identity for V2 continuity consumer.
            if (!attachmentSessionId.isNullOrBlank()) {
                addProperty("runtime_attachment_session_id", attachmentSessionId)
            }
            // PR-G: durable continuity fields — present when a durable era is active.
            if (!durableId.isNullOrBlank()) {
                addProperty("durable_session_id", durableId)
                addProperty("session_continuity_epoch", continuityEpoch ?: 0)
            }
            addProperty("idempotency_key", buildIdempotencyKey(taskId = deviceId, type = MsgType.DEVICE_REGISTER.value))
            // PR-5: include runtime-host identity in device_register so the gateway
            // can immediately classify this device as a formal runtime host rather than
            // a generic endpoint.
            if (hostDescriptor != null) {
                addProperty(RuntimeHostDescriptor.KEY_HOST_ID, hostDescriptor.hostId)
                addProperty(RuntimeHostDescriptor.KEY_FORMATION_ROLE, hostDescriptor.formationRole.wireValue)
                addProperty(RuntimeHostDescriptor.KEY_PARTICIPATION_STATE, hostDescriptor.participationState.wireValue)
            }
        }
        sendJson(gson.toJson(register))
        Log.i(TAG, "[WS:DEVICE_REGISTER] device_id=$deviceId trace_id=$sessionTraceId" +
                (if (!attachmentSessionId.isNullOrBlank()) " runtime_attachment_session_id=$attachmentSessionId" else "") +
                (if (!durableId.isNullOrBlank()) " durable_session_id=$durableId epoch=${continuityEpoch ?: 0}" else "") +
                if (hostDescriptor != null) " host_id=${hostDescriptor.hostId} " +
                    "formation_role=${hostDescriptor.formationRole.wireValue} " +
                    "participation_state=${hostDescriptor.participationState.wireValue}" else "")

        val baseActions = listOf(
            "location", "camera", "sensor_data", "automation",
            "notification", "sms", "phone_call", "contacts",
            "calendar", "voice_input", "screen_capture", "app_control"
        )
        val allActions = (baseActions + modelCapabilities).distinct()

        // PR-5: merge runtime-host identity metadata into the capability_report so the
        // gateway session-truth layer has complete host representation on the first report.
        val mergedMetadata = deviceMetadata.toMutableMap().also { m ->
            m["cross_device_enabled"] = crossDeviceEnabled
            m.putAll(hostMeta)
        }

        val report = CapabilityReport(
            platform = "android",
            device_id = deviceId,
            supported_actions = allActions,
            version = "3.0",
            // `local_model_inference` is intentionally absent from the fallback list.
            // It is added by GalaxyConnectionService.loadModels() only after
            // LocalInferenceRuntimeManager confirms the runtime is operational.
            // Claiming it before runtime readiness is verified would produce a false
            // capability advertisement on the initial connection handshake.
            capabilities = highLevelCapabilities.ifEmpty {
                listOf(
                    "autonomous_goal_execution",
                    "local_task_planning",
                    "local_ui_reasoning",
                    "cross_device_coordination"
                )
            },
            metadata = mergedMetadata
        )

        val handshake = JsonObject().apply {
            addProperty("type", "capability_report")
            addProperty("version", report.version)
            addProperty("platform", report.platform)
            addProperty("device_id", report.device_id)
            addProperty("device_type", "Android_Agent")
            addProperty("trace_id", sessionTraceId)
            addProperty("route_mode", currentRouteMode())
            if (!runtimeSessionId.isNullOrBlank()) addProperty("runtime_session_id", runtimeSessionId)
            // PR-G: stable attachment identity for V2 continuity consumer.
            if (!attachmentSessionId.isNullOrBlank()) {
                addProperty("runtime_attachment_session_id", attachmentSessionId)
            }
            // PR-G: durable continuity fields — present when a durable era is active.
            if (!durableId.isNullOrBlank()) {
                addProperty("durable_session_id", durableId)
                addProperty("session_continuity_epoch", continuityEpoch ?: 0)
            }
            addProperty("idempotency_key", buildIdempotencyKey(taskId = report.device_id, type = MsgType.CAPABILITY_REPORT.value))
            add("supported_actions", gson.toJsonTree(report.supported_actions))
            add("capabilities", gson.toJsonTree(report.capabilities))
            add("metadata", gson.toJsonTree(report.metadata))
        }

        val handshakeJson = gson.toJson(handshake)
        // Warn if metadata is incomplete so the issue is surfaced in logcat immediately.
        val missing = report.missingMetadataKeys()
        if (missing.isNotEmpty()) {
            Log.w(TAG, "[WS:CAPABILITY_REPORT] metadata is missing required keys: $missing — " +
                    "call setDeviceMetadata(appSettings.toMetadataMap()) before connecting")
        }
        // Route through sendJson() so the cross-device gate is uniformly enforced
        // across all uplink message types, including the initial capability_report.
        sendJson(handshakeJson)
        Log.i(TAG, "[WS:CAPABILITY_REPORT] device_id=${report.device_id} platform=${report.platform}" +
                " actions=${report.supported_actions.size} capabilities=${report.capabilities}" +
                " cross_device_enabled=$crossDeviceEnabled trace_id=$sessionTraceId route_mode=${currentRouteMode()}" +
                (if (!attachmentSessionId.isNullOrBlank()) " runtime_attachment_session_id=$attachmentSessionId" else "") +
                (if (!durableId.isNullOrBlank()) " durable_session_id=$durableId epoch=${continuityEpoch ?: 0}" else "") +
                if (hostDescriptor != null) " host_id=${hostDescriptor.hostId}" else "")
    }

    /**
     * 发送结构化诊断载荷（任务失败时调用）
     * 服务端 Loop 1（自修复）和 Loop 2（学习反馈）依据此信息分类重复失败
     *
     * @param taskId    失败任务的唯一标识
     * @param nodeName  上报诊断的节点名称
     * @param errorType 错误分类（如 "network_timeout"、"permission_denied"）
     * @param errorContext 具体错误描述或堆栈摘要
     */
    fun sendDiagnostics(
        taskId: String,
        nodeName: String,
        errorType: String,
        errorContext: String
    ): Boolean {
        val deviceId = getDeviceId()

        val diagnosticsPayload = DiagnosticsPayload(
            task_id = taskId,
            device_id = deviceId,
            node_name = nodeName,
            error_type = errorType,
            error_context = errorContext
        )
        val envelope = AipMessage(
            type = MsgType.DIAGNOSTICS_PAYLOAD,
            payload = diagnosticsPayload,
            device_id = deviceId,
            trace_id = sessionTraceId,
            runtime_session_id = runtimeSessionId,
            idempotency_key = buildIdempotencyKey(taskId = taskId, type = MsgType.DIAGNOSTICS_PAYLOAD.value)
        )

        // Route through sendJson() so the cross-device gate and connection check
        // are applied uniformly — the same as every other cross-device uplink message.
        return sendJson(gson.toJson(envelope))
    }

    /**
     * Sends a [MsgType.MESH_JOIN] message to report that this device is joining a mesh session.
     *
     * The [meshId] is tracked internally so that [sendMeshLeave] is called automatically
     * when [disconnect] is invoked while participating in a mesh.
     *
     * @param meshId       Stable mesh session identifier shared by all participants.
     * @param role         Role of this device in the mesh ("participant" or "coordinator").
     * @param capabilities Capability names this device contributes to the mesh.
     * @return true if the message was sent immediately; false if blocked or disconnected.
     */
    fun sendMeshJoin(
        meshId: String,
        role: String = "participant",
        capabilities: List<String> = emptyList()
    ): Boolean {
        val deviceId = getDeviceId()
        val payload = MeshJoinPayload(
            mesh_id = meshId,
            device_id = deviceId,
            role = role,
            capabilities = capabilities
        )
        val envelope = AipMessage(
            type = MsgType.MESH_JOIN,
            payload = payload,
            device_id = deviceId,
            trace_id = sessionTraceId,
            runtime_session_id = runtimeSessionId,
            idempotency_key = buildIdempotencyKey(taskId = meshId, type = MsgType.MESH_JOIN.value)
        )
        val sent = sendJson(gson.toJson(envelope))
        if (sent) {
            currentMeshId = meshId
            Log.i(TAG, "[MESH:JOIN] mesh_id=$meshId role=$role device_id=$deviceId trace_id=$sessionTraceId")
        }
        return sent
    }

    /**
     * Sends a [MsgType.MESH_LEAVE] message to notify the server that this device is leaving
     * the mesh session. Clears the tracked [currentMeshId] after sending.
     *
     * @param meshId Mesh session identifier to leave.
     * @param reason Reason for leaving: "disconnect", "task_complete", or "error".
     * @return true if the message was sent immediately; false if blocked or disconnected.
     */
    fun sendMeshLeave(
        meshId: String,
        reason: String = "disconnect"
    ): Boolean {
        val deviceId = getDeviceId()
        val payload = MeshLeavePayload(
            mesh_id = meshId,
            device_id = deviceId,
            reason = reason
        )
        val envelope = AipMessage(
            type = MsgType.MESH_LEAVE,
            payload = payload,
            device_id = deviceId,
            trace_id = sessionTraceId,
            runtime_session_id = runtimeSessionId,
            idempotency_key = buildIdempotencyKey(taskId = meshId, type = MsgType.MESH_LEAVE.value)
        )
        val sent = sendJson(gson.toJson(envelope))
        currentMeshId = null
        Log.i(TAG, "[MESH:LEAVE] mesh_id=$meshId reason=$reason device_id=$deviceId sent=$sent")
        return sent
    }

    /**
     * Sends a [MsgType.MESH_RESULT] message reporting aggregated parallel-subtask results
     * for a mesh session.
     *
     * @param meshId     Mesh session identifier.
     * @param taskId     Top-level task identifier associated with the mesh execution.
     * @param status     Aggregate status: "success", "partial", or "error".
     * @param results    Per-device subtask result summaries.
     * @param summary    Human-readable one-line aggregate outcome (optional).
     * @param latencyMs  Wall-clock time from first dispatch to last result (ms).
     * @return true if the message was sent immediately; false if blocked or disconnected.
     */
    fun sendMeshResult(
        meshId: String,
        taskId: String,
        status: String,
        results: List<MeshSubtaskResult> = emptyList(),
        summary: String? = null,
        latencyMs: Long = 0L
    ): Boolean {
        val deviceId = getDeviceId()
        val payload = MeshResultPayload(
            mesh_id = meshId,
            task_id = taskId,
            device_id = deviceId,
            status = status,
            results = results,
            summary = summary,
            latency_ms = latencyMs
        )
        val envelope = AipMessage(
            type = MsgType.MESH_RESULT,
            payload = payload,
            correlation_id = taskId,
            device_id = deviceId,
            trace_id = sessionTraceId,
            runtime_session_id = runtimeSessionId,
            idempotency_key = buildIdempotencyKey(taskId = taskId, type = MsgType.MESH_RESULT.value)
        )
        Log.i(TAG, "[MESH:RESULT] mesh_id=$meshId task_id=$taskId status=$status results=${results.size} trace_id=$sessionTraceId")
        return sendJson(gson.toJson(envelope))
    }

    /**
     * Sends a [MsgType.DEVICE_EXECUTION_EVENT] uplink message to V2.
     *
     * Encodes [payload] into an [AipMessage] envelope and routes it through [sendJson]
     * so the cross-device gate and connection check are uniformly enforced.
     *
     * ## V2 ingestion
     * V2 absorbs the event via
     * `core.android_device_state_store.absorb_device_execution_event(device_id, payload_dict)`,
     * which:
     *  1. Parses the payload into a `DeviceExecutionEvent` dataclass.
     *  2. Appends it to the in-process execution-event ring buffer.
     *  3. Forwards the event to `FlowLevelOperatorSurface` (when `flow_id` is non-empty)
     *     so live cross-device execution state is visible at the V2 operator surface.
     *
     * Send failure **never throws** — any exception is caught and logged so the caller's
     * execution lifecycle progression is not interrupted.
     *
     * @param payload The [DeviceExecutionEventPayload] describing the execution phase event.
     * @return `true` if the message was sent immediately; `false` if blocked, disconnected,
     *         or an exception occurred.
     */
    fun sendDeviceExecutionEvent(payload: DeviceExecutionEventPayload): Boolean {
        return try {
            val deviceId = getDeviceId()
            val enrichedPayload = payload.copy(
                device_id = payload.device_id.ifBlank { deviceId }
            )
            val envelope = AipMessage(
                type = MsgType.DEVICE_EXECUTION_EVENT,
                payload = enrichedPayload,
                device_id = deviceId,
                correlation_id = payload.task_id,
                trace_id = sessionTraceId,
                runtime_session_id = runtimeSessionId,
                idempotency_key = payload.event_id
            )
            val sent = sendJson(gson.toJson(envelope))
            if (sent) {
                Log.d(
                    TAG, "[DEVICE_EXEC_EVENT] sent event_id=${payload.event_id} " +
                        "task_id=${payload.task_id} phase=${payload.phase} " +
                        "step=${payload.step_index} is_blocking=${payload.is_blocking}"
                )
            } else {
                Log.w(
                    TAG, "[DEVICE_EXEC_EVENT] send blocked/failed event_id=${payload.event_id} " +
                        "task_id=${payload.task_id} phase=${payload.phase}"
                )
            }
            sent
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_EXEC_EVENT] unexpected error sending event task_id=${payload.task_id}: ${e.message}", e)
            false
        }
    }
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            
            when (json.get("type")?.asString) {
                "heartbeat_ack" -> {
                    Log.d(TAG, "[WS:HEARTBEAT_ACK] ts=${System.currentTimeMillis()}")
                }
                "task_assign" -> {
                    // Parse strong-typed task_assign; dispatch via onTaskAssign so callers
                    // can forward to EdgeExecutor without coupling the WS client to Android
                    // platform classes. correlation_id prefers task_id from payload.
                    // The envelope trace_id is propagated to the listener so the reply can
                    // echo the same trace identifier for full-chain correlation.
                    val payloadObj = json.getAsJsonObject("payload")
                    val taskId = payloadObj?.get("task_id")?.asString ?: ""
                    val correlationId = json.get("correlation_id")?.asString ?: taskId
                    val deviceId = json.get("device_id")?.asString ?: ""
                    val traceId = json.get("trace_id")?.asString?.takeIf { it.isNotBlank() }
                    val payloadJson = payloadObj?.toString() ?: "{}"
                    Log.i(TAG, "[WS:DOWNLINK] type=task_assign task_id=$taskId correlation_id=$correlationId device_id=$deviceId trace_id=$traceId")
                    listeners.forEach { it.onTaskAssign(taskId, payloadJson, traceId) }
                }
                // ── Legacy task-management types (compatibility window) ──────────
                // Inbound task_execute and task_status_query are remapped to the unified
                // task_assign handler. No separate fork logic is maintained.
                "task_execute", "task_status_query" -> {
                    val payloadObj = json.getAsJsonObject("payload")
                    val taskId = payloadObj?.get("task_id")?.asString
                        ?: json.get("task_id")?.asString ?: ""
                    val traceId = json.get("trace_id")?.asString?.takeIf { it.isNotBlank() }
                    // Use payload object if present; fall back to empty object so that the
                    // TaskAssignPayload deserialization does not pick up envelope-level fields.
                    val payloadJson = payloadObj?.toString() ?: "{}"
                    val originalType = json.get("type")?.asString ?: "unknown"
                    if (payloadObj == null) {
                        Log.w(TAG, "[WS:DOWNLINK] legacy type=$originalType has no payload object; task_id=$taskId will use defaults")
                    }
                    Log.i(TAG, "[WS:DOWNLINK] legacy type=$originalType → remapped to task_assign task_id=$taskId trace_id=$traceId")
                    listeners.forEach { it.onTaskAssign(taskId, payloadJson, traceId) }
                }
                "goal_execution" -> {
                    val payloadObj = json.getAsJsonObject("payload")
                    val taskId = payloadObj?.get("task_id")?.asString ?: ""
                    val correlationId = json.get("correlation_id")?.asString ?: taskId
                    val groupId = payloadObj?.get("group_id")?.asString ?: ""
                    val traceId = json.get("trace_id")?.asString?.takeIf { it.isNotBlank() }
                    val payloadJson = payloadObj?.toString() ?: "{}"
                    Log.i(TAG, "[WS:DOWNLINK] type=goal_execution task_id=$taskId correlation_id=$correlationId group_id=$groupId trace_id=$traceId")
                    listeners.forEach { it.onGoalExecution(taskId, payloadJson, traceId) }
                }
                "parallel_subtask" -> {
                    val payloadObj = json.getAsJsonObject("payload")
                    val taskId = payloadObj?.get("task_id")?.asString ?: ""
                    val groupId = payloadObj?.get("group_id")?.asString ?: ""
                    val subtaskIndex = payloadObj?.get("subtask_index")?.asString ?: ""
                    val traceId = json.get("trace_id")?.asString?.takeIf { it.isNotBlank() }
                    val payloadJson = payloadObj?.toString() ?: "{}"
                    Log.i(TAG, "[WS:DOWNLINK] type=parallel_subtask task_id=$taskId group_id=$groupId subtask_index=$subtaskIndex trace_id=$traceId")
                    listeners.forEach { it.onParallelSubtask(taskId, payloadJson, traceId) }
                }
                "task_cancel" -> {
                    val payloadObj = json.getAsJsonObject("payload")
                    val taskId = payloadObj?.get("task_id")?.asString ?: ""
                    val payloadJson = payloadObj?.toString() ?: "{}"
                    Log.i(TAG, "[WS:DOWNLINK] type=task_cancel task_id=$taskId")
                    listeners.forEach { it.onTaskCancel(taskId, payloadJson) }
                }
                // ── PR-H: HandoffEnvelopeV2 native consumption downlink ─────────────────
                // Dedicated strongly-typed dispatch path: parsed before the ADVANCED_TYPES
                // fallback so the envelope is never silently absorbed by the generic handler.
                "handoff_envelope_v2" -> {
                    val payloadObj = json.getAsJsonObject("payload")
                    val taskId = payloadObj?.get("task_id")?.asString ?: ""
                    val traceId = json.get("trace_id")?.asString?.takeIf { it.isNotBlank() }
                        ?: payloadObj?.get("trace_id")?.asString?.takeIf { it.isNotBlank() }
                    val payloadJson = payloadObj?.toString() ?: "{}"
                    Log.i(TAG, "[WS:DOWNLINK] type=handoff_envelope_v2 task_id=$taskId trace_id=$traceId")
                    GalaxyLogger.log(TAG, mapOf(
                        "event" to "handoff_envelope_v2_received",
                        "task_id" to taskId,
                        "trace_id" to (traceId ?: ""),
                        "status" to "dispatching"
                    ))
                    listeners.forEach { it.onHandoffEnvelopeV2(taskId, payloadJson, traceId) }
                }
                "response" -> {
                    val content = json.getAsJsonObject("payload")
                        ?.get("content")?.asString ?: text
                    listeners.forEach { it.onMessage(content) }
                }
                "error" -> {
                    val error = json.get("message")?.asString ?: "未知错误"
                    listeners.forEach { it.onError(error) }
                }
                else -> {
                    // ── Unified fallback for unrecognised inbound message types ───────────
                    // First check whether the type belongs to the PR-4 advanced-capability
                    // set. If so, dispatch to onAdvancedMessage() (structured log + optional
                    // ack). If the type is genuinely unknown (not in MsgType at all), dispatch
                    // to onUnknownMessage() so it can be logged and reported without crashing.
                    val rawType = json.get("type")?.asString
                    val messageId = json.get("message_id")?.asString
                    val knownType = rawType?.let { MsgType.fromValue(it) }
                    when {
                        knownType != null && knownType in MsgType.ADVANCED_TYPES -> {
                            Log.i(TAG, "[WS:ADVANCED] type=${knownType.value} message_id=$messageId — minimal-compat handler")
                            GalaxyLogger.log(TAG, mapOf(
                                "event" to "advanced_message_received",
                                "type" to knownType.value,
                                "message_id" to (messageId ?: ""),
                                "status" to "minimal_compat"
                            ))
                            listeners.forEach { it.onAdvancedMessage(knownType, messageId, text) }
                        }
                        else -> {
                            // Truly unknown type — log a structured warning instead of silently
                            // treating the message as plain text (which would hide protocol errors).
                            Log.w(TAG, "[WS:UNKNOWN] type=$rawType message_id=$messageId — unrecognised; routing to onUnknownMessage")
                            GalaxyLogger.log(TAG, mapOf(
                                "event" to "unknown_message_received",
                                "type" to (rawType ?: "null"),
                                "message_id" to (messageId ?: ""),
                                "status" to "unrecognised"
                            ))
                            listeners.forEach { it.onUnknownMessage(rawType, text) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析消息失败", e)
            // 作为纯文本处理
            listeners.forEach { it.onMessage(text) }
        }
    }
    
    /**
     * 启动心跳
     */
    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive && isConnected) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendHeartbeat()
            }
        }
    }
    
    /**
     * 停止心跳
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
    
    /**
     * 发送心跳，附带 device_id、route_mode、device_type 与重连次数用于联调追踪。
     */
    private fun sendHeartbeat() {
        val deviceId = getDeviceId()
        val heartbeat = JsonObject().apply {
            addProperty("type", "heartbeat")
            addProperty("timestamp", System.currentTimeMillis())
            addProperty("device_id", deviceId)
            addProperty("device_type", "Android_Agent")
            addProperty("route_mode", currentRouteMode())
            addProperty("trace_id", sessionTraceId)
            addProperty("reconnect_attempts", reconnectAttempts)
            if (!runtimeSessionId.isNullOrBlank()) addProperty("runtime_session_id", runtimeSessionId)
            addProperty("idempotency_key", buildIdempotencyKey(taskId = deviceId, type = "heartbeat"))
        }
        val json = gson.toJson(heartbeat)
        webSocket?.send(json)
        Log.d(TAG, "[WS:HEARTBEAT] device_id=$deviceId route_mode=${currentRouteMode()} reconnect_attempts=$reconnectAttempts")
    }
    
    /**
     * 安排重连 — exponential backoff with jitter, with perpetual watchdog recovery.
     *
     * Normal phase: Delay = RECONNECT_BACKOFF_MS[min(attempt, last)] + random jitter
     * [0, RECONNECT_JITTER_MAX_MS).
     *
     * Watchdog phase: Once [MAX_RECONNECT_ATTEMPTS] is reached, [Listener.onError] is
     * emitted (so the runtime can update recovery state and UI), the attempt counter is
     * reset to 0, and a new reconnect is scheduled at the maximum backoff + jitter.
     * This perpetual watchdog cycle repeats indefinitely — the device **never** stops
     * attempting to reconnect as long as [shouldReconnect] is `true`.  An explicit
     * [disconnect] call (which sets [shouldReconnect]=false) is the only way to stop the
     * cycle (e.g. user disables cross-device or stop() is called).
     *
     * This design eliminates the prior terminal dead state where the Android participant
     * would permanently fall out of the distributed system after 10 consecutive failures.
     */
    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            // Notify listeners of the ceiling breach so the runtime can update its
            // recovery state (RECOVERING → FAILED) and the UI can show a transient
            // "connection failed" message.  We then reset the counter and fall into
            // a perpetual watchdog retry at the capped backoff + jitter — no permanent stop.
            Log.w(
                TAG,
                "[WS:RETRY] Attempt ceiling ($MAX_RECONNECT_ATTEMPTS) reached; " +
                    "entering watchdog recovery cycle (will retry at cap delay indefinitely)"
            )
            GalaxyLogger.log(
                GalaxyLogger.TAG_RECONNECT,
                mapOf(
                    "event" to "watchdog_cycle_entered",
                    "attempt_ceiling" to MAX_RECONNECT_ATTEMPTS
                )
            )
            listeners.forEach { it.onError("无法连接到服务器") }
            // Reset the attempt counter so the next watchdog cycle starts from 0
            // and so the reconnectAttemptCount observable reflects a fresh cycle.
            reconnectAttempts = 0
            _reconnectAttemptCount.value = 0
            // Schedule a single watchdog retry at the capped delay so we do not storm.
            val watchdogDelay = RECONNECT_BACKOFF_MS.last() + Random.nextLong(RECONNECT_JITTER_MAX_MS)
            Log.i(TAG, "[WS:RETRY] Watchdog reconnect scheduled in ${watchdogDelay}ms")
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(watchdogDelay)
                if (shouldReconnect && !isConnected) {
                    connect()
                }
            }
            return
        }

        val delayIndex = min(reconnectAttempts, RECONNECT_BACKOFF_MS.size - 1)
        val baseDelay = RECONNECT_BACKOFF_MS[delayIndex]
        val jitter = Random.nextLong(RECONNECT_JITTER_MAX_MS)
        val delay = baseDelay + jitter
        reconnectAttempts++
        _reconnectAttemptCount.value = reconnectAttempts

        Log.i(
            TAG,
            "[WS:RETRY] Scheduling reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS " +
                "in ${delay}ms (base=${baseDelay}ms jitter=${jitter}ms)"
        )
        GalaxyLogger.log(GalaxyLogger.TAG_RECONNECT, mapOf(
            "attempt" to reconnectAttempts,
            "max_attempts" to MAX_RECONNECT_ATTEMPTS,
            "delay_ms" to delay
        ))

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delay)
            if (shouldReconnect && !isConnected) {
                connect()
            }
        }
    }

    /**
     * Flushes the [offlineQueue] by sending each queued message through [sendJson].
     * Must only be called after [onOpen] confirms the connection is up.
     *
     * Before draining, stale-session messages are purged via
     * [OfflineTaskQueue.discardForDifferentSession] using the current [durableSessionId].
     * This prevents messages enqueued under an earlier durable session from being
     * replayed into the new connection.
     *
     * All messages are sent through [sendJson] so that the cross-device gate and any
     * other runtime send constraints are uniformly enforced during replay — the flush
     * path does not bypass the gate.
     *
     * Messages that fail to send are logged (with count) but not re-enqueued;
     * the flush is best-effort since re-enqueue on flush failure could cause infinite retry loops.
     */
    private fun flushOfflineQueue() {
        // Purge messages enqueued under a different durable session before replaying.
        val tag = durableSessionId
        if (tag != null) {
            val discarded = offlineQueue.discardForDifferentSession(tag)
            if (discarded > 0) {
                Log.i(TAG, "[WS:OfflineQueue] Discarded $discarded stale-session message(s) before flush (tag=$tag)")
            }
        } else {
            val discarded = offlineQueue.discardSessionTaggedWithoutAuthority()
            if (discarded > 0) {
                Log.i(TAG, "[WS:OfflineQueue] Discarded $discarded tagged message(s) before flush because session authority is unavailable")
            }
        }

        val messages = offlineQueue.drainAll()
        if (messages.isEmpty()) return

        Log.i(TAG, "[WS:OfflineQueue] Flushing ${messages.size} offline message(s)")
        var sent = 0
        var lost = 0
        for (msg in messages) {
            // Route through sendJson() so that the cross-device gate is uniformly enforced
            // during replay — same constraints as every other outbound message.
            val ok = sendJson(msg.json)
            if (!ok) {
                lost++
                Log.w(TAG, "[WS:OfflineQueue] Flush failed for type=${msg.type}; message lost (total lost: $lost)")
            } else {
                sent++
                Log.d(TAG, "[WS:OfflineQueue] Flushed type=${msg.type}")
            }
        }
        if (lost > 0) {
            Log.w(TAG, "[WS:OfflineQueue] Flush complete: sent=$sent lost=$lost")
        } else {
            Log.i(TAG, "[WS:OfflineQueue] Flush complete: all $sent message(s) sent")
        }
    }
    
    /**
     * 是否已连接
     */
    override fun isConnected(): Boolean = isConnected

    /**
     * Called when network connectivity is restored (e.g. from a
     * [android.net.ConnectivityManager] broadcast or NetworkCallback).
     *
     * Cancels any pending backoff delay and triggers an immediate reconnect attempt
     * so that the device re-registers as soon as the network comes up.
     *
     * No-op if already connected or if [crossDeviceEnabled] is false.
     */
    fun notifyNetworkAvailable() {
        if (isConnected || !crossDeviceEnabled) return
        Log.i(TAG, "[WS:RETRY] Network available — resetting backoff and reconnecting immediately")
        reconnectAttempts = 0
        _reconnectAttemptCount.value = 0
        reconnectJob?.cancel()
        shouldReconnect = true
        scope.launch { connect() }
    }

    /**
     * 获取设备唯一标识
     */
    private fun getDeviceId(): String =
        sanitizeDeviceId(
            configuredDeviceId.takeIf { it.isNotBlank() }
                ?: "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}"
        )

    private fun sanitizeDeviceId(rawDeviceId: String): String =
        rawDeviceId
            .trim()
            .replace("\\s+".toRegex(), "_")
            .replace("[^A-Za-z0-9._-]".toRegex(), "_")

    private fun buildIdempotencyKey(taskId: String, type: String): String {
        val session = runtimeSessionId ?: "no_runtime_session"
        val nonce = java.util.UUID.randomUUID().toString()
        return "$session:$type:$taskId:$nonce"
    }

    // ── PR-33 test-support simulation API ────────────────────────────────────

    /**
     * PR-33 — For testing only: fires [Listener.onConnected] on all registered listeners
     * without establishing a real WebSocket connection.
     *
     * Allows unit tests to drive [com.ufo.galaxy.runtime.RuntimeController]'s
     * `permanentWsListener` reconnect transition deterministically — specifically the
     * `RECOVERING → RECOVERED` path — without requiring a live WS server.
     *
     * This method also sets [isConnected] to `true` so that subsequent
     * [sendJson] calls behave as if the socket is live.
     *
     * **Do not call from production code.**
     */
    internal fun simulateConnected() {
        isConnected = true
        listeners.forEach { it.onConnected() }
    }

    /**
     * PR-33 — For testing only: fires [Listener.onDisconnected] on all registered listeners
     * without a real WebSocket close event.
     *
     * Allows unit tests to drive the `IDLE → RECOVERING` recovery state transition in
     * [com.ufo.galaxy.runtime.RuntimeController.permanentWsListener] without a live server.
     *
     * This method also sets [isConnected] to `false`.
     *
     * **Do not call from production code.**
     */
    internal fun simulateDisconnected() {
        isConnected = false
        listeners.forEach { it.onDisconnected() }
    }

    /**
     * PR-33 — For testing only: fires [Listener.onError] on all registered listeners.
     *
     * Allows unit tests to drive the `RECOVERING → FAILED` recovery state transition in
     * [com.ufo.galaxy.runtime.RuntimeController.permanentWsListener] without a live server.
     *
     * This method also sets [isConnected] to `false`.
     *
     * **Do not call from production code.**
     */
    internal fun simulateError(error: String) {
        isConnected = false
        listeners.forEach { it.onError(error) }
    }
}

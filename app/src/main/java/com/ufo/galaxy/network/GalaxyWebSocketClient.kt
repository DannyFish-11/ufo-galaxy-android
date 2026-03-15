package com.ufo.galaxy.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ufo.galaxy.data.AIPMessage
import com.ufo.galaxy.data.AIPMessageType
import com.ufo.galaxy.data.CapabilityReport
import com.ufo.galaxy.observability.GalaxyLogger
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
 * Galaxy WebSocket 客户端
 * 负责与 Galaxy 服务器的实时通信
 *
 * **Reconnect strategy** — exponential backoff with jitter:
 *   base delays 1 s → 2 s → 4 s → 8 s → 16 s → 30 s (capped), plus up to 1 s
 *   of random jitter.  Attempt counter is reset to 0 on a successful [onOpen].
 *   Calling [connect] while already connected or connecting is a no-op.
 *   Calling [connect] after an explicit [disconnect] restarts the attempt counter.
 *
 * **Offline task queue** — when [sendJson] is called while disconnected and the
 *   message type matches [OfflineTaskQueue.QUEUEABLE_TYPES] ("task_result" /
 *   "goal_result"), the payload is enqueued in [offlineQueue] instead of being
 *   dropped.  The queue is flushed automatically when the connection is (re-)
 *   established.  Observable via [queueSize].
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
    private val serverUrl: String,
    crossDeviceEnabled: Boolean = true,
    val offlineQueue: OfflineTaskQueue = OfflineTaskQueue(),
    private val allowSelfSigned: Boolean = false
) : GatewayClient {
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
        fun buildOkHttpClient(allowSelfSigned: Boolean = false): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
            if (allowSelfSigned) {
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
         *
         * Default implementation is a no-op to maintain backward compatibility
         * with existing [Listener] implementations.
         */
        fun onTaskAssign(taskId: String, taskAssignPayloadJson: String) = Unit

        /**
         * Called when a [com.ufo.galaxy.protocol.MsgType.GOAL_EXECUTION] message is received.
         *
         * [taskId] is the task_id from the payload.
         * [goalPayloadJson] is the raw JSON of the payload, ready for deserialization
         * into [com.ufo.galaxy.protocol.GoalExecutionPayload].
         *
         * Default implementation is a no-op for backward compatibility.
         */
        fun onGoalExecution(taskId: String, goalPayloadJson: String) = Unit

        /**
         * Called when a [com.ufo.galaxy.protocol.MsgType.PARALLEL_SUBTASK] message is received.
         *
         * [taskId] is the task_id from the payload.
         * [subtaskPayloadJson] is the raw JSON of the payload, ready for deserialization
         * into [com.ufo.galaxy.protocol.GoalExecutionPayload].
         *
         * Default implementation is a no-op for backward compatibility.
         */
        fun onParallelSubtask(taskId: String, subtaskPayloadJson: String) = Unit

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
     */
    private var deviceMetadata: Map<String, Any> = mapOf(
        "goal_execution_enabled" to false,
        "local_model_enabled" to false,
        "cross_device_enabled" to crossDeviceEnabled,
        "parallel_execution_enabled" to false,
        "device_role" to "phone"
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
     * Expected keys: goal_execution_enabled, local_model_enabled, cross_device_enabled,
     * parallel_execution_enabled, device_role.
     * Unrecognised keys are forwarded as-is to the gateway.
     */
    fun setDeviceMetadata(metadata: Map<String, Any>) {
        deviceMetadata = metadata
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
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "[WS:CONNECT] WebSocket open — resetting backoff counter")
                GalaxyLogger.log(GalaxyLogger.TAG_CONNECT, mapOf("url" to serverUrl, "attempt" to reconnectAttempts))
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
     */
    fun disconnect() {
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
     */
    fun send(text: String): Boolean {
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
     */
    fun sendAIPMessage(message: AIPMessage): Boolean {
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
     * "goal_result"), the payload is enqueued in [offlineQueue] for delivery on the
     * next successful reconnect.  The method returns `false` in this case (the message
     * was not sent *now*) but will not be lost.
     *
     * @return true if the message was transmitted immediately; false if disconnected
     *         (queued or dropped depending on type).
     */
    override fun sendJson(json: String): Boolean {
        if (!isConnected) {
            // Attempt to queue queueable message types for later delivery
            val msgType = tryExtractType(json)
            if (msgType != null && msgType in OfflineTaskQueue.QUEUEABLE_TYPES) {
                offlineQueue.enqueue(msgType, json)
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
     * 发送 AIP v3.0 能力上报（握手消息）
     * 包含 platform、device_id、supported_actions、version，供服务端 Loop 3 推断能力差距。
     * [modelCapabilities] and [highLevelCapabilities] are merged in to reflect current
     * model/permission readiness. [deviceMetadata] is included for autonomous capability flags.
     */
    private fun sendHandshake() {
        val deviceId = getDeviceId()

        val baseActions = listOf(
            "location", "camera", "sensor_data", "automation",
            "notification", "sms", "phone_call", "contacts",
            "calendar", "voice_input", "screen_capture", "app_control"
        )
        val allActions = (baseActions + modelCapabilities).distinct()

        val report = CapabilityReport(
            platform = "android",
            device_id = deviceId,
            supported_actions = allActions,
            version = "3.0",
            capabilities = highLevelCapabilities.ifEmpty {
                listOf(
                    "autonomous_goal_execution",
                    "local_task_planning",
                    "local_ui_reasoning",
                    "cross_device_coordination",
                    "local_model_inference"
                )
            },
            metadata = deviceMetadata.toMutableMap().also { m ->
                m["cross_device_enabled"] = crossDeviceEnabled
            }
        )

        val handshake = JsonObject().apply {
            addProperty("type", "capability_report")
            addProperty("version", report.version)
            addProperty("platform", report.platform)
            addProperty("device_id", report.device_id)
            add("supported_actions", gson.toJsonTree(report.supported_actions))
            add("capabilities", gson.toJsonTree(report.capabilities))
            add("metadata", gson.toJsonTree(report.metadata))
        }

        val handshakeJson = gson.toJson(handshake)
        webSocket?.send(handshakeJson)
        Log.i(TAG, "[WS:CAPABILITY_REPORT] device_id=${report.device_id} platform=${report.platform}" +
                " actions=${report.supported_actions.size} capabilities=${report.capabilities}" +
                " cross_device_enabled=$crossDeviceEnabled")
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
        if (!isConnected) {
            Log.w(TAG, "未连接，无法发送诊断载荷")
            return false
        }

        val deviceId = getDeviceId()

        val diagnostics = JsonObject().apply {
            addProperty("type", "diagnostics_payload")
            addProperty("device_id", deviceId)
            addProperty("error_type", errorType)
            addProperty("error_context", errorContext)
            addProperty("task_id", taskId)
            addProperty("node_name", nodeName)
        }

        return webSocket?.send(gson.toJson(diagnostics)) ?: false
    }
    
    /**
     * 处理收到的消息
     */
    private fun handleMessage(text: String) {
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
                    val payloadObj = json.getAsJsonObject("payload")
                    val taskId = payloadObj?.get("task_id")?.asString ?: ""
                    val correlationId = json.get("correlation_id")?.asString ?: taskId
                    val deviceId = json.get("device_id")?.asString ?: ""
                    val payloadJson = payloadObj?.toString() ?: "{}"
                    Log.i(TAG, "[WS:DOWNLINK] type=task_assign task_id=$taskId correlation_id=$correlationId device_id=$deviceId")
                    listeners.forEach { it.onTaskAssign(taskId, payloadJson) }
                }
                "goal_execution" -> {
                    val payloadObj = json.getAsJsonObject("payload")
                    val taskId = payloadObj?.get("task_id")?.asString ?: ""
                    val correlationId = json.get("correlation_id")?.asString ?: taskId
                    val groupId = payloadObj?.get("group_id")?.asString ?: ""
                    val payloadJson = payloadObj?.toString() ?: "{}"
                    Log.i(TAG, "[WS:DOWNLINK] type=goal_execution task_id=$taskId correlation_id=$correlationId group_id=$groupId")
                    listeners.forEach { it.onGoalExecution(taskId, payloadJson) }
                }
                "parallel_subtask" -> {
                    val payloadObj = json.getAsJsonObject("payload")
                    val taskId = payloadObj?.get("task_id")?.asString ?: ""
                    val groupId = payloadObj?.get("group_id")?.asString ?: ""
                    val subtaskIndex = payloadObj?.get("subtask_index")?.asString ?: ""
                    val payloadJson = payloadObj?.toString() ?: "{}"
                    Log.i(TAG, "[WS:DOWNLINK] type=parallel_subtask task_id=$taskId group_id=$groupId subtask_index=$subtaskIndex")
                    listeners.forEach { it.onParallelSubtask(taskId, payloadJson) }
                }
                "task_cancel" -> {
                    val payloadObj = json.getAsJsonObject("payload")
                    val taskId = payloadObj?.get("task_id")?.asString ?: ""
                    val payloadJson = payloadObj?.toString() ?: "{}"
                    Log.i(TAG, "[WS:DOWNLINK] type=task_cancel task_id=$taskId")
                    listeners.forEach { it.onTaskCancel(taskId, payloadJson) }
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
                    // 默认作为文本消息处理
                    val content = json.getAsJsonObject("payload")
                        ?.get("content")?.asString ?: text
                    listeners.forEach { it.onMessage(content) }
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
     * 发送心跳，附带 device_id 与重连次数用于联调追踪。
     */
    private fun sendHeartbeat() {
        val deviceId = "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}"
        val heartbeat = JsonObject().apply {
            addProperty("type", "heartbeat")
            addProperty("timestamp", System.currentTimeMillis())
            addProperty("device_id", deviceId)
            addProperty("reconnect_attempts", reconnectAttempts)
        }
        val json = gson.toJson(heartbeat)
        webSocket?.send(json)
        Log.d(TAG, "[WS:HEARTBEAT] device_id=$deviceId reconnect_attempts=$reconnectAttempts")
    }
    
    /**
     * 安排重连 — exponential backoff with jitter.
     *
     * Delay = RECONNECT_BACKOFF_MS[min(attempt, last)] + random jitter [0, RECONNECT_JITTER_MAX_MS).
     */
    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "[WS:RETRY] Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached; giving up")
            listeners.forEach { it.onError("无法连接到服务器") }
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
     * Flushes the [offlineQueue] by sending each queued message over the live socket.
     * Must only be called after [onOpen] confirms the connection is up.
     * Messages that fail to send are logged (with count) but not re-enqueued;
     * the flush is best-effort since re-enqueue on flush failure could cause infinite retry loops.
     */
    private fun flushOfflineQueue() {
        val messages = offlineQueue.drainAll()
        if (messages.isEmpty()) return

        Log.i(TAG, "[WS:OfflineQueue] Flushing ${messages.size} offline message(s)")
        var sent = 0
        var lost = 0
        for (msg in messages) {
            val ok = webSocket?.send(msg.json) ?: false
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
        "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}"
}

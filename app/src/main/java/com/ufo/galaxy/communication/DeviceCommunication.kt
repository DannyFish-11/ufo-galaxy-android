package com.ufo.galaxy.communication

import android.util.Log
import com.ufo.galaxy.config.ServerConfig
import com.ufo.galaxy.device.DeviceRegistry
import com.ufo.galaxy.device.DeviceStatus
import com.ufo.galaxy.protocol.AIPMessageBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * UFO Galaxy - 统一设备通信管理器
 * 
 * 对齐服务端 device_communication.py
 * 提供统一的设备通信层
 * 
 * 支持:
 * - WebSocket 实时双向通信
 * - 心跳保活
 * - 消息确认
 * - 命令执行
 * 
 * @author UFO Galaxy Team
 * @version 2.0
 */
class DeviceCommunication(
    private val deviceRegistry: DeviceRegistry
) {
    companion object {
        private const val TAG = "DeviceCommunication"
        private const val HEARTBEAT_INTERVAL_MS = 30000L
        private const val RECONNECT_DELAY_MS = 5000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }
    
    // WebSocket 客户端
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var reconnectAttempts = 0
    private var heartbeatJob: Job? = null

    // Index into ServerConfig.WS_PATHS for the current connection attempt
    private var wsPathIndex = 0

    // Stored server URL (ws-scheme base) for reconnection attempts
    private var lastServerWsBase: String? = null

    /**
     * Trace identifier for the current WS session.
     *
     * Refreshed on every new connection (onOpen) so all messages within one
     * session share the same trace ID for end-to-end correlation.
     */
    @Volatile private var sessionTraceId: String = AIPMessageBuilder.generateTraceId()

    /**
     * Whether cross-device mode is active.
     * Determines [route_mode] injected into every outbound message.
     */
    @Volatile private var crossDeviceEnabled: Boolean = false

    /** Returns the trace identifier for the current WS session. */
    fun getTraceId(): String = sessionTraceId

    /**
     * Toggle cross-device mode.  This changes the [route_mode] value injected
     * into outbound messages ([ROUTE_MODE_CROSS_DEVICE] vs [ROUTE_MODE_LOCAL]).
     */
    fun setCrossDeviceEnabled(enabled: Boolean) {
        crossDeviceEnabled = enabled
    }

    /** Derive route_mode from the cross-device switch state. */
    private fun currentRouteMode(): String =
        if (crossDeviceEnabled) AIPMessageBuilder.ROUTE_MODE_CROSS_DEVICE
        else AIPMessageBuilder.ROUTE_MODE_LOCAL
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 连接状态
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // 消息流
    private val _messages = MutableSharedFlow<DeviceMessage>()
    val messages: SharedFlow<DeviceMessage> = _messages.asSharedFlow()
    
    // 命令流
    private val _commands = MutableSharedFlow<CommandMessage>()
    val commands: SharedFlow<CommandMessage> = _messages.asSharedFlow()
    
    // 等待响应的请求
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    
    // 消息处理器
    private val messageHandlers = ConcurrentHashMap<String, suspend (JSONObject) -> JSONObject?>()
    
    // 监听器
    private var listener: Listener? = null
    
    /**
     * 连接状态
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        ERROR
    }
    
    /**
     * 监听器接口
     */
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onMessage(message: DeviceMessage)
        fun onCommand(command: CommandMessage)
        fun onError(error: String)
    }
    
    /**
     * 设置监听器
     */
    fun setListener(listener: Listener) {
        this.listener = listener
    }
    
    /**
     * 连接到服务器
     *
     * The connection URL is built via [ServerConfig.buildWsUrl] starting from
     * path index 0 (`/ws/device/{id}`, the preferred path).  On failure the
     * index advances and the next candidate path is used (same strategy as
     * [com.ufo.galaxy.client.AIPClient]).
     */
    fun connect(serverUrl: String) {
        if (isConnected) {
            Log.d(TAG, "已连接，跳过")
            return
        }
        
        _connectionState.value = ConnectionState.CONNECTING
        
        val wsBase = serverUrl.replace("http://", "ws://").replace("https://", "wss://")
        lastServerWsBase = wsBase
        connectToWsBase(wsBase)
    }

    /**
     * Open a WebSocket connection to [wsBase] using the current [wsPathIndex].
     *
     * Shared by [connect] and [scheduleReconnect] to avoid duplicating the
     * [WebSocketListener] implementation.
     */
    private fun connectToWsBase(wsBase: String) {
        val deviceId = deviceRegistry.getDeviceId()
        val fullUrl = ServerConfig.buildWsUrl(wsBase, deviceId, wsPathIndex)
        Log.i(TAG, "连接到服务器 (path index $wsPathIndex): $fullUrl")

        val request = Request.Builder().url(fullUrl).build()
        webSocket = httpClient.newWebSocket(request, createWsListener())
    }

    /** Create the [WebSocketListener] shared by all connection attempts. */
    private fun createWsListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Refresh trace ID at the start of each new WS session.
            sessionTraceId = AIPMessageBuilder.generateTraceId()
            Log.i(TAG, "WebSocket 已打开 [trace_id=$sessionTraceId route_mode=${currentRouteMode()}]")
            isConnected = true
            reconnectAttempts = 0
            wsPathIndex = 0  // Reset to preferred path for future connections
            _connectionState.value = ConnectionState.CONNECTED
            deviceRegistry.updateStatus(DeviceStatus.ONLINE)
            sendDeviceRegister()
            startHeartbeat()
            listener?.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "收到消息: ${text.take(100)}...")
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket 正在关闭: $code - $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket 已关闭: $code - $reason")
            handleDisconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket 失败 (path index $wsPathIndex): ${t.message}", t)
            wsPathIndex = (wsPathIndex + 1) % ServerConfig.WS_PATHS.size
            handleError(t.message ?: "连接失败")
        }
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        Log.i(TAG, "断开连接")
        stopHeartbeat()
        webSocket?.close(1000, "用户断开")
        webSocket = null
        handleDisconnect()
    }
    
    /**
     * 发送设备注册消息，并在成功后立即发送能力上报
     */
    private fun sendDeviceRegister() {
        val message = deviceRegistry.createRegisterMessage()
        send(message)
        Log.i(TAG, "发送设备注册")
        // Capability report follows registration immediately; the server
        // AndroidBridge expects it to populate the CapabilityRegistry.
        sendCapabilityReport()
    }

    /**
     * 发送能力上报消息
     *
     * Sends a [AIPMessageBuilder.MessageType.CAPABILITY_REPORT] payload so the
     * server's CapabilityRegistry records this device's supported actions.
     *
     * When cross-device mode is active, the payload includes a structured
     * `capability_schema` array with `exec_mode` for each capability so the
     * server routing layer can make accurate dispatch decisions.
     */
    private fun sendCapabilityReport() {
        val message = deviceRegistry.createCapabilityReportMessage(crossDeviceEnabled)
        send(message)
        if (crossDeviceEnabled) {
            val schemas = deviceRegistry.buildAllCapabilitySchemas()
            val localCount  = schemas.count { it.execMode == com.ufo.galaxy.device.DeviceRegistry.EXEC_MODE_LOCAL }
            val remoteCount = schemas.count { it.execMode == com.ufo.galaxy.device.DeviceRegistry.EXEC_MODE_REMOTE }
            val bothCount   = schemas.count { it.execMode == com.ufo.galaxy.device.DeviceRegistry.EXEC_MODE_BOTH }
            Log.i(TAG, "发送能力上报 [count=${schemas.size} local=$localCount remote=$remoteCount both=$bothCount]")
        } else {
            Log.i(TAG, "发送能力上报")
        }
    }
    
    /**
     * 发送心跳
     */
    private fun sendHeartbeat() {
        val message = deviceRegistry.createHeartbeatMessage()
        send(message)
        Log.d(TAG, "发送心跳")
    }
    
    /**
     * 发送消息
     */
    fun send(message: JSONObject): Boolean {
        if (!isConnected) {
            Log.w(TAG, "未连接，无法发送")
            return false
        }
        
        val sent = webSocket?.send(message.toString()) ?: false
        if (sent) {
            Log.d(TAG, "发送消息: ${message.toString().take(100)}...")
        }
        return sent
    }
    
    /**
     * 发送命令并等待响应
     *
     * The outbound message is built via [AIPMessageBuilder] so that all
     * AIP/1.0 + v3 fields (`protocol`, `version`, `device_id`, `device_type`,
     * `message_id`, `timestamp`) are populated consistently.  The `message_id`
     * is extracted from the built message and used as the correlation key.
     */
    suspend fun sendCommand(
        action: String,
        payload: JSONObject,
        timeoutMs: Long = 30000
    ): JSONObject {
        val deviceId = deviceRegistry.getDeviceId()

        val message = AIPMessageBuilder.build(
            messageType = "command",
            sourceNodeId = deviceId,
            targetNodeId = "server",
            payload = payload,
            traceId = sessionTraceId,
            routeMode = currentRouteMode()
        ).apply {
            // Keep `action` at top level for server-side command routing
            put("action", action)
        }

        // Reuse the message_id generated by AIPMessageBuilder for correlation
        val messageId = message.getString("message_id")
        
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[messageId] = deferred
        
        try {
            send(message)
            
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } finally {
            pendingRequests.remove(messageId)
        }
    }
    
    /**
     * 发送文本消息
     *
     * Built via [AIPMessageBuilder] for a consistent AIP v3 envelope.
     */
    fun sendText(text: String): Boolean {
        val deviceId = deviceRegistry.getDeviceId()
        val payload = JSONObject().apply { put("content", text) }

        val message = AIPMessageBuilder.build(
            messageType = "command",
            sourceNodeId = deviceId,
            targetNodeId = "server",
            payload = payload,
            traceId = sessionTraceId,
            routeMode = currentRouteMode()
        ).apply {
            put("action", "chat")
        }
        
        return send(message)
    }
    
    /**
     * 处理收到的消息
     *
     * Inbound text is first normalised to AIP/1.0 field names via
     * [AIPMessageBuilder.parse] so that Microsoft Galaxy, v3, and native
     * AIP/1.0 wire formats are all handled uniformly by the same routing
     * logic below.
     */
    private fun handleMessage(text: String) {
        scope.launch {
            try {
                val json = AIPMessageBuilder.parse(text) ?: run {
                    Log.w(TAG, "无法解析消息，已忽略: ${text.take(100)}")
                    return@launch
                }
                val type = json.optString("type")
                val action = json.optString("action")
                val messageId = json.optString("message_id")
                val correlationId = json.optString("correlation_id")
                val payload = json.optJSONObject("payload") ?: JSONObject()
                
                when (type) {
                    // 确认消息
                    "ack" -> {
                        Log.d(TAG, "收到确认: $action")

                        if (action == "handshake" || action == "register"
                            || action == AIPMessageBuilder.MessageType.DEVICE_REGISTER) {
                            Log.i(TAG, "设备注册成功")
                        }
                    }
                    
                    // 响应消息
                    "response" -> {
                        // 检查是否是等待的响应
                        if (correlationId.isNotEmpty() && pendingRequests.containsKey(correlationId)) {
                            pendingRequests[correlationId]?.complete(json)
                        }
                        
                        val message = DeviceMessage(
                            type = type,
                            action = action,
                            payload = payload,
                            messageId = messageId
                        )
                        _messages.emit(message)
                        listener?.onMessage(message)
                    }
                    
                    // 命令消息 (type="command") and AIP v3 task assignment (type="task_assign").
                    // Both are routed through the same handler dispatch path so that actions
                    // like screenshot/click/swipe execute correctly regardless of which wire
                    // format the server uses.
                    "command", "task_assign" -> {
                        val command = CommandMessage(
                            action = action,
                            payload = payload,
                            messageId = messageId
                        )
                        _commands.emit(command)
                        listener?.onCommand(command)
                        
                        // 调用注册的处理器
                        if (messageHandlers.containsKey(action)) {
                            val handler = messageHandlers[action]!!
                            val result = handler(payload)
                            
                            if (result != null) {
                                // For task_assign messages, prefer task_id for correlation so
                                // the server can match the response to the originating task.
                                // Fall back to message_id when task_id is absent.
                                val effectiveCorrelationId = if (type == "task_assign") {
                                    json.optString("task_id").takeIf { it.isNotEmpty() } ?: messageId
                                } else {
                                    messageId
                                }
                                // 发送响应 - built via AIPMessageBuilder for consistent fields
                                val response = AIPMessageBuilder.build(
                                    messageType = "response",
                                    sourceNodeId = deviceRegistry.getDeviceId(),
                                    targetNodeId = "server",
                                    payload = result,
                                    traceId = sessionTraceId,
                                    routeMode = currentRouteMode()
                                ).apply {
                                    put("action", action)
                                    put("correlation_id", effectiveCorrelationId)
                                }
                                send(response)
                            }
                        }
                    }
                    
                    // 心跳确认
                    "heartbeat_ack", "heartbeat" -> {
                        // 更新心跳时间
                    }
                    
                    // 错误消息
                    "error" -> {
                        val error = json.optString("error", json.optString("message", "未知错误"))
                        listener?.onError(error)
                    }
                    
                    // 其他消息
                    else -> {
                        val message = DeviceMessage(
                            type = type,
                            action = action,
                            payload = payload,
                            messageId = messageId
                        )
                        _messages.emit(message)
                        listener?.onMessage(message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "解析消息失败", e)
            }
        }
    }
    
    /**
     * 注册消息处理器
     */
    fun registerHandler(action: String, handler: suspend (JSONObject) -> JSONObject?) {
        messageHandlers[action] = handler
    }
    
    /**
     * 启动心跳
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
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
     * 处理断开连接
     */
    private fun handleDisconnect() {
        isConnected = false
        _connectionState.value = ConnectionState.DISCONNECTED
        deviceRegistry.updateStatus(DeviceStatus.OFFLINE)
        stopHeartbeat()
        listener?.onDisconnected()
    }
    
    /**
     * 处理错误
     */
    private fun handleError(error: String) {
        isConnected = false
        _connectionState.value = ConnectionState.ERROR
        deviceRegistry.updateStatus(DeviceStatus.ERROR)
        stopHeartbeat()
        listener?.onError(error)
        
        // 尝试重连
        scheduleReconnect()
    }
    
    /**
     * 安排重连
     *
     * Uses [lastServerWsBase] stored at connection time so that the reconnect
     * attempt targets the same server (but possibly the next WS path candidate).
     * Calls [connectToWsBase] to reuse the shared [WebSocketListener].
     */
    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "达到最大重连次数")
            return
        }
        
        reconnectAttempts++
        _connectionState.value = ConnectionState.RECONNECTING
        
        val delay = RECONNECT_DELAY_MS * reconnectAttempts
        Log.i(TAG, "将在 ${delay}ms 后重连 (第 $reconnectAttempts 次)")
        
        val wsBase = lastServerWsBase ?: return
        scope.launch {
            delay(delay)
            if (!isConnected) {
                connectToWsBase(wsBase)
            }
        }
    }
    
    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean = isConnected
}

/**
 * 设备消息
 */

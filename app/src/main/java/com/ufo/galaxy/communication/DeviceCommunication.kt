package com.ufo.galaxy.communication

import android.util.Log
import com.ufo.galaxy.device.DeviceRegistry
import com.ufo.galaxy.device.DeviceStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONObject
import java.util.*
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
     */
    fun connect(serverUrl: String) {
        if (isConnected) {
            Log.d(TAG, "已连接，跳过")
            return
        }
        
        _connectionState.value = ConnectionState.CONNECTING
        
        val deviceId = deviceRegistry.getDeviceId()
        val wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://")
        val fullUrl = "$wsUrl/ws/device/$deviceId"
        
        Log.i(TAG, "连接到服务器: $fullUrl")
        
        val request = Request.Builder()
            .url(fullUrl)
            .build()
        
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket 已打开")
                isConnected = true
                reconnectAttempts = 0
                _connectionState.value = ConnectionState.CONNECTED
                deviceRegistry.updateStatus(DeviceStatus.ONLINE)
                
                // 发送注册消息
                sendDeviceRegister()
                
                // 启动心跳
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
                Log.e(TAG, "WebSocket 失败: ${t.message}", t)
                handleError(t.message ?: "连接失败")
            }
        })
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
     * 发送设备注册消息
     */
    private fun sendDeviceRegister() {
        val message = deviceRegistry.createRegisterMessage()
        send(message)
        Log.i(TAG, "发送设备注册")
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
     */
    suspend fun sendCommand(
        action: String,
        payload: JSONObject,
        timeoutMs: Long = 30000
    ): JSONObject {
        val messageId = UUID.randomUUID().toString().take(8)
        
        val message = JSONObject().apply {
            put("type", "command")
            put("action", action)
            put("device_id", deviceRegistry.getDeviceId())
            put("timestamp", System.currentTimeMillis())
            put("message_id", messageId)
            put("payload", payload)
        }
        
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
     */
    fun sendText(text: String): Boolean {
        val message = JSONObject().apply {
            put("type", "command")
            put("action", "chat")
            put("device_id", deviceRegistry.getDeviceId())
            put("timestamp", System.currentTimeMillis())
            put("message_id", UUID.randomUUID().toString().take(8))
            
            val payload = JSONObject().apply {
                put("content", text)
            }
            put("payload", payload)
        }
        
        return send(message)
    }
    
    /**
     * 处理收到的消息
     */
    private fun handleMessage(text: String) {
        scope.launch {
            try {
                val json = JSONObject(text)
                val type = json.optString("type")
                val action = json.optString("action")
                val messageId = json.optString("message_id")
                val correlationId = json.optString("correlation_id")
                val payload = json.optJSONObject("payload") ?: JSONObject()
                
                when (type) {
                    // 确认消息
                    "ack" -> {
                        Log.d(TAG, "收到确认: $action")
                        
                        if (action == "handshake" || action == "register") {
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
                    
                    // 命令消息
                    "command" -> {
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
                                // 发送响应
                                val response = JSONObject().apply {
                                    put("type", "response")
                                    put("action", action)
                                    put("device_id", deviceRegistry.getDeviceId())
                                    put("timestamp", System.currentTimeMillis())
                                    put("message_id", UUID.randomUUID().toString().take(8))
                                    put("correlation_id", messageId)
                                    put("payload", result)
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
        
        scope.launch {
            delay(delay)
            // 需要外部调用 connect
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

package com.ufo.galaxy.network

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket 客户端
 * 
 * 功能：
 * 1. 连接到 Galaxy Gateway
 * 2. 发送和接收 AIP v2.0 消息
 * 3. 自动重连机制
 * 4. 心跳保持
 * 
 * @author UFO Galaxy Team
 * @version 2.0.0
 * @date 2026-01-24
 */
class WebSocketClient(
    private val gatewayUrl: String,
    private val deviceId: String,
    private val onMessageReceived: (JSONObject) -> Unit,
    private val onConnectionStateChanged: (ConnectionState) -> Unit
) {
    
    private val TAG = "WebSocketClient"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    
    @Volatile
    private var currentState: ConnectionState = ConnectionState.DISCONNECTED
    
    @Volatile
    private var isManualDisconnect = false
    
    @Volatile
    private var reconnectAttempts = 0
    
    private val maxReconnectAttempts = 10
    private val reconnectDelayMs = 5000L
    
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        ERROR
    }
    
    /**
     * 连接到 Gateway
     */
    fun connect() {
        if (currentState == ConnectionState.CONNECTED || currentState == ConnectionState.CONNECTING) {
            Log.w(TAG, "Already connected or connecting")
            return
        }
        
        isManualDisconnect = false
        reconnectAttempts = 0
        
        updateState(ConnectionState.CONNECTING)
        
        val request = Request.Builder()
            .url(gatewayUrl)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected to $gatewayUrl")
                reconnectAttempts = 0
                updateState(ConnectionState.CONNECTED)
                startHeartbeat()
                
                // 发送设备注册消息
                sendRegistrationMessage()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received text message: ${text.take(100)}")
                try {
                    val message = JSONObject(text)
                    handleMessage(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message: $text", e)
                }
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received binary message: ${bytes.size} bytes")
                // 处理二进制消息（多媒体传输）
                // 将二进制数据转换为 Base64 并封装为 JSON
                try {
                    val base64Data = android.util.Base64.encodeToString(
                        bytes.toByteArray(),
                        android.util.Base64.NO_WRAP
                    )
                    val jsonMessage = org.json.JSONObject().apply {
                        put("type", "binary_data")
                        put("data", base64Data)
                        put("size", bytes.size)
                    }
                    onMessageReceived(jsonMessage)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process binary message", e)
                }
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code - $reason")
                webSocket.close(1000, null)
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code - $reason")
                stopHeartbeat()
                updateState(ConnectionState.DISCONNECTED)
                
                if (!isManualDisconnect) {
                    scheduleReconnect()
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                stopHeartbeat()
                updateState(ConnectionState.ERROR)
                
                if (!isManualDisconnect) {
                    scheduleReconnect()
                }
            }
        })
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        isManualDisconnect = true
        reconnectJob?.cancel()
        stopHeartbeat()
        webSocket?.close(1000, "Manual disconnect")
        webSocket = null
        updateState(ConnectionState.DISCONNECTED)
    }
    
    /**
     * 发送消息
     */
    fun sendMessage(message: JSONObject): Boolean {
        val ws = webSocket
        if (ws == null || currentState != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send message: not connected")
            return false
        }
        
        val text = message.toString()
        Log.d(TAG, "Sending message: ${text.take(100)}")
        return ws.send(text)
    }
    
    /**
     * 发送原始消息
     */
    fun sendRawMessage(message: String): Boolean {
        val ws = webSocket
        if (ws == null || currentState != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send message: not connected")
            return false
        }
        
        Log.d(TAG, "Sending raw message: ${message.take(100)}")
        return ws.send(message)
    }
    
    /**
     * 发送设备注册消息
     */
    private fun sendRegistrationMessage() {
        val message = JSONObject().apply {
            put("version", "2.0")
            put("type", "device_register")
            put("device_id", deviceId)
            put("device_type", "android")
            put("timestamp", System.currentTimeMillis())
            put("capabilities", JSONObject().apply {
                put("screen_capture", true)
                put("input_control", true)
                put("accessibility", true)
                put("voice_input", true)
                put("webrtc", true)
            })
        }
        sendMessage(message)
    }
    
    /**
     * 启动心跳
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(30000) // 每 30 秒发送一次心跳
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
     * 发送心跳
     */
    private fun sendHeartbeat() {
        val message = JSONObject().apply {
            put("version", "2.0")
            put("type", "heartbeat")
            put("device_id", deviceId)
            put("timestamp", System.currentTimeMillis())
        }
        sendMessage(message)
    }
    
    /**
     * 处理接收到的消息
     */
    private fun handleMessage(message: JSONObject) {
        val type = message.optString("type")
        Log.d(TAG, "Handling message type: $type")
        
        when (type) {
            "heartbeat_ack" -> {
                Log.d(TAG, "Heartbeat acknowledged")
            }
            "device_register_ack" -> {
                Log.i(TAG, "Device registration acknowledged")
            }
            else -> {
                // 将消息传递给外部处理器
                onMessageReceived(message)
            }
        }
    }
    
    /**
     * 调度重连
     */
    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.e(TAG, "Max reconnect attempts reached")
            updateState(ConnectionState.ERROR)
            return
        }
        
        reconnectAttempts++
        updateState(ConnectionState.RECONNECTING)
        
        reconnectJob = scope.launch {
            val delay = reconnectDelayMs * reconnectAttempts
            Log.i(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempts/$maxReconnectAttempts)")
            delay(delay)
            connect()
        }
    }
    
    /**
     * 更新连接状态
     */
    private fun updateState(newState: ConnectionState) {
        if (currentState != newState) {
            currentState = newState
            scope.launch(Dispatchers.Main) {
                onConnectionStateChanged(newState)
            }
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        disconnect()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
    
    /**
     * 获取当前连接状态
     */
    fun getConnectionState(): ConnectionState = currentState
    
    /**
     * 是否已连接
     */
    fun isConnected(): Boolean = currentState == ConnectionState.CONNECTED
}

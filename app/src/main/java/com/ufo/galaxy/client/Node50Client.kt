package com.ufo.galaxy.client

import android.content.Context
import android.util.Log
import com.ufo.galaxy.config.ServerConfig
import com.ufo.galaxy.protocol.AIPMessageBuilder
import com.ufo.galaxy.protocol.AIPProtocol
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Node 50 客户端
 * 
 * 连接到 UFO³ Galaxy 系统的核心节点 Node 50 (NLU 引擎)
 * 使用 AIP/1.0 协议进行通信
 * 
 * Node 50 是系统的"大脑"，负责：
 * - 自然语言理解 (NLU)
 * - 任务分发
 * - 节点协调
 * - 命令路由
 * 
 * @author Manus AI
 * @version 1.0
 * @date 2026-01-22
 */
class Node50Client(
    private val context: Context,
    private val node50Url: String,  // 例如: "http://100.64.0.1:8050" (Tailscale IP)
    private val messageHandler: (JSONObject) -> Unit
) {
    
    private val TAG = "Node50Client"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    
    @Volatile
    private var isConnected = false
    
    @Volatile
    private var isRegistered = false
    
    /**
     * 连接到 Node 50
     *
     * The WebSocket URL is built via [ServerConfig.buildWsUrl] so that the
     * preferred `/ws/device/{id}` path is used (index 0), consistent with
     * the rest of the communication stack.
     */
    fun connect() {
        if (isConnected) {
            Log.w(TAG, "已连接到 Node 50")
            return
        }
        
        Log.i(TAG, "🔗 正在连接到 Node 50: $node50Url")
        
        // Build WebSocket URL using ServerConfig (preferred path: /ws/device/{id})
        val wsBase = node50Url.replace("http://", "ws://").replace("https://", "wss://")
        val wsUrl = ServerConfig.buildWsUrl(wsBase, AIPProtocol.CLIENT_ID, 0)
        
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("X-Client-ID", AIPProtocol.CLIENT_ID)
            .build()
        
        webSocket = okHttpClient.newWebSocket(request, webSocketListener)
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        Log.i(TAG, "🔌 断开与 Node 50 的连接")
        stopHeartbeat()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isConnected = false
        isRegistered = false
    }
    
    /**
     * 发送命令到 Node 50
     *
     * The outbound message is built via [AIPMessageBuilder] so that all
     * AIP/1.0 + v3 fields (`protocol`, `version`, `device_id`, `device_type`,
     * `message_id`, `timestamp`) are populated consistently.
     */
    fun sendCommand(command: String, context: JSONObject? = null): Boolean {
        if (!isConnected) {
            Log.w(TAG, "未连接到 Node 50，无法发送命令")
            return false
        }

        val payload = JSONObject().apply {
            put("command", command)
            put("context", context ?: JSONObject().apply {
                put("platform", "android")
                put("client_id", AIPProtocol.CLIENT_ID)
            })
        }
        val message = AIPMessageBuilder.build(
            messageType = AIPProtocol.MessageType.COMMAND,
            sourceNodeId = AIPProtocol.CLIENT_ID,
            targetNodeId = AIPProtocol.NODE_50_ID,
            payload = payload
        )
        return sendMessage(message)
    }
    
    /**
     * 发送 AIP/1.0 消息
     */
    fun sendMessage(message: JSONObject): Boolean {
        if (!isConnected) {
            Log.w(TAG, "未连接，无法发送消息")
            return false
        }
        
        return try {
            val success = webSocket?.send(message.toString()) ?: false
            if (success) {
                Log.d(TAG, "📤 消息已发送: ${message.optString("type", "unknown")}")
            } else {
                Log.w(TAG, "❌ 消息发送失败")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "❌ 发送消息异常", e)
            false
        }
    }
    
    /**
     * 注册到 Node 50
     *
     * Built via [AIPMessageBuilder] for a consistent AIP v3 envelope with
     * `device_id`, `device_type`, `message_id`, and `timestamp`.
     *
     * The outbound `type` is [AIPMessageBuilder.MessageType.DEVICE_REGISTER]
     * to match the server AndroidBridge's expected message type.
     */
    private fun registerToNode50() {
        Log.i(TAG, "📝 正在向 Node 50 注册...")

        val payload = JSONObject().apply {
            put("client_type", "android")
            put("client_id", AIPProtocol.CLIENT_ID)
            put("device_info", AIPProtocol.getDeviceInfo())
            put("capabilities", AIPProtocol.getCapabilities())
        }
        val registerMessage = AIPMessageBuilder.build(
            messageType = AIPMessageBuilder.MessageType.DEVICE_REGISTER,
            sourceNodeId = AIPProtocol.CLIENT_ID,
            targetNodeId = AIPProtocol.NODE_50_ID,
            payload = payload
        )
        sendMessage(registerMessage)
    }
    
    /**
     * 发送心跳
     *
     * Built via [AIPMessageBuilder] for consistent AIP v3 envelope fields.
     */
    private fun sendHeartbeat() {
        val payload = JSONObject().apply {
            put("status", "online")
            put("client_id", AIPProtocol.CLIENT_ID)
        }
        val heartbeat = AIPMessageBuilder.build(
            messageType = AIPProtocol.MessageType.HEARTBEAT,
            sourceNodeId = AIPProtocol.CLIENT_ID,
            targetNodeId = AIPProtocol.NODE_50_ID,
            payload = payload
        )
        sendMessage(heartbeat)
    }
    
    /**
     * 启动心跳
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        
        heartbeatJob = scope.launch {
            while (isActive && isConnected) {
                try {
                    sendHeartbeat()
                    delay(30000) // 每 30 秒一次心跳
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 心跳发送异常", e)
                }
            }
        }
        
        Log.i(TAG, "✅ 心跳已启动")
    }
    
    /**
     * 停止心跳
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
    
    /**
     * 检查 Node 50 健康状态
     */
    suspend fun checkHealth(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$node50Url/health")
                    .get()
                    .build()
                
                val response = okHttpClient.newCall(request).execute()
                val isHealthy = response.isSuccessful
                
                Log.i(TAG, if (isHealthy) "✅ Node 50 健康检查通过" else "❌ Node 50 健康检查失败")
                isHealthy
            } catch (e: Exception) {
                Log.e(TAG, "❌ Node 50 健康检查异常", e)
                false
            }
        }
    }
    
    /**
     * 获取连接状态
     */
    fun isConnected(): Boolean = isConnected
    
    /**
     * 获取注册状态
     */
    fun isRegistered(): Boolean = isRegistered
    
    /**
     * 清理资源
     */
    fun cleanup() {
        disconnect()
        scope.cancel()
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
    }
    
    /**
     * WebSocket 监听器
     */
    private val webSocketListener = object : WebSocketListener() {
        
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "✅ WebSocket 连接已建立")
            isConnected = true
            
            // 注册到 Node 50
            registerToNode50()
            
            // 启动心跳
            startHeartbeat()
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "📨 收到消息: ${text.take(100)}...")
            
            try {
                // Normalise to AIP/1.0 field names regardless of wire format
                val message = AIPMessageBuilder.parse(text)
                
                if (message != null) {
                    // 检查是否是注册响应
                    val messageType = message.optString("type")
                    if (messageType == AIPProtocol.MessageType.RESPONSE) {
                        val payload = message.optJSONObject("payload")
                        if (payload?.optString("type") == "register_success") {
                            isRegistered = true
                            Log.i(TAG, "✅ 已成功注册到 Node 50")
                        }
                    }
                    
                    // 在主线程处理消息
                    scope.launch(Dispatchers.Main) {
                        messageHandler(message)
                    }
                } else {
                    Log.w(TAG, "⚠️ 收到无效的消息（非 JSON）")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ 解析消息失败", e)
            }
        }
        
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "⚠️ WebSocket 正在关闭: code=$code, reason=$reason")
            webSocket.close(1000, null)
        }
        
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "❌ WebSocket 已关闭: code=$code, reason=$reason")
            isConnected = false
            isRegistered = false
            stopHeartbeat()
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "❌ WebSocket 连接失败: ${t.message}", t)
            isConnected = false
            isRegistered = false
            stopHeartbeat()
        }
    }
}

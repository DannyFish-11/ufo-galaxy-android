package com.ufo.galaxy.api

import com.ufo.galaxy.config.ServerConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * UFO³ Galaxy API 客户端
 *
 * 负责与 Galaxy Gateway 进行通信，支持：
 * - HTTP REST API 调用
 * - WebSocket 实时通信
 * - 节点推送和订阅
 * - 智能路由和负载均衡
 *
 * REST device calls attempt `/api/v1/devices/*` first (current server routes) and
 * automatically fall back to `/api/devices/*` (legacy) when the server returns 404.
 *
 * @author Manus AI
 * @date 2026-01-22
 */
class GalaxyApiClient(
    private val baseUrl: String = "http://100.123.215.126:8888",
    private val apiKey: String? = null
) {
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 连接状态流
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // 消息流
    private val _messages = MutableSharedFlow<GalaxyMessage>()
    val messages: SharedFlow<GalaxyMessage> = _messages.asSharedFlow()
    
    // 节点状态流
    private val _nodeStatus = MutableStateFlow<Map<String, NodeStatus>>(emptyMap())
    val nodeStatus: StateFlow<Map<String, NodeStatus>> = _nodeStatus.asStateFlow()
    
    /**
     * 连接状态枚举
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
    
    /**
     * 初始化客户端
     */
    fun initialize() {
        scope.launch {
            connectWebSocket()
        }
    }
    
    /**
     * 连接 WebSocket
     */
    private fun connectWebSocket() {
        _connectionState.value = ConnectionState.CONNECTING
        
        val request = Request.Builder()
            .url("$baseUrl/ws")
            .apply {
                apiKey?.let { addHeader("Authorization", "Bearer $it") }
            }
            .build()
        
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.CONNECTED
                scope.launch {
                    _messages.emit(GalaxyMessage.SystemMessage("WebSocket 连接已建立"))
                }
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    handleWebSocketMessage(text)
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.ERROR
                scope.launch {
                    _messages.emit(GalaxyMessage.ErrorMessage("连接失败: ${t.message}"))
                }
                
                // 5秒后重连
                scope.launch {
                    delay(5000)
                    connectWebSocket()
                }
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        })
    }
    
    /**
     * 处理 WebSocket 消息
     */
    private suspend fun handleWebSocketMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            
            when (type) {
                "node_status" -> {
                    val nodeId = json.optString("node_id")
                    val status = json.optString("status")
                    val data = json.optJSONObject("data")
                    
                    val currentStatus = _nodeStatus.value.toMutableMap()
                    currentStatus[nodeId] = NodeStatus(
                        nodeId = nodeId,
                        status = status,
                        lastUpdate = System.currentTimeMillis(),
                        data = data?.toString()
                    )
                    _nodeStatus.value = currentStatus
                }
                
                "message" -> {
                    val content = json.optString("content")
                    val role = json.optString("role", "assistant")
                    _messages.emit(GalaxyMessage.ChatMessage(role, content))
                }
                
                "notification" -> {
                    val title = json.optString("title")
                    val body = json.optString("body")
                    _messages.emit(GalaxyMessage.NotificationMessage(title, body))
                }
                
                "error" -> {
                    val error = json.optString("error")
                    _messages.emit(GalaxyMessage.ErrorMessage(error))
                }
            }
        } catch (e: Exception) {
            _messages.emit(GalaxyMessage.ErrorMessage("解析消息失败: ${e.message}"))
        }
    }
    
    /**
     * 发送聊天消息 — 统一走 /api/v1/chat (MultiLLMRouter)，fallback 到 /api/llm/chat
     *
     * @param message  用户消息
     * @param model    模型名称，"auto" 表示由 MultiLLMRouter 自动选择
     * @param provider 指定 Provider（可选），如 "openai"/"anthropic"/"deepseek"/"groq"/"ollama"/"oneapi"
     * @param taskType 任务类型提示（可选），帮助路由器选择最优模型
     */
    suspend fun sendChatMessage(
        message: String,
        model: String = "auto",
        provider: String? = null,
        taskType: String? = null
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", message)
                    })
                })
                put("model", model)
                provider?.let { put("provider", it) }
                taskType?.let { put("task_type", it) }
            }

            val requestBody = json.toString()
                .toRequestBody("application/json".toMediaType())

            // 首选: /api/v1/chat (MultiLLMRouter)
            val v1Request = Request.Builder()
                .url("$baseUrl/api/v1/chat")
                .post(requestBody)
                .apply {
                    apiKey?.let { addHeader("Authorization", "Bearer $it") }
                }
                .build()

            val v1Response = httpClient.newCall(v1Request).execute()

            val response = if (v1Response.isSuccessful) {
                v1Response
            } else if (v1Response.code == 404) {
                // Fallback: /api/llm/chat (旧路径)
                val legacyRequestBody = json.toString()
                    .toRequestBody("application/json".toMediaType())
                val legacyRequest = Request.Builder()
                    .url("$baseUrl/api/llm/chat")
                    .post(legacyRequestBody)
                    .apply {
                        apiKey?.let { addHeader("Authorization", "Bearer $it") }
                    }
                    .build()
                httpClient.newCall(legacyRequest).execute()
            } else {
                v1Response
            }

            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val responseJson = JSONObject(responseBody)
                val choices = responseJson.getJSONArray("choices")
                val firstChoice = choices.getJSONObject(0)
                val messageObj = firstChoice.getJSONObject("message")
                val content = messageObj.getString("content")
                val usedProvider = responseJson.optString("provider", "unknown")
                val usedModel = responseJson.optString("model", model)

                Result.success(ChatResponse(
                    content = content,
                    provider = usedProvider,
                    model = usedModel
                ))
            } else {
                Result.failure(Exception("API 调用失败: ${response.code} - $responseBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取 LLM 路由器统计信息 — 从服务端 MultiLLMRouter 获取
     */
    suspend fun getLLMStats(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/v1/llm/stats")
                .get()
                .apply {
                    apiKey?.let { addHeader("Authorization", "Bearer $it") }
                }
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (response.isSuccessful) {
                Result.success(JSONObject(responseBody))
            } else {
                Result.failure(Exception("Stats unavailable: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 调用指定节点
     */
    suspend fun invokeNode(
        nodeId: String,
        method: String,
        params: Map<String, Any>
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("method", method)
                put("params", JSONObject(params))
            }
            
            val requestBody = json.toString()
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/api/node/$nodeId/invoke")
                .post(requestBody)
                .apply {
                    apiKey?.let { addHeader("Authorization", "Bearer $it") }
                }
                .build()
            
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (response.isSuccessful) {
                Result.success(JSONObject(responseBody))
            } else {
                Result.failure(Exception("节点调用失败: ${response.code} - $responseBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 获取系统健康状态
     */
    suspend fun getHealthStatus(): Result<HealthStatus> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()
            
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                Result.success(HealthStatus(
                    status = json.optString("status", "unknown"),
                    version = json.optString("version", "unknown"),
                    uptime = json.optLong("uptime", 0),
                    providers = json.optJSONObject("providers")?.let { providersJson ->
                        providersJson.keys().asSequence().associateWith { key ->
                            providersJson.getString(key)
                        }
                    } ?: emptyMap()
                ))
            } else {
                Result.failure(Exception("健康检查失败: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 订阅节点状态更新
     */
    fun subscribeToNode(nodeId: String) {
        webSocket?.send(JSONObject().apply {
            put("type", "subscribe")
            put("node_id", nodeId)
        }.toString())
    }

    /**
     * 取消订阅节点
     */
    fun unsubscribeFromNode(nodeId: String) {
        webSocket?.send(JSONObject().apply {
            put("type", "unsubscribe")
            put("node_id", nodeId)
        }.toString())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Device registration / heartbeat / discovery  (v1 with legacy fallback)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Register a device with the server.
     *
     * Tries `POST /api/v1/devices/register` first. On HTTP 404 it retries with
     * the legacy path `POST /api/devices/register`.
     */
    suspend fun registerDevice(deviceInfo: JSONObject): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            executeWithV1Fallback("/register", deviceInfo)
        }

    /**
     * Send a device heartbeat.
     *
     * Tries `POST /api/v1/devices/heartbeat` first; falls back to
     * `POST /api/devices/heartbeat` on 404.
     */
    suspend fun sendDeviceHeartbeat(deviceId: String): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply { put("device_id", deviceId) }
            executeWithV1Fallback("/heartbeat", body)
        }

    /**
     * Discover registered devices.
     *
     * Tries `GET /api/v1/devices/discover` first; falls back to
     * `GET /api/devices/discover` on 404.
     */
    suspend fun discoverDevices(): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            getWithV1Fallback("/discover")
        }

    /**
     * Execute a POST request against a device API sub-path, retrying with the
     * legacy prefix when the v1 endpoint returns 404.
     */
    private fun executeWithV1Fallback(subPath: String, body: JSONObject): Result<JSONObject> {
        return try {
            val v1Result = postDeviceApi(subPath, body, v1 = true)
            if (v1Result.isSuccess) {
                v1Result
            } else {
                // Check if failure was a 404 before falling back
                val ex = v1Result.exceptionOrNull()
                if (ex?.message?.contains("404") == true) {
                    postDeviceApi(subPath, body, v1 = false)
                } else {
                    v1Result
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun postDeviceApi(subPath: String, body: JSONObject, v1: Boolean): Result<JSONObject> {
        return try {
            val url = ServerConfig.buildRestUrl(baseUrl, subPath, v1)
            val requestBody = body.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .apply { apiKey?.let { addHeader("Authorization", "Bearer $it") } }
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"
            if (response.isSuccessful) {
                Result.success(JSONObject(responseBody))
            } else {
                Result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getWithV1Fallback(subPath: String): Result<JSONObject> {
        return try {
            val v1Result = getDeviceApi(subPath, v1 = true)
            if (v1Result.isSuccess) {
                v1Result
            } else {
                val ex = v1Result.exceptionOrNull()
                if (ex?.message?.contains("404") == true) {
                    getDeviceApi(subPath, v1 = false)
                } else {
                    v1Result
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getDeviceApi(subPath: String, v1: Boolean): Result<JSONObject> {
        return try {
            val url = ServerConfig.buildRestUrl(baseUrl, subPath, v1)
            val request = Request.Builder()
                .url(url)
                .get()
                .apply { apiKey?.let { addHeader("Authorization", "Bearer $it") } }
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"
            if (response.isSuccessful) {
                Result.success(JSONObject(responseBody))
            } else {
                Result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 关闭客户端
     */
    fun close() {
        webSocket?.close(1000, "Client closing")
        scope.cancel()
    }
}

/**
 * Galaxy 消息类型
 */
sealed class GalaxyMessage {
    data class ChatMessage(val role: String, val content: String) : GalaxyMessage()
    data class SystemMessage(val content: String) : GalaxyMessage()
    data class NotificationMessage(val title: String, val body: String) : GalaxyMessage()
    data class ErrorMessage(val error: String) : GalaxyMessage()
}

/**
 * 聊天响应
 */
data class ChatResponse(
    val content: String,
    val provider: String,
    val model: String
)

/**
 * 节点状态
 */
data class NodeStatus(
    val nodeId: String,
    val status: String,
    val lastUpdate: Long,
    val data: String?
)

/**
 * 健康状态
 */
data class HealthStatus(
    val status: String,
    val version: String,
    val uptime: Long,
    val providers: Map<String, String>
)

/**
 * 多模型路由统计
 */
data class LLMRouterStats(
    val providers: Map<String, ProviderStatus>,
    val totalRequests: Long,
    val taskTypeDistribution: Map<String, Long>
)

/**
 * Provider 状态
 */
data class ProviderStatus(
    val name: String,
    val status: String,
    val circuitState: String,
    val avgLatencyMs: Long,
    val errorCount: Long
)

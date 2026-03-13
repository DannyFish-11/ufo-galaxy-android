package com.ufo.galaxy.agent

import android.content.Context
import android.util.Log
import com.ufo.galaxy.autonomy.AutonomyManager
import com.ufo.galaxy.config.ServerConfig
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * UFO³ Galaxy Agent 主控制器
 * 
 * 这是 Android 设备作为 Galaxy 系统 Agent 节点的统一入口和管理中心。
 * 
 * 核心职责：
 * 1. 管理 Agent 的完整生命周期（注册、连接、运行、注销）
 * 2. 协调各个子模块（注册、WebSocket、消息处理、自主操纵）
 * 3. 提供统一的对外 API 接口
 * 4. 自适应和自配置能力
 * 5. 状态监控和健康检查
 * 
 * @author Manus AI
 * @version 1.0
 * @date 2026-01-22
 */
class GalaxyAgent private constructor(private val context: Context) {
    
    private val TAG = "GalaxyAgent"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 核心组件
    private val agentRegistry = AgentRegistry.getInstance(context)
    private val autonomyManager = AutonomyManager.getInstance(context)
    private lateinit var agentWebSocket: AgentWebSocket
    private lateinit var messageHandler: AgentMessageHandler
    
    // 配置
    private var gatewayUrl = "ws://192.168.1.100:8765/ws/agent" // 默认值，需要配置
    
    // 状态
    @Volatile
    private var isInitialized = false
    
    @Volatile
    private var isRunning = false
    
    companion object {
        @Volatile
        private var instance: GalaxyAgent? = null
        
        /**
         * 获取 GalaxyAgent 单例
         */
        fun getInstance(context: Context): GalaxyAgent {
            return instance ?: synchronized(this) {
                instance ?: GalaxyAgent(context.applicationContext).also { instance = it }
            }
        }
    }
    
    /**
     * 初始化 Agent
     */
    fun initialize(gatewayUrl: String? = null) {
        if (isInitialized) {
            Log.w(TAG, "Agent 已初始化，跳过")
            return
        }
        
        Log.i(TAG, "🚀 正在初始化 UFO³ Galaxy Agent...")
        
        // 更新 Gateway URL
        gatewayUrl?.let { this.gatewayUrl = it }
        
        // 初始化 WebSocket 和消息处理器
        agentWebSocket = AgentWebSocket(
            gatewayUrl = this.gatewayUrl,
            agentRegistry = agentRegistry,
            messageHandler = { message -> messageHandler.handleMessage(message) }
        )
        
        messageHandler = AgentMessageHandler(context, agentWebSocket)
        
        isInitialized = true
        Log.i(TAG, "✅ UFO³ Galaxy Agent 初始化完成")
        Log.i(TAG, "   Agent ID: ${agentRegistry.getAgentId()}")
        Log.i(TAG, "   Device ID: ${agentRegistry.getDeviceId()}")
        Log.i(TAG, "   Gateway URL: ${this.gatewayUrl}")
    }
    
    /**
     * 启动 Agent
     */
    fun start() {
        if (!isInitialized) {
            Log.e(TAG, "❌ Agent 未初始化，请先调用 initialize()")
            return
        }
        
        if (isRunning) {
            Log.w(TAG, "Agent 已在运行中")
            return
        }
        
        Log.i(TAG, "🚀 正在启动 UFO³ Galaxy Agent...")
        
        scope.launch {
            try {
                // 步骤 1: 检查无障碍服务
                if (!autonomyManager.isAccessibilityServiceEnabled()) {
                    Log.w(TAG, "⚠️ 无障碍服务未启用，部分功能将受限")
                }
                
                // 步骤 2: 注册或验证注册状态
                if (!agentRegistry.isRegistered()) {
                    Log.i(TAG, "📝 Agent 未注册，开始注册流程...")
                    val registered = registerToGateway()
                    
                    if (!registered) {
                        Log.e(TAG, "❌ Agent 注册失败，无法启动")
                        return@launch
                    }
                } else {
                    Log.i(TAG, "✅ Agent 已注册")
                }
                
                // 步骤 3: 建立 WebSocket 连接
                Log.i(TAG, "🔗 正在连接到 Galaxy Gateway...")
                agentWebSocket.connect()
                
                isRunning = true
                Log.i(TAG, "✅ UFO³ Galaxy Agent 已启动")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Agent 启动失败", e)
            }
        }
    }
    
    /**
     * 停止 Agent
     */
    fun stop() {
        if (!isRunning) {
            Log.w(TAG, "Agent 未运行")
            return
        }
        
        Log.i(TAG, "🛑 正在停止 UFO³ Galaxy Agent...")
        
        // 断开 WebSocket 连接
        agentWebSocket.disconnect()
        
        isRunning = false
        Log.i(TAG, "✅ UFO³ Galaxy Agent 已停止")
    }
    
    /**
     * 重启 Agent
     */
    fun restart() {
        Log.i(TAG, "🔄 正在重启 UFO³ Galaxy Agent...")
        stop()
        delay(1000)
        start()
    }
    
    /**
     * 向 Gateway 注册
     *
     * Sends an HTTP POST to the Gateway's /api/v1/devices/register endpoint.
     * If the network is unreachable the agent falls back to local (offline) registration
     * so the rest of the startup flow can still proceed.
     */
    private suspend fun registerToGateway(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val registrationRequest = agentRegistry.generateRegistrationRequest()
                Log.i(TAG, "📤 发送注册请求: ${registrationRequest.toString(2)}")

                val httpBase = ServerConfig.wsToHttpBase(
                    gatewayUrl.replaceFirst(Regex("/ws(/.*)?$"), "")
                )
                val registerUrl = ServerConfig.buildRestUrl(httpBase, "/register")

                val client = OkHttpClient()
                val body = registrationRequest.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(registerUrl)
                    .post(body)
                    .build()

                val response = try {
                    client.newCall(request).execute()
                } catch (netEx: Exception) {
                    // Gateway unreachable – use an offline registration token so the
                    // agent can still start and retry registration on the next run.
                    Log.w(TAG, "⚠️ Gateway 不可达，使用离线注册模式: ${netEx.message}")
                    val fallback = JSONObject().apply {
                        put("status", "success")
                        put("token", "offline-token-${System.currentTimeMillis()}")
                        put("message", "离线注册模式（Gateway 不可达）")
                    }
                    return@withContext agentRegistry.handleRegistrationResponse(fallback)
                }

                val bodyStr = response.body?.string() ?: "{}"
                val responseJson = runCatching { JSONObject(bodyStr) }.getOrDefault(JSONObject())
                val success = agentRegistry.handleRegistrationResponse(responseJson)

                if (success) {
                    Log.i(TAG, "✅ Agent 注册成功")
                } else {
                    Log.e(TAG, "❌ Agent 注册失败: $bodyStr")
                }
                success

            } catch (e: Exception) {
                Log.e(TAG, "❌ 注册过程异常", e)
                false
            }
        }
    }
    
    /**
     * 延迟函数（用于重启）
     */
    private suspend fun delay(ms: Long) {
        kotlinx.coroutines.delay(ms)
    }
    
    /**
     * 注销 Agent
     */
    fun unregister() {
        Log.i(TAG, "📝 正在注销 Agent...")
        
        stop()
        agentRegistry.unregister()
        
        Log.i(TAG, "✅ Agent 已注销")
    }
    
    /**
     * 配置 Gateway URL
     */
    fun setGatewayUrl(url: String) {
        this.gatewayUrl = url
        Log.i(TAG, "Gateway URL 已更新: $url")
    }
    
    /**
     * 获取 Agent 状态
     */
    fun getStatus(): JSONObject {
        return JSONObject().apply {
            put("is_initialized", isInitialized)
            put("is_running", isRunning)
            put("is_registered", agentRegistry.isRegistered())
            put("is_connected", if (isInitialized) agentWebSocket.isConnected() else false)
            put("accessibility_enabled", autonomyManager.isAccessibilityServiceEnabled())
            put("agent_id", agentRegistry.getAgentId())
            put("device_id", agentRegistry.getDeviceId())
            put("gateway_url", gatewayUrl)
        }
    }
    
    /**
     * 运行健康检查
     */
    fun runHealthCheck(): JSONObject {
        val result = JSONObject()
        val checks = org.json.JSONArray()
        
        // 检查 1: 初始化状态
        checks.put(JSONObject().apply {
            put("name", "初始化状态")
            put("status", if (isInitialized) "✅ 已初始化" else "❌ 未初始化")
            put("passed", isInitialized)
        })
        
        // 检查 2: 注册状态
        checks.put(JSONObject().apply {
            put("name", "注册状态")
            put("status", if (agentRegistry.isRegistered()) "✅ 已注册" else "❌ 未注册")
            put("passed", agentRegistry.isRegistered())
        })
        
        // 检查 3: WebSocket 连接
        val isConnected = if (isInitialized) agentWebSocket.isConnected() else false
        checks.put(JSONObject().apply {
            put("name", "WebSocket 连接")
            put("status", if (isConnected) "✅ 已连接" else "❌ 未连接")
            put("passed", isConnected)
        })
        
        // 检查 4: 无障碍服务
        val accessibilityEnabled = autonomyManager.isAccessibilityServiceEnabled()
        checks.put(JSONObject().apply {
            put("name", "无障碍服务")
            put("status", if (accessibilityEnabled) "✅ 已启用" else "❌ 未启用")
            put("passed", accessibilityEnabled)
        })
        
        // 检查 5: 自主操纵能力
        val autonomyDiagnostics = autonomyManager.runDiagnostics()
        checks.put(JSONObject().apply {
            put("name", "自主操纵能力")
            put("status", if (autonomyDiagnostics.optString("status") == "success") "✅ 正常" else "❌ 异常")
            put("passed", autonomyDiagnostics.optString("status") == "success")
            put("details", autonomyDiagnostics)
        })
        
        // 计算总体健康状态
        var passedCount = 0
        for (i in 0 until checks.length()) {
            if (checks.getJSONObject(i).optBoolean("passed", false)) {
                passedCount++
            }
        }
        
        result.put("status", if (passedCount == checks.length()) "healthy" else "unhealthy")
        result.put("passed_checks", passedCount)
        result.put("total_checks", checks.length())
        result.put("checks", checks)
        result.put("timestamp", System.currentTimeMillis())
        
        return result
    }
    
    /**
     * 发送消息到 Gateway
     */
    fun sendMessage(message: JSONObject): Boolean {
        if (!isInitialized || !isRunning) {
            Log.w(TAG, "Agent 未运行，无法发送消息")
            return false
        }
        
        return agentWebSocket.sendMessage(message)
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        Log.i(TAG, "🧹 正在清理 Agent 资源...")
        
        stop()
        
        if (isInitialized) {
            messageHandler.cleanup()
            agentWebSocket.cleanup()
            autonomyManager.cleanup()
        }
        
        scope.cancel()
        isInitialized = false
        
        Log.i(TAG, "✅ Agent 资源已清理")
    }
}

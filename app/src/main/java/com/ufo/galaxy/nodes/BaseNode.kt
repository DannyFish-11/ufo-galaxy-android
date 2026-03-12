package com.ufo.galaxy.nodes

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 节点基类
 */
abstract class BaseNode(
    protected val context: Context,
    val nodeId: String,
    val name: String
) {
    companion object {
        private const val TAG = "BaseNode"
    }

    /**
     * 获取节点状态
     */
    open fun getStatus(): String = "healthy"

    /**
     * 处理请求
     */
    abstract suspend fun handle(request: JSONObject): JSONObject

    /**
     * 关闭节点
     */
    open fun shutdown() {
        Log.i(TAG, "Node $nodeId ($name) shutdown")
    }
    
    /**
     * 获取节点能力列表
     */
    open fun getCapabilities(): List<String> = emptyList()
    
    /**
     * 执行节点操作
     */
    open suspend fun execute(action: String, params: JSONObject): NodeResult {
        val request = JSONObject().apply {
            put("action", action)
            params.keys().forEach { key ->
                put(key, params.get(key))
            }
        }
        val result = handle(request)
        return if (result.optBoolean("success", false)) {
            NodeResult.success(result)
        } else {
            NodeResult.error(result.optString("error", "Unknown error"))
        }
    }
}

/**
 * Node 00: State Machine (简化版)
 */
class Node00StateMachine(context: Context) : BaseNode(context, "00", "StateMachine") {
    
    private val state = mutableMapOf<String, Any>()

    override suspend fun handle(request: JSONObject): JSONObject {
        val action = request.optString("action")
        
        return when (action) {
            "get" -> {
                val key = request.optString("key")
                JSONObject().apply {
                    put("success", true)
                    put("value", state[key])
                }
            }
            "set" -> {
                val key = request.optString("key")
                val value = request.opt("value")
                state[key] = value
                JSONObject().apply {
                    put("success", true)
                }
            }
            else -> JSONObject().apply {
                put("success", false)
                put("error", "Unknown action")
            }
        }
    }
}

/**
 * Node 04: Tool Router (安卓版)
 */
class Node04ToolRouter(
    context: Context,
    private val toolRegistry: com.ufo.galaxy.core.ToolRegistry
) : BaseNode(context, "04", "ToolRouter") {

    /**
     * 路由任务到合适的工具
     */
    suspend fun routeTask(taskDescription: String, taskContext: Map<String, Any>): JSONObject {
        // 简化版：基于关键词匹配
        val lowerTask = taskDescription.lowercase()
        
        val selectedTool = when {
            "camera" in lowerTask || "photo" in lowerTask -> {
                toolRegistry.findByCapability("camera").firstOrNull()
            }
            "termux" in lowerTask || "shell" in lowerTask || "python" in lowerTask -> {
                toolRegistry.findByCapability("shell").firstOrNull()
            }
            "automate" in lowerTask || "task" in lowerTask -> {
                toolRegistry.findByCapability("automation").firstOrNull()
            }
            else -> null
        }

        return if (selectedTool != null) {
            JSONObject().apply {
                put("success", true)
                put("selected_tool", selectedTool.name)
                put("package", selectedTool.packageName)
                put("capabilities", org.json.JSONArray(selectedTool.capabilities))
                put("reason", "Matched by keyword")
            }
        } else {
            JSONObject().apply {
                put("success", false)
                put("error", "No suitable tool found")
            }
        }
    }

    override suspend fun handle(request: JSONObject): JSONObject {
        val taskDescription = request.optString("task")
        val context = mutableMapOf<String, Any>()
        
        // 解析 context
        request.optJSONObject("context")?.let { ctx ->
            ctx.keys().forEach { key ->
                context[key] = ctx.get(key)
            }
        }
        
        return routeTask(taskDescription, context)
    }
}

/**
 * Node 33: ADB Self-Control (使用无障碍服务)
 */
class Node33ADBSelf(context: Context) : BaseNode(context, "33", "ADBSelf") {

    override suspend fun handle(request: JSONObject): JSONObject {
        val action = request.optString("action")
        
        // 检查无障碍服务是否可用
        val accessibilityService = com.ufo.galaxy.service.UFOAccessibilityService.getInstance()
        if (accessibilityService == null) {
            return JSONObject().apply {
                put("success", false)
                put("error", "Accessibility service not enabled. Please enable it in Settings.")
            }
        }
        
        return when (action) {
            "click" -> {
                val x = request.optDouble("x").toFloat()
                val y = request.optDouble("y").toFloat()
                val success = accessibilityService.performClick(x, y)
                JSONObject().apply {
                    put("success", success)
                    if (success) {
                        put("message", "Clicked at ($x, $y)")
                    }
                }
            }
            
            "swipe" -> {
                val startX = request.optDouble("start_x").toFloat()
                val startY = request.optDouble("start_y").toFloat()
                val endX = request.optDouble("end_x").toFloat()
                val endY = request.optDouble("end_y").toFloat()
                val duration = request.optLong("duration", 300)
                val success = accessibilityService.performSwipe(startX, startY, endX, endY, duration)
                JSONObject().apply {
                    put("success", success)
                    if (success) {
                        put("message", "Swiped from ($startX, $startY) to ($endX, $endY)")
                    }
                }
            }
            
            "scroll" -> {
                val direction = request.optString("direction", "down")
                val amount = request.optInt("amount", 500)
                val success = accessibilityService.performScroll(direction, amount)
                JSONObject().apply {
                    put("success", success)
                    if (success) {
                        put("message", "Scrolled $direction")
                    }
                }
            }
            
            "get_screen" -> {
                accessibilityService.getScreenContent()
            }
            
            "click_text" -> {
                val text = request.optString("text")
                val exact = request.optBoolean("exact", false)
                val success = accessibilityService.clickElementByText(text, exact)
                JSONObject().apply {
                    put("success", success)
                    if (success) {
                        put("message", "Clicked element with text: $text")
                    } else {
                        put("error", "Element not found: $text")
                    }
                }
            }
            
            "click_id" -> {
                val viewId = request.optString("view_id")
                val success = accessibilityService.clickElementById(viewId)
                JSONObject().apply {
                    put("success", success)
                    if (success) {
                        put("message", "Clicked element with id: $viewId")
                    } else {
                        put("error", "Element not found: $viewId")
                    }
                }
            }
            
            "input_text" -> {
                val finderText = request.optString("finder_text")
                val inputText = request.optString("input_text")
                val success = accessibilityService.inputTextByFinder(finderText, inputText)
                JSONObject().apply {
                    put("success", success)
                    if (success) {
                        put("message", "Input text: $inputText")
                    } else {
                        put("error", "Failed to input text")
                    }
                }
            }
            
            "home" -> {
                val success = accessibilityService.performHome()
                JSONObject().apply {
                    put("success", success)
                }
            }
            
            "back" -> {
                val success = accessibilityService.performBack()
                JSONObject().apply {
                    put("success", success)
                }
            }
            
            "recents" -> {
                val success = accessibilityService.performRecents()
                JSONObject().apply {
                    put("success", success)
                }
            }
            
            "screenshot" -> {
                // 截图（返回 Base64）
                var result: JSONObject? = null
                val latch = java.util.concurrent.CountDownLatch(1)
                
                accessibilityService.takeScreenshot { screenshotResult ->
                    result = screenshotResult
                    latch.countDown()
                }
                
                // 等待截图完成（最多 5 秒）
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                
                result ?: JSONObject().apply {
                    put("success", false)
                    put("error", "Screenshot timeout")
                }
            }
            
            "screenshot_file" -> {
                // 截图并保存到文件
                val filePath = request.optString("file_path", "/sdcard/ufo_screenshot_${System.currentTimeMillis()}.png")
                var result: JSONObject? = null
                val latch = java.util.concurrent.CountDownLatch(1)
                
                accessibilityService.takeScreenshotToFile(filePath) { screenshotResult ->
                    result = screenshotResult
                    latch.countDown()
                }
                
                // 等待截图完成（最多 5 秒）
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                
                result ?: JSONObject().apply {
                    put("success", false)
                    put("error", "Screenshot timeout")
                }
            }
            
            else -> JSONObject().apply {
                put("success", false)
                put("error", "Unknown action: $action")
            }
        }
    }
}

/**
 * Node 41: MQTT Communication
 */
class Node41MQTT(context: Context) : BaseNode(context, "41", "MQTT") {

    override suspend fun handle(request: JSONObject): JSONObject {
        // MQTT 通信
        return JSONObject().apply {
            put("success", true)
            put("message", "MQTT node ready")
        }
    }
}

/**
 * Node 58: Multi-Model Intelligent Router
 *
 * 对接服务端 MultiLLMRouter，通过统一 API 网关路由到多个 LLM Provider。
 * 支持任务分类、复杂度评估、Provider 选择、熔断器保护。
 *
 * 链路: Android Node58 → /api/v1/chat (服务端) → MultiLLMRouter → Provider/OneAPI
 */
class Node58ModelRouter(
    context: Context,
    private val baseUrl: String = "http://100.123.215.126:8888",
    private val apiKey: String? = null
) : BaseNode(context, "58", "ModelRouter") {

    companion object {
        private const val TAG = "Node58ModelRouter"
    }

    /**
     * 任务类型 — 与服务端 MultiLLMRouter 的 8 类任务对齐
     */
    enum class TaskType(val value: String) {
        REASONING("reasoning"),
        FAST_RESPONSE("fast_response"),
        CODING("coding"),
        CREATIVE("creative"),
        ANALYSIS("analysis"),
        PLANNING("planning"),
        AGENT_CONTROL("agent_control"),
        GENERAL("general")
    }

    /**
     * Provider 信息
     */
    data class ProviderInfo(
        val name: String,
        val status: String,        // healthy / degraded / down
        val models: List<String>,
        val latencyMs: Long
    )

    // 缓存的 provider 状态
    @Volatile
    private var cachedProviders: Map<String, ProviderInfo> = emptyMap()
    private var lastProviderRefresh: Long = 0
    private val providerCacheTtlMs = 60_000L

    override fun getCapabilities() = listOf(
        "model_routing", "multi_llm", "task_classification",
        "provider_selection", "oneapi_gateway"
    )

    override suspend fun handle(request: JSONObject): JSONObject {
        val action = request.optString("action", "chat")
        return when (action) {
            "chat" -> routeChat(request)
            "classify" -> classifyTask(request)
            "list_providers" -> listProviders()
            "list_models" -> listModels()
            "provider_stats" -> getProviderStats()
            "health" -> checkHealth()
            else -> JSONObject().apply {
                put("success", false)
                put("error", "Unknown action: $action")
            }
        }
    }

    /**
     * 核心路由: 发送聊天请求到服务端 MultiLLMRouter
     */
    private suspend fun routeChat(request: JSONObject): JSONObject {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val messages = request.optJSONArray("messages") ?: org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", request.optString("message", request.optString("content", "")))
                    })
                }

                val model = request.optString("model", "auto")
                val provider = request.optString("provider", "")
                val taskHint = request.optString("task_type", "")
                val temperature = request.optDouble("temperature", 0.7)

                val body = JSONObject().apply {
                    put("messages", messages)
                    put("model", model)
                    if (provider.isNotEmpty()) put("provider", provider)
                    if (taskHint.isNotEmpty()) put("task_type", taskHint)
                    put("temperature", temperature)
                    put("stream", false)
                }

                val requestBody = body.toString()
                    .toByteArray()
                    .let { okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), it) }

                val httpRequest = okhttp3.Request.Builder()
                    .url("$baseUrl/api/v1/chat")
                    .post(requestBody)
                    .apply { apiKey?.let { addHeader("Authorization", "Bearer $it") } }
                    .build()

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body()?.string() ?: ""

                if (response.isSuccessful) {
                    val responseJson = JSONObject(responseBody)
                    JSONObject().apply {
                        put("success", true)
                        put("content", responseJson.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content", "") ?: "")
                        put("provider", responseJson.optString("provider", "unknown"))
                        put("model", responseJson.optString("model", model))
                        put("usage", responseJson.optJSONObject("usage") ?: JSONObject())
                        put("task_type", responseJson.optString("task_type", "general"))
                    }
                } else {
                    // Fallback: 尝试 /api/llm/chat (旧路径)
                    fallbackChat(body)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chat routing failed", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", "Chat routing failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Fallback 到旧的 /api/llm/chat 端点
     */
    private fun fallbackChat(body: JSONObject): JSONObject {
        return try {
            val requestBody = body.toString()
                .toByteArray()
                .let { okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), it) }

            val request = okhttp3.Request.Builder()
                .url("$baseUrl/api/llm/chat")
                .post(requestBody)
                .apply { apiKey?.let { addHeader("Authorization", "Bearer $it") } }
                .build()

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body()?.string() ?: ""

            if (response.isSuccessful) {
                val responseJson = JSONObject(responseBody)
                JSONObject().apply {
                    put("success", true)
                    put("content", responseJson.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "") ?: "")
                    put("provider", responseJson.optString("provider", "unknown"))
                    put("model", responseJson.optString("model", "unknown"))
                    put("fallback", true)
                }
            } else {
                JSONObject().apply {
                    put("success", false)
                    put("error", "Both /api/v1/chat and /api/llm/chat failed: ${response.code()}")
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("error", "Fallback chat failed: ${e.message}")
            }
        }
    }

    /**
     * 任务分类 — 在 Android 端预分类，发给服务端做最终决策
     */
    private fun classifyTask(request: JSONObject): JSONObject {
        val content = request.optString("content", request.optString("message", ""))
        val lower = content.lowercase()

        val taskType = when {
            lower.containsAny("analyze", "分析", "compare", "对比", "evaluate", "评估") -> TaskType.ANALYSIS
            lower.containsAny("code", "代码", "function", "函数", "bug", "debug", "implement", "实现") -> TaskType.CODING
            lower.containsAny("plan", "计划", "strategy", "策略", "design", "设计", "architecture") -> TaskType.PLANNING
            lower.containsAny("write", "写", "story", "故事", "creative", "创意", "poem", "诗") -> TaskType.CREATIVE
            lower.containsAny("reason", "推理", "think", "思考", "why", "为什么", "prove", "证明") -> TaskType.REASONING
            lower.containsAny("quick", "快", "simple", "简单", "what is", "是什么", "translate", "翻译") -> TaskType.FAST_RESPONSE
            lower.containsAny("agent", "智能体", "control", "控制", "device", "设备", "execute", "执行") -> TaskType.AGENT_CONTROL
            else -> TaskType.GENERAL
        }

        return JSONObject().apply {
            put("success", true)
            put("task_type", taskType.value)
            put("confidence", 0.7)
            put("content_length", content.length)
        }
    }

    /**
     * 列出可用 Provider — 从服务端 /health 获取
     */
    private suspend fun listProviders(): JSONObject {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                refreshProvidersIfNeeded()
                JSONObject().apply {
                    put("success", true)
                    put("providers", org.json.JSONArray().apply {
                        cachedProviders.forEach { (name, info) ->
                            put(JSONObject().apply {
                                put("name", name)
                                put("status", info.status)
                                put("models", org.json.JSONArray(info.models))
                                put("latency_ms", info.latencyMs)
                            })
                        }
                    })
                    put("count", cachedProviders.size)
                }
            } catch (e: Exception) {
                JSONObject().apply {
                    put("success", false)
                    put("error", "Failed to list providers: ${e.message}")
                }
            }
        }
    }

    /**
     * 列出所有可用模型 — 聚合所有 Provider 的模型
     */
    private suspend fun listModels(): JSONObject {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                refreshProvidersIfNeeded()
                val allModels = cachedProviders.flatMap { (provider, info) ->
                    info.models.map { model ->
                        JSONObject().apply {
                            put("model", model)
                            put("provider", provider)
                            put("status", info.status)
                        }
                    }
                }
                JSONObject().apply {
                    put("success", true)
                    put("models", org.json.JSONArray().apply { allModels.forEach { put(it) } })
                    put("count", allModels.size)
                }
            } catch (e: Exception) {
                JSONObject().apply {
                    put("success", false)
                    put("error", "Failed to list models: ${e.message}")
                }
            }
        }
    }

    /**
     * Provider 统计 — 从服务端 /api/v1/llm/stats 获取
     */
    private suspend fun getProviderStats(): JSONObject {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("$baseUrl/api/v1/llm/stats")
                    .get()
                    .apply { apiKey?.let { addHeader("Authorization", "Bearer $it") } }
                    .build()

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body()?.string() ?: "{}"

                if (response.isSuccessful) {
                    JSONObject(body).apply { put("success", true) }
                } else {
                    JSONObject().apply {
                        put("success", false)
                        put("error", "Stats unavailable: ${response.code()}")
                    }
                }
            } catch (e: Exception) {
                JSONObject().apply {
                    put("success", false)
                    put("error", "Stats failed: ${e.message}")
                }
            }
        }
    }

    /**
     * 健康检查
     */
    private suspend fun checkHealth(): JSONObject {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("$baseUrl/health")
                    .get()
                    .build()

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body()?.string() ?: "{}"

                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    // 更新 provider 缓存
                    json.optJSONObject("providers")?.let { providersJson ->
                        val providers = mutableMapOf<String, ProviderInfo>()
                        providersJson.keys().forEach { key ->
                            providers[key] = ProviderInfo(
                                name = key,
                                status = providersJson.optString(key, "unknown"),
                                models = emptyList(),
                                latencyMs = 0
                            )
                        }
                        cachedProviders = providers
                        lastProviderRefresh = System.currentTimeMillis()
                    }
                    json.apply { put("success", true) }
                } else {
                    JSONObject().apply {
                        put("success", false)
                        put("error", "Health check failed: ${response.code()}")
                    }
                }
            } catch (e: Exception) {
                JSONObject().apply {
                    put("success", false)
                    put("error", "Health check failed: ${e.message}")
                }
            }
        }
    }

    private fun refreshProvidersIfNeeded() {
        if (System.currentTimeMillis() - lastProviderRefresh > providerCacheTtlMs) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("$baseUrl/health")
                    .get()
                    .build()

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = JSONObject(response.body()?.string() ?: "{}")
                    json.optJSONObject("providers")?.let { providersJson ->
                        val providers = mutableMapOf<String, ProviderInfo>()
                        providersJson.keys().forEach { key ->
                            providers[key] = ProviderInfo(
                                name = key,
                                status = providersJson.optString(key, "unknown"),
                                models = emptyList(),
                                latencyMs = 0
                            )
                        }
                        cachedProviders = providers
                        lastProviderRefresh = System.currentTimeMillis()
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it) }
}

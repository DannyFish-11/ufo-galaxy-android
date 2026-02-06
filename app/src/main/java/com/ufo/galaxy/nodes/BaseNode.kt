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
 * Node 58: Model Router
 */
class Node58ModelRouter(context: Context) : BaseNode(context, "58", "ModelRouter") {

    override suspend fun handle(request: JSONObject): JSONObject {
        // 模型路由（可以调用 OneAPI）
        return JSONObject().apply {
            put("success", true)
            put("model", "local_or_cloud")
        }
    }
}

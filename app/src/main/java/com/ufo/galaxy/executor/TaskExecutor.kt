package com.ufo.galaxy.executor

import android.content.Context
import android.util.Log
import com.ufo.galaxy.autonomy.AutonomyManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * 任务执行器
 * 
 * 接收来自 Node 50 的任务指令，解析并执行
 * 支持多种任务类型：
 * - UI 自动化（点击、滑动、输入）
 * - 应用控制（打开、关闭、切换）
 * - 系统控制（音量、亮度、网络）
 * - 信息查询（屏幕内容、应用状态）
 * 
 * @author Manus AI
 * @version 1.0
 * @date 2026-01-22
 */
class TaskExecutor(
    private val context: Context,
    private val autonomyManager: AutonomyManager
) {
    
    private val TAG = "TaskExecutor"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * 任务类型
     */
    object TaskType {
        const val UI_AUTOMATION = "ui_automation"      // UI 自动化
        const val APP_CONTROL = "app_control"          // 应用控制
        const val SYSTEM_CONTROL = "system_control"    // 系统控制
        const val QUERY = "query"                      // 信息查询
        const val COMPOUND = "compound"                // 复合任务
    }
    
    /**
     * 执行任务
     * 
     * @param taskMessage AIP/1.0 格式的任务消息（已由 [com.ufo.galaxy.protocol.AIPMessageBuilder] 标准化）
     * @return 执行结果
     */
    suspend fun executeTask(taskMessage: JSONObject): JSONObject {
        return withContext(Dispatchers.Default) {
            try {
                Log.i(TAG, "📋 开始执行任务")
                
                // 提取任务信息（AIP/1.0 标准字段）
                val messageId = taskMessage.optString("message_id", "unknown")
                val payload = taskMessage.optJSONObject("payload")
                
                if (payload == null) {
                    return@withContext createErrorResult(messageId, "无效的任务 Payload")
                }
                
                // 解析任务
                val task = parseTask(payload)
                
                // 执行任务
                val result = when (task.type) {
                    TaskType.UI_AUTOMATION -> executeUIAutomation(task)
                    TaskType.APP_CONTROL -> executeAppControl(task)
                    TaskType.SYSTEM_CONTROL -> executeSystemControl(task)
                    TaskType.QUERY -> executeQuery(task)
                    TaskType.COMPOUND -> executeCompoundTask(task)
                    else -> createErrorResult(messageId, "不支持的任务类型: ${task.type}")
                }
                
                Log.i(TAG, "✅ 任务执行完成")
                result
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ 任务执行失败", e)
                createErrorResult("unknown", "任务执行异常: ${e.message}")
            }
        }
    }
    
    /**
     * 解析任务
     */
    private fun parseTask(payload: JSONObject): Task {
        return Task(
            type = payload.optString("task_type", TaskType.UI_AUTOMATION),
            action = payload.optString("action", ""),
            target = payload.optString("target", ""),
            params = payload.optJSONObject("params") ?: JSONObject(),
            subtasks = payload.optJSONArray("subtasks")
        )
    }
    
    /**
     * 执行 UI 自动化任务
     */
    private suspend fun executeUIAutomation(task: Task): JSONObject {
        return withContext(Dispatchers.Main) {
            try {
                Log.i(TAG, "🤖 执行 UI 自动化: ${task.action}")
                
                val result = when (task.action) {
                    "click" -> {
                        // 点击指定元素
                        val text = task.params.optString("text", "")
                        val resourceId = task.params.optString("resource_id", "")
                        
                        if (text.isNotEmpty()) {
                            autonomyManager.clickByText(text)
                        } else if (resourceId.isNotEmpty()) {
                            autonomyManager.clickByResourceId(resourceId)
                        } else {
                            false
                        }
                    }
                    
                    "input" -> {
                        // 输入文本
                        val text = task.params.optString("text", "")
                        autonomyManager.inputText(text)
                    }
                    
                    "swipe" -> {
                        // 滑动
                        val direction = task.params.optString("direction", "up")
                        autonomyManager.swipe(direction)
                    }
                    
                    "scroll" -> {
                        // 滚动
                        val direction = task.params.optString("direction", "down")
                        autonomyManager.scroll(direction)
                    }
                    
                    "back" -> {
                        // 返回
                        autonomyManager.performBack()
                    }
                    
                    "home" -> {
                        // 回到主屏幕
                        autonomyManager.performHome()
                    }
                    
                    "recent" -> {
                        // 打开最近任务
                        autonomyManager.performRecent()
                    }
                    
                    else -> false
                }
                
                if (result) {
                    createSuccessResult("UI 自动化执行成功")
                } else {
                    createErrorResult("unknown", "UI 自动化执行失败")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ UI 自动化执行异常", e)
                createErrorResult("unknown", "UI 自动化执行异常: ${e.message}")
            }
        }
    }
    
    /**
     * 执行应用控制任务
     */
    private suspend fun executeAppControl(task: Task): JSONObject {
        return withContext(Dispatchers.Main) {
            try {
                Log.i(TAG, "📱 执行应用控制: ${task.action}")
                
                val result = when (task.action) {
                    "open" -> {
                        // 打开应用
                        val packageName = task.params.optString("package_name", "")
                        autonomyManager.openApp(packageName)
                    }
                    
                    "close" -> {
                        // 关闭应用
                        val packageName = task.params.optString("package_name", "")
                        autonomyManager.closeApp(packageName)
                    }
                    
                    "switch" -> {
                        // 切换应用
                        val packageName = task.params.optString("package_name", "")
                        autonomyManager.switchToApp(packageName)
                    }
                    
                    else -> false
                }
                
                if (result) {
                    createSuccessResult("应用控制执行成功")
                } else {
                    createErrorResult("unknown", "应用控制执行失败")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ 应用控制执行异常", e)
                createErrorResult("unknown", "应用控制执行异常: ${e.message}")
            }
        }
    }
    
    /**
     * 执行系统控制任务
     */
    private suspend fun executeSystemControl(task: Task): JSONObject {
        return withContext(Dispatchers.Main) {
            try {
                Log.i(TAG, "⚙️ 执行系统控制: ${task.action}")
                
                val result = when (task.action) {
                    "volume" -> {
                        // 调节音量
                        val level = task.params.optInt("level", -1)
                        autonomyManager.setVolume(level)
                    }
                    
                    "brightness" -> {
                        // 调节亮度
                        val level = task.params.optInt("level", -1)
                        autonomyManager.setBrightness(level)
                    }
                    
                    "wifi" -> {
                        // 控制 WiFi
                        val enable = task.params.optBoolean("enable", true)
                        autonomyManager.setWiFi(enable)
                    }
                    
                    "bluetooth" -> {
                        // 控制蓝牙
                        val enable = task.params.optBoolean("enable", true)
                        autonomyManager.setBluetooth(enable)
                    }
                    
                    else -> false
                }
                
                if (result) {
                    createSuccessResult("系统控制执行成功")
                } else {
                    createErrorResult("unknown", "系统控制执行失败")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ 系统控制执行异常", e)
                createErrorResult("unknown", "系统控制执行异常: ${e.message}")
            }
        }
    }
    
    /**
     * 执行查询任务
     */
    private suspend fun executeQuery(task: Task): JSONObject {
        return withContext(Dispatchers.Main) {
            try {
                Log.i(TAG, "🔍 执行查询: ${task.action}")
                
                val data = when (task.action) {
                    "screen_content" -> {
                        // 获取屏幕内容
                        val uiTree = autonomyManager.captureUITree()
                        JSONObject().apply {
                            put("ui_tree", uiTree)
                        }
                    }
                    
                    "current_app" -> {
                        // 获取当前应用
                        val packageName = autonomyManager.getCurrentApp()
                        JSONObject().apply {
                            put("package_name", packageName)
                        }
                    }
                    
                    "device_status" -> {
                        // 获取设备状态
                        autonomyManager.getDeviceStatus()
                    }
                    
                    else -> JSONObject()
                }
                
                createSuccessResult("查询执行成功", data)
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ 查询执行异常", e)
                createErrorResult("unknown", "查询执行异常: ${e.message}")
            }
        }
    }
    
    /**
     * 执行复合任务
     */
    private suspend fun executeCompoundTask(task: Task): JSONObject {
        return withContext(Dispatchers.Default) {
            try {
                Log.i(TAG, "🔄 执行复合任务")
                
                val subtasks = task.subtasks
                if (subtasks == null || subtasks.length() == 0) {
                    return@withContext createErrorResult("unknown", "复合任务没有子任务")
                }
                
                val results = JSONArray()
                
                for (i in 0 until subtasks.length()) {
                    val subtaskPayload = subtasks.getJSONObject(i)
                    val subtask = parseTask(subtaskPayload)
                    
                    val subtaskResult = when (subtask.type) {
                        TaskType.UI_AUTOMATION -> executeUIAutomation(subtask)
                        TaskType.APP_CONTROL -> executeAppControl(subtask)
                        TaskType.SYSTEM_CONTROL -> executeSystemControl(subtask)
                        TaskType.QUERY -> executeQuery(subtask)
                        else -> createErrorResult("unknown", "不支持的子任务类型")
                    }
                    
                    results.put(subtaskResult)
                    
                    // 如果子任务失败，是否继续？
                    val continueOnError = task.params.optBoolean("continue_on_error", false)
                    if (!subtaskResult.optBoolean("success", false) && !continueOnError) {
                        break
                    }
                    
                    // 子任务间延迟
                    val delay = task.params.optLong("delay_ms", 500)
                    delay(delay)
                }
                
                createSuccessResult("复合任务执行完成", JSONObject().apply {
                    put("subtask_results", results)
                })
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ 复合任务执行异常", e)
                createErrorResult("unknown", "复合任务执行异常: ${e.message}")
            }
        }
    }
    
    /**
     * 创建成功结果
     */
    private fun createSuccessResult(message: String, data: JSONObject? = null): JSONObject {
        return JSONObject().apply {
            put("success", true)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
            if (data != null) {
                put("data", data)
            }
        }
    }
    
    /**
     * 创建错误结果
     */
    private fun createErrorResult(messageId: String, error: String): JSONObject {
        return JSONObject().apply {
            put("success", false)
            put("error", error)
            put("message_id", messageId)
            put("timestamp", System.currentTimeMillis())
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        scope.cancel()
    }
    
    /**
     * 任务数据类
     */
    private data class Task(
        val type: String,
        val action: String,
        val target: String,
        val params: JSONObject,
        val subtasks: JSONArray?
    )
}

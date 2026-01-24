package com.ufo.galaxy.task

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 任务执行器
 * 负责执行从 Galaxy Gateway 接收到的任务
 */
class TaskExecutor(private val context: Context) {
    
    companion object {
        private const val TAG = "TaskExecutor"
    }
    
    /**
     * 执行任务
     * @param taskId 任务 ID
     * @param taskType 任务类型
     * @param payload 任务数据
     * @return 执行结果
     */
    fun executeTask(taskId: String, taskType: String, payload: JSONObject): JSONObject {
        Log.i(TAG, "Executing task: $taskId ($taskType)")
        
        val result = JSONObject()
        result.put("task_id", taskId)
        result.put("status", "success")
        
        try {
            when (taskType) {
                "screen_capture" -> {
                    // 屏幕截图任务
                    result.put("message", "Screen capture initiated")
                    result.put("data", JSONObject().apply {
                        put("timestamp", System.currentTimeMillis())
                    })
                }
                
                "app_control" -> {
                    // 应用控制任务
                    val action = payload.optString("action")
                    val packageName = payload.optString("package_name")
                    result.put("message", "App control: $action for $packageName")
                }
                
                "system_info" -> {
                    // 系统信息查询
                    result.put("data", getSystemInfo())
                }
                
                "text_input" -> {
                    // 文本输入任务
                    val text = payload.optString("text")
                    result.put("message", "Text input: $text")
                }
                
                else -> {
                    // 未知任务类型
                    result.put("status", "error")
                    result.put("message", "Unknown task type: $taskType")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Task execution failed", e)
            result.put("status", "error")
            result.put("message", e.message ?: "Unknown error")
        }
        
        return result
    }
    
    /**
     * 获取系统信息
     */
    private fun getSystemInfo(): JSONObject {
        return JSONObject().apply {
            put("os", "Android")
            put("version", android.os.Build.VERSION.RELEASE)
            put("model", android.os.Build.MODEL)
            put("manufacturer", android.os.Build.MANUFACTURER)
            put("timestamp", System.currentTimeMillis())
        }
    }
}

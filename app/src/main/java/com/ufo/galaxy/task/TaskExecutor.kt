package com.ufo.galaxy.task

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ufo.galaxy.webrtc.ScreenCaptureService
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
                    // 启动屏幕采集服务
                    val intent = Intent(context, ScreenCaptureService::class.java)
                    context.startService(intent)
                    result.put("message", "Screen capture service started")
                    result.put("data", JSONObject().apply {
                        put("timestamp", System.currentTimeMillis())
                        put("service", "ScreenCaptureService")
                    })
                }
                
                "app_control" -> {
                    // 应用控制任务（需要无障碍服务支持）
                    val action = payload.optString("action")
                    val packageName = payload.optString("package_name")
                    
                    when (action) {
                        "launch" -> {
                            // 启动应用
                            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                            if (launchIntent != null) {
                                context.startActivity(launchIntent)
                                result.put("message", "Launched app: $packageName")
                            } else {
                                result.put("status", "error")
                                result.put("message", "App not found: $packageName")
                            }
                        }
                        else -> {
                            result.put("message", "App control action queued: $action for $packageName")
                            result.put("note", "Full control requires UFOAccessibilityService")
                        }
                    }
                }
                
                "system_info" -> {
                    // 系统信息查询
                    result.put("data", getSystemInfo())
                }
                
                "text_input" -> {
                    // 文本输入任务（需要无障碍服务支持）
                    val text = payload.optString("text")
                    result.put("message", "Text input queued: $text")
                    result.put("note", "Actual input requires UFOAccessibilityService")
                    result.put("data", JSONObject().apply {
                        put("text", text)
                        put("length", text.length)
                    })
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

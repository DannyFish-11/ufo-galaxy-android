package com.ufo.galaxy.task

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ufo.galaxy.webrtc.ScreenCaptureService
import com.ufo.galaxy.webrtc.WebRTCManager
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

                "screen_stream_start" -> {
                    val resultCode = payload.optInt("result_code", -1)
                    val dataJson = payload.optJSONObject("data")
                    val gatewayUrl = payload.optString("gateway_url").takeIf { it.isNotEmpty() }
                    val deviceId = payload.optString("device_id").takeIf { it.isNotEmpty() }

                    if (resultCode == -1) {
                        result.put("status", "error")
                        result.put("message", "screen_stream_start requires 'result_code' in payload")
                    } else {
                        val manager = WebRTCManager.getInstance(context)
                        if (gatewayUrl != null || deviceId != null) {
                            manager.initialize(
                                gatewayWsBase = gatewayUrl ?: com.ufo.galaxy.config.ServerConfig.DEFAULT_BASE_URL,
                                deviceId = deviceId ?: "android_device"
                            )
                        }
                        // Reconstruct a minimal Intent for MediaProjection if data is embedded
                        val projectionIntent = Intent().apply {
                            if (dataJson != null) {
                                // Callers may pass the MediaProjection data as a serialised Intent
                                // stored under "intent_action" / "intent_type".  For simple cases
                                // the executor is invoked from within the app where the actual
                                // Intent object is passed via the companion helper below.
                                dataJson.optString("intent_action").takeIf { it.isNotEmpty() }
                                    ?.let { action = it }
                            }
                        }
                        manager.startScreenSharing(resultCode, projectionIntent)
                        result.put("message", "WebRTC screen stream started")
                        result.put("data", JSONObject().apply {
                            put("timestamp", System.currentTimeMillis())
                            put("streaming", true)
                        })
                    }
                }

                "screen_stream_stop" -> {
                    WebRTCManager.getInstance(context).stopScreenSharing()
                    result.put("message", "WebRTC screen stream stopped")
                    result.put("data", JSONObject().apply {
                        put("timestamp", System.currentTimeMillis())
                        put("streaming", false)
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
     * Convenience overload: start a WebRTC stream when the caller holds the
     * MediaProjection [Intent] object directly (e.g., from an Activity result).
     */
    fun startScreenStream(
        taskId: String,
        resultCode: Int,
        projectionData: Intent,
        gatewayWsBase: String,
        deviceId: String
    ): JSONObject {
        val payload = JSONObject().apply {
            put("result_code", resultCode)
            put("gateway_url", gatewayWsBase)
            put("device_id", deviceId)
        }
        val result = JSONObject()
        result.put("task_id", taskId)
        result.put("status", "success")
        try {
            val manager = WebRTCManager.getInstance(context)
            manager.initialize(gatewayWsBase = gatewayWsBase, deviceId = deviceId)
            manager.startScreenSharing(resultCode, projectionData)
            result.put("message", "WebRTC screen stream started")
            result.put("data", JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("streaming", true)
            })
        } catch (e: Exception) {
            Log.e(TAG, "startScreenStream failed", e)
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

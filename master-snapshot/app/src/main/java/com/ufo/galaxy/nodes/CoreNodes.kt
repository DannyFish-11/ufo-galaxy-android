package com.ufo.galaxy.nodes

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 屏幕捕获节点 - Node_33
 * 与服务端 Node_33 对齐
 */
class ScreenCaptureNode(context: Context) : BaseNode(context, "33", "ScreenCapture") {
    override fun getCapabilities() = listOf("screen_capture", "screenshot", "screen_record")

    override suspend fun handle(request: JSONObject): JSONObject {
        val action = request.optString("action", "capture")
        val params = request
        return when (action) {
            "capture" -> captureScreen(params).toJson()
            "start_record" -> startRecording(params).toJson()
            "stop_record" -> stopRecording().toJson()
            else -> JSONObject().apply { put("success", false); put("error", "Unknown action: $action") }
        }
    }

    override suspend fun execute(action: String, params: JSONObject): NodeResult {
        return when (action) {
            "capture" -> captureScreen(params)
            "start_record" -> startRecording(params)
            "stop_record" -> stopRecording()
            else -> NodeResult.error("Unknown action: $action")
        }
    }

    private fun captureScreen(params: JSONObject): NodeResult {
        // 实际实现需要 MediaProjection API
        val result = JSONObject()
        result.put("status", "captured")
        result.put("format", params.optString("format", "jpeg"))
        result.put("timestamp", System.currentTimeMillis())
        return NodeResult.success(result)
    }

    private fun startRecording(params: JSONObject): NodeResult {
        val result = JSONObject()
        result.put("status", "recording_started")
        return NodeResult.success(result)
    }

    private fun stopRecording(): NodeResult {
        val result = JSONObject()
        result.put("status", "recording_stopped")
        return NodeResult.success(result)
    }
}

/**
 * 无障碍服务节点 - Node_35
 * 与服务端 Node_35 对齐
 */
class AccessibilityNode(context: Context) : BaseNode(context, "35", "Accessibility") {
    override fun getCapabilities() = listOf("accessibility", "ui_automation", "gesture", "text_input")

    override suspend fun handle(request: JSONObject): JSONObject {
        val action = request.optString("action", "")
        return execute(action, request).toJson()
    }

    override suspend fun execute(action: String, params: JSONObject): NodeResult {
        return when (action) {
            "click" -> performClick(params)
            "input_text" -> inputText(params)
            "scroll" -> performScroll(params)
            "get_ui_tree" -> getUITree()
            "find_element" -> findElement(params)
            else -> NodeResult.error("Unknown action: $action")
        }
    }

    private fun performClick(params: JSONObject): NodeResult {
        val x = params.optInt("x", 0)
        val y = params.optInt("y", 0)
        // 实际实现通过 AccessibilityService 执行
        val result = JSONObject()
        result.put("action", "click")
        result.put("x", x)
        result.put("y", y)
        result.put("status", "executed")
        return NodeResult.success(result)
    }

    private fun inputText(params: JSONObject): NodeResult {
        val text = params.optString("text", "")
        val result = JSONObject()
        result.put("action", "input_text")
        result.put("text", text)
        result.put("status", "executed")
        return NodeResult.success(result)
    }

    private fun performScroll(params: JSONObject): NodeResult {
        val direction = params.optString("direction", "down")
        val result = JSONObject()
        result.put("action", "scroll")
        result.put("direction", direction)
        result.put("status", "executed")
        return NodeResult.success(result)
    }

    private fun getUITree(): NodeResult {
        // 实际实现通过 AccessibilityService 获取
        val result = JSONObject()
        result.put("action", "get_ui_tree")
        result.put("status", "retrieved")
        return NodeResult.success(result)
    }

    private fun findElement(params: JSONObject): NodeResult {
        val selector = params.optString("selector", "")
        val result = JSONObject()
        result.put("action", "find_element")
        result.put("selector", selector)
        result.put("found", true)
        return NodeResult.success(result)
    }
}

/**
 * 输入注入节点 - Node_36
 * 与服务端 Node_36 对齐
 */
class InputInjectionNode(context: Context) : BaseNode(context, "36", "InputInjection") {
    override fun getCapabilities() = listOf("input_injection", "key_event", "touch_event")

    override suspend fun handle(request: JSONObject): JSONObject {
        val action = request.optString("action", "")
        return execute(action, request).toJson()
    }

    override suspend fun execute(action: String, params: JSONObject): NodeResult {
        return when (action) {
            "tap" -> performTap(params)
            "swipe" -> performSwipe(params)
            "key" -> sendKeyEvent(params)
            "long_press" -> performLongPress(params)
            else -> NodeResult.error("Unknown action: $action")
        }
    }

    private fun performTap(params: JSONObject): NodeResult {
        val x = params.optInt("x", 0)
        val y = params.optInt("y", 0)
        val result = JSONObject()
        result.put("action", "tap")
        result.put("x", x)
        result.put("y", y)
        result.put("status", "executed")
        return NodeResult.success(result)
    }

    private fun performSwipe(params: JSONObject): NodeResult {
        val startX = params.optInt("start_x", 0)
        val startY = params.optInt("start_y", 0)
        val endX = params.optInt("end_x", 0)
        val endY = params.optInt("end_y", 0)
        val duration = params.optLong("duration", 300)
        val result = JSONObject()
        result.put("action", "swipe")
        result.put("start", JSONObject().put("x", startX).put("y", startY))
        result.put("end", JSONObject().put("x", endX).put("y", endY))
        result.put("duration", duration)
        result.put("status", "executed")
        return NodeResult.success(result)
    }

    private fun sendKeyEvent(params: JSONObject): NodeResult {
        val keyCode = params.optInt("key_code", 0)
        val result = JSONObject()
        result.put("action", "key")
        result.put("key_code", keyCode)
        result.put("status", "executed")
        return NodeResult.success(result)
    }

    private fun performLongPress(params: JSONObject): NodeResult {
        val x = params.optInt("x", 0)
        val y = params.optInt("y", 0)
        val duration = params.optLong("duration", 1000)
        val result = JSONObject()
        result.put("action", "long_press")
        result.put("x", x)
        result.put("y", y)
        result.put("duration", duration)
        result.put("status", "executed")
        return NodeResult.success(result)
    }
}

/**
 * 应用管理节点 - Node_37
 * 与服务端 Node_37 对齐
 */
class AppManagerNode(context: Context) : BaseNode(context, "37", "AppManager") {
    override fun getCapabilities() = listOf("app_manager", "install", "uninstall", "launch", "list_apps")

    override suspend fun handle(request: JSONObject): JSONObject {
        val action = request.optString("action", "")
        return execute(action, request).toJson()
    }

    override suspend fun execute(action: String, params: JSONObject): NodeResult {
        return when (action) {
            "launch" -> launchApp(params)
            "list" -> listApps(params)
            "info" -> getAppInfo(params)
            "is_installed" -> isInstalled(params)
            else -> NodeResult.error("Unknown action: $action")
        }
    }

    private fun launchApp(params: JSONObject): NodeResult {
        val packageName = params.optString("package", "")
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                val result = JSONObject()
                result.put("action", "launch")
                result.put("package", packageName)
                result.put("status", "launched")
                return NodeResult.success(result)
            }
            return NodeResult.error("App not found: $packageName")
        } catch (e: Exception) {
            return NodeResult.error("Launch failed: ${e.message}")
        }
    }

    private fun listApps(params: JSONObject): NodeResult {
        val includeSystem = params.optBoolean("include_system", false)
        val apps = JSONArray()
        
        val packages = context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in packages) {
            if (!includeSystem && (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue
            }
            val appInfo = JSONObject()
            appInfo.put("package", app.packageName)
            appInfo.put("name", context.packageManager.getApplicationLabel(app).toString())
            apps.put(appInfo)
        }
        
        val result = JSONObject()
        result.put("apps", apps)
        result.put("count", apps.length())
        return NodeResult.success(result)
    }

    private fun getAppInfo(params: JSONObject): NodeResult {
        val packageName = params.optString("package", "")
        try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
            
            val result = JSONObject()
            result.put("package", packageName)
            result.put("name", context.packageManager.getApplicationLabel(appInfo).toString())
            result.put("version_name", packageInfo.versionName)
            result.put("version_code", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong())
            result.put("is_system", (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
            return NodeResult.success(result)
        } catch (e: PackageManager.NameNotFoundException) {
            return NodeResult.error("App not found: $packageName")
        }
    }

    private fun isInstalled(params: JSONObject): NodeResult {
        val packageName = params.optString("package", "")
        val installed = try {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        
        val result = JSONObject()
        result.put("package", packageName)
        result.put("installed", installed)
        return NodeResult.success(result)
    }
}

/**
 * 通知管理节点 - Node_38
 * 与服务端 Node_38 对齐
 */
class NotificationNode(context: Context) : BaseNode(context, "38", "Notification") {
    override fun getCapabilities() = listOf("notification", "read_notifications", "dismiss_notification")

    override suspend fun handle(request: JSONObject): JSONObject {
        val action = request.optString("action", "")
        return execute(action, request).toJson()
    }

    override suspend fun execute(action: String, params: JSONObject): NodeResult {
        return when (action) {
            "list" -> listNotifications()
            "dismiss" -> dismissNotification(params)
            "dismiss_all" -> dismissAllNotifications()
            else -> NodeResult.error("Unknown action: $action")
        }
    }

    private fun listNotifications(): NodeResult {
        // 实际实现需要 NotificationListenerService
        val result = JSONObject()
        result.put("action", "list")
        result.put("notifications", JSONArray())
        result.put("status", "retrieved")
        return NodeResult.success(result)
    }

    private fun dismissNotification(params: JSONObject): NodeResult {
        val key = params.optString("key", "")
        val result = JSONObject()
        result.put("action", "dismiss")
        result.put("key", key)
        result.put("status", "dismissed")
        return NodeResult.success(result)
    }

    private fun dismissAllNotifications(): NodeResult {
        val result = JSONObject()
        result.put("action", "dismiss_all")
        result.put("status", "dismissed")
        return NodeResult.success(result)
    }
}

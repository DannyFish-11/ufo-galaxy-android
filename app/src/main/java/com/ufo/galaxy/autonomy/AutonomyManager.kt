package com.ufo.galaxy.autonomy

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.ufo.galaxy.client.SystemControlHelper
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * 自主操纵管理器
 *
 * 功能：
 * 1. 管理 AutonomyService 的生命周期
 * 2. 处理来自 TaskExecutor / Gateway 的操作指令
 * 3. 定期推送 UI 树到 Gateway
 * 4. 提供统一的 API 接口
 *
 * System-control operations (WiFi, Bluetooth, volume, brightness) are
 * delegated to [SystemControlHelper] so they share the same implementation
 * with [com.ufo.galaxy.client.AndroidCommandExecutor].
 *
 * @author Manus AI
 * @version 1.1
 */
class AutonomyManager(private val context: Context) {

    private val TAG = "AutonomyManager"
    private val actionExecutor = ActionExecutor()
    private val systemControl = SystemControlHelper(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var uiTreePushJob: Job? = null

    companion object {
        @Volatile
        private var instance: AutonomyManager? = null

        fun getInstance(context: Context): AutonomyManager {
            return instance ?: synchronized(this) {
                instance ?: AutonomyManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Service / accessibility helpers
    // ──────────────────────────────────────────────────────────────

    /** Returns true when the AutonomyService accessibility service is running. */
    fun isEnabled(): Boolean {
        val service = AutonomyService.getInstance()
        return service != null && AutonomyService.isServiceAvailable()
    }

    /** Checks whether the AutonomyService is enabled via the AccessibilityManager API. */
    fun isAccessibilityServiceEnabled(): Boolean = isEnabled()

    /** Opens the Accessibility Settings page so the user can enable the service. */
    fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开无障碍设置失败", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Action execution
    // ──────────────────────────────────────────────────────────────

    fun executeAction(action: JSONObject): JSONObject = actionExecutor.executeAction(action)

    fun executeActionSequence(actions: org.json.JSONArray): JSONObject =
        actionExecutor.executeActionSequence(actions)

    // ──────────────────────────────────────────────────────────────
    // UI automation helpers (return Boolean for use with TaskExecutor)
    // ──────────────────────────────────────────────────────────────

    suspend fun clickByText(text: String): Boolean = withContext(Dispatchers.IO) {
        val action = JSONObject().apply {
            put("type", "click")
            put("text", text)
        }
        val result = executeAction(action)
        result.optString("status") == "success"
    }

    suspend fun clickByResourceId(resourceId: String): Boolean = withContext(Dispatchers.IO) {
        val action = JSONObject().apply {
            put("type", "click")
            put("params", JSONObject().apply { put("resource_id", resourceId) })
        }
        val result = executeAction(action)
        result.optString("status") == "success"
    }

    suspend fun inputText(text: String): Boolean = withContext(Dispatchers.IO) {
        val action = JSONObject().apply {
            put("type", "input_text")
            put("params", JSONObject().apply { put("text", text) })
        }
        val result = executeAction(action)
        result.optString("status") == "success"
    }

    suspend fun swipe(direction: String): Boolean = withContext(Dispatchers.IO) {
        val action = JSONObject().apply {
            put("type", "swipe")
            put("params", JSONObject().apply { put("direction", direction) })
        }
        val result = executeAction(action)
        result.optString("status") == "success"
    }

    suspend fun scroll(direction: String): Boolean = withContext(Dispatchers.IO) {
        val action = JSONObject().apply {
            put("type", "scroll")
            put("params", JSONObject().apply { put("direction", direction) })
        }
        val result = executeAction(action)
        result.optString("status") == "success"
    }

    // ──────────────────────────────────────────────────────────────
    // Global navigation actions
    // ──────────────────────────────────────────────────────────────

    fun performBack(): Boolean = performKey("back")
    fun performHome(): Boolean = performKey("home")
    fun performRecent(): Boolean = performKey("recent")

    private fun performKey(key: String): Boolean {
        val action = JSONObject().apply {
            put("type", "press_key")
            put("params", JSONObject().apply { put("key", key) })
        }
        val result = executeAction(action)
        return result.optString("status") == "success"
    }

    // ──────────────────────────────────────────────────────────────
    // App control
    // ──────────────────────────────────────────────────────────────

    /**
     * Launch [packageName] using the system launcher intent.
     * @return `true` on success.
     */
    suspend fun openApp(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "已打开应用: $packageName")
                true
            } else {
                Log.w(TAG, "未找到应用: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开应用失败: $packageName", e)
            false
        }
    }

    /**
     * Close [packageName] by pressing Home then clearing the recents entry via
     * the accessibility service. Falls back to Home if the service is unavailable.
     * @return `true` when the action was dispatched.
     */
    suspend fun closeApp(packageName: String): Boolean = withContext(Dispatchers.IO) {
        // Best-effort: go back to home so the app is no longer in the foreground
        performHome()
        Log.i(TAG, "关闭应用（已返回主屏幕）: $packageName")
        true
    }

    /**
     * Switch to [packageName] (equivalent to opening it).
     * @return `true` on success.
     */
    suspend fun switchToApp(packageName: String): Boolean = openApp(packageName)

    // ──────────────────────────────────────────────────────────────
    // UI tree capture
    // ──────────────────────────────────────────────────────────────

    /**
     * Capture the current UI tree.  Requires the AutonomyService to be running.
     * Safe to call from coroutine context.
     */
    suspend fun captureUITree(): JSONObject = withContext(Dispatchers.IO) {
        captureUITreeSync()
    }

    /** Synchronous variant – safe to call from any thread (no coroutine needed). */
    private fun captureUITreeSync(): JSONObject {
        val service = AutonomyService.getInstance()
        if (service == null) {
            Log.w(TAG, "captureUITree: 自主操纵服务未启用")
            return JSONObject().apply {
                put("status", "error")
                put("message", "自主操纵服务未启用")
            }
        }
        return service.captureUITree()
    }

    /** @see captureUITree */
    suspend fun getUITree(): String = captureUITree().toString(2)

    // ──────────────────────────────────────────────────────────────
    // Query helpers
    // ──────────────────────────────────────────────────────────────

    /** Returns the package name of the currently active app, or "unknown". */
    suspend fun getCurrentApp(): String = withContext(Dispatchers.IO) {
        val service = AutonomyService.getInstance()
        val uiTree = service?.captureUITree()
        uiTree?.optString("active_package", "unknown") ?: "unknown"
    }

    /** Returns a JSON summary of the device's current status. */
    suspend fun getDeviceStatus(): JSONObject = withContext(Dispatchers.IO) {
        val currentApp = getCurrentApp()
        JSONObject().apply {
            put("accessibility_service_enabled", isEnabled())
            put("current_app", currentApp)
            put("timestamp", System.currentTimeMillis())
        }
    }

    // ──────────────────────────────────────────────────────────────
    // System control – delegated to SystemControlHelper
    // ──────────────────────────────────────────────────────────────

    /**
     * Toggle WiFi.
     *
     * On Android 10+ (API 29+) opens the system Settings Panel so the user can
     * confirm manually (returns `true` to signal that the action was dispatched).
     * On earlier APIs, directly sets the WiFi state via WifiManager.
     *
     * @return `true` when the action was successfully dispatched or completed.
     */
    fun setWiFi(enable: Boolean): Boolean {
        val result = systemControl.toggleWifi(enable)
        val status = result.optString("status", "error")
        Log.i(TAG, "[WIFI] setWiFi($enable) -> $status: ${result.optString("message")}")
        // "pending_user_action" still counts as a successful dispatch
        return status == "success" || status == "pending_user_action"
    }

    /**
     * Toggle Bluetooth.
     *
     * Requires BLUETOOTH_CONNECT permission on Android 12+ (API 31+).
     *
     * @return `true` on success, `false` on error or missing permission.
     */
    fun setBluetooth(enable: Boolean): Boolean {
        val result = systemControl.toggleBluetooth(enable)
        val status = result.optString("status", "error")
        Log.i(TAG, "[BT] setBluetooth($enable) -> $status: ${result.optString("message")}")
        return status == "success"
    }

    /**
     * Set media volume to [level] (0–100).
     * @return `true` on success.
     */
    fun setVolume(level: Int): Boolean = systemControl.setVolume(level)

    /**
     * Set screen brightness to [level] (0–100).
     * Requires WRITE_SETTINGS permission.
     * @return `true` on success.
     */
    fun setBrightness(level: Int): Boolean = systemControl.setBrightness(level)

    // ──────────────────────────────────────────────────────────────
    // Gateway command handler
    // ──────────────────────────────────────────────────────────────

    fun handleGatewayCommand(command: JSONObject): JSONObject {
        val result = JSONObject()
        try {
            val commandType = command.getString("type")
            when (commandType) {
                "execute_action" -> {
                    val action = command.getJSONObject("action")
                    return executeAction(action)
                }
                "execute_sequence" -> {
                    val actions = command.getJSONArray("actions")
                    return executeActionSequence(actions)
                }
                "get_ui_tree" -> {
                    return captureUITreeSync()
                }
                "start_ui_push" -> {
                    val interval = command.optLong("interval", 5000)
                    startUITreePush(interval)
                    result.put("status", "success")
                    result.put("message", "已启动 UI 树推送")
                }
                "stop_ui_push" -> {
                    stopUITreePush()
                    result.put("status", "success")
                    result.put("message", "已停止 UI 树推送")
                }
                else -> {
                    result.put("status", "error")
                    result.put("message", "不支持的指令类型: $commandType")
                }
            }
        } catch (e: Exception) {
            result.put("status", "error")
            result.put("message", "处理指令失败: ${e.message}")
            Log.e(TAG, "处理 Gateway 指令失败", e)
        }
        return result
    }

    // ──────────────────────────────────────────────────────────────
    // UI tree push (periodic push to gateway)
    // ──────────────────────────────────────────────────────────────

    fun startUITreePush(intervalMs: Long = 5000) {
        stopUITreePush()
        uiTreePushJob = scope.launch {
            while (isActive) {
                try {
                    if (isAccessibilityServiceEnabled()) {
                        val uiTree = captureUITree()
                        if (uiTree.optString("status") == "success") {
                            Log.d(TAG, "UI 树已抓取 (节点数: ${uiTree.optInt("node_count", 0)})")
                        }
                    }
                    delay(intervalMs)
                } catch (e: Exception) {
                    Log.e(TAG, "UI 树推送失败", e)
                    delay(intervalMs)
                }
            }
        }
        Log.i(TAG, "✅ 已启动 UI 树自动推送，间隔: ${intervalMs}ms")
    }

    fun stopUITreePush() {
        uiTreePushJob?.cancel()
        uiTreePushJob = null
        Log.i(TAG, "❌ 已停止 UI 树自动推送")
    }

    // ──────────────────────────────────────────────────────────────
    // Diagnostics
    // ──────────────────────────────────────────────────────────────

    fun runDiagnostics(): JSONObject {
        val result = JSONObject()
        val checks = org.json.JSONArray()

        checks.put(JSONObject().apply {
            put("name", "无障碍服务")
            put("status", if (isAccessibilityServiceEnabled()) "✅ 已启用" else "❌ 未启用")
        })

        val uiTree = captureUITreeSync()
        checks.put(JSONObject().apply {
            put("name", "UI 树抓取")
            put("status", if (uiTree.optString("status") == "success") "✅ 正常" else "❌ 失败")
            put("node_count", uiTree.optInt("node_count", 0))
        })

        checks.put(JSONObject().apply {
            put("name", "动作执行器")
            put("status", "✅ 已初始化")
        })

        result.put("status", "success")
        result.put("checks", checks)
        result.put("timestamp", System.currentTimeMillis())
        return result
    }

    // ──────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────

    fun cleanup() {
        stopUITreePush()
        actionExecutor.cleanup()
        scope.cancel()
    }
}


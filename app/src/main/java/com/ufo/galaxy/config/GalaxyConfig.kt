package com.ufo.galaxy.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * UFO Galaxy 配置管理器
 * ====================
 * 
 * 统一管理所有配置项：
 * 1. 服务器连接配置
 * 2. 设备信息配置
 * 3. 功能开关配置
 * 4. UI 偏好配置
 * 
 * 作者：Manus AI
 * 日期：2026-02-06
 */
class GalaxyConfig private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GalaxyConfig"
        private const val PREFS_NAME = "ufo_galaxy_config"
        
        // 服务器配置键
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_API_PORT = "device_api_port"
        private const val KEY_WS_RECONNECT_INTERVAL = "ws_reconnect_interval"
        private const val KEY_STATUS_SYNC_INTERVAL = "status_sync_interval"
        
        // 设备配置键
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ALIAS = "device_alias"
        
        // 功能开关键
        private const val KEY_ENABLE_CAMERA = "enable_camera"
        private const val KEY_ENABLE_MICROPHONE = "enable_microphone"
        private const val KEY_ENABLE_BLUETOOTH = "enable_bluetooth"
        private const val KEY_ENABLE_NFC = "enable_nfc"
        private const val KEY_ENABLE_ACCESSIBILITY = "enable_accessibility"
        private const val KEY_ENABLE_FLOATING_WINDOW = "enable_floating_window"
        private const val KEY_ENABLE_AUTO_START = "enable_auto_start"
        
        // AI/OCR 配置键
        private const val KEY_DEEPSEEK_OCR2_API_KEY = "deepseek_ocr2_api_key"
        private const val KEY_DEEPSEEK_OCR2_API_BASE = "deepseek_ocr2_api_base"
        private const val KEY_DEEPSEEK_OCR2_MODEL = "deepseek_ocr2_model"
        private const val KEY_OCR_ENGINE = "ocr_engine"
        
        // DeepSeek OCR 2 默认值
        private const val DEFAULT_DEEPSEEK_OCR2_API_BASE = "https://api.novita.ai/v3/openai"
        private const val DEFAULT_DEEPSEEK_OCR2_MODEL = "deepseek/deepseek-ocr2"
        private const val DEFAULT_OCR_ENGINE = "deepseek_ocr2"
        
        // UI 配置键
        private const val KEY_UI_THEME = "ui_theme"
        private const val KEY_UI_DYNAMIC_ISLAND = "ui_dynamic_island"
        private const val KEY_UI_STATUS_PANEL = "ui_status_panel"
        
        // 默认值
        private const val DEFAULT_SERVER_URL = ""
        private const val DEFAULT_DEVICE_API_PORT = 8766
        private const val DEFAULT_WS_RECONNECT_INTERVAL = 5000L
        private const val DEFAULT_STATUS_SYNC_INTERVAL = 10000L
        
        @Volatile
        private var instance: GalaxyConfig? = null
        
        fun getInstance(context: Context): GalaxyConfig {
            return instance ?: synchronized(this) {
                instance ?: GalaxyConfig(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    
    // ========================================================================
    // 服务器配置
    // ========================================================================
    
    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }
    
    fun setServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
        Log.d(TAG, "Server URL set to: $url")
    }
    
    fun getDeviceApiPort(): Int {
        return prefs.getInt(KEY_DEVICE_API_PORT, DEFAULT_DEVICE_API_PORT)
    }
    
    fun setDeviceApiPort(port: Int) {
        prefs.edit().putInt(KEY_DEVICE_API_PORT, port).apply()
    }
    
    fun getWsReconnectInterval(): Long {
        return prefs.getLong(KEY_WS_RECONNECT_INTERVAL, DEFAULT_WS_RECONNECT_INTERVAL)
    }
    
    fun getStatusSyncInterval(): Long {
        return prefs.getLong(KEY_STATUS_SYNC_INTERVAL, DEFAULT_STATUS_SYNC_INTERVAL)
    }
    
    // ========================================================================
    // 设备配置
    // ========================================================================
    
    fun getDeviceId(): String {
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = generateDeviceId()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
            Log.d(TAG, "Generated new device ID: $deviceId")
        }
        return deviceId
    }
    
    private fun generateDeviceId(): String {
        val manufacturer = Build.MANUFACTURER.lowercase().take(4)
        val model = Build.MODEL.replace(" ", "").lowercase().take(6)
        val uuid = UUID.randomUUID().toString().take(8)
        return "android_${manufacturer}_${model}_$uuid"
    }
    
    fun getDeviceName(): String {
        return prefs.getString(KEY_DEVICE_NAME, null) 
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }
    
    fun setDeviceName(name: String) {
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
    }
    
    fun getDeviceAlias(): String {
        return prefs.getString(KEY_DEVICE_ALIAS, null) ?: getDeviceName()
    }
    
    fun setDeviceAlias(alias: String) {
        prefs.edit().putString(KEY_DEVICE_ALIAS, alias).apply()
    }
    
    // ========================================================================
    // 功能开关
    // ========================================================================
    
    fun isCameraEnabled(): Boolean = prefs.getBoolean(KEY_ENABLE_CAMERA, true)
    fun setCameraEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLE_CAMERA, enabled).apply()
    
    fun isMicrophoneEnabled(): Boolean = prefs.getBoolean(KEY_ENABLE_MICROPHONE, true)
    fun setMicrophoneEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLE_MICROPHONE, enabled).apply()
    
    fun isBluetoothEnabled(): Boolean = prefs.getBoolean(KEY_ENABLE_BLUETOOTH, true)
    fun setBluetoothEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLE_BLUETOOTH, enabled).apply()
    
    fun isNfcEnabled(): Boolean = prefs.getBoolean(KEY_ENABLE_NFC, true)
    fun setNfcEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLE_NFC, enabled).apply()
    
    fun isAccessibilityEnabled(): Boolean = prefs.getBoolean(KEY_ENABLE_ACCESSIBILITY, false)
    fun setAccessibilityEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLE_ACCESSIBILITY, enabled).apply()
    
    fun isFloatingWindowEnabled(): Boolean = prefs.getBoolean(KEY_ENABLE_FLOATING_WINDOW, true)
    fun setFloatingWindowEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLE_FLOATING_WINDOW, enabled).apply()
    
    fun isAutoStartEnabled(): Boolean = prefs.getBoolean(KEY_ENABLE_AUTO_START, false)
    fun setAutoStartEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLE_AUTO_START, enabled).apply()
    
    // ========================================================================
    // AI/OCR 配置
    // ========================================================================
    
    fun getDeepSeekOCR2ApiKey(): String {
        return prefs.getString(KEY_DEEPSEEK_OCR2_API_KEY, "") ?: ""
    }
    
    fun setDeepSeekOCR2ApiKey(key: String) {
        prefs.edit().putString(KEY_DEEPSEEK_OCR2_API_KEY, key).apply()
        Log.d(TAG, "DeepSeek OCR 2 API Key configured")
    }
    
    fun getDeepSeekOCR2ApiBase(): String {
        return prefs.getString(KEY_DEEPSEEK_OCR2_API_BASE, DEFAULT_DEEPSEEK_OCR2_API_BASE)
            ?: DEFAULT_DEEPSEEK_OCR2_API_BASE
    }
    
    fun setDeepSeekOCR2ApiBase(base: String) {
        prefs.edit().putString(KEY_DEEPSEEK_OCR2_API_BASE, base).apply()
    }
    
    fun getDeepSeekOCR2Model(): String {
        return prefs.getString(KEY_DEEPSEEK_OCR2_MODEL, DEFAULT_DEEPSEEK_OCR2_MODEL)
            ?: DEFAULT_DEEPSEEK_OCR2_MODEL
    }
    
    fun setDeepSeekOCR2Model(model: String) {
        prefs.edit().putString(KEY_DEEPSEEK_OCR2_MODEL, model).apply()
    }
    
    fun getOCREngine(): String {
        return prefs.getString(KEY_OCR_ENGINE, DEFAULT_OCR_ENGINE) ?: DEFAULT_OCR_ENGINE
    }
    
    fun setOCREngine(engine: String) {
        prefs.edit().putString(KEY_OCR_ENGINE, engine).apply()
        Log.d(TAG, "OCR engine set to: $engine")
    }
    
    fun isDeepSeekOCR2Configured(): Boolean {
        return getDeepSeekOCR2ApiKey().isNotEmpty()
    }
    
    // ========================================================================
    // UI 配置
    // ========================================================================
    
    enum class Theme {
        DARK,       // 深色主题（默认）
        LIGHT,      // 浅色主题
        SYSTEM      // 跟随系统
    }
    
    fun getTheme(): Theme {
        val value = prefs.getString(KEY_UI_THEME, Theme.DARK.name)
        return try {
            Theme.valueOf(value ?: Theme.DARK.name)
        } catch (e: Exception) {
            Theme.DARK
        }
    }
    
    fun setTheme(theme: Theme) {
        prefs.edit().putString(KEY_UI_THEME, theme.name).apply()
    }
    
    fun isDynamicIslandEnabled(): Boolean = prefs.getBoolean(KEY_UI_DYNAMIC_ISLAND, true)
    fun setDynamicIslandEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_UI_DYNAMIC_ISLAND, enabled).apply()
    
    fun isStatusPanelEnabled(): Boolean = prefs.getBoolean(KEY_UI_STATUS_PANEL, true)
    fun setStatusPanelEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_UI_STATUS_PANEL, enabled).apply()
    
    // ========================================================================
    // 配置导出/导入
    // ========================================================================
    
    /**
     * 导出所有配置为 JSON
     */
    fun exportConfig(): JSONObject {
        return JSONObject().apply {
            // 服务器配置
            put("server", JSONObject().apply {
                put("url", getServerUrl())
                put("device_api_port", getDeviceApiPort())
                put("ws_reconnect_interval", getWsReconnectInterval())
                put("status_sync_interval", getStatusSyncInterval())
            })
            
            // 设备配置
            put("device", JSONObject().apply {
                put("id", getDeviceId())
                put("name", getDeviceName())
                put("alias", getDeviceAlias())
            })
            
            // 功能开关
            put("features", JSONObject().apply {
                put("camera", isCameraEnabled())
                put("microphone", isMicrophoneEnabled())
                put("bluetooth", isBluetoothEnabled())
                put("nfc", isNfcEnabled())
                put("accessibility", isAccessibilityEnabled())
                put("floating_window", isFloatingWindowEnabled())
                put("auto_start", isAutoStartEnabled())
            })
            
            // AI/OCR 配置
            put("ai", JSONObject().apply {
                put("deepseek_ocr2_api_key", getDeepSeekOCR2ApiKey())
                put("deepseek_ocr2_api_base", getDeepSeekOCR2ApiBase())
                put("deepseek_ocr2_model", getDeepSeekOCR2Model())
                put("ocr_engine", getOCREngine())
            })
            
            // UI 配置
            put("ui", JSONObject().apply {
                put("theme", getTheme().name)
                put("dynamic_island", isDynamicIslandEnabled())
                put("status_panel", isStatusPanelEnabled())
            })
        }
    }
    
    /**
     * 从 JSON 导入配置
     */
    fun importConfig(json: JSONObject) {
        try {
            // 服务器配置
            json.optJSONObject("server")?.let { server ->
                server.optString("url", null)?.let { setServerUrl(it) }
                if (server.has("device_api_port")) {
                    setDeviceApiPort(server.getInt("device_api_port"))
                }
            }
            
            // 设备配置
            json.optJSONObject("device")?.let { device ->
                device.optString("name", null)?.let { setDeviceName(it) }
                device.optString("alias", null)?.let { setDeviceAlias(it) }
            }
            
            // 功能开关
            json.optJSONObject("features")?.let { features ->
                if (features.has("camera")) setCameraEnabled(features.getBoolean("camera"))
                if (features.has("microphone")) setMicrophoneEnabled(features.getBoolean("microphone"))
                if (features.has("bluetooth")) setBluetoothEnabled(features.getBoolean("bluetooth"))
                if (features.has("nfc")) setNfcEnabled(features.getBoolean("nfc"))
                if (features.has("accessibility")) setAccessibilityEnabled(features.getBoolean("accessibility"))
                if (features.has("floating_window")) setFloatingWindowEnabled(features.getBoolean("floating_window"))
                if (features.has("auto_start")) setAutoStartEnabled(features.getBoolean("auto_start"))
            }
            
            // AI/OCR 配置
            json.optJSONObject("ai")?.let { ai ->
                ai.optString("deepseek_ocr2_api_key", null)?.let { setDeepSeekOCR2ApiKey(it) }
                ai.optString("deepseek_ocr2_api_base", null)?.let { setDeepSeekOCR2ApiBase(it) }
                ai.optString("deepseek_ocr2_model", null)?.let { setDeepSeekOCR2Model(it) }
                ai.optString("ocr_engine", null)?.let { setOCREngine(it) }
            }
            
            // UI 配置
            json.optJSONObject("ui")?.let { ui ->
                ui.optString("theme", null)?.let { 
                    try { setTheme(Theme.valueOf(it)) } catch (e: Exception) {}
                }
                if (ui.has("dynamic_island")) setDynamicIslandEnabled(ui.getBoolean("dynamic_island"))
                if (ui.has("status_panel")) setStatusPanelEnabled(ui.getBoolean("status_panel"))
            }
            
            Log.i(TAG, "Config imported successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import config", e)
        }
    }
    
    /**
     * 重置为默认配置
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Config reset to defaults")
    }
}

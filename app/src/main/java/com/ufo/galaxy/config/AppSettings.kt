package com.ufo.galaxy.config

import android.content.Context
import android.content.SharedPreferences

/**
 * 运行时应用配置（SharedPreferences）
 *
 * 作为全局唯一的运行时配置入口，管理所有可在运行时修改的配置项。
 * 首次读取时优先从 SharedPreferences 读取，若未设置则回退到
 * assets/config.properties 中的值（通过 [AppConfig]）。
 *
 * 使用示例：
 * ```kotlin
 * val settings = AppSettings(context)
 * settings.crossDeviceEnabled = true
 * val url = settings.galaxyGatewayUrl
 * ```
 *
 * @author UFO³ Galaxy
 * @version 1.0
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // 确保 AppConfig 已从 assets 加载，供默认值回退使用
        AppConfig.loadConfig(context)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 跨设备开关
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 是否启用跨设备模式。
     *
     * true  → 消息通过 WebSocket 发送至 Galaxy Gateway（TaskSubmitPayload）。
     * false → 消息走本地执行链路。
     */
    var crossDeviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_CROSS_DEVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_CROSS_DEVICE_ENABLED, value).apply()

    // ──────────────────────────────────────────────────────────────────────────
    // Galaxy Gateway WebSocket 地址
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Galaxy Gateway 的 WebSocket 地址，例如 `ws://100.x.x.x:8000`。
     *
     * 默认值来源（优先级从高到低）：
     * 1. SharedPreferences（用户在运行时设置的值）
     * 2. assets/config.properties → galaxy.gateway.url
     * 3. [ServerConfig.DEFAULT_BASE_URL]
     */
    var galaxyGatewayUrl: String
        get() {
            val saved = prefs.getString(KEY_GATEWAY_URL, null)
            if (!saved.isNullOrBlank()) return saved
            val fromConfig = AppConfig.getString("galaxy.gateway.url", "")
            if (fromConfig.isNotBlank()) return fromConfig
            return ServerConfig.DEFAULT_BASE_URL
        }
        set(value) = prefs.edit().putString(KEY_GATEWAY_URL, value).apply()

    // ──────────────────────────────────────────────────────────────────────────
    // REST API 基础地址
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * REST API 基础地址（HTTP），例如 `http://100.x.x.x:8000`。
     *
     * 默认值来源（优先级从高到低）：
     * 1. SharedPreferences（用户在运行时设置的值）
     * 2. assets/config.properties → rest.base.url
     * 3. 由 [galaxyGatewayUrl] 转换而来（ws→http）
     */
    var restBaseUrl: String
        get() {
            val saved = prefs.getString(KEY_REST_BASE_URL, null)
            if (!saved.isNullOrBlank()) return saved
            val fromConfig = AppConfig.getString("rest.base.url", "")
            if (fromConfig.isNotBlank()) return fromConfig
            return ServerConfig.wsToHttpBase(galaxyGatewayUrl)
        }
        set(value) = prefs.edit().putString(KEY_REST_BASE_URL, value).apply()

    // ──────────────────────────────────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────────────────────────────────

    /** 将所有运行时配置重置为默认值（下次读取时从 config.properties 回退）。 */
    fun resetToDefaults() {
        prefs.edit()
            .remove(KEY_CROSS_DEVICE_ENABLED)
            .remove(KEY_GATEWAY_URL)
            .remove(KEY_REST_BASE_URL)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ufo_galaxy_settings"
        const val KEY_CROSS_DEVICE_ENABLED = "cross_device_enabled"
        const val KEY_GATEWAY_URL = "galaxy_gateway_url"
        const val KEY_REST_BASE_URL = "rest_base_url"
    }
}

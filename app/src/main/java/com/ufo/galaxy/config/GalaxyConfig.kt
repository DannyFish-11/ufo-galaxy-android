package com.ufo.galaxy.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 统一配置中心
 *
 * 管理 UFO Galaxy Android 的所有运行时配置：
 * - 服务器 URL（ServerConfig 常量 + 运行时覆盖）
 * - DeepSeek OCR 2 API 配置
 * - LLM Provider / OneAPI 配置
 * - 设备标识
 *
 * 优先级：SharedPreferences（运行时覆盖）> config.properties（编译时默认）
 */
class GalaxyConfig private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GalaxyConfig"
        private const val PREFS_NAME = "galaxy_config"

        // SharedPreferences keys
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEEPSEEK_OCR2_API_KEY = "deepseek_ocr2_api_key"
        private const val KEY_DEEPSEEK_OCR2_API_BASE = "deepseek_ocr2_api_base"
        private const val KEY_DEEPSEEK_OCR2_MODEL = "deepseek_ocr2_model"
        private const val KEY_OCR_ENGINE = "ocr_engine"
        private const val KEY_LLM_API_KEY = "llm_api_key"
        private const val KEY_LLM_BASE_URL = "llm_base_url"

        @Volatile
        private var instance: GalaxyConfig? = null

        fun getInstance(context: Context): GalaxyConfig {
            return instance ?: synchronized(this) {
                instance ?: GalaxyConfig(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Ensure AppConfig is loaded
        AppConfig.loadConfig(context)
    }

    // ── Server URL ──

    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, null)
            ?: AppConfig.getString("server_url", "")
    }

    fun getHttpBaseUrl(): String {
        val wsBase = getServerUrl()
        return if (wsBase.isNotEmpty()) {
            ServerConfig.wsToHttpBase(wsBase)
        } else {
            AppConfig.getString("http_base_url", "http://100.123.215.126:8888")
        }
    }

    // ── Device ID ──

    fun getDeviceId(): String {
        return prefs.getString(KEY_DEVICE_ID, null)
            ?: AppConfig.getString("device_id", "android_${android.os.Build.SERIAL}")
    }

    fun setDeviceId(deviceId: String) {
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    // ── DeepSeek OCR 2 ──

    fun getDeepSeekOCR2ApiKey(): String {
        return prefs.getString(KEY_DEEPSEEK_OCR2_API_KEY, null)
            ?: AppConfig.getString("deepseek_ocr2_api_key", "")
    }

    fun getDeepSeekOCR2ApiBase(): String {
        return prefs.getString(KEY_DEEPSEEK_OCR2_API_BASE, null)
            ?: AppConfig.getString("deepseek_ocr2_api_base", "https://api.novita.ai/v3/openai")
    }

    fun getDeepSeekOCR2Model(): String {
        return prefs.getString(KEY_DEEPSEEK_OCR2_MODEL, null)
            ?: AppConfig.getString("deepseek_ocr2_model", "deepseek/deepseek-ocr2")
    }

    // ── OCR Engine ──

    fun getOCREngine(): String {
        return prefs.getString(KEY_OCR_ENGINE, null)
            ?: AppConfig.getString("ocr_engine", "auto")
    }

    // ── LLM / OneAPI ──

    fun getLLMApiKey(): String {
        return prefs.getString(KEY_LLM_API_KEY, null)
            ?: AppConfig.getString("llm_api_key", "")
    }

    fun getLLMBaseUrl(): String {
        return prefs.getString(KEY_LLM_BASE_URL, null)
            ?: AppConfig.getString("llm_base_url", "")
    }

    // ── Generic configure ──

    fun configure(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        Log.d(TAG, "Config updated: $key")
    }

    fun getString(key: String, default: String = ""): String {
        return prefs.getString(key, null)
            ?: AppConfig.getString(key, default)
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        return if (prefs.contains(key)) {
            prefs.getBoolean(key, default)
        } else {
            AppConfig.getBoolean(key, default)
        }
    }

    fun getInt(key: String, default: Int = 0): Int {
        return if (prefs.contains(key)) {
            prefs.getInt(key, default)
        } else {
            AppConfig.getInt(key, default)
        }
    }
}

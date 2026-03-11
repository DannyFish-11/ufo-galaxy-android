package com.ufo.galaxy.config

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Galaxy 全局配置管理
 *
 * 使用 SharedPreferences 持久化运行时配置（如 Dashboard 设置的 API Key），
 * 同时从 config.properties（通过 AppConfig）读取默认值。
 */
class GalaxyConfig private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "galaxy_config"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEEPSEEK_OCR2_API_KEY = "deepseek_ocr2_api_key"
        private const val KEY_DEEPSEEK_OCR2_API_BASE = "deepseek_ocr2_api_base"
        private const val KEY_DEEPSEEK_OCR2_MODEL = "deepseek_ocr2_model"
        private const val KEY_OCR_ENGINE = "ocr_engine"

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

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDeviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrEmpty()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getServerUrl(): String =
        prefs.getString(KEY_SERVER_URL, null)
            ?: AppConfig.getString("galaxy.gateway.url", "")

    fun getDeepSeekOCR2ApiKey(): String =
        prefs.getString(KEY_DEEPSEEK_OCR2_API_KEY, null) ?: ""

    fun getDeepSeekOCR2ApiBase(): String =
        prefs.getString(KEY_DEEPSEEK_OCR2_API_BASE, null)
            ?: AppConfig.getString("deepseek.ocr2.api.base", "https://api.deepseek.com")

    fun getDeepSeekOCR2Model(): String =
        prefs.getString(KEY_DEEPSEEK_OCR2_MODEL, null)
            ?: AppConfig.getString("deepseek.ocr2.model", "deepseek-ocr2")

    fun getOCREngine(): String =
        prefs.getString(KEY_OCR_ENGINE, null)
            ?: AppConfig.getString("ocr.engine", "")

    // Setters for runtime updates (e.g., from dashboard)

    fun setDeepSeekOCR2ApiKey(key: String) {
        prefs.edit().putString(KEY_DEEPSEEK_OCR2_API_KEY, key).apply()
    }

    fun setDeepSeekOCR2ApiBase(base: String) {
        prefs.edit().putString(KEY_DEEPSEEK_OCR2_API_BASE, base).apply()
    }

    fun setDeepSeekOCR2Model(model: String) {
        prefs.edit().putString(KEY_DEEPSEEK_OCR2_MODEL, model).apply()
    }

    fun setServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun setOCREngine(engine: String) {
        prefs.edit().putString(KEY_OCR_ENGINE, engine).apply()
    }
}

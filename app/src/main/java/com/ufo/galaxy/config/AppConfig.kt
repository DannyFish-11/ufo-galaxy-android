package com.ufo.galaxy.config

import android.content.Context
import android.util.Log
import java.util.Properties

/**
 * 应用配置读取工具类
 *
 * 从 assets/config.properties 文件中读取配置
 *
 * @author Manus AI
 * @version 1.1
 * @date 2026-01-22
 */
object AppConfig {

    private const val TAG = "AppConfig"
    private val properties = Properties()
    private var isLoaded = false

    fun loadConfig(context: Context) {
        try {
            context.assets.open("config.properties").use { inputStream ->
                properties.load(inputStream)
            }
            isLoaded = true
            Log.i(TAG, "配置加载成功，共 ${properties.size} 个配置项")
        } catch (e: java.io.FileNotFoundException) {
            Log.w(TAG, "配置文件 config.properties 不存在，使用默认值")
        } catch (e: Exception) {
            Log.e(TAG, "加载配置文件失败", e)
        }
    }

    fun isLoaded(): Boolean = isLoaded

    fun getString(key: String, defaultValue: String = ""): String {
        return properties.getProperty(key, defaultValue)
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        val value = properties.getProperty(key) ?: return defaultValue
        return value.toIntOrNull() ?: run {
            Log.w(TAG, "配置项 '$key' 的值 '$value' 无法转换为 Int，使用默认值 $defaultValue")
            defaultValue
        }
    }

    fun getLong(key: String, defaultValue: Long = 0L): Long {
        val value = properties.getProperty(key) ?: return defaultValue
        return value.toLongOrNull() ?: run {
            Log.w(TAG, "配置项 '$key' 的值 '$value' 无法转换为 Long，使用默认值 $defaultValue")
            defaultValue
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        val value = properties.getProperty(key) ?: return defaultValue
        return value.toBoolean()
    }
}

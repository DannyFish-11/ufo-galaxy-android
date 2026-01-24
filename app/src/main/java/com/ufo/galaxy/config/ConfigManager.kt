package com.ufo.galaxy.config

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置管理器
 * 负责管理应用的配置信息
 */
class ConfigManager(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "ufo_galaxy_config",
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val KEY_GATEWAY_URL = "gateway_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        
        // 默认配置
        private const val DEFAULT_GATEWAY_URL = "ws://192.168.1.100:8000/ws"
        private const val DEFAULT_DEVICE_NAME = "Android Device"
    }
    
    /**
     * 获取 Gateway URL
     */
    fun getGatewayUrl(): String {
        return prefs.getString(KEY_GATEWAY_URL, DEFAULT_GATEWAY_URL) ?: DEFAULT_GATEWAY_URL
    }
    
    /**
     * 设置 Gateway URL
     */
    fun setGatewayUrl(url: String) {
        prefs.edit().putString(KEY_GATEWAY_URL, url).apply()
    }
    
    /**
     * 获取设备 ID
     */
    fun getDeviceId(): String {
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            // 生成新的设备 ID
            deviceId = "android_${System.currentTimeMillis()}"
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }
    
    /**
     * 获取设备名称
     */
    fun getDeviceName(): String {
        return prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME
    }
    
    /**
     * 设置设备名称
     */
    fun setDeviceName(name: String) {
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
    }
}

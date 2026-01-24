package com.ufo.galaxy.command

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.ufo.galaxy.config.ConfigManager
import org.json.JSONObject
import java.io.File

/**
 * 命令处理器
 * 负责处理从 Galaxy Gateway 接收到的命令
 */
class CommandHandler(private val context: Context) {
    
    private val configManager = ConfigManager(context)
    
    companion object {
        private const val TAG = "CommandHandler"
    }
    
    /**
     * 处理命令
     * @param commandId 命令 ID
     * @param commandType 命令类型
     * @param payload 命令数据
     * @return 处理结果
     */
    fun handleCommand(commandId: String, commandType: String, payload: JSONObject): JSONObject {
        Log.i(TAG, "Handling command: $commandId ($commandType)")
        
        val result = JSONObject()
        result.put("command_id", commandId)
        result.put("status", "success")
        
        try {
            when (commandType) {
                "ping" -> {
                    // Ping 命令
                    result.put("message", "pong")
                    result.put("timestamp", System.currentTimeMillis())
                }
                
                "get_status" -> {
                    // 获取设备状态
                    result.put("data", getDeviceStatus())
                }
                
                "set_config" -> {
                    // 设置配置
                    val key = payload.optString("key")
                    val value = payload.optString("value")
                    
                    when (key) {
                        "gateway_url" -> configManager.setGatewayUrl(value)
                        "device_name" -> configManager.setDeviceName(value)
                        else -> {
                            result.put("status", "error")
                            result.put("message", "Unknown config key: $key")
                        }
                    }
                    
                    if (result.optString("status") != "error") {
                        result.put("message", "Config set: $key = $value")
                    }
                }
                
                "restart_service" -> {
                    // 重启服务
                    result.put("message", "Service restart initiated")
                }
                
                "clear_cache" -> {
                    // 清除缓存
                    try {
                        val cacheDir = context.cacheDir
                        val cacheSize = deleteRecursive(cacheDir)
                        result.put("message", "Cache cleared")
                        result.put("data", JSONObject().apply {
                            put("bytes_freed", cacheSize)
                        })
                    } catch (e: Exception) {
                        result.put("status", "error")
                        result.put("message", "Failed to clear cache: ${e.message}")
                    }
                }
                
                else -> {
                    // 未知命令类型
                    result.put("status", "error")
                    result.put("message", "Unknown command type: $commandType")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command handling failed", e)
            result.put("status", "error")
            result.put("message", e.message ?: "Unknown error")
        }
        
        return result
    }
    
    /**
     * 获取设备状态
     */
    private fun getDeviceStatus(): JSONObject {
        return JSONObject().apply {
            put("online", true)
            put("battery_level", getBatteryLevel())
            put("network", "WiFi")
            put("timestamp", System.currentTimeMillis())
        }
    }
    
    /**
     * 获取真实电量
     */
    private fun getBatteryLevel(): Int {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            (level * 100 / scale)
        } else {
            -1
        }
    }
    
    /**
     * 递归删除文件夹
     */
    private fun deleteRecursive(fileOrDirectory: File): Long {
        var size = 0L
        if (fileOrDirectory.isDirectory) {
            fileOrDirectory.listFiles()?.forEach { child ->
                size += deleteRecursive(child)
            }
        }
        size += fileOrDirectory.length()
        fileOrDirectory.delete()
        return size
    }
}

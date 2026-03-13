package com.ufo.galaxy.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android 命令执行器
 *
 * 负责执行来自 Galaxy 主控的各种 Android 操作命令。
 *
 * WiFi / Bluetooth toggling is delegated to [SystemControlHelper] so that
 * the same implementation is used here and in AutonomyManager / TaskExecutor.
 */
class AndroidCommandExecutor(private val context: Context) {

    private val TAG = "AndroidCommandExecutor"
    private val systemControl = SystemControlHelper(context)
    
    /**
     * 执行命令
     */
    fun executeCommand(command: String, params: JSONObject): JSONObject {
        Log.i(TAG, "Executing command: $command with params: $params")
        
        return try {
            when (command) {
                // 系统信息
                "get_device_info" -> getDeviceInfo()
                "get_battery_status" -> getBatteryStatus()
                "get_network_info" -> getNetworkInfo()
                
                // 位置服务
                "get_location" -> getLocation()
                
                // 音量控制
                "set_volume" -> setVolume(params)
                "get_volume" -> getVolume()
                
                // WiFi 控制
                "get_wifi_status" -> getWifiStatus()
                "toggle_wifi" -> toggleWifi(params)
                
                // 蓝牙控制
                "toggle_bluetooth" -> toggleBluetooth(params)
                
                // 通知
                "send_notification" -> sendNotification(params)
                
                // 屏幕
                "get_screen_info" -> getScreenInfo()
                "set_brightness" -> setBrightness(params)
                
                // 应用控制
                "launch_app" -> launchApp(params)
                "get_installed_apps" -> getInstalledApps()
                
                // 传感器数据
                "get_sensor_data" -> getSensorData(params)
                
                else -> {
                    JSONObject().apply {
                        put("status", "error")
                        put("message", "Unknown command: $command")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command $command: ${e.message}", e)
            JSONObject().apply {
                put("status", "error")
                put("message", e.message ?: "Unknown error")
                put("command", command)
            }
        }
    }
    
    /**
     * 获取设备信息
     */
    private fun getDeviceInfo(): JSONObject {
        return JSONObject().apply {
            put("status", "success")
            put("data", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("brand", Build.BRAND)
                put("os_version", Build.VERSION.RELEASE)
                put("sdk_int", Build.VERSION.SDK_INT)
                put("hardware", Build.HARDWARE)
                put("board", Build.BOARD)
                put("display", Build.DISPLAY)
            })
        }
    }
    
    /**
     * 获取电池状态
     */
    private fun getBatteryStatus(): JSONObject {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging
        
        return JSONObject().apply {
            put("status", "success")
            put("data", JSONObject().apply {
                put("level", level)
                put("is_charging", isCharging)
                put("temperature", batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_TEMPERATURE) / 10.0)
            })
        }
    }
    
    /**
     * 获取网络信息
     */
    private fun getNetworkInfo(): JSONObject {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        
        return JSONObject().apply {
            put("status", "success")
            put("data", JSONObject().apply {
                put("wifi_enabled", wifiManager.isWifiEnabled)
                put("ssid", wifiInfo.ssid)
                put("ip_address", intToIp(wifiInfo.ipAddress))
                put("link_speed", wifiInfo.linkSpeed)
                put("rssi", wifiInfo.rssi)
            })
        }
    }
    
    /**
     * 获取位置
     */
    private fun getLocation(): JSONObject {
        // 注意：实际获取位置需要权限和异步操作
        // 这里返回一个占位符
        return JSONObject().apply {
            put("status", "success")
            put("message", "Location service requires runtime permission")
            put("data", JSONObject().apply {
                put("latitude", 0.0)
                put("longitude", 0.0)
                put("accuracy", 0.0)
            })
        }
    }
    
    /**
     * 设置音量
     */
    private fun setVolume(params: JSONObject): JSONObject {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = params.optInt("volume", 50)
        val streamType = when (params.optString("type", "media")) {
            "media" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        
        val maxVolume = audioManager.getStreamMaxVolume(streamType)
        val targetVolume = (volume * maxVolume / 100).coerceIn(0, maxVolume)
        audioManager.setStreamVolume(streamType, targetVolume, 0)
        
        return JSONObject().apply {
            put("status", "success")
            put("data", JSONObject().apply {
                put("volume", volume)
                put("type", params.optString("type", "media"))
            })
        }
    }
    
    /**
     * 获取音量
     */
    private fun getVolume(): JSONObject {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxMediaVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        
        return JSONObject().apply {
            put("status", "success")
            put("data", JSONObject().apply {
                put("media_volume", (mediaVolume * 100 / maxMediaVolume))
                put("media_max", maxMediaVolume)
            })
        }
    }
    
    /**
     * 获取 WiFi 状态
     */
    private fun getWifiStatus(): JSONObject {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        return JSONObject().apply {
            put("status", "success")
            put("data", JSONObject().apply {
                put("enabled", wifiManager.isWifiEnabled)
            })
        }
    }
    
    /**
     * 切换 WiFi
     *
     * On Android 10+ (API 29+) direct toggling via WifiManager is restricted
     * for non-system apps. [SystemControlHelper.toggleWifi] opens the system
     * WiFi Settings Panel so the user can confirm the action manually.
     * The response will contain `manual_required = true` in that case.
     */
    private fun toggleWifi(params: JSONObject): JSONObject {
        val enable = params.optBoolean("enable", true)
        Log.i(TAG, "[CMD] toggle_wifi enable=$enable")
        return systemControl.toggleWifi(enable)
    }

    /**
     * 切换蓝牙
     *
     * Requires BLUETOOTH_CONNECT permission on Android 12+ (API 31+).
     * Returns a structured error with `permission_required` when missing.
     */
    private fun toggleBluetooth(params: JSONObject): JSONObject {
        val enable = params.optBoolean("enable", true)
        Log.i(TAG, "[CMD] toggle_bluetooth enable=$enable")
        return systemControl.toggleBluetooth(enable)
    }
    
    /**
     * 发送通知
     */
    private fun sendNotification(params: JSONObject): JSONObject {
        val title = params.optString("title", "UFO Galaxy")
        val message = params.optString("message", "")
        val channelId = "ufo_galaxy_commands"
        val notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel (required on API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "UFO Galaxy Commands",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications sent by UFO³ Galaxy Agent"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId, notification)

        return JSONObject().apply {
            put("status", "success")
            put("data", JSONObject().apply {
                put("title", title)
                put("message", message)
                put("notification_id", notificationId)
            })
        }
    }
    
    /**
     * 获取屏幕信息
     */
    private fun getScreenInfo(): JSONObject {
        val displayMetrics = context.resources.displayMetrics
        
        return JSONObject().apply {
            put("status", "success")
            put("data", JSONObject().apply {
                put("width", displayMetrics.widthPixels)
                put("height", displayMetrics.heightPixels)
                put("density", displayMetrics.density)
                put("dpi", displayMetrics.densityDpi)
            })
        }
    }
    
    /**
     * 设置亮度
     *
     * Delegates to [SystemControlHelper.setBrightness].
     * Requires WRITE_SETTINGS permission (user must grant via
     * Settings → Apps → Special app access → Modify system settings).
     */
    private fun setBrightness(params: JSONObject): JSONObject {
        val brightness = params.optInt("brightness", 50)
        val success = systemControl.setBrightness(brightness)
        return if (success) {
            JSONObject().apply {
                put("status", "success")
                put("data", JSONObject().apply {
                    put("brightness", brightness)
                })
            }
        } else {
            JSONObject().apply {
                put("status", "error")
                put("message",
                    "Brightness control requires WRITE_SETTINGS permission. " +
                    "Grant it via Settings → Apps → Special app access → Modify system settings.")
                put("data", JSONObject().apply {
                    put("brightness", brightness)
                })
            }
        }
    }
    
    /**
     * 启动应用
     */
    private fun launchApp(params: JSONObject): JSONObject {
        val packageName = params.optString("package", "")
        
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                JSONObject().apply {
                    put("status", "success")
                    put("data", JSONObject().apply {
                        put("package", packageName)
                    })
                }
            } else {
                JSONObject().apply {
                    put("status", "error")
                    put("message", "App not found: $packageName")
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("message", e.message ?: "Failed to launch app")
            }
        }
    }
    
    /**
     * 获取已安装应用列表
     */
    private fun getInstalledApps(): JSONObject {
        val apps = context.packageManager.getInstalledApplications(0)
        val appList = apps.map { it.packageName }
        
        return JSONObject().apply {
            put("status", "success")
            put("data", JSONObject().apply {
                put("count", appList.size)
                put("apps", appList.take(50))  // 只返回前 50 个
            })
        }
    }
    
    /**
     * 获取传感器数据
     *
     * Lists available sensors for [sensorType] and attempts a one-shot synchronous
     * read (300 ms timeout) using a CountDownLatch.  If the latch times out (e.g. the
     * sensor hardware is unavailable or the calling thread has no Looper), the entry
     * for that sensor is still included but its "values" field is null.
     */
    private fun getSensorData(params: JSONObject): JSONObject {
        val sensorType = params.optString("sensor", "all")
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val sensorTypeMap = mapOf(
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "gyroscope"     to Sensor.TYPE_GYROSCOPE,
            "light"         to Sensor.TYPE_LIGHT,
            "pressure"      to Sensor.TYPE_PRESSURE,
            "magnetic"      to Sensor.TYPE_MAGNETIC_FIELD,
            "proximity"     to Sensor.TYPE_PROXIMITY
        )

        val typesToQuery: List<Int> = if (sensorType == "all") {
            sensorTypeMap.values.toList()
        } else {
            listOfNotNull(sensorTypeMap[sensorType])
        }

        // Collect last sensor event per type with a short blocking wait.
        // Only wait when at least one sensor is actually available on this device.
        val latestValues = mutableMapOf<Int, FloatArray>()
        val availableCount = typesToQuery.count { sensorManager.getDefaultSensor(it) != null }
        val collectedCount = java.util.concurrent.atomic.AtomicInteger(0)

        if (availableCount > 0) {
            val latch = CountDownLatch(1)

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (!latestValues.containsKey(event.sensor.type)) {
                        latestValues[event.sensor.type] = event.values.copyOf()
                        if (collectedCount.incrementAndGet() >= availableCount) {
                            latch.countDown()
                        }
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            }

            for (sType in typesToQuery) {
                val sensor = sensorManager.getDefaultSensor(sType) ?: continue
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }

            latch.await(300, TimeUnit.MILLISECONDS)
            sensorManager.unregisterListener(listener)
        }

        val sensorsArray = JSONArray()
        for (sType in typesToQuery) {
            val sensor = sensorManager.getDefaultSensor(sType) ?: continue
            val values = latestValues[sType]
            sensorsArray.put(JSONObject().apply {
                put("name", sensor.name)
                put("vendor", sensor.vendor)
                put("type", sType)
                put("max_range", sensor.maximumRange)
                put("resolution", sensor.resolution)
                put("power_ma", sensor.power)
                if (values != null) {
                    put("values", JSONArray().also { arr -> values.forEach { arr.put(it) } })
                } else {
                    put("values", JSONArray())
                }
            })
        }

        return JSONObject().apply {
            put("status", "success")
            put("data", JSONObject().apply {
                put("sensor_type", sensorType)
                put("sensors", sensorsArray)
                put("count", sensorsArray.length())
            })
        }
    }
    
    /**
     * 辅助函数：将整数 IP 转换为字符串
     */
    private fun intToIp(ip: Int): String {
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff
        )
    }
}

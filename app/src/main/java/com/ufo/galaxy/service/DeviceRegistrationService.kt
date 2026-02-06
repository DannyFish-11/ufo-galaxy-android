package com.ufo.galaxy.service

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 设备注册服务
 * 负责将 Android 设备注册到 UFO³ Galaxy 主系统
 */
class DeviceRegistrationService(private val context: Context) {

    companion object {
        private const val TAG = "DeviceRegistration"
        private const val DEFAULT_NODE50_URL = "http://localhost:8050"
        private const val REGISTRATION_ENDPOINT = "/api/register_device"
        private const val HEARTBEAT_INTERVAL = 30000L // 30 秒
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var isRegistered = false

    // 设备信息
    private val _deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    private val deviceInfo: JSONObject by lazy {
        JSONObject().apply {
            put("device_id", "Android_${_deviceId}")
            put("device_type", "Android")
            put("os_version", "Android ${Build.VERSION.RELEASE}")
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("capabilities", getDeviceCapabilities())
            put("status", "online")
        }
    }

    /**
     * 获取设备能力列表
     */
    private fun getDeviceCapabilities(): List<String> {
        return listOf(
            "accessibility_control",  // 无障碍控制
            "screenshot",             // 截图
            "gui_understanding",      // GUI 理解（通过 VLM）
            "voice_input",            // 语音输入
            "text_input",             // 文字输入
            "app_control",            // 应用控制
            "system_control",         // 系统控制
            "file_management",        // 文件管理
            "notification_access"     // 通知访问
        )
    }

    /**
     * 注册设备到主系统
     */
    suspend fun registerDevice(node50Url: String = DEFAULT_NODE50_URL): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Registering device to $node50Url...")

                val url = "$node50Url$REGISTRATION_ENDPOINT"
                val json = deviceInfo.toString()
                val body = json.toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        Log.i(TAG, "Device registered successfully: $responseBody")
                        isRegistered = true
                        
                        // 启动心跳
                        startHeartbeat(node50Url)
                        
                        true
                    } else {
                        Log.e(TAG, "Registration failed: ${response.code} ${response.message}")
                        false
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network error during registration", e)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Error during registration", e)
                false
            }
        }
    }

    /**
     * 启动心跳
     */
    private fun startHeartbeat(node50Url: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isRegistered) {
                delay(HEARTBEAT_INTERVAL)
                sendHeartbeat(node50Url)
            }
        }
    }

    /**
     * 发送心跳
     */
    private suspend fun sendHeartbeat(node50Url: String) {
        withContext(Dispatchers.IO) {
            try {
                val url = "$node50Url/api/heartbeat"
                val json = JSONObject().apply {
                    put("device_id", "Android_${_deviceId}")
                    put("status", "online")
                    put("timestamp", System.currentTimeMillis())
                }.toString()

                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Heartbeat sent successfully")
                    } else {
                        Log.w(TAG, "Heartbeat failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Heartbeat error: ${e.message}")
            }
        }
    }

    /**
     * 注销设备
     */
    suspend fun unregisterDevice(node50Url: String = DEFAULT_NODE50_URL): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Unregistering device from $node50Url...")

                val url = "$node50Url/api/unregister_device"
                val json = JSONObject().apply {
                    put("device_id", "Android_${_deviceId}")
                }.toString()

                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "Device unregistered successfully")
                        isRegistered = false
                        heartbeatJob?.cancel()
                        true
                    } else {
                        Log.e(TAG, "Unregistration failed: ${response.code}")
                        false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during unregistration", e)
                false
            }
        }
    }

    /**
     * 获取设备 ID
     */
    fun getDeviceId(): String = "Android_${_deviceId}"

    /**
     * 检查是否已注册
     */
    fun isDeviceRegistered(): Boolean = isRegistered

    /**
     * 关闭服务
     */
    fun shutdown() {
        heartbeatJob?.cancel()
        scope.cancel()
    }
}

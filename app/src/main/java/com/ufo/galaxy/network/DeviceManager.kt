package com.ufo.galaxy.network

import android.content.Context
import android.util.Log
import com.ufo.galaxy.config.GalaxyConfig
import com.ufo.galaxy.config.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 设备状态管理器
 *
 * 管理设备与服务端的连接状态和心跳上报。
 * 被 UnifiedCoreManager 使用。
 */
class DeviceManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DeviceManager"

        @Volatile
        private var instance: DeviceManager? = null

        fun getInstance(context: Context): DeviceManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val config by lazy { GalaxyConfig.getInstance(context) }

    @Volatile
    private var connected = false

    fun initialize() {
        Log.i(TAG, "DeviceManager initialized")
    }

    suspend fun connect() {
        withContext(Dispatchers.IO) {
            try {
                val httpBase = config.getHttpBaseUrl()
                val deviceId = config.getDeviceId()

                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("device_type", "android")
                    put("timestamp", System.currentTimeMillis())
                }

                val success = postJson(
                    "$httpBase${ServerConfig.REST_V1_PREFIX}/heartbeat", body
                )

                connected = success
                if (success) {
                    Log.i(TAG, "Connected to server: $httpBase")
                } else {
                    Log.w(TAG, "Connection heartbeat failed, will retry on next sync")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                connected = false
            }
        }
    }

    suspend fun sendDeviceStatus(status: JSONObject) {
        withContext(Dispatchers.IO) {
            try {
                val httpBase = config.getHttpBaseUrl()
                postJson("$httpBase${ServerConfig.REST_V1_PREFIX}/heartbeat", status)
            } catch (e: Exception) {
                Log.e(TAG, "Status send failed", e)
            }
        }
    }

    fun isConnected(): Boolean = connected

    private fun postJson(url: String, body: JSONObject): Boolean {
        return try {
            val requestBody = body.toString()
                .toByteArray()
                .let { okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), it) }

            val request = okhttp3.Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "POST $url failed: ${e.message}")
            false
        }
    }
}

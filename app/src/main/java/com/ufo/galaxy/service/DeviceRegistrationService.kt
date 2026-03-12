package com.ufo.galaxy.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.ufo.galaxy.config.GalaxyConfig
import com.ufo.galaxy.config.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 设备注册服务
 *
 * 通过 REST API 向服务端注册/注销当前 Android 设备。
 * 链路: DeviceRegistrationService → /api/v1/devices/register (fallback /api/devices/register)
 */
class DeviceRegistrationService(private val context: Context) {

    companion object {
        private const val TAG = "DeviceRegistration"
        private const val PREFS_NAME = "device_registration"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_REGISTERED = "registered"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val config by lazy { GalaxyConfig.getInstance(context) }

    fun getDeviceId(): String {
        val saved = prefs.getString(KEY_DEVICE_ID, null)
        if (saved != null) return saved

        val generated = "android_${Build.MODEL.replace(" ", "_")}_${System.currentTimeMillis()}"
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        config.setDeviceId(generated)
        return generated
    }

    fun isDeviceRegistered(): Boolean = prefs.getBoolean(KEY_REGISTERED, false)

    suspend fun registerDevice(serverUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val httpBase = ServerConfig.wsToHttpBase(serverUrl)
                val deviceId = getDeviceId()

                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("device_type", "android")
                    put("model", Build.MODEL)
                    put("sdk_version", Build.VERSION.SDK_INT)
                    put("timestamp", System.currentTimeMillis())
                }

                val success = postRegistration(
                    "$httpBase${ServerConfig.REST_V1_PREFIX}/register", body
                ) ?: postRegistration(
                    "$httpBase${ServerConfig.REST_LEGACY_PREFIX}/register", body
                )

                if (success == true) {
                    prefs.edit().putBoolean(KEY_REGISTERED, true).apply()
                    Log.i(TAG, "Device registered: $deviceId")
                }

                success ?: false
            } catch (e: Exception) {
                Log.e(TAG, "Registration failed", e)
                false
            }
        }
    }

    suspend fun unregisterDevice(serverUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val httpBase = ServerConfig.wsToHttpBase(serverUrl)
                val deviceId = getDeviceId()

                val body = JSONObject().apply {
                    put("device_id", deviceId)
                }

                val success = postRegistration(
                    "$httpBase${ServerConfig.REST_V1_PREFIX}/unregister", body
                ) ?: postRegistration(
                    "$httpBase${ServerConfig.REST_LEGACY_PREFIX}/unregister", body
                )

                if (success == true) {
                    prefs.edit().putBoolean(KEY_REGISTERED, false).apply()
                    Log.i(TAG, "Device unregistered: $deviceId")
                }

                success ?: false
            } catch (e: Exception) {
                Log.e(TAG, "Unregistration failed", e)
                false
            }
        }
    }

    private fun postRegistration(url: String, body: JSONObject): Boolean? {
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
            if (response.isSuccessful) true else null
        } catch (e: Exception) {
            Log.w(TAG, "POST $url failed: ${e.message}")
            null
        }
    }

    fun shutdown() {
        // nothing to clean up
    }
}

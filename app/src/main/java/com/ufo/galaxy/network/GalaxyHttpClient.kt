package com.ufo.galaxy.network

import android.util.Log
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 统一 HTTP 客户端
 *
 * 提供共享的 OkHttpClient 实例，避免 Node58、GUIUnderstanding、GalaxyApiClient
 * 各自创建独立客户端的问题。
 *
 * 用法:
 * ```kotlin
 * val result = GalaxyHttpClient.postJson(url, body, authToken)
 * val data = GalaxyHttpClient.get(url)
 * ```
 */
object GalaxyHttpClient {

    private const val TAG = "GalaxyHttpClient"

    private val JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8")

    /** 短超时客户端 — 用于 health check、stats 等快速请求 */
    val fast: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 标准超时客户端 — 用于一般 API 调用 */
    val standard: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 长超时客户端 — 用于 LLM chat、视觉分析等耗时请求 */
    val slow: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * POST JSON 请求
     *
     * @return 解析后的 JSONObject，请求失败返回 null
     */
    fun postJson(
        url: String,
        body: JSONObject,
        authToken: String? = null,
        client: OkHttpClient = standard
    ): JSONObject? {
        return try {
            val requestBody = RequestBody.create(JSON_MEDIA_TYPE, body.toString())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .apply { authToken?.let { addHeader("Authorization", "Bearer $it") } }
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body()?.string()

            if (response.isSuccessful && responseBody != null) {
                JSONObject(responseBody)
            } else {
                Log.w(TAG, "POST $url failed: ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST $url error: ${e.message}")
            null
        }
    }

    /**
     * GET 请求
     *
     * @return 解析后的 JSONObject，请求失败返回 null
     */
    fun get(
        url: String,
        authToken: String? = null,
        client: OkHttpClient = standard
    ): JSONObject? {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .apply { authToken?.let { addHeader("Authorization", "Bearer $it") } }
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body()?.string()

            if (response.isSuccessful && responseBody != null) {
                JSONObject(responseBody)
            } else {
                Log.w(TAG, "GET $url failed: ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET $url error: ${e.message}")
            null
        }
    }
}

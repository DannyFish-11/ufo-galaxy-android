package com.ufo.galaxy.api

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * REST client for Galaxy Gateway device-management endpoints.
 *
 * All device-facing REST calls follow a **v1-first with 404 fallback** strategy
 * that mirrors the pattern used by [com.ufo.galaxy.memory.OpenClawdMemoryBackflow]:
 *
 * 1. Issue the request to the **v1** path (`/api/v1/devices/<action>`).
 * 2. If the server returns **HTTP 404 only**, retry against the **legacy** path
 *    (`/api/devices/<action>`).
 * 3. Any other error (network exception, non-404 HTTP error) is returned immediately
 *    — no second attempt is made so that real server errors surface promptly.
 *
 * @param restBaseUrl  HTTP base URL of the Galaxy Gateway (no trailing slash),
 *                     e.g. `"http://100.0.0.1:8765"`.
 * @param httpClient   OkHttpClient used for all requests.  Override in tests to
 *                     inject a fake interceptor without real network calls.
 */
class GalaxyApiClient(
    private val restBaseUrl: String,
    private val httpClient: OkHttpClient = defaultClient()
) {

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Register a device with the gateway (v1-first, 404 fallback).
     *
     * Tries `POST /api/v1/devices/register` first. On HTTP 404 retries with
     * the legacy path `POST /api/devices/register`. Any other HTTP error or
     * network exception is returned immediately without a second attempt.
     *
     * @param deviceInfo JSON payload describing the device to register.
     * @return [Result.success] containing the server response body on 2xx;
     *         [Result.failure] otherwise.
     */
    fun registerDevice(deviceInfo: JSONObject): Result<JSONObject> {
        val base = restBaseUrl.trimEnd('/')
        val v1Url = "$base/api/v1/devices/register"
        val legacyUrl = "$base/api/devices/register"
        return postWithFallback(v1Url = v1Url, legacyUrl = legacyUrl, body = deviceInfo, action = "REGISTER")
    }

    /**
     * Send a heartbeat for the given device (v1-first, 404 fallback).
     *
     * Tries `POST /api/v1/devices/heartbeat` first. On HTTP 404 retries with
     * `POST /api/devices/heartbeat`. Any other HTTP error or network exception
     * is returned immediately.
     *
     * @param deviceId The identifier of the device sending the heartbeat.
     * @return [Result.success] containing the server response body on 2xx;
     *         [Result.failure] otherwise.
     */
    fun sendHeartbeat(deviceId: String): Result<JSONObject> {
        val base = restBaseUrl.trimEnd('/')
        val v1Url = "$base/api/v1/devices/heartbeat"
        val legacyUrl = "$base/api/devices/heartbeat"
        val body = JSONObject().apply { put("device_id", deviceId) }
        return postWithFallback(v1Url = v1Url, legacyUrl = legacyUrl, body = body, action = "HEARTBEAT")
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /**
     * Issues a POST request to [v1Url]; on HTTP 404 retries against [legacyUrl].
     * Non-404 failures are returned immediately without a second attempt.
     */
    private fun postWithFallback(
        v1Url: String,
        legacyUrl: String,
        body: JSONObject,
        action: String
    ): Result<JSONObject> {
        return try {
            val request = Request.Builder()
                .url(v1Url)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    Log.w(TAG, "[DEVICE:$action] v1 returned 404; falling back to legacy path")
                    postDirect(legacyUrl, body, action = "$action:LEGACY")
                } else {
                    val responseBody = response.body?.string()?.let { JSONObject(it) } ?: JSONObject()
                    if (response.isSuccessful) {
                        Log.i(TAG, "[DEVICE:$action] http=${response.code} endpoint=v1")
                        Result.success(responseBody)
                    } else {
                        Log.w(TAG, "[DEVICE:$action] http=${response.code} endpoint=v1")
                        Result.failure(Exception("HTTP ${response.code}"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE:$action] error=${e.message}", e)
            Result.failure(e)
        }
    }

    /** Issues a POST to [url] directly, without any fallback logic. */
    private fun postDirect(url: String, body: JSONObject, action: String): Result<JSONObject> {
        return try {
            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()?.let { JSONObject(it) } ?: JSONObject()
                if (response.isSuccessful) {
                    Log.i(TAG, "[DEVICE:$action] http=${response.code}")
                    Result.success(responseBody)
                } else {
                    Log.w(TAG, "[DEVICE:$action] http=${response.code}")
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE:$action] error=${e.message}", e)
            Result.failure(e)
        }
    }

    // ── Companion ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "GalaxyApiClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Default OkHttpClient with conservative timeouts suitable for gateway calls. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

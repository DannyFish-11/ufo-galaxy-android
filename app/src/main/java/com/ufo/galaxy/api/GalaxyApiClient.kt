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
 * **NOTE**: Device registration and heartbeat are handled by
 * [com.ufo.galaxy.network.GalaxyWebSocketClient] as part of the canonical WS-based
 * cross-device uplink backbone: the `capability_report` message sent in [onOpen]
 * serves as the device registration event, and `heartbeat` messages are emitted
 * automatically on a 30-second interval. REST-based registration and heartbeat methods
 * in this class ([registerDevice], [sendHeartbeat]) are therefore deprecated in favour
 * of the WS-based paths. This class is retained for legacy REST endpoint checks and
 * integration validation only.
 *
 * All device-facing REST calls follow a **v1-first with 404/405 fallback** strategy
 * that mirrors the pattern used by [com.ufo.galaxy.memory.OpenClawdMemoryBackflow]:
 *
 * 1. Issue the request to the **v1** path (`/api/v1/devices/<action>`).
 * 2. If the server returns **HTTP 404 only**, retry against the **legacy** path
 *    (`/api/devices/<action>`).
 * 3. Any other error (network exception, non-404 HTTP error) is returned immediately
 *    — no second attempt is made so that real server errors surface promptly.
 *
 * @param restBaseUrl  HTTP base URL of the Galaxy Gateway (no trailing slash),
 *                     e.g. `"http://100.0.0.1:9000"`.
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
     * **DEPRECATED**: Device registration is handled by [com.ufo.galaxy.network.GalaxyWebSocketClient]
     * via the `capability_report` message sent automatically on WS connection ([onOpen]).
     * This REST endpoint is retained only for diagnostic use cases.
     *
     * Tries `POST /api/v1/devices/register` first. On HTTP 404 retries with
     * the legacy path `POST /api/devices/register`. Any other HTTP error or
     * network exception is returned immediately without a second attempt.
     *
     * @param deviceInfo JSON payload describing the device to register.
     * @return [Result.success] containing the server response body on 2xx;
     *         [Result.failure] otherwise.
     */
    @Deprecated(
        message = "Device registration is handled by GalaxyWebSocketClient via the capability_report " +
            "message sent on WS connection. Use GalaxyWebSocketClient as the sole cross-device uplink."
    )
    fun registerDevice(deviceInfo: JSONObject): Result<JSONObject> {
        val base = restBaseUrl.trimEnd('/')
        val v1Url = "$base/api/v1/devices/register"
        val legacyUrl = "$base/api/devices/register"
        return postWithFallback(v1Url = v1Url, legacyUrl = legacyUrl, body = deviceInfo, action = "REGISTER")
    }

    /**
     * Send a heartbeat for the given device (v1-first, 404 fallback).
     *
     * **DEPRECATED**: Heartbeats are sent automatically by [com.ufo.galaxy.network.GalaxyWebSocketClient]
     * on a 30-second interval as part of the canonical WS-based uplink backbone.
     * This REST endpoint is retained only for diagnostic use cases.
     *
     * Tries `POST /api/v1/devices/{device_id}/heartbeat` first. On HTTP 404 **or 405**
     * retries with `POST /api/devices/heartbeat`. Any other HTTP error or network
     * exception is returned immediately.
     *
     * 405 也算:旧的裸路径 `/api/v1/devices/heartbeat` 会被 `/api/v1/devices/{device_id}`
     * 这条 GET 路由吃掉,POST 过去拿到的是 405 而不是 404 —— 只认 404 的兜底会在这里
     * 直接放弃,而这恰恰是路径重构后最常见的表现。
     *
     * @param deviceId The identifier of the device sending the heartbeat.
     * @return [Result.success] containing the server response body on 2xx;
     *         [Result.failure] otherwise.
     */
    @Deprecated(
        message = "Heartbeats are sent automatically by GalaxyWebSocketClient on a 30-second " +
            "interval. Use GalaxyWebSocketClient as the sole cross-device uplink."
    )
    fun sendHeartbeat(deviceId: String): Result<JSONObject> {
        val base = restBaseUrl.trimEnd('/')
        // V2 服务端的 v1 心跳路由是 /api/v1/devices/{device_id}/heartbeat,
        // 没有裸的 /api/v1/devices/heartbeat。此前打裸路径会撞上 /api/v1/devices/{device_id}
        // 这条 GET 路由 —— POST 过去得到的是 **405 而不是 404**,而下面的兜底只认 404,
        // 于是那条真的能用的 legacy 路径永远到不了。实测:v1 405 / legacy 422。
        val v1Url = "$base/api/v1/devices/${encodePathSegment(deviceId)}/heartbeat"
        val legacyUrl = "$base/api/devices/heartbeat"
        val body = JSONObject().apply { put("device_id", deviceId) }
        return postWithFallback(v1Url = v1Url, legacyUrl = legacyUrl, body = body, action = "HEARTBEAT")
    }

    /**
     * Reconcile this device's local (typically offline-generated) conversation session
     * into the user's canonical cross-device session line on V2.
     *
     * Posts to `POST /api/v1/sessions/reconcile`. After this call the local session id is
     * aliased to the canonical thread on the backend, so every subsequent turn carrying
     * that local id — online goal_execution, panel, or offline ingest — folds into the
     * one shared conversation line. New v1-only endpoint: no legacy fallback.
     *
     * @param localSessionId     The device-local conversation session id to claim.
     * @param canonicalSessionId Optional explicit target thread; blank → V2 picks the
     *                           user's active thread or creates one.
     * @param userId             Optional user id owning the canonical thread.
     * @param deviceId           This device's id (for the session_sync push back).
     * @param mergeHistory       When true, V2 merges the local session's recorded turns
     *                           into the canonical thread.
     */
    fun reconcileSession(
        localSessionId: String,
        canonicalSessionId: String = "",
        userId: String = "",
        deviceId: String = "",
        mergeHistory: Boolean = true
    ): Result<JSONObject> {
        val base = restBaseUrl.trimEnd('/')
        val url = "$base/api/v1/sessions/reconcile"
        val body = JSONObject().apply {
            put("local_session_id", localSessionId)
            if (canonicalSessionId.isNotBlank()) put("canonical_session_id", canonicalSessionId)
            if (userId.isNotBlank()) put("user_id", userId)
            if (deviceId.isNotBlank()) put("device_id", deviceId)
            put("merge_history", mergeHistory)
        }
        return postDirect(url, body, action = "SESSION_RECONCILE")
    }

    /**
     * Ingest a batch of conversation turns (typically recorded while the phone was
     * offline) into the unified session line on V2.
     *
     * Posts to `POST /api/v1/sessions/ingest_turns`. The session id is alias-resolved to
     * the canonical thread on the backend and each turn goes through the single unified
     * memory door. New v1-only endpoint: no legacy fallback.
     *
     * @param sessionId The (possibly local/alias) conversation session id.
     * @param turns     Ordered conversation turns to append.
     * @param userId    Optional user id owning the thread.
     * @param deviceId  This device's id.
     */
    fun ingestConversationTurns(
        sessionId: String,
        turns: List<ConversationTurn>,
        userId: String = "",
        deviceId: String = ""
    ): Result<JSONObject> {
        val base = restBaseUrl.trimEnd('/')
        val url = "$base/api/v1/sessions/ingest_turns"
        val turnsArray = org.json.JSONArray()
        for (t in turns) {
            turnsArray.put(JSONObject().apply {
                put("role", t.role)
                put("content", t.content)
                if (t.tsMs > 0) put("ts", t.tsMs / 1000.0)
            })
        }
        val body = JSONObject().apply {
            put("session_id", sessionId)
            if (userId.isNotBlank()) put("user_id", userId)
            if (deviceId.isNotBlank()) put("device_id", deviceId)
            put("turns", turnsArray)
        }
        return postDirect(url, body, action = "SESSION_INGEST_TURNS")
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
                // 405 也要兜底。"这条路由不在这个版本上"在 FastAPI 里有两种表现:
                // 路径完全不匹配是 404,而路径匹配了别的路由、方法不对是 **405**。
                // 只认 404 的兜底会在后一种情形下直接放弃 —— 而后一种恰恰是路径重构后
                // 最常见的表现(旧路径被某条 {param} 路由吃掉)。实测踩到过。
                if (response.code == 404 || response.code == 405) {
                    Log.w(TAG, "[DEVICE:$action] v1 returned ${response.code}; falling back to legacy path")
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

    /**
     * One conversation turn for offline ingest.
     *
     * @param role    "user" | "assistant" (system turns are ignored by V2).
     * @param content The turn text.
     * @param tsMs    Client wall-clock timestamp in epoch millis (0 = omit).
     */
    data class ConversationTurn(
        val role: String,
        val content: String,
        val tsMs: Long = 0L
    )

    // ── Companion ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "GalaxyApiClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * URL-encodes a single path segment.
         *
         * device_id 进路径就必须编码 —— 它来自设备侧,可能含 `/`、空格或非 ASCII。
         * 不编码时一个带 `/` 的 id 会把请求打到一条完全不同的路由上,而表现只是
         * 一个看不懂的 404/405,不会有任何一行提示是 id 的问题。
         *
         * `URLEncoder` 是表单编码:空格会变成 `+`,而路径段里必须是 `%20`,所以要换回来。
         */
        internal fun encodePathSegment(raw: String): String =
            java.net.URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

        /** Default OkHttpClient with conservative timeouts suitable for gateway calls. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

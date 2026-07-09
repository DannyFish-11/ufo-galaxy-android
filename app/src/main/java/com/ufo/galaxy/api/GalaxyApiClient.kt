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
     * Tries `POST /api/v1/devices/heartbeat` first. On HTTP 404 retries with
     * `POST /api/devices/heartbeat`. Any other HTTP error or network exception
     * is returned immediately.
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
        val v1Url = "$base/api/v1/devices/heartbeat"
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

        /** Default OkHttpClient with conservative timeouts suitable for gateway calls. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

package com.ufo.galaxy.config

import android.util.Log
import kotlin.coroutines.resume
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches gateway configuration from the remote REST endpoint using a
 * **v1-first with 404 fallback** strategy that mirrors the pattern used by
 * [com.ufo.galaxy.memory.OpenClawdMemoryBackflow]:
 *
 * 1. Issue a `GET /api/v1/config` request (v1 path).
 * 2. If the server returns **HTTP 404 only**, retry against the legacy path
 *    `GET /api/config`.
 * 3. Any other error (network exception, non-404 HTTP error) is returned
 *    immediately — no second attempt is made.
 *
 * The returned [JSONObject] is the raw config payload from the server.
 * Callers are responsible for extracting fields from the returned object.
 *
 * Usage:
 * ```kotlin
 * val fetcher = RemoteConfigFetcher(restBaseUrl = "http://100.0.0.1:9000")
 * val config: JSONObject? = fetcher.fetchConfig()
 * ```
 *
 * @param restBaseUrl  HTTP base URL of the Galaxy Gateway (no trailing slash),
 *                     e.g. `"http://100.0.0.1:9000"`.
 * @param httpClient   OkHttpClient used for all requests.  Override in tests to
 *                     inject a fake interceptor without real network calls.
 */
class RemoteConfigFetcher(
    private val restBaseUrl: String,
    private val httpClient: OkHttpClient = defaultClient()
) {

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Fetches gateway configuration (v1-first, 404 fallback).
     *
     * Calls `GET /api/v1/config` first. On HTTP 404 retries with the legacy
     * path `GET /api/config`. Any other HTTP error or network exception is
     * returned as a `null` result — no second attempt is made.
     *
     * @return Parsed [JSONObject] on success; `null` on any failure (network
     *         error, non-2xx response, or JSON parse error).
     */
    // CRITICAL-8: Async suspend function to avoid blocking the main thread
    suspend fun fetchConfig(): JSONObject? {
        val base = restBaseUrl.trimEnd('/')
        val v1Url = "$base/api/v1/config"
        val legacyUrl = "$base/api/config"
        return fetchWithFallback(v1Url = v1Url, legacyUrl = legacyUrl)
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /**
     * Issues a GET request to [v1Url]; on HTTP 404 retries against [legacyUrl].
     * Non-404 failures are returned as `null` immediately without a second attempt.
     * CRITICAL-8: Uses async enqueue to avoid blocking the caller thread.
     */
    private suspend fun fetchWithFallback(v1Url: String, legacyUrl: String): JSONObject? {
        val v1Result = fetchDirect(v1Url, endpoint = "v1") ?: return null
        if (v1Result.code == 404) {
            Log.w(TAG, "[CONFIG] v1 returned 404; falling back to legacy path")
            return fetchDirect(legacyUrl, endpoint = "legacy")?.body
        }
        if (!v1Result.success) {
            Log.w(TAG, "[CONFIG] http=${v1Result.code} endpoint=v1")
            return null
        }
        return v1Result.body
    }

    /** Internal result wrapper to carry both response code and parsed body. */
    private data class FetchResult(val code: Int, val success: Boolean, val body: JSONObject?)

    /** Issues a GET to [url] asynchronously using OkHttp enqueue. */
    private suspend fun fetchDirect(url: String, endpoint: String): FetchResult? {
        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        Log.e(TAG, "[CONFIG:$endpoint] error=${e.message}")
                        if (continuation.isActive) continuation.resume(null)
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.use { resp ->
                            val body = resp.body?.string()?.let { JSONObject(it) }
                            if (continuation.isActive) {
                                continuation.resume(FetchResult(code = resp.code, success = resp.isSuccessful, body = body))
                            }
                        }
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "[CONFIG:$endpoint] error=${e.message}", e)
            null
        }
    }

    // ── Companion ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "RemoteConfigFetcher"

        /** V1 config endpoint path. */
        const val CONFIG_V1_PATH = "/api/v1/config"

        /** Legacy config endpoint path (fallback). */
        const val CONFIG_LEGACY_PATH = "/api/config"

        /** Default OkHttpClient with conservative timeouts. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

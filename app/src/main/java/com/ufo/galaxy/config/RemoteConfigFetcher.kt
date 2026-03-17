package com.ufo.galaxy.config

import android.util.Log
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
 * val fetcher = RemoteConfigFetcher(restBaseUrl = "http://100.0.0.1:8765")
 * val config: JSONObject? = fetcher.fetchConfig()
 * ```
 *
 * @param restBaseUrl  HTTP base URL of the Galaxy Gateway (no trailing slash),
 *                     e.g. `"http://100.0.0.1:8765"`.
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
    fun fetchConfig(): JSONObject? {
        val base = restBaseUrl.trimEnd('/')
        val v1Url = "$base/api/v1/config"
        val legacyUrl = "$base/api/config"
        return fetchWithFallback(v1Url = v1Url, legacyUrl = legacyUrl)
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /**
     * Issues a GET request to [v1Url]; on HTTP 404 retries against [legacyUrl].
     * Non-404 failures are returned as `null` immediately without a second attempt.
     */
    private fun fetchWithFallback(v1Url: String, legacyUrl: String): JSONObject? {
        return try {
            val request = Request.Builder().url(v1Url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    Log.w(TAG, "[CONFIG] v1 returned 404; falling back to legacy path")
                    fetchDirect(legacyUrl, endpoint = "legacy")
                } else if (!response.isSuccessful) {
                    Log.w(TAG, "[CONFIG] http=${response.code} endpoint=v1")
                    null
                } else {
                    val body = response.body?.string()
                    Log.i(TAG, "[CONFIG] http=${response.code} endpoint=v1")
                    body?.let { JSONObject(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[CONFIG] error=${e.message}", e)
            null
        }
    }

    /** Issues a GET to [url] directly, without any fallback logic. */
    private fun fetchDirect(url: String, endpoint: String): JSONObject? {
        return try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "[CONFIG] http=${response.code} endpoint=$endpoint")
                    null
                } else {
                    val body = response.body?.string()
                    Log.i(TAG, "[CONFIG] http=${response.code} endpoint=$endpoint")
                    body?.let { JSONObject(it) }
                }
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

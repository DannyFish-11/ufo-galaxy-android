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
     * Calls `GET /api/config` first (实测那是 V2 上真实存在的那条). On HTTP 404
     * retries with `GET /api/v1/config` —— 那条服务端目前没有,留着是为了它补上之后
     * 这里不用改就能自动切过去. Any other HTTP error or network exception is
     * returned as a `null` result — no second attempt is made.
     *
     * @return Parsed [JSONObject] on success; `null` on any failure (network
     *         error, non-2xx response, or JSON parse error).
     */
    // CRITICAL-8: Async suspend function to avoid blocking the main thread
    //
    // 顺序是**先 /api/config、后 /api/v1/config**,与本类其余"v1 优先"的说法相反,
    // 这是有意的:实测 V2 服务端根本没有 /api/v1/config(404),配置值就在 /api/config。
    // 保持"v1 优先"只会让每一次取配置都先白打一个必然 404 的请求。
    //
    // 那为什么还留着 /api/v1/config 这一跳?因为它是**将来**的路径:一旦服务端补上,
    // 这里不用改就会自动切过去。也就是说这里不是"降级到 legacy",而是
    // "打现在存在的那个,并为将来留门"。
    //
    // 注意 /api/v1/config/status **不能**拿来顶替 —— 那是配置管理器的运行状态,
    // 不是配置值本身。名字像,内容完全不是一回事。
    suspend fun fetchConfig(): JSONObject? {
        val base = restBaseUrl.trimEnd('/')
        return fetchWithFallback(v1Url = "$base$CONFIG_PRIMARY_PATH", legacyUrl = "$base$CONFIG_FUTURE_PATH")
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /**
     * Issues a GET request to [v1Url]; on HTTP 404 retries against [legacyUrl].
     * (命名沿用历史;当前 [v1Url] 传的是 /api/config,[legacyUrl] 传的是 /api/v1/config。)
     * Non-404 failures are returned as `null` immediately without a second attempt.
     * CRITICAL-8: Uses async enqueue to avoid blocking the caller thread.
     */
    private suspend fun fetchWithFallback(v1Url: String, legacyUrl: String): JSONObject? {
        val v1Result = fetchDirect(v1Url, endpoint = "v1") ?: return null
        if (v1Result.code == 404) {
            Log.w(TAG, "[CONFIG] primary $CONFIG_PRIMARY_PATH returned 404; trying $CONFIG_FUTURE_PATH")
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

        /**
         * 当前真实存在的配置端点。V2 服务端实测:GET /api/config → 200。
         *
         * 名字不叫 CONFIG_LEGACY_PATH —— 它不是遗留物,是**现在唯一能用的那个**。
         */
        const val CONFIG_PRIMARY_PATH = "/api/config"

        /** 预留的 v1 路径。服务端目前没有(实测 404),补上之后这里会自动切过去。 */
        const val CONFIG_FUTURE_PATH = "/api/v1/config"

        /** V1 config endpoint path. */
        @Deprecated("服务端不存在这条路由(实测 404)。用 CONFIG_PRIMARY_PATH。", ReplaceWith("CONFIG_PRIMARY_PATH"))
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

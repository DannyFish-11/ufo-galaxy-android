package com.ufo.galaxy.config

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [RemoteConfigFetcher] v1-first with 404 fallback behavior.
 *
 * All HTTP calls are intercepted via a custom [OkHttpClient] interceptor to avoid
 * real network I/O.  Tests verify:
 *  - [RemoteConfigFetcher.fetchConfig] calls `GET /api/v1/config` first.
 *  - [RemoteConfigFetcher.fetchConfig] falls back to `GET /api/config` on 404.
 *  - [RemoteConfigFetcher.fetchConfig] does NOT fall back on non-404 HTTP errors.
 *  - Network exceptions are surfaced as `null` without a second attempt.
 *  - The returned [org.json.JSONObject] contains values from the server response.
 */
class RemoteConfigFetcherTest {

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Builds an [OkHttpClient] that returns [v1Code]+[v1Body] for paths
     * containing `/api/v1/` and [legacyCode]+[legacyBody] for all other paths.
     */
    private fun routingClient(
        v1Code: Int,
        legacyCode: Int,
        v1Body: String = "{}",
        legacyBody: String = "{}"
    ): OkHttpClient {
        val interceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            val (code, body) = if ("/api/v1/" in url) Pair(v1Code, v1Body) else Pair(legacyCode, legacyBody)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    private fun errorClient(): OkHttpClient {
        val interceptor = Interceptor { _ -> throw java.io.IOException("connection refused") }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    private fun fetcher(client: OkHttpClient) =
        RemoteConfigFetcher(restBaseUrl = "http://gw.example.com:8765", httpClient = client)

    private val sampleConfig = """{"ws_base":"ws://gw.example.com:8765","feature_flags":{"beta":true}}"""

    // ── fetchConfig v1-first ───────────────────────────────────────────────

    @Test
    fun `fetchConfig returns JSONObject when v1 endpoint returns 200`() {
        val f = fetcher(routingClient(v1Code = 200, legacyCode = 500, v1Body = sampleConfig))
        val result = f.fetchConfig()
        assertNotNull("fetchConfig must return non-null on v1 200", result)
        assertEquals("ws://gw.example.com:8765", result!!.optString("ws_base"))
    }

    @Test
    fun `fetchConfig hits v1 path first`() {
        val urls = mutableListOf<String>()
        val interceptor = Interceptor { chain ->
            urls += chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(sampleConfig.toResponseBody("application/json".toMediaType()))
                .build()
        }
        val f = RemoteConfigFetcher(
            restBaseUrl = "http://gw.example.com:8765",
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
        f.fetchConfig()
        assertTrue("First request must target /api/v1/config", urls[0].contains("/api/v1/config"))
    }

    // ── 404 fallback ───────────────────────────────────────────────────────

    @Test
    fun `fetchConfig falls back to legacy when v1 returns 404`() {
        val f = fetcher(
            routingClient(v1Code = 404, legacyCode = 200, legacyBody = sampleConfig)
        )
        val result = f.fetchConfig()
        assertNotNull("fetchConfig must return config from legacy after v1 404", result)
        assertEquals("ws://gw.example.com:8765", result!!.optString("ws_base"))
    }

    @Test
    fun `fetchConfig returns null when both v1 and legacy return 404`() {
        val f = fetcher(routingClient(v1Code = 404, legacyCode = 404))
        assertNull("fetchConfig must return null when both endpoints return 404", f.fetchConfig())
    }

    @Test
    fun `fetchConfig returns null when v1 returns 404 and legacy returns non-2xx`() {
        val f = fetcher(routingClient(v1Code = 404, legacyCode = 500))
        assertNull("fetchConfig must return null when legacy also fails", f.fetchConfig())
    }

    // ── No fallback on non-404 errors ──────────────────────────────────────

    @Test
    fun `fetchConfig does NOT fall back to legacy when v1 returns 500`() {
        val urls = mutableListOf<String>()
        val interceptor = Interceptor { chain ->
            urls += chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Server Error")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        val f = RemoteConfigFetcher(
            restBaseUrl = "http://gw.example.com:8765",
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
        val result = f.fetchConfig()
        assertNull("fetchConfig must return null on v1 500 without fallback", result)
        assertEquals("Only one request must be made (no fallback on 500)", 1, urls.size)
        assertTrue("Request must target v1 path", urls[0].contains("/api/v1/"))
    }

    @Test
    fun `fetchConfig does NOT fall back to legacy when v1 returns 401`() {
        val urls = mutableListOf<String>()
        val interceptor = Interceptor { chain ->
            urls += chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        val f = RemoteConfigFetcher(
            restBaseUrl = "http://gw.example.com:8765",
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
        val result = f.fetchConfig()
        assertNull(result)
        assertEquals("Only one request must be made (no fallback on 401)", 1, urls.size)
    }

    // ── Network errors ─────────────────────────────────────────────────────

    @Test
    fun `fetchConfig returns null on network exception`() {
        val f = fetcher(errorClient())
        assertNull("fetchConfig must return null on network exception", f.fetchConfig())
    }

    // ── Constant values ────────────────────────────────────────────────────

    @Test
    fun `CONFIG_V1_PATH is the expected value`() {
        assertEquals("/api/v1/config", RemoteConfigFetcher.CONFIG_V1_PATH)
    }

    @Test
    fun `CONFIG_LEGACY_PATH is the expected value`() {
        assertEquals("/api/config", RemoteConfigFetcher.CONFIG_LEGACY_PATH)
    }
}

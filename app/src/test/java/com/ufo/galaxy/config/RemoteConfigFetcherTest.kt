package com.ufo.galaxy.config

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [RemoteConfigFetcher]:先打服务端真实存在的那条,404 才走第二跳。
 *
 * 这段说明本身被改过一次 —— 原文写的是"v1-first",而顺序在这一轮被换过来了。
 * 一份还在描述旧顺序的类注释比没有注释更糟:它会让下一个人按错误的前提去读用例。
 *
 * All HTTP calls are intercepted via a custom [OkHttpClient] interceptor to avoid
 * real network I/O.  Tests verify:
 *  - [RemoteConfigFetcher.fetchConfig] calls `GET /api/config` first(实测 V2 上存在的那条)。
 *  - [RemoteConfigFetcher.fetchConfig] falls back to `GET /api/v1/config` on 404(预留路径)。
 *  - [RemoteConfigFetcher.fetchConfig] does NOT fall back on non-404 HTTP errors.
 *  - Network exceptions are surfaced as `null` without a second attempt.
 *  - The returned [org.json.JSONObject] contains values from the server response.
 */
class RemoteConfigFetcherTest {

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * 按**首选 / 次选**分流,不再按 v1 / legacy。
     *
     * 顺序在这一轮被换过来了:首选是 `/api/config`(V2 上真实存在的那条),
     * 次选才是 `/api/v1/config`(服务端目前没有,留作将来)。helper 若还按
     * "含 /api/v1/ 就是第一跳"分流,每一条用例的含义都会静默地反过来 ——
     * 而它们仍然会有绿有红,看不出是参数错位。所以这里连命名一起改掉。
     */
    private fun routingClient(
        primaryCode: Int,
        secondaryCode: Int,
        primaryBody: String = "{}",
        secondaryBody: String = "{}"
    ): OkHttpClient {
        val interceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            val isPrimary = RemoteConfigFetcher.CONFIG_PRIMARY_PATH in url && "/api/v1/" !in url
            val (code, body) =
                if (isPrimary) Pair(primaryCode, primaryBody) else Pair(secondaryCode, secondaryBody)
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
        RemoteConfigFetcher(restBaseUrl = "http://gw.example.com:9000", httpClient = client)

    private val sampleConfig = """{"ws_base":"ws://gw.example.com:9000","feature_flags":{"beta":true}}"""

    // ── fetchConfig v1-first ───────────────────────────────────────────────

    @Test
    fun `fetchConfig returns JSONObject when the primary endpoint returns 200`() = runBlocking {
        val f = fetcher(routingClient(primaryCode = 200, secondaryCode = 500, primaryBody = sampleConfig))
        val result = f.fetchConfig()
        assertNotNull("fetchConfig must return non-null on primary 200", result)
        assertEquals("ws://gw.example.com:9000", result!!.optString("ws_base"))
    }

    @Test
    fun `fetchConfig hits the endpoint that exists first`() = runBlocking {
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
            restBaseUrl = "http://gw.example.com:9000",
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
        f.fetchConfig()
        // 第一跳必须是**服务端真实存在的那条**。此前这里钉的是 /api/v1/config,
        // 而 V2 上那条根本不存在(实测 404)—— 于是这条测试恒绿地把一个必然 404
        // 的请求锁成了"正确行为"。测试能证明代码按预期跑,证明不了预期本身对不对。
        assertTrue(
            "First request must target the endpoint that actually exists (${RemoteConfigFetcher.CONFIG_PRIMARY_PATH})",
            urls[0].contains(RemoteConfigFetcher.CONFIG_PRIMARY_PATH)
        )
    }

    // ── 404 fallback ───────────────────────────────────────────────────────

    /** 首选 404 时才去试预留的 v1 路径 —— 服务端补上那条之后这里会自动切过去。 */
    @Test
    fun `fetchConfig falls through to the reserved v1 path when the primary returns 404`() = runBlocking {
        val f = fetcher(
            routingClient(primaryCode = 404, secondaryCode = 200, secondaryBody = sampleConfig)
        )
        val result = f.fetchConfig()
        assertNotNull("fetchConfig must return config from the reserved path after a primary 404", result)
        assertEquals("ws://gw.example.com:9000", result!!.optString("ws_base"))
    }

    @Test
    fun `fetchConfig returns null when both endpoints return 404`() = runBlocking {
        val f = fetcher(routingClient(primaryCode = 404, secondaryCode = 404))
        assertNull("fetchConfig must return null when both endpoints return 404", f.fetchConfig())
    }

    @Test
    fun `fetchConfig returns null when primary 404s and the reserved path is non-2xx`() = runBlocking {
        val f = fetcher(routingClient(primaryCode = 404, secondaryCode = 500))
        assertNull("fetchConfig must return null when the second hop also fails", f.fetchConfig())
    }

    // ── No fallback on non-404 errors ──────────────────────────────────────

    @Test
    fun `fetchConfig does NOT fall back to legacy when v1 returns 500`() = runBlocking {
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
            restBaseUrl = "http://gw.example.com:9000",
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
        val result = f.fetchConfig()
        assertNull("fetchConfig must return null on v1 500 without fallback", result)
        assertEquals("Only one request must be made (no fallback on 500)", 1, urls.size)
        // 这里此前钉的是 "含 /api/v1/",那是顺序换过来之前的写法。首选路径改成
        // /api/config 之后它就恒假了 —— 而这条用例真正要验的是"500 不触发第二跳"
        // (上面那句 urls.size == 1),路径只是用来确认第一跳打对了地方。
        assertTrue(
            "Request must target the primary path (${RemoteConfigFetcher.CONFIG_PRIMARY_PATH})",
            urls[0].contains(RemoteConfigFetcher.CONFIG_PRIMARY_PATH)
        )
    }

    @Test
    fun `fetchConfig does NOT fall back to legacy when v1 returns 401`() = runBlocking {
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
            restBaseUrl = "http://gw.example.com:9000",
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
        val result = f.fetchConfig()
        assertNull(result)
        assertEquals("Only one request must be made (no fallback on 401)", 1, urls.size)
    }

    // ── Network errors ─────────────────────────────────────────────────────

    @Test
    fun `fetchConfig returns null on network exception`() = runBlocking {
        val f = fetcher(errorClient())
        assertNull("fetchConfig must return null on network exception", f.fetchConfig())
    }

    // ── Constant values ────────────────────────────────────────────────────

    @Test
    fun `CONFIG_PRIMARY_PATH points at the endpoint that exists on V2`() {
        assertEquals("/api/config", RemoteConfigFetcher.CONFIG_PRIMARY_PATH)
    }

    @Test
    fun `CONFIG_FUTURE_PATH is the reserved v1 path`() {
        assertEquals("/api/v1/config", RemoteConfigFetcher.CONFIG_FUTURE_PATH)
    }

    /**
     * /api/v1/config/status **不是**配置值,是配置管理器的运行状态。
     * 名字像得足以让人顺手拿它顶替,所以在这里钉一条:谁都不许把它当配置端点。
     */
    @Test
    fun `config manager status path is never used as the config endpoint`() {
        assertTrue(RemoteConfigFetcher.CONFIG_PRIMARY_PATH != "/api/v1/config/status")
        assertTrue(RemoteConfigFetcher.CONFIG_FUTURE_PATH != "/api/v1/config/status")
    }
}

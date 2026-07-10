package com.ufo.galaxy.api

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [GalaxyApiClient] v1-first with 404 fallback behavior.
 *
 * All HTTP calls are intercepted via a custom [OkHttpClient] interceptor to avoid
 * real network I/O.  Tests verify:
 *  - [GalaxyApiClient.registerDevice] calls `/api/v1/devices/register` first.
 *  - [GalaxyApiClient.registerDevice] falls back to `/api/devices/register` on 404.
 *  - [GalaxyApiClient.registerDevice] does NOT fall back on non-404 errors.
 *  - [GalaxyApiClient.sendHeartbeat] calls `/api/v1/devices/heartbeat` first.
 *  - [GalaxyApiClient.sendHeartbeat] falls back to `/api/devices/heartbeat` on 404.
 *  - [GalaxyApiClient.sendHeartbeat] does NOT fall back on non-404 errors.
 *  - Network exceptions are surfaced as [Result.failure] without a second attempt.
 */
class GalaxyApiClientTest {

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Builds an [OkHttpClient] that returns [v1Code] for paths containing
     * `/api/v1/` and [legacyCode] for all other paths.
     */
    private fun routingClient(
        v1Code: Int,
        legacyCode: Int,
        legacyBody: String = "{}",
        v1Body: String = "{}"
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

    private fun urlCapturingClient(capturedUrls: MutableList<String>, code: Int = 200): OkHttpClient {
        val interceptor = Interceptor { chain ->
            capturedUrls += chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    private fun client(client: OkHttpClient) =
        GalaxyApiClient(restBaseUrl = "http://gw.example.com:9000", httpClient = client)

    private fun sampleDevice() = JSONObject().apply {
        put("device_id", "test-device-1")
        put("device_type", "Android_Agent")
    }

    // ── registerDevice ─────────────────────────────────────────────────────

    @Test
    fun `registerDevice succeeds when v1 endpoint returns 200`() {
        val apiClient = client(routingClient(v1Code = 200, legacyCode = 500))
        val result = apiClient.registerDevice(sampleDevice())
        assertTrue("registerDevice must succeed on v1 200", result.isSuccess)
    }

    @Test
    fun `registerDevice falls back to legacy when v1 returns 404`() {
        val apiClient = client(routingClient(v1Code = 404, legacyCode = 200))
        val result = apiClient.registerDevice(sampleDevice())
        assertTrue("registerDevice must succeed via legacy after v1 404", result.isSuccess)
    }

    @Test
    fun `registerDevice returns failure when both v1 and legacy return non-2xx`() {
        val apiClient = client(routingClient(v1Code = 404, legacyCode = 500))
        val result = apiClient.registerDevice(sampleDevice())
        assertFalse("registerDevice must fail when legacy also fails", result.isSuccess)
    }

    @Test
    fun `registerDevice does NOT fall back to legacy when v1 returns 500`() {
        val urls = mutableListOf<String>()
        // v1 returns 500; any call to legacy would also be captured
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
        val apiClient = GalaxyApiClient(
            restBaseUrl = "http://gw.example.com:9000",
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
        val result = apiClient.registerDevice(sampleDevice())
        assertFalse(result.isSuccess)
        assertEquals("Only one request must be made (no fallback on 500)", 1, urls.size)
        assertTrue("Request must target v1 path", urls[0].contains("/api/v1/"))
    }

    @Test
    fun `registerDevice returns failure on network exception without fallback`() {
        val apiClient = client(errorClient())
        val result = apiClient.registerDevice(sampleDevice())
        assertFalse("registerDevice must return failure on network exception", result.isSuccess)
    }

    @Test
    fun `registerDevice hits v1 path first`() {
        val urls = mutableListOf<String>()
        val apiClient = GalaxyApiClient(
            restBaseUrl = "http://gw.example.com:9000",
            httpClient = urlCapturingClient(urls, code = 200)
        )
        apiClient.registerDevice(sampleDevice())
        assertTrue("First request must target v1 path", urls[0].contains("/api/v1/devices/register"))
    }

    // ── sendHeartbeat ──────────────────────────────────────────────────────

    @Test
    fun `sendHeartbeat succeeds when v1 endpoint returns 200`() {
        val apiClient = client(routingClient(v1Code = 200, legacyCode = 500))
        val result = apiClient.sendHeartbeat("device-42")
        assertTrue("sendHeartbeat must succeed on v1 200", result.isSuccess)
    }

    @Test
    fun `sendHeartbeat falls back to legacy when v1 returns 404`() {
        val apiClient = client(routingClient(v1Code = 404, legacyCode = 200))
        val result = apiClient.sendHeartbeat("device-42")
        assertTrue("sendHeartbeat must succeed via legacy after v1 404", result.isSuccess)
    }

    @Test
    fun `sendHeartbeat returns failure when both v1 and legacy return non-2xx`() {
        val apiClient = client(routingClient(v1Code = 404, legacyCode = 503))
        val result = apiClient.sendHeartbeat("device-42")
        assertFalse("sendHeartbeat must fail when legacy also fails", result.isSuccess)
    }

    @Test
    fun `sendHeartbeat does NOT fall back to legacy when v1 returns 500`() {
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
        val apiClient = GalaxyApiClient(
            restBaseUrl = "http://gw.example.com:9000",
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
        val result = apiClient.sendHeartbeat("device-42")
        assertFalse(result.isSuccess)
        assertEquals("Only one request must be made (no fallback on 500)", 1, urls.size)
        assertTrue("Request must target v1 path", urls[0].contains("/api/v1/"))
    }

    @Test
    fun `sendHeartbeat returns failure on network exception`() {
        val apiClient = client(errorClient())
        val result = apiClient.sendHeartbeat("device-42")
        assertFalse("sendHeartbeat must return failure on network exception", result.isSuccess)
    }

    @Test
    fun `sendHeartbeat hits v1 path first`() {
        val urls = mutableListOf<String>()
        val apiClient = GalaxyApiClient(
            restBaseUrl = "http://gw.example.com:9000",
            httpClient = urlCapturingClient(urls, code = 200)
        )
        apiClient.sendHeartbeat("device-42")
        assertTrue("First request must target v1 path", urls[0].contains("/api/v1/devices/heartbeat"))
    }

    // ── reconcileSession ───────────────────────────────────────────────────

    /** Interceptor that captures both URL and request body for assertions. */
    private fun capturingClient(
        capturedUrls: MutableList<String>,
        capturedBodies: MutableList<String>,
        code: Int = 200
    ): OkHttpClient {
        val interceptor = Interceptor { chain ->
            capturedUrls += chain.request().url.toString()
            val buffer = okio.Buffer()
            chain.request().body?.writeTo(buffer)
            capturedBodies += buffer.readUtf8()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body("{\"success\": true}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    @Test
    fun `reconcileSession posts to v1 reconcile endpoint with local session id`() {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val apiClient = GalaxyApiClient(
            restBaseUrl = "http://gw.example.com:9000",
            httpClient = capturingClient(urls, bodies)
        )
        val result = apiClient.reconcileSession(
            localSessionId = "phone_local_1",
            canonicalSessionId = "desk_main",
            userId = "u1",
            deviceId = "phone"
        )
        assertTrue("reconcile must succeed on 200", result.isSuccess)
        assertEquals(1, urls.size)
        assertTrue(urls[0].endsWith("/api/v1/sessions/reconcile"))
        val body = JSONObject(bodies[0])
        assertEquals("phone_local_1", body.getString("local_session_id"))
        assertEquals("desk_main", body.getString("canonical_session_id"))
        assertTrue(body.getBoolean("merge_history"))
    }

    @Test
    fun `reconcileSession returns failure on network exception`() {
        val apiClient = client(errorClient())
        val result = apiClient.reconcileSession(localSessionId = "x")
        assertFalse(result.isSuccess)
    }

    @Test
    fun `reconcileSession does NOT fall back on 404 (v1-only endpoint)`() {
        val urls = mutableListOf<String>()
        val apiClient = GalaxyApiClient(
            restBaseUrl = "http://gw.example.com:9000",
            httpClient = urlCapturingClient(urls, code = 404)
        )
        val result = apiClient.reconcileSession(localSessionId = "x")
        assertFalse("404 is a real failure for the v1-only endpoint", result.isSuccess)
        assertEquals("Only one request — no legacy fallback", 1, urls.size)
    }

    // ── ingestConversationTurns ─────────────────────────────────────────────

    @Test
    fun `ingestConversationTurns posts turns array to v1 endpoint`() {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val apiClient = GalaxyApiClient(
            restBaseUrl = "http://gw.example.com:9000",
            httpClient = capturingClient(urls, bodies)
        )
        val result = apiClient.ingestConversationTurns(
            sessionId = "phone_offline",
            turns = listOf(
                GalaxyApiClient.ConversationTurn("user", "离线问题", tsMs = 1000L),
                GalaxyApiClient.ConversationTurn("assistant", "离线回答")
            ),
            userId = "u3",
            deviceId = "phone"
        )
        assertTrue(result.isSuccess)
        assertEquals(1, urls.size)
        assertTrue(urls[0].endsWith("/api/v1/sessions/ingest_turns"))
        val body = JSONObject(bodies[0])
        assertEquals("phone_offline", body.getString("session_id"))
        val turns = body.getJSONArray("turns")
        assertEquals(2, turns.length())
        assertEquals("user", turns.getJSONObject(0).getString("role"))
        assertEquals(1.0, turns.getJSONObject(0).getDouble("ts"), 0.0001)
        // 无 ts 的轮次不带 ts 字段
        assertFalse(turns.getJSONObject(1).has("ts"))
    }

    @Test
    fun `ingestConversationTurns handles empty list`() {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val apiClient = GalaxyApiClient(
            restBaseUrl = "http://gw.example.com:9000",
            httpClient = capturingClient(urls, bodies)
        )
        val result = apiClient.ingestConversationTurns(sessionId = "s", turns = emptyList())
        assertTrue(result.isSuccess)
        assertEquals(0, JSONObject(bodies[0]).getJSONArray("turns").length())
    }
}

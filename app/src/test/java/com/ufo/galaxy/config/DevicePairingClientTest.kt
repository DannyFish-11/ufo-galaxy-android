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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [DevicePairingClient] —— 设备半边配对流程(enroll → poll status → claim)。
 *
 * 全程 HTTP 由自定义 [OkHttpClient] 拦截器伪造,不触真网络。覆盖:
 *  - [DevicePairingClient.enroll] 命中 `/api/v1/pairing/enroll` 并解析 request_id。
 *  - [DevicePairingClient.status] / [DevicePairingClient.claim] 的正常与失败解析。
 *  - [DevicePairingClient.pairAndClaim] 端到端:pending→approved→claim 成功;denied/expired/超时;
 *    enroll 失败早退;claim 失败带原因。
 *  - onWaiting 回调在 pending 时被触发。
 */
class DevicePairingClientTest {

    private val JSON = "application/json".toMediaType()

    private fun resp(request: okhttp3.Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody(JSON))
            .build()

    /** 用一个按路径路由的拦截器构造客户端。[route] 返回 (code, body)。 */
    private fun client(route: (String) -> Pair<Int, String>): OkHttpClient {
        val interceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            val (code, body) = route(url)
            resp(chain.request(), code, body)
        }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    private fun pairing(client: OkHttpClient) =
        DevicePairingClient(restBaseUrl = "http://gw.example.com:9000", httpClient = client)

    // ── enroll ────────────────────────────────────────────────────────────────

    @Test
    fun `enroll returns request_id on ok`() = runBlocking {
        val c = client { _ -> 200 to """{"ok":true,"request_id":"rid-123"}""" }
        assertEquals("rid-123", pairing(c).enroll("dev-A", "android", "Phone"))
    }

    @Test
    fun `enroll returns null when server rejects`() = runBlocking {
        val c = client { _ -> 400 to """{"ok":false,"error":"invalid enrollment request (device_id required)"}""" }
        assertNull(pairing(c).enroll("dev-A", null, null))
    }

    @Test
    fun `enroll hits the enroll path`() = runBlocking {
        val urls = mutableListOf<String>()
        val interceptor = Interceptor { chain ->
            urls += chain.request().url.toString()
            resp(chain.request(), 200, """{"ok":true,"request_id":"rid"}""")
        }
        val c = OkHttpClient.Builder().addInterceptor(interceptor).build()
        pairing(c).enroll("dev-A", "android", "Phone")
        assertTrue("enroll must target /api/v1/pairing/enroll", urls[0].contains(DevicePairingClient.PATH_ENROLL))
    }

    // ── status / claim ─────────────────────────────────────────────────────────

    @Test
    fun `status parses status field`() = runBlocking {
        val c = client { _ -> 200 to """{"ok":true,"status":"pending"}""" }
        assertEquals("pending", pairing(c).status("rid"))
    }

    @Test
    fun `status returns null when not ok`() = runBlocking {
        val c = client { _ -> 404 to """{"ok":false}""" }
        assertNull(pairing(c).status("rid"))
    }

    @Test
    fun `claim returns token on ok`() = runBlocking {
        val c = client { _ -> 200 to """{"ok":true,"token":"tok-abc"}""" }
        assertEquals("tok-abc", pairing(c).claim("rid"))
    }

    @Test
    fun `claim returns null when not ok`() = runBlocking {
        val c = client { _ -> 409 to """{"ok":false,"error":"already claimed"}""" }
        assertNull(pairing(c).claim("rid"))
    }

    // ── pairAndClaim end-to-end ──────────────────────────────────────────────────

    @Test
    fun `pairAndClaim succeeds after pending then approved`() = runBlocking {
        // 第一次 status 返回 pending,第二次返回 approved;claim 交出 token。
        val statusCalls = AtomicInteger(0)
        val waits = mutableListOf<String>()
        val c = client { url ->
            when {
                url.contains("/pairing/enroll") -> 200 to """{"ok":true,"request_id":"rid-9"}"""
                url.contains("/pairing/status/") ->
                    if (statusCalls.getAndIncrement() == 0) 200 to """{"ok":true,"status":"pending"}"""
                    else 200 to """{"ok":true,"status":"approved"}"""
                url.contains("/pairing/claim/") -> 200 to """{"ok":true,"token":"TOK"}"""
                else -> 404 to "{}"
            }
        }
        val result = pairing(c).pairAndClaim(
            deviceId = "dev-A", deviceType = "android", name = "Phone",
            timeoutMs = 10_000L, pollIntervalMs = 1L, onWaiting = { waits += it }
        )
        assertTrue("pairAndClaim must succeed", result.ok)
        assertEquals("TOK", result.token)
        assertEquals("approved", result.status)
        assertTrue("onWaiting must fire while pending", waits.contains("pending"))
    }

    @Test
    fun `pairAndClaim fails fast when enroll fails`() = runBlocking {
        val c = client { _ -> 500 to """{"ok":false}""" }
        val result = pairing(c).pairAndClaim("dev-A", null, null, timeoutMs = 10_000L, pollIntervalMs = 1L)
        assertFalse(result.ok)
        assertEquals("enroll_failed", result.error)
    }

    @Test
    fun `pairAndClaim reports denied`() = runBlocking {
        val c = client { url ->
            when {
                url.contains("/pairing/enroll") -> 200 to """{"ok":true,"request_id":"rid"}"""
                url.contains("/pairing/status/") -> 200 to """{"ok":true,"status":"denied"}"""
                else -> 404 to "{}"
            }
        }
        val result = pairing(c).pairAndClaim("dev-A", null, null, timeoutMs = 10_000L, pollIntervalMs = 1L)
        assertFalse(result.ok)
        assertEquals("denied", result.status)
        assertEquals("denied_by_owner", result.error)
    }

    @Test
    fun `pairAndClaim reports expired`() = runBlocking {
        val c = client { url ->
            when {
                url.contains("/pairing/enroll") -> 200 to """{"ok":true,"request_id":"rid"}"""
                url.contains("/pairing/status/") -> 200 to """{"ok":true,"status":"expired"}"""
                else -> 404 to "{}"
            }
        }
        val result = pairing(c).pairAndClaim("dev-A", null, null, timeoutMs = 10_000L, pollIntervalMs = 1L)
        assertFalse(result.ok)
        assertEquals("expired", result.status)
        assertEquals("request_expired", result.error)
    }

    @Test
    fun `pairAndClaim reports claim_failed when approved but claim empty`() = runBlocking {
        val c = client { url ->
            when {
                url.contains("/pairing/enroll") -> 200 to """{"ok":true,"request_id":"rid"}"""
                url.contains("/pairing/status/") -> 200 to """{"ok":true,"status":"approved"}"""
                url.contains("/pairing/claim/") -> 200 to """{"ok":false}"""
                else -> 404 to "{}"
            }
        }
        val result = pairing(c).pairAndClaim("dev-A", null, null, timeoutMs = 10_000L, pollIntervalMs = 1L)
        assertFalse(result.ok)
        assertEquals("approved", result.status)
        assertEquals("claim_failed", result.error)
    }

    @Test
    fun `pairAndClaim times out while still pending`() = runBlocking {
        val c = client { url ->
            when {
                url.contains("/pairing/enroll") -> 200 to """{"ok":true,"request_id":"rid"}"""
                url.contains("/pairing/status/") -> 200 to """{"ok":true,"status":"pending"}"""
                else -> 404 to "{}"
            }
        }
        // timeout 极短 → 首轮 pending 后即越过 deadline。
        val result = pairing(c).pairAndClaim("dev-A", null, null, timeoutMs = 1L, pollIntervalMs = 1L)
        assertFalse(result.ok)
        assertEquals("timeout", result.status)
        assertEquals("approval_timeout", result.error)
    }

    @Test
    fun `PATH_ENROLL constant is the expected value`() {
        assertEquals("/api/v1/pairing/enroll", DevicePairingClient.PATH_ENROLL)
    }
}

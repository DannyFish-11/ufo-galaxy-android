package com.ufo.galaxy.config

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DevicePairingClient 契约测试 —— 三仓统一到 `/api/v1/pair/claim` 之后的形状。
 *
 * 换掉了什么
 * ==========
 * 此前打的是 `/api/v1/pairing/enroll` → 轮询 `status` → `claim/{request_id}`
 * 这条三段式。手表那侧又是 OAuth device flow。三种设备三条路，凭证形态与失败模式
 * 各不相同，而它们要接的是同一台机器。
 *
 * 这里钉住的
 * ==========
 * 1. 打的是 `/api/v1/pair/claim`，一次 POST，没有轮询；
 * 2. 请求里带**本机自己的** device_id —— 令牌签给它，签错了就是"配得上、连不了"；
 * 3. 候选路径解出来并按 priority 排序 —— 丢了就只剩内网地址，换个网就连不上；
 * 4. 失败原因分档：可重输 / 要等 / 网络问题，用户下一步要做的事完全不同。
 */
class DevicePairingClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = DevicePairingClient(restBaseUrl = server.url("/").toString())

    private fun okBody(
        token: String = "v1.tok",
        candidates: String = """[
            {"kind":"lan","url":"ws://192.168.1.5:9000/ws/device/desk","priority":1},
            {"kind":"funnel","url":"wss://box.ts.net/ws/device/desk","priority":2}
        ]""",
    ) = """{
        "success": true,
        "peer": {"device_id": "phone-1", "trust": "friend"},
        "capability_token": "$token",
        "token_scopes": ["device:status", "device:tap"],
        "token_issued": true,
        "endpoints": {"websocket": "ws://192.168.1.5:9000/ws/device/desk"},
        "candidates": $candidates,
        "gateway_device_id": "desk"
    }"""

    // ── 一、打对端点、带对身份 ───────────────────────────────────────────────

    @Test
    fun `claim hits the canonical pair claim endpoint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(okBody()))

        client().claim(deviceId = "phone-1", deviceName = "小米17", code = "HQ25G6")

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(
            "必须打 /api/v1/pair/claim，实际是 ${req.path}",
            req.path!!.contains(DevicePairingClient.PATH_CLAIM)
        )
        assertEquals("/api/v1/pair/claim", DevicePairingClient.PATH_CLAIM)
    }

    @Test
    fun `request carries this device's own identity`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(okBody()))

        client().claim(deviceId = "phone-1", deviceName = "小米17", code = "hq25g6")

        val body = JSONObject(server.takeRequest().body.readUtf8())
        // 令牌签给这个 id。填错 = 服务端签出的 subject 与握手时自报的 id 对不上 = 连不上。
        assertEquals("phone-1", body.getString("device_id"))
        assertEquals("小米17", body.getString("name"))
        assertEquals("android", body.getString("device_type"))
        // 短码归一成大写再发 —— 服务端也做归一，两边一致，用户小写输入也能用
        assertEquals("HQ25G6", body.getString("code"))
    }

    @Test
    fun `link is sent verbatim when scanning a qr code`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(okBody()))

        client().claim(deviceId = "phone-1", deviceName = null, link = "galaxy://pair?c=AA&s=BB")

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("galaxy://pair?c=AA&s=BB", body.getString("link"))
        assertFalse("扫码时不该再塞一个 code", body.has("code"))
    }

    // ── 二、候选路径必须解出来 ───────────────────────────────────────────────

    @Test
    fun `candidates are parsed and sorted by priority`() = runBlocking {
        // 故意乱序返回：排序必须由客户端保证，不能指望服务端一定有序
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                okBody(
                    candidates = """[
                        {"kind":"funnel","url":"wss://box.ts.net/ws/device/desk","priority":3},
                        {"kind":"lan","url":"ws://192.168.1.5:9000/ws/device/desk","priority":1},
                        {"kind":"tailscale","url":"wss://100.9.9.9:9000/ws/device/desk","priority":2}
                    ]"""
                )
            )
        )

        val r = client().claim(deviceId = "phone-1", deviceName = null, code = "HQ25G6")

        assertTrue(r.ok)
        assertEquals(listOf("lan", "tailscale", "funnel"), r.candidates.map { it.kind })
        assertEquals("desk", r.gatewayDeviceId)
        assertEquals(listOf("device:status", "device:tap"), r.scopes)
    }

    @Test
    fun `a candidate without priority is kept, not dropped`() = runBlocking {
        // 丢掉 = 少一条可达路径，而那正是这个字段存在的理由。
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                okBody(candidates = """[{"kind":"lan","url":"ws://a/ws/device/desk"}]""")
            )
        )

        val r = client().claim(deviceId = "phone-1", deviceName = null, code = "HQ25G6")

        assertEquals(1, r.candidates.size)
        assertEquals(1, r.candidates[0].priority)
    }

    @Test
    fun `missing candidates does not fail the pairing`() = runBlocking {
        // 老版本网关不给这个字段。配对本身仍应成功 —— 退回单地址逻辑，
        // 而不是让整台设备接不进来。
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"capability_token":"v1.tok","peer":{"device_id":"phone-1"}}"""
            )
        )

        val r = client().claim(deviceId = "phone-1", deviceName = null, code = "HQ25G6")

        assertTrue(r.ok)
        assertTrue(r.candidates.isEmpty())
    }

    // ── 三、失败原因要分得开 ─────────────────────────────────────────────────

    @Test
    fun `throttled is distinguished from a wrong code`() = runBlocking {
        // 429 = 猜错太多次，等一会儿就好；400 = 码本身不对，要重新拿一个。
        // 糊成一句"配对失败"的话，用户会一直重输一个其实没问题的码。
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setBody("""{"success":false,"error":"配对码错误次数过多,请稍后再试"}""")
        )

        val r = client().claim(deviceId = "phone-1", deviceName = null, code = "ZZZZZZ")

        assertFalse(r.ok)
        assertEquals("too_many_attempts", r.error)
        assertEquals("throttled", r.status)
    }

    @Test
    fun `server rejection carries the server's reason`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"success":false,"error":"配对码无效或已过期/已被使用"}""")
        )

        val r = client().claim(deviceId = "phone-1", deviceName = null, code = "ZZZZZZ")

        assertFalse(r.ok)
        assertEquals("配对码无效或已过期/已被使用", r.error)
    }

    @Test
    fun `success without a token is not treated as paired`() = runBlocking {
        // 服务端明确区分了"配对成功"与"令牌签发成功"。被拉黑的对端 scopes 为空，
        // 拿不到令牌 —— 此时当成配对完成，用户会以为好了，然后每次连接都被拒。
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"token_issued":false,"capability_token":null,
                    "peer":{"device_id":"phone-1","trust":"blocked"}}"""
            )
        )

        val r = client().claim(deviceId = "phone-1", deviceName = null, code = "HQ25G6")

        assertFalse(r.ok)
        assertEquals("no_token_issued", r.error)
        assertNull(r.token)
    }

    @Test
    fun `no code and no link is refused before any request`() = runBlocking {
        val r = client().claim(deviceId = "phone-1", deviceName = null)

        assertFalse(r.ok)
        assertEquals("need_code_or_link", r.error)
        // 没发出去任何请求 —— 明知会被拒还发一次，只会白白吃掉服务端的节流额度
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `blank device id is refused before any request`() = runBlocking {
        val r = client().claim(deviceId = "  ", deviceName = null, code = "HQ25G6")

        assertFalse(r.ok)
        assertEquals("missing_device_id", r.error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unparseable body does not crash`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))

        val r = client().claim(deviceId = "phone-1", deviceName = null, code = "HQ25G6")

        assertFalse(r.ok)
        assertTrue(r.error!!.startsWith("bad_response_"))
    }
}

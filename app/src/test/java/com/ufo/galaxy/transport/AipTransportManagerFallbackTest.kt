package com.ufo.galaxy.transport

import com.ufo.galaxy.network.GatewayClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 断网韧性回归钉：WS（指定传输 + 默认传输）都断、LAN TCP 直连活着时，
 * 消息必须走仍然连接的适配器，而不是「No available transport」直接失败。
 *
 * 这个缺陷是真实路径排查发现的：原实现的 fallback 只试 defaultTransport
 * （websocket），恰好击穿阶段 1 的核心场景 —— 中心断链时 LAN 通路白接。
 */
class AipTransportManagerFallbackTest {

    private class FakeClient(private var connected: Boolean) : GatewayClient {
        val sent = mutableListOf<String>()
        override fun isConnected(): Boolean = connected
        override fun sendJson(json: String): Boolean {
            sent.add(json)
            return true
        }
    }

    @Before
    fun fresh() {
        AipTransportManager.resetInstance()
    }

    @After
    fun cleanup() {
        AipTransportManager.resetInstance()
    }

    @Test
    fun `WS 断而 TCP 连着时消息走 TCP`() {
        val mgr = AipTransportManager.getInstance()
        val ws = FakeClient(connected = false)
        val tcp = FakeClient(connected = true)
        mgr.registerAdapter("websocket", ws)
        mgr.registerAdapter("tcp", tcp)

        val ok = mgr.sendJson("""{"type":"task_result","transport":"websocket","payload":{}}""")

        assertTrue("WS 断时应回退到仍连接的 TCP,而不是失败", ok)
        assertEquals(0, ws.sent.size)
        assertEquals(1, tcp.sent.size)
    }

    @Test
    fun `指定传输连着时不受影响`() {
        val mgr = AipTransportManager.getInstance()
        val ws = FakeClient(connected = true)
        val tcp = FakeClient(connected = true)
        mgr.registerAdapter("websocket", ws)
        mgr.registerAdapter("tcp", tcp)

        assertTrue(mgr.sendJson("""{"type":"heartbeat","transport":"websocket"}"""))
        assertEquals(1, ws.sent.size)
        assertEquals(0, tcp.sent.size)
    }

    @Test
    fun `全部都断时仍然如实失败`() {
        val mgr = AipTransportManager.getInstance()
        mgr.registerAdapter("websocket", FakeClient(connected = false))
        mgr.registerAdapter("tcp", FakeClient(connected = false))

        assertFalse(mgr.sendJson("""{"type":"heartbeat","transport":"websocket"}"""))
    }
}

package com.ufo.galaxy.transport

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * TcpDirectClient 对着**真实本地 ServerSocket** 的行为钉（不是 mock 传输）。
 *
 * 服务端侧解帧完全按 V2 `tcp_adapter._handle_incoming` 的规则
 * （4 字节大端长度 + UTF-8 JSON），因此这组测试同时钉住了互操作契约：
 * 这里能解出的帧，V2 网关就能解出。
 */
class TcpDirectClientTest {

    /** 模拟 V2 tcp 服务端：按 V2 规则解帧入队，可主动下发帧。 */
    private class FakeV2TcpServer : AutoCloseable {
        val server = ServerSocket(0)
        val received = LinkedBlockingQueue<String>()
        val connected = CountDownLatch(1)

        @Volatile
        var client: Socket? = null

        private val acceptThread = Thread {
            try {
                val s = server.accept()
                client = s
                connected.countDown()
                val input = DataInputStream(s.getInputStream())
                while (true) {
                    val json = TcpFrameCodec.readFrame(input) ?: break
                    received.put(json)
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        fun push(json: String) {
            val s = client ?: error("no client connected")
            val out = DataOutputStream(s.getOutputStream())
            out.write(TcpFrameCodec.encode(json))
            out.flush()
        }

        override fun close() {
            try {
                client?.close()
            } catch (_: Exception) {
            }
            server.close()
            acceptThread.interrupt()
        }
    }

    @Test
    fun `connect 即发 hello 心跳且携带 device_id —— V2 靠它登记 peer`() {
        FakeV2TcpServer().use { srv ->
            val client = TcpDirectClient(deviceId = "android_test_device")
            try {
                assertTrue(client.connect("127.0.0.1", srv.server.localPort))
                assertTrue(srv.connected.await(3, TimeUnit.SECONDS))
                val hello = srv.received.poll(3, TimeUnit.SECONDS)
                requireNotNull(hello) { "服务端没收到 hello 帧" }
                val obj = JSONObject(hello)
                assertEquals("heartbeat", obj.getString("type"))
                assertEquals("android_test_device", obj.getString("device_id"))
                assertTrue(client.isConnected())
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `sendJson 的帧原样到达服务端`() {
        FakeV2TcpServer().use { srv ->
            val client = TcpDirectClient(deviceId = "dev")
            try {
                assertTrue(client.connect("127.0.0.1", srv.server.localPort))
                srv.received.poll(3, TimeUnit.SECONDS) // 吃掉 hello
                val msg = """{"type":"task_result","payload":{"status":"success","备注":"多跳"}}"""
                assertTrue(client.sendJson(msg))
                assertEquals(msg, srv.received.poll(3, TimeUnit.SECONDS))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `服务端下发的帧进入 onMessage 回调`() {
        FakeV2TcpServer().use { srv ->
            val client = TcpDirectClient(deviceId = "dev")
            val inbound = LinkedBlockingQueue<String>()
            client.onMessage = { inbound.put(it) }
            try {
                assertTrue(client.connect("127.0.0.1", srv.server.localPort))
                assertTrue(srv.connected.await(3, TimeUnit.SECONDS))
                srv.received.poll(3, TimeUnit.SECONDS) // hello 已达，连接就绪
                srv.push("""{"type":"task_assign","payload":{"task_id":"t1"}}""")
                val got = inbound.poll(3, TimeUnit.SECONDS)
                requireNotNull(got) { "入站帧没有到达 onMessage" }
                assertEquals("task_assign", JSONObject(got).getString("type"))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `对端关闭后 isConnected 变 false 且 sendJson 如实失败并触发断开回调`() {
        FakeV2TcpServer().use { srv ->
            val client = TcpDirectClient(deviceId = "dev")
            val dropped = CountDownLatch(1)
            client.onDisconnected = { dropped.countDown() }
            try {
                assertTrue(client.connect("127.0.0.1", srv.server.localPort))
                assertTrue(srv.connected.await(3, TimeUnit.SECONDS))
                srv.client?.close()
                assertTrue("断开回调没有触发", dropped.await(3, TimeUnit.SECONDS))
                // 读线程收尾后连接态必须如实为断
                assertFalse(client.isConnected())
                assertFalse(client.sendJson("""{"type":"heartbeat"}"""))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `未连接时 sendJson 返回 false 而不是抛异常`() {
        val client = TcpDirectClient(deviceId = "dev")
        assertFalse(client.isConnected())
        assertFalse(client.sendJson("""{"type":"heartbeat"}"""))
    }

    @Test
    fun `连接不可达地址如实失败`() {
        val client = TcpDirectClient(deviceId = "dev")
        // 端口 1：几乎必然拒绝连接
        assertFalse(client.connect("127.0.0.1", 1))
        assertFalse(client.isConnected())
    }
}

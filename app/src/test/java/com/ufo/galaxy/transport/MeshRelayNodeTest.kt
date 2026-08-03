package com.ufo.galaxy.transport

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 手机中继节点回归钉：三个**真实监听端口**的 Kotlin 节点 A–B–C 强制邻接
 * （A 只认识 B），A 发给 C 必须经 B 真实中继（RREQ 现场发现 + 数据帧逐跳
 * 同步转发 + 端到端回执带完整路径）。
 *
 * 信封与 V2 core/node_communication.Message 对齐 —— V2 侧同款三节点测试是
 * test_mesh_routing_adapter.py::test_three_node_relay_end_to_end，两边互为镜像。
 */
class MeshRelayNodeTest {

    private class Node(val id: String) : AutoCloseable {
        val inbox = LinkedBlockingQueue<Pair<JSONObject, JSONObject>>()
        val plainInbox = LinkedBlockingQueue<String>()
        val relay = MeshRelayNode(nodeId = id) { aip, meta -> inbox.put(aip to meta) }
        val server = TcpDirectServer(onMessage = { plainInbox.put(it) }, meshRelay = relay)
        val port: Int = server.start(0)
        override fun close() = server.close()
    }

    private fun chain(): Triple<Node, Node, Node> {
        val a = Node("node_a")
        val b = Node("node_b")
        val c = Node("node_c")
        a.relay.addNeighbor("node_b", "127.0.0.1", b.port)
        b.relay.addNeighbor("node_a", "127.0.0.1", a.port)
        b.relay.addNeighbor("node_c", "127.0.0.1", c.port)
        c.relay.addNeighbor("node_b", "127.0.0.1", b.port)
        return Triple(a, b, c)
    }

    @Test
    fun `三节点链 A 经 B 中继到 C —— RREQ 发现 + 逐跳转发 + 端到端回执`() {
        val (a, b, c) = chain()
        try {
            val result = a.relay.send(JSONObject().put("type", "task_assign").put("probe", 1), "node_c")
            assertTrue("A→C 多跳失败: $result", result.optBoolean("success"))
            val (aip, meta) = c.inbox.poll(5, TimeUnit.SECONDS) ?: error("C 没收到")
            assertEquals("task_assign", aip.optString("type"))
            assertEquals("node_a", meta.optString("src"))
            val path = meta.optJSONArray("path")!!
            assertEquals(listOf("node_a", "node_b", "node_c"), (0 until path.length()).map { path.optString(it) })
        } finally {
            a.close(); b.close(); c.close()
        }
    }

    @Test
    fun `直连邻居单跳可达`() {
        val (a, b, c) = chain()
        try {
            val result = a.relay.send(JSONObject().put("type", "heartbeat"), "node_b")
            assertTrue(result.optBoolean("success"))
            assertTrue(b.inbox.poll(3, TimeUnit.SECONDS) != null)
        } finally {
            a.close(); b.close(); c.close()
        }
    }

    @Test
    fun `无路可达时如实失败`() {
        val (a, b, c) = chain()
        try {
            val result = a.relay.send(JSONObject().put("type", "heartbeat"), "node_ghost", discoveryTimeoutMs = 500)
            assertFalse(result.optBoolean("success"))
            assertTrue(result.optString("error").contains("no route"))
        } finally {
            a.close(); b.close(); c.close()
        }
    }

    @Test
    fun `同端口分流 —— 普通 AIP 帧走消息路由而不是 mesh`() {
        val (a, b, c) = chain()
        try {
            // 直接给 B 的端口发一条普通帧(无 message_type)
            val client = TcpDirectClient(deviceId = "probe")
            assertTrue(client.connect("127.0.0.1", b.port))
            assertTrue(client.sendJson("""{"type":"task_assign","payload":{"task_id":"t1"}}"""))
            val plain = b.plainInbox.poll(3, TimeUnit.SECONDS)
            // hello 心跳 + task_assign 都应从普通路径进来
            var sawTask = plain?.contains("task_assign") == true
            if (!sawTask) {
                val second = b.plainInbox.poll(3, TimeUnit.SECONDS)
                sawTask = second?.contains("task_assign") == true
            }
            assertTrue("普通帧没有走消息路由", sawTask)
            assertTrue("普通帧误入 mesh 投递", b.inbox.isEmpty())
            client.close()
        } finally {
            a.close(); b.close(); c.close()
        }
    }
}

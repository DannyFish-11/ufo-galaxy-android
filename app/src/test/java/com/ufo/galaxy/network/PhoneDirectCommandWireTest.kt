package com.ufo.galaxy.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ufo.galaxy.phone.PhoneActionCatalog
import java.lang.reflect.Method
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中心经 `devices__invoke` 让手机直接做一件事,在连接这一层的样子:
 *
 * - V2 发的 `command`(顶层 command_id + command + params)交给 [GalaxyWebSocketClient.Listener.onDeviceCommand],
 *   **不再**被 legacy 归一改成 task_assign、拿去空跑本地循环(那样中心干等 30 秒报超时);
 * - 旧式 `command`(payload 里带目标、没有 command_id)照旧走 task_assign;
 * - 能力上报只报 [GalaxyWebSocketClient.phoneActions] 给的动作 —— 没装执行的时候一个都不报;
 * - 权限变了可以只重发能力上报,不重发登记。
 */
class PhoneDirectCommandWireTest {

    private val processMessage: Method = GalaxyWebSocketClient::class.java
        .getDeclaredMethod("processMessage", String::class.java)
        .also { it.isAccessible = true }

    private class Captured : GalaxyWebSocketClient.Listener {
        val commands = mutableListOf<Triple<String, String, String>>()
        val taskAssigns = mutableListOf<String>()
        override fun onConnected() = Unit
        override fun onDisconnected() = Unit
        override fun onMessage(message: String) = Unit
        override fun onError(error: String) = Unit
        override fun onTaskAssign(taskId: String, taskAssignPayloadJson: String, traceId: String?) {
            taskAssigns += taskId
        }
        override fun onDeviceCommand(commandId: String, action: String, paramsJson: String) {
            commands += Triple(commandId, action, paramsJson)
        }
    }

    private class RecordingWebSocket : WebSocket {
        val sent = mutableListOf<String>()
        override fun request(): Request = Request.Builder().url("ws://localhost:9999").build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = sent.add(text)
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }

    private fun client() = GalaxyWebSocketClient(
        serverUrl = "ws://localhost:9999",
        crossDeviceEnabled = true,
        offlineQueue = OfflineTaskQueue(prefs = null),
    )

    private fun frames(socket: RecordingWebSocket, type: String): List<JsonObject> =
        socket.sent.map { JsonParser.parseString(it).asJsonObject }.filter { it.get("type")?.asString == type }

    @Test
    fun `a direct command from the center reaches the command listener and is not run as a goal`() {
        val c = client()
        val cap = Captured().also { c.addListener(it) }
        processMessage.invoke(
            c,
            """{"type":"command","command_id":"cmd_9","command":"location","params":{"x":1},"timestamp":"2026-10-25T09:00:00Z"}""",
        )
        assertEquals(1, cap.commands.size)
        assertEquals("cmd_9", cap.commands[0].first)
        assertEquals("location", cap.commands[0].second)
        assertEquals(1, JsonParser.parseString(cap.commands[0].third).asJsonObject["x"].asInt)
        assertTrue("不能再被当成目标交给本地循环", cap.taskAssigns.isEmpty())
    }

    @Test
    fun `an old goal-carrying command still goes to task_assign`() {
        val c = client()
        val cap = Captured().also { c.addListener(it) }
        processMessage.invoke(c, """{"type":"command","payload":{"task_id":"t-1","goal":"打开设置"}}""")
        assertEquals(listOf("t-1"), cap.taskAssigns)
        assertTrue(cap.commands.isEmpty())
    }

    @Test
    fun `without the phone's executor no direct action is reported`() {
        val c = client()
        val socket = RecordingWebSocket()
        c.installWebSocketForTest(socket)
        c.simulateCanonicalReconnectOpenForTest()
        val report = frames(socket, "capability_report").single()
        val actions = report.getAsJsonArray("supported_actions").map { it.asString }
        PhoneActionCatalog.ACTIONS.forEach { assertFalse("没人接就不报 ${it.name}", it.name in actions) }
        listOf("camera", "voice_input", "automation").forEach { assertFalse(it in actions) }
    }

    @Test
    fun `the report carries exactly the actions the phone can do with their descriptions`() {
        val c = client()
        c.phoneActions = { listOf("location", "sms") }
        val socket = RecordingWebSocket()
        c.installWebSocketForTest(socket)
        c.simulateCanonicalReconnectOpenForTest()
        val report = frames(socket, "capability_report").single()
        assertEquals(listOf("location", "sms"), report.getAsJsonArray("supported_actions").map { it.asString })
        val said = report.getAsJsonObject("action_descriptions")
        assertEquals(PhoneActionCatalog.BY_NAME.getValue("sms").description, said["sms"].asString)
        // 中心按设备自己的声明决定推不推请求进度,不看设备类型
        assertTrue("手机会显示自己那件请求的进度", report["shows_request_progress"].asBoolean)
        assertFalse("手机没有显示提问的界面,不声明能回答提问", report.has("answers_decisions"))
    }

    @Test
    fun `a permission change resends only the capability report`() {
        val c = client()
        var actions = listOf("sensor_data")
        c.phoneActions = { actions }
        val socket = RecordingWebSocket()
        c.refreshCapabilityReport()
        assertTrue("没连着就不发", socket.sent.isEmpty())

        c.installWebSocketForTest(socket)
        c.simulateCanonicalReconnectOpenForTest()
        socket.sent.clear()
        actions = listOf("sensor_data", "contacts")
        c.refreshCapabilityReport()
        assertTrue("不重发登记", frames(socket, "device_register").isEmpty())
        val report = frames(socket, "capability_report").single()
        assertEquals(listOf("sensor_data", "contacts"), report.getAsJsonArray("supported_actions").map { it.asString })
    }
}

package com.ufo.galaxy.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 手机回答中心智能体的提问:读 `decision_request` / `decision_withdraw`,人点的选项以 `human_input` 回去。
 * 帧形状与手表一致(V2 `request_human_decision` 发、`websocket_handler` 的 human_input 分支收)。
 */
class DecisionFramesTest {

    private val request = """
        {"type":"decision_request","payload":{"decision_id":"d-1","title":"要让「Pixel 9」app_control(app=微信)吗?",
         "summary":"这会改设备状态。","options":[{"id":"deny","label":"不要"},{"id":"once","label":"仅此次"},
         {"id":"session","label":"本会话"},{"id":"always","label":"永久允许"}],"urgency":"normal","timeout_ms":90000}}
    """.trimIndent()

    @Test
    fun `a decision request is read with its options in order`() {
        val d = DecisionFrames.parseRequest(request)!!
        assertEquals("d-1", d.decisionId)
        assertTrue(d.title.contains("app_control"))
        assertEquals(listOf("deny", "once", "session", "always"), d.options.map { it.id })
        assertEquals("永久允许", d.options.last().label)
        assertEquals(90000L, d.timeoutMs)
        assertNull("没给 timeout_ms 就是 null", DecisionFrames.parseRequest("""{"type":"decision_request","payload":{"decision_id":"d-9"}}""")!!.timeoutMs)
    }

    @Test
    fun `more options than the notification can hold go to the dialog, not half onto buttons`() {
        assertTrue(DecisionFrames.buttons(DecisionFrames.parseRequest(request)!!).isEmpty())
        val two = DecisionFrames.parseRequest(
            """{"type":"decision_request","payload":{"decision_id":"d-2","options":[{"id":"deny","label":"不要"},{"id":"approve","label":"批准"}]}}""",
        )!!
        assertEquals(listOf("deny", "approve"), DecisionFrames.buttons(two).map { it.id })
        assertEquals("智能体想问你", two.title)
    }

    @Test
    fun `other frames and a request without an id are not decisions`() {
        assertNull(DecisionFrames.parseRequest("""{"type":"command","payload":{}}"""))
        assertNull(DecisionFrames.parseRequest("""{"type":"decision_request","payload":{"title":"x"}}"""))
        assertNull(DecisionFrames.parseRequest("not json"))
        assertEquals("d-1", DecisionFrames.parseWithdraw("""{"type":"decision_withdraw","payload":{"decision_id":"d-1","reason":"answered_elsewhere"}}"""))
        assertNull(DecisionFrames.parseWithdraw(request))
    }

    /** V2 `docs/DEVICE_CLIENT_REQUIREMENTS.md` A1 给的样子,一个字段不多、不少。 */
    @Test
    fun `approve and deny go back exactly as the center reads them`() {
        for (option in listOf("approve", "deny")) {
            val f = DecisionFrames.humanInputFrame("phone-1", "d-1", option, null, 1000L)
            assertEquals(setOf("type", "version", "device_id", "correlation_id", "timestamp", "payload"), f.keySet())
            assertEquals("command", f["type"].asString)
            assertEquals("3.0", f["version"].asString)
            assertEquals("phone-1", f["device_id"].asString)
            assertTrue(f["correlation_id"].asString.matches(Regex("hi_[0-9a-f-]{36}")))
            assertEquals(1000L, f["timestamp"].asLong)
            val outer = f.getAsJsonObject("payload")
            assertEquals(setOf("command", "payload"), outer.keySet())
            assertEquals("human_input", outer["command"].asString)
            val inner = outer.getAsJsonObject("payload")
            assertEquals(setOf("decision_id", "selected_option", "device", "timestamp"), inner.keySet())
            assertEquals("d-1", inner["decision_id"].asString)
            assertEquals(option, inner["selected_option"].asString)
            assertEquals("android", inner["device"].asString)
            assertEquals(1000L, inner["timestamp"].asLong)
        }
        val a = DecisionFrames.humanInputFrame("p", "d-1", "approve", null, 1L)["correlation_id"].asString
        val b = DecisionFrames.humanInputFrame("p", "d-1", "approve", null, 1L)["correlation_id"].asString
        assertFalse("每次回答一个新的 correlation_id", a == b)
    }

    @Test
    fun `a question without options is answered in words, and words alone carry no option`() {
        val inner = DecisionFrames.humanInputFrame("p", "d-3", null, "  明天上午  ", 1L)
            .getAsJsonObject("payload").getAsJsonObject("payload")
        assertEquals("明天上午", inner["voice_input"].asString)
        assertFalse("批准只能经按钮,说一句话不带选项", inner.has("selected_option"))
    }

    @Test
    fun `the app installs the handler and the manifest has the button receiver and the dialog`() {
        val root = listOf(File("."), File("app"), File("..")).first { File(it, "src/main/AndroidManifest.xml").isFile }
        val app = File(root, "src/main/java/com/ufo/galaxy/UFOGalaxyApplication.kt").readText()
        assertTrue(app.contains("PhoneDecisions.install(webSocketClient, this)"))
        val manifest = File(root, "src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains(".phone.DecisionReplyReceiver"))
        assertTrue(manifest.contains(".phone.DecisionActivity"))
        val client = File(root, "src/main/java/com/ufo/galaxy/network/GalaxyWebSocketClient.kt").readText()
        val branch = client.substringAfter("MsgType.DECISION_REQUEST,").substringBefore("}")
        assertTrue("decision_request / decision_withdraw 要交给 onDecisionFrame", branch.contains("onDecisionFrame(text)"))
    }
}

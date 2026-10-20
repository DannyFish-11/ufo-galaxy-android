package com.ufo.galaxy.bridge

import com.ufo.galaxy.shared.protocol.A2A
import com.ufo.galaxy.shared.protocol.A2AEnvelope
import com.ufo.galaxy.shared.protocol.A2ALink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机上的桥接 Agent:接入握手、上交上下文、对话桥接、委派目标、交还。中心那一端用同一套
 * [A2ALink](V2 `core/phone_bridge.py` 走的就是这套语义)。
 */
class BridgeAgentRuntimeTest {

    private val dev = "phone-1"
    private val session = "phone:phone-1"
    private val center = A2ALink(session, "center", "agent:$dev")
    private val wire = mutableListOf<JsonObject>()
    private var online = true
    private val replies = mutableListOf<Pair<String, Boolean>>()
    private val goals = mutableListOf<Triple<String, String, List<String>>>()
    private var goalProgress: ((JsonObject) -> Unit)? = null
    private var goalDone: ((JsonObject) -> Unit)? = null
    private val returned = mutableListOf<Pair<String, List<String>>>()

    private val host = object : BridgeAgentRuntime.Host {
        override fun handoffContext() = buildJsonObject {
            put("dialog", buildJsonArray { add(buildJsonObject { put("role", "user"); put("text", "帮我订明早的车") }) })
            put("goal", "订车")
        }
        override fun onReply(text: String, success: Boolean) { replies += text to success }
        override fun runGoal(taskId: String, goal: String, constraints: List<String>, progress: (JsonObject) -> Unit, done: (JsonObject) -> Unit) {
            goals += Triple(taskId, goal, constraints); goalProgress = progress; goalDone = done
        }
        override fun onReturned(summary: String, openItems: List<String>) { returned += summary to openItems }
    }

    private val bridge = BridgeAgentRuntime({ dev }, { s -> if (online) wire += Json.parseToJsonElement(s).jsonObject; online }, host)

    /** 中心发一条给手机;返回手机这一来一回发出去的帧(确认之外的)。 */
    private fun fromCenter(kind: String, body: JsonObject = JsonObject(emptyMap()), taskId: String = ""): List<A2AEnvelope> {
        wire.clear()
        bridge.onFrame(center.make(kind, body, taskId = taskId).toAipFrame(dev).toString())
        return drain()
    }

    /** 中心收下手机发出的帧:确认交给中心链路,其余按序交付并回确认。 */
    private fun drain(): List<A2AEnvelope> {
        val out = mutableListOf<A2AEnvelope>()
        for (f in wire.toList()) {
            assertEquals("a2a", (f["type"] as JsonPrimitive).content)
            val env = A2AEnvelope.fromJson(f["payload"]!!.jsonObject)!!
            assertEquals("手机只能以它自己的 Agent 说话", "agent:$dev", env.fromAgent)
            val got = center.accept(env)
            out += got.deliver
            got.ack?.let { bridge.onFrame(it.toAipFrame(dev).toString()) }
        }
        wire.clear()
        return out
    }

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    @Test
    fun `the declared bridge version is the one v2 dispatches to`() {
        assertEquals("bridge/1", BridgeAgentRuntime.BRIDGE_VERSION)
    }

    @Test
    fun `before an agent is dispatched what the person says takes the old path`() {
        assertFalse(bridge.say("你好"))
        assertFalse(bridge.installed)
    }

    @Test
    fun `a dispatched agent installs itself and hands the local context over`() {
        val sent = fromCenter(A2A.Kind.AGENT_DISPATCH, buildJsonObject { put("bridge_version", "bridge/1"); put("autonomy", "ask") })
        assertTrue(bridge.installed)
        assertEquals(listOf(A2A.Kind.AGENT_INSTALLED, A2A.Kind.CONTEXT_HANDOFF), sent.map { it.kind })
        assertEquals("bridge/1", str(sent[0].body, "bridge_version"))
        assertEquals("订车", str(sent[1].body, "goal"))
        assertEquals("ask", str(bridge.spec, "autonomy"))
        assertTrue("确认之后中心的发件箱是空的", center.unacked().isEmpty())
    }

    @Test
    fun `a repeated dispatch after a reconnect does not hand the context over twice`() {
        fromCenter(A2A.Kind.AGENT_DISPATCH)
        val again = fromCenter(A2A.Kind.AGENT_DISPATCH)
        assertEquals(listOf(A2A.Kind.AGENT_INSTALLED), again.map { it.kind })
        assertEquals("true", str(again.single().body, "resumed"))
    }

    @Test
    fun `once installed what the person says goes to the central agent and the answer comes back`() {
        fromCenter(A2A.Kind.AGENT_DISPATCH)
        assertTrue(bridge.say("明早七点的车"))
        val up = drain()
        assertEquals(A2A.Kind.CONVERSATION, up.single().kind)
        assertEquals("明早七点的车", str(up.single().body, "text"))
        fromCenter(A2A.Kind.CONVERSATION, buildJsonObject { put("text", "订好了,七点在楼下"); put("success", true) })
        assertEquals(listOf("订好了,七点在楼下" to true), replies)
    }

    @Test
    fun `a duplicate answer is acknowledged again but shown once`() {
        fromCenter(A2A.Kind.AGENT_DISPATCH)
        val env = center.make(A2A.Kind.CONVERSATION, buildJsonObject { put("text", "好") })
        wire.clear()
        bridge.onFrame(env.toAipFrame(dev).toString())
        bridge.onFrame(env.toAipFrame(dev).toString())
        assertEquals(1, replies.size)
        assertEquals("两次都回确认", 2, wire.count { str(it["payload"]!!.jsonObject, "kind") == A2A.Kind.ACK })
    }

    @Test
    fun `a delegated goal runs on the phone and reports progress and result`() {
        fromCenter(A2A.Kind.AGENT_DISPATCH)
        fromCenter(A2A.Kind.DELEGATE_GOAL, buildJsonObject {
            put("goal", "打开滴滴叫车到公司")
            put("constraints", buildJsonArray { add(JsonPrimitive("不要付款")) })
        }, taskId = "dlg_1")
        assertEquals(Triple("dlg_1", "打开滴滴叫车到公司", listOf("不要付款")), goals.single())
        goalProgress!!(buildJsonObject { put("step", 1); put("total", 4) })
        goalDone!!(buildJsonObject { put("success", true); put("summary", "已叫车") })
        val up = drain()
        assertEquals(listOf(A2A.Kind.DELEGATE_PROGRESS, A2A.Kind.DELEGATE_RESULT), up.map { it.kind })
        assertTrue(up.all { it.taskId == "dlg_1" })
        assertFalse("进度不要确认", up[0].ackRequired)
        assertEquals("已叫车", str(up[1].body, "summary"))
    }

    @Test
    fun `an empty goal is answered instead of left hanging`() {
        fromCenter(A2A.Kind.AGENT_DISPATCH)
        val up = fromCenter(A2A.Kind.DELEGATE_GOAL, buildJsonObject { put("goal", " ") }, taskId = "dlg_2")
        assertEquals(A2A.Kind.DELEGATE_RESULT, up.single().kind)
        assertEquals("false", str(up.single().body, "success"))
        assertTrue(goals.isEmpty())
    }

    @Test
    fun `turning cross-device off asks for the context back and the phone carries on alone`() {
        fromCenter(A2A.Kind.AGENT_DISPATCH)
        assertTrue(bridge.requestReturn())
        assertEquals("true", str(drain().single().body, "request"))
        fromCenter(A2A.Kind.CONTEXT_RETURN, buildJsonObject {
            put("summary", "车订好了,明早七点")
            put("open_items", buildJsonArray { add(buildJsonObject { put("task_id", "dlg_9") }) })
        })
        assertEquals(listOf("车订好了,明早七点" to listOf("dlg_9")), returned)
        assertFalse(bridge.installed)
        assertFalse("交还之后人说的话回到手机自己", bridge.say("再订一辆"))
    }

    @Test
    fun `frames that are not from the center are ignored`() {
        val forged = A2ALink(session, "agent:someone-else", "agent:$dev").make(A2A.Kind.AGENT_DISPATCH)
        bridge.onFrame(forged.toAipFrame(dev).toString())
        assertFalse(bridge.installed)
        assertTrue(wire.isEmpty())
    }

    @Test
    fun `what could not be sent while offline is resent after registering again`() {
        fromCenter(A2A.Kind.AGENT_DISPATCH)
        online = false
        assertTrue(bridge.say("断网时说的"))
        assertTrue(wire.isEmpty())
        online = true
        assertEquals(1, bridge.resendUnacked())
        assertEquals("断网时说的", str(drain().single().body, "text"))
        assertEquals("确认之后不再补发", 0, bridge.resendUnacked().also { wire.clear() })
    }

    @Test
    fun `a new dispatch in another session opens a new link`() {
        fromCenter(A2A.Kind.AGENT_DISPATCH)
        val again = A2ALink("phone:phone-1:2", "center", "agent:$dev")
        wire.clear()
        bridge.onFrame(again.make(A2A.Kind.AGENT_DISPATCH).toAipFrame(dev).toString())
        val sessions = wire.map { str(it["payload"]!!.jsonObject, "session_id") }.toSet()
        assertEquals(setOf("phone:phone-1:2"), sessions)
        val kinds = wire.map { str(it["payload"]!!.jsonObject, "kind") }
        assertTrue("新的一段要重新交接上下文", A2A.Kind.CONTEXT_HANDOFF in kinds)
    }
}

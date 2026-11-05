package com.ufo.galaxy.call

import com.ufo.galaxy.shared.protocol.MsgType
import com.ufo.galaxy.shared.protocol.call.CallEvent
import com.ufo.galaxy.shared.protocol.call.CallSignaling
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关下行的通话信令在手机上怎么读:V2 `voice_call_route` 的 `voice_call_accepted` / `voice_call_end` 把字段放在
 * 顶层,`voice_event` 也是;状态机只读 `payload`。读错了的表现是「点了通话一直在呼叫中」。
 */
class CallFramesTest {

    private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.content ?: ""

    @Test
    fun `a flat accepted frame from the gateway becomes a payload the state machine reads`() {
        val msg = CallFrames.parse(
            """{"type":"voice_call_accepted","device_id":"phone-1","call_id":"call_1","sdp":"v=0","sdp_type":"answer","provider":"openai_realtime"}""",
        )!!
        assertEquals(MsgType.VOICE_CALL_ACCEPTED, msg.type)
        assertEquals("v=0", (msg.payload as JsonObject).s("sdp"))

        val s = CallSignaling("phone-1")
        s.dial("v=0")
        val ev = s.onGatewayMessage(msg)
        assertTrue("拨号中收到网关的 answer 就算接通", ev is CallEvent.Accepted)
        assertEquals("call_1", (ev as CallEvent.Accepted).callId)
    }

    @Test
    fun `a nested payload is used as is`() {
        val msg = CallFrames.parse("""{"type":"voice_event","payload":{"event":"agent_task","text":"在做:订会议室"}}""")!!
        assertEquals("agent_task", (msg.payload as JsonObject).s("event"))
    }

    @Test
    fun `the gateway's flat agent task line reaches the call screen`() {
        val s = CallSignaling("phone-1")
        s.dial("v=0")
        s.onGatewayMessage(CallFrames.parse("""{"type":"voice_call_accepted","call_id":"c","sdp":"v=0"}""")!!)
        val ev = s.onGatewayMessage(
            CallFrames.parse("""{"type":"voice_event","device_id":"phone-1","call_id":"c","event":"agent_task","text":"做完了:订会议室"}""")!!,
        )
        assertEquals(CallEvent.AiEvent("agent_task", "做完了:订会议室"), ev)
    }

    @Test
    fun `anything that is not call signalling is not parsed`() {
        assertNull(CallFrames.parse("""{"type":"command","command_id":"c","command":"location"}"""))
        assertNull(CallFrames.parse("""{"type":"voice_call_start","payload":{"sdp":"v=0"}}"""))
        assertNull(CallFrames.parse("not json"))
        assertNull(CallFrames.parse("""{"no_type":1}"""))
    }

    @Test
    fun `a rejected call says why in the phone's words and a normal hang-up says nothing`() {
        assertNull(CallEndNotice.of("user_hangup"))
        assertEquals("与网关的连接断了", CallEndNotice.of("connection_closed"))
        assertTrue(CallEndNotice.of("unauthenticated")!!.contains("手机"))
        assertEquals("没有可用的实时语音后端", CallEndNotice.of("没有可用的实时语音后端"))
    }
}

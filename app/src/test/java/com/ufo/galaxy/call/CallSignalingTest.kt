package com.ufo.galaxy.call

import com.ufo.galaxy.shared.protocol.call.CallEvent
import com.ufo.galaxy.shared.protocol.call.CallSignaling
import com.ufo.galaxy.shared.protocol.call.CallState
import com.ufo.galaxy.shared.protocol.AipMessage
import com.ufo.galaxy.shared.protocol.MsgType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通话状态机的单测。
 *
 * 这里钉的每一条都是**真会出事**的:候选发早了被网关丢掉、旧通话的 answer 串进新
 * 通话、拨号被拒时原因传不到界面。这些在真表上表现都是"通话偶尔不通",极难复现;
 * 在这一层却是几行断言的事。
 */
class CallSignalingTest {

    private fun sig() = CallSignaling("phone_test")

    private fun gateway(type: MsgType, vararg fields: Pair<String, String>): AipMessage =
        AipMessage(
            type = type,
            payload = buildJsonObject { fields.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
        )

    private fun AipMessage.field(key: String): String =
        (payload.jsonObject[key] as? JsonPrimitive)?.content ?: ""

    // ── 拨号 ────────────────────────────────────────────────────────────

    @Test
    fun `拨号发出带 offer 的 voice_call_start`() {
        val s = sig()
        val msg = s.dial("v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\n")

        assertNotNull(msg)
        assertEquals(MsgType.VOICE_CALL_START, msg!!.type)
        assertEquals("offer", msg.field("sdp_type"))
        assertEquals("phone_test", msg.deviceId)
        assertEquals(CallState.DIALING, s.state)
    }

    @Test
    fun `重复拨号不会发第二条`() {
        val s = sig()
        s.dial("v=0")
        assertNull("连点两下拨号键不该发两条 offer", s.dial("v=0"))
    }

    // ── ICE:整个状态机里最容易出事的一处 ──────────────────────────────

    @Test
    fun `接通前的候选排队而不是直接发出去`() {
        // WebRTC 在 setLocalDescription 一返回就吐候选,那时 voice_call_start 可能还没
        // 写进 socket。候选先到网关 → 网关那边 PeerConnection 还不存在 → 静默丢弃。
        val s = sig()
        s.dial("v=0")

        assertNull(s.localIce("candidate:1 1 udp 2130706431 10.0.0.2 5000 typ host", "0", 0))
        assertNull(s.localIce("candidate:2 1 udp 1694498815 203.0.113.7 5000 typ srflx", "0", 0))
        assertEquals(2, s.queuedIceCount)
    }

    @Test
    fun `接通后排队的候选一次放出并补上 call_id`() {
        val s = sig()
        s.dial("v=0")
        s.localIce("candidate:1 1 udp 2130706431 10.0.0.2 5000 typ host", "0", 0)
        s.onGatewayMessage(gateway(MsgType.VOICE_CALL_ACCEPTED, "sdp" to "v=0-answer", "call_id" to "call_9"))

        val drained = s.drainQueuedIce()

        assertEquals(1, drained.size)
        assertEquals("call_9", drained[0].field("call_id"))
        assertEquals(MsgType.VOICE_ICE, drained[0].type)
        assertEquals("放完必须清空,否则下一次 drain 会重发", 0, s.queuedIceCount)
    }

    @Test
    fun `接通后的候选直接发出不再排队`() {
        val s = sig()
        s.dial("v=0")
        s.onGatewayMessage(gateway(MsgType.VOICE_CALL_ACCEPTED, "sdp" to "v=0-answer", "call_id" to "call_9"))

        val msg = s.localIce("candidate:3 1 udp 1 198.51.100.4 5000 typ relay", "0", 0)

        assertNotNull(msg)
        assertEquals("call_9", msg!!.field("call_id"))
        assertEquals(0, s.queuedIceCount)
    }

    @Test
    fun `没拨号时的候选被丢掉`() {
        assertNull(sig().localIce("candidate:1", "0", 0))
    }

    // ── 串号:重连之后最容易出的一类问题 ────────────────────────────────

    @Test
    fun `不在拨号中收到 answer 一律忽略`() {
        // 上一通电话的迟到回包。装进当前这通的 PeerConnection 会把它弄坏。
        val s = sig()
        val ev = s.onGatewayMessage(gateway(MsgType.VOICE_CALL_ACCEPTED, "sdp" to "v=0", "call_id" to "old"))
        assertEquals(CallEvent.Ignored, ev)
        assertEquals(CallState.IDLE, s.state)
    }

    @Test
    fun `别的通话的事件被忽略`() {
        val s = sig()
        s.dial("v=0")
        s.onGatewayMessage(gateway(MsgType.VOICE_CALL_ACCEPTED, "sdp" to "v=0", "call_id" to "call_now"))

        val stale = s.onGatewayMessage(
            gateway(MsgType.VOICE_EVENT, "call_id" to "call_old", "event" to "final_transcript", "text" to "旧的"),
        )
        assertEquals(CallEvent.Ignored, stale)

        val mine = s.onGatewayMessage(
            gateway(MsgType.VOICE_EVENT, "call_id" to "call_now", "event" to "final_transcript", "text" to "明天下午三点"),
        )
        assertEquals(CallEvent.AiEvent("final_transcript", "明天下午三点"), mine)
    }

    // ── 结束 ────────────────────────────────────────────────────────────

    @Test
    fun `拨号被拒时原因必须能传到界面`() {
        // 网关拒绝时还没有 call_id 可给。若这里按 call_id 过滤,最需要看见的那条
        // ——「没配 provider key」「没装 aiortc」—— 就永远到不了表盘上。
        val s = sig()
        s.dial("v=0")

        val ev = s.onGatewayMessage(
            gateway(MsgType.VOICE_CALL_END, "reason" to "没有可用的实时语音后端(未配置 provider key,或建连失败)"),
        )

        assertTrue(ev is CallEvent.Ended)
        assertTrue((ev as CallEvent.Ended).reason.contains("provider"))
        assertEquals(CallState.ENDED, s.state)
    }

    @Test
    fun `挂断是幂等的`() {
        val s = sig()
        s.dial("v=0")
        s.onGatewayMessage(gateway(MsgType.VOICE_CALL_ACCEPTED, "sdp" to "v=0", "call_id" to "c1"))

        val first = s.hangup("user_hangup")
        assertNotNull(first)
        assertEquals("c1", first!!.field("call_id"))
        assertNull("第二次挂断不该再发一条", s.hangup("user_hangup"))
    }

    @Test
    fun `没拨号就挂断不发消息`() {
        assertNull(sig().hangup())
    }

    @Test
    fun `结束后不再接受任何上行`() {
        val s = sig()
        s.dial("v=0")
        s.onGatewayMessage(gateway(MsgType.VOICE_CALL_ACCEPTED, "sdp" to "v=0", "call_id" to "c1"))
        s.hangup()

        assertNull(s.localIce("candidate:1", "0", 0))
        assertNull(s.interrupt())
        assertEquals(emptyList<AipMessage>(), s.drainQueuedIce())
    }

    @Test
    fun `空 answer 按失败处理而不是假装接通`() {
        val s = sig()
        s.dial("v=0")
        val ev = s.onGatewayMessage(gateway(MsgType.VOICE_CALL_ACCEPTED, "call_id" to "c1"))
        assertTrue("空 SDP 装不进 PeerConnection,只能算失败", ev is CallEvent.Ended)
    }

    // ── 插话 ────────────────────────────────────────────────────────────

    @Test
    fun `插话只在通话中有意义`() {
        val s = sig()
        assertNull(s.interrupt())
        s.dial("v=0")
        assertNull("还没接通就插话没有对象", s.interrupt())

        s.onGatewayMessage(gateway(MsgType.VOICE_CALL_ACCEPTED, "sdp" to "v=0", "call_id" to "c1"))
        val msg = s.interrupt("user_speech")
        assertNotNull(msg)
        assertEquals(MsgType.VOICE_INTERRUPT, msg!!.type)
        assertEquals("user_speech", msg.field("reason"))
    }

    // ── 健壮性 ──────────────────────────────────────────────────────────

    @Test
    fun `字段类型不对不会把通话弄崩`() {
        // 一条畸形信令顶多是这个字段读不出来,不该抛异常拆掉整通电话。
        val s = sig()
        s.dial("v=0")
        val weird = AipMessage(
            type = MsgType.VOICE_CALL_ACCEPTED,
            payload = buildJsonObject {
                put("sdp", JsonPrimitive("v=0-answer"))
                put("call_id", buildJsonObject { put("nested", JsonPrimitive("oops")) })
            },
        )
        val ev = s.onGatewayMessage(weird)
        assertTrue(ev is CallEvent.Accepted)
        assertEquals("", (ev as CallEvent.Accepted).callId)
    }

    @Test
    fun `非通话类型的消息一概不认`() {
        val s = sig()
        s.dial("v=0")
        assertEquals(CallEvent.Ignored, s.onGatewayMessage(gateway(MsgType.PING)))
        assertEquals(CallEvent.Ignored, s.onGatewayMessage(gateway(MsgType.COMMAND)))
    }

    @Test
    fun `payload 不是对象时不崩`() {
        val s = sig()
        val msg = AipMessage(type = MsgType.VOICE_EVENT)  // payload 默认 JsonNull
        assertEquals(CallEvent.Ignored, s.onGatewayMessage(msg))
    }

    @Test
    fun `空候选串是收集结束的标记不是候选`() {
        val s = sig()
        s.dial("v=0")
        s.onGatewayMessage(gateway(MsgType.VOICE_CALL_ACCEPTED, "sdp" to "v=0", "call_id" to "c1"))
        assertEquals(
            CallEvent.Ignored,
            s.onGatewayMessage(gateway(MsgType.VOICE_ICE, "call_id" to "c1", "candidate" to "")),
        )
    }

    @Test
    fun `网关来的候选带回 mid 与 m_line_index`() {
        val s = sig()
        s.dial("v=0")
        s.onGatewayMessage(gateway(MsgType.VOICE_CALL_ACCEPTED, "sdp" to "v=0", "call_id" to "c1"))

        val ev = s.onGatewayMessage(
            gateway(
                MsgType.VOICE_ICE,
                "call_id" to "c1",
                "candidate" to "candidate:1 1 udp 2130706431 10.0.0.9 5000 typ host",
                "sdp_mid" to "0",
                "sdp_m_line_index" to "0",
            ),
        )
        assertEquals(CallEvent.RemoteIce("candidate:1 1 udp 2130706431 10.0.0.9 5000 typ host", "0", 0), ev)
    }

    @Test
    fun `上行采样率与网关默认一致`() {
        // 两边对不上不会报错,只会让网关按 48k 解一段 16k 的音频 —— 听起来像慢放。
        val s = sig()
        val msg = s.dial("v=0")!!
        assertEquals("48000", msg.field("sample_rate"))
        assertEquals(48000, CallSignaling.SAMPLE_RATE)
    }
}

/**
 * 线格式契约:设备发出去的字节,网关那边必须认得。
 *
 * 上面那些用例读的是 `payload[...]`,读得到不代表**序列化之后**还是那些键 ——
 * `@SerialName` 改一个字、枚举的 wire 值漂一点,上面全绿而通话在真表上完全打不通。
 * 所以这一组直接编码成 JSON 再断言键名与取值。
 */
class CallSignalingWireContractTest {

    private val json = Json { encodeDefaults = true }

    private fun encode(msg: AipMessage): JsonObject =
        json.parseToJsonElement(json.encodeToString(AipMessage.serializer(), msg)).jsonObject

    private fun JsonObject.s(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""

    @Test
    fun `voice_call_start 的线格式与网关的 VoiceCallStartMsg 对齐`() {
        val s = CallSignaling("phone_1")
        val wire = encode(s.dial("v=0\r\ns=-\r\n")!!)

        assertEquals("voice_call_start", wire.s("type"))
        assertEquals("phone_1", wire.s("device_id"))
        assertEquals("3.0", wire.s("version"))

        val payload = wire["payload"]!!.jsonObject
        // 网关侧 core/schemas/aip_v3.VoiceCallStartMsg 认这四个键。
        assertEquals("v=0\r\ns=-\r\n", payload.s("sdp"))
        assertEquals("offer", payload.s("sdp_type"))
        assertEquals("48000", payload.s("sample_rate"))
        assertTrue(payload.containsKey("locale"))
    }

    @Test
    fun `voice_ice 的线格式与网关的 VoiceIceMsg 对齐`() {
        val s = CallSignaling("phone_1")
        s.dial("v=0")
        s.onGatewayMessage(
            AipMessage(
                type = MsgType.VOICE_CALL_ACCEPTED,
                payload = buildJsonObject {
                    put("sdp", JsonPrimitive("v=0"))
                    put("call_id", JsonPrimitive("c1"))
                },
            ),
        )
        val wire = encode(s.localIce("candidate:1 1 udp 1 10.0.0.1 5000 typ host", "0", 0)!!)

        assertEquals("voice_ice", wire.s("type"))
        val payload = wire["payload"]!!.jsonObject
        // 网关的 _ice 读的就是这三个键;名字对不上等于候选全丢,而且不报错。
        assertEquals("candidate:1 1 udp 1 10.0.0.1 5000 typ host", payload.s("candidate"))
        assertEquals("0", payload.s("sdp_mid"))
        assertEquals("0", payload.s("sdp_m_line_index"))
        assertEquals("c1", payload.s("call_id"))
    }

    @Test
    fun `voice_interrupt 与 voice_call_end 的线格式`() {
        val s = CallSignaling("phone_1")
        s.dial("v=0")
        s.onGatewayMessage(
            AipMessage(
                type = MsgType.VOICE_CALL_ACCEPTED,
                payload = buildJsonObject {
                    put("sdp", JsonPrimitive("v=0"))
                    put("call_id", JsonPrimitive("c1"))
                },
            ),
        )

        val interrupt = encode(s.interrupt("user_speech")!!)
        assertEquals("voice_interrupt", interrupt.s("type"))
        assertEquals("user_speech", interrupt["payload"]!!.jsonObject.s("reason"))

        val end = encode(s.hangup("user_hangup")!!)
        assertEquals("voice_call_end", end.s("type"))
        assertEquals("c1", end["payload"]!!.jsonObject.s("call_id"))
        assertEquals("user_hangup", end["payload"]!!.jsonObject.s("reason"))
    }

    @Test
    fun `六个通话类型的枚举 wire 值就是网关认的那六个`() {
        // 三处协议权威(网关 SSOT / core schemas / 本仓 MsgType)口径必须一致。
        assertEquals("voice_call_start", MsgType.VOICE_CALL_START.value)
        assertEquals("voice_call_accepted", MsgType.VOICE_CALL_ACCEPTED.value)
        assertEquals("voice_call_end", MsgType.VOICE_CALL_END.value)
        assertEquals("voice_ice", MsgType.VOICE_ICE.value)
        assertEquals("voice_event", MsgType.VOICE_EVENT.value)
        assertEquals("voice_interrupt", MsgType.VOICE_INTERRUPT.value)
    }
}

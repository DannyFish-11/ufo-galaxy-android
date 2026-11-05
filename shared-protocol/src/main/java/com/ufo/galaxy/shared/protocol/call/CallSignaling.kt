package com.ufo.galaxy.shared.protocol.call

import com.ufo.galaxy.shared.protocol.AipMessage
import com.ufo.galaxy.shared.protocol.MsgType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 通话信令的状态机 —— **纯 Kotlin,不碰 Android 也不碰 WebRTC**。
 *
 * 为什么单独拆出来
 * --------------
 * 这条链路上真正容易出错的不是"怎么调 PeerConnection",而是**什么时候能发什么**:
 * 候选发早了会被网关丢掉、旧通话的消息串进新通话、挂断发两遍。这些全是纯逻辑,
 * 混在 WebRTC 胶水里就只能靠真表验证 —— 而真表验证既慢又没法进 CI。
 *
 * 所以状态机在这里,JVM 单测直接跑;设备上的通话控制器只负责把它的决定翻译成 WebRTC 调用。
 *
 * 放在共享协议模块里:手机(`com.ufo.galaxy.call.PhoneCallController`)用它;逻辑与 galaxy-wearos 的
 * `com.galaxy.wear.call.CallSignaling` 是同一份,手表那边日后可以直接改用这里的。
 */
enum class CallState {
    /** 没有通话。 */
    IDLE,

    /** 已发出 offer,等网关的 answer。 */
    DIALING,

    /** 已接通(拿到 answer 与 call_id)。 */
    IN_CALL,

    /** 已结束。终态 —— 再拨号要新建一个实例。 */
    ENDED,
}

/** 网关消息经状态机解读后的结果。 */
sealed interface CallEvent {
    /** 接通了,把 [answerSdp] 装进 PeerConnection。 */
    data class Accepted(val callId: String, val answerSdp: String, val provider: String) : CallEvent

    /** 网关来的 ICE 候选。 */
    data class RemoteIce(val sdp: String, val sdpMid: String?, val sdpMLineIndex: Int) : CallEvent

    /** AI 侧的状态/文字。[name] 是 provider 无关的事件名(如 final_transcript)。 */
    data class AiEvent(val name: String, val text: String) : CallEvent

    /** 通话结束。[reason] 一定非空 —— 界面要能说清为什么断的。 */
    data class Ended(val reason: String) : CallEvent

    /** 与本次通话无关(旧 call_id、方向反了、状态不对)。调用方什么都不做。 */
    data object Ignored : CallEvent
}

/**
 * 一通电话的信令状态。**不是线程安全的** —— 调用方(控制器)负责串行化。
 */
class CallSignaling(private val deviceId: String) {

    var state: CallState = CallState.IDLE
        private set

    var callId: String = ""
        private set

    /**
     * 还没能发出去的本地 ICE 候选。
     *
     * 必须排队,不能边收边发。WebRTC 在 `setLocalDescription(offer)` 一返回就开始吐候选,
     * 而那时 `voice_call_start` 可能还没写进 socket —— 候选先到网关,网关那边
     * PeerConnection 还不存在,候选被直接丢弃。丢掉的候选不会报错,表现是通话"偶尔
     * 连不上",而且在局域网里几乎复现不出来(host 候选一到就通了),只有走蜂窝
     * 数据、要靠后到的 srflx 候选时才暴露。
     */
    private val pendingIce = ArrayDeque<AipMessage>()

    /** 排队中的候选数。给测试与排障用。 */
    val queuedIceCount: Int get() = pendingIce.size

    // ── 上行 ────────────────────────────────────────────────────────────

    /** 拨号。只有 [CallState.IDLE] 能拨,重复调用返回 null 而不是发第二条。 */
    fun dial(offerSdp: String, locale: String = "zh-CN"): AipMessage? {
        if (state != CallState.IDLE) return null
        state = CallState.DIALING
        return message(
            MsgType.VOICE_CALL_START,
            buildJsonObject {
                put("sdp", JsonPrimitive(offerSdp))
                put("sdp_type", JsonPrimitive("offer"))
                put("sample_rate", JsonPrimitive(SAMPLE_RATE))
                put("locale", JsonPrimitive(locale))
            },
        )
    }

    /**
     * 本地收集到一个 ICE 候选。
     *
     * 还没接通时返回 null 并把它排进队列 —— 接通后由 [drainQueuedIce] 一次性放出。
     */
    fun localIce(sdp: String, sdpMid: String?, sdpMLineIndex: Int): AipMessage? {
        if (state == CallState.IDLE || state == CallState.ENDED) return null
        val msg = message(
            MsgType.VOICE_ICE,
            buildJsonObject {
                put("call_id", JsonPrimitive(callId))
                put("candidate", JsonPrimitive(sdp))
                put("sdp_mid", JsonPrimitive(sdpMid ?: ""))
                put("sdp_m_line_index", JsonPrimitive(sdpMLineIndex))
            },
        )
        if (state == CallState.DIALING) {
            pendingIce.addLast(msg)
            return null
        }
        return msg
    }

    /**
     * 放出排队的候选。接通后调一次。
     *
     * 排队时 call_id 还是空的,这里补上 —— 网关按连接找通话,call_id 主要是给日志和
     * 跨通话去重用的,但发一条 call_id 为空的信令等于把排障线索抹掉。
     */
    fun drainQueuedIce(): List<AipMessage> {
        if (state != CallState.IN_CALL) return emptyList()
        val out = pendingIce.map { withCallId(it) }
        pendingIce.clear()
        return out
    }

    /** 用户插话:AI 立刻闭嘴。没接通时无意义,返回 null。 */
    fun interrupt(reason: String = "user_speech"): AipMessage? {
        if (state != CallState.IN_CALL) return null
        return message(
            MsgType.VOICE_INTERRUPT,
            buildJsonObject {
                put("call_id", JsonPrimitive(callId))
                put("reason", JsonPrimitive(reason))
            },
        )
    }

    /**
     * 挂断。幂等 —— 已经结束时返回 null,不会发第二条。
     *
     * 重复发 voice_call_end 本身无害,但它会掩盖一件事:界面以为还在通话中所以又挂了
     * 一次。返回 null 让调用方能看出"这次挂断没有真的发生"。
     */
    fun hangup(reason: String = "user_hangup"): AipMessage? {
        if (state == CallState.IDLE || state == CallState.ENDED) {
            state = CallState.ENDED
            return null
        }
        state = CallState.ENDED
        pendingIce.clear()
        return message(
            MsgType.VOICE_CALL_END,
            buildJsonObject {
                put("call_id", JsonPrimitive(callId))
                put("reason", JsonPrimitive(reason))
            },
        )
    }

    // ── 下行 ────────────────────────────────────────────────────────────

    /** 解读一条网关消息。与本次通话无关的一律返回 [CallEvent.Ignored]。 */
    fun onGatewayMessage(msg: AipMessage): CallEvent {
        val payload = msg.payload as? JsonObject ?: return CallEvent.Ignored
        return when (msg.type) {
            MsgType.VOICE_CALL_ACCEPTED -> onAccepted(payload)
            MsgType.VOICE_ICE -> onRemoteIce(payload)
            MsgType.VOICE_EVENT -> onAiEvent(payload)
            MsgType.VOICE_CALL_END -> onEnded(payload)
            else -> CallEvent.Ignored
        }
    }

    private fun onAccepted(payload: JsonObject): CallEvent {
        // 不在拨号中却收到 answer:上一通电话的迟到回包,或者服务端串了。装进去会把
        // 当前这通的 PeerConnection 弄坏,所以直接丢。
        if (state != CallState.DIALING) return CallEvent.Ignored
        val sdp = payload.str("sdp")
        if (sdp.isEmpty()) return CallEvent.Ended("网关回了空的 answer")
        callId = payload.str("call_id")
        state = CallState.IN_CALL
        return CallEvent.Accepted(callId, sdp, payload.str("provider"))
    }

    private fun onRemoteIce(payload: JsonObject): CallEvent {
        if (state != CallState.IN_CALL) return CallEvent.Ignored
        if (!belongsToThisCall(payload)) return CallEvent.Ignored
        val cand = payload.str("candidate")
        if (cand.isEmpty()) return CallEvent.Ignored // 空串 = 候选收集结束
        val mid = payload.str("sdp_mid").ifEmpty { null }
        val idx = (payload["sdp_m_line_index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        return CallEvent.RemoteIce(cand, mid, idx)
    }

    private fun onAiEvent(payload: JsonObject): CallEvent {
        if (state != CallState.IN_CALL) return CallEvent.Ignored
        if (!belongsToThisCall(payload)) return CallEvent.Ignored
        return CallEvent.AiEvent(payload.str("event"), payload.str("text"))
    }

    private fun onEnded(payload: JsonObject): CallEvent {
        if (state == CallState.ENDED) return CallEvent.Ignored
        // 挂断**不**校验 call_id:拨号被拒时网关还没有 call_id 可给,而那条正是最需要
        // 送到界面上的消息(没装 aiortc、没配 key、SDP 谈崩,原因都在 reason 里)。
        state = CallState.ENDED
        pendingIce.clear()
        return CallEvent.Ended(payload.str("reason").ifEmpty { "通话已结束" })
    }

    private fun belongsToThisCall(payload: JsonObject): Boolean {
        val incoming = payload.str("call_id")
        return incoming.isEmpty() || callId.isEmpty() || incoming == callId
    }

    // ── 组装 ────────────────────────────────────────────────────────────

    private fun message(type: MsgType, payload: JsonObject): AipMessage =
        AipMessage(type = type, payload = payload, deviceId = deviceId)

    private fun withCallId(msg: AipMessage): AipMessage {
        val old = msg.payload.jsonObject
        val patched = buildJsonObject {
            old.forEach { (k, v) -> if (k != "call_id") put(k, v) }
            put("call_id", JsonPrimitive(callId))
        }
        return msg.copy(payload = patched)
    }

    /**
     * 取一个字符串字段。用安全转型而不是 `jsonPrimitive` —— 后者遇到对象/数组会抛,
     * 而一条字段类型不对的信令不该把整通电话弄崩:它顶多是这个字段读不出来。
     */
    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it != "null" } ?: ""

    companion object {
        /**
         * 上行采样率。48k 而不是 16k:WebRTC 的 Opus 在 48k 上跑,让设备端先降采样再
         * 交给编码器等于白丢一次高频,provider 那边要的降采样由网关做(它有音频桥)。
         */
        const val SAMPLE_RATE = 48000
    }
}

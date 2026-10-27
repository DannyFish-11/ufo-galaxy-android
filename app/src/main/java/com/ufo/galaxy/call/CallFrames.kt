package com.ufo.galaxy.call

import com.ufo.galaxy.shared.protocol.AipMessage
import com.ufo.galaxy.shared.protocol.MsgType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 网关发来的通话信令帧 → [AipMessage],交给 [com.ufo.galaxy.shared.protocol.call.CallSignaling]。
 *
 * 网关(V2 `galaxy_gateway/voice_call_route.py`)的 `voice_call_accepted` / `voice_call_end` 把字段放在**顶层**,
 * 有的帧放在 `payload` 里;状态机只读 `payload`。没有 `payload` 就把整帧当 payload —— 手表那边也是这么做的
 * (galaxy-wearos `AIPClient` 的默认分支)。纯 Kotlin。
 */
object CallFrames {

    /** 网关下行的通话信令。 */
    val TYPES: Set<MsgType> = setOf(MsgType.VOICE_CALL_ACCEPTED, MsgType.VOICE_CALL_END, MsgType.VOICE_EVENT, MsgType.VOICE_ICE)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 不是通话信令、解不开的,返回 null。 */
    fun parse(text: String): AipMessage? {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val type = (obj["type"] as? JsonPrimitive)?.content?.let { MsgType.fromValue(it) } ?: return null
        if (type !in TYPES) return null
        return AipMessage(type = type, payload = obj["payload"] as? JsonObject ?: obj)
    }
}

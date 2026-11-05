package com.ufo.galaxy.shared.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 设备此刻的状态,跟着心跳帧走(`{"type":"heartbeat", "payload":{"state":{...}}}`)。
 *
 * 设计见 V2 `docs/DEVICE_SELF_DESCRIPTION_DESIGN.md` 3.5;schema 在 V2 `contracts/device_state_v1.json`,
 * 中心读入在 V2 `core/device_state.py`。手机、手表都用这里拼。
 *
 * - 每一份都是此刻的**全部**状态:不知道的就不填(null),中心不沿用上一份,也不替设备推断;
 * - 只有这几个键 —— 心率、位置这类原始信号不出设备;设备想让模型知道、又不属于这几项的,写进 [note];
 * - [interruptible] 是设备自己判断的「现在能不能打扰人」,判断不了就不填。
 */
data class DeviceState(
    /** 电量,0 到 1。 */
    val battery: Float? = null,
    val charging: Boolean? = null,
    val screenOn: Boolean? = null,
    /** 戴在身上。 */
    val worn: Boolean? = null,
    /** 正在通话。 */
    val inCall: Boolean? = null,
    val interruptible: String? = null,
    val note: String? = null,
) {
    init {
        require(battery == null || battery in 0f..1f) { "电量要在 0 到 1 之间:$battery" }
        require(interruptible == null || interruptible in Interruptible.ALL) {
            "interruptible 不认识:$interruptible(可用:${Interruptible.ALL})"
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        battery?.let { put("battery", Math.round(it * 100f) / 100f) }
        charging?.let { put("charging", it) }
        screenOn?.let { put("screen_on", it) }
        worn?.let { put("worn", it) }
        inCall?.let { put("in_call", it) }
        interruptible?.let { put("interruptible", it) }
        note?.trim()?.take(MAX_NOTE)?.takeIf { it.isNotEmpty() }?.let { put("note", it) }
    }

    /** 现在能不能打扰人。 */
    object Interruptible {
        /** 明确别打扰:开着勿扰、极可能在睡。中心此刻不主动开口。 */
        const val BLOCKED = "blocked"

        /** 偏忙:只有要紧事才值得打断。 */
        const val BUSY = "busy"

        /** 说不好。 */
        const val NEUTRAL = "neutral"

        /** 可以:正看着、刚交互过。 */
        const val FREE = "free"

        val ALL = setOf(BLOCKED, BUSY, NEUTRAL, FREE)
    }

    companion object {
        /** 心跳帧 `payload` 里放状态的键。 */
        const val KEY = "state"
        const val MAX_NOTE = 120
    }
}

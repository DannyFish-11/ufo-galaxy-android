package com.ufo.galaxy.protocol

import org.json.JSONObject

/**
 * UFO Galaxy Android Agent - AIP v2.0 Message
 * 颗粒级对接协议实现
 */
data class AIPMessage(
    val type: Int,
    val deviceId: String,
    val payload: JSONObject,
    val timestamp: Long = System.currentTimeMillis() / 1000
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("type", type)
        json.put("device_id", deviceId)
        json.put("payload", payload)
        json.put("timestamp", timestamp)
        return json.toString()
    }

    companion object {
        const val TYPE_DEVICE_REGISTER = 0x01
        const val TYPE_DEVICE_HEARTBEAT = 0x03
        const val TYPE_CONTROL_COMMAND = 0x40
        const val TYPE_ANDROID_SCREEN = 0x60
        const val TYPE_RESULT = 0x80

        fun createRegister(deviceId: String, model: String): AIPMessage {
            val payload = JSONObject()
            payload.put("model", model)
            payload.put("capabilities", listOf("screen_capture", "adb_control", "accessibility"))
            return AIPMessage(TYPE_DEVICE_REGISTER, deviceId, payload)
        }
    }
}

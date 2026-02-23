package com.ufo.galaxy.communication

import org.json.JSONObject
import java.util.UUID

/**
 * UFO Galaxy - 设备消息
 * 
 * 完全对齐服务端 device_communication.py 的 DeviceMessage
 */
data class DeviceMessage(
    val type: String,
    val action: String = "",
    val payload: JSONObject = JSONObject(),
    val messageId: String = UUID.randomUUID().toString().take(8),
    val timestamp: Long = System.currentTimeMillis(),
    val deviceId: String = "",
    val correlationId: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("type", type)
            put("action", action)
            put("payload", payload)
            put("message_id", messageId)
            put("timestamp", timestamp)
            put("device_id", deviceId)
            put("correlation_id", correlationId)
        }
    }
    
    companion object {
        fun fromJson(json: JSONObject): DeviceMessage {
            return DeviceMessage(
                type = json.optString("type", ""),
                action = json.optString("action", ""),
                payload = json.optJSONObject("payload") ?: JSONObject(),
                messageId = json.optString("message_id", UUID.randomUUID().toString().take(8)),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                deviceId = json.optString("device_id", ""),
                correlationId = json.optString("correlation_id", "")
            )
        }
    }
}

/**
 * 命令消息
 */
data class CommandMessage(
    val action: String,
    val payload: JSONObject,
    val messageId: String,
    val deviceId: String = "",
    val correlationId: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("type", "command")
            put("action", action)
            put("payload", payload)
            put("message_id", messageId)
            put("device_id", deviceId)
            put("correlation_id", correlationId)
        }
    }
    
    companion object {
        fun fromJson(json: JSONObject): CommandMessage {
            return CommandMessage(
                action = json.optString("action", ""),
                payload = json.optJSONObject("payload") ?: JSONObject(),
                messageId = json.optString("message_id", ""),
                deviceId = json.optString("device_id", ""),
                correlationId = json.optString("correlation_id", "")
            )
        }
    }
}

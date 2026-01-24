package com.ufo.galaxy.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * AIP (Agent Interaction Protocol) v2.0 消息封装
 * 
 * 功能：
 * 1. 统一的消息格式封装
 * 2. 支持 10 种消息类型
 * 3. 消息验证和解析
 * 
 * @author UFO Galaxy Team
 * @version 2.0.0
 * @date 2026-01-24
 */
class AIPMessage private constructor(
    val version: String,
    val type: MessageType,
    val deviceId: String,
    val timestamp: Long,
    val payload: JSONObject
) {
    
    enum class MessageType(val value: String) {
        // 基础消息
        DEVICE_REGISTER("device_register"),
        DEVICE_REGISTER_ACK("device_register_ack"),
        HEARTBEAT("heartbeat"),
        HEARTBEAT_ACK("heartbeat_ack"),
        
        // 任务消息
        TASK_REQUEST("task_request"),
        TASK_RESPONSE("task_response"),
        TASK_STATUS("task_status"),
        
        // 控制消息
        COMMAND("command"),
        COMMAND_RESPONSE("command_response"),
        
        // 数据消息
        DATA_TRANSFER("data_transfer"),
        DATA_ACK("data_ack"),
        
        // 多媒体消息
        MEDIA_TRANSFER("media_transfer"),
        MEDIA_ACK("media_ack"),
        
        // 工具发现消息
        TOOL_DISCOVERY("tool_discovery"),
        TOOL_REGISTER("tool_register"),
        
        // 错误消息
        ERROR("error");
        
        companion object {
            fun fromString(value: String): MessageType? {
                return values().find { it.value == value }
            }
        }
    }
    
    /**
     * 转换为 JSON
     */
    fun toJSON(): JSONObject {
        return JSONObject().apply {
            put("version", version)
            put("type", type.value)
            put("device_id", deviceId)
            put("timestamp", timestamp)
            put("payload", payload)
        }
    }
    
    /**
     * 转换为 JSON 字符串
     */
    override fun toString(): String {
        return toJSON().toString()
    }
    
    companion object {
        private const val VERSION = "2.0"
        
        /**
         * 创建设备注册消息
         */
        fun createDeviceRegister(
            deviceId: String,
            deviceType: String,
            capabilities: Map<String, Any>
        ): AIPMessage {
            val payload = JSONObject().apply {
                put("device_type", deviceType)
                put("capabilities", JSONObject(capabilities))
            }
            return AIPMessage(
                version = VERSION,
                type = MessageType.DEVICE_REGISTER,
                deviceId = deviceId,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )
        }
        
        /**
         * 创建心跳消息
         */
        fun createHeartbeat(deviceId: String): AIPMessage {
            return AIPMessage(
                version = VERSION,
                type = MessageType.HEARTBEAT,
                deviceId = deviceId,
                timestamp = System.currentTimeMillis(),
                payload = JSONObject()
            )
        }
        
        /**
         * 创建任务响应消息
         */
        fun createTaskResponse(
            deviceId: String,
            taskId: String,
            success: Boolean,
            result: Any?,
            error: String? = null
        ): AIPMessage {
            val payload = JSONObject().apply {
                put("task_id", taskId)
                put("success", success)
                if (result != null) {
                    put("result", result)
                }
                if (error != null) {
                    put("error", error)
                }
            }
            return AIPMessage(
                version = VERSION,
                type = MessageType.TASK_RESPONSE,
                deviceId = deviceId,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )
        }
        
        /**
         * 创建任务状态消息
         */
        fun createTaskStatus(
            deviceId: String,
            taskId: String,
            status: String,
            progress: Int? = null
        ): AIPMessage {
            val payload = JSONObject().apply {
                put("task_id", taskId)
                put("status", status)
                if (progress != null) {
                    put("progress", progress)
                }
            }
            return AIPMessage(
                version = VERSION,
                type = MessageType.TASK_STATUS,
                deviceId = deviceId,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )
        }
        
        /**
         * 创建命令响应消息
         */
        fun createCommandResponse(
            deviceId: String,
            commandId: String,
            success: Boolean,
            result: Any?,
            error: String? = null
        ): AIPMessage {
            val payload = JSONObject().apply {
                put("command_id", commandId)
                put("success", success)
                if (result != null) {
                    put("result", result)
                }
                if (error != null) {
                    put("error", error)
                }
            }
            return AIPMessage(
                version = VERSION,
                type = MessageType.COMMAND_RESPONSE,
                deviceId = deviceId,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )
        }
        
        /**
         * 创建数据传输消息
         */
        fun createDataTransfer(
            deviceId: String,
            dataType: String,
            data: Any,
            metadata: Map<String, Any>? = null
        ): AIPMessage {
            val payload = JSONObject().apply {
                put("data_type", dataType)
                put("data", data)
                if (metadata != null) {
                    put("metadata", JSONObject(metadata))
                }
            }
            return AIPMessage(
                version = VERSION,
                type = MessageType.DATA_TRANSFER,
                deviceId = deviceId,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )
        }
        
        /**
         * 创建工具注册消息
         */
        fun createToolRegister(
            deviceId: String,
            toolName: String,
            toolType: String,
            capabilities: List<String>,
            endpoint: String
        ): AIPMessage {
            val payload = JSONObject().apply {
                put("tool_name", toolName)
                put("tool_type", toolType)
                put("capabilities", JSONArray(capabilities))
                put("endpoint", endpoint)
            }
            return AIPMessage(
                version = VERSION,
                type = MessageType.TOOL_REGISTER,
                deviceId = deviceId,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )
        }
        
        /**
         * 创建错误消息
         */
        fun createError(
            deviceId: String,
            errorCode: String,
            errorMessage: String,
            details: Map<String, Any>? = null
        ): AIPMessage {
            val payload = JSONObject().apply {
                put("error_code", errorCode)
                put("error_message", errorMessage)
                if (details != null) {
                    put("details", JSONObject(details))
                }
            }
            return AIPMessage(
                version = VERSION,
                type = MessageType.ERROR,
                deviceId = deviceId,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )
        }
        
        /**
         * 从 JSON 解析消息
         */
        fun fromJSON(json: JSONObject): AIPMessage? {
            return try {
                val version = json.optString("version", VERSION)
                val typeStr = json.optString("type")
                val type = MessageType.fromString(typeStr) ?: return null
                val deviceId = json.optString("device_id")
                val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                val payload = json.optJSONObject("payload") ?: JSONObject()
                
                AIPMessage(version, type, deviceId, timestamp, payload)
            } catch (e: Exception) {
                null
            }
        }
        
        /**
         * 从 JSON 字符串解析消息
         */
        fun fromString(jsonString: String): AIPMessage? {
            return try {
                val json = JSONObject(jsonString)
                fromJSON(json)
            } catch (e: Exception) {
                null
            }
        }
    }
}

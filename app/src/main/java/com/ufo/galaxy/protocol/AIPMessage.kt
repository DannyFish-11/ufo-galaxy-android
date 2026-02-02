package com.ufo.galaxy.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * UFO Galaxy Android Agent - AIP v2.0 Message
 * 颗粒级对接协议实现 - 与服务端完全对齐
 * 
 * 协议版本: 2.0
 * 最后更新: 2026-02-02
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
        // ============ 设备管理 (0x01-0x0F) ============
        const val TYPE_DEVICE_REGISTER = 0x01
        const val TYPE_DEVICE_UNREGISTER = 0x02
        const val TYPE_DEVICE_HEARTBEAT = 0x03
        const val TYPE_DEVICE_STATUS = 0x04
        const val TYPE_DEVICE_DISCOVER = 0x05

        // ============ 任务调度 (0x10-0x1F) ============
        const val TYPE_TASK_SUBMIT = 0x10
        const val TYPE_TASK_ASSIGN = 0x11
        const val TYPE_TASK_STATUS = 0x12
        const val TYPE_TASK_RESULT = 0x13
        const val TYPE_TASK_CANCEL = 0x14

        // ============ 协调同步 (0x20-0x2F) ============
        const val TYPE_COORD_SYNC = 0x20
        const val TYPE_COORD_BROADCAST = 0x21
        const val TYPE_COORD_ROUTING = 0x22
        const val TYPE_COORD_ELECTION = 0x23

        // ============ 数据传输 (0x30-0x3F) ============
        const val TYPE_DATA_REQUEST = 0x30
        const val TYPE_DATA_RESPONSE = 0x31
        const val TYPE_DATA_STREAM = 0x32

        // ============ 控制命令 (0x40-0x4F) ============
        const val TYPE_CONTROL_COMMAND = 0x40
        const val TYPE_CONTROL_CONFIG = 0x41
        const val TYPE_CONTROL_SHUTDOWN = 0x42

        // ============ 错误恢复 (0x50-0x5F) ============
        const val TYPE_ERROR_REPORT = 0x50
        const val TYPE_RECOVERY_REQUEST = 0x51
        const val TYPE_RECOVERY_RESPONSE = 0x52

        // ============ 安卓专用 (0x60-0x6F) ============
        const val TYPE_ANDROID_SCREEN = 0x60
        const val TYPE_ANDROID_INPUT = 0x61
        const val TYPE_ANDROID_INSTALL = 0x62
        const val TYPE_ANDROID_ACCESSIBILITY = 0x63
        const val TYPE_ANDROID_NOTIFICATION = 0x64
        const val TYPE_ANDROID_SENSOR = 0x65

        // ============ 结果响应 (0x80-0x8F) ============
        const val TYPE_RESULT = 0x80
        const val TYPE_RESULT_SUCCESS = 0x81
        const val TYPE_RESULT_FAILURE = 0x82
        const val TYPE_RESULT_PARTIAL = 0x83

        /**
         * 从 JSON 字符串解析消息
         */
        fun fromJson(jsonStr: String): AIPMessage {
            val json = JSONObject(jsonStr)
            return AIPMessage(
                type = json.getInt("type"),
                deviceId = json.getString("device_id"),
                payload = json.getJSONObject("payload"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis() / 1000)
            )
        }

        /**
         * 创建设备注册消息
         */
        fun createRegister(deviceId: String, model: String, capabilities: List<String>): AIPMessage {
            val payload = JSONObject()
            payload.put("model", model)
            payload.put("os_version", android.os.Build.VERSION.RELEASE)
            payload.put("sdk_version", android.os.Build.VERSION.SDK_INT)
            payload.put("device_type", "android")
            payload.put("capabilities", JSONArray(capabilities))
            return AIPMessage(TYPE_DEVICE_REGISTER, deviceId, payload)
        }

        /**
         * 创建心跳消息
         */
        fun createHeartbeat(deviceId: String, load: Float = 0f): AIPMessage {
            val payload = JSONObject()
            payload.put("load", load)
            payload.put("status", "online")
            return AIPMessage(TYPE_DEVICE_HEARTBEAT, deviceId, payload)
        }

        /**
         * 创建任务结果消息
         */
        fun createTaskResult(deviceId: String, taskId: String, success: Boolean, data: JSONObject? = null): AIPMessage {
            val payload = JSONObject()
            payload.put("task_id", taskId)
            payload.put("success", success)
            payload.put("data", data ?: JSONObject())
            return AIPMessage(if (success) TYPE_RESULT_SUCCESS else TYPE_RESULT_FAILURE, deviceId, payload)
        }

        /**
         * 创建屏幕数据消息
         */
        fun createScreenData(deviceId: String, screenData: ByteArray, format: String = "jpeg"): AIPMessage {
            val payload = JSONObject()
            payload.put("format", format)
            payload.put("data", android.util.Base64.encodeToString(screenData, android.util.Base64.NO_WRAP))
            return AIPMessage(TYPE_ANDROID_SCREEN, deviceId, payload)
        }

        /**
         * 创建输入事件消息
         */
        fun createInputEvent(deviceId: String, eventType: String, x: Int, y: Int, text: String? = null): AIPMessage {
            val payload = JSONObject()
            payload.put("event_type", eventType)
            payload.put("x", x)
            payload.put("y", y)
            if (text != null) {
                payload.put("text", text)
            }
            return AIPMessage(TYPE_ANDROID_INPUT, deviceId, payload)
        }

        /**
         * 创建无障碍事件消息
         */
        fun createAccessibilityEvent(deviceId: String, eventType: String, nodeInfo: JSONObject): AIPMessage {
            val payload = JSONObject()
            payload.put("event_type", eventType)
            payload.put("node_info", nodeInfo)
            return AIPMessage(TYPE_ANDROID_ACCESSIBILITY, deviceId, payload)
        }

        /**
         * 创建错误报告消息
         */
        fun createErrorReport(deviceId: String, errorType: String, message: String, stackTrace: String? = null): AIPMessage {
            val payload = JSONObject()
            payload.put("error_type", errorType)
            payload.put("message", message)
            if (stackTrace != null) {
                payload.put("stack_trace", stackTrace)
            }
            return AIPMessage(TYPE_ERROR_REPORT, deviceId, payload)
        }

        /**
         * 创建状态同步消息
         */
        fun createStateSync(deviceId: String, state: JSONObject): AIPMessage {
            val payload = JSONObject()
            payload.put("state", state)
            return AIPMessage(TYPE_COORD_SYNC, deviceId, payload)
        }
    }
}

/**
 * AIP 消息类型枚举 - 与服务端 MessageType 对齐
 */
enum class MessageType(val code: Int) {
    // 设备管理
    DEVICE_REGISTER(0x01),
    DEVICE_UNREGISTER(0x02),
    DEVICE_HEARTBEAT(0x03),
    DEVICE_STATUS(0x04),
    DEVICE_DISCOVER(0x05),

    // 任务调度
    TASK_SUBMIT(0x10),
    TASK_ASSIGN(0x11),
    TASK_STATUS(0x12),
    TASK_RESULT(0x13),
    TASK_CANCEL(0x14),

    // 协调同步
    COORD_SYNC(0x20),
    COORD_BROADCAST(0x21),
    COORD_ROUTING(0x22),
    COORD_ELECTION(0x23),

    // 数据传输
    DATA_REQUEST(0x30),
    DATA_RESPONSE(0x31),
    DATA_STREAM(0x32),

    // 控制命令
    CONTROL_COMMAND(0x40),
    CONTROL_CONFIG(0x41),
    CONTROL_SHUTDOWN(0x42),

    // 错误恢复
    ERROR_REPORT(0x50),
    RECOVERY_REQUEST(0x51),
    RECOVERY_RESPONSE(0x52),

    // 安卓专用
    ANDROID_SCREEN(0x60),
    ANDROID_INPUT(0x61),
    ANDROID_INSTALL(0x62),
    ANDROID_ACCESSIBILITY(0x63),
    ANDROID_NOTIFICATION(0x64),
    ANDROID_SENSOR(0x65),

    // 结果响应
    RESULT(0x80),
    RESULT_SUCCESS(0x81),
    RESULT_FAILURE(0x82),
    RESULT_PARTIAL(0x83);

    companion object {
        fun fromCode(code: Int): MessageType? = values().find { it.code == code }
    }
}

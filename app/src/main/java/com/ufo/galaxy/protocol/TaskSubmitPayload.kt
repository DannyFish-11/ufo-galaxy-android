package com.ufo.galaxy.protocol

import org.json.JSONObject
import java.util.UUID

/**
 * 任务提交载荷（TaskSubmitPayload）
 *
 * 跨设备模式下，Android 端向 Galaxy Gateway 发送的消息载荷。
 * 符合 AIP v3 协议中 [MsgType.TASK_SUBMIT] 的格式要求。
 *
 * 用法示例：
 * ```kotlin
 * val payload = TaskSubmitPayload(goal = "打开微信")
 * val json = payload.toJson()
 * ```
 *
 * @property taskId   任务唯一标识（默认随机生成）
 * @property goal     用户输入的自然语言目标
 * @property deviceId 提交任务的设备 ID
 * @property source   来源标识（"text_input" / "voice_input"）
 *
 * @author UFO³ Galaxy
 * @version 1.0
 */
data class TaskSubmitPayload(
    val goal: String,
    val taskId: String = UUID.randomUUID().toString(),
    val deviceId: String = "",
    val source: String = "text_input"
) {
    /**
     * 序列化为 JSONObject，作为 AIP v3 消息的 payload 字段值。
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", MsgType.TASK_SUBMIT)
        put("task_id", taskId)
        put("goal", goal)
        put("device_id", deviceId)
        put("source", source)
        put("timestamp", System.currentTimeMillis())
    }

    companion object {
        /**
         * 从 JSONObject 反序列化（接收服务端回传时使用）。
         */
        fun fromJson(json: JSONObject) = TaskSubmitPayload(
            goal = json.optString("goal", ""),
            taskId = json.optString("task_id", UUID.randomUUID().toString()),
            deviceId = json.optString("device_id", ""),
            source = json.optString("source", "text_input")
        )
    }
}

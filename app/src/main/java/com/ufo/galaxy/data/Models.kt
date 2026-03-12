package com.ufo.galaxy.data

/**
 * UFO Galaxy Android - 数据模型
 */

/**
 * 应用配置
 *
 * Performance / resource fields:
 *  @param plannerMaxTokens   Maximum tokens the MobileVLM planner may generate per call.
 *  @param plannerTemperature Sampling temperature for MobileVLM (lower = more deterministic).
 *  @param plannerTimeoutMs   HTTP connect+read timeout for MobileVLM inference calls (ms).
 *  @param groundingTimeoutMs HTTP connect+read timeout for SeeClick grounding calls (ms).
 *  @param scaledMaxEdge      Longest edge (px) for screenshot downscaling before grounding.
 *                            0 = disabled (pass full-resolution image to grounding engine).
 */
data class AppConfig(
    val serverUrl: String,
    val apiVersion: String,
    val isDebug: Boolean,
    val crossDeviceEnabled: Boolean = true,
    val plannerMaxTokens: Int = 512,
    val plannerTemperature: Double = 0.1,
    val plannerTimeoutMs: Int = 30_000,
    val groundingTimeoutMs: Int = 15_000,
    val scaledMaxEdge: Int = 720
)

/**
 * 消息角色
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

/**
 * 聊天消息
 */
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val metadata: Map<String, Any>? = null
)

/**
 * AIP 消息类型
 */
enum class AIPMessageType {
    TEXT,
    VOICE,
    IMAGE,
    COMMAND,
    STATUS,
    ERROR
}

/**
 * AIP 协议消息
 */
data class AIPMessage(
    val version: String = "3.0",
    val type: AIPMessageType,
    val payload: Any,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String? = null,
    val deviceId: String? = null
)

/**
 * AIP v3.0 能力上报载荷
 * 用于 Loop 3（自动扩展）：服务端依据此信息推断能力差距
 *
 * @param platform           Device platform identifier (e.g., "android").
 * @param device_id          Unique device identifier.
 * @param supported_actions  Low-level action capabilities (e.g., "screen_capture", "app_control").
 * @param version            Protocol version; always "3.0".
 * @param capabilities       High-level autonomous capability names (e.g.,
 *                           "autonomous_goal_execution", "local_task_planning").
 *                           Empty list when not applicable.
 * @param metadata           Key-value metadata flags reported to the gateway, e.g.:
 *                           goal_execution_enabled, local_model_enabled,
 *                           cross_device_enabled, parallel_execution_enabled, device_role.
 */
data class CapabilityReport(
    val platform: String,
    val device_id: String,
    val supported_actions: List<String>,
    val version: String = "3.0",
    val capabilities: List<String> = emptyList(),
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * 结构化诊断/遥测载荷
 * 用于 Loop 1（自修复）和 Loop 2（学习反馈）：服务端依据此信息分类重复失败
 */
data class DiagnosticsPayload(
    val error_type: String,
    val error_context: String,
    val task_id: String,
    val node_name: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 设备状态
 */
data class DeviceStatus(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val isOnline: Boolean,
    val lastSeen: Long,
    val capabilities: List<String> = emptyList()
)

/**
 * 任务状态
 */
enum class TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * 任务信息
 */
data class TaskInfo(
    val taskId: String,
    val name: String,
    val description: String,
    val status: TaskStatus,
    val progress: Float = 0f,
    val createdAt: Long,
    val updatedAt: Long,
    val result: Any? = null,
    val error: String? = null
)

/**
 * 连接状态
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

/**
 * 系统状态
 */
data class SystemState(
    val connectionState: ConnectionState,
    val serverVersion: String? = null,
    val activeNodes: Int = 0,
    val pendingTasks: Int = 0,
    val lastHeartbeat: Long? = null
)

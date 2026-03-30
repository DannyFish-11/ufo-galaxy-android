package com.ufo.galaxy.data

/**
 * UFO Galaxy Android - 数据模型
 */

/**
 * 应用配置
 *
 * Holds build-time and identity fields that do not change at runtime.
 * Local-chain execution settings (planner tokens, timeouts, scaling)
 * were moved to [AppSettings] so they participate in the unified
 * configuration hierarchy and can be overridden at runtime without
 * a recompile.
 */
data class AppConfig(
    val serverUrl: String,
    val apiVersion: String,
    val isDebug: Boolean,
    val crossDeviceEnabled: Boolean = true
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
 * This is the **canonical runtime identity payload** consumed by the server to determine
 * device capability and readiness without any Android-specific interpretation.
 * Every sent payload **must** contain all keys in [REQUIRED_METADATA_KEYS].
 *
 * @param platform           Device platform identifier (e.g., "android").
 * @param device_id          Unique device identifier.
 * @param supported_actions  Low-level action capabilities (e.g., "screen_capture", "app_control").
 * @param version            Protocol version; always "3.0".
 * @param capabilities       High-level autonomous capability names (e.g.,
 *                           "autonomous_goal_execution", "local_task_planning").
 *                           Empty list when not applicable.
 * @param metadata           Canonical runtime identity flags reported to the gateway.
 *                           Must contain all 8 keys defined in [REQUIRED_METADATA_KEYS]:
 *                           `goal_execution_enabled`, `local_model_enabled`,
 *                           `cross_device_enabled`, `parallel_execution_enabled`,
 *                           `device_role`, `model_ready`, `accessibility_ready`,
 *                           `overlay_ready`. Use [AppSettings.toMetadataMap] to build
 *                           a conforming map. Use [validate] to check completeness.
 */
data class CapabilityReport(
    val platform: String,
    val device_id: String,
    val supported_actions: List<String>,
    val version: String = "3.0",
    val capabilities: List<String> = emptyList(),
    val metadata: Map<String, Any> = emptyMap()
) {
    /**
     * Returns `true` when [metadata] contains all keys in [REQUIRED_METADATA_KEYS].
     *
     * Call this before sending the payload to the gateway to detect incomplete metadata
     * early. A payload with missing keys will still be sent but the server may treat
     * the device as having unknown readiness state.
     */
    fun validate(): Boolean = REQUIRED_METADATA_KEYS.all { metadata.containsKey(it) }

    /**
     * Returns the set of missing required metadata keys, or an empty set when the
     * payload is complete. Intended for logging / diagnostics only.
     */
    fun missingMetadataKeys(): Set<String> = REQUIRED_METADATA_KEYS - metadata.keys

    companion object {
        /**
         * The canonical set of metadata keys that every `capability_report` payload
         * **must** contain for the server to treat this device as a fully-identified
         * runtime peer.
         *
         * | Key                       | Type    | Meaning |
         * |---------------------------|---------|---------|
         * | `goal_execution_enabled`  | Boolean | Device can execute autonomous goals end-to-end. |
         * | `local_model_enabled`     | Boolean | On-device inference models are loaded and ready. |
         * | `cross_device_enabled`    | Boolean | Cross-device collaboration switch is ON. |
         * | `parallel_execution_enabled` | Boolean | Parallel subtask execution is active. |
         * | `device_role`             | String  | Logical cluster role: "phone", "tablet", "hub". |
         * | `model_ready`             | Boolean | Model files are present and verified on disk. |
         * | `accessibility_ready`     | Boolean | HardwareKeyListener accessibility service is enabled. |
         * | `overlay_ready`           | Boolean | SYSTEM_ALERT_WINDOW (overlay) permission is granted. |
         *
         * Populated in production by [AppSettings.toMetadataMap].
         */
        val REQUIRED_METADATA_KEYS: Set<String> = setOf(
            "goal_execution_enabled",
            "local_model_enabled",
            "cross_device_enabled",
            "parallel_execution_enabled",
            "device_role",
            "model_ready",
            "accessibility_ready",
            "overlay_ready"
        )
    }
}

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

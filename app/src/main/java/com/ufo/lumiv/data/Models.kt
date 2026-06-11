package com.ufo.lumiv.data

import com.ufo.lumiv.runtime.LocalExecutionModeGate
import com.ufo.lumiv.runtime.LocalIntelligenceCapabilityStatus

/**
 * UFO Lumiv Android - 数据模型
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
@Deprecated("Use AipMessage from AipModels.kt (AIP v3)", ReplaceWith("AipMessage"))
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
 * This is the canonical **capability-provider publication payload** consumed by the server
 * to determine device capability and readiness without any Android-specific interpretation.
 * It is not the source of truth for Android runtime identity, participant identity, attachment
 * state, or reconnect lifecycle semantics.
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

    /**
     * Returns `true` when [metadata] contains all keys in [SCHEDULING_BASIS_METADATA_KEYS].
     *
     * Scheduling-basis keys are populated by
     * [com.ufo.lumiv.capability.AndroidCapabilityVector.toSchedulingMetadata] and merged
     * into the payload before sending.  Call this (in addition to [validate]) when the
     * sender should include full scheduling signals for placement decisions.
     */
    fun validateSchedulingBasis(): Boolean =
        SCHEDULING_BASIS_METADATA_KEYS.all { metadata.containsKey(it) }

    /**
     * Returns the set of missing scheduling-basis metadata keys, or an empty set when all
     * scheduling keys are present. Intended for logging / diagnostics only.
     */
    fun missingSchedulingBasisKeys(): Set<String> = SCHEDULING_BASIS_METADATA_KEYS - metadata.keys

    /**
     * Returns the set of missing canonical gate metadata keys, or an empty set when complete.
     * Intended for diagnostics/logging.
     */
    fun missingCanonicalGateMetadataKeys(): Set<String> =
        CANONICAL_GATE_METADATA_KEYS - metadata.keys

    /**
     * Returns the canonical gate metadata keys that are present but malformed.
     *
     * A malformed key is one whose value is the wrong type or whose string value is outside the
     * documented canonical vocabulary.
     */
    fun malformedCanonicalGateMetadataKeys(): Set<String> {
        val malformed = mutableSetOf<String>()

        BOOLEAN_CANONICAL_GATE_METADATA_KEYS.forEach { key ->
            if (metadata.containsKey(key) && metadata[key] !is Boolean) {
                malformed += key
            }
        }
        STRING_CANONICAL_GATE_METADATA_KEYS.forEach { key ->
            if (metadata.containsKey(key) && metadata[key] !is String) {
                malformed += key
            }
        }

        val executionModeState = metadataString(LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE)
        if (executionModeState != null &&
            LocalExecutionModeGate.ExecutionModeState.fromWireValue(executionModeState) == null
        ) {
            malformed += LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE
        }

        val modeState = metadataString("mode_state")
        if (modeState != null && modeState !in VALID_MODE_STATE_VALUES) {
            malformed += "mode_state"
        }

        val modeReadinessState = metadataString("mode_readiness_state")
        if (modeReadinessState != null && modeReadinessState !in VALID_MODE_READINESS_VALUES) {
            malformed += "mode_readiness_state"
        }

        val localIntelligenceStatus = metadataString("local_intelligence_status")
        if (localIntelligenceStatus != null &&
            LocalIntelligenceCapabilityStatus.entries.none { it.wireValue == localIntelligenceStatus }
        ) {
            malformed += "local_intelligence_status"
        }

        val schemaVersion = metadataString(LocalExecutionModeGate.KEY_SCHEMA_VERSION)
        if (schemaVersion != null && schemaVersion != LocalExecutionModeGate.SCHEMA_VERSION) {
            malformed += LocalExecutionModeGate.KEY_SCHEMA_VERSION
        }

        val transitioningTo = metadataString(LocalExecutionModeGate.KEY_TRANSITIONING_TO)
        if (transitioningTo != null &&
            LocalExecutionModeGate.ExecutionModeState.fromWireValue(transitioningTo) == null
        ) {
            malformed += LocalExecutionModeGate.KEY_TRANSITIONING_TO
        }

        return malformed
    }

    /**
     * Returns cross-field canonical gate contract issues that would let Android emit a metadata
     * surface inconsistent with [LocalExecutionModeGate]'s canonical semantics.
     */
    fun canonicalGateContractIssues(): List<String> {
        val issues = mutableListOf<String>()
        val executionModeState = LocalExecutionModeGate.ExecutionModeState.fromWireValue(
            metadataString(LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE)
        ) ?: return issues

        val semantics = LocalExecutionModeGate.capabilityMetadataSemanticsFor(executionModeState)
        val modeState = metadataString("mode_state")
        if (modeState != null && modeState != semantics.modeState) {
            issues += "mode_state_mismatch_for_execution_mode_state"
        }

        val modeReadinessState = metadataString("mode_readiness_state")
        if (modeReadinessState != null && modeReadinessState != semantics.modeReadinessState) {
            issues += "mode_readiness_state_mismatch_for_execution_mode_state"
        }

        val crossDeviceEligibility = metadataBoolean("cross_device_eligibility")
        if (crossDeviceEligibility != null &&
            crossDeviceEligibility != semantics.acceptsCrossDeviceTasks
        ) {
            issues += "cross_device_eligibility_mismatch_for_execution_mode_state"
        }

        val acceptsCrossDeviceTasks = metadataBoolean(LocalExecutionModeGate.KEY_ACCEPTS_CROSS_DEVICE_TASKS)
        if (acceptsCrossDeviceTasks != null &&
            acceptsCrossDeviceTasks != semantics.acceptsCrossDeviceTasks
        ) {
            issues += "mode_accepts_cross_device_tasks_mismatch_for_execution_mode_state"
        }

        val v2GovernanceActive = metadataBoolean(LocalExecutionModeGate.KEY_V2_GOVERNANCE_ACTIVE)
        if (v2GovernanceActive != null && v2GovernanceActive != semantics.v2GovernanceActive) {
            issues += "mode_v2_governance_active_mismatch_for_execution_mode_state"
        }

        val isHoldState = metadataBoolean(LocalExecutionModeGate.KEY_IS_HOLD_STATE)
        if (isHoldState != null && isHoldState != semantics.isHoldState) {
            issues += "mode_is_hold_state_mismatch_for_execution_mode_state"
        }

        val goalExecutionEnabled = metadataBoolean("goal_execution_enabled")
        val goalExecutionEligibility = metadataBoolean("goal_execution_eligibility")
        if (goalExecutionEnabled != null && goalExecutionEligibility != null &&
            goalExecutionEligibility != (semantics.acceptsCrossDeviceTasks && goalExecutionEnabled)
        ) {
            issues += "goal_execution_eligibility_mismatch_for_execution_mode_state"
        }

        val parallelExecutionEnabled = metadataBoolean("parallel_execution_enabled")
        val parallelExecutionEligibility = metadataBoolean("parallel_execution_eligibility")
        if (parallelExecutionEnabled != null && parallelExecutionEligibility != null &&
            parallelExecutionEligibility != (semantics.acceptsCrossDeviceTasks && parallelExecutionEnabled)
        ) {
            issues += "parallel_execution_eligibility_mismatch_for_execution_mode_state"
        }

        val transitioningTo = metadataString(LocalExecutionModeGate.KEY_TRANSITIONING_TO)
        when {
            executionModeState == LocalExecutionModeGate.ExecutionModeState.TRANSITIONING &&
                transitioningTo.isNullOrBlank() ->
                issues += "mode_transitioning_to_missing_for_transitioning_state"
            executionModeState != LocalExecutionModeGate.ExecutionModeState.TRANSITIONING &&
                !transitioningTo.isNullOrBlank() ->
                issues += "mode_transitioning_to_present_for_stable_state"
        }

        return issues
    }

    /**
     * Returns `true` when canonical gate metadata is complete, well-formed, and semantically
     * aligned with [LocalExecutionModeGate]'s canonical execution-mode contract.
     */
    fun validateCanonicalGateMetadata(): Boolean =
        missingCanonicalGateMetadataKeys().isEmpty() &&
            malformedCanonicalGateMetadataKeys().isEmpty() &&
            canonicalGateContractIssues().isEmpty()

    /**
     * Builds a stable Android-side metadata-evidence surface that summarizes capability-report
     * metadata completeness and semantic validity for runtime/governance observability.
     */
    fun metadataEvidenceSurface(): Map<String, Any> {
        val missingRequired = missingMetadataKeys().toList().sorted()
        val missingCanonical = missingCanonicalGateMetadataKeys().toList().sorted()
        val malformedCanonical = malformedCanonicalGateMetadataKeys().toList().sorted()
        val canonicalContractIssues = canonicalGateContractIssues().sorted()
        val missingScheduling = missingSchedulingBasisKeys().toList().sorted()
        return mapOf(
            KEY_METADATA_REQUIRED_COMPLETE to missingRequired.isEmpty(),
            KEY_METADATA_CANONICAL_GATE_COMPLETE to missingCanonical.isEmpty(),
            KEY_METADATA_CANONICAL_GATE_VALID to malformedCanonical.isEmpty() &&
                canonicalContractIssues.isEmpty(),
            KEY_METADATA_SCHEDULING_BASIS_COMPLETE to missingScheduling.isEmpty(),
            KEY_METADATA_MISSING_REQUIRED_KEYS to missingRequired,
            KEY_METADATA_MISSING_CANONICAL_GATE_KEYS to missingCanonical,
            KEY_METADATA_MALFORMED_CANONICAL_GATE_KEYS to malformedCanonical,
            KEY_METADATA_CANONICAL_GATE_CONTRACT_ISSUES to canonicalContractIssues,
            KEY_METADATA_MISSING_SCHEDULING_BASIS_KEYS to missingScheduling
        )
    }

    private fun metadataString(key: String): String? = metadata[key] as? String

    private fun metadataBoolean(key: String): Boolean? = metadata[key] as? Boolean

    companion object {
        /**
         * The canonical set of metadata keys that every `capability_report` payload
         * **must** contain for scheduling/readiness evaluation at the gateway.
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

        /**
         * The canonical set of scheduling-basis metadata keys introduced in PR-6
         * (post-#533 dual-repo runtime unification — Canonical Device Capability &
         * Scheduling Basis).
         *
         * These keys are produced by
         * [com.ufo.lumiv.capability.AndroidCapabilityVector.toSchedulingMetadata] and are
         * distinct from (non-overlapping with) both [REQUIRED_METADATA_KEYS] and the
         * runtime-host keys defined on
         * [com.ufo.lumiv.runtime.RuntimeHostDescriptor].
         *
         * | Key                                | Type    | Meaning |
         * |------------------------------------|---------|---------|
         * | `scheduling_local_eligible`        | Boolean | Device is eligible for local autonomous execution. |
         * | `scheduling_cross_device_eligible` | Boolean | Device is eligible for cross-device participation. |
         * | `scheduling_parallel_subtask_eligible` | Boolean | Device can accept parallel subtask assignments. |
         * | `scheduling_execution_dimensions`  | String  | Comma-separated active [com.ufo.lumiv.capability.AndroidCapabilityVector.ExecutionDimension] wire values. |
         *
         * When present, these keys give the gateway a pre-computed, Android-side scheduling
         * signal that avoids re-deriving eligibility from raw flags on the server side.
         */
        val SCHEDULING_BASIS_METADATA_KEYS: Set<String> = setOf(
            "scheduling_local_eligible",
            "scheduling_cross_device_eligible",
            "scheduling_parallel_subtask_eligible",
            "scheduling_execution_dimensions"
        )

        /**
         * Canonical Android gate metadata keys expected on capability_report metadata for
         * cross-repo governance/orchestration gating.
         *
         * This is intentionally additive relative to [REQUIRED_METADATA_KEYS].
         */
        val CANONICAL_GATE_METADATA_KEYS: Set<String> = setOf(
            "degraded_mode",
            "mode_state",
            "mode_readiness_state",
            "cross_device_eligibility",
            "goal_execution_eligibility",
            "parallel_execution_eligibility",
            "local_intelligence_status",
            "local_inference_ready",
            "local_inference_available",
            LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE,
            LocalExecutionModeGate.KEY_ACCEPTS_CROSS_DEVICE_TASKS,
            LocalExecutionModeGate.KEY_V2_GOVERNANCE_ACTIVE,
            LocalExecutionModeGate.KEY_IS_HOLD_STATE,
            LocalExecutionModeGate.KEY_DEGRADATION_REASONS,
            LocalExecutionModeGate.KEY_SEMANTIC_TAG,
            LocalExecutionModeGate.KEY_SCHEMA_VERSION
        )

        val BOOLEAN_CANONICAL_GATE_METADATA_KEYS: Set<String> = setOf(
            "degraded_mode",
            "cross_device_eligibility",
            "goal_execution_eligibility",
            "parallel_execution_eligibility",
            "local_inference_ready",
            "local_inference_available",
            LocalExecutionModeGate.KEY_ACCEPTS_CROSS_DEVICE_TASKS,
            LocalExecutionModeGate.KEY_V2_GOVERNANCE_ACTIVE,
            LocalExecutionModeGate.KEY_IS_HOLD_STATE
        )

        val STRING_CANONICAL_GATE_METADATA_KEYS: Set<String> = setOf(
            "mode_state",
            "mode_readiness_state",
            "local_intelligence_status",
            LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE,
            LocalExecutionModeGate.KEY_DEGRADATION_REASONS,
            LocalExecutionModeGate.KEY_SEMANTIC_TAG,
            LocalExecutionModeGate.KEY_SCHEMA_VERSION,
            LocalExecutionModeGate.KEY_TRANSITIONING_TO
        )

        private val VALID_MODE_STATE_VALUES: Set<String> = setOf("local_only", "cross_device")
        private val VALID_MODE_READINESS_VALUES: Set<String> = setOf("ready", "degraded")

        // Android runtime metadata-evidence summary keys (additive observability surface).
        const val KEY_METADATA_REQUIRED_COMPLETE = "android_metadata_required_complete"
        const val KEY_METADATA_CANONICAL_GATE_COMPLETE = "android_metadata_canonical_gate_complete"
        const val KEY_METADATA_CANONICAL_GATE_VALID = "android_metadata_canonical_gate_valid"
        const val KEY_METADATA_SCHEDULING_BASIS_COMPLETE = "android_metadata_scheduling_basis_complete"
        const val KEY_METADATA_MISSING_REQUIRED_KEYS = "android_metadata_missing_required_keys"
        const val KEY_METADATA_MISSING_CANONICAL_GATE_KEYS = "android_metadata_missing_canonical_gate_keys"
        const val KEY_METADATA_MALFORMED_CANONICAL_GATE_KEYS = "android_metadata_malformed_canonical_gate_keys"
        const val KEY_METADATA_CANONICAL_GATE_CONTRACT_ISSUES = "android_metadata_canonical_gate_contract_issues"
        const val KEY_METADATA_MISSING_SCHEDULING_BASIS_KEYS = "android_metadata_missing_scheduling_basis_keys"
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
    val diagnostic_schema_version: String? = null,
    val diagnostic_domain: String? = null,
    val diagnostic_reason: String? = null,
    val local_cause: String? = null,
    val uplink_semantic_boundary_class: String? = null,
    val operator_projection_class: String? = null,
    val diagnostics_failure_explanation_schema_version: String? = null,
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

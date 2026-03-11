package com.ufo.galaxy.data

/**
 * AIP v3 message type identifiers for the cloud-edge task pipeline.
 *
 * Step [3]  Android -> Gateway : TASK_SUBMIT
 * Step [6]  Gateway -> Android : TASK_ASSIGN
 * Step [8]  Android -> Gateway : TASK_RESULT / COMMAND_RESULT
 */
enum class AipV3MessageType(val value: String) {
    TASK_SUBMIT("task_submit"),
    TASK_ASSIGN("task_assign"),
    TASK_RESULT("task_result"),
    COMMAND_RESULT("command_result")
}

/**
 * Execution status returned by the Android edge agent.
 */
enum class TaskExecutionStatus(val value: String) {
    SUCCESS("success"),
    ERROR("error"),
    TIMEOUT("timeout"),
    CANCELLED("cancelled")
}

/**
 * Step [3] – Uplink payload: Android -> Gateway (AIP v3).
 * Carries the user's natural-language task text and session context.
 * No coordinate fields; the gateway is responsible only for intent understanding.
 */
data class TaskSubmitPayload(
    val task_text: String,
    val device_id: String,
    val session_id: String,
    val context: Map<String, String> = emptyMap()
)

/**
 * Step [6] – Downlink payload: Gateway -> Android (AIP v3).
 * Carries the task goal and execution constraints.
 * Must NOT contain x/y coordinates; coordinate resolution is local-only.
 *
 * @param task_id          Unique task identifier.
 * @param goal             High-level natural-language objective for the local agent.
 * @param constraints      Optional list of natural-language constraint strings.
 * @param max_steps        Maximum number of action steps the local agent may attempt.
 * @param require_local_agent True when the edge device must execute locally via
 *                         its own Planner/Grounding/Runtime pipeline.
 */
data class TaskAssignPayload(
    val task_id: String,
    val goal: String,
    val constraints: List<String> = emptyList(),
    val max_steps: Int,
    val require_local_agent: Boolean
)

/**
 * Step [8] – Task-level result uplink: Android -> Gateway (AIP v3).
 * Reports the outcome of a full task execution attempt.
 *
 * @param task_id   Echoed from the originating [TaskAssignPayload].
 * @param step_id   Index of the last executed step (1-based string).
 * @param status    Final execution status.
 * @param error     Human-readable error description, populated when status == ERROR.
 * @param snapshot  Optional Base64-encoded JPEG screenshot of the final UI state,
 *                  used by the gateway (step [9]) for cloud-side correction.
 */
data class TaskResultPayload(
    val task_id: String,
    val step_id: String,
    val status: TaskExecutionStatus,
    val error: String? = null,
    val snapshot: String? = null
)

/**
 * Step [8] – Command/step-level result uplink: Android -> Gateway (AIP v3).
 * Reports the outcome of a single action step within a task.
 *
 * @param task_id   Echoed from the originating [TaskAssignPayload].
 * @param step_id   Index of this individual step (1-based string).
 * @param action    Textual name of the action that was attempted.
 * @param status    Step-level execution status.
 * @param error     Human-readable error description when status == ERROR.
 * @param snapshot  Optional Base64-encoded JPEG screenshot captured after this step.
 */
data class CommandResultPayload(
    val task_id: String,
    val step_id: String,
    val action: String,
    val status: TaskExecutionStatus,
    val error: String? = null,
    val snapshot: String? = null
)

/**
 * Envelope wrapper for all AIP v3 messages exchanged over the WebSocket channel.
 */
data class AipV3Envelope(
    val version: String = "3.0",
    val type: AipV3MessageType,
    val payload: Any,
    val timestamp: Long = System.currentTimeMillis(),
    val session_id: String? = null,
    val device_id: String? = null
)

package com.ufo.galaxy.protocol

/**
 * AIP v3 message type identifiers, mirroring server-side MsgType enum exactly.
 * Covers all message types used across the cloud-edge task pipeline.
 */
enum class MsgType(val value: String) {
    TASK_SUBMIT("task_submit"),
    TASK_ASSIGN("task_assign"),
    TASK_RESULT("task_result"),
    COMMAND_RESULT("command_result"),
    DEVICE_REGISTER("device_register"),
    CAPABILITY_REPORT("capability_report"),
    HEARTBEAT("heartbeat"),
    HEARTBEAT_ACK("heartbeat_ack"),
    GOAL_EXECUTION("goal_execution"),
    PARALLEL_SUBTASK("parallel_subtask"),
    GOAL_RESULT("goal_result"),
    /** Downlink: server requests cancellation of a running task or parallel subtask. */
    TASK_CANCEL("task_cancel"),
    /** Uplink: device acknowledges the cancellation request. */
    CANCEL_RESULT("cancel_result")
}

/**
 * AIP v3 message envelope with [correlation_id] for request/response pairing.
 * Field names mirror the server-side AipMessage structure exactly.
 *
 * @param type           Message type identifier.
 * @param payload        Typed payload object (TaskSubmitPayload, TaskAssignPayload, etc.).
 * @param correlation_id Echoes the originating [TaskAssignPayload.task_id] in replies.
 * @param version        Protocol version; always "3.0".
 * @param timestamp      Unix epoch millis auto-set at construction.
 * @param session_id     Optional session identifier.
 * @param device_id      Optional device identifier.
 * @param trace_id       End-to-end trace identifier propagated across all hops (Android →
 *                       Gateway → Agent Runtime and back). Generated once per task; echoed
 *                       unchanged in every message that belongs to the same execution chain.
 *                       Consumers may use this for full-chain observability and log correlation.
 * @param route_mode     Routing path taken for this message: `"local"` (cross-device OFF, or
 *                       local execution only) or `"cross_device"` (delegated to Gateway /
 *                       Agent Runtime). Preserved in every hop of the AIP v3 pipeline.
 */
data class AipMessage(
    val type: MsgType,
    val payload: Any,
    val correlation_id: String? = null,
    val version: String = "3.0",
    val timestamp: Long = System.currentTimeMillis(),
    val session_id: String? = null,
    val device_id: String? = null,
    val trace_id: String? = null,
    val route_mode: String? = null
)

/**
 * On-device screen snapshot captured after an action step.
 * [data] is Base64-encoded JPEG. Coordinates never leave the device.
 *
 * @param data      Base64-encoded JPEG screenshot.
 * @param width     Screen width in pixels.
 * @param height    Screen height in pixels.
 * @param timestamp Capture time in Unix epoch millis.
 */
data class Snapshot(
    val data: String,
    val width: Int,
    val height: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Context attached to a [TaskSubmitPayload] for device and session state.
 *
 * @param locale          Current device locale (e.g., "zh-CN").
 * @param app_foreground  Package name of the foreground application.
 * @param extra           Arbitrary key-value pairs for extensibility.
 */
data class TaskSubmitContext(
    val locale: String? = null,
    val app_foreground: String? = null,
    val extra: Map<String, String> = emptyMap()
)

/**
 * Step [3] – Uplink payload: Android → Gateway (AIP v3).
 * Carries the user's natural-language task text and session context.
 * No coordinate fields; the gateway performs intent understanding only.
 *
 * @param task_text  Natural-language task description from the user.
 * @param device_id  Unique device identifier.
 * @param session_id Active session identifier.
 * @param context    Optional device and session context.
 */
data class TaskSubmitPayload(
    val task_text: String,
    val device_id: String,
    val session_id: String,
    val context: TaskSubmitContext = TaskSubmitContext()
)

/**
 * Step [6] – Downlink payload: Gateway → Android (AIP v3).
 * Carries the task goal and execution constraints.
 * Must NOT contain x/y coordinates; coordinate resolution is local-only.
 *
 * @param task_id             Unique task identifier.
 * @param goal                High-level natural-language objective for the local agent.
 * @param constraints         Optional list of natural-language constraint strings.
 * @param max_steps           Maximum number of action steps the local agent may attempt.
 * @param require_local_agent True when the edge device must execute locally.
 */
data class TaskAssignPayload(
    val task_id: String,
    val goal: String,
    val constraints: List<String> = emptyList(),
    val max_steps: Int,
    val require_local_agent: Boolean
)

/**
 * Step-level result accumulated by [EdgeExecutor] during task execution.
 * Mirrors the server-side StepResult structure exactly.
 *
 * @param step_id       1-based step index as a string.
 * @param action        Symbolic action name executed (e.g., "tap", "scroll").
 * @param success       Whether the action completed without error.
 * @param error         Human-readable error description when [success] is false.
 * @param snapshot      Optional on-device screenshot captured after this step.
 * @param latency_ms    Wall-clock execution time for this step in milliseconds.
 * @param snapshot_ref  Optional reference identifier for the snapshot (e.g., file path or hash).
 */
data class StepResult(
    val step_id: String,
    val action: String,
    val success: Boolean,
    val error: String? = null,
    val snapshot: Snapshot? = null,
    val latency_ms: Long = 0L,
    val snapshot_ref: String? = null
)

/**
 * Step [8] – Task-level result uplink: Android → Gateway (AIP v3).
 * [correlation_id] is set to the originating [TaskAssignPayload.task_id].
 *
 * @param task_id        Echoed from [TaskAssignPayload].
 * @param correlation_id Set to [task_id] for reply routing.
 * @param status         Final execution status ("success" | "error" | "cancelled").
 * @param steps          Ordered list of step results accumulated during execution.
 * @param error          Human-readable error description when status is "error".
 * @param snapshot       Optional final screen snapshot for cloud-side correction.
 */
data class TaskResultPayload(
    val task_id: String,
    val correlation_id: String? = null,
    val status: String,
    val steps: List<StepResult> = emptyList(),
    val error: String? = null,
    val snapshot: Snapshot? = null
)

/**
 * Step [8] – Command/step-level result uplink: Android → Gateway (AIP v3).
 *
 * @param task_id  Echoed from [TaskAssignPayload].
 * @param step_id  1-based step index as a string.
 * @param action   Symbolic action name.
 * @param status   Step-level execution status.
 * @param error    Human-readable error description when status is "error".
 * @param snapshot Optional screenshot captured after this step.
 */
data class CommandResultPayload(
    val task_id: String,
    val step_id: String,
    val action: String,
    val status: String,
    val error: String? = null,
    val snapshot: Snapshot? = null
)

/**
 * Downlink payload for a high-level autonomous goal or parallel subtask.
 * Sent by the gateway when the device has [autonomous_goal_execution] capability.
 *
 * For [MsgType.GOAL_EXECUTION] and [MsgType.PARALLEL_SUBTASK].
 *
 * @param task_id       Unique task identifier.
 * @param goal          Natural-language objective for the local agent.
 * @param constraints   Optional natural-language constraints.
 * @param max_steps     Maximum action steps the local agent may attempt (default 10).
 * @param group_id      Parallel-group identifier; non-null for parallel_subtask.
 * @param subtask_index Zero-based index of this subtask within the group.
 * @param timeout_ms    Per-task execution timeout in milliseconds. 0 = use default
 *                      ([DEFAULT_TIMEOUT_MS]). Capped at [MAX_TIMEOUT_MS].
 */
data class GoalExecutionPayload(
    val task_id: String,
    val goal: String,
    val constraints: List<String> = emptyList(),
    val max_steps: Int = 10,
    val group_id: String? = null,
    val subtask_index: Int? = null,
    val timeout_ms: Long = 0L
) {
    companion object {
        /** Default per-task timeout when [timeout_ms] is 0 or not specified (30 s). */
        const val DEFAULT_TIMEOUT_MS = 30_000L
        /** Hard upper cap for any per-task timeout (5 min). */
        const val MAX_TIMEOUT_MS = 300_000L
    }

    /**
     * Effective timeout to use: [timeout_ms] when positive, otherwise [DEFAULT_TIMEOUT_MS].
     * Always capped at [MAX_TIMEOUT_MS].
     */
    val effectiveTimeoutMs: Long
        get() = if (timeout_ms > 0L) timeout_ms.coerceAtMost(MAX_TIMEOUT_MS)
                else DEFAULT_TIMEOUT_MS
}

/**
 * Uplink result for [MsgType.GOAL_EXECUTION] and [MsgType.PARALLEL_SUBTASK].
 * Includes all fields required for parallel-group convergence on the server side.
 *
 * @param task_id        Echoed from [GoalExecutionPayload].
 * @param correlation_id Set to [task_id] for reply routing.
 * @param status         Final status ("success" | "error" | "cancelled" | "disabled").
 * @param result         Human-readable success summary (gateway aggregation: summary).
 * @param details        Additional details or error description.
 * @param group_id       Echoed from [GoalExecutionPayload.group_id].
 * @param subtask_index  Echoed from [GoalExecutionPayload.subtask_index].
 * @param latency_ms     Wall-clock execution time in milliseconds.
 * @param device_id      Reporting device identifier.
 * @param device_role    Logical device role (e.g., "phone", "tablet", "hub") from [AppSettings].
 * @param steps          Step-level results accumulated during execution.
 * @param outputs        High-level string outputs collected during execution (gateway aggregation).
 * @param error          Human-readable error when status is "error" or "disabled".
 */
data class GoalResultPayload(
    val task_id: String,
    val correlation_id: String? = null,
    val status: String,
    val result: String? = null,
    val details: String? = null,
    val group_id: String? = null,
    val subtask_index: Int? = null,
    val latency_ms: Long = 0L,
    val device_id: String = "",
    val device_role: String = "",
    val steps: List<StepResult> = emptyList(),
    val outputs: List<String> = emptyList(),
    val error: String? = null
)

/**
 * Downlink payload for [MsgType.TASK_CANCEL].
 * Sent by the gateway to request cancellation of a running task or parallel subtask.
 *
 * @param task_id        Unique task identifier to cancel.
 * @param group_id       Optional parallel-group identifier; present for parallel_subtask cancels.
 * @param subtask_index  Optional zero-based subtask index within the group.
 */
data class TaskCancelPayload(
    val task_id: String,
    val group_id: String? = null,
    val subtask_index: Int? = null
)

/**
 * Uplink acknowledgement for [MsgType.CANCEL_RESULT].
 * Sent by the device in response to a [MsgType.TASK_CANCEL] request.
 *
 * @param task_id        Echoed from [TaskCancelPayload.task_id].
 * @param correlation_id Set to [task_id] for reply routing.
 * @param status         "cancelled" if the task was successfully cancelled;
 *                       "no_op" if the task was not found (already completed or never started).
 * @param was_running    True when the task was actively executing at the time of the cancel request.
 * @param group_id       Echoed from [TaskCancelPayload.group_id].
 * @param subtask_index  Echoed from [TaskCancelPayload.subtask_index].
 * @param device_id      Reporting device identifier.
 * @param error          Optional human-readable detail when status is "no_op".
 */
data class CancelResultPayload(
    val task_id: String,
    val correlation_id: String? = null,
    val status: String,
    val was_running: Boolean,
    val group_id: String? = null,
    val subtask_index: Int? = null,
    val device_id: String = "",
    val error: String? = null
)

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
    /**
     * Uplink: device reports the result of a goal_execution / task_assign / parallel_subtask.
     * 对应 Python 侧 _handle_goal_execution_result。
     */
    GOAL_EXECUTION_RESULT("goal_execution_result"),
    /** Downlink: server requests cancellation of a running task or parallel subtask. */
    TASK_CANCEL("task_cancel"),
    /** Uplink: device acknowledges the cancellation request. */
    CANCEL_RESULT("cancel_result"),
    /** Uplink: device reports joining a mesh session. */
    MESH_JOIN("mesh_join"),
    /** Uplink: device reports leaving a mesh session. */
    MESH_LEAVE("mesh_leave"),
    /** Uplink: device reports aggregated parallel-subtask results for a mesh session. */
    MESH_RESULT("mesh_result"),
    /** Uplink: structured diagnostic payload for task failure classification (Loop 1/2). */
    DIAGNOSTICS_PAYLOAD("diagnostics_payload"),

    // ── Advanced / low-priority capability channels (PR-4 minimal-compat stubs) ──────────
    // These types are recognised by the AIP v3 model so inbound messages are never silently
    // dropped or treated as raw text. Full business-logic implementations are TODO; each
    // currently triggers a structured log entry and, where relevant, an ack reply.

    /** Downlink: gateway relays a message from another node to this device.
     *  @status minimal-compat — logged + ack sent; no relay-chain logic yet. */
    RELAY("relay"),
    /** Downlink: gateway requests this device to forward a task to another peer.
     *  @status minimal-compat — logged; no peer-routing logic yet. */
    FORWARD("forward"),
    /** Downlink: gateway sends a directed reply to a previous device-originated request.
     *  @status minimal-compat — logged; no reply-state machine yet. */
    REPLY("reply"),
    /** Downlink/Uplink: delivery acknowledgement for critical protocol messages.
     *  @status minimal-compat — inbound logged; outbound [AckPayload] available for callers. */
    ACK("ack"),

    /** Downlink: gateway requests hybrid (partial-local / partial-remote) task execution.
     *  @status minimal-compat — payload parsed into [HybridExecutePayload]; logged; degrade
     *  reply sent because full hybrid executor is not yet implemented. */
    HYBRID_EXECUTE("hybrid_execute"),
    /** Uplink: device reports the result of a hybrid execution task.
     *  @status minimal-compat — [HybridResultPayload] model available; send path present. */
    HYBRID_RESULT("hybrid_result"),
    /** Uplink: device signals that a hybrid task was downgraded (e.g. to pure local mode).
     *  @status minimal-compat — [HybridDegradePayload] model available; send path present. */
    HYBRID_DEGRADE("hybrid_degrade"),

    /** Downlink: gateway sends a retrieval-augmented generation query to the device.
     *  @status minimal-compat — logged; empty result returned; full RAG pipeline TODO. */
    RAG_QUERY("rag_query"),
    /** Uplink: device returns the result of a RAG query.
     *  @status minimal-compat — model available; send path present. */
    RAG_RESULT("rag_result"),

    /** Downlink: gateway requests on-device code execution (e.g. Python snippet).
     *  @status minimal-compat — logged; error result returned; sandbox TODO. */
    CODE_EXECUTE("code_execute"),
    /** Uplink: device returns the result of a code execution request.
     *  @status minimal-compat — model available; send path present. */
    CODE_RESULT("code_result"),

    /** Downlink: gateway announces a new peer device joining the session.
     *  @status minimal-compat — logged; no peer-state tracking yet. */
    PEER_ANNOUNCE("peer_announce"),
    /** Downlink/Uplink: peer capability exchange between devices.
     *  @status minimal-compat — logged; no capability-negotiation logic yet. */
    PEER_EXCHANGE("peer_exchange"),
    /** Downlink: gateway pushes a mesh topology update (node list / adjacency).
     *  @status minimal-compat — logged; no topology-aware routing yet. */
    MESH_TOPOLOGY("mesh_topology"),

    /** Downlink: gateway sends a wake event to resume an idle or suspended device.
     *  @status minimal-compat — logged; ack sent; no suspend/resume state machine yet. */
    WAKE_EVENT("wake_event"),
    /** Downlink: gateway requests session state migration to another device.
     *  @status minimal-compat — logged; degrade/reject reply sent; full migration TODO. */
    SESSION_MIGRATE("session_migrate"),

    /** Downlink/Uplink: coordination synchronisation tick between coordinator and participants.
     *  @status minimal-compat — logged; ack sent; no sync-state machine yet. */
    COORD_SYNC("coord_sync"),
    /** Downlink: gateway broadcasts a message to all devices in a session.
     *  @status minimal-compat — logged; no broadcast fan-out yet. */
    BROADCAST("broadcast"),
    /** Downlink: gateway requests a distributed resource lock.
     *  @status minimal-compat — logged; ack sent; no lock-manager yet. */
    LOCK("lock"),
    /** Downlink: gateway releases a distributed resource lock.
     *  @status minimal-compat — logged; ack sent; no lock-manager yet. */
    UNLOCK("unlock"),

    // ── PR-3: Canonical cross-device handoff / takeover contract ─────────────────────────────
    // These two types canonicalise the Android ↔ main-runtime takeover path introduced in
    // the post-#533 dual-repo unification track.  A TAKEOVER_REQUEST arrives when the main
    // runtime (PC/OpenClawd) wants Android to accept and continue executing an in-flight task.
    // TAKEOVER_RESPONSE is the Android-side acknowledgement / rejection reply.

    /** Downlink: main runtime asks Android to take over execution of an in-flight task.
     *  Payload model: [com.ufo.galaxy.agent.TakeoverRequestEnvelope].
     *  @status pr3 — payload parsed; ack sent; full takeover executor deferred to PR-5. */
    TAKEOVER_REQUEST("takeover_request"),

    /** Uplink: Android responds to a [TAKEOVER_REQUEST] with acceptance or rejection.
     *  Payload model: [com.ufo.galaxy.agent.TakeoverResponseEnvelope].
     *  @status pr3 — model available; send path present via GalaxyConnectionService. */
    TAKEOVER_RESPONSE("takeover_response"),

    // ── PR-16: Delegated execution signal outbound transport ──────────────────────────────
    // Uplink signal emitted by Android during and after delegated task execution.
    // Carries ACK / PROGRESS / RESULT / TIMEOUT / CANCELLED lifecycle events with full
    // identity continuity so the main-repo tracker can reconcile state without ambiguity.

    /** Uplink: Android emits a delegated-execution lifecycle signal to the main runtime.
     *  Carries ACK / PROGRESS / RESULT (COMPLETED / FAILED / TIMEOUT / CANCELLED) events.
     *  Payload model: [DelegatedExecutionSignalPayload].
     *  @status pr16 — payload defined; send path present via GalaxyConnectionService. */
    DELEGATED_EXECUTION_SIGNAL("delegated_execution_signal");

    companion object {
        /**
         * Mapping from legacy / v2 outbound type strings to authoritative AIP v3 names.
         *
         * New code must use [MsgType] enum entries directly.  This map exists solely for
         * normalisation of legacy inputs (e.g. from stored preferences or third-party code)
         * before messages are sent via [com.ufo.galaxy.input.InputRouter], the sole canonical
         * cross-device uplink routing backbone.
         *
         * | Legacy string        | v3 equivalent      |
         * |----------------------|--------------------|
         * | `registration`       | `device_register`  |
         * | `register`           | `device_register`  |
         * | `heartbeat`          | `heartbeat`        |
         * | `command`            | `task_assign`      |
         * | `command_result`     | `command_result`   |
         * | `task_execute`       | `task_assign`      |
         * | `task_status_query`  | `task_assign`      |
         */
        val LEGACY_TYPE_MAP: Map<String, String> = mapOf(
            "registration"      to DEVICE_REGISTER.value,
            "register"          to DEVICE_REGISTER.value,
            "heartbeat"         to HEARTBEAT.value,
            "command"           to TASK_ASSIGN.value,
            "command_result"    to COMMAND_RESULT.value,
            // Legacy task-management types mapped to the unified v3 task_assign path.
            // Receiving code must re-map these to task_assign internally (compatibility window).
            "task_execute"      to TASK_ASSIGN.value,
            "task_status_query" to TASK_ASSIGN.value
        )

        /**
         * Converts a legacy / v2 type string to its authoritative AIP v3 equivalent.
         *
         * Returns [legacyType] unchanged when it is already a v3 name or not listed in
         * [LEGACY_TYPE_MAP].
         */
        fun toV3Type(legacyType: String): String = LEGACY_TYPE_MAP[legacyType] ?: legacyType

        /** Backing O(1) lookup map for [fromValue]. Built once at class-load time. */
        private val VALUE_MAP: Map<String, MsgType> = entries.associateBy { it.value }

        /**
         * Looks up a [MsgType] by its wire-format [value] string.
         *
         * Returns `null` when [value] does not match any known type. Callers should
         * treat `null` as an unknown/future type and route to the fallback handler
         * rather than crashing or silently discarding the message.
         */
        fun fromValue(value: String): MsgType? = VALUE_MAP[value]

        /**
         * Set of advanced / low-priority message types added in PR-4.
         * These types receive minimal-compat handling (log + optional ack) in
         * [GalaxyWebSocketClient] and [GalaxyConnectionService].
         *
         * TODO(PR-5+): promote individual types to dedicated handlers as business
         * requirements are confirmed.
         */
        val ADVANCED_TYPES: Set<MsgType> = setOf(
            RELAY, FORWARD, REPLY, ACK,
            HYBRID_EXECUTE, HYBRID_RESULT, HYBRID_DEGRADE,
            RAG_QUERY, RAG_RESULT,
            CODE_EXECUTE, CODE_RESULT,
            PEER_ANNOUNCE, PEER_EXCHANGE, MESH_TOPOLOGY,
            WAKE_EVENT, SESSION_MIGRATE,
            COORD_SYNC, BROADCAST, LOCK, UNLOCK,
            TAKEOVER_REQUEST, TAKEOVER_RESPONSE
        )

        /**
         * Advanced types for which the device should send an [AckPayload] reply
         * to confirm receipt, even though full business logic is not yet implemented.
         */
        val ACK_ON_RECEIPT_TYPES: Set<MsgType> = setOf(
            RELAY, WAKE_EVENT, COORD_SYNC, LOCK, UNLOCK,
            TAKEOVER_REQUEST
        )
    }
}

/**
 * AIP v3 message envelope with [correlation_id] for request/response pairing.
 * Field names mirror the server-side AipMessage structure exactly.
 *
 * @param type           Message type identifier.
 * @param payload        Typed payload object (TaskSubmitPayload, TaskAssignPayload, etc.).
 * @param correlation_id Echoes the originating [TaskAssignPayload.task_id] in replies.
 * @param protocol       Wire-protocol identifier; always `"AIP/1.0"` for AIP v3 messages.
 * @param version        Protocol version; always "3.0".
 * @param timestamp      Unix epoch millis auto-set at construction.
 * @param session_id     Optional session identifier.
 * @param device_id      Optional device identifier.
 * @param trace_id       End-to-end trace identifier propagated across all hops (Android →
 *                       Gateway → Agent Runtime and back). Generated once per task; echoed
 *                       unchanged in every message that belongs to the same execution chain.
 *                       Consumers may use this for full-chain observability and log correlation.
 * @param route_mode          Routing path taken for this message: `"local"` (cross-device OFF,
 *                            or local execution only) or `"cross_device"` (delegated to Gateway
 *                            / Agent Runtime). Preserved in every hop of the AIP v3 pipeline.
 * @param runtime_session_id  Stable per-app-launch session identifier.  Generated once at
 *                            application startup via [java.util.UUID.randomUUID] and propagated
 *                            unchanged across every message in the same runtime session.
 *                            Consumers may use this to correlate all messages originating from
 *                            a single device run without relying on `session_id` or `trace_id`.
 * @param idempotency_key     Per-send unique key for safe message deduplication.  Should be
 *                            derived from `task_id + timestamp` (or a UUID) so that duplicate
 *                            sends can be detected and discarded by the gateway.
 * @param source_runtime_posture  Canonical source-device participation posture, aligned with
 *                            the server-side semantics from PR #533.  Valid values are defined
 *                            in [com.ufo.galaxy.runtime.SourceRuntimePosture]:
 *                            `"control_only"` (source is a control/initiator only) or
 *                            `"join_runtime"` (source also participates as a runtime executor).
 *                            Defaults to `null` for backwards compatibility; consumers must
 *                            treat `null` as equivalent to `"control_only"`.
 */
data class AipMessage(
    val type: MsgType,
    val payload: Any,
    val correlation_id: String? = null,
    val protocol: String = "AIP/1.0",
    val version: String = "3.0",
    val timestamp: Long = System.currentTimeMillis(),
    val session_id: String? = null,
    val device_id: String? = null,
    val trace_id: String? = null,
    val route_mode: String? = null,
    val runtime_session_id: String? = null,
    val idempotency_key: String? = null,
    val source_runtime_posture: String? = null
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
 * @param task_id    Unique task identifier; echoed from [AipMessage.correlation_id] so the
 *                   gateway can correlate the submit with the subsequent task_assign reply.
 *                   Defaults to an empty string when the caller does not supply one (e.g. in
 *                   tests), but **must** be populated for every real outbound message.
 * @param context    Optional device and session context.
 * @param source_runtime_posture  Canonical source-device participation posture carried from
 *                   the initiating context. Valid values: `"control_only"` (default, source is
 *                   a pure control/initiator) or `"join_runtime"` (source also participates as
 *                   a runtime executor). Defaults to `null`; the gateway treats `null` as
 *                   `"control_only"` for backwards compatibility.
 */
data class TaskSubmitPayload(
    val task_text: String,
    val device_id: String,
    val session_id: String,
    val task_id: String = "",
    val context: TaskSubmitContext = TaskSubmitContext(),
    val source_runtime_posture: String? = null
) {
    /**
     * Returns `true` when all required fields are non-blank.
     *
     * Callers (e.g. [com.ufo.galaxy.input.InputRouter]) should call [validate] before
     * sending the payload and reject messages that fail.  For a human-readable description
     * of the first failing field, use [validationError].
     */
    fun validate(): Boolean = task_text.isNotBlank() && device_id.isNotBlank() && session_id.isNotBlank()

    /**
     * Returns a debug-friendly description of the first failing required field, or `null` when
     * [validate] passes.  Intended for logging / error messages only; not for UI display.
     */
    fun validationError(): String? = when {
        task_text.isBlank()  -> "task_text is blank"
        device_id.isBlank()  -> "device_id is blank"
        session_id.isBlank() -> "session_id is blank"
        else                 -> null
    }
}

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
 * @param source_runtime_posture  Canonical source-device participation posture forwarded from
 *                            the originating task_submit. Valid values: `"control_only"` or
 *                            `"join_runtime"`. Defaults to `null`; treat `null` as
 *                            `"control_only"` for backwards compatibility.
 */
data class TaskAssignPayload(
    val task_id: String,
    val goal: String,
    val constraints: List<String> = emptyList(),
    val max_steps: Int,
    val require_local_agent: Boolean,
    val source_runtime_posture: String? = null
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
 * @param trace_id       End-to-end trace identifier echoed from the originating [task_assign]
 *                       envelope. Populated by [GalaxyConnectionService] before sending.
 *                       Generated locally when the inbound task_assign carried none.
 * @param device_id      Identifier of the device that executed the task.
 *                       Populated by [GalaxyConnectionService] before sending.
 * @param result_summary Human-readable one-line outcome description.
 *                       Populated by [GalaxyConnectionService] before sending.
 */
data class TaskResultPayload(
    val task_id: String,
    val correlation_id: String? = null,
    val status: String,
    val steps: List<StepResult> = emptyList(),
    val error: String? = null,
    val snapshot: Snapshot? = null,
    val trace_id: String? = null,
    val device_id: String = "",
    val result_summary: String? = null
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
 * @param source_runtime_posture  Canonical source-device participation posture forwarded from
 *                      the originating request. Valid values: `"control_only"` or
 *                      `"join_runtime"`. Defaults to `null`; treat `null` as `"control_only"`
 *                      for backwards compatibility.
 */
data class GoalExecutionPayload(
    val task_id: String,
    val goal: String,
    val constraints: List<String> = emptyList(),
    val max_steps: Int = 10,
    val group_id: String? = null,
    val subtask_index: Int? = null,
    val timeout_ms: Long = 0L,
    val source_runtime_posture: String? = null
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
 * @param source_runtime_posture  Echoed from [GoalExecutionPayload.source_runtime_posture] so
 *                       the gateway can associate the result with the originating posture context.
 *                       Defaults to `null` for backwards compatibility.
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
    val error: String? = null,
    val source_runtime_posture: String? = null
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

/**
 * Uplink payload for [MsgType.DIAGNOSTICS_PAYLOAD].
 * Carries structured failure information for server-side Loop 1 (self-repair) and
 * Loop 2 (learning feedback) classification.
 *
 * @param task_id       Failing task's unique identifier.
 * @param device_id     Reporting device identifier.
 * @param node_name     Name of the reporting node.
 * @param error_type    Error classification (e.g., "network_timeout", "permission_denied").
 * @param error_context Specific error description or stack summary.
 * @param timestamp     Unix epoch millis at the time of the failure.
 */
data class DiagnosticsPayload(
    val task_id: String,
    val device_id: String,
    val node_name: String,
    val error_type: String,
    val error_context: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Uplink payload for [MsgType.MESH_JOIN].
 * Sent when the device joins a mesh session to report participation readiness.
 *
 * @param mesh_id      Stable mesh session identifier shared by all participants.
 * @param device_id    Joining device identifier.
 * @param role         Role of this device in the mesh: "participant" or "coordinator".
 * @param capabilities Capability names this device contributes to the mesh.
 */
data class MeshJoinPayload(
    val mesh_id: String,
    val device_id: String,
    val role: String = "participant",
    val capabilities: List<String> = emptyList()
)

/**
 * Uplink payload for [MsgType.MESH_LEAVE].
 * Sent when the device leaves a mesh session (on disconnect or task completion).
 *
 * @param mesh_id   Mesh session identifier.
 * @param device_id Leaving device identifier.
 * @param reason    Reason for leaving: "disconnect", "task_complete", or "error".
 */
data class MeshLeavePayload(
    val mesh_id: String,
    val device_id: String,
    val reason: String = "disconnect"
)

/**
 * Per-device subtask summary included in [MeshResultPayload].
 *
 * @param device_id   Device that executed this subtask.
 * @param subtask_id  Subtask identifier (<groupId>_sub_<index>).
 * @param status      "success" or "error".
 * @param output      Optional result payload string from the device.
 * @param error       Error description when [status] is "error".
 */
data class MeshSubtaskResult(
    val device_id: String,
    val subtask_id: String,
    val status: String,
    val output: String? = null,
    val error: String? = null
)

/**
 * Uplink payload for [MsgType.MESH_RESULT].
 * Reports aggregated parallel-subtask results when all participants in a mesh session
 * have completed their subtasks.
 *
 * @param mesh_id     Mesh session identifier.
 * @param task_id     Associated top-level task identifier.
 * @param device_id   Device reporting the aggregation (usually the coordinator).
 * @param status      Aggregate status: "success", "partial", or "error".
 * @param results     Per-device subtask result summaries.
 * @param summary     Human-readable one-line aggregate outcome.
 * @param latency_ms  Wall-clock time from first subtask dispatch to last result (ms).
 */
data class MeshResultPayload(
    val mesh_id: String,
    val task_id: String,
    val device_id: String,
    val status: String,
    val results: List<MeshSubtaskResult> = emptyList(),
    val summary: String? = null,
    val latency_ms: Long = 0L
)

// ── PR-4 advanced-capability minimal payload models ───────────────────────────────────────
// These classes represent the AIP v3 wire format for the new low-priority capability
// channels.  Full business implementations are TODO; each class is intentionally minimal
// (only the fields required for correct ack/logging are mandatory).

/**
 * Uplink/Downlink acknowledgement payload for [MsgType.ACK].
 * Sent by the device to confirm receipt of a critical protocol message.
 *
 * @param message_id  Identifier of the message being acknowledged (mirrors the inbound
 *                    `message_id` envelope field, or the `task_id` when absent).
 * @param type_acked  The [MsgType.value] string of the message being acknowledged.
 * @param device_id   Acknowledging device identifier.
 * @param status      Always `"received"` for a simple receipt ack.
 */
data class AckPayload(
    val message_id: String,
    val type_acked: String,
    val device_id: String,
    val status: String = "received"
)

/**
 * Downlink payload for [MsgType.HYBRID_EXECUTE].
 * Requests partial-local / partial-remote execution of a task.
 *
 * @param task_id      Unique task identifier.
 * @param goal         Natural-language objective.
 * @param local_steps  Steps to be executed locally on the device.
 * @param remote_steps Steps to be delegated to the cloud/Agent Runtime.
 * @param timeout_ms   Per-task execution timeout (0 = use server default).
 */
data class HybridExecutePayload(
    val task_id: String,
    val goal: String,
    val local_steps: List<String> = emptyList(),
    val remote_steps: List<String> = emptyList(),
    val timeout_ms: Long = 0L
)

/**
 * Uplink payload for [MsgType.HYBRID_RESULT].
 * Reports the outcome of a [MsgType.HYBRID_EXECUTE] request.
 *
 * @param task_id        Echoed from [HybridExecutePayload].
 * @param correlation_id Set to [task_id] for reply routing.
 * @param status         "success" | "error" | "degraded".
 * @param local_result   Summary of locally-executed steps.
 * @param remote_result  Summary of remotely-executed steps.
 * @param device_id      Reporting device identifier.
 * @param error          Error description when status is "error".
 * @param latency_ms     Wall-clock execution time in milliseconds.
 */
data class HybridResultPayload(
    val task_id: String,
    val correlation_id: String? = null,
    val status: String,
    val local_result: String? = null,
    val remote_result: String? = null,
    val device_id: String = "",
    val error: String? = null,
    val latency_ms: Long = 0L
)

/**
 * Uplink payload for [MsgType.HYBRID_DEGRADE].
 * Signals that a [MsgType.HYBRID_EXECUTE] request was downgraded (e.g. to pure local
 * execution) because the remote component was unavailable.
 *
 * @param task_id       Echoed from [HybridExecutePayload].
 * @param correlation_id Set to [task_id] for reply routing.
 * @param reason        Human-readable reason for degradation.
 * @param fallback_mode Mode adopted after degradation: "local_only" or "remote_only".
 * @param device_id     Reporting device identifier.
 */
data class HybridDegradePayload(
    val task_id: String,
    val correlation_id: String? = null,
    val reason: String,
    val fallback_mode: String = "local_only",
    val device_id: String = ""
)

// ── PR-16: Delegated execution signal outbound payload ────────────────────────────────────

/**
 * Uplink payload for [MsgType.DELEGATED_EXECUTION_SIGNAL].
 *
 * Carries a delegated-execution lifecycle signal (ACK / PROGRESS / RESULT) from the
 * Android runtime to the main-repo host so the host's tracker can reconcile state.
 * All identity fields are echoed on every signal to allow the host to correlate signals
 * with its own dispatch record without maintaining session state on the wire.
 *
 * ### Required fields (always present)
 * @param signal_id              Stable UUID idempotency key for this emission.  The host
 *                               can discard duplicate deliveries with the same [signal_id].
 * @param emission_seq           Monotonic position in the per-execution signal sequence:
 *                               ACK=1, PROGRESS=2, RESULT=3.  Allows the host to detect
 *                               out-of-order delivery.
 * @param task_id                Task identifier echoed from the originating takeover request.
 * @param trace_id               End-to-end trace identifier for distributed tracing.
 * @param attached_session_id    Attached runtime session identifier this signal is scoped to.
 * @param device_id              Stable identifier of the Android device emitting the signal.
 * @param handoff_contract_version Handoff contract schema version from the originating unit.
 * @param signal_kind            Discriminator wire value: `"ack"`, `"progress"`, or `"result"`.
 * @param unit_id                Delegated-unit identifier from the originating takeover request.
 * @param step_count             Number of execution steps completed at signal emission time.
 * @param activation_status_hint Wire value of the current activation status at emission time.
 * @param timestamp_ms           Epoch-ms timestamp when this signal was produced on-device.
 *
 * ### Conditional fields
 * @param result_kind            Terminal outcome discriminator; present only for
 *                               [signal_kind] = `"result"` signals.  Wire values:
 *                               `"completed"`, `"failed"`, `"timeout"`, `"cancelled"`,
 *                               `"rejected"`.  `null` for ACK and PROGRESS signals.
 */
data class DelegatedExecutionSignalPayload(
    val signal_id: String,
    val emission_seq: Int,
    val task_id: String,
    val trace_id: String,
    val attached_session_id: String,
    val device_id: String,
    val handoff_contract_version: Int,
    val signal_kind: String,
    val unit_id: String,
    val step_count: Int,
    val activation_status_hint: String,
    val timestamp_ms: Long,
    val result_kind: String? = null
)

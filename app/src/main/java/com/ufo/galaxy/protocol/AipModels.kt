package com.ufo.galaxy.protocol

import com.ufo.galaxy.shared.protocol.MsgType

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
 *
 * ## PR-G: V2 observability/tracing metadata (PR-47 compatibility)
 * The following fields carry richer observability and cross-system tracing metadata
 * introduced by V2 production-grade observability.  All fields are optional so that
 * pre-V2 / pre-PR-47 senders remain compatible.  Receivers MUST accept these fields
 * without failure and MUST NOT rely on them for core routing or execution decisions.
 *
 * @param dispatch_trace_id   Optional cross-system dispatch trace correlation identifier.
 *                            When present, this value allows the full dispatch chain from
 *                            the V2 orchestrator through gateway to Android to be traced
 *                            with a single stable ID across structured log entries.
 *                            Android echoes this value in uplink result messages for
 *                            full-chain V2 observability correlation.  `null` for legacy
 *                            / pre-V2 senders; a blank value is treated as `null`.
 * @param session_correlation_id  Optional session-level correlation identifier propagated
 *                            across session transitions, handoffs, and recovery events
 *                            within the same logical session context.  Unlike [dispatch_trace_id]
 *                            (dispatch-chain scoped), this spans multiple dispatches.
 *                            Android MUST accept this field without failure.  `null` for
 *                            legacy senders.
 */
import com.google.gson.JsonElement
import com.google.gson.JsonNull

data class AipMessage(
    val type: MsgType,
    val payload: JsonElement = JsonNull,
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
    val source_runtime_posture: String? = null,
    // ── PR-G: V2 observability/tracing metadata (optional; null-safe for legacy senders) ──
    val dispatch_trace_id: String? = null,
    val session_correlation_id: String? = null
)

/**
 * PR-CROSS-SYNC: State event payload — V2 pushes phase transitions and state changes
 * to Android via this payload.
 *
 * @param event_category  Event category: "phase" | "task" | "skill" | "device" | "mesh".
 * @param event_action    Event action: e.g. "silent", "liminal", "manifest" for phase category.
 * @param from_phase      Previous phase (for phase transition events).
 * @param to_phase        New phase (for phase transition events).
 * @param source          Source of the event, e.g. "desktop_presence_runtime".
 * @param sync_type       Sync type: "cross_device_broadcast" | "direct_push".
 * @param payload         Extra event-specific data as key-value map.
 */
data class StateEventPayload(
    val event_category: String = "",
    val event_action: String = "",
    val from_phase: String = "",
    val to_phase: String = "",
    val source: String = "",
    val sync_type: String = "cross_device_broadcast",
    val payload: Map<String, Any> = emptyMap()
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
 *
 * ## Android NL initiation metadata (PR-993 compatibility)
 *
 * The following fields carry Android-originated natural-language initiation metadata
 * introduced by PR-993. They are required when the request originates from an Android NL
 * input in cross-device mode, and must be absent (null) for all other sources so that V2
 * can distinguish Android-originated initiations from server-dispatched tasks.
 *
 * @param nl_initiation_origin      Originating device/system identity. Must be
 *                   `"android_device"` for Android NL initiations; `null` for all other sources.
 * @param nl_initiation_mode        NL initiation mode from
 *                   [com.ufo.galaxy.runtime.AndroidNlInitiationContract.NlInitiationMode].
 *                   Must be `"android_nl_cross_device"` for Android NL initiations; `null`
 *                   for all other sources.
 * @param nl_initiation_authority_scope  Authority scope from
 *                   [com.ufo.galaxy.runtime.AndroidNlInitiationContract.NlAuthorityScope].
 *                   Must be `"v2_central"` for all valid Android NL initiations; `null` for
 *                   other sources. V2 MUST NOT admit an Android NL initiation that declares
 *                   any scope other than `"v2_central"`.
 * @param nl_initiation_lineage     Session/device lineage string of the form
 *                   `"android/{deviceId}/{runtimeSessionId}"` for Android NL initiations;
 *                   `null` for all other sources.
 */
data class TaskSubmitPayload(
    val task_text: String,
    val device_id: String,
    val session_id: String,
    val task_id: String = "",
    val context: TaskSubmitContext = TaskSubmitContext(),
    val source_runtime_posture: String? = null,
    // ── PR-993: Android NL initiation metadata (null for non-Android-NL sources) ──
    val nl_initiation_origin: String? = null,
    val nl_initiation_mode: String? = null,
    val nl_initiation_authority_scope: String? = null,
    val nl_initiation_lineage: String? = null
) {
    /**
     * Returns `true` when all required fields are non-blank and any NL initiation fields
     * present are consistent (authority scope must be `"v2_central"` when origin is set).
     *
     * Callers (e.g. [com.ufo.galaxy.input.InputRouter]) should call [validate] before
     * sending the payload and reject messages that fail.  For a human-readable description
     * of the first failing field, use [validationError].
     */
    fun validate(): Boolean =
        task_text.isNotBlank() &&
            device_id.isNotBlank() &&
            session_id.isNotBlank() &&
            nlInitiationFieldsValid()

    /**
     * Returns `true` when the NL initiation fields are internally consistent.
     *
     * If [nl_initiation_origin] is non-null (Android-originated NL initiation), then
     * [nl_initiation_authority_scope] MUST be `"v2_central"`. Any other scope value
     * (or an absent scope when origin is present) constitutes a governance violation and
     * must be rejected before the payload is sent uplink.
     *
     * If [nl_initiation_origin] is null, all other NL initiation fields are expected to
     * be null as well (non-NL source); this method returns `true` in that case.
     */
    private fun nlInitiationFieldsValid(): Boolean {
        if (nl_initiation_origin == null) return true
        return nl_initiation_authority_scope == "v2_central"
    }

    /**
     * Returns a debug-friendly description of the first failing required field, or `null` when
     * [validate] passes.  Intended for logging / error messages only; not for UI display.
     */
    fun validationError(): String? = when {
        task_text.isBlank()  -> "task_text is blank"
        device_id.isBlank()  -> "device_id is blank"
        session_id.isBlank() -> "session_id is blank"
        nl_initiation_origin != null && nl_initiation_authority_scope != "v2_central" ->
            "nl_initiation_authority_scope must be 'v2_central' when nl_initiation_origin is present " +
            "(got: ${nl_initiation_authority_scope ?: "null"})"
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
 *
 * ## V2 source dispatch metadata (PR-D compatibility)
 * The following fields carry richer source-dispatch orchestration metadata introduced
 * by V2 source dispatch wiring. All fields are optional so that pre-V2 senders remain
 * compatible. Android handlers MUST treat `null` / absent values as equivalent to the
 * legacy contract and MUST NOT rely on these fields for core execution decisions.
 *
 * @param dispatch_intent     Optional dispatch intent label from the V2 orchestrator
 *                            (e.g. `"task_execute"`, `"staged_handoff"`). `null` for
 *                            legacy senders.
 * @param dispatch_origin     Optional identifier of the originating orchestrator or
 *                            device that initiated this dispatch. `null` for legacy
 *                            senders.
 * @param orchestration_stage Optional label for the current orchestration stage when
 *                            the task is part of a multi-stage dispatch sequence.
 *                            `null` for single-stage / legacy dispatches.
 * @param execution_context   Optional key-value execution context forwarded from the
 *                            V2 orchestrator (e.g. locale, priority hints). Empty map
 *                            for legacy senders; Android handlers MUST safely ignore
 *                            unknown keys.
 *
 * ## V2 explicit executor target typing (PR-E compatibility)
 * @param executor_target_type Optional explicit executor target type from V2's target-typing
 *                            model. Valid values: `"android_device"`, `"node_service"`,
 *                            `"worker"`, `"local"`. `null` for legacy/pre-V2 senders;
 *                            Android handlers MUST treat `null` as backward-compatible
 *                            "unspecified". Unknown values MUST be tolerated without rejection.
 *
 * ## V2 durable continuity and recovery context (PR-F compatibility)
 * The following fields carry durable continuity and recovery metadata introduced by V2
 * to preserve execution and session association across reconnects, handoffs, and
 * recoverable interruptions.  All fields are optional so that pre-PR-F senders remain
 * compatible.  Android handlers MUST safely accept these fields and MUST NOT fail when
 * they are absent.
 *
 * @param continuity_token    Opaque stable token identifying the durable execution
 *                            continuity context across reconnects or handoffs; `null`
 *                            for legacy / non-continuity dispatches.
 * @param recovery_context    Optional key-value map carrying recovery hints forwarded
 *                            by V2 (e.g. last known checkpoint, recovery policy hints).
 *                            Empty map for legacy senders; unknown keys MUST be ignored.
 * @param is_resumable        `true` when V2 considers this a resumable execution that
 *                            should survive a recoverable interruption; `false` when the
 *                            dispatch is explicitly terminal; `null` for legacy senders
 *                            (Android applies default behaviour).
 * @param interruption_reason Reason for the interruption that triggered this
 *                            resume/recovery dispatch.  Recognised values are declared
 *                            in [com.ufo.galaxy.runtime.ContinuityRecoveryContext]:
 *                            `"reconnect"`, `"handoff"`, `"device_pause"`,
 *                            `"transport_degraded"`.  Unknown values MUST be tolerated.
 *                            `null` for non-recovery dispatches.
 *
 * ## V2 observability/tracing metadata (PR-G compatibility)
 * The following fields carry cross-system observability and tracing metadata introduced
 * by V2 production-grade runtime observability.  All fields are optional so that
 * pre-PR-G senders remain compatible.  Android handlers MUST accept these fields
 * without failure and MUST NOT rely on them for core execution decisions.
 *
 * @param dispatch_trace_id   Optional cross-system dispatch trace correlation identifier.
 *                            When present, allows the full dispatch chain from the V2
 *                            orchestrator through gateway to Android to be traced with a
 *                            single stable ID.  `null` for legacy / pre-V2 senders.
 * @param lifecycle_event_id  Optional identifier of the V2 lifecycle event that triggered
 *                            this dispatch (e.g. reconnect, session restore).  Used for
 *                            cross-system observability correlation only; does not affect
 *                            execution semantics.  `null` for non-lifecycle-triggered
 *                            dispatches.
 */
data class TaskAssignPayload(
    val task_id: String,
    val goal: String,
    val constraints: List<String> = emptyList(),
    val max_steps: Int,
    val require_local_agent: Boolean,
    val source_runtime_posture: String? = null,
    // ── PR-D: V2 source dispatch metadata (optional; null-safe for legacy senders) ──
    val dispatch_intent: String? = null,
    val dispatch_origin: String? = null,
    val orchestration_stage: String? = null,
    val execution_context: Map<String, String> = emptyMap(),
    // ── PR-E: V2 explicit executor target typing (optional; null-safe for legacy senders) ──
    val executor_target_type: String? = null,
    // ── PR-F: V2 durable continuity and recovery context (optional; null-safe for legacy senders) ──
    val continuity_token: String? = null,
    val recovery_context: Map<String, String> = emptyMap(),
    val is_resumable: Boolean? = null,
    val interruption_reason: String? = null,
    // ── PR-G: V2 observability/tracing metadata (optional; null-safe for legacy senders) ──
    val dispatch_trace_id: String? = null,
    val lifecycle_event_id: String? = null
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
 *
 * ## V2 staged dispatch metadata (PR-D compatibility)
 * The following fields carry staged-dispatch orchestration metadata introduced by V2.
 * All fields are optional so that pre-V2 / non-staged senders remain compatible.
 * Android handlers MUST safely ignore these fields when `null` / absent.
 *
 * @param staged_mesh_id   Optional mesh session identifier when this payload is part of
 *                         a V2 staged-mesh dispatch. `null` for non-staged dispatches.
 * @param staged_subtask_id Optional subtask identifier when this payload is a staged
 *                         subtask within a mesh session. `null` for non-staged dispatches.
 * @param execution_context Optional key-value execution context forwarded from the V2
 *                         orchestrator. Empty map for legacy senders; Android handlers
 *                         MUST safely ignore unknown keys.
 *
 * ## V2 explicit executor target typing (PR-E compatibility)
 * @param executor_target_type Optional explicit executor target type introduced by V2's
 *                         target-typing model. Valid values defined in
 *                         [com.ufo.galaxy.runtime.ExecutorTargetType]:
 *                         `"android_device"`, `"node_service"`, `"worker"`, `"local"`.
 *                         `null` for legacy/pre-V2 senders; Android handlers MUST treat
 *                         `null` as backward-compatible "unspecified" and continue executing.
 *                         Unknown values MUST also be tolerated without rejection.
 *
 * ## V2 durable continuity and recovery context (PR-F compatibility)
 * The following fields carry durable continuity and recovery metadata introduced by V2
 * to preserve execution and session association across reconnects, handoffs, and
 * recoverable interruptions.  All fields are optional so that pre-PR-F senders remain
 * compatible.  Android handlers MUST safely accept these fields and MUST NOT fail when
 * they are absent.
 *
 * @param continuity_token    Opaque stable token identifying the durable execution
 *                            continuity context across reconnects or handoffs; `null`
 *                            for legacy / non-continuity dispatches.
 * @param recovery_context    Optional key-value map carrying recovery hints forwarded
 *                            by V2 (e.g. last known checkpoint, recovery policy hints).
 *                            Empty map for legacy senders; unknown keys MUST be ignored.
 * @param is_resumable        `true` when V2 considers this a resumable execution that
 *                            should survive a recoverable interruption; `false` when the
 *                            dispatch is explicitly terminal; `null` for legacy senders
 *                            (Android applies default behaviour).  Android MUST NOT
 *                            collapse a resumable execution into a terminal failure.
 * @param interruption_reason Reason for the interruption that triggered this
 *                            resume/recovery dispatch.  Recognised values are declared
 *                            in [com.ufo.galaxy.runtime.ContinuityRecoveryContext]:
 *                            `"reconnect"`, `"handoff"`, `"device_pause"`,
 *                            `"transport_degraded"`.  Unknown values MUST be tolerated.
 *                            `null` for non-recovery dispatches.
 *
 * ## V2 observability/tracing metadata (PR-G compatibility)
 * The following fields carry cross-system observability and tracing metadata introduced
 * by V2 production-grade runtime observability.  All fields are optional so that
 * pre-PR-G senders remain compatible.  Android handlers MUST accept these fields
 * without failure and MUST NOT rely on them for core execution decisions.
 *
 * @param dispatch_trace_id   Optional cross-system dispatch trace correlation identifier.
 *                            When present, allows the full dispatch chain from the V2
 *                            orchestrator through gateway to Android to be traced with a
 *                            single stable ID.  `null` for legacy / pre-V2 senders.
 * @param lifecycle_event_id  Optional identifier of the V2 lifecycle event that triggered
 *                            this dispatch.  Used for cross-system observability correlation
 *                            only; does not affect execution semantics.  `null` for
 *                            non-lifecycle-triggered dispatches.
 *
 * ## V2 richer dispatch metadata (PR-48 compatibility)
 * The following fields carry richer source dispatch metadata introduced by V2 to allow
 * Android to correlate inbound execution commands with the V2 orchestration plan that
 * generated them.  All fields are optional so that pre-PR-48 / legacy senders remain
 * compatible.  Android handlers MUST accept these fields without failure and MUST NOT
 * rely on them for core execution decisions.
 *
 * @param dispatch_plan_id    Optional stable identifier for the V2 source dispatch plan
 *                            that produced this command.  Allows full correlation between
 *                            the inbound execution and the originating V2 orchestration
 *                            decision.  Echoed in result payloads for end-to-end
 *                            correlation.  `null` for legacy / pre-V2 senders.
 * @param source_dispatch_strategy  Optional hint describing the routing strategy used by
 *                            the V2 source dispatch orchestrator when selecting this device
 *                            as the execution target.  Recognised values are defined in
 *                            [com.ufo.galaxy.runtime.ExecutionContractCompatibilityValidator.DispatchStrategyHint]:
 *                            `"local"`, `"remote_handoff"`, `"fallback_local"`,
 *                            `"staged_mesh"`.  Unknown values MUST be tolerated.  `null`
 *                            for legacy / pre-V2 senders.
 * @param delegated_flow_id   (PR-bridge) Stable identifier for the V2 canonical delegated flow
 *                            entity that produced this command.  When present, Android uses this as
 *                            [com.ufo.galaxy.runtime.AndroidDelegatedFlowBridge.delegatedFlowId].
 *                            `null` for legacy/pre-bridge senders; Android derives a local
 *                            identifier from [task_id] in that case.
 * @param flow_lineage_id     (PR-bridge) Lineage identity of the V2 canonical delegated flow
 *                            entity.  Shared by all Android-side flows belonging to the same V2
 *                            canonical flow family.  `null` for legacy/pre-bridge senders;
 *                            Android defaults to [task_id].
 */
data class GoalExecutionPayload(
    val task_id: String,
    val goal: String,
    val constraints: List<String> = emptyList(),
    val max_steps: Int = 10,
    val group_id: String? = null,
    val subtask_index: Int? = null,
    val timeout_ms: Long = 0L,
    val source_runtime_posture: String? = null,
    // Explicit Android runtime execution semantic class used for canonical closure reporting.
    val execution_runtime_kind: String? = null,
    // ── PR-D: V2 staged dispatch metadata (optional; null-safe for legacy senders) ──
    val staged_mesh_id: String? = null,
    val staged_subtask_id: String? = null,
    val dispatch_intent: String? = null,
    val dispatch_origin: String? = null,
    val orchestration_stage: String? = null,
    val execution_context: Map<String, String> = emptyMap(),
    // ── PR-E: V2 explicit executor target typing (optional; null-safe for legacy senders) ──
    val executor_target_type: String? = null,
    // ── PR-F: V2 durable continuity and recovery context (optional; null-safe for legacy senders) ──
    val continuity_token: String? = null,
    val recovery_context: Map<String, String> = emptyMap(),
    val is_resumable: Boolean? = null,
    val interruption_reason: String? = null,
    // ── PR-G: V2 observability/tracing metadata (optional; null-safe for legacy senders) ──
    val dispatch_trace_id: String? = null,
    val lifecycle_event_id: String? = null,
    // ── PR-48: V2 richer dispatch metadata (optional; null-safe for legacy senders) ──
    val dispatch_plan_id: String? = null,
    val source_dispatch_strategy: String? = null,
    // ── PR-49 (PR-I): V2 policy-driven routing outcome metadata (optional; null-safe for legacy senders) ──
    val policy_routing_outcome: String? = null,
    val policy_failure_reason: String? = null,
    val readiness_degradation_hint: String? = null,
    // ── PR-bridge: Delegated flow bridge identity (optional; null-safe for legacy senders) ──
    val delegated_flow_id: String? = null,
    val flow_lineage_id: String? = null,
    // ── PR-2: NL-driven execution spine context (optional; null-safe for legacy senders) ──
    // problem_context: V2-originated NL problem description that this execution contributes to.
    // problem_solving_role: V2-declared execution spine participation kind for this dispatch.
    // Both are null for legacy senders; Android MUST operate correctly without them.
    val problem_context: String? = null,
    val problem_solving_role: String? = null
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
 * @param status         Final status ("success" | "error" | "cancelled" | "disabled" | "hold").
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
 * @param executor_target_type  Echoed from [GoalExecutionPayload.executor_target_type] so the
 *                       gateway can correlate the result with the explicit target type used in
 *                       the originating command. `null` for pre-V2 / unspecified dispatches.
 * @param continuity_token  Echoed from [GoalExecutionPayload.continuity_token] so V2 can
 *                       correlate the result with the originating durable continuity context.
 *                       `null` for legacy / non-continuity dispatches.
 * @param recovery_context  Echoed from [GoalExecutionPayload.recovery_context] when non-empty
 *                       so V2 can recover execution-relevant recovery hints without relying on
 *                       separate side channels. `null` when no recovery context was provided.
 * @param is_resumable   Echoed from [GoalExecutionPayload.is_resumable] so V2 can determine
 *                       whether Android treated this execution as resumable or terminal.
 *                       `null` for legacy senders.
 * @param interruption_reason Echoed from [GoalExecutionPayload.interruption_reason] so V2 can
 *                       preserve interruption semantics across resume/handoff/recovery paths.
 *                       `null` when no interruption reason was provided.
 * @param dispatch_trace_id  Echoed from [GoalExecutionPayload.dispatch_trace_id] so V2 can
 *                       correlate the result with the originating dispatch chain.  `null`
 *                       for legacy / pre-V2 senders that do not include dispatch tracing.
 * @param dispatch_plan_id  Echoed from [GoalExecutionPayload.dispatch_plan_id] so V2 can
 *                       correlate the result with the originating dispatch plan.  `null`
 *                       for legacy / pre-V2 senders that do not include dispatch plan tracking.
 * @param policy_routing_outcome  Echoed from [GoalExecutionPayload.policy_routing_outcome] so
 *                       V2 can correlate the result with the policy layer's routing decision.
 *                       `null` for legacy / pre-V2 senders that do not include policy routing.
 * @param policy_rejection_detail  Structured rejection detail when
 *                       [GoalExecutionPayload.policy_routing_outcome] is
 *                       [com.ufo.galaxy.runtime.PolicyRoutingContext.RoutingOutcome.REJECTED].
 *                       Echoed from [GoalExecutionPayload.policy_failure_reason] when present,
 *                       so V2 can distinguish the specific rejection reason rather than relying
 *                       solely on the generic [error] string.  `null` for non-rejected outcomes
 *                       and legacy paths.
 * @param hold_reason    Structured hold reason when [status] is
 *                       [com.ufo.galaxy.runtime.PolicyRoutingContext.RESULT_STATUS_HOLD].
 *                       Set to [com.ufo.galaxy.runtime.PolicyRoutingContext.RESULT_HOLD_REASON_TEMPORARILY_UNAVAILABLE]
 *                       when the outcome is
 *                       [com.ufo.galaxy.runtime.PolicyRoutingContext.RoutingOutcome.TEMPORARILY_UNAVAILABLE],
 *                       signalling a non-terminal hold that V2 should retry when readiness is
 *                       restored.  `null` for non-hold results and legacy paths.
 * @param is_continuation  `true` when Android executed this task as a **continuation** of a
 *                       prior interrupted execution
 *                       ([com.ufo.galaxy.runtime.PolicyRoutingContext.RoutingOutcome.RESUMED]).
 *                       `null` when the task was executed as a fresh dispatch (non-resumed
 *                       outcomes).  Allows V2 to distinguish resumed executions from fresh
 *                       executions in the uplink result.
 *
 * ## Unified result contract fields (single online-result contract)
 * The following three fields complete the unified result uplink contract so that all
 * production result paths (success / error / timeout / cancellation / disabled) carry
 * the same set of identity, classification, and summary fields and V2 can consume them
 * through a single canonical handler without guessing or fallback mapping.
 *
 * @param normalized_status  Canonical result kind derived from [status] by
 *                       [com.ufo.galaxy.service.GalaxyConnectionService.sendGoalResult] at
 *                       emission time.  Always set on the outbound wire payload; `null` only
 *                       for locally-constructed payloads that have not yet been emitted.
 *                       Stable values: `"final_completion"`, `"cancellation"`,
 *                       `"disabled"`, `"failure"`.
 *                       Allows V2 unified result ingress to classify outcomes without
 *                       raw-status string inspection or per-path guessing.
 * @param runtime_session_id  Stable per-app-launch session identifier echoed into the
 *                       payload for replay self-containment.  Set by
 *                       [com.ufo.galaxy.service.GalaxyConnectionService.sendGoalResult]
 *                       from [com.ufo.galaxy.UFOGalaxyApplication.runtimeSessionId] at
 *                       emission time.  When a result is replayed from the offline queue,
 *                       this field allows the V2 ingress to correlate the replayed result
 *                       with the originating runtime session without re-querying the device.
 *                       `null` only for locally-constructed payloads that have not yet
 *                       been emitted through the canonical send path.
 * @param result_summary Human-readable one-line outcome description.  Mirrors the same
 *                       field in [TaskResultPayload] and [HandoffEnvelopeV2ResultPayload]
 *                       so that all uplink result payloads carry a consistent summary
 *                       field.  Populated by
 *                       [com.ufo.galaxy.service.GalaxyConnectionService.sendGoalResult]
 *                       from the [result] field when not explicitly set by the caller.
 *                       `null` only for pre-emission / locally-constructed payloads.
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
    val source_runtime_posture: String? = null,
    val execution_runtime_kind: String? = null,
    val dispatch_intent: String? = null,
    val dispatch_origin: String? = null,
    val orchestration_stage: String? = null,
    // ── PR-E: V2 explicit executor target typing (optional; echoed for full-chain correlation) ──
    val executor_target_type: String? = null,
    // ── PR-F: V2 durable continuity and recovery context (optional; echoed for full-chain correlation) ──
    val continuity_token: String? = null,
    val recovery_context: Map<String, String>? = null,
    val is_resumable: Boolean? = null,
    val interruption_reason: String? = null,
    // ── PR-G: V2 observability/tracing metadata (optional; echoed for full-chain correlation) ──
    val dispatch_trace_id: String? = null,
    // ── PR-48: V2 richer dispatch metadata (optional; echoed for full-chain correlation) ──
    val dispatch_plan_id: String? = null,
    // ── PR-49 (PR-I): V2 policy-driven routing outcome (optional; echoed for full-chain correlation) ──
    val policy_routing_outcome: String? = null,
    // ── PR-5B: structured policy outcome result fields (optional; null for non-policy / legacy paths) ──
    val policy_rejection_detail: String? = null,
    val hold_reason: String? = null,
    val is_continuation: Boolean? = null,
    // Android-side participation tier projection for V2 routing / closure consumption.
    // Values: "pre_attach" | "fully_attached" | "dispatch_eligible" | "distributed_participant".
    val participation_tier: String? = null,
    // Canonical LocalExecutionModeGate wire state echoed in result uplink for mode-context parity.
    val execution_mode_state: String? = null,
    // Echoed mode gate booleans so V2 can consume result-level local/cross-device eligibility.
    val cross_device_eligibility: Boolean? = null,
    val local_mode_gate_deferred: Boolean? = null,
    // Result-time local inference availability hint from Android's runtime/capability truth.
    val local_inference_available: Boolean? = null,
    // ── Unified result contract: canonical kind + replay identity ──────────────────────────────
    // Both fields are set by GalaxyConnectionService.sendGoalResult at emission time and are
    // null only for pre-emission / locally-constructed payloads.
    val normalized_status: String? = null,
    val runtime_session_id: String? = null,
    // ── Unified result contract: normalised result summary ─────────────────────────────────────
    // Mirrors the result_summary field in TaskResultPayload and HandoffEnvelopeV2ResultPayload
    // so that all uplink result payloads carry a consistent human-readable one-line outcome.
    // Populated by GalaxyConnectionService.sendGoalResult from the `result` field when absent.
    val result_summary: String? = null,
    // ── PR-6: completion/closure visibility semantics for result uplink ───────────────────────
    // Mirrors DeviceExecutionEventPayload completion semantics so V2 can consume execution-event
    // and goal-result closure truth through the same boolean contract.
    // - result_returned: Android has produced a terminal result for this task.
    // - completion_signaled: Android has emitted a completion-side signal for this task.
    // - closure_ready_for_acceptance: this result is terminal and may enter acceptance closure.
    // Null only as defensive defaults; GalaxyConnectionService.sendGoalResult fills these.
    val result_returned: Boolean? = null,
    val completion_signaled: Boolean? = null,
    val closure_ready_for_acceptance: Boolean? = null,
    // ── PR-6: unified participant lifecycle phase on result uplink ─────────────────────────────
    // Mirrors snapshot/event lifecycle fields so V2 can correlate results with Android lifecycle
    // phase at emission time without inferring from mode/participation combinations.
    val unified_lifecycle_phase: String? = null,
    val unified_lifecycle_schema_version: String? = null,
    // ── PR-2: NL-driven execution spine closure reporting ──────────────────────────────────────
    // problem_solving_closure_class: Android's contribution to V2 canonical problem-solving
    // closure. Wire value of AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.
    // Allows V2 unified_result_ingress / canonical_completion_ingress to distinguish task
    // completion from problem-solving progress. Null only for pre-emission payloads.
    val problem_solving_closure_class: String? = null,
    // execution_spine_participation_kind: The spine participation mode Android used.
    // Wire value of AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.
    // Echoed in results for V2 task_result_canonical_truth_chain correlation. Null for legacy.
    val execution_spine_participation_kind: String? = null,

    // ── 统一真相上行合约：参与真相布尔字段（AndroidUnifiedTruthUplinkContract）──────────────────
    //
    // dispatch_eligible: Android 在结果上报时是否处于 dispatch_eligible 或 distributed_participant
    //   参与层级。允许 V2 接受/闭合路径无需解析 participation_tier 字符串。
    //   当 participation_tier 为 "dispatch_eligible" 或 "distributed_participant" 时为 true。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendGoalResult 在发送时填充。
    //
    // distributed_participant: Android 在结果上报时是否处于分布式参与者层级。
    //   当 participation_tier 为 "distributed_participant" 时为 true。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendGoalResult 在发送时填充。
    //
    // session_attached: Android 在结果上报时是否已附加运行时会话。
    //   当 participation_tier 不为 "pre_attach" 时为 true。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendGoalResult 在发送时填充。
    val dispatch_eligible: Boolean? = null,
    val distributed_participant: Boolean? = null,
    val session_attached: Boolean? = null,

    // ── 统一真相上行合约：模式真相布尔字段（AndroidUnifiedTruthUplinkContract）──────────────────
    //
    // local_mode_active: Android 在结果上报时是否处于本地模式（execution_mode_state = "local_only"）。
    //   true 时 V2 应知晓此结果来自本地执行而非跨设备委托路径。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendGoalResult 在发送时填充。
    //
    // runtime_constrained: Android 在结果上报时是否处于运行时受限状态。
    //   对应 AndroidUnifiedTruthUplinkContract.ConstraintSemantics 中的 isConstraint=true 状态。
    //   V2 应将此字段用于接受置信度调整：受限状态下的结果可信度较低。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendGoalResult 在发送时填充。
    //
    // runtime_deferred: Android 在结果上报时是否处于运行时延迟状态。
    //   对应 AndroidUnifiedTruthUplinkContract.ConstraintSemantics 中的 isDeferred=true 状态。
    //   V2 应将此字段用于重试策略决策：延迟状态下可等待后续结果。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendGoalResult 在发送时填充。
    val local_mode_active: Boolean? = null,
    val runtime_constrained: Boolean? = null,
    val runtime_deferred: Boolean? = null,

    // ── 统一真相上行合约：治理真相字段（AndroidUnifiedTruthUplinkContract）────────────────────
    //
    // governance_state: Android 当前治理状态。
    //   值来自 AndroidUnifiedTruthUplinkContract.GovernanceState：
    //   "local_autonomous" | "v2_governed" | "delegated_execution" | "governance_blocked"。
    //
    // governance_blocked: Android 当前是否被治理层阻塞（如 operator isolate / suspend）。
    //
    // delegated_execution_active: Android 在结果上报时是否仍处于活跃委托执行中。
    //
    // takeover_state: Android 当前接管生命周期状态。
    //   值来自 AndroidUnifiedTruthUplinkContract.TakeoverState：
    //   "inactive" | "pending" | "active"。
    val governance_state: String? = null,
    val governance_blocked: Boolean? = null,
    val delegated_execution_active: Boolean? = null,
    val takeover_state: String? = null,

    // ── 统一真相上行合约：本地能力真相字段（AndroidUnifiedTruthUplinkContract）──────────────────
    //
    // local_llm_ready: Android 本地 LLM 在结果上报时是否就绪（模型已加载、权重已验证）。
    //   true 意味着 Android 已在本地语言模型支持下执行此任务，无需网络依赖。
    //   与 local_inference_available 不同：local_llm_ready 专指 LLM 组件就绪状态。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendGoalResult 在发送时填充。
    //
    // accessibility_ready: Android 可访问性服务在结果上报时是否就绪。
    //   true 意味着此结果的执行路径具备完整 UI 自动化能力。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendGoalResult 在发送时填充。
    //
    // local_mode_capable: Android 在结果上报时是否具备完整本地模式运行能力。
    //   对应 AndroidUnifiedTruthUplinkContract.LocalCapabilityState.isLocalModeCapable。
    //   true 意味着 local_inference_available 且无关键降级，此结果可信度较高。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendGoalResult 在发送时填充。
    val local_llm_ready: Boolean? = null,
    val accessibility_ready: Boolean? = null,
    val local_mode_capable: Boolean? = null,
    val ingress_boundary_class: String? = null,
    val ingress_consumption_kind: String? = null,
    val ingress_signal_class: String? = null,
    val ingress_schema_version: String? = null,

    // ── PR-05v2 (Android): 结果上行闭环边界字段（AndroidResultUplinkBoundaryContract）────────────
    //
    // result_signal_class: 本上行信号的结果信号语义分类。
    //   使 V2 无需字段组合推断即可将结果路由至正确的消费路径。
    //   Values:
    //     "authority_result"          — 权威终态结果，V2 MUST 关闭任务（truth anchor）
    //     "acceptance_closure_signal" — 验收/闭合相关信号，V2 MAY 进入 acceptance adjudication
    //     "diagnostics_informational" — 诊断性信号，V2 MUST NOT 用于任务关闭
    //   V2 core/task_result_canonical_truth_chain.py MUST 读取此字段，
    //   仅对 "authority_result" 执行任务关闭逻辑。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendGoalResult 在发送时填充。
    //
    // acceptance_candidate_class: 本上行的验收候选资格分类。
    //   消除 V2 通过 result_returned + completion_signaled + closure_ready_for_acceptance
    //   + governance_blocked 四个布尔字段组合推断验收资格的复杂性。
    //   Values:
    //     "eligible_for_acceptance"  — 可接受候选结果，V2 可执行 acceptance closure
    //     "pending_result_return"    — 执行中，等待终态结果
    //     "acceptance_blocked"       — 治理阻塞或运行时受限，等待约束解除
    //     "closure_not_applicable"   — 诊断性上行，不纳入验收链
    //   V2 core/acceptance_adjudication.py MUST 读取此字段，
    //   仅对 "eligible_for_acceptance" 执行 acceptance closure。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendGoalResult 在发送时填充。
    //
    // result_uplink_boundary_schema_version: 本字段组 schema 版本。
    //   取自 AndroidResultUplinkBoundaryContract.SCHEMA_VERSION。
    val result_signal_class: String? = null,
    val acceptance_candidate_class: String? = null,
    val result_uplink_boundary_schema_version: String? = null,
    val uplink_semantic_boundary_class: String? = null,
    val operator_projection_class: String? = null,
    val diagnostics_failure_explanation_schema_version: String? = null,
    val authority_runtime_completion_signal_class: String? = null,
    val result_completion_signal_class: String? = null,
    val closure_finalization_signal_class: String? = null,
    val operator_done_projection_class: String? = null,
    val completion_closure_uplink_schema_version: String? = null,
    val schema_version: String? = null,
    val completion_closure_contract_version: String? = null,
    val completion_emission_id: String? = null,
    val idempotency_key: String? = null,
    val local_execution_completed: Boolean? = null,
    val advisory_evidence_sent: Boolean? = null,
    val v2_uplink_acknowledged: Boolean? = null,
    val v2_reconciliation_acknowledged: Boolean? = null,
    val v2_canonical_truth_completed: Boolean? = null,
    val v2_mature_closure_achieved: Boolean? = null,
    val outward_truth_surface_class: String? = null,
    val is_v2_confirmed_canonical_truth: Boolean? = null,

    // ── PR-08v2 (Android): 分布式运行参与边界收束字段（结果上行）───────────────────────────────
    //
    // participation_boundary_role: 结果上报时 Android 的参与边界角色分类。
    //   由 AndroidDistributedRuntimeParticipationBoundaryContract.derive() 推导。
    //   V2 MUST read this field to route goal_execution_result through the correct
    //   distributed runtime participation chain; MUST NOT re-derive from field combinations.
    //
    // ownership_posture_class: 结果上报时 Android 的 ownership posture 语义分类。
    //   V2 MAY 用于 dispatch routing；MUST NOT 用于 authority closure 或 takeover 终局判断。
    //
    // remote_local_mode_class: 结果上报时 Android 的 remote/local 执行模式分类。
    //   区分主动声明本地模式（local_only_declared）与 fallback 转入（fallback_local / degraded_fallback）。
    //
    // participation_boundary_schema_version: 本字段组 schema 版本。
    //   取自 AndroidDistributedRuntimeParticipationBoundaryContract.SCHEMA_VERSION。
    val participation_boundary_role: String? = null,
    val ownership_posture_class: String? = null,
    val remote_local_mode_class: String? = null,
    val participation_boundary_schema_version: String? = null,
    // PR-09Android: distributed truth / ownership uplink boundary fields.
    // These keep transport/reporting/result carriers distinct from canonical truth / handoff /
    // diagnostics semantics so V2 can route Android uplinks without weakening authoritative input.
    val authority_signal_class: String? = null,
    val ownership_uplink_class: String? = null,
    val session_continuity_class: String? = null,
    val device_posture_signal_class: String? = null,
    val distributed_truth_ownership_uplink_schema_version: String? = null,
    val durable_session_id: String? = null,
    val session_continuity_epoch: Int? = null,

    // ── PR-116: Android unified continuity recovery state fields ─────────────────────────
    //
    // continuity_recovery_state: unified Android-side recovery phase wire value.
    //   Values from AndroidContinuityRecoveryStateModel.RecoveryPhase.wireValue:
    //     "resumed-cleanly"          — no interrupted state; clean start
    //     "recovering"               — WS reconnect in progress
    //     "recovered-inflight"       — prior in-flight task recovered locally (advisory)
    //     "lost-inflight"            — prior in-flight task was dropped; V2 must reconcile
    //     "requires-reconciliation"  — durable artifact found; V2 must resolve truth
    //     "stale-recovery-artifact"  — artifact belongs to old session; MUST NOT be used
    //     "recovery-failed"          — all reconnect attempts exhausted
    //   V2 MUST treat this as advisory Android-local evidence, not canonical final truth.
    //   Null only as a defensive default; GalaxyConnectionService.sendGoalResult fills this.
    //
    // continuity_recovery_source: label of the Android code path that produced the phase.
    //   Echoes InflightContinuityRecoverySnapshot.source (e.g. "process_recreated",
    //   "runtime_stop", "reconnect_recovery"). Allows V2 observability to attribute the
    //   recovery observation to a specific recovery trigger.
    //   Null only as a defensive default; GalaxyConnectionService.sendGoalResult fills this.
    //
    // continuity_recovery_schema_version: schema version of the recovery state contract.
    //   Taken from AndroidContinuityRecoveryStateModel.SCHEMA_VERSION.
    val continuity_recovery_state: String? = null,
    val continuity_recovery_source: String? = null,
    val continuity_recovery_schema_version: String? = null,
    // ── Stage 2: Android runtime emission truth semantics ───────────────────────────────
    //
    // execution_continuity_class:
    //   Explicitly distinguishes fresh, resumed, recovered, replayed, and degraded execution
    //   continuity semantics so resumed/recovered output does not appear fresh by default.
    //
    // terminal_emission_class:
    //   Distinguishes active/in-progress state from canonical terminal completion,
    //   resumed/recovered terminal completion, replayed terminal delivery, and degraded
    //   terminal-like output.
    //
    // terminal_delivery_disposition:
    //   Records whether Android emitted only a local signal, sent directly, queued offline,
    //   failed to send, or later replay-forwarded the terminal payload.
    //
    // result_convergence_decision:
    //   Stable Android-side convergence decision semantic tag (for example
    //   "emit_final_for_flow" or "emit_partial_for_flow") from the result convergence layer.
    //
    // runtime_emission_truth_schema_version:
    //   Schema version for the Stage 2 runtime emission truth fields.
    val execution_continuity_class: String? = null,
    val terminal_emission_class: String? = null,
    val terminal_delivery_disposition: String? = null,
    val result_convergence_decision: String? = null,
    val runtime_emission_truth_schema_version: String? = null,
    // ── PR-119: explicit recovery routing contract for canonical V2 handling ───────────────
    //
    // recovery_state_v2_routing_category: stable V2 routing category for the current
    //   continuity_recovery_state. V2 MUST consume this explicitly instead of treating
    //   Android recovery evidence as generic metadata.
    //
    // recovery_state_routing_requires_v2_action: "true" when V2 must trigger canonical
    //   handling (reconciliation, stale rejection, closure review, reconnect verdict).
    //
    // recovery_state_routing_is_advisory_only: "true" when the recovery evidence is
    //   Android-local advisory evidence only and MUST NOT be treated as canonical truth.
    //
    // recovery_state_routing_canonical_closure_blocked: "true" when V2 MUST NOT derive
    //   canonical closure directly from this recovery evidence.
    //
    // recovery_state_routing_schema_version: schema version of the routing contract.
    //   Taken from AndroidCrossRepoRecoveryStateRoutingContract.SCHEMA_VERSION.
    val recovery_state_v2_routing_category: String? = null,
    val recovery_state_routing_requires_v2_action: String? = null,
    val recovery_state_routing_is_advisory_only: String? = null,
    val recovery_state_routing_canonical_closure_blocked: String? = null,
    val recovery_state_routing_schema_version: String? = null,
    val uplink_lineage_schema_version: String? = null,
    val uplink_lineage_execution_id: String? = null,
    val uplink_lineage_emission_id: String? = null,
    val uplink_lineage_dedupe_key: String? = null,
    val uplink_lineage_recovery_basis: String? = null
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
 * @param diagnostic_schema_version Stable schema version for local diagnostic reason surfaces.
 * @param diagnostic_domain Stable local diagnostic domain for V2 canonical reconciliation.
 * @param diagnostic_reason Stable local diagnostic reason code for V2 canonical reconciliation.
 * @param local_cause Surface where Android observed the local failure cause.
 * @param timestamp     Unix epoch millis at the time of the failure.
 */
data class DiagnosticsPayload(
    val task_id: String,
    val device_id: String,
    val node_name: String,
    val error_type: String,
    val error_context: String,
    val diagnostic_schema_version: String? = null,
    val diagnostic_domain: String? = null,
    val diagnostic_reason: String? = null,
    val local_cause: String? = null,
    val ingress_boundary_class: String? = null,
    val ingress_consumption_kind: String? = null,
    val ingress_signal_class: String? = null,
    val ingress_schema_version: String? = null,
    val uplink_semantic_boundary_class: String? = null,
    val operator_projection_class: String? = null,
    val diagnostics_failure_explanation_schema_version: String? = null,
    val authority_signal_class: String? = null,
    val ownership_uplink_class: String? = null,
    val session_continuity_class: String? = null,
    val device_posture_signal_class: String? = null,
    val distributed_truth_ownership_uplink_schema_version: String? = null,
    // ── PR-120 (Android): Non-closure signal boundary classification ────────────
    //
    // non_closure_signal_class: Explicit non-closure classification for this diagnostics payload.
    //   Always "diagnostics_only" (AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.DIAGNOSTICS_ONLY).
    //   V2 MUST treat a payload carrying this field as non-closure regardless of other fields.
    //   MUST NOT be used to derive canonical task closure or final truth.
    val non_closure_signal_class: String? = null,
    val non_closure_schema_version: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Uplink payload for [MsgType.MESH_JOIN].
 * Sent when the device joins a mesh session to report participation readiness.
 * Canonical coordination semantic: `coordination_participant_joined`.
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
 * Canonical coordination semantic: `coordination_participant_left`.
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
 * Canonical coordination semantics:
 * - `coordination_execution_result_reported`
 * - terminal outcome via [status] (`success`/`partial`/`error`)
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
 * @param delegated_flow_id      (PR-bridge) Stable identifier for the V2 canonical delegated
 *                               flow entity this signal belongs to.  `null` when the signal was
 *                               emitted by a flow created before PR-bridge.
 * @param flow_lineage_id        (PR-bridge) Lineage identity of the V2 canonical delegated flow
 *                               entity.  Allows the main-repo host to correlate this signal with
 *                               other signals from the same flow family.  `null` when the signal
 *                               was emitted by a flow created before PR-bridge.
 * @param takeover_completion_kind (PR-11B Android) Classified takeover completion truth for this
 *                               signal: `"pending"`, `"completed"`, `"failed"`, `"interrupted"`,
 *                               `"rejected"`, or `"unknown"`.
 * @param ownership_return_state  (PR-11B Android) Ownership-return status for resumed transfer:
 *                               `"ownership_held_by_android"`,
 *                               `"ownership_return_pending_uplink"`,
 *                               `"ownership_return_signalled_to_v2"`, or
 *                               `"ownership_return_signalled_to_v2_retried"`.
 * @param takeover_outcome_visibility (PR-11B Android) Observation quality for this takeover
 *                               outcome: `"partially_observed"`, `"terminal_observed"`,
 *                               `"interrupted_terminal_observed"`, `"retried_terminal_observed"`,
 *                               `"retried_interrupted_terminal_observed"`.
 * @param takeover_result_uplink_attempt (PR-11B Android) Monotonic send-attempt count for this
 *                               signal id. `1` means first observation; `>1` means delayed/retried.
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
    val result_kind: String? = null,
    // ── PR-bridge: Delegated flow bridge identity (optional; null for pre-bridge signals) ──
    val delegated_flow_id: String? = null,
    val flow_lineage_id: String? = null,
    // ── PR-11B (Android): takeover completion + ownership-return semantics ──
    val takeover_completion_kind: String? = null,
    val ownership_return_state: String? = null,
    val takeover_outcome_visibility: String? = null,
    val takeover_result_uplink_attempt: Int? = null
)

// ── PR-35: Promoted long-tail payload models ──────────────────────────────────────────────
// These models replace generic-forward / logged-only handling for the three highest-value
// long-tail message types: PEER_EXCHANGE, MESH_TOPOLOGY, and COORD_SYNC.

/**
 * Inbound/uplink payload for [MsgType.PEER_EXCHANGE].
 *
 * Carries peer device capability records during multi-device session capability exchange.
 * Promoted from minimal-compat (logged only) to dedicated stateful handling in PR-35.
 *
 * @param source_device_id  Device identifier of the peer announcing its capabilities.
 * @param capabilities      List of capability names the peer device supports.
 *                          May be empty when the peer has no additional capabilities to advertise.
 * @param mesh_id           Optional mesh session this exchange belongs to.
 * @param exchange_id       Unique identifier for this capability exchange round; echoed in the ack.
 */
data class PeerExchangePayload(
    val source_device_id: String,
    val capabilities: List<String> = emptyList(),
    val mesh_id: String? = null,
    val exchange_id: String? = null
)

/**
 * Inbound payload for [MsgType.MESH_TOPOLOGY].
 *
 * Carries a snapshot of the current mesh topology (node list / adjacency) pushed by the
 * gateway when the mesh configuration changes.  Promoted from minimal-compat (logged only)
 * to dedicated stateful handling in PR-35.
 *
 * @param mesh_id       Mesh session identifier this topology snapshot belongs to.
 * @param nodes         List of device identifiers currently participating in the mesh.
 * @param topology_seq  Monotonic sequence number for topology updates.
 *                      Consumers should discard updates with lower sequence numbers than the
 *                      last received value to handle out-of-order delivery.
 * @param coordinator   Device identifier of the current mesh coordinator, if known.
 */
data class MeshTopologyPayload(
    val mesh_id: String,
    val nodes: List<String> = emptyList(),
    val topology_seq: Int = 0,
    val coordinator: String? = null
)

/**
 * Uplink acknowledgement payload for [MsgType.COORD_SYNC].
 *
 * Sent by the device in response to a coordination sync tick received from the gateway.
 * Promoted from generic [AckPayload] response to a dedicated sequence-aware payload in PR-35,
 * so the coordinator can verify sequence continuity across devices.
 *
 * @param sync_id       Identifier of the inbound sync tick being acknowledged
 *                      (echoed from the inbound `message_id` field, or a new UUID when absent).
 * @param device_id     Acknowledging device identifier.
 * @param sync_seq      Sync tick sequence number echoed from the inbound payload; `0` when absent.
 *                      Allows the coordinator to detect gaps in the acknowledgement sequence.
 * @param tick_count    Number of COORD_SYNC ticks received by this device in the current session.
 *                      Monotonically increasing; the coordinator can use this to detect missed ticks.
 * @param phase         Current coordination lifecycle phase on this device.
 *                      Always `"active"` in PR-35; reserved for future phase-state tracking.
 */
data class CoordSyncAckPayload(
    val sync_id: String,
    val device_id: String,
    val sync_seq: Int = 0,
    val tick_count: Int,
    val phase: String = "active"
)

// ── PR-36: Promoted long-tail payload model ───────────────────────────────────────────────
// PeerAnnouncePayload promotes PEER_ANNOUNCE from logged-only minimal-compat to a stateful
// peer-presence tracker that retains a per-session joined-peer record.

/**
 * Inbound payload for [MsgType.PEER_ANNOUNCE].
 *
 * Carries the announcement of a new peer device joining the current session, pushed by the
 * gateway when a peer connects or re-connects.  Promoted from minimal-compat (logged only)
 * to dedicated stateful handling in PR-36.
 *
 * @param peer_device_id    Device identifier of the joining peer.
 * @param peer_role         Optional role the peer is assuming in this session
 *                          (e.g. `"participant"`, `"observer"`).  `null` when the gateway
 *                          does not specify a role.
 * @param session_id        Optional session identifier this announcement belongs to.
 * @param announce_seq      Monotonic sequence number for peer announcements within a session.
 *                          Consumers should ignore duplicates with the same [peer_device_id]
 *                          and a lower [announce_seq] than the last retained value.
 */
data class PeerAnnouncePayload(
    val peer_device_id: String,
    val peer_role: String? = null,
    val session_id: String? = null,
    val announce_seq: Int = 0
)

// ── PR-06: Reconciliation signal uplink payload ────────────────────────────────────────────

/**
 * Uplink payload for [MsgType.RECONCILIATION_SIGNAL].
 *
 * Carries a reconciliation lifecycle signal from [com.ufo.galaxy.runtime.RuntimeController.reconciliationSignals]
 * to V2 so V2's participant-truth reconciliation loop can apply Android's canonical local truth.
 *
 * All identity fields are stable across retries so V2 can deduplicate by [signal_id].
 *
 * ### Required fields (always present)
 * @param signal_id            Stable UUID idempotency key for this emission.  V2 can discard
 *                             duplicate deliveries with the same [signal_id].
 * @param kind                 Wire discriminator from [com.ufo.galaxy.runtime.ReconciliationSignal.Kind.wireValue]:
 *                             `"task_result"`, `"task_cancelled"`, `"task_failed"`,
 *                             `"task_accepted"`, `"task_status_update"`,
 *                             `"participant_state"`, or `"runtime_truth_snapshot"`.
 * @param participant_id       Stable participant node identifier for V2 routing.
 * @param status               Wire-level status matching [com.ufo.galaxy.runtime.ReconciliationSignal]
 *                             status constants (e.g. `"success"`, `"cancelled"`, `"failed"`).
 * @param emitted_at_ms        Epoch-millisecond timestamp when the signal was emitted on-device.
 * @param reconciliation_epoch Monotonic epoch from the participant's runtime-truth clock.
 * @param device_id            Stable identifier of the Android device emitting the signal.
 *
 * ### Conditional fields
 * @param task_id              Task this signal belongs to; `null` for participant-state signals.
 * @param correlation_id       Correlation identifier echoed from the originating request; may be `null`.
 * @param session_id           Runtime session identifier at time of emission; may be `null`.
 * @param durable_session_id   Stable activation-era session identifier; constant across transparent
 *                             reconnects within the same Android runtime era.
 * @param session_continuity_epoch Monotone reconnect epoch within [durable_session_id]; enables V2
 *                             to discard late reconciliation signals from a prior reconnect epoch.
 * @param payload              Signal-specific free-form fields (e.g. `error_detail`, `health_state`).
 *                             Empty map when no additional fields apply.
 * @param runtime_truth        Populated only for `kind = "runtime_truth_snapshot"` signals.
 *                             Contains the serialised [com.ufo.galaxy.runtime.AndroidParticipantRuntimeTruth]
 *                             key-value map.  `null` for all other signal kinds.
 */
data class ReconciliationSignalPayload(
    val signal_id: String,
    val kind: String,
    val participant_id: String,
    val status: String,
    val emitted_at_ms: Long,
    val reconciliation_epoch: Int,
    val device_id: String,
    val task_id: String? = null,
    val correlation_id: String? = null,
    val session_id: String? = null,
    val durable_session_id: String? = null,
    val session_continuity_epoch: Int? = null,
    val payload: Map<String, Any?> = emptyMap(),
    val runtime_truth: Map<String, Any>? = null,
    val ingress_boundary_class: String? = null,
    val ingress_consumption_kind: String? = null,
    val ingress_signal_class: String? = null,
    val ingress_schema_version: String? = null,
    val schema_version: String? = null,
    val completion_closure_contract_version: String? = null,
    val completion_emission_id: String? = null,
    val idempotency_key: String? = null,
    val is_v2_confirmed_canonical_truth: Boolean? = null,
    val uplink_lineage_schema_version: String? = null,
    val uplink_lineage_execution_id: String? = null,
    val uplink_lineage_emission_id: String? = null,
    val uplink_lineage_dedupe_key: String? = null,
    val uplink_lineage_recovery_basis: String? = null
)

// ── PR-H: HandoffEnvelopeV2 native consumption result payload ─────────────────────────────

/**
 * Uplink result payload for [MsgType.HANDOFF_ENVELOPE_V2_RESULT].
 *
 * Sent by Android after consuming a [MsgType.HANDOFF_ENVELOPE_V2] envelope.
 * Carries the full ACK / execution outcome so V2 can reconcile its handoff
 * state without a "consumed-but-no-confirmation" black-hole.
 *
 * All identity fields are echoed from the originating envelope to allow
 * end-to-end correlation by V2 without requiring per-device session state.
 *
 * ## Status model
 *
 * The [status] field uses a stable three-value vocabulary defined by companion constants:
 *
 * | [status] value        | Constant            | Meaning                                      |
 * |-----------------------|---------------------|----------------------------------------------|
 * | `"ack"`               | [STATUS_ACK]        | Envelope received and parsed; execution starting |
 * | `"result"`            | [STATUS_RESULT]     | Execution completed successfully              |
 * | `"failure"`           | [STATUS_FAILURE]    | Execution failed (parse error, runtime error) |
 *
 * Android sends one [STATUS_ACK] immediately after the envelope is parsed, then exactly
 * one terminal message: either [STATUS_RESULT] or [STATUS_FAILURE].
 *
 * ## Fields
 *
 * | Field                    | Role                                                              |
 * |--------------------------|-------------------------------------------------------------------|
 * | [handoff_id]             | Stable handoff identifier; echoed from the originating envelope;  |
 * |                          | resolved at construction time (falls back to [task_id] for legacy)|
 * | [task_id]                | Echoed from [com.ufo.galaxy.agent.HandoffEnvelopeV2.task_id]      |
 * | [trace_id]               | Echoed from [com.ufo.galaxy.agent.HandoffEnvelopeV2.trace_id]     |
 * | [correlation_id]         | Set to [task_id] for reply routing                                |
 * | [status]                 | Stable status: [STATUS_ACK] / [STATUS_RESULT] / [STATUS_FAILURE]  |
 * | [result_summary]         | Human-readable one-line outcome for gateway aggregation           |
 * | [error]                  | Structured error detail when [status] is [STATUS_FAILURE]         |
 * | [consumed_at_ms]         | Epoch-ms timestamp when Android received and started consuming    |
 * | [device_id]              | Consuming Android device identifier                               |
 * | [route_mode]             | Routing path ("cross_device") for gateway correlation             |
 * | [dispatch_plan_id]       | Echoed from the originating envelope; null for legacy senders     |
 * | [continuity_token]       | Echoed from the originating envelope; null for legacy senders     |
 * | [dispatch_intent]        | Echoed from the originating envelope; null for legacy senders     |
 * | [execution_context]      | Echoed from the originating envelope; empty for legacy senders    |
 * | [executor_target_type]   | Echoed from the originating envelope; null for legacy senders     |
 * | [source_runtime_posture] | Echoed from the originating envelope; null for legacy senders     |
 *
 * @param handoff_id             Stable handoff identifier echoed from [HandoffEnvelopeV2.handoff_id].
 *                               Always non-null in the result payload: callers must resolve the
 *                               effective identifier at construction time (using
 *                               `envelope.handoff_id?.takeIf { it.isNotBlank() } ?: task_id`
 *                               for legacy senders that omit the field).
 * @param task_id                Unique task identifier echoed from [HandoffEnvelopeV2].
 * @param trace_id               End-to-end trace identifier echoed from [HandoffEnvelopeV2].
 * @param correlation_id         Set to [task_id] for gateway reply routing.
 * @param status                 Stable execution status: [STATUS_ACK], [STATUS_RESULT], or [STATUS_FAILURE].
 * @param result_summary         Human-readable one-line outcome description.
 * @param error                  Structured error description when [status] is [STATUS_FAILURE].
 * @param consumed_at_ms         Epoch-ms timestamp when Android received the envelope.
 * @param device_id              Consuming Android device identifier.
 * @param route_mode             Routing path; always "cross_device" for handoff consumption.
 * @param dispatch_plan_id       Echoed from [HandoffEnvelopeV2.dispatch_plan_id]; null for legacy.
 * @param continuity_token       Echoed from [HandoffEnvelopeV2.continuity_token]; null for legacy.
 * @param dispatch_intent        Echoed from [HandoffEnvelopeV2.dispatch_intent]; null for legacy.
 * @param execution_context      Echoed from [HandoffEnvelopeV2.execution_context]; empty for legacy.
 * @param executor_target_type   Echoed from [HandoffEnvelopeV2.executor_target_type]; null for legacy.
 * @param source_runtime_posture Echoed from [HandoffEnvelopeV2.source_runtime_posture]; null for legacy.
 */
data class HandoffEnvelopeV2ResultPayload(
    val handoff_id: String,
    val task_id: String,
    val trace_id: String,
    val correlation_id: String,
    val status: String,
    val result_summary: String? = null,
    val error: String? = null,
    val consumed_at_ms: Long = System.currentTimeMillis(),
    val device_id: String = "",
    val route_mode: String = "cross_device",
    // ── Echoed identity fields for end-to-end V2 correlation ─────────────────────────
    val dispatch_plan_id: String? = null,
    val continuity_token: String? = null,
    val dispatch_intent: String? = null,
    val execution_context: Map<String, String> = emptyMap(),
    val executor_target_type: String? = null,
    val source_runtime_posture: String? = null
) {
    companion object {
        /** Stable status: Android has received and parsed the envelope; execution is starting. */
        const val STATUS_ACK = "ack"

        /** Stable status: execution completed successfully; [HandoffEnvelopeV2ResultPayload.result_summary] is set. */
        const val STATUS_RESULT = "result"

        /** Stable status: execution failed (parse error or runtime error); [HandoffEnvelopeV2ResultPayload.error] is set. */
        const val STATUS_FAILURE = "failure"
    }
}

/**
 * Downlink payload for [MsgType.OPERATOR_ACTION_REQUEST].
 *
 * Represents an operator/governance action routed from V2/board surfaces into Android's
 * runtime control plane.
 */
data class OperatorActionRequestPayload(
    val action_id: String,
    val action_kind: String,
    val task_id: String? = null,
    val trace_id: String? = null,
    val requested_by: String? = null,
    val reason: String? = null,
    val session_id: String? = null,
    val governance_context: Map<String, String> = emptyMap(),
    val requested_at_ms: Long = System.currentTimeMillis()
)

/**
 * Uplink payload for [MsgType.OPERATOR_ACTION_RESULT].
 *
 * Carries both decision (accepted/rejected) and execution outcome semantics for operator actions.
 *
 * ## PR-B2 参与上下文字段（V2 消费约定）
 *
 * PR-B2 新增以下参与上下文字段，确保 V2 下游消费方可以将 operator action 的决策与执行
 * 结果关联到精确的 Android 侧参与语境，而无需跨消息重建上下文：
 *
 * | 字段                         | 语义                                                        |
 * |------------------------------|-------------------------------------------------------------|
 * | [participation_tier]         | 七级参与层级（wire 值），从 [authoritative_participation_state] 派生 |
 * | [local_mode_active]          | Android 当前是否处于本地模式（非跨设备）                        |
 * | [runtime_constrained]        | Android 是否因运行时约束而无法正常分发                          |
 * | [runtime_deferred]           | Android 是否处于延迟/保持状态                                  |
 * | [delegated_execution_active] | Android 当前是否有活跃的委托执行任务                            |
 *
 * V2 消费方 MUST 使用 [participation_tier] 而非通过 [authoritative_participation_state]
 * 组合推断参与层级；MUST 将 [delegated_execution_active] 作为委托执行状态的权威信号。
 */
data class OperatorActionResultPayload(
    val action_id: String,
    val action_kind: String,
    val phase: String,
    val decision_status: String,
    val execution_status: String,
    val rollback_status: String = ROLLBACK_NOT_REQUIRED,
    val task_id: String? = null,
    val trace_id: String? = null,
    val runtime_state: String? = null,
    val reconnect_recovery_state: String? = null,
    val authoritative_participation_state: String? = null,
    // ── PR-B2: 参与上下文保留字段 ────────────────────────────────────────────────
    // 这些字段在 action 接收时刻由 OperatorActionReceiver 捕获，并在 DECISION 和
    // EXECUTION 两个阶段保持一致，使 V2 无需跨消息重建参与上下文。
    /** 七级参与层级（wire 值），从 [authoritative_participation_state] 派生。*/
    val participation_tier: String? = null,
    /** Android 当前是否处于本地执行模式（LocalExecutionModeGate.ExecutionModeState == LOCAL_ONLY）。 */
    val local_mode_active: Boolean? = null,
    /** Android 是否因运行时约束无法正常分发（dispatch_eligible == false 或 CONSTRAINED 降级）。 */
    val runtime_constrained: Boolean? = null,
    /** Android 是否处于延迟/保持状态（isHoldState == true）。 */
    val runtime_deferred: Boolean? = null,
    /** Android 当前是否有活跃的委托执行任务（来自 AndroidUnifiedTruthUplinkContract.GovernanceTruth）。 */
    val delegated_execution_active: Boolean? = null,
    // ── PR-11Android: tool/action/authorization uplink 收束字段 ───────────────────────────
    // operator_intent_capture_class: 本消息中 operator 意图捕获语义分类。
    // runtime_authority_class: 本消息中 runtime 授权结论分类（批准/拒绝）。
    // actual_execution_signal_class: 本消息中实际执行信号分类（未执行/尝试但无 side effect/确认 side effect）。
    // tool_invocation_signal_class: 本消息中工具调用信号分类（已确认调用/未调用）。
    // result_reporting_signal_class: 本消息中结果上报语义分类（decision/execution）。
    // post_action_explanation_class: 本消息中 post-action 解释语义分类（none/factual/failure）。
    // tool_action_authorization_schema_version: 上述字段 schema 版本。
    val operator_intent_capture_class: String? = null,
    val runtime_authority_class: String? = null,
    val actual_execution_signal_class: String? = null,
    val tool_invocation_signal_class: String? = null,
    val result_reporting_signal_class: String? = null,
    val post_action_explanation_class: String? = null,
    val tool_action_authorization_schema_version: String? = null,
    val attached_session_id: String? = null,
    val active_takeover_id: String? = null,
    val error: String? = null,
    val details: Map<String, String> = emptyMap(),
    val reported_at_ms: Long = System.currentTimeMillis()
) {
    companion object {
        const val PHASE_DECISION = "decision"
        const val PHASE_EXECUTION = "execution"

        const val DECISION_ACCEPTED = "accepted"
        const val DECISION_REJECTED = "rejected"

        const val EXECUTION_PENDING = "pending"
        const val EXECUTION_EXECUTED = "executed"
        const val EXECUTION_FAILED = "failed"
        const val EXECUTION_REJECTED = "rejected"
        const val EXECUTION_PARTIAL = "partial"

        const val ROLLBACK_NOT_REQUIRED = "not_required"
        const val ROLLBACK_COMPLETED = "rollback_completed"
        const val ROLLBACK_COMPENSATING_ACTION_REQUESTED = "compensating_action_requested"
    }
}

// ── Android-side signal closure: device readiness report uplink ──────────────────────────────

/**
 * Uplink payload for [MsgType.DEVICE_READINESS_REPORT].
 *
 * Carries the structured readiness artifact and per-dimension snapshot produced by
 * [com.ufo.galaxy.runtime.DelegatedRuntimeReadinessEvaluator] toward V2 release-gate and
 * governance consumption paths.
 *
 * Emitted by Android after the capability_report handshake completes and after any
 * relevant dimension-state change, so V2 always has a current Android-side readiness
 * conclusion available without needing to poll.
 *
 * ## Artifact vocabulary
 *
 * The [artifact_tag] field uses the stable wire-tag constants from
 * [com.ufo.galaxy.runtime.DelegatedRuntimeReadinessEvaluator]:
 *
 * | [artifact_tag]                                        | Meaning                                                |
 * |-------------------------------------------------------|--------------------------------------------------------|
 * | `"device_ready_for_release"`                          | All five dimensions are READY; V2 gate may proceed.    |
 * | `"device_not_ready_due_to_truth_gap"`                 | Truth / continuity dimension has an unresolved gap.    |
 * | `"device_not_ready_due_to_result_gap"`                | Result-convergence dimension has an unresolved gap.    |
 * | `"device_not_ready_due_to_execution_event_gap"`       | Canonical-execution-event dimension has a gap.         |
 * | `"device_not_ready_due_to_compat_gap"`                | Compat / legacy-blocking dimension has a gap.          |
 * | `"device_readiness_unknown_due_to_missing_signal"`    | One or more dimensions have no signal yet.             |
 *
 * @param artifact_tag        Stable wire-tag of the [com.ufo.galaxy.runtime.DeviceReadinessArtifact].
 * @param snapshot_id         UUID of this readiness snapshot; stable across retransmissions.
 * @param device_id           Device identifier.
 * @param session_id          Runtime session ID at emission time; null when not yet established.
 * @param reported_at_ms      Wall-clock epoch-ms timestamp of artifact production.
 * @param dimension_states    Per-dimension status map: dimension wire-name → status string
 *                            (`"READY"`, `"GAP"`, or `"UNKNOWN"`).
 * @param first_gap_reason    Human-readable explanation of the first gap found; null when
 *                            artifact is [artifact_tag] `device_ready_for_release` or
 *                            `device_readiness_unknown_due_to_missing_signal`.
 * @param missing_dimensions  Dimension wire-names that have no signal yet; empty when all
 *                            dimensions have been reported.
 */
data class DeviceReadinessReportPayload(
    val artifact_tag: String,
    val snapshot_id: String,
    val device_id: String,
    val session_id: String?,
    val reported_at_ms: Long = System.currentTimeMillis(),
    val dimension_states: Map<String, String> = emptyMap(),
    val first_gap_reason: String? = null,
    val missing_dimensions: List<String> = emptyList(),
    val ingress_boundary_class: String? = null,
    val ingress_consumption_kind: String? = null,
    val ingress_signal_class: String? = null,
    val ingress_schema_version: String? = null,
    // ── PR-120 (Android): Non-closure signal boundary classification ────────────
    //
    // non_closure_signal_class: Explicit non-closure classification for this readiness report payload.
    //   Always "readiness_only" (AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.READINESS_ONLY).
    //   V2 MUST NOT use this payload for canonical task closure; only for release-gate assessment.
    val non_closure_signal_class: String? = null,
    val non_closure_schema_version: String? = null
)

// ── PR-4 (Android): Governance / acceptance / strategy report uplink payloads ────────────────

/**
 * Uplink payload for [MsgType.DEVICE_GOVERNANCE_REPORT].
 *
 * Carries the structured post-graduation governance artifact and per-dimension observation
 * snapshot produced by
 * [com.ufo.galaxy.runtime.DelegatedRuntimePostGraduationGovernanceEvaluator] toward V2
 * post-graduation governance / enforcement layer consumption paths.
 *
 * Emitted by Android on service start (baseline — all dimensions UNKNOWN) and after each
 * relevant dimension observation-state change so V2 always has a current Android-side
 * governance conclusion available.
 *
 * ## Artifact semantics
 *
 * [artifact_tag] uses the stable wire-tag constants from
 * [com.ufo.galaxy.runtime.DelegatedRuntimePostGraduationGovernanceEvaluator]:
 *
 * | [artifact_tag]                                                    | Meaning                                                               |
 * |-------------------------------------------------------------------|-----------------------------------------------------------------------|
 * | `"device_governance_compliant"`                                   | All five dimensions compliant; device remains canonical.              |
 * | `"device_governance_violation_due_to_truth_regression"`           | Truth / continuity alignment regression detected since graduation.    |
 * | `"device_governance_violation_due_to_result_regression"`          | Result convergence regression detected since graduation.              |
 * | `"device_governance_violation_due_to_execution_visibility_regression"` | Execution visibility regression detected since graduation.       |
 * | `"device_governance_violation_due_to_compat_bypass"`              | Compat / legacy bypass reintroduced since graduation.                 |
 * | `"device_governance_unknown_due_to_missing_signal"`               | One or more dimensions have no signal yet.                            |
 *
 * ## V2 artifact classification
 *
 * This artifact is **canonical participant evidence** when produced from a live
 * post-graduation governance evaluation path in GalaxyConnectionService.  V2 may treat
 * `device_governance_compliant` as authoritative ongoing-compliance evidence.  Violation
 * artifacts feed directly into V2 enforcement / escalation inputs.
 *
 * @param artifact_tag          Stable wire-tag of the [com.ufo.galaxy.runtime.DeviceGovernanceArtifact].
 * @param snapshot_id           UUID of this governance snapshot; stable across retransmissions.
 * @param device_id             Device identifier.
 * @param session_id            Runtime session ID at emission time; null when not yet established.
 * @param reported_at_ms        Wall-clock epoch-ms timestamp of artifact production.
 * @param dimension_states      Per-dimension observation status map: dimension wire-name →
 *                              status string (`"COMPLIANT"`, `"REGRESSION"`, or `"UNKNOWN"`).
 * @param first_regression_reason Human-readable explanation of the first regression found; null
 *                              when artifact is compliant or unknown-due-to-missing-signal.
 * @param missing_dimensions    Dimension wire-names that have no observation signal yet; empty
 *                              when all dimensions have been reported.
 */
data class DeviceGovernanceReportPayload(
    val artifact_tag: String,
    val snapshot_id: String,
    val device_id: String,
    val session_id: String?,
    val reported_at_ms: Long = System.currentTimeMillis(),
    val dimension_states: Map<String, String> = emptyMap(),
    val first_regression_reason: String? = null,
    val missing_dimensions: List<String> = emptyList(),
    val ingress_boundary_class: String? = null,
    val ingress_consumption_kind: String? = null,
    val ingress_signal_class: String? = null,
    val ingress_schema_version: String? = null,
    // ── PR-120 (Android): Non-closure signal boundary classification ────────────
    //
    // non_closure_signal_class: Explicit non-closure classification for this governance report.
    //   Always "evaluator_advisory_only" (AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.EVALUATOR_ADVISORY_ONLY).
    //   V2 MUST feed this only to governance/graduation gates; MUST NOT produce canonical task closure.
    val non_closure_signal_class: String? = null,
    val non_closure_schema_version: String? = null
)

/**
 * Uplink payload for [MsgType.DEVICE_ACCEPTANCE_REPORT].
 *
 * Carries the structured final acceptance artifact and per-dimension evidence snapshot
 * produced by [com.ufo.galaxy.runtime.DelegatedRuntimeAcceptanceEvaluator] toward V2
 * final acceptance / graduation gate consumption paths.
 *
 * Emitted by Android on service start (baseline — all dimensions UNKNOWN) and after each
 * relevant dimension evidence-state change so V2 always has a current Android-side
 * acceptance conclusion available.
 *
 * ## Artifact semantics
 *
 * [artifact_tag] uses the stable wire-tag constants from
 * [com.ufo.galaxy.runtime.DelegatedRuntimeAcceptanceEvaluator]:
 *
 * | [artifact_tag]                                       | Meaning                                                            |
 * |------------------------------------------------------|--------------------------------------------------------------------|
 * | `"device_accepted_for_graduation"`                   | All six evidence dimensions satisfied; device may graduate.        |
 * | `"device_rejected_due_to_missing_evidence"`          | Readiness prerequisite not yet established or explicit gap.        |
 * | `"device_rejected_due_to_truth_gap"`                 | Truth / continuity evidence gap found.                             |
 * | `"device_rejected_due_to_result_gap"`                | Result convergence evidence gap found.                             |
 * | `"device_rejected_due_to_execution_event_gap"`       | Canonical execution event evidence gap found.                      |
 * | `"device_rejected_due_to_compat_bypass_risk"`        | Compat / legacy bypass risk found in evidence.                     |
 * | `"device_acceptance_unknown_due_to_incomplete_signal"` | One or more dimensions have no signal yet.                       |
 *
 * ## V2 artifact classification
 *
 * This artifact is **canonical participant evidence** when produced from a live
 * acceptance evaluation path in GalaxyConnectionService.  V2 graduation gate must treat
 * `device_accepted_for_graduation` as an authoritative graduation readiness signal.
 *
 * @param artifact_tag          Stable wire-tag of the [com.ufo.galaxy.runtime.DeviceAcceptanceArtifact].
 * @param snapshot_id           UUID of this acceptance snapshot; stable across retransmissions.
 * @param device_id             Device identifier.
 * @param session_id            Runtime session ID at emission time; null when not yet established.
 * @param reported_at_ms        Wall-clock epoch-ms timestamp of artifact production.
 * @param dimension_states      Per-dimension evidence status map: dimension wire-name →
 *                              status string (`"EVIDENCED"`, `"GAP"`, or `"UNKNOWN"`).
 * @param first_gap_reason      Human-readable explanation of the first evidence gap found; null
 *                              when artifact is accepted or unknown-due-to-incomplete-signal.
 * @param missing_dimensions    Dimension wire-names that have no evidence signal yet; empty
 *                              when all dimensions have been reported.
 */
data class DeviceAcceptanceReportPayload(
    val artifact_tag: String,
    val snapshot_id: String,
    val device_id: String,
    val session_id: String?,
    val reported_at_ms: Long = System.currentTimeMillis(),
    val dimension_states: Map<String, String> = emptyMap(),
    val first_gap_reason: String? = null,
    val missing_dimensions: List<String> = emptyList(),
    val ingress_boundary_class: String? = null,
    val ingress_consumption_kind: String? = null,
    val ingress_signal_class: String? = null,
    val ingress_schema_version: String? = null,
    // ── PR-120 (Android): Non-closure signal boundary classification ────────────
    //
    // non_closure_signal_class: Explicit non-closure classification for this acceptance report.
    //   Always "evaluator_advisory_only" (AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.EVALUATOR_ADVISORY_ONLY).
    //   V2 graduation gate uses this for readiness evidence only; MUST NOT produce canonical task closure.
    val non_closure_signal_class: String? = null,
    val non_closure_schema_version: String? = null
)

/**
 * Uplink payload for [MsgType.DEVICE_STRATEGY_REPORT].
 *
 * Carries the structured program strategy / evolution posture artifact and per-dimension
 * posture snapshot produced by [com.ufo.galaxy.runtime.DelegatedRuntimeStrategyEvaluator]
 * toward V2 program strategy / evolution control layer consumption paths.
 *
 * Emitted by Android on service start (baseline — all dimensions UNKNOWN) and after each
 * relevant dimension posture-state change so V2 always has a current Android-side strategy
 * posture conclusion available.
 *
 * ## Artifact semantics
 *
 * [artifact_tag] uses the stable wire-tag constants from
 * [com.ufo.galaxy.runtime.DelegatedRuntimeStrategyEvaluator]:
 *
 * | [artifact_tag]                                                    | Meaning                                                         |
 * |-------------------------------------------------------------------|-----------------------------------------------------------------|
 * | `"device_strategy_on_track"`                                      | All five dimensions on track; evolution posture aligned.        |
 * | `"device_strategy_risk_due_to_contract_instability"`              | Contract instability / regression pressure risk detected.       |
 * | `"device_strategy_risk_due_to_governance_regression_trend"`       | Negative governance trend detected at strategy level.           |
 * | `"device_strategy_risk_due_to_rollout_maturity_gap"`              | Rollout maturity / default-on posture gap detected.             |
 * | `"device_strategy_risk_due_to_runtime_coupling_drift"`            | Cross-module coupling drift detected.                           |
 * | `"device_strategy_unknown_due_to_missing_program_signal"`         | One or more dimensions have no program signal yet.              |
 *
 * ## V2 artifact classification
 *
 * This artifact is **advisory / observation-only** by default.  V2 program strategy /
 * evolution control layer may consume `device_strategy_on_track` as an advisory alignment
 * signal, but risk artifacts should inform strategy decisions rather than block execution
 * unilaterally.  V2 retains full orchestration authority over program strategy decisions.
 *
 * @param artifact_tag          Stable wire-tag of the [com.ufo.galaxy.runtime.DeviceStrategyArtifact].
 * @param snapshot_id           UUID of this strategy snapshot; stable across retransmissions.
 * @param device_id             Device identifier.
 * @param session_id            Runtime session ID at emission time; null when not yet established.
 * @param reported_at_ms        Wall-clock epoch-ms timestamp of artifact production.
 * @param dimension_states      Per-dimension posture status map: dimension wire-name →
 *                              status string (`"ON_TRACK"`, `"AT_RISK"`, or `"UNKNOWN"`).
 * @param first_risk_reason     Human-readable explanation of the first strategic risk found; null
 *                              when artifact is on-track or unknown-due-to-missing-signal.
 * @param missing_dimensions    Dimension wire-names that have no program signal yet; empty when
 *                              all dimensions have been reported.
 */
data class DeviceStrategyReportPayload(
    val artifact_tag: String,
    val snapshot_id: String,
    val device_id: String,
    val session_id: String?,
    val reported_at_ms: Long = System.currentTimeMillis(),
    val dimension_states: Map<String, String> = emptyMap(),
    val first_risk_reason: String? = null,
    val missing_dimensions: List<String> = emptyList(),
    val ingress_boundary_class: String? = null,
    val ingress_consumption_kind: String? = null,
    val ingress_signal_class: String? = null,
    val ingress_schema_version: String? = null,
    // ── PR-120 (Android): Non-closure signal boundary classification ────────────
    //
    // non_closure_signal_class: Explicit non-closure classification for this strategy report.
    //   Always "evaluator_advisory_only" (AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.EVALUATOR_ADVISORY_ONLY).
    //   V2 MUST feed this only to strategy/evolution gates; MUST NOT produce canonical task closure.
    val non_closure_signal_class: String? = null,
    val non_closure_schema_version: String? = null
)

/**
 * Uplink payload for [MsgType.DEVICE_AUDIT_REPORT].
 *
 * Carries the structured Android delegated runtime audit evidence and per-dimension audit
 * snapshot produced by [com.ufo.galaxy.runtime.AndroidDelegatedRuntimeAudit] toward V2
 * acceptance, readiness, and governance layer consumption paths.
 *
 * Emitted by Android on service start (baseline — all dimensions UNKNOWN) and after each
 * relevant dimension audit-state change so V2 always has a current, structured Android-side
 * runtime audit conclusion available.
 *
 * This payload closes the `android_participant unresolved` gap in V2's
 * `system_final_acceptance_verdict` by providing evidence that is:
 *  - Structured (not human-readable log only)
 *  - Classified into named evidence quality states (ready / degraded / unavailable / stale /
 *    malformed_incomplete / unverified)
 *  - Backed by explicit capability honesty verification (not just capability advertisement)
 *  - Freshness-bounded so V2 can detect stale participant state
 *  - Stable in wire format (schema_version 1.0)
 *
 * ## Evidence tag semantics
 *
 * [evidence_tag] uses the stable wire-tag constants from
 * [com.ufo.galaxy.runtime.AndroidDelegatedRuntimeAudit]:
 *
 * | [evidence_tag]                            | Meaning                                                                  |
 * |-------------------------------------------|--------------------------------------------------------------------------|
 * | `"audit_evidence_ready"`                  | All six dimensions AUDITED and fresh; participant fully operational.      |
 * | `"audit_evidence_degraded"`               | Some dimensions DEGRADED; participant available with limitations.         |
 * | `"audit_evidence_unavailable"`            | Participant not reachable or not registered.                             |
 * | `"audit_evidence_stale"`                  | Evidence is too old; refresh required before V2 consumption.             |
 * | `"audit_evidence_malformed_incomplete"`   | Evidence is malformed or incomplete; V2 must reject.                     |
 * | `"audit_evidence_unverified"`             | One or more dimensions have no signal; audit cannot be concluded.        |
 *
 * ## V2 evidence ingestion
 *
 * V2 code should use [wire_map] for ingestion.  The map has schema_version `"1.0"` and
 * contains all fields from [com.ufo.galaxy.runtime.AndroidDelegatedRuntimeAuditSnapshot.toWireMap].
 *
 * @param evidence_tag          Stable wire-tag of the [com.ufo.galaxy.runtime.AndroidDelegatedRuntimeAuditEvidence].
 * @param snapshot_id           UUID of this audit snapshot; stable across retransmissions.
 * @param device_id             Device identifier.
 * @param session_id            Runtime session ID at emission time; null when not yet established.
 * @param reported_at_ms        Wall-clock epoch-ms timestamp of evidence production.
 * @param dimension_states      Per-dimension audit status map: dimension wire-name →
 *                              status string (`"audited"`, `"degraded"`, `"unavailable"`,
 *                              `"stale"`, `"malformed"`, or `"unknown"`).
 * @param dimension_reasons     Per-dimension reason map: dimension wire-name → reason string
 *                              (only for non-AUDITED states).
 * @param missing_dimensions    Dimension wire-names that have no audit signal yet; empty when
 *                              all dimensions have been reported.
 * @param capability_honesty_audited  `true` when CAPABILITY_HONESTY dimension is AUDITED
 *                              (i.e. [CapabilityHonestyGuard.isHonest] returned no violations).
 * @param health_state_audited  `true` when HEALTH_STATE dimension is AUDITED (runtime healthy).
 * @param participant_registered `true` when PARTICIPANT_REGISTRATION dimension is AUDITED.
 * @param wire_map              The full [com.ufo.galaxy.runtime.AndroidDelegatedRuntimeAuditSnapshot.toWireMap]
 *                              output for direct V2 ingestion; schema_version `"1.0"`.
 */
data class DeviceAuditReportPayload(
    val evidence_tag: String,
    val snapshot_id: String,
    val device_id: String,
    val session_id: String?,
    val reported_at_ms: Long = System.currentTimeMillis(),
    val dimension_states: Map<String, String> = emptyMap(),
    val dimension_reasons: Map<String, String> = emptyMap(),
    val missing_dimensions: List<String> = emptyList(),
    val capability_honesty_audited: Boolean = false,
    val health_state_audited: Boolean = false,
    val participant_registered: Boolean = false,
    val wire_map: Map<String, Any> = emptyMap()
)

// ── PR-RT: Android runtime-state snapshot uplink payload ─────────────────────────────────

/**
 * Uplink payload for [MsgType.DEVICE_STATE_SNAPSHOT] (PR-RT).
 *
 * Carries a complete structured snapshot of the Android device runtime state emitted on the
 * canonical Android→V2 control-plane WebSocket path. V2 absorbs this payload via
 * `core.android_device_state_store.absorb_device_state_snapshot()`, which makes the data
 * available at the V2 operator/ecosystem surfaces.
 *
 * Field names use snake_case to match the V2 `_parse_state_snapshot` function's primary
 * keys (the V2 parser also accepts camelCase aliases).
 *
 * @param device_id             Identity of the Android device (from `Build.MANUFACTURER_Build.MODEL`).
 * @param snapshot_ts           Unix epoch-ms timestamp of snapshot production.
 *
 * Native runtime availability:
 * @param llama_cpp_available   Whether libllama.so loaded successfully ([NativeInferenceLoader.isLlamaCppAvailable]).
 * @param ncnn_available        Whether libncnn.so loaded successfully ([NativeInferenceLoader.isNcnnAvailable]).
 * @param active_runtime_type   Current primary inference runtime (`"LLAMA_CPP"`, `"NCNN"`, `"HYBRID"`, `"CENTER"`).
 *
 * Readiness state (from [AppSettings] and [LocalLoopReadinessProvider]):
 * @param model_ready           Whether local model files are present and verified.
 * @param accessibility_ready   Whether the Accessibility Service is active and ready.
 * @param overlay_ready         Whether the overlay permission is granted and active.
 * @param local_loop_ready      Whether the full local-loop pipeline is ready for execution.
 * @param degraded_reasons      Human-readable list of active degradation reasons, if any.
 *
 * Model identity (from [ModelAssetManager]):
 * @param model_id              Canonical model identifier (e.g. `"mobilevlm_v2_1.7b"`).
 * @param runtime_type          Model runtime type string (e.g. `"LLAMA_CPP"`).
 * @param checksum_ok           Whether the model checksum passed verification.
 * @param mobilevlm_present     Whether the MobileVLM model file exists on device.
 * @param mobilevlm_checksum_ok Whether the MobileVLM checksum passed verification.
 * @param seeclick_present      Whether the SeeClick model files (param+bin) exist on device.
 * @param pending_first_download Whether the device is still awaiting its first model download.
 *
 * Local loop config (from [UFOGalaxyApplication.localLoopConfig]):
 * @param local_loop_config     Active LocalLoopConfig as a map, or null when not yet initialised.
 *
 * Runtime health (from [LocalInferenceRuntimeManager.state]):
 * @param warmup_result         Warmup result string (`"ok"`, `"degraded"`, `"failed"`, `"not_started"`, `"unavailable"`).
 * @param runtime_health_snapshot Planner + grounding component health map, or null.
 *
 * Queue / fallback state:
 * @param offline_queue_depth        Current depth of the offline task queue ([GalaxyWebSocketClient.queueSize]).
 * @param current_fallback_tier      Current fallback tier string derived from rollout-control state.
 * @param planner_fallback_tier      Active planner fallback tier string derived from
 *                                   [com.ufo.galaxy.config.FallbackConfig.enablePlannerFallback]:
 *                                   `"active"` when the local planner fallback ladder is enabled,
 *                                   `"disabled"` when it is disabled, `null` when the config is
 *                                   not yet available.  V2 `_parse_state_snapshot` accepts this
 *                                   field under the primary key `planner_fallback_tier`.
 * @param grounding_fallback_tier    Active grounding fallback tier string derived from
 *                                   [com.ufo.galaxy.config.FallbackConfig.enableGroundingFallback]:
 *                                   `"active"` when the local grounding fallback ladder is enabled,
 *                                   `"disabled"` when it is disabled, `null` when the config is
 *                                   not yet available.  V2 `_parse_state_snapshot` accepts this
 *                                   field under the primary key `grounding_fallback_tier`.
 *
 * Session / invocation identity continuity (PR-6):
 * @param durable_session_id         The durable session era identifier from
 *                                   [com.ufo.galaxy.runtime.DurableSessionContinuityRecord.durableSessionId].
 *                                   Stable across all WS reconnects within the same activation era.
 *                                   `null` when no durable era is active (not yet started or already stopped).
 * @param session_continuity_epoch   Monotonically increasing reconnect counter within the durable era from
 *                                   [com.ufo.galaxy.runtime.DurableSessionContinuityRecord.sessionContinuityEpoch].
 *                                   `null` when [durable_session_id] is `null`.
 * @param runtime_session_id         Per-app-launch runtime session UUID from
 *                                   [com.ufo.galaxy.UFOGalaxyApplication.runtimeSessionId].
 *                                   Stable within one app process; distinct from [durable_session_id].
 *                                   `null` when the session is not yet initialised.
 * @param attached_session_id        The attached runtime session UUID from
 *                                   [com.ufo.galaxy.runtime.AttachedRuntimeSession.sessionId].
 *                                   Created per attach event; stable across transitions.
 *                                   `null` when no session is currently attached.
 *
 * Carrier manifestation/presence hints (PR-8):
 * @param carrier_foreground_visible Whether the app is currently in the foreground, backed by
 *                                   [com.ufo.galaxy.runtime.RuntimeController.appForegroundVisible].
 *                                   `true` = Android is visibly present to the user (active
 *                                   interaction surface); `false` = backgrounded runtime carrier;
 *                                   `null` = foreground state not yet observed (safe default).
 *                                   No fake value is ever emitted.
 * @param interaction_surface_ready  Whether the full local interaction surface is operational:
 *                                   `accessibility_ready && overlay_ready`.  `true` when Android
 *                                   can both display a floating overlay and interact with the
 *                                   screen; `false` when either capability is missing; `null`
 *                                   when either underlying flag is unavailable.  Matches the
 *                                   same pre-flight condition used by
 *                                   [com.ufo.galaxy.runtime.CrossDeviceEnablementError] and
 *                                   [com.ufo.galaxy.agent.TakeoverEligibilityAssessor].
 *
 * Final Android carrier consolidation fields (PR-10):
 * @param carrier_runtime_state      Wire label of [com.ufo.galaxy.runtime.RuntimeController.state]
 *                                   at snapshot time.  Values: `"idle"` | `"starting"` | `"active"` |
 *                                   `"failed"` | `"local_only"`.  Allows V2 to distinguish snapshots
 *                                   emitted from an Active (cross-device) carrier from those emitted
 *                                   during local-only or startup state.  `null` only when the runtime
 *                                   state cannot be read (defensive default; should never occur in
 *                                   practice since [RuntimeController.RuntimeState] is always defined).
 * @param reconnect_recovery_state   Wire value of [com.ufo.galaxy.runtime.RuntimeController.reconnectRecoveryState]
 *                                   at snapshot time.  Values: `"idle"` | `"recovering"` |
 *                                   `"recovered"` | `"failed"`.  Contextualizes snapshots emitted
 *                                   during a reconnect recovery cycle so V2 can handle them
 *                                   correctly.  `null` only as a defensive default (same as above).
 *
 * PR-12: Android real reconnect/recovery participation fields.
 * These three fields close the gap between scattered reconnect components and expose a
 * coherent, wire-stable participation model that V2 can act on during recovery closure
 * without inferring participation kind from field combinations.
 * @param reconnect_participation_kind Classification of the reconnect/re-attach scenario
 *                                   from [com.ufo.galaxy.runtime.AndroidReconnectRecoveryParticipationContract].
 *                                   Values: `"fresh_attach"` | `"transport_reconnect"` |
 *                                   `"process_recreation_with_context"` |
 *                                   `"process_recreation_without_context"`.
 *                                   V2 MUST use this field to select the correct recovery closure
 *                                   path.  `null` only as a defensive default.
 * @param identity_reuse_decision    How Android reuses its durable identity during reconnect.
 *                                   Values: `"reuse_durable_participant"` | `"reuse_session_only"` |
 *                                   `"fresh_identity"`.
 *                                   V2 MUST use this field to determine whether to look up a prior
 *                                   participant record or initialise a new one.  `null` only as a
 *                                   defensive default.
 * @param replay_eligibility         Whether offline-queued messages are eligible for replay.
 *                                   Values: `"eligible_for_replay"` | `"stale_session_blocked"` |
 *                                   `"queue_empty"`.
 *                                   V2 MUST use this field to decide whether to expect a replay
 *                                   flush from Android before treating the session as fully
 *                                   recovered.  `null` only as a defensive default.
 *
 * PR-4: Authoritative capability authority fields.
 * These four fields are the stable, first-class projection of the
 * [com.ufo.galaxy.runtime.CapabilityAuthoritySnapshot] that V2 can use for dispatch
 * scoring without parsing opaque maps or cross-referencing multiple sub-fields.
 * @param capability_schema_version  Stable schema version string for V2 drift detection.
 *                                   Matches [com.ufo.galaxy.runtime.CapabilityAuthoritySnapshot.SCHEMA_VERSION].
 *                                   Allows V2 to detect Android-side schema changes across releases.
 * @param local_intelligence_status  Wire value of [com.ufo.galaxy.runtime.LocalIntelligenceCapabilityStatus]
 *                                   at snapshot time.  Values: `"active"` | `"degraded"` | `"disabled"` |
 *                                   `"unavailable"` | `"recovering"`.  This is the canonical single-field
 *                                   signal for local inference availability; V2 does not need to
 *                                   infer it from `warmup_result` or `runtime_health_snapshot`.
 * @param planner_ready              `true` when the planner runtime component is healthy/ready at
 *                                   snapshot time.  Derived from [RuntimeHealthSnapshot.plannerHealth].
 *                                   Distinct from `planner_fallback_tier` (which reflects fallback
 *                                   ladder config, not live health).
 * @param grounding_ready            `true` when the grounding runtime component is healthy/ready at
 *                                   snapshot time.  Derived from [RuntimeHealthSnapshot.groundingHealth].
 *                                   Distinct from `grounding_fallback_tier` (which reflects fallback
 *                                   ladder config, not live health).
 */
data class DeviceStateSnapshotPayload(
    val device_id: String,
    val snapshot_ts: Long = System.currentTimeMillis(),
    // Monotone emission sequence (service-lifecycle scoped) for deterministic ordering on
    // reconnect/replay paths where wall-clock order alone may be ambiguous.
    val snapshot_sequence: Long? = null,

    // Native runtime availability
    val llama_cpp_available: Boolean?,
    val ncnn_available: Boolean?,
    val active_runtime_type: String?,

    // Readiness state
    val model_ready: Boolean?,
    val accessibility_ready: Boolean?,
    val overlay_ready: Boolean?,
    val local_loop_ready: Boolean?,
    val degraded_reasons: List<String> = emptyList(),

    // Model identity
    val model_id: String?,
    val runtime_type: String?,
    val checksum_ok: Boolean?,
    val mobilevlm_present: Boolean?,
    val mobilevlm_checksum_ok: Boolean?,
    val seeclick_present: Boolean?,
    val pending_first_download: Boolean?,

    // Local loop config
    val local_loop_config: Map<String, Any>? = null,

    // Runtime health
    val warmup_result: String?,
    val runtime_health_snapshot: Map<String, Any>? = null,

    // Queue / fallback
    val offline_queue_depth: Int?,
    val current_fallback_tier: String?,
    // Explicit busy/idle projection from Android execution-event state tracking.
    val active_execution_count: Int? = null,
    val execution_busy: Boolean? = null,

    // PR-3: Per-subsystem fallback tier fields accepted by V2 _parse_state_snapshot.
    // Derived from LocalLoopConfig.fallback; null when the config is not yet initialised.
    val planner_fallback_tier: String? = null,
    val grounding_fallback_tier: String? = null,

    // PR-6: Session/invocation identity continuity fields.
    // All four fields are derived from real Android runtime state; null when the backing
    // state is not yet active.  No fake placeholder values are ever set.
    val durable_session_id: String? = null,
    val session_continuity_epoch: Int? = null,
    val runtime_session_id: String? = null,
    val attached_session_id: String? = null,

    // PR-8: Carrier manifestation/presence hints.
    // Both fields are derived exclusively from existing real Android state — no fake
    // placeholder values are emitted when the backing state is unavailable.
    //
    // carrier_foreground_visible: true when the app is currently in the foreground
    //   (RuntimeController.appForegroundVisible); false when backgrounded; null before
    //   the first explicit lifecycle transition is received.  This is the canonical
    //   carrier-visibility hint: when true, Android is an active interactive surface
    //   visible to the user; when false, it is a background runtime carrier.
    //
    // interaction_surface_ready: true when BOTH accessibility_ready AND overlay_ready
    //   are true, meaning Android can display a floating window (overlay) and interact
    //   with the screen (accessibility) — i.e. the full local interaction surface is
    //   operational.  Derived from AppSettings at snapshot time; null when either flag
    //   is null.  Matches the same pre-flight condition checked by
    //   CrossDeviceEnablementError and TakeoverEligibilityAssessor.
    val carrier_foreground_visible: Boolean? = null,
    val interaction_surface_ready: Boolean? = null,

    // Authoritative Android-side mode/governance state signals.
    // mode_state: "local_only" | "cross_device".
    // mode_readiness_state: "ready" | "degraded".
    // *_eligibility: coherent gate outcomes derived from cross_device_enabled and feature flags.
    val mode_state: String? = null,
    val mode_readiness_state: String? = null,
    val cross_device_eligibility: Boolean? = null,
    val goal_execution_eligibility: Boolean? = null,
    val parallel_execution_eligibility: Boolean? = null,
    // PR-1 Android authoritative participation truth.
    // Values:
    //  "local_only" | "control_only" | "cross_device_capable" | "cross_device_enabled" |
    //  "fully_attached" | "dispatch_eligible" | "distributed_participant".
    val authoritative_participation_state: String? = null,
    val participation_tier: String? = null,
    val authoritative_participation_transition_sequence: Long? = null,
    val authoritative_participation_transition_trigger: String? = null,
    val authoritative_participation_transition_history: List<String>? = null,

    // PR-10: Final Android carrier consolidation — cross-cutting carrier state fields.
    //
    // carrier_runtime_state: wire label of RuntimeController.state at snapshot time.
    //   Values: "idle" | "starting" | "active" | "failed" | "local_only".
    //   Allows V2 to distinguish a snapshot emitted from an Active (cross-device) carrier
    //   from one emitted during local-only or startup state.  Null is the safe default
    //   only; in practice this field is always populated at the sendDeviceStateSnapshot()
    //   call site from the live RuntimeController state.
    //
    // reconnect_recovery_state: wire value of RuntimeController.reconnectRecoveryState at
    //   snapshot time.  Values: "idle" | "recovering" | "recovered" | "failed".
    //   Contextualizes snapshots emitted during recovery cycles so V2 can handle them
    //   correctly (e.g. ignore stale snapshots tagged "recovering").  Null is the safe
    //   default only; in practice this field is always populated at emit time.
    val carrier_runtime_state: String? = null,
    val reconnect_recovery_state: String? = null,

    // PR-4: Authoritative capability authority fields.
    // These four fields are the stable, first-class projection for V2 dispatch scoring.
    // They are derived from CapabilityAuthoritySnapshot so the derivation logic lives
    // in one place and is independently testable.
    //
    // capability_schema_version: matches CapabilityAuthoritySnapshot.SCHEMA_VERSION.
    //   Null only when the snapshot builder could not run (defensive default).
    //
    // local_intelligence_status: canonical single-field inference availability signal.
    //   V2 uses this instead of inferring from warmup_result or runtime_health_snapshot.
    //
    // planner_ready / grounding_ready: explicit per-subsystem health booleans.
    //   Distinct from planner_fallback_tier / grounding_fallback_tier (which reflect
    //   FallbackConfig state, not live component health).
    val capability_schema_version: String? = null,
    val local_intelligence_status: String? = null,
    val planner_ready: Boolean? = null,
    val grounding_ready: Boolean? = null,

    // PR-04: Mesh participation runtime closure fields.
    //
    // mesh_participation_lifecycle_state: canonical lifecycle state of Android as a mesh
    //   participant at snapshot time.  Derived from runtime health, rollout gates, and
    //   barrier state via AndroidMeshParticipationRuntimeContract.derive().
    //   Values: "inactive" | "joining" | "active" | "barrier_waiting" | "barrier_released"
    //           | "degraded" | "constrained" | "fallback" | "leaving".
    //   Null only as a defensive default; populated at sendDeviceStateSnapshot().
    //
    // barrier_participation_state: Android's position in the current V2-coordinated barrier
    //   cycle.  Android is not a barrier authority — V2 owns coordination — but Android
    //   reports its barrier response state so V2 can track barrier completion and observability.
    //   Values: "not_applicable" | "waiting" | "released" | "timed_out".
    //   Null only as a defensive default; populated at sendDeviceStateSnapshot().
    //
    // collaboration_lifecycle_state: lifecycle state of the current LocalCollaborationAgent
    //   execution cycle.  Models the progression from subtask assignment through execution
    //   to completion or failure.
    //   Values: "idle" | "subtask_assigned" | "executing" | "completed" | "failed" | "cancelled".
    //   Null only as a defensive default; populated at sendDeviceStateSnapshot().
    //
    // mesh_constrained_reasons: non-empty list of machine-readable reason strings when
    //   mesh participation is degraded, constrained, or using a fallback path.  Each entry
    //   corresponds to a specific runtime condition that limits participation quality.
    //   Empty list when participation is fully healthy and unconstrained.
    //
    // mesh_participation_ready: Android-side boolean readiness projection from
    //   AndroidMeshParticipationRuntimeContract.MeshRuntimeStateReport.isParticipationReady.
    //
    // mesh_runtime_engaged: Android-side execution-engagement projection from
    //   AndroidMeshParticipationRuntimeContract.MeshRuntimeStateReport.isRuntimeEngaged.
    //
    // mesh_runtime_closed: Android-side runtime-closure projection from
    //   AndroidMeshParticipationRuntimeContract.MeshRuntimeStateReport.isRuntimeClosed.
    //
    // mesh_runtime_proof_quality: proof grade string from
    //   AndroidMeshParticipationRuntimeContract.MeshRuntimeStateReport.proofQuality.
    //   Values: "live" | "partial" | "stale" | "missing".
    val mesh_participation_lifecycle_state: String? = null,
    val barrier_participation_state: String? = null,
    val collaboration_lifecycle_state: String? = null,
    val mesh_constrained_reasons: List<String> = emptyList(),
    val mesh_participation_ready: Boolean? = null,
    val mesh_runtime_engaged: Boolean? = null,
    val mesh_runtime_closed: Boolean? = null,
    val mesh_runtime_proof_quality: String? = null,
    // PR-14 Android: explicit mesh direct-runtime truth surface.
    // These fields let V2 and reviewers distinguish direct peer viability from explicit
    // gateway fallback without inferring it from scattered peer/topology hints.
    val mesh_direct_schema_version: String? = null,
    val mesh_direct_state: String? = null,
    val mesh_direct_route: String? = null,
    val mesh_direct_channel_ready: Boolean? = null,
    val mesh_direct_peer_count: Int? = null,
    val mesh_direct_ready_peer_count: Int? = null,
    val mesh_direct_reason_codes: List<String> = emptyList(),
    val mesh_direct_last_attempt_stage: String? = null,
    val mesh_direct_last_attempt_succeeded: Boolean? = null,

    // PR-8Android: Canonical execution mode state from LocalExecutionModeGate.
    //
    // execution_mode_state: the 5-state execution mode from LocalExecutionModeGate.decide().
    //   Values: "inactive" | "local_only" | "cross_device_active" |
    //           "cross_device_degraded" | "transitioning".
    //   V2's android_runtime_transition_reducer.py MUST read this field rather than
    //   inferring mode from combinations of mode_state/mode_readiness_state/carrier fields.
    //   Null only as a defensive default; populated at sendDeviceStateSnapshot().
    //
    // durable_participant_id: stable per-installation UUID for cross-era participant
    //   record correlation in V2's android_device_state_store.py.
    //   Constant across process kills and WS reconnects; resets only on app uninstall.
    //   Null only as a defensive default (first emission before ID is initialised).
    //
    // participant_identity_freshness: Android-originated freshness classification for
    //   the current participant registration.  Values: "fresh" | "recovered" | "stale".
    //   V2's unified_governance_semantics.py MUST read this rather than applying its own
    //   staleness heuristics.  Null only as a defensive default.
    val execution_mode_state: String? = null,
    val durable_participant_id: String? = null,
    val participant_identity_freshness: String? = null,

    // PR-08Android: Canonical runtime truth unification fields.
    //
    // reported_state_semantic_class: the dominant semantic class of this snapshot, derived
    //   from AndroidCanonicalRuntimeTruthContract.classifySnapshot().
    //   Values: "capability" | "observation" | "active_runtime" | "derived_local" |
    //           "terminal_reporting".
    //   V2's canonical truth reducer uses this class to route the snapshot to the correct
    //   truth tier (capability_truth, participant_observation, runtime_state_truth,
    //   local_projection, or terminal_truth).  Null only as a defensive default.
    //
    // degraded_condition_class: the classification of any degraded, fallback, constrained,
    //   partial, delayed, or recovered condition present at snapshot time.  Derived from
    //   AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition().
    //   Values: "nominal" | "degraded" | "fallback" | "constrained" | "partial" |
    //           "delayed" | "recovered".
    //   V2 uses this class to determine dispatch policy adjustments.  Null only as defensive
    //   default; populated at sendDeviceStateSnapshot().
    //
    // local_observation_basis: declares how the fields in this snapshot were derived.
    //   Values: "live_runtime" | "cached_state" | "derived_projection" | "none".
    //   V2 applies staleness heuristics based on this declaration.  Null only as defensive
    //   default; "live_runtime" is the expected nominal value at sendDeviceStateSnapshot().
    val reported_state_semantic_class: String? = null,
    val degraded_condition_class: String? = null,
    val local_observation_basis: String? = null,

    // PR-7B: Evidence presence kind — explicit label for the quality and completeness of
    //   the evidence backing this snapshot publication.
    //   Values: "positive_evidence" | "unknown" | "unavailable" | "delayed" |
    //           "partial" | "failed_observation".
    //   V2 MUST use this field to determine governance policy:
    //     - "positive_evidence": apply standard dispatch and capability scoring.
    //     - "unknown": withhold dispatch; evidence not yet available.
    //     - "unavailable": block dispatch requiring this capability surface.
    //     - "delayed": apply staleness penalty and extend timeout budgets.
    //     - "partial": limit dispatch to confirmed subsystems only.
    //     - "failed_observation": apply recovery policy; MUST NOT assume healthy state.
    //   Null only as a defensive default; populated at sendDeviceStateSnapshot().
    val evidence_presence_kind: String? = null,

     // Unified operational/readiness surface fields.
     //
     // operational_surface_schema_version: version of the Android-side structured operational
     //   surface contract. Populated from
     //   com.ufo.galaxy.runtime.AndroidOperationalStateSurfaceContract.SCHEMA_VERSION.
     //
     // operational_surface_states: compact per-axis state map for cross-repo aggregation.
     //   Keys:
     //     - registration_discoverability
     //     - capability_visibility
     //     - operational_readiness
     //     - active_usable_path
     //     - runtime_host_posture
     //     - degraded_mode
     //     - recovery_repair
     //     - cross_device_participation
     //     - session_continuity
     //     - task_initiation_eligibility
     //     - result_closure
     //     - minimum_access_admission
     //     - operator_visible_control_perception
     //
     // operational_surface_authority: per-axis authority classification. Makes explicit where
     //   Android is locally authoritative versus where V2 retains final admission / closure /
     //   aggregation authority.
     //
     // operational_surface_limitations: machine-readable limitation list describing why Android
     //   still cannot claim full symmetry or final aggregation authority on its own.
     val operational_surface_schema_version: String? = null,
     val operational_surface_states: Map<String, String> = emptyMap(),
     val operational_surface_authority: Map<String, String> = emptyMap(),
     val operational_surface_limitations: List<String> = emptyList(),

     // PR-12: Reconnect/recovery participation fields — real participation semantics for
     //   cross-repository continuity and recovery closure with V2.
    //
    // reconnect_participation_kind: classification of the reconnect/re-attach scenario.
    //   Values: "fresh_attach" | "transport_reconnect" |
    //           "process_recreation_with_context" | "process_recreation_without_context".
    //   V2 MUST use this field to select the correct recovery closure path.
    //   Null only as a defensive default; populated at sendDeviceStateSnapshot().
    //
    // identity_reuse_decision: how Android reuses its durable identity during reconnect.
    //   Values: "reuse_durable_participant" | "reuse_session_only" | "fresh_identity".
    //   V2 MUST use this field to determine whether to look up a prior participant record
    //   or initialise a new one.  Null only as a defensive default.
    //
    // replay_eligibility: whether offline-queued messages are eligible for replay.
    //   Values: "eligible_for_replay" | "stale_session_blocked" | "queue_empty".
    //   V2 MUST use this field to decide whether to expect a replay flush from Android
     //   before treating the session as fully recovered.  Null only as defensive default.
     val reconnect_participation_kind: String? = null,
     val identity_reuse_decision: String? = null,
     val replay_eligibility: String? = null,

    // PR-5 (Android): Runtime observability and problem-solving audit fields.
    //
    // observability_audit_schema_version: version of the Android-side observability audit
    //   contract fields. Populated from
    //   com.ufo.galaxy.runtime.AndroidRuntimeObservabilityAuditContract.SCHEMA_VERSION.
    //
    // execution_path_tag: canonical tag for the execution path active at snapshot time.
    //   Values: "local_path" | "cross_device_path" | "delegated_path" |
    //           "takeover_path" | "degraded_path" | "unknown".
    //   V2's android_device_state_store.py and metrics/android_slo_metrics.py MUST use this
    //   field to distinguish local-only execution from cross-device execution without
    //   combining multiple field heuristics.
    //
    // audit_contribution_class: role of this snapshot in the end-to-end problem-solving
    //   audit trail.
    //   Values: "participation_attestation" | "execution_contribution" |
    //           "execution_outcome" | "interruption_record" | "recovery_contribution" |
    //           "takeover_contribution" | "delegated_contribution" |
    //           "operator_action_outcome" | "informational".
    //   V2's problem_solving_audit_chain.py and board/reliability_surface.py read this
    //   field to determine which audit tier the snapshot belongs to.
    //
    // observability_reliability_class: fidelity of this snapshot for SLO computation.
    //   Values: "high_fidelity" | "reduced_fidelity" | "stale" | "interrupted" | "unknown".
    //   V2's SLO metrics pipeline MUST NOT treat reduced_fidelity/stale/interrupted
    //   signals as confirmed execution data points without applying uncertainty adjustment.
    val observability_audit_schema_version: String? = null,
    val execution_path_tag: String? = null,
    val audit_contribution_class: String? = null,
    val observability_reliability_class: String? = null,

    // ── 统一真相上行合约：快照级统一真相字段（AndroidUnifiedTruthUplinkContract）──────────────
    //
    // unified_truth_schema_version: 本合约字段组的 schema 版本。
    //   取自 AndroidUnifiedTruthUplinkContract.SCHEMA_VERSION。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在 sendDeviceStateSnapshot() 时填充。
    //
    // dispatch_eligible: Android 在快照时是否处于 dispatch_eligible 或 distributed_participant
    //   参与层级。V2 可通过此单一布尔字段替代解析 participation_tier 字符串。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在 sendDeviceStateSnapshot() 时填充。
    //
    // distributed_participant: Android 在快照时是否处于分布式参与者层级。
    //   当 participation_tier 为 "distributed_participant" 时为 true。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在 sendDeviceStateSnapshot() 时填充。
    //
    // session_attached: Android 在快照时是否已附加运行时会话。
    //   当 participation_tier 不为 "pre_attach" 时为 true。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在 sendDeviceStateSnapshot() 时填充。
    //
    // local_mode_active: Android 在快照时是否处于本地模式（execution_mode_state = "local_only"）。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在 sendDeviceStateSnapshot() 时填充。
    //
    // runtime_constrained: Android 在快照时是否处于运行时受限状态。
    //   对应 AndroidUnifiedTruthUplinkContract.ConstraintSemantics 中的 isConstraint=true 状态。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在 sendDeviceStateSnapshot() 时填充。
    //
    // runtime_deferred: Android 在快照时是否处于运行时延迟状态。
    //   对应 AndroidUnifiedTruthUplinkContract.ConstraintSemantics 中的 isDeferred=true 状态。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在 sendDeviceStateSnapshot() 时填充。
    //
    // constraint_semantics: 统一约束语义 wire 值，取自
    //   AndroidUnifiedTruthUplinkContract.ConstraintSemantics.wireValue。
    //   V2 MUST 读取此字段而非通过字段组合推断约束状态。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在 sendDeviceStateSnapshot() 时填充。
    //
    // governance_state: Android 当前治理状态。
    //   值来自 AndroidUnifiedTruthUplinkContract.GovernanceState：
    //   "local_autonomous" | "v2_governed" | "delegated_execution" | "governance_blocked"。
    //
    // governance_blocked: Android 当前是否被治理层显式阻塞（如 operator isolate / suspend）。
    //
    // delegated_execution_active: Android 在快照时是否存在活跃委托执行。
    //
    // takeover_state: Android 当前接管生命周期状态。
    //   值来自 AndroidUnifiedTruthUplinkContract.TakeoverState：
    //   "inactive" | "pending" | "active"。
    //
    // local_llm_ready: Android 本地 LLM 在快照时是否就绪（模型已加载、权重已验证）。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在 sendDeviceStateSnapshot() 时填充。
    //
    // local_mode_capable: Android 在快照时是否具备完整本地模式运行能力。
    //   对应 AndroidUnifiedTruthUplinkContract.LocalCapabilityState.isLocalModeCapable。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在 sendDeviceStateSnapshot() 时填充。
    //
    // local_capability_state: 统一本地能力状态 wire 值，取自
    //   AndroidUnifiedTruthUplinkContract.LocalCapabilityState.wireValue。
    //   V2 MUST 读取此字段而非通过 local_intelligence_status / model_ready 等组合推断。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在 sendDeviceStateSnapshot() 时填充。
    val unified_truth_schema_version: String? = null,
    val dispatch_eligible: Boolean? = null,
    val distributed_participant: Boolean? = null,
    val session_attached: Boolean? = null,
    val local_mode_active: Boolean? = null,
    val runtime_constrained: Boolean? = null,
    val runtime_deferred: Boolean? = null,
    val constraint_semantics: String? = null,
    val governance_state: String? = null,
    val governance_blocked: Boolean? = null,
    val delegated_execution_active: Boolean? = null,
    val takeover_state: String? = null,
    val local_llm_ready: Boolean? = null,
    val local_mode_capable: Boolean? = null,
    val local_capability_state: String? = null,

    // ── 统一参与者生命周期阶段（AndroidUnifiedParticipantLifecyclePhase）─────────────────────
    //
    // unified_lifecycle_phase: Android 参与者在共享中心—分布式生命周期模型中的精确位置。
    //   取自 AndroidUnifiedParticipantLifecyclePhase.derive() 的推导结果。
    //   Values:
    //     "unregistered"     — 跨设备未启用或未注册；无参与可能
    //     "registered"       — 跨设备已启用且持有参与者身份，但 WS 未连接
    //     "connected"        — WS 已连接，能力通告尚未完成
    //     "visible"          — 能力已通告，V2 可见；会话附加或就绪可能尚未满足
    //     "ready"            — 运行时健康、会话已附加、就绪满足；可接受委托工作
    //     "takeover_eligible"— READY 且无活跃执行；接管门控放行
    //     "participating"    — 活跃分布式执行或接管正在运行
    //     "degraded"         — 运行时降级或治理阻断；仅基础能力
    //     "recovering"       — WS 重连中或运行时恢复中；分发阻断
    //     "unavailable"      — 运行时不可用或已停止；无执行可能
    //   V2 MUST 直接读取此字段作为 Android 生命周期阶段的权威来源，
    //   而非通过 participation_tier / carrier_runtime_state 等字段组合自行推导。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在 sendDeviceStateSnapshot() 时填充。
    //
    // unified_lifecycle_schema_version: 本字段组 schema 版本。
    //   取自 AndroidUnifiedParticipantLifecyclePhase.SCHEMA_VERSION。
    val unified_lifecycle_phase: String? = null,
    val unified_lifecycle_schema_version: String? = null,

    // ── PR-3Android: 参与语义规范化字段（AndroidParticipationSemanticNormalizationContract）──────
    //
    // participation_mode_class: Android 当前参与模式的统一语义分类 wire 值。
    //   取自 AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.wireValue。
    //   Values:
    //     "local_only_idle"        — 本地模式，无活跃执行
    //     "local_only_executing"   — 本地模式，任务执行中
    //     "cross_device_ready"     — 跨设备模式，等待调度（V2 可派发新任务）
    //     "distributed_executing"  — 跨设备模式，委托执行中（分布式主链活跃节点）
    //     "takeover_executing"     — 接管执行中
    //     "degraded"               — 运行时降级，参与受限
    //     "constrained"            — 受约束，不可分发
    //     "unavailable"            — 未参与，无执行可能
    //   V2 MUST 直接读取此字段替代跨域字段组合推断（distributed_participant +
    //   delegated_execution_active + local_mode_active 组合推断是语义漂移来源）。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在 sendDeviceStateSnapshot() 时填充。
    //
    // local_execution_active: Android 当前是否存在活跃的本地执行（无论路径）。
    //   = (local_execution_activity_kind != "none")。
    //   V2 可用此单一布尔字段替代解析 execution_busy + distributed_participant 的组合。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在 sendDeviceStateSnapshot() 时填充。
    //
    // local_execution_activity_kind: 本地执行活跃的语义种类 wire 值。
    //   取自 AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.wireValue。
    //   Values:
    //     "none"                  — 无活跃本地执行
    //     "local_assistive"       — 本地辅助执行（非分布式路径）
    //     "delegated_participant" — 委托执行参与（V2 委托的分布式主链执行）
    //     "takeover_participant"  — 接管执行（V2 接管路径）
    //   V2 MUST 读取此字段以区分本地辅助执行与委托/接管执行，
    //   不得仅凭 execution_busy = true 将所有执行活动归入分布式参与统计。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在 sendDeviceStateSnapshot() 时填充。
    //
    // participation_semantic_schema_version: 本字段组 schema 版本。
    //   取自 AndroidParticipationSemanticNormalizationContract.SCHEMA_VERSION。
    val participation_mode_class: String? = null,
    val local_execution_active: Boolean? = null,
    val local_execution_activity_kind: String? = null,
    val participation_semantic_schema_version: String? = null,

    // ── PR-4Android: 工程边界可靠性字段（AndroidBoundaryReliabilityContract）─────────────────────
    //
    // source_field_coverage_class: 本快照中 V2 路由和审计所需来源字段的完整性等级。
    //   取自 AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.wireValue。
    //   Values:
    //     "complete" — device_id + runtime_session_id 均存在，V2 可高置信度关联此快照
    //     "partial"  — device_id 存在但 runtime_session_id 缺失，V2 路由可行但关联能力降低
    //     "absent"   — device_id 缺失，V2 无法路由此快照（MUST 视为异常）
    //   V2 SHOULD 过滤 absent 等级的快照并记录诊断，
    //   MUST 对 partial 等级的快照降低 android_device_state_store.py 的关联置信度。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendDeviceStateSnapshot() 在发送时填充。
    //
    // boundary_reliability_schema_version: 本字段组 schema 版本。
    //   取自 AndroidBoundaryReliabilityContract.SCHEMA_VERSION。
    val source_field_coverage_class: String? = null,
    val boundary_reliability_schema_version: String? = null,

    // ── PR-02v2 (Android): 跨设备 dispatch 边界收束字段（AndroidCrossDeviceDispatchBoundaryContract）──
    //
    // dispatch_boundary_class: Android 当前消费的 cross-device dispatch 边界类型分类。
    //   与 V2 PR-02v2 的 dispatch_path 词汇直接对应，消除 Android ↔ V2 dispatch 叙事漂移。
    //   Values:
    //     "canonical_cross_device"        — 主链 canonical cross-device dispatch 路径（V2: canonical_dispatch）
    //     "controlled_canonical_fallback" — AgentRuntimeBridge 重试耗尽后的受控本地回退（V2: canonical_fallback）
    //     "compat_fallback"               — 遗留消息映射或 AgentBridge compat 入口路径（V2: compat_fallback）
    //     "legacy_bypass"                 — 绕过 canonical 链的遗留绕过路径（V2: legacy_bypass）
    //     "not_cross_device"              — Android 处于本地模式，无 cross-device dispatch 边界
    //   V2 core/device_router.py MUST 读取此字段与自身 dispatch_path 字段对齐，
    //   而非通过 execution_path_tag / participation_tier 组合推断。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendDeviceStateSnapshot() 在发送时填充。
    //
    // dispatch_path_consumption_kind: Android 在当前 dispatch 边界中的消费角色。
    //   Values:
    //     "inbound_execution"        — 主链入站执行消费（V2 派发的 task_assign/goal_execution/takeover）
    //     "local_fallback_execution" — 本地回退消费（canonical 路径失败后的本地执行）
    //     "none"                     — 无活跃 dispatch 消费
    //   V2 core/cross_device_coordinator.py MUST 读取此字段识别 Android 在 dispatch 链中的角色。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendDeviceStateSnapshot() 在发送时填充。
    //
    // dispatch_boundary_schema_version: 本字段组 schema 版本。
    //   取自 AndroidCrossDeviceDispatchBoundaryContract.SCHEMA_VERSION。
    val dispatch_boundary_class: String? = null,
    val dispatch_path_consumption_kind: String? = null,
    val dispatch_boundary_schema_version: String? = null,
    val ingress_boundary_class: String? = null,
    val ingress_consumption_kind: String? = null,
    val ingress_signal_class: String? = null,
    val ingress_schema_version: String? = null,

    // ── PR-05v2 (Android): 结果上行闭环边界字段（AndroidResultUplinkBoundaryContract）────────────
    //
    // result_signal_class: 快照发送时本信号的结果信号语义分类。
    //   device_state_snapshot 始终为 "diagnostics_informational"（参与状态快照，非任务结果）。
    //   V2 core/android_device_state_store.py MUST 对此分类仅存储而不触发任务关闭。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendDeviceStateSnapshot() 在发送时填充。
    //
    // acceptance_candidate_class: 快照发送时本信号的验收候选资格分类。
    //   device_state_snapshot 始终为 "closure_not_applicable"（无任务结果语义）。
    //   V2 MUST NOT 对快照信号执行任何验收或任务关闭操作。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendDeviceStateSnapshot() 在发送时填充。
    //
    // result_uplink_boundary_schema_version: 本字段组 schema 版本。
    //   取自 AndroidResultUplinkBoundaryContract.SCHEMA_VERSION。
    val result_signal_class: String? = null,
    val acceptance_candidate_class: String? = null,
    val result_uplink_boundary_schema_version: String? = null,
    val uplink_semantic_boundary_class: String? = null,
    val operator_projection_class: String? = null,
    val diagnostics_failure_explanation_schema_version: String? = null,
    val authority_runtime_completion_signal_class: String? = null,
    val result_completion_signal_class: String? = null,
    val closure_finalization_signal_class: String? = null,
    val operator_done_projection_class: String? = null,
    val completion_closure_uplink_schema_version: String? = null,
    val schema_version: String? = null,
    val completion_closure_contract_version: String? = null,
    val completion_emission_id: String? = null,
    val idempotency_key: String? = null,
    val local_execution_completed: Boolean? = null,
    val advisory_evidence_sent: Boolean? = null,
    val v2_uplink_acknowledged: Boolean? = null,
    val v2_reconciliation_acknowledged: Boolean? = null,
    val v2_canonical_truth_completed: Boolean? = null,
    val v2_mature_closure_achieved: Boolean? = null,
    val outward_truth_surface_class: String? = null,
    val is_v2_confirmed_canonical_truth: Boolean? = null,

    // ── PR-08v2 (Android): 分布式运行参与边界收束字段 ────────────────────────────────────────
    //
    // participation_boundary_role: 本快照发送时 Android 的参与边界角色分类。
    //   由 AndroidDistributedRuntimeParticipationBoundaryContract.derive() 推导。
    //   V2 MUST read this field to determine Android's current distributed runtime role;
    //   MUST NOT re-derive from field combinations (distributed_participant + execution_mode_state).
    //   device_state_snapshot 为诊断性信号，始终为 "diagnostics_summary_only"。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendDeviceStateSnapshot() 在发送时填充。
    //
    // ownership_posture_class: 本快照发送时 Android 的 ownership posture 语义分类。
    //   V2 MAY 用于 dispatch routing；MUST NOT 用于 authority closure 或 takeover 终局判断。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendDeviceStateSnapshot() 在发送时填充。
    //
    // remote_local_mode_class: 本快照发送时 Android 的 remote/local 执行模式分类。
    //   区分主动声明的本地模式（local_only_declared）与 fallback 转入（fallback_local / degraded_fallback）。
    //   V2 MUST 区分这两种状态：主动声明表示策略性选择，不是能力故障。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.sendDeviceStateSnapshot() 在发送时填充。
    //
    // participation_boundary_schema_version: 本字段组 schema 版本。
    //   取自 AndroidDistributedRuntimeParticipationBoundaryContract.SCHEMA_VERSION。
    val participation_boundary_role: String? = null,
    val ownership_posture_class: String? = null,
    val remote_local_mode_class: String? = null,
    val participation_boundary_schema_version: String? = null,
    // PR-09Android: distributed truth / ownership uplink boundary fields for snapshot/reporting
    // carriers. device_state_snapshot remains ordinary reporting / diagnostics, but now declares
    // that explicitly instead of forcing V2 to infer it from mixed fields.
    val authority_signal_class: String? = null,
    val ownership_uplink_class: String? = null,
    val session_continuity_class: String? = null,
    val device_posture_signal_class: String? = null,
    val distributed_truth_ownership_uplink_schema_version: String? = null,

    // ── PR-116: Android unified continuity recovery state fields ─────────────────────────
    //
    // continuity_recovery_state: unified Android-side recovery phase wire value.
    //   Values from AndroidContinuityRecoveryStateModel.RecoveryPhase.wireValue:
    //     "resumed-cleanly"          — no interrupted state; clean start
    //     "recovering"               — WS reconnect in progress
    //     "recovered-inflight"       — prior in-flight task recovered locally (advisory)
    //     "lost-inflight"            — prior in-flight task was dropped; V2 must reconcile
    //     "requires-reconciliation"  — durable artifact found; V2 must resolve truth
    //     "stale-recovery-artifact"  — artifact belongs to old session; MUST NOT be used
    //     "recovery-failed"          — all reconnect attempts exhausted
    //   V2 MUST treat this as advisory Android-local evidence, not canonical final truth.
    //   Populated at sendDeviceStateSnapshot(); null only as defensive default.
    //
    // continuity_recovery_source: label of the Android code path that produced the phase.
    //   Echoes InflightContinuityRecoverySnapshot.source or a reconnect-path label.
    //   Populated at sendDeviceStateSnapshot(); null only as defensive default.
    //
    // continuity_recovery_schema_version: schema version of the recovery state contract.
    //   Taken from AndroidContinuityRecoveryStateModel.SCHEMA_VERSION.
    val continuity_recovery_state: String? = null,
    val continuity_recovery_source: String? = null,
    val continuity_recovery_schema_version: String? = null,
    // ── PR-119: explicit recovery routing contract for canonical V2 handling ───────────────
    //
    // recovery_state_v2_routing_category: stable V2 routing category for the current
    //   continuity_recovery_state. V2 MUST consume this explicitly instead of treating
    //   Android recovery evidence as generic metadata.
    //
    // recovery_state_routing_requires_v2_action: "true" when V2 must trigger canonical
    //   handling (reconciliation, stale rejection, closure review, reconnect verdict).
    //
    // recovery_state_routing_is_advisory_only: "true" when the recovery evidence is
    //   Android-local advisory evidence only and MUST NOT be treated as canonical truth.
    //
    // recovery_state_routing_canonical_closure_blocked: "true" when V2 MUST NOT derive
    //   canonical closure directly from this recovery evidence.
    //
    // recovery_state_routing_schema_version: schema version of the routing contract.
    //   Taken from AndroidCrossRepoRecoveryStateRoutingContract.SCHEMA_VERSION.
    val recovery_state_v2_routing_category: String? = null,
    val recovery_state_routing_requires_v2_action: String? = null,
    val recovery_state_routing_is_advisory_only: String? = null,
    val recovery_state_routing_canonical_closure_blocked: String? = null,
    val recovery_state_routing_schema_version: String? = null,
    val uplink_lineage_schema_version: String? = null,
    val uplink_lineage_execution_id: String? = null,
    val uplink_lineage_emission_id: String? = null,
    val uplink_lineage_dedupe_key: String? = null,
    val uplink_lineage_recovery_basis: String? = null,
    // ── PR-120 (Android): Non-closure signal boundary classification ────────────
    //
    // non_closure_signal_class: Explicit non-closure classification for device state snapshots.
    //   Always "diagnostics_only" (AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.DIAGNOSTICS_ONLY).
    //   Complements result_signal_class="diagnostics_informational" and acceptance_candidate_class="closure_not_applicable".
    //   V2 MUST store snapshots for observability only and MUST NOT derive canonical task closure.
    val non_closure_signal_class: String? = null,
    val non_closure_schema_version: String? = null
)

// ── PR-2 (Android): Device execution-event uplink payload ────────────────────────────────

/**
 * Uplink payload for [MsgType.DEVICE_EXECUTION_EVENT] (PR-2 Android companion).
 *
 * Carries a single structured execution-phase event emitted by Android during a delegated
 * or local execution flow.  V2 absorbs this payload via
 * `core.android_device_state_store.absorb_device_execution_event()`, which stores and
 * forwards it to the FlowLevelOperatorSurface so the V2 operator plane can observe live
 * Android-side execution state.
 *
 * Field names use snake_case to match the V2 `_parse_execution_event` primary keys
 * (V2 also accepts camelCase aliases `flowId`, `taskId`, `stepIndex`, `isBlocking`,
 * `blockingReason`, `stagnationDetected`, `fallbackTier`).
 *
 * ## Phase values
 *
 * The [phase] field maps to the canonical Android execution lifecycle:
 *
 * | Phase string              | When emitted                                               |
 * |---------------------------|------------------------------------------------------------|
 * | `"execution_started"`     | Immediately before the execution pipeline is invoked.      |
 * | `"execution_progress"`    | After each successfully executed step.                     |
 * | `"completed"`             | After a successful terminal result (status=success).       |
 * | `"failed"`                | After a failure terminal result (status=error/timeout).    |
 * | `"stagnation_detected"`   | When the loop terminates due to stagnation.                |
 * | `"cancelled"`             | When the task is cancelled.                                |
 * | `"fallback_transition"`   | When a delegated path falls back to local execution.       |
 * | `"takeover_milestone"`    | When a takeover execution lifecycle event is emitted.      |
 *
 * @param flow_id              Delegated flow identifier (= task_id for most paths).
 * @param task_id              Originating task identifier.
 * @param phase                Execution phase string; one of the phase values above.
 * @param step_index           Zero-based index of the current step, or -1 if unknown /
 *                             not applicable (e.g. for start / terminal events).
 * @param is_blocking          `true` when this event represents a blocking or terminal
 *                             failure condition that prevents further execution.
 * @param blocking_reason      Human-readable reason for blocking; empty string when
 *                             [is_blocking] is `false`.
 * @param stagnation_detected  `true` when the terminal event is caused by stagnation.
 * @param fallback_tier        Active fallback tier at the time of emission, or `null`
 *                             when not applicable.
 * @param device_id            Android device identifier.
 * @param event_id             UUID idempotency key for this specific emission.
 * @param source_component     Name of the Android component that produced this event
 *                             (e.g. `"GalaxyConnectionService"`, `"LoopController"`).
 * @param timestamp_ms         Epoch-ms production timestamp.
 * @param durable_session_id   Durable session era identifier from
 *                             [com.ufo.galaxy.runtime.DurableSessionContinuityRecord.durableSessionId],
 *                             stable across WS reconnects.  `null` when no durable era is active.
 * @param session_continuity_epoch Reconnect counter within the durable era from
 *                             [com.ufo.galaxy.runtime.DurableSessionContinuityRecord.sessionContinuityEpoch].
 *                             `null` when [durable_session_id] is `null`.
 * @param runtime_session_id   Per-app-launch runtime session UUID; allows V2 to correlate
 *                             events to a specific WS connection cycle.  `null` when not yet
 *                             initialised.
 * @param attached_session_id  Attached runtime session UUID from
 *                             [com.ufo.galaxy.runtime.AttachedRuntimeSession.sessionId];
 *                             stable within one attach event.  `null` when no session is attached.
 * @param carrier_runtime_state Wire label of [com.ufo.galaxy.runtime.RuntimeController.state]
 *                             at event emission time.  Values: `"idle"` | `"starting"` |
 *                             `"active"` | `"failed"` | `"local_only"`.  Provides V2 with the
 *                             carrier's cross-device participation mode at the precise moment an
 *                             execution event is emitted, enabling coherent event correlation.
 *                             `null` only as a defensive default (PR-10).
 *
 * ## PR-3 schema alignment note
 *
 * [event_ts] is a V2-compatible seconds-epoch timestamp derived from [timestamp_ms].
 * V2's `_parse_execution_event` extracts `event_ts` / `eventTs` / `timestamp` (seconds)
 * to populate `DeviceExecutionEvent.event_ts`; it does **not** read `timestamp_ms`.
 * Adding [event_ts] as a backed field closes this gap without removing [timestamp_ms],
 * which remains the canonical Android-side timestamp field.
 *
 * @see MsgType.DEVICE_EXECUTION_EVENT
 */
data class DeviceExecutionEventPayload(
    val flow_id: String,
    val task_id: String,
    val phase: String,
    val step_index: Int = -1,
    val is_blocking: Boolean = false,
    val blocking_reason: String = "",
    val stagnation_detected: Boolean = false,
    val fallback_tier: String? = null,
    // Monotone execution-event sequence (service-lifecycle scoped) for deterministic event
    // ordering on reconnect/replay paths where wall-clock timestamps can collide.
    val execution_event_sequence: Long? = null,
    // Explicit busy/idle projection after this phase transition is applied.
    val active_execution_count: Int? = null,
    val execution_busy: Boolean? = null,
    val device_id: String = "",
    val event_id: String = java.util.UUID.randomUUID().toString(),
    val source_component: String = "",
    val timestamp_ms: Long = System.currentTimeMillis(),
    // PR-6: Session/invocation identity continuity fields.
    // All four fields are sourced from real Android runtime state; null when the backing
    // state is not yet active.  No fake placeholder values are ever set.
    val durable_session_id: String? = null,
    val session_continuity_epoch: Int? = null,
    val runtime_session_id: String? = null,
    val attached_session_id: String? = null,

    // PR-8: Carrier manifestation/presence hints — same semantics as
    // DeviceStateSnapshotPayload; sourced from real Android state at emission time.
    // carrier_foreground_visible: RuntimeController.appForegroundVisible at event time.
    // interaction_surface_ready: accessibility_ready && overlay_ready at event time.
    // null when the backing state is unavailable; no fake values emitted.
    val carrier_foreground_visible: Boolean? = null,
    val interaction_surface_ready: Boolean? = null,

    // Authoritative Android-side mode/governance state signals.
    // mode_state: "local_only" | "cross_device".
    // mode_readiness_state: "ready" | "degraded".
    // *_eligibility: coherent gate outcomes derived from cross_device_enabled and feature flags.
    val mode_state: String? = null,
    val mode_readiness_state: String? = null,
    val cross_device_eligibility: Boolean? = null,
    val goal_execution_eligibility: Boolean? = null,
    val parallel_execution_eligibility: Boolean? = null,
    // PR-1 Android authoritative participation truth.
    // Values:
    //  "local_only" | "control_only" | "cross_device_capable" | "cross_device_enabled" |
    //  "fully_attached" | "dispatch_eligible" | "distributed_participant".
    val authoritative_participation_state: String? = null,
    val participation_tier: String? = null,
    val authoritative_participation_transition_sequence: Long? = null,
    val authoritative_participation_transition_trigger: String? = null,
    val authoritative_participation_transition_history: List<String>? = null,

    // PR-10: Final carrier consolidation — runtime state included in every execution event
    // so V2 can correlate event emission with the carrier's cross-device participation mode.
    // carrier_runtime_state: RuntimeController.state.wireLabel at event time.
    // Values: "idle" | "starting" | "active" | "failed" | "local_only".
    // null only as a defensive default; should never be null at a real emission point.
    val carrier_runtime_state: String? = null,

    // PR-8Android: Canonical execution mode state from LocalExecutionModeGate.decide().
    // Values: "inactive" | "local_only" | "cross_device_active" |
    //         "cross_device_degraded" | "transitioning".
    // Emitted alongside each execution event so V2 can correlate the event with the
    // precise execution mode at the time of emission.  Null only as defensive default.
    val execution_mode_state: String? = null,

    // Governance truth fields from AndroidUnifiedTruthUplinkContract.
    // governance_state: "local_autonomous" | "v2_governed" |
    //   "delegated_execution" | "governance_blocked".
    // governance_blocked: explicit governance-layer block flag.
    // delegated_execution_active: true when a delegated unit is actively executing.
    // takeover_state: "inactive" | "pending" | "active".
    val governance_state: String? = null,
    val governance_blocked: Boolean? = null,
    val delegated_execution_active: Boolean? = null,
    val takeover_state: String? = null,

    // PR-08Android: Canonical runtime truth unification fields for execution events.
    //
    // reported_state_semantic_class: semantic class of the state this event reports.
    //   Values: "capability" | "observation" | "active_runtime" | "derived_local" |
    //           "terminal_reporting".
    //   Allows V2 to route the event to the correct truth tier without field inspection.
    //   Null only as defensive default; populated at event-emission call sites.
    //
    // result_uplink_semantic_class: semantic class of any result uplink this event carries.
    //   Values: "authoritative_terminal" | "authoritative_interruption" |
    //           "authoritative_recovery" | "informational".
    //   V2 uses this class to determine what truth-maintenance action to apply:
    //     - authoritative_terminal: close execution, apply success/failure/rejected policy.
    //     - authoritative_interruption: mark interrupted; apply retry/fallback policy.
    //     - authoritative_recovery: close interruption era; resume normal policy.
    //     - informational: record progress; no terminal action.
    //   Null only as defensive default; populated at event-emission call sites.
    //
    // terminal_outcome_kind: deterministic local terminal observation class for this event.
    //   Values: "completion" | "partial_completion" | "failure" | "interruption" |
    //           "abort" | "timeout" | "fallback" | "recovery" | "non_terminal".
    //   This is Android's local observed terminal taxonomy (not the center-facing reducer class).
    //   V2 can compare this with result_uplink_semantic_class to distinguish local observation
    //   from reported authoritative semantics.
    //
    // execution_lifecycle_phase / previous_execution_lifecycle_phase:
    //   Canonical lifecycle phase projection from AndroidExecutionLifecycleContract.
    //   V2 can use these fields as protocol-level truth instead of inferring lifecycle from
    //   ad-hoc event phase strings.
    //
    // lifecycle_transition_valid:
    //   True when previous_execution_lifecycle_phase -> execution_lifecycle_phase exists in
    //   AndroidExecutionLifecycleContract.TRANSITION_TABLE. Null when no prior phase is known.
    //
    // lifecycle_result_uplink_required / lifecycle_state_uplink_required:
    //   Canonical requirement projection from ExecutionUplinkDiscipline for the reported
    //   execution_lifecycle_phase.
    //
    // lifecycle_terminal_phase:
    //   True when execution_lifecycle_phase is terminal under AndroidExecutionLifecycleContract.
    val reported_state_semantic_class: String? = null,
    val result_uplink_semantic_class: String? = null,
    val terminal_outcome_kind: String? = null,
    val execution_lifecycle_phase: String? = null,
    val previous_execution_lifecycle_phase: String? = null,
    val lifecycle_transition_valid: Boolean? = null,
    val lifecycle_result_uplink_required: Boolean? = null,
    val lifecycle_state_uplink_required: Boolean? = null,
    val lifecycle_terminal_phase: Boolean? = null,
    // PR-3Android counterpart: explicit completion/closure visibility semantics for
    // execution-event consumption in the shared source-of-truth pipeline.
    // These three booleans mirror ReconciliationSignal payload semantics:
    //   - result_returned: Android has reached a terminal result phase for this task.
    //   - completion_signaled: Android has emitted a completion-side execution signal.
    //   - closure_ready_for_acceptance: this event is terminal and can enter acceptance closure.
    // Null only as a defensive default; GalaxyConnectionService fills these at event emission.
    val result_returned: Boolean? = null,
    val completion_signaled: Boolean? = null,
    val closure_ready_for_acceptance: Boolean? = null,

    // PR-7B: Evidence presence kind — explicit label for the quality and completeness of
    //   the evidence backing this execution event publication.
    //   Values: "positive_evidence" | "unknown" | "unavailable" | "delayed" |
    //           "partial" | "failed_observation".
    //   V2 MUST use this field to determine governance policy for each execution event:
    //     - "positive_evidence": apply standard dispatch and capability scoring.
    //     - "unknown": do not treat as confirmed state; await further evidence.
    //     - "unavailable": resource was explicitly inaccessible; do not retry without
    //       a subsequent positive-evidence publication confirming availability.
    //     - "delayed": event was queued; apply staleness and timeout budget extensions.
    //     - "partial": primary path unavailable; limit to confirmed fallback subsystems.
    //     - "failed_observation": runtime or stagnation failure observed; apply recovery policy.
    //   Null only as a defensive default; populated at event-emission call sites.
    val evidence_presence_kind: String? = null,
    // Wire value of AndroidCanonicalRuntimeTruthContract.LocalObservationBasis for this event.
    // Values: "live_runtime" | "cached_state" | "derived_projection" | "none".
    // Makes event-side projection/freshness basis explicit for V2 reliability reducers.
    val local_observation_basis: String? = null,

    // PR-5 (Android): Runtime observability and problem-solving audit fields for execution
    // events. Same semantics as the corresponding DeviceStateSnapshotPayload fields; see
    // AndroidRuntimeObservabilityAuditContract for full classification rules.
    //
    // execution_path_tag: canonical tag for the execution path taken for this event.
    //   Values: "local_path" | "cross_device_path" | "delegated_path" |
    //           "takeover_path" | "degraded_path" | "unknown".
    //
    // audit_contribution_class: role of this event in the problem-solving audit trail.
    //   Values: "participation_attestation" | "execution_contribution" |
    //           "execution_outcome" | "interruption_record" | "recovery_contribution" |
    //           "takeover_contribution" | "delegated_contribution" |
    //           "operator_action_outcome" | "informational".
    //
    // observability_reliability_class: fidelity of this event for SLO computation.
    //   Values: "high_fidelity" | "reduced_fidelity" | "stale" | "interrupted" | "unknown".
    //
    // observability_audit_schema_version: version of the observability audit contract.
    val observability_audit_schema_version: String? = null,
    val execution_path_tag: String? = null,
    val audit_contribution_class: String? = null,
    val observability_reliability_class: String? = null,

    // ── 统一参与者生命周期阶段（AndroidUnifiedParticipantLifecyclePhase）─────────────────────
    //
    // unified_lifecycle_phase: 与 DeviceStateSnapshotPayload 语义相同；此处记录事件发射时
    //   的精确生命周期阶段，便于 V2 将执行事件与当时的参与者阶段关联。
    //   Values 同 DeviceStateSnapshotPayload.unified_lifecycle_phase。
    //   Null 仅作为防御性默认值；GalaxyConnectionService 在事件发射时填充。
    val unified_lifecycle_phase: String? = null,
    val unified_lifecycle_schema_version: String? = null,

    // ── PR-3Android: 参与语义规范化字段（AndroidParticipationSemanticNormalizationContract）──────
    //
    // 与 DeviceStateSnapshotPayload 语义相同；此处在执行事件中携带事件发射时刻的规范化参与模式，
    // 使 V2 无需跨消息重建上下文即可将执行事件关联到精确的参与语义状态。
    //
    // participation_mode_class: 执行事件发射时 Android 的统一参与模式分类 wire 值。
    //   V2 可直接用于执行事件路由，区分本地执行与分布式执行路径，
    //   无需组合 distributed_participant + delegated_execution_active + local_mode_active。
    //
    // local_execution_active: 执行事件发射时是否存在活跃的本地执行。
    //   对于执行事件，此字段通常为 true；Null 仅作为防御性默认值。
    //
    // local_execution_activity_kind: 执行事件发射时本地执行活跃的语义种类 wire 值。
    //   V2 MUST 读取此字段以区分本地辅助执行与委托/接管执行，
    //   确保执行事件进入正确的审计链（problem_solving_audit_chain.py）。
    //
    // participation_semantic_schema_version: 本字段组 schema 版本。
    val participation_mode_class: String? = null,
    val local_execution_active: Boolean? = null,
    val local_execution_activity_kind: String? = null,
    val participation_semantic_schema_version: String? = null,

    // ── PR-4Android: 工程边界可靠性字段（AndroidBoundaryReliabilityContract）─────────────────────
    //
    // async_scope_class: 发送此执行事件的协程的生命周期绑定类型。
    //   取自 AndroidBoundaryReliabilityContract.AsyncScopeClass.wireValue。
    //   Values:
    //     "service_scoped"           — 在 serviceScope（SupervisorJob）内发送（标准路径）
    //     "timeout_guarded"          — 在 withTimeout/withTimeoutOrNull 块内发送（时间有界）
    //     "lifecycle_bound"          — 在 UI 组件生命周期范围内发送（Activity/ViewModel 等）
    //     "detached_fire_and_forget" — 无生命周期绑定，脱管即发即弃（高风险路径）
    //   V2 MUST 对 detached_fire_and_forget 事件应用更宽松的延迟容忍（INV-BR-01）。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.deviceExecutionEventSink 在发射时填充。
    //
    // source_field_coverage_class: 本执行事件中 V2 路由和审计所需来源字段的完整性等级。
    //   取自 AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.wireValue。
    //   Values: "complete" | "partial" | "absent"。
    //   V2 MUST 拒绝 absent 等级的执行事件进入权威状态更新路径（INV-BR-02）。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.deviceExecutionEventSink 在发射时填充。
    //
    // authority_boundary_check_mode: 发送此执行事件前 Android 侧权限检查的显式程度。
    //   取自 AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.wireValue。
    //   Values:
    //     "explicit_contract_gate" — 通过形式化 Android 治理合约门控（最高级别）
    //     "governance_validated"   — 通过治理评估器验证（标准委托执行路径）
    //     "audit_trail_only"       — 仅记录审计追踪，未进行门控
    //     "assumed_implicit"       — 依赖隐式上下文假设（最低级别）
    //   V2 SHOULD 对 governance_validated 及以上的事件赋予更高的审计置信度（INV-BR-03）。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.deviceExecutionEventSink 在发射时填充。
    //
    // boundary_reliability_schema_version: 本字段组 schema 版本。
    //   取自 AndroidBoundaryReliabilityContract.SCHEMA_VERSION。
    //
    // ── PR-14Android: mesh direct-runtime truth fields ──────────────────────────────────────────
    // 与 DeviceStateSnapshotPayload 同语义，但表示执行事件发射时刻 Android 对 mesh direct
    // peer path 的判断与最近一次 direct send 尝试结果。
    val mesh_direct_schema_version: String? = null,
    val mesh_direct_state: String? = null,
    val mesh_direct_route: String? = null,
    val mesh_direct_channel_ready: Boolean? = null,
    val mesh_direct_peer_count: Int? = null,
    val mesh_direct_ready_peer_count: Int? = null,
    val mesh_direct_reason_codes: List<String> = emptyList(),
    val mesh_direct_last_attempt_stage: String? = null,
    val mesh_direct_last_attempt_succeeded: Boolean? = null,
    val async_scope_class: String? = null,
    val source_field_coverage_class: String? = null,
    val authority_boundary_check_mode: String? = null,
    val boundary_reliability_schema_version: String? = null,

    // ── PR-02v2 (Android): 跨设备 dispatch 边界收束字段（AndroidCrossDeviceDispatchBoundaryContract）──
    //
    // dispatch_boundary_class: 执行事件发射时 Android 消费的 cross-device dispatch 边界类型。
    //   与 V2 PR-02v2 的 dispatch_path 词汇直接对应，使 V2 可将执行事件关联到正确的 dispatch 边界。
    //   Values:
    //     "canonical_cross_device"        — 主链 canonical cross-device dispatch 路径
    //     "controlled_canonical_fallback" — AgentRuntimeBridge 受控本地回退
    //     "compat_fallback"               — 遗留消息映射或 AgentBridge compat 入口路径
    //     "legacy_bypass"                 — 遗留绕过路径
    //     "not_cross_device"              — Android 处于本地模式
    //   V2 core/device_router.py 和 core/cross_device_coordinator.py MUST 读取此字段
    //   与自身 dispatch_path 字段对齐，而非通过组合推断。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.deviceExecutionEventSink 在发射时填充。
    //
    // dispatch_path_consumption_kind: 执行事件发射时 Android 在 dispatch 边界中的消费角色。
    //   Values: "inbound_execution" | "local_fallback_execution" | "none"。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.deviceExecutionEventSink 在发射时填充。
    //
    // dispatch_boundary_schema_version: 本字段组 schema 版本。
    //   取自 AndroidCrossDeviceDispatchBoundaryContract.SCHEMA_VERSION。
    val dispatch_boundary_class: String? = null,
    val dispatch_path_consumption_kind: String? = null,
    val dispatch_boundary_schema_version: String? = null,
    val ingress_boundary_class: String? = null,
    val ingress_consumption_kind: String? = null,
    val ingress_signal_class: String? = null,
    val ingress_schema_version: String? = null,

    // ── PR-05v2 (Android): 结果上行闭环边界字段（AndroidResultUplinkBoundaryContract）────────────
    //
    // result_signal_class: 执行事件发射时本信号的结果信号语义分类。
    //   使 V2 无需字段组合推断即可将执行事件路由至正确的消费路径。
    //   Values: "authority_result" | "acceptance_closure_signal" | "diagnostics_informational"
    //   V2 core/task_result_canonical_truth_chain.py MUST 读取此字段，
    //   仅对 "authority_result" 执行任务关闭逻辑。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.deviceExecutionEventSink 在发射时填充。
    //
    // acceptance_candidate_class: 执行事件发射时本信号的验收候选资格分类。
    //   Values: "eligible_for_acceptance" | "pending_result_return" | "acceptance_blocked" | "closure_not_applicable"
    //   V2 core/acceptance_adjudication.py MUST 读取此字段，
    //   仅对 "eligible_for_acceptance" 执行 acceptance closure。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.deviceExecutionEventSink 在发射时填充。
    //
    // result_uplink_boundary_schema_version: 本字段组 schema 版本。
    //   取自 AndroidResultUplinkBoundaryContract.SCHEMA_VERSION。
    val result_signal_class: String? = null,
    val acceptance_candidate_class: String? = null,
    val result_uplink_boundary_schema_version: String? = null,
    val uplink_semantic_boundary_class: String? = null,
    val operator_projection_class: String? = null,
    val diagnostics_failure_explanation_schema_version: String? = null,
    val authority_runtime_completion_signal_class: String? = null,
    val result_completion_signal_class: String? = null,
    val closure_finalization_signal_class: String? = null,
    val operator_done_projection_class: String? = null,
    val completion_closure_uplink_schema_version: String? = null,
    val local_execution_completed: Boolean? = null,
    val advisory_evidence_sent: Boolean? = null,
    val v2_uplink_acknowledged: Boolean? = null,
    val v2_reconciliation_acknowledged: Boolean? = null,
    val v2_canonical_truth_completed: Boolean? = null,
    val v2_mature_closure_achieved: Boolean? = null,
    val outward_truth_surface_class: String? = null,

    // ── PR-08v2 (Android): 分布式运行参与边界收束字段（在执行事件发射层填充）───────────────────────
    //
    // participation_boundary_role: 执行事件发射时 Android 的参与边界角色分类。
    //   由 AndroidDistributedRuntimeParticipationBoundaryContract.derive() 推导。
    //   V2 MUST read this field to route execution events to the correct distributed runtime chain.
    //   Null 仅作为防御性默认值；GalaxyConnectionService.deviceExecutionEventSink 在发射时填充。
    //
    // ownership_posture_class: 执行事件发射时 Android 的 ownership posture 语义分类。
    //   V2 MAY 用于 dispatch routing；MUST NOT 用于 authority closure 或 takeover 终局判断。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.deviceExecutionEventSink 在发射时填充。
    //
    // remote_local_mode_class: 执行事件发射时 Android 的 remote/local 执行模式分类。
    //   区分主动声明的本地模式（local_only_declared）与 fallback 转入（fallback_local / degraded_fallback）。
    //   Null 仅作为防御性默认值；GalaxyConnectionService.deviceExecutionEventSink 在发射时填充。
    //
    // participation_boundary_schema_version: 本字段组 schema 版本。
    //   取自 AndroidDistributedRuntimeParticipationBoundaryContract.SCHEMA_VERSION。
    val participation_boundary_role: String? = null,
    val ownership_posture_class: String? = null,
    val remote_local_mode_class: String? = null,
    val participation_boundary_schema_version: String? = null,
    // PR-09Android: distributed truth / ownership uplink boundary fields for execution events.
    // These let V2 distinguish transport/reporting flow from canonical result truth and
    // handoff/ownership governance without re-deriving semantics from event phase names.
    val authority_signal_class: String? = null,
    val ownership_uplink_class: String? = null,
    val session_continuity_class: String? = null,
    val device_posture_signal_class: String? = null,
    val distributed_truth_ownership_uplink_schema_version: String? = null,
    val uplink_lineage_schema_version: String? = null,
    val uplink_lineage_execution_id: String? = null,
    val uplink_lineage_emission_id: String? = null,
    val uplink_lineage_dedupe_key: String? = null,
    val uplink_lineage_recovery_basis: String? = null
) {
    /**
     * PR-3: V2-compatible event timestamp in seconds since epoch.
     *
     * V2's `_parse_execution_event` reads `event_ts` / `eventTs` / `timestamp` (seconds)
     * to populate `DeviceExecutionEvent.event_ts`.  Android's canonical timestamp field
     * [timestamp_ms] is in milliseconds and is not currently read by V2's `event_ts`
     * extractor — this backed field closes that gap.
     *
     * Computed automatically from [timestamp_ms]; no caller action required.
     */
    val event_ts: Double = timestamp_ms / 1000.0

    companion object {
        /** Phase value: execution pipeline invoked; awaiting first step. */
        const val PHASE_EXECUTION_STARTED = "execution_started"
        /** Phase value: a single step completed successfully. */
        const val PHASE_EXECUTION_PROGRESS = "execution_progress"
        /** Phase value: terminal success — all steps completed. */
        const val PHASE_COMPLETED = "completed"
        /** Phase value: terminal failure — error / timeout / max_steps. */
        const val PHASE_FAILED = "failed"
        /** Phase value: terminal — stagnation guard triggered. */
        const val PHASE_STAGNATION_DETECTED = "stagnation_detected"
        /** Phase value: terminal — task cancelled. */
        const val PHASE_CANCELLED = "cancelled"
        /** Phase value: delegated path fell back to local execution. */
        const val PHASE_FALLBACK_TRANSITION = "fallback_transition"
        /** Phase value: takeover lifecycle milestone. */
        const val PHASE_TAKEOVER_MILESTONE = "takeover_milestone"
    }
}

// ── PR-7 (Android): Unified perception / vision emission payload ───────────────────────────

/**
 * Structured Android-side vision request contract nested inside [DevicePerceptionEmissionPayload].
 *
 * This block carries the actual perception prompt semantics independently from whether the
 * surrounding emission is a one-shot vision request or a multimodal participation signal.
 */
data class DeviceVisionPayload(
    val contract_kind: String = "android_vision",
    val prompt_text: String? = null,
    val request_reason: String? = null,
    val screenshot_ref: String? = null,
    val image_width: Int? = null,
    val image_height: Int? = null
)

/**
 * Structured Android-side grounding contract nested inside [DevicePerceptionEmissionPayload].
 */
data class DeviceGroundingPayload(
    val contract_kind: String = "android_grounding",
    val intent: String,
    val input_width: Int,
    val input_height: Int,
    val result_x: Int? = null,
    val result_y: Int? = null,
    val confidence: Float? = null,
    val element_description: String? = null,
    val error: String? = null
)

/**
 * Structured Android-side local-perception contract nested inside [DevicePerceptionEmissionPayload].
 */
data class DeviceLocalPerceptionPayload(
    val contract_kind: String = "android_local_perception",
    val screen_width: Int? = null,
    val screen_height: Int? = null,
    val capture_present: Boolean = false,
    val planner_participated: Boolean = false,
    val grounding_participated: Boolean = false
)

/**
 * Uplink payload for [MsgType.DEVICE_PERCEPTION_EMISSION].
 *
 * Formalises Android's perception emission semantics for V2 multimodal ingestion by:
 *  1. Separating **one-shot vision requests** from **multimodal participation signals**
 *     via [emission_kind], [carrier_semantics], and [participation_semantics].
 *  2. Unifying screenshot / vision / grounding / local-perception data into one stable
 *     contract using [screenshot], [vision_payload], [grounding_payload], and
 *     [local_perception_payload].
 *  3. Carrying trace, carrier, and session metadata on every emission so V2 can correlate
 *     Android perception with the main multimodal chain.
 */
data class DevicePerceptionEmissionPayload(
    val flow_id: String,
    val task_id: String,
    val emission_kind: String,
    val perception_stage: String,
    val carrier_semantics: String,
    val participation_semantics: String,
    val participates_in_multimodal_main_chain: Boolean,
    val step_index: Int = -1,
    val screenshot: Snapshot? = null,
    val vision_payload: DeviceVisionPayload? = null,
    val grounding_payload: DeviceGroundingPayload? = null,
    val local_perception_payload: DeviceLocalPerceptionPayload? = null,
    val trace_id: String? = null,
    val dispatch_trace_id: String? = null,
    val device_id: String = "",
    val emission_id: String = java.util.UUID.randomUUID().toString(),
    val source_component: String = "",
    val timestamp_ms: Long = System.currentTimeMillis(),
    val durable_session_id: String? = null,
    val session_continuity_epoch: Int? = null,
    val runtime_session_id: String? = null,
    val attached_session_id: String? = null,
    val carrier_foreground_visible: Boolean? = null,
    val interaction_surface_ready: Boolean? = null,
    val mode_state: String? = null,
    val mode_readiness_state: String? = null,
    val cross_device_eligibility: Boolean? = null,
    val goal_execution_eligibility: Boolean? = null,
    val parallel_execution_eligibility: Boolean? = null,
    val carrier_runtime_state: String? = null,
    val canonical_ingress_protocol: String? = null,
    val canonical_ingress_target: String? = null,
    val downstream_consumption_semantics: String? = null,
    val canonical_main_chain_eligible: Boolean? = null
) {
    /** V2-compatible seconds-epoch timestamp derived from [timestamp_ms]. */
    val event_ts: Double = timestamp_ms / 1000.0

    companion object {
        const val EMISSION_KIND_ONE_SHOT_VISION_REQUEST = "one_shot_vision_request"
        const val EMISSION_KIND_MULTIMODAL_PARTICIPATION_SIGNAL =
            "multimodal_participation_signal"

        const val STAGE_PLANNING = "planning"
        const val STAGE_GROUNDING = "grounding"

        const val CARRIER_SEMANTICS_VISION_PROBE = "vision_probe"
        const val CARRIER_SEMANTICS_MULTIMODAL_MAIN_CHAIN = "multimodal_main_chain"

        const val PARTICIPATION_SEMANTICS_ONE_SHOT_REQUEST = "one_shot_request"
        const val PARTICIPATION_SEMANTICS_MAIN_CHAIN_INPUT = "main_chain_input"

        const val CANONICAL_INGRESS_PROTOCOL_ANDROID_PERCEPTION = "android_perception_ingress_contract"
        const val CANONICAL_INGRESS_TARGET_V2_PERCEPTION_FRAME = "android_bridge_perception_frame_ingress"
        const val DOWNSTREAM_CONSUMPTION_ROUTE_CONTEXT_PLANNING = "route_context_planning"
        const val DOWNSTREAM_CONSUMPTION_VISION_PROBE_ONLY = "vision_probe_only"
    }

    fun toCanonicalIngressPayload(): DevicePerceptionEmissionPayload {
        val hasGroundingIntent = !grounding_payload?.intent.isNullOrBlank()
        val hasVisionPrompt = !vision_payload?.prompt_text.isNullOrBlank()
        val hasPerceptionContext = local_perception_payload?.capture_present == true || hasVisionPrompt
        val eligibleForMainChain =
            participates_in_multimodal_main_chain &&
                participation_semantics == PARTICIPATION_SEMANTICS_MAIN_CHAIN_INPUT &&
                carrier_semantics == CARRIER_SEMANTICS_MULTIMODAL_MAIN_CHAIN &&
                hasGroundingIntent &&
                hasPerceptionContext

        return copy(
            emission_kind = if (eligibleForMainChain) {
                EMISSION_KIND_MULTIMODAL_PARTICIPATION_SIGNAL
            } else {
                EMISSION_KIND_ONE_SHOT_VISION_REQUEST
            },
            perception_stage = if (eligibleForMainChain) STAGE_GROUNDING else STAGE_PLANNING,
            carrier_semantics = if (eligibleForMainChain) {
                CARRIER_SEMANTICS_MULTIMODAL_MAIN_CHAIN
            } else {
                CARRIER_SEMANTICS_VISION_PROBE
            },
            participation_semantics = if (eligibleForMainChain) {
                PARTICIPATION_SEMANTICS_MAIN_CHAIN_INPUT
            } else {
                PARTICIPATION_SEMANTICS_ONE_SHOT_REQUEST
            },
            participates_in_multimodal_main_chain = eligibleForMainChain,
            canonical_ingress_protocol = CANONICAL_INGRESS_PROTOCOL_ANDROID_PERCEPTION,
            canonical_ingress_target = CANONICAL_INGRESS_TARGET_V2_PERCEPTION_FRAME,
            downstream_consumption_semantics = if (eligibleForMainChain) {
                DOWNSTREAM_CONSUMPTION_ROUTE_CONTEXT_PLANNING
            } else {
                DOWNSTREAM_CONSUMPTION_VISION_PROBE_ONLY
            },
            canonical_main_chain_eligible = eligibleForMainChain
        )
    }
}

package com.ufo.galaxy.agent

import com.ufo.galaxy.UFOGalaxyApplication

/**
 * Server-contract handoff envelope (H4 – HandoffEnvelopeV2 adapter layer).
 *
 * This data class mirrors the `HandoffEnvelopeV2` structure expected by the
 * `ufo-galaxy-realization-v2` gateway bridge endpoint.  It carries every field
 * required for full-chain task delegation:
 *
 * | Field                    | Server contract role                                                    |
 * |--------------------------|-------------------------------------------------------------------------|
 * | [trace_id]               | End-to-end observability identifier (propagated unchanged)              |
 * | [task_id]                | Unique task identifier; echoed on every reply                           |
 * | [goal]                   | Natural-language objective for Agent Runtime                            |
 * | [exec_mode]              | Execution mode: "local" / "remote" / "both"                             |
 * | [route_mode]             | Routing path: "local" / "cross_device"                                  |
 * | [capability]             | Optional capability name required for execution                         |
 * | [session_id]             | Session-level grouping identifier                                       |
 * | [context]                | Arbitrary key-value context forwarded to Agent Runtime                  |
 * | [constraints]            | Natural-language constraint strings from task_assign                    |
 * | [runtime_session_id]     | Stable per-app-launch session ID (see [UFOGalaxyApplication.runtimeSessionId]) |
 * | [idempotency_key]        | Per-send deduplication key (task_id + timestamp)                        |
 * | [source_runtime_posture] | Canonical source-device participation posture (PR #533 contract):       |
 * |                          | `"control_only"` or `"join_runtime"`. Null = backwards-compat default.  |
 * | [dispatch_intent]        | (PR-D) Optional dispatch intent label from V2 orchestrator. Null for    |
 * |                          | legacy senders; absent from bridge JSON when null.                      |
 * | [dispatch_origin]        | (PR-D) Optional originating orchestrator / device identifier. Null for  |
 * |                          | legacy senders.                                                         |
 * | [orchestration_stage]    | (PR-D) Optional orchestration stage label for multi-stage dispatches.   |
 * |                          | Null for single-stage / legacy dispatches.                              |
 * | [execution_context]      | (PR-D) Optional key-value execution context from V2 orchestrator.       |
 * |                          | Empty map for legacy senders.                                           |
 * | [executor_target_type]   | (PR-E) Optional explicit executor target type from V2's target-typing   |
 * |                          | model. `"android_device"`, `"node_service"`, `"worker"`, `"local"`.     |
 * |                          | Null for legacy/pre-V2 senders.                                         |
 * | [dispatch_plan_id]       | (PR-48) Stable identifier for the V2 source dispatch plan that produced  |
 * |                          | this handoff command; `null` for legacy/pre-V2 senders.                 |
 * | [source_dispatch_strategy] | (PR-48) Strategy hint from the V2 source dispatch orchestrator;        |
 * |                          | recognised values: `"local"`, `"remote_handoff"`, `"fallback_local"`,   |
 * |                          | `"staged_mesh"`. Unknown values MUST be tolerated. `null` for legacy     |
 * |                          | senders.                                                                 |
 * | [continuity_token]       | (PR-F) Opaque stable token identifying the durable execution continuity  |
 * |                          | context across reconnects or handoffs. Null for legacy senders.          |
 * | [recovery_context]       | (PR-F) Key-value map carrying recovery hints from V2. Empty for legacy   |
 * |                          | senders; unknown keys MUST be ignored.                                   |
 * | [is_resumable]           | (PR-F) `true` when V2 considers this a resumable execution; `false`     |
 * |                          | when terminal; null for legacy senders.                                  |
 * | [interruption_reason]    | (PR-F) Reason for the interruption that triggered this resume/recovery   |
 * |                          | dispatch. Null for non-recovery dispatches.                              |
 *
 * ## Backward compatibility
 * The existing [AgentRuntimeBridge.HandoffRequest] / [AgentRuntimeBridge.buildBridgeJson]
 * contract is preserved unchanged.  [HandoffEnvelopeV2] is an **additive** layer: callers
 * can convert a [AgentRuntimeBridge.HandoffRequest] via [AgentRuntimeBridge.HandoffRequest.toEnvelopeV2]
 * and serialise it independently when they need to send a V2-compliant payload.
 *
 * @param trace_id               End-to-end trace identifier.
 * @param task_id                Unique task identifier.
 * @param goal                   Natural-language objective.
 * @param exec_mode              Execution mode constant.
 * @param route_mode             Routing path constant.
 * @param capability             Optional capability name.
 * @param session_id             Optional session identifier.
 * @param context                Arbitrary key-value context map.
 * @param constraints            Constraint string list.
 * @param runtime_session_id     Stable per-app-launch session identifier.
 * @param idempotency_key        Per-send unique deduplication key.
 * @param source_runtime_posture Canonical source-device participation posture; see
 *                               [com.ufo.galaxy.runtime.SourceRuntimePosture].
 * @param dispatch_intent        (PR-D) Optional dispatch intent from V2 orchestrator.
 * @param dispatch_origin        (PR-D) Optional originating orchestrator / device ID.
 * @param orchestration_stage    (PR-D) Optional orchestration stage label.
 * @param execution_context      (PR-D) Optional key-value execution context from V2.
 * @param executor_target_type   (PR-E) Optional explicit executor target type from V2's
 *                               target-typing model. `null` for legacy/pre-V2 senders.
 * @param dispatch_plan_id       (PR-48) Stable V2 dispatch plan identifier; null for
 *                               legacy senders.
 * @param source_dispatch_strategy (PR-48) V2 source dispatch strategy hint; null for
 *                               legacy senders; unknown values MUST be tolerated.
 * @param continuity_token       (PR-F) Opaque stable continuity token; null for legacy senders.
 * @param recovery_context       (PR-F) Optional key-value recovery hints from V2.
 * @param is_resumable           (PR-F) Resumability flag from V2; null for legacy senders.
 * @param interruption_reason    (PR-F) Interruption reason for recovery dispatches; null otherwise.
 */
data class HandoffEnvelopeV2(
    val trace_id: String,
    val task_id: String,
    val goal: String,
    val exec_mode: String,
    val route_mode: String,
    val capability: String? = null,
    val session_id: String? = null,
    val context: Map<String, String> = emptyMap(),
    val constraints: List<String> = emptyList(),
    val runtime_session_id: String? = null,
    val idempotency_key: String? = null,
    val source_runtime_posture: String? = null,
    // ── PR-D: V2 source dispatch metadata (optional; null-safe for legacy callers) ──
    val dispatch_intent: String? = null,
    val dispatch_origin: String? = null,
    val orchestration_stage: String? = null,
    val execution_context: Map<String, String> = emptyMap(),
    // ── PR-E: V2 explicit executor target typing (optional; null-safe for legacy callers) ──
    val executor_target_type: String? = null,
    // ── PR-48: V2 richer dispatch metadata (optional; null-safe for legacy callers) ──
    val dispatch_plan_id: String? = null,
    val source_dispatch_strategy: String? = null,
    // ── PR-F: V2 durable continuity and recovery context (optional; null-safe for legacy callers) ──
    val continuity_token: String? = null,
    val recovery_context: Map<String, String> = emptyMap(),
    val is_resumable: Boolean? = null,
    val interruption_reason: String? = null
)

/**
 * Converts this [AgentRuntimeBridge.HandoffRequest] to a [HandoffEnvelopeV2].
 *
 * - [HandoffEnvelopeV2.runtime_session_id] is populated from
 *   [UFOGalaxyApplication.runtimeSessionId] (stable for the lifetime of the process).
 * - [HandoffEnvelopeV2.idempotency_key] is derived from `task_id + "_" + currentTimeMillis`
 *   to produce a per-send unique key suitable for gateway-side deduplication.
 * - [HandoffEnvelopeV2.source_runtime_posture] is mapped directly from
 *   [AgentRuntimeBridge.HandoffRequest.sourceRuntimePosture].
 * - PR-D fields ([dispatch_intent], [dispatch_origin], [orchestration_stage],
 *   [execution_context]) are mapped 1:1 from the request so that V2 source dispatch
 *   metadata is propagated to the gateway bridge endpoint unchanged.
 * - PR-E field ([executor_target_type]) is mapped 1:1 from the request so that V2
 *   explicit executor target typing metadata is propagated to the gateway bridge endpoint.
 * - PR-48 fields ([dispatch_plan_id], [source_dispatch_strategy]) default to `null` in
 *   outbound handoffs originating on Android; these fields are populated by V2 when it
 *   generates or echoes the envelope on the server side.
 * - PR-F fields ([continuity_token], [recovery_context], [is_resumable],
 *   [interruption_reason]) are mapped 1:1 from the request so that durable continuity
 *   and recovery context is propagated to the gateway bridge endpoint unchanged.
 *
 * All other fields are mapped 1:1 from the source request.
 */
fun AgentRuntimeBridge.HandoffRequest.toEnvelopeV2(): HandoffEnvelopeV2 = HandoffEnvelopeV2(
    trace_id = traceId,
    task_id = taskId,
    goal = goal,
    exec_mode = execMode,
    route_mode = routeMode,
    capability = capability,
    session_id = sessionId,
    context = context,
    constraints = constraints,
    runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
    idempotency_key = java.util.UUID.randomUUID().toString(),
    source_runtime_posture = sourceRuntimePosture,
    dispatch_intent = dispatchIntent,
    dispatch_origin = dispatchOrigin,
    orchestration_stage = orchestrationStage,
    execution_context = executionContext,
    executor_target_type = executorTargetType,
    continuity_token = continuityToken,
    recovery_context = recoveryContext,
    is_resumable = isResumable,
    interruption_reason = interruptionReason
)

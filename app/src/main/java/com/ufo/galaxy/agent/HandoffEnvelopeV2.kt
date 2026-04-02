package com.ufo.galaxy.agent

import com.ufo.galaxy.UFOGalaxyApplication

/**
 * Server-contract handoff envelope (H4 – HandoffEnvelopeV2 adapter layer).
 *
 * This data class mirrors the `HandoffEnvelopeV2` structure expected by the
 * `ufo-galaxy-realization-v2` gateway bridge endpoint.  It carries every field
 * required for full-chain task delegation:
 *
 * | Field                | Server contract role                                         |
 * |----------------------|--------------------------------------------------------------|
 * | [trace_id]           | End-to-end observability identifier (propagated unchanged)   |
 * | [task_id]            | Unique task identifier; echoed on every reply               |
 * | [goal]               | Natural-language objective for Agent Runtime                 |
 * | [exec_mode]          | Execution mode: "local" / "remote" / "both"                  |
 * | [route_mode]         | Routing path: "local" / "cross_device"                      |
 * | [capability]         | Optional capability name required for execution             |
 * | [session_id]         | Session-level grouping identifier                           |
 * | [context]            | Arbitrary key-value context forwarded to Agent Runtime      |
 * | [constraints]        | Natural-language constraint strings from task_assign        |
 * | [runtime_session_id] | Stable per-app-launch session ID (see [UFOGalaxyApplication.runtimeSessionId]) |
 * | [idempotency_key]    | Per-send deduplication key (task_id + timestamp)            |
 *
 * ## Backward compatibility
 * The existing [AgentRuntimeBridge.HandoffRequest] / [AgentRuntimeBridge.buildBridgeJson]
 * contract is preserved unchanged.  [HandoffEnvelopeV2] is an **additive** layer: callers
 * can convert a [AgentRuntimeBridge.HandoffRequest] via [AgentRuntimeBridge.HandoffRequest.toEnvelopeV2]
 * and serialise it independently when they need to send a V2-compliant payload.
 *
 * @param trace_id           End-to-end trace identifier.
 * @param task_id            Unique task identifier.
 * @param goal               Natural-language objective.
 * @param exec_mode          Execution mode constant.
 * @param route_mode         Routing path constant.
 * @param capability         Optional capability name.
 * @param session_id         Optional session identifier.
 * @param context            Arbitrary key-value context map.
 * @param constraints        Constraint string list.
 * @param runtime_session_id Stable per-app-launch session identifier.
 * @param idempotency_key    Per-send unique deduplication key.
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
    val idempotency_key: String? = null
)

/**
 * Converts this [AgentRuntimeBridge.HandoffRequest] to a [HandoffEnvelopeV2].
 *
 * - [HandoffEnvelopeV2.runtime_session_id] is populated from
 *   [UFOGalaxyApplication.runtimeSessionId] (stable for the lifetime of the process).
 * - [HandoffEnvelopeV2.idempotency_key] is derived from `task_id + "_" + currentTimeMillis`
 *   to produce a per-send unique key suitable for gateway-side deduplication.
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
    idempotency_key = java.util.UUID.randomUUID().toString()
)

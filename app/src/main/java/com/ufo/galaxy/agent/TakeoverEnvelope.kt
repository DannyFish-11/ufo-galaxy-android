package com.ufo.galaxy.agent

import com.ufo.galaxy.runtime.SourceRuntimePosture

/**
 * Canonical Android-side envelope for an inbound cross-device takeover request.
 *
 * This data class models the `takeover_request` message sent by the main runtime
 * (PC / OpenClawd) when it wants the Android device to accept and continue executing
 * an in-flight task that was previously running on another device.
 *
 * ## PR-3 role
 * Part of the post-PR-#533 dual-repo runtime host unification track (PR 3):
 * canonicalise the Android ↔ main-runtime handoff/takeover path.  All inbound
 * takeover messages must be modelled through this envelope; older ad-hoc bridge
 * payloads that pre-date this contract should be considered deprecated.
 *
 * ## Field semantics
 * | Field                    | Role                                                                     |
 * |--------------------------|--------------------------------------------------------------------------|
 * | [takeover_id]            | Stable identifier for this specific takeover request (not task_id).      |
 * | [task_id]                | The task being handed over; echoed in [TakeoverResponseEnvelope].        |
 * | [trace_id]               | End-to-end observability identifier propagated from the originating task.|
 * | [goal]                   | Natural-language objective the Android device should continue executing.  |
 * | [source_device_id]       | Identifier of the device that initiated the original task.               |
 * | [source_runtime_posture] | Canonical posture from PR #533: `"control_only"` or `"join_runtime"`.   |
 *                            | Tells Android whether the source is a pure controller or also a runtime. |
 * | [exec_mode]              | Execution mode context: `"local"` / `"remote"` / `"both"`.              |
 * | [route_mode]             | Routing path: always `"cross_device"` for takeover requests.            |
 * | [session_id]             | Session-level grouping identifier from the originating task.             |
 * | [runtime_session_id]     | Stable per-app-launch session ID from the originating device.            |
 * | [context]                | Arbitrary key-value context forwarded from the originating device.       |
 * | [constraints]            | Natural-language constraint strings from the original task_assign.       |
 * | [checkpoint]             | Optional progress checkpoint; describes how far the task has progressed. |
 * | [handoff_reason]         | (PR-9) Why the main-repo is delegating — wire value of                    |
 * |                          | [DelegatedHandoffContract.HandoffReason]; null for legacy senders.       |
 * | [originating_host_id]    | (PR-9) [com.ufo.galaxy.runtime.RuntimeHostDescriptor.hostId] of the     |
 * |                          | dispatching device; null for legacy senders.                             |
 * | [originating_formation_role] | (PR-9) [com.ufo.galaxy.runtime.RuntimeHostDescriptor.FormationRole]  |
 * |                          | wire value of the originating device; null for legacy senders.           |
 * | [required_capability_dimensions] | (PR-9) Comma-separated or list of               |
 * |                          | [com.ufo.galaxy.capability.AndroidCapabilityVector.ExecutionDimension]  |
 * |                          | wire values the delegated task requires the receiver to have.            |
 * | [continuation_token]     | (PR-9) Opaque, stable machine-readable continuation state token           |
 * |                          | produced by the originating executor; more structured than [checkpoint]. |
 *
 * ## Backward compatibility
 * All fields except [takeover_id], [task_id], [trace_id], and [goal] are optional
 * so that the envelope can accept messages from main-runtime versions that pre-date
 * this contract.  [source_runtime_posture] defaults to `null`; consumers must use
 * [SourceRuntimePosture.fromValue] to resolve it to a safe default.  All PR-9 fields
 * default to `null` / empty so that pre-PR-9 senders remain compatible.
 *
 * @param takeover_id                  Unique identifier for this takeover request.
 * @param task_id                      Task identifier being handed over.
 * @param trace_id                     End-to-end trace identifier propagated from the origin.
 * @param goal                         Natural-language objective for the Android executor.
 * @param source_device_id             Identifier of the originating device.
 * @param source_runtime_posture       Canonical source-device participation posture.
 * @param exec_mode                    Execution mode constant.
 * @param route_mode                   Routing path constant; `"cross_device"` for takeovers.
 * @param session_id                   Session-level identifier.
 * @param runtime_session_id           Stable per-app-launch session identifier.
 * @param context                      Arbitrary key-value context from the originating device.
 * @param constraints                  Constraint strings from the original task_assign.
 * @param checkpoint                   Optional progress description from the originating executor.
 * @param handoff_reason               (PR-9) Wire value of [DelegatedHandoffContract.HandoffReason];
 *                                     null from legacy senders.
 * @param originating_host_id          (PR-9) RuntimeHostDescriptor.hostId of the dispatching
 *                                     device; null from legacy senders.
 * @param originating_formation_role   (PR-9) FormationRole wire value of the originating
 *                                     device; null from legacy senders.
 * @param required_capability_dimensions (PR-9) ExecutionDimension wire values the delegated
 *                                     task requires; empty from legacy senders.
 * @param continuation_token           (PR-9) Opaque machine-readable continuation state token;
 *                                     null when not provided by the originating executor.
 */
data class TakeoverRequestEnvelope(
    val takeover_id: String,
    val task_id: String,
    val trace_id: String,
    val goal: String,
    val source_device_id: String? = null,
    val source_runtime_posture: String? = null,
    val exec_mode: String = AgentRuntimeBridge.EXEC_MODE_REMOTE,
    val route_mode: String = AgentRuntimeBridge.ROUTE_MODE_CROSS_DEVICE,
    val session_id: String? = null,
    val runtime_session_id: String? = null,
    val context: Map<String, String> = emptyMap(),
    val constraints: List<String> = emptyList(),
    val checkpoint: String? = null,
    // ── PR-9: Handoff contract fields ─────────────────────────────────────────
    val handoff_reason: String? = null,
    val originating_host_id: String? = null,
    val originating_formation_role: String? = null,
    val required_capability_dimensions: List<String> = emptyList(),
    val continuation_token: String? = null
) {
    /**
     * Returns the resolved [source_runtime_posture] using [SourceRuntimePosture.fromValue].
     *
     * Guarantees that callers always receive a canonical posture string without having to
     * null-check [source_runtime_posture] manually.
     */
    val resolvedPosture: String
        get() = SourceRuntimePosture.fromValue(source_runtime_posture)

    /**
     * Returns `true` when the originating device (source) is a pure control/initiator
     * and should NOT participate as a runtime executor for this task.
     */
    val isSourceControlOnly: Boolean
        get() = SourceRuntimePosture.isControlOnly(source_runtime_posture)

    /**
     * Returns `true` when the originating device is also joining the runtime as an executor.
     */
    val isSourceJoinRuntime: Boolean
        get() = SourceRuntimePosture.isJoinRuntime(source_runtime_posture)
}

/**
 * Canonical Android-side envelope for a cross-device takeover response.
 *
 * Sent uplink (Android → Gateway → main runtime) in reply to a
 * [TakeoverRequestEnvelope].  Carries Android's decision (accept / reject) and
 * the relevant runtime metadata so the main runtime can update its session truth.
 *
 * ## PR-5 additions
 * [runtime_host_id] and [formation_role] are included when [accepted] is `true` so
 * the main runtime can record the accepting device as a first-class runtime host in
 * its session truth, rather than as a generic connected endpoint.
 *
 * ## Field semantics
 * | Field                    | Role                                                                     |
 * |--------------------------|--------------------------------------------------------------------------|
 * | [takeover_id]            | Echoed from [TakeoverRequestEnvelope.takeover_id] for correlation.       |
 * | [task_id]                | Echoed from [TakeoverRequestEnvelope.task_id].                           |
 * | [trace_id]               | Echoed from [TakeoverRequestEnvelope.trace_id] for end-to-end tracing.   |
 * | [accepted]               | `true` = Android accepts the task; `false` = Android rejects it.         |
 * | [rejection_reason]       | Human-readable reason when [accepted] is `false`.                        |
 * | [device_id]              | Identifier of the Android device sending this response.                  |
 * | [runtime_session_id]     | Stable per-app-launch session ID of the Android device.                  |
 * | [source_runtime_posture] | Posture echoed from [TakeoverRequestEnvelope]; preserved for correlation. |
 * | [exec_mode]              | Execution mode Android will use if [accepted] is `true`.                 |
 * | [runtime_host_id]        | (PR-5) Stable host-instance UUID of the accepting device; non-null when  |
 * |                          | [accepted] is `true` and a [RuntimeHostDescriptor] is available.         |
 * | [formation_role]         | (PR-5) [FormationRole.wireValue] of the accepting device; non-null when  |
 * |                          | [accepted] is `true` and a [RuntimeHostDescriptor] is available.         |
 *
 * @param takeover_id            Echoed from [TakeoverRequestEnvelope.takeover_id].
 * @param task_id                Echoed from [TakeoverRequestEnvelope.task_id].
 * @param trace_id               Echoed trace identifier.
 * @param accepted               Whether Android accepted the takeover.
 * @param rejection_reason       Reason for rejection; null when [accepted] is `true`.
 * @param device_id              Identifier of the responding Android device.
 * @param runtime_session_id     Stable Android session identifier.
 * @param source_runtime_posture Echoed from the request for correlation.
 * @param exec_mode              Execution mode the Android device will use.
 * @param runtime_host_id        (PR-5) Stable runtime-host UUID; non-null on acceptance.
 * @param formation_role         (PR-5) Formation role wire value; non-null on acceptance.
 */
data class TakeoverResponseEnvelope(
    val takeover_id: String,
    val task_id: String,
    val trace_id: String,
    val accepted: Boolean,
    val rejection_reason: String? = null,
    val device_id: String? = null,
    val runtime_session_id: String? = null,
    val source_runtime_posture: String? = null,
    val exec_mode: String = AgentRuntimeBridge.EXEC_MODE_REMOTE,
    val runtime_host_id: String? = null,
    val formation_role: String? = null
)

/**
 * Outcome of an Android-side takeover request handling.
 *
 * Produced by [GalaxyConnectionService] internal takeover handling to communicate
 * the decision and supporting metadata to callers.
 *
 * @param takeoverId  Echoed from [TakeoverRequestEnvelope.takeover_id].
 * @param taskId      Echoed from [TakeoverRequestEnvelope.task_id].
 * @param traceId     Echoed trace identifier.
 * @param accepted    Whether Android accepted the takeover.
 * @param reason      Human-readable outcome or rejection reason.
 */
data class TakeoverHandlingResult(
    val takeoverId: String,
    val taskId: String,
    val traceId: String,
    val accepted: Boolean,
    val reason: String
)

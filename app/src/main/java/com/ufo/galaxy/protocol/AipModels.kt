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
     *  @status pr36-promoted — [PeerAnnouncePayload] parsed; per-session joined-peer record
     *  retained; structured ACK sent. Promoted from minimal-compat (logged only) in PR-36. */
    PEER_ANNOUNCE("peer_announce"),
    /** Downlink/Uplink: peer capability exchange between devices.
     *  @status pr35-promoted — [PeerExchangePayload] parsed; capability record retained per peer;
     *  structured ACK sent. Promoted from minimal-compat (logged only) in PR-35. */
    PEER_EXCHANGE("peer_exchange"),
    /** Downlink: gateway pushes a mesh topology update (node list / adjacency).
     *  @status pr35-promoted — [MeshTopologyPayload] parsed; topology snapshot retained;
     *  structured ACK sent. Promoted from minimal-compat (logged only) in PR-35. */
    MESH_TOPOLOGY("mesh_topology"),

    /** Downlink: gateway sends a wake event to resume an idle or suspended device.
     *  @status minimal-compat — logged; ack sent; no suspend/resume state machine yet. */
    WAKE_EVENT("wake_event"),
    /** Downlink: gateway requests session state migration to another device.
     *  @status minimal-compat — logged; degrade/reject reply sent; full migration TODO. */
    SESSION_MIGRATE("session_migrate"),

    /** Downlink/Uplink: coordination synchronisation tick between coordinator and participants.
     *  @status pr35-promoted — sequence-aware [CoordSyncAckPayload] response with per-session
     *  tick counter. Promoted from minimal-compat (generic ACK only) in PR-35. */
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
     *  @status pr15-complete — full delegated takeover executor path closed:
     *  eligibility gate ([com.ufo.galaxy.agent.TakeoverEligibilityAssessor]),
     *  session gate ([com.ufo.galaxy.agent.DelegatedRuntimeReceiver]),
     *  canonical executor ([com.ufo.galaxy.agent.DelegatedTakeoverExecutor]) emitting
     *  ACK / PROGRESS / RESULT signals with stable identity and [com.ufo.galaxy.runtime.EmittedSignalLedger];
     *  structured rejection for all ineligible paths; timeout/cancellation/failure outcomes
     *  distinguished and forwarded to [com.ufo.galaxy.runtime.RuntimeController.notifyTakeoverFailed]. */
    TAKEOVER_REQUEST("takeover_request"),

    /** Uplink: Android responds to a [TAKEOVER_REQUEST] with acceptance or rejection.
     *  Payload model: [com.ufo.galaxy.agent.TakeoverResponseEnvelope].
     *  @status pr15-complete — accepted/rejected response sent with structured rejection_reason,
     *  runtime_host_id, and formation_role; send path present via GalaxyConnectionService. */
    TAKEOVER_RESPONSE("takeover_response"),

    // ── PR-16: Delegated execution signal outbound transport ──────────────────────────────
    // Uplink signal emitted by Android during and after delegated task execution.
    // Carries ACK / PROGRESS / RESULT / TIMEOUT / CANCELLED lifecycle events with full
    // identity continuity so the main-repo tracker can reconcile state without ambiguity.

    /** Uplink: Android emits a delegated-execution lifecycle signal to the main runtime.
     *  Carries ACK / PROGRESS / RESULT (COMPLETED / FAILED / TIMEOUT / CANCELLED) events.
     *  Payload model: [DelegatedExecutionSignalPayload].
     *  @status pr16 — payload defined; send path present via GalaxyConnectionService. */
    DELEGATED_EXECUTION_SIGNAL("delegated_execution_signal"),

    // ── PR-H: HandoffEnvelopeV2 native Android consumption ───────────────────────────────
    // Downlink: V2 sends a HandoffEnvelopeV2 to this device, requesting native consumption
    // of the enclosed task via the full Android execution chain (parse → execute → ack/result).
    // HANDOFF_ENVELOPE_V2_RESULT is the uplink reply carrying the ACK and execution outcome.

    /** Downlink: V2 sends a [com.ufo.galaxy.agent.HandoffEnvelopeV2] envelope to Android
     *  for native consumption.  Android parses the envelope, maps it to an execution payload,
     *  executes the goal via the canonical execution chain, and sends back a
     *  [HANDOFF_ENVELOPE_V2_RESULT] uplink with the ACK / result / failure state.
     *  Payload model: [com.ufo.galaxy.agent.HandoffEnvelopeV2].
     *  @status pr-h — promoted; dedicated stateful handler in GalaxyConnectionService. */
    HANDOFF_ENVELOPE_V2("handoff_envelope_v2"),

    /** Uplink: Android reports the result of consuming a [HANDOFF_ENVELOPE_V2].
     *  Carries ACK / status / result_summary / error for end-to-end V2 correlation.
     *  Payload model: [HandoffEnvelopeV2ResultPayload].
     *  @status pr-h — payload defined; send path present via GalaxyConnectionService. */
    HANDOFF_ENVELOPE_V2_RESULT("handoff_envelope_v2_result"),

    // ── PR-06: Reconciliation signal uplink ────────────────────────────────────────────────
    // Uplink signal emitted by Android RuntimeController for all reconciliation lifecycle
    // events. Carries task and participant state changes from Android to V2 so V2's
    // participant-truth reconciliation loop can close against Android's canonical local truth.

    /** Uplink: Android emits a reconciliation signal to V2.
     *  Carries TASK_RESULT / TASK_CANCELLED / TASK_FAILED / PARTICIPANT_STATE /
     *  RUNTIME_TRUTH_SNAPSHOT (and all other [com.ufo.galaxy.runtime.ReconciliationSignal.Kind])
     *  events from [com.ufo.galaxy.runtime.RuntimeController.reconciliationSignals].
     *  Payload model: [ReconciliationSignalPayload].
     *  @status pr-06 — payload defined; send path present via GalaxyConnectionService. */
    RECONCILIATION_SIGNAL("reconciliation_signal"),

    // ── Android-side signal closure: readiness artifact uplink ───────────────────────────────
    // Uplink message that carries the structured [com.ufo.galaxy.runtime.DeviceReadinessArtifact]
    // and per-dimension [com.ufo.galaxy.runtime.DelegatedRuntimeReadinessSnapshot] produced by
    // [com.ufo.galaxy.runtime.DelegatedRuntimeReadinessEvaluator].
    // Enables V2 readiness-gate and governance paths to absorb Android-side readiness conclusions
    // without polling, closing the evaluator → V2 visibility gap.

    /** Uplink: Android reports its delegated-runtime readiness artifact and per-dimension
     *  snapshot to V2.  Emitted after capability_report is sent and after each relevant
     *  dimension-state change so V2 release-gate and governance paths have a reliable
     *  Android-side readiness signal.
     *  Payload model: [DeviceReadinessReportPayload].
     *  @status android-closure — payload defined; send path present via GalaxyConnectionService. */
    DEVICE_READINESS_REPORT("device_readiness_report"),

    // ── PR-4 (Android): Governance / acceptance / strategy artifact uplinks ───────────────────
    // Uplink messages that carry the structured evaluator artifacts and per-dimension snapshots
    // produced by the Android post-graduation governance, acceptance, and strategy evaluators.
    // Enables V2 governance / graduation / program-strategy paths to absorb Android-side
    // evaluator conclusions without polling, closing the evaluator → V2 visibility gap for
    // all four evaluator layers.

    /** Uplink: Android reports its post-graduation governance artifact and per-dimension snapshot
     *  to V2.  Emitted on service start (baseline with all dimensions UNKNOWN) and after each
     *  relevant dimension observation-state change so V2 post-graduation governance and
     *  enforcement paths have a reliable Android-side governance signal.
     *  Payload model: [DeviceGovernanceReportPayload].
     *  @status pr-4-android — payload defined; send path present via GalaxyConnectionService. */
    DEVICE_GOVERNANCE_REPORT("device_governance_report"),

    /** Uplink: Android reports its delegated-runtime acceptance artifact and per-dimension
     *  snapshot to V2.  Emitted on service start (baseline with all dimensions UNKNOWN) and
     *  after each relevant dimension evidence-state change so V2 final acceptance / graduation
     *  gate paths have a reliable Android-side acceptance signal.
     *  Payload model: [DeviceAcceptanceReportPayload].
     *  @status pr-4-android — payload defined; send path present via GalaxyConnectionService. */
    DEVICE_ACCEPTANCE_REPORT("device_acceptance_report"),

    /** Uplink: Android reports its program strategy / evolution posture artifact and per-dimension
     *  snapshot to V2.  Emitted on service start (baseline with all dimensions UNKNOWN) and
     *  after each relevant dimension posture-state change so V2 program strategy / evolution
     *  control paths have a reliable Android-side strategy signal.
     *  Payload model: [DeviceStrategyReportPayload].
     *  @status pr-4-android — payload defined; send path present via GalaxyConnectionService. */
    DEVICE_STRATEGY_REPORT("device_strategy_report"),

    // ── PR-68 (Android): Delegated runtime audit evidence uplink ─────────────────────────────
    // Uplink message that carries the structured Android delegated runtime audit evidence and
    // per-dimension audit snapshot produced by
    // [com.ufo.galaxy.runtime.AndroidDelegatedRuntimeAudit].
    // Enables V2 system_final_acceptance_verdict to resolve the android_participant dimension
    // by ingesting a structured, cross-repo-consumable evidence payload that classifies
    // participant registration, availability, execution readiness, health state, capability
    // honesty, and evidence freshness.

    /** Uplink: Android reports its delegated runtime audit evidence and per-dimension audit
     *  snapshot to V2.  Emitted on service start (baseline with all dimensions UNKNOWN) and
     *  after each relevant dimension audit-state change so V2 acceptance, readiness, and
     *  governance paths have a reliable, structured Android-side runtime audit signal.
     *  Payload model: [DeviceAuditReportPayload].
     *  @status pr-68-android — payload defined; wire map consumable by V2 evidence ingestion. */
    DEVICE_AUDIT_REPORT("device_audit_report"),

    // ── PR-RT: Android runtime-state snapshot uplink ──────────────────────────────────────────
    // Uplink message that carries a complete structured snapshot of the Android device runtime
    // state emitted on the canonical Android→V2 control-plane WebSocket path.
    //
    // V2 side: absorbed by galaxy_gateway/android/handlers/device_state_snapshot.py into
    // core.android_device_state_store, and surfaced at:
    //   GET /api/v1/operator/devices/ecosystem
    //   GET /api/v1/operator/devices/ecosystem/{device_id}

    /** Uplink: Android emits a complete runtime-state snapshot to V2.
     *  Carries: native-runtime availability (llama.cpp / NCNN), model readiness, model
     *  identity, accessibility / overlay readiness, local-loop readiness, offline-queue depth,
     *  fallback tier, warmup result, and runtime health.
     *  Emitted after device registration, after reconnect/recovery, and on readiness changes.
     *  Payload model: [DeviceStateSnapshotPayload].
     *  V2 absorbed by: core.android_device_state_store.absorb_device_state_snapshot().
     *  @status pr-rt — payload defined; send path wired in GalaxyConnectionService. */
    DEVICE_STATE_SNAPSHOT("device_state_snapshot"),

    // ── PR-2 (Android): Execution-event uplink ────────────────────────────────────────────
    // Uplink message emitted by Android during and after delegated flow execution.
    // Carries per-step and phase-transition execution events (planning, grounding, execution,
    // stagnation, fallback, completed, failed) so V2 FlowLevelOperatorSurface can observe
    // live cross-device execution state and project it through the operator/flow surfaces.
    //
    // V2 side: absorbed by core.android_device_state_store.absorb_execution_event() and
    // forwarded to core.flow_level_operator_surface via _forward_execution_event_to_flow_surface.

    /** Uplink: Android emits a structured execution-phase event to V2 during delegated execution.
     *  Carries flow_id, task_id, phase, step_index, is_blocking, blocking_reason,
     *  stagnation_detected, and fallback_tier.  Emitted at real execution lifecycle points
     *  (execution_started, execution_progress, completed, failed, stagnation_detected,
     *  fallback_transition, takeover_milestone) so V2 can track live flow state.
     *  Payload model: [DeviceExecutionEventPayload].
     *  V2 absorbed by: core.android_device_state_store.absorb_device_execution_event().
     *  @status pr-2-android — payload defined; send path wired in GalaxyConnectionService. */
    DEVICE_EXECUTION_EVENT("device_execution_event");

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
         * [GalaxyWebSocketClient] and [GalaxyConnectionService], except for the three types
         * promoted in PR-35 (PEER_EXCHANGE, MESH_TOPOLOGY, COORD_SYNC) which now have
         * dedicated stateful handlers.
         *
         * Transitional types must not be extended as canonical architecture.
         * @see com.ufo.galaxy.runtime.LongTailCompatibilityRegistry
         */
        val ADVANCED_TYPES: Set<MsgType> = setOf(
            RELAY, FORWARD, REPLY, ACK,
            HYBRID_EXECUTE, HYBRID_RESULT, HYBRID_DEGRADE,
            RAG_QUERY, RAG_RESULT,
            CODE_EXECUTE, CODE_RESULT,
            PEER_ANNOUNCE, PEER_EXCHANGE, MESH_TOPOLOGY,
            WAKE_EVENT, SESSION_MIGRATE,
            COORD_SYNC, BROADCAST, LOCK, UNLOCK,
            TAKEOVER_REQUEST, TAKEOVER_RESPONSE,
            // ── PR-H: HandoffEnvelopeV2 native consumption ──────────────────
            HANDOFF_ENVELOPE_V2
        )

        /**
         * Advanced types for which the device should send an [AckPayload] reply
         * to confirm receipt via the generic minimal-compat path.
         *
         * Note: [COORD_SYNC] was removed from this set in PR-35; it now receives a
         * dedicated sequence-aware [CoordSyncAckPayload] response via [GalaxyConnectionService].
         * [PEER_EXCHANGE] and [MESH_TOPOLOGY] were never in this set; they also received
         * dedicated handling in PR-35.
         */
        val ACK_ON_RECEIPT_TYPES: Set<MsgType> = setOf(
            RELAY, WAKE_EVENT, LOCK, UNLOCK,
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
    val source_runtime_posture: String? = null,
    // ── PR-G: V2 observability/tracing metadata (optional; null-safe for legacy senders) ──
    val dispatch_trace_id: String? = null,
    val session_correlation_id: String? = null
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
    // ── PR-D: V2 staged dispatch metadata (optional; null-safe for legacy senders) ──
    val staged_mesh_id: String? = null,
    val staged_subtask_id: String? = null,
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
    val flow_lineage_id: String? = null
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
 * @param is_resumable   Echoed from [GoalExecutionPayload.is_resumable] so V2 can determine
 *                       whether Android treated this execution as resumable or terminal.
 *                       `null` for legacy senders.
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
    // ── PR-E: V2 explicit executor target typing (optional; echoed for full-chain correlation) ──
    val executor_target_type: String? = null,
    // ── PR-F: V2 durable continuity and recovery context (optional; echoed for full-chain correlation) ──
    val continuity_token: String? = null,
    val is_resumable: Boolean? = null,
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
    // ── Unified result contract: canonical kind + replay identity ──────────────────────────────
    // Both fields are set by GalaxyConnectionService.sendGoalResult at emission time and are
    // null only for pre-emission / locally-constructed payloads.
    val normalized_status: String? = null,
    val runtime_session_id: String? = null,
    // ── Unified result contract: normalised result summary ─────────────────────────────────────
    // Mirrors the result_summary field in TaskResultPayload and HandoffEnvelopeV2ResultPayload
    // so that all uplink result payloads carry a consistent human-readable one-line outcome.
    // Populated by GalaxyConnectionService.sendGoalResult from the `result` field when absent.
    val result_summary: String? = null
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
    val flow_lineage_id: String? = null
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
    val runtime_truth: Map<String, Any>? = null
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
    val missing_dimensions: List<String> = emptyList()
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
    val missing_dimensions: List<String> = emptyList()
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
    val missing_dimensions: List<String> = emptyList()
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
    val missing_dimensions: List<String> = emptyList()
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
 */
data class DeviceStateSnapshotPayload(
    val device_id: String,
    val snapshot_ts: Long = System.currentTimeMillis(),

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
    val attached_session_id: String? = null
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
    val attached_session_id: String? = null
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

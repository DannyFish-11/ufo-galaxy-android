package com.ufo.galaxy.runtime

/**
 * PR-12 — Canonical Task Dispatch and Delegated Execution Chain.
 *
 * Defines the canonical end-to-end task dispatch and delegated execution chain across
 * both repositories (`ufo-galaxy-android` and `ufo-galaxy-realization-v2`), making
 * the dispatch system explicitly inspectable and governable without changing any core
 * behavior.
 *
 * ## Purpose
 *
 * The system contains a meaningful execution chain spanning source dispatch orchestration,
 * local execution, remote handoff, fallback-local behavior, staged mesh coordination,
 * Android inbound dispatch, delegated signal ingress, execution tracking, signal
 * reconciliation, and result handling.  This object provides a single canonical, machine-
 * readable model so that:
 *
 * - The primary dispatch path is unambiguously identified.
 * - Fallback, staged, and compatibility paths are clearly distinguished.
 * - Android-side inbound dispatch is placed explicitly inside the same execution model.
 * - Result, signal, and reconciliation responsibilities are distribution-traced to
 *   their owning layers.
 * - Future work can reason about task dispatch as a single governed system.
 *
 * ## Design intent
 *
 * This is an **additive, compatibility-safe** model.  It does not change any runtime
 * behavior, wire contracts, or identifier values.  All path descriptors reference
 * implementation components that already exist; this object only formalizes the
 * relationships between them.
 *
 * ## Path modes
 *
 * | [DispatchPathMode]               | Description                                                         |
 * |----------------------------------|---------------------------------------------------------------------|
 * | [DispatchPathMode.CANONICAL]     | Primary cross-device path through the Gateway.                      |
 * | [DispatchPathMode.LOCAL]         | On-device closed-loop execution, no Gateway involvement.            |
 * | [DispatchPathMode.FALLBACK]      | Explicit fallback to local after remote handoff failure.            |
 * | [DispatchPathMode.STAGED_MESH]   | Subtask execution assigned by the V2 staged-mesh coordinator.       |
 * | [DispatchPathMode.DELEGATED]     | Inbound delegated takeover accepted and executed on this device.    |
 * | [DispatchPathMode.COMPATIBILITY] | Legacy message types remapped to canonical path (no fork logic).    |
 *
 * ## Dispatch phases
 *
 * The full execution chain is divided into six phases.  Each phase is described by a
 * [DispatchPhaseDescriptor] in [phases].
 *
 * ## Signal emission contract
 *
 * The delegated path emits exactly three signals per accepted execution unit, in
 * invariant order regardless of terminal outcome kind:
 * ```
 * ACK (emissionSeq=1) → PROGRESS (emissionSeq=2) → RESULT (emissionSeq=3)
 * ```
 * See [DelegatedExecutionSignal], [DelegatedTakeoverExecutor], and
 * [EmittedSignalLedger] for the signal emission contract.
 *
 * ## Rollout governance
 *
 * All execution paths are gated by [RolloutControlSnapshot].  The governing flag for
 * each path is recorded in [paths] via [DispatchPathDescriptor.rolloutGate].
 *
 * ## Relationship to other canonical models
 *
 * - [CanonicalSessionAxis] — defines the session identity model across all execution paths.
 *   [CanonicalDispatchChain] describes which execution paths exist; [CanonicalSessionAxis]
 *   describes which session families they use.
 * - [RolloutControlSnapshot] — provides the rollout gate values that govern path activation.
 * - [ExecutionRouteTag] — provides machine-readable tagging for the executing path on
 *   result delivery; [DispatchPathMode] is the planning-time analog.
 */
object CanonicalDispatchChain {

    // ── Path descriptor registry ──────────────────────────────────────────────

    /**
     * Complete registry of all execution path descriptors in the canonical dispatch chain.
     *
     * Each [DispatchPathDescriptor] describes one execution path, its classification,
     * its rollout gate, and the primary Android components involved.  Consumers can
     * query this list to enumerate all paths or look up a specific path by
     * [DispatchPathDescriptor.pathMode].
     */
    val paths: List<DispatchPathDescriptor> = listOf(

        // ── Canonical primary cross-device path ───────────────────────────────

        DispatchPathDescriptor(
            pathMode     = DispatchPathMode.CANONICAL,
            label        = "canonical_cross_device",
            description  = "Primary dispatch path: user input → InputRouter " +
                "(crossDeviceEnabled=true) → GatewayClient.sendJson(TaskSubmitPayload) → " +
                "Galaxy Gateway → inbound task_assign → GalaxyConnectionService → " +
                "EdgeExecutor / AgentRuntimeBridge → TaskResultPayload uplink.",
            rolloutGate  = RolloutGate.CROSS_DEVICE_ALLOWED,
            androidEntry = "InputRouter",
            androidExit  = "GalaxyWebSocketClient.sendJson(TaskResultPayload)",
            resultTag    = ExecutionRouteTag.CROSS_DEVICE
        ),

        // ── Local closed-loop path ────────────────────────────────────────────

        DispatchPathDescriptor(
            pathMode     = DispatchPathMode.LOCAL,
            label        = "local_closed_loop",
            description  = "On-device closed-loop execution: InputRouter " +
                "(crossDeviceEnabled=false) → LocalLoopExecutor → LoopController " +
                "(planner + grounding + accessibility) → LocalLoopResult. " +
                "No Gateway involvement.",
            rolloutGate  = RolloutGate.NONE,
            androidEntry = "InputRouter",
            androidExit  = "LocalLoopResult delivered to MainViewModel",
            resultTag    = ExecutionRouteTag.LOCAL
        ),

        // ── Fallback-local path ───────────────────────────────────────────────

        DispatchPathDescriptor(
            pathMode     = DispatchPathMode.FALLBACK,
            label        = "fallback_local",
            description  = "Explicit fallback to local execution after AgentRuntimeBridge " +
                "exhausts all retries (3 attempts, backoff 1/2/4 s). Logged at WARN, " +
                "counted in MetricsRecorder.handoffFallbacks, tagged ExecutionRouteTag.FALLBACK.",
            rolloutGate  = RolloutGate.FALLBACK_TO_LOCAL_ALLOWED,
            androidEntry = "AgentRuntimeBridge (STATUS_FALLBACK)",
            androidExit  = "GalaxyWebSocketClient.sendJson(TaskResultPayload)",
            resultTag    = ExecutionRouteTag.FALLBACK
        ),

        // ── Staged mesh coordination path ─────────────────────────────────────

        DispatchPathDescriptor(
            pathMode     = DispatchPathMode.STAGED_MESH,
            label        = "staged_mesh_subtask",
            description  = "Staged-mesh subtask assigned by the V2 coordinator via " +
                "goal_execution / parallel_subtask inbound message. " +
                "StagedMeshExecutionTarget gates on crossDeviceAllowed, then delegates " +
                "to the goal-execution pipeline. Android does not manage mesh session " +
                "lifecycle (join / leave) — that belongs to the V2 coordinator.",
            rolloutGate  = RolloutGate.CROSS_DEVICE_ALLOWED,
            androidEntry = "GalaxyConnectionService (goal_execution / parallel_subtask)",
            androidExit  = "StagedMeshParticipationResult → GOAL_EXECUTION_RESULT uplink",
            resultTag    = null
        ),

        // ── Delegated takeover path ───────────────────────────────────────────

        DispatchPathDescriptor(
            pathMode     = DispatchPathMode.DELEGATED,
            label        = "delegated_takeover",
            description  = "Inbound delegated execution: takeover_request → " +
                "DelegatedRuntimeReceiver.receive() (gate: ATTACHED session + " +
                "delegatedExecutionAllowed) → AttachedRuntimeSession.withExecutionAccepted() " +
                "→ DelegatedTakeoverExecutor.execute() → ACK / PROGRESS / RESULT signals " +
                "(emissionSeq 1/2/3) emitted via DelegatedExecutionSignalPayload uplink. " +
                "Signal identity (signalId, emissionSeq, attachedSessionId, taskId, traceId) " +
                "is captured at receipt time and stable across the full lifecycle.",
            rolloutGate  = RolloutGate.DELEGATED_EXECUTION_ALLOWED,
            androidEntry = "GalaxyConnectionService (takeover_request)",
            androidExit  = "DelegatedExecutionSignalPayload uplink (RESULT signal)",
            resultTag    = ExecutionRouteTag.DELEGATED
        ),

        // ── Compatibility path ────────────────────────────────────────────────

        DispatchPathDescriptor(
            pathMode     = DispatchPathMode.COMPATIBILITY,
            label        = "legacy_message_compat",
            description  = "Legacy task_execute and task_status_query inbound messages " +
                "are remapped to task_assign at two layers: MsgType.LEGACY_TYPE_MAP " +
                "(toV3Type()) and GalaxyWebSocketClient.handleMessage() (dispatches to " +
                "onTaskAssign). No fork logic is maintained; the canonical task_assign " +
                "path handles all remapped messages without modification.",
            rolloutGate  = RolloutGate.NONE,
            androidEntry = "GalaxyWebSocketClient.handleMessage() (task_execute / task_status_query)",
            androidExit  = "Same as CANONICAL path after remapping",
            resultTag    = ExecutionRouteTag.CROSS_DEVICE
        )
    )

    // ── Phase descriptor registry ─────────────────────────────────────────────

    /**
     * Six canonical dispatch phases that span the complete execution chain.
     *
     * Phases are ordered from source (Phase 1) to result presentation (Phase 6).
     * Each phase is bounded to one or both repositories; the authority boundary is
     * made explicit so cross-repo coordination work can be scoped to the correct side.
     */
    val phases: List<DispatchPhaseDescriptor> = listOf(

        DispatchPhaseDescriptor(
            phaseIndex   = 1,
            label        = "source_dispatch_orchestration",
            description  = "Task assignment and dispatch orchestration governed by the " +
                "center runtime (main-repo). Galaxy Gateway receives the task-submit " +
                "envelope and assigns the task to capable device(s). Android does not " +
                "participate in this phase except as the WS-connected source device.",
            authority    = PhaseAuthority.CENTER_GOVERNED,
            androidLayer = null
        ),

        DispatchPhaseDescriptor(
            phaseIndex   = 2,
            label        = "android_inbound_dispatch",
            description  = "Inbound message received by GalaxyWebSocketClient; parsed and " +
                "routed by GalaxyConnectionService. Message type determines which execution " +
                "handler is invoked: task_assign → handleTaskAssign(); " +
                "goal_execution / parallel_subtask → LocalGoalExecutor; " +
                "task_cancel → TaskCancelRegistry; takeover_request → DelegatedRuntimeReceiver.",
            authority    = PhaseAuthority.ANDROID_GOVERNED,
            androidLayer = "GalaxyConnectionService (service/)"
        ),

        DispatchPhaseDescriptor(
            phaseIndex   = 3,
            label        = "execution_pipeline",
            description  = "On-device execution. Canonical path: EdgeExecutor.handleTaskAssign() " +
                "or AgentRuntimeBridge.handoff() for remote handoff. Delegated path: " +
                "DelegatedTakeoverExecutor.execute(). Local path: LoopController.execute(). " +
                "Staged mesh path: StagedMeshExecutionTarget.acceptSubtask().",
            authority    = PhaseAuthority.ANDROID_GOVERNED,
            androidLayer = "EdgeExecutor / LoopController / DelegatedTakeoverExecutor / " +
                "StagedMeshExecutionTarget (agent/, loop/, runtime/)"
        ),

        DispatchPhaseDescriptor(
            phaseIndex   = 4,
            label        = "execution_tracking_and_signal_emission",
            description  = "Execution state is tracked via DelegatedExecutionTracker " +
                "(delegated path only) and ExecutionRouteTag (all paths). Delegated path " +
                "emits exactly three signals per unit: ACK (emissionSeq=1), PROGRESS (2), " +
                "RESULT (3). Signal identity (signalId, emissionSeq) enables main-repo " +
                "host deduplication and sequence verification. EmittedSignalLedger records " +
                "every emitted signal for replay-safe re-delivery.",
            authority    = PhaseAuthority.ANDROID_GOVERNED,
            androidLayer = "DelegatedExecutionTracker / DelegatedExecutionSignal / " +
                "EmittedSignalLedger / ExecutionRouteTag (runtime/)"
        ),

        DispatchPhaseDescriptor(
            phaseIndex   = 5,
            label        = "result_and_signal_return",
            description  = "Result payloads (TaskResultPayload / GoalResultPayload / " +
                "CancelResultPayload) and delegated signals (DelegatedExecutionSignalPayload) " +
                "are sent via GalaxyWebSocketClient. If WS is disconnected, result payloads " +
                "are buffered in OfflineTaskQueue (max 50, 24 h TTL) and replayed FIFO on " +
                "reconnect. Delegated signals are not buffered; the main-repo host detects " +
                "missing signals via emissionSeq.",
            authority    = PhaseAuthority.ANDROID_GOVERNED,
            androidLayer = "GalaxyWebSocketClient / OfflineTaskQueue (network/)"
        ),

        DispatchPhaseDescriptor(
            phaseIndex   = 6,
            label        = "result_handling_and_reconciliation",
            description  = "Android-side: MainViewModel routes result to " +
                "UnifiedResultPresentation; UI surfaces (MainActivity / " +
                "EnhancedFloatingService) reflect result. ExecutionRouteTag recorded in " +
                "MainUiState.lastExecutionRoute. TakeoverFallbackEvent triggers UI cleanup. " +
                "Center-side: main-repo tracker reconciles delegated execution signals and " +
                "closes the dispatch record. Memory backflow (OpenClawd) persists the result.",
            authority    = PhaseAuthority.SHARED,
            androidLayer = "MainViewModel / UnifiedResultPresentation (ui/viewmodel/)"
        )
    )

    // ── Lookup helpers ────────────────────────────────────────────────────────

    /** Returns the [DispatchPathDescriptor] for [mode], or `null` if not found. */
    fun pathFor(mode: DispatchPathMode): DispatchPathDescriptor? =
        paths.firstOrNull { it.pathMode == mode }

    /** Returns the [DispatchPhaseDescriptor] for the given 1-based [phaseIndex]. */
    fun phase(phaseIndex: Int): DispatchPhaseDescriptor? =
        phases.firstOrNull { it.phaseIndex == phaseIndex }

    /** Returns all paths governed by [gate], or all paths when [gate] is `null`. */
    fun pathsForGate(gate: RolloutGate?): List<DispatchPathDescriptor> =
        if (gate == null) paths else paths.filter { it.rolloutGate == gate }

    /**
     * PR-37 — Returns the subset of dispatch paths that are currently eligible given
     * the supplied runtime/session/rollout state.
     *
     * Uses [RuntimeDispatchReadinessCoordinator.resolve] to evaluate cross-device
     * eligibility, then filters [paths] to those whose [DispatchPathDescriptor.rolloutGate]
     * is satisfied:
     *
     * - [DispatchPathMode.CANONICAL], [DispatchPathMode.STAGED_MESH], and
     *   [DispatchPathMode.DELEGATED] are eligible only when cross-device readiness is
     *   confirmed (runtime Active, session ATTACHED, rollout flag on).
     * - [DispatchPathMode.FALLBACK] is eligible when [RolloutControlSnapshot.fallbackToLocalAllowed]
     *   is `true`, regardless of runtime or session state.
     * - [DispatchPathMode.COMPATIBILITY] is always eligible (no rollout gate).
     * - [DispatchPathMode.LOCAL] is always eligible.
     *
     * @param runtimeState    Current [RuntimeController.RuntimeState].
     * @param attachedSession Current [AttachedRuntimeSession], or `null` if none.
     * @param rollout         Current [RolloutControlSnapshot].
     * @return                The [DispatchPathDescriptor] entries that are eligible for
     *                        dispatch under the supplied state.
     */
    fun resolveEligiblePathsForState(
        runtimeState: RuntimeController.RuntimeState,
        attachedSession: AttachedRuntimeSession?,
        rollout: RolloutControlSnapshot
    ): List<DispatchPathDescriptor> {
        val readiness = RuntimeDispatchReadinessCoordinator.resolve(
            runtimeState    = runtimeState,
            attachedSession = attachedSession,
            rollout         = rollout
        )
        return paths.filter { descriptor ->
            when (descriptor.rolloutGate) {
                RolloutGate.NONE                      -> true
                RolloutGate.CROSS_DEVICE_ALLOWED      -> readiness.isEligible
                RolloutGate.FALLBACK_TO_LOCAL_ALLOWED -> rollout.fallbackToLocalAllowed
                RolloutGate.DELEGATED_EXECUTION_ALLOWED ->
                    readiness.isEligible && rollout.delegatedExecutionAllowed
                RolloutGate.GOAL_EXECUTION_ALLOWED    ->
                    readiness.isEligible && rollout.goalExecutionAllowed
            }
        }
    }
}

// ── Data types ────────────────────────────────────────────────────────────────

/**
 * Classification of a dispatch path inside the canonical execution chain.
 *
 * Used by [DispatchPathDescriptor.pathMode] to make the canonical / fallback / staged /
 * compatibility path distinctions explicit and machine-readable.
 */
enum class DispatchPathMode(val wireValue: String) {
    /** Primary cross-device path through the Gateway. */
    CANONICAL("canonical"),

    /** On-device closed-loop execution; no Gateway involvement. */
    LOCAL("local"),

    /** Explicit fallback to local after remote handoff failure. */
    FALLBACK("fallback"),

    /** Subtask execution assigned by the V2 staged-mesh coordinator. */
    STAGED_MESH("staged_mesh"),

    /** Inbound delegated takeover accepted and executed on this device. */
    DELEGATED("delegated"),

    /** Legacy message types remapped to the canonical path. */
    COMPATIBILITY("compatibility")
}

/**
 * The rollout-control gate that governs whether a dispatch path is active.
 *
 * Maps to the corresponding flag in [RolloutControlSnapshot].
 */
enum class RolloutGate(val snapshotField: String) {
    /** Governed by [RolloutControlSnapshot.crossDeviceAllowed]. */
    CROSS_DEVICE_ALLOWED("crossDeviceAllowed"),

    /** Governed by [RolloutControlSnapshot.delegatedExecutionAllowed]. */
    DELEGATED_EXECUTION_ALLOWED("delegatedExecutionAllowed"),

    /** Governed by [RolloutControlSnapshot.fallbackToLocalAllowed]. */
    FALLBACK_TO_LOCAL_ALLOWED("fallbackToLocalAllowed"),

    /** Governed by [RolloutControlSnapshot.goalExecutionAllowed]. */
    GOAL_EXECUTION_ALLOWED("goalExecutionAllowed"),

    /** No rollout gate; the path is always available. */
    NONE("none")
}

/**
 * The authority domain responsible for governing a dispatch phase.
 */
enum class PhaseAuthority {
    /** Center / main-repo governed. Android participates but does not own decisions. */
    CENTER_GOVERNED,

    /** Android-governed. Android owns the decision and execution for this phase. */
    ANDROID_GOVERNED,

    /** Shared responsibility: both Android and center contribute to this phase. */
    SHARED
}

/**
 * Descriptor for one execution path in the canonical dispatch chain.
 *
 * @param pathMode     Classification of this path ([DispatchPathMode]).
 * @param label        Stable machine-readable label for this path.
 * @param description  Human-readable description of the path, including the Android entry
 *                     component, execution steps, and exit point.
 * @param rolloutGate  The [RolloutControlSnapshot] flag that must be `true` for this path
 *                     to be active, or [RolloutGate.NONE] if the path is always available.
 * @param androidEntry Human-readable description of the Android component where this path
 *                     enters the execution layer.
 * @param androidExit  Human-readable description of the Android component where this path
 *                     exits (result / signal uplink).
 * @param resultTag    The [ExecutionRouteTag] that is recorded for results produced on this
 *                     path, or `null` for paths that do not produce a tagged result.
 */
data class DispatchPathDescriptor(
    val pathMode: DispatchPathMode,
    val label: String,
    val description: String,
    val rolloutGate: RolloutGate,
    val androidEntry: String,
    val androidExit: String,
    val resultTag: ExecutionRouteTag?
)

/**
 * Descriptor for one phase in the canonical dispatch chain.
 *
 * @param phaseIndex   1-based index; phases are ordered from source (1) to result (6).
 * @param label        Stable machine-readable label for this phase.
 * @param description  Human-readable description of what happens in this phase, including
 *                     Android components and cross-repo coordination responsibilities.
 * @param authority    The [PhaseAuthority] domain that owns this phase.
 * @param androidLayer The primary Android package / component for this phase, or `null`
 *                     for center-governed phases where Android is not the authority.
 */
data class DispatchPhaseDescriptor(
    val phaseIndex: Int,
    val label: String,
    val description: String,
    val authority: PhaseAuthority,
    val androidLayer: String?
)

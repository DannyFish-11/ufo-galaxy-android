package com.ufo.galaxy.runtime

/**
 * PR-61 — Android Participant Runtime Semantics Boundary.
 *
 * A single, structured, reviewer-facing boundary declaration for three acceptance criteria:
 *
 *  1. **What Android considers local participant/runtime truth** — the complete set of
 *     state fields that Android owns exclusively and publishes to V2 as self-reported truth.
 *
 *  2. **How Android represents cancel/status/failure/result semantics** — the explicit,
 *     named execution-outcome protocol covering all task lifecycle phases from acceptance
 *     through terminal outcome.
 *
 *  3. **Whether Android behaves as a first-class participant runtime** — the explicit
 *     declaration of Android's role as a local-truth-owning execution participant, distinct
 *     from both a passive command executor and a second orchestration authority.
 *
 * ## Problem addressed
 *
 * Prior PRs established the individual runtime truth surfaces:
 *  - PR-51: [AndroidParticipantRuntimeTruth], [ReconciliationSignal], [ActiveTaskStatus]
 *  - PR-52: [RuntimeController.reconciliationSignals] and emission points
 *  - PR-53: [AppLifecycleParticipantBoundary], [HybridParticipantCapabilityBoundary],
 *           [ParticipantRecoveryReadinessSnapshot]
 *  - PR-60: [AndroidAppLifecycleTransition], [HybridParticipantCapability],
 *           [AndroidLifecycleRecoveryContract]
 *
 * However, each surface answers one specific question in isolation.  Without a single
 * point of reference, a reviewer still needs to assemble the answer to the three
 * acceptance-criteria questions from multiple scattered surfaces.
 *
 * [ParticipantRuntimeSemanticsBoundary] is that single point of reference.  It does
 * **not** own any lifecycle state — it documents the boundary and provides structured
 * constants so the answers to all three questions are programmatically verifiable in tests.
 *
 * ## Responsibility boundary
 *
 * Android is the **participant-side runtime owner**.  It owns:
 *  - All fields in [ANDROID_TRUTH_DOMAIN] exclusively.
 *  - All [ExecutionOutcome] signal semantics (what the outcomes mean and when they are emitted).
 *  - Its own lifecycle transitions ([AndroidAppLifecycleTransition]) and hybrid capability status.
 *
 * V2 is the **canonical orchestration authority**.  It owns:
 *  - What to do with Android's signals (whether to retry, rebalance, close a task).
 *  - Global session / participant / task assignment truth.
 *  - Cross-participant formation, barrier, and merge coordination.
 *
 * Android MUST NOT make orchestration decisions unilaterally.  V2 MUST NOT override
 * Android's local truth without receiving an explicit signal from Android.
 */
object ParticipantRuntimeSemanticsBoundary {

    // ── 1. Android-owned truth domains ───────────────────────────────────────

    /**
     * Complete map of all Android-owned runtime truth domains and their owning surfaces.
     *
     * Each entry maps a truth domain name to the canonical surface ([TruthDomainEntry])
     * that owns and publishes that domain.  V2 must treat all fields in each domain as
     * Android-reported local truth that takes precedence over V2's cached state for that
     * domain.
     *
     * Truth domains:
     *  - `"participant_identity"` — participant and device identity fields.
     *  - `"participation_state"` — lifecycle participation readiness.
     *  - `"session_attachment"` — current session identity and attachment state.
     *  - `"runtime_posture"` — source runtime posture (join_runtime / control_only).
     *  - `"health_state"` — local execution environment health.
     *  - `"readiness_state"` — dispatch-selection readiness.
     *  - `"active_task_status"` — in-flight task execution status.
     *  - `"task_outcomes"` — cancel, status, failure, and result signals.
     *  - `"lifecycle_transitions"` — app-level lifecycle event model.
     *  - `"hybrid_capability"` — hybrid/distributed execution capability status.
     */
    val ANDROID_TRUTH_DOMAIN: Map<String, TruthDomainEntry> = mapOf(
        "participant_identity" to TruthDomainEntry(
            domainKey = "participant_identity",
            fields = listOf("participantId", "deviceId", "hostId", "deviceRole", "formationRole"),
            canonicalSurface = "RuntimeHostDescriptor + CanonicalParticipantModel",
            snapshotSurface = "AndroidParticipantRuntimeTruth",
            v2ConsumerAction = "Update participant registry entry for this device"
        ),
        "participation_state" to TruthDomainEntry(
            domainKey = "participation_state",
            fields = listOf("participationState"),
            canonicalSurface = "RuntimeHostDescriptor.HostParticipationState",
            snapshotSurface = "AndroidParticipantRuntimeTruth",
            v2ConsumerAction = "Update device dispatch eligibility; do not dispatch to INACTIVE or DRAINING"
        ),
        "session_attachment" to TruthDomainEntry(
            domainKey = "session_attachment",
            fields = listOf("sessionId", "sessionState", "delegatedExecutionCount", "isReuseValid"),
            canonicalSurface = "AttachedRuntimeSession + AttachedRuntimeHostSessionSnapshot",
            snapshotSurface = "AndroidParticipantRuntimeTruth",
            v2ConsumerAction = "Update session assignment; validate dispatch target against sessionState"
        ),
        "runtime_posture" to TruthDomainEntry(
            domainKey = "runtime_posture",
            fields = listOf("sourceRuntimePosture"),
            canonicalSurface = "SourceRuntimePosture",
            snapshotSurface = "AndroidParticipantRuntimeTruth",
            v2ConsumerAction = "Determine task allocation: JOIN_RUNTIME → eligible; CONTROL_ONLY → initiator only"
        ),
        "health_state" to TruthDomainEntry(
            domainKey = "health_state",
            fields = listOf("healthState"),
            canonicalSurface = "ParticipantHealthState",
            snapshotSurface = "AndroidParticipantRuntimeTruth + ReconciliationSignal.PARTICIPANT_STATE",
            v2ConsumerAction = "Adjust dispatch priority; block new dispatch for RECOVERING/FAILED"
        ),
        "readiness_state" to TruthDomainEntry(
            domainKey = "readiness_state",
            fields = listOf("readinessState"),
            canonicalSurface = "ParticipantReadinessState",
            snapshotSurface = "AndroidParticipantRuntimeTruth + ReconciliationSignal.PARTICIPANT_STATE",
            v2ConsumerAction = "Include/exclude from dispatch selection; READY or READY_WITH_FALLBACK eligible"
        ),
        "active_task_status" to TruthDomainEntry(
            domainKey = "active_task_status",
            fields = listOf("activeTaskId", "activeTaskStatus"),
            canonicalSurface = "ActiveTaskStatus",
            snapshotSurface = "AndroidParticipantRuntimeTruth",
            v2ConsumerAction = "Track in-flight execution; CANCELLING/FAILING indicate impending terminal signal"
        ),
        "task_outcomes" to TruthDomainEntry(
            domainKey = "task_outcomes",
            fields = listOf("cancel", "status", "failure", "result"),
            canonicalSurface = "ReconciliationSignal + AndroidSessionContribution",
            snapshotSurface = "RuntimeController.reconciliationSignals",
            v2ConsumerAction = "Close/update task according to ExecutionOutcome protocol; see EXECUTION_OUTCOMES"
        ),
        "lifecycle_transitions" to TruthDomainEntry(
            domainKey = "lifecycle_transitions",
            fields = listOf("appLifecycleTransition"),
            canonicalSurface = "AndroidAppLifecycleTransition",
            snapshotSurface = "RuntimeController.onAppLifecycleTransition → V2MultiDeviceLifecycleEvent",
            v2ConsumerAction = "Update device presence; reconnect=RECONNECTED, process kill=CONNECTED (new era)"
        ),
        "hybrid_capability" to TruthDomainEntry(
            domainKey = "hybrid_capability",
            fields = listOf("hybridCapabilityStatus"),
            canonicalSurface = "HybridParticipantCapability",
            snapshotSurface = "HybridParticipantCapabilityBoundary",
            v2ConsumerAction = "Apply fallback policy for NOT_YET_IMPLEMENTED capabilities; do not assume availability"
        )
    )

    /**
     * Count of truth domains that Android exclusively owns.
     *
     * Useful in tests to assert the domain inventory has not silently shrunk.
     */
    const val TRUTH_DOMAIN_COUNT: Int = 10

    // ── 2. Cancel / Status / Failure / Result semantics ──────────────────────

    /**
     * Structured execution-outcome protocol covering all task lifecycle phases.
     *
     * Each [ExecutionOutcome] entry declares:
     *  - The [ReconciliationSignal.Kind] wire value emitted by Android.
     *  - The phase in the task lifecycle when the outcome is emitted.
     *  - Whether the outcome is terminal (closes the V2 task record).
     *  - The V2 action required on receipt.
     *
     * Ordered by lifecycle phase: pre-execution → in-progress → terminal.
     */
    val EXECUTION_OUTCOMES: List<ExecutionOutcome> = listOf(
        ExecutionOutcome(
            signalKind = ReconciliationSignal.Kind.TASK_ACCEPTED.wireValue,
            phase = ExecutionPhase.PRE_EXECUTION,
            isTerminal = false,
            androidEmitCondition = "Android accepted a delegated task and execution has begun",
            v2Action = "Mark task as in-progress under this participant; block duplicate dispatch"
        ),
        ExecutionOutcome(
            signalKind = ReconciliationSignal.Kind.TASK_STATUS_UPDATE.wireValue,
            phase = ExecutionPhase.IN_PROGRESS,
            isTerminal = false,
            androidEmitCondition = "Android reports intermediate execution progress for a running task",
            v2Action = "Update in-flight progress view; do not close task"
        ),
        ExecutionOutcome(
            signalKind = ReconciliationSignal.Kind.TASK_RESULT.wireValue,
            phase = ExecutionPhase.TERMINAL,
            isTerminal = true,
            androidEmitCondition = "Android completed the task successfully",
            v2Action = "Close task as success; update participant contribution record and session truth"
        ),
        ExecutionOutcome(
            signalKind = ReconciliationSignal.Kind.TASK_CANCELLED.wireValue,
            phase = ExecutionPhase.TERMINAL,
            isTerminal = true,
            androidEmitCondition = "Android is stopping the task (explicit cancel or runtime preemption); " +
                "emitted BEFORE the full termination sequence completes",
            v2Action = "Close task as cancelled; release execution capacity; do not re-dispatch until " +
                "terminal AndroidSessionContribution.Kind.CANCELLATION confirms completion"
        ),
        ExecutionOutcome(
            signalKind = ReconciliationSignal.Kind.TASK_FAILED.wireValue,
            phase = ExecutionPhase.TERMINAL,
            isTerminal = true,
            androidEmitCondition = "Android encountered a failure condition; emitted BEFORE the full " +
                "termination sequence completes",
            v2Action = "Close task as failed; begin fallback/retry evaluation per V2 orchestration policy; " +
                "inspect payload[error_detail] for failure classification"
        ),
        ExecutionOutcome(
            signalKind = ReconciliationSignal.Kind.PARTICIPANT_STATE.wireValue,
            phase = ExecutionPhase.ANY,
            isTerminal = false,
            androidEmitCondition = "Android participant state changed: health, readiness, or posture",
            v2Action = "Update canonical participant view immediately; no task closure"
        ),
        ExecutionOutcome(
            signalKind = ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT.wireValue,
            phase = ExecutionPhase.ANY,
            isTerminal = false,
            androidEmitCondition = "Android publishes a full AndroidParticipantRuntimeTruth snapshot",
            v2Action = "Full reconciliation pass: resolve conflicts in favour of the snapshot; " +
                "discard snapshots with lower reconciliationEpoch than most recently received"
        )
    )

    /**
     * Count of distinct execution outcomes in the protocol.
     *
     * Useful in tests to assert the protocol inventory has not silently shrunk.
     */
    const val EXECUTION_OUTCOME_COUNT: Int = 7

    /**
     * Count of terminal execution outcomes in the protocol.
     *
     * Terminal outcomes close the V2 task record: TASK_RESULT, TASK_CANCELLED, TASK_FAILED.
     */
    const val TERMINAL_OUTCOME_COUNT: Int = 3

    /**
     * Protocol safety rules that constrain Android's signal emission behaviour.
     *
     * These rules are invariants that V2 may rely on:
     *  - [RULE_NO_RESULT_AFTER_CANCEL]: Android never emits TASK_RESULT for a taskId after
     *    emitting TASK_CANCELLED for the same taskId.
     *  - [RULE_SIGNAL_ID_UNIQUE]: signalId values are unique across all signals.
     *  - [RULE_EPOCH_MONOTONIC]: reconciliationEpoch is monotonically increasing per participant.
     *  - [RULE_SNAPSHOT_AUTHORITATIVE]: RUNTIME_TRUTH_SNAPSHOT resolves conflicts in Android's
     *    favour — V2 must update its state to match the snapshot.
     *  - [RULE_ANDROID_LOCAL_TRUTH_ONLY]: Android owns its local truth; it does not modify
     *    V2 canonical state directly.  All V2 state changes are V2's decision based on signals.
     */
    const val RULE_NO_RESULT_AFTER_CANCEL = "TASK_CANCELLED for a taskId implies no TASK_RESULT will follow for that taskId"
    const val RULE_SIGNAL_ID_UNIQUE = "signalId values are unique per signal emission for deduplication"
    const val RULE_EPOCH_MONOTONIC = "reconciliationEpoch is monotonically increasing per participant process lifetime"
    const val RULE_SNAPSHOT_AUTHORITATIVE = "RUNTIME_TRUTH_SNAPSHOT resolves conflicts in Android's favour"
    const val RULE_ANDROID_LOCAL_TRUTH_ONLY = "Android owns local truth; V2 owns canonical orchestration truth"

    /** All protocol safety rules as a stable, testable set. */
    val PROTOCOL_SAFETY_RULES: Set<String> = setOf(
        RULE_NO_RESULT_AFTER_CANCEL,
        RULE_SIGNAL_ID_UNIQUE,
        RULE_EPOCH_MONOTONIC,
        RULE_SNAPSHOT_AUTHORITATIVE,
        RULE_ANDROID_LOCAL_TRUTH_ONLY
    )

    // ── 3. First-class participant runtime declaration ────────────────────────

    /**
     * Explicit declaration of Android's role as a first-class participant runtime.
     *
     * Android is **not** a passive command executor: it owns local truth, runs its own
     * perception/planning/execution loop, manages its own lifecycle, and participates as an
     * equal peer in the multi-device execution fabric.
     *
     * This declaration captures the distinguishing behaviours that make Android a
     * first-class participant rather than a passive target:
     *
     * | Property | First-class participant behaviour |
     * |---|---|
     * | Local AI execution | MobileVLM 1.7B planner + SeeClick grounding + AccessibilityService |
     * | Local truth ownership | All domains in [ANDROID_TRUTH_DOMAIN] |
     * | Lifecycle authority | [AndroidAppLifecycleTransition] decisions are Android's |
     * | Cancel/status reporting | Pre-terminal signals via [ReconciliationSignal] |
     * | Posture elevation | Can be [SourceRuntimePosture.JOIN_RUNTIME] (full runtime host) |
     * | Health self-assessment | Reports [ParticipantHealthState] independently |
     * | Readiness self-assessment | Reports [ParticipantReadinessState] independently |
     * | Formation participation | Can act as COORDINATOR or PARTICIPANT role |
     *
     * Android is explicitly **not** a second orchestration authority:
     *  - It does not make task-assignment decisions.
     *  - It does not decide session continuation or termination unilaterally.
     *  - It does not override V2's canonical participant truth.
     *  - It does not participate in barrier coordination (V2-side authority).
     */
    const val FIRST_CLASS_PARTICIPANT_DECLARATION =
        "Android is a first-class participant runtime: it owns local execution truth, runs a " +
            "complete local AI perception/planning/execution loop, manages its own lifecycle, " +
            "and reports structured cancel/status/failure/result signals to V2. It is not a " +
            "passive executor and not a second orchestration authority."

    /**
     * Behaviours that make Android a first-class participant (not a passive executor).
     *
     * Each entry is a named capability that can be verified in tests.
     */
    val FIRST_CLASS_PARTICIPANT_BEHAVIOURS: Map<String, String> = mapOf(
        "local_ai_execution_loop" to
            "MobileVLM planner + SeeClick grounding + AccessibilityService: screenshot→plan→click→repeat",
        "local_truth_ownership" to
            "Exclusive ownership of ${TRUTH_DOMAIN_COUNT} truth domains; V2 must not override without signal",
        "lifecycle_authority" to
            "AndroidAppLifecycleTransition decisions are Android's; V2 observes V2MultiDeviceLifecycleEvent",
        "pre_terminal_cancel_signal" to
            "TASK_CANCELLED emitted as soon as cancel is determined, before termination completes",
        "pre_terminal_failure_signal" to
            "TASK_FAILED emitted as soon as failure is detected, before termination completes",
        "posture_elevation" to
            "Can elevate from CONTROL_ONLY to JOIN_RUNTIME; becomes full runtime host peer",
        "health_self_assessment" to
            "ParticipantHealthState assessed and reported independently: HEALTHY/DEGRADED/RECOVERING/FAILED",
        "readiness_self_assessment" to
            "ParticipantReadinessState assessed and reported independently: READY/READY_WITH_FALLBACK/NOT_READY",
        "formation_participation" to
            "Participates in DeviceFormationGroup as COORDINATOR or PARTICIPANT role"
    )

    /**
     * Count of distinct first-class participant behaviours.
     *
     * Useful in tests to assert the behaviour inventory has not silently shrunk.
     */
    const val FIRST_CLASS_BEHAVIOUR_COUNT: Int = 9

    /**
     * Explicit behaviours that Android does NOT perform — boundaries against becoming
     * a second orchestration authority.
     *
     * These are the constraints that keep Android on the participant side:
     */
    val NOT_ORCHESTRATION_AUTHORITY_BOUNDARIES: Map<String, String> = mapOf(
        "no_task_assignment" to
            "Android does not decide which tasks to dispatch or to which devices",
        "no_unilateral_session_continuation" to
            "Android reports lifecycle events to V2; V2 decides whether to continue or terminate the session",
        "no_canonical_truth_override" to
            "Android reports local truth; V2's canonical reconciliation decision is V2's authority",
        "no_barrier_coordination" to
            "Barrier/merge coordination is V2-side authority; Android reports task completion, not barrier signals",
        "no_formation_rebalance_authority" to
            "Formation rebalance decisions are V2-side; Android reports health/readiness state changes"
    )

    /**
     * Count of explicit non-orchestration boundaries.
     *
     * Useful in tests to assert the boundary inventory has not silently shrunk.
     */
    const val NOT_ORCHESTRATION_BOUNDARY_COUNT: Int = 5

    // ── Companion data types ──────────────────────────────────────────────────

    /**
     * An entry in the Android-owned truth domain registry.
     *
     * @property domainKey           Stable key for this truth domain.
     * @property fields              List of field names belonging to this domain.
     * @property canonicalSurface    The Kotlin class(es) that own and maintain this domain.
     * @property snapshotSurface     The surface(s) through which V2 observes this domain.
     * @property v2ConsumerAction    What V2 must do on receiving an update for this domain.
     */
    data class TruthDomainEntry(
        val domainKey: String,
        val fields: List<String>,
        val canonicalSurface: String,
        val snapshotSurface: String,
        val v2ConsumerAction: String
    )

    /**
     * An entry in the execution-outcome protocol registry.
     *
     * @property signalKind            Wire value of [ReconciliationSignal.Kind] for this outcome.
     * @property phase                 Lifecycle phase when this outcome is emitted.
     * @property isTerminal            Whether this outcome closes the V2 task record.
     * @property androidEmitCondition  When Android emits this signal.
     * @property v2Action              What V2 must do on receiving this signal.
     */
    data class ExecutionOutcome(
        val signalKind: String,
        val phase: ExecutionPhase,
        val isTerminal: Boolean,
        val androidEmitCondition: String,
        val v2Action: String
    )

    /**
     * Task lifecycle phase in the execution-outcome protocol.
     */
    enum class ExecutionPhase(val wireValue: String) {
        /** Before execution starts (task accepted but not yet running). */
        PRE_EXECUTION("pre_execution"),
        /** Task is actively executing. */
        IN_PROGRESS("in_progress"),
        /** Task execution has concluded (success, cancel, or failure). */
        TERMINAL("terminal"),
        /** Signal is independent of task execution phase. */
        ANY("any")
    }
}

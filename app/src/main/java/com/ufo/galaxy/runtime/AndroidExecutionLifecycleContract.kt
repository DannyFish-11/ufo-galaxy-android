package com.ufo.galaxy.runtime

/**
 * PR-07 (Android) — Android Execution Lifecycle Hardening Contract.
 *
 * Single authoritative contract for Android-side execution lifecycle semantics covering all
 * execution paths: start → active → interruption → completion → failure → retry → recovery.
 *
 * ## Problem addressed
 *
 * Prior to PR-07, Android execution lifecycle was fragmented across multiple surfaces:
 *  - [DelegatedActivationRecord.ActivationStatus]: PENDING/ACTIVATING/ACTIVE/COMPLETED/FAILED/REJECTED
 *  - [AndroidFlowExecutionPhase]: RECEIVED/ACTIVATING/ACTIVE_*/COMPLETED/FAILED/REJECTED/UNKNOWN
 *  - [AndroidLifecycleRecoveryContract]: connection-level recovery semantics
 *
 * None of those surfaces defined the full execution lifecycle including:
 *  - **Interruption**: execution interrupted by timeout, cancellation, or disconnect.
 *  - **Degraded execution**: execution running in fallback/degraded mode with reduced capability.
 *  - **Retry**: execution being retried after a retriable interruption.
 *  - **Recovery**: execution-level recovery distinct from connection-level recovery.
 *
 * [AndroidExecutionLifecycleContract] closes these gaps by defining:
 *  1. [ExecutionLifecyclePhase] — the full 12-phase execution lifecycle state machine.
 *  2. [TRANSITION_TABLE] — valid phase transitions with uplink requirements.
 *  3. [UplinkRequirement] — what signals must be reported at each transition.
 *  4. [LIFECYCLE_INVARIANTS] — testable governance rules that all execution paths MUST respect.
 *  5. Helper functions for phase classification and uplink decisions.
 *
 * ## Capability vs active execution vs terminal state
 *
 * A key hardening goal is eliminating ambiguity between three fundamentally different states:
 *
 *  | Category            | Phases                                          | V2 dispatch eligible? |
 *  |---------------------|-------------------------------------------------|-----------------------|
 *  | Capability (idle)   | [ExecutionLifecyclePhase.CAPABILITY_IDLE]       | Yes (new task OK)     |
 *  | Active execution    | [ExecutionLifecyclePhase.ACTIVE],               | No (task in flight)   |
 *  |                     | [ExecutionLifecyclePhase.DEGRADED]              |                       |
 *  | Partial/transient   | [ExecutionLifecyclePhase.PENDING],              | No (transitioning)    |
 *  |                     | [ExecutionLifecyclePhase.ACTIVATING],           |                       |
 *  |                     | [ExecutionLifecyclePhase.INTERRUPTED],          |                       |
 *  |                     | [ExecutionLifecyclePhase.TIMED_OUT],            |                       |
 *  |                     | [ExecutionLifecyclePhase.RETRYING]              |                       |
 *  | Terminal            | [ExecutionLifecyclePhase.COMPLETED],            | Yes (after clearing)  |
 *  |                     | [ExecutionLifecyclePhase.FAILED],               |                       |
 *  |                     | [ExecutionLifecyclePhase.REJECTED]              |                       |
 *
 * @see ExecutionUplinkDiscipline
 * @see DelegatedActivationRecord.ActivationStatus
 * @see AndroidFlowExecutionPhase
 * @see AndroidLifecycleRecoveryContract
 */
object AndroidExecutionLifecycleContract {

    // ── Full execution lifecycle phase enum ───────────────────────────────────

    /**
     * Complete Android-side execution lifecycle phase covering all paths including
     * interruption, degraded execution, retry, and recovery.
     *
     * ## Phase graph
     *
     * ```
     * CAPABILITY_IDLE ──► PENDING ──► ACTIVATING ──► ACTIVE ──────────────────────── ► COMPLETED
     *         ▲                │             │            │                                  │
     *         │                │             │            ├──► DEGRADED ──► ACTIVE            │
     *         │                │             │            │        └──────► FAILED             │
     *         │                │             │            ├──► INTERRUPTED ──► RETRYING ──►(ACTIVATING)
     *         │                │             │            │         └──────────────── ► FAILED │
     *         │                │             │            ├──► TIMED_OUT ──────────── ► FAILED │
     *         │                │             │            └──► FAILED                          │
     *         │                │             └──────────────────────────────────────► (various)│
     *         │                └──────────────────────────────────────────────────── ► REJECTED│
     *         └─────────────────────────────────────────────────────── (from terminal) ◄───────┘
     * ```
     *
     * @property wireValue              Stable lowercase string for JSON payloads and logging.
     * @property isTerminal             True when this is a final execution state (no transitions out except to CAPABILITY_IDLE).
     * @property isActiveExecution      True when an Android execution component owns execution truth.
     * @property isCapabilityOnly       True when the device is registered/capable but not executing.
     * @property requiresResultUplink   True when a result signal MUST be emitted on entering this phase.
     * @property requiresStateUplink    True when a state snapshot MUST be published on entering this phase.
     */
    enum class ExecutionLifecyclePhase(
        val wireValue: String,
        val isTerminal: Boolean,
        val isActiveExecution: Boolean,
        val isCapabilityOnly: Boolean,
        val requiresResultUplink: Boolean,
        val requiresStateUplink: Boolean
    ) {

        /**
         * Device is registered and advertising capabilities, but no execution task is active.
         *
         * This is the **idle** state between executions. It represents declared capability,
         * not active execution. V2 may dispatch new tasks to a participant in this phase.
         *
         * MUST NOT be reported as "active execution" in any uplink.
         *
         * Uplinks: none required (capability is advertised via capability_report).
         */
        CAPABILITY_IDLE(
            wireValue = "capability_idle",
            isTerminal = false,
            isActiveExecution = false,
            isCapabilityOnly = true,
            requiresResultUplink = false,
            requiresStateUplink = false
        ),

        /**
         * Execution request accepted; pre-execution gate check passed.
         *
         * The inbound command has been validated and the unit is queued for activation.
         * No execution pipeline has been entered yet. A STATE uplink is required to inform
         * V2 that Android has accepted the execution request.
         *
         * Uplinks: STATE (informs V2 of acceptance; no result signal).
         */
        PENDING(
            wireValue = "pending",
            isTerminal = false,
            isActiveExecution = false,
            isCapabilityOnly = false,
            requiresResultUplink = false,
            requiresStateUplink = true
        ),

        /**
         * Execution pipeline is initialising: resources being allocated, models being loaded.
         *
         * The activation pipeline has begun but execution steps have not yet started.
         * STATE uplink required so V2 tracks that execution is in preparation.
         *
         * Uplinks: STATE.
         */
        ACTIVATING(
            wireValue = "activating",
            isTerminal = false,
            isActiveExecution = false,
            isCapabilityOnly = false,
            requiresResultUplink = false,
            requiresStateUplink = true
        ),

        /**
         * Execution pipeline is running and making forward progress.
         *
         * An Android execution component (goal executor, loop, collaboration agent, or
         * takeover executor) owns execution truth. STATE uplink required on entry.
         * PROGRESS signals may be emitted per execution step.
         *
         * Uplinks: STATE on entry (ACK signal marks ACTIVE entry).
         */
        ACTIVE(
            wireValue = "active",
            isTerminal = false,
            isActiveExecution = true,
            isCapabilityOnly = false,
            requiresResultUplink = false,
            requiresStateUplink = true
        ),

        /**
         * Execution is running in a degraded or fallback mode.
         *
         * Primary execution resource (planner or grounder) is unavailable or unhealthy;
         * execution continues through a fallback path ([DegradedPlannerService] or
         * [DegradedGroundingService]). Capability is reduced but execution is not terminal.
         * V2 must be informed of the reduced capability via STATE uplink.
         *
         * MUST NOT be advertised as full ACTIVE capability.
         *
         * Uplinks: STATE (includes [KEY_FALLBACK_TIER] and [KEY_IS_DEGRADED_EXECUTION]=true).
         */
        DEGRADED(
            wireValue = "degraded",
            isTerminal = false,
            isActiveExecution = true,
            isCapabilityOnly = false,
            requiresResultUplink = false,
            requiresStateUplink = true
        ),

        /**
         * Execution was interrupted by an external event before reaching a terminal state.
         *
         * Interruption causes include: WS disconnect ([INTERRUPTION_CAUSE_WS_DISCONNECT]),
         * explicit cancellation ([INTERRUPTION_CAUSE_EXPLICIT_CANCEL]), process kill
         * ([INTERRUPTION_CAUSE_PROCESS_KILL]), and session invalidation
         * ([INTERRUPTION_CAUSE_SESSION_INVALIDATION]).
         *
         * **This is the critical gap closed by PR-07**: previously, interruption was mapped
         * silently to FAILED, preventing V2 from distinguishing retriable interruptions from
         * definitive failures and applying its retry/fallback policy appropriately.
         *
         * After entering INTERRUPTED, Android MUST:
         *  1. Emit a RESULT signal with the interruption cause.
         *  2. Publish a STATE snapshot.
         *  3. Clear in-flight task state.
         *
         * INTERRUPTED is not terminal — it may transition to RETRYING (if retry is allowed)
         * or FAILED (if not).
         *
         * Uplinks: RESULT (interruption signal), STATE (with [KEY_INTERRUPTION_CAUSE]).
         */
        INTERRUPTED(
            wireValue = "interrupted",
            isTerminal = false,
            isActiveExecution = false,
            isCapabilityOnly = false,
            requiresResultUplink = true,
            requiresStateUplink = true
        ),

        /**
         * Execution timed out before reaching a terminal state.
         *
         * A wall-clock budget was exceeded. The execution pipeline has been cancelled.
         * RESULT(TIMEOUT) and STATE uplinks are required. TIMED_OUT transitions immediately
         * to terminal FAILED after the uplinks are emitted.
         *
         * Uplinks: RESULT (kind=TIMEOUT), STATE.
         */
        TIMED_OUT(
            wireValue = "timed_out",
            isTerminal = false,
            isActiveExecution = false,
            isCapabilityOnly = false,
            requiresResultUplink = true,
            requiresStateUplink = true
        ),

        /**
         * Execution is being retried after an interruption or retriable failure.
         *
         * A retry attempt has been authorised. The execution pipeline will be re-entered
         * via ACTIVATING. V2 must receive a STATE uplink so it can track the retry attempt
         * count and apply its own retry limit policy.
         *
         * RETRYING MUST carry a non-zero [KEY_RETRY_ATTEMPT_COUNT] in its STATE uplink.
         *
         * Uplinks: STATE (with [KEY_RETRY_ATTEMPT_COUNT]).
         */
        RETRYING(
            wireValue = "retrying",
            isTerminal = false,
            isActiveExecution = false,
            isCapabilityOnly = false,
            requiresResultUplink = false,
            requiresStateUplink = true
        ),

        /**
         * Execution completed successfully. Terminal phase.
         *
         * A result has been returned and a RESULT signal (kind=COMPLETED) has been emitted.
         * Android MUST clear all in-flight task state after the uplink.
         *
         * Uplinks: RESULT (kind=COMPLETED), STATE.
         */
        COMPLETED(
            wireValue = "completed",
            isTerminal = true,
            isActiveExecution = false,
            isCapabilityOnly = false,
            requiresResultUplink = true,
            requiresStateUplink = true
        ),

        /**
         * Execution failed definitively. Terminal phase.
         *
         * An unrecoverable error, definitive timeout, or cancelled/exhausted execution
         * terminated without a successful result. A RESULT signal (FAILED/TIMEOUT/CANCELLED)
         * must have been emitted. Android MUST clear all in-flight task state.
         *
         * Uplinks: RESULT (kind=FAILED/TIMEOUT/CANCELLED), STATE.
         */
        FAILED(
            wireValue = "failed",
            isTerminal = true,
            isActiveExecution = false,
            isCapabilityOnly = false,
            requiresResultUplink = true,
            requiresStateUplink = true
        ),

        /**
         * Execution was rejected before the pipeline was entered. Terminal phase.
         *
         * Session gate or eligibility check failed; no execution pipeline was entered.
         * A RESULT signal (kind=REJECTED) is required so V2 can close the flow.
         *
         * Uplinks: RESULT (kind=REJECTED), STATE.
         */
        REJECTED(
            wireValue = "rejected",
            isTerminal = true,
            isActiveExecution = false,
            isCapabilityOnly = false,
            requiresResultUplink = true,
            requiresStateUplink = true
        ),

        /**
         * Sentinel for unknown or future phases.
         *
         * Consumers MUST NOT treat UNKNOWN as an error; it indicates a payload from a
         * newer version of Android or V2. Forward-compatibility sentinel only.
         */
        UNKNOWN(
            wireValue = "unknown",
            isTerminal = false,
            isActiveExecution = false,
            isCapabilityOnly = false,
            requiresResultUplink = false,
            requiresStateUplink = false
        );

        companion object {

            /** All phases that represent active or pending execution (not capability or terminal). */
            val EXECUTION_PHASES: Set<ExecutionLifecyclePhase> = setOf(
                PENDING, ACTIVATING, ACTIVE, DEGRADED, INTERRUPTED, TIMED_OUT, RETRYING
            )

            /** All terminal execution phases. */
            val TERMINAL_PHASES: Set<ExecutionLifecyclePhase> = setOf(
                COMPLETED, FAILED, REJECTED
            )

            /** All active execution phases (an Android component owns execution truth). */
            val ACTIVE_EXECUTION_PHASES: Set<ExecutionLifecyclePhase> = setOf(
                ACTIVE, DEGRADED
            )

            /** All phases that require a result uplink on entry. */
            val RESULT_UPLINK_REQUIRED_PHASES: Set<ExecutionLifecyclePhase> =
                entries.filter { it.requiresResultUplink }.toSet()

            /** All phases that require a state uplink on entry. */
            val STATE_UPLINK_REQUIRED_PHASES: Set<ExecutionLifecyclePhase> =
                entries.filter { it.requiresStateUplink }.toSet()

            /**
             * Parses [value] to an [ExecutionLifecyclePhase], returning [UNKNOWN] for
             * unrecognised or null inputs (forward-compatibility).
             */
            fun fromValue(value: String?): ExecutionLifecyclePhase =
                entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
        }
    }

    // ── Uplink requirement model ──────────────────────────────────────────────

    /**
     * Required uplink actions when transitioning between [ExecutionLifecyclePhase]s.
     *
     * @property resultUplinkRequired  True when a RESULT signal MUST be emitted.
     * @property stateUplinkRequired   True when a STATE snapshot MUST be published.
     * @property uplinkNote            Human-readable description of the uplink requirement.
     */
    data class UplinkRequirement(
        val resultUplinkRequired: Boolean,
        val stateUplinkRequired: Boolean,
        val uplinkNote: String
    )

    // ── Phase transition table ────────────────────────────────────────────────

    /**
     * Canonical execution lifecycle transition table.
     *
     * Key: pair of (from-phase, to-phase). Value: [UplinkRequirement] for that transition.
     *
     * Only valid transitions are listed. Callers MUST NOT perform transitions absent from
     * this table. An absent transition is a lifecycle governance violation.
     *
     * See [isValidTransition] and [uplinkRequirementFor] for guarded access.
     */
    val TRANSITION_TABLE: Map<Pair<ExecutionLifecyclePhase, ExecutionLifecyclePhase>, UplinkRequirement> = mapOf(

        // ── Normal execution path ─────────────────────────────────────────────

        (ExecutionLifecyclePhase.CAPABILITY_IDLE to ExecutionLifecyclePhase.PENDING) to UplinkRequirement(
            resultUplinkRequired = false,
            stateUplinkRequired = true,
            uplinkNote = "New execution accepted from idle; publish PENDING state to V2"
        ),
        (ExecutionLifecyclePhase.PENDING to ExecutionLifecyclePhase.ACTIVATING) to UplinkRequirement(
            resultUplinkRequired = false,
            stateUplinkRequired = true,
            uplinkNote = "Execution pipeline initialising; publish ACTIVATING state to V2"
        ),
        (ExecutionLifecyclePhase.ACTIVATING to ExecutionLifecyclePhase.ACTIVE) to UplinkRequirement(
            resultUplinkRequired = false,
            stateUplinkRequired = true,
            uplinkNote = "Pipeline running; publish ACTIVE state to V2 (ACK signal marks this entry)"
        ),
        (ExecutionLifecyclePhase.ACTIVE to ExecutionLifecyclePhase.COMPLETED) to UplinkRequirement(
            resultUplinkRequired = true,
            stateUplinkRequired = true,
            uplinkNote = "Active execution completed; emit RESULT(COMPLETED) and publish COMPLETED state"
        ),
        (ExecutionLifecyclePhase.DEGRADED to ExecutionLifecyclePhase.COMPLETED) to UplinkRequirement(
            resultUplinkRequired = true,
            stateUplinkRequired = true,
            uplinkNote = "Degraded execution completed; emit RESULT(COMPLETED) and publish state"
        ),

        // ── Degraded / fallback path ──────────────────────────────────────────

        (ExecutionLifecyclePhase.ACTIVE to ExecutionLifecyclePhase.DEGRADED) to UplinkRequirement(
            resultUplinkRequired = false,
            stateUplinkRequired = true,
            uplinkNote = "Execution fell back to degraded mode; publish DEGRADED state with fallback_tier"
        ),
        (ExecutionLifecyclePhase.DEGRADED to ExecutionLifecyclePhase.ACTIVE) to UplinkRequirement(
            resultUplinkRequired = false,
            stateUplinkRequired = true,
            uplinkNote = "Degraded execution resumed to full active mode; publish ACTIVE state"
        ),
        (ExecutionLifecyclePhase.DEGRADED to ExecutionLifecyclePhase.FAILED) to UplinkRequirement(
            resultUplinkRequired = true,
            stateUplinkRequired = true,
            uplinkNote = "Degraded execution failed definitively; emit RESULT(FAILED) and publish FAILED state"
        ),
        (ExecutionLifecyclePhase.ACTIVATING to ExecutionLifecyclePhase.DEGRADED) to UplinkRequirement(
            resultUplinkRequired = false,
            stateUplinkRequired = true,
            uplinkNote = "Pipeline entered degraded mode during activation; publish DEGRADED state"
        ),

        // ── Interruption path ─────────────────────────────────────────────────

        (ExecutionLifecyclePhase.ACTIVE to ExecutionLifecyclePhase.INTERRUPTED) to UplinkRequirement(
            resultUplinkRequired = true,
            stateUplinkRequired = true,
            uplinkNote = "Active execution interrupted (disconnect/cancel); emit RESULT(INTERRUPTED) with interruption_cause"
        ),
        (ExecutionLifecyclePhase.DEGRADED to ExecutionLifecyclePhase.INTERRUPTED) to UplinkRequirement(
            resultUplinkRequired = true,
            stateUplinkRequired = true,
            uplinkNote = "Degraded execution interrupted; emit RESULT(INTERRUPTED) and publish state"
        ),
        (ExecutionLifecyclePhase.ACTIVATING to ExecutionLifecyclePhase.INTERRUPTED) to UplinkRequirement(
            resultUplinkRequired = true,
            stateUplinkRequired = true,
            uplinkNote = "Activation interrupted before ACTIVE; emit RESULT(INTERRUPTED) and publish state"
        ),

        // ── Timeout path ──────────────────────────────────────────────────────

        (ExecutionLifecyclePhase.ACTIVE to ExecutionLifecyclePhase.TIMED_OUT) to UplinkRequirement(
            resultUplinkRequired = true,
            stateUplinkRequired = true,
            uplinkNote = "Active execution timed out; emit RESULT(TIMEOUT) and publish TIMED_OUT state"
        ),
        (ExecutionLifecyclePhase.DEGRADED to ExecutionLifecyclePhase.TIMED_OUT) to UplinkRequirement(
            resultUplinkRequired = true,
            stateUplinkRequired = true,
            uplinkNote = "Degraded execution timed out; emit RESULT(TIMEOUT) and publish state"
        ),
        (ExecutionLifecyclePhase.ACTIVATING to ExecutionLifecyclePhase.TIMED_OUT) to UplinkRequirement(
            resultUplinkRequired = true,
            stateUplinkRequired = true,
            uplinkNote = "Activation timed out before ACTIVE; emit RESULT(TIMEOUT) and publish state"
        ),
        (ExecutionLifecyclePhase.TIMED_OUT to ExecutionLifecyclePhase.FAILED) to UplinkRequirement(
            resultUplinkRequired = false,
            stateUplinkRequired = true,
            uplinkNote = "TIMED_OUT advances to terminal FAILED; publish FAILED state (RESULT already emitted)"
        ),

        // ── Retry path ────────────────────────────────────────────────────────

        (ExecutionLifecyclePhase.INTERRUPTED to ExecutionLifecyclePhase.RETRYING) to UplinkRequirement(
            resultUplinkRequired = false,
            stateUplinkRequired = true,
            uplinkNote = "Interrupted execution entering retry; publish RETRYING state with retry_attempt_count"
        ),
        (ExecutionLifecyclePhase.RETRYING to ExecutionLifecyclePhase.ACTIVATING) to UplinkRequirement(
            resultUplinkRequired = false,
            stateUplinkRequired = true,
            uplinkNote = "Retry attempt beginning activation; publish ACTIVATING state"
        ),
        (ExecutionLifecyclePhase.RETRYING to ExecutionLifecyclePhase.FAILED) to UplinkRequirement(
            resultUplinkRequired = true,
            stateUplinkRequired = true,
            uplinkNote = "Retry exhausted or denied; emit RESULT(FAILED) and publish FAILED state"
        ),
        (ExecutionLifecyclePhase.INTERRUPTED to ExecutionLifecyclePhase.FAILED) to UplinkRequirement(
            resultUplinkRequired = false,
            stateUplinkRequired = true,
            uplinkNote = "Interrupted execution deemed terminal (no retry); publish FAILED state (RESULT emitted at INTERRUPTED)"
        ),

        // ── Direct failure paths ──────────────────────────────────────────────

        (ExecutionLifecyclePhase.ACTIVE to ExecutionLifecyclePhase.FAILED) to UplinkRequirement(
            resultUplinkRequired = true,
            stateUplinkRequired = true,
            uplinkNote = "Active execution failed directly; emit RESULT(FAILED) and publish FAILED state"
        ),
        (ExecutionLifecyclePhase.ACTIVATING to ExecutionLifecyclePhase.FAILED) to UplinkRequirement(
            resultUplinkRequired = true,
            stateUplinkRequired = true,
            uplinkNote = "Activation failed (resource/model unavailable); emit RESULT(FAILED) and publish state"
        ),
        (ExecutionLifecyclePhase.PENDING to ExecutionLifecyclePhase.FAILED) to UplinkRequirement(
            resultUplinkRequired = true,
            stateUplinkRequired = true,
            uplinkNote = "Pending execution failed before activation; emit RESULT(FAILED) and publish state"
        ),

        // ── Rejection path ────────────────────────────────────────────────────

        (ExecutionLifecyclePhase.CAPABILITY_IDLE to ExecutionLifecyclePhase.REJECTED) to UplinkRequirement(
            resultUplinkRequired = true,
            stateUplinkRequired = true,
            uplinkNote = "Execution rejected at gate (no session/eligibility); emit RESULT(REJECTED) and publish state"
        ),
        (ExecutionLifecyclePhase.PENDING to ExecutionLifecyclePhase.REJECTED) to UplinkRequirement(
            resultUplinkRequired = true,
            stateUplinkRequired = true,
            uplinkNote = "Pending execution rejected (session closed/invalidated); emit RESULT(REJECTED) and publish state"
        ),

        // ── Terminal → capability idle return path ────────────────────────────

        (ExecutionLifecyclePhase.COMPLETED to ExecutionLifecyclePhase.CAPABILITY_IDLE) to UplinkRequirement(
            resultUplinkRequired = false,
            stateUplinkRequired = true,
            uplinkNote = "Completed execution cleared; return to CAPABILITY_IDLE and publish idle state"
        ),
        (ExecutionLifecyclePhase.FAILED to ExecutionLifecyclePhase.CAPABILITY_IDLE) to UplinkRequirement(
            resultUplinkRequired = false,
            stateUplinkRequired = true,
            uplinkNote = "Failed execution cleared; return to CAPABILITY_IDLE and publish idle state"
        ),
        (ExecutionLifecyclePhase.REJECTED to ExecutionLifecyclePhase.CAPABILITY_IDLE) to UplinkRequirement(
            resultUplinkRequired = false,
            stateUplinkRequired = true,
            uplinkNote = "Rejected execution cleared; return to CAPABILITY_IDLE and publish idle state"
        )
    )

    // ── Phase classification helpers ──────────────────────────────────────────

    /**
     * Returns `true` when [from] → [to] is a valid lifecycle transition.
     *
     * Callers MUST check this before performing any phase transition. An invalid transition
     * is a lifecycle governance violation.
     */
    fun isValidTransition(
        from: ExecutionLifecyclePhase,
        to: ExecutionLifecyclePhase
    ): Boolean = TRANSITION_TABLE.containsKey(from to to)

    /**
     * Returns the [UplinkRequirement] for the transition from [from] to [to],
     * or `null` when the transition is not in [TRANSITION_TABLE].
     */
    fun uplinkRequirementFor(
        from: ExecutionLifecyclePhase,
        to: ExecutionLifecyclePhase
    ): UplinkRequirement? = TRANSITION_TABLE[from to to]

    /**
     * Returns `true` when [phase] represents active execution ownership by an Android component.
     *
     * **Use this instead of ad-hoc phase checks.** Active execution means an Android component
     * owns execution truth for a task. Capability-only and transient phases are excluded.
     */
    fun isActiveExecution(phase: ExecutionLifecyclePhase): Boolean = phase.isActiveExecution

    /**
     * Returns `true` when [phase] is a terminal execution state.
     *
     * Terminal states indicate execution is definitively over. Android MUST NOT perform
     * further execution transitions (except to CAPABILITY_IDLE) from a terminal phase.
     */
    fun isTerminal(phase: ExecutionLifecyclePhase): Boolean = phase.isTerminal

    /**
     * Returns `true` when [phase] represents declared capability without active execution.
     *
     * CAPABILITY_IDLE MUST NOT be reported as active execution in any uplink.
     */
    fun isCapabilityOnly(phase: ExecutionLifecyclePhase): Boolean = phase.isCapabilityOnly

    /**
     * Returns the set of valid successor phases reachable from [phase].
     */
    fun validTransitionsFrom(phase: ExecutionLifecyclePhase): Set<ExecutionLifecyclePhase> =
        TRANSITION_TABLE.keys
            .filter { (from, _) -> from == phase }
            .map { (_, to) -> to }
            .toSet()

    // ── Wire key constants ────────────────────────────────────────────────────

    /** Wire key for the execution lifecycle phase in metadata maps and state uplink payloads. */
    const val KEY_EXECUTION_LIFECYCLE_PHASE = "execution_lifecycle_phase"

    /** Wire key for the retry attempt count in RETRYING state uplinks. Must be ≥ 1. */
    const val KEY_RETRY_ATTEMPT_COUNT = "retry_attempt_count"

    /** Wire key for the interruption cause in INTERRUPTED state uplinks. */
    const val KEY_INTERRUPTION_CAUSE = "interruption_cause"

    /** Wire key for the fallback tier used in DEGRADED execution state uplinks. */
    const val KEY_FALLBACK_TIER = "fallback_tier"

    /** Wire key for the degraded execution flag in DEGRADED state uplinks. */
    const val KEY_IS_DEGRADED_EXECUTION = "is_degraded_execution"

    // ── Interruption cause constants ──────────────────────────────────────────

    /** Interruption cause: WebSocket disconnected while execution was active. */
    const val INTERRUPTION_CAUSE_WS_DISCONNECT = "ws_disconnect"

    /** Interruption cause: execution was explicitly cancelled by the runtime or V2. */
    const val INTERRUPTION_CAUSE_EXPLICIT_CANCEL = "explicit_cancel"

    /** Interruption cause: process was killed by the OS while execution was active. */
    const val INTERRUPTION_CAUSE_PROCESS_KILL = "process_kill"

    /** Interruption cause: session was invalidated while execution was active. */
    const val INTERRUPTION_CAUSE_SESSION_INVALIDATION = "session_invalidation"

    // ── Fallback tier constants ───────────────────────────────────────────────

    /** Fallback tier: planner fell back to the degraded planner service. */
    const val FALLBACK_TIER_DEGRADED_PLANNER = "degraded_planner"

    /** Fallback tier: grounder fell back to the degraded grounding service. */
    const val FALLBACK_TIER_DEGRADED_GROUNDER = "degraded_grounder"

    /** Fallback tier: full local execution fell back to remote execution (bridge fallback). */
    const val FALLBACK_TIER_REMOTE_BRIDGE = "remote_bridge"

    // ── Lifecycle governance invariants ───────────────────────────────────────

    /**
     * Lifecycle governance invariants that MUST hold across all execution paths.
     *
     * These invariants are verified programmatically in
     * [com.ufo.galaxy.runtime.Pr85AndroidExecutionLifecycleHardeningTest].
     *
     * Any execution path that violates these invariants has a lifecycle hardening gap.
     */
    val LIFECYCLE_INVARIANTS: List<String> = listOf(
        "INV-01: A terminal phase (COMPLETED/FAILED/REJECTED) MUST NOT transition to any non-terminal, non-CAPABILITY_IDLE phase",
        "INV-02: A RESULT uplink MUST be emitted for every terminal phase entry (COMPLETED/FAILED/REJECTED)",
        "INV-03: A STATE uplink MUST be emitted for every non-CAPABILITY_IDLE phase transition",
        "INV-04: INTERRUPTED MUST only transition to RETRYING or FAILED — never directly back to ACTIVE",
        "INV-05: DEGRADED MUST NOT be reported as ACTIVE in capability advertisements or result uplinks",
        "INV-06: CAPABILITY_IDLE MUST NOT be reported as active execution in any uplink",
        "INV-07: Terminal phases MUST clear all in-flight task state before emitting the final STATE uplink",
        "INV-08: RETRYING MUST carry a non-zero retry_attempt_count in its STATE uplink",
        "INV-09: TIMED_OUT MUST emit RESULT(TIMEOUT) before transitioning to terminal FAILED",
        "INV-10: INTERRUPTED MUST emit a RESULT signal with interruption_cause before transitioning to RETRYING or FAILED",
        "INV-11: DEGRADED execution MUST include fallback_tier and is_degraded_execution=true in its STATE uplink",
        "INV-12: CAPABILITY_IDLE is the only phase that requires no uplink — all other phases require STATE uplink"
    )
}

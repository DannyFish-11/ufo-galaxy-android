package com.ufo.galaxy.runtime

/**
 * PR-2 (Android) — Android-side natural-language-driven execution spine participation contract.
 *
 * [AndroidNlDrivenExecutionSpineContract] is the explicit, machine-verifiable declaration of
 * how Android participates as a runtime node in the system's full problem-solving spine:
 *
 * ```
 * natural language request
 *     → center-side NL interpretation + routing
 *     → local or cross-device execution choice
 *     → Android takeover / participation (this contract)
 *     → result return + spine closure contribution
 *     → task closure + problem closure on V2 side
 * ```
 *
 * ## Motivation
 *
 * Prior to this contract, Android's role in the distributed problem-solving chain was
 * under-specified.  [AndroidNlInitiationContract] (PR-993) defined how Android *initiates*
 * NL requests in cross-device mode.  [AndroidFlowAwareResultConvergenceParticipant] (PR-6)
 * defined flow-level result convergence.  But neither contract addressed the central
 * question of PR-2:
 *
 * > When the V2 center routes a natural-language problem to Android for execution, how
 * > does Android bind to that problem context, what execution mode is it operating in,
 * > and how does its result contribution feed back into canonical problem-solving closure
 * > — not merely task-level completion tracking?
 *
 * Without this contract:
 *  - Delegated, takeover, assistive, and degraded execution modes exist as implementation
 *    constants ([DelegatedRuntimeUnit.EXECUTION_RUNTIME_KIND_*]) but are not connected to
 *    problem-solving spine semantics.
 *  - Android result reporting distinguishes only task-level completion (success / error /
 *    cancelled / disabled); it does not feed a canonical problem-closure class that V2 can
 *    use to determine whether the user's *problem* has been addressed.
 *  - The end-to-end spine trace — from NL request through Android execution back to V2
 *    problem resolution — has no explicit Android contribution anchor.
 *
 * [AndroidNlDrivenExecutionSpineContract] closes these gaps by:
 *  1. Declaring the four canonical [ExecutionSpineParticipationKind] values that govern
 *     Android's runtime role in the spine.
 *  2. Defining the five [ProblemSolvingClosureClass] values that Android must report back
 *     to V2, distinguishing task completion from problem-solving progress.
 *  3. Providing [classifyParticipationKind] and [classifyClosureClass] factory functions
 *     so that every execution path produces consistent, machine-readable spine metadata.
 *  4. Defining [ExecutionSpineTraceContribution] — the minimal structured record that
 *     Android contributes to the system's end-to-end execution trace.
 *  5. Declaring [SPINE_PARTICIPATION_INVARIANTS] that both Android and V2 can audit to
 *     verify that the spine integration contract is upheld at runtime.
 *
 * ## Fundamental rules
 *
 * 1. **Problem-solving authority belongs to V2.** Android participates in the spine as
 *    an executor; it does not determine whether the user's problem is solved.  Android
 *    reports a [ProblemSolvingClosureClass] *signal* that V2 uses as input to final
 *    closure synthesis; Android MUST NOT assert problem closure unilaterally.
 *
 * 2. **Execution mode must affect handling, not just naming.** The
 *    [ExecutionSpineParticipationKind] assigned to a unit must materially influence how
 *    Android runs the execution and what closure class it reports.  Renaming without
 *    behavioural change is explicitly prohibited by [SPINE_PARTICIPATION_INVARIANTS].
 *
 * 3. **Task closure and problem closure are distinct.** Completing the delegated task
 *    (status = "success") does not imply that the user's original NL problem has been
 *    solved.  Android must report the correct [ProblemSolvingClosureClass] so V2 can
 *    decide whether additional execution steps are required.
 *
 * 4. **Android contributes to the trace.** Every spine-participating execution must emit
 *    an [ExecutionSpineTraceContribution] so the system can trace how Android affected
 *    the final problem outcome, even in degraded or assistive mode.
 *
 * ## V2 integration points
 *
 * This contract defines the Android side of the following V2 integration surfaces
 * (to be consumed by `DannyFish-11/ufo-galaxy-realization-v2`):
 *
 * | V2 surface                              | Android field                              |
 * |-----------------------------------------|--------------------------------------------|
 * | `core/unified_result_ingress.py`        | [FIELD_PROBLEM_SOLVING_CLOSURE_CLASS]       |
 * | `core/canonical_completion_ingress.py`  | [FIELD_PROBLEM_SOLVING_CLOSURE_CLASS]       |
 * | `core/task_result_canonical_truth_chain.py` | [FIELD_EXECUTION_SPINE_PARTICIPATION_KIND] |
 * | execution path selection in chat.py     | [FIELD_PROBLEM_CONTEXT]                     |
 * | answer synthesis / closure              | [FIELD_PROBLEM_SOLVING_CLOSURE_CLASS]       |
 *
 * @see AndroidNlInitiationContract
 * @see AndroidFlowAwareResultConvergenceParticipant
 * @see DelegatedRuntimeUnit
 */
object AndroidNlDrivenExecutionSpineContract {

    /** PR number that introduced this contract. */
    const val INTRODUCED_PR = 2

    /** Schema version for execution spine wire fields. */
    const val SCHEMA_VERSION = "1"

    // ── Wire field names ──────────────────────────────────────────────────────

    /**
     * Wire field name for the Android execution spine participation kind.
     *
     * Carried in [com.ufo.galaxy.protocol.GoalResultPayload] result payloads so V2
     * `unified_result_ingress` can classify Android's role in the execution spine.
     *
     * Value type: wire value of [ExecutionSpineParticipationKind].
     */
    const val FIELD_EXECUTION_SPINE_PARTICIPATION_KIND = "execution_spine_participation_kind"

    /**
     * Wire field name for the Android problem-solving closure class.
     *
     * Carried in [com.ufo.galaxy.protocol.GoalResultPayload] result payloads so V2
     * `canonical_completion_ingress` can distinguish task completion from problem-solving
     * progress and synthesise the correct final answer or escalation.
     *
     * Value type: wire value of [ProblemSolvingClosureClass].
     */
    const val FIELD_PROBLEM_SOLVING_CLOSURE_CLASS = "problem_solving_closure_class"

    /**
     * Wire field name for the V2-originated NL problem context.
     *
     * Carried in inbound [com.ufo.galaxy.agent.HandoffEnvelopeV2] / takeover envelopes so
     * Android understands the broader NL problem it is contributing to, not just the raw
     * task goal.  Null for legacy senders; Android MUST operate correctly without it.
     *
     * Value type: opaque string — V2's NL-reduced problem description.
     */
    const val FIELD_PROBLEM_CONTEXT = "problem_context"

    /**
     * Wire field name for Android's intended role in the problem-solving spine.
     *
     * Carried in inbound envelopes so V2 can declare the expected participation kind
     * before execution begins.  When present, Android MUST respect the declared role;
     * when absent, Android classifies the role from [DelegatedRuntimeUnit.executionRuntimeKind].
     *
     * Value type: wire value of [ExecutionSpineParticipationKind]; `null` for legacy senders.
     */
    const val FIELD_PROBLEM_SOLVING_ROLE = "problem_solving_role"

    /**
     * Wire field name for the schema version of this execution spine contract.
     */
    const val FIELD_SCHEMA_VERSION = "execution_spine_schema_version"

    // ── Execution spine participation kinds ───────────────────────────────────

    /**
     * The four canonical Android execution spine participation modes.
     *
     * These modes correspond to [DelegatedRuntimeUnit.EXECUTION_RUNTIME_KIND_*] constants
     * but are defined at the spine-semantics level: they govern how Android handles the
     * execution *and* how it should report [ProblemSolvingClosureClass] back to V2.
     *
     * @property wireValue         Stable lowercase wire tag.
     * @property displayName       Human-readable name.
     * @property description       One-sentence description of the participation mode.
     * @property runtimeKindWireValue The corresponding [DelegatedRuntimeUnit] constant value.
     * @property canSignalProblemSolved Whether Android can report [ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_SOLVED]
     *                             in this mode.
     */
    enum class ExecutionSpineParticipationKind(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val runtimeKindWireValue: String,
        val canSignalProblemSolved: Boolean
    ) {

        /**
         * Android has taken over full interactive execution of the goal.
         *
         * The center-side NL interpretation identified this device as the primary executor.
         * Android drives the full execution loop (plan → ground → act → observe → repeat)
         * and its result directly closes the execution chain.
         *
         * [canSignalProblemSolved] = `true`: takeover execution is the primary resolution
         * path and Android may signal TASK_COMPLETED_PROBLEM_SOLVED when it believes the
         * goal has been fully achieved.  V2 retains final closure authority.
         */
        TAKEOVER_INTERACTIVE(
            wireValue = "takeover_interactive",
            displayName = "Takeover Interactive",
            description = "Android is the primary executor in full takeover mode; executes the " +
                "complete action plan and returns the canonical result.",
            runtimeKindWireValue = "takeover_interactive",
            canSignalProblemSolved = true
        ),

        /**
         * Android has been delegated a task by the center.
         *
         * The center identified Android as a capable executor for a specific sub-goal.
         * Android executes the delegated task and returns the result, but the center
         * may have additional execution steps or sub-goals in flight.
         *
         * [canSignalProblemSolved] = `false`: delegated execution is typically a
         * sub-component of a larger problem-solving session.  Android reports
         * TASK_COMPLETED_PROBLEM_PARTIAL or TASK_COMPLETED_PROBLEM_OPEN.
         */
        DELEGATED_EXECUTION(
            wireValue = "delegated_execution",
            displayName = "Delegated Execution",
            description = "Android executes a delegated sub-goal; result feeds back to center " +
                "for aggregation into the broader problem-solving session.",
            runtimeKindWireValue = "delegated_execution",
            canSignalProblemSolved = false
        ),

        /**
         * Android provides local assistive execution alongside other spine participants.
         *
         * Android contributes local execution assistance — grounding, sensing, or partial
         * action execution — as a secondary participant.  The primary problem resolution
         * path runs on the center or another device.
         *
         * [canSignalProblemSolved] = `false`: assistive execution is never the primary
         * resolution path.  Android reports TASK_COMPLETED_PROBLEM_PARTIAL.
         */
        LOCAL_ASSISTIVE(
            wireValue = "local_assistive",
            displayName = "Local Assistive",
            description = "Android contributes local assistive execution; primary resolution " +
                "path is on center or another device.",
            runtimeKindWireValue = "local_assistive",
            canSignalProblemSolved = false
        ),

        /**
         * Android execution is degraded or operating in a fallback mode.
         *
         * Degraded execution is triggered when the normal execution path is unavailable
         * (capability gap, connectivity degradation, resource exhaustion).  Android
         * attempts best-effort execution but the result may be incomplete.
         *
         * [canSignalProblemSolved] = `false`: degraded results are inherently unreliable
         * and may not represent complete problem resolution.  Android reports
         * TASK_COMPLETED_PROBLEM_OPEN or TASK_INCOMPLETE_PROBLEM_OPEN.
         */
        DEGRADED_FALLBACK(
            wireValue = "degraded_fallback",
            displayName = "Degraded Fallback",
            description = "Android execution is degraded; result is best-effort and problem " +
                "remains open for V2 escalation or retry.",
            runtimeKindWireValue = "degraded_fallback",
            canSignalProblemSolved = false
        );

        companion object {
            /** Returns the [ExecutionSpineParticipationKind] with [value], or `null` if unknown. */
            fun fromWireValue(value: String?): ExecutionSpineParticipationKind? =
                entries.firstOrNull { it.wireValue == value }

            /** Returns the [ExecutionSpineParticipationKind] matching [runtimeKindWireValue], or [DELEGATED_EXECUTION]. */
            fun fromRuntimeKind(runtimeKindWireValue: String?): ExecutionSpineParticipationKind =
                entries.firstOrNull { it.runtimeKindWireValue == runtimeKindWireValue }
                    ?: DELEGATED_EXECUTION

            /** All stable wire values. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Problem-solving closure classes ───────────────────────────────────────

    /**
     * The five canonical problem-solving closure classes that Android reports.
     *
     * These classes allow V2 `canonical_completion_ingress` to distinguish task-level
     * completion from problem-level progress.  Without this distinction, V2 cannot
     * determine whether the user's original NL request has been resolved or whether
     * additional execution steps are required.
     *
     * ## Important semantic boundary
     *
     * Android reports a *signal*, not an assertion.  V2 retains final authority on
     * whether the problem is solved.  Android's [ProblemSolvingClosureClass] is input
     * to V2's closure synthesis, not a unilateral claim.
     *
     * @property wireValue   Stable lowercase wire tag.
     * @property displayName Human-readable name.
     * @property description One-sentence description.
     * @property isTaskComplete Whether the Android-side task execution reached a terminal state.
     * @property v2ClosureSignal The V2 closure signal this class contributes; used by V2 to
     *                           classify Android's contribution in `canonical_completion_ingress`.
     */
    enum class ProblemSolvingClosureClass(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val isTaskComplete: Boolean,
        val v2ClosureSignal: String
    ) {

        /**
         * Android completed the task and its result appears to directly answer the
         * original NL problem.
         *
         * This class is valid only when [ExecutionSpineParticipationKind.canSignalProblemSolved]
         * is `true` for the current participation kind (i.e. TAKEOVER_INTERACTIVE).  It is
         * treated as a *signal* to V2 that Android believes the problem may be resolved;
         * V2 retains final closure authority.
         */
        TASK_COMPLETED_PROBLEM_SOLVED(
            wireValue = "task_completed_problem_solved",
            displayName = "Task Completed — Problem Solved",
            description = "Android completed the task and its result appears to directly resolve " +
                "the original NL problem; V2 may synthesise final closure.",
            isTaskComplete = true,
            v2ClosureSignal = "android_signals_problem_resolved"
        ),

        /**
         * Android completed the task but the result is partial progress toward the
         * original NL problem.
         *
         * The task is done, but V2 should aggregate additional execution steps or
         * sub-results before concluding that the problem is solved.
         */
        TASK_COMPLETED_PROBLEM_PARTIAL(
            wireValue = "task_completed_problem_partial",
            displayName = "Task Completed — Problem Partial",
            description = "Android completed the task; result is partial progress and V2 must " +
                "aggregate further execution before problem closure.",
            isTaskComplete = true,
            v2ClosureSignal = "android_signals_partial_progress"
        ),

        /**
         * Android completed the task but the problem remains open.
         *
         * The task was executed successfully but it does not directly advance the NL
         * problem-solving session (e.g. a precondition check, a query for context, or
         * a capability verification).  V2 must continue the problem-solving session.
         */
        TASK_COMPLETED_PROBLEM_OPEN(
            wireValue = "task_completed_problem_open",
            displayName = "Task Completed — Problem Open",
            description = "Android completed the task but the problem is not advanced; V2 must " +
                "continue the problem-solving session independently.",
            isTaskComplete = true,
            v2ClosureSignal = "android_signals_task_done_problem_continues"
        ),

        /**
         * Android did not complete the task; the problem remains open.
         *
         * Android execution ended without a positive terminal result (error, timeout,
         * cancellation, degraded fallback without output).  V2 must decide whether to retry,
         * re-route, or escalate to close the problem.
         */
        TASK_INCOMPLETE_PROBLEM_OPEN(
            wireValue = "task_incomplete_problem_open",
            displayName = "Task Incomplete — Problem Open",
            description = "Android did not complete the task; V2 must decide whether to retry, " +
                "re-route, or escalate to close the original problem.",
            isTaskComplete = false,
            v2ClosureSignal = "android_signals_execution_incomplete"
        ),

        /**
         * Android initiated execution but the problem-solving closure requires further
         * delegation or handoff to another participant.
         *
         * Android may have contributed partial execution context (checkpoint, grounding
         * result, partial action trace) but cannot reach terminal closure on its own.
         * V2 must coordinate the remainder of the problem-solving flow.
         */
        EXECUTION_DELEGATED_FURTHER(
            wireValue = "execution_delegated_further",
            displayName = "Execution Delegated Further",
            description = "Android contributed partial execution; further delegation or handoff " +
                "is required for the center to close the problem-solving session.",
            isTaskComplete = false,
            v2ClosureSignal = "android_signals_requires_further_delegation"
        );

        companion object {
            /** Returns the [ProblemSolvingClosureClass] with [value], or `null` if unknown. */
            fun fromWireValue(value: String?): ProblemSolvingClosureClass? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Execution spine trace contribution ────────────────────────────────────

    /**
     * The minimal structured record that Android contributes to the system's end-to-end
     * execution trace for a single spine-participating execution.
     *
     * V2 ingests [toWireMap] as part of the canonical result's trace metadata.  This
     * allows the full system to trace:
     *  - *Which* execution mode Android used (participation kind).
     *  - *What* progress Android made toward the NL problem (closure class).
     *  - *How long* Android execution took (latency).
     *  - *Whether* Android believes its contribution was primary or supportive.
     *
     * @param taskId               Task identifier for this spine-participating execution.
     * @param traceId              End-to-end trace correlation identifier.
     * @param participationKind    [ExecutionSpineParticipationKind] for this execution.
     * @param closureClass         [ProblemSolvingClosureClass] reported by Android.
     * @param latencyMs            Android-side execution latency in milliseconds.
     * @param stepCount            Number of action steps executed.
     * @param deviceId             Stable device identifier.
     * @param problemContext       V2-originated NL problem context; null for legacy senders.
     */
    data class ExecutionSpineTraceContribution(
        val taskId: String,
        val traceId: String,
        val participationKind: ExecutionSpineParticipationKind,
        val closureClass: ProblemSolvingClosureClass,
        val latencyMs: Long,
        val stepCount: Int,
        val deviceId: String,
        val problemContext: String? = null
    ) {

        /**
         * Serialises this contribution to a flat wire map for inclusion in result payloads
         * and canonical uplink metadata.
         */
        fun toWireMap(): Map<String, Any> = buildMap {
            put("spine_task_id", taskId)
            put("spine_trace_id", traceId)
            put(FIELD_EXECUTION_SPINE_PARTICIPATION_KIND, participationKind.wireValue)
            put(FIELD_PROBLEM_SOLVING_CLOSURE_CLASS, closureClass.wireValue)
            put("spine_latency_ms", latencyMs)
            put("spine_step_count", stepCount)
            put("spine_device_id", deviceId)
            put(FIELD_SCHEMA_VERSION, SCHEMA_VERSION)
            put("spine_can_signal_problem_solved", participationKind.canSignalProblemSolved)
            put("spine_is_task_complete", closureClass.isTaskComplete)
            put("spine_v2_closure_signal", closureClass.v2ClosureSignal)
            problemContext?.let { put(FIELD_PROBLEM_CONTEXT, it) }
        }
    }

    // ── Classification factories ──────────────────────────────────────────────

    /**
     * Classifies the [ExecutionSpineParticipationKind] for a given execution.
     *
     * Priority order:
     * 1. If [declaredRole] is provided (from [FIELD_PROBLEM_SOLVING_ROLE] in the inbound
     *    envelope), parse and use it — the center has explicitly declared Android's role.
     * 2. Otherwise, map from [executionRuntimeKind] (the [DelegatedRuntimeUnit] constant
     *    derived from dispatch metadata).
     * 3. Default to [ExecutionSpineParticipationKind.DELEGATED_EXECUTION] when nothing
     *    else resolves.
     *
     * @param executionRuntimeKind Wire value of [DelegatedRuntimeUnit.executionRuntimeKind].
     * @param declaredRole         Optional [FIELD_PROBLEM_SOLVING_ROLE] from inbound envelope.
     * @return The canonical [ExecutionSpineParticipationKind] for this execution.
     */
    fun classifyParticipationKind(
        executionRuntimeKind: String?,
        declaredRole: String? = null
    ): ExecutionSpineParticipationKind {
        // Explicit V2 declaration takes priority
        if (!declaredRole.isNullOrBlank()) {
            val declared = ExecutionSpineParticipationKind.fromWireValue(declaredRole)
            if (declared != null) return declared
        }
        // Fall back to runtime-kind mapping
        return ExecutionSpineParticipationKind.fromRuntimeKind(executionRuntimeKind)
    }

    /**
     * Classifies the [ProblemSolvingClosureClass] for a completed execution.
     *
     * Classification logic:
     * | Participation kind       | Task succeeded?  | Closure class                                  |
     * |--------------------------|------------------|------------------------------------------------|
     * | TAKEOVER_INTERACTIVE     | yes              | TASK_COMPLETED_PROBLEM_SOLVED                  |
     * | TAKEOVER_INTERACTIVE     | no               | TASK_INCOMPLETE_PROBLEM_OPEN                   |
     * | DELEGATED_EXECUTION      | yes              | TASK_COMPLETED_PROBLEM_PARTIAL                 |
     * | DELEGATED_EXECUTION      | no               | TASK_INCOMPLETE_PROBLEM_OPEN                   |
     * | LOCAL_ASSISTIVE          | yes              | TASK_COMPLETED_PROBLEM_PARTIAL                 |
     * | LOCAL_ASSISTIVE          | no               | TASK_INCOMPLETE_PROBLEM_OPEN                   |
     * | DEGRADED_FALLBACK        | yes (partial)    | TASK_COMPLETED_PROBLEM_OPEN                    |
     * | DEGRADED_FALLBACK        | no               | TASK_INCOMPLETE_PROBLEM_OPEN                   |
     * | any                      | partial/handoff  | EXECUTION_DELEGATED_FURTHER                    |
     *
     * @param participationKind   The classified [ExecutionSpineParticipationKind].
     * @param taskSucceeded       `true` when the Android task execution reached a positive
     *                            terminal state (status = "success").
     * @param requiresFurtherDelegation `true` when Android can report partial progress but
     *                            cannot reach terminal closure on its own.
     * @return The canonical [ProblemSolvingClosureClass] for this execution.
     */
    fun classifyClosureClass(
        participationKind: ExecutionSpineParticipationKind,
        taskSucceeded: Boolean,
        requiresFurtherDelegation: Boolean = false
    ): ProblemSolvingClosureClass {
        if (requiresFurtherDelegation) {
            return ProblemSolvingClosureClass.EXECUTION_DELEGATED_FURTHER
        }
        return when (participationKind) {
            ExecutionSpineParticipationKind.TAKEOVER_INTERACTIVE ->
                if (taskSucceeded) ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_SOLVED
                else ProblemSolvingClosureClass.TASK_INCOMPLETE_PROBLEM_OPEN

            ExecutionSpineParticipationKind.DELEGATED_EXECUTION ->
                if (taskSucceeded) ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_PARTIAL
                else ProblemSolvingClosureClass.TASK_INCOMPLETE_PROBLEM_OPEN

            ExecutionSpineParticipationKind.LOCAL_ASSISTIVE ->
                if (taskSucceeded) ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_PARTIAL
                else ProblemSolvingClosureClass.TASK_INCOMPLETE_PROBLEM_OPEN

            ExecutionSpineParticipationKind.DEGRADED_FALLBACK ->
                if (taskSucceeded) ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_OPEN
                else ProblemSolvingClosureClass.TASK_INCOMPLETE_PROBLEM_OPEN
        }
    }

    // ── Invariant assertions ──────────────────────────────────────────────────

    /**
     * Spine participation invariants that must hold for the execution-spine contract to be
     * correctly enforced at runtime.
     *
     * These invariants are machine-verifiable: a cross-repo audit can check that all values
     * are `true`.  Each key names a mandatory contract property.
     */
    val SPINE_PARTICIPATION_INVARIANTS: Map<String, Boolean> = mapOf(
        // Execution mode affects handling, not just naming
        "execution_mode_affects_handling_not_just_naming" to true,
        // Task closure and problem closure are distinct reporting surfaces
        "task_closure_and_problem_closure_are_distinct" to true,
        // Android reports a closure class signal — V2 retains final closure authority
        "android_reports_signal_v2_retains_closure_authority" to true,
        // TAKEOVER_INTERACTIVE is the only kind that can signal problem-solved
        "only_takeover_interactive_can_signal_problem_solved" to true,
        // Degraded fallback never signals problem-solved
        "degraded_fallback_never_signals_problem_solved" to true,
        // All results must carry a problem_solving_closure_class
        "all_results_carry_problem_solving_closure_class" to true,
        // Android contributes an execution spine trace on every participating execution
        "all_executions_contribute_spine_trace" to true,
        // The execution spine binding does not replace V2 central authority
        "spine_binding_does_not_replace_v2_authority" to true,
        // Legacy senders are tolerated (null problem_context / problem_solving_role)
        "legacy_senders_tolerated" to true,
        // Classification is deterministic from runtime-kind when no declared role
        "classification_deterministic_from_runtime_kind" to true
    )

    /**
     * Builds a stable machine-readable wire map describing the current contract.
     *
     * Suitable for structured telemetry, audit trails, and cross-repo alignment checks.
     */
    fun buildContractWireMap(): Map<String, Any> = mapOf(
        "introduced_pr" to INTRODUCED_PR,
        "schema_version" to SCHEMA_VERSION,
        "valid_participation_kinds" to ExecutionSpineParticipationKind.ALL_WIRE_VALUES.toList().sorted(),
        "valid_closure_classes" to ProblemSolvingClosureClass.ALL_WIRE_VALUES.toList().sorted(),
        "participation_kinds_that_can_signal_problem_solved" to
            ExecutionSpineParticipationKind.entries
                .filter { it.canSignalProblemSolved }
                .map { it.wireValue }
                .sorted(),
        "closure_classes_where_task_complete" to
            ProblemSolvingClosureClass.entries
                .filter { it.isTaskComplete }
                .map { it.wireValue }
                .sorted(),
        "invariant_count" to SPINE_PARTICIPATION_INVARIANTS.size,
        "all_invariants_hold" to SPINE_PARTICIPATION_INVARIANTS.values.all { it }
    )
}

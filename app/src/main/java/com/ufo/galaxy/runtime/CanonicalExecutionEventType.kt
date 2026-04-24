package com.ufo.galaxy.runtime

/**
 * PR-7 (Android) — Enumeration of canonical Android-side execution event types for
 * flow-level operator visibility.
 *
 * [CanonicalExecutionEventType] defines the complete taxonomy of structured execution
 * events that Android emits during delegated flow execution.  Each value corresponds to
 * a named phase transition or milestone in the Android-side delegated runtime pipeline
 * and maps to a stable [wireValue] string used in V2 uplink payloads and operator inspect
 * surfaces.
 *
 * ## Event families
 *
 * ### Planning events
 * Emitted by [com.ufo.galaxy.loop.LoopController] during the plan-inference phase:
 *  - [PLANNING_STARTED]   — planner has begun inference for the current goal.
 *  - [PLANNING_PROGRESS]  — intermediate planning step completed (e.g. fallback attempt).
 *  - [PLANNING_BLOCKED]   — planning cannot proceed (model unavailable, inference error).
 *
 * ### Grounding events
 * Emitted by [com.ufo.galaxy.loop.LoopController] / [com.ufo.galaxy.agent.EdgeExecutor]
 * during coordinate-resolution / SeeClick grounding:
 *  - [GROUNDING_STARTED]  — grounding stage has begun for the current action step.
 *  - [GROUNDING_PROGRESS] — grounding step progressed (e.g. fallback ladder advance).
 *  - [GROUNDING_BLOCKED]  — grounding cannot resolve a target (coordinate failure).
 *
 * ### Execution events
 * Emitted by [com.ufo.galaxy.loop.LoopController] / [com.ufo.galaxy.agent.AutonomousExecutionPipeline]
 * during action dispatch and post-action observation:
 *  - [EXECUTION_STARTED]  — execution phase has begun for this delegated flow.
 *  - [EXECUTION_PROGRESS] — an execution step completed and observation was recorded.
 *  - [EXECUTION_BLOCKED]  — execution is blocked by a policy gate or system condition.
 *
 * ### Replan / stagnation events
 * Emitted by [com.ufo.galaxy.loop.LoopController] when the loop must recover:
 *  - [REPLAN_TRIGGERED]      — loop detected a need to re-invoke the planner.
 *  - [STAGNATION_DETECTED]   — stagnation detector fired; execution is not making progress.
 *
 * ### Gate decision events
 * Emitted by [com.ufo.galaxy.agent.AutonomousExecutionPipeline] / runtime gate layers:
 *  - [GATE_DECISION_EMITTED] — a policy or readiness gate evaluated and produced a decision.
 *
 * ### Takeover / collaboration / parallel milestones
 * Emitted by [com.ufo.galaxy.agent.DelegatedTakeoverExecutor],
 * [com.ufo.galaxy.agent.LocalCollaborationAgent], and the parallel-subtask path:
 *  - [TAKEOVER_MILESTONE]      — a named lifecycle milestone in the takeover execution path.
 *  - [COLLABORATION_MILESTONE] — a named lifecycle milestone in the local collaboration path.
 *  - [PARALLEL_MILESTONE]      — a named lifecycle milestone in the parallel subtask path.
 *
 * @property wireValue  Stable lowercase string used in JSON payloads and operator surfaces.
 *
 * @see AndroidCanonicalExecutionEventOwner
 * @see CanonicalExecutionEvent
 */
enum class CanonicalExecutionEventType(val wireValue: String) {

    // ── Planning events ───────────────────────────────────────────────────────

    /**
     * The local planner has begun inference for the current goal.
     *
     * Emitted at the start of each planning cycle in
     * [com.ufo.galaxy.loop.LoopController].  Lets the operator know that Android is
     * actively generating a plan rather than being idle.
     */
    PLANNING_STARTED("planning_started"),

    /**
     * An intermediate planning step completed.
     *
     * Emitted when the planner makes incremental progress (e.g. advances through
     * [com.ufo.galaxy.loop.PlannerFallbackLadder] stages or produces a partial plan).
     * Should not be emitted for every minor inference token — only at meaningful
     * planning checkpoints.
     */
    PLANNING_PROGRESS("planning_progress"),

    /**
     * Planning cannot proceed for the current goal.
     *
     * Emitted when the planner returns an unrecoverable block condition — for example
     * when all fallback options are exhausted or the model is unavailable.  The
     * [CanonicalExecutionEvent.blockingRationale] field should explain why planning is
     * blocked.
     */
    PLANNING_BLOCKED("planning_blocked"),

    // ── Grounding events ──────────────────────────────────────────────────────

    /**
     * Grounding (coordinate resolution / SeeClick) has begun for the current action step.
     *
     * Emitted when the executor attempts to resolve an abstract action intent to a
     * concrete screen coordinate.
     */
    GROUNDING_STARTED("grounding_started"),

    /**
     * A grounding step progressed.
     *
     * Emitted when the grounding fallback ladder advances to the next stage or when an
     * intermediate coordinate resolution attempt completes.
     */
    GROUNDING_PROGRESS("grounding_progress"),

    /**
     * Grounding cannot resolve the target for the current action step.
     *
     * Emitted when all grounding strategies have failed and the step cannot proceed.
     * The [CanonicalExecutionEvent.blockingRationale] field should name the grounding
     * failure cause.
     */
    GROUNDING_BLOCKED("grounding_blocked"),

    // ── Execution events ──────────────────────────────────────────────────────

    /**
     * Execution has started for this delegated flow.
     *
     * Emitted by [com.ufo.galaxy.agent.AutonomousExecutionPipeline] when the pipeline
     * accepts and begins running the goal.  The operator can now see that Android has
     * transitioned from activation to active execution.
     */
    EXECUTION_STARTED("execution_started"),

    /**
     * An execution step completed and post-action observation was recorded.
     *
     * Emitted after each successful action dispatch and post-action screenshot/observation
     * cycle in [com.ufo.galaxy.loop.LoopController].  Carries [CanonicalExecutionEvent.stepIndex]
     * so the operator can track forward progress.
     */
    EXECUTION_PROGRESS("execution_progress"),

    /**
     * Execution is blocked by a policy gate or system condition.
     *
     * Emitted when the execution pipeline cannot proceed — for example when a
     * [com.ufo.galaxy.agent.TakeoverEligibilityAssessor] gate returns false, when the
     * session is terminating, or when a system-level condition prevents dispatch.  The
     * [CanonicalExecutionEvent.gateRationale] field should name the blocking gate.
     */
    EXECUTION_BLOCKED("execution_blocked"),

    // ── Replan / stagnation events ────────────────────────────────────────────

    /**
     * The loop controller detected a need to re-invoke the planner.
     *
     * Emitted when [com.ufo.galaxy.loop.LoopController] decides to abandon the current
     * plan and generate a new one — for example when step-level retry exhaustion triggers
     * re-planning or when the post-action observation signals that the plan is no longer
     * valid.  The [CanonicalExecutionEvent.replanReason] field should explain the trigger.
     */
    REPLAN_TRIGGERED("replan_triggered"),

    /**
     * The stagnation detector has fired.
     *
     * Emitted when [com.ufo.galaxy.local.StagnationDetector] determines that the
     * execution is not making progress — for example repeated identical actions or
     * absence of UI change across consecutive steps.  The
     * [CanonicalExecutionEvent.stagnationReason] field should name the stagnation pattern.
     */
    STAGNATION_DETECTED("stagnation_detected"),

    // ── Gate decision events ──────────────────────────────────────────────────

    /**
     * A policy or readiness gate evaluated and produced a decision.
     *
     * Emitted by [com.ufo.galaxy.agent.AutonomousExecutionPipeline],
     * [com.ufo.galaxy.agent.TakeoverEligibilityAssessor], or any runtime gate layer when
     * a gate evaluation concludes.  The [CanonicalExecutionEvent.gateRationale] field
     * should name the gate and its outcome.
     */
    GATE_DECISION_EMITTED("gate_decision_emitted"),

    // ── Takeover / collaboration / parallel milestones ────────────────────────

    /**
     * A named lifecycle milestone in the takeover execution path.
     *
     * Emitted by [com.ufo.galaxy.agent.DelegatedTakeoverExecutor] at key lifecycle
     * transitions: ACK accepted, execution started, takeover active, result produced.
     * The [CanonicalExecutionEvent.reason] field should name the milestone.
     */
    TAKEOVER_MILESTONE("takeover_milestone"),

    /**
     * A named lifecycle milestone in the local collaboration path.
     *
     * Emitted by [com.ufo.galaxy.agent.LocalCollaborationAgent] at key collaboration
     * state transitions.  The [CanonicalExecutionEvent.reason] field should name the
     * milestone.
     */
    COLLABORATION_MILESTONE("collaboration_milestone"),

    /**
     * A named lifecycle milestone in the parallel subtask execution path.
     *
     * Emitted by the parallel subtask execution path when a parallel arm reaches a key
     * milestone (started, result produced, arm cancelled).  The
     * [CanonicalExecutionEvent.reason] field should name the milestone and may carry
     * subtask identity context.
     */
    PARALLEL_MILESTONE("parallel_milestone");

    companion object {

        /**
         * Parses [value] to a [CanonicalExecutionEventType], returning [DEFAULT] for
         * unknown or null inputs.
         *
         * @param value  Wire string from a JSON payload; may be `null`.
         */
        fun fromValue(value: String?): CanonicalExecutionEventType =
            entries.firstOrNull { it.wireValue == value } ?: DEFAULT

        /** Default event type for unknown or absent wire values. */
        val DEFAULT: CanonicalExecutionEventType = EXECUTION_PROGRESS
    }
}

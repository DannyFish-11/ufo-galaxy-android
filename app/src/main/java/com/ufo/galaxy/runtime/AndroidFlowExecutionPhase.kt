package com.ufo.galaxy.runtime

/**
 * **Android-side execution phase for a delegated flow** (PR-bridge,
 * post-#533 dual-repo runtime unification master plan — Android-Side Delegated Flow Bridge
 * Model, Android side).
 *
 * [AndroidFlowExecutionPhase] describes the current lifecycle stage of a delegated flow
 * as it is executed on the Android device.  It is the primary status dimension of
 * [AndroidDelegatedFlowBridge] and replaces the fragmented per-component status checks
 * that were previously needed to understand "where in the execution chain is this flow right
 * now".
 *
 * ## Phase progression
 *
 * ```
 *   ┌──────────────────────────────────────────────────────────────────────────┐
 *   │ Inbound command arrives at Android                                        │
 *   │   (task_assign / goal_execution / parallel_subtask / takeover_request)   │
 *   └──────────────────────────────────────────────────────────────────────────┘
 *                               │
 *                               ▼
 *                        RECEIVED — gate check passed; activation inputs validated
 *                               │
 *                               ▼
 *                        ACTIVATING — local execution resources / pipeline initialising
 *                               │
 *              ┌────────────────┼──────────────────┬──────────────────┐
 *              ▼                ▼                  ▼                  ▼
 *   ACTIVE_GOAL_EXECUTION  ACTIVE_LOOP    ACTIVE_COLLABORATION  ACTIVE_TAKEOVER
 *    (goal_execution /      (LoopController  (LocalCollaboration  (DelegatedTakeover
 *     task_assign path)      running)         Agent running)       Executor running)
 *              └────────────────┼──────────────────┴──────────────────┘
 *                               │
 *                    ┌──────────┴──────────┐
 *                    ▼                     ▼
 *                COMPLETED             FAILED / REJECTED
 * ```
 *
 * ## Design notes
 *
 * - Phases are mutually exclusive: a flow is always in exactly one phase at any instant.
 * - The four ACTIVE_* variants distinguish *which* Android component currently owns
 *   execution truth for this flow.  This allows operator tooling, continuity, and replay
 *   consumers to understand the execution ownership boundary without inspecting per-component
 *   internal state.
 * - Terminal phases ([COMPLETED], [FAILED], [REJECTED]) are final: once reached, a flow
 *   MUST NOT transition to any non-terminal phase.
 *
 * @property wireValue Stable lowercase string used in JSON payloads, metadata maps, and logging.
 *                     Consumers MUST treat unknown values as [UNKNOWN] (forward-compatibility).
 */
enum class AndroidFlowExecutionPhase(val wireValue: String) {

    /**
     * The inbound command has been validated and accepted for local execution.
     *
     * Session gate (where applicable) has passed.  Activation inputs have been converted
     * into a local domain model (e.g. [com.ufo.galaxy.agent.DelegatedRuntimeUnit]).
     * The flow has not yet entered any execution pipeline.
     *
     * Equivalent anchor: [com.ufo.galaxy.runtime.DelegatedActivationRecord.ActivationStatus.PENDING].
     */
    RECEIVED("received"),

    /**
     * Local execution resources are being prepared.
     *
     * The execution pipeline has been notified and is initialising (e.g. checking model
     * availability, allocating resources).  Execution steps have not yet begun.
     *
     * Equivalent anchor: [com.ufo.galaxy.runtime.DelegatedActivationRecord.ActivationStatus.ACTIVATING].
     */
    ACTIVATING("activating"),

    /**
     * The flow is actively executing via a goal-execution pipeline.
     *
     * Applies to [DelegatedFlowEntryKind.GOAL_EXECUTION] and
     * [DelegatedFlowEntryKind.TASK_ASSIGN] paths when the
     * [com.ufo.galaxy.agent.AutonomousExecutionPipeline] or
     * [com.ufo.galaxy.agent.EdgeExecutor] is running.
     *
     * Execution truth is owned by the goal-execution pipeline during this phase.
     */
    ACTIVE_GOAL_EXECUTION("active_goal_execution"),

    /**
     * The flow is actively executing via the local closed-loop controller.
     *
     * The [com.ufo.galaxy.loop.LoopController] is driving the execution loop:
     * planning → grounding → action → observation → repeat.
     * This phase is entered when the execution involves multi-step local inference
     * rather than a single pass through the pipeline.
     *
     * Execution truth is owned by [com.ufo.galaxy.loop.LoopController] during this phase.
     */
    ACTIVE_LOOP("active_loop"),

    /**
     * The flow is executing as a parallel subtask via the local collaboration agent.
     *
     * [com.ufo.galaxy.agent.LocalCollaborationAgent] is coordinating execution of a
     * [DelegatedFlowEntryKind.PARALLEL_SUBTASK] within a V2-orchestrated parallel group.
     * Group identity ([com.ufo.galaxy.protocol.GoalExecutionPayload.group_id]) and subtask
     * index are stable during this phase.
     *
     * Execution truth is owned by [com.ufo.galaxy.agent.LocalCollaborationAgent] during this phase.
     */
    ACTIVE_COLLABORATION("active_collaboration"),

    /**
     * The flow is executing via the delegated takeover executor.
     *
     * [com.ufo.galaxy.agent.DelegatedTakeoverExecutor] is managing the full lifecycle:
     * producing ACK / PROGRESS / RESULT signals and driving the local execution pipeline
     * to completion.  This phase applies to [DelegatedFlowEntryKind.TAKEOVER_REQUEST] paths.
     *
     * Execution truth is owned by [com.ufo.galaxy.agent.DelegatedTakeoverExecutor] during this phase.
     */
    ACTIVE_TAKEOVER("active_takeover"),

    /**
     * The flow completed successfully.
     *
     * A result has been returned to the calling pipeline and a RESULT signal with
     * [com.ufo.galaxy.runtime.DelegatedExecutionSignal.ResultKind.COMPLETED] has been emitted
     * (where applicable).  This is a terminal phase.
     *
     * Equivalent anchor: [com.ufo.galaxy.runtime.DelegatedActivationRecord.ActivationStatus.COMPLETED].
     */
    COMPLETED("completed"),

    /**
     * The flow failed during local execution.
     *
     * An error, timeout, or cancellation occurred.  A RESULT signal with the corresponding
     * [com.ufo.galaxy.runtime.DelegatedExecutionSignal.ResultKind] has been emitted (where
     * applicable).  This is a terminal phase.
     *
     * Equivalent anchor: [com.ufo.galaxy.runtime.DelegatedActivationRecord.ActivationStatus.FAILED].
     */
    FAILED("failed"),

    /**
     * The flow was rejected before local execution began.
     *
     * Session gate or eligibility check failed.  No execution pipeline was entered.
     * This is a terminal phase.
     *
     * Equivalent anchor: [com.ufo.galaxy.runtime.DelegatedActivationRecord.ActivationStatus.REJECTED].
     */
    REJECTED("rejected"),

    /**
     * Sentinel for unknown or future phases not present in this enum.
     *
     * Callers that encounter this value received a payload from a newer Android or V2 version.
     * They MUST NOT treat [UNKNOWN] as an error.
     */
    UNKNOWN("unknown");

    /**
     * `true` when this phase represents a terminal state (COMPLETED, FAILED, or REJECTED).
     *
     * Once a flow is terminal, it MUST NOT transition to any non-terminal phase.
     */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == REJECTED

    /**
     * `true` when this phase represents one of the four active execution variants.
     *
     * Active phases indicate that an Android execution component currently owns execution
     * truth for the flow.
     */
    val isActiveExecution: Boolean
        get() = this == ACTIVE_GOAL_EXECUTION
            || this == ACTIVE_LOOP
            || this == ACTIVE_COLLABORATION
            || this == ACTIVE_TAKEOVER

    companion object {

        /**
         * Parses [value] to an [AndroidFlowExecutionPhase], returning [UNKNOWN] for
         * unrecognised or null inputs.
         *
         * @param value Wire string from a JSON payload or metadata map; may be null.
         * @return The matching [AndroidFlowExecutionPhase], or [UNKNOWN] when unrecognised.
         */
        fun fromValue(value: String?): AndroidFlowExecutionPhase =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

package com.ufo.galaxy.trace

import com.ufo.galaxy.local.LocalLoopReadiness
import com.ufo.galaxy.local.StepObservation
import com.ufo.galaxy.nlp.NormalizedGoal

/**
 * Lightweight record of a single local-loop execution session.
 *
 * A [LocalLoopTrace] is created at session start and progressively populated as
 * the loop runs. Once the session terminates the [terminalResult] field is set and
 * the trace is complete.
 *
 * Design principles:
 * - **No raw images**: screenshots are never stored in the trace by default, keeping
 *   memory usage low. Only summary data (step counts, latencies, observations) is kept.
 * - **Append-only updates**: call the `record*` helper methods; never mutate list
 *   properties directly.
 * - **Thread-safe helpers**: all list mutations are guarded by `synchronized(this)`.
 *
 * @property sessionId          Unique identifier for this execution session (UUID v4).
 * @property originalGoal       Unmodified natural-language goal string from the caller.
 * @property normalizedGoal     Normalized goal produced by [com.ufo.galaxy.nlp.GoalNormalizer],
 *                              or `null` if normalization was skipped.
 * @property readinessSnapshot  Readiness state captured at session start; `null` if unavailable.
 * @property startTimeMs        Wall-clock time (ms since epoch) when the session started.
 * @property endTimeMs          Wall-clock time (ms since epoch) when the session ended;
 *                              `null` while the session is still running.
 * @property planOutputs        Ordered list of plan outputs (from initial plan and replans).
 * @property groundingOutputs   Ordered list of grounding outputs, one per dispatched step.
 * @property actionRecords      Ordered list of dispatched action records.
 * @property stepObservations   Ordered list of [StepObservation]s, one per completed step.
 * @property terminalResult     Final outcome once the session ends; `null` while running.
 */
data class LocalLoopTrace(
    val sessionId: String,
    val originalGoal: String,
    val normalizedGoal: NormalizedGoal? = null,
    val readinessSnapshot: LocalLoopReadiness? = null,
    val startTimeMs: Long = System.currentTimeMillis(),
    var endTimeMs: Long? = null,
    val planOutputs: MutableList<PlanOutput> = mutableListOf(),
    val groundingOutputs: MutableList<GroundingOutput> = mutableListOf(),
    val actionRecords: MutableList<ActionRecord> = mutableListOf(),
    val stepObservations: MutableList<StepObservation> = mutableListOf(),
    var terminalResult: TerminalResult? = null
) {
    /** Wall-clock duration of the session in ms; `null` while still running. */
    val durationMs: Long?
        get() = endTimeMs?.let { it - startTimeMs }

    /** Number of completed steps. */
    val stepCount: Int
        get() = stepObservations.size

    /**
     * Alias that makes the session layer explicit for convergence work.
     *
     * This value is semantically a ConversationSession identity and is intentionally distinct
     * from runtime attachment / delegated transfer session identifiers.
     */
    val conversationSessionId: String
        get() = sessionId

    /** True while [terminalResult] is null (session is still running). */
    val isRunning: Boolean
        get() = terminalResult == null

    // ── Append helpers ────────────────────────────────────────────────────────

    /** Appends a [PlanOutput] (from a plan or replan). Thread-safe. */
    fun recordPlan(output: PlanOutput) = synchronized(this) { planOutputs.add(output) }

    /** Appends a [GroundingOutput] for one step. Thread-safe. */
    fun recordGrounding(output: GroundingOutput) = synchronized(this) { groundingOutputs.add(output) }

    /** Appends an [ActionRecord] for one dispatched action. Thread-safe. */
    fun recordAction(record: ActionRecord) = synchronized(this) { actionRecords.add(record) }

    /** Appends a [StepObservation] after a step completes. Thread-safe. */
    fun recordStep(observation: StepObservation) = synchronized(this) { stepObservations.add(observation) }

    /** Sets [terminalResult] and [endTimeMs], marking the session complete. Thread-safe. */
    fun complete(result: TerminalResult) = synchronized(this) {
        terminalResult = result
        endTimeMs = System.currentTimeMillis()
    }
}

// ── Supporting value objects ───────────────────────────────────────────────────

/**
 * Summary of one plan or replan output from the local planner.
 *
 * @property stepIndex    Loop step index at which planning occurred (0 = initial plan).
 * @property isReplan     `true` when this is a replan triggered by a failed or stagnated step.
 * @property actionCount  Number of action steps in the generated plan.
 * @property latencyMs    Planner inference latency in milliseconds.
 * @property rawOutput    Optional abbreviated text output from the planner (no images).
 */
data class PlanOutput(
    val stepIndex: Int,
    val isReplan: Boolean,
    val actionCount: Int,
    val latencyMs: Long,
    val rawOutput: String? = null
)

/**
 * Summary of one grounding engine call for a single action step.
 *
 * @property stepId       Identifier of the step this grounding call belongs to.
 * @property actionType   Action type token (e.g. "tap", "scroll").
 * @property confidence   Grounding confidence [0.0, 1.0].
 * @property targetFound  Whether a plausible target was located.
 * @property latencyMs    Grounding engine latency in milliseconds.
 * @property fallbackTier Fallback tier used (0 = primary; >0 = fallback ladder tier).
 */
data class GroundingOutput(
    val stepId: String,
    val actionType: String,
    val confidence: Float,
    val targetFound: Boolean,
    val latencyMs: Long,
    val fallbackTier: Int = 0
)

/**
 * Minimal record of one dispatched action.
 *
 * @property stepId       Step identifier.
 * @property actionType   Action type token.
 * @property intent       Natural-language intent from the planner.
 * @property dispatchedAt Wall-clock time (ms since epoch) the action was dispatched.
 * @property succeeded    Whether the accessibility dispatcher confirmed success.
 */
data class ActionRecord(
    val stepId: String,
    val actionType: String,
    val intent: String,
    val dispatchedAt: Long,
    val succeeded: Boolean
)

/**
 * Terminal outcome of a local-loop session.
 *
 * @property status       One of [STATUS_SUCCESS], [STATUS_FAILED], or [STATUS_CANCELLED].
 * @property stopReason   Machine-readable stop reason (e.g. "task_complete", "timeout").
 * @property error        Human-readable error description; `null` on success.
 * @property totalSteps   Total number of steps executed in the session.
 */
data class TerminalResult(
    val status: String,
    val stopReason: String?,
    val error: String?,
    val totalSteps: Int
) {
    companion object {
        const val STATUS_SUCCESS   = "success"
        const val STATUS_FAILED    = "failed"
        const val STATUS_CANCELLED = "cancelled"
    }
}

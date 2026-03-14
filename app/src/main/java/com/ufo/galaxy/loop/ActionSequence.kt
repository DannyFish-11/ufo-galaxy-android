package com.ufo.galaxy.loop

/**
 * Execution status for a single [ActionStep].
 */
enum class StepStatus {
    /** Step has not yet been attempted. */
    PENDING,
    /** Step is currently executing. */
    RUNNING,
    /** Step completed successfully. */
    SUCCESS,
    /** Step failed after all retries. */
    FAILED,
    /** Step was skipped (e.g., superseded by a replan). */
    SKIPPED
}

/**
 * A single discrete action within an [ActionSequence].
 *
 * @param id            Unique identifier within the session (e.g., "step_1").
 * @param actionType    Symbolic action: "tap" | "scroll" | "type" | "open_app" | "back" | "home".
 * @param intent        Natural-language intent forwarded to the grounding engine.
 * @param parameters    Action-specific key-value pairs (e.g., "text", "direction", "package").
 * @param status        Current execution status.
 * @param retries       Number of retry attempts consumed for this step.
 * @param confidence    Grounding confidence score [0.0, 1.0]; 0 when grounding was skipped.
 * @param failureReason Human-readable reason when [status] is [StepStatus.FAILED].
 */
data class ActionStep(
    val id: String,
    val actionType: String,
    val intent: String,
    val parameters: Map<String, String> = emptyMap(),
    val status: StepStatus = StepStatus.PENDING,
    val retries: Int = 0,
    val confidence: Float = 0f,
    val failureReason: String? = null
)

/**
 * An ordered sequence of [ActionStep]s produced by [LocalPlanner] for a given instruction.
 *
 * @param sessionId   Unique identifier for the automation session.
 * @param instruction Original natural-language instruction that produced this sequence.
 * @param steps       Ordered list of steps to execute.
 */
data class ActionSequence(
    val sessionId: String,
    val instruction: String,
    val steps: List<ActionStep>
)

/**
 * Final result produced when a [LoopController] session terminates.
 *
 * @param sessionId   Session identifier.
 * @param instruction Original instruction that started the session.
 * @param status      Terminal outcome: "success" | "failed" | "cancelled".
 * @param steps       All [ActionStep]s executed (including retries and skips).
 * @param stopReason  Machine-readable stop reason (e.g., "task_complete", "max_steps_reached").
 * @param error       Human-readable error message when [status] is not "success".
 */
data class LoopResult(
    val sessionId: String,
    val instruction: String,
    val status: String,
    val steps: List<ActionStep>,
    val stopReason: String? = null,
    val error: String? = null
)

/**
 * Describes the current state of a [LoopController] session.
 */
sealed class LoopStatus {
    /** No session is active. */
    object Idle : LoopStatus()

    /**
     * A session is actively running.
     *
     * @param sessionId     Current session identifier.
     * @param stepIndex     1-based index of the step currently executing.
     * @param totalSteps    Total steps planned in the current sequence (may change after replan).
     * @param currentAction Human-readable description of the action being executed.
     */
    data class Running(
        val sessionId: String,
        val stepIndex: Int,
        val totalSteps: Int,
        val currentAction: String
    ) : LoopStatus()

    /**
     * Session completed successfully.
     *
     * @param sessionId Session identifier.
     * @param stepCount Total steps executed.
     * @param summary   Human-readable completion summary.
     */
    data class Done(
        val sessionId: String,
        val stepCount: Int,
        val summary: String
    ) : LoopStatus()

    /**
     * Session ended due to an error or exceeded the max-steps budget.
     *
     * @param sessionId Session identifier.
     * @param reason    Human-readable failure reason.
     * @param stepIndex 1-based index of the step at which the failure occurred.
     */
    data class Failed(
        val sessionId: String,
        val reason: String,
        val stepIndex: Int
    ) : LoopStatus()
}

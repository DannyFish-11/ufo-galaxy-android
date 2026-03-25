package com.ufo.galaxy.local

import com.ufo.galaxy.agent.LocalGoalExecutor
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload

/**
 * Options controlling a single local loop execution request.
 *
 * @property instruction  Natural-language goal passed to the local loop.
 * @property maxSteps     Optional step-budget override. `null` uses the executor's default.
 */
data class LocalLoopOptions(
    val instruction: String,
    val maxSteps: Int? = null
)

/**
 * Structured result from a [LocalLoopExecutor.execute] call.
 *
 * Wraps [com.ufo.galaxy.loop.LoopResult] into a stable public type so that callers
 * (UI, tests, future components) do not depend on the internal [LoopController] model
 * directly.
 *
 * @property sessionId    Unique session identifier.
 * @property instruction  The goal that was executed.
 * @property status       Outcome: [STATUS_SUCCESS], [STATUS_FAILED], or [STATUS_CANCELLED].
 * @property stepCount    Number of action steps executed.
 * @property stopReason   Machine-readable stop reason (echoed from [LoopController]).
 * @property error        Human-readable error description, or `null` on success.
 */
data class LocalLoopResult(
    val sessionId: String,
    val instruction: String,
    val status: String,
    val stepCount: Int,
    val stopReason: String?,
    val error: String?
) {
    companion object {
        /** Execution completed successfully. */
        const val STATUS_SUCCESS = LoopController.STATUS_SUCCESS

        /** Execution failed (model error, step exhausted, etc.). */
        const val STATUS_FAILED = LoopController.STATUS_FAILED

        /** Execution was cancelled (remote task preemption, etc.). */
        const val STATUS_CANCELLED = LoopController.STATUS_CANCELLED
    }

    /** True when this result represents a successful execution. */
    val isSuccess: Boolean get() = status == STATUS_SUCCESS
}

/**
 * Single canonical entrypoint for local goal execution.
 *
 * Provides two execution paths that share the same local closed-loop core:
 * 1. [execute] — natural-language instruction from the UI or voice input.
 * 2. [executeGoal] — structured [GoalExecutionPayload] delivered by the gateway.
 *
 * Both callers should delegate here so that readiness reporting, logging, and
 * future local-loop features (trace, metrics) are applied consistently.
 */
interface LocalLoopExecutor {

    /**
     * Executes [options] through the local closed loop.
     *
     * Must be called from a coroutine; never from the main thread. The implementation
     * dispatches blocking work on [kotlinx.coroutines.Dispatchers.IO] internally.
     *
     * @return [LocalLoopResult] describing the final outcome.
     */
    suspend fun execute(options: LocalLoopOptions): LocalLoopResult

    /**
     * Executes a gateway-delivered [GoalExecutionPayload] locally and returns a
     * [GoalResultPayload] suitable for sending back to the server.
     */
    fun executeGoal(payload: GoalExecutionPayload): GoalResultPayload
}

/**
 * Default [LocalLoopExecutor] that delegates:
 * - [execute] → [LoopController.execute]
 * - [executeGoal] → [LocalGoalExecutor.executeGoal]
 *
 * A readiness snapshot is logged before each execution so that diagnostics capture
 * the subsystem state at the time the request was received.
 *
 * @param loopController     Closed-loop orchestrator for natural-language instructions.
 * @param goalExecutor       Handles structured gateway [GoalExecutionPayload]s.
 * @param readinessProvider  Used to log the readiness state before execution starts.
 */
class DefaultLocalLoopExecutor(
    private val loopController: LoopController,
    private val goalExecutor: LocalGoalExecutor,
    private val readinessProvider: LocalLoopReadinessProvider
) : LocalLoopExecutor {

    companion object {
        private const val TAG = "GALAXY:LOCAL:EXECUTOR"
    }

    override suspend fun execute(options: LocalLoopOptions): LocalLoopResult {
        val readiness = readinessProvider.getReadiness()
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "execute_start",
                "instruction_len" to options.instruction.length,
                "readiness_state" to readiness.state.name,
                "blocker_count" to readiness.blockers.size,
                "blockers" to readiness.blockers.joinToString { it.name }
            )
        )

        val loopResult = loopController.execute(options.instruction)

        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "execute_done",
                "session_id" to loopResult.sessionId,
                "status" to loopResult.status,
                "steps" to loopResult.steps.size,
                "stop_reason" to (loopResult.stopReason ?: "")
            )
        )

        return LocalLoopResult(
            sessionId = loopResult.sessionId,
            instruction = loopResult.instruction,
            status = loopResult.status,
            stepCount = loopResult.steps.size,
            stopReason = loopResult.stopReason,
            error = loopResult.error
        )
    }

    override fun executeGoal(payload: GoalExecutionPayload): GoalResultPayload {
        val readiness = readinessProvider.getReadiness()
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "execute_goal_start",
                "task_id" to payload.task_id,
                "readiness_state" to readiness.state.name,
                "blocker_count" to readiness.blockers.size
            )
        )
        return goalExecutor.executeGoal(payload)
    }
}

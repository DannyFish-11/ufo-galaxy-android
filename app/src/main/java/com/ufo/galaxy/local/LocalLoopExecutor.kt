package com.ufo.galaxy.local

import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.LocalGoalExecutor
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.GoalExecutionPayload
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * Canonical entrypoint for **UI/voice-driven** local loop execution.
 *
 * All user-facing task dispatch (from UI or voice input) passes through this interface.
 * Implementations decide which underlying execution pipeline to use (rich closed-loop or
 * canonical goal executor), while callers remain insulated from that detail.
 *
 * **Gateway goal execution is NOT routed through this interface.** Messages of type
 * `goal_execution` and `parallel_subtask` arrive over the WebSocket and are dispatched
 * exclusively through the canonical gateway path:
 * [com.ufo.galaxy.service.GalaxyConnectionService]
 *   → [com.ufo.galaxy.agent.AutonomousExecutionPipeline] (runtime + feature gates)
 *   → [com.ufo.galaxy.agent.LocalGoalExecutor.executeGoal]
 *
 * Keeping gateway execution outside this interface ensures the
 * [com.ufo.galaxy.agent.AutonomousExecutionPipeline] runtime gate
 * ([com.ufo.galaxy.data.AppSettings.crossDeviceEnabled]) and feature gate
 * ([com.ufo.galaxy.data.AppSettings.goalExecutionEnabled]) are never bypassed.
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
}

/**
 * Default [LocalLoopExecutor] that delegates [execute] to the canonical local execution path.
 *
 * ## Execution strategy
 * When [goalExecutor] is provided (the production path wired in
 * [com.ufo.galaxy.UFOGalaxyApplication]), execution is delegated to
 * [com.ufo.galaxy.agent.LocalGoalExecutor.executeGoal] — the same step-level pipeline
 * used by gateway-delivered `task_assign` and `goal_execution` messages. This ensures
 * **one canonical local execution core** regardless of how the task originated.
 *
 * When [goalExecutor] is `null` (tests or backward-compatible callers that only supply
 * [loopController]), execution falls back to the rich [LoopController] pipeline
 * (stagnation detection, fallback planners, retries, etc.).
 *
 * A readiness snapshot is logged before each execution so that diagnostics capture
 * the subsystem state at the time the request was received.
 *
 * @param loopController     Rich closed-loop orchestrator; used as fallback when [goalExecutor] is null.
 * @param readinessProvider  Used to check and log readiness before execution starts.
 * @param goalExecutor       Canonical AIP v3 step executor (screenshot → plan → ground → action).
 *                           When non-null, [execute] delegates to this instead of [loopController].
 */
class DefaultLocalLoopExecutor(
    private val loopController: LoopController,
    private val readinessProvider: LocalLoopReadinessProvider,
    private val goalExecutor: LocalGoalExecutor? = null
) : LocalLoopExecutor {

    companion object {
        private const val TAG = "GALAXY:LOCAL:EXECUTOR"

        /**
         * Stop-reason used when execution is blocked because one or more critical
         * subsystems are unavailable (accessibility service, screenshot capture, etc.).
         * Corresponds to [LocalLoopState.UNAVAILABLE].
         */
        const val STOP_READINESS_UNAVAILABLE = "readiness_unavailable"
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

        // Gate: block execution when one or more critical subsystems are unavailable.
        if (readiness.state == LocalLoopState.UNAVAILABLE) {
            val blockerList = readiness.blockers.joinToString { it.name }
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "execute_blocked",
                    "reason" to STOP_READINESS_UNAVAILABLE,
                    "blockers" to blockerList
                )
            )
            return LocalLoopResult(
                sessionId = UUID.randomUUID().toString(),
                instruction = options.instruction,
                status = LocalLoopResult.STATUS_FAILED,
                stepCount = 0,
                stopReason = STOP_READINESS_UNAVAILABLE,
                error = "Local loop unavailable — blocked by: $blockerList"
            )
        }

        // ── Canonical path: delegate to LocalGoalExecutor (same pipeline as gateway tasks) ──
        if (goalExecutor != null) {
            return executeViaGoalExecutor(options)
        }

        // ── Fallback path: rich LoopController pipeline (used in tests / no-goalExecutor callers) ──
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

    /**
     * Executes [options] through the canonical [goalExecutor] pipeline.
     *
     * Converts [LocalLoopOptions] to a [GoalExecutionPayload], delegates to
     * [LocalGoalExecutor.executeGoal], and maps the result back to [LocalLoopResult].
     *
     * This is the **production execution path** that ensures both UI-driven and
     * gateway-assigned local tasks use the same step-level core (screenshot → plan →
     * ground → action via [com.ufo.galaxy.agent.EdgeExecutor]).
     */
    private suspend fun executeViaGoalExecutor(options: LocalLoopOptions): LocalLoopResult =
        withContext(Dispatchers.IO) {
            val taskId = UUID.randomUUID().toString()
            val payload = GoalExecutionPayload(
                goal = options.instruction,
                task_id = taskId,
                group_id = null,
                subtask_index = null,
                max_steps = options.maxSteps ?: LoopController.DEFAULT_MAX_STEPS,
                timeout_ms = 0L,
                constraints = emptyList()
            )

            val goalResult = goalExecutor!!.executeGoal(payload)

            // Map EdgeExecutor / AutonomousExecutionPipeline status values to LocalLoopResult values.
            val mappedStatus = when (goalResult.status) {
                EdgeExecutor.STATUS_SUCCESS -> LocalLoopResult.STATUS_SUCCESS
                EdgeExecutor.STATUS_CANCELLED -> LocalLoopResult.STATUS_CANCELLED
                else -> LocalLoopResult.STATUS_FAILED // covers STATUS_ERROR, STATUS_TIMEOUT, "disabled"
            }

            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "execute_done",
                    "session_id" to taskId,
                    "status" to mappedStatus,
                    "steps" to goalResult.steps.size,
                    "stop_reason" to (goalResult.status.takeUnless { it == EdgeExecutor.STATUS_SUCCESS } ?: "")
                )
            )

            LocalLoopResult(
                sessionId = taskId,
                instruction = options.instruction,
                status = mappedStatus,
                stepCount = goalResult.steps.size,
                stopReason = goalResult.status.takeUnless { it == EdgeExecutor.STATUS_SUCCESS },
                error = goalResult.error
            )
        }
}

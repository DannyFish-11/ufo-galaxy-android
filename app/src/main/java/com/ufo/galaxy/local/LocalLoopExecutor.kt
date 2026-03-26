package com.ufo.galaxy.local

import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.observability.GalaxyLogger
import java.util.UUID

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
 * Handles natural-language instructions that originate from the UI or voice input
 * and routes them through the local closed-loop pipeline ([LoopController]).
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
 * Default [LocalLoopExecutor] that delegates [execute] → [LoopController.execute].
 *
 * A readiness snapshot is logged before each execution so that diagnostics capture
 * the subsystem state at the time the request was received.
 *
 * This class is **exclusively the UI/voice-driven local path**. For gateway-delivered
 * goal execution see [com.ufo.galaxy.agent.AutonomousExecutionPipeline].
 *
 * @param loopController     Closed-loop orchestrator for natural-language instructions.
 * @param readinessProvider  Used to log the readiness state before execution starts.
 */
class DefaultLocalLoopExecutor(
    private val loopController: LoopController,
    private val readinessProvider: LocalLoopReadinessProvider
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
}

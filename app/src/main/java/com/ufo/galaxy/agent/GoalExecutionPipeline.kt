package com.ufo.galaxy.agent

import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload

/**
 * **Testable abstraction over the local goal-execution pipeline** (PR-12,
 * post-#533 dual-repo runtime unification master plan — Canonical Android-Side
 * Delegated Receipt-to-Local-Takeover Executor Binding, Android side).
 *
 * [GoalExecutionPipeline] is a single-method functional interface that decouples
 * [DelegatedTakeoverExecutor] from the concrete [AutonomousExecutionPipeline].  This
 * separation allows [DelegatedTakeoverExecutor] to be fully exercised in JVM unit tests
 * without an Android framework or a real execution pipeline.
 *
 * ## Production wiring
 *
 * In [com.ufo.galaxy.service.GalaxyConnectionService] the pipeline is wired as a lambda
 * adapter over [AutonomousExecutionPipeline.handleGoalExecution]:
 *
 * ```kotlin
 * private val goalExecutionPipeline = GoalExecutionPipeline { payload ->
 *     UFOGalaxyApplication.autonomousExecutionPipeline.handleGoalExecution(payload)
 * }
 * ```
 *
 * ## Test wiring
 *
 * In unit tests a fake implementation returns canned [GoalResultPayload] instances or
 * throws on demand:
 *
 * ```kotlin
 * val successPipeline = GoalExecutionPipeline { payload ->
 *     GoalResultPayload(task_id = payload.task_id, status = "success")
 * }
 *
 * val failingPipeline = GoalExecutionPipeline { _ ->
 *     throw RuntimeException("pipeline failure")
 * }
 * ```
 *
 * @see DelegatedTakeoverExecutor
 * @see AutonomousExecutionPipeline
 */
fun interface GoalExecutionPipeline {

    /**
     * Executes the supplied [payload] through the local goal-execution pipeline and
     * returns a [GoalResultPayload] describing the outcome.
     *
     * Implementations are expected to be synchronous (non-suspending) because the
     * real [AutonomousExecutionPipeline.handleGoalExecution] is synchronous.  Callers
     * are responsible for dispatching to an appropriate thread/coroutine context.
     *
     * @param payload The goal-execution payload built from the delegated unit.
     * @return A [GoalResultPayload] with status, result, and latency fields populated.
     * @throws Exception Any execution-level error; [DelegatedTakeoverExecutor] will
     *                   catch this and advance the tracker to
     *                   [com.ufo.galaxy.runtime.DelegatedActivationRecord.ActivationStatus.FAILED].
     */
    fun executeGoal(payload: GoalExecutionPayload): GoalResultPayload
}

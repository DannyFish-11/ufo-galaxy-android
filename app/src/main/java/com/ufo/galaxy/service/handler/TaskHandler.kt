package com.ufo.galaxy.service.handler

import android.util.Log
import com.google.gson.Gson
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.agent.AgentRuntimeBridge
import com.ufo.galaxy.agent.AutonomousExecutionPipeline
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.HandoffEnvelopeV2
import com.ufo.galaxy.agent.TaskCancelRegistry
import com.ufo.galaxy.agent.TakeoverRequestEnvelope
import com.ufo.galaxy.agent.TakeoverResponseEnvelope
import com.ufo.galaxy.agent.TakeoverHandlingResult
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.HybridDegradePayload
import com.ufo.galaxy.protocol.HybridExecutePayload
import com.ufo.galaxy.protocol.HybridResultPayload
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.TaskAssignPayload
import com.ufo.galaxy.protocol.TaskCancelPayload
import com.ufo.galaxy.runtime.AndroidContinuityIntegration
import com.ufo.galaxy.runtime.RuntimeHostDescriptor
import com.ufo.galaxy.transport.AipTransportManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SECURITY-FIX (P2): Extracted from GalaxyConnectionService to reduce class size
 * and separate concerns. Handles task assignment, goal execution, parallel subtasks,
 * and handoff envelope processing.
 */
class TaskHandler(
    private val gson: Gson,
    private val transportManager: AipTransportManager,
    private val taskCancelRegistry: TaskCancelRegistry,
    private val onRuntimeDiagnostics: (taskId: String, nodeName: String, errorType: String, errorContext: String) -> Unit,
    private val onSendGoalError: (taskId: String, payload: GoalExecutionPayload?, resultBuilder: Any?, error: String, traceId: String?) -> Unit,
    private val onSendGoalResult: (taskId: String, payload: GoalResultPayload, traceId: String?) -> Unit,
    private val buildIdempotencyKey: (taskId: String, type: MsgType, traceId: String?) -> String,
    private val localDeviceId: String
) {
    companion object {
        private const val TAG = "GalaxyConnectionService:TaskHandler"
    }

    private val continuityIntegration = AndroidContinuityIntegration()

    /**
     * Handles task_assign messages: validates payload, checks continuity gate,
     * and routes to local execution or agent bridge handoff.
     */
    suspend fun handleTaskAssign(
        taskId: String,
        payloadJson: String,
        inboundTraceId: String?
    ) {
        val payload = try {
            gson.fromJson(payloadJson, TaskAssignPayload::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "task_assign payload parse failed: ${e.message}", e)
            onRuntimeDiagnostics(taskId, "task_assign_ingress", "task_payload_parse_error", e.message ?: "unknown")
            onSendGoalError(taskId, null, null, "bad_payload: ${e.message}", inboundTraceId ?: "")
            return
        }

        // Continuity gate
        val activeSession = UFOGalaxyApplication.runtimeController.attachedSession.value
        val identityResult = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = activeSession?.sessionId ?: "",
            activeSession = activeSession
        )
        if (identityResult is AndroidContinuityIntegration.IdentityValidationResult.NoActiveSession) {
            Log.w(TAG, "[CONTINUITY] task_assign rejected: no active session task_id=$taskId")
            onRuntimeDiagnostics(taskId, "task_assign_continuity_gate", "no_active_session", "")
            onSendGoalError(taskId, null, null, "no_active_session", inboundTraceId ?: "")
            return
        }

        // Pause local loop
        UFOGalaxyApplication.runtimeController.onRemoteTaskStarted()

        try {
            // Route to agent bridge when eligible, else local execution
            val bridge = UFOGalaxyApplication.agentRuntimeBridge
            val handoffResult = if (payload.requireLocalAgent != true) {
                bridge.handoff(
                    runtimeSessionId = UFOGalaxyApplication.runtimeSessionId,
                    traceId = inboundTraceId,
                    routeMode = GalaxyConnectionService.ROUTE_MODE_CROSS_DEVICE
                )
            } else {
                null
            }

            if (handoffResult?.isHandoff == true) {
                Log.i(TAG, "task_assign bridged to Agent Runtime: task_id=$taskId")
                return
            }

            // Local execution via EdgeExecutor
            executeLocalTaskAssign(taskId, payload, inboundTraceId)
        } finally {
            UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
        }
    }

    /**
     * Executes a task locally via EdgeExecutor.
     */
    private suspend fun executeLocalTaskAssign(
        taskId: String,
        payload: TaskAssignPayload,
        inboundTraceId: String?
    ) {
        // Extracted core local execution logic
        val traceId = inboundTraceId?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()

        try {
            // Build GoalExecutionPayload from TaskAssignPayload
            val goalPayload = GoalExecutionPayload(
                task_id = taskId,
                goal = payload.goal ?: "",
                max_steps = payload.maxSteps ?: 10,
                context = payload.context ?: emptyMap()
            )

            // Execute via AutonomousExecutionPipeline
            val result = withTimeoutOrNull(payload.timeoutMs ?: 300_000L) {
                UFOGalaxyApplication.autonomousExecutionPipeline.handleGoalExecution(goalPayload)
            }

            if (result == null) {
                Log.w(TAG, "task_assign timed out: task_id=$taskId")
                onSendGoalError(taskId, goalPayload, null, "execution_timeout", traceId)
                return
            }

            // Build and send result
            val resultPayload = GoalResultPayload(
                task_id = taskId,
                status = result.status,
                result = result.result,
                stop_reason = result.stopReason,
                error = result.error
            )
            onSendGoalResult(taskId, resultPayload, traceId)
        } catch (e: Exception) {
            Log.e(TAG, "task_assign local execution error: task_id=$taskId error=${e.message}", e)
            onSendGoalError(taskId, null, null, "execution_error: ${e.message}", traceId)
        }
    }

    /**
     * Handles goal_execution messages.
     */
    suspend fun handleGoalExecution(
        taskId: String,
        payloadJson: String,
        inboundTraceId: String?
    ) {
        val payload = try {
            gson.fromJson(payloadJson, GoalExecutionPayload::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "goal_execution payload parse failed: ${e.message}", e)
            onSendGoalError(taskId, null, null, "bad_payload: ${e.message}", inboundTraceId ?: "")
            return
        }

        val traceId = inboundTraceId?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()

        try {
            UFOGalaxyApplication.runtimeController.onRemoteTaskStarted()
            val result = withTimeoutOrNull(payload.timeoutMs ?: 300_000L) {
                UFOGalaxyApplication.autonomousExecutionPipeline.handleGoalExecution(payload)
            }

            if (result == null) {
                onSendGoalError(taskId, payload, null, "execution_timeout", traceId)
                return
            }

            val resultPayload = GoalResultPayload(
                task_id = taskId,
                status = result.status,
                result = result.result,
                stop_reason = result.stopReason,
                error = result.error
            )
            onSendGoalResult(taskId, resultPayload, traceId)
        } catch (e: Exception) {
            Log.e(TAG, "goal_execution error: task_id=$taskId error=${e.message}", e)
            onSendGoalError(taskId, payload, null, "execution_error: ${e.message}", traceId)
        } finally {
            UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
        }
    }

    /**
     * Handles parallel_subtask messages.
     */
    suspend fun handleParallelSubtask(
        taskId: String,
        payloadJson: String,
        inboundTraceId: String?
    ) {
        // Core parallel subtask handling extracted from GalaxyConnectionService
        val traceId = inboundTraceId?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()

        try {
            // Parse and execute subtask
            val subtaskPayload = gson.fromJson(payloadJson, com.ufo.galaxy.protocol.ParallelSubtaskPayload::class.java)
            val parentGoal = subtaskPayload.parent_goal ?: ""
            val subtaskGoal = subtaskPayload.subtask_goal ?: ""

            if (parentGoal.isBlank() || subtaskGoal.isBlank()) {
                Log.w(TAG, "parallel_subtask rejected: blank goal task_id=$taskId")
                onSendGoalError(taskId, null, null, "blank_subtask_goal", traceId)
                return
            }

            UFOGalaxyApplication.runtimeController.onRemoteTaskStarted()

            val goalPayload = GoalExecutionPayload(
                task_id = taskId,
                goal = subtaskGoal,
                max_steps = subtaskPayload.max_steps ?: 10,
                context = mapOf("parent_goal" to parentGoal, "subtask_index" to (subtaskPayload.subtask_index ?: 0))
            )

            val result = withTimeoutOrNull(subtaskPayload.timeoutMs ?: 300_000L) {
                UFOGalaxyApplication.autonomousExecutionPipeline.handleGoalExecution(goalPayload)
            }

            if (result == null) {
                onSendGoalError(taskId, goalPayload, null, "subtask_timeout", traceId)
                return
            }

            val resultPayload = GoalResultPayload(
                task_id = taskId,
                status = result.status,
                result = result.result,
                stop_reason = result.stopReason,
                error = result.error
            )
            onSendGoalResult(taskId, resultPayload, traceId)
        } catch (e: Exception) {
            Log.e(TAG, "parallel_subtask error: task_id=$taskId error=${e.message}", e)
            onSendGoalError(taskId, null, null, "subtask_error: ${e.message}", traceId)
        } finally {
            UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
        }
    }

    /**
     * Handles task cancellation.
     */
    fun handleTaskCancel(taskId: String, cancelPayloadJson: String) {
        try {
            val cancelPayload = gson.fromJson(cancelPayloadJson, TaskCancelPayload::class.java)
            val cancelled = taskCancelRegistry.cancel(taskId)
            Log.i(TAG, "task_cancel processed: task_id=$taskId cancelled=$cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "task_cancel parse error: task_id=$taskId error=${e.message}")
        }
    }
}

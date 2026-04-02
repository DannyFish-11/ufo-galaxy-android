package com.ufo.galaxy.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.ufo.galaxy.R
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.agent.AgentRuntimeBridge
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.TaskCancelRegistry
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.CancelResultPayload
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.TaskAssignPayload
import com.ufo.galaxy.protocol.TaskCancelPayload
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.memory.MemoryEntry
import com.ufo.galaxy.memory.OpenClawdMemoryBackflow
import com.ufo.galaxy.protocol.TaskResultPayload
import com.ufo.galaxy.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * **Canonical inbound gateway-message dispatcher** for the cross-device pipeline.
 *
 * This Android [Service] owns the **inbound WebSocket message loop**: it registers a
 * [GalaxyWebSocketClient.Listener] and dispatches every inbound AIP v3 message to the
 * correct handler. It is the sole component that processes gateway-originated tasks on
 * behalf of this device.
 *
 * ## Ownership boundaries
 *  - **Runtime lifecycle**: all WS connect/disconnect and `crossDeviceEnabled` changes go
 *    through [com.ufo.galaxy.runtime.RuntimeController] (the sole lifecycle authority).
 *    This service calls [RuntimeController.connectIfEnabled] on start and never touches
 *    the WebSocket client directly for connection management.
 *  - **Inbound dispatch**: parses each [AipMessage] and routes by type:
 *    - `task_assign`      → [AgentRuntimeBridge.handoff] (when eligible) or [EdgeExecutor]
 *    - `goal_execution`   → [AutonomousExecutionPipeline.handleGoalExecution]
 *    - `parallel_subtask` → [AutonomousExecutionPipeline.handleParallelSubtask]
 *    - `task_cancel`      → [TaskCancelRegistry.cancel]
 *  - **Cancellation**: every goal/subtask coroutine is registered in [TaskCancelRegistry]
 *    so in-flight tasks can be cooperatively cancelled via `task_cancel`.
 *  - **Remote task handoff**: calls [RuntimeController.onRemoteTaskStarted] when a
 *    gateway task arrives (blocking the local [LoopController]) and
 *    [RuntimeController.onRemoteTaskFinished] when the result is sent back.
 *
 * On start: loads MobileVLM and SeeClick models via [GalaxyWebSocketClient.setModelCapabilities].
 * On destroy: unloads models and removes the WS listener.
 */
class GalaxyConnectionService : Service() {
    
    companion object {
        private const val TAG = "GalaxyConnectionService"
        private const val NOTIFICATION_ID = 1001
    }
    
    private val binder = LocalBinder()
    private lateinit var webSocketClient: GalaxyWebSocketClient
    private val gson = Gson()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Stable device identifier used in all outbound AIP v3 message envelopes. */
    private val localDeviceId: String by lazy {
        "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}"
    }

    /** Tracks active goal_execution / parallel_subtask coroutine jobs for cancel support. */
    private val taskCancelRegistry = TaskCancelRegistry()

    private lateinit var wsListener: GalaxyWebSocketClient.Listener
    
    inner class LocalBinder : Binder() {
        fun getService(): GalaxyConnectionService = this@GalaxyConnectionService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "服务创建")
        
        webSocketClient = UFOGalaxyApplication.webSocketClient
        
        // 设置监听器：处理连接状态和 task_assign 路由
        wsListener = object : GalaxyWebSocketClient.Listener {
            override fun onConnected() {
                Log.d(TAG, "已连接到 Galaxy")
                updateNotification("已连接")
            }
            
            override fun onDisconnected() {
                Log.d(TAG, "与 Galaxy 断开连接")
                updateNotification("已断开")
                // Safety: unblock the local LoopController in case the Gateway disconnected
                // while a remote task was in flight. The finally blocks in handleTaskAssign,
                // handleGoalExecution, and handleParallelSubtask cover normal completion;
                // this covers unexpected drops (network outage, server crash) so the loop
                // is never blocked permanently.
                UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
            }
            
            override fun onMessage(message: String) {
                Log.d(TAG, "收到消息: ${message.take(50)}...")
            }
            
            override fun onError(error: String) {
                Log.e(TAG, "连接错误: $error")
                updateNotification("连接错误")
            }

            override fun onTaskAssign(taskId: String, taskAssignPayloadJson: String, traceId: String?) {
                Log.i(TAG, "收到 task_assign: task_id=$taskId trace_id=$traceId")
                GalaxyLogger.log(GalaxyLogger.TAG_TASK_RECV, mapOf("task_id" to taskId, "type" to "task_assign", "trace_id" to (traceId ?: "")))
                serviceScope.launch {
                    handleTaskAssign(taskId, taskAssignPayloadJson, traceId)
                }
            }

            override fun onGoalExecution(taskId: String, goalPayloadJson: String) {
                Log.i(TAG, "收到 goal_execution: task_id=$taskId")
                GalaxyLogger.log(GalaxyLogger.TAG_TASK_RECV, mapOf("task_id" to taskId, "type" to "goal_execution"))
                serviceScope.launch {
                    // Register inside the coroutine to avoid any race between launch and register.
                    taskCancelRegistry.register(taskId, coroutineContext[Job]!!)
                    handleGoalExecution(taskId, goalPayloadJson)
                }
            }

            override fun onParallelSubtask(taskId: String, subtaskPayloadJson: String) {
                Log.i(TAG, "收到 parallel_subtask: task_id=$taskId")
                serviceScope.launch {
                    taskCancelRegistry.register(taskId, coroutineContext[Job]!!)
                    handleParallelSubtask(taskId, subtaskPayloadJson)
                }
            }

            override fun onTaskCancel(taskId: String, cancelPayloadJson: String) {
                Log.i(TAG, "收到 task_cancel: task_id=$taskId")
                serviceScope.launch {
                    handleTaskCancel(taskId, cancelPayloadJson)
                }
            }
        }
        webSocketClient.addListener(wsListener)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "服务启动")

        // Restore cross-device runtime state via RuntimeController — the sole lifecycle
        // authority.  RuntimeController reads settings.crossDeviceEnabled internally;
        // direct webSocketClient.setCrossDeviceEnabled() / connect() calls are not
        // permitted outside RuntimeController.
        val settings = UFOGalaxyApplication.appSettings
        val savedCrossDevice = settings.crossDeviceEnabled
        UFOGalaxyApplication.runtimeController.connectIfEnabled()
        Log.i(TAG, "服务启动：通过 RuntimeController 恢复跨设备状态 crossDeviceEnabled=$savedCrossDevice")

        // 启动前台服务（常驻通知，防止后台进程被系统回收）
        startForeground(NOTIFICATION_ID, createNotification(
            if (savedCrossDevice) "跨设备模式已启用" else "本地模式"
        ))

        // Pre-warm and then load models in background.
        // Pre-warming sends a lightweight health ping + optional dry-run to reduce cold start
        // latency on the first real inference call.
        serviceScope.launch {
            ensureModels()
            prewarmServices()
            loadModels()
            // Re-run all readiness checks after models are loaded so the UI and
            // capability_report reflect the final state (including overlay/accessibility).
            UFOGalaxyApplication.instance.refreshReadiness()
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "服务销毁")
        webSocketClient.removeListener(wsListener)
        webSocketClient.disconnect()
        unloadModels()
        serviceScope.cancel()
    }

    /**
     * 处理 task_assign：反序列化 payload、执行本地 EdgeExecutor 或委托 AgentRuntimeBridge、回传 task_result。
     * 在 IO 线程中执行；EdgeExecutor 内部所有异常均已捕获并映射为 ERROR 结果。
     *
     * When a task_assign arrives (Round 5 bridge flow):
     * 1. Notifies [RuntimeController.onRemoteTaskStarted] to cancel any running local
     *    [com.ufo.galaxy.loop.LoopController] session.
     * 2a. If cross-device is ON **and** [TaskAssignPayload.require_local_agent] is `false`:
     *     delegates to [AgentRuntimeBridge.handoff] with exec_mode=REMOTE, carrying
     *     trace_id, route_mode, capability, and session context.
     *     - If handoff succeeds ([HandoffResult.isHandoff] = true): task has been forwarded
     *       to Agent Runtime; no local execution is needed. The Agent Runtime will send the
     *       result directly to the Gateway.
     *     - If handoff fails / times out (all retries exhausted): falls back to local
     *       EdgeExecutor with an explicit error log; no silent swallowing.
     * 2b. If cross-device is OFF or [require_local_agent] is `true`: executes locally via
     *     [EdgeExecutor] as before (full backward compatibility).
     * 3. Sends back the AIP v3 task_result envelope (for local execution or fallback path);
     *    the envelope now includes [trace_id] and [route_mode] for full-chain traceability.
     * 4. Notifies [RuntimeController.onRemoteTaskFinished] to unblock local execution.
     * 5. Persists the result to OpenClawd memory.
     */
    private suspend fun handleTaskAssign(taskId: String, payloadJson: String, inboundTraceId: String?) {
        val payload = try {
            gson.fromJson(payloadJson, TaskAssignPayload::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "task_assign payload 解析失败: ${e.message}", e)
            sendTaskError(taskId, "bad_payload: ${e.message}", inboundTraceId)
            return
        }

        // Pause any running local LoopController session.
        UFOGalaxyApplication.runtimeController.onRemoteTaskStarted()

        // Preserve trace_id from the inbound task_assign envelope for full-chain correlation.
        // Generate a new UUID only when the gateway did not supply one.
        val traceId = inboundTraceId?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()
        val crossDevice = UFOGalaxyApplication.appSettings.crossDeviceEnabled
        val routeMode = if (crossDevice) AgentRuntimeBridge.ROUTE_MODE_CROSS_DEVICE
                        else AgentRuntimeBridge.ROUTE_MODE_LOCAL

        if (crossDevice && !payload.require_local_agent) {
            // ── Bridge path: cross-device ON + task does not require local execution ──
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "bridge_handoff_attempt",
                    "task_id" to taskId,
                    "trace_id" to traceId,
                    "route_mode" to routeMode
                )
            )
            val handoffRequest = AgentRuntimeBridge.HandoffRequest(
                traceId = traceId,
                taskId = taskId,
                goal = payload.goal,
                execMode = AgentRuntimeBridge.EXEC_MODE_REMOTE,
                routeMode = routeMode,
                capability = "task_execution",
                constraints = payload.constraints
            )
            val handoffResult = UFOGalaxyApplication.agentRuntimeBridge.handoff(handoffRequest)

            if (handoffResult.isHandoff) {
                // Task successfully handed off to Agent Runtime.
                // Agent Runtime will send the result directly; no local execution needed.
                updateNotification("任务 ${taskId.take(8)}: 已转交 Agent Runtime")
                Log.i(
                    TAG,
                    "task_assign bridge handoff OK task_id=$taskId trace_id=$traceId"
                )
                // Unblock local loop after scheduling the handoff.
                UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
            } else {
                // Handoff failed (fallback to local execution).
                Log.w(
                    TAG,
                    "task_assign bridge handoff failed — falling back to local execution " +
                        "task_id=$taskId trace_id=$traceId error=${handoffResult.error}"
                )
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "bridge_fallback",
                        "task_id" to taskId,
                        "trace_id" to traceId,
                        "error" to handoffResult.error
                    )
                )
                executeLocalTaskAssign(taskId, payload, traceId, routeMode)
            }
        } else {
            // ── Local path: cross-device OFF or require_local_agent=true ─────────────
            executeLocalTaskAssign(taskId, payload, traceId, routeMode)
        }
    }

    /**
     * Executes a task_assign payload locally via [EdgeExecutor] and sends the task_result
     * back to the Gateway. Both trace_id and route_mode are propagated in the reply envelope.
     *
     * Called from:
     *  - The local execution path (cross-device OFF or require_local_agent=true).
     *  - The bridge fallback path (bridge handoff failed after all retries).
     */
    private suspend fun executeLocalTaskAssign(
        taskId: String,
        payload: TaskAssignPayload,
        traceId: String,
        routeMode: String
    ) {
        var goalResult: GoalResultPayload? = null
        try {
            updateNotification("执行任务 ${taskId.take(8)}…")

            // ── 统一通过 LocalGoalExecutor 处理（与 goal_execution 路径一致）─────────
            // 将 TaskAssignPayload 规范化为 GoalExecutionPayload，
            // 经过 GoalNormalizer.normalize() 清洗自然语言指令 + 合并 constraints，
            // 保证 task_assign 和 goal_execution 执行相同的规范化逻辑。
            val goalPayload = GoalExecutionPayload(
                goal = payload.goal,
                task_id = taskId,
                group_id = null,
                subtask_index = null,
                max_steps = payload.max_steps,
                timeout_ms = 0L,
                constraints = payload.constraints,
            )
            val rawResult = UFOGalaxyApplication.localGoalExecutor.executeGoal(goalPayload)
            goalResult = rawResult.copy(
                correlation_id = taskId,
                device_id = localDeviceId,
                device_role = settings.deviceRole,
                latency_ms = rawResult.latency_ms ?: 0L,
            )

            // ── 通过 GOAL_EXECUTION_RESULT 回传（与 goal_execution 路径一致）────────
            // Android → Gateway 的结果回传统一使用 GOAL_EXECUTION_RESULT，
            // 与 goal_execution / parallel_subtask 共用同一 handler（_handle_goal_execution_result）。
            sendGoalResult(goalResult, traceId, routeMode)
            Log.i(
                TAG,
                "GOAL_EXECUTION_RESULT(task_assign) 已回传 task_id=$taskId " +
                    "status=${goalResult.status} latency=${goalResult.latency_ms}ms " +
                    "trace_id=$traceId route_mode=$routeMode"
            )
            updateNotification("任务 ${taskId.take(8)}: ${goalResult.status}")
        } catch (err: Exception) {
            Log.e(TAG, "executeLocalTaskAssign 执行失败 task_id=$taskId", err)
            val errorResult = GoalResultPayload(
                task_id = taskId,
                correlation_id = taskId,
                status = EdgeExecutor.STATUS_ERROR,
                error = err.message ?: "unknown",
                group_id = null,
                subtask_index = null,
                latency_ms = 0L,
                device_id = localDeviceId,
                device_role = settings.deviceRole
            )
            sendGoalResult(errorResult, traceId, routeMode)
        } finally {
            // Unblock local loop: always called even if edgeExecutor throws.
            UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
        }

        // Persist result to OpenClawd memory.
        goalResult?.let { result ->
            serviceScope.launch(Dispatchers.IO) {
                storeMemoryEntry(
                    taskId = taskId,
                    goal = payload.goal,
                    status = result.status,
                    summary = "task_assign (via LocalGoalExecutor): ${result.steps.size} step(s) executed",
                    steps = result.steps.map { "${it.action}: ${if (it.success) "ok" else (it.error ?: "fail")}" },
                    routeMode = routeMode
                )
            }
        }
    }

    /**
     * Handles a [MsgType.GOAL_EXECUTION] message by delegating to
     * [AutonomousExecutionPipeline], which gates execution behind
     * [AppSettings.goalExecutionEnabled] and sends the structured
     * [GoalResultPayload] back to the gateway.
     *
     * Enforces [GoalExecutionPayload.effectiveTimeoutMs] via [withTimeout]. On timeout
     * a structured [EdgeExecutor.STATUS_TIMEOUT] result is returned so the server can
     * still perform correct aggregation. Deregisters the task from [taskCancelRegistry]
     * in its `finally` block.
     *
     * Runs on [serviceScope] (IO dispatcher); all exceptions inside
     * [AutonomousExecutionPipeline] and [LocalGoalExecutor] are already mapped to
     * ERROR status.
     */
    private suspend fun handleGoalExecution(taskId: String, payloadJson: String) {
        val payload = try {
            gson.fromJson(payloadJson, GoalExecutionPayload::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "goal_execution payload 解析失败: ${e.message}", e)
            sendGoalError(taskId, null, null, "bad_payload: ${e.message}")
            taskCancelRegistry.deregister(taskId)
            return
        }

        // Pause any running local LoopController session.
        UFOGalaxyApplication.runtimeController.onRemoteTaskStarted()

        val timeoutMs = payload.effectiveTimeoutMs
        var finalResult: GoalResultPayload? = null
        try {
            val result = withTimeout(timeoutMs) {
                UFOGalaxyApplication.autonomousExecutionPipeline.handleGoalExecution(payload)
            }
            if (isActive) {
                sendGoalResult(result)
                finalResult = result
                Log.i(TAG, "goal_result 已回传 task_id=$taskId status=${result.status} latency=${result.latency_ms}ms")
            }
        } catch (e: TimeoutCancellationException) {
            GalaxyLogger.log(
                GalaxyLogger.TAG_TASK_TIMEOUT,
                mapOf("task_id" to taskId, "timeout_ms" to timeoutMs, "type" to "goal_execution")
            )
            Log.w(TAG, "[TASK:TIMEOUT] goal_execution timed out task_id=$taskId timeout_ms=$timeoutMs")
            val timeoutResult = buildTimeoutGoalResult(taskId, payload, timeoutMs)
            sendGoalResult(timeoutResult)
            finalResult = timeoutResult
        } finally {
            taskCancelRegistry.deregister(taskId)
            // Unblock local loop.
            UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
        }

        // Persist to OpenClawd memory outside the try/finally to avoid blocking the finally.
        finalResult?.let { r ->
            serviceScope.launch(Dispatchers.IO) {
                storeMemoryEntry(
                    taskId = taskId,
                    goal = payload.goal,
                    status = r.status,
                    summary = "goal_execution: latency=${r.latency_ms}ms",
                    steps = r.steps.map { "${it.action}: ${if (it.success) "ok" else (it.error ?: "fail")}" },
                    routeMode = "cross_device"
                )
            }
        }
    }

    /**
     * Handles a [MsgType.PARALLEL_SUBTASK] message by delegating to
     * [AutonomousExecutionPipeline], which gates execution behind
     * [AppSettings.parallelExecutionEnabled] and sends the structured
     * [GoalResultPayload] back.
     *
     * Enforces [GoalExecutionPayload.effectiveTimeoutMs] via [withTimeout]. On timeout
     * a structured [EdgeExecutor.STATUS_TIMEOUT] result is returned. Deregisters the
     * task from [taskCancelRegistry] in its `finally` block.
     *
     * Runs on [serviceScope] (IO dispatcher); all exceptions inside
     * [AutonomousExecutionPipeline] and [LocalGoalExecutor] are already mapped to
     * ERROR status.
     */
    private suspend fun handleParallelSubtask(taskId: String, payloadJson: String) {
        val payload = try {
            gson.fromJson(payloadJson, GoalExecutionPayload::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "parallel_subtask payload 解析失败: ${e.message}", e)
            sendGoalError(taskId, null, null, "bad_payload: ${e.message}")
            taskCancelRegistry.deregister(taskId)
            return
        }

        // Pause any running local LoopController session.
        UFOGalaxyApplication.runtimeController.onRemoteTaskStarted()

        val timeoutMs = payload.effectiveTimeoutMs
        var finalResult: GoalResultPayload? = null
        try {
            val result = withTimeout(timeoutMs) {
                UFOGalaxyApplication.autonomousExecutionPipeline.handleParallelSubtask(payload)
            }
            if (isActive) {
                sendGoalResult(result)
                finalResult = result
                Log.i(
                    TAG,
                    "goal_result (parallel) 已回传 task_id=$taskId status=${result.status} " +
                        "group_id=${result.group_id} subtask_index=${result.subtask_index} " +
                        "latency=${result.latency_ms}ms"
                )
            }
        } catch (e: TimeoutCancellationException) {
            GalaxyLogger.log(
                GalaxyLogger.TAG_TASK_TIMEOUT,
                mapOf(
                    "task_id" to taskId, "timeout_ms" to timeoutMs,
                    "type" to "parallel_subtask",
                    "group_id" to payload.group_id, "subtask_index" to payload.subtask_index
                )
            )
            Log.w(
                TAG,
                "[TASK:TIMEOUT] parallel_subtask timed out task_id=$taskId " +
                    "group_id=${payload.group_id} subtask_index=${payload.subtask_index} timeout_ms=$timeoutMs"
            )
            val timeoutResult = buildTimeoutGoalResult(taskId, payload, timeoutMs)
            sendGoalResult(timeoutResult)
            finalResult = timeoutResult
        } finally {
            taskCancelRegistry.deregister(taskId)
            // Unblock local loop.
            UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
        }

        // Persist to OpenClawd memory.
        finalResult?.let { r ->
            serviceScope.launch(Dispatchers.IO) {
                storeMemoryEntry(
                    taskId = taskId,
                    goal = payload.goal,
                    status = r.status,
                    summary = "parallel_subtask idx=${payload.subtask_index}: latency=${r.latency_ms}ms",
                    steps = r.steps.map { "${it.action}: ${if (it.success) "ok" else (it.error ?: "fail")}" },
                    routeMode = "cross_device"
                )
            }
        }
    }

    /**
     * Handles a [MsgType.TASK_CANCEL] request.
     *
     * Looks up the task in [taskCancelRegistry]:
     * - If found: cancels the coroutine and sends a `cancelled` [CancelResultPayload].
     * - If not found: the task already completed or never existed; sends a `no_op` reply.
     *
     * This operation is idempotent — repeated cancel requests for the same task_id will
     * return `no_op` after the first cancel succeeds.
     */
    private fun handleTaskCancel(taskId: String, cancelPayloadJson: String) {
        val cancelPayload = try {
            gson.fromJson(cancelPayloadJson, TaskCancelPayload::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "task_cancel payload 解析失败: ${e.message}", e)
            // Best-effort: still try to cancel by taskId
            TaskCancelPayload(task_id = taskId)
        }

        val wasRunning = taskCancelRegistry.cancel(taskId)

        if (wasRunning) {
            GalaxyLogger.log(
                GalaxyLogger.TAG_TASK_CANCEL,
                mapOf(
                    "task_id" to taskId,
                    "group_id" to cancelPayload.group_id,
                    "subtask_index" to cancelPayload.subtask_index,
                    "was_running" to true
                )
            )
            Log.i(TAG, "[TASK:CANCEL] 已取消运行中的任务 task_id=$taskId")
        } else {
            GalaxyLogger.log(
                GalaxyLogger.TAG_TASK_CANCEL,
                mapOf("task_id" to taskId, "was_running" to false, "result" to "no_op")
            )
            Log.i(TAG, "[TASK:CANCEL] 任务未找到（已完成或未启动）task_id=$taskId")
        }

        val cancelResult = CancelResultPayload(
            task_id = taskId,
            correlation_id = taskId,
            status = if (wasRunning) EdgeExecutor.STATUS_CANCELLED else "no_op",
            was_running = wasRunning,
            group_id = cancelPayload.group_id,
            subtask_index = cancelPayload.subtask_index,
            device_id = localDeviceId,
            error = if (!wasRunning) "task not found or already completed" else null
        )
        val envelope = AipMessage(
            type = MsgType.CANCEL_RESULT,
            payload = cancelResult,
            correlation_id = taskId,
            device_id = localDeviceId,
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = java.util.UUID.randomUUID().toString()
        )
        webSocketClient.sendJson(gson.toJson(envelope))
    }

    /** Sends a [GoalResultPayload] wrapped in an AIP v3 envelope. */
    private fun sendGoalResult(result: GoalResultPayload) {
        val envelope = AipMessage(
            type = MsgType.GOAL_RESULT,
            payload = result,
            correlation_id = result.task_id,
            device_id = result.device_id.ifEmpty { localDeviceId },
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = java.util.UUID.randomUUID().toString()
        )
        webSocketClient.sendJson(gson.toJson(envelope))
    }

    /**
     * Sends a [GoalResultPayload] as a [MsgType.GOAL_EXECUTION_RESULT] envelope with
     * full trace context (trace_id + route_mode).
     *
     * Used for:
     * - task_assign → GOAL_EXECUTION_RESULT（与 goal_execution / parallel_subtask 统一）
     * - goal_execution → GOAL_EXECUTION_RESULT
     * - parallel_subtask → GOAL_EXECUTION_RESULT
     */
    private fun sendGoalResult(result: GoalResultPayload, traceId: String, routeMode: String) {
        val envelope = AipMessage(
            type = MsgType.GOAL_EXECUTION_RESULT,
            payload = result,
            correlation_id = result.correlation_id ?: result.task_id,
            device_id = result.device_id.ifEmpty { localDeviceId },
            trace_id = traceId,
            route_mode = routeMode,
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = java.util.UUID.randomUUID().toString()
        )
        webSocketClient.sendJson(gson.toJson(envelope))
    }

    /** Sends an error [GoalResultPayload] when payload parsing fails. */
    private fun sendGoalError(
        taskId: String,
        groupId: String?,
        subtaskIndex: Int?,
        errorMsg: String
    ) {
        val errorResult = GoalResultPayload(
            task_id = taskId,
            correlation_id = taskId,
            status = com.ufo.galaxy.agent.EdgeExecutor.STATUS_ERROR,
            error = errorMsg,
            group_id = groupId,
            subtask_index = subtaskIndex,
            latency_ms = 0L,
            device_id = localDeviceId
        )
        sendGoalResult(errorResult)
    }

    /**
     * Builds a standardised timeout [GoalResultPayload].
     * All required aggregation fields (correlation_id, device_id, group_id,
     * subtask_index) are populated so the gateway can still converge results.
     */
    private fun buildTimeoutGoalResult(
        taskId: String,
        payload: GoalExecutionPayload,
        timeoutMs: Long
    ) = GoalResultPayload(
        task_id = taskId,
        correlation_id = taskId,
        status = EdgeExecutor.STATUS_TIMEOUT,
        error = "Task exceeded timeout of ${timeoutMs}ms",
        group_id = payload.group_id,
        subtask_index = payload.subtask_index,
        latency_ms = timeoutMs,
        device_id = localDeviceId
    )

    /**
     * 回传 task_result 错误（payload 解析失败时使用）。
     */
    private fun sendTaskError(taskId: String, errorMsg: String, traceId: String? = null) {
        val errorResult = TaskResultPayload(
            task_id = taskId,
            correlation_id = taskId,
            status = com.ufo.galaxy.agent.EdgeExecutor.STATUS_ERROR,
            error = errorMsg,
            device_id = localDeviceId,
            trace_id = traceId,
            result_summary = "error: $errorMsg"
        )
        val envelope = AipMessage(
            type = MsgType.TASK_RESULT,
            payload = errorResult,
            correlation_id = taskId,
            device_id = localDeviceId,
            trace_id = traceId,
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = java.util.UUID.randomUUID().toString()
        )
        webSocketClient.sendJson(gson.toJson(envelope))
    }

    /**
     * Checks whether all required model files are present and, if any are missing or
     * corrupted, enqueues their downloads via [ModelDownloader].
     *
     * Download URLs are configured in [com.ufo.galaxy.model.ModelAssetManager] companion
     * object constants. When a URL is empty the download is silently skipped and the
     * caller must install the file manually before inference is possible.
     *
     * This method suspends until all enqueued downloads finish. It can also be called
     * directly from UI code to let the user trigger a re-download on demand.
     */
    suspend fun ensureModels() {
        val assetManager = UFOGalaxyApplication.modelAssetManager
        val downloader = UFOGalaxyApplication.modelDownloader

        // Re-verify file system state before deciding what to download.
        assetManager.verifyAll()

        val specs = assetManager.downloadSpecsForMissing()
        if (specs.isEmpty()) {
            Log.d(TAG, "ensureModels: all model files present, no download needed")
            return
        }

        Log.i(TAG, "ensureModels: downloading ${specs.size} missing/corrupted model file(s)")
        for (spec in specs) {
            var lastLoggedPct = -1
            val ok = downloader.downloadSync(spec) { status ->
                when (status) {
                    is ModelDownloader.DownloadStatus.Progress -> {
                        // Log at most once per 10% to avoid flooding logcat.
                        if (status.totalBytes > 0) {
                            val pct = (status.bytesDownloaded * 10 / status.totalBytes).toInt()
                            if (pct != lastLoggedPct) {
                                lastLoggedPct = pct
                                Log.d(TAG, "Downloading ${spec.modelId}: ${pct * 10}%")
                            }
                        }
                    }
                    is ModelDownloader.DownloadStatus.Success ->
                        Log.i(TAG, "Downloaded ${spec.modelId} → ${status.file.absolutePath}")
                    is ModelDownloader.DownloadStatus.Failure ->
                        Log.e(TAG, "Download failed for ${spec.modelId}: ${status.error}")
                }
            }
            if (!ok) {
                Log.e(TAG, "ensureModels: failed to download ${spec.modelId}; inference may be unavailable")
            }
        }
        // Refresh status after downloads complete.
        assetManager.verifyAll()
    }

    /**
     * Pre-warms the MobileVLM and SeeClick inference servers before full model loading.
     * Sends a lightweight health ping to each server to establish a warm TCP connection
     * and surface any startup failures early.
     */
    private fun prewarmServices() {
        Log.i(TAG, "预热推理服务...")
        val plannerReady = UFOGalaxyApplication.plannerService.prewarm()
        val groundingReady = UFOGalaxyApplication.groundingService.prewarm()
        Log.i(TAG, "预热完成: planner=$plannerReady grounding=$groundingReady")
    }

    /**
     * 加载 MobileVLM 规划器和 SeeClick grounding 模型。
     * 加载结果通过 setModelCapabilities 通知 gateway。
     * Only advertises capabilities when both models are loaded.
     *
     * Persists [localModelEnabled] to [AppSettings] and updates [GalaxyWebSocketClient]
     * device metadata so the capability_report reflects the real model state.
     */
    private fun loadModels() {
        Log.i(TAG, "开始加载本地模型...")
        val plannerLoaded = UFOGalaxyApplication.plannerService.loadModel()
        val groundingLoaded = UFOGalaxyApplication.groundingService.loadModel()
        Log.i(TAG, "模型加载完成: planner=$plannerLoaded grounding=$groundingLoaded")

        val assetManager = UFOGalaxyApplication.modelAssetManager
        if (plannerLoaded) assetManager.markLoaded(com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_MOBILEVLM)
        else assetManager.markUnloaded(com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_MOBILEVLM)
        if (groundingLoaded) assetManager.markLoaded(com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_SEECLICK)
        else assetManager.markUnloaded(com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_SEECLICK)

        // Only advertise low-level capabilities when all models are loaded.
        val lowLevelCaps = mutableListOf<String>()
        if (plannerLoaded) lowLevelCaps.add("local_planning")
        if (groundingLoaded) lowLevelCaps.add("local_grounding")
        webSocketClient.setModelCapabilities(lowLevelCaps)

        // Persist actual model state and update metadata from AppSettings as the source
        // of truth. `local_model_enabled` reflects whether the inference servers have loaded
        // the models. `model_ready` (file-level readiness) is set by ReadinessChecker.checkAll()
        // called in the onStartCommand coroutine after this method returns; we do not set it
        // here to avoid a two-sources-of-truth conflict.
        val localModelEnabled = plannerLoaded && groundingLoaded
        val settings = UFOGalaxyApplication.appSettings
        settings.localModelEnabled = localModelEnabled

        webSocketClient.setHighLevelCapabilities(
            listOf(
                "autonomous_goal_execution",
                "local_task_planning",
                "local_ui_reasoning",
                "cross_device_coordination",
                "local_model_inference"
            )
        )
        webSocketClient.setDeviceMetadata(
            mapOf(
                "goal_execution_enabled" to settings.goalExecutionEnabled,
                "local_model_enabled" to localModelEnabled,
                "cross_device_enabled" to settings.crossDeviceEnabled,
                "parallel_execution_enabled" to settings.parallelExecutionEnabled,
                "device_role" to settings.deviceRole,
                "model_ready" to settings.modelReady,
                "accessibility_ready" to settings.accessibilityReady,
                "overlay_ready" to settings.overlayReady
            )
        )
        Log.i(TAG, "已更新模型能力: lowLevel=$lowLevelCaps localModelEnabled=$localModelEnabled modelReady=${settings.modelReady}")
    }

    /**
     * 卸载本地模型以释放设备内存。
     */
    private fun unloadModels() {
        UFOGalaxyApplication.plannerService.unloadModel()
        UFOGalaxyApplication.groundingService.unloadModel()
        UFOGalaxyApplication.modelAssetManager.markUnloaded(com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_MOBILEVLM)
        UFOGalaxyApplication.modelAssetManager.markUnloaded(com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_SEECLICK)
        Log.i(TAG, "本地模型已卸载")
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, UFOGalaxyApplication.CHANNEL_SERVICE)
            .setContentTitle("UFO Galaxy")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    /**
     * 更新通知
     */
    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * 获取连接状态
     */
    fun isConnected(): Boolean = webSocketClient.isConnected()

    /**
     * Persists a task result to the OpenClawd memory store via [OpenClawdMemoryBackflow].
     *
     * This is a best-effort, fire-and-forget operation: errors are logged but never
     * re-thrown. All cross-device task results (task_assign, goal_execution,
     * parallel_subtask) are stored with [routeMode] = "cross_device".
     *
     * The [route_mode] field lets the gateway and memory indexer distinguish between
     * locally-executed tasks and tasks dispatched via the AIP v3 cross-device pipeline.
     */
    private fun storeMemoryEntry(
        taskId: String,
        goal: String,
        status: String,
        summary: String,
        steps: List<String> = emptyList(),
        routeMode: String = "cross_device"
    ) {
        try {
            val restBaseUrl = UFOGalaxyApplication.appSettings.restBaseUrl
            val backflow = OpenClawdMemoryBackflow(restBaseUrl = restBaseUrl)
            val entry = MemoryEntry(
                task_id = taskId,
                goal = goal,
                status = status,
                summary = summary,
                steps = steps,
                route_mode = routeMode
            )
            val ok = backflow.store(entry)
            Log.d(TAG, "[MEMORY] storeMemoryEntry task_id=$taskId status=$status ok=$ok")
        } catch (e: Exception) {
            Log.w(TAG, "[MEMORY] storeMemoryEntry failed task_id=$taskId: ${e.message}")
        }
    }
}

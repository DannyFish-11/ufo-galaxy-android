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
import com.ufo.galaxy.agent.DelegatedRuntimeReceiver
import com.ufo.galaxy.agent.DelegatedTakeoverExecutor
import com.ufo.galaxy.agent.GoalExecutionPipeline
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.TaskCancelRegistry
import com.ufo.galaxy.agent.TakeoverRequestEnvelope
import com.ufo.galaxy.agent.TakeoverResponseEnvelope
import com.ufo.galaxy.agent.TakeoverHandlingResult
import com.ufo.galaxy.runtime.DelegatedExecutionSignal
import com.ufo.galaxy.runtime.DelegatedExecutionSignalSink
import com.ufo.galaxy.runtime.toOutboundPayload
import com.ufo.galaxy.runtime.SourceRuntimePosture
import com.ufo.galaxy.runtime.LocalRuntimeContext
import com.ufo.galaxy.runtime.RuntimeHostDescriptor
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.AckPayload
import com.ufo.galaxy.protocol.CancelResultPayload
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.HybridDegradePayload
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

        // ── Route-mode constants ──────────────────────────────────────────────
        /**
         * Route mode for gateway-delivered tasks (goal_execution, parallel_subtask, task_assign
         * via bridge). All results from these paths carry this value so the main-repo
         * session-truth layer can correlate results by route without re-parsing envelopes.
         *
         * Aliased from [AgentRuntimeBridge.ROUTE_MODE_CROSS_DEVICE] to keep intra-class
         * usages concise and to document that this service always treats its inbound
         * gateway tasks as cross-device-routed.
         */
        const val ROUTE_MODE_CROSS_DEVICE = AgentRuntimeBridge.ROUTE_MODE_CROSS_DEVICE

        // ── PR-3: Canonical takeover defaults ─────────────────────────────────
        /**
         * Default maximum number of goal-execution steps allowed inside a single
         * takeover session.  Consumers may override this per-request; this constant
         * acts as the safe fallback when no explicit limit is supplied.
         */
        const val TAKEOVER_DEFAULT_MAX_STEPS = 10

        /**
         * Default timeout in milliseconds for a single takeover session.
         * `0L` means "no timeout" — the takeover runs until it completes or is
         * cancelled externally.  Consumers may supply a positive value to impose a
         * wall-clock limit.
         */
        const val TAKEOVER_DEFAULT_TIMEOUT_MS = 0L
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

    // ── PR-3: Canonical takeover state ────────────────────────────────────────

    /**
     * The `takeover_id` of the takeover request currently being processed, or `null`
     * when no takeover is active.  Written on the service's IO dispatcher; volatile
     * for safe reads from any thread.
     *
     * Used by [TakeoverEligibilityAssessor] to block concurrent takeovers: the main
     * runtime must receive a [TakeoverResponseEnvelope] (accepted or rejected) before
     * a new [MsgType.TAKEOVER_REQUEST] will be accepted.
     */
    @Volatile
    private var activeTakeoverId: String? = null

    /** Canonical assessor that evaluates takeover eligibility based on device readiness. */
    private val takeoverEligibilityAssessor: TakeoverEligibilityAssessor by lazy {
        TakeoverEligibilityAssessor(UFOGalaxyApplication.appSettings)
    }

    /**
     * Canonical gate for delegated runtime receipt under an attached session (PR-8).
     *
     * Called inside [handleTakeoverRequest] after [TakeoverEligibilityAssessor] confirms
     * device readiness, to enforce that delegated work is accepted only when an explicit
     * [com.ufo.galaxy.runtime.AttachedRuntimeSession] is active.
     */
    private val delegatedRuntimeReceiver = DelegatedRuntimeReceiver()

    /**
     * Signal sink for delegated-execution lifecycle events (PR-12 / PR-16).
     *
     * Receives [com.ufo.galaxy.runtime.DelegatedExecutionSignal] events (ACK / PROGRESS /
     * RESULT / TIMEOUT / CANCELLED) emitted by [delegatedTakeoverExecutor] and:
     *  1. Writes them to structured telemetry via [GalaxyLogger] (observability).
     *  2. Transmits each signal as a [com.ufo.galaxy.protocol.MsgType.DELEGATED_EXECUTION_SIGNAL]
     *     AIP v3 message uplink via [webSocketClient] (PR-16 transport closure).
     *
     * Signal send failure never interrupts the delegated execution lifecycle — any error
     * is caught internally and logged for diagnostics observability.
     */
    private val delegatedSignalSink = DelegatedExecutionSignalSink { signal ->
        GalaxyLogger.log(TAG, signal.toMetadataMap())
        sendDelegatedExecutionSignal(signal)
    }

    /**
     * Canonical binding from accepted delegated receipt into local takeover execution (PR-12).
     *
     * [DelegatedTakeoverExecutor] manages the full lifecycle of an accepted
     * [com.ufo.galaxy.agent.DelegatedRuntimeUnit]: creates the
     * [com.ufo.galaxy.runtime.DelegatedExecutionTracker], emits ACK and RESULT signals via
     * [delegatedSignalSink], and returns a typed [DelegatedTakeoverExecutor.ExecutionOutcome]
     * so [handleTakeoverRequest] no longer needs inline try/catch logic.
     */
    private val delegatedTakeoverExecutor: DelegatedTakeoverExecutor by lazy {
        DelegatedTakeoverExecutor(
            pipeline = GoalExecutionPipeline { payload ->
                UFOGalaxyApplication.autonomousExecutionPipeline.handleGoalExecution(payload)
            },
            signalSink = delegatedSignalSink
        )
    }

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

            override fun onGoalExecution(taskId: String, goalPayloadJson: String, traceId: String?) {
                Log.i(TAG, "收到 goal_execution: task_id=$taskId trace_id=$traceId")
                GalaxyLogger.log(GalaxyLogger.TAG_TASK_RECV, mapOf("task_id" to taskId, "type" to "goal_execution", "trace_id" to (traceId ?: "")))
                serviceScope.launch {
                    // Register inside the coroutine to avoid any race between launch and register.
                    taskCancelRegistry.register(taskId, coroutineContext[Job]!!)
                    handleGoalExecution(taskId, goalPayloadJson, traceId)
                }
            }

            override fun onParallelSubtask(taskId: String, subtaskPayloadJson: String, traceId: String?) {
                Log.i(TAG, "收到 parallel_subtask: task_id=$taskId trace_id=$traceId")
                serviceScope.launch {
                    taskCancelRegistry.register(taskId, coroutineContext[Job]!!)
                    handleParallelSubtask(taskId, subtaskPayloadJson, traceId)
                }
            }

            override fun onTaskCancel(taskId: String, cancelPayloadJson: String) {
                Log.i(TAG, "收到 task_cancel: task_id=$taskId")
                serviceScope.launch {
                    handleTaskCancel(taskId, cancelPayloadJson)
                }
            }

            /**
             * Minimal-compat handler for PR-4 advanced capability channels.
             *
             * For types in [MsgType.ACK_ON_RECEIPT_TYPES] a structured ack is sent back so
             * the server knows the message was received. All other advanced types are logged
             * only.  Full business-logic implementations are deferred to future PRs.
             *
             * PR-3 addition: [MsgType.TAKEOVER_REQUEST] is dispatched to
             * [handleTakeoverRequest] which parses the canonical [TakeoverRequestEnvelope],
             * sends an ack, and returns a [TakeoverResponseEnvelope] rejection (full takeover
             * executor is deferred to PR-5).
             */
            override fun onAdvancedMessage(
                type: MsgType,
                messageId: String?,
                rawJson: String
            ) {
                Log.i(TAG, "[ADVANCED:RECV] type=${type.value} message_id=$messageId")
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "advanced_message_handled",
                        "type" to type.value,
                        "message_id" to (messageId ?: ""),
                        "handler" to "minimal_compat"
                    )
                )
                // Send ack for types that require delivery confirmation.
                if (type in MsgType.ACK_ON_RECEIPT_TYPES) {
                    serviceScope.launch {
                        sendAdvancedAck(type, messageId)
                    }
                }
                // HYBRID_EXECUTE: respond with a degrade payload because the full hybrid
                // executor is not yet implemented.
                if (type == MsgType.HYBRID_EXECUTE) {
                    serviceScope.launch {
                        sendHybridDegrade(rawJson)
                    }
                }
                // TAKEOVER_REQUEST (PR-5): parse the canonical TakeoverRequestEnvelope,
                // evaluate eligibility, and accept when all readiness conditions are met.
                // When accepted, dispatches the takeover goal to the execution pipeline
                // and includes runtime_host_id/formation_role in the acceptance response.
                // When not eligible, sends a structured rejection.
                if (type == MsgType.TAKEOVER_REQUEST) {
                    serviceScope.launch {
                        handleTakeoverRequest(messageId, rawJson)
                    }
                }
            }

            /**
             * Fallback for completely unrecognised message types.
             * Logs a structured warning so failures are never silent.
             */
            override fun onUnknownMessage(rawType: String?, rawJson: String) {
                Log.w(TAG, "[UNKNOWN:RECV] type=$rawType — unrecognised AIP v3 message type; ignored")
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "unknown_message_ignored",
                        "type" to (rawType ?: "null"),
                        "handler" to "fallback"
                    )
                )
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
                constraints = payload.constraints,
                sourceRuntimePosture = SourceRuntimePosture.fromValue(payload.source_runtime_posture)
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
        // Build a canonical LocalRuntimeContext at the ingress point so posture is
        // available as a typed, normalised value throughout this execution scope.
        val runtimeContext = LocalRuntimeContext.of(
            taskId = taskId,
            sessionId = null,
            sourceRuntimePosture = payload.source_runtime_posture,
            traceId = traceId,
            deviceRole = UFOGalaxyApplication.appSettings.deviceRole
        )
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "local_task_assign_ingress",
                "task_id" to taskId,
                "posture" to runtimeContext.sourceRuntimePosture,
                "is_join_runtime" to runtimeContext.isJoinRuntime,
                "trace_id" to traceId,
                "route_mode" to routeMode
            )
        )

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
                source_runtime_posture = payload.source_runtime_posture
            )
            val rawResult = UFOGalaxyApplication.localGoalExecutor.executeGoal(goalPayload)
            goalResult = rawResult.copy(
                correlation_id = taskId,
                device_id = localDeviceId,
                device_role = UFOGalaxyApplication.appSettings.deviceRole,
                latency_ms = rawResult.latency_ms ?: 0L,
                source_runtime_posture = payload.source_runtime_posture
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
                device_role = UFOGalaxyApplication.appSettings.deviceRole
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
     * Result envelopes are sent as [MsgType.GOAL_EXECUTION_RESULT] with full trace context
     * ([traceId] from the inbound envelope, route_mode="cross_device") so every hop in the
     * AIP v3 pipeline carries consistent correlation metadata.
     *
     * Runs on [serviceScope] (IO dispatcher); all exceptions inside
     * [AutonomousExecutionPipeline] and [LocalGoalExecutor] are already mapped to
     * ERROR status.
     *
     * @param taskId       task_id extracted from the inbound payload.
     * @param payloadJson  Raw JSON of the [GoalExecutionPayload].
     * @param inboundTraceId  trace_id from the inbound AIP envelope; null if absent.
     */
    private suspend fun handleGoalExecution(taskId: String, payloadJson: String, inboundTraceId: String?) {
        val payload = try {
            gson.fromJson(payloadJson, GoalExecutionPayload::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "goal_execution payload 解析失败: ${e.message}", e)
            val traceId = inboundTraceId ?: java.util.UUID.randomUUID().toString()
            sendGoalError(taskId, null, null, "bad_payload: ${e.message}", traceId, ROUTE_MODE_CROSS_DEVICE)
            taskCancelRegistry.deregister(taskId)
            return
        }

        // Pause any running local LoopController session.
        UFOGalaxyApplication.runtimeController.onRemoteTaskStarted()

        // Preserve inbound trace_id for full-chain correlation; generate only when absent.
        val traceId = inboundTraceId?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()

        val timeoutMs = payload.effectiveTimeoutMs
        var finalResult: GoalResultPayload? = null
        try {
            val result = withTimeout(timeoutMs) {
                UFOGalaxyApplication.autonomousExecutionPipeline.handleGoalExecution(payload)
            }
            if (isActive) {
                // Enrich result with posture and send with full trace context.
                val enriched = result.takeIf { it.source_runtime_posture != null }
                    ?: result.copy(source_runtime_posture = payload.source_runtime_posture)
                sendGoalResult(enriched, traceId, ROUTE_MODE_CROSS_DEVICE)
                finalResult = enriched
                Log.i(TAG, "goal_result 已回传 task_id=$taskId status=${enriched.status} latency=${enriched.latency_ms}ms trace_id=$traceId")
            }
        } catch (e: TimeoutCancellationException) {
            GalaxyLogger.log(
                GalaxyLogger.TAG_TASK_TIMEOUT,
                mapOf("task_id" to taskId, "timeout_ms" to timeoutMs, "type" to "goal_execution")
            )
            Log.w(TAG, "[TASK:TIMEOUT] goal_execution timed out task_id=$taskId timeout_ms=$timeoutMs")
            val timeoutResult = buildTimeoutGoalResult(taskId, payload, timeoutMs)
            sendGoalResult(timeoutResult, traceId, ROUTE_MODE_CROSS_DEVICE)
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
                    routeMode = ROUTE_MODE_CROSS_DEVICE
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
     * Result envelopes are sent as [MsgType.GOAL_EXECUTION_RESULT] with full trace context
     * ([traceId] from the inbound envelope, route_mode="cross_device") so every hop in the
     * AIP v3 pipeline carries consistent correlation metadata.
     *
     * Runs on [serviceScope] (IO dispatcher); all exceptions inside
     * [AutonomousExecutionPipeline] and [LocalGoalExecutor] are already mapped to
     * ERROR status.
     *
     * @param taskId       task_id extracted from the inbound payload.
     * @param payloadJson  Raw JSON of the [GoalExecutionPayload].
     * @param inboundTraceId  trace_id from the inbound AIP envelope; null if absent.
     */
    private suspend fun handleParallelSubtask(taskId: String, payloadJson: String, inboundTraceId: String?) {
        val payload = try {
            gson.fromJson(payloadJson, GoalExecutionPayload::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "parallel_subtask payload 解析失败: ${e.message}", e)
            val traceId = inboundTraceId ?: java.util.UUID.randomUUID().toString()
            sendGoalError(taskId, null, null, "bad_payload: ${e.message}", traceId, ROUTE_MODE_CROSS_DEVICE)
            taskCancelRegistry.deregister(taskId)
            return
        }

        // Pause any running local LoopController session.
        UFOGalaxyApplication.runtimeController.onRemoteTaskStarted()

        // Preserve inbound trace_id for full-chain correlation; generate only when absent.
        val traceId = inboundTraceId?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()

        val timeoutMs = payload.effectiveTimeoutMs
        var finalResult: GoalResultPayload? = null
        try {
            val result = withTimeout(timeoutMs) {
                UFOGalaxyApplication.autonomousExecutionPipeline.handleParallelSubtask(payload)
            }
            if (isActive) {
                // Enrich result with posture and send with full trace context.
                val enriched = result.takeIf { it.source_runtime_posture != null }
                    ?: result.copy(source_runtime_posture = payload.source_runtime_posture)
                sendGoalResult(enriched, traceId, ROUTE_MODE_CROSS_DEVICE)
                finalResult = enriched
                Log.i(
                    TAG,
                    "goal_result (parallel) 已回传 task_id=$taskId status=${enriched.status} " +
                        "group_id=${enriched.group_id} subtask_index=${enriched.subtask_index} " +
                        "latency=${enriched.latency_ms}ms trace_id=$traceId"
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
            sendGoalResult(timeoutResult, traceId, ROUTE_MODE_CROSS_DEVICE)
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
                    routeMode = ROUTE_MODE_CROSS_DEVICE
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
        errorMsg: String,
        traceId: String? = null,
        routeMode: String? = null
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
        if (traceId != null && routeMode != null) {
            sendGoalResult(errorResult, traceId, routeMode)
        } else {
            sendGoalResult(errorResult)
        }
    }

    /**
     * Builds a standardised timeout [GoalResultPayload].
     * All required aggregation fields (correlation_id, device_id, group_id,
     * subtask_index, source_runtime_posture) are populated so the gateway can
     * still converge results and correlate them with the originating request.
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
        device_id = localDeviceId,
        source_runtime_posture = payload.source_runtime_posture
    )

    // ── PR-4 advanced-capability minimal helpers ──────────────────────────────────────────

    /**
     * Sends an [AckPayload] confirming receipt of an advanced-capability message.
     *
     * Called for message types in [MsgType.ACK_ON_RECEIPT_TYPES] that require delivery
     * confirmation even though full business logic is not yet implemented.
     *
     * @param type      The type of the inbound message being acknowledged.
     * @param messageId The `message_id` from the inbound AIP v3 envelope; used as the
     *                  payload's [AckPayload.message_id]. Falls back to a new UUID when null.
     */
    private fun sendAdvancedAck(type: MsgType, messageId: String?) {
        val ackPayload = AckPayload(
            message_id = messageId ?: java.util.UUID.randomUUID().toString(),
            type_acked = type.value,
            device_id = localDeviceId
        )
        val envelope = AipMessage(
            type = MsgType.ACK,
            payload = ackPayload,
            device_id = localDeviceId,
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = java.util.UUID.randomUUID().toString()
        )
        val sent = webSocketClient.sendJson(gson.toJson(envelope))
        Log.d(TAG, "[ADVANCED:ACK] type_acked=${type.value} message_id=${ackPayload.message_id} sent=$sent")
    }

    /**
     * Transmits a [DelegatedExecutionSignal] as a [MsgType.DELEGATED_EXECUTION_SIGNAL]
     * AIP v3 message uplink (PR-16).
     *
     * Called by [delegatedSignalSink] after structured logging so that every ACK /
     * PROGRESS / RESULT / TIMEOUT / CANCELLED lifecycle event is delivered to the
     * main-repo host as a stable, parseable outbound message.
     *
     * Send failure **never throws** — any error is caught and logged for diagnostics
     * observability so the executor's lifecycle progression is not interrupted.
     *
     * @param signal The [DelegatedExecutionSignal] emitted by [delegatedTakeoverExecutor].
     */
    private fun sendDelegatedExecutionSignal(signal: DelegatedExecutionSignal) {
        try {
            val payload = signal.toOutboundPayload(deviceId = localDeviceId)
            val envelope = AipMessage(
                type = MsgType.DELEGATED_EXECUTION_SIGNAL,
                payload = payload,
                device_id = localDeviceId,
                trace_id = signal.traceId,
                correlation_id = signal.taskId,
                idempotency_key = signal.signalId,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId
            )
            val sent = webSocketClient.sendJson(gson.toJson(envelope))
            if (sent) {
                Log.d(TAG, "[DELEGATED_SIGNAL] sent signal_id=${signal.signalId} kind=${signal.kind.wireValue} task_id=${signal.taskId}")
            } else {
                Log.w(
                    TAG,
                    "[DELEGATED_SIGNAL] send failed signal_id=${signal.signalId} kind=${signal.kind.wireValue} task_id=${signal.taskId}"
                )
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "delegated_signal_send_failed",
                        "signal_id" to signal.signalId,
                        "signal_kind" to signal.kind.wireValue,
                        "task_id" to signal.taskId,
                        "trace_id" to signal.traceId,
                        "emission_seq" to signal.emissionSeq
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DELEGATED_SIGNAL] unexpected error sending signal signal_id=${signal.signalId}: ${e.message}", e)
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "delegated_signal_send_error",
                    "signal_id" to signal.signalId,
                    "signal_kind" to signal.kind.wireValue,
                    "task_id" to signal.taskId,
                    "error" to (e.message ?: "unknown")
                )
            )
        }
    }

    /**
     * Sends a [HybridDegradePayload] in response to a [MsgType.HYBRID_EXECUTE] message.
     *
     * Called when the full hybrid executor is not yet implemented; informs the gateway that
     * the device has downgraded to local-only mode for this task.
     *
     * @param rawHybridJson The raw JSON of the inbound hybrid_execute envelope, used to
     *                      extract [task_id] for the degrade reply.
     */
    private fun sendHybridDegrade(rawHybridJson: String) {
        val taskId = try {
            gson.fromJson(rawHybridJson, com.google.gson.JsonObject::class.java)
                ?.getAsJsonObject("payload")?.get("task_id")?.asString ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "[ADVANCED:HYBRID_DEGRADE] failed to extract task_id from hybrid_execute payload: ${e.message}")
            ""
        }

        val degradePayload = HybridDegradePayload(
            task_id = taskId,
            correlation_id = taskId.ifEmpty { null },
            reason = "hybrid_executor_not_implemented",
            fallback_mode = "local_only",
            device_id = localDeviceId
        )
        val envelope = AipMessage(
            type = MsgType.HYBRID_DEGRADE,
            payload = degradePayload,
            correlation_id = taskId.ifEmpty { null },
            device_id = localDeviceId,
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = java.util.UUID.randomUUID().toString()
        )
        val sent = webSocketClient.sendJson(gson.toJson(envelope))
        Log.i(TAG, "[ADVANCED:HYBRID_DEGRADE] task_id=$taskId sent=$sent")
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "hybrid_degrade_sent",
                "task_id" to taskId,
                "reason" to "hybrid_executor_not_implemented"
            )
        )
    }

    // ── PR-3: Canonical takeover request/response path ────────────────────────────────────

    /**
     * Handles an inbound [MsgType.TAKEOVER_REQUEST] message via the canonical path.
     *
     * Parses the raw JSON into a [TakeoverRequestEnvelope], evaluates device eligibility
     * via [TakeoverEligibilityAssessor], logs structured metadata (including posture for
     * correlation), and sends a [TakeoverResponseEnvelope] back to the gateway.
     *
     * ## Decision flow
     * 1. Parse the inbound JSON into a [TakeoverRequestEnvelope].
     * 2. Invoke [TakeoverEligibilityAssessor.assess] with the current [activeTakeoverId].
     * 3. If **not eligible**: send rejection with the assessor's structured reason and return.
     * 4. If **eligible** but full takeover executor is deferred: send rejection with
     *    `"takeover_executor_not_implemented"` (PR-5 TODO) and return.
     *
     * ## Concurrent-takeover protection
     * [activeTakeoverId] is set to the incoming `takeover_id` while the request is being
     * processed and cleared when the response has been sent.  This prevents a second inbound
     * [MsgType.TAKEOVER_REQUEST] from being accepted while one is already in progress.
     *
     * ## PR-3 scope
     * The delivery ack is sent by the generic [MsgType.ACK_ON_RECEIPT_TYPES] path in
     * [onAdvancedMessage] before this function is called.  This function sends the
     * richer [MsgType.TAKEOVER_RESPONSE] envelope which carries the structured decision
     * so the main runtime can update its session truth immediately.
     *
     * @param messageId Optional message_id from the inbound AIP v3 envelope.
     * @param rawJson   Raw JSON string of the inbound takeover_request message.
     */
    private fun handleTakeoverRequest(messageId: String?, rawJson: String): TakeoverHandlingResult {
        val envelope = try {
            val jsonObj = gson.fromJson(rawJson, com.google.gson.JsonObject::class.java)
            val payload = jsonObj?.getAsJsonObject("payload") ?: jsonObj
            TakeoverRequestEnvelope(
                takeover_id = payload?.get("takeover_id")?.asString
                    ?: (messageId ?: java.util.UUID.randomUUID().toString()),
                task_id = payload?.get("task_id")?.asString ?: "",
                trace_id = payload?.get("trace_id")?.asString ?: java.util.UUID.randomUUID().toString(),
                goal = payload?.get("goal")?.asString ?: "",
                source_device_id = payload?.get("source_device_id")?.asString,
                source_runtime_posture = payload?.get("source_runtime_posture")?.asString,
                exec_mode = payload?.get("exec_mode")?.asString ?: AgentRuntimeBridge.EXEC_MODE_REMOTE,
                route_mode = payload?.get("route_mode")?.asString ?: AgentRuntimeBridge.ROUTE_MODE_CROSS_DEVICE,
                session_id = payload?.get("session_id")?.asString,
                runtime_session_id = payload?.get("runtime_session_id")?.asString,
                checkpoint = payload?.get("checkpoint")?.asString
            )
        } catch (e: Exception) {
            Log.w(TAG, "[PR3:TAKEOVER] Failed to parse takeover_request: ${e.message}")
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "takeover_request_parse_error",
                    "error" to (e.message ?: "unknown"),
                    "message_id" to (messageId ?: "")
                )
            )
            // Return a safe failure result; ack was already sent by the generic path.
            return TakeoverHandlingResult(
                takeoverId = messageId ?: "unknown",
                taskId = "",
                traceId = "",
                accepted = false,
                reason = "parse_error: ${e.message}"
            )
        }

        val resolvedPosture = envelope.resolvedPosture

        // ── Eligibility assessment (canonical PR-3 path) ──────────────────────
        // Capture the existing active takeover before setting the new one.
        // The assessor uses the captured value to detect concurrent takeovers.
        val existingActiveTakeoverId = activeTakeoverId
        // Mark this request as in-flight so any concurrent inbound request is blocked.
        activeTakeoverId = envelope.takeover_id
        try {
            val eligibility = takeoverEligibilityAssessor.assess(
                envelope = envelope,
                activeTakeoverId = existingActiveTakeoverId
            )

            Log.i(
                TAG,
                "[PR3:TAKEOVER] takeover_request received takeover_id=${envelope.takeover_id} " +
                    "task_id=${envelope.task_id} trace_id=${envelope.trace_id} " +
                    "source_device=${envelope.source_device_id} posture=$resolvedPosture " +
                    "eligible=${eligibility.eligible} reason=${eligibility.reason}"
            )
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "takeover_request_received",
                    "takeover_id" to envelope.takeover_id,
                    "task_id" to envelope.task_id,
                    "trace_id" to envelope.trace_id,
                    "source_device_id" to (envelope.source_device_id ?: ""),
                    "source_runtime_posture" to resolvedPosture,
                    "exec_mode" to envelope.exec_mode,
                    "route_mode" to envelope.route_mode,
                    "eligible" to eligibility.eligible,
                    "eligibility_reason" to eligibility.reason
                )
            )

            // If the device is not eligible, reject with the assessor's specific reason so
            // the main runtime can distinguish device-not-ready from executor-not-implemented.
            if (!eligibility.eligible) {
                sendTakeoverResponse(
                    TakeoverResponseEnvelope(
                        takeover_id = envelope.takeover_id,
                        task_id = envelope.task_id,
                        trace_id = envelope.trace_id,
                        accepted = false,
                        rejection_reason = eligibility.reason,
                        device_id = localDeviceId,
                        runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                        source_runtime_posture = envelope.source_runtime_posture,
                        exec_mode = envelope.exec_mode
                    )
                )
                return TakeoverHandlingResult(
                    takeoverId = envelope.takeover_id,
                    taskId = envelope.task_id,
                    traceId = envelope.trace_id,
                    accepted = false,
                    reason = eligibility.reason
                )
            }

            // PR-8: Gate delegated receipt on an active AttachedRuntimeSession.
            // Device readiness (eligibility) is necessary but not sufficient: the session
            // must also be explicitly attached before Android accepts delegated work.
            val currentSession = UFOGalaxyApplication.runtimeController.attachedSession.value
            val receiptResult = delegatedRuntimeReceiver.receive(envelope, currentSession)
            if (receiptResult is DelegatedRuntimeReceiver.ReceiptResult.Rejected) {
                Log.w(
                    TAG,
                    "[PR8:DELEGATE] Delegated receipt rejected — no active attached session: " +
                        "takeover_id=${envelope.takeover_id} reason=${receiptResult.reason}"
                )
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "delegated_receipt_rejected",
                        "takeover_id" to envelope.takeover_id,
                        "task_id" to envelope.task_id,
                        "trace_id" to envelope.trace_id,
                        "rejection_outcome" to receiptResult.outcome.reason,
                        "reason" to receiptResult.reason
                    )
                )
                sendTakeoverResponse(
                    TakeoverResponseEnvelope(
                        takeover_id = envelope.takeover_id,
                        task_id = envelope.task_id,
                        trace_id = envelope.trace_id,
                        accepted = false,
                        rejection_reason = receiptResult.reason,
                        device_id = localDeviceId,
                        runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                        source_runtime_posture = envelope.source_runtime_posture,
                        exec_mode = envelope.exec_mode
                    )
                )
                return TakeoverHandlingResult(
                    takeoverId = envelope.takeover_id,
                    taskId = envelope.task_id,
                    traceId = envelope.trace_id,
                    accepted = false,
                    reason = receiptResult.reason
                )
            }
            // Session gate passed — extract the delegated unit and activation record.
            val delegatedUnit = (receiptResult as DelegatedRuntimeReceiver.ReceiptResult.Accepted).unit
            val activationRecord = receiptResult.record
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "delegated_receipt_accepted",
                    "takeover_id" to envelope.takeover_id,
                    "task_id" to envelope.task_id,
                    "trace_id" to envelope.trace_id,
                    "attached_session_id" to delegatedUnit.attachedSessionId,
                    "resolved_posture" to delegatedUnit.resolvedPosture,
                    "activation_status" to activationRecord.activationStatus.wireValue
                )
            )
            // PR-14: Record that a delegated execution has been accepted under the current
            // attached session.  This increments the session's delegatedExecutionCount without
            // re-creating the session or changing its identity — multiple tasks can flow
            // through the same session without any per-task session re-init.
            UFOGalaxyApplication.runtimeController.recordDelegatedExecutionAccepted()

            // PR-12: The executor owns all lifecycle transitions (PENDING → ACTIVATING →
            // ACTIVE → COMPLETED/FAILED); do not pre-advance the record here.

            // PR-5: Device is eligible — accept the takeover as a first-class runtime host.
            // Include runtime_host_id and formation_role in the acceptance response so the
            // main runtime can record this Android instance as a formal execution surface.
            val hostDescriptor = UFOGalaxyApplication.runtimeHostDescriptor
            val acceptanceResponse = TakeoverResponseEnvelope(
                takeover_id = envelope.takeover_id,
                task_id = envelope.task_id,
                trace_id = envelope.trace_id,
                accepted = true,
                rejection_reason = null,
                device_id = localDeviceId,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                source_runtime_posture = envelope.source_runtime_posture,
                exec_mode = envelope.exec_mode,
                runtime_host_id = hostDescriptor?.hostId,
                formation_role = hostDescriptor?.formationRole?.wireValue
            )
            sendTakeoverResponse(acceptanceResponse)

            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "takeover_accepted",
                    "takeover_id" to envelope.takeover_id,
                    "task_id" to envelope.task_id,
                    "trace_id" to envelope.trace_id,
                    "runtime_host_id" to (hostDescriptor?.hostId ?: ""),
                    "formation_role" to (hostDescriptor?.formationRole?.wireValue ?: ""),
                    "attached_session_id" to delegatedUnit.attachedSessionId,
                    "activation_status" to activationRecord.activationStatus.wireValue
                )
            )

            // PR-12/PR-13: Dispatch through the canonical delegated takeover executor.
            // DelegatedTakeoverExecutor manages the full lifecycle: creates the
            // DelegatedExecutionTracker, emits ACK/PROGRESS/RESULT signals, advances tracker
            // through PENDING → ACTIVATING → ACTIVE → COMPLETED/FAILED, and returns a typed
            // outcome.  PR-13 adds PROGRESS signal emission at ACTIVE and distinguishes
            // TIMEOUT/CANCELLED outcomes from generic FAILED.
            UFOGalaxyApplication.runtimeController.onRemoteTaskStarted()
            serviceScope.launch {
                try {
                    val outcome = delegatedTakeoverExecutor.execute(delegatedUnit, activationRecord)
                    when (outcome) {
                        is DelegatedTakeoverExecutor.ExecutionOutcome.Completed -> {
                            val enriched = outcome.goalResult.copy(
                                source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
                            )
                            sendGoalResult(enriched, envelope.trace_id, ROUTE_MODE_CROSS_DEVICE)
                            Log.i(
                                TAG,
                                "[PR13:TAKEOVER] goal_result sent takeover_id=${envelope.takeover_id} " +
                                    "task_id=${envelope.task_id} status=${enriched.status} " +
                                    "trace_id=${envelope.trace_id} " +
                                    "attached_session_id=${delegatedUnit.attachedSessionId} " +
                                    "steps=${outcome.tracker.stepCount}"
                            )
                        }
                        is DelegatedTakeoverExecutor.ExecutionOutcome.Failed -> {
                            Log.w(
                                TAG,
                                "[PR13:TAKEOVER] delegated execution failed takeover_id=${envelope.takeover_id} " +
                                    "task_id=${envelope.task_id} error=${outcome.error}"
                            )
                            sendGoalError(
                                envelope.task_id, null, null,
                                "takeover_error: ${outcome.error}", envelope.trace_id,
                                ROUTE_MODE_CROSS_DEVICE
                            )
                        }
                    }
                } finally {
                    UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
                }
            }

            return TakeoverHandlingResult(
                takeoverId = envelope.takeover_id,
                taskId = envelope.task_id,
                traceId = envelope.trace_id,
                accepted = true,
                reason = "accepted"
            )
        } finally {
            // Always clear the active takeover ID once we have sent our response so
            // subsequent requests are not incorrectly blocked.
            activeTakeoverId = null
        }
    }

    /**
     * Sends a [TakeoverResponseEnvelope] wrapped in an AIP v3 [MsgType.TAKEOVER_RESPONSE]
     * envelope to the gateway.
     *
     * The response carries the takeover decision (accepted / rejected), rejection reason,
     * and echoes [TakeoverResponseEnvelope.source_runtime_posture] for posture correlation
     * on the main-runtime side.
     *
     * @param response The populated [TakeoverResponseEnvelope] to send.
     */
    private fun sendTakeoverResponse(response: TakeoverResponseEnvelope) {
        val envelope = AipMessage(
            type = MsgType.TAKEOVER_RESPONSE,
            payload = response,
            correlation_id = response.task_id.ifEmpty { null },
            device_id = localDeviceId,
            trace_id = response.trace_id,
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = java.util.UUID.randomUUID().toString(),
            source_runtime_posture = response.source_runtime_posture
        )
        val sent = webSocketClient.sendJson(gson.toJson(envelope))
        Log.i(
            TAG,
            "[PR3:TAKEOVER] takeover_response sent takeover_id=${response.takeover_id} " +
                "task_id=${response.task_id} accepted=${response.accepted} " +
                "reason=${response.rejection_reason} sent=$sent"
        )
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "takeover_response_sent",
                "takeover_id" to response.takeover_id,
                "task_id" to response.task_id,
                "trace_id" to response.trace_id,
                "accepted" to response.accepted,
                "rejection_reason" to (response.rejection_reason ?: ""),
                "source_runtime_posture" to (response.source_runtime_posture ?: ""),
                "sent" to sent
            )
        )
    }

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

package com.ufo.galaxy.ui.viewmodel

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.data.ChatMessage
import com.ufo.galaxy.data.MessageRole
import com.ufo.galaxy.debug.LocalLoopDebugViewModel
import com.ufo.galaxy.input.InputRouter
import com.ufo.galaxy.local.LocalLoopOptions
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.runtime.RuntimeController
import com.ufo.galaxy.runtime.CrossDeviceSetupError
import com.ufo.galaxy.runtime.ExecutionRouteTag
import com.ufo.galaxy.runtime.TakeoverFallbackEvent
import com.ufo.galaxy.speech.SpeechInputManager
import com.ufo.galaxy.speech.SpeechState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * A single error entry recorded for the diagnostics panel.
 *
 * @param ts     Unix timestamp in milliseconds when the error was observed.
 * @param reason Human-readable error description.
 */
data class ErrorEntry(val ts: Long, val reason: String)

/**
 * A single task entry recorded for the diagnostics panel.
 *
 * @param ts      Unix timestamp in milliseconds when the task was received or completed.
 * @param taskId  The task identifier.
 * @param outcome Short outcome string (e.g. "received", "success", "failed", "timeout", "cancelled").
 */
data class TaskEntry(val ts: Long, val taskId: String, val outcome: String)

/**
 * 主界面 UI 状态
 */
data class MainUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val isScrollExpanded: Boolean = true,
    val isConnected: Boolean = false,
    val error: String? = null,
    val isListening: Boolean = false,
    val partialSpeechResult: String = "",
    /** Mirrors [AppSettings.crossDeviceEnabled]; drives the settings toggle in the UI. */
    val crossDeviceEnabled: Boolean = false,
    /** True when local model files are present and verified. */
    val modelReady: Boolean = false,
    /** True when the HardwareKeyListener accessibility service is enabled. */
    val accessibilityReady: Boolean = false,
    /** True when the SYSTEM_ALERT_WINDOW overlay permission is granted. */
    val overlayReady: Boolean = false,
    /** True when any readiness check has failed; autonomous execution may be limited. */
    val degradedMode: Boolean = false,
    /** Number of outgoing task results currently queued offline (read-only debug info). */
    val queueSize: Int = 0,
    /** Number of consecutive reconnect attempts since last successful connect (read-only debug info). */
    val reconnectAttempt: Int = 0,
    // ── Diagnostics (PR15) ───────────────────────────────────────────────────
    /** Task id of the most recently received task_assign or goal_execution. */
    val lastTaskId: String = "",
    /** Most recent error reason (from WS error or task result error field). */
    val lastErrorReason: String = "",
    /** True while the diagnostics panel is visible. */
    val showDiagnostics: Boolean = false,
    // ── Diagnostics UX (PR17) ────────────────────────────────────────────────
    /** Up to 5 most recent errors recorded in the current session, newest-last. */
    val recentErrors: List<ErrorEntry> = emptyList(),
    /** Up to 5 most recent task ids and their outcomes, newest-last. */
    val recentTaskIds: List<TaskEntry> = emptyList(),
    /** True when the active network connection appears reachable (WS connected). */
    val networkOk: Boolean = false,
    /** True when the OS battery-optimisation exemption has been granted for this app. */
    val batteryOptimizationsDisabled: Boolean = false,
    /**
     * Non-null when the cross-device runtime failed to register (network error or timeout).
     * The value is the human-readable failure reason. Set back to null via
     * [MainViewModel.clearRegistrationFailure] after the user dismisses the dialog.
     */
    val registrationFailure: String? = null,
    /**
     * PR-27 — Typed category of the most recent registration failure; non-null when
     * [registrationFailure] is also non-null.
     *
     * Consumers use this to present category-appropriate recovery actions in the
     * registration failure dialog:
     *  - [CrossDeviceSetupError.Category.CONFIGURATION] → "Open Settings" CTA.
     *  - [CrossDeviceSetupError.Category.NETWORK] → "Retry" CTA.
     *  - [CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED] → "Open Permissions" CTA.
     *
     * Reset to `null` together with [registrationFailure] by
     * [MainViewModel.clearRegistrationFailure].
     */
    val registrationFailureCategory: CrossDeviceSetupError.Category? = null,
    // ── Network settings (网络与诊断增强包) ──────────────────────────────────
    /** True while the network settings screen is shown. */
    val showNetworkSettings: Boolean = false,
    /** True while a network diagnostics run is in progress. */
    val isDiagnosticsRunning: Boolean = false,
    /** Text of the last completed diagnostics run; null if not yet run. */
    val diagnosticsReport: String? = null,
    // ── Local-loop debug panel (PR-G) ─────────────────────────────────────────
    /** True while the local-loop debug panel is visible. Debug-only entry point. */
    val showLocalLoopDebug: Boolean = false,
    // ── PR-29: Execution route tracking ──────────────────────────────────────
    /**
     * The execution route that produced the most recent task result, or `null` if no task
     * has completed yet since [MainViewModel] was created.
     *
     * Updated by [MainViewModel] whenever a result arrives via any path:
     *  - [ExecutionRouteTag.LOCAL] — local [com.ufo.galaxy.local.LocalLoopExecutor] result.
     *  - [ExecutionRouteTag.CROSS_DEVICE] — server message via WebSocket.
     *  - [ExecutionRouteTag.FALLBACK] — delegated takeover failure (TakeoverFallbackEvent).
     *  - [ExecutionRouteTag.DELEGATED] — successful delegated takeover completion.
     *
     * **Lifecycle**: persists across individual tasks within a session — it always reflects
     * the last completed task's route and is never reset between tasks.  It starts as `null`
     * and remains `null` until the first task result of any kind is received.
     *
     * Exposed as a diagnostic field; not shown in user-facing chat text.
     */
    val lastExecutionRoute: ExecutionRouteTag? = null,
    // ── PR-30: Operator-facing observability additions ────────────────────────
    /**
     * PR-30 — Per-route execution counts accumulated since [MainViewModel] was created.
     *
     * Incremented every time [lastExecutionRoute] is set.  Each entry maps an
     * [ExecutionRouteTag] to the number of results that arrived via that path during the
     * current session.  Zero-count entries are omitted (not present in the map).
     *
     * Exposed for operator-facing diagnostics (see [MainViewModel.buildDiagnosticsText])
     * and for regression tests that verify route-count bookkeeping.
     */
    val executionRouteCounts: Map<ExecutionRouteTag, Int> = emptyMap(),
    /**
     * PR-30 — Category of the **last** observed setup / registration failure, or `null`
     * when no failure has occurred in the current session.
     *
     * Unlike [registrationFailureCategory] — which is cleared together with
     * [registrationFailure] when the user dismisses the dialog — this field is **never
     * cleared**.  It provides a persistent operator-visible signal of the most recent
     * setup failure category, useful for diagnosing rollout issues without relying on
     * dialog-dismiss timing.
     *
     * Set by the [MainViewModel] observer of [RuntimeController.setupError].
     */
    val lastSetupFailureCategory: CrossDeviceSetupError.Category? = null,
    // ── PR-31: Rollout-control snapshot ──────────────────────────────────────
    /**
     * PR-31 — Snapshot of all rollout-control flags as reported by
     * [RuntimeController.rolloutControlSnapshot].
     *
     * Null before the first emission from [RuntimeController] (typically immediately after
     * the ViewModel observes the flow for the first time).  The snapshot is updated
     * whenever the underlying flags change (e.g. after [RuntimeController.applyKillSwitch]
     * or after settings are saved).
     *
     * Exposed for operator-facing diagnostics and regression tests.
     */
    val rolloutControlSnapshot: com.ufo.galaxy.runtime.RolloutControlSnapshot? = null
)

/**
 * 主界面 ViewModel
 * 管理聊天状态、与 Galaxy 服务器的通信和语音输入
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "MainViewModel"
        /** Outcome written when a task_assign/goal_execution is first received. */
        internal const val OUTCOME_RECEIVED  = "received"
    }
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    private val webSocketClient: GalaxyWebSocketClient
        get() = UFOGalaxyApplication.webSocketClient

    /**
     * Unified input router shared by this ViewModel and [com.ufo.galaxy.service.EnhancedFloatingService].
     *  - crossDeviceEnabled=false → local; [LocalLoopExecutor.execute] runs in [viewModelScope] and
     *    [onLocalResult] updates the chat and clears isLoading.
     *  - crossDeviceEnabled=true + WS connected → AIP v3 task_submit uplink; isLoading cleared
     *    later by [handleServerMessage] / [wsListener].
     *  - crossDeviceEnabled=true + WS NOT connected → [onError] surfaces the reason; isLoading
     *    is cleared immediately.
     */
    private val inputRouter: InputRouter by lazy {
        InputRouter(
            settings = UFOGalaxyApplication.appSettings,
            webSocketClient = webSocketClient,
            localLoopExecutor = UFOGalaxyApplication.localLoopExecutor,
            coroutineScope = viewModelScope,
            onLocalResult = { result ->
                val presentation = UnifiedResultPresentation.fromLocalResult(result)
                // Record the outcome for the diagnostics panel.
                pushTaskId(result.sessionId, presentation.outcome)

                // PR-30: Structured execution-route log for operator diagnostics.
                GalaxyLogger.log(
                    GalaxyLogger.TAG_EXEC_ROUTE,
                    mapOf("route" to ExecutionRouteTag.LOCAL.wireValue, "task_id" to result.sessionId)
                )

                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.ASSISTANT,
                    content = presentation.summary,
                    timestamp = System.currentTimeMillis()
                )
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + assistantMessage,
                        isLoading = false,
                        // PR-29: record which execution path produced this result.
                        lastExecutionRoute = ExecutionRouteTag.LOCAL,
                        // PR-30: increment per-route counter for operator diagnostics.
                        executionRouteCounts = incrementRouteCount(state.executionRouteCounts, ExecutionRouteTag.LOCAL)
                    )
                }
            },
            onError = { reason ->
                Log.e(TAG, "InputRouter error: $reason")
                pushError(reason)
                _uiState.update { it.copy(error = reason, isLoading = false) }
            }
        )
    }
    
    // 语音输入管理器
    private val speechManager: SpeechInputManager by lazy {
        SpeechInputManager(getApplication<Application>().applicationContext)
    }

    private val wsListener = object : GalaxyWebSocketClient.Listener {
        override fun onConnected() {
            Log.d(TAG, "WebSocket 已连接")
            _uiState.update { it.copy(isConnected = true, error = null, networkOk = true) }
        }

        override fun onDisconnected() {
            Log.d(TAG, "WebSocket 已断开")
            _uiState.update { it.copy(isConnected = false, networkOk = false) }
        }

        override fun onMessage(message: String) {
            Log.d(TAG, "收到消息: $message")
            handleServerMessage(message)
        }

        override fun onError(error: String) {
            Log.e(TAG, "WebSocket 错误: $error")
            pushError(error)
            _uiState.update { it.copy(error = error, isLoading = false) }
        }

        override fun onTaskAssign(taskId: String, taskAssignPayloadJson: String) {
            pushTaskId(taskId, OUTCOME_RECEIVED)
        }
    }
    
    init {
        Log.d(TAG, "MainViewModel 初始化")
        setupWebSocketListener()
        setupSpeechListener()
        addWelcomeMessage()
        // Restore persisted cross-device setting and readiness state into UI state.
        val settings = UFOGalaxyApplication.appSettings
        _uiState.update {
            it.copy(
                crossDeviceEnabled = settings.crossDeviceEnabled,
                modelReady = settings.modelReady,
                accessibilityReady = settings.accessibilityReady,
                overlayReady = settings.overlayReady,
                degradedMode = settings.degradedMode
            )
        }
        // Observe offline queue size and reconnect attempt count from the WS client.
        // Both flows are collected in viewModelScope, which is tied to the ViewModel's
        // lifecycle and cancelled automatically in onCleared() — no resource leak.
        viewModelScope.launch {
            webSocketClient.queueSize.collect { size ->
                _uiState.update { it.copy(queueSize = size) }
            }
        }
        viewModelScope.launch {
            webSocketClient.reconnectAttemptCount.collect { attempts ->
                if (attempts > _uiState.value.reconnectAttempt) {
                    // New reconnect attempt — record in metrics
                    UFOGalaxyApplication.metricsRecorder.recordWsReconnect()
                }
                _uiState.update { it.copy(reconnectAttempt = attempts) }
            }
        }
        // Observe RuntimeController registration failures and surface them as dialogs.
        // SharedFlow: each emission is a one-time event (failure reason string).
        UFOGalaxyApplication.runtimeController.registrationError
            .onEach { reason ->
                Log.w(TAG, "RuntimeController registration failure: $reason")
                UFOGalaxyApplication.metricsRecorder.recordRegistrationFailure()
                pushError(reason)
                _uiState.update { it.copy(registrationFailure = reason, crossDeviceEnabled = false) }
            }
            .launchIn(viewModelScope)
        // PR-27: Observe typed setup errors and surface the category so the failure dialog
        // can present context-appropriate recovery actions (open settings vs. retry vs.
        // open permissions) rather than a single generic "Retry" button.
        // PR-30: Also update [MainUiState.lastSetupFailureCategory] — a persistent diagnostic
        // field that is never cleared, unlike [registrationFailureCategory].
        UFOGalaxyApplication.runtimeController.setupError
            .onEach { err ->
                Log.w(
                    TAG,
                    "CrossDevice setup error: category=${err.category.wireValue} " +
                        "canRetry=${err.canRetry} reason=${err.reason}"
                )
                _uiState.update {
                    it.copy(
                        registrationFailureCategory = err.category,
                        // PR-30: persist last category for operator-facing diagnostics.
                        lastSetupFailureCategory = err.category
                    )
                }
            }
            .launchIn(viewModelScope)
        // PR-23: Observe takeover-level failures and keep diagnostics state consistent.
        // PR-26: Unified result presentation — fallback events now also surface a user-facing
        // assistant message so the chat experience is consistent regardless of which path
        // (local / cross-device / delegated / fallback) produced the outcome.
        // PR-29: Sets [MainUiState.lastExecutionRoute] to [ExecutionRouteTag.FALLBACK] so the
        // diagnostics panel reflects that the last task ended via a delegated-takeover failure.
        // PR-30: Emits [GalaxyLogger.TAG_EXEC_ROUTE] (FALLBACK) and increments route counts.
        UFOGalaxyApplication.runtimeController.takeoverFailure
            .onEach { event ->
                Log.w(
                    TAG,
                    "Takeover failure: id=${event.takeoverId} task=${event.taskId} " +
                        "cause=${event.cause.wireValue} reason=${event.reason}"
                )
                // PR-30: Structured execution-route log for operator diagnostics.
                GalaxyLogger.log(
                    GalaxyLogger.TAG_EXEC_ROUTE,
                    mapOf(
                        "route" to ExecutionRouteTag.FALLBACK.wireValue,
                        "task_id" to event.taskId.ifEmpty { event.takeoverId },
                        "cause" to event.cause.wireValue
                    )
                )
                val presentation = UnifiedResultPresentation.fromFallbackEvent(event)
                // Record in the diagnostics recent-error list.
                pushError(event.reason)
                // Record the failure outcome for the diagnostics recent-task list.
                pushTaskId(event.taskId.ifEmpty { event.takeoverId }, presentation.outcome)
                // Surface the fallback result as a user-visible assistant message so
                // the chat experience is identical to local and cross-device paths.
                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.ASSISTANT,
                    content = presentation.summary,
                    timestamp = System.currentTimeMillis()
                )
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + assistantMessage,
                        isLoading = false,
                        // PR-29: record which execution path produced this result.
                        lastExecutionRoute = ExecutionRouteTag.FALLBACK,
                        // PR-30: increment per-route counter for operator diagnostics.
                        executionRouteCounts = incrementRouteCount(state.executionRouteCounts, ExecutionRouteTag.FALLBACK)
                    )
                }
            }
            .launchIn(viewModelScope)
        // PR-31: Observe rollout-control snapshot changes and surface them in UI state
        // so the diagnostics panel always shows the current rollout-control posture.
        UFOGalaxyApplication.runtimeController.rolloutControlSnapshot
            .onEach { snapshot ->
                Log.d(TAG, "Rollout control snapshot updated: killSwitch=${snapshot.killSwitchActive} " +
                    "crossDevice=${snapshot.crossDeviceAllowed} delegated=${snapshot.delegatedExecutionAllowed} " +
                    "fallback=${snapshot.fallbackToLocalAllowed} goal=${snapshot.goalExecutionAllowed}")
                _uiState.update { it.copy(rolloutControlSnapshot = snapshot) }
            }
            .launchIn(viewModelScope)
    }
    
    /**
     * 设置 WebSocket 监听器
     */
    private fun setupWebSocketListener() {
        webSocketClient.addListener(wsListener)
    }
    
    /**
     * 设置语音识别监听器
     */
    private fun setupSpeechListener() {
        speechManager.onResult = { result ->
            Log.d(TAG, "语音识别结果: $result")
            _uiState.update { 
                it.copy(
                    inputText = result,
                    isListening = false,
                    partialSpeechResult = ""
                ) 
            }
            // 自动发送
            if (result.isNotBlank()) {
                sendMessage(result)
            }
        }
        
        speechManager.onPartialResult = { partial ->
            _uiState.update { it.copy(partialSpeechResult = partial) }
        }
        
        speechManager.onError = { error ->
            Log.e(TAG, "语音识别错误: $error")
            _uiState.update { 
                it.copy(
                    isListening = false,
                    partialSpeechResult = "",
                    error = error
                ) 
            }
        }
    }
    
    /**
     * 添加欢迎消息
     */
    private fun addWelcomeMessage() {
        val welcomeMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.ASSISTANT,
            content = "你好！我是 UFO Galaxy 智能助手。\n\n我可以帮助你：\n• 控制智能设备\n• 执行自动化任务\n• 回答问题和提供建议\n\n有什么我可以帮助你的吗？",
            timestamp = System.currentTimeMillis()
        )
        _uiState.update { it.copy(messages = listOf(welcomeMessage)) }
    }
    
    /**
     * 处理服务器消息
     *
     * PR-26: Uses [UnifiedResultPresentation.fromServerMessage] so that cross-device /
     * delegated results surface through the same presentation path as local results.
     * PR-29: Sets [MainUiState.lastExecutionRoute] to [ExecutionRouteTag.CROSS_DEVICE]
     * so the diagnostics panel reflects that this result arrived via the Gateway WebSocket.
     * PR-30: Emits [GalaxyLogger.TAG_EXEC_ROUTE] and increments [MainUiState.executionRouteCounts].
     */
    private fun handleServerMessage(message: String) {
        viewModelScope.launch {
            try {
                val presentation = UnifiedResultPresentation.fromServerMessage(message)
                // PR-30: Structured execution-route log for operator diagnostics.
                GalaxyLogger.log(
                    GalaxyLogger.TAG_EXEC_ROUTE,
                    mapOf("route" to ExecutionRouteTag.CROSS_DEVICE.wireValue)
                )
                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.ASSISTANT,
                    content = presentation.summary,
                    timestamp = System.currentTimeMillis()
                )
                
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + assistantMessage,
                        isLoading = false,
                        // PR-29: record which execution path produced this result.
                        lastExecutionRoute = ExecutionRouteTag.CROSS_DEVICE,
                        // PR-30: increment per-route counter for operator diagnostics.
                        executionRouteCounts = incrementRouteCount(state.executionRouteCounts, ExecutionRouteTag.CROSS_DEVICE)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理消息失败", e)
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }
    
    /**
     * 更新输入文本
     */
    fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }
    
    /**
     * 发送消息
     *
     * Cross-device enabled + WS connected: sends a [TaskSubmitPayload] uplink so the
     * gateway can plan and return a [task_assign] for local execution.
     * Local-only (crossDeviceEnabled=false or disconnected): runs [EdgeExecutor] directly.
     */
    fun sendMessage(text: String? = null) {
        val messageText = text ?: _uiState.value.inputText.trim()
        if (messageText.isEmpty()) return
        // Double-submit guard: ignore if a task is already in-flight.
        if (_uiState.value.isLoading) {
            Log.d(TAG, "sendMessage: already loading, ignoring duplicate submit")
            return
        }

        Log.d(TAG, "发送消息: $messageText")

        // Track the last submitted goal for the local-loop debug panel re-run action.
        _lastGoal = messageText

        // 添加用户消息
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = messageText,
            timestamp = System.currentTimeMillis()
        )

        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                inputText = "",
                isLoading = true
            )
        }

        // Delegate to unified InputRouter.
        // LOCAL  → onLocalResult callback resets isLoading on completion.
        // CROSS_DEVICE → server reply resets isLoading via handleServerMessage / wsListener.
        // ERROR  → onError callback already cleared isLoading; nothing more to do here.
        val routeMode = inputRouter.route(messageText)
        if (routeMode == InputRouter.RouteMode.CROSS_DEVICE) {
            Log.i(TAG, "sendMessage: route_mode=cross_device")
        }
    }

    /**
     * Executes [goal] via the canonical [LocalLoopExecutor] (local-only path).
     *
     * Retained for edge cases where direct local execution is needed outside of
     * [inputRouter]. Normal code paths use [inputRouter.route] which launches
     * [LocalLoopExecutor.execute] internally and delivers the result via [onLocalResult].
     *
     * PR-26: Uses [UnifiedResultPresentation.fromLocalResult] for consistent formatting.
     * PR-29: Sets [MainUiState.lastExecutionRoute] to [ExecutionRouteTag.LOCAL].
     * PR-30: Emits [GalaxyLogger.TAG_EXEC_ROUTE] and increments [MainUiState.executionRouteCounts].
     */
    private suspend fun executeLocally(goal: String) {
        val result = UFOGalaxyApplication.localLoopExecutor.execute(LocalLoopOptions(instruction = goal))
        val presentation = UnifiedResultPresentation.fromLocalResult(result)

        // Record the outcome for the diagnostics panel.
        pushTaskId(result.sessionId, presentation.outcome)

        // PR-30: Structured execution-route log for operator diagnostics.
        GalaxyLogger.log(
            GalaxyLogger.TAG_EXEC_ROUTE,
            mapOf("route" to ExecutionRouteTag.LOCAL.wireValue, "task_id" to result.sessionId)
        )

        val assistantMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.ASSISTANT,
            content = presentation.summary,
            timestamp = System.currentTimeMillis()
        )
        _uiState.update { state ->
            state.copy(
                messages = state.messages + assistantMessage,
                isLoading = false,
                // PR-29: record which execution path produced this result.
                lastExecutionRoute = ExecutionRouteTag.LOCAL,
                // PR-30: increment per-route counter for operator diagnostics.
                executionRouteCounts = incrementRouteCount(state.executionRouteCounts, ExecutionRouteTag.LOCAL)
            )
        }
    }
    
    /**
     * 开始语音输入
     */
    fun startVoiceInput() {
        Log.d(TAG, "开始语音输入")
        
        if (!speechManager.isAvailable()) {
            _uiState.update { it.copy(error = "语音识别不可用") }
            return
        }
        
        _uiState.update { it.copy(isListening = true, partialSpeechResult = "") }
        speechManager.startListening("zh-CN")
    }
    
    /**
     * 停止语音输入
     */
    fun stopVoiceInput() {
        Log.d(TAG, "停止语音输入")
        speechManager.stopListening()
        _uiState.update { it.copy(isListening = false) }
    }
    
    /**
     * 取消语音输入
     */
    fun cancelVoiceInput() {
        Log.d(TAG, "取消语音输入")
        speechManager.cancel()
        _uiState.update { it.copy(isListening = false, partialSpeechResult = "") }
    }
    
    /**
     * Refreshes readiness flags from [AppSettings] into [MainUiState].
     *
     * Should be called after a permission grant, accessibility service state change,
     * or after [UFOGalaxyApplication.refreshReadiness] has run.
     */
    fun refreshReadiness() {
        val settings = UFOGalaxyApplication.appSettings
        _uiState.update {
            it.copy(
                modelReady = settings.modelReady,
                accessibilityReady = settings.accessibilityReady,
                overlayReady = settings.overlayReady,
                degradedMode = settings.degradedMode
            )
        }
    }

    /**
     * 切换卷轴展开状态
     */
    fun toggleScroll() {
        _uiState.update { it.copy(isScrollExpanded = !it.isScrollExpanded) }
    }
    
    /**
     * 展开卷轴
     */
    fun expandScroll() {
        _uiState.update { it.copy(isScrollExpanded = true) }
    }
    
    /**
     * 收起卷轴
     */
    fun collapseScroll() {
        _uiState.update { it.copy(isScrollExpanded = false) }
    }
    
    /**
     * 清除错误
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Toggles the cross-device collaboration setting.
     *
     * - When toggling **on**: checks if the gateway is configured (non-placeholder address).
     *   If not configured, opens the network settings screen for the user to set up the
     *   gateway address (or auto-discover via Tailscale) instead of failing silently.
     *   If configured, delegates to [RuntimeController.startWithTimeout], which is the
     *   **sole lifecycle authority** responsible for enabling the WS client and updating
     *   [AppSettings.crossDeviceEnabled].
     * - When toggling **off**: delegates to [RuntimeController.stop], which disconnects
     *   the WebSocket, resets [AppSettings.crossDeviceEnabled], and transitions to
     *   [RuntimeController.RuntimeState.LocalOnly].
     * - Updates [MainUiState.crossDeviceEnabled] immediately so the toggle reflects user
     *   intent; [RuntimeController] will revert both the setting and the UI state if
     *   registration fails.
     */
    fun toggleCrossDeviceEnabled() {
        val newValue = !_uiState.value.crossDeviceEnabled
        Log.i(TAG, "toggleCrossDeviceEnabled → $newValue")
        // Update UI state immediately to reflect user's intent.
        _uiState.update { it.copy(crossDeviceEnabled = newValue) }
        if (newValue) {
            // Check if gateway is configured before attempting connection.
            val settings = UFOGalaxyApplication.appSettings
            val gatewayConfigured = settings.gatewayHost.isNotBlank() ||
                isRealUrl(settings.galaxyGatewayUrl)
            if (!gatewayConfigured) {
                // Gateway not configured — revert toggle and prompt user to set up.
                Log.i(TAG, "toggleCrossDeviceEnabled: gateway not configured → opening settings")
                _uiState.update { it.copy(crossDeviceEnabled = false, showNetworkSettings = true) }
                return
            }
            // Delegate entirely to RuntimeController — it sets settings.crossDeviceEnabled,
            // enables the WS client, and handles failure/fallback internally.
            viewModelScope.launch {
                val ok = UFOGalaxyApplication.runtimeController.startWithTimeout()
                if (!ok) {
                    // Registration failed — registrationError observer will update UI.
                    _uiState.update { it.copy(crossDeviceEnabled = false) }
                }
            }
        } else {
            // Delegate entirely to RuntimeController — it resets settings.crossDeviceEnabled
            // and disconnects the WS client.
            UFOGalaxyApplication.runtimeController.stop()
        }
    }

    /**
     * Clears [MainUiState.registrationFailure] and [MainUiState.registrationFailureCategory]
     * after the user has dismissed the dialog.
     */
    fun clearRegistrationFailure() {
        _uiState.update { it.copy(registrationFailure = null, registrationFailureCategory = null) }
    }

    /**
     * Retries cross-device registration after a failure.
     *
     * PR-27 — Category-aware retry logic:
     *  - [CrossDeviceSetupError.Category.CONFIGURATION] → clears the failure dialog and
     *    opens the network settings screen instead of attempting a connection that would
     *    fail again for the same configuration reason.
     *  - [CrossDeviceSetupError.Category.NETWORK] → clears the failure dialog and
     *    immediately re-attempts connection via [toggleCrossDeviceEnabled], which is
     *    equivalent to the user manually flipping the toggle back on after it was
     *    automatically disabled by [RuntimeController.handleFailure].
     *
     * PR-29 — CAPABILITY_NOT_SATISFIED tightening:
     *  - [CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED] → opens the network
     *    settings screen (where readiness flags are visible) instead of blindly retrying.
     *    Retrying without granting the missing permission / readiness would fail immediately
     *    with the same error, so directing the user to settings is the only meaningful path.
     *
     * When no category is recorded (e.g. the dialog was shown by an older code path without
     * category enrichment), falls back to the direct retry path.
     *
     * Called from the "Retry" / "Open Settings" button in the registration failure dialog
     * displayed in [com.ufo.galaxy.ui.MainActivity].
     */
    fun retryRegistration() {
        val category = _uiState.value.registrationFailureCategory
        _uiState.update { it.copy(registrationFailure = null, registrationFailureCategory = null) }
        when (category) {
            CrossDeviceSetupError.Category.CONFIGURATION -> {
                // Configuration error: opening settings is the only meaningful recovery path.
                Log.i(TAG, "retryRegistration: configuration error → opening network settings")
                // PR-30: Log the recovery action for operator diagnostics.
                GalaxyLogger.log(
                    GalaxyLogger.TAG_SETUP_RECOVERY,
                    mapOf("category" to category.wireValue, "action" to "open_settings")
                )
                _uiState.update { it.copy(showNetworkSettings = true) }
            }
            CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED -> {
                // PR-29: Capability error: retrying without granting the missing
                // permission/capability would fail immediately.  Direct to settings
                // so the user can satisfy the capability requirement first.
                Log.i(TAG, "retryRegistration: capability error → opening network settings for readiness review")
                // PR-30: Log the recovery action for operator diagnostics.
                GalaxyLogger.log(
                    GalaxyLogger.TAG_SETUP_RECOVERY,
                    mapOf("category" to category.wireValue, "action" to "open_permissions")
                )
                _uiState.update { it.copy(showNetworkSettings = true) }
            }
            else -> {
                // Network error (or unknown): attempt re-connection.
                // crossDeviceEnabled was set to false when the failure occurred; toggling now
                // will set it to true and attempt a new startWithTimeout().
                // PR-30: Log the recovery action for operator diagnostics.
                GalaxyLogger.log(
                    GalaxyLogger.TAG_SETUP_RECOVERY,
                    mapOf(
                        "category" to (category?.wireValue ?: "category_unknown"),
                        "action" to "retry_connect"
                    )
                )
                toggleCrossDeviceEnabled()
            }
        }
    }

    // ── Network settings (网络与诊断增强包) ──────────────────────────────────

    /** Opens the network settings screen. */
    fun openNetworkSettings() {
        _uiState.update { it.copy(showNetworkSettings = true) }
    }

    /** Closes the network settings screen. */
    fun closeNetworkSettings() {
        _uiState.update { it.copy(showNetworkSettings = false) }
    }

    /**
     * Saves network settings to [AppSettings] and optionally triggers a WS reconnect.
     *
     * PR-27 — When [reconnect] is true and cross-device is currently enabled, uses
     * [RuntimeController.reconnect] (stop + startWithTimeout atomically) so that the
     * new settings take effect cleanly without leaving the runtime in an inconsistent
     * intermediate state.
     *
     * @param reconnect When true, reconnects the runtime with the new configuration.
     */
    fun saveNetworkSettings(
        gatewayHost: String,
        gatewayPort: Int,
        useTls: Boolean,
        allowSelfSigned: Boolean,
        deviceId: String,
        restBase: String,
        metricsEndpoint: String,
        reconnect: Boolean = false
    ) {
        val s = UFOGalaxyApplication.appSettings
        s.gatewayHost = gatewayHost.trim()
        s.gatewayPort = gatewayPort
        s.useTls = useTls
        s.allowSelfSigned = allowSelfSigned
        s.deviceId = deviceId.trim()
        // Only update restBaseUrl when the user filled the field; otherwise leave existing value
        if (restBase.isNotBlank()) s.restBaseUrl = restBase.trim()
        s.metricsEndpoint = metricsEndpoint.trim()
        Log.i(TAG, "saveNetworkSettings: host=${s.gatewayHost} port=${s.gatewayPort} tls=${s.useTls} reconnect=$reconnect")
        if (reconnect && _uiState.value.crossDeviceEnabled) {
            // PR-27: Use RuntimeController.reconnect() for atomic stop + startWithTimeout.
            viewModelScope.launch {
                val ok = UFOGalaxyApplication.runtimeController.reconnect()
                if (!ok) {
                    _uiState.update { it.copy(crossDeviceEnabled = false) }
                }
            }
        }
    }

    /**
     * Triggers Tailscale auto-discovery via [com.ufo.galaxy.network.TailscaleAdapter].
     * If a gateway is found, writes the host to [AppSettings] and updates the UI.
     */
    fun autoDiscoverTailscale() {
        viewModelScope.launch {
            val adapter = UFOGalaxyApplication.tailscaleAdapter
            val foundHost = adapter.autoDiscoverNode50()
            if (foundHost != null) {
                adapter.applyGatewayHost(foundHost)
                Log.i(TAG, "autoDiscoverTailscale: found gateway $foundHost")
            } else {
                Log.w(TAG, "autoDiscoverTailscale: no gateway found")
                pushError("自动探测未找到可用的网关节点")
            }
        }
    }

    /**
     * Detects the device's own Tailscale IP and writes it to [AppSettings.gatewayHost].
     * Used by the "一键填入 Tailscale" button.
     */
    fun fillTailscaleIp() {
        viewModelScope.launch {
            val adapter = UFOGalaxyApplication.tailscaleAdapter
            val ip = adapter.getLocalTailscaleIp()
            if (ip != null) {
                UFOGalaxyApplication.appSettings.gatewayHost = ip
                Log.i(TAG, "fillTailscaleIp: $ip")
            } else {
                pushError("未检测到 Tailscale 网络，请确认已安装并登录 Tailscale")
            }
        }
    }

    /**
     * Runs the full network diagnostics suite and stores the report in [MainUiState.diagnosticsReport].
     */
    fun runNetworkDiagnostics() {
        if (_uiState.value.isDiagnosticsRunning) return
        _uiState.update { it.copy(isDiagnosticsRunning = true, diagnosticsReport = null) }
        viewModelScope.launch {
            try {
                val diagnostics = UFOGalaxyApplication.networkDiagnostics
                val report = diagnostics.runAll()
                _uiState.update { it.copy(isDiagnosticsRunning = false, diagnosticsReport = report.toText()) }
                Log.i(TAG, "Network diagnostics complete: allOk=${report.allOk}")
            } catch (e: Exception) {
                _uiState.update { it.copy(isDiagnosticsRunning = false, diagnosticsReport = "诊断失败: ${e.message}") }
                Log.e(TAG, "runNetworkDiagnostics failed", e)
            }
        }
    }
    
    /**
     * Activity onResume 时调用
     */
    fun onResume() {
        Log.d(TAG, "onResume - 通过 RuntimeController 恢复连接状态")
        // Delegate to RuntimeController — the sole lifecycle authority — to restore the WS
        // connection if cross-device is currently enabled in settings.  Direct
        // webSocketClient.connect() calls are not permitted outside RuntimeController.
        UFOGalaxyApplication.runtimeController.connectIfEnabled()
        // Refresh readiness flags — overlay/accessibility state may have changed while
        // the app was in the background (e.g. user visited settings to grant permissions).
        refreshReadiness()
        // Refresh health-check fields for the diagnostics panel.
        _uiState.update {
            it.copy(
                networkOk = webSocketClient.isConnected(),
                batteryOptimizationsDisabled = checkBatteryOptimizationsDisabled()
            )
        }
    }
    
    /**
     * Activity onPause 时调用
     */
    fun onPause() {
        Log.d(TAG, "onPause")
        // 保持连接，不断开
    }
    
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "MainViewModel 销毁")
        webSocketClient.removeListener(wsListener)
        speechManager.release()
    }

    // ── Diagnostics (PR15) ───────────────────────────────────────────────────

    /**
     * Shows or hides the diagnostics panel.
     */
    fun toggleDiagnostics() {
        _uiState.update { it.copy(showDiagnostics = !it.showDiagnostics) }
    }

    /**
     * Returns the [GalaxyLogger] log file ([java.io.File]) ready to be attached
     * to an Android share [Intent], or `null` when no log file exists yet.
     */
    fun getLogFile(): java.io.File? = GalaxyLogger.getLogFile()

    // ── Diagnostics UX helpers (PR17) ────────────────────────────────────────

    /**
     * Appends [reason] to the front of [MainUiState.recentErrors], keeping at most 5 entries.
     * Also updates [MainUiState.lastErrorReason] for backward compatibility.
     */
    private fun pushError(reason: String) {
        val entry = ErrorEntry(ts = System.currentTimeMillis(), reason = reason)
        _uiState.update { s ->
            s.copy(
                lastErrorReason = reason,
                recentErrors = (s.recentErrors + entry).takeLast(5)
            )
        }
    }

    /**
     * Appends a [TaskEntry] to [MainUiState.recentTaskIds], keeping at most 5 entries.
     * Also updates [MainUiState.lastTaskId] for backward compatibility.
     */
    private fun pushTaskId(taskId: String, outcome: String) {
        val entry = TaskEntry(ts = System.currentTimeMillis(), taskId = taskId, outcome = outcome)
        _uiState.update { s ->
            s.copy(
                lastTaskId = taskId,
                recentTaskIds = (s.recentTaskIds + entry).takeLast(5)
            )
        }
    }

    /**
     * Checks whether the OS has exempted this app from battery optimisations.
     *
     * Returns `false` on API < 23 (the API did not exist yet) or when the check itself
     * fails (the method should never throw, but we guard defensively).
     */
    internal fun checkBatteryOptimizationsDisabled(): Boolean {
        return try {
            val pm = getApplication<Application>()
                .getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Builds a plain-text diagnostics snapshot suitable for clipboard copy or sharing.
     *
     * The snapshot includes connection state, readiness flags, health-check results,
     * the last 5 errors, and the last 5 task outcomes.
     *
     * PR-30: Also includes per-route execution counts and last setup failure category
     * for operator-facing rollout diagnostics.
     */
    fun buildDiagnosticsText(): String {
        val s = _uiState.value
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("=== UFO Galaxy Diagnostics ===")
            appendLine("Time: $ts")
            appendLine()
            appendLine("-- Connection --")
            appendLine("State: ${if (s.isConnected) "Connected" else "Disconnected"}")
            appendLine("Network OK: ${s.networkOk}")
            appendLine("Reconnect attempts: ${s.reconnectAttempt}")
            appendLine("Offline queue: ${s.queueSize}")
            appendLine()
            appendLine("-- Readiness --")
            appendLine("Model files: ${if (s.modelReady) "Ready" else "Not ready"}")
            appendLine("Accessibility: ${if (s.accessibilityReady) "Enabled" else "Disabled"}")
            appendLine("Overlay (SYSTEM_ALERT_WINDOW): ${if (s.overlayReady) "Granted" else "Denied"}")
            appendLine("Degraded mode: ${if (s.degradedMode) "Yes" else "No"}")
            appendLine()
            appendLine("-- Health Checklist --")
            appendLine("Network OK: ${s.networkOk}")
            appendLine("Permissions OK: ${s.accessibilityReady && s.overlayReady}")
            appendLine("Battery optimizations disabled: ${s.batteryOptimizationsDisabled}")
            appendLine()
            // PR-29: Include last execution route so post-release diagnostics can identify
            // which execution path was used for the most recent task.
            appendLine("-- Last Execution Route --")
            appendLine("Route: ${s.lastExecutionRoute?.wireValue ?: "none"}")
            appendLine()
            // PR-30: Per-route execution counts for operator-facing rollout diagnostics.
            appendLine("-- Execution Routes (this session) --")
            if (s.executionRouteCounts.isEmpty()) {
                appendLine("  (no tasks completed)")
            } else {
                ExecutionRouteTag.values().forEach { tag ->
                    val count = s.executionRouteCounts[tag] ?: 0
                    if (count > 0) appendLine("  ${tag.wireValue}: $count")
                }
            }
            // PR-30: Last setup failure category for operator visibility.
            if (s.lastSetupFailureCategory != null) {
                appendLine("Last setup failure: ${s.lastSetupFailureCategory.wireValue}")
            }
            appendLine()
            // PR-31: Rollout-control snapshot for operator-facing kill-switch diagnostics.
            appendLine("-- Rollout Control --")
            val rcs = s.rolloutControlSnapshot
            if (rcs == null) {
                appendLine("  (not yet available)")
            } else {
                appendLine("  cross_device_allowed: ${rcs.crossDeviceAllowed}")
                appendLine("  delegated_execution_allowed: ${rcs.delegatedExecutionAllowed}")
                appendLine("  fallback_to_local_allowed: ${rcs.fallbackToLocalAllowed}")
                appendLine("  goal_execution_allowed: ${rcs.goalExecutionAllowed}")
                appendLine("  kill_switch_active: ${rcs.killSwitchActive}")
            }
            appendLine()
            appendLine("-- Recent Errors (last ${s.recentErrors.size}) --")
            if (s.recentErrors.isEmpty()) {
                appendLine("  (none)")
            } else {
                s.recentErrors.forEach { e ->
                    appendLine("  [${formatDiagTs(e.ts)}] ${e.reason}")
                }
            }
            appendLine()
            appendLine("-- Recent Tasks (last ${s.recentTaskIds.size}) --")
            if (s.recentTaskIds.isEmpty()) {
                appendLine("  (none)")
            } else {
                s.recentTaskIds.forEach { t ->
                    appendLine("  [${formatDiagTs(t.ts)}] ${t.taskId} → ${t.outcome}")
                }
            }
        }.trimEnd()
    }

    private fun formatDiagTs(ts: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts))

    /**
     * PR-30 — Returns a new map with the count for [route] incremented by one.
     *
     * Keeps the existing entries unchanged and returns a new [Map] instance (immutable
     * update semantics, safe for use inside [_uiState].update calls).
     */
    private fun incrementRouteCount(
        counts: Map<ExecutionRouteTag, Int>,
        route: ExecutionRouteTag
    ): Map<ExecutionRouteTag, Int> =
        counts + (route to ((counts[route] ?: 0) + 1))

    // ── Local-loop debug panel (PR-G) ─────────────────────────────────────────

    /**
     * Debug-only ViewModel for the local-loop debug panel.
     *
     * Lazily constructed the first time [openLocalLoopDebug] is called so that it
     * has zero overhead in normal (non-debug) usage.
     */
    val localLoopDebugViewModel: LocalLoopDebugViewModel by lazy {
        LocalLoopDebugViewModel(
            readinessProvider = UFOGalaxyApplication.localLoopReadinessProvider,
            traceStore = UFOGalaxyApplication.localLoopTraceStore,
            configProvider = { UFOGalaxyApplication.localLoopConfig },
            historyStore = UFOGalaxyApplication.sessionHistoryStore,
            coroutineScope = viewModelScope,
            lastGoalProvider = { _lastGoal },
            rerunGoalAction = { goal -> sendMessage(goal) }
        )
    }

    /** Stores the most recently submitted goal so the debug panel can offer "Re-run". */
    private var _lastGoal: String? = null

    /** Shows the local-loop debug panel and triggers an initial data refresh. */
    fun openLocalLoopDebug() {
        _uiState.update { it.copy(showLocalLoopDebug = true) }
        localLoopDebugViewModel.refresh()
    }

    /** Hides the local-loop debug panel. */
    fun closeLocalLoopDebug() {
        _uiState.update { it.copy(showLocalLoopDebug = false) }
    }

    /**
     * Returns true when [url] looks like a real, configured URL (not a placeholder).
     *
     * A URL is considered a placeholder when it:
     * - is blank
     * - contains `x` in the host portion (e.g. "100.x.x.x")
     * - uses the compile-time fallback scheme + host pattern
     */
    internal fun isRealUrl(url: String): Boolean {
        if (url.isBlank()) return false
        // Extract the host portion (between :// and the next : or /)
        val host = url
            .substringAfter("://")
            .substringBefore(":")
            .substringBefore("/")
        // Placeholder patterns: contains 'x' (e.g. "100.x.x.x") or is empty
        if (host.isBlank()) return false
        if (host.contains('x', ignoreCase = true) && !host.matches(Regex("[0-9a-fA-F:.]+"))) return false
        return true
    }
}

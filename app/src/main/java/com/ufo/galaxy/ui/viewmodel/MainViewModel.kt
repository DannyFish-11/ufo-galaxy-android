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
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.network.MessageRouter
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.speech.SpeechInputManager
import com.ufo.galaxy.speech.SpeechState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val batteryOptimizationsDisabled: Boolean = false
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
     * Unified input router shared by this ViewModel and [EnhancedFloatingService].
     * - crossDeviceEnabled=false → local only via [executeLocally].
     * - crossDeviceEnabled=true + WS connected → WS TaskSubmit uplink.
     * - crossDeviceEnabled=true + WS NOT connected → explicit error surfaced to UI; no local fallback.
     */
    private val messageRouter: MessageRouter by lazy {
        MessageRouter(
            settings = UFOGalaxyApplication.appSettings,
            webSocketClient = webSocketClient,
            onError = { reason ->
                Log.e(TAG, "MessageRouter error: $reason")
                pushError(reason)
                _uiState.update { it.copy(error = reason, isLoading = false) }
            }
        ) { text ->
            viewModelScope.launch {
                try {
                    executeLocally(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Local execution error", e)
                    _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
            }
        }
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
                _uiState.update { it.copy(reconnectAttempt = attempts) }
            }
        }
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
     */
    private fun handleServerMessage(message: String) {
        viewModelScope.launch {
            try {
                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.ASSISTANT,
                    content = message,
                    timestamp = System.currentTimeMillis()
                )
                
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + assistantMessage,
                        isLoading = false
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

        Log.d(TAG, "发送消息: $messageText")

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

        // Delegate to unified MessageRouter.
        // LOCAL  → localFallback lambda resets isLoading on completion.
        // CROSS_DEVICE → server reply resets isLoading via handleServerMessage / wsListener.
        // ERROR  → onError callback already set isLoading=false; nothing more to do here.
        val routeMode = messageRouter.route(messageText)
        if (routeMode == com.ufo.galaxy.network.MessageRouter.RouteMode.CROSS_DEVICE) {
            Log.i(TAG, "sendMessage: route_mode=cross_device")
        }
    }

    /**
     * Executes [goal] directly via [LoopController] (local-only path).
     *
     * Runs on Dispatchers.IO via LoopController.execute(); posts result back to the
     * UI on completion. Cross-device path is kept intact in [sendMessage] via
     * [MessageRouter]; this function is only reached when cross-device is OFF.
     */
    private suspend fun executeLocally(goal: String) {
        val result = UFOGalaxyApplication.loopController.execute(goal)

        // Record the outcome for the diagnostics panel.
        pushTaskId(result.sessionId, result.status)

        val summary = when (result.status) {
            LoopController.STATUS_SUCCESS ->
                "任务完成（${result.steps.size} 步）"
            LoopController.STATUS_CANCELLED ->
                "任务取消: ${result.error ?: ""}"
            else ->
                "任务失败: ${result.error ?: result.stopReason ?: "未知错误"}"
        }

        val assistantMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.ASSISTANT,
            content = summary,
            timestamp = System.currentTimeMillis()
        )
        _uiState.update { state ->
            state.copy(
                messages = state.messages + assistantMessage,
                isLoading = false
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
     * - Persists the new value via [AppSettings].
     * - Updates [GalaxyWebSocketClient.crossDeviceEnabled] via [UFOGalaxyApplication.setCrossDeviceEnabled].
     *   When toggled **off** the active WebSocket connection is torn down.
     *   When toggled **on** [GalaxyWebSocketClient.connect] is called so the gateway
     *   session is re-established and the capability_report is sent.
     * - Updates [MainUiState.crossDeviceEnabled] so the UI toggle reflects the new state
     *   immediately without waiting for a WS connection event.
     */
    fun toggleCrossDeviceEnabled() {
        val newValue = !_uiState.value.crossDeviceEnabled
        Log.i(TAG, "toggleCrossDeviceEnabled → $newValue")
        // Persist
        UFOGalaxyApplication.appSettings.crossDeviceEnabled = newValue
        // Update WS client (disconnects if newValue==false and currently connected)
        UFOGalaxyApplication.instance.setCrossDeviceEnabled(newValue)
        // Update UI state
        _uiState.update { it.copy(crossDeviceEnabled = newValue) }
        // Reconnect when enabling.
        // Connection failures are surfaced via the wsListener.onError → uiState.error path;
        // the toggle reflects the user's *intent* (crossDeviceEnabled=true means "user wants
        // cross-device"), while isConnected tracks the actual live connection state.
        if (newValue) {
            viewModelScope.launch { webSocketClient.connect() }
        }
    }
    
    /**
     * Activity onResume 时调用
     */
    fun onResume() {
        Log.d(TAG, "onResume - 尝试连接 WebSocket")
        viewModelScope.launch {
            webSocketClient.connect()
        }
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
}

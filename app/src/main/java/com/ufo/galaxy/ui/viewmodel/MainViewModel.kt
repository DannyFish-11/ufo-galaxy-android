package com.ufo.galaxy.ui.viewmodel

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.data.ChatMessage
import com.ufo.galaxy.data.MessageRole
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.TaskAssignPayload
import com.ufo.galaxy.protocol.TaskSubmitPayload
import com.ufo.galaxy.speech.SpeechInputManager
import com.ufo.galaxy.speech.SpeechState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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
    val partialSpeechResult: String = ""
)

/**
 * 主界面 ViewModel
 * 管理聊天状态、与 Galaxy 服务器的通信和语音输入
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "MainViewModel"
    }
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    private val webSocketClient: GalaxyWebSocketClient
        get() = UFOGalaxyApplication.webSocketClient

    private val gson = Gson()
    
    // 语音输入管理器
    private val speechManager: SpeechInputManager by lazy {
        SpeechInputManager(getApplication<Application>().applicationContext)
    }

    private val wsListener = object : GalaxyWebSocketClient.Listener {
        override fun onConnected() {
            Log.d(TAG, "WebSocket 已连接")
            _uiState.update { it.copy(isConnected = true, error = null) }
        }

        override fun onDisconnected() {
            Log.d(TAG, "WebSocket 已断开")
            _uiState.update { it.copy(isConnected = false) }
        }

        override fun onMessage(message: String) {
            Log.d(TAG, "收到消息: $message")
            handleServerMessage(message)
        }

        override fun onError(error: String) {
            Log.e(TAG, "WebSocket 错误: $error")
            _uiState.update { it.copy(error = error, isLoading = false) }
        }
    }
    
    init {
        Log.d(TAG, "MainViewModel 初始化")
        setupWebSocketListener()
        setupSpeechListener()
        addWelcomeMessage()
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

        val crossDeviceEnabled = UFOGalaxyApplication.appConfig.crossDeviceEnabled

        viewModelScope.launch {
            try {
                if (crossDeviceEnabled && webSocketClient.isConnected()) {
                    // 跨设备模式：发送 task_submit 上行，等待 gateway 返回 task_assign
                    sendTaskSubmitUplink(messageText)
                } else {
                    // 本地模式：直接通过 EdgeExecutor 执行
                    executeLocally(messageText)
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送消息失败", e)
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    /**
     * Sends a [TaskSubmitPayload] wrapped in an [AipMessage] (type=TASK_SUBMIT) uplink.
     * The gateway will process the goal and return a task_assign via WebSocket.
     */
    private fun sendTaskSubmitUplink(goal: String) {
        val deviceId = "${Build.MANUFACTURER}_${Build.MODEL}"
        val sessionId = UUID.randomUUID().toString()
        val payload = TaskSubmitPayload(
            task_text = goal,
            device_id = deviceId,
            session_id = sessionId
        )
        val envelope = AipMessage(
            type = MsgType.TASK_SUBMIT,
            payload = payload,
            device_id = deviceId
        )
        val sent = webSocketClient.sendJson(gson.toJson(envelope))
        if (!sent) {
            Log.w(TAG, "task_submit 发送失败，回退到本地执行")
            viewModelScope.launch { executeLocally(goal) }
        }
        // isLoading remains true until task_result arrives via onMessage
    }

    /**
     * Executes [goal] directly via [EdgeExecutor] (local-only path).
     * Runs on Dispatchers.IO; posts result back to the UI on completion.
     */
    private suspend fun executeLocally(goal: String) {
        val taskAssign = TaskAssignPayload(
            task_id = UUID.randomUUID().toString(),
            goal = goal,
            max_steps = 10,
            require_local_agent = true
        )
        val result = withContext(Dispatchers.IO) {
            UFOGalaxyApplication.edgeExecutor.handleTaskAssign(taskAssign)
        }

        val summary = when (result.status) {
            "success" -> "任务完成（${result.steps.size} 步）"
            "cancelled" -> "任务取消: ${result.error ?: ""}"
            else -> "任务失败: ${result.error ?: "未知错误"}"
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
     * Activity onResume 时调用
     */
    fun onResume() {
        Log.d(TAG, "onResume - 尝试连接 WebSocket")
        viewModelScope.launch {
            webSocketClient.connect()
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
}

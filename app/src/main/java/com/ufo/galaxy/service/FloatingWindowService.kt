package com.ufo.galaxy.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import com.ufo.galaxy.R
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.network.DeviceManager
import com.ufo.galaxy.protocol.AIPMessage
import kotlinx.coroutines.*
import org.json.JSONObject
import android.util.Log
import android.graphics.drawable.GradientDrawable
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * UFO Galaxy 浮动窗口服务
 * 
 * 功能：
 * - 黑白渐变极简极客风格
 * - 语音和文本输入
 * - 可拖动
 * - Dynamic Island 风格
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindowService"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private var isExpanded = false
    
    // UI 组件
    private lateinit var compactView: LinearLayout
    private lateinit var expandedView: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var historyText: TextView
    private lateinit var statusIndicator: View
    private lateinit var voiceButton: ImageButton
    
    // 语音识别
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    
    // 设备管理器
    private lateinit var deviceManager: DeviceManager
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "FloatingWindowService created")
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // 初始化设备管理器
        initDeviceManager()
        
        createFloatingWindow()
        initSpeechRecognizer()
    }

    private fun createFloatingWindow() {
        // 创建浮动窗口布局
        floatingView = LayoutInflater.from(this).inflate(
            R.layout.floating_window,
            null
        )
        
        // 获取 UI 组件
        compactView = floatingView.findViewById(R.id.compactView)
        expandedView = floatingView.findViewById(R.id.expandedView)
        inputField = floatingView.findViewById(R.id.inputField)
        historyText = floatingView.findViewById(R.id.historyText)
        statusIndicator = floatingView.findViewById(R.id.statusIndicator)
        voiceButton = floatingView.findViewById(R.id.voiceButton)
        
        // 设置黑白渐变背景
        setupGradientBackground()
        
        // 设置点击事件
        setupClickListeners()
        
        // 窗口参数
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 20
        params.y = 100
        
        // 添加到窗口
        windowManager.addView(floatingView, params)
        
        // 设置拖动
        setupDragging(params)
        
        Log.i(TAG, "Floating window created")
    }

    private fun setupGradientBackground() {
        // 紧凑视图：黑白渐变圆形
        val compactGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF000000.toInt(), 0xFF1a1a1a.toInt(), 0xFF000000.toInt())
        )
        compactGradient.cornerRadius = 100f
        compactGradient.setStroke(2, 0xFFFFFFFF.toInt())
        compactView.background = compactGradient
        
        // 展开视图：黑白渐变圆角矩形
        val expandedGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF000000.toInt(), 0xFF1a1a1a.toInt(), 0xFF000000.toInt())
        )
        expandedGradient.cornerRadius = 30f
        expandedGradient.setStroke(2, 0xFFFFFFFF.toInt())
        expandedView.background = expandedGradient
        
        // 输入框背景
        val inputGradient = GradientDrawable()
        inputGradient.setColor(0xFF0a0a0a.toInt())
        inputGradient.cornerRadius = 15f
        inputGradient.setStroke(1, 0xFF333333.toInt())
        inputField.background = inputGradient
        
        // 历史记录背景
        val historyGradient = GradientDrawable()
        historyGradient.setColor(0xFF0a0a0a.toInt())
        historyGradient.cornerRadius = 15f
        historyGradient.setStroke(1, 0xFF333333.toInt())
        historyText.background = historyGradient
    }

    private fun setupClickListeners() {
        // 紧凑视图点击：展开
        compactView.setOnClickListener {
            toggleExpand()
        }
        
        // 关闭按钮
        floatingView.findViewById<ImageButton>(R.id.closeButton).setOnClickListener {
            if (isExpanded) {
                toggleExpand()
            } else {
                stopSelf()
            }
        }
        
        // 发送按钮
        floatingView.findViewById<Button>(R.id.sendButton).setOnClickListener {
            sendCommand()
        }
        
        // 语音按钮
        voiceButton.setOnClickListener {
            toggleVoiceInput()
        }
        
        // 输入框回车发送
        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else {
                false
            }
        }
    }

    private fun setupDragging(params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        
        floatingView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (initialTouchX - event.rawX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        
        if (isExpanded) {
            // 展开
            compactView.visibility = View.GONE
            expandedView.visibility = View.VISIBLE
            
            // 聚焦输入框
            inputField.requestFocus()
            
            // 显示软键盘
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(inputField, 0)
            
            Log.i(TAG, "Floating window expanded")
        } else {
            // 收起
            compactView.visibility = View.VISIBLE
            expandedView.visibility = View.GONE
            
            // 隐藏软键盘
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(inputField.windowToken, 0)
            
            Log.i(TAG, "Floating window collapsed")
        }
    }

    private fun sendCommand() {
        val command = inputField.text.toString().trim()
        if (command.isEmpty()) {
            return
        }
        
        // 清空输入框
        inputField.text.clear()
        
        // 添加到历史
        addToHistory("用户: $command")
        
        // 发送到 Agent Core
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    UFOGalaxyApplication.instance.agentCore.handleTask(
                        command,
                        mapOf("source" to "floating_window")
                    )
                }
                
                val response = result.optString("response", "处理完成")
                addToHistory("系统: $response")
                
                updateStatus(true)
            } catch (e: Exception) {
                addToHistory("错误: ${e.message}")
                updateStatus(false)
                Log.e(TAG, "Error handling command", e)
            }
        }
        
        Log.i(TAG, "Command sent: $command")
    }

    private fun addToHistory(message: String) {
        val currentText = historyText.text.toString()
        val newText = if (currentText.isEmpty()) {
            message
        } else {
            "$currentText\n$message"
        }
        historyText.text = newText
        
        // 滚动到底部
        historyText.post {
            val scrollView = floatingView.findViewById<ScrollView>(R.id.historyScrollView)
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun updateStatus(success: Boolean) {
        val color = if (success) {
            0xFF00FF00.toInt()  // 绿色
        } else {
            0xFFFF0000.toInt()  // 红色
        }
        statusIndicator.setBackgroundColor(color)
    }

    // ========== 语音识别 ==========

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition not available")
            voiceButton.isEnabled = false
            return
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                Log.i(TAG, "Ready for speech")
                addToHistory("系统: 正在录音...")
            }

            override fun onBeginningOfSpeech() {
                Log.i(TAG, "Beginning of speech")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // 可以用于显示音量波形
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.i(TAG, "End of speech")
                isListening = false
                updateVoiceButton()
            }

            override fun onError(error: Int) {
                Log.e(TAG, "Speech recognition error: $error")
                isListening = false
                updateVoiceButton()
                
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                    SpeechRecognizer.ERROR_NO_MATCH -> "无法识别"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                    SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                    else -> "未知错误"
                }
                addToHistory("错误: $errorMessage")
            }

            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val text = matches[0]
                    Log.i(TAG, "Speech recognition result: $text")
                    
                    // 填充到输入框
                    inputField.setText(text)
                    inputField.setSelection(text.length)
                    
                    addToHistory("识别: $text")
                }
                
                isListening = false
                updateVoiceButton()
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {}

            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
    }

    private fun toggleVoiceInput() {
        if (isListening) {
            stopVoiceInput()
        } else {
            startVoiceInput()
        }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        
        speechRecognizer?.startListening(intent)
        isListening = true
        updateVoiceButton()
        
        Log.i(TAG, "Voice input started")
    }

    private fun stopVoiceInput() {
        speechRecognizer?.stopListening()
        isListening = false
        updateVoiceButton()
        
        Log.i(TAG, "Voice input stopped")
    }

    private fun updateVoiceButton() {
        if (isListening) {
            voiceButton.setColorFilter(0xFFFF0000.toInt())  // 红色（录音中）
        } else {
            voiceButton.setColorFilter(0xFFFFFFFF.toInt())  // 白色（待机）
        }
    }

    /**
     * 初始化设备管理器
     */
    private fun initDeviceManager() {
        // 从配置管理器读取 Gateway URL
        val configManager = com.ufo.galaxy.config.ConfigManager(applicationContext)
        val gatewayUrl = configManager.getGatewayUrl()
        
        deviceManager = DeviceManager(
            context = applicationContext,
            gatewayUrl = gatewayUrl
        )
        
        deviceManager.initialize()
        deviceManager.connect()
        
        // 注册消息处理器
        deviceManager.registerMessageHandler("task_request") { payload ->
            handleTaskRequest(payload)
        }
        
        deviceManager.registerMessageHandler("command") { payload ->
            handleCommand(payload)
        }
        
        Log.i(TAG, "DeviceManager initialized")
    }
    
    /**
     * 处理任务请求
     */
    private fun handleTaskRequest(payload: JSONObject) {
        val taskId = payload.optString("task_id")
        val taskType = payload.optString("task_type")
        
        Log.i(TAG, "Received task: $taskId ($taskType)")
        
        // 使用 TaskExecutor 执行任务
        val taskExecutor = com.ufo.galaxy.task.TaskExecutor(applicationContext)
        val result = taskExecutor.executeTask(taskId, taskType, payload)
        
        // 发送任务结果回 Gateway
        deviceManager.sendTaskResult(taskId, result)
        
        // 在 UI 上显示任务通知
        scope.launch {
            addToHistory("[任务] $taskType: ${result.optString("status")}")
        }
    }
    
    /**
     * 处理命令
     */
    private fun handleCommand(payload: JSONObject) {
        val commandId = payload.optString("command_id")
        val commandType = payload.optString("command_type")
        
        Log.i(TAG, "Received command: $commandId ($commandType)")
        
        // 使用 CommandHandler 处理命令
        val commandHandler = com.ufo.galaxy.command.CommandHandler(applicationContext)
        val result = commandHandler.handleCommand(commandId, commandType, payload)
        
        // 发送命令结果回 Gateway
        deviceManager.sendCommandResult(commandId, result)
        
        // 在 UI 上显示命令通知
        scope.launch {
            addToHistory("[命令] $commandType: ${result.optString("status")}")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // 清理设备管理器
        if (::deviceManager.isInitialized) {
            deviceManager.cleanup()
        }
        
        // 停止语音识别
        speechRecognizer?.destroy()
        speechRecognizer = null
        
        // 移除浮动窗口
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        
        // 取消协程
        scope.cancel()
        
        Log.i(TAG, "FloatingWindowService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

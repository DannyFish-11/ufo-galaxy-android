package com.ufo.galaxy.ui

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import com.ufo.galaxy.R
import com.ufo.galaxy.network.DeviceManager
import com.ufo.galaxy.network.AIPMessage
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import kotlinx.coroutines.*

/**
 * UFO³ Galaxy Android 浮窗服务
 * 
 * 功能：
 * 1. 系统级浮窗显示
 * 2. 黑白渐变极简设计
 * 3. 语音 + 文本双输入
 * 4. Dynamic Island 风格（可展开/收起）
 * 5. 与 Galaxy Gateway 实时通信
 * 
 * @author Manus AI
 * @version 1.0
 * @date 2026-01-24
 */
class FloatingWindowService : Service() {
    
    private val TAG = "FloatingWindowService"
    
    // UI 组件
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var minimizedView: View
    
    // 展开状态的 UI 组件
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var voiceButton: ImageButton
    private lateinit var minimizeButton: ImageButton
    private lateinit var closeButton: ImageButton
    private lateinit var responseTextView: TextView
    
    // 最小化状态的 UI 组件
    private lateinit var capsuleView: LinearLayout
    private lateinit var capsuleIcon: ImageView
    
    // 语音识别
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    
    // 设备管理器
    private var deviceManager: DeviceManager? = null
    
    // 协程
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 状态
    private var isExpanded = true
    
    companion object {
        const val ACTION_SHOW = "com.ufo.galaxy.ui.SHOW_WINDOW"
        const val ACTION_HIDE = "com.ufo.galaxy.ui.HIDE_WINDOW"
        const val ACTION_TOGGLE = "com.ufo.galaxy.ui.TOGGLE_WINDOW"
        
        @Volatile
        private var instance: FloatingWindowService? = null
        
        fun getInstance(): FloatingWindowService? = instance
        
        fun isRunning(): Boolean = instance != null
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 初始化 WindowManager
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // 初始化设备管理器
        deviceManager = DeviceManager.getInstance(this)
        
        // 创建浮窗视图
        createFloatingWindow()
        
        // 初始化语音识别
        initSpeechRecognizer()
        
        Log.i(TAG, "FloatingWindowService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showWindow()
            ACTION_HIDE -> hideWindow()
            ACTION_TOGGLE -> toggleWindow()
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        
        // 移除浮窗
        if (::floatingView.isInitialized && floatingView.isAttachedToWindow) {
            windowManager.removeView(floatingView)
        }
        if (::minimizedView.isInitialized && minimizedView.isAttachedToWindow) {
            windowManager.removeView(minimizedView)
        }
        
        // 释放语音识别
        speechRecognizer?.destroy()
        speechRecognizer = null
        
        // 取消协程
        scope.cancel()
        
        instance = null
        Log.i(TAG, "FloatingWindowService destroyed")
    }
    
    /**
     * 创建浮窗视图
     */
    private fun createFloatingWindow() {
        // 创建展开状态的浮窗
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window_expanded, null)
        
        // 创建最小化状态的浮窗
        minimizedView = LayoutInflater.from(this).inflate(R.layout.floating_window_minimized, null)
        
        // 初始化展开状态的 UI 组件
        inputEditText = floatingView.findViewById(R.id.input_edit_text)
        sendButton = floatingView.findViewById(R.id.send_button)
        voiceButton = floatingView.findViewById(R.id.voice_button)
        minimizeButton = floatingView.findViewById(R.id.minimize_button)
        closeButton = floatingView.findViewById(R.id.close_button)
        responseTextView = floatingView.findViewById(R.id.response_text_view)
        
        // 初始化最小化状态的 UI 组件
        capsuleView = minimizedView.findViewById(R.id.capsule_view)
        capsuleIcon = minimizedView.findViewById(R.id.capsule_icon)
        
        // 设置点击事件
        sendButton.setOnClickListener { sendTextMessage() }
        voiceButton.setOnClickListener { toggleVoiceInput() }
        minimizeButton.setOnClickListener { minimizeWindow() }
        closeButton.setOnClickListener { stopSelf() }
        capsuleView.setOnClickListener { expandWindow() }
        
        // 配置浮窗参数（展开状态）
        val expandedParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }
        
        // 配置浮窗参数（最小化状态）
        val minimizedParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 100
        }
        
        // 添加拖动功能（展开状态）
        setupDraggable(floatingView, expandedParams)
        
        // 添加拖动功能（最小化状态）
        setupDraggable(minimizedView, minimizedParams)
        
        // 添加浮窗到 WindowManager
        windowManager.addView(floatingView, expandedParams)
        
        Log.i(TAG, "Floating window created")
    }
    
    /**
     * 设置浮窗可拖动
     */
    private fun setupDraggable(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }
    
    /**
     * 初始化语音识别
     */
    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition not available")
            voiceButton.isEnabled = false
            return
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.i(TAG, "Ready for speech")
                    isListening = true
                    updateVoiceButtonState()
                }
                
                override fun onBeginningOfSpeech() {
                    Log.i(TAG, "Speech started")
                }
                
                override fun onRmsChanged(rmsdB: Float) {
                    // 可以用于显示音量波形
                }
                
                override fun onBufferReceived(buffer: ByteArray?) {}
                
                override fun onEndOfSpeech() {
                    Log.i(TAG, "Speech ended")
                    isListening = false
                    updateVoiceButtonState()
                }
                
                override fun onError(error: Int) {
                    Log.e(TAG, "Speech recognition error: $error")
                    isListening = false
                    updateVoiceButtonState()
                    showToast("语音识别失败，请重试")
                }
                
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        Log.i(TAG, "Speech result: $text")
                        inputEditText.setText(text)
                        sendTextMessage()
                    }
                }
                
                override fun onPartialResults(partialResults: Bundle?) {}
                
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }
    
    /**
     * 切换语音输入
     */
    private fun toggleVoiceInput() {
        if (isListening) {
            stopVoiceInput()
        } else {
            startVoiceInput()
        }
    }
    
    /**
     * 开始语音输入
     */
    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        
        speechRecognizer?.startListening(intent)
        Log.i(TAG, "Voice input started")
    }
    
    /**
     * 停止语音输入
     */
    private fun stopVoiceInput() {
        speechRecognizer?.stopListening()
        isListening = false
        updateVoiceButtonState()
        Log.i(TAG, "Voice input stopped")
    }
    
    /**
     * 更新语音按钮状态
     */
    private fun updateVoiceButtonState() {
        if (isListening) {
            voiceButton.setImageResource(android.R.drawable.ic_btn_speak_now)
            voiceButton.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        } else {
            voiceButton.setImageResource(android.R.drawable.ic_btn_speak_now)
            voiceButton.clearColorFilter()
        }
    }
    
    /**
     * 发送文本消息
     */
    private fun sendTextMessage() {
        val text = inputEditText.text.toString().trim()
        if (text.isEmpty()) {
            showToast("请输入内容")
            return
        }
        
        // 显示用户消息
        responseTextView.text = "You: $text\n\n${responseTextView.text}"
        
        // 清空输入框
        inputEditText.text.clear()
        
        // 发送到 Galaxy Gateway
        scope.launch {
            try {
                val message = AIPMessage.createTaskMessage(
                    deviceId = deviceManager?.getDeviceId() ?: "unknown",
                    taskId = "task_${System.currentTimeMillis()}",
                    taskType = "user_input",
                    taskData = mapOf("text" to text)
                )
                
                deviceManager?.sendMessage(message)
                Log.i(TAG, "Message sent: $text")
                
                // 显示发送成功
                showToast("消息已发送")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                showToast("发送失败: ${e.message}")
            }
        }
    }
    
    /**
     * 最小化窗口
     */
    private fun minimizeWindow() {
        if (isExpanded) {
            windowManager.removeView(floatingView)
            
            val minimizedParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 20
                y = 100
            }
            
            setupDraggable(minimizedView, minimizedParams)
            windowManager.addView(minimizedView, minimizedParams)
            
            isExpanded = false
            Log.i(TAG, "Window minimized")
        }
    }
    
    /**
     * 展开窗口
     */
    private fun expandWindow() {
        if (!isExpanded) {
            windowManager.removeView(minimizedView)
            
            val expandedParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 100
            }
            
            setupDraggable(floatingView, expandedParams)
            windowManager.addView(floatingView, expandedParams)
            
            isExpanded = true
            Log.i(TAG, "Window expanded")
        }
    }
    
    /**
     * 显示窗口
     */
    private fun showWindow() {
        if (isExpanded) {
            if (!floatingView.isAttachedToWindow) {
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        WindowManager.LayoutParams.TYPE_PHONE
                    },
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 100
                }
                windowManager.addView(floatingView, params)
            }
        } else {
            if (!minimizedView.isAttachedToWindow) {
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        WindowManager.LayoutParams.TYPE_PHONE
                    },
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = 20
                    y = 100
                }
                windowManager.addView(minimizedView, params)
            }
        }
    }
    
    /**
     * 隐藏窗口
     */
    private fun hideWindow() {
        if (floatingView.isAttachedToWindow) {
            windowManager.removeView(floatingView)
        }
        if (minimizedView.isAttachedToWindow) {
            windowManager.removeView(minimizedView)
        }
    }
    
    /**
     * 切换窗口显示/隐藏
     */
    private fun toggleWindow() {
        if (floatingView.isAttachedToWindow || minimizedView.isAttachedToWindow) {
            hideWindow()
        } else {
            showWindow()
        }
    }
    
    /**
     * 显示 Toast
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

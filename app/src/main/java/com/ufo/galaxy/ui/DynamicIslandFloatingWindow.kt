package com.ufo.galaxy.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.cardview.widget.CardView
import com.ufo.galaxy.R
import com.ufo.galaxy.network.WebSocketClient
import kotlinx.coroutines.*

/**
 * UFO Galaxy - Dynamic Island Style Floating Window
 * 灵动岛风格浮窗
 * 
 * 特性:
 * - 极简极客风格 (黑白渐变)
 * - 灵动岛交互模式
 * - 语音+文字输入
 * - 实时系统状态
 */
class DynamicIslandFloatingWindow(private val context: Context) {
    
    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private lateinit var floatingView: View
    private lateinit var minimizedView: CardView
    private lateinit var expandedView: CardView
    
    private var isExpanded = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // UI Components
    private lateinit var statusDot: View
    private lateinit var statusDotExpanded: View
    private lateinit var activityIndicator: ProgressBar
    private lateinit var minimizedText: TextView
    private lateinit var inputText: EditText
    private lateinit var btnVoiceInput: CardView
    private lateinit var btnSend: ImageButton
    private lateinit var btnMinimize: ImageButton
    private lateinit var statNodes: TextView
    private lateinit var statActive: TextView
    private lateinit var statHealth: TextView
    
    // WebSocket Client
    private var wsClient: WebSocketClient? = null
    
    fun show() {
        if (::floatingView.isInitialized) return
        
        // Inflate layout
        floatingView = LayoutInflater.from(context).inflate(
            R.layout.floating_window_dynamic_island,
            null
        )
        
        // Initialize views
        initializeViews()
        
        // Setup window parameters
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        
        // Add view to window
        windowManager.addView(floatingView, params)
        
        // Setup listeners
        setupListeners()
        
        // Connect to server
        connectToServer()
        
        // Start status updates
        startStatusUpdates()
    }
    
    private fun initializeViews() {
        minimizedView = floatingView.findViewById(R.id.floating_minimized)
        expandedView = floatingView.findViewById(R.id.floating_expanded)
        
        statusDot = floatingView.findViewById(R.id.status_dot)
        statusDotExpanded = floatingView.findViewById(R.id.status_dot_expanded)
        activityIndicator = floatingView.findViewById(R.id.activity_indicator)
        minimizedText = floatingView.findViewById(R.id.minimized_text)
        
        inputText = floatingView.findViewById(R.id.input_text)
        btnVoiceInput = floatingView.findViewById(R.id.btn_voice_input)
        btnSend = floatingView.findViewById(R.id.btn_send)
        btnMinimize = floatingView.findViewById(R.id.btn_minimize)
        
        statNodes = floatingView.findViewById(R.id.stat_nodes)
        statActive = floatingView.findViewById(R.id.stat_active)
        statHealth = floatingView.findViewById(R.id.stat_health)
    }
    
    private fun setupListeners() {
        // Toggle expand/minimize on tap
        minimizedView.setOnClickListener {
            toggleExpanded()
        }
        
        btnMinimize.setOnClickListener {
            toggleExpanded()
        }
        
        // Voice input
        btnVoiceInput.setOnClickListener {
            startVoiceInput()
        }
        
        // Text input
        btnSend.setOnClickListener {
            sendTextCommand()
        }
        
        inputText.setOnEditorActionListener { _, _, _ ->
            sendTextCommand()
            true
        }
    }
    
    private fun toggleExpanded() {
        isExpanded = !isExpanded
        
        if (isExpanded) {
            // Expand animation
            minimizedView.visibility = View.GONE
            expandedView.visibility = View.VISIBLE
            animateExpand()
        } else {
            // Minimize animation
            expandedView.visibility = View.GONE
            minimizedView.visibility = View.VISIBLE
            animateMinimize()
        }
    }
    
    private fun animateExpand() {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 300
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            val value = animation.animatedValue as Float
            expandedView.alpha = value
            expandedView.scaleX = 0.9f + (0.1f * value)
            expandedView.scaleY = 0.9f + (0.1f * value)
        }
        animator.start()
    }
    
    private fun animateMinimize() {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 200
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            val value = animation.animatedValue as Float
            minimizedView.alpha = value
            minimizedView.scaleX = 0.8f + (0.2f * value)
            minimizedView.scaleY = 0.8f + (0.2f * value)
        }
        animator.start()
    }
    
    private fun startVoiceInput() {
        // TODO: Implement voice recognition
        Toast.makeText(context, "Voice input starting...", Toast.LENGTH_SHORT).show()
        showActivity(true)
        
        // Simulate voice processing
        scope.launch {
            delay(2000)
            showActivity(false)
        }
    }
    
    private fun sendTextCommand() {
        val command = inputText.text.toString().trim()
        if (command.isEmpty()) return
        
        showActivity(true)
        
        // Send command to server
        scope.launch {
            try {
                wsClient?.sendMessage(mapOf(
                    "message_type" to "COMMAND",
                    "command" to command,
                    "source" to "android_floating_window"
                ))
                
                inputText.text.clear()
                Toast.makeText(context, "Command sent", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to send: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                showActivity(false)
            }
        }
    }
    
    private fun showActivity(show: Boolean) {
        activityIndicator.visibility = if (show) View.VISIBLE else View.GONE
    }
    
    private fun connectToServer() {
        scope.launch {
            try {
                // TODO: Get server URL from settings
                val serverUrl = "ws://192.168.1.100:8765/android"
                wsClient = WebSocketClient(serverUrl)
                wsClient?.connect()
                
                updateStatusDot(true)
            } catch (e: Exception) {
                updateStatusDot(false)
            }
        }
    }
    
    private fun updateStatusDot(online: Boolean) {
        val color = if (online) {
            context.getColor(R.color.status_online)
        } else {
            context.getColor(R.color.status_offline)
        }
        
        statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        statusDotExpanded.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }
    
    private fun startStatusUpdates() {
        scope.launch {
            while (isActive) {
                updateStats()
                delay(5000) // Update every 5 seconds
            }
        }
    }
    
    private fun updateStats() {
        // TODO: Get real stats from server
        scope.launch {
            try {
                // Placeholder values
                statNodes.text = "107"
                statActive.text = "0"
                statHealth.text = "100%"
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
    
    fun hide() {
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        scope.cancel()
        wsClient?.disconnect()
    }
}

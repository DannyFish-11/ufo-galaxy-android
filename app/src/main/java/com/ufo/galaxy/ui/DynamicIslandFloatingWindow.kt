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
import com.ufo.galaxy.network.DeviceManager
import kotlinx.coroutines.*
import org.json.JSONObject

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
    
    // Network
    private var deviceManager: DeviceManager? = null
    
    fun show() {
        val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_window_dynamic_island, null)
        
        initViews()
        setupListeners()
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 50
        }
        
        windowManager.addView(floatingView, params)
        
        // Connect to server
        connectToServer()
    }
    
    private fun initViews() {
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
        
        // Initial state
        expandedView.visibility = View.GONE
        minimizedView.visibility = View.VISIBLE
    }
    
    private fun setupListeners() {
        minimizedView.setOnClickListener {
            expand()
        }
        
        btnMinimize.setOnClickListener {
            collapse()
        }
        
        btnSend.setOnClickListener {
            val command = inputText.text.toString().trim()
            if (command.isNotEmpty()) {
                sendCommand(command)
            }
        }
        
        btnVoiceInput.setOnClickListener {
            // TODO: Implement voice input
            Toast.makeText(context, "Voice input coming soon", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun expand() {
        if (isExpanded) return
        isExpanded = true
        
        // Animate expansion
        minimizedView.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                minimizedView.visibility = View.GONE
                expandedView.visibility = View.VISIBLE
                expandedView.alpha = 0f
                expandedView.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
        
        // Update window params to allow focus
        updateWindowParams(true)
    }
    
    private fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        
        // Animate collapse
        expandedView.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                expandedView.visibility = View.GONE
                minimizedView.visibility = View.VISIBLE
                minimizedView.alpha = 0f
                minimizedView.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
        
        // Update window params to not focusable
        updateWindowParams(false)
    }
    
    private fun updateWindowParams(focusable: Boolean) {
        val params = floatingView.layoutParams as WindowManager.LayoutParams
        params.flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        windowManager.updateViewLayout(floatingView, params)
    }
    
    private fun sendCommand(command: String) {
        showActivity(true)
        
        // Send command to server
        scope.launch {
            try {
                val message = JSONObject().apply {
                    put("message_type", "COMMAND")
                    put("command", command)
                    put("source", "android_floating_window")
                }
                deviceManager?.sendRawMessage(message.toString())
                
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
                deviceManager = DeviceManager(context, serverUrl)
                deviceManager?.initialize()
                deviceManager?.connect()
                
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
        
        statusDot.setBackgroundColor(color)
        statusDotExpanded.setBackgroundColor(color)
    }
    
    fun updateStats(nodes: Int, active: Int, health: Int) {
        statNodes.text = "$nodes"
        statActive.text = "$active"
        statHealth.text = "$health%"
    }
    
    fun updateStatus(text: String) {
        minimizedText.text = text
    }
    
    fun hide() {
        try {
            windowManager.removeView(floatingView)
        } catch (e: Exception) {
            // View might not be attached
        }
        scope.cancel()
        deviceManager?.cleanup()
    }
}

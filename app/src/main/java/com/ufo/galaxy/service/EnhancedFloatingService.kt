package com.ufo.galaxy.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.ufo.galaxy.R
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.TaskSubmitPayload
import com.ufo.galaxy.ui.MainActivity
import com.ufo.galaxy.ui.components.EdgeTriggerDetector
import java.util.UUID

/**
 * 增强版悬浮窗服务
 * 实现完整的灵动岛交互，包括：
 * - 右侧边缘滑动唤醒
 * - 灵动岛展开/收起动画
 * - 展开后的聊天界面
 * - 语音输入支持
 */
class EnhancedFloatingService : Service() {
    
    companion object {
        private const val TAG = "EnhancedFloatingService"
        private const val NOTIFICATION_ID = 1003
        private const val OVERLAY_PROMPT_NOTIFICATION_ID = 1005
        
        // 灵动岛尺寸
        private const val ISLAND_COLLAPSED_WIDTH = 120
        private const val ISLAND_COLLAPSED_HEIGHT = 36
        private const val ISLAND_EXPANDED_WIDTH = 320
        private const val ISLAND_EXPANDED_HEIGHT = 400
        
        // 状态
        var isExpanded = false
            private set
    }
    
    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var edgeTrigger: EdgeTriggerDetector? = null
    
    // UI 组件
    private var statusText: TextView? = null
    private var chatContainer: LinearLayout? = null
    private var inputField: EditText? = null
    private var sendButton: ImageButton? = null
    private var voiceButton: ImageButton? = null
    private var loadingIndicator: ProgressBar? = null
    
    // 布局参数
    private val layoutParams: WindowManager.LayoutParams by lazy {
        WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            width = dpToPx(ISLAND_COLLAPSED_WIDTH)
            height = dpToPx(ISLAND_COLLAPSED_HEIGHT)
            y = dpToPx(50)
        }
    }
    
    // WebSocket 客户端
    private val webSocketClient: GalaxyWebSocketClient
        get() = UFOGalaxyApplication.webSocketClient

    private val gson = Gson()

    private lateinit var wsListener: GalaxyWebSocketClient.Listener
    
    // 唤醒广播接收器
    private val wakeUpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_WAKE_UP) {
                Log.i(TAG, "收到唤醒广播")
                expandIsland()
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "增强版悬浮窗服务创建")
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // 注册唤醒广播
        val filter = IntentFilter(ACTION_WAKE_UP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wakeUpReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wakeUpReceiver, filter)
        }
        
        // 创建悬浮视图
        createFloatingView()
        
        // 启动边缘检测
        startEdgeTrigger()
        
        // 设置 WebSocket 监听
        setupWebSocketListener()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "增强版悬浮窗服务启动")
        
        startForeground(NOTIFICATION_ID, createNotification())
        showFloatingView()
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "增强版悬浮窗服务销毁")
        
        unregisterReceiver(wakeUpReceiver)
        edgeTrigger?.stop()
        removeFloatingView()
        if (::wsListener.isInitialized) {
            webSocketClient.removeListener(wsListener)
        }
    }
    
    /**
     * 创建悬浮视图
     */
    private fun createFloatingView() {
        floatingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.dynamic_island_bg)
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            
            // 状态文本（收起时显示）
            statusText = TextView(context).apply {
                text = "UFO Galaxy"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                gravity = android.view.Gravity.CENTER
            }
            addView(statusText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            
            // 聊天容器（展开时显示）
            chatContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                
                // 消息区域
                val messagesArea = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                }
                addView(messagesArea)
                
                // 加载指示器
                loadingIndicator = ProgressBar(context).apply {
                    visibility = View.GONE
                }
                addView(loadingIndicator, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.CENTER
                })
                
                // 输入区域
                val inputArea = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    
                    // 语音按钮
                    voiceButton = ImageButton(context).apply {
                        setImageResource(android.R.drawable.ic_btn_speak_now)
                        setBackgroundResource(android.R.drawable.btn_default)
                        setOnClickListener { startVoiceInput() }
                    }
                    addView(voiceButton, LinearLayout.LayoutParams(
                        dpToPx(40),
                        dpToPx(40)
                    ))
                    
                    // 输入框
                    inputField = EditText(context).apply {
                        hint = "输入消息..."
                        setTextColor(0xFFFFFFFF.toInt())
                        setHintTextColor(0x80FFFFFF.toInt())
                        setBackgroundResource(android.R.drawable.edit_text)
                        setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                    }
                    addView(inputField, LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ))
                    
                    // 发送按钮
                    sendButton = ImageButton(context).apply {
                        setImageResource(android.R.drawable.ic_menu_send)
                        setBackgroundResource(android.R.drawable.btn_default)
                        setOnClickListener { sendMessage() }
                    }
                    addView(sendButton, LinearLayout.LayoutParams(
                        dpToPx(40),
                        dpToPx(40)
                    ))
                }
                addView(inputArea, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            addView(chatContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ))
            
            // 触摸监听
            setOnTouchListener(FloatingTouchListener())
            
            // 点击切换展开状态
            setOnClickListener { toggleExpand() }
            
            // 长按打开主界面
            setOnLongClickListener {
                openMainActivity()
                true
            }
        }
    }
    
    /**
     * 显示悬浮视图
     */
    private fun showFloatingView() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted; cannot show enhanced floating window")
            showOverlayPermissionPrompt()
            return
        }
        try {
            floatingView?.let {
                if (it.parent == null) {
                    windowManager.addView(it, layoutParams)
                    Log.d(TAG, "悬浮窗已显示")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "显示悬浮窗失败", e)
        }
    }

    /**
     * Shows a persistent notification prompting the user to grant the overlay permission.
     */
    private fun showOverlayPermissionPrompt() {
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            settingsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, UFOGalaxyApplication.CHANNEL_ALERTS)
            .setContentTitle("UFO Galaxy 需要悬浮窗权限")
            .setContentText("点击此通知前往设置，启用"显示在其他应用上层"权限以使用灵动岛功能。")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(OVERLAY_PROMPT_NOTIFICATION_ID, notification)
        Log.i(TAG, "Overlay permission prompt notification shown")
    }
    
    /**
     * 移除悬浮视图
     */
    private fun removeFloatingView() {
        try {
            floatingView?.let {
                if (it.parent != null) {
                    windowManager.removeView(it)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "移除悬浮窗失败", e)
        }
    }
    
    /**
     * 启动边缘检测
     */
    private fun startEdgeTrigger() {
        edgeTrigger = EdgeTriggerDetector(this) {
            Log.i(TAG, "边缘滑动触发")
            vibrate()
            expandIsland()
        }
        edgeTrigger?.start()
    }
    
    /**
     * 切换展开状态
     */
    private fun toggleExpand() {
        if (isExpanded) {
            collapseIsland()
        } else {
            expandIsland()
        }
    }
    
    /**
     * 展开灵动岛
     */
    private fun expandIsland() {
        if (isExpanded) return
        isExpanded = true
        
        vibrate()
        
        // 更新 flags 以允许输入
        layoutParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        
        // 动画展开
        animateSize(
            fromWidth = dpToPx(ISLAND_COLLAPSED_WIDTH),
            toWidth = dpToPx(ISLAND_EXPANDED_WIDTH),
            fromHeight = dpToPx(ISLAND_COLLAPSED_HEIGHT),
            toHeight = dpToPx(ISLAND_EXPANDED_HEIGHT)
        )
        
        // 显示聊天容器
        statusText?.visibility = View.GONE
        chatContainer?.visibility = View.VISIBLE
        
        Log.d(TAG, "灵动岛已展开")
    }
    
    /**
     * 收起灵动岛
     */
    private fun collapseIsland() {
        if (!isExpanded) return
        isExpanded = false
        
        vibrate()
        
        // 更新 flags
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        
        // 动画收起
        animateSize(
            fromWidth = dpToPx(ISLAND_EXPANDED_WIDTH),
            toWidth = dpToPx(ISLAND_COLLAPSED_WIDTH),
            fromHeight = dpToPx(ISLAND_EXPANDED_HEIGHT),
            toHeight = dpToPx(ISLAND_COLLAPSED_HEIGHT)
        )
        
        // 隐藏聊天容器
        chatContainer?.visibility = View.GONE
        statusText?.visibility = View.VISIBLE
        
        Log.d(TAG, "灵动岛已收起")
    }
    
    /**
     * 动画改变大小
     */
    private fun animateSize(fromWidth: Int, toWidth: Int, fromHeight: Int, toHeight: Int) {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = OvershootInterpolator(0.8f)
            
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                layoutParams.width = (fromWidth + (toWidth - fromWidth) * progress).toInt()
                layoutParams.height = (fromHeight + (toHeight - fromHeight) * progress).toInt()
                
                try {
                    windowManager.updateViewLayout(floatingView, layoutParams)
                } catch (e: Exception) {
                    Log.e(TAG, "更新布局失败", e)
                }
            }
        }
        animator.start()
    }
    
    /**
     * 震动反馈
     */
    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(50)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "震动失败", e)
        }
    }
    
    /**
     * 设置 WebSocket 监听
     */
    private fun setupWebSocketListener() {
        wsListener = object : GalaxyWebSocketClient.Listener {
            override fun onConnected() {
                statusText?.post {
                    statusText?.text = "已连接"
                }
            }
            
            override fun onDisconnected() {
                statusText?.post {
                    statusText?.text = "未连接"
                }
            }
            
            override fun onMessage(message: String) {
                loadingIndicator?.post {
                    loadingIndicator?.visibility = View.GONE
                }
            }
            
            override fun onError(error: String) {
                statusText?.post {
                    statusText?.text = "错误"
                }
            }
        }
        webSocketClient.addListener(wsListener)
    }
    
    /**
     * 发送消息
     *
     * Cross-device enabled + WS connected: sends a TaskSubmitPayload uplink.
     * Otherwise: falls back to legacy TEXT send.
     */
    private fun sendMessage() {
        val text = inputField?.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        
        inputField?.setText("")
        loadingIndicator?.visibility = View.VISIBLE

        val crossDeviceEnabled = UFOGalaxyApplication.appSettings.crossDeviceEnabled
        if (crossDeviceEnabled && webSocketClient.isConnected()) {
            val deviceId = "${Build.MANUFACTURER}_${Build.MODEL}"
            val sessionId = UUID.randomUUID().toString()
            val payload = TaskSubmitPayload(
                task_text = text,
                device_id = deviceId,
                session_id = sessionId
            )
            val envelope = AipMessage(
                type = MsgType.TASK_SUBMIT,
                payload = payload,
                device_id = deviceId
            )
            webSocketClient.sendJson(gson.toJson(envelope))
        } else {
            webSocketClient.send(text)
        }
    }
    
    /**
     * 开始语音输入（悬浮窗不支持语音输入；请在主界面使用语音输入功能）
     */
    private fun startVoiceInput() {
        Log.d(TAG, "语音输入仅在主界面支持；请长按悬浮窗打开主界面")
    }
    
    /**
     * 打开主界面
     */
    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, UFOGalaxyApplication.CHANNEL_SERVICE)
            .setContentTitle("UFO Galaxy")
            .setContentText("智能助手已就绪")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
    
    /**
     * dp 转 px
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
    
    /**
     * 悬浮窗触摸监听器
     */
    private inner class FloatingTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var isDragging = false
        
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    
                    if (!isDragging && (kotlin.math.abs(deltaX) > 10 || kotlin.math.abs(deltaY) > 10)) {
                        isDragging = true
                    }
                    
                    if (isDragging && !isExpanded) {
                        layoutParams.x = initialX + deltaX.toInt()
                        layoutParams.y = initialY + deltaY.toInt()
                        
                        try {
                            windowManager.updateViewLayout(floatingView, layoutParams)
                        } catch (e: Exception) {
                            Log.e(TAG, "移动悬浮窗失败", e)
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        return true
                    }
                }
            }
            return false
        }
    }
}

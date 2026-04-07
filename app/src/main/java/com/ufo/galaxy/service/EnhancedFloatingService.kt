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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationCompat
import com.ufo.galaxy.R
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.input.InputRouter
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.runtime.TakeoverFallbackEvent
import com.ufo.galaxy.ui.MainActivity
import com.ufo.galaxy.ui.components.EdgeTriggerDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

        // 任务状态常量
        internal const val STATUS_IDLE = "idle"
        internal const val STATUS_RUNNING = "running"
        internal const val STATUS_SUCCESS = "success"
        internal const val STATUS_ERROR = "error"

        // 状态
        var isExpanded = false
            private set
    }
    
    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var edgeTrigger: EdgeTriggerDetector? = null

    /** Coroutine scope for local [com.ufo.galaxy.local.LocalLoopExecutor] sessions. Cancelled in [onDestroy]. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // UI 组件
    private var statusText: TextView? = null
    private var chatContainer: LinearLayout? = null
    private var inputField: EditText? = null
    private var sendButton: ImageButton? = null
    private var voiceButton: ImageButton? = null
    private var loadingIndicator: ProgressBar? = null
    private var crossDeviceSwitch: SwitchCompat? = null
    
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

    /**
     * Tracks the task_id and status for the floating-window task summary.
     * Updated from the WS listener (task received) and send completion.
     */
    private var lastTaskId: String = ""
    private var taskStatus: String = STATUS_IDLE   // STATUS_IDLE | STATUS_RUNNING | STATUS_SUCCESS | STATUS_ERROR

    /**
     * Unified input router: cross-device enabled + WS connected → AIP v3 task_submit uplink;
     * local (cross-device OFF) → [LocalLoopExecutor] canonical local execution pipeline launched
     * in [serviceScope]. [onError] surfaces WS-unavailable errors directly in the floating status
     * label. [onLocalResult] updates status and hides the loading indicator on task completion.
     */
    private val inputRouter: InputRouter by lazy {
        InputRouter(
            settings = UFOGalaxyApplication.appSettings,
            webSocketClient = webSocketClient,
            localLoopExecutor = UFOGalaxyApplication.localLoopExecutor,
            coroutineScope = serviceScope,
            onLocalResult = { result ->
                lastTaskId = result.sessionId.take(8)
                taskStatus = if (result.status == com.ufo.galaxy.local.LocalLoopResult.STATUS_SUCCESS) {
                    STATUS_SUCCESS
                } else {
                    STATUS_ERROR
                }
                Log.i(TAG, "[FLOAT] local loop done status=${result.status} steps=${result.stepCount}")
                updateStatusLabel()
                loadingIndicator?.post { loadingIndicator?.visibility = android.view.View.GONE }
            },
            onError = { reason ->
                Log.e(TAG, "[FLOAT] Route error: $reason")
                taskStatus = STATUS_ERROR
                updateStatusLabel()
                loadingIndicator?.post { loadingIndicator?.visibility = android.view.View.GONE }
            }
        )
    }

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

        // Observe RuntimeController registration failures and show floating dialog.
        serviceScope.launch {
            UFOGalaxyApplication.runtimeController.registrationError.collect { reason ->
                Log.w(TAG, "[FLOAT] Registration failure: $reason")
                // Reset switch state on main thread.
                crossDeviceSwitch?.post {
                    crossDeviceSwitch?.isChecked = false
                }
                showRegistrationFailureDialog(reason)
            }
        }

        // PR-23: Observe takeover-level failures and clear stale floating-surface state.
        // Unlike registration failures, individual takeover failures do NOT reset the
        // cross-device toggle; they only clear the "active" task indicator so the user
        // sees the current (failed) task outcome rather than a stuck running state.
        serviceScope.launch {
            UFOGalaxyApplication.runtimeController.takeoverFailure.collect { event ->
                Log.w(
                    TAG,
                    "[FLOAT] Takeover failure: id=${event.takeoverId} task=${event.taskId} " +
                        "cause=${event.cause.wireValue} reason=${event.reason}"
                )
                // Update task status to ERROR and hide any loading indicator so the
                // floating overlay does not remain stuck in a "running" visual state.
                taskStatus = STATUS_ERROR
                lastTaskId = event.taskId.ifEmpty { event.takeoverId }.take(8)
                updateStatusLabel()
                loadingIndicator?.post { loadingIndicator?.visibility = android.view.View.GONE }
            }
        }
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
        serviceScope.cancel()
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
                text = buildStatusLabel()
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
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

                // 跨设备开关
                crossDeviceSwitch = SwitchCompat(context).apply {
                    text = "跨设备"
                    setTextColor(0xFFFFFFFF.toInt())
                    isChecked = UFOGalaxyApplication.appSettings.crossDeviceEnabled
                    setOnCheckedChangeListener { _, isChecked ->
                        taskStatus = STATUS_IDLE
                        updateStatusLabel()
                        if (isChecked) {
                            // Delegate to RuntimeController; it handles registration and
                            // emits a registrationError event on failure (observed above).
                            serviceScope.launch {
                                val ok = UFOGalaxyApplication.runtimeController.startWithTimeout()
                                if (!ok) {
                                    // Revert switch; dialog is shown via registrationError observer.
                                    crossDeviceSwitch?.post { crossDeviceSwitch?.isChecked = false }
                                }
                            }
                        } else {
                            UFOGalaxyApplication.runtimeController.stop()
                        }
                    }
                }
                addView(crossDeviceSwitch, LinearLayout.LayoutParams(
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
     * 设置 WebSocket 监听：更新悬浮窗状态标签和任务简报。
     */
    private fun setupWebSocketListener() {
        wsListener = object : GalaxyWebSocketClient.Listener {
            override fun onConnected() {
                statusText?.post { updateStatusLabel() }
            }

            override fun onDisconnected() {
                statusText?.post { updateStatusLabel() }
            }

            override fun onMessage(message: String) {
                loadingIndicator?.post {
                    loadingIndicator?.visibility = View.GONE
                }
            }

            override fun onError(error: String) {
                taskStatus = STATUS_ERROR
                statusText?.post { updateStatusLabel() }
            }

            override fun onTaskAssign(taskId: String, taskAssignPayloadJson: String) {
                lastTaskId = taskId
                taskStatus = STATUS_RUNNING
                statusText?.post { updateStatusLabel() }
                loadingIndicator?.post { loadingIndicator?.visibility = View.VISIBLE }
                Log.i(TAG, "[FLOAT] task_assign task_id=$taskId")
            }

            override fun onGoalExecution(taskId: String, goalPayloadJson: String) {
                lastTaskId = taskId
                taskStatus = STATUS_RUNNING
                statusText?.post { updateStatusLabel() }
                loadingIndicator?.post { loadingIndicator?.visibility = View.VISIBLE }
                Log.i(TAG, "[FLOAT] goal_execution task_id=$taskId")
            }
        }
        webSocketClient.addListener(wsListener)
    }

    /**
     * Builds the status-label text for the collapsed/expanded floating island.
     * Format: "[mode] | [task_id_short] | [status]"
     */
    private fun buildStatusLabel(): String {
        val mode = if (UFOGalaxyApplication.appSettings.crossDeviceEnabled) "跨设备" else "本地"
        val idPart = if (lastTaskId.isNotEmpty()) lastTaskId.take(8) else "—"
        val statusLabel = when (taskStatus) {
            STATUS_RUNNING -> "执行中"
            STATUS_SUCCESS -> "成功"
            STATUS_ERROR   -> "错误"
            else           -> if (webSocketClient.isConnected()) "已连接" else "未连接"
        }
        return "$mode | $idPart | $statusLabel"
    }

    /**
     * Posts a status-label update to the UI thread.
     */
    private fun updateStatusLabel() {
        statusText?.post { statusText?.text = buildStatusLabel() }
    }

    /**
     * Shows a floating overlay dialog when cross-device device registration fails.
     *
     * Because [EnhancedFloatingService] is a [Service] (not an [Activity]), we cannot use
     * the standard [android.app.AlertDialog] API directly. Instead, we create a small
     * overlay view using the [WindowManager] with [TYPE_APPLICATION_OVERLAY] type, which
     * is the same mechanism used for the floating island itself.
     *
     * The dialog auto-dismisses when the user taps "确定". If the overlay permission has
     * not been granted yet, the error is logged (overlay cannot be shown without permission).
     */
    private fun showRegistrationFailureDialog(reason: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            Log.w(TAG, "[FLOAT] Cannot show registration failure dialog: overlay permission not granted. reason=$reason")
            return
        }

        val context = this
        // Build a simple LinearLayout dialog container.
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF212121.toInt())
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))

            addView(android.widget.TextView(context).apply {
                text = "跨设备注册失败"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                setPadding(0, 0, 0, dpToPx(8))
            })

            addView(android.widget.TextView(context).apply {
                text = reason
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 13f
                setPadding(0, 0, 0, dpToPx(12))
            })

            // Dismiss button — must be added after we create dialogParams reference.
        }

        val dialogParams = WindowManager.LayoutParams().apply {
            type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            format = android.graphics.PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            width = dpToPx(280)
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = android.view.Gravity.CENTER
        }

        // Dismiss button: removes the dialog view from the WindowManager.
        dialogView.addView(android.widget.Button(context).apply {
            text = "关闭跨设备"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF455A64.toInt())
            setOnClickListener {
                try { windowManager.removeView(dialogView) } catch (_: Exception) {}
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dpToPx(8) })

        // Retry button: re-attempts cross-device registration.
        dialogView.addView(android.widget.Button(context).apply {
            text = "重试"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1976D2.toInt())
            setOnClickListener {
                try { windowManager.removeView(dialogView) } catch (_: Exception) {}
                serviceScope.launch {
                    val ok = UFOGalaxyApplication.runtimeController.startWithTimeout()
                    crossDeviceSwitch?.post { crossDeviceSwitch?.isChecked = ok }
                    if (!ok) {
                        // Notify the user that the retry also failed.
                        android.widget.Toast.makeText(
                            context,
                            "重试失败，请检查网络或 Gateway 配置后再试。",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        try {
            windowManager.addView(dialogView, dialogParams)
            Log.i(TAG, "[FLOAT] Registration failure dialog shown: $reason")
        } catch (e: Exception) {
            Log.e(TAG, "[FLOAT] Failed to show registration failure dialog", e)
        }
    }

    /**
     * 发送消息
     *
     * Delegates to [InputRouter]: cross-device enabled + WS connected → AIP v3 task_submit;
     * otherwise → local [com.ufo.galaxy.local.LocalLoopExecutor] execution. Loading indicator
     * is shown before routing; it is hidden in [InputRouter.onLocalResult] or
     * [InputRouter.onError] callbacks.
     * For cross-device, status is updated when task_assign/goal_result arrives via [wsListener].
     */
    private fun sendMessage() {
        val text = inputField?.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        // Double-submit guard: ignore if a task is already in-flight.
        if (taskStatus == STATUS_RUNNING) {
            Log.d(TAG, "[FLOAT] sendMessage: already running, ignoring duplicate submit")
            return
        }

        inputField?.setText("")
        taskStatus = STATUS_RUNNING
        updateStatusLabel()
        loadingIndicator?.visibility = View.VISIBLE
        val routeMode = inputRouter.route(text)
        Log.i(TAG, "[FLOAT] sendMessage route_mode=$routeMode")
    }
    
    /**
     * 开始语音输入：悬浮窗暂不支持内嵌语音识别，提示用户长按悬浮窗打开主界面。
     */
    private fun startVoiceInput() {
        Log.d(TAG, "语音输入仅在主界面支持；提示用户打开主界面")
        android.widget.Toast.makeText(
            this,
            "请长按悬浮窗打开主界面以使用语音输入",
            android.widget.Toast.LENGTH_SHORT
        ).show()
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

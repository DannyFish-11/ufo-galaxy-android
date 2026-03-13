package com.ufo.galaxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ufo.galaxy.MainActivity
import com.ufo.galaxy.agent.GalaxyAgentV2
import com.ufo.galaxy.agent.MessageRouter
import com.ufo.galaxy.config.AppSettings
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.ui.theme.GeekThemePremium
import java.util.*

/**
 * 增强版悬浮窗服务（EnhancedFloatingService）
 *
 * 实现灵动岛风格悬浮窗，仅包含以下 4 个元素（符合 P0 需求）：
 *   1. 文本输入框
 *   2. 语音按钮
 *   3. 跨设备开关
 *   4. 任务简报/状态
 *
 * 与 [AppSettings] 双向同步跨设备开关状态；
 * 所有用户输入通过 [MessageRouter] 统一路由。
 *
 * @author UFO³ Galaxy
 * @version 1.0
 */
class EnhancedFloatingService : android.app.Service(), LifecycleOwner,
    ViewModelStoreOwner, SavedStateRegistryOwner {

    // ── Compose/Lifecycle 所需的实现 ─────────────────────────────────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // ── 服务组件 ─────────────────────────────────────────────────────────────
    private lateinit var windowManager: WindowManager
    private var floatingView: ComposeView? = null

    private lateinit var settings: AppSettings
    private lateinit var wsClient: GalaxyWebSocketClient
    private lateinit var localAgent: GalaxyAgentV2
    private lateinit var router: MessageRouter

    /** 共享的任务简报文本（Compose State） */
    private val taskStatus = mutableStateOf("就绪")

    companion object {
        private const val TAG = "EnhancedFloating"
        private const val CHANNEL_ID = "enhanced_floating_channel"
        private const val NOTIFICATION_ID = 1001
    }

    // ── 生命周期 ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        settings = AppSettings(applicationContext)
        wsClient = GalaxyWebSocketClient(applicationContext)
        localAgent = GalaxyAgentV2(applicationContext)
        router = MessageRouter(applicationContext, wsClient, localAgent)

        // 若跨设备模式已启用，恢复 WS 连接
        if (settings.crossDeviceEnabled) {
            wsClient.setCrossDeviceEnabled(true)
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        createFloatingWindow()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        floatingView?.let { windowManager.removeView(it) }
        floatingView = null
        store.clear()
        super.onDestroy()
    }

    // ── 悬浮窗创建 ───────────────────────────────────────────────────────────

    private fun createFloatingWindow() {
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@EnhancedFloatingService)
            setViewTreeViewModelStoreOwner(this@EnhancedFloatingService)
            setViewTreeSavedStateRegistryOwner(this@EnhancedFloatingService)
            setContent {
                GeekThemePremium {
                    FloatingIslandContent()
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 60
        }

        floatingView = view
        windowManager.addView(view, params)
    }

    // ── Compose UI ───────────────────────────────────────────────────────────

    @Composable
    private fun FloatingIslandContent() {
        var inputText by remember { mutableStateOf("") }
        var crossDevice by remember { mutableStateOf(settings.crossDeviceEnabled) }
        val status by taskStatus

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.Black.copy(alpha = 0.88f),
            shadowElevation = 12.dp,
            modifier = Modifier
                .padding(8.dp)
                .widthIn(min = 280.dp, max = 360.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ── 状态/简报行 ──────────────────────────────────────────────
                Text(
                    text = status,
                    color = if (crossDevice) Color(0xFF64FFDA) else Color(0xFFB0BEC5),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                // ── 输入行（文本框 + 语音 + 发送） ──────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text("输入指令…", fontSize = 13.sp, color = Color.Gray)
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF64FFDA),
                            unfocusedBorderColor = Color.Gray,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(fontSize = 13.sp)
                    )

                    // 语音按钮
                    IconButton(
                        onClick = { startVoiceRecognition { text -> sendInput(text, "voice_input") } },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("🎤", fontSize = 18.sp)
                    }

                    // 发送按钮
                    IconButton(
                        onClick = {
                            val text = inputText.trim()
                            if (text.isNotEmpty()) {
                                sendInput(text, "text_input")
                                inputText = ""
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("▶", fontSize = 16.sp, color = Color(0xFF64FFDA))
                    }
                }

                // ── 跨设备开关行 ─────────────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (crossDevice) "跨设备 ON" else "跨设备 OFF",
                        color = if (crossDevice) Color(0xFF64FFDA) else Color(0xFFB0BEC5),
                        fontSize = 12.sp
                    )
                    Switch(
                        checked = crossDevice,
                        onCheckedChange = { enabled ->
                            crossDevice = enabled
                            toggleCrossDevice(enabled)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF64FFDA),
                            checkedTrackColor = Color(0xFF37474F)
                        )
                    )
                }
            }
        }
    }

    // ── 业务逻辑 ─────────────────────────────────────────────────────────────

    /** 切换跨设备模式，并驱动 [GalaxyWebSocketClient] 连接/断开。 */
    private fun toggleCrossDevice(enabled: Boolean) {
        Log.i(TAG, "跨设备开关 → $enabled")
        wsClient.setCrossDeviceEnabled(enabled)
        taskStatus.value = if (enabled) "跨设备模式已启用" else "本地模式"
    }

    /** 将用户输入发往统一路由器。 */
    private fun sendInput(text: String, source: String) {
        Log.i(TAG, "[$source] → $text")
        taskStatus.value = "执行中: ${text.take(20)}…"
        router.route(text, source)
    }

    /** 启动系统语音识别，识别结果通过 [onResult] 回调返回。 */
    private fun startVoiceRecognition(onResult: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            taskStatus.value = "⚠️ 语音识别不可用"
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: android.os.Bundle?) {
                taskStatus.value = "🎤 请说话…"
            }
            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (!text.isNullOrEmpty()) onResult(text)
                else taskStatus.value = "⚠️ 未识别到语音"
                recognizer.destroy()
            }
            override fun onError(error: Int) {
                taskStatus.value = "⚠️ 语音识别错误: $error"
                recognizer.destroy()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: android.os.Bundle?) {}
            override fun onEvent(t: Int, p: android.os.Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        recognizer.startListening(intent)
    }

    // ── 前台通知 ─────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Galaxy 悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UFO³ Galaxy")
            .setContentText("悬浮窗服务运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

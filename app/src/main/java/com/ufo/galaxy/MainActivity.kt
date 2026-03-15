package com.ufo.galaxy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ufo.galaxy.agent.GalaxyAgentV2
import com.ufo.galaxy.agent.MessageRouter
import com.ufo.galaxy.config.AppConfig
import com.ufo.galaxy.config.AppSettings
import com.ufo.galaxy.config.RemoteConfigFetcher
import com.ufo.galaxy.input.NaturalLanguageInputManager
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.service.EnhancedFloatingService
import com.ufo.galaxy.ui.theme.GeekThemePremium
import java.util.*
import kotlinx.coroutines.launch

/**
 * UFO³ Galaxy Android Agent - 主活动
 *
 * 完整集成的 Android APP，作为 Galaxy 系统的移动端节点。
 *
 * 核心功能：
 * 1. 灵动岛 UI - 悬浮窗交互界面（EnhancedFloatingService）
 * 2. 自然语言输入 - 语音和文本（经 MessageRouter 路由）
 * 3. 跨设备开关 - 与 AppSettings 双向同步，驱动 GalaxyWebSocketClient
 * 4. Galaxy 系统集成 - 连接到 Galaxy Gateway（跨设备模式）
 * 5. 本地执行链路 - Node50 / 本地 pipeline（本地模式）
 *
 * @author UFO³ Galaxy Enhancement Team
 * @version 2.5
 */
class MainActivity : ComponentActivity() {

    // 运行时配置（SharedPreferences）
    private lateinit var appSettings: AppSettings

    // Gateway WebSocket 客户端（跨设备模式）
    private lateinit var wsClient: GalaxyWebSocketClient

    // 本地 Agent（本地模式）
    private lateinit var galaxyAgent: GalaxyAgentV2

    // 统一消息路由器
    private lateinit var messageRouter: MessageRouter

    // 自然语言输入管理器（仅做输入采集）
    private lateinit var inputManager: NaturalLanguageInputManager

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "✅ 所有权限已授予", Toast.LENGTH_SHORT).show()
            initializeComponents()
        } else {
            Toast.makeText(this, "⚠️ 需要所有权限才能正常工作", Toast.LENGTH_LONG).show()
        }
    }

    // 语音识别启动器
    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            matches?.firstOrNull()?.let { text ->
                handleNaturalLanguageInput(text, isVoice = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化配置（需先于其他组件）
        appSettings = AppSettings(applicationContext)

        setContent {
            GeekThemePremium {
                MainScreen()
            }
        }

        // 初始化自然语言输入管理器（仅采集，回调进路由器）
        inputManager = NaturalLanguageInputManager(this)
        inputManager.initialize()
        inputManager.onInputReceived = { input ->
            handleNaturalLanguageInput(input, isVoice = false)
        }

        requestNecessaryPermissions()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 主界面 Composable
    // ──────────────────────────────────────────────────────────────────────────

    @Composable
    fun MainScreen() {
        var agentStatus by remember { mutableStateOf("未初始化") }
        var floatingWindowActive by remember { mutableStateOf(false) }
        var inputText by remember { mutableStateOf("") }

        // 跨设备开关状态（与 AppSettings 同步）
        var crossDeviceEnabled by remember { mutableStateOf(appSettings.crossDeviceEnabled) }

        // 监听 Agent 状态
        LaunchedEffect(Unit) {
            if (::galaxyAgent.isInitialized) {
                galaxyAgent.status.collect { status ->
                    agentStatus = status
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 标题
                Text(
                    text = "UFO³ Galaxy",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "Android Agent v2.5",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ── 跨设备开关卡片 ───────────────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "跨设备模式",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (crossDeviceEnabled)
                                    "✅ 已启用 → 通过 Gateway 执行"
                                else
                                    "🏠 已禁用 → 本地执行",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = crossDeviceEnabled,
                            onCheckedChange = { enabled ->
                                crossDeviceEnabled = enabled
                                onCrossDeviceToggled(enabled)
                            }
                        )
                    }
                }

                // ── Agent 状态卡片 ───────────────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Agent 状态",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = agentStatus,
                            style = MaterialTheme.typography.bodyLarge,
                            color = when {
                                agentStatus.contains("已连接") -> MaterialTheme.colorScheme.primary
                                agentStatus.contains("错误") -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // ── 悬浮窗控制 ──────────────────────────────────────────────
                Button(
                    onClick = {
                        if (floatingWindowActive) {
                            stopEnhancedFloatingService()
                            floatingWindowActive = false
                        } else {
                            if (checkOverlayPermission()) {
                                startEnhancedFloatingService()
                                floatingWindowActive = true
                            } else {
                                requestOverlayPermission()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (floatingWindowActive) "关闭悬浮窗" else "启动悬浮窗")
                }

                // ── 自然语言输入 ─────────────────────────────────────────────
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("输入指令") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如：打开微信") }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                handleNaturalLanguageInput(inputText, isVoice = false)
                                inputText = ""
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("发送")
                    }

                    Button(
                        onClick = { startVoiceRecognition() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🎤 语音")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── 快捷操作 ────────────────────────────────────────────────
                Text(
                    text = "快捷操作",
                    style = MaterialTheme.typography.titleMedium
                )

                Button(
                    onClick = { openAccessibilitySettings() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("开启无障碍服务")
                }

                Button(
                    onClick = { openAgentSettings() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Agent 设置")
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "通过自然语言控制您的所有设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 初始化
    // ──────────────────────────────────────────────────────────────────────────

    private fun requestNecessaryPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            initializeComponents()
        }
    }

    /**
     * 初始化各核心组件。权限获批后调用。
     *
     * 启动流程：
     * 1. 从 /api/v1/config 拉取远端配置并进行多通道探测（选择最优传输通道）。
     * 2. 使用更新后的配置初始化 WS 客户端和本地 Agent。
     * 3. 仅当跨设备开关为 ON 时才建立 WS 连接。
     */
    private fun initializeComponents() {
        lifecycleScope.launch {
            try {
                // ── Step 1: Remote config fetch + channel selection ────────────
                // Updates ServerConfig in-memory overrides and optionally updates
                // AppSettings with the best channel host.
                // Falls back to local config.properties on any failure – no crash.
                RemoteConfigFetcher.fetchAndApply(appSettings)

                // ── Step 2: Init WS client + local Agent with updated config ──
                wsClient = GalaxyWebSocketClient(applicationContext)

                // 本地 Agent
                galaxyAgent = GalaxyAgentV2(applicationContext)
                galaxyAgent.start()

                // 统一路由器
                messageRouter = MessageRouter(applicationContext, wsClient, galaxyAgent)

                // ── Step 3: Cross-device switch gate ──────────────────────────
                // WS is only opened when the cross-device switch is ON.
                // If the switch is OFF the app stays in local/offline mode.
                if (appSettings.crossDeviceEnabled) {
                    wsClient.setCrossDeviceEnabled(true)
                }

                Toast.makeText(this@MainActivity, "✅ Galaxy Agent 已启动", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "❌ 组件初始化失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 跨设备开关逻辑
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 处理跨设备开关切换事件。
     *
     * - enabled=true  → 更新 AppSettings + GalaxyWebSocketClient.connect()
     * - enabled=false → 更新 AppSettings + GalaxyWebSocketClient.disconnect()
     */
    private fun onCrossDeviceToggled(enabled: Boolean) {
        appSettings.crossDeviceEnabled = enabled
        if (::wsClient.isInitialized) {
            wsClient.setCrossDeviceEnabled(enabled)
        }
        Toast.makeText(
            this,
            if (enabled) "✅ 跨设备模式已启用，正在连接 Gateway…"
            else "🏠 已切换到本地模式",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 消息处理（统一入口 → MessageRouter）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 处理来自文本框或语音识别的自然语言输入。
     * 所有输入均经过 [MessageRouter] 路由，不直接发送。
     */
    private fun handleNaturalLanguageInput(text: String, isVoice: Boolean) {
        if (!::messageRouter.isInitialized) {
            Toast.makeText(this, "⚠️ Router 未初始化", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            this,
            if (isVoice) "🎤 语音: $text" else "📝 文本: $text",
            Toast.LENGTH_SHORT
        ).show()
        val source = if (isVoice) "voice_input" else "text_input"
        messageRouter.route(text, source)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 悬浮窗控制（使用 EnhancedFloatingService）
    // ──────────────────────────────────────────────────────────────────────────

    private fun startEnhancedFloatingService() {
        val intent = Intent(this, EnhancedFloatingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "✅ 悬浮窗已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopEnhancedFloatingService() {
        stopService(Intent(this, EnhancedFloatingService::class.java))
        Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 语音识别
    // ──────────────────────────────────────────────────────────────────────────

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出您的指令...")
        }
        try {
            speechRecognizerLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "❌ 语音识别不可用", Toast.LENGTH_SHORT).show()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 权限辅助
    // ──────────────────────────────────────────────────────────────────────────

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "请开启 UFO³ Galaxy 自主操纵服务", Toast.LENGTH_LONG).show()
    }

    /**
     * 显示当前运行时配置（来自 AppSettings + config.properties 回退）。
     */
    private fun openAgentSettings() {
        val gatewayUrl = appSettings.galaxyGatewayUrl
        val restUrl = appSettings.restBaseUrl
        val crossDevice = appSettings.crossDeviceEnabled
        val agentName = AppConfig.getString("agent.name", "Android Agent")
        val heartbeat = AppConfig.getString("heartbeat.interval", "30000")

        val message = buildString {
            appendLine("跨设备模式: ${if (crossDevice) "已启用" else "已禁用"}")
            appendLine()
            appendLine("Gateway URL: $gatewayUrl")
            appendLine("REST URL: $restUrl")
            appendLine("Agent 名称: $agentName")
            val heartbeatDisplay = heartbeat.toLongOrNull()
                ?.let { "${it / 1000}s" } ?: "未知配置"
            appendLine("心跳间隔: $heartbeatDisplay")
            appendLine()
            appendLine("如需修改默认值，请编辑 assets/config.properties 后重启应用。")
            appendLine("运行时修改通过跨设备开关实时生效。")
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Agent 设置")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 生命周期
    // ──────────────────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        if (::galaxyAgent.isInitialized) galaxyAgent.stop()
        if (::wsClient.isInitialized) wsClient.disconnect()
    }
}


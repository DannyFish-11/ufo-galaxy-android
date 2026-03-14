package com.ufo.galaxy.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ufo.galaxy.R
import com.ufo.galaxy.service.FloatingWindowService
import com.ufo.galaxy.service.GalaxyConnectionService
import com.ufo.galaxy.ui.components.ChatScreen
import com.ufo.galaxy.ui.components.DiagnosticsScreen
import com.ufo.galaxy.ui.components.ScrollPaperContainer
import com.ufo.galaxy.ui.components.copyDiagnostics
import com.ufo.galaxy.ui.components.shareLogs
import com.ufo.galaxy.ui.theme.UFOGalaxyTheme
import com.ufo.galaxy.ui.viewmodel.MainViewModel

/**
 * UFO Galaxy Android - 主 Activity
 * 实现书法卷轴式 UI 和系统级 AI 交互
 */
class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }

    // Proper ViewModel creation using the activity-level delegate so the Application is
    // automatically provided to AndroidViewModel.
    private val mainViewModel: MainViewModel by viewModels()
    
    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.d(TAG, "所有权限已授予")
            startServices()
        } else {
            Log.w(TAG, "部分权限被拒绝")
            Toast.makeText(this, "部分功能可能受限", Toast.LENGTH_SHORT).show()
        }
    }
    
    // 悬浮窗权限请求
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            Log.d(TAG, "悬浮窗权限已授予")
            startFloatingService()
        } else {
            Log.w(TAG, "悬浮窗权限被拒绝")
            Toast.makeText(this, "灵动岛功能需要悬浮窗权限", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "MainActivity 创建")
        
        // 请求权限
        requestPermissions()
        
        // 设置 Compose UI
        setContent {
            UFOGalaxyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel = mainViewModel)
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        mainViewModel.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        mainViewModel.onPause()
    }
    
    /**
     * 请求必要权限
     */
    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        
        permissionLauncher.launch(permissions.toTypedArray())
    }
    
    /**
     * 请求悬浮窗权限
     */
    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            startFloatingService()
        }
    }
    
    /**
     * 启动服务
     */
    private fun startServices() {
        // 启动 Galaxy 连接服务
        val connectionIntent = Intent(this, GalaxyConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(connectionIntent)
        } else {
            startService(connectionIntent)
        }
        
        // 请求悬浮窗权限并启动悬浮窗服务
        requestOverlayPermission()
    }
    
    /**
     * 启动悬浮窗服务
     */
    private fun startFloatingService() {
        val floatingIntent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(floatingIntent)
        } else {
            startService(floatingIntent)
        }
    }
}

/**
 * Read-only status bar showing WebSocket connection state, offline queue depth,
 * and current reconnect attempt count (PR14 debug UI).
 *
 * Only visible when [crossDeviceEnabled] is true.  Displays:
 * - A green "● 已连接" or red "● 断开" indicator.
 * - Queue depth when > 0: "队列: N".
 * - Retry counter when > 0 and disconnected: "重试: N".
 */
@Composable
private fun ConnectionStatusBar(
    isConnected: Boolean,
    crossDeviceEnabled: Boolean,
    queueSize: Int,
    reconnectAttempt: Int
) {
    if (!crossDeviceEnabled) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val (dot, label, tint) = if (isConnected) {
                Triple("●", "已连接", MaterialTheme.colorScheme.primary)
            } else {
                Triple("●", "断开", MaterialTheme.colorScheme.error)
            }
            Text(
                text = "$dot $label",
                style = MaterialTheme.typography.labelSmall,
                color = tint
            )
            if (queueSize > 0) {
                Text(
                    text = "队列: $queueSize",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isConnected && reconnectAttempt > 0) {
                Text(
                    text = "重试: $reconnectAttempt",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Non-blocking readiness status banner shown in degraded mode.
 *
 * Displays which capabilities are unavailable so the user can take corrective action
 * (e.g. enable the accessibility service or grant overlay permission). The banner does
 * not block interaction; all chat and task features remain accessible.
 */
@Composable
private fun ReadinessBanner(
    modelReady: Boolean,
    accessibilityReady: Boolean,
    overlayReady: Boolean
) {
    val issues = buildList {
        if (!modelReady) add("本地模型未就绪")
        if (!accessibilityReady) add("无障碍服务未启用")
        if (!overlayReady) add("悬浮窗权限未授予")
    }
    if (issues.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "⚠ 降级模式：${issues.joinToString("、")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 主屏幕 Composable
 *
 * The [TopAppBar] includes a cross-device toggle that persists the setting via
 * [MainViewModel.toggleCrossDeviceEnabled] and reconnects (or disconnects) the
 * gateway WebSocket accordingly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Show registration failure dialog when cross-device registration fails.
    uiState.registrationFailure?.let { reason ->
        AlertDialog(
            onDismissRequest = { viewModel.clearRegistrationFailure() },
            title = { Text("跨设备注册失败") },
            text = { Text(reason) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearRegistrationFailure() }) {
                    Text("确定")
                }
            }
        )
    }

    // Show diagnostics overlay when toggled
    if (uiState.showDiagnostics) {
        DiagnosticsScreen(
            isConnected = uiState.isConnected,
            lastErrorReason = uiState.lastErrorReason,
            lastTaskId = uiState.lastTaskId,
            modelReady = uiState.modelReady,
            accessibilityReady = uiState.accessibilityReady,
            overlayReady = uiState.overlayReady,
            reconnectAttempt = uiState.reconnectAttempt,
            queueSize = uiState.queueSize,
            recentErrors = uiState.recentErrors,
            recentTaskIds = uiState.recentTaskIds,
            networkOk = uiState.networkOk,
            batteryOptimizationsDisabled = uiState.batteryOptimizationsDisabled,
            onClose = { viewModel.toggleDiagnostics() },
            onExportLogs = {
                val file = viewModel.getLogFile()
                if (file != null) {
                    shareLogs(context, file)
                } else {
                    Toast.makeText(context, "No logs yet – logs are created after the first connection or task event.", Toast.LENGTH_SHORT).show()
                }
            },
            onCopyDiagnostics = {
                val text = viewModel.buildDiagnosticsText()
                copyDiagnostics(context, text)
                Toast.makeText(context, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UFO Galaxy") },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        // Diagnostics button (PR15)
                        IconButton(onClick = { viewModel.toggleDiagnostics() }) {
                            Icon(Icons.Default.Info, contentDescription = "Diagnostics")
                        }
                        Text(
                            text = stringResource(R.string.settings_cross_device),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Switch(
                            checked = uiState.crossDeviceEnabled,
                            onCheckedChange = { viewModel.toggleCrossDeviceEnabled() }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Show a non-blocking readiness status banner when in degraded mode.
                if (uiState.degradedMode) {
                    ReadinessBanner(
                        modelReady = uiState.modelReady,
                        accessibilityReady = uiState.accessibilityReady,
                        overlayReady = uiState.overlayReady
                    )
                }

                // Read-only connection state + offline queue status bar (PR14).
                ConnectionStatusBar(
                    isConnected = uiState.isConnected,
                    crossDeviceEnabled = uiState.crossDeviceEnabled,
                    queueSize = uiState.queueSize,
                    reconnectAttempt = uiState.reconnectAttempt
                )

                // 书法卷轴容器
                ScrollPaperContainer(
                    isExpanded = uiState.isScrollExpanded,
                    onExpandChange = { viewModel.toggleScroll() },
                    modifier = Modifier.weight(1f)
                ) {
                    // 聊天界面
                    ChatScreen(
                        messages = uiState.messages,
                        inputText = uiState.inputText,
                        isLoading = uiState.isLoading,
                        onInputChange = { viewModel.updateInput(it) },
                        onSend = { viewModel.sendMessage() },
                        onVoiceInput = { viewModel.startVoiceInput() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

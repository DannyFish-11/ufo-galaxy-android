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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ufo.galaxy.R
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.data.SharedPrefsAppSettings
import com.ufo.galaxy.service.EnhancedFloatingService
import com.ufo.galaxy.service.GalaxyConnectionService
import com.ufo.galaxy.ui.components.ChatScreen
import com.ufo.galaxy.ui.components.DiagnosticsScreen
import com.ufo.galaxy.ui.components.LocalLoopDebugPanel
import com.ufo.galaxy.ui.components.ScrollPaperContainer
import com.ufo.galaxy.ui.components.copyDiagnostics
import com.ufo.galaxy.ui.components.shareLogs
import com.ufo.galaxy.ui.theme.UFOGalaxyTheme
import com.ufo.galaxy.runtime.PhaseStateMachine
import com.ufo.galaxy.ui.viewmodel.MainViewModel

/**
 * UFO Galaxy Android - 主 Activity
 * 实现书法卷轴式 UI 和系统级 AI 交互
 */
class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        const val ENTRYPOINT_ROLE = "sub_entry"
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
            // CRITICAL: Detect permanently denied permissions (user selected "Don't ask again")
            // and show a guidance dialog directing to app settings.
            val permanentlyDenied = permissions.filter { !it.value }.keys.filter { perm ->
                !shouldShowRequestPermissionRationale(perm)
            }
            if (permanentlyDenied.isNotEmpty()) {
                Log.w(TAG, "Permissions permanently denied: $permanentlyDenied — showing settings guidance")
                showPermissionSettingsDialog(permanentlyDenied)
            } else {
                Toast.makeText(this, "部分功能可能受限", Toast.LENGTH_SHORT).show()
            }
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

        // PR-URL-REQUIRED: Check if configured on first launch
        val appSettings = AppSettings.getInstance(applicationContext)
        if (!appSettings.isConfigured()) {
            Log.i(TAG, "First launch — gateway not configured, launching FirstTimeSetupActivity")
            startActivity(Intent(this, FirstTimeSetupActivity::class.java))
            finish()
            return
        }

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
     * B1-FIX: Added BLUETOOTH/BLUETOOTH_ADMIN for Android <= 11 and REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * to ensure the foreground service can survive Doze mode.
     *
     * ROUND-3-FIX: Only request permissions that are not already granted. This avoids
     * re-prompting the user on every Activity recreation (configuration change, etc.).
     */
    private fun requestPermissions() {
        val allPermissions = mutableListOf<String>()
        val permissionsToRequest = mutableListOf<String>()

        allPermissions.add(Manifest.permission.INTERNET)
        allPermissions.add(Manifest.permission.RECORD_AUDIO)
        allPermissions.add(Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            allPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            allPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            allPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // B1-FIX: Pre-Android 12 devices need BLUETOOTH and BLUETOOTH_ADMIN
            allPermissions.add(Manifest.permission.BLUETOOTH)
            allPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        // ROUND-3-FIX: Filter out already-granted permissions before launching the request.
        for (perm in allPermissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(perm)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d(TAG, "All permissions already granted — skipping request")
            startServices()
        }

        // B1-FIX: Request battery optimization exemption for long-running foreground service
        requestBatteryOptimizationExemption()

        // B1-FIX: Request overlay permission together with other permissions
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }
    
    /**
     * CRITICAL: Shows a dialog guiding the user to the app settings when one or more
     * permissions have been permanently denied ("Don't ask again").
     *
     * The dialog explains which permissions are required and provides a button that
     * opens the app settings page so the user can manually grant them.
     */
    private fun showPermissionSettingsDialog(permanentlyDenied: List<String>) {
        val permissionNames = permanentlyDenied.map { perm ->
            when (perm) {
                Manifest.permission.RECORD_AUDIO -> "麦克风"
                Manifest.permission.CAMERA -> "相机"
                Manifest.permission.POST_NOTIFICATIONS -> "通知"
                Manifest.permission.BLUETOOTH_CONNECT -> "蓝牙连接"
                Manifest.permission.BLUETOOTH_SCAN -> "蓝牙扫描"
                else -> perm.substringAfterLast(".")
            }
        }.joinToString("、")

        android.app.AlertDialog.Builder(this)
            .setTitle("权限已被永久拒绝")
            .setMessage("以下权限已被永久拒绝，需要到应用设置中手动开启：\n\n$permissionNames\n\n请在设置中找到 UFO Galaxy 并开启相应权限。")
            .setPositiveButton("前往设置") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open app settings: ${e.message}")
                    Toast.makeText(this, "无法打开设置页面", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show()
    }

    /**
     * B1-FIX: Request battery optimization whitelist to keep foreground service alive in Doze mode.
     */
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to request battery optimization exemption: ${e.message}")
                }
            }
        }
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
        val connectionIntent = GalaxyConnectionService.createMainEntryIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(connectionIntent)
        } else {
            startService(connectionIntent)
        }
        
        // 请求悬浮窗权限并启动悬浮窗服务
        requestOverlayPermission()
    }
    
    /**
     * Starts the canonical floating-island surface ([EnhancedFloatingService]).
     *
     * All floating-UI entry points — [MainActivity], [BootReceiver], and
     * [FloatingIslandActivity] — must start [EnhancedFloatingService].
     * The legacy [com.ufo.galaxy.service.FloatingWindowService] is retained in
     * AndroidManifest.xml for compatibility only and is no longer started from
     * any canonical path.
     */
    private fun startFloatingService() {
        val floatingIntent = Intent(this, EnhancedFloatingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(floatingIntent)
        } else {
            startService(floatingIntent)
        }
    }
}

/**
 * Read-only status bar showing WebSocket connection state, offline queue depth,
 * current reconnect attempt count (PR14 debug UI), and current three-phase state
 * (SILENT / LIMINAL / MANIFEST) — C9-FIX.
 *
 * Only visible when [crossDeviceEnabled] is true.  Displays:
 * - A green "● 已连接" or red "● 断开" indicator.
 * - Current phase indicator (Silent / Liminal / Manifest) with distinct colours.
 * - Queue depth when > 0: "队列: N".
 * - Retry counter when > 0 and disconnected: "重试: N".
 */
@Composable
private fun ConnectionStatusBar(
    isConnected: Boolean,
    crossDeviceEnabled: Boolean,
    queueSize: Int,
    reconnectAttempt: Int,
    // C9-FIX: Current phase from PhaseStateMachine for UI visualization.
    currentPhase: PhaseStateMachine.Phase = PhaseStateMachine.Phase.SILENT
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
            // C9-FIX: Display current three-phase state with colour coding
            val phaseLabel = when (currentPhase) {
                PhaseStateMachine.Phase.SILENT -> "Silent"
                PhaseStateMachine.Phase.LIMINAL -> "Liminal"
                PhaseStateMachine.Phase.MANIFEST -> "Manifest"
            }
            val phaseColor = when (currentPhase) {
                PhaseStateMachine.Phase.SILENT -> MaterialTheme.colorScheme.outline
                PhaseStateMachine.Phase.LIMINAL -> MaterialTheme.colorScheme.tertiary
                PhaseStateMachine.Phase.MANIFEST -> MaterialTheme.colorScheme.primary
            }
            Text(
                text = "[$phaseLabel]",
                style = MaterialTheme.typography.labelSmall,
                color = phaseColor
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

    // Show network settings screen when requested
    if (uiState.showNetworkSettings) {
        val settings = UFOGalaxyApplication.appSettings
        NetworkSettingsScreen(
            wsStateLabel = if (uiState.isConnected) "已连接" else if (uiState.crossDeviceEnabled) "连接中…" else "已断开",
            wsConnected = uiState.isConnected,
            initialGatewayHost = settings.gatewayHost,
            initialGatewayPort = settings.gatewayPort.toString(),
            initialUseTls = settings.useTls,
            initialAllowSelfSigned = settings.allowSelfSigned,
            initialDeviceId = settings.deviceId,
            initialRestBase = settings.restBaseUrl,
            initialMetricsEndpoint = settings.metricsEndpoint,
            isDiagnosticsRunning = uiState.isDiagnosticsRunning,
            diagnosticsText = uiState.diagnosticsReport,
            onSave = { host, port, tls, selfSigned, deviceId, restBase, metrics ->
                viewModel.saveNetworkSettings(host, port, tls, selfSigned, deviceId, restBase, metrics, reconnect = false)
                Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
            },
            onSaveAndReconnect = { host, port, tls, selfSigned, deviceId, restBase, metrics ->
                viewModel.saveNetworkSettings(host, port, tls, selfSigned, deviceId, restBase, metrics, reconnect = true)
                Toast.makeText(context, "设置已保存，正在重连…", Toast.LENGTH_SHORT).show()
            },
            onAutoDiscover = { viewModel.autoDiscoverTailscale() },
            onFillTailscaleIp = { viewModel.fillTailscaleIp() },
            onRunDiagnostics = { viewModel.runNetworkDiagnostics() },
            onClose = { viewModel.closeNetworkSettings() }
        )
        return
    }

    // Show registration failure dialog when cross-device registration fails.
    uiState.registrationFailure?.let { reason ->
        AlertDialog(
            onDismissRequest = { viewModel.clearRegistrationFailure() },
            title = { Text("跨设备注册失败") },
            text = { Text(reason) },
            confirmButton = {
                TextButton(onClick = { viewModel.retryRegistration() }) {
                    Text("重试")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.clearRegistrationFailure()
                    viewModel.openNetworkSettings()
                }) {
                    Text("查看诊断/设置")
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

    // Show local-loop debug panel (debug-only entry point, PR-G / PR-H)
    if (uiState.showLocalLoopDebug) {
        val debugVm = viewModel.localLoopDebugViewModel
        val debugState by debugVm.state.collectAsState()
        LocalLoopDebugPanel(
            state = debugState,
            onClose = { viewModel.closeLocalLoopDebug() },
            onRefresh = { debugVm.refresh() },
            onRerunGoal = { debugVm.rerunLastGoal() },
            onClearTrace = { debugVm.clearTraceState() },
            onForceReadiness = { debugVm.forceReadinessRefresh() },
            onEmitSnapshot = { debugVm.emitDiagnosticSnapshot() },
            onClearHistory = { debugVm.clearSessionHistory() }
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
                        // Local-loop debug panel button (PR-G, debug-only)
                        IconButton(onClick = { viewModel.openLocalLoopDebug() }) {
                            Icon(Icons.Default.BugReport, contentDescription = "Local-Loop Debug")
                        }
                        // Network settings button (网络与诊断增强包)
                        IconButton(onClick = { viewModel.openNetworkSettings() }) {
                            Icon(Icons.Default.Settings, contentDescription = "网络与诊断设置")
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
                // C9-FIX: Pass currentPhase to display three-phase state (SILENT/LIMINAL/MANIFEST).
                ConnectionStatusBar(
                    isConnected = uiState.isConnected,
                    crossDeviceEnabled = uiState.crossDeviceEnabled,
                    queueSize = uiState.queueSize,
                    reconnectAttempt = uiState.reconnectAttempt,
                    currentPhase = uiState.currentPhase
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

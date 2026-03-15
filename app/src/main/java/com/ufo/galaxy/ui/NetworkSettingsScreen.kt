package com.ufo.galaxy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 网络与诊断设置界面（网络与诊断增强包）
 *
 * 用户可在此界面配置：
 * - 网关主机/IP、端口
 * - TLS 开关、允许自签名证书（调试选项）
 * - 设备 ID
 * - REST 基础 URL（当为空时由 host+port 自动推导）
 * - 指标上报端点（可选）
 *
 * 操作按钮：
 * - 保存               — 写入 SharedPreferences
 * - 保存并重连          — 写入后触发 WebSocket 重连
 * - 自动探测           — 调用 TailscaleAdapter.autoDiscoverNode50
 * - 一键填入 Tailscale — 检测本机 Tailscale IP 并写入主机字段
 *
 * 顶部显示当前 WS 连接状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsScreen(
    /** 当前 WS 状态文本，例如 "已连接" / "连接中…" / "已断开" */
    wsStateLabel: String,
    /** true = 当前已连接 */
    wsConnected: Boolean,
    /** 当前设置字段初始值 */
    initialGatewayHost: String,
    initialGatewayPort: String,
    initialUseTls: Boolean,
    initialAllowSelfSigned: Boolean,
    initialDeviceId: String,
    initialRestBase: String,
    initialMetricsEndpoint: String,
    /** 诊断运行中 */
    isDiagnosticsRunning: Boolean,
    /** 最近一次诊断报告文本，null = 尚未运行 */
    diagnosticsText: String?,
    /** 回调 */
    onSave: (host: String, port: Int, useTls: Boolean, allowSelfSigned: Boolean,
             deviceId: String, restBase: String, metricsEndpoint: String) -> Unit,
    onSaveAndReconnect: (host: String, port: Int, useTls: Boolean, allowSelfSigned: Boolean,
                         deviceId: String, restBase: String, metricsEndpoint: String) -> Unit,
    onAutoDiscover: () -> Unit,
    onFillTailscaleIp: () -> Unit,
    onRunDiagnostics: () -> Unit,
    onClose: () -> Unit
) {
    // Form state
    var gatewayHost by remember { mutableStateOf(initialGatewayHost) }
    var gatewayPort by remember { mutableStateOf(initialGatewayPort) }
    var useTls by remember { mutableStateOf(initialUseTls) }
    var allowSelfSigned by remember { mutableStateOf(initialAllowSelfSigned) }
    var deviceId by remember { mutableStateOf(initialDeviceId) }
    var restBase by remember { mutableStateOf(initialRestBase) }
    var metricsEndpoint by remember { mutableStateOf(initialMetricsEndpoint) }

    fun currentValues() = Triple(
        gatewayHost to gatewayPort.toIntOrNull(),
        Triple(useTls, allowSelfSigned, deviceId),
        Pair(restBase, metricsEndpoint)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("网络与诊断") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── WS 状态 ──────────────────────────────────────────────────────
            WsStateChip(label = wsStateLabel, connected = wsConnected)

            // ── 网关配置 ─────────────────────────────────────────────────────
            SectionTitle("网关配置")

            OutlinedTextField(
                value = gatewayHost,
                onValueChange = { gatewayHost = it },
                label = { Text("主机 / IP") },
                placeholder = { Text("100.64.0.1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = gatewayPort,
                onValueChange = { gatewayPort = it },
                label = { Text("端口") },
                placeholder = { Text("8765") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("使用 TLS (wss:// / https://)", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = useTls, onCheckedChange = { useTls = it })
            }

            if (useTls) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("允许自签名证书", style = MaterialTheme.typography.bodyMedium)
                        Text("⚠ 仅限调试环境", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    Switch(checked = allowSelfSigned, onCheckedChange = { allowSelfSigned = it })
                }
            }

            // ── 设备与端点 ───────────────────────────────────────────────────
            SectionTitle("设备与端点")

            OutlinedTextField(
                value = deviceId,
                onValueChange = { deviceId = it },
                label = { Text("设备 ID") },
                placeholder = { Text("android-phone-1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = restBase,
                onValueChange = { restBase = it },
                label = { Text("REST 基础 URL（可留空，由主机自动推导）") },
                placeholder = { Text("http://100.64.0.1:8765") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = metricsEndpoint,
                onValueChange = { metricsEndpoint = it },
                label = { Text("指标上报端点（可选）") },
                placeholder = { Text("http://100.64.0.1:9090/metrics") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ── 操作按钮 ─────────────────────────────────────────────────────
            SectionTitle("操作")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val port = gatewayPort.toIntOrNull() ?: 8765
                        onSave(gatewayHost, port, useTls, allowSelfSigned, deviceId, restBase, metricsEndpoint)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存")
                }
                Button(
                    onClick = {
                        val port = gatewayPort.toIntOrNull() ?: 8765
                        onSaveAndReconnect(gatewayHost, port, useTls, allowSelfSigned, deviceId, restBase, metricsEndpoint)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存并重连")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onAutoDiscover,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("自动探测")
                }
                OutlinedButton(
                    onClick = onFillTailscaleIp,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("一键填入 Tailscale")
                }
            }

            // ── 网络诊断 ─────────────────────────────────────────────────────
            SectionTitle("网络诊断")

            Button(
                onClick = onRunDiagnostics,
                enabled = !isDiagnosticsRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isDiagnosticsRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("诊断中…")
                } else {
                    Text("运行诊断")
                }
            }

            diagnosticsText?.let { text ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Helper composables ────────────────────────────────────────────────────────

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
    HorizontalDivider()
}

@Composable
private fun WsStateChip(label: String, connected: Boolean) {
    val (containerColor, contentColor) = if (connected) {
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.wrapContentWidth()
    ) {
        Text(
            text = "● $label",
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

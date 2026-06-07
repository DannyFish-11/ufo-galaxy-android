package com.ufo.galaxy.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ufo.galaxy.ui.viewmodel.ErrorEntry
import com.ufo.galaxy.ui.viewmodel.TaskEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics panel displayed as a full-screen overlay from [com.ufo.galaxy.ui.MainActivity].
 *
 * Shows a summary of the current device-side observability state:
 * - Clear status badge (Connected / Disconnected / Degraded)
 * - Last 5 errors with timestamps
 * - Last 5 task ids and outcomes
 * - Quick actions: Copy diagnostics to clipboard, Share diagnostics (log file)
 * - Lightweight health checklist (network, permissions, battery optimisations)
 *
 * @param isConnected                 True when the WebSocket is connected.
 * @param lastErrorReason             Most recent error string, or empty if none.
 * @param lastTaskId                  Task id of the most recently received task, or empty.
 * @param modelReady                  True when local model files are verified.
 * @param accessibilityReady          True when the accessibility service is enabled.
 * @param overlayReady                True when the SYSTEM_ALERT_WINDOW permission is granted.
 * @param reconnectAttempt            Current reconnect attempt count.
 * @param queueSize                   Number of messages in the offline queue.
 * @param recentErrors                Up to 5 recent error entries (newest-last).
 * @param recentTaskIds               Up to 5 recent task entries (newest-last).
 * @param networkOk                   True when the active network appears reachable.
 * @param batteryOptimizationsDisabled True when the OS exemption has been granted.
 * @param onClose                     Called when the user taps the close / back button.
 * @param onExportLogs                Called when the user taps "Share Logs"; provides the
 *                                    log [File] (may be `null` if no log exists yet).
 * @param onCopyDiagnostics           Called when the user taps "Copy Diagnostics"; the
 *                                    caller should place the diagnostics text on the clipboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    isConnected: Boolean,
    lastErrorReason: String,
    lastTaskId: String,
    modelReady: Boolean,
    accessibilityReady: Boolean,
    overlayReady: Boolean,
    reconnectAttempt: Int,
    queueSize: Int,
    recentErrors: List<ErrorEntry> = emptyList(),
    recentTaskIds: List<TaskEntry> = emptyList(),
    networkOk: Boolean = false,
    batteryOptimizationsDisabled: Boolean = false,
    onClose: () -> Unit,
    onExportLogs: () -> Unit,
    onCopyDiagnostics: () -> Unit = {}
) {
    val degraded = !modelReady || !accessibilityReady || !overlayReady

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close diagnostics")
                    }
                },
                actions = {
                    IconButton(onClick = onCopyDiagnostics) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy diagnostics")
                    }
                    IconButton(onClick = onExportLogs) {
                        Icon(Icons.Default.Share, contentDescription = "Share logs")
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
            // ── Status badge ─────────────────────────────────────────────────
            ConnectionStatusBadge(isConnected = isConnected, degraded = degraded)

            // ── Connection section ────────────────────────────────────────────
            DiagnosticsSection(title = "Connection") {
                DiagnosticsRow("Reconnect attempts", reconnectAttempt.toString())
                DiagnosticsRow("Offline queue", queueSize.toString())
            }

            // ── Health checklist ──────────────────────────────────────────────
            DiagnosticsSection(title = "Health Checklist") {
                HealthCheckRow("Network reachable", networkOk)
                HealthCheckRow(
                    label = "Permissions OK",
                    ok = accessibilityReady && overlayReady
                )
                HealthCheckRow("Battery optimizations disabled", batteryOptimizationsDisabled)
            }

            // ── Readiness flags ───────────────────────────────────────────────
            DiagnosticsSection(title = "Readiness Flags") {
                DiagnosticsRow(
                    "Model files",
                    if (modelReady) "✓ Ready" else "✗ Not ready",
                    valueColor = if (modelReady) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                DiagnosticsRow(
                    "Accessibility",
                    if (accessibilityReady) "✓ Enabled" else "✗ Disabled",
                    valueColor = if (accessibilityReady) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                DiagnosticsRow(
                    "Overlay (SYSTEM_ALERT_WINDOW)",
                    if (overlayReady) "✓ Granted" else "✗ Denied",
                    valueColor = if (overlayReady) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                DiagnosticsRow(
                    "Degraded mode",
                    if (degraded) "Yes" else "No",
                    valueColor = if (degraded) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }

            // ── Recent errors ─────────────────────────────────────────────────
            DiagnosticsSection(title = "Recent Errors (last ${recentErrors.size})") {
                if (recentErrors.isEmpty()) {
                    DiagnosticsRow("—", "no errors recorded")
                } else {
                    recentErrors.asReversed().forEach { e ->
                        DiagnosticsRow(
                            label = formatTs(e.ts),
                            value = e.reason,
                            valueColor = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ── Recent tasks ──────────────────────────────────────────────────
            DiagnosticsSection(title = "Recent Tasks (last ${recentTaskIds.size})") {
                if (recentTaskIds.isEmpty()) {
                    DiagnosticsRow("—", "no tasks recorded")
                } else {
                    recentTaskIds.asReversed().forEach { t ->
                        val outcomeColor = when (t.outcome) {
                            "success" -> MaterialTheme.colorScheme.primary
                            "received" -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.error
                        }
                        DiagnosticsRow(
                            label = "${formatTs(t.ts)} ${t.taskId.take(12)}…",
                            value = t.outcome,
                            valueColor = outcomeColor
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Quick actions ─────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCopyDiagnostics,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
                Button(
                    onClick = onExportLogs,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Share Logs")
                }
            }
        }
    }
}

// ── Internal helpers ──────────────────────────────────────────────────────────

/**
 * Prominent pill-shaped badge showing the overall connection / health status.
 */
@Composable
private fun ConnectionStatusBadge(isConnected: Boolean, degraded: Boolean) {
    val (label, containerColor, contentColor) = when {
        isConnected && !degraded -> Triple(
            "● Connected",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        isConnected && degraded -> Triple(
            "● Degraded",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        else -> Triple(
            "● Disconnected",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        modifier = Modifier.wrapContentWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

/**
 * A single health-check row with a green check or red ✗ icon.
 */
@Composable
private fun HealthCheckRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (ok) "✓" else "✗",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun DiagnosticsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            content()
        }
    }
}

@Composable
private fun DiagnosticsRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = valueColor
        )
    }
}

private fun formatTs(ts: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts))

/**
 * Convenience function: fires a share sheet for [logFile] using [FileProvider].
 *
 * The caller must declare a [FileProvider] authority of `<packageName>.provider`
 * in `AndroidManifest.xml` and a matching `res/xml/file_provider_paths.xml`
 * (already present in the project via `files_path` pointing at `filesDir`).
 */
fun shareLogs(context: Context, logFile: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        logFile
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "UFO Galaxy Observability Log")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Export Logs"))
}

/**
 * Copies [text] to the system clipboard with label "UFO Galaxy Diagnostics".
 */
fun copyDiagnostics(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("UFO Galaxy Diagnostics", text))
}

package com.ufo.galaxy.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

/**
 * Diagnostics panel displayed as a full-screen overlay from [com.ufo.galaxy.ui.MainActivity].
 *
 * Shows a summary of the current device-side observability state:
 * - WebSocket connection state
 * - Last error reason
 * - Last task id received
 * - Readiness flags (model / accessibility / overlay)
 *
 * A "Export Logs" button triggers a standard Android share-sheet pointing at the
 * [GalaxyLogger] log file so the user can forward it by e-mail, AirDrop, etc.
 *
 * @param isConnected         True when the WebSocket is connected.
 * @param lastErrorReason     Last recorded error string, or empty if none.
 * @param lastTaskId          Task id of the most recently received task, or empty.
 * @param modelReady          True when local model files are verified.
 * @param accessibilityReady  True when the accessibility service is enabled.
 * @param overlayReady        True when the SYSTEM_ALERT_WINDOW permission is granted.
 * @param reconnectAttempt    Current reconnect attempt count.
 * @param queueSize           Number of messages in the offline queue.
 * @param onClose             Called when the user taps the close / back button.
 * @param onExportLogs        Called when the user taps "Export Logs"; provides the
 *                            log [File] (may be `null` if no log exists yet).
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
    onClose: () -> Unit,
    onExportLogs: () -> Unit
) {
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
                    IconButton(onClick = onExportLogs) {
                        Icon(Icons.Default.Share, contentDescription = "Export logs")
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
            DiagnosticsSection(title = "Connection") {
                DiagnosticsRow("State", if (isConnected) "● Connected" else "● Disconnected",
                    valueColor = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                DiagnosticsRow("Reconnect attempts", reconnectAttempt.toString())
                DiagnosticsRow("Offline queue", queueSize.toString())
            }

            DiagnosticsSection(title = "Last Task") {
                DiagnosticsRow("Task ID",  lastTaskId.ifEmpty { "—" })
                DiagnosticsRow("Last error", lastErrorReason.ifEmpty { "—" })
            }

            DiagnosticsSection(title = "Readiness Flags") {
                DiagnosticsRow("Model files",      if (modelReady)         "✓ Ready"    else "✗ Not ready",
                    valueColor = if (modelReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                DiagnosticsRow("Accessibility",    if (accessibilityReady) "✓ Enabled"  else "✗ Disabled",
                    valueColor = if (accessibilityReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                DiagnosticsRow("Overlay (SYSTEM_ALERT_WINDOW)",
                    if (overlayReady) "✓ Granted" else "✗ Denied",
                    valueColor = if (overlayReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                DiagnosticsRow("Degraded mode",
                    if (!modelReady || !accessibilityReady || !overlayReady) "Yes" else "No",
                    valueColor = if (!modelReady || !accessibilityReady || !overlayReady)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onExportLogs,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Export Logs")
            }
        }
    }
}

// ── Internal helpers ──────────────────────────────────────────────────────────

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
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
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

package com.ufo.galaxy.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ufo.galaxy.debug.LocalLoopDebugState
import com.ufo.galaxy.history.SessionHistorySummary
import com.ufo.galaxy.local.LocalLoopReadiness
import com.ufo.galaxy.trace.LocalLoopTrace
import com.ufo.galaxy.trace.TerminalResult

/**
 * Debug-only panel that surfaces local-loop readiness, config, trace information,
 * and persistent session history for developers during local development and testing.
 *
 * This composable is **not** shown in production UI flows. It is surfaced only when
 * [com.ufo.galaxy.ui.viewmodel.MainUiState.showLocalLoopDebug] is `true`, which is
 * toggled via a debug-only entry point in [com.ufo.galaxy.ui.MainActivity].
 *
 * @param state            Current [LocalLoopDebugState] to display.
 * @param onClose          Called when the user taps the close button.
 * @param onRefresh        Called when the user taps the "Refresh" action.
 * @param onRerunGoal      Called when the user taps "Re-run Last Goal".
 * @param onClearTrace     Called when the user taps "Clear Trace State".
 * @param onForceReadiness Called when the user taps "Force Readiness Refresh".
 * @param onEmitSnapshot   Called when the user taps "Emit Snapshot".
 * @param onClearHistory   Called when the user taps "Clear History". No-op when `null`.
 * @param historyMaxShown  Maximum number of history entries to display. Defaults to 10.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalLoopDebugPanel(
    state: LocalLoopDebugState,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onRerunGoal: () -> Unit,
    onClearTrace: () -> Unit,
    onForceReadiness: () -> Unit,
    onEmitSnapshot: () -> Unit,
    onClearHistory: (() -> Unit)? = null,
    historyMaxShown: Int = 10
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local-Loop Debug") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close debug panel")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
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
            // ── Readiness snapshot ────────────────────────────────────────────
            DebugSection(title = "Readiness Snapshot") {
                ReadinessContent(readiness = state.readinessSnapshot)
            }

            // ── Config snapshot ───────────────────────────────────────────────
            DebugSection(title = "Config Snapshot") {
                ConfigContent(state = state)
            }

            // ── Latest trace ──────────────────────────────────────────────────
            DebugSection(title = "Latest Trace (${state.traceCount} retained)") {
                TraceContent(trace = state.latestTrace, terminal = state.lastTerminalResult)
            }

            // ── Last goal ─────────────────────────────────────────────────────
            DebugSection(title = "Last Goal") {
                DebugRow("Goal", state.lastGoal ?: "(none)")
            }

            // ── Emitted snapshot ──────────────────────────────────────────────
            if (state.diagnosticSnapshot != null) {
                DebugSection(title = "Emitted Snapshot") {
                    Text(
                        text = state.diagnosticSnapshot,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Session history ───────────────────────────────────────────────
            DebugSection(title = "Session History (${state.historyCount} persisted)") {
                SessionHistoryContent(
                    history = state.sessionHistory,
                    maxShown = historyMaxShown
                )
            }

            Spacer(Modifier.height(4.dp))

            // ── Developer actions ─────────────────────────────────────────────
            DebugSection(title = "Developer Actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onForceReadiness,
                            modifier = Modifier.weight(1f)
                        ) { Text("Readiness\nRefresh", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(
                            onClick = onClearTrace,
                            modifier = Modifier.weight(1f)
                        ) { Text("Clear Trace\nState", style = MaterialTheme.typography.labelSmall) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRerunGoal,
                            enabled = state.lastGoal != null,
                            modifier = Modifier.weight(1f)
                        ) { Text("Re-run\nLast Goal", style = MaterialTheme.typography.labelSmall) }
                        Button(
                            onClick = onEmitSnapshot,
                            modifier = Modifier.weight(1f)
                        ) { Text("Emit\nSnapshot", style = MaterialTheme.typography.labelSmall) }
                    }
                    if (onClearHistory != null) {
                        OutlinedButton(
                            onClick = onClearHistory,
                            enabled = state.historyCount > 0,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Clear Session History (${state.historyCount})", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

// ── Section helpers ────────────────────────────────────────────────────────────

@Composable
private fun DebugSection(
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
private fun DebugRow(label: String, value: String) {
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
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ReadinessContent(readiness: LocalLoopReadiness?) {
    if (readiness == null) {
        DebugRow("Status", "(not yet refreshed)")
        return
    }
    DebugRow("State", readiness.state.name)
    DebugRow("Model files", boolLabel(readiness.modelFilesReady))
    DebugRow("Planner loaded", boolLabel(readiness.plannerLoaded))
    DebugRow("Grounding loaded", boolLabel(readiness.groundingLoaded))
    DebugRow("Accessibility", boolLabel(readiness.accessibilityReady))
    DebugRow("Screenshot", boolLabel(readiness.screenshotReady))
    DebugRow("Action executor", boolLabel(readiness.actionExecutorReady))
    if (readiness.blockers.isNotEmpty()) {
        DebugRow("Blockers", readiness.blockers.joinToString { it.name })
    }
}

@Composable
private fun ConfigContent(state: LocalLoopDebugState) {
    val c = state.configSnapshot
    if (c == null) {
        DebugRow("Config", "(not available)")
        return
    }
    DebugRow("maxSteps", c.maxSteps.toString())
    DebugRow("maxRetriesPerStep", c.maxRetriesPerStep.toString())
    DebugRow("stepTimeoutMs", c.stepTimeoutMs.toString())
    DebugRow("goalTimeoutMs", c.goalTimeoutMs.toString())
    DebugRow("plannerFallback", boolLabel(c.fallback.enablePlannerFallback))
    DebugRow("groundingFallback", boolLabel(c.fallback.enableGroundingFallback))
    DebugRow("remoteHandoff", boolLabel(c.fallback.enableRemoteHandoff))
}

@Composable
private fun TraceContent(trace: LocalLoopTrace?, terminal: TerminalResult?) {
    if (trace == null) {
        DebugRow("Trace", "(no trace available)")
        return
    }
    DebugRow("Session ID", trace.sessionId.take(16) + "…")
    DebugRow("Running", boolLabel(trace.isRunning))
    DebugRow("Steps", trace.stepCount.toString())
    DebugRow("Duration ms", trace.durationMs?.toString() ?: "running")
    if (terminal != null) {
        DebugRow("Status", terminal.status)
        DebugRow("Stop reason", terminal.stopReason ?: "—")
        if (terminal.error != null) {
            DebugRow("Error", terminal.error)
        }
    }
}

private fun boolLabel(value: Boolean) = if (value) "✓ true" else "✗ false"

@Composable
private fun SessionHistoryContent(history: List<SessionHistorySummary>, maxShown: Int) {
    if (history.isEmpty()) {
        DebugRow("History", "(no persisted sessions)")
        return
    }
    val shown = history.take(maxShown)
    shown.forEachIndexed { index, entry ->
        if (index > 0) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
        DebugRow("Session", entry.sessionId.take(16) + "…")
        DebugRow("Status", entry.status)
        DebugRow("Steps", entry.stepCount.toString())
        DebugRow("Duration ms", entry.durationMs.toString())
        if (entry.stopReason != null) DebugRow("Stop reason", entry.stopReason)
        if (entry.error != null) DebugRow("Error", entry.error)
    }
    if (history.size > maxShown) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "… and ${history.size - maxShown} more",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

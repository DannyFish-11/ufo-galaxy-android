package com.ufo.galaxy.ui.viewmodel

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the PR17 diagnostics UX logic that lives in [MainUiState] and the
 * helper data classes [ErrorEntry] / [TaskEntry].
 *
 * These tests run on the JVM and do not require an Android device or emulator.
 *
 * ## Manual verification checklist (device / emulator)
 *
 * 1. **Status badge:**
 *    - Connected + no degraded: green "● Connected" pill at the top.
 *    - Connected + degraded (e.g. accessibility off): amber "● Degraded" pill.
 *    - Disconnected: red "● Disconnected" pill.
 *
 * 2. **Recent errors (last 5):**
 *    - Trigger a WS error (disable Wi-Fi or use an invalid server URL).
 *    - Open Diagnostics panel → "Recent Errors" card shows up to 5 entries with
 *      timestamps formatted as HH:mm:ss and the error message in red.
 *
 * 3. **Recent tasks (last 5):**
 *    - Send 6+ tasks in quick succession.
 *    - Open Diagnostics panel → "Recent Tasks" card shows only the last 5.
 *    - Each row shows timestamp, truncated task id, and outcome ("received" / "success"
 *      / "failed" / "cancelled").
 *
 * 4. **Copy diagnostics:**
 *    - Tap the copy icon in the top-bar or the "Copy" button.
 *    - Paste into any text field → full diagnostics snapshot is pasted including all
 *      sections (Connection, Readiness, Health Checklist, Recent Errors, Recent Tasks).
 *
 * 5. **Share logs:**
 *    - Tap the share icon in the top-bar or the "Share Logs" button.
 *    - Android share-sheet appears offering the galaxy_observability.log file.
 *
 * 6. **Health checklist:**
 *    - Open Diagnostics panel → "Health Checklist" card shows three rows:
 *        • "Network reachable": ✓ when WS is connected, ✗ otherwise.
 *        • "Permissions OK": ✓ when both accessibility and overlay are granted, ✗ otherwise.
 *        • "Battery optimizations disabled": ✓ when the app is exempt from battery optimisation.
 */
class DiagnosticsUxTest {

    // ── ErrorEntry ────────────────────────────────────────────────────────────

    @Test
    fun `ErrorEntry stores timestamp and reason`() {
        val ts = System.currentTimeMillis()
        val entry = ErrorEntry(ts = ts, reason = "network_timeout")
        assertEquals(ts, entry.ts)
        assertEquals("network_timeout", entry.reason)
    }

    // ── TaskEntry ─────────────────────────────────────────────────────────────

    @Test
    fun `TaskEntry stores timestamp, taskId, and outcome`() {
        val ts = System.currentTimeMillis()
        val entry = TaskEntry(ts = ts, taskId = "task-001", outcome = "success")
        assertEquals(ts, entry.ts)
        assertEquals("task-001", entry.taskId)
        assertEquals("success", entry.outcome)
    }

    // ── recentErrors list logic ───────────────────────────────────────────────

    @Test
    fun `adding 6 errors keeps only the last 5`() {
        var state = MainUiState()
        repeat(6) { i ->
            val entry = ErrorEntry(ts = i.toLong(), reason = "error_$i")
            state = state.copy(recentErrors = (state.recentErrors + entry).takeLast(5))
        }
        assertEquals(5, state.recentErrors.size)
        // oldest (i=0) should be gone; i=1 should be the new oldest
        assertEquals("error_1", state.recentErrors.first().reason)
        assertEquals("error_5", state.recentErrors.last().reason)
    }

    @Test
    fun `adding fewer than 5 errors keeps all`() {
        var state = MainUiState()
        repeat(3) { i ->
            val entry = ErrorEntry(ts = i.toLong(), reason = "error_$i")
            state = state.copy(recentErrors = (state.recentErrors + entry).takeLast(5))
        }
        assertEquals(3, state.recentErrors.size)
    }

    // ── recentTaskIds list logic ──────────────────────────────────────────────

    @Test
    fun `adding 7 tasks keeps only the last 5`() {
        var state = MainUiState()
        repeat(7) { i ->
            val entry = TaskEntry(ts = i.toLong(), taskId = "task-$i", outcome = "success")
            state = state.copy(recentTaskIds = (state.recentTaskIds + entry).takeLast(5))
        }
        assertEquals(5, state.recentTaskIds.size)
        assertEquals("task-2", state.recentTaskIds.first().taskId)
        assertEquals("task-6", state.recentTaskIds.last().taskId)
    }

    // ── buildDiagnosticsText ──────────────────────────────────────────────────

    @Test
    fun `buildDiagnosticsText includes all section headers`() {
        val state = MainUiState(
            isConnected = true,
            networkOk = true,
            reconnectAttempt = 2,
            queueSize = 3,
            modelReady = true,
            accessibilityReady = false,
            overlayReady = true,
            degradedMode = true,
            batteryOptimizationsDisabled = true,
            recentErrors = listOf(ErrorEntry(1000L, "err1"), ErrorEntry(2000L, "err2")),
            recentTaskIds = listOf(TaskEntry(3000L, "t-abc", "success"))
        )

        val text = buildDiagnosticsTextFromState(state)

        assertTrue(text.contains("=== UFO Galaxy Diagnostics ==="))
        assertTrue(text.contains("-- Connection --"))
        assertTrue(text.contains("-- Readiness --"))
        assertTrue(text.contains("-- Health Checklist --"))
        assertTrue(text.contains("-- Recent Errors"))
        assertTrue(text.contains("-- Recent Tasks"))
    }

    @Test
    fun `buildDiagnosticsText shows Connected when isConnected=true`() {
        val state = MainUiState(isConnected = true)
        val text = buildDiagnosticsTextFromState(state)
        assertTrue(text.contains("State: Connected"))
    }

    @Test
    fun `buildDiagnosticsText shows Disconnected when isConnected=false`() {
        val state = MainUiState(isConnected = false)
        val text = buildDiagnosticsTextFromState(state)
        assertTrue(text.contains("State: Disconnected"))
    }

    @Test
    fun `buildDiagnosticsText lists recent errors with task ids`() {
        val state = MainUiState(
            recentErrors = listOf(ErrorEntry(1000L, "timeout"), ErrorEntry(2000L, "refused")),
            recentTaskIds = listOf(TaskEntry(3000L, "task-xyz", "success"))
        )
        val text = buildDiagnosticsTextFromState(state)
        assertTrue(text.contains("timeout"))
        assertTrue(text.contains("refused"))
        assertTrue(text.contains("task-xyz"))
        assertTrue(text.contains("success"))
    }

    @Test
    fun `buildDiagnosticsText shows none for empty lists`() {
        val state = MainUiState()
        val text = buildDiagnosticsTextFromState(state)
        assertTrue(text.contains("(none)"))
    }

    @Test
    fun `buildDiagnosticsText shows battery optimizations disabled flag`() {
        val stateOk = MainUiState(batteryOptimizationsDisabled = true)
        val stateNotOk = MainUiState(batteryOptimizationsDisabled = false)
        assertTrue(buildDiagnosticsTextFromState(stateOk).contains("Battery optimizations disabled: true"))
        assertTrue(buildDiagnosticsTextFromState(stateNotOk).contains("Battery optimizations disabled: false"))
    }

    // ── MainUiState defaults ──────────────────────────────────────────────────

    @Test
    fun `MainUiState defaults recentErrors and recentTaskIds to empty`() {
        val state = MainUiState()
        assertTrue(state.recentErrors.isEmpty())
        assertTrue(state.recentTaskIds.isEmpty())
    }

    @Test
    fun `MainUiState defaults networkOk to false`() {
        assertFalse(MainUiState().networkOk)
    }

    @Test
    fun `MainUiState defaults batteryOptimizationsDisabled to false`() {
        assertFalse(MainUiState().batteryOptimizationsDisabled)
    }

    // ── Helper: replicates buildDiagnosticsText logic without ViewModel ───────

    /**
     * Mirrors the [MainViewModel.buildDiagnosticsText] logic so it can be tested
     * without an Android Application instance.
     */
    private fun buildDiagnosticsTextFromState(s: MainUiState): String {
        return buildString {
            appendLine("=== UFO Galaxy Diagnostics ===")
            appendLine("Time: <test>")
            appendLine()
            appendLine("-- Connection --")
            appendLine("State: ${if (s.isConnected) "Connected" else "Disconnected"}")
            appendLine("Network OK: ${s.networkOk}")
            appendLine("Reconnect attempts: ${s.reconnectAttempt}")
            appendLine("Offline queue: ${s.queueSize}")
            appendLine()
            appendLine("-- Readiness --")
            appendLine("Model files: ${if (s.modelReady) "Ready" else "Not ready"}")
            appendLine("Accessibility: ${if (s.accessibilityReady) "Enabled" else "Disabled"}")
            appendLine("Overlay (SYSTEM_ALERT_WINDOW): ${if (s.overlayReady) "Granted" else "Denied"}")
            appendLine("Degraded mode: ${if (s.degradedMode) "Yes" else "No"}")
            appendLine()
            appendLine("-- Health Checklist --")
            appendLine("Network OK: ${s.networkOk}")
            appendLine("Permissions OK: ${s.accessibilityReady && s.overlayReady}")
            appendLine("Battery optimizations disabled: ${s.batteryOptimizationsDisabled}")
            appendLine()
            appendLine("-- Recent Errors (last ${s.recentErrors.size}) --")
            if (s.recentErrors.isEmpty()) {
                appendLine("  (none)")
            } else {
                s.recentErrors.forEach { e -> appendLine("  [ts] ${e.reason}") }
            }
            appendLine()
            appendLine("-- Recent Tasks (last ${s.recentTaskIds.size}) --")
            if (s.recentTaskIds.isEmpty()) {
                appendLine("  (none)")
            } else {
                s.recentTaskIds.forEach { t -> appendLine("  [ts] ${t.taskId} → ${t.outcome}") }
            }
        }.trimEnd()
    }
}

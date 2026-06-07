package com.ufo.galaxy.debug

import com.ufo.galaxy.config.FallbackConfig
import com.ufo.galaxy.config.LocalLoopConfig
import com.ufo.galaxy.local.LocalLoopFailureType
import com.ufo.galaxy.local.LocalLoopReadiness
import com.ufo.galaxy.local.LocalLoopState
import com.ufo.galaxy.trace.LocalLoopTrace
import com.ufo.galaxy.trace.TerminalResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [LocalLoopDebugState] — the data model powering the local-loop debug panel.
 *
 * All tests run on the JVM and do not require an Android device or emulator.
 */
class LocalLoopDebugStateTest {

    // ── Default values ────────────────────────────────────────────────────────

    @Test
    fun `default state has null readiness and config snapshots`() {
        val state = LocalLoopDebugState()
        assertNull(state.readinessSnapshot)
        assertNull(state.configSnapshot)
    }

    @Test
    fun `default state has null trace and terminal result`() {
        val state = LocalLoopDebugState()
        assertNull(state.latestTrace)
        assertNull(state.lastTerminalResult)
    }

    @Test
    fun `default state has null lastGoal`() {
        assertNull(LocalLoopDebugState().lastGoal)
    }

    @Test
    fun `default state has zero traceCount`() {
        assertEquals(0, LocalLoopDebugState().traceCount)
    }

    @Test
    fun `default state has isRefreshing false`() {
        assertFalse(LocalLoopDebugState().isRefreshing)
    }

    @Test
    fun `default state has null diagnosticSnapshot`() {
        assertNull(LocalLoopDebugState().diagnosticSnapshot)
    }

    @Test
    fun `default state has empty debugFlagsEnabled`() {
        assertTrue(LocalLoopDebugState().debugFlagsEnabled.isEmpty())
    }

    // ── Copy semantics ────────────────────────────────────────────────────────

    @Test
    fun `copy with readinessSnapshot updates only that field`() {
        val readiness = LocalLoopReadiness(
            modelFilesReady = true,
            plannerLoaded = true,
            groundingLoaded = true,
            accessibilityReady = true,
            screenshotReady = true,
            actionExecutorReady = true
        )
        val state = LocalLoopDebugState().copy(readinessSnapshot = readiness)
        assertNotNull(state.readinessSnapshot)
        assertEquals(LocalLoopState.READY, state.readinessSnapshot!!.state)
        assertNull(state.configSnapshot)
    }

    @Test
    fun `copy with configSnapshot preserves readiness`() {
        val readiness = LocalLoopReadiness(
            modelFilesReady = false,
            plannerLoaded = false,
            groundingLoaded = false,
            accessibilityReady = false,
            screenshotReady = false,
            actionExecutorReady = false,
            blockers = listOf(LocalLoopFailureType.ACCESSIBILITY_SERVICE_DISABLED)
        )
        val config = LocalLoopConfig.defaults()
        val state = LocalLoopDebugState(readinessSnapshot = readiness).copy(configSnapshot = config)
        assertNotNull(state.readinessSnapshot)
        assertNotNull(state.configSnapshot)
        assertEquals(LocalLoopConfig.DEFAULT_MAX_STEPS, state.configSnapshot!!.maxSteps)
    }

    @Test
    fun `copy with isRefreshing true and then false`() {
        val s1 = LocalLoopDebugState().copy(isRefreshing = true)
        assertTrue(s1.isRefreshing)
        val s2 = s1.copy(isRefreshing = false)
        assertFalse(s2.isRefreshing)
    }

    // ── Trace and terminal result ─────────────────────────────────────────────

    @Test
    fun `copy with latestTrace preserves terminalResult`() {
        val trace = LocalLoopTrace(sessionId = "s1", originalGoal = "open settings")
        val terminal = TerminalResult(
            status = TerminalResult.STATUS_SUCCESS,
            stopReason = "task_complete",
            error = null,
            totalSteps = 3
        )
        trace.complete(terminal)
        val state = LocalLoopDebugState(latestTrace = trace, lastTerminalResult = terminal)
        assertEquals("s1", state.latestTrace!!.sessionId)
        assertEquals(TerminalResult.STATUS_SUCCESS, state.lastTerminalResult!!.status)
        assertEquals(3, state.lastTerminalResult!!.totalSteps)
    }

    @Test
    fun `copy clearing latestTrace also clears lastTerminalResult`() {
        val trace = LocalLoopTrace(sessionId = "s2", originalGoal = "tap button")
        val terminal = TerminalResult(
            status = TerminalResult.STATUS_FAILED,
            stopReason = "timeout",
            error = "step_timeout",
            totalSteps = 1
        )
        trace.complete(terminal)
        val state = LocalLoopDebugState(latestTrace = trace, lastTerminalResult = terminal)
            .copy(latestTrace = null, lastTerminalResult = null)
        assertNull(state.latestTrace)
        assertNull(state.lastTerminalResult)
    }

    // ── Config content ────────────────────────────────────────────────────────

    @Test
    fun `LocalLoopConfig defaults round-trips through state`() {
        val config = LocalLoopConfig(
            maxSteps = 5,
            maxRetriesPerStep = 1,
            stepTimeoutMs = 10_000L,
            goalTimeoutMs = 60_000L,
            fallback = FallbackConfig(enableRemoteHandoff = true)
        )
        val state = LocalLoopDebugState(configSnapshot = config)
        assertEquals(5, state.configSnapshot!!.maxSteps)
        assertEquals(1, state.configSnapshot!!.maxRetriesPerStep)
        assertEquals(10_000L, state.configSnapshot!!.stepTimeoutMs)
        assertEquals(60_000L, state.configSnapshot!!.goalTimeoutMs)
        assertTrue(state.configSnapshot!!.fallback.enableRemoteHandoff)
    }

    // ── Diagnostic snapshot ───────────────────────────────────────────────────

    @Test
    fun `copy with diagnosticSnapshot stores the text`() {
        val text = "=== snapshot ==="
        val state = LocalLoopDebugState().copy(diagnosticSnapshot = text)
        assertEquals(text, state.diagnosticSnapshot)
    }

    // ── traceCount ────────────────────────────────────────────────────────────

    @Test
    fun `traceCount reflects the value set via copy`() {
        val state = LocalLoopDebugState().copy(traceCount = 7)
        assertEquals(7, state.traceCount)
    }

    // ── lastGoal ──────────────────────────────────────────────────────────────

    @Test
    fun `lastGoal stores the goal string`() {
        val state = LocalLoopDebugState(lastGoal = "send email")
        assertEquals("send email", state.lastGoal)
    }

    @Test
    fun `copy clears lastGoal to null`() {
        val state = LocalLoopDebugState(lastGoal = "open browser").copy(lastGoal = null)
        assertNull(state.lastGoal)
    }
}

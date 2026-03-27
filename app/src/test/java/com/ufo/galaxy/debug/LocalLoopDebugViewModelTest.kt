package com.ufo.galaxy.debug

import com.ufo.galaxy.config.LocalLoopConfig
import com.ufo.galaxy.local.LocalLoopFailureType
import com.ufo.galaxy.local.LocalLoopReadiness
import com.ufo.galaxy.local.LocalLoopReadinessProvider
import com.ufo.galaxy.local.LocalLoopState
import com.ufo.galaxy.trace.LocalLoopTrace
import com.ufo.galaxy.trace.LocalLoopTraceStore
import com.ufo.galaxy.trace.TerminalResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [LocalLoopDebugViewModel].
 *
 * Verifies that developer actions (refresh, rerunLastGoal, clearTraceState,
 * forceReadinessRefresh, emitDiagnosticSnapshot) update [LocalLoopDebugState]
 * correctly.
 *
 * All tests run on the JVM using [runBlocking] and [Dispatchers.Unconfined] so that
 * launched coroutines execute synchronously inline with the test thread.
 */
class LocalLoopDebugViewModelTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun readyReadiness() = LocalLoopReadiness(
        modelFilesReady = true,
        plannerLoaded = true,
        groundingLoaded = true,
        accessibilityReady = true,
        screenshotReady = true,
        actionExecutorReady = true
    )

    private fun unavailableReadiness() = LocalLoopReadiness(
        modelFilesReady = false,
        plannerLoaded = false,
        groundingLoaded = false,
        accessibilityReady = false,
        screenshotReady = false,
        actionExecutorReady = false,
        blockers = listOf(LocalLoopFailureType.ACCESSIBILITY_SERVICE_DISABLED)
    )

    private fun fakeProvider(readiness: LocalLoopReadiness): LocalLoopReadinessProvider =
        object : LocalLoopReadinessProvider {
            override fun getReadiness(): LocalLoopReadiness = readiness
        }

    /** Builds a VM whose coroutines run on [Dispatchers.Unconfined] for synchronous test execution. */
    private fun viewModel(
        readiness: LocalLoopReadiness = readyReadiness(),
        store: LocalLoopTraceStore = LocalLoopTraceStore(),
        config: LocalLoopConfig? = null,
        lastGoal: String? = null,
        rerunAction: ((String) -> Unit)? = null
    ) = LocalLoopDebugViewModel(
        readinessProvider = fakeProvider(readiness),
        traceStore = store,
        configProvider = if (config != null) ({ config }) else null,
        coroutineScope = CoroutineScope(Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
        lastGoalProvider = if (lastGoal != null) ({ lastGoal }) else null,
        rerunGoalAction = rerunAction
    )

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    fun `refresh populates readinessSnapshot`() = runBlocking {
        val vm = viewModel(readiness = readyReadiness())
        vm.refresh()
        assertEquals(LocalLoopState.READY, vm.state.value.readinessSnapshot?.state)
    }

    @Test
    fun `refresh with unavailable readiness reflects UNAVAILABLE state`() = runBlocking {
        val vm = viewModel(readiness = unavailableReadiness())
        vm.refresh()
        assertEquals(LocalLoopState.UNAVAILABLE, vm.state.value.readinessSnapshot?.state)
    }

    @Test
    fun `refresh populates configSnapshot when configProvider is wired`() = runBlocking {
        val config = LocalLoopConfig(maxSteps = 7)
        val vm = viewModel(config = config)
        vm.refresh()
        assertEquals(7, vm.state.value.configSnapshot?.maxSteps)
    }

    @Test
    fun `refresh leaves configSnapshot null when configProvider is not wired`() = runBlocking {
        val vm = viewModel()   // no configProvider
        vm.refresh()
        assertNull(vm.state.value.configSnapshot)
    }

    @Test
    fun `refresh with empty trace store leaves latestTrace null`() = runBlocking {
        val vm = viewModel(store = LocalLoopTraceStore())
        vm.refresh()
        assertNull(vm.state.value.latestTrace)
        assertEquals(0, vm.state.value.traceCount)
    }

    @Test
    fun `refresh with one trace populates latestTrace and traceCount`() = runBlocking {
        val store = LocalLoopTraceStore()
        val trace = LocalLoopTrace(sessionId = "abc", originalGoal = "tap save")
        store.beginTrace(trace)
        val vm = viewModel(store = store)
        vm.refresh()
        assertNotNull(vm.state.value.latestTrace)
        assertEquals("abc", vm.state.value.latestTrace!!.sessionId)
        assertEquals(1, vm.state.value.traceCount)
    }

    @Test
    fun `refresh sets lastGoal from lastGoalProvider when available`() = runBlocking {
        val vm = viewModel(lastGoal = "open camera")
        vm.refresh()
        assertEquals("open camera", vm.state.value.lastGoal)
    }

    @Test
    fun `refresh falls back to trace originalGoal when lastGoalProvider is not wired`() = runBlocking {
        val store = LocalLoopTraceStore()
        val trace = LocalLoopTrace(sessionId = "t1", originalGoal = "go to settings")
        store.beginTrace(trace)
        val vm = viewModel(store = store)   // no lastGoalProvider
        vm.refresh()
        assertEquals("go to settings", vm.state.value.lastGoal)
    }

    @Test
    fun `refresh clears isRefreshing on completion`() = runBlocking {
        val vm = viewModel()
        vm.refresh()
        assertFalse(vm.state.value.isRefreshing)
    }

    // ── rerunLastGoal ─────────────────────────────────────────────────────────

    @Test
    fun `rerunLastGoal calls rerunGoalAction with lastGoal`() = runBlocking {
        var called: String? = null
        val vm = viewModel(lastGoal = "send file", rerunAction = { called = it })
        vm.refresh()
        vm.rerunLastGoal()
        assertEquals("send file", called)
    }

    @Test
    fun `rerunLastGoal is no-op when lastGoal is null`() = runBlocking {
        var called = false
        val vm = viewModel(lastGoal = null, rerunAction = { called = true })
        vm.refresh()
        vm.rerunLastGoal()
        assertFalse(called)
    }

    @Test
    fun `rerunLastGoal is no-op when rerunGoalAction is not wired`() = runBlocking {
        val vm = viewModel(lastGoal = "browse web", rerunAction = null)
        vm.refresh()
        vm.rerunLastGoal()  // must not throw
    }

    // ── clearTraceState ───────────────────────────────────────────────────────

    @Test
    fun `clearTraceState empties trace fields in state`() = runBlocking {
        val store = LocalLoopTraceStore()
        store.beginTrace(LocalLoopTrace(sessionId = "x", originalGoal = "goal"))
        val vm = viewModel(store = store)
        vm.refresh()
        assertNotNull(vm.state.value.latestTrace)

        vm.clearTraceState()

        assertNull(vm.state.value.latestTrace)
        assertNull(vm.state.value.lastTerminalResult)
        assertEquals(0, vm.state.value.traceCount)
    }

    @Test
    fun `clearTraceState also clears the underlying store`() = runBlocking {
        val store = LocalLoopTraceStore()
        store.beginTrace(LocalLoopTrace(sessionId = "y", originalGoal = "goal2"))
        val vm = viewModel(store = store)
        vm.refresh()

        vm.clearTraceState()

        assertEquals(0, store.size())
    }

    // ── forceReadinessRefresh ─────────────────────────────────────────────────

    @Test
    fun `forceReadinessRefresh updates readinessSnapshot`() = runBlocking {
        val vm = viewModel(readiness = unavailableReadiness())
        vm.refresh()
        assertEquals(LocalLoopState.UNAVAILABLE, vm.state.value.readinessSnapshot?.state)

        vm.forceReadinessRefresh()
        assertEquals(LocalLoopState.UNAVAILABLE, vm.state.value.readinessSnapshot?.state)
    }

    @Test
    fun `forceReadinessRefresh clears isRefreshing on completion`() = runBlocking {
        val vm = viewModel()
        vm.forceReadinessRefresh()
        assertFalse(vm.state.value.isRefreshing)
    }

    // ── emitDiagnosticSnapshot ────────────────────────────────────────────────

    @Test
    fun `emitDiagnosticSnapshot sets diagnosticSnapshot to non-null text`() = runBlocking {
        val vm = viewModel()
        vm.refresh()
        vm.emitDiagnosticSnapshot()
        assertNotNull(vm.state.value.diagnosticSnapshot)
    }

    @Test
    fun `emitDiagnosticSnapshot includes readiness state in text`() = runBlocking {
        val vm = viewModel(readiness = readyReadiness())
        vm.refresh()
        vm.emitDiagnosticSnapshot()
        val snapshot = vm.state.value.diagnosticSnapshot!!
        assertTrue(snapshot.contains("READY"))
    }

    @Test
    fun `emitDiagnosticSnapshot includes config when available`() = runBlocking {
        val vm = viewModel(config = LocalLoopConfig(maxSteps = 3))
        vm.refresh()
        vm.emitDiagnosticSnapshot()
        val snapshot = vm.state.value.diagnosticSnapshot!!
        assertTrue(snapshot.contains("maxSteps"))
        assertTrue(snapshot.contains("3"))
    }

    @Test
    fun `emitDiagnosticSnapshot before refresh still produces text`() = runBlocking {
        val vm = viewModel()
        vm.emitDiagnosticSnapshot()
        assertNotNull(vm.state.value.diagnosticSnapshot)
        assertTrue(vm.state.value.diagnosticSnapshot!!.contains("Local-Loop Debug Snapshot"))
    }

    @Test
    fun `emitDiagnosticSnapshot includes terminal result when trace is complete`() = runBlocking {
        val store = LocalLoopTraceStore()
        val trace = LocalLoopTrace(sessionId = "z", originalGoal = "submit form")
        trace.complete(
            TerminalResult(
                status = TerminalResult.STATUS_FAILED,
                stopReason = "timeout",
                error = "step_timeout",
                totalSteps = 2
            )
        )
        store.beginTrace(trace)

        val vm = viewModel(store = store)
        vm.refresh()
        vm.emitDiagnosticSnapshot()

        val snapshot = vm.state.value.diagnosticSnapshot!!
        assertTrue(snapshot.contains("failed"))
    }
}

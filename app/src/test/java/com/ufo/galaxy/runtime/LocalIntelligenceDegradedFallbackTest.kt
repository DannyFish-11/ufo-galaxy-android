package com.ufo.galaxy.runtime

import com.ufo.galaxy.inference.DegradedGroundingService
import com.ufo.galaxy.inference.DegradedPlannerService
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.inference.WarmupResult
import com.ufo.galaxy.model.ModelAssetManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests that [DegradedPlannerService] and [DegradedGroundingService] provide explicit,
 * structured degraded/fallback behaviour when the local inference runtime is absent,
 * failing, or in safe mode.
 *
 * Also verifies that [LocalIntelligenceCapabilityStatus] aligns correctly with
 * [LocalInferenceRuntimeManager.ManagerState] in every degraded/failed scenario,
 * and that the runtime manager produces the correct degraded state transitions.
 *
 * No real HTTP calls are made: stub services control warmup outcomes.
 */
class LocalIntelligenceDegradedFallbackTest {

    private lateinit var manager: LocalInferenceRuntimeManager
    private lateinit var planner: StubPlannerService
    private lateinit var grounding: StubGroundingService

    @Before
    fun setUp() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "degrade_test_${System.nanoTime()}")
        tmpDir.mkdirs()
        val assetManager = ModelAssetManager(tmpDir)
        planner = StubPlannerService()
        grounding = StubGroundingService()
        manager = LocalInferenceRuntimeManager(planner, grounding, assetManager)
    }

    // ── DegradedPlannerService ── basic contract ──────────────────────────────

    @Test
    fun `DegradedPlannerService plan returns DEGRADED prefix in error`() {
        val service = DegradedPlannerService(degradedReason = "runtime not started")
        val result = service.plan("open settings", emptyList())
        assertNotNull("plan must return a non-null error in degraded state", result.error)
        assertTrue(
            "error must start with DEGRADED:",
            result.error!!.startsWith("DEGRADED:")
        )
        assertTrue("steps must be empty in degraded state", result.steps.isEmpty())
    }

    @Test
    fun `DegradedPlannerService replan returns DEGRADED prefix in error`() {
        val service = DegradedPlannerService(degradedReason = "safe mode active")
        val failedStep = LocalPlannerService.PlanStep("tap", "the button")
        val result = service.replan("goal", emptyList(), failedStep, "previous error")
        assertNotNull(result.error)
        assertTrue("replan error must start with DEGRADED:", result.error!!.startsWith("DEGRADED:"))
        assertTrue(result.steps.isEmpty())
    }

    @Test
    fun `DegradedPlannerService isModelLoaded returns false`() {
        val service = DegradedPlannerService(degradedReason = "test")
        assertFalse(service.isModelLoaded())
    }

    @Test
    fun `DegradedPlannerService loadModel returns false`() {
        val service = DegradedPlannerService(degradedReason = "test")
        assertFalse(service.loadModel())
    }

    @Test
    fun `DegradedPlannerService warmupWithResult returns HEALTH_CHECK failure`() {
        val service = DegradedPlannerService(degradedReason = "runtime failed")
        val result = service.warmupWithResult()
        assertFalse(result.success)
        assertEquals(WarmupResult.WarmupStage.HEALTH_CHECK, result.stage)
        assertTrue(result.error!!.startsWith("DEGRADED:"))
    }

    // ── DegradedGroundingService ── basic contract ────────────────────────────

    @Test
    fun `DegradedGroundingService ground returns DEGRADED prefix in error`() {
        val service = DegradedGroundingService(degradedReason = "model missing")
        val result = service.ground("tap the button", "fakeBase64", 1080, 2340)
        assertNotNull(result.error)
        assertTrue("error must start with DEGRADED:", result.error!!.startsWith("DEGRADED:"))
        assertEquals(0, result.x)
        assertEquals(0, result.y)
        assertEquals(0f, result.confidence)
    }

    @Test
    fun `DegradedGroundingService warmupWithResult returns HEALTH_CHECK failure`() {
        val service = DegradedGroundingService(degradedReason = "safe mode")
        val result = service.warmupWithResult()
        assertFalse(result.success)
        assertEquals(WarmupResult.WarmupStage.HEALTH_CHECK, result.stage)
        assertTrue(result.error!!.startsWith("DEGRADED:"))
    }

    // ── DegradedPlannerService.forState factory ───────────────────────────────

    @Test
    fun `forState Stopped produces reason 'runtime not started'`() {
        val service = DegradedPlannerService.forState(LocalInferenceRuntimeManager.ManagerState.Stopped)
        assertTrue(service.degradedReason.contains("not started"))
    }

    @Test
    fun `forState Failed carries failure reason`() {
        val service = DegradedPlannerService.forState(
            LocalInferenceRuntimeManager.ManagerState.Failed("health endpoint unreachable")
        )
        assertTrue(service.degradedReason.contains("health endpoint unreachable"))
    }

    @Test
    fun `forState SafeMode produces reason containing safe mode`() {
        val service = DegradedPlannerService.forState(LocalInferenceRuntimeManager.ManagerState.SafeMode)
        assertTrue(service.degradedReason.contains("safe mode"))
    }

    @Test
    fun `forState Degraded carries degradation reason`() {
        val snapshot = RuntimeHealthSnapshot(
            plannerHealth = RuntimeHealthSnapshot.ComponentHealth.UNHEALTHY,
            groundingHealth = RuntimeHealthSnapshot.ComponentHealth.HEALTHY
        )
        val service = DegradedPlannerService.forState(
            LocalInferenceRuntimeManager.ManagerState.Degraded(snapshot, "planner failed")
        )
        assertTrue(service.degradedReason.contains("planner failed"))
    }

    @Test
    fun `DegradedGroundingService forState derives same reason as planner`() {
        val state = LocalInferenceRuntimeManager.ManagerState.Failed("disk full")
        val plannerService = DegradedPlannerService.forState(state)
        val groundingService = DegradedGroundingService.forState(state)
        assertEquals(plannerService.degradedReason, groundingService.degradedReason)
    }

    // ── LocalIntelligenceCapabilityStatus ── state alignment ─────────────────

    @Test
    fun `CapabilityStatus is ACTIVE when manager state is Running`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        val status = LocalIntelligenceCapabilityStatus.from(manager.state.value)
        assertEquals(LocalIntelligenceCapabilityStatus.ACTIVE, status)
    }

    @Test
    fun `CapabilityStatus is DEGRADED when manager state is Degraded`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = true
        manager.start()
        assertTrue("State must be Degraded", manager.state.value is LocalInferenceRuntimeManager.ManagerState.Degraded)
        val status = LocalIntelligenceCapabilityStatus.from(manager.state.value)
        assertEquals(LocalIntelligenceCapabilityStatus.DEGRADED, status)
    }

    @Test
    fun `CapabilityStatus is DISABLED when manager state is Failed`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()
        assertTrue("State must be Failed", manager.state.value is LocalInferenceRuntimeManager.ManagerState.Failed)
        val status = LocalIntelligenceCapabilityStatus.from(manager.state.value)
        assertEquals(LocalIntelligenceCapabilityStatus.DISABLED, status)
    }

    @Test
    fun `CapabilityStatus is DISABLED when manager state is Stopped`() {
        val status = LocalIntelligenceCapabilityStatus.from(manager.state.value)
        assertEquals(LocalIntelligenceCapabilityStatus.DISABLED, status)
    }

    @Test
    fun `CapabilityStatus is DISABLED when manager state is SafeMode`() {
        manager.enterSafeMode()
        val status = LocalIntelligenceCapabilityStatus.from(manager.state.value)
        assertEquals(LocalIntelligenceCapabilityStatus.DISABLED, status)
    }

    @Test
    fun `CapabilityStatus from RuntimeStartResult Success is ACTIVE`() {
        val status = LocalIntelligenceCapabilityStatus.from(RuntimeStartResult.Success)
        assertEquals(LocalIntelligenceCapabilityStatus.ACTIVE, status)
    }

    @Test
    fun `CapabilityStatus from RuntimeStartResult Degraded is DEGRADED`() {
        val status = LocalIntelligenceCapabilityStatus.from(
            RuntimeStartResult.Degraded("planner unavailable")
        )
        assertEquals(LocalIntelligenceCapabilityStatus.DEGRADED, status)
    }

    @Test
    fun `CapabilityStatus from RuntimeStartResult Failure is DISABLED`() {
        val status = LocalIntelligenceCapabilityStatus.from(
            RuntimeStartResult.Failure(RuntimeStartResult.StartStage.HEALTH_CHECK, "both down")
        )
        assertEquals(LocalIntelligenceCapabilityStatus.DISABLED, status)
    }

    // ── wire value round-trip ─────────────────────────────────────────────────

    @Test
    fun `ACTIVE wire value is 'active'`() {
        assertEquals("active", LocalIntelligenceCapabilityStatus.ACTIVE.wireValue)
    }

    @Test
    fun `DEGRADED wire value is 'degraded'`() {
        assertEquals("degraded", LocalIntelligenceCapabilityStatus.DEGRADED.wireValue)
    }

    @Test
    fun `DISABLED wire value is 'disabled'`() {
        assertEquals("disabled", LocalIntelligenceCapabilityStatus.DISABLED.wireValue)
    }

    @Test
    fun `fromWireValue round-trips all known values`() {
        for (status in LocalIntelligenceCapabilityStatus.entries) {
            assertEquals(status, LocalIntelligenceCapabilityStatus.fromWireValue(status.wireValue))
        }
    }

    @Test
    fun `fromWireValue unknown value returns DISABLED`() {
        assertEquals(
            LocalIntelligenceCapabilityStatus.DISABLED,
            LocalIntelligenceCapabilityStatus.fromWireValue("unknown_value")
        )
        assertEquals(
            LocalIntelligenceCapabilityStatus.DISABLED,
            LocalIntelligenceCapabilityStatus.fromWireValue(null)
        )
    }

    // ── capability honesty: DEGRADED prefix distinguishes from transient errors ──

    @Test
    fun `DegradedPlannerService error is distinguishable from transient inference error`() {
        val degraded = DegradedPlannerService(degradedReason = "model missing")
        val result = degraded.plan("goal", emptyList())
        // Error starts with DEGRADED: prefix
        assertTrue(result.error!!.startsWith("DEGRADED:"))
        // A transient inference error would NOT start with DEGRADED:
        val transientError = "HTTP 503 from MobileVLM endpoint"
        assertFalse("Transient error should not have DEGRADED prefix",
            transientError.startsWith("DEGRADED:"))
    }

    @Test
    fun `DegradedGroundingService error is distinguishable from transient inference error`() {
        val degraded = DegradedGroundingService(degradedReason = "safe mode")
        val result = degraded.ground("tap login", "base64data", 0, 0)
        assertTrue(result.error!!.startsWith("DEGRADED:"))
        val transientError = "SeeClick grounding timed out"
        assertFalse("Transient error should not have DEGRADED prefix",
            transientError.startsWith("DEGRADED:"))
    }

    // ── stub helpers ─────────────────────────────────────────────────────────

    private class StubPlannerService : LocalPlannerService {
        var warmupSucceeds = true
        private var loaded = false

        override fun loadModel(): Boolean { loaded = warmupSucceeds; return loaded }
        override fun unloadModel() { loaded = false }
        override fun isModelLoaded(): Boolean = loaded

        override fun warmupWithResult(): WarmupResult {
            loaded = warmupSucceeds
            return if (warmupSucceeds) WarmupResult.success()
            else WarmupResult.failure(WarmupResult.WarmupStage.HEALTH_CHECK, "stub planner failed")
        }

        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(emptyList(), "stub")

        override fun replan(
            goal: String, constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep, error: String, screenshotBase64: String?
        ) = LocalPlannerService.PlanResult(emptyList(), "stub")
    }

    private class StubGroundingService : LocalGroundingService {
        var warmupSucceeds = true
        private var loaded = false

        override fun loadModel(): Boolean { loaded = warmupSucceeds; return loaded }
        override fun unloadModel() { loaded = false }
        override fun isModelLoaded(): Boolean = loaded

        override fun warmupWithResult(): WarmupResult {
            loaded = warmupSucceeds
            return if (warmupSucceeds) WarmupResult.success()
            else WarmupResult.failure(WarmupResult.WarmupStage.HEALTH_CHECK, "stub grounding failed")
        }

        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(0, 0, 0f, "", "stub")
    }
}

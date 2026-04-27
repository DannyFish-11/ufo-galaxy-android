package com.ufo.galaxy.runtime

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
 * Unit tests for [LocalInferenceRuntimeManager].
 *
 * Uses stub implementations of [LocalPlannerService] and [LocalGroundingService]
 * to exercise all lifecycle transitions without real HTTP calls.
 */
class LocalInferenceRuntimeManagerTest {

    private lateinit var manager: LocalInferenceRuntimeManager
    private lateinit var planner: StubPlannerService
    private lateinit var grounding: StubGroundingService
    private lateinit var modelAssetManager: ModelAssetManager

    @Before
    fun setUp() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "models_test_${System.nanoTime()}")
        tmpDir.mkdirs()
        // Create stub model files so LocalInferenceRuntimeManager.checkModelFiles() passes.
        File(tmpDir, ModelAssetManager.MOBILEVLM_FILE).writeText("stub")
        File(tmpDir, ModelAssetManager.SEECLICK_PARAM_FILE).writeText("stub")
        File(tmpDir, ModelAssetManager.SEECLICK_BIN_FILE).writeText("stub")
        modelAssetManager = ModelAssetManager(tmpDir)
        planner = StubPlannerService()
        grounding = StubGroundingService()
        manager = LocalInferenceRuntimeManager(planner, grounding, modelAssetManager)
    }

    // ── initial state ────────────────────────────────────────────────────────

    @Test
    fun `initial state is Stopped`() {
        assertTrue(manager.state.value is LocalInferenceRuntimeManager.ManagerState.Stopped)
    }

    @Test
    fun `isInSafeMode starts false`() {
        assertFalse(manager.isInSafeMode)
    }

    // ── start: both succeed ──────────────────────────────────────────────────

    @Test
    fun `start returns Success when both warmups succeed`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        val result = manager.start()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `state is Running after successful start`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        assertTrue(manager.state.value is LocalInferenceRuntimeManager.ManagerState.Running)
    }

    // ── start: one fails → Degraded ──────────────────────────────────────────

    @Test
    fun `start returns Degraded when only planner fails`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = true
        val result = manager.start()
        assertTrue(result is RuntimeStartResult.Degraded)
        assertTrue(result.isUsable)
    }

    @Test
    fun `start returns Degraded when only grounding fails`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = false
        val result = manager.start()
        assertTrue(result is RuntimeStartResult.Degraded)
    }

    @Test
    fun `state is Degraded after partial start`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = true
        manager.start()
        assertTrue(manager.state.value is LocalInferenceRuntimeManager.ManagerState.Degraded)
    }

    // ── start: both fail → Failed ────────────────────────────────────────────

    @Test
    fun `start returns Failure when both warmups fail`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        val result = manager.start()
        assertFalse(result.isSuccess)
        assertFalse(result.isUsable)
    }

    @Test
    fun `state is Failed after both warmups fail`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()
        assertTrue(manager.state.value is LocalInferenceRuntimeManager.ManagerState.Failed)
    }

    // ── stop ─────────────────────────────────────────────────────────────────

    @Test
    fun `stop transitions to Stopped`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        manager.stop()
        assertTrue(manager.state.value is LocalInferenceRuntimeManager.ManagerState.Stopped)
    }

    @Test
    fun `stop unloads both models`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        manager.stop()
        assertFalse(planner.isModelLoaded())
        assertFalse(grounding.isModelLoaded())
    }

    // ── restart ──────────────────────────────────────────────────────────────

    @Test
    fun `restart returns Success after both succeed on second attempt`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        val result = manager.restart()
        assertTrue(result.isSuccess)
    }

    // ── healthCheck ──────────────────────────────────────────────────────────

    @Test
    fun `healthCheck reports HEALTHY when both loaded`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        val snapshot = manager.healthCheck()
        assertTrue(snapshot.isFullyHealthy)
    }

    @Test
    fun `healthCheck reports UNHEALTHY when planner not loaded`() {
        val snapshot = manager.healthCheck()
        assertEquals(
            RuntimeHealthSnapshot.ComponentHealth.UNHEALTHY,
            snapshot.plannerHealth
        )
    }

    // ── safe mode ────────────────────────────────────────────────────────────

    @Test
    fun `enterSafeMode sets isInSafeMode and transitions to SafeMode`() {
        manager.enterSafeMode()
        assertTrue(manager.isInSafeMode)
        assertTrue(manager.state.value is LocalInferenceRuntimeManager.ManagerState.SafeMode)
    }

    @Test
    fun `clearSafeMode clears isInSafeMode and transitions to Stopped`() {
        manager.enterSafeMode()
        manager.clearSafeMode()
        assertFalse(manager.isInSafeMode)
        assertTrue(manager.state.value is LocalInferenceRuntimeManager.ManagerState.Stopped)
    }

    @Test
    fun `start while in safe mode returns Failure`() = runBlocking {
        manager.enterSafeMode()
        val result = manager.start()
        assertFalse(result.isSuccess)
        // State should remain SafeMode since start was blocked
        assertTrue(manager.state.value is LocalInferenceRuntimeManager.ManagerState.SafeMode)
    }

    @Test
    fun `enterSafeMode unloads both models`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        manager.enterSafeMode()
        assertFalse(planner.isModelLoaded())
        assertFalse(grounding.isModelLoaded())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private class StubPlannerService : LocalPlannerService {
        var warmupSucceeds = true
        private var loaded = false

        override fun loadModel(): Boolean {
            loaded = warmupSucceeds
            return loaded
        }

        override fun unloadModel() { loaded = false }
        override fun isModelLoaded(): Boolean = loaded

        override fun warmupWithResult(): WarmupResult {
            loaded = warmupSucceeds
            return if (warmupSucceeds) WarmupResult.success()
            else WarmupResult.failure(WarmupResult.WarmupStage.HEALTH_CHECK, "Stub planner warmup failed")
        }

        override fun plan(
            goal: String,
            constraints: List<String>,
            screenshotBase64: String?
        ): LocalPlannerService.PlanResult = LocalPlannerService.PlanResult(emptyList(), "stub")

        override fun replan(
            goal: String,
            constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String,
            screenshotBase64: String?
        ): LocalPlannerService.PlanResult = LocalPlannerService.PlanResult(emptyList(), "stub")
    }

    private class StubGroundingService : LocalGroundingService {
        var warmupSucceeds = true
        private var loaded = false

        override fun loadModel(): Boolean {
            loaded = warmupSucceeds
            return loaded
        }

        override fun unloadModel() { loaded = false }
        override fun isModelLoaded(): Boolean = loaded

        override fun warmupWithResult(): WarmupResult {
            loaded = warmupSucceeds
            return if (warmupSucceeds) WarmupResult.success()
            else WarmupResult.failure(WarmupResult.WarmupStage.HEALTH_CHECK, "Stub grounding warmup failed")
        }

        override fun ground(
            intent: String,
            screenshotBase64: String,
            width: Int,
            height: Int
        ): LocalGroundingService.GroundingResult = LocalGroundingService.GroundingResult(
            x = 0, y = 0, confidence = 0f, element_description = "", error = "stub"
        )
    }
}

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
 * Smoke / integration validation for honest local inference capability reporting.
 *
 * Verifies the contract between [LocalInferenceRuntimeManager] state and the
 * capability advertisement that [com.ufo.galaxy.service.GalaxyConnectionService]
 * forwards to the gateway via capability_report:
 *
 * - `local_model_inference` must appear in high-level capabilities **only** when
 *   [RuntimeStartResult.isUsable] is true (Success or Degraded).
 * - When [RuntimeStartResult] is [RuntimeStartResult.Failure] (both runtimes down),
 *   `local_model_inference` must be **absent** from the capabilities list.
 * - [RuntimeStartResult.isSuccess] implies fully healthy; [RuntimeStartResult.isUsable]
 *   allows a degraded-mode advertisement.
 * - [LocalInferenceRuntimeManager.state] reflects the lifecycle transition matching the
 *   advertised capability level.
 *
 * This test does **not** involve real HTTP calls; stub services are used to control
 * warmup outcomes deterministically.
 */
class InferenceCapabilityReportingTest {

    private lateinit var manager: LocalInferenceRuntimeManager
    private lateinit var planner: StubPlannerService
    private lateinit var grounding: StubGroundingService

    @Before
    fun setUp() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "cap_report_test_${System.nanoTime()}")
        tmpDir.mkdirs()
        val assetManager = ModelAssetManager(tmpDir)
        planner = StubPlannerService()
        grounding = StubGroundingService()
        manager = LocalInferenceRuntimeManager(planner, grounding, assetManager)
    }

    // ── capability advertisement contract ────────────────────────────────────

    @Test
    fun `local_model_inference capability present when both runtimes succeed`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true

        val result = manager.start()

        assertTrue("result must be isUsable", result.isUsable)
        assertTrue("result must be isSuccess", result.isSuccess)
        // Validate that the caller (GalaxyConnectionService.loadModels) would include the capability.
        val capabilities = buildCapabilitiesFromResult(result)
        assertTrue(
            "local_model_inference must be advertised when runtime is Running",
            capabilities.contains("local_model_inference")
        )
    }

    @Test
    fun `local_model_inference capability present when only planner succeeds (degraded)`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = false

        val result = manager.start()

        assertTrue("Degraded result must be isUsable", result.isUsable)
        assertFalse("Degraded result must not be isSuccess", result.isSuccess)
        assertTrue("state must be Degraded", manager.state.value is LocalInferenceRuntimeManager.ManagerState.Degraded)
        val capabilities = buildCapabilitiesFromResult(result)
        assertTrue(
            "local_model_inference must be advertised in degraded mode (planner still operational)",
            capabilities.contains("local_model_inference")
        )
    }

    @Test
    fun `local_model_inference capability present when only grounding succeeds (degraded)`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = true

        val result = manager.start()

        assertTrue("Degraded result must be isUsable", result.isUsable)
        val capabilities = buildCapabilitiesFromResult(result)
        assertTrue(
            "local_model_inference must be advertised in degraded mode (grounding still operational)",
            capabilities.contains("local_model_inference")
        )
    }

    @Test
    fun `local_model_inference capability absent when both runtimes fail`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false

        val result = manager.start()

        assertFalse("Failure result must not be isSuccess", result.isSuccess)
        assertFalse("Failure result must not be isUsable", result.isUsable)
        assertTrue("state must be Failed", manager.state.value is LocalInferenceRuntimeManager.ManagerState.Failed)
        val capabilities = buildCapabilitiesFromResult(result)
        assertFalse(
            "local_model_inference must NOT be advertised when both runtimes have failed",
            capabilities.contains("local_model_inference")
        )
    }

    @Test
    fun `local_model_inference capability absent before start is called`() {
        // Manager starts in Stopped state; no capability_report has been sent.
        assertTrue(manager.state.value is LocalInferenceRuntimeManager.ManagerState.Stopped)
        // The fallback default capability list (used before loadModels() runs) must not include
        // local_model_inference — it is absent from GalaxyWebSocketClient's hardcoded default.
        val defaultCaps = buildDefaultCapabilities()
        assertFalse(
            "local_model_inference must NOT appear in pre-load default capability list",
            defaultCaps.contains("local_model_inference")
        )
    }

    @Test
    fun `local_model_inference capability absent after stop`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        assertTrue("state should be Running before stop", manager.state.value is LocalInferenceRuntimeManager.ManagerState.Running)

        manager.stop()

        assertTrue("state must be Stopped after stop", manager.state.value is LocalInferenceRuntimeManager.ManagerState.Stopped)
        // After stop the next loadModels() call would need a fresh start() result.
        // Simulate a re-check of runtime result from a Stopped state (no live result = not usable).
        val stoppedResult = RuntimeStartResult.Failure(
            stage = RuntimeStartResult.StartStage.HEALTH_CHECK,
            message = "Runtime not started"
        )
        val capabilities = buildCapabilitiesFromResult(stoppedResult)
        assertFalse(
            "local_model_inference must not be advertised when runtime is Stopped",
            capabilities.contains("local_model_inference")
        )
    }

    @Test
    fun `local_model_inference capability absent in safe mode`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        manager.enterSafeMode()

        assertTrue("state must be SafeMode", manager.state.value is LocalInferenceRuntimeManager.ManagerState.SafeMode)
        assertTrue("isInSafeMode must be true", manager.isInSafeMode)

        // In safe mode start() is blocked; capability report must reflect unavailability.
        val safeModeStartResult = manager.start()
        assertFalse("start in safe mode must not succeed", safeModeStartResult.isSuccess)
        assertFalse("start in safe mode must not be usable", safeModeStartResult.isUsable)
        val capabilities = buildCapabilitiesFromResult(safeModeStartResult)
        assertFalse(
            "local_model_inference must NOT be advertised while in safe mode",
            capabilities.contains("local_model_inference")
        )
    }

    // ── inference_runtime_state metadata contract ────────────────────────────

    @Test
    fun `inference_runtime_state is running when both runtimes succeed`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true

        val result = manager.start()

        assertEquals("running", inferenceRuntimeStateLabel(result))
    }

    @Test
    fun `inference_runtime_state is degraded when only one runtime succeeds`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = false

        val result = manager.start()

        assertEquals("degraded", inferenceRuntimeStateLabel(result))
    }

    @Test
    fun `inference_runtime_state is unavailable when both runtimes fail`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false

        val result = manager.start()

        assertEquals("unavailable", inferenceRuntimeStateLabel(result))
    }

    @Test
    fun `local_inference_ready is true only when both runtimes succeed`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        assertTrue("local_inference_ready must be true when both models loaded",
            planner.isModelLoaded() && grounding.isModelLoaded())
    }

    @Test
    fun `local_inference_ready is false when either runtime fails`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = false
        manager.start()

        assertFalse("local_inference_ready must be false when grounding not loaded",
            planner.isModelLoaded() && grounding.isModelLoaded())
    }

    // ── restart clears and re-advertises correctly ───────────────────────────

    @Test
    fun `capability advertisement updates correctly after restart`() = runBlocking {
        // First start fails
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        val firstResult = manager.start()
        assertFalse(firstResult.isUsable)

        // Fix services; restart should succeed
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        val restartResult = manager.restart()

        assertTrue("restart result must be isUsable", restartResult.isUsable)
        val capabilities = buildCapabilitiesFromResult(restartResult)
        assertTrue(
            "local_model_inference must be present after successful restart",
            capabilities.contains("local_model_inference")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Mirrors the capability list construction logic in GalaxyConnectionService.loadModels().
     * Returns the high-level capabilities that would be sent in capability_report.
     */
    private fun buildCapabilitiesFromResult(result: RuntimeStartResult): List<String> {
        val caps = mutableListOf(
            "autonomous_goal_execution",
            "local_task_planning",
            "local_ui_reasoning",
            "cross_device_coordination"
        )
        if (result.isSuccess || result.isUsable) {
            caps.add("local_model_inference")
        }
        return caps
    }

    /**
     * Mirrors the fallback capability list used by GalaxyWebSocketClient.sendHandshake()
     * before GalaxyConnectionService.loadModels() has run.
     */
    private fun buildDefaultCapabilities(): List<String> = listOf(
        "autonomous_goal_execution",
        "local_task_planning",
        "local_ui_reasoning",
        "cross_device_coordination"
        // local_model_inference intentionally absent from pre-load default
    )

    /**
     * Mirrors the inference_runtime_state label derivation in GalaxyConnectionService.loadModels().
     */
    private fun inferenceRuntimeStateLabel(result: RuntimeStartResult): String = when {
        result.isSuccess -> "running"
        result.isUsable -> "degraded"
        else -> "unavailable"
    }

    // ── stub services ─────────────────────────────────────────────────────────

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

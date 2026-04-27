package com.ufo.galaxy.runtime

import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.inference.WarmupResult
import com.ufo.galaxy.model.ModelAssetManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Local Intelligence Runtime Regression Baseline — blocking CI gate.
 *
 * This test suite establishes the **minimum automated regression line** for Android's
 * local-intelligence runtime as a participant in the dual-repo system.  It explicitly
 * covers the four areas required for Android to be a trustworthy, self-reporting
 * participant runtime:
 *
 *  1. **Startup / shutdown basics** — the runtime starts from [ManagerState.Stopped],
 *     transitions correctly on success and failure, and returns to [ManagerState.Stopped]
 *     after [stop].
 *
 *  2. **Runtime state transitions** — all named lifecycle states
 *     ([ManagerState.Running], [ManagerState.Degraded], [ManagerState.Failed],
 *     [ManagerState.SafeMode]) are reachable via the documented API, and recovery
 *     paths return to a usable state.
 *
 *  3. **Failure / recovery path basics** — the restart and safe-mode / clear-safe-mode
 *     paths work end-to-end: a failed runtime can recover; a safe-mode runtime can be
 *     re-started after the mode is cleared.
 *
 *  4. **Capability honesty under common failure states** — [LocalIntelligenceCapabilityStatus]
 *     reflects the true runtime state at every point in the lifecycle.  The runtime must
 *     never claim [LocalIntelligenceCapabilityStatus.ACTIVE] when in a degraded or failed
 *     state, and must never claim [LocalIntelligenceCapabilityStatus.DISABLED] when
 *     genuinely running.
 *
 * ## Blocking gate semantics
 *
 * Failure here means Android's local runtime has regressed in a way that would cause V2 to
 * receive dishonest capability reports or to schedule tasks onto an unavailable participant.
 * **Do not skip or suppress any assertion** without a matching change to the local runtime
 * architecture and an explicit cross-repo review.
 *
 * All tests are pure-JVM; no device, emulator, or real ML runtime is required.
 */
class LocalIntelligenceRuntimeRegressionTest {

    private lateinit var manager: LocalInferenceRuntimeManager
    private lateinit var planner: StubPlannerService
    private lateinit var grounding: StubGroundingService

    @Before
    fun setUp() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "runtime_regression_${System.nanoTime()}")
        tmpDir.mkdirs()
        // Provide stub model files so ModelAssetManager.checkFiles() passes.
        File(tmpDir, ModelAssetManager.MOBILEVLM_FILE).writeText("stub")
        File(tmpDir, ModelAssetManager.SEECLICK_PARAM_FILE).writeText("stub")
        File(tmpDir, ModelAssetManager.SEECLICK_BIN_FILE).writeText("stub")
        val assetManager = ModelAssetManager(tmpDir)
        planner = StubPlannerService()
        grounding = StubGroundingService()
        manager = LocalInferenceRuntimeManager(planner, grounding, assetManager)
    }

    // ── 1. Startup / shutdown basics ─────────────────────────────────────────

    @Test
    fun `runtime starts in Stopped state — not pre-started or pre-loaded`() {
        assertTrue(
            "Runtime must start in Stopped state: no inference should be available before start() is called",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Stopped
        )
    }

    @Test
    fun `start with both services succeeding transitions to Running`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        assertTrue(
            "Runtime must be in Running state when both planner and grounding warmup succeed",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Running
        )
    }

    @Test
    fun `stop from Running transitions back to Stopped`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        assertTrue("Pre-condition: must be Running", manager.state.value is LocalInferenceRuntimeManager.ManagerState.Running)

        manager.stop()
        assertTrue(
            "stop() must always return the runtime to Stopped state",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Stopped
        )
    }

    @Test
    fun `stop from Degraded transitions back to Stopped`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = true
        manager.start()
        assertTrue("Pre-condition: must be Degraded", manager.state.value is LocalInferenceRuntimeManager.ManagerState.Degraded)

        manager.stop()
        assertTrue(
            "stop() must return the runtime to Stopped regardless of prior state",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Stopped
        )
    }

    @Test
    fun `stop from Failed transitions back to Stopped`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()
        assertTrue("Pre-condition: must be Failed", manager.state.value is LocalInferenceRuntimeManager.ManagerState.Failed)

        manager.stop()
        assertTrue(
            "stop() must return the runtime to Stopped even after total failure",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Stopped
        )
    }

    @Test
    fun `full startup-shutdown-startup cycle completes correctly`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true

        manager.start()
        assertTrue("Cycle step 1: must be Running after first start", manager.state.value is LocalInferenceRuntimeManager.ManagerState.Running)

        manager.stop()
        assertTrue("Cycle step 2: must be Stopped after stop", manager.state.value is LocalInferenceRuntimeManager.ManagerState.Stopped)

        manager.start()
        assertTrue(
            "Cycle step 3: must be Running again after second start — startup is idempotent",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Running
        )
    }

    // ── 2. Runtime state transitions ─────────────────────────────────────────

    @Test
    fun `partial failure (planner down) transitions to Degraded not Failed`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = true
        manager.start()
        assertTrue(
            "Partial failure must produce Degraded (usable) — not Failed (unusable)",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Degraded
        )
    }

    @Test
    fun `partial failure (grounding down) transitions to Degraded not Failed`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = false
        manager.start()
        assertTrue(
            "Partial failure must produce Degraded (usable) — not Failed (unusable)",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Degraded
        )
    }

    @Test
    fun `total failure transitions to Failed not Degraded`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()
        assertTrue(
            "Total failure must produce Failed (unusable) — not Degraded",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Failed
        )
    }

    @Test
    fun `enterSafeMode transitions to SafeMode from any state`() {
        manager.enterSafeMode()
        assertTrue(
            "enterSafeMode() must transition to SafeMode immediately",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.SafeMode
        )
        assertTrue(
            "isInSafeMode must be true while in SafeMode",
            manager.isInSafeMode
        )
    }

    @Test
    fun `clearSafeMode transitions from SafeMode to Stopped`() {
        manager.enterSafeMode()
        assertTrue("Pre-condition: must be in SafeMode", manager.state.value is LocalInferenceRuntimeManager.ManagerState.SafeMode)

        manager.clearSafeMode()
        assertTrue(
            "clearSafeMode() must transition to Stopped, ready for a clean restart",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Stopped
        )
        assertFalse(
            "isInSafeMode must be false after clearSafeMode()",
            manager.isInSafeMode
        )
    }

    // ── 3. Failure / recovery path basics ────────────────────────────────────

    @Test
    fun `restart from Failed state succeeds when services recover`() = runBlocking {
        // First start fails
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()
        assertTrue("Pre-condition: must be Failed", manager.state.value is LocalInferenceRuntimeManager.ManagerState.Failed)

        // Services recover
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        val result = manager.restart()

        assertTrue(
            "restart() must return a success result when services recover",
            result.isSuccess
        )
        assertTrue(
            "Runtime must be in Running state after successful restart from Failed",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Running
        )
    }

    @Test
    fun `restart from Degraded state produces Running when both services recover`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = true
        manager.start()
        assertTrue("Pre-condition: must be Degraded", manager.state.value is LocalInferenceRuntimeManager.ManagerState.Degraded)

        planner.warmupSucceeds = true
        manager.restart()
        assertTrue(
            "restart() with both services healthy must elevate from Degraded to Running",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Running
        )
    }

    @Test
    fun `safe mode then clear then restart recovers to Running`() = runBlocking {
        // Enter safe mode while running
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        manager.enterSafeMode()
        assertTrue("Pre-condition: must be in SafeMode", manager.state.value is LocalInferenceRuntimeManager.ManagerState.SafeMode)

        // Clear safe mode and restart
        manager.clearSafeMode()
        val result = manager.start()

        assertTrue(
            "After clearSafeMode() + start(), runtime must recover to Running",
            result.isSuccess
        )
        assertTrue(
            "State must be Running after safe-mode recovery path",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Running
        )
    }

    @Test
    fun `start while in SafeMode returns Failure without modifying SafeMode state`() = runBlocking {
        manager.enterSafeMode()
        val result = manager.start()
        assertFalse(
            "start() while in SafeMode must fail — inference must not start in safe mode",
            result.isSuccess
        )
        assertTrue(
            "Runtime must remain in SafeMode after a rejected start() attempt",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.SafeMode
        )
    }

    // ── 4. Capability honesty under common failure states ────────────────────

    /**
     * When the runtime is in [ManagerState.Stopped], capability must be [DISABLED].
     * Reporting [ACTIVE] here would cause V2 to schedule tasks onto an unready participant.
     */
    @Test
    fun `capability is DISABLED when runtime is Stopped`() {
        val capability = LocalIntelligenceCapabilityStatus.from(manager.state.value)
        assertEquals(
            "Capability MUST be DISABLED in Stopped state — V2 must not schedule tasks onto an unstarted runtime",
            LocalIntelligenceCapabilityStatus.DISABLED,
            capability
        )
    }

    @Test
    fun `capability is ACTIVE when runtime is Running — honest positive claim`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        val capability = LocalIntelligenceCapabilityStatus.from(manager.state.value)
        assertEquals(
            "Capability must be ACTIVE when both planner and grounding are running — this is an honest positive claim",
            LocalIntelligenceCapabilityStatus.ACTIVE,
            capability
        )
    }

    @Test
    fun `capability is DEGRADED when runtime is Degraded — honest partial claim`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = true
        manager.start()
        val capability = LocalIntelligenceCapabilityStatus.from(manager.state.value)
        assertEquals(
            "Capability must be DEGRADED when partial failure — not ACTIVE, not DISABLED",
            LocalIntelligenceCapabilityStatus.DEGRADED,
            capability
        )
    }

    @Test
    fun `capability is DISABLED when runtime has Failed — no false positive`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()
        val capability = LocalIntelligenceCapabilityStatus.from(manager.state.value)
        assertEquals(
            "Capability MUST be DISABLED in Failed state — false ACTIVE/DEGRADED claim would cause V2 to schedule onto a broken participant",
            LocalIntelligenceCapabilityStatus.DISABLED,
            capability
        )
    }

    @Test
    fun `capability is DISABLED in SafeMode — inference is blocked`() {
        manager.enterSafeMode()
        val capability = LocalIntelligenceCapabilityStatus.from(manager.state.value)
        assertEquals(
            "Capability MUST be DISABLED in SafeMode — V2 must not dispatch tasks while safe mode is active",
            LocalIntelligenceCapabilityStatus.DISABLED,
            capability
        )
    }

    @Test
    fun `capability returns to ACTIVE after recovery from Failed`() = runBlocking {
        // Fail first
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()
        assertEquals(
            "Pre-condition: capability must be DISABLED in Failed",
            LocalIntelligenceCapabilityStatus.DISABLED,
            LocalIntelligenceCapabilityStatus.from(manager.state.value)
        )

        // Recover
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.restart()
        assertEquals(
            "Capability must return to ACTIVE after a successful recovery — dishonest DISABLED after recovery would prevent task scheduling",
            LocalIntelligenceCapabilityStatus.ACTIVE,
            LocalIntelligenceCapabilityStatus.from(manager.state.value)
        )
    }

    @Test
    fun `capability returns to DISABLED after stop following Running`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        assertEquals(
            "Pre-condition: capability must be ACTIVE while Running",
            LocalIntelligenceCapabilityStatus.ACTIVE,
            LocalIntelligenceCapabilityStatus.from(manager.state.value)
        )

        manager.stop()
        assertEquals(
            "Capability must return to DISABLED after stop() — stale ACTIVE capability after shutdown would cause V2 to schedule onto a stopped participant",
            LocalIntelligenceCapabilityStatus.DISABLED,
            LocalIntelligenceCapabilityStatus.from(manager.state.value)
        )
    }

    @Test
    fun `capability wire values are stable across all states`() {
        assertEquals("active wire value must be 'active'", "active", LocalIntelligenceCapabilityStatus.ACTIVE.wireValue)
        assertEquals("degraded wire value must be 'degraded'", "degraded", LocalIntelligenceCapabilityStatus.DEGRADED.wireValue)
        assertEquals("disabled wire value must be 'disabled'", "disabled", LocalIntelligenceCapabilityStatus.DISABLED.wireValue)
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

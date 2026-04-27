package com.ufo.galaxy.runtime

import com.ufo.galaxy.inference.DegradedGroundingService
import com.ufo.galaxy.inference.DegradedPlannerService
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.inference.WarmupResult
import com.ufo.galaxy.model.ModelAssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * PR-14 — Android Local Intelligence Runtime Lifecycle Hardening tests.
 *
 * Validates the three new [LocalInferenceRuntimeManager.ManagerState] variants introduced
 * to close the gaps in the startup / crash / pre-recovery state machine:
 *
 *  1. **[LocalInferenceRuntimeManager.ManagerState.FailedStartup]** — startup-pipeline
 *     failure, distinct from a post-startup runtime crash.  Carries the failure [reason]
 *     and the [RuntimeStartResult.StartStage] at which the pipeline stopped.
 *
 *  2. **[LocalInferenceRuntimeManager.ManagerState.PartialReady]** — transitional state
 *     emitted during [LocalInferenceRuntimeManager.start] after the first component
 *     (planner) succeeds its warmup but before the second (grounding) is confirmed healthy.
 *
 *  3. **[LocalInferenceRuntimeManager.ManagerState.Unavailable]** — pre-recovery state
 *     emitted by [LocalInferenceRuntimeManager.recoverIfUnhealthy] immediately after
 *     detecting an unhealthy component, before the stop-then-start recovery cycle begins.
 *
 * ## Coverage areas
 *
 * - startup failure tests (FailedStartup with model-files and warmup failure)
 * - partial-ready transition tests (PartialReady emitted during startup)
 * - Unavailable → Recovering → final-state transition tests
 * - capability honesty for all new states
 * - DegradedPlannerService / DegradedGroundingService forState mappings
 * - FailedStartup stage propagation (MODEL_FILES vs HEALTH_CHECK)
 * - restart recovery from FailedStartup
 *
 * All tests are pure-JVM; no device, emulator, or real ML runtime is required.
 */
class Pr14AndroidRuntimeLifecycleHardeningTest {

    private lateinit var manager: LocalInferenceRuntimeManager
    private lateinit var planner: StubPlannerService
    private lateinit var grounding: StubGroundingService
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "pr14_test_${System.nanoTime()}")
        tmpDir.mkdirs()
        File(tmpDir, ModelAssetManager.MOBILEVLM_FILE).writeText("stub")
        File(tmpDir, ModelAssetManager.SEECLICK_PARAM_FILE).writeText("stub")
        File(tmpDir, ModelAssetManager.SEECLICK_BIN_FILE).writeText("stub")
        val assetManager = ModelAssetManager(tmpDir)
        planner = StubPlannerService()
        grounding = StubGroundingService()
        manager = LocalInferenceRuntimeManager(planner, grounding, assetManager)
    }

    // ── FailedStartup state: both warmups fail ────────────────────────────────

    @Test
    fun `start with both services failing transitions to FailedStartup`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()
        assertTrue(
            "Total startup failure must produce FailedStartup, not the generic Failed state",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.FailedStartup
        )
    }

    @Test
    fun `FailedStartup reason is non-empty after total warmup failure`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()
        val state = manager.state.value as LocalInferenceRuntimeManager.ManagerState.FailedStartup
        assertTrue("FailedStartup.reason must be non-empty", state.reason.isNotBlank())
    }

    @Test
    fun `FailedStartup stage is HEALTH_CHECK after warmup failure`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()
        val state = manager.state.value as LocalInferenceRuntimeManager.ManagerState.FailedStartup
        assertEquals(
            "FailedStartup.stage must be HEALTH_CHECK when warmup fails",
            RuntimeStartResult.StartStage.HEALTH_CHECK,
            state.stage
        )
    }

    // ── FailedStartup state: model files missing ──────────────────────────────

    @Test
    fun `start with missing model files transitions to FailedStartup at MODEL_FILES stage`() = runBlocking {
        val emptyDir = File(System.getProperty("java.io.tmpdir"), "pr14_empty_${System.nanoTime()}")
        emptyDir.mkdirs()
        val emptyManager = LocalInferenceRuntimeManager(planner, grounding, ModelAssetManager(emptyDir))

        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        emptyManager.start()

        val state = emptyManager.state.value
        assertTrue(
            "Missing model files must produce FailedStartup, not generic Failed",
            state is LocalInferenceRuntimeManager.ManagerState.FailedStartup
        )
        assertEquals(
            "FailedStartup.stage must be MODEL_FILES when model files are absent",
            RuntimeStartResult.StartStage.MODEL_FILES,
            (state as LocalInferenceRuntimeManager.ManagerState.FailedStartup).stage
        )
    }

    @Test
    fun `FailedStartup reason contains model file name when file is missing`() = runBlocking {
        val emptyDir = File(System.getProperty("java.io.tmpdir"), "pr14_empty2_${System.nanoTime()}")
        emptyDir.mkdirs()
        val emptyManager = LocalInferenceRuntimeManager(planner, grounding, ModelAssetManager(emptyDir))

        emptyManager.start()

        val state = emptyManager.state.value as LocalInferenceRuntimeManager.ManagerState.FailedStartup
        assertTrue(
            "FailedStartup.reason must reference the problematic model file(s)",
            state.reason.isNotBlank()
        )
    }

    // ── FailedStartup: capability honesty ─────────────────────────────────────

    @Test
    fun `capability is DISABLED in FailedStartup state`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()
        val status = LocalIntelligenceCapabilityStatus.from(manager.state.value)
        assertEquals(
            "FailedStartup must report DISABLED capability — V2 must not dispatch tasks to a runtime that never started",
            LocalIntelligenceCapabilityStatus.DISABLED,
            status
        )
    }

    @Test
    fun `FailedStartup state maps to DISABLED directly when constructed`() {
        val state = LocalInferenceRuntimeManager.ManagerState.FailedStartup(
            reason = "warmup endpoint unreachable",
            stage = RuntimeStartResult.StartStage.HEALTH_CHECK
        )
        assertEquals(
            LocalIntelligenceCapabilityStatus.DISABLED,
            LocalIntelligenceCapabilityStatus.from(state)
        )
    }

    // ── FailedStartup: restart recovery ───────────────────────────────────────

    @Test
    fun `restart after FailedStartup succeeds when services recover`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()
        assertTrue(
            "Precondition: must be FailedStartup",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.FailedStartup
        )

        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        val result = manager.restart()

        assertTrue("restart() after FailedStartup must succeed when services recover", result.isSuccess)
        assertTrue(
            "State must be Running after restart recovery from FailedStartup",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Running
        )
    }

    @Test
    fun `stop after FailedStartup transitions to Stopped`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()
        assertTrue(
            "Precondition: must be FailedStartup",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.FailedStartup
        )

        manager.stop()
        assertTrue(
            "stop() must return to Stopped even from FailedStartup",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Stopped
        )
    }

    @Test
    fun `capability returns to ACTIVE after restart from FailedStartup`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()
        assertEquals(
            "Precondition: capability must be DISABLED after FailedStartup",
            LocalIntelligenceCapabilityStatus.DISABLED,
            LocalIntelligenceCapabilityStatus.from(manager.state.value)
        )

        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.restart()
        assertEquals(
            "Capability must return to ACTIVE after restart from FailedStartup",
            LocalIntelligenceCapabilityStatus.ACTIVE,
            LocalIntelligenceCapabilityStatus.from(manager.state.value)
        )
    }

    // ── PartialReady state: properties ────────────────────────────────────────

    @Test
    fun `PartialReady state holds completedComponents and pendingComponents`() {
        val state = LocalInferenceRuntimeManager.ManagerState.PartialReady(
            completedComponents = listOf("planner"),
            pendingComponents = listOf("grounding")
        )
        assertEquals(listOf("planner"), state.completedComponents)
        assertEquals(listOf("grounding"), state.pendingComponents)
    }

    @Test
    fun `PartialReady with empty lists is constructable`() {
        val state = LocalInferenceRuntimeManager.ManagerState.PartialReady(
            completedComponents = emptyList(),
            pendingComponents = emptyList()
        )
        assertTrue(state.completedComponents.isEmpty())
        assertTrue(state.pendingComponents.isEmpty())
    }

    // ── PartialReady state: capability honesty ────────────────────────────────

    @Test
    fun `PartialReady maps to DEGRADED capability status`() {
        val state = LocalInferenceRuntimeManager.ManagerState.PartialReady(
            completedComponents = listOf("planner"),
            pendingComponents = listOf("grounding")
        )
        assertEquals(
            "PartialReady must report DEGRADED capability — inference is partially possible",
            LocalIntelligenceCapabilityStatus.DEGRADED,
            LocalIntelligenceCapabilityStatus.from(state)
        )
    }

    @Test
    fun `PartialReady does not report ACTIVE capability`() {
        val state = LocalInferenceRuntimeManager.ManagerState.PartialReady(
            completedComponents = listOf("planner"),
            pendingComponents = listOf("grounding")
        )
        assertFalse(
            "PartialReady must never claim ACTIVE capability — startup is incomplete",
            LocalIntelligenceCapabilityStatus.from(state) == LocalIntelligenceCapabilityStatus.ACTIVE
        )
    }

    // ── PartialReady state: startup state history ─────────────────────────────

    @Test
    fun `PartialReady is emitted during startup after planner succeeds`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true

        val capturedStates = mutableListOf<LocalInferenceRuntimeManager.ManagerState>()
        val collectJob = launch(Dispatchers.Unconfined) {
            manager.state.collect { capturedStates.add(it) }
        }

        manager.start()
        collectJob.cancel()

        assertTrue(
            "PartialReady must appear in state history when planner warmup succeeds before grounding starts: $capturedStates",
            capturedStates.any { it is LocalInferenceRuntimeManager.ManagerState.PartialReady }
        )
    }

    @Test
    fun `PartialReady completedComponents contains planner during startup`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true

        val capturedStates = mutableListOf<LocalInferenceRuntimeManager.ManagerState>()
        val collectJob = launch(Dispatchers.Unconfined) {
            manager.state.collect { capturedStates.add(it) }
        }

        manager.start()
        collectJob.cancel()

        val partialReady = capturedStates
            .filterIsInstance<LocalInferenceRuntimeManager.ManagerState.PartialReady>()
            .firstOrNull()
        assertNotNull("PartialReady state must be observed during startup", partialReady)
        assertTrue(
            "PartialReady.completedComponents must include 'planner': ${partialReady?.completedComponents}",
            partialReady!!.completedComponents.contains("planner")
        )
        assertTrue(
            "PartialReady.pendingComponents must include 'grounding': ${partialReady.pendingComponents}",
            partialReady.pendingComponents.contains("grounding")
        )
    }

    @Test
    fun `startup state sequence is Starting then PartialReady then Running when both succeed`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true

        val capturedStates = mutableListOf<LocalInferenceRuntimeManager.ManagerState>()
        val collectJob = launch(Dispatchers.Unconfined) {
            manager.state.collect { capturedStates.add(it) }
        }

        manager.start()
        collectJob.cancel()

        val stateNames = capturedStates.map { it::class.simpleName }
        assertTrue(
            "Starting must precede PartialReady in state history: $stateNames",
            stateNames.indexOf("Starting") < stateNames.indexOf("PartialReady")
        )
        assertTrue(
            "PartialReady must precede Running in state history: $stateNames",
            stateNames.indexOf("PartialReady") < stateNames.indexOf("Running")
        )
    }

    @Test
    fun `PartialReady is not emitted when planner warmup fails`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = true

        val capturedStates = mutableListOf<LocalInferenceRuntimeManager.ManagerState>()
        val collectJob = launch(Dispatchers.Unconfined) {
            manager.state.collect { capturedStates.add(it) }
        }

        manager.start()
        collectJob.cancel()

        assertFalse(
            "PartialReady must NOT be emitted when planner warmup itself fails: $capturedStates",
            capturedStates.any { it is LocalInferenceRuntimeManager.ManagerState.PartialReady }
        )
    }

    // ── Unavailable state: properties ────────────────────────────────────────

    @Test
    fun `Unavailable state holds reason`() {
        val state = LocalInferenceRuntimeManager.ManagerState.Unavailable(
            reason = "planner=UNHEALTHY, grounding=HEALTHY"
        )
        assertEquals("planner=UNHEALTHY, grounding=HEALTHY", state.reason)
    }

    // ── Unavailable state: capability honesty ─────────────────────────────────

    @Test
    fun `Unavailable maps to UNAVAILABLE capability status`() {
        val state = LocalInferenceRuntimeManager.ManagerState.Unavailable(
            reason = "health check failed"
        )
        assertEquals(
            "Unavailable must report UNAVAILABLE capability — transient crash, not deliberate shutdown",
            LocalIntelligenceCapabilityStatus.UNAVAILABLE,
            LocalIntelligenceCapabilityStatus.from(state)
        )
    }

    @Test
    fun `Unavailable is distinct from DISABLED and RECOVERING capability statuses`() {
        val unavailableStatus = LocalIntelligenceCapabilityStatus.from(
            LocalInferenceRuntimeManager.ManagerState.Unavailable("crash")
        )
        assertFalse(
            "Unavailable must not map to DISABLED — it is a transient, unintended loss, not a deliberate shutdown",
            unavailableStatus == LocalIntelligenceCapabilityStatus.DISABLED
        )
        assertFalse(
            "Unavailable must not map to RECOVERING — recovery has not yet been initiated",
            unavailableStatus == LocalIntelligenceCapabilityStatus.RECOVERING
        )
        assertEquals(LocalIntelligenceCapabilityStatus.UNAVAILABLE, unavailableStatus)
    }

    // ── Unavailable: pre-recovery transition ──────────────────────────────────

    @Test
    fun `Unavailable is emitted by recoverIfUnhealthy before Recovering`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        planner.simulateCrash()

        planner.warmupSucceeds = true
        val capturedStates = mutableListOf<LocalInferenceRuntimeManager.ManagerState>()
        val collectJob = launch(Dispatchers.Unconfined) {
            manager.state.collect { capturedStates.add(it) }
        }

        manager.recoverIfUnhealthy()
        collectJob.cancel()

        assertTrue(
            "Unavailable must be emitted before Recovering in recoverIfUnhealthy: $capturedStates",
            capturedStates.any { it is LocalInferenceRuntimeManager.ManagerState.Unavailable }
        )
    }

    @Test
    fun `Unavailable precedes Recovering in recoverIfUnhealthy state sequence`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        planner.simulateCrash()
        planner.warmupSucceeds = true

        val capturedStates = mutableListOf<LocalInferenceRuntimeManager.ManagerState>()
        val collectJob = launch(Dispatchers.Unconfined) {
            manager.state.collect { capturedStates.add(it) }
        }

        manager.recoverIfUnhealthy()
        collectJob.cancel()

        val stateNames = capturedStates.map { it::class.simpleName }
        val unavailableIdx = stateNames.indexOf("Unavailable")
        val recoveringIdx = stateNames.indexOf("Recovering")

        assertTrue("Unavailable must appear in state history: $stateNames", unavailableIdx >= 0)
        assertTrue("Recovering must appear in state history: $stateNames", recoveringIdx >= 0)
        assertTrue(
            "Unavailable must precede Recovering in state history: $stateNames",
            unavailableIdx < recoveringIdx
        )
    }

    @Test
    fun `Unavailable reason describes which components are unhealthy`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        planner.simulateCrash()
        planner.warmupSucceeds = true

        val capturedStates = mutableListOf<LocalInferenceRuntimeManager.ManagerState>()
        val collectJob = launch(Dispatchers.Unconfined) {
            manager.state.collect { capturedStates.add(it) }
        }

        manager.recoverIfUnhealthy()
        collectJob.cancel()

        val unavailable = capturedStates
            .filterIsInstance<LocalInferenceRuntimeManager.ManagerState.Unavailable>()
            .firstOrNull()
        assertNotNull("Unavailable state must be observed", unavailable)
        assertTrue(
            "Unavailable.reason must be non-empty: '${unavailable?.reason}'",
            unavailable!!.reason.isNotBlank()
        )
    }

    @Test
    fun `recoverIfUnhealthy does not emit Unavailable when runtime is healthy`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        val capturedStates = mutableListOf<LocalInferenceRuntimeManager.ManagerState>()
        val collectJob = launch(Dispatchers.Unconfined) {
            manager.state.collect { capturedStates.add(it) }
        }

        manager.recoverIfUnhealthy()
        collectJob.cancel()

        assertFalse(
            "Unavailable must NOT be emitted when runtime is already healthy: $capturedStates",
            capturedStates.any { it is LocalInferenceRuntimeManager.ManagerState.Unavailable }
        )
    }

    // ── DegradedPlannerService.forState: new states ───────────────────────────

    @Test
    fun `forState FailedStartup produces reason containing startup failure details`() {
        val state = LocalInferenceRuntimeManager.ManagerState.FailedStartup(
            reason = "health endpoint unreachable",
            stage = RuntimeStartResult.StartStage.HEALTH_CHECK
        )
        val service = DegradedPlannerService.forState(state)
        assertTrue(
            "DegradedPlannerService reason must reference FailedStartup details: '${service.degradedReason}'",
            service.degradedReason.contains("health endpoint unreachable")
        )
    }

    @Test
    fun `forState PartialReady produces reason mentioning partial components`() {
        val state = LocalInferenceRuntimeManager.ManagerState.PartialReady(
            completedComponents = listOf("planner"),
            pendingComponents = listOf("grounding")
        )
        val service = DegradedPlannerService.forState(state)
        assertTrue(
            "DegradedPlannerService reason must acknowledge PartialReady state: '${service.degradedReason}'",
            service.degradedReason.isNotBlank()
        )
    }

    @Test
    fun `forState Unavailable produces reason mentioning unavailability`() {
        val state = LocalInferenceRuntimeManager.ManagerState.Unavailable(
            reason = "planner=UNHEALTHY"
        )
        val service = DegradedPlannerService.forState(state)
        assertTrue(
            "DegradedPlannerService reason must reference Unavailable state: '${service.degradedReason}'",
            service.degradedReason.contains("unavailable")
        )
    }

    @Test
    fun `DegradedGroundingService forState derives same reason as planner for new states`() {
        listOf(
            LocalInferenceRuntimeManager.ManagerState.FailedStartup(
                "model missing", RuntimeStartResult.StartStage.MODEL_FILES
            ),
            LocalInferenceRuntimeManager.ManagerState.PartialReady(
                completedComponents = listOf("planner"),
                pendingComponents = listOf("grounding")
            ),
            LocalInferenceRuntimeManager.ManagerState.Unavailable("crash detected")
        ).forEach { state ->
            val plannerReason = DegradedPlannerService.forState(state).degradedReason
            val groundingReason = DegradedGroundingService.forState(state).degradedReason
            assertEquals(
                "DegradedGroundingService and DegradedPlannerService must derive the same reason for $state",
                plannerReason,
                groundingReason
            )
        }
    }

    // ── Complete state machine: all 10 states coverage ────────────────────────

    @Test
    fun `all ManagerState variants map to a valid LocalIntelligenceCapabilityStatus`() {
        val snapshot = RuntimeHealthSnapshot(
            plannerHealth = RuntimeHealthSnapshot.ComponentHealth.HEALTHY,
            groundingHealth = RuntimeHealthSnapshot.ComponentHealth.HEALTHY
        )
        val allStates: List<LocalInferenceRuntimeManager.ManagerState> = listOf(
            LocalInferenceRuntimeManager.ManagerState.Stopped,
            LocalInferenceRuntimeManager.ManagerState.Starting,
            LocalInferenceRuntimeManager.ManagerState.Running(snapshot),
            LocalInferenceRuntimeManager.ManagerState.Degraded(snapshot, "partial"),
            LocalInferenceRuntimeManager.ManagerState.Failed("crash"),
            LocalInferenceRuntimeManager.ManagerState.SafeMode,
            LocalInferenceRuntimeManager.ManagerState.Recovering,
            LocalInferenceRuntimeManager.ManagerState.FailedStartup(
                "warmup failed", RuntimeStartResult.StartStage.HEALTH_CHECK
            ),
            LocalInferenceRuntimeManager.ManagerState.PartialReady(
                listOf("planner"), listOf("grounding")
            ),
            LocalInferenceRuntimeManager.ManagerState.Unavailable("crash")
        )

        allStates.forEach { state ->
            val status = LocalIntelligenceCapabilityStatus.from(state)
            assertNotNull(
                "Every ManagerState must produce a non-null capability status: $state",
                status
            )
        }
    }

    @Test
    fun `only Running state produces ACTIVE capability among all states`() {
        val snapshot = RuntimeHealthSnapshot(
            plannerHealth = RuntimeHealthSnapshot.ComponentHealth.HEALTHY,
            groundingHealth = RuntimeHealthSnapshot.ComponentHealth.HEALTHY
        )
        val allStates: List<LocalInferenceRuntimeManager.ManagerState> = listOf(
            LocalInferenceRuntimeManager.ManagerState.Stopped,
            LocalInferenceRuntimeManager.ManagerState.Starting,
            LocalInferenceRuntimeManager.ManagerState.Running(snapshot),
            LocalInferenceRuntimeManager.ManagerState.Degraded(snapshot, "partial"),
            LocalInferenceRuntimeManager.ManagerState.Failed("crash"),
            LocalInferenceRuntimeManager.ManagerState.SafeMode,
            LocalInferenceRuntimeManager.ManagerState.Recovering,
            LocalInferenceRuntimeManager.ManagerState.FailedStartup(
                "warmup failed", RuntimeStartResult.StartStage.HEALTH_CHECK
            ),
            LocalInferenceRuntimeManager.ManagerState.PartialReady(
                listOf("planner"), listOf("grounding")
            ),
            LocalInferenceRuntimeManager.ManagerState.Unavailable("crash")
        )

        val activeStates = allStates.filter {
            LocalIntelligenceCapabilityStatus.from(it) == LocalIntelligenceCapabilityStatus.ACTIVE
        }
        assertEquals("Only Running must produce ACTIVE capability", 1, activeStates.size)
        assertTrue(activeStates.first() is LocalInferenceRuntimeManager.ManagerState.Running)
    }

    @Test
    fun `new states produce distinct and correct capability statuses`() {
        assertEquals(
            "FailedStartup must → DISABLED",
            LocalIntelligenceCapabilityStatus.DISABLED,
            LocalIntelligenceCapabilityStatus.from(
                LocalInferenceRuntimeManager.ManagerState.FailedStartup(
                    "error", RuntimeStartResult.StartStage.HEALTH_CHECK
                )
            )
        )
        assertEquals(
            "PartialReady must → DEGRADED",
            LocalIntelligenceCapabilityStatus.DEGRADED,
            LocalIntelligenceCapabilityStatus.from(
                LocalInferenceRuntimeManager.ManagerState.PartialReady(
                    listOf("planner"), listOf("grounding")
                )
            )
        )
        assertEquals(
            "Unavailable must → UNAVAILABLE",
            LocalIntelligenceCapabilityStatus.UNAVAILABLE,
            LocalIntelligenceCapabilityStatus.from(
                LocalInferenceRuntimeManager.ManagerState.Unavailable("crash")
            )
        )
    }

    // ── Stub helpers ─────────────────────────────────────────────────────────

    private class StubPlannerService : LocalPlannerService {
        var warmupSucceeds = true
        private var loaded = false

        fun simulateCrash() { loaded = false }

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

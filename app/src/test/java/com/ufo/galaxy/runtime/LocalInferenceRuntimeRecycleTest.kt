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
 * Tests for the unhealthy-recycle / recovery lifecycle of [LocalInferenceRuntimeManager].
 *
 * Covers:
 * - [LocalInferenceRuntimeManager.recoverIfUnhealthy]: no-op when healthy, restart when unhealthy.
 * - State transitions through [LocalInferenceRuntimeManager.ManagerState.Recovering].
 * - [LocalIntelligenceCapabilityStatus.RECOVERING] and [LocalIntelligenceCapabilityStatus.UNAVAILABLE]
 *   wire semantics.
 * - Capability honesty after a mid-run crash (model becomes unloaded while in Running state).
 * - Model provisioning failure: start blocked when required model files are absent.
 * - Degraded-then-recovered path: partial runtime failure followed by successful recovery.
 *
 * All tests use stub services and a temp directory; no real HTTP calls are made.
 */
class LocalInferenceRuntimeRecycleTest {

    private lateinit var manager: LocalInferenceRuntimeManager
    private lateinit var planner: CrashableStubPlannerService
    private lateinit var grounding: CrashableStubGroundingService
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "recycle_test_${System.nanoTime()}")
        tmpDir.mkdirs()
        // Stub model files so checkModelFiles() passes.
        File(tmpDir, ModelAssetManager.MOBILEVLM_FILE).writeText("stub")
        File(tmpDir, ModelAssetManager.SEECLICK_PARAM_FILE).writeText("stub")
        File(tmpDir, ModelAssetManager.SEECLICK_BIN_FILE).writeText("stub")

        val assetManager = ModelAssetManager(tmpDir)
        planner = CrashableStubPlannerService()
        grounding = CrashableStubGroundingService()
        manager = LocalInferenceRuntimeManager(planner, grounding, assetManager)
    }

    // ── recoverIfUnhealthy: healthy runtime ───────────────────────────────────

    @Test
    fun `recoverIfUnhealthy returns Success immediately when runtime is fully healthy`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        assertTrue("Precondition: state must be Running before recovery check",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Running)

        val result = manager.recoverIfUnhealthy()

        assertTrue("recoverIfUnhealthy must return Success when runtime is healthy", result.isSuccess)
        assertTrue("State must remain Running after no-op recovery",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Running)
    }

    @Test
    fun `recoverIfUnhealthy does not transition to Recovering when runtime is healthy`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        val statesBefore = manager.state.value
        manager.recoverIfUnhealthy()

        // State must not have regressed from Running
        assertTrue("State must remain Running after no-op recoverIfUnhealthy",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Running)
        assertTrue("State before and after no-op recovery must both be Running",
            statesBefore is LocalInferenceRuntimeManager.ManagerState.Running)
    }

    // ── recoverIfUnhealthy: crashed planner ───────────────────────────────────

    @Test
    fun `recoverIfUnhealthy restarts successfully after planner crash`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        // Simulate planner crash: model is unloaded externally without state update
        planner.simulateCrash()
        assertFalse("Precondition: planner must report unloaded after crash",
            planner.isModelLoaded())

        // Recovery should restart the planner
        planner.warmupSucceeds = true
        val result = manager.recoverIfUnhealthy()

        assertTrue("recoverIfUnhealthy must return usable result after planner restart",
            result.isUsable)
    }

    @Test
    fun `recoverIfUnhealthy restarts successfully after grounding crash`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        // Simulate grounding crash
        grounding.simulateCrash()
        assertFalse("Precondition: grounding must report unloaded after crash",
            grounding.isModelLoaded())

        grounding.warmupSucceeds = true
        val result = manager.recoverIfUnhealthy()

        assertTrue("recoverIfUnhealthy must return usable result after grounding restart",
            result.isUsable)
    }

    @Test
    fun `recoverIfUnhealthy returns Running after both services recover`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        // Crash both
        planner.simulateCrash()
        grounding.simulateCrash()

        // Allow both to recover
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        val result = manager.recoverIfUnhealthy()

        assertTrue("Full recovery must produce Success result", result.isSuccess)
        assertTrue("State must be Running after full recovery",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Running)
    }

    @Test
    fun `recoverIfUnhealthy returns Degraded when recovery restores only one service`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        // Crash both
        planner.simulateCrash()
        grounding.simulateCrash()

        // Only planner recovers
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = false
        val result = manager.recoverIfUnhealthy()

        assertTrue("Partial recovery must produce Degraded result",
            result is RuntimeStartResult.Degraded)
        assertTrue("State must be Degraded after partial recovery",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Degraded)
    }

    @Test
    fun `recoverIfUnhealthy returns Failure when recovery fails for both services`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        // Crash both, do not allow recovery
        planner.simulateCrash()
        grounding.simulateCrash()
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false

        val result = manager.recoverIfUnhealthy()

        assertFalse("Failed recovery must not return isSuccess", result.isSuccess)
        assertFalse("Failed recovery must not return isUsable", result.isUsable)
        assertTrue("State must be FailedStartup after unsuccessful recovery",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.FailedStartup)
    }

    // ── recoverIfUnhealthy: safe-mode interaction ─────────────────────────────

    @Test
    fun `recoverIfUnhealthy blocked while in safe mode`() = runBlocking {
        manager.enterSafeMode()
        assertTrue("Precondition: must be in safe mode", manager.isInSafeMode)

        val result = manager.recoverIfUnhealthy()

        assertFalse("Recovery must be blocked in safe mode", result.isSuccess)
        assertFalse("Recovery must not be usable in safe mode", result.isUsable)
        assertTrue("State must remain SafeMode after blocked recovery",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.SafeMode)
    }

    // ── Recovering state: ManagerState and CapabilityStatus ──────────────────

    @Test
    fun `CapabilityStatus RECOVERING maps from Recovering state`() {
        val status = LocalIntelligenceCapabilityStatus.from(
            LocalInferenceRuntimeManager.ManagerState.Recovering
        )
        assertEquals(
            "ManagerState.Recovering must produce RECOVERING capability status",
            LocalIntelligenceCapabilityStatus.RECOVERING,
            status
        )
    }

    @Test
    fun `RECOVERING wire value is recovering`() {
        assertEquals("recovering", LocalIntelligenceCapabilityStatus.RECOVERING.wireValue)
    }

    @Test
    fun `UNAVAILABLE wire value is unavailable`() {
        assertEquals("unavailable", LocalIntelligenceCapabilityStatus.UNAVAILABLE.wireValue)
    }

    @Test
    fun `fromWireValue recovers RECOVERING and UNAVAILABLE correctly`() {
        assertEquals(
            LocalIntelligenceCapabilityStatus.RECOVERING,
            LocalIntelligenceCapabilityStatus.fromWireValue("recovering")
        )
        assertEquals(
            LocalIntelligenceCapabilityStatus.UNAVAILABLE,
            LocalIntelligenceCapabilityStatus.fromWireValue("unavailable")
        )
    }

    @Test
    fun `fromWireValue round-trips all five statuses`() {
        for (status in LocalIntelligenceCapabilityStatus.entries) {
            assertEquals(
                "fromWireValue must round-trip ${status.wireValue}",
                status,
                LocalIntelligenceCapabilityStatus.fromWireValue(status.wireValue)
            )
        }
    }

    // ── Capability honesty under mid-run crash ────────────────────────────────

    @Test
    fun `healthCheck reports UNHEALTHY after planner crash`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        assertTrue("Precondition: healthCheck must be fully healthy after start",
            manager.healthCheck().isFullyHealthy)

        planner.simulateCrash()

        val snapshot = manager.healthCheck()
        assertEquals(
            "plannerHealth must be UNHEALTHY after crash",
            RuntimeHealthSnapshot.ComponentHealth.UNHEALTHY,
            snapshot.plannerHealth
        )
        assertEquals(
            "groundingHealth must remain HEALTHY",
            RuntimeHealthSnapshot.ComponentHealth.HEALTHY,
            snapshot.groundingHealth
        )
        assertFalse("Snapshot must not be fully healthy after crash", snapshot.isFullyHealthy)
    }

    @Test
    fun `healthCheck reports UNHEALTHY after grounding crash`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        grounding.simulateCrash()

        val snapshot = manager.healthCheck()
        assertEquals(
            "groundingHealth must be UNHEALTHY after crash",
            RuntimeHealthSnapshot.ComponentHealth.UNHEALTHY,
            snapshot.groundingHealth
        )
        assertFalse("Snapshot must not be fully healthy after crash", snapshot.isFullyHealthy)
    }

    @Test
    fun `capability status reflects ACTIVE after recovery from crash`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        assertEquals(
            "Precondition: ACTIVE before crash",
            LocalIntelligenceCapabilityStatus.ACTIVE,
            LocalIntelligenceCapabilityStatus.from(manager.state.value)
        )

        // Crash, then recover
        planner.simulateCrash()
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.recoverIfUnhealthy()

        assertEquals(
            "Capability status must be ACTIVE after successful recovery",
            LocalIntelligenceCapabilityStatus.ACTIVE,
            LocalIntelligenceCapabilityStatus.from(manager.state.value)
        )
    }

    @Test
    fun `capability status reflects DEGRADED after partial recovery`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        // Crash both; only planner recovers
        planner.simulateCrash()
        grounding.simulateCrash()
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = false
        manager.recoverIfUnhealthy()

        assertEquals(
            "Capability status must be DEGRADED after partial recovery",
            LocalIntelligenceCapabilityStatus.DEGRADED,
            LocalIntelligenceCapabilityStatus.from(manager.state.value)
        )
    }

    @Test
    fun `capability status reflects DISABLED after failed recovery`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        // Crash both; neither recovers
        planner.simulateCrash()
        grounding.simulateCrash()
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.recoverIfUnhealthy()

        assertEquals(
            "Capability status must be DISABLED after failed recovery",
            LocalIntelligenceCapabilityStatus.DISABLED,
            LocalIntelligenceCapabilityStatus.from(manager.state.value)
        )
    }

    // ── Model provisioning failure: start blocked when files absent ───────────

    @Test
    fun `start returns Failure at MODEL_FILES stage when model files are missing`() = runBlocking {
        // Fresh manager with empty models dir (no stub files)
        val emptyDir = File(System.getProperty("java.io.tmpdir"), "empty_models_${System.nanoTime()}")
        emptyDir.mkdirs()
        val emptyAssetManager = ModelAssetManager(emptyDir)
        val bareManager = LocalInferenceRuntimeManager(planner, grounding, emptyAssetManager)

        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true

        val result = bareManager.start()

        assertFalse("Start must fail when model files are absent", result.isSuccess)
        assertFalse("Start must not be usable when model files are absent", result.isUsable)
        assertTrue("Failure must be at MODEL_FILES stage",
            result is RuntimeStartResult.Failure &&
                result.stage == RuntimeStartResult.StartStage.MODEL_FILES)
        assertTrue("State must be FailedStartup when model files are missing",
            bareManager.state.value is LocalInferenceRuntimeManager.ManagerState.FailedStartup)
    }

    @Test
    fun `start returns Failure when only MobileVLM file is missing`() = runBlocking {
        // Only SeeClick files present — MobileVLM missing
        val partialDir = File(System.getProperty("java.io.tmpdir"), "partial_models_${System.nanoTime()}")
        partialDir.mkdirs()
        File(partialDir, ModelAssetManager.SEECLICK_PARAM_FILE).writeText("stub")
        File(partialDir, ModelAssetManager.SEECLICK_BIN_FILE).writeText("stub")
        val assetManager = ModelAssetManager(partialDir)
        val partialManager = LocalInferenceRuntimeManager(planner, grounding, assetManager)

        val result = partialManager.start()

        assertFalse("Start must fail when MobileVLM file is absent", result.isSuccess)
        assertTrue("Failure must be at MODEL_FILES stage",
            result is RuntimeStartResult.Failure &&
                result.stage == RuntimeStartResult.StartStage.MODEL_FILES)
    }

    @Test
    fun `start returns Failure when SeeClick bin file is missing`() = runBlocking {
        // MobileVLM + param present, but bin missing
        val partialDir = File(System.getProperty("java.io.tmpdir"), "partial_models2_${System.nanoTime()}")
        partialDir.mkdirs()
        File(partialDir, ModelAssetManager.MOBILEVLM_FILE).writeText("stub")
        File(partialDir, ModelAssetManager.SEECLICK_PARAM_FILE).writeText("stub")
        // SEECLICK_BIN_FILE intentionally absent
        val assetManager = ModelAssetManager(partialDir)
        val partialManager = LocalInferenceRuntimeManager(planner, grounding, assetManager)

        val result = partialManager.start()

        assertFalse("Start must fail when SeeClick bin file is absent", result.isSuccess)
        assertTrue("Failure must be at MODEL_FILES stage",
            result is RuntimeStartResult.Failure &&
                result.stage == RuntimeStartResult.StartStage.MODEL_FILES)
    }

    // ── recoverIfUnhealthy: model files absent during recovery ────────────────

    @Test
    fun `recoverIfUnhealthy returns MODEL_FILES failure if files disappear between start and recovery`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()
        assertTrue("Precondition: Running state before file removal",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Running)

        // Delete model files to simulate storage failure
        File(tmpDir, ModelAssetManager.MOBILEVLM_FILE).delete()

        // Crash the planner to force a recovery attempt
        planner.simulateCrash()
        planner.warmupSucceeds = true

        val result = manager.recoverIfUnhealthy()

        assertFalse("Recovery must fail when model files are missing", result.isSuccess)
        assertFalse("Recovery must not be usable when model files are missing", result.isUsable)
    }

    // ── cleanupStaleFiles ─────────────────────────────────────────────────────

    @Test
    fun `cleanupStaleFiles removes tmp files`() {
        val tmpFile = File(tmpDir, "downloading.tmp")
        tmpFile.writeText("partial data")
        assertTrue("Precondition: .tmp file must exist", tmpFile.exists())

        val mam = ModelAssetManager(tmpDir)
        val deleted = mam.cleanupStaleFiles()

        assertTrue("At least one file must have been deleted", deleted >= 1)
        assertFalse(".tmp file must be removed after cleanup", tmpFile.exists())
    }

    @Test
    fun `cleanupStaleFiles removes unrecognised files`() {
        val staleFile = File(tmpDir, "old_model_weights.bin")
        staleFile.writeText("stale data")

        val mam = ModelAssetManager(tmpDir)
        val deleted = mam.cleanupStaleFiles()

        assertTrue("Unrecognised file must have been deleted", deleted >= 1)
        assertFalse("Stale file must not exist after cleanup", staleFile.exists())
    }

    @Test
    fun `cleanupStaleFiles preserves known model files`() {
        // Known files were written in setUp()
        val mam = ModelAssetManager(tmpDir)
        mam.cleanupStaleFiles()

        assertTrue("MOBILEVLM_FILE must survive cleanup",
            File(tmpDir, ModelAssetManager.MOBILEVLM_FILE).exists())
        assertTrue("SEECLICK_PARAM_FILE must survive cleanup",
            File(tmpDir, ModelAssetManager.SEECLICK_PARAM_FILE).exists())
        assertTrue("SEECLICK_BIN_FILE must survive cleanup",
            File(tmpDir, ModelAssetManager.SEECLICK_BIN_FILE).exists())
    }

    @Test
    fun `cleanupStaleFiles returns zero when no stale files present`() {
        val mam = ModelAssetManager(tmpDir)
        val deleted = mam.cleanupStaleFiles()
        assertEquals("No stale files must result in zero deletions", 0, deleted)
    }

    @Test
    fun `cleanupStaleFiles skips subdirectories`() {
        val subDir = File(tmpDir, "subdir")
        subDir.mkdirs()
        assertTrue("Precondition: subdirectory must exist", subDir.isDirectory)

        val mam = ModelAssetManager(tmpDir)
        val deleted = mam.cleanupStaleFiles()

        assertTrue("Subdirectory must not be deleted by cleanupStaleFiles", subDir.exists())
        assertEquals("No files should be deleted when only subdirectories are stale", 0, deleted)
    }

    // ── SeeClick bin file registry tracking ──────────────────────────────────

    @Test
    fun `verifyModel tracks SEECLICK_BIN separately from SEECLICK param`() {
        val mam = ModelAssetManager(tmpDir)
        val paramStatus = mam.verifyModel(ModelAssetManager.MODEL_ID_SEECLICK)
        val binStatus = mam.verifyModel(ModelAssetManager.MODEL_ID_SEECLICK_BIN)

        assertEquals("SEECLICK param must be READY", ModelAssetManager.ModelStatus.READY, paramStatus)
        assertEquals("SEECLICK bin must be READY", ModelAssetManager.ModelStatus.READY, binStatus)
    }

    @Test
    fun `verifyModel returns MISSING for seeclick_bin when bin file absent`() {
        File(tmpDir, ModelAssetManager.SEECLICK_BIN_FILE).delete()
        val mam = ModelAssetManager(tmpDir)
        val binStatus = mam.verifyModel(ModelAssetManager.MODEL_ID_SEECLICK_BIN)
        assertEquals(
            "SEECLICK bin must be MISSING when file absent",
            ModelAssetManager.ModelStatus.MISSING,
            binStatus
        )
    }

    @Test
    fun `downloadSpecsForMissing returns bin spec when only bin is missing`() {
        // Remove only the bin file
        File(tmpDir, ModelAssetManager.SEECLICK_BIN_FILE).delete()
        val mam = ModelAssetManager(tmpDir)
        mam.verifyAll()

        val specs = mam.downloadSpecsForMissing()

        val binSpec = specs.firstOrNull { it.modelId == ModelAssetManager.MODEL_ID_SEECLICK_BIN }
        assertNotNull("Bin download spec must be present when bin file is missing", binSpec)

        val paramSpec = specs.firstOrNull { it.modelId == ModelAssetManager.MODEL_ID_SEECLICK }
        assertNull("Param download spec must NOT be present when param file is already present", paramSpec)

        val vlmSpec = specs.firstOrNull { it.modelId == ModelAssetManager.MODEL_ID_MOBILEVLM }
        assertNull("MobileVLM spec must NOT be present when VLM file is already present", vlmSpec)
    }

    @Test
    fun `downloadSpecsForMissing returns param spec independently when only param is missing`() {
        // Remove only the param file
        File(tmpDir, ModelAssetManager.SEECLICK_PARAM_FILE).delete()
        val mam = ModelAssetManager(tmpDir)
        mam.verifyAll()

        val specs = mam.downloadSpecsForMissing()

        val paramSpec = specs.firstOrNull { it.modelId == ModelAssetManager.MODEL_ID_SEECLICK }
        assertNotNull("Param download spec must be present when param file is missing", paramSpec)

        val binSpec = specs.firstOrNull { it.modelId == ModelAssetManager.MODEL_ID_SEECLICK_BIN }
        assertNull("Bin download spec must NOT be present when bin file exists", binSpec)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Stub planner that supports a [simulateCrash] hook to make [isModelLoaded] return false
     * without going through [unloadModel], mimicking an out-of-process inference server crash.
     */
    private class CrashableStubPlannerService : LocalPlannerService {
        var warmupSucceeds = true
        private var loaded = false

        fun simulateCrash() {
            loaded = false
        }

        override fun loadModel(): Boolean {
            loaded = warmupSucceeds
            return loaded
        }

        override fun unloadModel() {
            loaded = false
        }

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

    /**
     * Stub grounding service with [simulateCrash] support, mirroring [CrashableStubPlannerService].
     */
    private class CrashableStubGroundingService : LocalGroundingService {
        var warmupSucceeds = true
        private var loaded = false

        fun simulateCrash() {
            loaded = false
        }

        override fun loadModel(): Boolean {
            loaded = warmupSucceeds
            return loaded
        }

        override fun unloadModel() {
            loaded = false
        }

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

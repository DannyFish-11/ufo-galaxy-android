package com.ufo.galaxy.runtime

import com.ufo.galaxy.model.ModelAssetManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * PR-73 — Formal default activation policy for Android local intelligence capabilities.
 *
 * Validates all five required automated verification scenarios:
 *
 *  1. **Default activation correctness** — DEFAULT_ON capabilities (RUNTIME_HOST,
 *     GUI_INTERACTION) are active when their base conditions are met; their tier is
 *     formally classified as [LocalIntelligenceActivationPolicy.ActivationTier.DEFAULT_ON].
 *
 *  2. **Guarded activation correctness** — GUARDED_ON capabilities (PLANNER, GROUNDING,
 *     LOCAL_INFERENCE) are active only when guard conditions are met; their tier is
 *     formally classified as [LocalIntelligenceActivationPolicy.ActivationTier.GUARDED_ON].
 *
 *  3. **Degraded state capability shrinkage** — when the runtime is DEGRADED or partially
 *     ready, guarded capabilities that require full health are blocked; the snapshot
 *     reflects the reduced activation set.
 *
 *  4. **Recovery capability restoration** — after recovery from a failure, guarded
 *     capabilities are re-evaluated; successful recovery restores them; limited recovery
 *     keeps them blocked.
 *
 *  5. **Structure-present ≠ default-mainline-active** — [ActivationSnapshot.isStructureOnlyNotMainlineActive]
 *     is `true` when lifecycle is READY/DEGRADED but no guarded capability passes its
 *     guard; prevents "structure exists" from being misread as "fully active mainline".
 *
 * All tests are pure-JVM; no device, emulator, or real ML runtime required.
 */
class Pr73LocalIntelligenceActivationPolicyTest {

    private lateinit var manager: LocalInferenceRuntimeManager
    private lateinit var planner: StubPlannerService
    private lateinit var grounding: StubGroundingService
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "pr73_${System.nanoTime()}")
        tmpDir.mkdirs()
        File(tmpDir, ModelAssetManager.MOBILEVLM_FILE).writeText("stub")
        File(tmpDir, ModelAssetManager.SEECLICK_PARAM_FILE).writeText("stub")
        File(tmpDir, ModelAssetManager.SEECLICK_BIN_FILE).writeText("stub")
        val assetManager = ModelAssetManager(tmpDir)
        planner = StubPlannerService()
        grounding = StubGroundingService()
        manager = LocalInferenceRuntimeManager(planner, grounding, assetManager)
    }

    // ════════════════════════════════════════════════════════════════════════
    // POLICY STRUCTURE — tier classification
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `RUNTIME_HOST capability has DEFAULT_ON tier`() {
        assertEquals(
            "RUNTIME_HOST must be DEFAULT_ON tier",
            LocalIntelligenceActivationPolicy.ActivationTier.DEFAULT_ON,
            LocalIntelligenceActivationPolicy.Capability.RUNTIME_HOST.tier
        )
    }

    @Test
    fun `GUI_INTERACTION capability has DEFAULT_ON tier`() {
        assertEquals(
            "GUI_INTERACTION must be DEFAULT_ON tier",
            LocalIntelligenceActivationPolicy.ActivationTier.DEFAULT_ON,
            LocalIntelligenceActivationPolicy.Capability.GUI_INTERACTION.tier
        )
    }

    @Test
    fun `PLANNER capability has GUARDED_ON tier`() {
        assertEquals(
            "PLANNER must be GUARDED_ON tier",
            LocalIntelligenceActivationPolicy.ActivationTier.GUARDED_ON,
            LocalIntelligenceActivationPolicy.Capability.PLANNER.tier
        )
    }

    @Test
    fun `GROUNDING capability has GUARDED_ON tier`() {
        assertEquals(
            "GROUNDING must be GUARDED_ON tier",
            LocalIntelligenceActivationPolicy.ActivationTier.GUARDED_ON,
            LocalIntelligenceActivationPolicy.Capability.GROUNDING.tier
        )
    }

    @Test
    fun `LOCAL_INFERENCE capability has GUARDED_ON tier`() {
        assertEquals(
            "LOCAL_INFERENCE must be GUARDED_ON tier",
            LocalIntelligenceActivationPolicy.ActivationTier.GUARDED_ON,
            LocalIntelligenceActivationPolicy.Capability.LOCAL_INFERENCE.tier
        )
    }

    @Test
    fun `DEFAULT_ON tier isDefaultMainline is true`() {
        assertTrue(
            "DEFAULT_ON tier must have isDefaultMainline=true",
            LocalIntelligenceActivationPolicy.ActivationTier.DEFAULT_ON.isDefaultMainline
        )
    }

    @Test
    fun `GUARDED_ON tier isDefaultMainline is false`() {
        assertFalse(
            "GUARDED_ON tier must have isDefaultMainline=false — guarded capabilities are not unconditionally mainline",
            LocalIntelligenceActivationPolicy.ActivationTier.GUARDED_ON.isDefaultMainline
        )
    }

    @Test
    fun `defaultMainlineCapabilities returns only DEFAULT_ON capabilities`() {
        val mainline = LocalIntelligenceActivationPolicy.defaultMainlineCapabilities()
        assertTrue(
            "RUNTIME_HOST must be in default mainline",
            mainline.contains(LocalIntelligenceActivationPolicy.Capability.RUNTIME_HOST)
        )
        assertTrue(
            "GUI_INTERACTION must be in default mainline",
            mainline.contains(LocalIntelligenceActivationPolicy.Capability.GUI_INTERACTION)
        )
        assertFalse(
            "PLANNER must NOT be in default mainline — it is guarded",
            mainline.contains(LocalIntelligenceActivationPolicy.Capability.PLANNER)
        )
        assertFalse(
            "GROUNDING must NOT be in default mainline — it is guarded",
            mainline.contains(LocalIntelligenceActivationPolicy.Capability.GROUNDING)
        )
        assertFalse(
            "LOCAL_INFERENCE must NOT be in default mainline — it is guarded",
            mainline.contains(LocalIntelligenceActivationPolicy.Capability.LOCAL_INFERENCE)
        )
    }

    @Test
    fun `guardedCapabilities returns only GUARDED_ON capabilities`() {
        val guarded = LocalIntelligenceActivationPolicy.guardedCapabilities()
        assertTrue(
            "PLANNER must be in guarded set",
            guarded.contains(LocalIntelligenceActivationPolicy.Capability.PLANNER)
        )
        assertTrue(
            "GROUNDING must be in guarded set",
            guarded.contains(LocalIntelligenceActivationPolicy.Capability.GROUNDING)
        )
        assertTrue(
            "LOCAL_INFERENCE must be in guarded set",
            guarded.contains(LocalIntelligenceActivationPolicy.Capability.LOCAL_INFERENCE)
        )
        assertFalse(
            "RUNTIME_HOST must NOT be in guarded set",
            guarded.contains(LocalIntelligenceActivationPolicy.Capability.RUNTIME_HOST)
        )
        assertFalse(
            "GUI_INTERACTION must NOT be in guarded set",
            guarded.contains(LocalIntelligenceActivationPolicy.Capability.GUI_INTERACTION)
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. DEFAULT ACTIVATION CORRECTNESS
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `RUNTIME_HOST is default-mainline-active when host is ACTIVE and lifecycle allows`() {
        val inputs = fullReadyInputs()
        val result = LocalIntelligenceActivationPolicy.evaluateActivation(
            LocalIntelligenceActivationPolicy.Capability.RUNTIME_HOST, inputs
        )
        assertTrue("RUNTIME_HOST must be active in full-ready state", result.isActive)
        assertTrue("RUNTIME_HOST must be default-mainline when active", result.isDefaultMainline)
        assertFalse("RUNTIME_HOST must not be guarded-active", result.isGuardedActive)
        assertNull("RUNTIME_HOST must have no fallback scenario when active", result.activeFallbackScenario)
    }

    @Test
    fun `GUI_INTERACTION is default-mainline-active when accessibility and overlay ready`() {
        val inputs = fullReadyInputs()
        val result = LocalIntelligenceActivationPolicy.evaluateActivation(
            LocalIntelligenceActivationPolicy.Capability.GUI_INTERACTION, inputs
        )
        assertTrue("GUI_INTERACTION must be active when accessibility+overlay ready", result.isActive)
        assertTrue("GUI_INTERACTION must be default-mainline when active", result.isDefaultMainline)
        assertFalse("GUI_INTERACTION must not be guarded-active", result.isGuardedActive)
        assertNull("GUI_INTERACTION must have no fallback scenario when active", result.activeFallbackScenario)
    }

    @Test
    fun `RUNTIME_HOST is inactive when lifecycle is UNAVAILABLE_FAILED`() {
        val inputs = LocalIntelligenceActivationPolicy.ActivationInputs(
            formalLifecycleState = FormalParticipantLifecycleState.UNAVAILABLE_FAILED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DISABLED,
            runtimeHostActive = true,
            accessibilityReady = true,
            overlayReady = true
        )
        val result = LocalIntelligenceActivationPolicy.evaluateActivation(
            LocalIntelligenceActivationPolicy.Capability.RUNTIME_HOST, inputs
        )
        assertFalse("RUNTIME_HOST must be inactive in UNAVAILABLE_FAILED state", result.isActive)
        assertFalse("RUNTIME_HOST must not be default-mainline when inactive", result.isDefaultMainline)
        assertNotNull("RUNTIME_HOST must report a fallback scenario when inactive", result.activeFallbackScenario)
    }

    @Test
    fun `GUI_INTERACTION is inactive when accessibility service is not ready`() {
        val inputs = LocalIntelligenceActivationPolicy.ActivationInputs(
            formalLifecycleState = FormalParticipantLifecycleState.READY,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE,
            accessibilityReady = false,
            overlayReady = true,
            runtimeHostActive = true
        )
        val result = LocalIntelligenceActivationPolicy.evaluateActivation(
            LocalIntelligenceActivationPolicy.Capability.GUI_INTERACTION, inputs
        )
        assertFalse("GUI_INTERACTION must be inactive when accessibility not ready", result.isActive)
        assertFalse("GUI_INTERACTION must not be default-mainline when inactive", result.isDefaultMainline)
        assertEquals(
            "GUI_INTERACTION fallback must be DISABLED_NO_MODEL_PARTIAL_READY",
            LocalIntelligenceActivationPolicy.FallbackScenario.DISABLED_NO_MODEL_PARTIAL_READY,
            result.activeFallbackScenario
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. GUARDED ACTIVATION CORRECTNESS
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `PLANNER is guarded-active when model loaded and lifecycle READY`() {
        val inputs = fullReadyInputs()
        val result = LocalIntelligenceActivationPolicy.evaluateActivation(
            LocalIntelligenceActivationPolicy.Capability.PLANNER, inputs
        )
        assertTrue("PLANNER must be active when model loaded and READY", result.isActive)
        assertFalse("PLANNER must not be default-mainline — it is guarded", result.isDefaultMainline)
        assertTrue("PLANNER must be guarded-active when guard passes", result.isGuardedActive)
        assertNull("PLANNER must have no fallback when active", result.activeFallbackScenario)
    }

    @Test
    fun `GROUNDING is guarded-active when model loaded and lifecycle READY`() {
        val inputs = fullReadyInputs()
        val result = LocalIntelligenceActivationPolicy.evaluateActivation(
            LocalIntelligenceActivationPolicy.Capability.GROUNDING, inputs
        )
        assertTrue("GROUNDING must be active when model loaded and READY", result.isActive)
        assertFalse("GROUNDING must not be default-mainline — it is guarded", result.isDefaultMainline)
        assertTrue("GROUNDING must be guarded-active when guard passes", result.isGuardedActive)
        assertNull("GROUNDING must have no fallback when active", result.activeFallbackScenario)
    }

    @Test
    fun `LOCAL_INFERENCE is guarded-active when both models loaded and inference ACTIVE`() {
        val inputs = fullReadyInputs()
        val result = LocalIntelligenceActivationPolicy.evaluateActivation(
            LocalIntelligenceActivationPolicy.Capability.LOCAL_INFERENCE, inputs
        )
        assertTrue("LOCAL_INFERENCE must be active with full-ready inputs", result.isActive)
        assertFalse("LOCAL_INFERENCE must not be default-mainline — it is guarded", result.isDefaultMainline)
        assertTrue("LOCAL_INFERENCE must be guarded-active when all guards pass", result.isGuardedActive)
        assertNull("LOCAL_INFERENCE must have no fallback when active", result.activeFallbackScenario)
    }

    @Test
    fun `PLANNER is inactive when model is not loaded — MODEL_UNAVAILABLE fallback`() {
        val inputs = LocalIntelligenceActivationPolicy.ActivationInputs(
            formalLifecycleState = FormalParticipantLifecycleState.READY,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DISABLED,
            plannerModelLoaded = false,
            groundingModelLoaded = true
        )
        val result = LocalIntelligenceActivationPolicy.evaluateActivation(
            LocalIntelligenceActivationPolicy.Capability.PLANNER, inputs
        )
        assertFalse("PLANNER must be inactive when model not loaded", result.isActive)
        assertFalse("PLANNER must not be guarded-active when model not loaded", result.isGuardedActive)
        assertEquals(
            "PLANNER fallback must be MODEL_UNAVAILABLE when model not loaded",
            LocalIntelligenceActivationPolicy.FallbackScenario.MODEL_UNAVAILABLE,
            result.activeFallbackScenario
        )
    }

    @Test
    fun `GROUNDING is inactive when model is not loaded — MODEL_UNAVAILABLE fallback`() {
        val inputs = LocalIntelligenceActivationPolicy.ActivationInputs(
            formalLifecycleState = FormalParticipantLifecycleState.READY,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DISABLED,
            plannerModelLoaded = true,
            groundingModelLoaded = false
        )
        val result = LocalIntelligenceActivationPolicy.evaluateActivation(
            LocalIntelligenceActivationPolicy.Capability.GROUNDING, inputs
        )
        assertFalse("GROUNDING must be inactive when model not loaded", result.isActive)
        assertFalse("GROUNDING must not be guarded-active when model not loaded", result.isGuardedActive)
        assertEquals(
            "GROUNDING fallback must be MODEL_UNAVAILABLE when model not loaded",
            LocalIntelligenceActivationPolicy.FallbackScenario.MODEL_UNAVAILABLE,
            result.activeFallbackScenario
        )
    }

    @Test
    fun `LOCAL_INFERENCE is inactive when planner model not loaded`() {
        val inputs = LocalIntelligenceActivationPolicy.ActivationInputs(
            formalLifecycleState = FormalParticipantLifecycleState.READY,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE,
            plannerModelLoaded = false,
            groundingModelLoaded = true
        )
        val result = LocalIntelligenceActivationPolicy.evaluateActivation(
            LocalIntelligenceActivationPolicy.Capability.LOCAL_INFERENCE, inputs
        )
        assertFalse("LOCAL_INFERENCE must be inactive when planner model not loaded", result.isActive)
        assertEquals(
            "LOCAL_INFERENCE fallback must be MODEL_UNAVAILABLE when planner model missing",
            LocalIntelligenceActivationPolicy.FallbackScenario.MODEL_UNAVAILABLE,
            result.activeFallbackScenario
        )
    }

    @Test
    fun `LOCAL_INFERENCE is inactive when grounding model not loaded`() {
        val inputs = LocalIntelligenceActivationPolicy.ActivationInputs(
            formalLifecycleState = FormalParticipantLifecycleState.READY,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE,
            plannerModelLoaded = true,
            groundingModelLoaded = false
        )
        val result = LocalIntelligenceActivationPolicy.evaluateActivation(
            LocalIntelligenceActivationPolicy.Capability.LOCAL_INFERENCE, inputs
        )
        assertFalse("LOCAL_INFERENCE must be inactive when grounding model not loaded", result.isActive)
        assertEquals(
            "LOCAL_INFERENCE fallback must be MODEL_UNAVAILABLE when grounding model missing",
            LocalIntelligenceActivationPolicy.FallbackScenario.MODEL_UNAVAILABLE,
            result.activeFallbackScenario
        )
    }

    @Test
    fun `PLANNER is inactive when lifecycle is STARTING`() {
        val inputs = LocalIntelligenceActivationPolicy.ActivationInputs(
            formalLifecycleState = FormalParticipantLifecycleState.STARTING,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DISABLED,
            plannerModelLoaded = true,
            groundingModelLoaded = true
        )
        val result = LocalIntelligenceActivationPolicy.evaluateActivation(
            LocalIntelligenceActivationPolicy.Capability.PLANNER, inputs
        )
        assertFalse("PLANNER must be inactive in STARTING state", result.isActive)
        assertFalse("PLANNER must not be guarded-active in STARTING state", result.isGuardedActive)
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. DEGRADED STATE CAPABILITY SHRINKAGE
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `LOCAL_INFERENCE is inactive in DEGRADED lifecycle state`() {
        val inputs = LocalIntelligenceActivationPolicy.ActivationInputs(
            formalLifecycleState = FormalParticipantLifecycleState.DEGRADED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DEGRADED,
            plannerModelLoaded = true,
            groundingModelLoaded = true,
            accessibilityReady = true,
            overlayReady = true,
            runtimeHostActive = true
        )
        val result = LocalIntelligenceActivationPolicy.evaluateActivation(
            LocalIntelligenceActivationPolicy.Capability.LOCAL_INFERENCE, inputs
        )
        assertFalse(
            "LOCAL_INFERENCE must be inactive in DEGRADED lifecycle — inference requires READY",
            result.isActive
        )
        assertEquals(
            "LOCAL_INFERENCE fallback must be DEGRADED_RUNTIME in degraded lifecycle",
            LocalIntelligenceActivationPolicy.FallbackScenario.DEGRADED_RUNTIME,
            result.activeFallbackScenario
        )
    }

    @Test
    fun `PLANNER may be active in DEGRADED lifecycle state when model loaded`() {
        val inputs = LocalIntelligenceActivationPolicy.ActivationInputs(
            formalLifecycleState = FormalParticipantLifecycleState.DEGRADED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DEGRADED,
            plannerModelLoaded = true,
            groundingModelLoaded = false
        )
        val result = LocalIntelligenceActivationPolicy.evaluateActivation(
            LocalIntelligenceActivationPolicy.Capability.PLANNER, inputs
        )
        assertTrue(
            "PLANNER may be guarded-active in DEGRADED state if model loaded",
            result.isActive
        )
        assertTrue("PLANNER must be guarded-active in degraded state", result.isGuardedActive)
    }

    @Test
    fun `degraded runtime shrinks active capability set — snapshot confirms`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = false
        manager.start()

        assertTrue(
            "Pre-condition: state must be Degraded",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Degraded
        )

        val snapshot = LocalIntelligenceActivationPolicySurface.buildSnapshot(
            managerState = manager.state.value,
            plannerModelLoaded = true,
            groundingModelLoaded = false,
            accessibilityReady = true,
            overlayReady = true,
            runtimeHostActive = true
        )

        assertFalse(
            "LOCAL_INFERENCE must not be active in degraded runtime",
            snapshot.isActive(LocalIntelligenceActivationPolicy.Capability.LOCAL_INFERENCE)
        )
        assertFalse(
            "GROUNDING must not be active when grounding model not loaded",
            snapshot.isActive(LocalIntelligenceActivationPolicy.Capability.GROUNDING)
        )
        assertFalse(
            "Snapshot must not report isFullyActive in degraded state",
            snapshot.isFullyActive
        )
    }

    @Test
    fun `RECOVERING lifecycle blocks all guarded capabilities`() {
        val inputs = LocalIntelligenceActivationPolicy.ActivationInputs(
            formalLifecycleState = FormalParticipantLifecycleState.RECOVERING,
            inferenceStatus = LocalIntelligenceCapabilityStatus.RECOVERING,
            plannerModelLoaded = true,
            groundingModelLoaded = true,
            accessibilityReady = true,
            overlayReady = true,
            runtimeHostActive = true
        )
        val snapshot = LocalIntelligenceActivationPolicySurface.buildSnapshot(inputs)

        assertFalse(
            "PLANNER must be inactive in RECOVERING state",
            snapshot.isActive(LocalIntelligenceActivationPolicy.Capability.PLANNER)
        )
        assertFalse(
            "GROUNDING must be inactive in RECOVERING state",
            snapshot.isActive(LocalIntelligenceActivationPolicy.Capability.GROUNDING)
        )
        assertFalse(
            "LOCAL_INFERENCE must be inactive in RECOVERING state",
            snapshot.isActive(LocalIntelligenceActivationPolicy.Capability.LOCAL_INFERENCE)
        )
        assertFalse(
            "RUNTIME_HOST must be inactive in RECOVERING state (lifecycle blocked)",
            snapshot.isActive(LocalIntelligenceActivationPolicy.Capability.RUNTIME_HOST)
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. RECOVERY CAPABILITY RESTORATION
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `guarded capabilities are restored after successful recovery`() = runBlocking {
        // Start fully healthy
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        assertEquals(
            "Pre-condition: state must be Running",
            LocalInferenceRuntimeManager.ManagerState.Running::class.java,
            manager.state.value::class.java
        )

        // Simulate crash
        planner.simulateCrash()

        // Recover successfully
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.recoverIfUnhealthy()

        val snapshot = LocalIntelligenceActivationPolicySurface.buildSnapshot(
            managerState = manager.state.value,
            plannerModelLoaded = true,
            groundingModelLoaded = true,
            accessibilityReady = true,
            overlayReady = true,
            runtimeHostActive = true
        )

        assertTrue(
            "PLANNER must be guarded-active after successful recovery",
            snapshot.isGuardedActive(LocalIntelligenceActivationPolicy.Capability.PLANNER)
        )
        assertTrue(
            "GROUNDING must be guarded-active after successful recovery",
            snapshot.isGuardedActive(LocalIntelligenceActivationPolicy.Capability.GROUNDING)
        )
        assertTrue(
            "LOCAL_INFERENCE must be guarded-active after successful recovery",
            snapshot.isGuardedActive(LocalIntelligenceActivationPolicy.Capability.LOCAL_INFERENCE)
        )
    }

    @Test
    fun `guarded capabilities remain blocked after partial recovery`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        planner.simulateCrash()

        // Recovery: planner comes back but grounding fails
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = false
        manager.recoverIfUnhealthy()

        val snapshot = LocalIntelligenceActivationPolicySurface.buildSnapshot(
            managerState = manager.state.value,
            plannerModelLoaded = true,
            groundingModelLoaded = false,
            accessibilityReady = true,
            overlayReady = true,
            runtimeHostActive = true
        )

        assertFalse(
            "LOCAL_INFERENCE must remain inactive after partial recovery (grounding still down)",
            snapshot.isActive(LocalIntelligenceActivationPolicy.Capability.LOCAL_INFERENCE)
        )
        assertFalse(
            "GROUNDING must remain inactive after partial recovery (model not loaded)",
            snapshot.isActive(LocalIntelligenceActivationPolicy.Capability.GROUNDING)
        )
    }

    @Test
    fun `RECOVERING fallback scenario reported for capabilities during recovery`() {
        val inputs = LocalIntelligenceActivationPolicy.ActivationInputs(
            formalLifecycleState = FormalParticipantLifecycleState.RECOVERING,
            inferenceStatus = LocalIntelligenceCapabilityStatus.RECOVERING,
            plannerModelLoaded = true,
            groundingModelLoaded = true
        )
        val result = LocalIntelligenceActivationPolicy.evaluateActivation(
            LocalIntelligenceActivationPolicy.Capability.PLANNER, inputs
        )
        assertFalse("PLANNER must not be active during recovery", result.isActive)
        assertEquals(
            "PLANNER fallback must be RECOVERED_BUT_LIMITED during RECOVERING state",
            LocalIntelligenceActivationPolicy.FallbackScenario.RECOVERED_BUT_LIMITED,
            result.activeFallbackScenario
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. STRUCTURE-PRESENT ≠ DEFAULT-MAINLINE-ACTIVE
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `isStructureOnlyNotMainlineActive is true when lifecycle READY but no model loaded`() {
        val inputs = LocalIntelligenceActivationPolicy.ActivationInputs(
            formalLifecycleState = FormalParticipantLifecycleState.READY,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DISABLED,
            plannerModelLoaded = false,
            groundingModelLoaded = false,
            accessibilityReady = true,
            overlayReady = true,
            runtimeHostActive = true
        )
        val snapshot = LocalIntelligenceActivationPolicySurface.buildSnapshot(inputs)

        assertTrue(
            "isStructureOnlyNotMainlineActive must be true when lifecycle=READY but no models loaded",
            snapshot.isStructureOnlyNotMainlineActive
        )
    }

    @Test
    fun `isStructureOnlyNotMainlineActive is false when guarded capabilities are active`() {
        val snapshot = LocalIntelligenceActivationPolicySurface.buildSnapshot(
            fullReadyInputs()
        )

        assertFalse(
            "isStructureOnlyNotMainlineActive must be false when guarded capabilities are active",
            snapshot.isStructureOnlyNotMainlineActive
        )
    }

    @Test
    fun `isStructureOnlyNotMainlineActive is true in DEGRADED state with no model`() {
        val inputs = LocalIntelligenceActivationPolicy.ActivationInputs(
            formalLifecycleState = FormalParticipantLifecycleState.DEGRADED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DEGRADED,
            plannerModelLoaded = false,
            groundingModelLoaded = false
        )
        val snapshot = LocalIntelligenceActivationPolicySurface.buildSnapshot(inputs)

        assertTrue(
            "isStructureOnlyNotMainlineActive must be true in DEGRADED with no models",
            snapshot.isStructureOnlyNotMainlineActive
        )
    }

    @Test
    fun `stopped runtime is not default mainline active — snapshot reflects this`() = runBlocking {
        // Manager has not been started; state is Stopped
        assertTrue(
            "Pre-condition: state must be Stopped",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Stopped
        )

        val snapshot = LocalIntelligenceActivationPolicySurface.buildSnapshot(
            managerState = manager.state.value,
            plannerModelLoaded = false,
            groundingModelLoaded = false,
            accessibilityReady = false,
            overlayReady = false,
            runtimeHostActive = false
        )

        assertFalse(
            "No capability must be active when runtime is Stopped",
            snapshot.hasAnyActive
        )
        assertFalse(
            "Stopped runtime must not be fully active",
            snapshot.isFullyActive
        )
        assertTrue(
            "Stopped runtime must not be default-mainline or guarded active",
            snapshot.defaultMainlineActive.isEmpty() && snapshot.guardedActive.isEmpty()
        )
    }

    @Test
    fun `failed runtime produces no active guarded capabilities`() = runBlocking {
        planner.warmupSucceeds = false
        grounding.warmupSucceeds = false
        manager.start()

        assertTrue(
            "Pre-condition: state must be Failed after both runtimes fail",
            manager.state.value is LocalInferenceRuntimeManager.ManagerState.Failed
        )

        val snapshot = LocalIntelligenceActivationPolicySurface.buildSnapshot(
            managerState = manager.state.value,
            plannerModelLoaded = false,
            groundingModelLoaded = false,
            accessibilityReady = true,
            overlayReady = true,
            runtimeHostActive = false
        )

        assertTrue(
            "guardedActive must be empty when runtime is Failed",
            snapshot.guardedActive.isEmpty()
        )
        assertFalse(
            "LOCAL_INFERENCE must not be active in Failed state",
            snapshot.isActive(LocalIntelligenceActivationPolicy.Capability.LOCAL_INFERENCE)
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // SNAPSHOT — metadata integration
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `toCapabilityTierMetadata contains tier entry for every capability`() {
        val snapshot = LocalIntelligenceActivationPolicySurface.buildSnapshot(fullReadyInputs())
        val metadata = LocalIntelligenceActivationPolicySurface.toCapabilityTierMetadata(snapshot)

        for (cap in LocalIntelligenceActivationPolicy.Capability.entries) {
            assertTrue(
                "Tier metadata must contain entry for capability '${cap.wireValue}'",
                metadata.containsKey(cap.wireValue)
            )
            assertEquals(
                "Tier metadata for '${cap.wireValue}' must match capability's tier wire value",
                cap.tier.wireValue,
                metadata[cap.wireValue]
            )
        }
    }

    @Test
    fun `toCapabilityActivationMetadata reflects active state correctly`() {
        val snapshot = LocalIntelligenceActivationPolicySurface.buildSnapshot(fullReadyInputs())
        val metadata = LocalIntelligenceActivationPolicySurface.toCapabilityActivationMetadata(snapshot)

        assertEquals(
            "LOCAL_INFERENCE must be 'active' in metadata when fully ready",
            "active",
            metadata[LocalIntelligenceActivationPolicy.Capability.LOCAL_INFERENCE.wireValue]
        )
        assertEquals(
            "RUNTIME_HOST must be 'active' in metadata when fully ready",
            "active",
            metadata[LocalIntelligenceActivationPolicy.Capability.RUNTIME_HOST.wireValue]
        )
    }

    @Test
    fun `toCapabilityActivationMetadata reflects inactive state when runtime stopped`() {
        val inputs = LocalIntelligenceActivationPolicy.ActivationInputs(
            formalLifecycleState = FormalParticipantLifecycleState.UNAVAILABLE_FAILED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DISABLED
        )
        val snapshot = LocalIntelligenceActivationPolicySurface.buildSnapshot(inputs)
        val metadata = LocalIntelligenceActivationPolicySurface.toCapabilityActivationMetadata(snapshot)

        assertEquals(
            "LOCAL_INFERENCE must be 'inactive' in metadata when runtime stopped",
            "inactive",
            metadata[LocalIntelligenceActivationPolicy.Capability.LOCAL_INFERENCE.wireValue]
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // WIRE VALUE ROUND-TRIP
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `all activation tiers have stable wire values`() {
        for (tier in LocalIntelligenceActivationPolicy.ActivationTier.entries) {
            val parsed = LocalIntelligenceActivationPolicy.ActivationTier.fromWireValue(tier.wireValue)
            assertEquals(
                "ActivationTier wire value round-trip must be stable for ${tier.name}",
                tier,
                parsed
            )
        }
    }

    @Test
    fun `all capability wire values are stable`() {
        for (cap in LocalIntelligenceActivationPolicy.Capability.entries) {
            val parsed = LocalIntelligenceActivationPolicy.Capability.fromWireValue(cap.wireValue)
            assertEquals(
                "Capability wire value round-trip must be stable for ${cap.name}",
                cap,
                parsed
            )
        }
    }

    @Test
    fun `all fallback scenario wire values are stable`() {
        for (scenario in LocalIntelligenceActivationPolicy.FallbackScenario.entries) {
            val parsed = LocalIntelligenceActivationPolicy.FallbackScenario.fromWireValue(scenario.wireValue)
            assertEquals(
                "FallbackScenario wire value round-trip must be stable for ${scenario.name}",
                scenario,
                parsed
            )
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════════

    /** Returns fully-ready inputs where all capabilities should be active. */
    private fun fullReadyInputs() = LocalIntelligenceActivationPolicy.ActivationInputs(
        formalLifecycleState = FormalParticipantLifecycleState.READY,
        inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE,
        plannerModelLoaded = true,
        groundingModelLoaded = true,
        accessibilityReady = true,
        overlayReady = true,
        runtimeHostActive = true
    )

    // ── Stub services ─────────────────────────────────────────────────────────

    private class StubPlannerService : com.ufo.galaxy.inference.LocalPlannerService {
        var warmupSucceeds = true
        private var loaded = false
        private var crashed = false

        override fun loadModel(): Boolean {
            if (crashed) return false
            loaded = warmupSucceeds
            return loaded
        }

        override fun unloadModel() {
            loaded = false
        }

        override fun isModelLoaded(): Boolean = loaded && !crashed

        fun simulateCrash() {
            crashed = true
            loaded = false
        }

        override fun warmupWithResult(): com.ufo.galaxy.inference.WarmupResult {
            if (crashed) return com.ufo.galaxy.inference.WarmupResult.failure(
                com.ufo.galaxy.inference.WarmupResult.WarmupStage.HEALTH_CHECK, "Crashed"
            )
            loaded = warmupSucceeds
            return if (warmupSucceeds) com.ufo.galaxy.inference.WarmupResult.success()
            else com.ufo.galaxy.inference.WarmupResult.failure(
                com.ufo.galaxy.inference.WarmupResult.WarmupStage.HEALTH_CHECK, "stub planner failed"
            )
        }

        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            com.ufo.galaxy.inference.LocalPlannerService.PlanResult(emptyList(), "stub")

        override fun replan(
            goal: String, constraints: List<String>,
            failedStep: com.ufo.galaxy.inference.LocalPlannerService.PlanStep,
            error: String, screenshotBase64: String?
        ) = com.ufo.galaxy.inference.LocalPlannerService.PlanResult(emptyList(), "stub")
    }

    private class StubGroundingService : com.ufo.galaxy.inference.LocalGroundingService {
        var warmupSucceeds = true
        private var loaded = false

        override fun loadModel(): Boolean {
            loaded = warmupSucceeds
            return loaded
        }

        override fun unloadModel() {
            loaded = false
        }

        override fun isModelLoaded(): Boolean = loaded

        override fun warmupWithResult(): com.ufo.galaxy.inference.WarmupResult {
            loaded = warmupSucceeds
            return if (warmupSucceeds) com.ufo.galaxy.inference.WarmupResult.success()
            else com.ufo.galaxy.inference.WarmupResult.failure(
                com.ufo.galaxy.inference.WarmupResult.WarmupStage.HEALTH_CHECK, "stub grounding failed"
            )
        }

        override fun ground(
            intent: String, screenshotBase64: String, width: Int, height: Int
        ) = com.ufo.galaxy.inference.LocalGroundingService.GroundingResult(0, 0, 0f, "")
    }
}

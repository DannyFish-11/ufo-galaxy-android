package com.ufo.galaxy.runtime

import com.ufo.galaxy.model.ModelAssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * PR-14 — Formal Android participant capability declaration, health state, and recovery
 * semantics acceptance tests.
 *
 * Validates the four required automated verification scenarios:
 *
 *  1. **Degraded capability reduction** — capability set shrinks when runtime is DEGRADED;
 *     inference capability is blocked.
 *
 *  2. **Recovery restores capabilities** — after a successful recovery cycle, the full
 *     capability set (including inference) is available again.
 *
 *  3. **Unavailable model blocks capability claim** — while a model is unavailable or
 *     recovery is in progress, no capability advertisement is permitted.
 *
 *  4. **Re-register after recovery consistency** — after recovery, the re-registration
 *     requirement is detected and the heartbeat capability set is consistent with the
 *     re-advertised set.
 *
 * Also covers:
 *  - [FormalParticipantLifecycleState] — all 5 states, derivation from ManagerState and
 *    ParticipantHealthState, capability gates, re-advertise trigger detection.
 *  - [CapabilityReadvertiseContract] — all 4 ReadvertiseAction variants, decide() logic,
 *    re-registration requirement, heartbeat consistency.
 *  - [RecoveryCapabilityAlignmentGuard] — checkAlignment(), computeAllowedCapabilities(),
 *    isAdvertisementBlocked(), requiresPostRecoveryReRegistration().
 *  - [ParticipantHealthState.STARTING] — new state added for formal lifecycle model.
 *
 * All tests are pure-JVM; no device, emulator, or real ML runtime required.
 */
class PrFormalCapabilityDeclarationTest {

    private lateinit var manager: LocalInferenceRuntimeManager
    private lateinit var planner: StubPlannerService
    private lateinit var grounding: StubGroundingService
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "pr_formal_cap_${System.nanoTime()}")
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
    // 1. DEGRADED CAPABILITY REDUCTION
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `degraded state produces DEGRADED formal lifecycle state`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = false
        manager.start()

        val formal = FormalParticipantLifecycleState.fromManagerState(manager.state.value)
        assertEquals(
            "Partial start failure must map to DEGRADED formal state",
            FormalParticipantLifecycleState.DEGRADED,
            formal
        )
    }

    @Test
    fun `degraded state blocks inference capability advertisement`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = false
        manager.start()

        val formal = FormalParticipantLifecycleState.fromManagerState(manager.state.value)
        assertFalse(
            "Inference capability must be blocked in DEGRADED state",
            FormalParticipantLifecycleState.inferenceCapabilityAllowed(
                formal,
                inferenceStatusActive = true
            )
        )
    }

    @Test
    fun `degraded state permits base capabilities only`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = false
        manager.start()

        val formal = FormalParticipantLifecycleState.fromManagerState(manager.state.value)
        val allowed = RecoveryCapabilityAlignmentGuard.computeAllowedCapabilities(
            lifecycleState = formal,
            inferenceAvailable = true
        )
        assertEquals(
            "DEGRADED state must allow only base capabilities",
            CapabilityHonestyGuard.BASE_CAPABILITIES,
            allowed
        )
    }

    @Test
    fun `advertising inference in DEGRADED state produces alignment violation`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = false
        manager.start()

        val formal = FormalParticipantLifecycleState.fromManagerState(manager.state.value)
        val result = RecoveryCapabilityAlignmentGuard.checkAlignment(
            lifecycleState = formal,
            advertisedCapabilities = setOf(CapabilityHonestyGuard.CAPABILITY_LOCAL_MODEL_INFERENCE),
            inferenceAvailable = true
        )
        assertFalse(
            "Advertising inference in DEGRADED state must produce alignment violation",
            result.isAligned
        )
        assertTrue(
            "Violation must identify local_model_inference",
            result.violations.any {
                it.violatingCapability == CapabilityHonestyGuard.CAPABILITY_LOCAL_MODEL_INFERENCE
            }
        )
    }

    @Test
    fun `advertising base capabilities in DEGRADED state is aligned`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = false
        manager.start()

        val formal = FormalParticipantLifecycleState.fromManagerState(manager.state.value)
        val result = RecoveryCapabilityAlignmentGuard.checkAlignment(
            lifecycleState = formal,
            advertisedCapabilities = CapabilityHonestyGuard.BASE_CAPABILITIES,
            inferenceAvailable = false
        )
        assertTrue(
            "Advertising only base capabilities in DEGRADED state must be aligned",
            result.isAligned
        )
    }

    @Test
    fun `DEGRADED readvertise decision is READVERTISE_BASE_ONLY`() {
        val decision = CapabilityReadvertiseContract.decide(
            lifecycleState = FormalParticipantLifecycleState.DEGRADED,
            inferenceAvailable = true
        )
        assertEquals(
            "DEGRADED must produce READVERTISE_BASE_ONLY action",
            CapabilityReadvertiseContract.ReadvertiseAction.READVERTISE_BASE_ONLY,
            decision.action
        )
    }

    @Test
    fun `DEGRADED readvertise decision has base capabilities only`() {
        val decision = CapabilityReadvertiseContract.decide(
            lifecycleState = FormalParticipantLifecycleState.DEGRADED,
            inferenceAvailable = true
        )
        assertEquals(
            "DEGRADED readvertise decision must include only base capabilities",
            CapabilityHonestyGuard.BASE_CAPABILITIES,
            decision.allowedCapabilities
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. RECOVERY RESTORES CAPABILITIES
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `recovery from crash restores READY formal state`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        planner.simulateCrash()
        planner.warmupSucceeds = true
        manager.recoverIfUnhealthy()

        val formal = FormalParticipantLifecycleState.fromManagerState(manager.state.value)
        assertEquals(
            "After successful recovery, formal state must be READY",
            FormalParticipantLifecycleState.READY,
            formal
        )
    }

    @Test
    fun `recovery restores inference capability when both services recover`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        planner.simulateCrash()
        planner.warmupSucceeds = true
        manager.recoverIfUnhealthy()

        val formal = FormalParticipantLifecycleState.fromManagerState(manager.state.value)
        val inferenceStatus = LocalIntelligenceCapabilityStatus.from(manager.state.value)
        assertTrue(
            "Recovery must restore inference capability when both services are healthy",
            FormalParticipantLifecycleState.inferenceCapabilityAllowed(
                formal,
                inferenceStatusActive = (inferenceStatus == LocalIntelligenceCapabilityStatus.ACTIVE)
            )
        )
    }

    @Test
    fun `READY readvertise decision is READVERTISE_FULL with active inference`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        planner.simulateCrash()
        planner.warmupSucceeds = true
        manager.recoverIfUnhealthy()

        val formal = FormalParticipantLifecycleState.fromManagerState(manager.state.value)
        val inferenceActive =
            LocalIntelligenceCapabilityStatus.from(manager.state.value) == LocalIntelligenceCapabilityStatus.ACTIVE

        val decision = CapabilityReadvertiseContract.decide(
            lifecycleState = formal,
            inferenceAvailable = inferenceActive
        )
        assertEquals(
            "After successful recovery, readvertise decision must be READVERTISE_FULL",
            CapabilityReadvertiseContract.ReadvertiseAction.READVERTISE_FULL,
            decision.action
        )
    }

    @Test
    fun `READY with active inference allows full capability set including inference`() {
        val allowed = RecoveryCapabilityAlignmentGuard.computeAllowedCapabilities(
            lifecycleState = FormalParticipantLifecycleState.READY,
            inferenceAvailable = true
        )
        assertTrue(
            "READY + inference must include local_model_inference",
            CapabilityHonestyGuard.CAPABILITY_LOCAL_MODEL_INFERENCE in allowed
        )
        assertTrue(
            "READY + inference must include all base capabilities",
            allowed.containsAll(CapabilityHonestyGuard.BASE_CAPABILITIES)
        )
    }

    @Test
    fun `advertising full capability in READY with inference is aligned`() {
        val fullSet = CapabilityHonestyGuard.BASE_CAPABILITIES +
            CapabilityHonestyGuard.CAPABILITY_LOCAL_MODEL_INFERENCE
        val result = RecoveryCapabilityAlignmentGuard.checkAlignment(
            lifecycleState = FormalParticipantLifecycleState.READY,
            advertisedCapabilities = fullSet,
            inferenceAvailable = true
        )
        assertTrue(
            "Full capability set is aligned in READY state with inference",
            result.isAligned
        )
        assertTrue("No violations expected", result.violations.isEmpty())
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. UNAVAILABLE MODEL BLOCKS CAPABILITY CLAIM
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `STARTING state blocks all capability advertisement`() {
        val formal = FormalParticipantLifecycleState.STARTING
        assertTrue(
            "STARTING state must block all capability advertisement",
            RecoveryCapabilityAlignmentGuard.isAdvertisementBlocked(formal)
        )
    }

    @Test
    fun `RECOVERING state blocks all capability advertisement`() {
        val formal = FormalParticipantLifecycleState.RECOVERING
        assertTrue(
            "RECOVERING state must block all capability advertisement",
            RecoveryCapabilityAlignmentGuard.isAdvertisementBlocked(formal)
        )
    }

    @Test
    fun `UNAVAILABLE_FAILED state blocks all capability advertisement`() {
        val formal = FormalParticipantLifecycleState.UNAVAILABLE_FAILED
        assertTrue(
            "UNAVAILABLE_FAILED state must block all capability advertisement",
            RecoveryCapabilityAlignmentGuard.isAdvertisementBlocked(formal)
        )
    }

    @Test
    fun `advertising any capability while RECOVERING produces violation`() = runBlocking {
        planner.warmupSucceeds = true
        grounding.warmupSucceeds = true
        manager.start()

        planner.simulateCrash()
        planner.warmupSucceeds = false

        val capturedStates = mutableListOf<LocalInferenceRuntimeManager.ManagerState>()
        val collectJob = launch(Dispatchers.Unconfined) {
            manager.state.collect { capturedStates.add(it) }
        }

        manager.recoverIfUnhealthy()
        collectJob.cancel()

        val recoveringStates = capturedStates
            .filter { it is LocalInferenceRuntimeManager.ManagerState.Recovering }
        assertTrue("Recovering state must have been observed", recoveringStates.isNotEmpty())

        val formal = FormalParticipantLifecycleState.fromManagerState(
            LocalInferenceRuntimeManager.ManagerState.Recovering
        )
        val result = RecoveryCapabilityAlignmentGuard.checkAlignment(
            lifecycleState = formal,
            advertisedCapabilities = setOf("autonomous_goal_execution"),
            inferenceAvailable = false
        )
        assertFalse(
            "Advertising any capability while RECOVERING must be a violation",
            result.isAligned
        )
    }

    @Test
    fun `model unavailable state maps to RECOVERING formal state`() {
        val unavailableState = LocalInferenceRuntimeManager.ManagerState.Unavailable("crash detected")
        val formal = FormalParticipantLifecycleState.fromManagerState(unavailableState)
        assertEquals(
            "Unavailable ManagerState must map to RECOVERING formal state",
            FormalParticipantLifecycleState.RECOVERING,
            formal
        )
    }

    @Test
    fun `model unavailable blocks all capability claims`() {
        val formal = FormalParticipantLifecycleState.fromManagerState(
            LocalInferenceRuntimeManager.ManagerState.Unavailable("crash")
        )
        val allowed = RecoveryCapabilityAlignmentGuard.computeAllowedCapabilities(
            lifecycleState = formal,
            inferenceAvailable = false
        )
        assertTrue(
            "Unavailable model state must produce empty allowed capability set",
            allowed.isEmpty()
        )
    }

    @Test
    fun `FailedStartup blocks all capability claims`() {
        val formal = FormalParticipantLifecycleState.fromManagerState(
            LocalInferenceRuntimeManager.ManagerState.FailedStartup(
                "model missing",
                RuntimeStartResult.StartStage.MODEL_FILES
            )
        )
        assertTrue(
            "FailedStartup must map to a state that blocks capability advertisement",
            RecoveryCapabilityAlignmentGuard.isAdvertisementBlocked(formal)
        )
    }

    @Test
    fun `SUPPRESS_UNTIL_STABLE readvertise action for RECOVERING`() {
        val decision = CapabilityReadvertiseContract.decide(
            lifecycleState = FormalParticipantLifecycleState.RECOVERING,
            inferenceAvailable = true
        )
        assertEquals(
            "RECOVERING must produce SUPPRESS_UNTIL_STABLE readvertise action",
            CapabilityReadvertiseContract.ReadvertiseAction.SUPPRESS_UNTIL_STABLE,
            decision.action
        )
        assertTrue(
            "RECOVERING readvertise decision must have empty allowed capabilities",
            decision.allowedCapabilities.isEmpty()
        )
    }

    @Test
    fun `BLOCK_READVERTISE action for UNAVAILABLE_FAILED`() {
        val decision = CapabilityReadvertiseContract.decide(
            lifecycleState = FormalParticipantLifecycleState.UNAVAILABLE_FAILED,
            inferenceAvailable = false
        )
        assertEquals(
            "UNAVAILABLE_FAILED must produce BLOCK_READVERTISE readvertise action",
            CapabilityReadvertiseContract.ReadvertiseAction.BLOCK_READVERTISE,
            decision.action
        )
        assertFalse(
            "UNAVAILABLE_FAILED readvertise decision must not permit advertisement",
            decision.advertisementPermitted
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. RE-REGISTER AFTER RECOVERY CONSISTENCY
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `recovering to READY requires re-registration`() {
        val req = RecoveryCapabilityAlignmentGuard.requiresPostRecoveryReRegistration(
            previousState = FormalParticipantLifecycleState.RECOVERING,
            newState = FormalParticipantLifecycleState.READY,
            inferenceAvailable = true
        )
        assertNotNull(
            "Transitioning RECOVERING → READY must require re-registration",
            req
        )
    }

    @Test
    fun `unavailable_failed to READY requires re-registration`() {
        val req = RecoveryCapabilityAlignmentGuard.requiresPostRecoveryReRegistration(
            previousState = FormalParticipantLifecycleState.UNAVAILABLE_FAILED,
            newState = FormalParticipantLifecycleState.READY,
            inferenceAvailable = false
        )
        assertNotNull(
            "Transitioning UNAVAILABLE_FAILED → READY must require re-registration",
            req
        )
    }

    @Test
    fun `starting to READY requires re-registration`() {
        val req = RecoveryCapabilityAlignmentGuard.requiresPostRecoveryReRegistration(
            previousState = FormalParticipantLifecycleState.STARTING,
            newState = FormalParticipantLifecycleState.READY,
            inferenceAvailable = true
        )
        assertNotNull(
            "Transitioning STARTING → READY must require re-registration",
            req
        )
    }

    @Test
    fun `READY to READY does not require re-registration`() {
        val req = RecoveryCapabilityAlignmentGuard.requiresPostRecoveryReRegistration(
            previousState = FormalParticipantLifecycleState.READY,
            newState = FormalParticipantLifecycleState.READY,
            inferenceAvailable = true
        )
        assertNull(
            "READY → READY must NOT require re-registration",
            req
        )
    }

    @Test
    fun `re-registration requirement after recovery includes correct capability set with inference`() {
        val req = RecoveryCapabilityAlignmentGuard.requiresPostRecoveryReRegistration(
            previousState = FormalParticipantLifecycleState.RECOVERING,
            newState = FormalParticipantLifecycleState.READY,
            inferenceAvailable = true
        )
        assertNotNull(req)
        assertTrue(
            "Re-registration capability set must include inference when available",
            CapabilityHonestyGuard.CAPABILITY_LOCAL_MODEL_INFERENCE in req!!.requiredCapabilities
        )
    }

    @Test
    fun `re-registration requirement after degraded recovery includes base only`() {
        val req = RecoveryCapabilityAlignmentGuard.requiresPostRecoveryReRegistration(
            previousState = FormalParticipantLifecycleState.RECOVERING,
            newState = FormalParticipantLifecycleState.DEGRADED,
            inferenceAvailable = false
        )
        assertNotNull(req)
        assertEquals(
            "Re-registration capability set must be base only when inference is unavailable",
            CapabilityHonestyGuard.BASE_CAPABILITIES,
            req!!.requiredCapabilities
        )
        assertFalse(
            "Re-registration capability set must NOT include inference when degraded",
            CapabilityHonestyGuard.CAPABILITY_LOCAL_MODEL_INFERENCE in req.requiredCapabilities
        )
    }

    @Test
    fun `heartbeat must match re-advertised capabilities — consistent case`() {
        val readvertised = CapabilityHonestyGuard.BASE_CAPABILITIES +
            CapabilityHonestyGuard.CAPABILITY_LOCAL_MODEL_INFERENCE
        val heartbeat = CapabilityHonestyGuard.BASE_CAPABILITIES +
            CapabilityHonestyGuard.CAPABILITY_LOCAL_MODEL_INFERENCE
        assertTrue(
            "Heartbeat capability set must match re-advertised set",
            CapabilityReadvertiseContract.heartbeatMustMatchReadvertise(
                readvertisedCapabilities = readvertised,
                heartbeatCapabilities = heartbeat
            )
        )
    }

    @Test
    fun `heartbeat capability mismatch after re-advertise is detected`() {
        val readvertised = CapabilityHonestyGuard.BASE_CAPABILITIES
        val staleFull = CapabilityHonestyGuard.BASE_CAPABILITIES +
            CapabilityHonestyGuard.CAPABILITY_LOCAL_MODEL_INFERENCE
        assertFalse(
            "Heartbeat carrying full capabilities after base-only re-advertisement " +
                "must be detected as inconsistent",
            CapabilityReadvertiseContract.heartbeatMustMatchReadvertise(
                readvertisedCapabilities = readvertised,
                heartbeatCapabilities = staleFull
            )
        )
    }

    @Test
    fun `recovery to READY requires re-registration via CapabilityReadvertiseContract`() {
        assertTrue(
            "RECOVERING → READY must require re-registration per CapabilityReadvertiseContract",
            CapabilityReadvertiseContract.requiresReRegistration(
                previousState = FormalParticipantLifecycleState.RECOVERING,
                newState = FormalParticipantLifecycleState.READY
            )
        )
    }

    @Test
    fun `recovery to DEGRADED requires re-registration`() {
        assertTrue(
            "RECOVERING → DEGRADED must require re-registration",
            CapabilityReadvertiseContract.requiresReRegistration(
                previousState = FormalParticipantLifecycleState.RECOVERING,
                newState = FormalParticipantLifecycleState.DEGRADED
            )
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // FormalParticipantLifecycleState — state derivation completeness
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `all ManagerState variants map to a FormalParticipantLifecycleState`() {
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
            val formal = FormalParticipantLifecycleState.fromManagerState(state)
            assertNotNull(
                "Every ManagerState must map to a non-null FormalParticipantLifecycleState: $state",
                formal
            )
        }
    }

    @Test
    fun `Starting and PartialReady both map to STARTING formal state`() {
        assertEquals(
            FormalParticipantLifecycleState.STARTING,
            FormalParticipantLifecycleState.fromManagerState(
                LocalInferenceRuntimeManager.ManagerState.Starting
            )
        )
        assertEquals(
            FormalParticipantLifecycleState.STARTING,
            FormalParticipantLifecycleState.fromManagerState(
                LocalInferenceRuntimeManager.ManagerState.PartialReady(
                    listOf("planner"), listOf("grounding")
                )
            )
        )
    }

    @Test
    fun `Recovering and Unavailable both map to RECOVERING formal state`() {
        assertEquals(
            FormalParticipantLifecycleState.RECOVERING,
            FormalParticipantLifecycleState.fromManagerState(
                LocalInferenceRuntimeManager.ManagerState.Recovering
            )
        )
        assertEquals(
            FormalParticipantLifecycleState.RECOVERING,
            FormalParticipantLifecycleState.fromManagerState(
                LocalInferenceRuntimeManager.ManagerState.Unavailable("crash")
            )
        )
    }

    @Test
    fun `all ParticipantHealthState values map to a FormalParticipantLifecycleState`() {
        ParticipantHealthState.entries.forEach { healthState ->
            val formal = FormalParticipantLifecycleState.fromHealthState(healthState)
            assertNotNull(
                "Every ParticipantHealthState must map to a formal state: $healthState",
                formal
            )
        }
    }

    @Test
    fun `STARTING ParticipantHealthState maps to STARTING formal state`() {
        assertEquals(
            FormalParticipantLifecycleState.STARTING,
            FormalParticipantLifecycleState.fromHealthState(ParticipantHealthState.STARTING)
        )
    }

    @Test
    fun `HEALTHY ParticipantHealthState maps to READY formal state`() {
        assertEquals(
            FormalParticipantLifecycleState.READY,
            FormalParticipantLifecycleState.fromHealthState(ParticipantHealthState.HEALTHY)
        )
    }

    @Test
    fun `FormalParticipantLifecycleState wire values are distinct`() {
        val wireValues = FormalParticipantLifecycleState.ALL_WIRE_VALUES
        assertEquals(
            "All formal lifecycle wire values must be distinct",
            FormalParticipantLifecycleState.entries.size,
            wireValues.size
        )
    }

    @Test
    fun `fromWireValue round-trips all formal states`() {
        FormalParticipantLifecycleState.entries.forEach { state ->
            assertEquals(
                "fromWireValue must round-trip ${state.wireValue}",
                state,
                FormalParticipantLifecycleState.fromWireValue(state.wireValue)
            )
        }
    }

    @Test
    fun `fromWireValue returns null for unknown wire value`() {
        assertNull(
            "fromWireValue must return null for unknown wire value",
            FormalParticipantLifecycleState.fromWireValue("totally_unknown_state")
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // ParticipantHealthState — STARTING state
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `ParticipantHealthState STARTING has wireValue starting`() {
        assertEquals("starting", ParticipantHealthState.STARTING.wireValue)
    }

    @Test
    fun `ParticipantHealthState STARTING blocks task acceptance`() {
        assertFalse(
            "STARTING state must not accept tasks",
            ParticipantHealthState.isAcceptingTasks(ParticipantHealthState.STARTING)
        )
    }

    @Test
    fun `ParticipantHealthState STARTING blocks capability advertisement`() {
        assertTrue(
            "STARTING state must block capability advertisement",
            ParticipantHealthState.isCapabilityAdvertisementBlocked(ParticipantHealthState.STARTING)
        )
    }

    @Test
    fun `ParticipantHealthState ALL_WIRE_VALUES includes starting`() {
        assertTrue(
            "ALL_WIRE_VALUES must include 'starting'",
            "starting" in ParticipantHealthState.ALL_WIRE_VALUES
        )
    }

    @Test
    fun `ParticipantHealthState fromValue parses starting`() {
        assertEquals(
            "fromValue must parse 'starting' to STARTING",
            ParticipantHealthState.STARTING,
            ParticipantHealthState.fromValue("starting")
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // CapabilityReadvertiseContract — ReadvertiseAction coverage
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `ReadvertiseAction count is READVERTISE_ACTION_COUNT`() {
        assertEquals(
            "ReadvertiseAction must have READVERTISE_ACTION_COUNT variants",
            CapabilityReadvertiseContract.READVERTISE_ACTION_COUNT,
            CapabilityReadvertiseContract.ReadvertiseAction.entries.size
        )
    }

    @Test
    fun `READVERTISE_FULL permits advertisement`() {
        val decision = CapabilityReadvertiseContract.decide(
            lifecycleState = FormalParticipantLifecycleState.READY,
            inferenceAvailable = true
        )
        assertEquals(
            CapabilityReadvertiseContract.ReadvertiseAction.READVERTISE_FULL,
            decision.action
        )
        assertTrue(decision.advertisementPermitted)
    }

    @Test
    fun `READVERTISE_BASE_ONLY permits advertisement`() {
        val decision = CapabilityReadvertiseContract.decide(
            lifecycleState = FormalParticipantLifecycleState.READY,
            inferenceAvailable = false
        )
        assertEquals(
            CapabilityReadvertiseContract.ReadvertiseAction.READVERTISE_BASE_ONLY,
            decision.action
        )
        assertTrue(decision.advertisementPermitted)
    }

    @Test
    fun `SUPPRESS_UNTIL_STABLE does not permit advertisement`() {
        val decision = CapabilityReadvertiseContract.decide(
            lifecycleState = FormalParticipantLifecycleState.STARTING,
            inferenceAvailable = false
        )
        assertEquals(
            CapabilityReadvertiseContract.ReadvertiseAction.SUPPRESS_UNTIL_STABLE,
            decision.action
        )
        assertFalse(decision.advertisementPermitted)
    }

    // ════════════════════════════════════════════════════════════════════════
    // StabilizationBaseline — new entries registered
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `StabilizationBaseline registers FormalParticipantLifecycleState`() {
        assertTrue(
            "StabilizationBaseline must register formal-participant-lifecycle-state",
            StabilizationBaseline.isRegistered("formal-participant-lifecycle-state")
        )
    }

    @Test
    fun `StabilizationBaseline registers CapabilityReadvertiseContract`() {
        assertTrue(
            "StabilizationBaseline must register capability-readvertise-contract",
            StabilizationBaseline.isRegistered("capability-readvertise-contract")
        )
    }

    @Test
    fun `StabilizationBaseline registers RecoveryCapabilityAlignmentGuard`() {
        assertTrue(
            "StabilizationBaseline must register recovery-capability-alignment-guard",
            StabilizationBaseline.isRegistered("recovery-capability-alignment-guard")
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // Stub helpers
    // ════════════════════════════════════════════════════════════════════════

    private class StubPlannerService : com.ufo.galaxy.inference.LocalPlannerService {
        var warmupSucceeds = true
        private var loaded = false

        fun simulateCrash() { loaded = false }

        override fun loadModel(): Boolean { loaded = warmupSucceeds; return loaded }
        override fun unloadModel() { loaded = false }
        override fun isModelLoaded(): Boolean = loaded

        override fun warmupWithResult(): com.ufo.galaxy.inference.WarmupResult {
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

        override fun loadModel(): Boolean { loaded = warmupSucceeds; return loaded }
        override fun unloadModel() { loaded = false }
        override fun isModelLoaded(): Boolean = loaded

        override fun warmupWithResult(): com.ufo.galaxy.inference.WarmupResult {
            loaded = warmupSucceeds
            return if (warmupSucceeds) com.ufo.galaxy.inference.WarmupResult.success()
            else com.ufo.galaxy.inference.WarmupResult.failure(
                com.ufo.galaxy.inference.WarmupResult.WarmupStage.HEALTH_CHECK, "stub grounding failed"
            )
        }

        override fun groundElement(
            screenshotBase64: String,
            elementDescription: String
        ) = com.ufo.galaxy.inference.LocalGroundingService.GroundingResult(null, null, "stub")
    }
}

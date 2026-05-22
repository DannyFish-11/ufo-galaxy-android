package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.loop.ExecutorBridge
import com.ufo.galaxy.loop.LocalPlanner
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.network.GalaxyWebSocketClient
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-119 — Android Cross-Repo Recovery State Routing Contract tests.
 *
 * Validates:
 * 1. Every [AndroidContinuityRecoveryStateModel.RecoveryPhase] maps to the expected
 *    [AndroidCrossRepoRecoveryStateRoutingContract.V2RoutingCategory].
 * 2. RECOVERED_INFLIGHT is routed as advisory-only with canonical closure blocked.
 * 3. REQUIRES_RECONCILIATION always sets requiresV2Action=true.
 * 4. STALE_RECOVERY_ARTIFACT triggers stale artifact rejection with closure blocked.
 * 5. Wire map contains all expected keys with correct values.
 * 6. [AndroidCrossRepoRecoveryStateRoutingContract.ROUTING_CONTRACT_INVARIANTS] are non-empty
 *    and cover the key INV-ROUTING constraints.
 * 7. All routing decisions stay aligned with V2_CONSUMPTION_PATH_MAP (cross-repo contract).
 * 8. [ReconciliationSignal.runtimeTruthSnapshot] includes routing metadata in its payload.
 * 9. [RuntimeController.publishRuntimeTruthSnapshot] emits RUNTIME_TRUTH_SNAPSHOT with
 *    routing category in the signal payload.
 * 10. StabilizationBaseline registers android-cross-repo-recovery-state-routing-contract.
 */
class Pr119AndroidCrossRepoRecoveryStateRoutingContractTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ─── fakes ────────────────────────────────────────────────────────────────

    private class FakePlannerService : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep("tap", "tap"))
            )
        override fun replan(
            goal: String,
            constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String,
            screenshotBase64: String?
        ) = LocalPlannerService.PlanResult(steps = emptyList(), error = "no replan")
    }

    private class FakeGrounder : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(x = 540, y = 1170, confidence = 0.9f)
    }

    private class FakeAccessibilityExecutor : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
    }

    private class FakeScreenshotProvider : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        override fun screenWidth() = 1080
        override fun screenHeight() = 2340
    }

    private fun buildLoopController(): LoopController {
        val modelsDir = tmpFolder.newFolder("models-${System.nanoTime()}")
        return LoopController(
            localPlanner = LocalPlanner(FakePlannerService()),
            executorBridge = ExecutorBridge(
                groundingService = FakeGrounder(),
                accessibilityExecutor = FakeAccessibilityExecutor(),
                imageScaler = NoOpImageScaler()
            ),
            screenshotProvider = FakeScreenshotProvider(),
            modelAssetManager = ModelAssetManager(modelsDir),
            modelDownloader = ModelDownloader(modelsDir)
        )
    }

    private fun buildController(
        settings: InMemoryAppSettings = InMemoryAppSettings()
    ): RuntimeController {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        return RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L,
            hostDescriptor = RuntimeHostDescriptor(
                hostId = "host-119", deviceId = "Pixel-119", deviceRole = "phone",
                formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
            )
        )
    }

    // ── 1. routeRecoveryPhase: each phase maps to the expected V2RoutingCategory ──────────

    @Test
    fun `RESUMED_CLEANLY maps to NO_RECOVERY_ACTION_REQUIRED`() {
        val decision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RESUMED_CLEANLY
        )
        assertEquals(
            AndroidCrossRepoRecoveryStateRoutingContract.V2RoutingCategory.NO_RECOVERY_ACTION_REQUIRED,
            decision.v2RoutingCategory
        )
        assertFalse("RESUMED_CLEANLY must not require V2 action", decision.requiresV2Action)
        assertTrue("RESUMED_CLEANLY evidence is advisory only", decision.isAdvisoryOnly)
        assertFalse("RESUMED_CLEANLY must not block canonical closure", decision.canonicalClosureBlocked)
    }

    @Test
    fun `RECOVERING maps to PENDING_RECONNECT_VERDICT`() {
        val decision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERING
        )
        assertEquals(
            AndroidCrossRepoRecoveryStateRoutingContract.V2RoutingCategory.PENDING_RECONNECT_VERDICT,
            decision.v2RoutingCategory
        )
        assertTrue("RECOVERING must require V2 action", decision.requiresV2Action)
        assertFalse("RECOVERING is not advisory only", decision.isAdvisoryOnly)
        assertTrue("RECOVERING must block canonical closure pending reconnect verdict", decision.canonicalClosureBlocked)
    }

    @Test
    fun `RECOVERED_INFLIGHT maps to ADVISORY_INFLIGHT_EVIDENCE`() {
        val decision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERED_INFLIGHT
        )
        assertEquals(
            AndroidCrossRepoRecoveryStateRoutingContract.V2RoutingCategory.ADVISORY_INFLIGHT_EVIDENCE,
            decision.v2RoutingCategory
        )
        assertFalse("RECOVERED_INFLIGHT must not require V2 action by itself", decision.requiresV2Action)
        assertTrue("RECOVERED_INFLIGHT is advisory only (INV-ROUTING-01)", decision.isAdvisoryOnly)
        assertTrue(
            "RECOVERED_INFLIGHT must block canonical closure pending V2 adjudication (INV-ROUTING-01)",
            decision.canonicalClosureBlocked
        )
    }

    @Test
    fun `LOST_INFLIGHT maps to TASK_CLOSURE_OR_RECONCILIATION_REQUIRED`() {
        val decision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.LOST_INFLIGHT
        )
        assertEquals(
            AndroidCrossRepoRecoveryStateRoutingContract.V2RoutingCategory.TASK_CLOSURE_OR_RECONCILIATION_REQUIRED,
            decision.v2RoutingCategory
        )
        assertTrue("LOST_INFLIGHT must require V2 action (INV-ROUTING-06)", decision.requiresV2Action)
        assertFalse("LOST_INFLIGHT is not advisory only", decision.isAdvisoryOnly)
        assertFalse(
            "LOST_INFLIGHT does not block canonical closure — V2 may close the task",
            decision.canonicalClosureBlocked
        )
    }

    @Test
    fun `REQUIRES_RECONCILIATION maps to CANONICAL_RECONCILIATION_PASS`() {
        val decision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.REQUIRES_RECONCILIATION
        )
        assertEquals(
            AndroidCrossRepoRecoveryStateRoutingContract.V2RoutingCategory.CANONICAL_RECONCILIATION_PASS,
            decision.v2RoutingCategory
        )
        assertTrue("REQUIRES_RECONCILIATION must require V2 action (INV-ROUTING-02)", decision.requiresV2Action)
        assertFalse("REQUIRES_RECONCILIATION is not advisory only", decision.isAdvisoryOnly)
        assertTrue(
            "REQUIRES_RECONCILIATION must block canonical closure pending reconciliation pass (INV-ROUTING-02)",
            decision.canonicalClosureBlocked
        )
    }

    @Test
    fun `STALE_RECOVERY_ARTIFACT maps to STALE_ARTIFACT_REJECTION`() {
        val decision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.STALE_RECOVERY_ARTIFACT
        )
        assertEquals(
            AndroidCrossRepoRecoveryStateRoutingContract.V2RoutingCategory.STALE_ARTIFACT_REJECTION,
            decision.v2RoutingCategory
        )
        assertTrue("STALE_RECOVERY_ARTIFACT must require V2 rejection action (INV-ROUTING-03)", decision.requiresV2Action)
        assertFalse("STALE_RECOVERY_ARTIFACT is not advisory once identified as stale", decision.isAdvisoryOnly)
        assertTrue(
            "STALE_RECOVERY_ARTIFACT must block canonical closure (INV-ROUTING-03)",
            decision.canonicalClosureBlocked
        )
    }

    @Test
    fun `RECOVERY_FAILED maps to TERMINAL_RECONNECT_FAILURE`() {
        val decision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERY_FAILED
        )
        assertEquals(
            AndroidCrossRepoRecoveryStateRoutingContract.V2RoutingCategory.TERMINAL_RECONNECT_FAILURE,
            decision.v2RoutingCategory
        )
        assertTrue("RECOVERY_FAILED must require V2 action", decision.requiresV2Action)
        assertFalse("RECOVERY_FAILED is not advisory only", decision.isAdvisoryOnly)
        assertTrue("RECOVERY_FAILED must block canonical closure", decision.canonicalClosureBlocked)
    }

    // ── 2. All phases have a routing decision ──────────────────────────────────────────────

    @Test
    fun `all RecoveryPhase values have an explicit routing decision`() {
        AndroidContinuityRecoveryStateModel.RecoveryPhase.entries.forEach { phase ->
            val decision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(phase)
            assertEquals(
                "routing decision phase must match the input phase for $phase",
                phase,
                decision.phase
            )
            assertTrue(
                "v2HandlingPath must be non-blank for $phase",
                decision.v2HandlingPath.isNotBlank()
            )
        }
    }

    // ── 3. Advisory-only implies canonical closure blocked ─────────────────────────────────

    @Test
    fun `advisory-only routing decisions do not directly allow canonical closure for recovery-bearing phases`() {
        // Phases that are marked advisory-only must also have canonicalClosureBlocked=true
        // when they carry recovery evidence (RECOVERED_INFLIGHT), preventing V2 from
        // treating advisory evidence as canonical closure without adjudication.
        val advisoryDecision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERED_INFLIGHT
        )
        assertTrue(
            "RECOVERED_INFLIGHT is advisory only — V2 must adjudicate independently",
            advisoryDecision.isAdvisoryOnly
        )
        assertTrue(
            "RECOVERED_INFLIGHT advisory evidence must block canonical closure (INV-ROUTING-01)",
            advisoryDecision.canonicalClosureBlocked
        )
    }

    // ── 4. Wire map correctness ────────────────────────────────────────────────────────────

    @Test
    fun `toWireMap returns all five expected keys with correct values`() {
        val decision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.REQUIRES_RECONCILIATION
        )
        val wireMap = AndroidCrossRepoRecoveryStateRoutingContract.toWireMap(decision)

        assertEquals(
            decision.v2RoutingCategory.wireValue,
            wireMap[AndroidCrossRepoRecoveryStateRoutingContract.KEY_V2_ROUTING_CATEGORY]
        )
        assertEquals(
            decision.requiresV2Action.toString(),
            wireMap[AndroidCrossRepoRecoveryStateRoutingContract.KEY_ROUTING_REQUIRES_V2_ACTION]
        )
        assertEquals(
            decision.isAdvisoryOnly.toString(),
            wireMap[AndroidCrossRepoRecoveryStateRoutingContract.KEY_ROUTING_IS_ADVISORY_ONLY]
        )
        assertEquals(
            decision.canonicalClosureBlocked.toString(),
            wireMap[AndroidCrossRepoRecoveryStateRoutingContract.KEY_ROUTING_CANONICAL_CLOSURE_BLOCKED]
        )
        assertEquals(
            AndroidCrossRepoRecoveryStateRoutingContract.SCHEMA_VERSION,
            wireMap[AndroidCrossRepoRecoveryStateRoutingContract.KEY_ROUTING_SCHEMA_VERSION]
        )
    }

    @Test
    fun `toWireMap STALE_RECOVERY_ARTIFACT has stale_artifact_rejection routing category`() {
        val decision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.STALE_RECOVERY_ARTIFACT
        )
        val wireMap = AndroidCrossRepoRecoveryStateRoutingContract.toWireMap(decision)
        assertEquals(
            "stale_artifact_rejection",
            wireMap[AndroidCrossRepoRecoveryStateRoutingContract.KEY_V2_ROUTING_CATEGORY]
        )
        assertEquals(
            "true",
            wireMap[AndroidCrossRepoRecoveryStateRoutingContract.KEY_ROUTING_REQUIRES_V2_ACTION]
        )
        assertEquals(
            "true",
            wireMap[AndroidCrossRepoRecoveryStateRoutingContract.KEY_ROUTING_CANONICAL_CLOSURE_BLOCKED]
        )
    }

    // ── 5. ROUTING_CONTRACT_INVARIANTS coverage ────────────────────────────────────────────

    @Test
    fun `ROUTING_CONTRACT_INVARIANTS is non-empty`() {
        assertTrue(
            "ROUTING_CONTRACT_INVARIANTS must be non-empty",
            AndroidCrossRepoRecoveryStateRoutingContract.ROUTING_CONTRACT_INVARIANTS.isNotEmpty()
        )
    }

    @Test
    fun `ROUTING_CONTRACT_INVARIANTS covers RECOVERED_INFLIGHT advisory boundary (INV-ROUTING-01)`() {
        val hasAdvisoryInvariant =
            AndroidCrossRepoRecoveryStateRoutingContract.ROUTING_CONTRACT_INVARIANTS
                .any { it.contains("INV-ROUTING-01") && it.contains("RECOVERED_INFLIGHT") }
        assertTrue(
            "Must have INV-ROUTING-01 declaring RECOVERED_INFLIGHT advisory boundary",
            hasAdvisoryInvariant
        )
    }

    @Test
    fun `ROUTING_CONTRACT_INVARIANTS covers REQUIRES_RECONCILIATION V2 action requirement (INV-ROUTING-02)`() {
        val hasReconciliationInvariant =
            AndroidCrossRepoRecoveryStateRoutingContract.ROUTING_CONTRACT_INVARIANTS
                .any { it.contains("INV-ROUTING-02") && it.contains("REQUIRES_RECONCILIATION") }
        assertTrue(
            "Must have INV-ROUTING-02 requiring V2 reconciliation pass for REQUIRES_RECONCILIATION",
            hasReconciliationInvariant
        )
    }

    @Test
    fun `ROUTING_CONTRACT_INVARIANTS covers STALE_RECOVERY_ARTIFACT rejection (INV-ROUTING-03)`() {
        val hasStaleInvariant =
            AndroidCrossRepoRecoveryStateRoutingContract.ROUTING_CONTRACT_INVARIANTS
                .any { it.contains("INV-ROUTING-03") && it.contains("STALE_RECOVERY_ARTIFACT") }
        assertTrue(
            "Must have INV-ROUTING-03 declaring STALE_RECOVERY_ARTIFACT rejection invariant",
            hasStaleInvariant
        )
    }

    @Test
    fun `ROUTING_CONTRACT_INVARIANTS covers routing metadata emission requirement (INV-ROUTING-05)`() {
        val hasEmissionInvariant =
            AndroidCrossRepoRecoveryStateRoutingContract.ROUTING_CONTRACT_INVARIANTS
                .any { it.contains("INV-ROUTING-05") && it.contains("RUNTIME_TRUTH_SNAPSHOT") }
        assertTrue(
            "Must have INV-ROUTING-05 requiring routing metadata in RUNTIME_TRUTH_SNAPSHOT payload",
            hasEmissionInvariant
        )
    }

    @Test
    fun `ROUTING_CONTRACT_INVARIANTS covers LOST_INFLIGHT V2 action requirement (INV-ROUTING-06)`() {
        val hasLostInflightInvariant =
            AndroidCrossRepoRecoveryStateRoutingContract.ROUTING_CONTRACT_INVARIANTS
                .any { it.contains("INV-ROUTING-06") && it.contains("LOST_INFLIGHT") }
        assertTrue(
            "Must have INV-ROUTING-06 requiring V2 action for LOST_INFLIGHT",
            hasLostInflightInvariant
        )
    }

    // ── 6. V2_ROUTING_INTENT_MAP covers all phases ────────────────────────────────────────

    @Test
    fun `V2_ROUTING_INTENT_MAP covers all RecoveryPhase values`() {
        AndroidContinuityRecoveryStateModel.RecoveryPhase.entries.forEach { phase ->
            val decision = AndroidCrossRepoRecoveryStateRoutingContract.V2_ROUTING_INTENT_MAP[phase.wireValue]
            assertNotNull(
                "V2_ROUTING_INTENT_MAP must contain entry for phase ${phase.wireValue}",
                decision
            )
            assertEquals(
                "V2_ROUTING_INTENT_MAP entry for ${phase.wireValue} must reference correct phase",
                phase,
                decision!!.phase
            )
        }
    }

    @Test
    fun `V2_ROUTING_INTENT_MAP entries are consistent with routeRecoveryPhase`() {
        AndroidContinuityRecoveryStateModel.RecoveryPhase.entries.forEach { phase ->
            val mapDecision = AndroidCrossRepoRecoveryStateRoutingContract.V2_ROUTING_INTENT_MAP[phase.wireValue]
            val directDecision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(phase)
            assertNotNull(mapDecision)
            assertEquals(
                "V2_ROUTING_INTENT_MAP and routeRecoveryPhase must agree on category for $phase",
                directDecision.v2RoutingCategory,
                mapDecision!!.v2RoutingCategory
            )
            assertEquals(
                "V2_ROUTING_INTENT_MAP and routeRecoveryPhase must agree on requiresV2Action for $phase",
                directDecision.requiresV2Action,
                mapDecision.requiresV2Action
            )
        }
    }

    // ── 7. Cross-repo alignment with V2_CONSUMPTION_PATH_MAP ──────────────────────────────

    @Test
    fun `v2HandlingPath in every routing decision matches V2_CONSUMPTION_PATH_MAP`() {
        AndroidContinuityRecoveryStateModel.RecoveryPhase.entries.forEach { phase ->
            val decision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(phase)
            val expectedPath = AndroidContinuityRecoveryStateModel.V2_CONSUMPTION_PATH_MAP[phase.wireValue]
            assertNotNull(
                "V2_CONSUMPTION_PATH_MAP must contain entry for ${phase.wireValue}",
                expectedPath
            )
            assertEquals(
                "v2HandlingPath for $phase must match V2_CONSUMPTION_PATH_MAP (INV-ROUTING-08)",
                expectedPath,
                decision.v2HandlingPath
            )
        }
    }

    // ── 8. ReconciliationSignal.runtimeTruthSnapshot includes routing metadata ────────────

    @Test
    fun `runtimeTruthSnapshot with REQUIRES_RECONCILIATION routing decision includes routing keys in payload`() {
        val phase = AndroidContinuityRecoveryStateModel.RecoveryPhase.REQUIRES_RECONCILIATION
        val routingDecision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(phase)

        val truth = AndroidParticipantRuntimeTruth.from(
            descriptor = RuntimeHostDescriptor(
                hostId = "host-119-signal", deviceId = "Pixel-119", deviceRole = "phone",
                formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
            ),
            sessionSnapshot = null,
            healthState = ParticipantHealthState.UNKNOWN,
            readinessState = ParticipantReadinessState.UNKNOWN,
            activeTaskId = "task-119",
            activeTaskStatus = null,
            inflightContinuityState = phase.wireValue,
            inflightContinuityTaskId = "task-119",
            inflightContinuitySource = "process_recreated",
            inflightContinuityObservedAtMs = null,
            carrierForegroundVisible = false,
            authoritativeParticipationState = "active",
            authoritativeParticipationTransitionSequence = 1L,
            authoritativeParticipationTransitionTrigger = "init",
            authoritativeParticipationTransitionHistory = emptyList(),
            reconciliationEpoch = 1
        )

        val signal = ReconciliationSignal.runtimeTruthSnapshot(
            truth = truth,
            v2RoutingDecision = routingDecision
        )

        assertEquals(
            "Signal kind must be RUNTIME_TRUTH_SNAPSHOT",
            ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT,
            signal.kind
        )
        assertEquals(
            "Payload must carry routing category",
            AndroidCrossRepoRecoveryStateRoutingContract.V2RoutingCategory.CANONICAL_RECONCILIATION_PASS.wireValue,
            signal.payload[ReconciliationSignal.KEY_V2_ROUTING_CATEGORY]
        )
        assertEquals(
            "Payload must carry requiresV2Action=true for REQUIRES_RECONCILIATION",
            "true",
            signal.payload[ReconciliationSignal.KEY_ROUTING_REQUIRES_V2_ACTION]
        )
        assertEquals(
            "Payload must carry canonicalClosureBlocked=true for REQUIRES_RECONCILIATION",
            "true",
            signal.payload[ReconciliationSignal.KEY_ROUTING_CANONICAL_CLOSURE_BLOCKED]
        )
        assertEquals(
            "Payload must carry isAdvisoryOnly=false for REQUIRES_RECONCILIATION",
            "false",
            signal.payload[ReconciliationSignal.KEY_ROUTING_IS_ADVISORY_ONLY]
        )
        assertEquals(
            "Payload must carry routing schema version",
            AndroidCrossRepoRecoveryStateRoutingContract.SCHEMA_VERSION,
            signal.payload[ReconciliationSignal.KEY_ROUTING_SCHEMA_VERSION]
        )
    }

    @Test
    fun `runtimeTruthSnapshot with RECOVERED_INFLIGHT routing decision marks advisory and closure blocked`() {
        val phase = AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERED_INFLIGHT
        val routingDecision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(phase)

        val truth = AndroidParticipantRuntimeTruth.from(
            descriptor = RuntimeHostDescriptor(
                hostId = "host-119-advisory", deviceId = "Pixel-119", deviceRole = "phone",
                formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
            ),
            sessionSnapshot = null,
            healthState = ParticipantHealthState.UNKNOWN,
            readinessState = ParticipantReadinessState.UNKNOWN,
            activeTaskId = "task-advisory-119",
            activeTaskStatus = null,
            inflightContinuityState = phase.wireValue,
            inflightContinuityTaskId = "task-advisory-119",
            inflightContinuitySource = "reconnect_recovery",
            inflightContinuityObservedAtMs = null,
            carrierForegroundVisible = true,
            authoritativeParticipationState = "active",
            authoritativeParticipationTransitionSequence = 2L,
            authoritativeParticipationTransitionTrigger = "reconnect",
            authoritativeParticipationTransitionHistory = emptyList(),
            reconciliationEpoch = 2
        )

        val signal = ReconciliationSignal.runtimeTruthSnapshot(
            truth = truth,
            v2RoutingDecision = routingDecision
        )

        assertEquals(
            "Advisory-only routing must be reflected in payload",
            "true",
            signal.payload[ReconciliationSignal.KEY_ROUTING_IS_ADVISORY_ONLY]
        )
        assertEquals(
            "Canonical closure must be blocked for advisory evidence (INV-ROUTING-01)",
            "true",
            signal.payload[ReconciliationSignal.KEY_ROUTING_CANONICAL_CLOSURE_BLOCKED]
        )
        assertEquals(
            "Advisory evidence must not require V2 action alone",
            "false",
            signal.payload[ReconciliationSignal.KEY_ROUTING_REQUIRES_V2_ACTION]
        )
        assertEquals(
            "Routing category must be advisory_inflight_evidence",
            AndroidCrossRepoRecoveryStateRoutingContract.V2RoutingCategory.ADVISORY_INFLIGHT_EVIDENCE.wireValue,
            signal.payload[ReconciliationSignal.KEY_V2_ROUTING_CATEGORY]
        )
    }

    @Test
    fun `runtimeTruthSnapshot without routing decision does not include routing keys`() {
        val truth = AndroidParticipantRuntimeTruth.from(
            descriptor = RuntimeHostDescriptor(
                hostId = "host-119-norouting", deviceId = "Pixel-119", deviceRole = "phone",
                formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
            ),
            sessionSnapshot = null,
            healthState = ParticipantHealthState.UNKNOWN,
            readinessState = ParticipantReadinessState.UNKNOWN,
            activeTaskId = null,
            activeTaskStatus = null,
            inflightContinuityState = AndroidContinuityRecoveryStateModel.RecoveryPhase.RESUMED_CLEANLY.wireValue,
            inflightContinuityTaskId = null,
            inflightContinuitySource = "none",
            inflightContinuityObservedAtMs = null,
            carrierForegroundVisible = false,
            authoritativeParticipationState = "active",
            authoritativeParticipationTransitionSequence = 0L,
            authoritativeParticipationTransitionTrigger = "init",
            authoritativeParticipationTransitionHistory = emptyList(),
            reconciliationEpoch = 0
        )

        // No routing decision provided — routing keys must be absent
        val signal = ReconciliationSignal.runtimeTruthSnapshot(truth = truth)

        assertTrue(
            "Without routing decision, KEY_V2_ROUTING_CATEGORY must be absent from payload",
            !signal.payload.containsKey(ReconciliationSignal.KEY_V2_ROUTING_CATEGORY)
        )
    }

    // ── 9. RuntimeController.publishRuntimeTruthSnapshot emits routing metadata ─────────

    @Test
    fun `publishRuntimeTruthSnapshot emits RUNTIME_TRUTH_SNAPSHOT with routing category in payload`() = runBlocking {
        val controller = buildController()
        controller.setActiveForTest()

        val deferred = async {
            withTimeout(500) {
                controller.reconciliationSignals.first {
                    it.kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
                }
            }
        }

        controller.publishRuntimeTruthSnapshot()

        val signal = deferred.await()
        assertNotNull(
            "RUNTIME_TRUTH_SNAPSHOT payload MUST carry routing category (INV-ROUTING-05)",
            signal.payload[ReconciliationSignal.KEY_V2_ROUTING_CATEGORY]
        )
        // Default phase is RESUMED_CLEANLY → NO_RECOVERY_ACTION_REQUIRED
        assertEquals(
            "Default recovery phase routes to no_recovery_action_required",
            AndroidCrossRepoRecoveryStateRoutingContract.V2RoutingCategory.NO_RECOVERY_ACTION_REQUIRED.wireValue,
            signal.payload[ReconciliationSignal.KEY_V2_ROUTING_CATEGORY]
        )
        assertNotNull(
            "RUNTIME_TRUTH_SNAPSHOT payload MUST carry routing schema version",
            signal.payload[ReconciliationSignal.KEY_ROUTING_SCHEMA_VERSION]
        )
    }

    @Test
    fun `publishRuntimeTruthSnapshot emits REQUIRES_RECONCILIATION routing when process rebuild with crossDevice enabled`() = runBlocking {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true, deviceId = "Pixel-119")
        val firstController = buildController(settings)
        firstController.setActiveForTest()
        firstController.recordDelegatedTaskAccepted("task-recon-119")

        // New controller simulates process rebuild with crossDevice still enabled
        val controller = buildController(settings)
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.PROCESS_RECREATED)

        val deferred = async {
            withTimeout(500) {
                controller.reconciliationSignals.first {
                    it.kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
                }
            }
        }

        controller.publishRuntimeTruthSnapshot()

        val signal = deferred.await()
        assertEquals(
            "Process rebuild with crossDevice enabled must emit canonical_reconciliation_pass routing",
            AndroidCrossRepoRecoveryStateRoutingContract.V2RoutingCategory.CANONICAL_RECONCILIATION_PASS.wireValue,
            signal.payload[ReconciliationSignal.KEY_V2_ROUTING_CATEGORY]
        )
        assertEquals(
            "REQUIRES_RECONCILIATION routing must declare requiresV2Action=true",
            "true",
            signal.payload[ReconciliationSignal.KEY_ROUTING_REQUIRES_V2_ACTION]
        )
        assertEquals(
            "REQUIRES_RECONCILIATION routing must block canonical closure",
            "true",
            signal.payload[ReconciliationSignal.KEY_ROUTING_CANONICAL_CLOSURE_BLOCKED]
        )
    }

    @Test
    fun `publishRuntimeTruthSnapshot emits RECOVERING routing when reconnect is RECOVERING`() = runBlocking {
        val controller = buildController()
        controller.setActiveForTest()
        controller.setReconnectRecoveryStateForTest(ReconnectRecoveryState.RECOVERING)

        val deferred = async {
            withTimeout(500) {
                controller.reconciliationSignals.first {
                    it.kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
                }
            }
        }

        controller.publishRuntimeTruthSnapshot()

        val signal = deferred.await()
        assertEquals(
            "RECOVERING phase must emit pending_reconnect_verdict routing",
            AndroidCrossRepoRecoveryStateRoutingContract.V2RoutingCategory.PENDING_RECONNECT_VERDICT.wireValue,
            signal.payload[ReconciliationSignal.KEY_V2_ROUTING_CATEGORY]
        )
        assertEquals(
            "RECOVERING routing must declare requiresV2Action=true",
            "true",
            signal.payload[ReconciliationSignal.KEY_ROUTING_REQUIRES_V2_ACTION]
        )
        assertEquals(
            "RECOVERING routing must block canonical closure (INV-ROUTING-07)",
            "true",
            signal.payload[ReconciliationSignal.KEY_ROUTING_CANONICAL_CLOSURE_BLOCKED]
        )
    }

    @Test
    fun `publishRuntimeTruthSnapshot emits stale_artifact_rejection routing after stale artifact is found`() = runBlocking {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true, deviceId = "Pixel-119")
        // Write an artifact from an old durable session
        val oldArtifact = InflightContinuityRecoveryArtifact(
            taskId = "task-stale-119",
            activeTaskStatus = ActiveTaskStatus.RUNNING.wireValue,
            durableSessionId = "old-session-era-119",
            sessionContinuityEpoch = 1,
            runtimeAttachmentSessionId = null,
            attachedSessionId = null
        )
        settings.inflightContinuityRecoveryArtifact = oldArtifact.toJson()
        settings.lastDurableSessionId = "new-session-era-119"

        val controller = buildController(settings)
        controller.onAppLifecycleTransition(AndroidAppLifecycleTransition.PROCESS_RECREATED)

        val deferred = async {
            withTimeout(500) {
                controller.reconciliationSignals.first {
                    it.kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
                }
            }
        }

        controller.publishRuntimeTruthSnapshot()

        val signal = deferred.await()
        assertEquals(
            "Stale artifact phase must emit stale_artifact_rejection routing (INV-ROUTING-03)",
            AndroidCrossRepoRecoveryStateRoutingContract.V2RoutingCategory.STALE_ARTIFACT_REJECTION.wireValue,
            signal.payload[ReconciliationSignal.KEY_V2_ROUTING_CATEGORY]
        )
        assertEquals(
            "Stale artifact routing must declare requiresV2Action=true",
            "true",
            signal.payload[ReconciliationSignal.KEY_ROUTING_REQUIRES_V2_ACTION]
        )
        assertEquals(
            "Stale artifact routing must block canonical closure",
            "true",
            signal.payload[ReconciliationSignal.KEY_ROUTING_CANONICAL_CLOSURE_BLOCKED]
        )
    }

    // ── 10. StabilizationBaseline registration ─────────────────────────────────────────────

    @Test
    fun `android-cross-repo-recovery-state-routing-contract is registered in StabilizationBaseline`() {
        val entry = StabilizationBaseline.forId("android-cross-repo-recovery-state-routing-contract")
        assertNotNull(
            "android-cross-repo-recovery-state-routing-contract must be registered in StabilizationBaseline",
            entry
        )
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
        assertEquals(
            StabilizationBaseline.ExtensionGuidance.EXTEND,
            entry.extensionGuidance
        )
        assertEquals(119, entry.introducedPr)
    }
}

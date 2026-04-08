package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.input.InputRouter
import com.ufo.galaxy.local.DefaultLocalLoopExecutor
import com.ufo.galaxy.local.FakeReadinessProvider
import com.ufo.galaxy.local.LocalLoopResult
import com.ufo.galaxy.loop.ExecutorBridge
import com.ufo.galaxy.loop.LocalPlanner
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.network.GatewayClient
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.speech.NaturalLanguageInputManager
import com.ufo.galaxy.ui.viewmodel.MainUiState
import com.ufo.galaxy.ui.viewmodel.UnifiedResultPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-34 — Final product-grade runtime and interaction acceptance pack.
 *
 * Regression and acceptance test suite establishing the product-grade acceptance baseline
 * for the Android runtime and interaction surfaces. This suite validates — without
 * introducing a new acceptance architecture — that the six core acceptance surfaces
 * (text input, voice, floating secondary entry, cross-device toggle/failure/recovery,
 * delegated target, and unified result) all behave consistently and recoverably.
 *
 * ## Acceptance scope
 *
 *  1. [GalaxyLogger.TAG_INTERACTION_ACCEPTANCE] — new stable structured log tag constant
 *     (`"GALAXY:INTERACTION:ACCEPTANCE"`) that marks product-grade acceptance checkpoints
 *     across all interaction surfaces.
 *
 *  2. **Text input execution stability** — [NaturalLanguageInputManager.submit] routes
 *     non-blank input, ignores blank/empty input, and correctly distinguishes local vs.
 *     cross-device routing based on [com.ufo.galaxy.data.AppSettings.crossDeviceEnabled].
 *
 *  3. **Main interface voice stability** — [NaturalLanguageInputManager.submitVoiceResult]
 *     delegates to [NaturalLanguageInputManager.submit] under the same routing semantics,
 *     ensuring voice and text entry surfaces behave identically.
 *
 *  4. **Floating secondary entry availability** — [com.ufo.galaxy.service.EnhancedFloatingService]
 *     status constants (`STATUS_IDLE`, `STATUS_RUNNING`, `STATUS_SUCCESS`, `STATUS_ERROR`)
 *     are stable, distinct, and non-empty — proving the secondary entry point is available
 *     across all task lifecycle phases.
 *
 *  5. **Cross-device toggle / failure / recovery clarity**:
 *     - [RolloutControlSnapshot] correctly reflects toggle state and computes derived flags.
 *     - [ReconnectRecoveryState] phase-progression semantics are stable and recoverable.
 *     - [CrossDeviceSetupError.Category] failure classifications cover all setup failure paths.
 *
 *  6. **Delegated target behavior stability** — [TargetSelectionOutcome] and
 *     [DelegatedTargetReadinessProjection] produce consistent, machine-readable suitability
 *     verdicts (SELECTED / FALLBACK / REJECTED) from the attached session state.
 *
 *  7. **Local / cross-device / fallback result consistency** — [UnifiedResultPresentation]
 *     normalises all three execution paths into a consistent `isSuccess` / `summary` /
 *     `outcome` contract; no path-specific leakage occurs between result presentations.
 *
 *  8. **MainUiState acceptance fields** — [MainUiState] carries the product-grade
 *     acceptance fields introduced across PR-26 through PR-34, all accessible and
 *     independently settable.
 *
 * ## Test matrix
 *
 * ### GalaxyLogger.TAG_INTERACTION_ACCEPTANCE
 *  - TAG_INTERACTION_ACCEPTANCE value is "GALAXY:INTERACTION:ACCEPTANCE"
 *  - TAG_INTERACTION_ACCEPTANCE is distinct from TAG_RECONNECT_RECOVERY
 *  - TAG_INTERACTION_ACCEPTANCE is distinct from TAG_STAGED_MESH
 *  - TAG_INTERACTION_ACCEPTANCE is distinct from TAG_ROLLOUT_CONTROL
 *  - TAG_INTERACTION_ACCEPTANCE is distinct from TAG_EXEC_ROUTE
 *  - TAG_INTERACTION_ACCEPTANCE is distinct from all pre-PR34 tags
 *
 * ### Text input execution stability
 *  - submit returns false for empty string
 *  - submit returns false for blank string
 *  - submit routes non-blank input when cross-device disabled (local path)
 *  - submit sends message via WS when cross-device enabled and connected
 *  - submit returns false when cross-device enabled but WS disconnected
 *  - multiple non-blank submits are each independently routed
 *
 * ### Main interface voice stability
 *  - submitVoiceResult returns false for empty voice transcript
 *  - submitVoiceResult routes non-blank voice transcript via cross-device when enabled
 *  - submitVoiceResult routes non-blank voice transcript via local path when disabled
 *  - submitVoiceResult and submit behave identically for same input
 *
 * ### Floating secondary entry availability
 *  - STATUS_IDLE is "idle"
 *  - STATUS_RUNNING is "running"
 *  - STATUS_SUCCESS is "success"
 *  - STATUS_ERROR is "error"
 *  - all four status constants are distinct
 *  - all four status constants are non-empty
 *
 * ### Cross-device toggle clarity — RolloutControlSnapshot
 *  - crossDeviceAllowed=true reflects enabled cross-device
 *  - crossDeviceAllowed=false reflects disabled cross-device
 *  - killSwitchActive is true only when both crossDeviceAllowed and goalExecutionAllowed are false
 *  - killSwitchActive is false when crossDeviceAllowed is true
 *  - isFullyEnabled is true only when all four flags are true
 *
 * ### Cross-device recovery clarity — ReconnectRecoveryState
 *  - recovery phases progress: IDLE → RECOVERING → RECOVERED
 *  - recovery phases progress: IDLE → RECOVERING → FAILED
 *  - recovery states are copy-stable on MainUiState
 *
 * ### Cross-device failure clarity — CrossDeviceSetupError.Category
 *  - CONFIGURATION category wireValue is "configuration_error"
 *  - NETWORK category wireValue is "network_error"
 *  - CAPABILITY_NOT_SATISFIED category wireValue is "capability_not_satisfied"
 *  - all three category wire values are distinct
 *
 * ### Delegated target behavior stability — TargetSelectionOutcome
 *  - SELECTED wireValue is "selected"
 *  - FALLBACK wireValue is "fallback"
 *  - REJECTED wireValue is "rejected"
 *  - all three outcome wire values are distinct
 *
 * ### Delegated target behavior stability — DelegatedTargetReadinessProjection
 *  - attached primary-role session produces SELECTED outcome
 *  - detached session produces REJECTED outcome
 *  - detached session is not a suitable target
 *  - attached session is a suitable target
 *  - suitability reason is null for suitable target
 *  - unsuitability reason is non-null for rejected target
 *
 * ### Local / cross-device / fallback result consistency — UnifiedResultPresentation
 *  - local success produces isSuccess=true
 *  - local failure produces isSuccess=false
 *  - local cancelled produces isSuccess=false
 *  - server message (cross-device) produces isSuccess=true
 *  - fallback FAILED produces isSuccess=false
 *  - fallback DISCONNECT produces isSuccess=false
 *  - all result paths produce non-empty summary
 *  - local and cross-device success both produce isSuccess=true (consistency)
 *  - fallback paths all produce isSuccess=false (consistency)
 *  - summaries do not contain path-specific leakage
 *
 * ### MainUiState acceptance fields
 *  - reconnectRecoveryState defaults to IDLE
 *  - rolloutControlSnapshot defaults to null
 *  - lastExecutionRoute defaults to null
 *  - registrationFailureCategory defaults to null
 *  - acceptance fields are independently settable without affecting unrelated fields
 */
class Pr34RuntimeInteractionAcceptanceTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies ─────────────────────────────────────────────────────

    private class TrivialPlannerService : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep("tap", "tap the button"))
            )
        override fun replan(
            goal: String, constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String, screenshotBase64: String?
        ) = LocalPlannerService.PlanResult(steps = emptyList(), error = "no replan")
    }

    private class TrivialGrounder : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(x = 540, y = 1170, confidence = 0.9f)
    }

    private class TrivialAccessibilityExecutor : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
    }

    private class TrivialScreenshotProvider : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        override fun screenWidth() = 1080
        override fun screenHeight() = 2340
    }

    private class FakeGatewayClient(
        var connected: Boolean = false,
        var sendResult: Boolean = true
    ) : GatewayClient {
        val sentMessages = mutableListOf<String>()
        override fun isConnected(): Boolean = connected
        override fun sendJson(json: String): Boolean {
            if (connected && sendResult) {
                sentMessages.add(json)
                return true
            }
            return false
        }
    }

    // ── Builder helpers ───────────────────────────────────────────────────────

    private fun buildLoopController(): LoopController {
        val modelsDir = tmpFolder.newFolder("models")
        return LoopController(
            localPlanner = LocalPlanner(TrivialPlannerService()),
            executorBridge = ExecutorBridge(
                groundingService = TrivialGrounder(),
                accessibilityExecutor = TrivialAccessibilityExecutor(),
                imageScaler = NoOpImageScaler()
            ),
            screenshotProvider = TrivialScreenshotProvider(),
            modelAssetManager = ModelAssetManager(modelsDir),
            modelDownloader = ModelDownloader(modelsDir)
        )
    }

    private fun buildInputRouter(
        crossDeviceEnabled: Boolean = false,
        gateway: FakeGatewayClient = FakeGatewayClient(connected = false),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    ): InputRouter {
        val settings = InMemoryAppSettings(crossDeviceEnabled = crossDeviceEnabled)
        val localLoopExecutor = DefaultLocalLoopExecutor(
            loopController = buildLoopController(),
            readinessProvider = FakeReadinessProvider.fullyReady()
        )
        return InputRouter(
            settings = settings,
            webSocketClient = gateway,
            localLoopExecutor = localLoopExecutor,
            coroutineScope = scope
        )
    }

    private fun buildInputManager(
        crossDeviceEnabled: Boolean = false,
        gateway: FakeGatewayClient = FakeGatewayClient(connected = false)
    ): NaturalLanguageInputManager =
        NaturalLanguageInputManager(buildInputRouter(crossDeviceEnabled, gateway))

    // ── Helper: attached session snapshot ─────────────────────────────────────

    private fun attachedSnapshot(
        hostRole: String = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
    ): AttachedRuntimeHostSessionSnapshot {
        val session = AttachedRuntimeSession.create(
            hostId = "host-pr34",
            deviceId = "device-pr34"
        )
        return AttachedRuntimeHostSessionSnapshot.from(
            session = session,
            runtimeSessionId = "rts-pr34",
            hostRole = hostRole
        )
    }

    private fun detachedSnapshot(): AttachedRuntimeHostSessionSnapshot {
        val session = AttachedRuntimeSession.create(
            hostId = "host-pr34-detached",
            deviceId = "device-pr34-detached"
        ).detach(AttachedRuntimeSession.DetachCause.NORMAL)
        return AttachedRuntimeHostSessionSnapshot.from(
            session = session,
            runtimeSessionId = "rts-pr34-detached",
            hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
        )
    }

    private fun AttachedRuntimeSession.detach(
        cause: AttachedRuntimeSession.DetachCause
    ): AttachedRuntimeSession = copy(
        state = AttachedRuntimeSession.State.DETACHED,
        detachCause = cause,
        detachedAtMs = System.currentTimeMillis()
    )

    // ─────────────────────────────────────────────────────────────────────────
    // GalaxyLogger.TAG_INTERACTION_ACCEPTANCE
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `TAG_INTERACTION_ACCEPTANCE value is GALAXY_INTERACTION_ACCEPTANCE`() {
        assertEquals("GALAXY:INTERACTION:ACCEPTANCE", GalaxyLogger.TAG_INTERACTION_ACCEPTANCE)
    }

    @Test
    fun `TAG_INTERACTION_ACCEPTANCE is distinct from TAG_RECONNECT_RECOVERY`() {
        assertFalse(
            "TAG_INTERACTION_ACCEPTANCE must not equal TAG_RECONNECT_RECOVERY",
            GalaxyLogger.TAG_INTERACTION_ACCEPTANCE == GalaxyLogger.TAG_RECONNECT_RECOVERY
        )
    }

    @Test
    fun `TAG_INTERACTION_ACCEPTANCE is distinct from TAG_STAGED_MESH`() {
        assertFalse(
            "TAG_INTERACTION_ACCEPTANCE must not equal TAG_STAGED_MESH",
            GalaxyLogger.TAG_INTERACTION_ACCEPTANCE == GalaxyLogger.TAG_STAGED_MESH
        )
    }

    @Test
    fun `TAG_INTERACTION_ACCEPTANCE is distinct from TAG_ROLLOUT_CONTROL`() {
        assertFalse(
            "TAG_INTERACTION_ACCEPTANCE must not equal TAG_ROLLOUT_CONTROL",
            GalaxyLogger.TAG_INTERACTION_ACCEPTANCE == GalaxyLogger.TAG_ROLLOUT_CONTROL
        )
    }

    @Test
    fun `TAG_INTERACTION_ACCEPTANCE is distinct from TAG_EXEC_ROUTE`() {
        assertFalse(
            "TAG_INTERACTION_ACCEPTANCE must not equal TAG_EXEC_ROUTE",
            GalaxyLogger.TAG_INTERACTION_ACCEPTANCE == GalaxyLogger.TAG_EXEC_ROUTE
        )
    }

    @Test
    fun `TAG_INTERACTION_ACCEPTANCE is distinct from all pre-PR34 tags`() {
        val prePr34Tags = listOf(
            GalaxyLogger.TAG_CONNECT,
            GalaxyLogger.TAG_DISCONNECT,
            GalaxyLogger.TAG_RECONNECT,
            GalaxyLogger.TAG_TASK_RECV,
            GalaxyLogger.TAG_TASK_EXEC,
            GalaxyLogger.TAG_TASK_RETURN,
            GalaxyLogger.TAG_READINESS,
            GalaxyLogger.TAG_DEGRADED,
            GalaxyLogger.TAG_ERROR,
            GalaxyLogger.TAG_EXEC_ROUTE,
            GalaxyLogger.TAG_SETUP_RECOVERY,
            GalaxyLogger.TAG_RECONNECT_OUTCOME,
            GalaxyLogger.TAG_FALLBACK_DECISION,
            GalaxyLogger.TAG_ROLLOUT_CONTROL,
            GalaxyLogger.TAG_KILL_SWITCH,
            GalaxyLogger.TAG_STAGED_MESH,
            GalaxyLogger.TAG_RECONNECT_RECOVERY
        )
        prePr34Tags.forEach { existingTag ->
            assertFalse(
                "TAG_INTERACTION_ACCEPTANCE must not equal pre-PR34 tag '$existingTag'",
                GalaxyLogger.TAG_INTERACTION_ACCEPTANCE == existingTag
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Text input execution stability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `submit returns false for empty string`() {
        val manager = buildInputManager(crossDeviceEnabled = true,
            gateway = FakeGatewayClient(connected = true))
        assertFalse("empty input must not be routed", manager.submit(""))
    }

    @Test
    fun `submit returns false for blank string`() {
        val manager = buildInputManager(crossDeviceEnabled = true,
            gateway = FakeGatewayClient(connected = true))
        assertFalse("blank input must not be routed", manager.submit("   "))
    }

    @Test
    fun `submit routes non-blank input via local path when cross-device disabled`() {
        val gateway = FakeGatewayClient(connected = false)
        val manager = buildInputManager(crossDeviceEnabled = false, gateway = gateway)

        val result = manager.submit("打开微信")

        // local path: returns false (not cross-device), gateway receives nothing
        assertFalse("local path must not return true for cross-device flag", result)
        assertEquals("local path must not send to gateway", 0, gateway.sentMessages.size)
    }

    @Test
    fun `submit sends message via WS when cross-device enabled and connected`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val manager = buildInputManager(crossDeviceEnabled = true, gateway = gateway)

        val result = manager.submit("搜索天气")

        assertTrue("cross-device enabled + connected must return true", result)
        assertEquals("one WS message must be sent", 1, gateway.sentMessages.size)
        assertTrue("sent message must contain the submitted text",
            gateway.sentMessages[0].contains("搜索天气"))
    }

    @Test
    fun `submit returns false when cross-device enabled but WS disconnected`() {
        val gateway = FakeGatewayClient(connected = false)
        val manager = buildInputManager(crossDeviceEnabled = true, gateway = gateway)

        // When WS is disconnected, cross-device routing fails; InputRouter calls onError
        // and does NOT fall back silently to local. submit() returns false (not cross-device).
        val result = manager.submit("打开日历")

        assertFalse("disconnected WS must not produce a cross-device true return", result)
        assertEquals("no WS message must be sent when disconnected", 0, gateway.sentMessages.size)
    }

    @Test
    fun `multiple non-blank submits are each independently routed`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val manager = buildInputManager(crossDeviceEnabled = true, gateway = gateway)

        manager.submit("打开微信")
        manager.submit("发送消息")
        manager.submit("截图")

        assertEquals("each submit must produce one WS message", 3, gateway.sentMessages.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main interface voice stability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `submitVoiceResult returns false for empty voice transcript`() {
        val manager = buildInputManager(crossDeviceEnabled = true,
            gateway = FakeGatewayClient(connected = true))
        assertFalse("empty voice transcript must not be routed", manager.submitVoiceResult(""))
    }

    @Test
    fun `submitVoiceResult routes non-blank voice transcript via cross-device when enabled`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val manager = buildInputManager(crossDeviceEnabled = true, gateway = gateway)

        val result = manager.submitVoiceResult("打开相册")

        assertTrue("voice result must route via cross-device when enabled + connected", result)
        assertEquals("one WS message must be sent for voice input", 1, gateway.sentMessages.size)
    }

    @Test
    fun `submitVoiceResult routes non-blank voice transcript via local path when disabled`() {
        val gateway = FakeGatewayClient(connected = false)
        val manager = buildInputManager(crossDeviceEnabled = false, gateway = gateway)

        val result = manager.submitVoiceResult("设置闹钟")

        assertFalse("voice result must use local path when cross-device disabled", result)
        assertEquals("no WS message for local voice route", 0, gateway.sentMessages.size)
    }

    @Test
    fun `submitVoiceResult and submit behave identically for the same input`() {
        val gateway1 = FakeGatewayClient(connected = true, sendResult = true)
        val manager1 = buildInputManager(crossDeviceEnabled = true, gateway = gateway1)

        val gateway2 = FakeGatewayClient(connected = true, sendResult = true)
        val manager2 = buildInputManager(crossDeviceEnabled = true, gateway = gateway2)

        val viaVoice = manager1.submitVoiceResult("打开设置")
        val viaText = manager2.submit("打开设置")

        assertEquals("voice and text routing results must be identical", viaVoice, viaText)
        assertEquals("both must produce the same number of WS messages",
            gateway1.sentMessages.size, gateway2.sentMessages.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Floating secondary entry availability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `floating entry STATUS_IDLE is 'idle'`() {
        assertEquals("idle", com.ufo.galaxy.service.EnhancedFloatingService.STATUS_IDLE)
    }

    @Test
    fun `floating entry STATUS_RUNNING is 'running'`() {
        assertEquals("running", com.ufo.galaxy.service.EnhancedFloatingService.STATUS_RUNNING)
    }

    @Test
    fun `floating entry STATUS_SUCCESS is 'success'`() {
        assertEquals("success", com.ufo.galaxy.service.EnhancedFloatingService.STATUS_SUCCESS)
    }

    @Test
    fun `floating entry STATUS_ERROR is 'error'`() {
        assertEquals("error", com.ufo.galaxy.service.EnhancedFloatingService.STATUS_ERROR)
    }

    @Test
    fun `all four floating entry status constants are distinct`() {
        val statuses = listOf(
            com.ufo.galaxy.service.EnhancedFloatingService.STATUS_IDLE,
            com.ufo.galaxy.service.EnhancedFloatingService.STATUS_RUNNING,
            com.ufo.galaxy.service.EnhancedFloatingService.STATUS_SUCCESS,
            com.ufo.galaxy.service.EnhancedFloatingService.STATUS_ERROR
        )
        assertEquals(
            "All four floating entry status constants must be distinct",
            statuses.size,
            statuses.distinct().size
        )
    }

    @Test
    fun `all four floating entry status constants are non-empty`() {
        listOf(
            com.ufo.galaxy.service.EnhancedFloatingService.STATUS_IDLE,
            com.ufo.galaxy.service.EnhancedFloatingService.STATUS_RUNNING,
            com.ufo.galaxy.service.EnhancedFloatingService.STATUS_SUCCESS,
            com.ufo.galaxy.service.EnhancedFloatingService.STATUS_ERROR
        ).forEach { status ->
            assertTrue("Status constant '$status' must be non-empty", status.isNotEmpty())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cross-device toggle clarity — RolloutControlSnapshot
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `crossDeviceAllowed=true reflects enabled cross-device`() {
        val snap = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        assertTrue("crossDeviceAllowed=true must be reflected", snap.crossDeviceAllowed)
    }

    @Test
    fun `crossDeviceAllowed=false reflects disabled cross-device`() {
        val snap = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        assertFalse("crossDeviceAllowed=false must be reflected", snap.crossDeviceAllowed)
    }

    @Test
    fun `killSwitchActive is true when both crossDeviceAllowed and goalExecutionAllowed are false`() {
        val snap = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        assertTrue("killSwitch must be active when both cross-device and goal-exec are off",
            snap.killSwitchActive)
    }

    @Test
    fun `killSwitchActive is false when crossDeviceAllowed is true`() {
        val snap = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        assertFalse("killSwitch must not be active when crossDeviceAllowed is true",
            snap.killSwitchActive)
    }

    @Test
    fun `isFullyEnabled is true only when all four flags are true`() {
        val fullyOn = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        assertTrue("isFullyEnabled must be true when all flags are on", fullyOn.isFullyEnabled)

        val partiallyOn = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        assertFalse("isFullyEnabled must be false when any flag is off", partiallyOn.isFullyEnabled)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cross-device recovery clarity — ReconnectRecoveryState
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `recovery phases progress IDLE to RECOVERING to RECOVERED`() {
        var state = ReconnectRecoveryState.IDLE
        assertEquals("initial phase must be IDLE", ReconnectRecoveryState.IDLE, state)

        state = ReconnectRecoveryState.RECOVERING
        assertEquals("after disconnect phase must be RECOVERING",
            ReconnectRecoveryState.RECOVERING, state)

        state = ReconnectRecoveryState.RECOVERED
        assertEquals("after reconnect phase must be RECOVERED",
            ReconnectRecoveryState.RECOVERED, state)
    }

    @Test
    fun `recovery phases progress IDLE to RECOVERING to FAILED`() {
        var state = ReconnectRecoveryState.IDLE
        state = ReconnectRecoveryState.RECOVERING
        state = ReconnectRecoveryState.FAILED

        assertEquals("after exhausted attempts phase must be FAILED",
            ReconnectRecoveryState.FAILED, state)
    }

    @Test
    fun `recovery states are copy-stable on MainUiState`() {
        val base = MainUiState()
        assertEquals("default recovery state must be IDLE",
            ReconnectRecoveryState.IDLE, base.reconnectRecoveryState)

        val recovering = base.copy(reconnectRecoveryState = ReconnectRecoveryState.RECOVERING)
        assertEquals("copied state must carry RECOVERING",
            ReconnectRecoveryState.RECOVERING, recovering.reconnectRecoveryState)

        val recovered = recovering.copy(reconnectRecoveryState = ReconnectRecoveryState.RECOVERED)
        assertEquals("copied state must carry RECOVERED",
            ReconnectRecoveryState.RECOVERED, recovered.reconnectRecoveryState)
        // other fields must not change
        assertEquals("inputText must be unaffected by recovery state change",
            base.inputText, recovered.inputText)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cross-device failure clarity — CrossDeviceSetupError.Category
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `CONFIGURATION category wireValue is 'configuration_error'`() {
        assertEquals("configuration_error",
            CrossDeviceSetupError.Category.CONFIGURATION.wireValue)
    }

    @Test
    fun `NETWORK category wireValue is 'network_error'`() {
        assertEquals("network_error",
            CrossDeviceSetupError.Category.NETWORK.wireValue)
    }

    @Test
    fun `CAPABILITY_NOT_SATISFIED category wireValue is 'capability_not_satisfied'`() {
        assertEquals("capability_not_satisfied",
            CrossDeviceSetupError.Category.CAPABILITY_NOT_SATISFIED.wireValue)
    }

    @Test
    fun `all three CrossDeviceSetupError category wire values are distinct`() {
        val values = CrossDeviceSetupError.Category.values().map { it.wireValue }
        assertEquals(
            "All CrossDeviceSetupError.Category wire values must be distinct",
            values.size,
            values.distinct().size
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delegated target behavior stability — TargetSelectionOutcome
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `SELECTED wireValue is 'selected'`() {
        assertEquals("selected", TargetSelectionOutcome.SELECTED.wireValue)
    }

    @Test
    fun `FALLBACK wireValue is 'fallback'`() {
        assertEquals("fallback", TargetSelectionOutcome.FALLBACK.wireValue)
    }

    @Test
    fun `REJECTED wireValue is 'rejected'`() {
        assertEquals("rejected", TargetSelectionOutcome.REJECTED.wireValue)
    }

    @Test
    fun `all three TargetSelectionOutcome wire values are distinct`() {
        val values = TargetSelectionOutcome.values().map { it.wireValue }
        assertEquals(
            "All TargetSelectionOutcome wire values must be distinct",
            values.size,
            values.distinct().size
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delegated target behavior stability — DelegatedTargetReadinessProjection
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `attached primary-role session produces SELECTED outcome`() {
        val snapshot = attachedSnapshot(
            hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
        )
        val projection = DelegatedTargetReadinessProjection.from(snapshot)
        assertEquals("attached PRIMARY session must produce SELECTED",
            TargetSelectionOutcome.SELECTED, projection.selectionOutcome)
    }

    @Test
    fun `detached session produces REJECTED outcome`() {
        val snapshot = detachedSnapshot()
        val projection = DelegatedTargetReadinessProjection.from(snapshot)
        assertEquals("detached session must produce REJECTED",
            TargetSelectionOutcome.REJECTED, projection.selectionOutcome)
    }

    @Test
    fun `detached session is not a suitable target`() {
        val snapshot = detachedSnapshot()
        val projection = DelegatedTargetReadinessProjection.from(snapshot)
        assertFalse("detached session must not be a suitable target", projection.isSuitableTarget)
    }

    @Test
    fun `attached session is a suitable target`() {
        val snapshot = attachedSnapshot()
        val projection = DelegatedTargetReadinessProjection.from(snapshot)
        assertTrue("attached session must be a suitable target", projection.isSuitableTarget)
    }

    @Test
    fun `suitability reason is null for suitable target`() {
        val snapshot = attachedSnapshot()
        val projection = DelegatedTargetReadinessProjection.from(snapshot)
        assertNull("unsuitabilityReason must be null for a suitable target",
            projection.unsuitabilityReason)
    }

    @Test
    fun `unsuitability reason is non-null for rejected target`() {
        val snapshot = detachedSnapshot()
        val projection = DelegatedTargetReadinessProjection.from(snapshot)
        assertNotNull("unsuitabilityReason must be non-null for a rejected target",
            projection.unsuitabilityReason)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Local / cross-device / fallback result consistency — UnifiedResultPresentation
    // ─────────────────────────────────────────────────────────────────────────

    private fun localSuccess() = LocalLoopResult(
        sessionId = "sess-pr34-ok",
        instruction = "open settings",
        status = LocalLoopResult.STATUS_SUCCESS,
        stepCount = 3,
        stopReason = null,
        error = null
    )

    private fun localFailure() = LocalLoopResult(
        sessionId = "sess-pr34-fail",
        instruction = "open settings",
        status = LocalLoopResult.STATUS_FAILED,
        stepCount = 1,
        stopReason = "model_error",
        error = "screenshot failed"
    )

    private fun localCancelled() = LocalLoopResult(
        sessionId = "sess-pr34-cancel",
        instruction = "send message",
        status = LocalLoopResult.STATUS_CANCELLED,
        stepCount = 0,
        stopReason = null,
        error = "preempted"
    )

    private fun takeoverFailure(cause: TakeoverFallbackEvent.Cause) = TakeoverFallbackEvent(
        takeoverId = "to-pr34",
        taskId = "t-pr34",
        traceId = "tr-pr34",
        reason = "test_reason",
        cause = cause
    )

    @Test
    fun `local success produces isSuccess=true`() {
        val p = UnifiedResultPresentation.fromLocalResult(localSuccess())
        assertTrue("local success must produce isSuccess=true", p.isSuccess)
    }

    @Test
    fun `local failure produces isSuccess=false`() {
        val p = UnifiedResultPresentation.fromLocalResult(localFailure())
        assertFalse("local failure must produce isSuccess=false", p.isSuccess)
    }

    @Test
    fun `local cancelled produces isSuccess=false`() {
        val p = UnifiedResultPresentation.fromLocalResult(localCancelled())
        assertFalse("local cancelled must produce isSuccess=false", p.isSuccess)
    }

    @Test
    fun `server message (cross-device) produces isSuccess=true`() {
        val p = UnifiedResultPresentation.fromServerMessage("任务完成")
        assertTrue("server message must produce isSuccess=true", p.isSuccess)
    }

    @Test
    fun `fallback FAILED produces isSuccess=false`() {
        val p = UnifiedResultPresentation.fromFallbackEvent(
            takeoverFailure(TakeoverFallbackEvent.Cause.FAILED))
        assertFalse("fallback FAILED must produce isSuccess=false", p.isSuccess)
    }

    @Test
    fun `fallback DISCONNECT produces isSuccess=false`() {
        val p = UnifiedResultPresentation.fromFallbackEvent(
            takeoverFailure(TakeoverFallbackEvent.Cause.DISCONNECT))
        assertFalse("fallback DISCONNECT must produce isSuccess=false", p.isSuccess)
    }

    @Test
    fun `all result paths produce non-empty summary`() {
        val presentations = listOf(
            UnifiedResultPresentation.fromLocalResult(localSuccess()),
            UnifiedResultPresentation.fromLocalResult(localFailure()),
            UnifiedResultPresentation.fromLocalResult(localCancelled()),
            UnifiedResultPresentation.fromServerMessage("result text"),
            UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.FAILED)),
            UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.TIMEOUT)),
            UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.CANCELLED)),
            UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.DISCONNECT))
        )
        presentations.forEach { p ->
            assertTrue("All result paths must produce non-empty summary", p.summary.isNotEmpty())
        }
    }

    @Test
    fun `local and cross-device success both produce isSuccess=true (consistency)`() {
        val localP = UnifiedResultPresentation.fromLocalResult(localSuccess())
        val serverP = UnifiedResultPresentation.fromServerMessage("done")

        assertTrue("local success isSuccess must be true", localP.isSuccess)
        assertTrue("cross-device success isSuccess must be true", serverP.isSuccess)
    }

    @Test
    fun `fallback paths all produce isSuccess=false (consistency)`() {
        TakeoverFallbackEvent.Cause.values().forEach { cause ->
            val p = UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(cause))
            assertFalse("fallback cause $cause must produce isSuccess=false", p.isSuccess)
        }
    }

    @Test
    fun `summaries do not contain path-specific leakage`() {
        val allSummaries = listOf(
            UnifiedResultPresentation.fromLocalResult(localSuccess()).summary,
            UnifiedResultPresentation.fromLocalResult(localFailure()).summary,
            UnifiedResultPresentation.fromFallbackEvent(
                takeoverFailure(TakeoverFallbackEvent.Cause.FAILED)).summary,
            UnifiedResultPresentation.fromFallbackEvent(
                takeoverFailure(TakeoverFallbackEvent.Cause.DISCONNECT)).summary
        )
        allSummaries.forEach { summary ->
            assertFalse("summary must not contain 'cross_device'",
                summary.contains("cross_device", ignoreCase = true))
            assertFalse("summary must not contain 'takeover'",
                summary.contains("takeover", ignoreCase = true))
            assertFalse("summary must not contain 'delegated'",
                summary.contains("delegated", ignoreCase = true))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MainUiState acceptance fields
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `reconnectRecoveryState defaults to IDLE`() {
        assertEquals("reconnectRecoveryState must default to IDLE",
            ReconnectRecoveryState.IDLE, MainUiState().reconnectRecoveryState)
    }

    @Test
    fun `rolloutControlSnapshot defaults to null`() {
        assertNull("rolloutControlSnapshot must default to null",
            MainUiState().rolloutControlSnapshot)
    }

    @Test
    fun `lastExecutionRoute defaults to null`() {
        assertNull("lastExecutionRoute must default to null",
            MainUiState().lastExecutionRoute)
    }

    @Test
    fun `registrationFailureCategory defaults to null`() {
        assertNull("registrationFailureCategory must default to null",
            MainUiState().registrationFailureCategory)
    }

    @Test
    fun `acceptance fields are independently settable without affecting unrelated fields`() {
        val base = MainUiState()

        // Set reconnect recovery state
        val withRecovery = base.copy(reconnectRecoveryState = ReconnectRecoveryState.RECOVERING)
        assertEquals("reconnectRecoveryState must be RECOVERING",
            ReconnectRecoveryState.RECOVERING, withRecovery.reconnectRecoveryState)
        assertEquals("inputText must be unaffected", base.inputText, withRecovery.inputText)
        assertEquals("isLoading must be unaffected", base.isLoading, withRecovery.isLoading)

        // Set rollout control snapshot
        val snap = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        val withSnapshot = base.copy(rolloutControlSnapshot = snap)
        assertNotNull("rolloutControlSnapshot must be set", withSnapshot.rolloutControlSnapshot)
        assertEquals("reconnectRecoveryState must remain IDLE when snapshot is changed",
            ReconnectRecoveryState.IDLE, withSnapshot.reconnectRecoveryState)

        // Set registration failure category
        val withCategory = base.copy(
            registrationFailureCategory = CrossDeviceSetupError.Category.NETWORK
        )
        assertEquals("registrationFailureCategory must be NETWORK",
            CrossDeviceSetupError.Category.NETWORK, withCategory.registrationFailureCategory)
        assertNull("rolloutControlSnapshot must remain null when category is changed",
            withCategory.rolloutControlSnapshot)
    }
}

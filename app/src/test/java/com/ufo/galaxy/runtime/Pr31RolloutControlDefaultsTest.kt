package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.agent.TakeoverEligibilityAssessor
import com.ufo.galaxy.agent.TakeoverRequestEnvelope
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.loop.ExecutorBridge
import com.ufo.galaxy.loop.LocalPlanner
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.ui.viewmodel.MainUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-31 — Rollout-control and default-behavior hardening.
 *
 * Regression and acceptance test suite for all PR-31 additions:
 *
 *  1. [RolloutControlSnapshot] data class — canonical typed view of all rollout-control
 *     flags with stable wire-key constants, [toMetadataMap], computed properties
 *     ([killSwitchActive], [isFullyEnabled]), [SAFE_DEFAULTS], and [from] factory.
 *
 *  2. New [AppSettings] flags: [com.ufo.galaxy.data.AppSettings.delegatedExecutionAllowed]
 *     (default `true`) and [com.ufo.galaxy.data.AppSettings.fallbackToLocalAllowed]
 *     (default `true`).  Safe defaults are conservative and backward-compatible.
 *
 *  3. [GalaxyLogger.TAG_ROLLOUT_CONTROL] and [GalaxyLogger.TAG_KILL_SWITCH] tag constants.
 *
 *  4. [RuntimeController.rolloutControlSnapshot] — observable [StateFlow] of the current
 *     rollout-control state.
 *
 *  5. [RuntimeController.applyKillSwitch] — atomically disables all remote execution
 *     paths and logs the event.
 *
 *  6. [RuntimeController.refreshRolloutControlSnapshot] — syncs the snapshot after
 *     external settings changes.
 *
 *  7. [TakeoverEligibilityAssessor] gains [TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_DELEGATED_EXECUTION_DISABLED]
 *     and checks [com.ufo.galaxy.data.AppSettings.delegatedExecutionAllowed] before
 *     capability checks.
 *
 *  8. [MainUiState.rolloutControlSnapshot] field — surfaces the snapshot in UI state
 *     for operator-facing diagnostics.
 *
 * ## Test matrix
 *
 * ### RolloutControlSnapshot — data model
 *  - wire key constants have stable values
 *  - SAFE_DEFAULTS has crossDevice=false, goal=false, delegated=true, fallback=true
 *  - killSwitchActive is true when crossDevice=false AND goal=false
 *  - killSwitchActive is false when crossDevice=true
 *  - killSwitchActive is false when goal=true
 *  - isFullyEnabled is true only when all flags are true
 *  - isFullyEnabled is false when any flag is false
 *  - toMetadataMap contains all four wire keys
 *  - toMetadataMap values reflect current field values
 *  - from(settings) reads crossDeviceEnabled correctly
 *  - from(settings) reads goalExecutionEnabled correctly
 *  - from(settings) reads delegatedExecutionAllowed correctly
 *  - from(settings) reads fallbackToLocalAllowed correctly
 *  - data class copy semantics are correct
 *
 * ### AppSettings new fields — safe defaults
 *  - delegatedExecutionAllowed defaults to true in InMemoryAppSettings
 *  - fallbackToLocalAllowed defaults to true in InMemoryAppSettings
 *  - delegatedExecutionAllowed can be set to false
 *  - fallbackToLocalAllowed can be set to false
 *  - changing delegatedExecutionAllowed does not affect other flags
 *  - changing fallbackToLocalAllowed does not affect other flags
 *
 * ### GalaxyLogger PR-31 tag constants
 *  - TAG_ROLLOUT_CONTROL value is "GALAXY:ROLLOUT:CONTROL"
 *  - TAG_KILL_SWITCH value is "GALAXY:KILL:SWITCH"
 *  - PR-31 tags are distinct from each other
 *  - PR-31 tags are distinct from all pre-PR31 tags
 *
 * ### RuntimeController.rolloutControlSnapshot
 *  - initial snapshot reflects settings at construction
 *  - snapshot crossDeviceAllowed tracks settings.crossDeviceEnabled
 *  - snapshot goalExecutionAllowed tracks settings.goalExecutionEnabled
 *  - snapshot delegatedExecutionAllowed tracks settings.delegatedExecutionAllowed
 *  - snapshot fallbackToLocalAllowed tracks settings.fallbackToLocalAllowed
 *  - refreshRolloutControlSnapshot updates snapshot immediately
 *
 * ### RuntimeController.applyKillSwitch
 *  - applyKillSwitch sets crossDeviceEnabled=false in settings
 *  - applyKillSwitch sets goalExecutionEnabled=false in settings
 *  - applyKillSwitch transitions runtime to LocalOnly
 *  - applyKillSwitch emits TAG_KILL_SWITCH log entry
 *  - applyKillSwitch emits TAG_ROLLOUT_CONTROL entries for each disabled flag
 *  - applyKillSwitch does NOT clear delegatedExecutionAllowed
 *  - applyKillSwitch does NOT clear fallbackToLocalAllowed
 *  - snapshot killSwitchActive is true after applyKillSwitch
 *  - applyKillSwitch with custom reason emits that reason in log entry
 *
 * ### TakeoverEligibilityAssessor — BLOCKED_DELEGATED_EXECUTION_DISABLED
 *  - BLOCKED_DELEGATED_EXECUTION_DISABLED wireValue is "delegated_execution_disabled"
 *  - assess returns BLOCKED_DELEGATED_EXECUTION_DISABLED when delegatedExecutionAllowed=false
 *  - BLOCKED_DELEGATED_EXECUTION_DISABLED is checked after goal and before capability flags
 *  - setting delegatedExecutionAllowed=true with other flags ready → ELIGIBLE
 *  - BLOCKED_CROSS_DEVICE_DISABLED takes precedence over BLOCKED_DELEGATED_EXECUTION_DISABLED
 *  - BLOCKED_GOAL_EXECUTION_DISABLED takes precedence over BLOCKED_DELEGATED_EXECUTION_DISABLED
 *
 * ### MainUiState.rolloutControlSnapshot
 *  - rolloutControlSnapshot defaults to null
 *  - can be set to a valid snapshot via copy
 *  - snapshot fields are preserved through copy operations
 *  - killSwitchActive is correctly computed from set snapshot
 */
class Pr31RolloutControlDefaultsTest {

    @Rule
    @JvmField
    val tmpFolder = TemporaryFolder()

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class TrivialPlannerService : LocalPlannerService {
        override suspend fun plan(goal: String, context: String): List<String> =
            listOf("step1")
    }

    private class TrivialGrounder : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(
            intent: String, screenshotBase64: String, width: Int, height: Int
        ) = LocalGroundingService.GroundingResult(x = 540, y = 1170, confidence = 0.9f)
    }

    private class FakeAccessibilityExecutor : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
    }

    private class FakeScreenshotProvider : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        override fun screenWidth() = 1080
        override fun screenHeight() = 2340
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private fun buildLoopController(): LoopController {
        val modelsDir = tmpFolder.newFolder("models")
        return LoopController(
            localPlanner = LocalPlanner(TrivialPlannerService()),
            executorBridge = ExecutorBridge(
                groundingService = TrivialGrounder(),
                accessibilityExecutor = FakeAccessibilityExecutor(),
                imageScaler = NoOpImageScaler()
            ),
            screenshotProvider = FakeScreenshotProvider(),
            modelAssetManager = ModelAssetManager(modelsDir),
            modelDownloader = ModelDownloader(modelsDir)
        )
    }

    private fun buildController(
        settings: InMemoryAppSettings = InMemoryAppSettings(),
        timeoutMs: Long = 150L
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = timeoutMs
        )
        return controller to client
    }

    private fun makeTakeoverEnvelope(): TakeoverRequestEnvelope =
        TakeoverRequestEnvelope(
            takeover_id = "takeover-pr31-test",
            task_id = "task-pr31-test",
            trace_id = "trace-pr31-test",
            goal = "open the calculator",
            source_runtime_posture = null
        )

    // ── RolloutControlSnapshot — wire key constants ───────────────────────────

    @Test
    fun `KEY_CROSS_DEVICE_ALLOWED wire value is stable`() {
        assertEquals("cross_device_allowed", RolloutControlSnapshot.KEY_CROSS_DEVICE_ALLOWED)
    }

    @Test
    fun `KEY_DELEGATED_EXECUTION_ALLOWED wire value is stable`() {
        assertEquals("delegated_execution_allowed", RolloutControlSnapshot.KEY_DELEGATED_EXECUTION_ALLOWED)
    }

    @Test
    fun `KEY_FALLBACK_TO_LOCAL_ALLOWED wire value is stable`() {
        assertEquals("fallback_to_local_allowed", RolloutControlSnapshot.KEY_FALLBACK_TO_LOCAL_ALLOWED)
    }

    @Test
    fun `KEY_GOAL_EXECUTION_ALLOWED wire value is stable`() {
        assertEquals("goal_execution_allowed", RolloutControlSnapshot.KEY_GOAL_EXECUTION_ALLOWED)
    }

    // ── RolloutControlSnapshot — SAFE_DEFAULTS ────────────────────────────────

    @Test
    fun `SAFE_DEFAULTS crossDeviceAllowed is false`() {
        assertFalse(RolloutControlSnapshot.SAFE_DEFAULTS.crossDeviceAllowed)
    }

    @Test
    fun `SAFE_DEFAULTS goalExecutionAllowed is false`() {
        assertFalse(RolloutControlSnapshot.SAFE_DEFAULTS.goalExecutionAllowed)
    }

    @Test
    fun `SAFE_DEFAULTS delegatedExecutionAllowed is true`() {
        assertTrue(RolloutControlSnapshot.SAFE_DEFAULTS.delegatedExecutionAllowed)
    }

    @Test
    fun `SAFE_DEFAULTS fallbackToLocalAllowed is true`() {
        assertTrue(RolloutControlSnapshot.SAFE_DEFAULTS.fallbackToLocalAllowed)
    }

    @Test
    fun `SAFE_DEFAULTS killSwitchActive is true`() {
        assertTrue(RolloutControlSnapshot.SAFE_DEFAULTS.killSwitchActive)
    }

    @Test
    fun `SAFE_DEFAULTS isFullyEnabled is false`() {
        assertFalse(RolloutControlSnapshot.SAFE_DEFAULTS.isFullyEnabled)
    }

    // ── RolloutControlSnapshot — killSwitchActive ────────────────────────────

    @Test
    fun `killSwitchActive is true when crossDevice=false and goal=false`() {
        val snap = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        assertTrue(snap.killSwitchActive)
    }

    @Test
    fun `killSwitchActive is false when crossDevice=true`() {
        val snap = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        assertFalse(snap.killSwitchActive)
    }

    @Test
    fun `killSwitchActive is false when goal=true`() {
        val snap = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = false,
            fallbackToLocalAllowed = false,
            goalExecutionAllowed = true
        )
        assertFalse(snap.killSwitchActive)
    }

    // ── RolloutControlSnapshot — isFullyEnabled ───────────────────────────────

    @Test
    fun `isFullyEnabled is true only when all flags are true`() {
        val snap = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        assertTrue(snap.isFullyEnabled)
    }

    @Test
    fun `isFullyEnabled is false when crossDevice is false`() {
        assertFalse(
            RolloutControlSnapshot(
                crossDeviceAllowed = false,
                delegatedExecutionAllowed = true,
                fallbackToLocalAllowed = true,
                goalExecutionAllowed = true
            ).isFullyEnabled
        )
    }

    @Test
    fun `isFullyEnabled is false when delegated is false`() {
        assertFalse(
            RolloutControlSnapshot(
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = false,
                fallbackToLocalAllowed = true,
                goalExecutionAllowed = true
            ).isFullyEnabled
        )
    }

    @Test
    fun `isFullyEnabled is false when fallback is false`() {
        assertFalse(
            RolloutControlSnapshot(
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = true,
                fallbackToLocalAllowed = false,
                goalExecutionAllowed = true
            ).isFullyEnabled
        )
    }

    @Test
    fun `isFullyEnabled is false when goal is false`() {
        assertFalse(
            RolloutControlSnapshot(
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = true,
                fallbackToLocalAllowed = true,
                goalExecutionAllowed = false
            ).isFullyEnabled
        )
    }

    // ── RolloutControlSnapshot — toMetadataMap ────────────────────────────────

    @Test
    fun `toMetadataMap contains all four wire keys`() {
        val map = RolloutControlSnapshot.SAFE_DEFAULTS.toMetadataMap()
        assertTrue(map.containsKey(RolloutControlSnapshot.KEY_CROSS_DEVICE_ALLOWED))
        assertTrue(map.containsKey(RolloutControlSnapshot.KEY_DELEGATED_EXECUTION_ALLOWED))
        assertTrue(map.containsKey(RolloutControlSnapshot.KEY_FALLBACK_TO_LOCAL_ALLOWED))
        assertTrue(map.containsKey(RolloutControlSnapshot.KEY_GOAL_EXECUTION_ALLOWED))
    }

    @Test
    fun `toMetadataMap values reflect field values`() {
        val snap = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = false,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        val map = snap.toMetadataMap()
        assertEquals(true, map[RolloutControlSnapshot.KEY_CROSS_DEVICE_ALLOWED])
        assertEquals(false, map[RolloutControlSnapshot.KEY_DELEGATED_EXECUTION_ALLOWED])
        assertEquals(true, map[RolloutControlSnapshot.KEY_FALLBACK_TO_LOCAL_ALLOWED])
        assertEquals(false, map[RolloutControlSnapshot.KEY_GOAL_EXECUTION_ALLOWED])
    }

    @Test
    fun `toMetadataMap does not include killSwitchActive`() {
        val map = RolloutControlSnapshot.SAFE_DEFAULTS.toMetadataMap()
        assertFalse(map.containsKey("kill_switch_active"))
    }

    // ── RolloutControlSnapshot — from(settings) factory ──────────────────────

    @Test
    fun `from reads crossDeviceEnabled from settings`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        assertTrue(RolloutControlSnapshot.from(settings).crossDeviceAllowed)
    }

    @Test
    fun `from reads goalExecutionEnabled from settings`() {
        val settings = InMemoryAppSettings(goalExecutionEnabled = true)
        assertTrue(RolloutControlSnapshot.from(settings).goalExecutionAllowed)
    }

    @Test
    fun `from reads delegatedExecutionAllowed from settings`() {
        val settings = InMemoryAppSettings(delegatedExecutionAllowed = false)
        assertFalse(RolloutControlSnapshot.from(settings).delegatedExecutionAllowed)
    }

    @Test
    fun `from reads fallbackToLocalAllowed from settings`() {
        val settings = InMemoryAppSettings(fallbackToLocalAllowed = false)
        assertFalse(RolloutControlSnapshot.from(settings).fallbackToLocalAllowed)
    }

    @Test
    fun `from with all defaults matches expected baseline`() {
        val settings = InMemoryAppSettings()  // all defaults
        val snap = RolloutControlSnapshot.from(settings)
        // crossDeviceEnabled default = false
        assertFalse(snap.crossDeviceAllowed)
        // goalExecutionEnabled default = false
        assertFalse(snap.goalExecutionAllowed)
        // delegatedExecutionAllowed default = true (backward compat)
        assertTrue(snap.delegatedExecutionAllowed)
        // fallbackToLocalAllowed default = true (backward compat)
        assertTrue(snap.fallbackToLocalAllowed)
        // With both primary flags off, kill-switch is active by default
        assertTrue(snap.killSwitchActive)
    }

    // ── RolloutControlSnapshot — data class copy semantics ───────────────────

    @Test
    fun `copy preserves all fields`() {
        val original = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = false,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        val copied = original.copy(delegatedExecutionAllowed = true)
        assertTrue(copied.crossDeviceAllowed)
        assertTrue(copied.delegatedExecutionAllowed)
        assertTrue(copied.fallbackToLocalAllowed)
        assertTrue(copied.goalExecutionAllowed)
    }

    // ── AppSettings new fields — safe defaults ────────────────────────────────

    @Test
    fun `delegatedExecutionAllowed defaults to true in InMemoryAppSettings`() {
        assertTrue(InMemoryAppSettings().delegatedExecutionAllowed)
    }

    @Test
    fun `fallbackToLocalAllowed defaults to true in InMemoryAppSettings`() {
        assertTrue(InMemoryAppSettings().fallbackToLocalAllowed)
    }

    @Test
    fun `delegatedExecutionAllowed can be set to false`() {
        val settings = InMemoryAppSettings()
        settings.delegatedExecutionAllowed = false
        assertFalse(settings.delegatedExecutionAllowed)
    }

    @Test
    fun `fallbackToLocalAllowed can be set to false`() {
        val settings = InMemoryAppSettings()
        settings.fallbackToLocalAllowed = false
        assertFalse(settings.fallbackToLocalAllowed)
    }

    @Test
    fun `changing delegatedExecutionAllowed does not affect other flags`() {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            goalExecutionEnabled = true,
            fallbackToLocalAllowed = true
        )
        settings.delegatedExecutionAllowed = false
        assertTrue(settings.crossDeviceEnabled)
        assertTrue(settings.goalExecutionEnabled)
        assertTrue(settings.fallbackToLocalAllowed)
    }

    @Test
    fun `changing fallbackToLocalAllowed does not affect other flags`() {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            goalExecutionEnabled = true,
            delegatedExecutionAllowed = true
        )
        settings.fallbackToLocalAllowed = false
        assertTrue(settings.crossDeviceEnabled)
        assertTrue(settings.goalExecutionEnabled)
        assertTrue(settings.delegatedExecutionAllowed)
    }

    // ── GalaxyLogger PR-31 tag constants ──────────────────────────────────────

    @Test
    fun `TAG_ROLLOUT_CONTROL value is GALAXY ROLLOUT CONTROL`() {
        assertEquals("GALAXY:ROLLOUT:CONTROL", GalaxyLogger.TAG_ROLLOUT_CONTROL)
    }

    @Test
    fun `TAG_KILL_SWITCH value is GALAXY KILL SWITCH`() {
        assertEquals("GALAXY:KILL:SWITCH", GalaxyLogger.TAG_KILL_SWITCH)
    }

    @Test
    fun `PR-31 tags are distinct from each other`() {
        assertFalse(GalaxyLogger.TAG_ROLLOUT_CONTROL == GalaxyLogger.TAG_KILL_SWITCH)
    }

    @Test
    fun `PR-31 tags are distinct from all pre-PR31 tags`() {
        val prePr31Tags = setOf(
            GalaxyLogger.TAG_CONNECT,
            GalaxyLogger.TAG_DISCONNECT,
            GalaxyLogger.TAG_RECONNECT,
            GalaxyLogger.TAG_TASK_RECV,
            GalaxyLogger.TAG_TASK_EXEC,
            GalaxyLogger.TAG_TASK_RETURN,
            GalaxyLogger.TAG_READINESS,
            GalaxyLogger.TAG_DEGRADED,
            GalaxyLogger.TAG_ERROR,
            GalaxyLogger.TAG_LOCAL_LOOP_START,
            GalaxyLogger.TAG_LOCAL_LOOP_STEP,
            GalaxyLogger.TAG_LOCAL_LOOP_PLAN,
            GalaxyLogger.TAG_LOCAL_LOOP_DONE,
            GalaxyLogger.TAG_EXEC_ROUTE,
            GalaxyLogger.TAG_SETUP_RECOVERY,
            GalaxyLogger.TAG_RECONNECT_OUTCOME,
            GalaxyLogger.TAG_FALLBACK_DECISION
        )
        val pr31Tags = listOf(GalaxyLogger.TAG_ROLLOUT_CONTROL, GalaxyLogger.TAG_KILL_SWITCH)
        pr31Tags.forEach { tag ->
            assertFalse(
                "PR-31 tag '$tag' must not clash with a pre-PR31 tag",
                prePr31Tags.contains(tag)
            )
        }
    }

    // ── RuntimeController.rolloutControlSnapshot ──────────────────────────────

    @Test
    fun `initial rolloutControlSnapshot reflects settings at construction`() {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = false,
            goalExecutionEnabled = false,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true
        )
        val (controller, _) = buildController(settings)
        val snap = controller.rolloutControlSnapshot.value
        assertFalse(snap.crossDeviceAllowed)
        assertFalse(snap.goalExecutionAllowed)
        assertTrue(snap.delegatedExecutionAllowed)
        assertTrue(snap.fallbackToLocalAllowed)
    }

    @Test
    fun `refreshRolloutControlSnapshot updates snapshot after settings mutation`() {
        val settings = InMemoryAppSettings()
        val (controller, _) = buildController(settings)

        settings.delegatedExecutionAllowed = false
        controller.refreshRolloutControlSnapshot()

        assertFalse(controller.rolloutControlSnapshot.value.delegatedExecutionAllowed)
    }

    @Test
    fun `refreshRolloutControlSnapshot reflects all four flags`() {
        val settings = InMemoryAppSettings()
        val (controller, _) = buildController(settings)

        settings.crossDeviceEnabled = true
        settings.goalExecutionEnabled = true
        settings.delegatedExecutionAllowed = true
        settings.fallbackToLocalAllowed = true
        controller.refreshRolloutControlSnapshot()

        val snap = controller.rolloutControlSnapshot.value
        assertTrue(snap.crossDeviceAllowed)
        assertTrue(snap.goalExecutionAllowed)
        assertTrue(snap.delegatedExecutionAllowed)
        assertTrue(snap.fallbackToLocalAllowed)
        assertTrue(snap.isFullyEnabled)
    }

    @Test
    fun `initial snapshot has killSwitchActive=true with default settings`() {
        val (controller, _) = buildController(InMemoryAppSettings())
        assertTrue(controller.rolloutControlSnapshot.value.killSwitchActive)
    }

    // ── RuntimeController.applyKillSwitch ────────────────────────────────────

    @Test
    fun `applyKillSwitch sets crossDeviceEnabled=false in settings`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true, goalExecutionEnabled = true)
        val (controller, _) = buildController(settings)
        controller.applyKillSwitch()
        assertFalse(settings.crossDeviceEnabled)
    }

    @Test
    fun `applyKillSwitch sets goalExecutionEnabled=false in settings`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true, goalExecutionEnabled = true)
        val (controller, _) = buildController(settings)
        controller.applyKillSwitch()
        assertFalse(settings.goalExecutionEnabled)
    }

    @Test
    fun `applyKillSwitch transitions runtime to LocalOnly`() {
        val settings = InMemoryAppSettings()
        val (controller, _) = buildController(settings)
        controller.applyKillSwitch()
        assertEquals(RuntimeController.RuntimeState.LocalOnly, controller.state.value)
    }

    @Test
    fun `applyKillSwitch does NOT clear delegatedExecutionAllowed`() {
        val settings = InMemoryAppSettings(delegatedExecutionAllowed = true)
        val (controller, _) = buildController(settings)
        controller.applyKillSwitch()
        // delegatedExecutionAllowed must remain true so re-enabling only requires primary flags
        assertTrue(settings.delegatedExecutionAllowed)
    }

    @Test
    fun `applyKillSwitch does NOT clear fallbackToLocalAllowed`() {
        val settings = InMemoryAppSettings(fallbackToLocalAllowed = true)
        val (controller, _) = buildController(settings)
        controller.applyKillSwitch()
        assertTrue(settings.fallbackToLocalAllowed)
    }

    @Test
    fun `snapshot killSwitchActive is true after applyKillSwitch`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true, goalExecutionEnabled = true)
        val (controller, _) = buildController(settings)
        controller.applyKillSwitch()
        assertTrue(controller.rolloutControlSnapshot.value.killSwitchActive)
    }

    @Test
    fun `applyKillSwitch emits TAG_KILL_SWITCH log entry`() {
        GalaxyLogger.clear()
        val (controller, _) = buildController(InMemoryAppSettings())
        controller.applyKillSwitch("test kill switch")
        val killSwitchEntries = GalaxyLogger.getEntries()
            .filter { it.tag == GalaxyLogger.TAG_KILL_SWITCH }
        assertTrue(
            "Expected at least one TAG_KILL_SWITCH entry after applyKillSwitch",
            killSwitchEntries.isNotEmpty()
        )
    }

    @Test
    fun `applyKillSwitch emits TAG_ROLLOUT_CONTROL entries for disabled flags`() {
        GalaxyLogger.clear()
        val settings = InMemoryAppSettings(crossDeviceEnabled = true, goalExecutionEnabled = true)
        val (controller, _) = buildController(settings)
        controller.applyKillSwitch()
        val rolloutEntries = GalaxyLogger.getEntries()
            .filter { it.tag == GalaxyLogger.TAG_ROLLOUT_CONTROL }
        assertTrue(
            "Expected TAG_ROLLOUT_CONTROL entries after applyKillSwitch",
            rolloutEntries.isNotEmpty()
        )
        // Each rollout entry should have source="kill_switch"
        rolloutEntries.forEach { entry ->
            assertEquals("kill_switch", entry.fields["source"])
        }
    }

    @Test
    fun `applyKillSwitch with custom reason includes reason in log entry`() {
        GalaxyLogger.clear()
        val (controller, _) = buildController(InMemoryAppSettings())
        controller.applyKillSwitch("emergency disable for deployment PR-31")
        val entry = GalaxyLogger.getEntries()
            .firstOrNull { it.tag == GalaxyLogger.TAG_KILL_SWITCH }
        assertNotNull("TAG_KILL_SWITCH entry must be present", entry)
        assertEquals("emergency disable for deployment PR-31", entry!!.fields["reason"])
    }

    // ── TakeoverEligibilityAssessor — BLOCKED_DELEGATED_EXECUTION_DISABLED ───

    @Test
    fun `BLOCKED_DELEGATED_EXECUTION_DISABLED wireValue is stable`() {
        assertEquals(
            "delegated_execution_disabled",
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_DELEGATED_EXECUTION_DISABLED.reason
        )
    }

    @Test
    fun `assess returns BLOCKED_DELEGATED_EXECUTION_DISABLED when delegatedExecutionAllowed=false`() {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            goalExecutionEnabled = true,
            delegatedExecutionAllowed = false,
            accessibilityReady = true,
            overlayReady = true
        )
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(makeTakeoverEnvelope())
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_DELEGATED_EXECUTION_DISABLED,
            result.outcome
        )
    }

    @Test
    fun `fully enabled settings with delegatedExecutionAllowed=true yields ELIGIBLE`() {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            goalExecutionEnabled = true,
            delegatedExecutionAllowed = true,
            accessibilityReady = true,
            overlayReady = true
        )
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(makeTakeoverEnvelope())
        assertTrue(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.ELIGIBLE,
            result.outcome
        )
    }

    @Test
    fun `BLOCKED_CROSS_DEVICE_DISABLED takes precedence over BLOCKED_DELEGATED_EXECUTION_DISABLED`() {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = false,
            goalExecutionEnabled = true,
            delegatedExecutionAllowed = false,
            accessibilityReady = true,
            overlayReady = true
        )
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(makeTakeoverEnvelope())
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_CROSS_DEVICE_DISABLED,
            result.outcome
        )
    }

    @Test
    fun `BLOCKED_GOAL_EXECUTION_DISABLED takes precedence over BLOCKED_DELEGATED_EXECUTION_DISABLED`() {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            goalExecutionEnabled = false,
            delegatedExecutionAllowed = false,
            accessibilityReady = true,
            overlayReady = true
        )
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(makeTakeoverEnvelope())
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_GOAL_EXECUTION_DISABLED,
            result.outcome
        )
    }

    @Test
    fun `BLOCKED_DELEGATED_EXECUTION_DISABLED takes precedence over BLOCKED_ACCESSIBILITY_NOT_READY`() {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            goalExecutionEnabled = true,
            delegatedExecutionAllowed = false,
            accessibilityReady = false,
            overlayReady = false
        )
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(makeTakeoverEnvelope())
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_DELEGATED_EXECUTION_DISABLED,
            result.outcome
        )
    }

    // ── MainUiState.rolloutControlSnapshot ───────────────────────────────────

    @Test
    fun `rolloutControlSnapshot defaults to null in MainUiState`() {
        assertNull(MainUiState().rolloutControlSnapshot)
    }

    @Test
    fun `rolloutControlSnapshot can be set via copy`() {
        val snap = RolloutControlSnapshot.SAFE_DEFAULTS
        val state = MainUiState().copy(rolloutControlSnapshot = snap)
        assertNotNull(state.rolloutControlSnapshot)
        assertFalse(state.rolloutControlSnapshot!!.crossDeviceAllowed)
    }

    @Test
    fun `rolloutControlSnapshot fields are preserved through copy operations`() {
        val snap = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = false,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        val original = MainUiState(rolloutControlSnapshot = snap)
        val copied = original.copy(isLoading = true, isConnected = true)
        assertNotNull(copied.rolloutControlSnapshot)
        assertTrue(copied.rolloutControlSnapshot!!.crossDeviceAllowed)
        assertFalse(copied.rolloutControlSnapshot!!.delegatedExecutionAllowed)
        assertTrue(copied.rolloutControlSnapshot!!.fallbackToLocalAllowed)
        assertTrue(copied.rolloutControlSnapshot!!.goalExecutionAllowed)
    }

    @Test
    fun `killSwitchActive is correctly computed from set snapshot`() {
        val snap = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        val state = MainUiState(rolloutControlSnapshot = snap)
        assertTrue(state.rolloutControlSnapshot!!.killSwitchActive)
    }

    @Test
    fun `rolloutControlSnapshot snapshot survives non-related MainUiState updates`() {
        val snap = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        var state = MainUiState(rolloutControlSnapshot = snap)
        // Simulate multiple state updates from different sources
        state = state.copy(isLoading = false)
        state = state.copy(isConnected = true)
        state = state.copy(error = null)
        assertNotNull(state.rolloutControlSnapshot)
        assertTrue(state.rolloutControlSnapshot!!.isFullyEnabled)
    }

    // ── Rollout control — behavioral determinism under kill-switch ────────────

    @Test
    fun `applyKillSwitch twice is idempotent`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true, goalExecutionEnabled = true)
        val (controller, _) = buildController(settings)
        controller.applyKillSwitch("first call")
        controller.applyKillSwitch("second call")
        // After two calls, flags are still false (idempotent)
        assertFalse(settings.crossDeviceEnabled)
        assertFalse(settings.goalExecutionEnabled)
        assertEquals(RuntimeController.RuntimeState.LocalOnly, controller.state.value)
        assertTrue(controller.rolloutControlSnapshot.value.killSwitchActive)
    }

    @Test
    fun `rollout snapshot is deterministically consistent with settings after kill switch`() {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            goalExecutionEnabled = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true
        )
        val (controller, _) = buildController(settings)
        controller.applyKillSwitch()

        val snap = controller.rolloutControlSnapshot.value
        // Primary flags must be false
        assertFalse(snap.crossDeviceAllowed)
        assertFalse(snap.goalExecutionAllowed)
        // Fine-grained flags must be unchanged (preserved for easy re-enable)
        assertTrue(snap.delegatedExecutionAllowed)
        assertTrue(snap.fallbackToLocalAllowed)
        // Kill switch must be active
        assertTrue(snap.killSwitchActive)
        // Fully enabled must be false
        assertFalse(snap.isFullyEnabled)
    }

    @Test
    fun `rolloutControlSnapshot initial value flow has snapshot immediately available`() {
        val settings = InMemoryAppSettings()
        val (controller, _) = buildController(settings)
        // The snapshot must be available synchronously from the StateFlow without waiting
        val snap = controller.rolloutControlSnapshot.value
        assertNotNull(snap)
    }
}

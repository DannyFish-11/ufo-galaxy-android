package com.ufo.galaxy.runtime

import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.loop.ExecutorBridge
import com.ufo.galaxy.loop.LocalPlanner
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.network.GalaxyWebSocketClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for the **host-facing reuse state** of [AttachedRuntimeSession] and
 * [RuntimeController.currentSessionSnapshot] (PR-17 — host-facing attached session
 * reuse state alignment).
 *
 * These tests verify that the host can always obtain a stable, authoritative, and
 * complete snapshot of Android attached-session reuse state — covering all fields
 * required for canonical dispatch decisions on the main-orchestrator (host) side.
 *
 * ## Test matrix
 *
 * ### toMetadataMap — host-facing required fields
 *  - Map always contains KEY_DEVICE_ID on a fresh session.
 *  - KEY_DEVICE_ID value matches the session's deviceId.
 *  - Map always contains KEY_IS_REUSE_VALID on a fresh session.
 *  - KEY_IS_REUSE_VALID is true when state is ATTACHED.
 *  - KEY_IS_REUSE_VALID is false when state is DETACHING.
 *  - KEY_IS_REUSE_VALID is false when state is DETACHED.
 *
 * ### KEY_DEVICE_ID constant value
 *  - KEY_DEVICE_ID is "attached_session_device_id".
 *
 * ### KEY_IS_REUSE_VALID constant value
 *  - KEY_IS_REUSE_VALID is "attached_session_is_reuse_valid".
 *
 * ### Snapshot completeness — all six required host fields
 *  - Snapshot contains all six required host-facing keys on a fresh session.
 *  - Snapshot contains all six required host-facing keys after DETACHING transition.
 *  - Snapshot contains all six required host-facing keys after DETACHED transition.
 *
 * ### Outward state consistency across lifecycle causes
 *  - attach: is_reuse_valid=true, detach_cause absent.
 *  - beginDetaching(EXPLICIT_DETACH): is_reuse_valid=false, detach_cause=explicit_detach.
 *  - beginDetaching(DISCONNECT): is_reuse_valid=false, detach_cause=disconnect.
 *  - beginDetaching(DISABLE): is_reuse_valid=false, detach_cause=disable.
 *  - beginDetaching(INVALIDATION): is_reuse_valid=false, detach_cause=invalidation.
 *  - detachedWith(EXPLICIT_DETACH): is_reuse_valid=false, detach_cause=explicit_detach.
 *  - detachedWith(DISCONNECT): is_reuse_valid=false, detach_cause=disconnect.
 *  - detachedWith(DISABLE): is_reuse_valid=false, detach_cause=disable.
 *  - detachedWith(INVALIDATION): is_reuse_valid=false, detach_cause=invalidation.
 *
 * ### delegated_execution_count in snapshot
 *  - Snapshot reflects updated count after withExecutionAccepted.
 *  - is_reuse_valid remains true in snapshot after withExecutionAccepted.
 *
 * ### RuntimeController.currentSessionSnapshot
 *  - Returns null when no session exists.
 *  - Returns null after stop() with no prior session.
 */
class AttachedRuntimeSessionHostFacingSnapshotTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun freshSession(
        hostId: String = "host-pr17",
        deviceId: String = "pixel-8-pro"
    ) = AttachedRuntimeSession.create(hostId = hostId, deviceId = deviceId)

    /** The six field keys the host MUST always receive. */
    private val requiredHostKeys = setOf(
        AttachedRuntimeSession.KEY_SESSION_ID,
        AttachedRuntimeSession.KEY_DEVICE_ID,
        AttachedRuntimeSession.KEY_STATE,
        AttachedRuntimeSession.KEY_DELEGATED_EXECUTION_COUNT,
        AttachedRuntimeSession.KEY_IS_REUSE_VALID,
        AttachedRuntimeSession.KEY_HOST_ID
    )

    // ── KEY_DEVICE_ID constant ────────────────────────────────────────────────

    @Test
    fun `KEY_DEVICE_ID constant is attached_session_device_id`() {
        assertEquals("attached_session_device_id", AttachedRuntimeSession.KEY_DEVICE_ID)
    }

    // ── KEY_IS_REUSE_VALID constant ───────────────────────────────────────────

    @Test
    fun `KEY_IS_REUSE_VALID constant is attached_session_is_reuse_valid`() {
        assertEquals("attached_session_is_reuse_valid", AttachedRuntimeSession.KEY_IS_REUSE_VALID)
    }

    // ── toMetadataMap — device_id ─────────────────────────────────────────────

    @Test
    fun `toMetadataMap always contains KEY_DEVICE_ID on fresh session`() {
        val map = freshSession().toMetadataMap()
        assertTrue(
            "toMetadataMap must always contain KEY_DEVICE_ID so the host can identify the device",
            map.containsKey(AttachedRuntimeSession.KEY_DEVICE_ID)
        )
    }

    @Test
    fun `toMetadataMap KEY_DEVICE_ID value matches session deviceId`() {
        val session = freshSession(deviceId = "samsung-galaxy-s24")
        val map = session.toMetadataMap()
        assertEquals(
            "KEY_DEVICE_ID in metadata must equal the session's deviceId field",
            "samsung-galaxy-s24",
            map[AttachedRuntimeSession.KEY_DEVICE_ID]
        )
    }

    @Test
    fun `toMetadataMap KEY_DEVICE_ID is preserved after withExecutionAccepted`() {
        val session = freshSession(deviceId = "my-device")
        val map = session.withExecutionAccepted().toMetadataMap()
        assertEquals("my-device", map[AttachedRuntimeSession.KEY_DEVICE_ID])
    }

    @Test
    fun `toMetadataMap KEY_DEVICE_ID is present after DETACHING transition`() {
        val map = freshSession(deviceId = "dev-42")
            .beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
            .toMetadataMap()
        assertTrue(map.containsKey(AttachedRuntimeSession.KEY_DEVICE_ID))
        assertEquals("dev-42", map[AttachedRuntimeSession.KEY_DEVICE_ID])
    }

    @Test
    fun `toMetadataMap KEY_DEVICE_ID is present after DETACHED transition`() {
        val map = freshSession(deviceId = "dev-99")
            .detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
            .toMetadataMap()
        assertTrue(map.containsKey(AttachedRuntimeSession.KEY_DEVICE_ID))
        assertEquals("dev-99", map[AttachedRuntimeSession.KEY_DEVICE_ID])
    }

    // ── toMetadataMap — is_reuse_valid ────────────────────────────────────────

    @Test
    fun `toMetadataMap always contains KEY_IS_REUSE_VALID on fresh session`() {
        val map = freshSession().toMetadataMap()
        assertTrue(
            "toMetadataMap must always include KEY_IS_REUSE_VALID for host-side dispatch decisions",
            map.containsKey(AttachedRuntimeSession.KEY_IS_REUSE_VALID)
        )
    }

    @Test
    fun `toMetadataMap KEY_IS_REUSE_VALID is true when state is ATTACHED`() {
        val map = freshSession().toMetadataMap()
        assertEquals(
            "is_reuse_valid must be true for an ATTACHED session",
            true,
            map[AttachedRuntimeSession.KEY_IS_REUSE_VALID]
        )
    }

    @Test
    fun `toMetadataMap KEY_IS_REUSE_VALID is false when state is DETACHING`() {
        val map = freshSession()
            .beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
            .toMetadataMap()
        assertEquals(
            "is_reuse_valid must be false for a DETACHING session",
            false,
            map[AttachedRuntimeSession.KEY_IS_REUSE_VALID]
        )
    }

    @Test
    fun `toMetadataMap KEY_IS_REUSE_VALID is false when state is DETACHED`() {
        val map = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
            .toMetadataMap()
        assertEquals(
            "is_reuse_valid must be false for a DETACHED session",
            false,
            map[AttachedRuntimeSession.KEY_IS_REUSE_VALID]
        )
    }

    @Test
    fun `toMetadataMap KEY_IS_REUSE_VALID remains true after withExecutionAccepted`() {
        val map = freshSession().withExecutionAccepted().toMetadataMap()
        assertEquals(
            "is_reuse_valid must still be true after accepting a delegated execution",
            true,
            map[AttachedRuntimeSession.KEY_IS_REUSE_VALID]
        )
    }

    // ── Snapshot completeness ─────────────────────────────────────────────────

    @Test
    fun `toMetadataMap contains all six required host-facing keys on fresh session`() {
        val map = freshSession().toMetadataMap()
        val missing = requiredHostKeys - map.keys
        assertTrue(
            "toMetadataMap must contain all required host-facing keys; missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `toMetadataMap contains all six required host-facing keys after DETACHING`() {
        val map = freshSession()
            .beginDetaching(AttachedRuntimeSession.DetachCause.INVALIDATION)
            .toMetadataMap()
        val missing = requiredHostKeys - map.keys
        assertTrue(
            "toMetadataMap must still carry all required keys in DETACHING state; missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `toMetadataMap contains all six required host-facing keys after DETACHED`() {
        val map = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
            .toMetadataMap()
        val missing = requiredHostKeys - map.keys
        assertTrue(
            "toMetadataMap must still carry all required keys in DETACHED state; missing: $missing",
            missing.isEmpty()
        )
    }

    // ── Outward state consistency — attach ────────────────────────────────────

    @Test
    fun `attached session snapshot has is_reuse_valid=true and no detach_cause`() {
        val map = freshSession().toMetadataMap()
        assertEquals(true, map[AttachedRuntimeSession.KEY_IS_REUSE_VALID])
        assertFalse(
            "KEY_DETACH_CAUSE must be absent for an active attached session",
            map.containsKey(AttachedRuntimeSession.KEY_DETACH_CAUSE)
        )
        assertEquals("attached", map[AttachedRuntimeSession.KEY_STATE])
    }

    // ── Outward state consistency — beginDetaching (all four causes) ──────────

    @Test
    fun `beginDetaching EXPLICIT_DETACH snapshot has is_reuse_valid=false and correct cause`() {
        val map = freshSession()
            .beginDetaching(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
            .toMetadataMap()
        assertEquals(false, map[AttachedRuntimeSession.KEY_IS_REUSE_VALID])
        assertEquals("explicit_detach", map[AttachedRuntimeSession.KEY_DETACH_CAUSE])
        assertEquals("detaching", map[AttachedRuntimeSession.KEY_STATE])
    }

    @Test
    fun `beginDetaching DISCONNECT snapshot has is_reuse_valid=false and correct cause`() {
        val map = freshSession()
            .beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
            .toMetadataMap()
        assertEquals(false, map[AttachedRuntimeSession.KEY_IS_REUSE_VALID])
        assertEquals("disconnect", map[AttachedRuntimeSession.KEY_DETACH_CAUSE])
    }

    @Test
    fun `beginDetaching DISABLE snapshot has is_reuse_valid=false and correct cause`() {
        val map = freshSession()
            .beginDetaching(AttachedRuntimeSession.DetachCause.DISABLE)
            .toMetadataMap()
        assertEquals(false, map[AttachedRuntimeSession.KEY_IS_REUSE_VALID])
        assertEquals("disable", map[AttachedRuntimeSession.KEY_DETACH_CAUSE])
    }

    @Test
    fun `beginDetaching INVALIDATION snapshot has is_reuse_valid=false and correct cause`() {
        val map = freshSession()
            .beginDetaching(AttachedRuntimeSession.DetachCause.INVALIDATION)
            .toMetadataMap()
        assertEquals(false, map[AttachedRuntimeSession.KEY_IS_REUSE_VALID])
        assertEquals("invalidation", map[AttachedRuntimeSession.KEY_DETACH_CAUSE])
    }

    // ── Outward state consistency — detachedWith (all four causes) ────────────

    @Test
    fun `detachedWith EXPLICIT_DETACH snapshot has is_reuse_valid=false and correct cause`() {
        val map = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
            .toMetadataMap()
        assertEquals(false, map[AttachedRuntimeSession.KEY_IS_REUSE_VALID])
        assertEquals("explicit_detach", map[AttachedRuntimeSession.KEY_DETACH_CAUSE])
        assertEquals("detached", map[AttachedRuntimeSession.KEY_STATE])
    }

    @Test
    fun `detachedWith DISCONNECT snapshot has is_reuse_valid=false and correct cause`() {
        val map = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
            .toMetadataMap()
        assertEquals(false, map[AttachedRuntimeSession.KEY_IS_REUSE_VALID])
        assertEquals("disconnect", map[AttachedRuntimeSession.KEY_DETACH_CAUSE])
    }

    @Test
    fun `detachedWith DISABLE snapshot has is_reuse_valid=false and correct cause`() {
        val map = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
            .toMetadataMap()
        assertEquals(false, map[AttachedRuntimeSession.KEY_IS_REUSE_VALID])
        assertEquals("disable", map[AttachedRuntimeSession.KEY_DETACH_CAUSE])
    }

    @Test
    fun `detachedWith INVALIDATION snapshot has is_reuse_valid=false and correct cause`() {
        val map = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
            .toMetadataMap()
        assertEquals(false, map[AttachedRuntimeSession.KEY_IS_REUSE_VALID])
        assertEquals("invalidation", map[AttachedRuntimeSession.KEY_DETACH_CAUSE])
    }

    // ── delegated_execution_count in snapshot ─────────────────────────────────

    @Test
    fun `snapshot delegated_execution_count reflects withExecutionAccepted calls`() {
        val map = freshSession()
            .withExecutionAccepted()
            .withExecutionAccepted()
            .withExecutionAccepted()
            .toMetadataMap()
        assertEquals(
            "Snapshot execution count must match the number of accepted delegations",
            3,
            map[AttachedRuntimeSession.KEY_DELEGATED_EXECUTION_COUNT]
        )
    }

    @Test
    fun `snapshot sessionId is stable across multiple withExecutionAccepted calls`() {
        val original = freshSession()
        val map = original
            .withExecutionAccepted()
            .withExecutionAccepted()
            .toMetadataMap()
        assertEquals(
            "Session ID in snapshot must be stable — host must not treat each task acceptance as a new session",
            original.sessionId,
            map[AttachedRuntimeSession.KEY_SESSION_ID]
        )
    }

    // ── RuntimeController.currentSessionSnapshot ──────────────────────────────

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun buildLoopController(): LoopController {
        val modelsDir = tmpFolder.newFolder("models")
        return LoopController(
            localPlanner = LocalPlanner(object : LocalPlannerService {
                override fun loadModel() = true
                override fun unloadModel() {}
                override fun isModelLoaded() = true
                override fun plan(
                    goal: String, constraints: List<String>, screenshotBase64: String?
                ) = LocalPlannerService.PlanResult(
                    steps = listOf(LocalPlannerService.PlanStep("tap", "tap"))
                )
                override fun replan(
                    goal: String, constraints: List<String>,
                    failedStep: LocalPlannerService.PlanStep, error: String,
                    screenshotBase64: String?
                ) = LocalPlannerService.PlanResult(steps = emptyList(), error = "no replan")
            }),
            executorBridge = ExecutorBridge(
                groundingService = object : LocalGroundingService {
                    override fun loadModel() = true
                    override fun unloadModel() {}
                    override fun isModelLoaded() = true
                    override fun ground(
                        intent: String, screenshotBase64: String, width: Int, height: Int
                    ) = LocalGroundingService.GroundingResult(x = 540, y = 1170, confidence = 0.9f)
                },
                accessibilityExecutor = object : AccessibilityExecutor {
                    override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
                },
                imageScaler = NoOpImageScaler()
            ),
            screenshotProvider = object : EdgeExecutor.ScreenshotProvider {
                override fun captureJpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
                override fun screenWidth() = 1080
                override fun screenHeight() = 2340
            },
            modelAssetManager = ModelAssetManager(modelsDir),
            modelDownloader = ModelDownloader(modelsDir)
        )
    }

    private fun buildController(
        settings: InMemoryAppSettings = InMemoryAppSettings(),
        hostDescriptor: RuntimeHostDescriptor? = null,
        timeoutMs: Long = 100L
    ): RuntimeController = RuntimeController(
        webSocketClient = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        ),
        settings = settings,
        loopController = buildLoopController(),
        registrationTimeoutMs = timeoutMs,
        hostDescriptor = hostDescriptor
    )

    @Test
    fun `currentSessionSnapshot returns null when no session exists`() {
        val controller = buildController()
        assertNull(
            "currentSessionSnapshot must return null before any session is opened",
            controller.currentSessionSnapshot()
        )
    }

    @Test
    fun `currentSessionSnapshot returns null after stop with no prior session`() {
        val controller = buildController()
        controller.stop()
        assertNull(
            "currentSessionSnapshot must remain null after stop() when no session existed",
            controller.currentSessionSnapshot()
        )
    }

    @Test
    fun `currentSessionSnapshot is null after invalidateSession with no prior session`() {
        val controller = buildController()
        controller.invalidateSession()
        assertNull(
            "currentSessionSnapshot must be null when invalidateSession is a no-op",
            controller.currentSessionSnapshot()
        )
    }
}

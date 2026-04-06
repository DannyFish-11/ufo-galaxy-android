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
 * Unit tests for [AttachedRuntimeHostSessionSnapshot] and
 * [RuntimeController.currentHostSessionSnapshot] (PR-19 — canonical host-session
 * snapshot / projection).
 *
 * These tests verify that the canonical projection:
 *  1. Always includes all nine required fields regardless of session lifecycle state.
 *  2. Maps each field to the correct value at every transition (attach / detach /
 *     reconnect / invalidate).
 *  3. Derives [AttachedRuntimeHostSessionSnapshot.posture] and
 *     [AttachedRuntimeHostSessionSnapshot.invalidationReason] consistently.
 *  4. Exposes [AttachedRuntimeHostSessionSnapshot.hostRole] from the host descriptor.
 *  5. Keeps [AttachedRuntimeHostSessionSnapshot.runtimeSessionId] stable within a
 *     connection cycle and distinct from [AttachedRuntimeHostSessionSnapshot.sessionId].
 *  6. Does not break the existing delegated execution path.
 *
 * ## Test matrix
 *
 * ### toProjectionMap — always-present required keys
 *  - All eight always-present keys present on a fresh (ATTACHED) session.
 *  - All eight always-present keys present after DETACHING transition.
 *  - All eight always-present keys present after DETACHED transition (explicit detach).
 *  - All eight always-present keys present after DETACHED transition (invalidation).
 *
 * ### KEY_* constant values
 *  - KEY_SESSION_ID is "session_id".
 *  - KEY_DEVICE_ID is "device_id".
 *  - KEY_RUNTIME_SESSION_ID is "runtime_session_id".
 *  - KEY_ATTACHMENT_STATE is "attachment_state".
 *  - KEY_IS_REUSE_VALID is "is_reuse_valid".
 *  - KEY_DELEGATED_EXECUTION_COUNT is "delegated_execution_count".
 *  - KEY_INVALIDATION_REASON is "invalidation_reason".
 *  - KEY_HOST_ROLE is "host_role".
 *  - KEY_POSTURE is "posture".
 *
 * ### attachment_state field
 *  - "attached" when session state is ATTACHED.
 *  - "detaching" when session state is DETACHING.
 *  - "detached" when session state is DETACHED.
 *
 * ### posture field
 *  - "join_runtime" when session state is ATTACHED.
 *  - "control_only" when session state is DETACHING.
 *  - "control_only" when session state is DETACHED.
 *
 * ### is_reuse_valid field
 *  - true when session state is ATTACHED.
 *  - false when session state is DETACHING.
 *  - false when session state is DETACHED.
 *
 * ### invalidation_reason field
 *  - Absent when session is fresh (ATTACHED, no cause).
 *  - Absent when detach cause is EXPLICIT_DETACH.
 *  - Absent when detach cause is DISCONNECT.
 *  - Absent when detach cause is DISABLE.
 *  - Present ("invalidation") when detach cause is INVALIDATION.
 *
 * ### host_role field
 *  - Reflects FormationRole.PRIMARY when descriptor has PRIMARY role.
 *  - Reflects FormationRole.SECONDARY when descriptor has SECONDARY role.
 *  - Reflects FormationRole.SATELLITE when descriptor has SATELLITE role.
 *  - Defaults to FormationRole.DEFAULT wireValue when no descriptor provided.
 *
 * ### delegated_execution_count field
 *  - Zero for a fresh session.
 *  - Reflects withExecutionAccepted count.
 *
 * ### session_id and runtime_session_id
 *  - session_id matches AttachedRuntimeSession.sessionId.
 *  - runtime_session_id is a distinct value from session_id.
 *  - runtime_session_id is stable across withExecutionAccepted calls (same from() invocations).
 *  - session_id is stable across withExecutionAccepted calls.
 *
 * ### RuntimeController.currentHostSessionSnapshot
 *  - Returns null when no session exists.
 *  - Returns null after stop() with no prior session.
 *  - Returns null after invalidateSession() with no prior session.
 */
class AttachedRuntimeHostSessionSnapshotTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun freshSession(
        hostId: String = "host-pr19",
        deviceId: String = "pixel-9-pro"
    ) = AttachedRuntimeSession.create(hostId = hostId, deviceId = deviceId)

    private fun primaryDescriptor(deviceId: String = "pixel-9-pro") =
        RuntimeHostDescriptor.of(
            deviceId = deviceId,
            deviceRole = "phone",
            formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY
        )

    private fun secondaryDescriptor() =
        RuntimeHostDescriptor.of(
            deviceId = "secondary-device",
            deviceRole = "tablet",
            formationRole = RuntimeHostDescriptor.FormationRole.SECONDARY
        )

    private fun satelliteDescriptor() =
        RuntimeHostDescriptor.of(
            deviceId = "satellite-device",
            deviceRole = "watch",
            formationRole = RuntimeHostDescriptor.FormationRole.SATELLITE
        )

    private val runtimeSessionId = "test-runtime-session-uuid-001"

    private fun snapshot(
        session: AttachedRuntimeSession,
        descriptor: RuntimeHostDescriptor? = primaryDescriptor(),
        rsid: String = runtimeSessionId
    ) = AttachedRuntimeHostSessionSnapshot.from(
        session = session,
        runtimeSessionId = rsid,
        hostDescriptor = descriptor
    )

    /**
     * The eight always-present projection keys.  [AttachedRuntimeHostSessionSnapshot.KEY_INVALIDATION_REASON]
     * is deliberately excluded because it is conditional (present only on INVALIDATION cause).
     */
    private val requiredProjectionKeys = setOf(
        AttachedRuntimeHostSessionSnapshot.KEY_SESSION_ID,
        AttachedRuntimeHostSessionSnapshot.KEY_DEVICE_ID,
        AttachedRuntimeHostSessionSnapshot.KEY_RUNTIME_SESSION_ID,
        AttachedRuntimeHostSessionSnapshot.KEY_ATTACHMENT_STATE,
        AttachedRuntimeHostSessionSnapshot.KEY_IS_REUSE_VALID,
        AttachedRuntimeHostSessionSnapshot.KEY_DELEGATED_EXECUTION_COUNT,
        AttachedRuntimeHostSessionSnapshot.KEY_HOST_ROLE,
        AttachedRuntimeHostSessionSnapshot.KEY_POSTURE
    )

    // ── KEY_* constant values ─────────────────────────────────────────────────

    @Test
    fun `KEY_SESSION_ID is session_id`() {
        assertEquals("session_id", AttachedRuntimeHostSessionSnapshot.KEY_SESSION_ID)
    }

    @Test
    fun `KEY_DEVICE_ID is device_id`() {
        assertEquals("device_id", AttachedRuntimeHostSessionSnapshot.KEY_DEVICE_ID)
    }

    @Test
    fun `KEY_RUNTIME_SESSION_ID is runtime_session_id`() {
        assertEquals("runtime_session_id", AttachedRuntimeHostSessionSnapshot.KEY_RUNTIME_SESSION_ID)
    }

    @Test
    fun `KEY_ATTACHMENT_STATE is attachment_state`() {
        assertEquals("attachment_state", AttachedRuntimeHostSessionSnapshot.KEY_ATTACHMENT_STATE)
    }

    @Test
    fun `KEY_IS_REUSE_VALID is is_reuse_valid`() {
        assertEquals("is_reuse_valid", AttachedRuntimeHostSessionSnapshot.KEY_IS_REUSE_VALID)
    }

    @Test
    fun `KEY_DELEGATED_EXECUTION_COUNT is delegated_execution_count`() {
        assertEquals(
            "delegated_execution_count",
            AttachedRuntimeHostSessionSnapshot.KEY_DELEGATED_EXECUTION_COUNT
        )
    }

    @Test
    fun `KEY_INVALIDATION_REASON is invalidation_reason`() {
        assertEquals(
            "invalidation_reason",
            AttachedRuntimeHostSessionSnapshot.KEY_INVALIDATION_REASON
        )
    }

    @Test
    fun `KEY_HOST_ROLE is host_role`() {
        assertEquals("host_role", AttachedRuntimeHostSessionSnapshot.KEY_HOST_ROLE)
    }

    @Test
    fun `KEY_POSTURE is posture`() {
        assertEquals("posture", AttachedRuntimeHostSessionSnapshot.KEY_POSTURE)
    }

    // ── toProjectionMap — always-present required keys ────────────────────────

    @Test
    fun `toProjectionMap contains all eight required keys on fresh ATTACHED session`() {
        val map = snapshot(freshSession()).toProjectionMap()
        val missing = requiredProjectionKeys - map.keys
        assertTrue(
            "toProjectionMap must contain all required keys on a fresh session; missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `toProjectionMap contains all eight required keys after DETACHING transition`() {
        val detaching = freshSession()
            .beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val map = snapshot(detaching).toProjectionMap()
        val missing = requiredProjectionKeys - map.keys
        assertTrue(
            "toProjectionMap must contain all required keys in DETACHING state; missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `toProjectionMap contains all eight required keys after DETACHED transition via explicit detach`() {
        val detached = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val map = snapshot(detached).toProjectionMap()
        val missing = requiredProjectionKeys - map.keys
        assertTrue(
            "toProjectionMap must contain all required keys in DETACHED state; missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `toProjectionMap contains all eight required keys after DETACHED transition via invalidation`() {
        val detached = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        val map = snapshot(detached).toProjectionMap()
        val missing = requiredProjectionKeys - map.keys
        assertTrue(
            "toProjectionMap must contain all required keys in DETACHED/INVALIDATION state; missing: $missing",
            missing.isEmpty()
        )
    }

    // ── attachment_state field ────────────────────────────────────────────────

    @Test
    fun `attachment_state is attached for fresh session`() {
        val map = snapshot(freshSession()).toProjectionMap()
        assertEquals(
            "attachment_state must be 'attached' for a freshly created session",
            "attached",
            map[AttachedRuntimeHostSessionSnapshot.KEY_ATTACHMENT_STATE]
        )
    }

    @Test
    fun `attachment_state is detaching after beginDetaching`() {
        val session = freshSession()
            .beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val map = snapshot(session).toProjectionMap()
        assertEquals(
            "attachment_state must be 'detaching' after beginDetaching",
            "detaching",
            map[AttachedRuntimeHostSessionSnapshot.KEY_ATTACHMENT_STATE]
        )
    }

    @Test
    fun `attachment_state is detached after detachedWith`() {
        val session = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val map = snapshot(session).toProjectionMap()
        assertEquals(
            "attachment_state must be 'detached' after detachedWith",
            "detached",
            map[AttachedRuntimeHostSessionSnapshot.KEY_ATTACHMENT_STATE]
        )
    }

    // ── posture field ─────────────────────────────────────────────────────────

    @Test
    fun `posture is join_runtime when session is ATTACHED`() {
        val map = snapshot(freshSession()).toProjectionMap()
        assertEquals(
            "posture must be 'join_runtime' when the session is actively attached",
            SourceRuntimePosture.JOIN_RUNTIME,
            map[AttachedRuntimeHostSessionSnapshot.KEY_POSTURE]
        )
    }

    @Test
    fun `posture is control_only when session is DETACHING`() {
        val session = freshSession()
            .beginDetaching(AttachedRuntimeSession.DetachCause.DISABLE)
        val map = snapshot(session).toProjectionMap()
        assertEquals(
            "posture must be 'control_only' when the session is detaching",
            SourceRuntimePosture.CONTROL_ONLY,
            map[AttachedRuntimeHostSessionSnapshot.KEY_POSTURE]
        )
    }

    @Test
    fun `posture is control_only when session is DETACHED`() {
        val session = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val map = snapshot(session).toProjectionMap()
        assertEquals(
            "posture must be 'control_only' when the session is fully detached",
            SourceRuntimePosture.CONTROL_ONLY,
            map[AttachedRuntimeHostSessionSnapshot.KEY_POSTURE]
        )
    }

    // ── is_reuse_valid field ──────────────────────────────────────────────────

    @Test
    fun `is_reuse_valid is true when session is ATTACHED`() {
        val map = snapshot(freshSession()).toProjectionMap()
        assertEquals(
            "is_reuse_valid must be true for an actively attached session",
            true,
            map[AttachedRuntimeHostSessionSnapshot.KEY_IS_REUSE_VALID]
        )
    }

    @Test
    fun `is_reuse_valid is false when session is DETACHING`() {
        val session = freshSession()
            .beginDetaching(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val map = snapshot(session).toProjectionMap()
        assertEquals(
            "is_reuse_valid must be false when the session is detaching",
            false,
            map[AttachedRuntimeHostSessionSnapshot.KEY_IS_REUSE_VALID]
        )
    }

    @Test
    fun `is_reuse_valid is false when session is DETACHED`() {
        val session = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val map = snapshot(session).toProjectionMap()
        assertEquals(
            "is_reuse_valid must be false when the session is fully detached",
            false,
            map[AttachedRuntimeHostSessionSnapshot.KEY_IS_REUSE_VALID]
        )
    }

    // ── invalidation_reason field ─────────────────────────────────────────────

    @Test
    fun `invalidation_reason is absent for a fresh ATTACHED session`() {
        val map = snapshot(freshSession()).toProjectionMap()
        assertFalse(
            "invalidation_reason must be absent when the session is active and has no cause",
            map.containsKey(AttachedRuntimeHostSessionSnapshot.KEY_INVALIDATION_REASON)
        )
    }

    @Test
    fun `invalidation_reason is absent when detach cause is EXPLICIT_DETACH`() {
        val session = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val map = snapshot(session).toProjectionMap()
        assertFalse(
            "invalidation_reason must be absent for EXPLICIT_DETACH — it is not an invalidation",
            map.containsKey(AttachedRuntimeHostSessionSnapshot.KEY_INVALIDATION_REASON)
        )
    }

    @Test
    fun `invalidation_reason is absent when detach cause is DISCONNECT`() {
        val session = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val map = snapshot(session).toProjectionMap()
        assertFalse(
            "invalidation_reason must be absent for DISCONNECT cause",
            map.containsKey(AttachedRuntimeHostSessionSnapshot.KEY_INVALIDATION_REASON)
        )
    }

    @Test
    fun `invalidation_reason is absent when detach cause is DISABLE`() {
        val session = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        val map = snapshot(session).toProjectionMap()
        assertFalse(
            "invalidation_reason must be absent for DISABLE cause",
            map.containsKey(AttachedRuntimeHostSessionSnapshot.KEY_INVALIDATION_REASON)
        )
    }

    @Test
    fun `invalidation_reason is present and correct when detach cause is INVALIDATION`() {
        val session = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        val map = snapshot(session).toProjectionMap()
        assertTrue(
            "invalidation_reason must be present when detach cause is INVALIDATION",
            map.containsKey(AttachedRuntimeHostSessionSnapshot.KEY_INVALIDATION_REASON)
        )
        assertEquals(
            "invalidation_reason value must be the INVALIDATION wire value",
            AttachedRuntimeSession.DetachCause.INVALIDATION.wireValue,
            map[AttachedRuntimeHostSessionSnapshot.KEY_INVALIDATION_REASON]
        )
    }

    @Test
    fun `invalidation_reason is present during DETACHING with INVALIDATION cause`() {
        val session = freshSession()
            .beginDetaching(AttachedRuntimeSession.DetachCause.INVALIDATION)
        val map = snapshot(session).toProjectionMap()
        assertTrue(
            "invalidation_reason must be present even in DETACHING state when cause is INVALIDATION",
            map.containsKey(AttachedRuntimeHostSessionSnapshot.KEY_INVALIDATION_REASON)
        )
    }

    // ── host_role field ───────────────────────────────────────────────────────

    @Test
    fun `host_role reflects PRIMARY formation role from descriptor`() {
        val map = snapshot(freshSession(), descriptor = primaryDescriptor()).toProjectionMap()
        assertEquals(
            "host_role must be 'primary' when descriptor has PRIMARY formation role",
            RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue,
            map[AttachedRuntimeHostSessionSnapshot.KEY_HOST_ROLE]
        )
    }

    @Test
    fun `host_role reflects SECONDARY formation role from descriptor`() {
        val map = snapshot(freshSession(), descriptor = secondaryDescriptor()).toProjectionMap()
        assertEquals(
            "host_role must be 'secondary' when descriptor has SECONDARY formation role",
            RuntimeHostDescriptor.FormationRole.SECONDARY.wireValue,
            map[AttachedRuntimeHostSessionSnapshot.KEY_HOST_ROLE]
        )
    }

    @Test
    fun `host_role reflects SATELLITE formation role from descriptor`() {
        val map = snapshot(freshSession(), descriptor = satelliteDescriptor()).toProjectionMap()
        assertEquals(
            "host_role must be 'satellite' when descriptor has SATELLITE formation role",
            RuntimeHostDescriptor.FormationRole.SATELLITE.wireValue,
            map[AttachedRuntimeHostSessionSnapshot.KEY_HOST_ROLE]
        )
    }

    @Test
    fun `host_role defaults to FormationRole DEFAULT wireValue when no descriptor provided`() {
        val map = snapshot(freshSession(), descriptor = null).toProjectionMap()
        assertEquals(
            "host_role must default to FormationRole.DEFAULT wireValue when no descriptor is available",
            RuntimeHostDescriptor.FormationRole.DEFAULT.wireValue,
            map[AttachedRuntimeHostSessionSnapshot.KEY_HOST_ROLE]
        )
    }

    // ── delegated_execution_count field ──────────────────────────────────────

    @Test
    fun `delegated_execution_count is zero for a fresh session`() {
        val map = snapshot(freshSession()).toProjectionMap()
        assertEquals(
            "delegated_execution_count must be zero for a freshly created session",
            0,
            map[AttachedRuntimeHostSessionSnapshot.KEY_DELEGATED_EXECUTION_COUNT]
        )
    }

    @Test
    fun `delegated_execution_count reflects withExecutionAccepted calls`() {
        val session = freshSession()
            .withExecutionAccepted()
            .withExecutionAccepted()
            .withExecutionAccepted()
        val map = snapshot(session).toProjectionMap()
        assertEquals(
            "delegated_execution_count must reflect three accepted delegated executions",
            3,
            map[AttachedRuntimeHostSessionSnapshot.KEY_DELEGATED_EXECUTION_COUNT]
        )
    }

    @Test
    fun `is_reuse_valid remains true after withExecutionAccepted`() {
        val session = freshSession().withExecutionAccepted().withExecutionAccepted()
        val map = snapshot(session).toProjectionMap()
        assertEquals(
            "is_reuse_valid must remain true after accepting delegated executions",
            true,
            map[AttachedRuntimeHostSessionSnapshot.KEY_IS_REUSE_VALID]
        )
    }

    // ── session_id and runtime_session_id ─────────────────────────────────────

    @Test
    fun `session_id matches AttachedRuntimeSession sessionId`() {
        val session = freshSession()
        val map = snapshot(session).toProjectionMap()
        assertEquals(
            "session_id in projection must match AttachedRuntimeSession.sessionId",
            session.sessionId,
            map[AttachedRuntimeHostSessionSnapshot.KEY_SESSION_ID]
        )
    }

    @Test
    fun `runtime_session_id matches the supplied runtimeSessionId`() {
        val customRsid = "custom-rsid-abc-123"
        val map = snapshot(freshSession(), rsid = customRsid).toProjectionMap()
        assertEquals(
            "runtime_session_id in projection must match the supplied runtimeSessionId",
            customRsid,
            map[AttachedRuntimeHostSessionSnapshot.KEY_RUNTIME_SESSION_ID]
        )
    }

    @Test
    fun `runtime_session_id is distinct from session_id`() {
        val session = freshSession()
        val rsid = "distinct-runtime-session-id"
        val map = snapshot(session, rsid = rsid).toProjectionMap()
        assertNotEquals(
            "runtime_session_id must be a distinct value from session_id",
            map[AttachedRuntimeHostSessionSnapshot.KEY_SESSION_ID],
            map[AttachedRuntimeHostSessionSnapshot.KEY_RUNTIME_SESSION_ID]
        )
    }

    @Test
    fun `session_id is stable across withExecutionAccepted calls`() {
        val original = freshSession()
        val evolved = original.withExecutionAccepted().withExecutionAccepted()
        val originalMap = snapshot(original).toProjectionMap()
        val evolvedMap = snapshot(evolved).toProjectionMap()
        assertEquals(
            "session_id must remain stable across withExecutionAccepted — host must not treat " +
                "each task acceptance as a new session",
            originalMap[AttachedRuntimeHostSessionSnapshot.KEY_SESSION_ID],
            evolvedMap[AttachedRuntimeHostSessionSnapshot.KEY_SESSION_ID]
        )
    }

    @Test
    fun `device_id matches AttachedRuntimeSession deviceId`() {
        val session = freshSession(deviceId = "samsung-s25")
        val map = snapshot(session).toProjectionMap()
        assertEquals(
            "device_id in projection must match AttachedRuntimeSession.deviceId",
            "samsung-s25",
            map[AttachedRuntimeHostSessionSnapshot.KEY_DEVICE_ID]
        )
    }

    // ── from() factory — data class field alignment ───────────────────────────

    @Test
    fun `from produces snapshot with correct sessionId field`() {
        val session = freshSession()
        val snap = snapshot(session)
        assertEquals(session.sessionId, snap.sessionId)
    }

    @Test
    fun `from produces snapshot with correct deviceId field`() {
        val session = freshSession(deviceId = "pixel-fold")
        val snap = snapshot(session)
        assertEquals("pixel-fold", snap.deviceId)
    }

    @Test
    fun `from produces snapshot with correct runtimeSessionId field`() {
        val snap = snapshot(freshSession(), rsid = "my-rsid-999")
        assertEquals("my-rsid-999", snap.runtimeSessionId)
    }

    @Test
    fun `from produces snapshot with correct attachmentState for ATTACHED`() {
        val snap = snapshot(freshSession())
        assertEquals("attached", snap.attachmentState)
    }

    @Test
    fun `from produces snapshot with correct attachmentState for DETACHING`() {
        val session = freshSession().beginDetaching(AttachedRuntimeSession.DetachCause.DISABLE)
        val snap = snapshot(session)
        assertEquals("detaching", snap.attachmentState)
    }

    @Test
    fun `from produces snapshot with correct attachmentState for DETACHED`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val snap = snapshot(session)
        assertEquals("detached", snap.attachmentState)
    }

    @Test
    fun `from produces snapshot with null invalidationReason for non-invalidation causes`() {
        for (cause in listOf(
            AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH,
            AttachedRuntimeSession.DetachCause.DISCONNECT,
            AttachedRuntimeSession.DetachCause.DISABLE
        )) {
            val session = freshSession().detachedWith(cause)
            val snap = snapshot(session)
            assertNull(
                "invalidationReason must be null for cause=$cause",
                snap.invalidationReason
            )
        }
    }

    @Test
    fun `from produces snapshot with non-null invalidationReason for INVALIDATION cause`() {
        val session = freshSession()
            .detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        val snap = snapshot(session)
        assertNotNull(
            "invalidationReason must be non-null when cause is INVALIDATION",
            snap.invalidationReason
        )
        assertEquals(
            "invalidationReason value must match INVALIDATION wireValue",
            "invalidation",
            snap.invalidationReason
        )
    }

    @Test
    fun `from produces snapshot with join_runtime posture for ATTACHED session`() {
        val snap = snapshot(freshSession())
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, snap.posture)
    }

    @Test
    fun `from produces snapshot with control_only posture for DETACHING session`() {
        val session = freshSession().beginDetaching(AttachedRuntimeSession.DetachCause.DISABLE)
        val snap = snapshot(session)
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, snap.posture)
    }

    @Test
    fun `from produces snapshot with control_only posture for DETACHED session`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val snap = snapshot(session)
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, snap.posture)
    }

    // ── RuntimeController.currentHostSessionSnapshot ──────────────────────────

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
    fun `currentHostSessionSnapshot returns null when no session exists`() {
        val controller = buildController()
        assertNull(
            "currentHostSessionSnapshot must return null before any session is opened",
            controller.currentHostSessionSnapshot()
        )
    }

    @Test
    fun `currentHostSessionSnapshot returns null after stop with no prior session`() {
        val controller = buildController()
        controller.stop()
        assertNull(
            "currentHostSessionSnapshot must remain null after stop() when no session existed",
            controller.currentHostSessionSnapshot()
        )
    }

    @Test
    fun `currentHostSessionSnapshot returns null after invalidateSession with no prior session`() {
        val controller = buildController()
        controller.invalidateSession()
        assertNull(
            "currentHostSessionSnapshot must be null when invalidateSession is a no-op",
            controller.currentHostSessionSnapshot()
        )
    }
}

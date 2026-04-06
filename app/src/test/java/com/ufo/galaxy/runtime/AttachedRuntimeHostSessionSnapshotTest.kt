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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [AttachedRuntimeHostSessionSnapshot] (PR-19 — canonical attached
 * runtime host-session snapshot projection, Android side).
 *
 * ## Test matrix
 *
 * ### Snapshot field consistency — all 9 required fields
 *  - toMetadataMap always contains KEY_SESSION_ID.
 *  - toMetadataMap always contains KEY_DEVICE_ID.
 *  - toMetadataMap always contains KEY_RUNTIME_SESSION_ID.
 *  - toMetadataMap always contains KEY_ATTACHMENT_STATE.
 *  - toMetadataMap always contains KEY_IS_REUSE_VALID.
 *  - toMetadataMap always contains KEY_DELEGATED_EXECUTION_COUNT.
 *  - toMetadataMap always contains KEY_HOST_ROLE.
 *  - toMetadataMap always contains KEY_POSTURE.
 *  - toMetadataMap contains KEY_INVALIDATION_REASON only when cause is INVALIDATION.
 *
 * ### AttachedRuntimeHostSessionSnapshot.from — field projection
 *  - sessionId is taken from session.sessionId.
 *  - deviceId is taken from session.deviceId.
 *  - runtimeSessionId is taken from the provided runtimeSessionId param.
 *  - attachmentState reflects session.state on attach.
 *  - isReuseValid is true for an ATTACHED session.
 *  - delegatedExecutionCount reflects session.delegatedExecutionCount.
 *  - hostRole reflects hostDescriptor.formationRole.
 *  - posture reflects hostDescriptor.participationState.
 *  - hostRole defaults to FormationRole.DEFAULT when hostDescriptor is null.
 *  - posture defaults to HostParticipationState.DEFAULT when hostDescriptor is null.
 *
 * ### invalidation_reason semantics
 *  - invalidationReason is null for an ATTACHED session.
 *  - invalidationReason is null when detachCause is EXPLICIT_DETACH.
 *  - invalidationReason is null when detachCause is DISCONNECT.
 *  - invalidationReason is null when detachCause is DISABLE.
 *  - invalidationReason equals detachCause.wireValue when detachCause is INVALIDATION.
 *  - KEY_INVALIDATION_REASON is absent from toMetadataMap when invalidationReason is null.
 *  - KEY_INVALIDATION_REASON is present in toMetadataMap when invalidationReason is non-null.
 *
 * ### State transition correctness
 *  - attach: attachmentState=ATTACHED, isReuseValid=true, invalidationReason=null.
 *  - beginDetaching(EXPLICIT_DETACH): attachmentState=DETACHING, isReuseValid=false, invalidationReason=null.
 *  - detachedWith(EXPLICIT_DETACH): attachmentState=DETACHED, isReuseValid=false, invalidationReason=null.
 *  - detachedWith(DISCONNECT): attachmentState=DETACHED, isReuseValid=false, invalidationReason=null.
 *  - detachedWith(DISABLE): attachmentState=DETACHED, isReuseValid=false, invalidationReason=null.
 *  - detachedWith(INVALIDATION): attachmentState=DETACHED, isReuseValid=false, invalidationReason=non-null.
 *  - delegatedExecutionCount increases correctly after withExecutionAccepted.
 *
 * ### Wire value consistency
 *  - KEY_ATTACHMENT_STATE value equals AttachedRuntimeSession.State.wireValue.
 *  - KEY_HOST_ROLE value equals RuntimeHostDescriptor.FormationRole.wireValue.
 *  - KEY_POSTURE value equals RuntimeHostDescriptor.HostParticipationState.wireValue.
 *
 * ### RuntimeController.currentHostSessionSnapshot
 *  - Returns null before any session is opened.
 *  - Returns null after stop() with no prior session.
 *  - Returns null after invalidateSession() with no prior session.
 */
class AttachedRuntimeHostSessionSnapshotTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun freshSession(
        hostId: String = "host-pr19",
        deviceId: String = "pixel-9-pro"
    ) = AttachedRuntimeSession.create(hostId = hostId, deviceId = deviceId)

    private fun defaultDescriptor(
        formationRole: RuntimeHostDescriptor.FormationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState: RuntimeHostDescriptor.HostParticipationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
    ) = RuntimeHostDescriptor.of(
        deviceId = "pixel-9-pro",
        deviceRole = "phone",
        formationRole = formationRole
    ).copy(participationState = participationState)

    private val runtimeSessionId = "runtime-session-id-pr19"

    private fun snapshotFor(
        session: AttachedRuntimeSession = freshSession(),
        runtimeSessionId: String = this.runtimeSessionId,
        hostDescriptor: RuntimeHostDescriptor? = defaultDescriptor()
    ) = AttachedRuntimeHostSessionSnapshot.from(
        session = session,
        runtimeSessionId = runtimeSessionId,
        hostDescriptor = hostDescriptor
    )

    /**
     * The 8 unconditional metadata keys that must always be present.
     * KEY_INVALIDATION_REASON is the 9th field but is conditional on INVALIDATION cause.
     */
    private val requiredKeys = setOf(
        AttachedRuntimeHostSessionSnapshot.KEY_SESSION_ID,
        AttachedRuntimeHostSessionSnapshot.KEY_DEVICE_ID,
        AttachedRuntimeHostSessionSnapshot.KEY_RUNTIME_SESSION_ID,
        AttachedRuntimeHostSessionSnapshot.KEY_ATTACHMENT_STATE,
        AttachedRuntimeHostSessionSnapshot.KEY_IS_REUSE_VALID,
        AttachedRuntimeHostSessionSnapshot.KEY_DELEGATED_EXECUTION_COUNT,
        AttachedRuntimeHostSessionSnapshot.KEY_HOST_ROLE,
        AttachedRuntimeHostSessionSnapshot.KEY_POSTURE
    )

    // ── Snapshot field consistency ────────────────────────────────────────────

    @Test
    fun `toMetadataMap contains all 8 unconditional required keys on fresh session`() {
        val map = snapshotFor().toMetadataMap()
        val missing = requiredKeys - map.keys
        assertTrue(
            "toMetadataMap must contain all 8 unconditional required keys (KEY_INVALIDATION_REASON " +
                "is the 9th field but is conditional on INVALIDATION cause); missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `toMetadataMap contains KEY_SESSION_ID`() {
        assertTrue(AttachedRuntimeHostSessionSnapshot.KEY_SESSION_ID in snapshotFor().toMetadataMap())
    }

    @Test
    fun `toMetadataMap contains KEY_DEVICE_ID`() {
        assertTrue(AttachedRuntimeHostSessionSnapshot.KEY_DEVICE_ID in snapshotFor().toMetadataMap())
    }

    @Test
    fun `toMetadataMap contains KEY_RUNTIME_SESSION_ID`() {
        assertTrue(AttachedRuntimeHostSessionSnapshot.KEY_RUNTIME_SESSION_ID in snapshotFor().toMetadataMap())
    }

    @Test
    fun `toMetadataMap contains KEY_ATTACHMENT_STATE`() {
        assertTrue(AttachedRuntimeHostSessionSnapshot.KEY_ATTACHMENT_STATE in snapshotFor().toMetadataMap())
    }

    @Test
    fun `toMetadataMap contains KEY_IS_REUSE_VALID`() {
        assertTrue(AttachedRuntimeHostSessionSnapshot.KEY_IS_REUSE_VALID in snapshotFor().toMetadataMap())
    }

    @Test
    fun `toMetadataMap contains KEY_DELEGATED_EXECUTION_COUNT`() {
        assertTrue(AttachedRuntimeHostSessionSnapshot.KEY_DELEGATED_EXECUTION_COUNT in snapshotFor().toMetadataMap())
    }

    @Test
    fun `toMetadataMap contains KEY_HOST_ROLE`() {
        assertTrue(AttachedRuntimeHostSessionSnapshot.KEY_HOST_ROLE in snapshotFor().toMetadataMap())
    }

    @Test
    fun `toMetadataMap contains KEY_POSTURE`() {
        assertTrue(AttachedRuntimeHostSessionSnapshot.KEY_POSTURE in snapshotFor().toMetadataMap())
    }

    @Test
    fun `toMetadataMap does not contain KEY_INVALIDATION_REASON when invalidationReason is null`() {
        val map = snapshotFor().toMetadataMap()
        assertFalse(
            "KEY_INVALIDATION_REASON must be absent when session is not invalidated",
            AttachedRuntimeHostSessionSnapshot.KEY_INVALIDATION_REASON in map
        )
    }

    // ── from() field projection ───────────────────────────────────────────────

    @Test
    fun `sessionId matches session sessionId`() {
        val session = freshSession()
        val snapshot = snapshotFor(session = session)
        assertEquals(session.sessionId, snapshot.sessionId)
    }

    @Test
    fun `deviceId matches session deviceId`() {
        val session = freshSession(deviceId = "pixel-fold")
        val snapshot = snapshotFor(session = session)
        assertEquals("pixel-fold", snapshot.deviceId)
    }

    @Test
    fun `runtimeSessionId matches the provided param`() {
        val id = "custom-runtime-session-id"
        val snapshot = snapshotFor(runtimeSessionId = id)
        assertEquals(id, snapshot.runtimeSessionId)
    }

    @Test
    fun `attachmentState is ATTACHED for a fresh session`() {
        assertEquals(AttachedRuntimeSession.State.ATTACHED, snapshotFor().attachmentState)
    }

    @Test
    fun `isReuseValid is true for a fresh ATTACHED session`() {
        assertTrue(snapshotFor().isReuseValid)
    }

    @Test
    fun `delegatedExecutionCount is 0 on a fresh session`() {
        assertEquals(0, snapshotFor().delegatedExecutionCount)
    }

    @Test
    fun `delegatedExecutionCount reflects accepted executions`() {
        val session = freshSession().withExecutionAccepted().withExecutionAccepted()
        assertEquals(2, snapshotFor(session = session).delegatedExecutionCount)
    }

    @Test
    fun `hostRole reflects hostDescriptor formationRole`() {
        val descriptor = defaultDescriptor(formationRole = RuntimeHostDescriptor.FormationRole.SECONDARY)
        val snapshot = snapshotFor(hostDescriptor = descriptor)
        assertEquals(RuntimeHostDescriptor.FormationRole.SECONDARY, snapshot.hostRole)
    }

    @Test
    fun `posture reflects hostDescriptor participationState`() {
        val descriptor = defaultDescriptor(participationState = RuntimeHostDescriptor.HostParticipationState.STANDBY)
        val snapshot = snapshotFor(hostDescriptor = descriptor)
        assertEquals(RuntimeHostDescriptor.HostParticipationState.STANDBY, snapshot.posture)
    }

    @Test
    fun `hostRole defaults to FormationRole DEFAULT when hostDescriptor is null`() {
        val snapshot = snapshotFor(hostDescriptor = null)
        assertEquals(RuntimeHostDescriptor.FormationRole.DEFAULT, snapshot.hostRole)
    }

    @Test
    fun `posture defaults to HostParticipationState DEFAULT when hostDescriptor is null`() {
        val snapshot = snapshotFor(hostDescriptor = null)
        assertEquals(RuntimeHostDescriptor.HostParticipationState.DEFAULT, snapshot.posture)
    }

    // ── invalidation_reason semantics ─────────────────────────────────────────

    @Test
    fun `invalidationReason is null for an ATTACHED session`() {
        assertNull(snapshotFor(session = freshSession()).invalidationReason)
    }

    @Test
    fun `invalidationReason is null when detachCause is EXPLICIT_DETACH`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        assertNull(snapshotFor(session = session).invalidationReason)
    }

    @Test
    fun `invalidationReason is null when detachCause is DISCONNECT`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertNull(snapshotFor(session = session).invalidationReason)
    }

    @Test
    fun `invalidationReason is null when detachCause is DISABLE`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        assertNull(snapshotFor(session = session).invalidationReason)
    }

    @Test
    fun `invalidationReason equals wireValue when detachCause is INVALIDATION`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        val snapshot = snapshotFor(session = session)
        assertEquals(
            AttachedRuntimeSession.DetachCause.INVALIDATION.wireValue,
            snapshot.invalidationReason
        )
    }

    @Test
    fun `KEY_INVALIDATION_REASON is present in toMetadataMap when cause is INVALIDATION`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        val map = snapshotFor(session = session).toMetadataMap()
        assertTrue(
            "KEY_INVALIDATION_REASON must be present when session was invalidated",
            AttachedRuntimeHostSessionSnapshot.KEY_INVALIDATION_REASON in map
        )
    }

    @Test
    fun `KEY_INVALIDATION_REASON is absent from toMetadataMap for EXPLICIT_DETACH`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val map = snapshotFor(session = session).toMetadataMap()
        assertFalse(
            "KEY_INVALIDATION_REASON must be absent for EXPLICIT_DETACH",
            AttachedRuntimeHostSessionSnapshot.KEY_INVALIDATION_REASON in map
        )
    }

    // ── State transition correctness ──────────────────────────────────────────

    @Test
    fun `attach — attachmentState=ATTACHED isReuseValid=true invalidationReason=null`() {
        val snapshot = snapshotFor(session = freshSession())
        assertEquals(AttachedRuntimeSession.State.ATTACHED, snapshot.attachmentState)
        assertTrue(snapshot.isReuseValid)
        assertNull(snapshot.invalidationReason)
    }

    @Test
    fun `beginDetaching EXPLICIT_DETACH — attachmentState=DETACHING isReuseValid=false invalidationReason=null`() {
        val session = freshSession().beginDetaching(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val snapshot = snapshotFor(session = session)
        assertEquals(AttachedRuntimeSession.State.DETACHING, snapshot.attachmentState)
        assertFalse(snapshot.isReuseValid)
        assertNull(snapshot.invalidationReason)
    }

    @Test
    fun `detachedWith EXPLICIT_DETACH — attachmentState=DETACHED isReuseValid=false invalidationReason=null`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        val snapshot = snapshotFor(session = session)
        assertEquals(AttachedRuntimeSession.State.DETACHED, snapshot.attachmentState)
        assertFalse(snapshot.isReuseValid)
        assertNull(snapshot.invalidationReason)
    }

    @Test
    fun `detachedWith DISCONNECT — attachmentState=DETACHED isReuseValid=false invalidationReason=null`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val snapshot = snapshotFor(session = session)
        assertEquals(AttachedRuntimeSession.State.DETACHED, snapshot.attachmentState)
        assertFalse(snapshot.isReuseValid)
        assertNull(snapshot.invalidationReason)
    }

    @Test
    fun `detachedWith DISABLE — attachmentState=DETACHED isReuseValid=false invalidationReason=null`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        val snapshot = snapshotFor(session = session)
        assertEquals(AttachedRuntimeSession.State.DETACHED, snapshot.attachmentState)
        assertFalse(snapshot.isReuseValid)
        assertNull(snapshot.invalidationReason)
    }

    @Test
    fun `detachedWith INVALIDATION — attachmentState=DETACHED isReuseValid=false invalidationReason=non-null`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        val snapshot = snapshotFor(session = session)
        assertEquals(AttachedRuntimeSession.State.DETACHED, snapshot.attachmentState)
        assertFalse(snapshot.isReuseValid)
        assertNotNull(snapshot.invalidationReason)
    }

    @Test
    fun `delegatedExecutionCount increases correctly after withExecutionAccepted`() {
        var session = freshSession()
        for (i in 1..5) {
            session = session.withExecutionAccepted()
            assertEquals(i, snapshotFor(session = session).delegatedExecutionCount)
        }
    }

    // ── Wire value consistency ────────────────────────────────────────────────

    @Test
    fun `KEY_ATTACHMENT_STATE wire value equals state wireValue`() {
        val session = freshSession()
        val map = snapshotFor(session = session).toMetadataMap()
        assertEquals(
            session.state.wireValue,
            map[AttachedRuntimeHostSessionSnapshot.KEY_ATTACHMENT_STATE]
        )
    }

    @Test
    fun `KEY_ATTACHMENT_STATE wire value is detaching after beginDetaching`() {
        val session = freshSession().beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val map = snapshotFor(session = session).toMetadataMap()
        assertEquals(
            AttachedRuntimeSession.State.DETACHING.wireValue,
            map[AttachedRuntimeHostSessionSnapshot.KEY_ATTACHMENT_STATE]
        )
    }

    @Test
    fun `KEY_ATTACHMENT_STATE wire value is detached after detachedWith`() {
        val session = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        val map = snapshotFor(session = session).toMetadataMap()
        assertEquals(
            AttachedRuntimeSession.State.DETACHED.wireValue,
            map[AttachedRuntimeHostSessionSnapshot.KEY_ATTACHMENT_STATE]
        )
    }

    @Test
    fun `KEY_HOST_ROLE wire value equals formationRole wireValue`() {
        val descriptor = defaultDescriptor(formationRole = RuntimeHostDescriptor.FormationRole.SATELLITE)
        val map = snapshotFor(hostDescriptor = descriptor).toMetadataMap()
        assertEquals(
            RuntimeHostDescriptor.FormationRole.SATELLITE.wireValue,
            map[AttachedRuntimeHostSessionSnapshot.KEY_HOST_ROLE]
        )
    }

    @Test
    fun `KEY_POSTURE wire value equals participationState wireValue`() {
        val descriptor = defaultDescriptor(participationState = RuntimeHostDescriptor.HostParticipationState.DRAINING)
        val map = snapshotFor(hostDescriptor = descriptor).toMetadataMap()
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.DRAINING.wireValue,
            map[AttachedRuntimeHostSessionSnapshot.KEY_POSTURE]
        )
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
    fun `currentHostSessionSnapshot returns null before any session is opened`() {
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

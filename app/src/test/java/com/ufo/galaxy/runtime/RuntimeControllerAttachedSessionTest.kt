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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for [RuntimeController.attachedSession] — the PR-7 attached-runtime session
 * lifecycle integrated into [RuntimeController].
 *
 * ## Test matrix
 *
 * ### Initial state
 *  - attachedSession is null before any lifecycle event.
 *
 * ### stop() with no prior session
 *  - attachedSession remains null after stop() when no session was ever created.
 *
 * ### stop() detaches with DISABLE cause
 *  - If a session is manually opened then stop() is called, the session transitions
 *    to DETACHED with DetachCause.DISABLE.
 *
 * ### invalidateSession() with no prior session
 *  - No-op: attachedSession remains null.
 *
 * ### invalidateSession() invalidates an active session
 *  - Session transitions to DETACHED with DetachCause.INVALIDATION.
 *
 * ### Distinction from connection presence
 *  - A null session means no cross-device runtime participation (ordinary presence
 *    or disconnected).
 *  - A non-null ATTACHED session means explicit participation.
 *
 * ### startWithTimeout failure
 *  - attachedSession remains null after a failed startWithTimeout (WS unreachable).
 *
 * ### Session identity
 *  - Each new session has a unique sessionId.
 *  - Session hostId and deviceId reflect the provided RuntimeHostDescriptor.
 *
 * ### RuntimeHostDescriptor participation state sync
 *  - When a session is opened, hostDescriptor participation state is set to ACTIVE.
 *  - When a session is closed, hostDescriptor participation state is set to INACTIVE.
 *
 * ### Lifecycle cause coverage
 *  - DISABLE: stop() call.
 *  - DISCONNECT: WS disconnect while session is active.
 *  - INVALIDATION: invalidateSession() call.
 */
class RuntimeControllerAttachedSessionTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies ─────────────────────────────────────────────────────

    private class SingleStepPlannerService : LocalPlannerService {
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

    private class FakeGrounder : LocalGroundingService {
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

    // ── Builder helpers ───────────────────────────────────────────────────────

    private fun buildLoopController(): LoopController {
        val modelsDir = tmpFolder.newFolder("models")
        return LoopController(
            localPlanner = LocalPlanner(SingleStepPlannerService()),
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

    private fun buildTestHostDescriptor(
        hostId: String = "test-host-id",
        deviceId: String = "test-device"
    ): RuntimeHostDescriptor = RuntimeHostDescriptor(
        hostId = hostId,
        deviceId = deviceId,
        deviceRole = "phone",
        formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
    )

    private fun buildController(
        settings: InMemoryAppSettings = InMemoryAppSettings(),
        hostDescriptor: RuntimeHostDescriptor? = null,
        timeoutMs: Long = 100L
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = timeoutMs,
            hostDescriptor = hostDescriptor
        )
        return controller to client
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `attachedSession is null before any lifecycle event`() {
        val (controller, _) = buildController()
        assertNull(
            "attachedSession must be null before any activation",
            controller.attachedSession.value
        )
    }

    // ── stop() behaviour ──────────────────────────────────────────────────────

    @Test
    fun `attachedSession remains null after stop when no session was created`() {
        val (controller, _) = buildController()
        controller.stop()
        assertNull(
            "attachedSession must remain null after stop() when no session existed",
            controller.attachedSession.value
        )
    }

    // ── invalidateSession() ───────────────────────────────────────────────────

    @Test
    fun `invalidateSession is a no-op when no session exists`() {
        val (controller, _) = buildController()
        controller.invalidateSession()
        assertNull(
            "invalidateSession must be a no-op when no session exists",
            controller.attachedSession.value
        )
    }

    // ── startWithTimeout failure leaves no session ────────────────────────────

    @Test
    fun `startWithTimeout failure leaves attachedSession null`() = runBlocking {
        val (controller, _) = buildController(timeoutMs = 200L)
        controller.startWithTimeout()
        assertNull(
            "attachedSession must remain null after startWithTimeout failure",
            controller.attachedSession.value
        )
    }

    // ── Distinction from connection presence ──────────────────────────────────

    @Test
    fun `null attachedSession means no cross-device runtime participation`() {
        val (controller, _) = buildController()
        // Before any activation, there is no participation.
        val session = controller.attachedSession.value
        val isParticipating = session?.isAttached == true
        assertFalse(
            "Null session must not be interpreted as cross-device runtime participation",
            isParticipating
        )
    }

    // ── RuntimeHostDescriptor participation state sync ────────────────────────

    @Test
    fun `hostDescriptor participation state starts INACTIVE before session`() {
        val descriptor = buildTestHostDescriptor()
        assertEquals(
            "Host descriptor must start INACTIVE before any attached session",
            RuntimeHostDescriptor.HostParticipationState.INACTIVE,
            descriptor.participationState
        )
    }

    @Test
    fun `RuntimeController constructor accepts null hostDescriptor`() {
        // Ensures backward compatibility for tests and callers that do not supply a descriptor.
        val (controller, _) = buildController(hostDescriptor = null)
        assertNull(
            "attachedSession must be null when constructed without a hostDescriptor",
            controller.attachedSession.value
        )
    }

    @Test
    fun `RuntimeController constructor accepts non-null hostDescriptor`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)
        // The descriptor is accepted; session is still null until WS connects.
        assertNull(
            "attachedSession is null even with a hostDescriptor until WS connects",
            controller.attachedSession.value
        )
    }

    // ── Session identity ──────────────────────────────────────────────────────

    @Test
    fun `AttachedRuntimeSession create reflects provided hostId`() {
        val session = AttachedRuntimeSession.create(hostId = "my-host", deviceId = "my-device")
        assertEquals("my-host", session.hostId)
    }

    @Test
    fun `AttachedRuntimeSession create reflects provided deviceId`() {
        val session = AttachedRuntimeSession.create(hostId = "h", deviceId = "pixel-8")
        assertEquals("pixel-8", session.deviceId)
    }

    @Test
    fun `AttachedRuntimeSession create uses hostId from RuntimeHostDescriptor`() {
        val descriptor = buildTestHostDescriptor(hostId = "descriptor-host")
        val session = AttachedRuntimeSession.create(
            hostId = descriptor.hostId,
            deviceId = descriptor.deviceId
        )
        assertEquals("descriptor-host", session.hostId)
    }

    @Test
    fun `two sessions created from same descriptor have distinct sessionIds`() {
        val descriptor = buildTestHostDescriptor()
        val s1 = AttachedRuntimeSession.create(hostId = descriptor.hostId, deviceId = descriptor.deviceId)
        val s2 = AttachedRuntimeSession.create(hostId = descriptor.hostId, deviceId = descriptor.deviceId)
        assertNotEquals("Each new attach event must produce a distinct sessionId", s1.sessionId, s2.sessionId)
    }

    // ── Lifecycle coverage contract ───────────────────────────────────────────

    @Test
    fun `stop transitions RuntimeState to LocalOnly regardless of session state`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val (controller, _) = buildController(settings = settings)
        controller.stop()
        assertTrue(
            "stop() must transition to LocalOnly",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )
    }

    @Test
    fun `stop disables crossDeviceEnabled in settings`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val (controller, _) = buildController(settings = settings)
        controller.stop()
        assertFalse("crossDeviceEnabled must be false after stop()", settings.crossDeviceEnabled)
    }

    @Test
    fun `connectIfEnabled with crossDeviceEnabled false transitions to LocalOnly`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = false)
        val (controller, _) = buildController(settings = settings)
        controller.connectIfEnabled()
        assertTrue(
            "connectIfEnabled with crossDevice=false must produce LocalOnly",
            controller.state.value is RuntimeController.RuntimeState.LocalOnly
        )
    }

    @Test
    fun `connectIfEnabled with crossDeviceEnabled false leaves attachedSession null`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = false)
        val (controller, _) = buildController(settings = settings)
        controller.connectIfEnabled()
        assertNull(
            "attachedSession must remain null when cross-device is disabled",
            controller.attachedSession.value
        )
    }

    // ── recordDelegatedExecutionAccepted() (PR-14) ────────────────────────────

    @Test
    fun `recordDelegatedExecutionAccepted is a no-op when no session exists`() {
        val (controller, _) = buildController()
        // Precondition: no session.
        assertNull(controller.attachedSession.value)
        // Must not throw and session must remain null.
        controller.recordDelegatedExecutionAccepted()
        assertNull(
            "recordDelegatedExecutionAccepted must be a no-op when no session exists",
            controller.attachedSession.value
        )
    }

    @Test
    fun `setActiveForTest promotes host participation to ACTIVE in canonical projection`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, _) = buildController(hostDescriptor = descriptor)

        controller.setActiveForTest()

        val participant = controller.currentCanonicalParticipant()
        assertNotNull("Canonical participant projection must be available after activation", participant)
        assertEquals(
            "Host participation must be ACTIVE once attached session is opened",
            RuntimeHostDescriptor.HostParticipationState.ACTIVE,
            participant!!.participationState
        )
    }

    @Test
    fun `disconnect then reconnect drives host participation INACTIVE to ACTIVE`() {
        val descriptor = buildTestHostDescriptor()
        val (controller, client) = buildController(hostDescriptor = descriptor)
        controller.setActiveForTest()
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.ACTIVE,
            controller.currentCanonicalParticipant()!!.participationState
        )

        client.simulateDisconnected()
        assertEquals(
            "Host participation must retire to INACTIVE during disconnect",
            RuntimeHostDescriptor.HostParticipationState.INACTIVE,
            controller.currentCanonicalParticipant()!!.participationState
        )

        client.simulateConnected()
        assertEquals(
            "Host participation must re-activate to ACTIVE after reconnect replacement",
            RuntimeHostDescriptor.HostParticipationState.ACTIVE,
            controller.currentCanonicalParticipant()!!.participationState
        )
    }

    @Test
    fun `stop retains detached session with DISABLE cause for retirement semantics`() {
        val (controller, _) = buildController(hostDescriptor = buildTestHostDescriptor())
        controller.setActiveForTest()

        controller.stop()

        val detached = controller.attachedSession.value
        assertNotNull("stop() must retain detached session projection", detached)
        assertTrue("Session must be detached after stop()", detached!!.isDetached)
        assertEquals(
            AttachedRuntimeSession.DetachCause.DISABLE,
            detached.detachCause
        )
    }

    @Test
    fun `invalidateSession retains detached session with INVALIDATION cause`() {
        val (controller, _) = buildController(hostDescriptor = buildTestHostDescriptor())
        controller.setActiveForTest()

        controller.invalidateSession()

        val detached = controller.attachedSession.value
        assertNotNull("invalidateSession() must retain detached session projection", detached)
        assertTrue("Session must be detached after invalidation", detached!!.isDetached)
        assertEquals(
            AttachedRuntimeSession.DetachCause.INVALIDATION,
            detached.detachCause
        )
    }
}

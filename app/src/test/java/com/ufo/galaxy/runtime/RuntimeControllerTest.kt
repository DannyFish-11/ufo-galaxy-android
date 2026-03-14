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
import com.ufo.galaxy.loop.LoopStatus
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.network.OfflineTaskQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [RuntimeController].
 */
class RuntimeControllerTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val noOpPlanner = object : LocalPlannerService {
        override fun loadModel() = false
        override fun unloadModel() {}
        override fun isModelLoaded() = false
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(steps = emptyList())
        override fun replan(
            goal: String, constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String, screenshotBase64: String?
        ) = LocalPlannerService.PlanResult(steps = emptyList())
        override fun prewarm() = false
    }

    private val noOpGrounder = object : LocalGroundingService {
        override fun loadModel() = false
        override fun unloadModel() {}
        override fun isModelLoaded() = false
        override fun ground(screenshotBase64: String, intent: String, screenWidth: Int, screenHeight: Int) =
            LocalGroundingService.GroundingResult(x = 0, y = 0, confidence = 0f)
        override fun prewarm() = false
    }

    private fun makeLoopController(): LoopController {
        val dir = tmpFolder.newFolder("loop_${System.nanoTime()}")
        return LoopController(
            localPlanner = LocalPlanner(plannerService = noOpPlanner),
            executorBridge = ExecutorBridge(
                groundingService = noOpGrounder,
                accessibilityExecutor = object : AccessibilityExecutor {
                    override fun execute(action: AccessibilityExecutor.AccessibilityAction) = false
                },
                imageScaler = NoOpImageScaler()
            ),
            screenshotProvider = object : EdgeExecutor.ScreenshotProvider {
                override fun captureJpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
                override fun screenWidth() = 1080
                override fun screenHeight() = 1920
            },
            modelAssetManager = ModelAssetManager(dir),
            modelDownloader = ModelDownloader(dir)
        )
    }

    private fun makeClient(crossDevice: Boolean = false) = GalaxyWebSocketClient(
        serverUrl = "ws://localhost:9999",
        initialCrossDeviceEnabled = crossDevice,
        offlineQueue = OfflineTaskQueue(prefs = null)
    )

    private fun buildController(
        crossDevice: Boolean = false,
        settings: InMemoryAppSettings = InMemoryAppSettings(crossDeviceEnabled = crossDevice),
        client: GalaxyWebSocketClient = makeClient(crossDevice),
        loop: LoopController = makeLoopController()
    ): RuntimeController {
        val dir = tmpFolder.newFolder("rc_${System.nanoTime()}")
        return RuntimeController(
            settings = settings,
            webSocketClient = client,
            loopController = loop,
            modelAssetManager = ModelAssetManager(dir),
            modelDownloader = ModelDownloader(dir)
        )
    }

    @Test
    fun `initial state is LOCAL_ONLY when crossDeviceEnabled is false`() {
        val ctrl = buildController(crossDevice = false)
        assertEquals(RuntimeController.RuntimeState.LOCAL_ONLY, ctrl.state.value)
        ctrl.destroy()
    }

    @Test
    fun `initial state is DISCONNECTED when crossDeviceEnabled is true`() {
        val ctrl = buildController(crossDevice = true)
        assertEquals(RuntimeController.RuntimeState.DISCONNECTED, ctrl.state.value)
        ctrl.destroy()
    }

    @Test
    fun `enable transitions state to CONNECTING`() {
        val ctrl = buildController(crossDevice = false)
        ctrl.enable()
        assertEquals(RuntimeController.RuntimeState.CONNECTING, ctrl.state.value)
        ctrl.destroy()
    }

    @Test
    fun `enable persists crossDeviceEnabled true`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = false)
        val ctrl = buildController(crossDevice = false, settings = settings)
        ctrl.enable()
        assertTrue(settings.crossDeviceEnabled)
        ctrl.destroy()
    }

    @Test
    fun `enable is no-op when already CONNECTING`() {
        val ctrl = buildController(crossDevice = false)
        ctrl.enable()
        ctrl.enable()
        assertEquals(RuntimeController.RuntimeState.CONNECTING, ctrl.state.value)
        ctrl.destroy()
    }

    @Test
    fun `disable transitions state to LOCAL_ONLY`() {
        val ctrl = buildController(crossDevice = true)
        ctrl.disable()
        assertEquals(RuntimeController.RuntimeState.LOCAL_ONLY, ctrl.state.value)
        ctrl.destroy()
    }

    @Test
    fun `disable persists crossDeviceEnabled false`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val ctrl = buildController(crossDevice = true, settings = settings)
        ctrl.disable()
        assertFalse(settings.crossDeviceEnabled)
        ctrl.destroy()
    }

    @Test
    fun `onRemoteTaskStarted transitions state to REMOTE_EXECUTING`() {
        val ctrl = buildController(crossDevice = true)
        ctrl.onRemoteTaskStarted()
        assertEquals(RuntimeController.RuntimeState.REMOTE_EXECUTING, ctrl.state.value)
        ctrl.destroy()
    }

    @Test
    fun `onRemoteTaskStarted pauses LoopController and resume clears the flag`() {
        val loop = makeLoopController()
        val ctrl = buildController(crossDevice = true, loop = loop)

        ctrl.onRemoteTaskStarted()
        // pause() is called; the loop is now in "pause-requested" state.
        // Calling resume() should clear the flag and leave status Idle.
        loop.resume()
        assertEquals(LoopStatus.Idle, loop.status.value)
        ctrl.destroy()
    }

    @Test
    fun `onRemoteTaskFinished transitions state away from REMOTE_EXECUTING`() {
        val ctrl = buildController(crossDevice = true)
        ctrl.onRemoteTaskStarted()
        ctrl.onRemoteTaskFinished()
        assertNotEquals(RuntimeController.RuntimeState.REMOTE_EXECUTING, ctrl.state.value)
        ctrl.destroy()
    }

    @Test
    fun `ensureModels completes without error`() = runBlocking {
        val ctrl = buildController()
        ctrl.ensureModels()
        ctrl.destroy()
    }

    @Test
    fun `destroy is idempotent`() {
        val ctrl = buildController()
        ctrl.destroy()
        ctrl.destroy()
    }

    @Test
    fun `onFailure is non-null SharedFlow`() {
        val ctrl = buildController()
        assertNotNull(ctrl.onFailure)
        ctrl.destroy()
    }
}

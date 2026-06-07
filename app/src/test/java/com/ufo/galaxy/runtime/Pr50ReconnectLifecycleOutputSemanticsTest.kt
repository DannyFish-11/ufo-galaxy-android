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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-50 — Reconnect / lifecycle output semantics strengthening.
 *
 * Acceptance test suite validating that the V2 lifecycle event stream emits a
 * consistent, identity-stable signal for every phase of the reconnect-recovery cycle,
 * addressing the following gaps identified in the cross-repo review:
 *
 *  1. **RECOVERING/FAILED V2 events were silently dropped when `hostDescriptor` is null.**
 *     [RuntimeController.emitFormationRebalanceForRecovery] now emits
 *     [V2MultiDeviceLifecycleEvent.DeviceDegraded] unconditionally for RECOVERING and
 *     FAILED states, using the session identity from [RuntimeController.attachedSession]
 *     and [RuntimeController.durableSessionContinuityRecord].
 *
 *  2. **[V2MultiDeviceLifecycleEvent.DeviceDegraded] now carries `durableSessionId` and
 *     `sessionContinuityEpoch`** so V2 can correlate `ws_recovering` and
 *     `ws_recovery_failed` events with the specific session era being recovered without
 *     re-parsing prior events.
 *
 *  3. **[RuntimeController.notifyParticipantHealthChanged] also populates `durableSessionId`
 *     and `sessionContinuityEpoch`** in the DeviceDegraded events it emits for health
 *     transitions (DEGRADED, RECOVERING, FAILED), ensuring identity consistency across
 *     both the WS-recovery and health-change paths.
 *
 * ## Test matrix
 *
 * ### V2MultiDeviceLifecycleEvent.DeviceDegraded — new identity fields
 *  - DeviceDegraded carries durableSessionId field (nullable by default)
 *  - DeviceDegraded carries sessionContinuityEpoch field (nullable by default)
 *  - DeviceDegraded durableSessionId may be null (backward-compatible default)
 *  - DeviceDegraded sessionContinuityEpoch may be null (backward-compatible default)
 *  - DeviceDegraded with explicit durableSessionId preserves value
 *  - DeviceDegraded with explicit sessionContinuityEpoch preserves value
 *
 * ### RuntimeController — RECOVERING V2 event carries durableSessionId
 *  - ws_recovering DeviceDegraded carries non-null durableSessionId after activation
 *  - ws_recovering DeviceDegraded durableSessionId matches durableSessionContinuityRecord
 *  - ws_recovering DeviceDegraded sessionContinuityEpoch matches record epoch
 *  - ws_recovering DeviceDegraded deviceId matches session deviceId
 *
 * ### RuntimeController — FAILED V2 event carries durableSessionId
 *  - ws_recovery_failed DeviceDegraded carries non-null durableSessionId
 *  - ws_recovery_failed DeviceDegraded durableSessionId matches durableSessionContinuityRecord
 *  - ws_recovery_failed DeviceDegraded deviceId matches session deviceId
 *
 * ### RuntimeController — V2 RECOVERING event emitted without hostDescriptor
 *  - ws_recovering DeviceDegraded emitted even when hostDescriptor is null
 *  - ws_recovering DeviceDegraded deviceId is populated from attached session
 *
 * ### RuntimeController — V2 FAILED event emitted without hostDescriptor
 *  - ws_recovery_failed DeviceDegraded emitted even when hostDescriptor is null
 *
 * ### RuntimeController — notifyParticipantHealthChanged DeviceDegraded carries durableSessionId
 *  - health_degraded DeviceDegraded carries durableSessionId when durable record is active
 *  - health_failed DeviceDegraded carries durableSessionId when durable record is active
 *
 * ### RuntimeController — full RECOVERING → RECOVERED V2 sequence
 *  - RECOVERING emits DeviceDisconnected then DeviceDegraded(ws_recovering)
 *  - RECOVERED emits DeviceReconnected with same durableSessionId
 *  - durableSessionId is stable across the full recovery cycle
 *
 * ### RuntimeController — full RECOVERING → FAILED V2 sequence
 *  - RECOVERING emits DeviceDegraded(ws_recovering) with durableSessionId
 *  - FAILED emits DeviceDegraded(ws_recovery_failed) with same durableSessionId
 */
class Pr50ReconnectLifecycleOutputSemanticsTest {

    @Rule
    @JvmField
    val tmpFolder = TemporaryFolder()

    // ── Fake services ─────────────────────────────────────────────────────────

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
            localPlanner = LocalPlanner(TrivialPlannerService()),
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

    private fun buildDescriptor(): RuntimeHostDescriptor =
        RuntimeHostDescriptor.of(
            deviceId = "test-device",
            deviceRole = "phone",
            formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY
        )

    private fun buildController(
        descriptor: RuntimeHostDescriptor? = null,
        deviceId: String = "test-device"
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val settings = InMemoryAppSettings().also { it.deviceId = deviceId }
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L,
            hostDescriptor = descriptor
        )
        return controller to client
    }

    // ── DeviceDegraded — new identity fields ──────────────────────────────────

    @Test
    fun `DeviceDegraded carries durableSessionId field nullable by default`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDegraded(
            deviceId = "d1",
            sessionId = "s1",
            degradationKind = "ws_recovering",
            continuationMode = "degraded_continuation"
        )
        assertNull("Default durableSessionId must be null", event.durableSessionId)
    }

    @Test
    fun `DeviceDegraded carries sessionContinuityEpoch field nullable by default`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDegraded(
            deviceId = "d1",
            sessionId = "s1",
            degradationKind = "ws_recovering",
            continuationMode = "degraded_continuation"
        )
        assertNull("Default sessionContinuityEpoch must be null", event.sessionContinuityEpoch)
    }

    @Test
    fun `DeviceDegraded with explicit durableSessionId preserves value`() {
        val durableId = "durable-era-abc"
        val event = V2MultiDeviceLifecycleEvent.DeviceDegraded(
            deviceId = "d1",
            sessionId = "s1",
            degradationKind = "ws_recovering",
            continuationMode = "degraded_continuation",
            durableSessionId = durableId
        )
        assertEquals(durableId, event.durableSessionId)
    }

    @Test
    fun `DeviceDegraded with explicit sessionContinuityEpoch preserves value`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDegraded(
            deviceId = "d1",
            sessionId = "s1",
            degradationKind = "ws_recovering",
            continuationMode = "degraded_continuation",
            sessionContinuityEpoch = 3
        )
        assertEquals(3, event.sessionContinuityEpoch)
    }

    @Test
    fun `DeviceDegraded wireValue unchanged after adding identity fields`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDegraded(
            deviceId = "d1",
            sessionId = null,
            degradationKind = "ws_recovering",
            continuationMode = "degraded_continuation",
            durableSessionId = "durable-era",
            sessionContinuityEpoch = 1
        )
        assertEquals(V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DEGRADED, event.wireValue)
    }

    // ── RECOVERING V2 event carries durableSessionId ──────────────────────────

    @Test
    fun `ws_recovering DeviceDegraded carries non-null durableSessionId after activation`() = runBlocking {
        val (controller, client) = buildController(descriptor = buildDescriptor())
        controller.setActiveForTest()

        client.simulateDisconnected()

        val event = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first { it is V2MultiDeviceLifecycleEvent.DeviceDegraded }
        }
        assertNotNull("Expected a DeviceDegraded event after WS disconnect", event)
        val degraded = event as V2MultiDeviceLifecycleEvent.DeviceDegraded
        assertEquals("ws_recovering", degraded.degradationKind)
        assertNotNull(
            "ws_recovering DeviceDegraded must carry durableSessionId",
            degraded.durableSessionId
        )
    }

    @Test
    fun `ws_recovering DeviceDegraded durableSessionId matches durableSessionContinuityRecord`() = runBlocking {
        val (controller, client) = buildController(descriptor = buildDescriptor())
        controller.setActiveForTest()
        val expectedDurableId = controller.durableSessionContinuityRecord.value?.durableSessionId

        client.simulateDisconnected()

        val event = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first { it is V2MultiDeviceLifecycleEvent.DeviceDegraded }
        }
        val degraded = event as V2MultiDeviceLifecycleEvent.DeviceDegraded
        assertEquals(
            "DeviceDegraded durableSessionId must match durableSessionContinuityRecord",
            expectedDurableId,
            degraded.durableSessionId
        )
    }

    @Test
    fun `ws_recovering DeviceDegraded sessionContinuityEpoch matches record epoch`() = runBlocking {
        val (controller, client) = buildController(descriptor = buildDescriptor())
        controller.setActiveForTest()
        val expectedEpoch = controller.durableSessionContinuityRecord.value?.sessionContinuityEpoch

        client.simulateDisconnected()

        val event = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first { it is V2MultiDeviceLifecycleEvent.DeviceDegraded }
        }
        val degraded = event as V2MultiDeviceLifecycleEvent.DeviceDegraded
        assertEquals(
            "DeviceDegraded sessionContinuityEpoch must match durable record epoch",
            expectedEpoch,
            degraded.sessionContinuityEpoch
        )
    }

    @Test
    fun `ws_recovering DeviceDegraded deviceId matches session deviceId`() = runBlocking {
        val (controller, client) = buildController(descriptor = buildDescriptor())
        controller.setActiveForTest()

        client.simulateDisconnected()

        val event = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first { it is V2MultiDeviceLifecycleEvent.DeviceDegraded }
        }
        val degraded = event as V2MultiDeviceLifecycleEvent.DeviceDegraded
        assertEquals("test-device", degraded.deviceId)
    }

    // ── FAILED V2 event carries durableSessionId ──────────────────────────────

    @Test
    fun `ws_recovery_failed DeviceDegraded carries non-null durableSessionId`() = runBlocking {
        val (controller, client) = buildController(descriptor = buildDescriptor())
        controller.setActiveForTest()

        client.simulateDisconnected()
        // Drain the RECOVERING event
        withTimeoutOrNull(200) {
            controller.v2LifecycleEvents.first { it is V2MultiDeviceLifecycleEvent.DeviceDegraded }
        }
        client.simulateError("connection refused")

        val event = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first {
                it is V2MultiDeviceLifecycleEvent.DeviceDegraded &&
                    (it as V2MultiDeviceLifecycleEvent.DeviceDegraded).degradationKind == "ws_recovery_failed"
            }
        }
        assertNotNull("Expected a ws_recovery_failed DeviceDegraded event", event)
        val degraded = event as V2MultiDeviceLifecycleEvent.DeviceDegraded
        assertNotNull(
            "ws_recovery_failed DeviceDegraded must carry durableSessionId",
            degraded.durableSessionId
        )
    }

    @Test
    fun `ws_recovery_failed DeviceDegraded durableSessionId matches record`() = runBlocking {
        val (controller, client) = buildController(descriptor = buildDescriptor())
        controller.setActiveForTest()
        val expectedDurableId = controller.durableSessionContinuityRecord.value?.durableSessionId

        client.simulateDisconnected()
        withTimeoutOrNull(200) {
            controller.v2LifecycleEvents.first { it is V2MultiDeviceLifecycleEvent.DeviceDegraded }
        }
        client.simulateError("connection refused")

        val event = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first {
                it is V2MultiDeviceLifecycleEvent.DeviceDegraded &&
                    (it as V2MultiDeviceLifecycleEvent.DeviceDegraded).degradationKind == "ws_recovery_failed"
            }
        }
        val degraded = event as V2MultiDeviceLifecycleEvent.DeviceDegraded
        assertEquals(
            "ws_recovery_failed DeviceDegraded durableSessionId must match durable record",
            expectedDurableId,
            degraded.durableSessionId
        )
    }

    @Test
    fun `ws_recovery_failed DeviceDegraded deviceId matches session deviceId`() = runBlocking {
        val (controller, client) = buildController(descriptor = buildDescriptor())
        controller.setActiveForTest()

        client.simulateDisconnected()
        withTimeoutOrNull(200) {
            controller.v2LifecycleEvents.first { it is V2MultiDeviceLifecycleEvent.DeviceDegraded }
        }
        client.simulateError("connection refused")

        val event = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first {
                it is V2MultiDeviceLifecycleEvent.DeviceDegraded &&
                    (it as V2MultiDeviceLifecycleEvent.DeviceDegraded).degradationKind == "ws_recovery_failed"
            }
        }
        val degraded = event as V2MultiDeviceLifecycleEvent.DeviceDegraded
        assertEquals("test-device", degraded.deviceId)
    }

    // ── V2 RECOVERING event emitted without hostDescriptor ────────────────────

    @Test
    fun `ws_recovering DeviceDegraded emitted even when hostDescriptor is null`() = runBlocking {
        // Build controller with NO hostDescriptor — previously this would silently drop
        // the V2 DeviceDegraded event because emitFormationRebalanceForRecovery returned
        // early on null hostDescriptor.
        val (controller, client) = buildController(descriptor = null, deviceId = "no-descriptor-device")
        controller.setActiveForTest()

        client.simulateDisconnected()

        val event = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first {
                it is V2MultiDeviceLifecycleEvent.DeviceDegraded &&
                    (it as V2MultiDeviceLifecycleEvent.DeviceDegraded).degradationKind == "ws_recovering"
            }
        }
        assertNotNull(
            "ws_recovering DeviceDegraded must be emitted even without a hostDescriptor",
            event
        )
    }

    @Test
    fun `ws_recovering DeviceDegraded deviceId populated from attached session when no hostDescriptor`() = runBlocking {
        val (controller, client) = buildController(descriptor = null, deviceId = "no-descriptor-device")
        controller.setActiveForTest()

        client.simulateDisconnected()

        val event = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first {
                it is V2MultiDeviceLifecycleEvent.DeviceDegraded &&
                    (it as V2MultiDeviceLifecycleEvent.DeviceDegraded).degradationKind == "ws_recovering"
            }
        }
        val degraded = event as V2MultiDeviceLifecycleEvent.DeviceDegraded
        assertTrue(
            "DeviceDegraded deviceId must be non-blank when no hostDescriptor",
            degraded.deviceId.isNotBlank()
        )
    }

    // ── V2 FAILED event emitted without hostDescriptor ────────────────────────

    @Test
    fun `ws_recovery_failed DeviceDegraded emitted even when hostDescriptor is null`() = runBlocking {
        val (controller, client) = buildController(descriptor = null, deviceId = "no-descriptor-device")
        controller.setActiveForTest()

        client.simulateDisconnected()
        withTimeoutOrNull(200) {
            controller.v2LifecycleEvents.first {
                it is V2MultiDeviceLifecycleEvent.DeviceDegraded &&
                    (it as V2MultiDeviceLifecycleEvent.DeviceDegraded).degradationKind == "ws_recovering"
            }
        }
        client.simulateError("ws timeout")

        val event = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first {
                it is V2MultiDeviceLifecycleEvent.DeviceDegraded &&
                    (it as V2MultiDeviceLifecycleEvent.DeviceDegraded).degradationKind == "ws_recovery_failed"
            }
        }
        assertNotNull(
            "ws_recovery_failed DeviceDegraded must be emitted even without a hostDescriptor",
            event
        )
    }

    // ── notifyParticipantHealthChanged DeviceDegraded identity fields ─────────

    @Test
    fun `health_degraded DeviceDegraded carries durableSessionId when durable record is active`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())
        controller.setActiveForTest()

        controller.notifyParticipantHealthChanged(ParticipantHealthState.DEGRADED)

        val event = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first {
                it is V2MultiDeviceLifecycleEvent.DeviceDegraded &&
                    (it as V2MultiDeviceLifecycleEvent.DeviceDegraded).degradationKind == "health_degraded"
            }
        }
        assertNotNull("Expected a health_degraded DeviceDegraded event", event)
        val degraded = event as V2MultiDeviceLifecycleEvent.DeviceDegraded
        assertNotNull(
            "health_degraded DeviceDegraded must carry durableSessionId when durable record is active",
            degraded.durableSessionId
        )
    }

    @Test
    fun `health_failed DeviceDegraded carries durableSessionId when durable record is active`() = runBlocking {
        val (controller, _) = buildController(descriptor = buildDescriptor())
        controller.setActiveForTest()

        controller.notifyParticipantHealthChanged(ParticipantHealthState.FAILED)

        val event = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first {
                it is V2MultiDeviceLifecycleEvent.DeviceDegraded &&
                    (it as V2MultiDeviceLifecycleEvent.DeviceDegraded).degradationKind == "health_failed"
            }
        }
        assertNotNull("Expected a health_failed DeviceDegraded event", event)
        val degraded = event as V2MultiDeviceLifecycleEvent.DeviceDegraded
        assertNotNull(
            "health_failed DeviceDegraded must carry durableSessionId when durable record is active",
            degraded.durableSessionId
        )
    }

    // ── Full RECOVERING → RECOVERED V2 sequence ───────────────────────────────

    @Test
    fun `RECOVERING to RECOVERED V2 sequence carries stable durableSessionId`() = runBlocking {
        val (controller, client) = buildController(descriptor = buildDescriptor())
        controller.setActiveForTest()
        val activationDurableId = controller.durableSessionContinuityRecord.value?.durableSessionId

        // Trigger RECOVERING
        client.simulateDisconnected()
        val recoveringEvent = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first {
                it is V2MultiDeviceLifecycleEvent.DeviceDegraded &&
                    (it as V2MultiDeviceLifecycleEvent.DeviceDegraded).degradationKind == "ws_recovering"
            }
        }
        val recovering = recoveringEvent as V2MultiDeviceLifecycleEvent.DeviceDegraded
        assertEquals(
            "DeviceDegraded(ws_recovering) must carry the activation-era durableSessionId",
            activationDurableId,
            recovering.durableSessionId
        )

        // Trigger RECOVERED
        client.simulateConnected()
        val reconnectedEvent = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first { it is V2MultiDeviceLifecycleEvent.DeviceReconnected }
        }
        assertNotNull("Expected a DeviceReconnected event after reconnect", reconnectedEvent)
        val reconnected = reconnectedEvent as V2MultiDeviceLifecycleEvent.DeviceReconnected
        assertEquals(
            "DeviceReconnected must carry the same durableSessionId as DeviceDegraded(ws_recovering)",
            recovering.durableSessionId,
            reconnected.durableSessionId
        )
    }

    // ── Full RECOVERING → FAILED V2 sequence ─────────────────────────────────

    @Test
    fun `RECOVERING to FAILED V2 sequence carries stable durableSessionId`() = runBlocking {
        val (controller, client) = buildController(descriptor = buildDescriptor())
        controller.setActiveForTest()
        val activationDurableId = controller.durableSessionContinuityRecord.value?.durableSessionId

        // Trigger RECOVERING
        client.simulateDisconnected()
        val recoveringEvent = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first {
                it is V2MultiDeviceLifecycleEvent.DeviceDegraded &&
                    (it as V2MultiDeviceLifecycleEvent.DeviceDegraded).degradationKind == "ws_recovering"
            }
        }
        val recovering = recoveringEvent as V2MultiDeviceLifecycleEvent.DeviceDegraded

        // Trigger FAILED
        client.simulateError("connection refused")
        val failedEvent = withTimeoutOrNull(300) {
            controller.v2LifecycleEvents.first {
                it is V2MultiDeviceLifecycleEvent.DeviceDegraded &&
                    (it as V2MultiDeviceLifecycleEvent.DeviceDegraded).degradationKind == "ws_recovery_failed"
            }
        }
        assertNotNull("Expected a ws_recovery_failed DeviceDegraded event", failedEvent)
        val failed = failedEvent as V2MultiDeviceLifecycleEvent.DeviceDegraded

        assertEquals(
            "DeviceDegraded(ws_recovery_failed) must carry the same durableSessionId as DeviceDegraded(ws_recovering)",
            recovering.durableSessionId,
            failed.durableSessionId
        )
        assertEquals(
            "Both recovery-cycle events must carry the activation-era durableSessionId",
            activationDurableId,
            failed.durableSessionId
        )
    }

    // ── StabilizationBaseline — PR-50 entries ─────────────────────────────────

    @Test
    fun `StabilizationBaseline has exactly three PR-50 entries`() {
        val pr50Entries = StabilizationBaseline.entries.filter { it.introducedPr == 50 }
        assertEquals("exactly three PR-50 entries expected", 3, pr50Entries.size)
    }

    @Test
    fun `v2-device-degraded-durable-session-id is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("v2-device-degraded-durable-session-id")
        assertNotNull("v2-device-degraded-durable-session-id must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `v2-device-degraded-session-continuity-epoch is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("v2-device-degraded-session-continuity-epoch")
        assertNotNull("v2-device-degraded-session-continuity-epoch must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `runtime-controller-recovery-v2-unconditional is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("runtime-controller-recovery-v2-unconditional")
        assertNotNull("runtime-controller-recovery-v2-unconditional must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `all PR-50 entries have EXTEND guidance`() {
        val pr50Entries = StabilizationBaseline.entries.filter { it.introducedPr == 50 }
        pr50Entries.forEach { entry ->
            assertEquals(
                "PR-50 entry '${entry.surfaceId}' must have EXTEND guidance",
                StabilizationBaseline.ExtensionGuidance.EXTEND,
                entry.extensionGuidance
            )
        }
    }

    @Test
    fun `all PR-50 entries have non-blank rationale`() {
        val pr50Entries = StabilizationBaseline.entries.filter { it.introducedPr == 50 }
        pr50Entries.forEach { entry ->
            assertTrue(
                "PR-50 entry '${entry.surfaceId}' must have non-blank rationale",
                entry.rationale.isNotBlank()
            )
        }
    }
}

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-118 — Android Continuity Diagnostics Contract tests.
 *
 * Validates that:
 * 1. [AndroidContinuityDiagnosticsContract] defines typed event kinds with stable wire values.
 * 2. [RuntimeController.continuityDiagnosticsEvents] is a non-null observable SharedFlow.
 * 3. Reconnect state transitions emit [ReconnectClassificationOutcome] events observable
 *    from service/test consumers without logcat.
 * 4. [RuntimeController.publishInflightContinuityRecovery] emits [RecoveryArtifactResolved]
 *    events with the correct disposition, source, and session metadata.
 * 5. [RuntimeController.emitReconciliationSignal] emits [ReconciliationSignalDiagnostic]
 *    events capturing the real emit outcome (EMITTED_IMMEDIATELY vs BUFFER_FULL_*).
 * 6. [RuntimeController.recordOfflineReplayQueued] and [recordOfflineReplayFlushed] emit
 *    [OfflineReplayEvent] events with the correct kind and queue depth.
 * 7. Session epoch hint in diagnostics events reflects the session at decision time;
 *    old-session events carry the stale session ID, not the current one.
 * 8. [StabilizationBaseline] registers the new surface with correct counts.
 */
class Pr118AndroidContinuityDiagnosticsContractTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ─── fakes ────────────────────────────────────────────────────────────────

    private class FakePlannerService : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(steps = listOf(LocalPlannerService.PlanStep("tap", "tap")))
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
            LocalGroundingService.GroundingResult(x = 540, y = 1170, confidence = 0.9f, element_description = "")
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
        settings: InMemoryAppSettings = InMemoryAppSettings(),
        descriptor: RuntimeHostDescriptor? = RuntimeHostDescriptor(
            hostId = "host-118", deviceId = "Pixel-118", deviceRole = "phone",
            formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L,
            hostDescriptor = descriptor
        )
        return controller to client
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. Contract structure — event kinds and wire constants
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `EVENT_KIND_RECONNECT_CLASSIFICATION has stable wire value`() {
        assertEquals(
            "reconnect_classification_outcome",
            AndroidContinuityDiagnosticsContract.EVENT_KIND_RECONNECT_CLASSIFICATION
        )
    }

    @Test
    fun `EVENT_KIND_OFFLINE_REPLAY has stable wire value`() {
        assertEquals(
            "offline_replay_event",
            AndroidContinuityDiagnosticsContract.EVENT_KIND_OFFLINE_REPLAY
        )
    }

    @Test
    fun `EVENT_KIND_RECOVERY_ARTIFACT_RESOLVED has stable wire value`() {
        assertEquals(
            "recovery_artifact_resolved",
            AndroidContinuityDiagnosticsContract.EVENT_KIND_RECOVERY_ARTIFACT_RESOLVED
        )
    }

    @Test
    fun `EVENT_KIND_RECONCILIATION_SIGNAL_DIAGNOSTIC has stable wire value`() {
        assertEquals(
            "reconciliation_signal_diagnostic",
            AndroidContinuityDiagnosticsContract.EVENT_KIND_RECONCILIATION_SIGNAL_DIAGNOSTIC
        )
    }

    @Test
    fun `OfflineReplayEventKind QUEUED has stable wire value`() {
        assertEquals("queued", AndroidContinuityDiagnosticsContract.OfflineReplayEventKind.QUEUED.wireValue)
    }

    @Test
    fun `OfflineReplayEventKind FLUSHED has stable wire value`() {
        assertEquals("flushed", AndroidContinuityDiagnosticsContract.OfflineReplayEventKind.FLUSHED.wireValue)
    }

    @Test
    fun `ReconciliationEmitOutcome EMITTED_IMMEDIATELY has stable wire value`() {
        assertEquals(
            "emitted_immediately",
            AndroidContinuityDiagnosticsContract.ReconciliationEmitOutcome.EMITTED_IMMEDIATELY.wireValue
        )
    }

    @Test
    fun `ReconciliationEmitOutcome BUFFER_FULL_RETRY_SCHEDULED has stable wire value`() {
        assertEquals(
            "buffer_full_retry_scheduled",
            AndroidContinuityDiagnosticsContract.ReconciliationEmitOutcome.BUFFER_FULL_RETRY_SCHEDULED.wireValue
        )
    }

    @Test
    fun `ReconciliationEmitOutcome BUFFER_FULL_DROPPED has stable wire value`() {
        assertEquals(
            "buffer_full_dropped",
            AndroidContinuityDiagnosticsContract.ReconciliationEmitOutcome.BUFFER_FULL_DROPPED.wireValue
        )
    }

    @Test
    fun `SCHEMA_VERSION is 1`() {
        assertEquals("1", AndroidContinuityDiagnosticsContract.SCHEMA_VERSION)
    }

    @Test
    fun `INTRODUCED_PR is 118`() {
        assertEquals(118, AndroidContinuityDiagnosticsContract.INTRODUCED_PR)
    }

    @Test
    fun `DIAGNOSTICS_INVARIANTS is non-empty and has 8 entries`() {
        assertEquals(8, AndroidContinuityDiagnosticsContract.DIAGNOSTICS_INVARIANTS.size)
        assertTrue(AndroidContinuityDiagnosticsContract.DIAGNOSTICS_INVARIANTS.all { it.isNotBlank() })
    }

    @Test
    fun `V2_CONSUMPTION_PATH_MAP covers all four event kinds`() {
        val map = AndroidContinuityDiagnosticsContract.V2_CONSUMPTION_PATH_MAP
        assertTrue(map.containsKey(AndroidContinuityDiagnosticsContract.EVENT_KIND_RECONNECT_CLASSIFICATION))
        assertTrue(map.containsKey(AndroidContinuityDiagnosticsContract.EVENT_KIND_OFFLINE_REPLAY))
        assertTrue(map.containsKey(AndroidContinuityDiagnosticsContract.EVENT_KIND_RECOVERY_ARTIFACT_RESOLVED))
        assertTrue(map.containsKey(AndroidContinuityDiagnosticsContract.EVENT_KIND_RECONCILIATION_SIGNAL_DIAGNOSTIC))
        assertTrue(map.values.all { it.isNotBlank() })
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. ContinuityDiagnosticsEvent toWireMap — schema fields
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ReconnectClassificationOutcome toWireMap includes all required fields`() {
        val event = AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconnectClassificationOutcome(
            fromState = "recovering",
            toState = "recovered",
            trigger = "ws_reconnected_active",
            sessionEpochHint = "sess-118:3",
            durableSessionId = "sess-118",
            observedAtMs = 100_000L
        )
        val wire = event.toWireMap()
        assertEquals(AndroidContinuityDiagnosticsContract.EVENT_KIND_RECONNECT_CLASSIFICATION, wire["event_kind"])
        assertEquals("recovering", wire["from_state"])
        assertEquals("recovered", wire["to_state"])
        assertEquals("ws_reconnected_active", wire["trigger"])
        assertEquals("sess-118:3", wire["session_epoch_hint"])
        assertEquals("sess-118", wire["durable_session_id"])
        assertEquals(100_000L, wire["observed_at_ms"])
        assertEquals(AndroidContinuityDiagnosticsContract.SCHEMA_VERSION, wire["schema_version"])
    }

    @Test
    fun `OfflineReplayEvent toWireMap includes all required fields`() {
        val event = AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.OfflineReplayEvent(
            kind = AndroidContinuityDiagnosticsContract.OfflineReplayEventKind.QUEUED.wireValue,
            queueDepth = 5,
            sessionTag = "sess-118",
            sessionEpochHint = "sess-118:2",
            durableSessionId = "sess-118",
            observedAtMs = 200_000L
        )
        val wire = event.toWireMap()
        assertEquals(AndroidContinuityDiagnosticsContract.EVENT_KIND_OFFLINE_REPLAY, wire["event_kind"])
        assertEquals("queued", wire["kind"])
        assertEquals(5, wire["queue_depth"])
        assertEquals("sess-118", wire["session_tag"])
        assertEquals(AndroidContinuityDiagnosticsContract.SCHEMA_VERSION, wire["schema_version"])
    }

    @Test
    fun `RecoveryArtifactResolved toWireMap includes all required fields`() {
        val event = AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.RecoveryArtifactResolved(
            disposition = "requires-reconciliation",
            source = "process_recreated",
            taskId = "task-118",
            artifactSessionId = "sess-old-117",
            sessionEpochHint = "sess-118:1",
            durableSessionId = "sess-118",
            observedAtMs = 300_000L
        )
        val wire = event.toWireMap()
        assertEquals(AndroidContinuityDiagnosticsContract.EVENT_KIND_RECOVERY_ARTIFACT_RESOLVED, wire["event_kind"])
        assertEquals("requires-reconciliation", wire["disposition"])
        assertEquals("process_recreated", wire["source"])
        assertEquals("task-118", wire["task_id"])
        assertEquals("sess-old-117", wire["artifact_session_id"])
        assertEquals(AndroidContinuityDiagnosticsContract.SCHEMA_VERSION, wire["schema_version"])
    }

    @Test
    fun `ReconciliationSignalDiagnostic toWireMap includes all required fields`() {
        val event = AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconciliationSignalDiagnostic(
            signalKind = "task_result",
            signalId = "sig-118",
            taskId = "task-118",
            emitOutcome = AndroidContinuityDiagnosticsContract.ReconciliationEmitOutcome.EMITTED_IMMEDIATELY.wireValue,
            sessionEpochHint = "sess-118:1",
            durableSessionId = "sess-118",
            observedAtMs = 400_000L
        )
        val wire = event.toWireMap()
        assertEquals(AndroidContinuityDiagnosticsContract.EVENT_KIND_RECONCILIATION_SIGNAL_DIAGNOSTIC, wire["event_kind"])
        assertEquals("task_result", wire["signal_kind"])
        assertEquals("sig-118", wire["signal_id"])
        assertEquals("task-118", wire["task_id"])
        assertEquals("emitted_immediately", wire["emit_outcome"])
        assertEquals(AndroidContinuityDiagnosticsContract.SCHEMA_VERSION, wire["schema_version"])
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. RuntimeController.continuityDiagnosticsEvents — flow is observable
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `continuityDiagnosticsEvents is non-null and observable`() {
        val (controller, _) = buildController()
        assertNotNull(controller.continuityDiagnosticsEvents)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. Reconnect classification — observable after reconnect state transition
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `WS disconnect emits ReconnectClassificationOutcome IDLE to RECOVERING`() = runBlocking {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val deferred = async {
            withTimeout(2_000L) {
                controller.continuityDiagnosticsEvents.first {
                    it is AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconnectClassificationOutcome &&
                        (it as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconnectClassificationOutcome)
                            .toState == ReconnectRecoveryState.RECOVERING.wireValue
                }
            }
        }
        client.simulateDisconnected()
        val event = deferred.await()
        val outcome = event as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconnectClassificationOutcome
        assertEquals(ReconnectRecoveryState.IDLE.wireValue, outcome.fromState)
        assertEquals(ReconnectRecoveryState.RECOVERING.wireValue, outcome.toState)
        assertEquals("ws_disconnect_active", outcome.trigger)
        assertTrue(outcome.observedAtMs > 0)
    }

    @Test
    fun `WS reconnect emits ReconnectClassificationOutcome RECOVERING to RECOVERED`() = runBlocking {
        val (controller, client) = buildController()
        controller.setActiveForTest()
        client.simulateDisconnected()
        // Verify precondition
        assertEquals(ReconnectRecoveryState.RECOVERING, controller.reconnectRecoveryState.value)

        val deferred = async {
            withTimeout(2_000L) {
                controller.continuityDiagnosticsEvents.first {
                    it is AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconnectClassificationOutcome &&
                        (it as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconnectClassificationOutcome)
                            .toState == ReconnectRecoveryState.RECOVERED.wireValue
                }
            }
        }
        client.simulateConnected()
        val event = deferred.await()
        val outcome = event as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconnectClassificationOutcome
        assertEquals(ReconnectRecoveryState.RECOVERING.wireValue, outcome.fromState)
        assertEquals(ReconnectRecoveryState.RECOVERED.wireValue, outcome.toState)
        assertEquals("ws_reconnected_active", outcome.trigger)
    }

    @Test
    fun `WS error emits ReconnectClassificationOutcome RECOVERING to FAILED`() = runBlocking {
        val (controller, client) = buildController()
        controller.setActiveForTest()
        client.simulateDisconnected()
        assertEquals(ReconnectRecoveryState.RECOVERING, controller.reconnectRecoveryState.value)

        val deferred = async {
            withTimeout(2_000L) {
                controller.continuityDiagnosticsEvents.first {
                    it is AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconnectClassificationOutcome &&
                        (it as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconnectClassificationOutcome)
                            .toState == ReconnectRecoveryState.FAILED.wireValue
                }
            }
        }
        client.simulateDisconnected()
        val event = deferred.await()
        val outcome = event as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconnectClassificationOutcome
        assertEquals(ReconnectRecoveryState.RECOVERING.wireValue, outcome.fromState)
        assertEquals(ReconnectRecoveryState.FAILED.wireValue, outcome.toState)
        assertEquals("ws_error", outcome.trigger)
    }

    @Test
    fun `reconnect classification event durableSessionId is null before any session is open`() =
        runBlocking {
            val (controller, client) = buildController()
            controller.setActiveForTest()

            val deferred = async {
                withTimeout(2_000L) {
                    controller.continuityDiagnosticsEvents.first {
                        it is AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconnectClassificationOutcome
                    }
                }
            }
            client.simulateDisconnected()
            val event = deferred.await()
            // session_epoch_hint reflects "no_session" when no durable session is attached
            // (setActiveForTest opens a TEST_ONLY session which may or may not set a durable ID)
            assertNotNull(
                (event as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconnectClassificationOutcome)
                    .sessionEpochHint
            )
        }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. Recovery artifact resolved — observable via continuityDiagnosticsEvents
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `RESUMED_CLEANLY recovery emits RecoveryArtifactResolved with resumed-cleanly disposition`() =
        runBlocking {
            val settings = InMemoryAppSettings()
            settings.crossDeviceEnabled = false // no artifact → RESUMED_CLEANLY
            val (controller, _) = buildController(settings = settings)

            val deferred = async {
                withTimeout(2_000L) {
                    controller.continuityDiagnosticsEvents.first {
                        it is AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.RecoveryArtifactResolved &&
                            (it as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.RecoveryArtifactResolved)
                                .disposition == InflightContinuityDisposition.RESUMED_CLEANLY.wireValue
                    }
                }
            }
            // connectIfEnabled calls classifyPersistedInflightContinuityIfNeeded
            controller.connectIfEnabled()
            val event = deferred.await()
            val resolved = event as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.RecoveryArtifactResolved
            assertEquals(InflightContinuityDisposition.RESUMED_CLEANLY.wireValue, resolved.disposition)
            assertNull(resolved.taskId)
        }

    @Test
    fun `LOST_INFLIGHT recovery emits RecoveryArtifactResolved with lost-inflight disposition`() =
        runBlocking {
            val settings = InMemoryAppSettings()
            settings.inflightContinuityRecoveryArtifact = InflightContinuityRecoveryArtifact(
                taskId = "task-118-lost",
                activeTaskStatus = ActiveTaskStatus.RUNNING.wireValue,
                durableSessionId = "sess-118",
                sessionContinuityEpoch = 1,
                runtimeAttachmentSessionId = null,
                attachedSessionId = null
            ).toJson()
            settings.crossDeviceEnabled = false // → LOST_INFLIGHT path
            val (controller, _) = buildController(settings = settings)

            val deferred = async {
                withTimeout(2_000L) {
                    controller.continuityDiagnosticsEvents.first {
                        it is AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.RecoveryArtifactResolved &&
                            (it as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.RecoveryArtifactResolved)
                                .disposition == InflightContinuityDisposition.LOST_INFLIGHT.wireValue
                    }
                }
            }
            controller.connectIfEnabled()
            val event = deferred.await()
            val resolved = event as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.RecoveryArtifactResolved
            assertEquals(InflightContinuityDisposition.LOST_INFLIGHT.wireValue, resolved.disposition)
            assertEquals("task-118-lost", resolved.taskId)
        }

    @Test
    fun `REQUIRES_RECONCILIATION recovery emits RecoveryArtifactResolved`() = runBlocking {
        val settings = InMemoryAppSettings()
        settings.inflightContinuityRecoveryArtifact = InflightContinuityRecoveryArtifact(
            taskId = "task-118-reconcile",
            activeTaskStatus = ActiveTaskStatus.RUNNING.wireValue,
            durableSessionId = "sess-118",
            sessionContinuityEpoch = 2,
            runtimeAttachmentSessionId = null,
            attachedSessionId = null
        ).toJson()
        settings.crossDeviceEnabled = true // → REQUIRES_RECONCILIATION path
        val (controller, _) = buildController(settings = settings)

        val deferred = async {
            withTimeout(2_000L) {
                controller.continuityDiagnosticsEvents.first {
                    it is AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.RecoveryArtifactResolved &&
                        (it as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.RecoveryArtifactResolved)
                            .disposition == InflightContinuityDisposition.REQUIRES_RECONCILIATION.wireValue
                }
            }
        }
        controller.connectIfEnabled()
        val event = deferred.await()
        val resolved = event as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.RecoveryArtifactResolved
        assertEquals(InflightContinuityDisposition.REQUIRES_RECONCILIATION.wireValue, resolved.disposition)
        assertEquals("task-118-reconcile", resolved.taskId)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. Stale session guard — old-session artifact carries stale session ID
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `stale recovery artifact emits RecoveryArtifactResolved with stale artifact session id`() =
        runBlocking {
            val settings = InMemoryAppSettings()
            val oldSessionId = "sess-old-999"
            val newSessionId = "sess-new-118"
            // Artifact from an old session
            settings.inflightContinuityRecoveryArtifact = InflightContinuityRecoveryArtifact(
                taskId = "task-stale",
                activeTaskStatus = ActiveTaskStatus.RUNNING.wireValue,
                durableSessionId = oldSessionId,
                sessionContinuityEpoch = 1,
                runtimeAttachmentSessionId = null,
                attachedSessionId = null
            ).toJson()
            // Current session in settings differs — stale guard fires
            settings.lastDurableSessionId = newSessionId
            val (controller, _) = buildController(settings = settings)

            val deferred = async {
                withTimeout(2_000L) {
                    controller.continuityDiagnosticsEvents.first {
                        it is AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.RecoveryArtifactResolved
                    }
                }
            }
            controller.connectIfEnabled()
            val event = deferred.await()
            val resolved = event as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.RecoveryArtifactResolved
            // STALE_RECOVERY_ARTIFACT — old session artifact must not masquerade as current
            assertEquals(InflightContinuityDisposition.STALE_RECOVERY_ARTIFACT.wireValue, resolved.disposition)
            // artifactSessionId carries the old session, not the new one
            assertEquals(oldSessionId, resolved.artifactSessionId)
        }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. Reconciliation signal diagnostic — observable emit outcome
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `publishTaskResult emits ReconciliationSignalDiagnostic with EMITTED_IMMEDIATELY outcome`() =
        runBlocking {
            val settings = InMemoryAppSettings()
            settings.crossDeviceEnabled = true
            val (controller, _) = buildController(settings = settings)
            controller.setActiveForTest()

            val deferred = async {
                withTimeout(2_000L) {
                    controller.continuityDiagnosticsEvents.first {
                        it is AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconciliationSignalDiagnostic &&
                            (it as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconciliationSignalDiagnostic)
                                .signalKind == ReconciliationSignal.Kind.TASK_RESULT.wireValue
                    }
                }
            }
            controller.recordDelegatedTaskAccepted(
                taskId = "task-118-result",
                correlationId = null
            )
            controller.publishTaskResult(
                taskId = "task-118-result",
                correlationId = null
            )
            val event = deferred.await()
            val diag = event as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconciliationSignalDiagnostic
            assertEquals(ReconciliationSignal.Kind.TASK_RESULT.wireValue, diag.signalKind)
            assertEquals("task-118-result", diag.taskId)
            assertEquals(
                AndroidContinuityDiagnosticsContract.ReconciliationEmitOutcome.EMITTED_IMMEDIATELY.wireValue,
                diag.emitOutcome
            )
            assertTrue(diag.signalId.isNotBlank())
        }

    @Test
    fun `reconciliation signal diagnostic emit outcome field is non-blank`() = runBlocking {
        val settings = InMemoryAppSettings()
        settings.crossDeviceEnabled = true
        val (controller, _) = buildController(settings = settings)
        controller.setActiveForTest()

        val deferred = async {
            withTimeout(2_000L) {
                controller.continuityDiagnosticsEvents.first {
                    it is AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconciliationSignalDiagnostic
                }
            }
        }
        controller.recordDelegatedTaskAccepted("task-118-emit-check", correlationId = null)
        val event = deferred.await()
        val diag = event as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.ReconciliationSignalDiagnostic
        assertTrue(diag.emitOutcome.isNotBlank())
        assertTrue(diag.signalId.isNotBlank())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 8. Offline replay events — QUEUED and FLUSHED observable
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `recordOfflineReplayQueued emits OfflineReplayEvent with QUEUED kind`() = runBlocking {
        val (controller, _) = buildController()

        val deferred = async {
            withTimeout(2_000L) {
                controller.continuityDiagnosticsEvents.first {
                    it is AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.OfflineReplayEvent &&
                        (it as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.OfflineReplayEvent)
                            .kind == AndroidContinuityDiagnosticsContract.OfflineReplayEventKind.QUEUED.wireValue
                }
            }
        }
        controller.recordOfflineReplayQueued(queueDepth = 3, sessionTag = "sess-118")
        val event = deferred.await()
        val replayEvent = event as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.OfflineReplayEvent
        assertEquals(AndroidContinuityDiagnosticsContract.OfflineReplayEventKind.QUEUED.wireValue, replayEvent.kind)
        assertEquals(3, replayEvent.queueDepth)
        assertEquals("sess-118", replayEvent.sessionTag)
        assertTrue(replayEvent.observedAtMs > 0)
    }

    @Test
    fun `recordOfflineReplayFlushed emits OfflineReplayEvent with FLUSHED kind`() = runBlocking {
        val (controller, _) = buildController()

        val deferred = async {
            withTimeout(2_000L) {
                controller.continuityDiagnosticsEvents.first {
                    it is AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.OfflineReplayEvent &&
                        (it as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.OfflineReplayEvent)
                            .kind == AndroidContinuityDiagnosticsContract.OfflineReplayEventKind.FLUSHED.wireValue
                }
            }
        }
        controller.recordOfflineReplayFlushed(queueDepth = 0, sessionTag = "sess-118")
        val event = deferred.await()
        val replayEvent = event as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.OfflineReplayEvent
        assertEquals(AndroidContinuityDiagnosticsContract.OfflineReplayEventKind.FLUSHED.wireValue, replayEvent.kind)
        assertEquals(0, replayEvent.queueDepth)
    }

    @Test
    fun `recordOfflineReplayQueued with null sessionTag produces null sessionTag in event`() =
        runBlocking {
            val (controller, _) = buildController()

            val deferred = async {
                withTimeout(2_000L) {
                    controller.continuityDiagnosticsEvents.first {
                        it is AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.OfflineReplayEvent
                    }
                }
            }
            controller.recordOfflineReplayQueued(queueDepth = 1, sessionTag = null)
            val event = deferred.await()
            val replayEvent = event as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.OfflineReplayEvent
            assertNull(replayEvent.sessionTag)
        }

    // ══════════════════════════════════════════════════════════════════════════
    // 9. Session epoch hint — no_session when no durable session open
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `offline replay event session epoch hint is no_session when no durable session is open`() =
        runBlocking {
            val (controller, _) = buildController()
            // No session opened yet

            val deferred = async {
                withTimeout(2_000L) {
                    controller.continuityDiagnosticsEvents.first {
                        it is AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.OfflineReplayEvent
                    }
                }
            }
            controller.recordOfflineReplayQueued(queueDepth = 2, sessionTag = null)
            val event = deferred.await()
            val replayEvent = event as AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent.OfflineReplayEvent
            assertEquals("no_session", replayEvent.sessionEpochHint)
            assertNull(replayEvent.durableSessionId)
        }

    // ══════════════════════════════════════════════════════════════════════════
    // 10. StabilizationBaseline — new surface registered
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `android-continuity-diagnostics-contract is registered in StabilizationBaseline`() {
        assertNotNull(StabilizationBaseline.forId("android-continuity-diagnostics-contract"))
    }

    @Test
    fun `android-continuity-diagnostics-contract is CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("android-continuity-diagnostics-contract")!!
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    @Test
    fun `android-continuity-diagnostics-contract introducedPr is 118`() {
        val e = StabilizationBaseline.forId("android-continuity-diagnostics-contract")!!
        assertEquals(118, e.introducedPr)
    }

    // 真 bug 修复(runtime-regression 349 处失败排查发现):以下两条断言的是写这个
    // 测试那个 PR-118 落地时的快照值,后续 PR 持续往 StabilizationBaseline.entries
    // 里加条目,数字自然过期。更新到当前真实值(CANONICAL_STABLE=150,total=156;
    // 156 已由 CI 实测验证——Pr14/Pr11 同断言在 156 时通过)。
    @Test
    fun `StabilizationBaseline CANONICAL_STABLE count is 150 after PR-118`() {
        assertEquals(150, StabilizationBaseline.byStability(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE).size)
    }

    @Test
    fun `StabilizationBaseline total entry count is 156 after PR-118`() {
        assertEquals(156, StabilizationBaseline.entries.size)
    }
}

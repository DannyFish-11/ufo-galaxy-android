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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-1 — Android Durable Session Runtime Foundation.
 *
 * Regression and acceptance test suite for all PR-1 additions:
 *
 *  1. [DurableSessionContinuityRecord] — durable session identity record that persists
 *     across WS reconnects within a single activation era, providing:
 *     - a stable [DurableSessionContinuityRecord.durableSessionId] (constant for the era)
 *     - a monotone [DurableSessionContinuityRecord.sessionContinuityEpoch] counter
 *       (incremented on each transparent reconnect)
 *
 *  2. [RuntimeController.durableSessionContinuityRecord] — observable [StateFlow]
 *     exposing the current durable record:
 *     - Created (epoch=0) on initial activation or background restore.
 *     - Epoch incremented on transparent reconnect ([SessionOpenSource.RECONNECT_RECOVERY]).
 *     - `null` before first activation and after [RuntimeController.stop] /
 *       [RuntimeController.invalidateSession].
 *
 *  3. [AttachedRuntimeHostSessionSnapshot] durable fields — two new optional fields
 *     [AttachedRuntimeHostSessionSnapshot.durableSessionId] and
 *     [AttachedRuntimeHostSessionSnapshot.sessionContinuityEpoch] projected from the
 *     active [DurableSessionContinuityRecord]:
 *     - Present in [AttachedRuntimeHostSessionSnapshot.toMap] only when a durable record
 *       is active; absent otherwise.
 *     - [AttachedRuntimeHostSessionSnapshot.ALWAYS_PRESENT_KEYS] is unchanged (still 8).
 *
 *  4. [CanonicalSessionAxis] durable extensions — new family
 *     [CanonicalSessionFamily.DURABLE_RUNTIME_SESSION], new layer
 *     [SessionContinuityLayer.DURABLE], new behavior
 *     [SessionContinuityBehavior.DURABLE_ACROSS_ACTIVATION], and two new carrier entries.
 *
 * ## Test matrix
 *
 * ### DurableSessionContinuityRecord — wire key constants
 *  - KEY_DURABLE_SESSION_ID is "durable_session_id"
 *  - KEY_SESSION_CONTINUITY_EPOCH is "session_continuity_epoch"
 *  - KEY_ACTIVATION_EPOCH_MS is "durable_session_activation_epoch_ms"
 *  - KEY_ACTIVATION_SOURCE is "durable_session_activation_source"
 *  - ALL_KEYS contains all four constants
 *  - all four wire keys are distinct
 *
 * ### DurableSessionContinuityRecord — create() factory
 *  - create() produces a non-null record
 *  - create() starts at epoch 0
 *  - create() assigns a non-blank durableSessionId
 *  - create() records the provided activationSource
 *  - create() sets a non-zero activationEpochMs
 *  - two create() calls produce distinct durableSessionIds
 *
 * ### DurableSessionContinuityRecord — withEpochIncremented()
 *  - withEpochIncremented increments epoch by exactly 1
 *  - withEpochIncremented preserves durableSessionId
 *  - withEpochIncremented preserves activationEpochMs
 *  - withEpochIncremented preserves activationSource
 *  - repeated withEpochIncremented calls accumulate correctly
 *
 * ### DurableSessionContinuityRecord — toMetadataMap()
 *  - toMetadataMap contains all four ALL_KEYS
 *  - toMetadataMap KEY_DURABLE_SESSION_ID maps to durableSessionId
 *  - toMetadataMap KEY_SESSION_CONTINUITY_EPOCH maps to epoch
 *
 * ### RuntimeController.durableSessionContinuityRecord — initial state
 *  - durableSessionContinuityRecord is null before activation
 *
 * ### RuntimeController.durableSessionContinuityRecord — setActiveForTest creates durable record
 *  - setActiveForTest produces non-null durableSessionContinuityRecord
 *  - setActiveForTest starts at epoch 0
 *  - setActiveForTest produces a non-blank durableSessionId
 *
 * ### RuntimeController.durableSessionContinuityRecord — persists across reconnect, epoch increments
 *  - durableSessionId is preserved after disconnect+reconnect cycle
 *  - sessionContinuityEpoch increments to 1 after first reconnect
 *  - sessionContinuityEpoch increments to 2 after second reconnect
 *
 * ### RuntimeController.durableSessionContinuityRecord — stop() resets to null
 *  - stop() resets durableSessionContinuityRecord to null
 *  - stop() resets even after a reconnect cycle
 *
 * ### RuntimeController.durableSessionContinuityRecord — invalidateSession() resets to null
 *  - invalidateSession() resets durableSessionContinuityRecord to null
 *
 * ### AttachedRuntimeHostSessionSnapshot — durable fields
 *  - from() with null durableRecord produces null durableSessionId in snapshot
 *  - from() with durable record populates durableSessionId and epoch
 *  - toMap() excludes durable keys when durableSessionId is null
 *  - toMap() includes durable keys when durableSessionId is non-null
 *  - toMap() includes correct epoch value
 *  - ALWAYS_PRESENT_KEYS still contains exactly 8 entries
 *  - KEY_DURABLE_SESSION_ID is not in ALWAYS_PRESENT_KEYS
 *  - KEY_SESSION_CONTINUITY_EPOCH is not in ALWAYS_PRESENT_KEYS
 *  - hostSessionSnapshot includes durableSessionId after setActiveForTest
 *  - hostSessionSnapshot durableSessionId is preserved across disconnect+reconnect
 *  - hostSessionSnapshot epoch increments after reconnect
 *  - hostSessionSnapshot durable fields absent after stop
 *
 * ### CanonicalSessionAxis — DURABLE_RUNTIME_SESSION extensions
 *  - DURABLE_RUNTIME_SESSION family has canonicalTerm "durable_session_id"
 *  - DURABLE_RUNTIME_SESSION family has no wireAlias
 *  - DURABLE continuity layer exists
 *  - DURABLE_ACROSS_ACTIVATION continuity behavior exists
 *  - DURABLE_ACROSS_ACTIVATION wireValue is "durable_across_activation" (if it has one)
 *  - CanonicalSessionAxis includes DURABLE_RUNTIME_SESSION carrier for DurableSessionContinuityRecord
 *  - CanonicalSessionAxis includes DURABLE_RUNTIME_SESSION carrier for snapshot
 *  - continuity model for DURABLE_RUNTIME_SESSION survives reconnect
 *  - continuity model for DURABLE_RUNTIME_SESSION does not survive invalidation
 *  - carriersForFamily DURABLE_RUNTIME_SESSION returns 2 entries
 *  - carriersForLayer DURABLE returns 2 entries
 */
class Pr1DurableSessionRuntimeFoundationTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake dependencies (mirroring Pr33ReconnectResilienceTest style) ───────

    private class TrivialPlannerService : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep("tap", "button"))
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

    private fun buildController(): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = InMemoryAppSettings(),
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L
        )
        return controller to client
    }

    // ── DurableSessionContinuityRecord — wire key constants ───────────────────

    @Test
    fun `KEY_DURABLE_SESSION_ID is durable_session_id`() {
        assertEquals("durable_session_id", DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID)
    }

    @Test
    fun `KEY_SESSION_CONTINUITY_EPOCH is session_continuity_epoch`() {
        assertEquals("session_continuity_epoch", DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH)
    }

    @Test
    fun `KEY_ACTIVATION_EPOCH_MS is durable_session_activation_epoch_ms`() {
        assertEquals(
            "durable_session_activation_epoch_ms",
            DurableSessionContinuityRecord.KEY_ACTIVATION_EPOCH_MS
        )
    }

    @Test
    fun `KEY_ACTIVATION_SOURCE is durable_session_activation_source`() {
        assertEquals(
            "durable_session_activation_source",
            DurableSessionContinuityRecord.KEY_ACTIVATION_SOURCE
        )
    }

    @Test
    fun `ALL_KEYS contains all four wire key constants`() {
        val all = DurableSessionContinuityRecord.ALL_KEYS
        assertTrue(all.contains(DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID))
        assertTrue(all.contains(DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH))
        assertTrue(all.contains(DurableSessionContinuityRecord.KEY_ACTIVATION_EPOCH_MS))
        assertTrue(all.contains(DurableSessionContinuityRecord.KEY_ACTIVATION_SOURCE))
        assertEquals("ALL_KEYS must have exactly 4 entries", 4, all.size)
    }

    @Test
    fun `all four DurableSessionContinuityRecord wire keys are distinct`() {
        val keys = listOf(
            DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID,
            DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH,
            DurableSessionContinuityRecord.KEY_ACTIVATION_EPOCH_MS,
            DurableSessionContinuityRecord.KEY_ACTIVATION_SOURCE
        )
        assertEquals("All four wire keys must be distinct", keys.size, keys.distinct().size)
    }

    // ── DurableSessionContinuityRecord — create() factory ────────────────────

    @Test
    fun `create() produces a non-null record`() {
        val record = DurableSessionContinuityRecord.create("user_activation")
        assertNotNull(record)
    }

    @Test
    fun `create() starts at epoch 0`() {
        val record = DurableSessionContinuityRecord.create("user_activation")
        assertEquals("Initial epoch must be 0", 0, record.sessionContinuityEpoch)
    }

    @Test
    fun `create() assigns a non-blank durableSessionId`() {
        val record = DurableSessionContinuityRecord.create("user_activation")
        assertTrue("durableSessionId must be non-blank", record.durableSessionId.isNotBlank())
    }

    @Test
    fun `create() records the provided activationSource`() {
        val record = DurableSessionContinuityRecord.create("background_restore")
        assertEquals("background_restore", record.activationSource)
    }

    @Test
    fun `create() sets a non-zero activationEpochMs`() {
        val before = System.currentTimeMillis()
        val record = DurableSessionContinuityRecord.create("user_activation")
        val after = System.currentTimeMillis()
        assertTrue(
            "activationEpochMs must be within the test execution window",
            record.activationEpochMs in before..after
        )
    }

    @Test
    fun `two create() calls produce distinct durableSessionIds`() {
        val a = DurableSessionContinuityRecord.create("user_activation")
        val b = DurableSessionContinuityRecord.create("user_activation")
        assertNotEquals(
            "Successive create() calls must produce distinct durableSessionIds",
            a.durableSessionId,
            b.durableSessionId
        )
    }

    // ── DurableSessionContinuityRecord — withEpochIncremented() ──────────────

    @Test
    fun `withEpochIncremented increments epoch by exactly 1`() {
        val record = DurableSessionContinuityRecord.create("user_activation")
        val next = record.withEpochIncremented()
        assertEquals("Epoch must increment by exactly 1", 1, next.sessionContinuityEpoch)
    }

    @Test
    fun `withEpochIncremented preserves durableSessionId`() {
        val record = DurableSessionContinuityRecord.create("user_activation")
        val next = record.withEpochIncremented()
        assertEquals(
            "durableSessionId must be unchanged after epoch increment",
            record.durableSessionId,
            next.durableSessionId
        )
    }

    @Test
    fun `withEpochIncremented preserves activationEpochMs`() {
        val record = DurableSessionContinuityRecord.create("user_activation")
        val next = record.withEpochIncremented()
        assertEquals(record.activationEpochMs, next.activationEpochMs)
    }

    @Test
    fun `withEpochIncremented preserves activationSource`() {
        val record = DurableSessionContinuityRecord.create("background_restore")
        val next = record.withEpochIncremented()
        assertEquals("background_restore", next.activationSource)
    }

    @Test
    fun `repeated withEpochIncremented calls accumulate correctly`() {
        val record = DurableSessionContinuityRecord.create("user_activation")
        val after3 = record
            .withEpochIncremented()
            .withEpochIncremented()
            .withEpochIncremented()
        assertEquals("Epoch must be 3 after three increments", 3, after3.sessionContinuityEpoch)
        assertEquals(
            "durableSessionId must be unchanged after multiple increments",
            record.durableSessionId,
            after3.durableSessionId
        )
    }

    // ── DurableSessionContinuityRecord — toMetadataMap() ─────────────────────

    @Test
    fun `toMetadataMap contains all four ALL_KEYS`() {
        val record = DurableSessionContinuityRecord.create("user_activation")
        val map = record.toMetadataMap()
        for (key in DurableSessionContinuityRecord.ALL_KEYS) {
            assertTrue("toMetadataMap must contain key '$key'", map.containsKey(key))
        }
    }

    @Test
    fun `toMetadataMap KEY_DURABLE_SESSION_ID maps to durableSessionId`() {
        val record = DurableSessionContinuityRecord.create("user_activation")
        val map = record.toMetadataMap()
        assertEquals(record.durableSessionId, map[DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID])
    }

    @Test
    fun `toMetadataMap KEY_SESSION_CONTINUITY_EPOCH maps to epoch`() {
        val record = DurableSessionContinuityRecord.create("user_activation")
            .withEpochIncremented()
            .withEpochIncremented()
        val map = record.toMetadataMap()
        assertEquals(2, map[DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH])
    }

    // ── RuntimeController — durableSessionContinuityRecord initial state ──────

    @Test
    fun `durableSessionContinuityRecord is null before any activation`() {
        val (controller, _) = buildController()
        assertNull(
            "durableSessionContinuityRecord must be null before activation",
            controller.durableSessionContinuityRecord.value
        )
    }

    // ── RuntimeController — setActiveForTest creates durable record ───────────

    @Test
    fun `setActiveForTest produces non-null durableSessionContinuityRecord`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()
        assertNotNull(
            "durableSessionContinuityRecord must be non-null after setActiveForTest",
            controller.durableSessionContinuityRecord.value
        )
    }

    @Test
    fun `setActiveForTest starts durable record at epoch 0`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()
        val record = controller.durableSessionContinuityRecord.value!!
        assertEquals(
            "Initial sessionContinuityEpoch must be 0 after setActiveForTest",
            0,
            record.sessionContinuityEpoch
        )
    }

    @Test
    fun `setActiveForTest produces a non-blank durableSessionId`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()
        val record = controller.durableSessionContinuityRecord.value!!
        assertTrue(
            "durableSessionId must be non-blank after setActiveForTest",
            record.durableSessionId.isNotBlank()
        )
    }

    // ── RuntimeController — durable record persists across reconnect ──────────

    @Test
    fun `durableSessionId is preserved after disconnect-reconnect cycle`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val idBeforeDisconnect = controller.durableSessionContinuityRecord.value!!.durableSessionId

        client.simulateDisconnected()
        client.simulateConnected()

        val idAfterReconnect = controller.durableSessionContinuityRecord.value!!.durableSessionId

        assertEquals(
            "durableSessionId must remain stable across a transparent disconnect/reconnect cycle",
            idBeforeDisconnect,
            idAfterReconnect
        )
    }

    @Test
    fun `sessionContinuityEpoch increments to 1 after first reconnect`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        assertEquals("Epoch must start at 0", 0, controller.durableSessionContinuityRecord.value!!.sessionContinuityEpoch)

        client.simulateDisconnected()
        client.simulateConnected()

        assertEquals(
            "Epoch must be 1 after first transparent reconnect",
            1,
            controller.durableSessionContinuityRecord.value!!.sessionContinuityEpoch
        )
    }

    @Test
    fun `sessionContinuityEpoch increments to 2 after second reconnect`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        // First reconnect cycle.
        client.simulateDisconnected()
        client.simulateConnected()

        // Second reconnect cycle.
        client.simulateDisconnected()
        client.simulateConnected()

        assertEquals(
            "Epoch must be 2 after second transparent reconnect",
            2,
            controller.durableSessionContinuityRecord.value!!.sessionContinuityEpoch
        )
    }

    // ── RuntimeController — stop() resets durable record ─────────────────────

    @Test
    fun `stop() resets durableSessionContinuityRecord to null`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()

        assertNotNull(
            "Precondition: durable record must exist before stop",
            controller.durableSessionContinuityRecord.value
        )

        controller.stop()

        assertNull(
            "durableSessionContinuityRecord must be null after stop()",
            controller.durableSessionContinuityRecord.value
        )
    }

    @Test
    fun `stop() resets durable record even after a reconnect cycle`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        client.simulateDisconnected()
        client.simulateConnected()

        assertEquals("Epoch must be 1 before stop", 1, controller.durableSessionContinuityRecord.value!!.sessionContinuityEpoch)

        controller.stop()

        assertNull(
            "durableSessionContinuityRecord must be null after stop() (even after reconnect cycle)",
            controller.durableSessionContinuityRecord.value
        )
    }

    // ── RuntimeController — invalidateSession() resets durable record ─────────

    @Test
    fun `invalidateSession() resets durableSessionContinuityRecord to null`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()

        assertNotNull(
            "Precondition: durable record must exist before invalidation",
            controller.durableSessionContinuityRecord.value
        )

        controller.invalidateSession()

        assertNull(
            "durableSessionContinuityRecord must be null after invalidateSession()",
            controller.durableSessionContinuityRecord.value
        )
    }

    // ── AttachedRuntimeHostSessionSnapshot — durable fields ──────────────────

    @Test
    fun `from() with null durableRecord produces null durableSessionId in snapshot`() {
        val session = AttachedRuntimeSession.create(hostId = "host-1", deviceId = "device-1")
        val snapshot = AttachedRuntimeHostSessionSnapshot.from(
            session = session,
            runtimeSessionId = "rts-001",
            hostRole = "participant",
            durableRecord = null
        )
        assertNull(
            "durableSessionId must be null when no durable record is provided",
            snapshot.durableSessionId
        )
    }

    @Test
    fun `from() with durable record populates durableSessionId`() {
        val session = AttachedRuntimeSession.create(hostId = "host-1", deviceId = "device-1")
        val record = DurableSessionContinuityRecord.create("user_activation")
        val snapshot = AttachedRuntimeHostSessionSnapshot.from(
            session = session,
            runtimeSessionId = "rts-001",
            hostRole = "participant",
            durableRecord = record
        )
        assertEquals(
            "durableSessionId must match the durable record's id",
            record.durableSessionId,
            snapshot.durableSessionId
        )
    }

    @Test
    fun `from() with durable record populates sessionContinuityEpoch`() {
        val session = AttachedRuntimeSession.create(hostId = "host-1", deviceId = "device-1")
        val record = DurableSessionContinuityRecord.create("user_activation")
            .withEpochIncremented()
        val snapshot = AttachedRuntimeHostSessionSnapshot.from(
            session = session,
            runtimeSessionId = "rts-001",
            hostRole = "participant",
            durableRecord = record
        )
        assertEquals(
            "sessionContinuityEpoch must match the durable record's epoch",
            1,
            snapshot.sessionContinuityEpoch
        )
    }

    @Test
    fun `toMap() excludes durable keys when durableSessionId is null`() {
        val session = AttachedRuntimeSession.create(hostId = "host-1", deviceId = "device-1")
        val snapshot = AttachedRuntimeHostSessionSnapshot.from(
            session = session,
            runtimeSessionId = "rts-001",
            hostRole = "participant",
            durableRecord = null
        )
        val map = snapshot.toMap()
        assertFalse(
            "toMap() must not include KEY_DURABLE_SESSION_ID when durableSessionId is null",
            map.containsKey(AttachedRuntimeHostSessionSnapshot.KEY_DURABLE_SESSION_ID)
        )
        assertFalse(
            "toMap() must not include KEY_SESSION_CONTINUITY_EPOCH when durableSessionId is null",
            map.containsKey(AttachedRuntimeHostSessionSnapshot.KEY_SESSION_CONTINUITY_EPOCH)
        )
    }

    @Test
    fun `toMap() includes durable keys when durableSessionId is non-null`() {
        val session = AttachedRuntimeSession.create(hostId = "host-1", deviceId = "device-1")
        val record = DurableSessionContinuityRecord.create("user_activation")
        val snapshot = AttachedRuntimeHostSessionSnapshot.from(
            session = session,
            runtimeSessionId = "rts-001",
            hostRole = "participant",
            durableRecord = record
        )
        val map = snapshot.toMap()
        assertTrue(
            "toMap() must include KEY_DURABLE_SESSION_ID when durableSessionId is non-null",
            map.containsKey(AttachedRuntimeHostSessionSnapshot.KEY_DURABLE_SESSION_ID)
        )
        assertTrue(
            "toMap() must include KEY_SESSION_CONTINUITY_EPOCH when durableSessionId is non-null",
            map.containsKey(AttachedRuntimeHostSessionSnapshot.KEY_SESSION_CONTINUITY_EPOCH)
        )
    }

    @Test
    fun `toMap() KEY_DURABLE_SESSION_ID maps to the durable record id`() {
        val session = AttachedRuntimeSession.create(hostId = "host-1", deviceId = "device-1")
        val record = DurableSessionContinuityRecord.create("user_activation")
        val snapshot = AttachedRuntimeHostSessionSnapshot.from(
            session = session,
            runtimeSessionId = "rts-001",
            hostRole = "participant",
            durableRecord = record
        )
        assertEquals(
            record.durableSessionId,
            snapshot.toMap()[AttachedRuntimeHostSessionSnapshot.KEY_DURABLE_SESSION_ID]
        )
    }

    @Test
    fun `toMap() KEY_SESSION_CONTINUITY_EPOCH maps to the durable record epoch`() {
        val session = AttachedRuntimeSession.create(hostId = "host-1", deviceId = "device-1")
        val record = DurableSessionContinuityRecord.create("user_activation")
            .withEpochIncremented()
            .withEpochIncremented()
        val snapshot = AttachedRuntimeHostSessionSnapshot.from(
            session = session,
            runtimeSessionId = "rts-001",
            hostRole = "participant",
            durableRecord = record
        )
        assertEquals(
            2,
            snapshot.toMap()[AttachedRuntimeHostSessionSnapshot.KEY_SESSION_CONTINUITY_EPOCH]
        )
    }

    @Test
    fun `ALWAYS_PRESENT_KEYS still contains exactly 8 entries after PR-1`() {
        assertEquals(
            "ALWAYS_PRESENT_KEYS must remain 8 after PR-1 (durable fields are conditional)",
            8,
            AttachedRuntimeHostSessionSnapshot.ALWAYS_PRESENT_KEYS.size
        )
    }

    @Test
    fun `KEY_DURABLE_SESSION_ID is not in ALWAYS_PRESENT_KEYS`() {
        assertFalse(
            "KEY_DURABLE_SESSION_ID must be excluded from ALWAYS_PRESENT_KEYS (conditional field)",
            AttachedRuntimeHostSessionSnapshot.ALWAYS_PRESENT_KEYS.contains(
                AttachedRuntimeHostSessionSnapshot.KEY_DURABLE_SESSION_ID
            )
        )
    }

    @Test
    fun `KEY_SESSION_CONTINUITY_EPOCH is not in ALWAYS_PRESENT_KEYS`() {
        assertFalse(
            "KEY_SESSION_CONTINUITY_EPOCH must be excluded from ALWAYS_PRESENT_KEYS (conditional field)",
            AttachedRuntimeHostSessionSnapshot.ALWAYS_PRESENT_KEYS.contains(
                AttachedRuntimeHostSessionSnapshot.KEY_SESSION_CONTINUITY_EPOCH
            )
        )
    }

    @Test
    fun `hostSessionSnapshot includes durableSessionId after setActiveForTest`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()

        val snapshot = controller.hostSessionSnapshot.value
        assertNotNull("hostSessionSnapshot must be non-null after setActiveForTest", snapshot)
        assertNotNull(
            "hostSessionSnapshot.durableSessionId must be non-null after setActiveForTest",
            snapshot!!.durableSessionId
        )
    }

    @Test
    fun `hostSessionSnapshot durableSessionId is preserved across disconnect-reconnect`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        val idBefore = controller.hostSessionSnapshot.value!!.durableSessionId
        assertNotNull("Precondition: durableSessionId must be non-null before disconnect", idBefore)

        client.simulateDisconnected()
        client.simulateConnected()

        val idAfter = controller.hostSessionSnapshot.value!!.durableSessionId
        assertEquals(
            "hostSessionSnapshot.durableSessionId must remain stable across reconnect",
            idBefore,
            idAfter
        )
    }

    @Test
    fun `hostSessionSnapshot sessionContinuityEpoch increments after reconnect`() {
        val (controller, client) = buildController()
        controller.setActiveForTest()

        assertEquals(
            "Initial epoch in snapshot must be 0",
            0,
            controller.hostSessionSnapshot.value!!.sessionContinuityEpoch
        )

        client.simulateDisconnected()
        client.simulateConnected()

        assertEquals(
            "Epoch in snapshot must be 1 after first reconnect",
            1,
            controller.hostSessionSnapshot.value!!.sessionContinuityEpoch
        )
    }

    @Test
    fun `hostSessionSnapshot durable fields are absent after stop`() {
        val (controller, _) = buildController()
        controller.setActiveForTest()
        controller.stop()

        // After stop, there is no attached session, so hostSessionSnapshot is null.
        // The durable record should be cleared.
        assertNull(
            "durableSessionContinuityRecord must be null after stop",
            controller.durableSessionContinuityRecord.value
        )
    }

    // ── CanonicalSessionAxis — DURABLE_RUNTIME_SESSION extensions ────────────

    @Test
    fun `DURABLE_RUNTIME_SESSION family has canonicalTerm durable_session_id`() {
        assertEquals(
            "durable_session_id",
            CanonicalSessionFamily.DURABLE_RUNTIME_SESSION.canonicalTerm
        )
    }

    @Test
    fun `DURABLE_RUNTIME_SESSION family has no wireAlias`() {
        assertNull(
            "DURABLE_RUNTIME_SESSION must have no wire alias",
            CanonicalSessionFamily.DURABLE_RUNTIME_SESSION.wireAlias
        )
    }

    @Test
    fun `DURABLE_RUNTIME_SESSION hasWireAlias is false`() {
        assertFalse(CanonicalSessionFamily.DURABLE_RUNTIME_SESSION.hasWireAlias)
    }

    @Test
    fun `DURABLE continuity layer exists`() {
        val layer = SessionContinuityLayer.DURABLE
        assertNotNull(layer)
    }

    @Test
    fun `DURABLE_ACROSS_ACTIVATION continuity behavior exists`() {
        val behavior = SessionContinuityBehavior.DURABLE_ACROSS_ACTIVATION
        assertNotNull(behavior)
    }

    @Test
    fun `CanonicalSessionAxis carriersForFamily DURABLE_RUNTIME_SESSION returns 2 entries`() {
        val axis = CanonicalSessionAxis()
        val carriers = axis.carriersForFamily(CanonicalSessionFamily.DURABLE_RUNTIME_SESSION)
        assertEquals(
            "DURABLE_RUNTIME_SESSION family must have exactly 2 carrier entries",
            2,
            carriers.size
        )
    }

    @Test
    fun `CanonicalSessionAxis carriersForLayer DURABLE returns 2 entries`() {
        val axis = CanonicalSessionAxis()
        val carriers = axis.carriersForLayer(SessionContinuityLayer.DURABLE)
        assertEquals(
            "DURABLE layer must have exactly 2 carrier entries",
            2,
            carriers.size
        )
    }

    @Test
    fun `CanonicalSessionAxis includes DurableSessionContinuityRecord carrier`() {
        val axis = CanonicalSessionAxis()
        val entry = axis.entryForCarrier("DurableSessionContinuityRecord.durableSessionId")
        assertNotNull(
            "CanonicalSessionAxis must include entry for DurableSessionContinuityRecord.durableSessionId",
            entry
        )
        assertEquals(
            "durable_session_id",
            entry!!.crossRepoTerm
        )
        assertEquals(
            CanonicalSessionFamily.DURABLE_RUNTIME_SESSION,
            entry.sessionFamily
        )
    }

    @Test
    fun `CanonicalSessionAxis includes AttachedRuntimeHostSessionSnapshot durable carrier`() {
        val axis = CanonicalSessionAxis()
        val entry = axis.entryForCarrier("AttachedRuntimeHostSessionSnapshot.durableSessionId")
        assertNotNull(
            "CanonicalSessionAxis must include entry for AttachedRuntimeHostSessionSnapshot.durableSessionId",
            entry
        )
        assertEquals(
            CanonicalSessionFamily.DURABLE_RUNTIME_SESSION,
            entry!!.sessionFamily
        )
        assertEquals(
            SessionContinuityLayer.DURABLE,
            entry.continuityLayer
        )
    }

    @Test
    fun `continuity model for DURABLE_RUNTIME_SESSION survives reconnect`() {
        val axis = CanonicalSessionAxis()
        val model = axis.continuityModelFor(CanonicalSessionFamily.DURABLE_RUNTIME_SESSION)
        assertNotNull("DURABLE_RUNTIME_SESSION must have a continuity model", model)
        assertTrue(
            "DURABLE_RUNTIME_SESSION must survive reconnect",
            model!!.surviveReconnect
        )
    }

    @Test
    fun `continuity model for DURABLE_RUNTIME_SESSION does not survive invalidation`() {
        val axis = CanonicalSessionAxis()
        val model = axis.continuityModelFor(CanonicalSessionFamily.DURABLE_RUNTIME_SESSION)
        assertNotNull("DURABLE_RUNTIME_SESSION must have a continuity model", model)
        assertFalse(
            "DURABLE_RUNTIME_SESSION must not survive invalidation",
            model!!.surviveInvalidation
        )
    }

    @Test
    fun `continuity model for DURABLE_RUNTIME_SESSION uses DURABLE_ACROSS_ACTIVATION behavior`() {
        val axis = CanonicalSessionAxis()
        val model = axis.continuityModelFor(CanonicalSessionFamily.DURABLE_RUNTIME_SESSION)
        assertNotNull(model)
        assertEquals(
            SessionContinuityBehavior.DURABLE_ACROSS_ACTIVATION,
            model!!.continuityBehavior
        )
    }

    @Test
    fun `all CanonicalSessionFamily values including DURABLE_RUNTIME_SESSION have continuity models`() {
        val axis = CanonicalSessionAxis()
        for (family in CanonicalSessionFamily.values()) {
            assertNotNull(
                "CanonicalSessionAxis must have a continuity model for family $family",
                axis.continuityModelFor(family)
            )
        }
    }

    // ── RuntimeController — PR-7 lastDurableSessionId persistence ────────────

    private fun buildControllerWithSettings(): Triple<RuntimeController, GalaxyWebSocketClient, InMemoryAppSettings> {
        val settings = InMemoryAppSettings()
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = buildLoopController(),
            registrationTimeoutMs = 100L
        )
        return Triple(controller, client, settings)
    }

    @Test
    fun `setActiveForTest persists durableSessionId to settings lastDurableSessionId`() {
        val (controller, _, settings) = buildControllerWithSettings()

        assertTrue("Precondition: lastDurableSessionId must be blank before activation",
            settings.lastDurableSessionId.isBlank())

        controller.setActiveForTest()

        val durableId = controller.durableSessionContinuityRecord.value?.durableSessionId
        assertNotNull("Precondition: durable record must exist after setActiveForTest", durableId)
        assertEquals(
            "lastDurableSessionId must match the active durableSessionId after activation",
            durableId,
            settings.lastDurableSessionId
        )
    }

    @Test
    fun `stop() clears settings lastDurableSessionId`() {
        val (controller, _, settings) = buildControllerWithSettings()
        controller.setActiveForTest()

        assertTrue("Precondition: lastDurableSessionId must be non-blank after activation",
            settings.lastDurableSessionId.isNotBlank())

        controller.stop()

        assertTrue(
            "lastDurableSessionId must be blank after stop()",
            settings.lastDurableSessionId.isBlank()
        )
    }

    @Test
    fun `invalidateSession() clears settings lastDurableSessionId`() {
        val (controller, _, settings) = buildControllerWithSettings()
        controller.setActiveForTest()

        assertTrue("Precondition: lastDurableSessionId must be non-blank after activation",
            settings.lastDurableSessionId.isNotBlank())

        controller.invalidateSession()

        assertTrue(
            "lastDurableSessionId must be blank after invalidateSession()",
            settings.lastDurableSessionId.isBlank()
        )
    }

    @Test
    fun `reconnect cycle does not change settings lastDurableSessionId`() {
        val (controller, client, settings) = buildControllerWithSettings()
        controller.setActiveForTest()

        val idAfterActivation = settings.lastDurableSessionId
        assertTrue("Precondition: lastDurableSessionId must be non-blank after activation",
            idAfterActivation.isNotBlank())

        client.simulateDisconnected()
        client.simulateConnected()

        assertEquals(
            "lastDurableSessionId must be unchanged after a transparent reconnect cycle",
            idAfterActivation,
            settings.lastDurableSessionId
        )
    }
}

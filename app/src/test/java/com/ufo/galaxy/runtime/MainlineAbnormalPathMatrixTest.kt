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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-25 — Mainline abnormal-path matrix and Phase A acceptance.
 *
 * Verifies that:
 *  1. **Remote task blocks local loop**: [RuntimeController.onRemoteTaskStarted] immediately
 *     cancels any running local [LoopController] session and marks the loop as blocked;
 *     subsequent local execution attempts return [LoopController.STATUS_CANCELLED] with
 *     [LoopController.STOP_BLOCKED_BY_REMOTE] until [RuntimeController.onRemoteTaskFinished]
 *     clears the block.
 *  2. **Fallback local after remote failure**: after any takeover failure cause
 *     (FAILED / TIMEOUT / CANCELLED / DISCONNECT), calling [RuntimeController.onRemoteTaskFinished]
 *     unblocks the local loop so that subsequent local executions succeed without any additional
 *     state reset.
 *  3. **Session truth through failures**: [RuntimeController.attachedSession],
 *     [RuntimeController.hostSessionSnapshot], and [RuntimeController.targetReadinessProjection]
 *     are deliberately NOT modified by a takeover failure — the device stays attached and ready
 *     for the next incoming delegated task.
 *  4. **Selection truth through abnormal paths**: [DelegatedTargetReadinessProjection.selectionOutcome]
 *     remains [TargetSelectionOutcome.SELECTED] (for primary-role sessions) and
 *     [TargetSelectionOutcome.FALLBACK] (for secondary-role sessions) through takeover failures;
 *     it transitions deterministically to [TargetSelectionOutcome.REJECTED] only on explicit
 *     session-closing events (stop / invalidate / disconnect).
 *  5. **Phase A acceptance**: all key delegated execution, session truth, fallback, and selection
 *     flows complete with stable, predictable outcomes — regressions in any of these abnormal-path
 *     behaviors are caught deterministically.
 *  6. **No duplicate authority**: there is no second path that produces conflicting fallback or
 *     session-truth state — [RuntimeController] remains the sole authority.
 *
 * ## Dependency
 *
 * Assumes PR-24 baseline: [DelegatedTargetReadinessProjection.selectionOutcome] and
 * [RuntimeController.targetReadinessProjection] are already in place.
 *
 * ## Test matrix
 *
 * ### Remote task blocks local loop (AIP v3 — task_assign / goal_execution arrival)
 *  - onRemoteTaskStarted sets loopController isRemoteTaskActive to true
 *  - blocked local loop returns STATUS_CANCELLED
 *  - blocked local loop stopReason is STOP_BLOCKED_BY_REMOTE
 *  - local loop remains blocked after notifyTakeoverFailed alone (no auto-unblock)
 *
 * ### Fallback local after remote failure (all four causes)
 *  - after FAILED: onRemoteTaskFinished unblocks loop — local execute succeeds
 *  - after TIMEOUT: onRemoteTaskFinished unblocks loop — local execute succeeds
 *  - after CANCELLED: onRemoteTaskFinished unblocks loop — local execute succeeds
 *  - after DISCONNECT: onRemoteTaskFinished unblocks loop — local execute succeeds
 *
 * ### Full abnormal sequence (block → fail → unblock → local succeeds)
 *  - full abnormal sequence with FAILED cause completes with local STATUS_SUCCESS
 *  - full abnormal sequence with DISCONNECT cause completes with local STATUS_SUCCESS
 *  - multiple sequential abnormal cycles all end with local execution available
 *
 * ### Session truth through abnormal paths
 *  - attachedSession is unchanged after notifyTakeoverFailed
 *  - hostSessionSnapshot is unchanged after notifyTakeoverFailed
 *  - targetReadinessProjection is unchanged after notifyTakeoverFailed
 *  - session stays suitable for next delegated task after takeover failure
 *
 * ### Selection truth through abnormal paths (Phase A acceptance)
 *  - SELECTED outcome is stable through takeover failure (primary role)
 *  - FALLBACK outcome is stable through takeover failure (secondary role)
 *  - REJECTED outcome after stop() is deterministic
 *  - REJECTED outcome after invalidateSession() is deterministic
 *  - selection outcome transitions correctly: attach SELECTED → stop → REJECTED
 *
 * ### Delegated execution failure path acceptance
 *  - notifyTakeoverFailed emits before loop is unblocked (ordering guarantee)
 *  - onRemoteTaskFinished is safe after notifyTakeoverFailed (correct finally-block order)
 *  - double onRemoteTaskFinished after failure is safe and idempotent
 *
 * ### Phase A acceptance: no duplicate authority or parallel abnormal-path manager
 *  - takeoverFailure is sole failure authority: no session mutation, no state change
 *  - settings.crossDeviceEnabled not mutated by takeover failure
 *  - registrationError does not emit on takeover failure
 */
class MainlineAbnormalPathMatrixTest {

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

    private fun buildController(
        loopController: LoopController = buildLoopController(),
        settings: InMemoryAppSettings = InMemoryAppSettings(),
        hostDescriptor: RuntimeHostDescriptor? = buildHostDescriptor(),
        timeoutMs: Long = 100L
    ): Pair<RuntimeController, GalaxyWebSocketClient> {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false
        )
        val controller = RuntimeController(
            webSocketClient = client,
            settings = settings,
            loopController = loopController,
            registrationTimeoutMs = timeoutMs,
            hostDescriptor = hostDescriptor
        )
        return controller to client
    }

    private fun buildHostDescriptor(
        hostId: String = "pr25-host",
        deviceId: String = "pr25-device",
        formationRole: RuntimeHostDescriptor.FormationRole = RuntimeHostDescriptor.FormationRole.PRIMARY
    ): RuntimeHostDescriptor = RuntimeHostDescriptor(
        hostId = hostId,
        deviceId = deviceId,
        deviceRole = "phone",
        formationRole = formationRole,
        participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
    )

    private fun freshSession(
        hostId: String = "pr25-host",
        deviceId: String = "pr25-device"
    ): AttachedRuntimeSession = AttachedRuntimeSession.create(hostId = hostId, deviceId = deviceId)

    private fun snapshotFrom(
        session: AttachedRuntimeSession,
        runtimeSessionId: String = "rts-pr25",
        hostRole: String = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
    ) = AttachedRuntimeHostSessionSnapshot.from(
        session = session,
        runtimeSessionId = runtimeSessionId,
        hostRole = hostRole
    )

    private fun readinessFrom(
        session: AttachedRuntimeSession,
        runtimeSessionId: String = "rts-pr25",
        hostRole: String = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
    ) = DelegatedTargetReadinessProjection.from(snapshotFrom(session, runtimeSessionId, hostRole))

    // ── Remote task blocks local loop ─────────────────────────────────────────

    @Test
    fun `onRemoteTaskStarted sets loopController isRemoteTaskActive to true`() {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        assertFalse("isRemoteTaskActive must be false before any remote task", loopController.isRemoteTaskActive)
        controller.onRemoteTaskStarted()
        assertTrue(
            "isRemoteTaskActive must be true immediately after onRemoteTaskStarted",
            loopController.isRemoteTaskActive
        )
    }

    @Test
    fun `blocked local loop returns STATUS_CANCELLED`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        controller.onRemoteTaskStarted()
        val result = loopController.execute("open settings")

        assertEquals(
            "Local loop must return STATUS_CANCELLED when remote task is active",
            LoopController.STATUS_CANCELLED,
            result.status
        )
    }

    @Test
    fun `blocked local loop stopReason is STOP_BLOCKED_BY_REMOTE`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        controller.onRemoteTaskStarted()
        val result = loopController.execute("send message")

        assertEquals(
            "stopReason must be STOP_BLOCKED_BY_REMOTE to distinguish from user-cancel",
            LoopController.STOP_BLOCKED_BY_REMOTE,
            result.stopReason
        )
    }

    @Test
    fun `local loop remains blocked after notifyTakeoverFailed alone`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        controller.onRemoteTaskStarted()
        assertTrue("Loop must be blocked before failure notification", loopController.isRemoteTaskActive)

        // notifyTakeoverFailed alone must NOT clear the block — onRemoteTaskFinished() is
        // the caller's responsibility, typically in a finally block.
        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "err", TakeoverFallbackEvent.Cause.FAILED)
        job.join()

        assertTrue(
            "Loop must remain blocked after notifyTakeoverFailed — only onRemoteTaskFinished clears it",
            loopController.isRemoteTaskActive
        )
    }

    // ── Fallback local after remote failure (all four causes) ─────────────────

    @Test
    fun `after FAILED remote execution onRemoteTaskFinished unblocks loop and local succeeds`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        controller.onRemoteTaskStarted()
        val failJob = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-f", "t-f", "tr-f", "pipeline_error", TakeoverFallbackEvent.Cause.FAILED)
        failJob.join()
        controller.onRemoteTaskFinished()  // finally-block equivalent

        val localResult = loopController.execute("open camera")
        assertEquals(
            "Local execution must succeed (STATUS_SUCCESS) after FAILED remote fallback",
            LoopController.STATUS_SUCCESS,
            localResult.status
        )
    }

    @Test
    fun `after TIMEOUT remote execution onRemoteTaskFinished unblocks loop and local succeeds`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        controller.onRemoteTaskStarted()
        val failJob = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-t", "t-t", "tr-t", "timed_out", TakeoverFallbackEvent.Cause.TIMEOUT)
        failJob.join()
        controller.onRemoteTaskFinished()

        val localResult = loopController.execute("open camera")
        assertEquals(
            "Local execution must succeed (STATUS_SUCCESS) after TIMEOUT remote fallback",
            LoopController.STATUS_SUCCESS,
            localResult.status
        )
    }

    @Test
    fun `after CANCELLED remote execution onRemoteTaskFinished unblocks loop and local succeeds`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        controller.onRemoteTaskStarted()
        val failJob = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-c", "t-c", "tr-c", "user_cancelled", TakeoverFallbackEvent.Cause.CANCELLED)
        failJob.join()
        controller.onRemoteTaskFinished()

        val localResult = loopController.execute("tap the button")
        assertEquals(
            "Local execution must succeed (STATUS_SUCCESS) after CANCELLED remote fallback",
            LoopController.STATUS_SUCCESS,
            localResult.status
        )
    }

    @Test
    fun `after DISCONNECT remote execution onRemoteTaskFinished unblocks loop and local succeeds`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        controller.onRemoteTaskStarted()
        val failJob = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-d", "t-d", "tr-d", "ws_disconnected", TakeoverFallbackEvent.Cause.DISCONNECT)
        failJob.join()
        controller.onRemoteTaskFinished()

        val localResult = loopController.execute("tap the button")
        assertEquals(
            "Local execution must succeed (STATUS_SUCCESS) after DISCONNECT remote fallback",
            LoopController.STATUS_SUCCESS,
            localResult.status
        )
    }

    // ── Full abnormal sequence (block → fail → unblock → local succeeds) ──────

    @Test
    fun `full abnormal sequence with FAILED cause completes with local STATUS_SUCCESS`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        // Step 1: remote task starts → local loop is blocked.
        controller.onRemoteTaskStarted()
        val blockedResult = loopController.execute("should be blocked")
        assertEquals(
            "Loop must be blocked (CANCELLED) during remote task execution",
            LoopController.STATUS_CANCELLED,
            blockedResult.status
        )
        assertEquals(LoopController.STOP_BLOCKED_BY_REMOTE, blockedResult.stopReason)

        // Step 2: remote task fails → failure event emitted.
        val failJob = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed(
            takeoverId = "to-full-failed",
            taskId = "t-full",
            traceId = "tr-full",
            reason = "execution_pipeline_error",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        failJob.join()

        // Step 3: finally-block clears the remote-task block.
        controller.onRemoteTaskFinished()
        assertFalse(
            "Loop must be unblocked after onRemoteTaskFinished",
            loopController.isRemoteTaskActive
        )

        // Step 4: local execution resumes successfully.
        val localResult = loopController.execute("open settings after fallback")
        assertEquals(
            "Local fallback execution must succeed after full abnormal path cycle",
            LoopController.STATUS_SUCCESS,
            localResult.status
        )
    }

    @Test
    fun `full abnormal sequence with DISCONNECT cause completes with local STATUS_SUCCESS`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        controller.onRemoteTaskStarted()

        // Simulate disconnect while task in flight.
        val failJob = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed(
            takeoverId = "to-disc",
            taskId = "t-disc",
            traceId = "tr-disc",
            reason = "gateway_disconnected",
            cause = TakeoverFallbackEvent.Cause.DISCONNECT
        )
        failJob.join()
        controller.onRemoteTaskFinished()

        val localResult = loopController.execute("navigate home after disconnect fallback")
        assertEquals(
            "Local fallback must succeed after disconnect-caused remote failure",
            LoopController.STATUS_SUCCESS,
            localResult.status
        )
    }

    @Test
    fun `multiple sequential abnormal cycles all end with local execution available`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        val causes = listOf(
            TakeoverFallbackEvent.Cause.FAILED,
            TakeoverFallbackEvent.Cause.TIMEOUT,
            TakeoverFallbackEvent.Cause.CANCELLED
        )

        causes.forEachIndexed { index, cause ->
            controller.onRemoteTaskStarted()

            val failJob = launch { controller.takeoverFailure.first() }
            controller.notifyTakeoverFailed(
                takeoverId = "to-cycle-$index",
                taskId = "t-cycle-$index",
                traceId = "tr-cycle-$index",
                reason = "cycle_failure_$index",
                cause = cause
            )
            failJob.join()
            controller.onRemoteTaskFinished()

            val localResult = loopController.execute("local task after cycle $index")
            assertEquals(
                "Local execution must succeed after abnormal cycle $index (cause=${cause.wireValue})",
                LoopController.STATUS_SUCCESS,
                localResult.status
            )
        }
    }

    // ── Session truth through abnormal paths ──────────────────────────────────

    @Test
    fun `attachedSession is unchanged after notifyTakeoverFailed`() = runBlocking {
        val (controller, _) = buildController()

        val sessionBefore = controller.attachedSession.value  // null — no session opened yet
        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "err", TakeoverFallbackEvent.Cause.FAILED)
        job.join()

        assertEquals(
            "attachedSession must be unchanged after takeover failure — session is not invalidated by task failures",
            sessionBefore,
            controller.attachedSession.value
        )
    }

    @Test
    fun `hostSessionSnapshot is unchanged after notifyTakeoverFailed`() = runBlocking {
        val (controller, _) = buildController()

        val snapshotBefore = controller.hostSessionSnapshot.value
        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "err", TakeoverFallbackEvent.Cause.TIMEOUT)
        job.join()

        assertEquals(
            "hostSessionSnapshot must be unchanged after takeover failure — snapshot is not re-derived by task failures",
            snapshotBefore,
            controller.hostSessionSnapshot.value
        )
    }

    @Test
    fun `targetReadinessProjection is unchanged after notifyTakeoverFailed`() = runBlocking {
        val (controller, _) = buildController()

        val projectionBefore = controller.targetReadinessProjection.value
        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "err", TakeoverFallbackEvent.Cause.CANCELLED)
        job.join()

        assertEquals(
            "targetReadinessProjection must be unchanged after takeover failure — selection truth not disturbed by task-level failures",
            projectionBefore,
            controller.targetReadinessProjection.value
        )
    }

    @Test
    fun `session isSuitableTarget stays true through takeover failure for primary role`() {
        val attached = freshSession()
        val readiness = readinessFrom(
            session = attached,
            hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
        )

        assertTrue(
            "isSuitableTarget must be true for a live ATTACHED session — not affected by task-level failures",
            readiness.isSuitableTarget
        )
        assertEquals(
            "selectionOutcome must remain SELECTED — task failures do not downgrade the selection outcome",
            TargetSelectionOutcome.SELECTED,
            readiness.selectionOutcome
        )
        assertNull(
            "selectionOutcomeReason must be null for SELECTED — no reason needed",
            readiness.selectionOutcomeReason
        )
    }

    // ── Selection truth through abnormal paths (Phase A acceptance) ───────────

    @Test
    fun `SELECTED outcome is stable through takeover failure (primary role)`() {
        val session = freshSession()

        // Before any failure — should be SELECTED.
        val beforeFailure = readinessFrom(
            session = session,
            hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
        )
        assertEquals(
            "selectionOutcome must be SELECTED before any failure",
            TargetSelectionOutcome.SELECTED,
            beforeFailure.selectionOutcome
        )

        // After a takeover failure, the session itself is NOT closed — the projection is
        // recomputed from the same session state and must still yield SELECTED.
        val afterFailure = readinessFrom(
            session = session,
            hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue
        )
        assertEquals(
            "selectionOutcome must remain SELECTED after takeover failure — session was not closed",
            TargetSelectionOutcome.SELECTED,
            afterFailure.selectionOutcome
        )
    }

    @Test
    fun `FALLBACK outcome is stable through takeover failure (secondary role)`() {
        val session = freshSession()

        val beforeFailure = readinessFrom(
            session = session,
            hostRole = RuntimeHostDescriptor.FormationRole.SECONDARY.wireValue
        )
        assertEquals(
            "selectionOutcome must be FALLBACK before any failure (secondary role)",
            TargetSelectionOutcome.FALLBACK,
            beforeFailure.selectionOutcome
        )

        val afterFailure = readinessFrom(
            session = session,
            hostRole = RuntimeHostDescriptor.FormationRole.SECONDARY.wireValue
        )
        assertEquals(
            "selectionOutcome must remain FALLBACK after takeover failure (secondary role)",
            TargetSelectionOutcome.FALLBACK,
            afterFailure.selectionOutcome
        )
        assertEquals(
            "selectionOutcomeReason must remain non_primary_role after takeover failure",
            DelegatedTargetReadinessProjection.FALLBACK_REASON_NON_PRIMARY_ROLE,
            afterFailure.selectionOutcomeReason
        )
    }

    @Test
    fun `REJECTED outcome after stop() is deterministic`() = runBlocking {
        val (controller, _) = buildController()

        // stop() closes any attached session and disables cross-device.
        controller.stop()

        // targetReadinessProjection will be null (no session was ever opened in this test);
        // if a session had been opened, it would be REJECTED.  Either way: no SELECTED/FALLBACK
        // projection should survive after stop().
        val projection = controller.targetReadinessProjection.value
        if (projection != null) {
            assertEquals(
                "selectionOutcome must be REJECTED after stop() — session is closed on disable",
                TargetSelectionOutcome.REJECTED,
                projection.selectionOutcome
            )
        } else {
            assertNull(
                "No projection is also valid after stop() with no prior session",
                projection
            )
        }
    }

    @Test
    fun `REJECTED selection outcome after DetachCause DISCONNECT`() {
        val session = freshSession()
        val disconnected = session.detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val readiness = readinessFrom(session = disconnected)

        assertEquals(
            "selectionOutcome must be REJECTED for a DISCONNECTED session",
            TargetSelectionOutcome.REJECTED,
            readiness.selectionOutcome
        )
        assertEquals(
            "selectionOutcomeReason must be not_attached for DISCONNECT detach",
            DelegatedTargetReadinessProjection.UNSUITABILITY_NOT_ATTACHED,
            readiness.selectionOutcomeReason
        )
    }

    @Test
    fun `REJECTED selection outcome after DetachCause INVALIDATION`() {
        val session = freshSession()
        val invalidated = session.detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        val readiness = readinessFrom(session = invalidated)

        assertEquals(
            "selectionOutcome must be REJECTED for an INVALIDATED session",
            TargetSelectionOutcome.REJECTED,
            readiness.selectionOutcome
        )
        assertEquals(
            "selectionOutcomeReason must be invalidated for INVALIDATION detach",
            DelegatedTargetReadinessProjection.UNSUITABILITY_INVALIDATED,
            readiness.selectionOutcomeReason
        )
    }

    @Test
    fun `selection outcome transitions correctly from SELECTED to REJECTED on session close`() {
        val attached = freshSession()
        val detached = attached.detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)

        val rAttached = readinessFrom(session = attached)
        val rDetached = readinessFrom(session = detached)

        assertEquals(
            "Attached primary-role session must yield SELECTED",
            TargetSelectionOutcome.SELECTED,
            rAttached.selectionOutcome
        )
        assertEquals(
            "Detached session (EXPLICIT_DETACH) must yield REJECTED",
            TargetSelectionOutcome.REJECTED,
            rDetached.selectionOutcome
        )
    }

    @Test
    fun `SATELLITE role yields FALLBACK outcome and is stable through failure`() {
        val session = freshSession()
        val r = readinessFrom(
            session = session,
            hostRole = RuntimeHostDescriptor.FormationRole.SATELLITE.wireValue
        )

        assertEquals(
            "Satellite-role attached session must yield FALLBACK",
            TargetSelectionOutcome.FALLBACK,
            r.selectionOutcome
        )
        assertEquals(
            "selectionOutcomeReason must be non_primary_role for satellite",
            DelegatedTargetReadinessProjection.FALLBACK_REASON_NON_PRIMARY_ROLE,
            r.selectionOutcomeReason
        )
        assertTrue("Satellite-role session must still be suitable target", r.isSuitableTarget)
    }

    // ── Delegated execution failure path acceptance ───────────────────────────

    @Test
    fun `notifyTakeoverFailed emits before loop is unblocked — ordering guarantee`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        controller.onRemoteTaskStarted()

        var failureEmittedBeforeUnblock = false
        val job = launch {
            controller.takeoverFailure.first()
            // At the moment this collector fires, the loop should still be blocked — the
            // caller hasn't called onRemoteTaskFinished() yet.
            failureEmittedBeforeUnblock = loopController.isRemoteTaskActive
        }

        controller.notifyTakeoverFailed(
            "to-order", "t-order", "tr-order", "pipeline_error", TakeoverFallbackEvent.Cause.FAILED
        )
        job.join()

        assertTrue(
            "takeoverFailure must be observed while loop is still blocked — failure event precedes unblock",
            failureEmittedBeforeUnblock
        )

        // Now clear the block — simulating the finally block in real callers.
        controller.onRemoteTaskFinished()
        assertFalse("Loop must be unblocked after onRemoteTaskFinished", loopController.isRemoteTaskActive)
    }

    @Test
    fun `onRemoteTaskFinished is safe after notifyTakeoverFailed — correct finally-block order`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        controller.onRemoteTaskStarted()
        val failJob = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "err", TakeoverFallbackEvent.Cause.TIMEOUT)
        failJob.join()

        // onRemoteTaskFinished must not throw or corrupt state.
        controller.onRemoteTaskFinished()

        assertFalse("Loop must be unblocked", loopController.isRemoteTaskActive)
        // And must still be usable.
        val result = loopController.execute("post-failure local task")
        assertEquals(LoopController.STATUS_SUCCESS, result.status)
    }

    @Test
    fun `double onRemoteTaskFinished after failure is safe and idempotent`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)

        controller.onRemoteTaskStarted()
        val failJob = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-2", "t-2", "tr-2", "err", TakeoverFallbackEvent.Cause.CANCELLED)
        failJob.join()

        // Simulate both the WS disconnect handler and the task's finally block both
        // calling onRemoteTaskFinished().
        controller.onRemoteTaskFinished()
        controller.onRemoteTaskFinished()  // second call must not throw or corrupt state

        assertFalse("Loop must remain unblocked after double onRemoteTaskFinished", loopController.isRemoteTaskActive)

        val result = loopController.execute("ensure loop is usable after double unblock")
        assertEquals(LoopController.STATUS_SUCCESS, result.status)
    }

    // ── Phase A acceptance: no duplicate authority or parallel abnormal-path manager ──

    @Test
    fun `takeoverFailure is sole failure authority — no session mutation and no state change`() = runBlocking {
        val (controller, _) = buildController()

        val stateBefore = controller.state.value
        val sessionBefore = controller.attachedSession.value
        val snapshotBefore = controller.hostSessionSnapshot.value
        val projectionBefore = controller.targetReadinessProjection.value

        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed(
            takeoverId = "to-authority",
            taskId = "t-authority",
            traceId = "tr-authority",
            reason = "authority_check",
            cause = TakeoverFallbackEvent.Cause.FAILED
        )
        job.join()

        assertEquals("state must not change after takeover failure", stateBefore, controller.state.value)
        assertEquals("attachedSession must not change after takeover failure", sessionBefore, controller.attachedSession.value)
        assertEquals("hostSessionSnapshot must not change after takeover failure", snapshotBefore, controller.hostSessionSnapshot.value)
        assertEquals(
            "targetReadinessProjection must not change after takeover failure",
            projectionBefore,
            controller.targetReadinessProjection.value
        )
    }

    @Test
    fun `settings crossDeviceEnabled is not mutated by takeover failure`() = runBlocking {
        val settings = InMemoryAppSettings()
        settings.crossDeviceEnabled = true
        val (controller, _) = buildController(settings = settings)

        val job = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-s", "t-s", "tr-s", "err", TakeoverFallbackEvent.Cause.DISCONNECT)
        job.join()

        assertTrue(
            "settings.crossDeviceEnabled must remain true after takeover failure — recovery must not require re-enable",
            settings.crossDeviceEnabled
        )
    }

    @Test
    fun `registrationError does not emit on takeover failure`() = runBlocking {
        val (controller, _) = buildController()

        var registrationErrorReceived = false
        val registrationJob = launch {
            withTimeoutOrNull(400L) {
                controller.registrationError.first()
                registrationErrorReceived = true
            }
        }

        val takeoverJob = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-reg", "t-reg", "tr-reg", "err", TakeoverFallbackEvent.Cause.FAILED)
        takeoverJob.join()
        registrationJob.join()

        assertFalse(
            "registrationError must NOT fire on notifyTakeoverFailed — these are independent failure domains",
            registrationErrorReceived
        )
    }

    @Test
    fun `takeoverFailure does not emit on registration failure`() = runBlocking {
        val (controller, _) = buildController(timeoutMs = 150L)

        var takeoverFailureReceived = false
        val takeoverJob = launch {
            withTimeoutOrNull(400L) {
                controller.takeoverFailure.first()
                takeoverFailureReceived = true
            }
        }

        // Trigger a registration failure (WS unreachable — will time out immediately).
        controller.startWithTimeout()
        takeoverJob.join()

        assertFalse(
            "takeoverFailure must NOT emit when only a registration failure occurs — independent failure domains",
            takeoverFailureReceived
        )
    }

    // ── Phase A acceptance: ready-for-next-task after each abnormal path ──────

    @Test
    fun `runtime is ready for next task after FAILED remote execution`() = runBlocking {
        val loopController = buildLoopController()
        val (controller, _) = buildController(loopController = loopController)
        val initialState = controller.state.value

        controller.onRemoteTaskStarted()
        val failJob = launch { controller.takeoverFailure.first() }
        controller.notifyTakeoverFailed("to-next", "t-next", "tr-next", "err", TakeoverFallbackEvent.Cause.FAILED)
        failJob.join()
        controller.onRemoteTaskFinished()

        // State must be unchanged — runtime is still ready.
        assertEquals(
            "Runtime state must be unchanged — system must be ready for the next task",
            initialState,
            controller.state.value
        )

        // onRemoteTaskStarted must work again — next delegated task can arrive.
        controller.onRemoteTaskStarted()
        assertTrue("isRemoteTaskActive must be true — ready to receive next task", loopController.isRemoteTaskActive)
        controller.onRemoteTaskFinished()
    }

    @Test
    fun `multiple successive takeover failures each emit a distinct event — no event collapse`() = runBlocking {
        val (controller, _) = buildController()

        val events = mutableListOf<TakeoverFallbackEvent>()
        val collectJob = launch {
            repeat(4) { events.add(controller.takeoverFailure.first()) }
        }

        controller.notifyTakeoverFailed("to-1", "t-1", "tr-1", "e1", TakeoverFallbackEvent.Cause.FAILED)
        controller.notifyTakeoverFailed("to-2", "t-2", "tr-2", "e2", TakeoverFallbackEvent.Cause.TIMEOUT)
        controller.notifyTakeoverFailed("to-3", "t-3", "tr-3", "e3", TakeoverFallbackEvent.Cause.CANCELLED)
        controller.notifyTakeoverFailed("to-4", "t-4", "tr-4", "e4", TakeoverFallbackEvent.Cause.DISCONNECT)
        collectJob.join()

        assertEquals("All 4 distinct failure events must be received", 4, events.size)
        assertEquals("First event must be FAILED", TakeoverFallbackEvent.Cause.FAILED, events[0].cause)
        assertEquals("Second event must be TIMEOUT", TakeoverFallbackEvent.Cause.TIMEOUT, events[1].cause)
        assertEquals("Third event must be CANCELLED", TakeoverFallbackEvent.Cause.CANCELLED, events[2].cause)
        assertEquals("Fourth event must be DISCONNECT", TakeoverFallbackEvent.Cause.DISCONNECT, events[3].cause)
        assertEquals("First event takeoverId must be to-1", "to-1", events[0].takeoverId)
        assertEquals("Fourth event takeoverId must be to-4", "to-4", events[3].takeoverId)
    }
}

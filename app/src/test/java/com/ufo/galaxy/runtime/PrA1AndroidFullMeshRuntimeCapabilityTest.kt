package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.HybridExecutePayload
import com.ufo.galaxy.protocol.StepResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-A1 (Android) — Full mesh runtime capability regression suite.
 *
 * Proves that Android can truthfully advertise full mesh runtime readiness when real
 * prerequisites are satisfied.  Specifically validates:
 *
 *  1. [HybridParticipantCapability.HYBRID_EXECUTE_FULL] is [HybridParticipantCapability.SupportLevel.AVAILABLE].
 *  2. [HybridParticipantCapability.BARRIER_COORDINATION] is [HybridParticipantCapability.SupportLevel.AVAILABLE].
 *  3. [HybridExecuteFullCoordinator] is a real, runnable code path that produces [HybridExecutionResult].
 *  4. [BarrierCoordinationParticipant] is a real, runnable code path with state transitions.
 *  5. [AndroidMeshParticipationContract.evaluate] can return
 *     [AndroidMeshParticipationContract.ReadinessLevel.READY] with
 *     [AndroidMeshParticipationContract.ParticipationReport.fullMeshRuntimeExecutable] = `true`.
 *
 * ## Test matrix
 *
 * ### HybridParticipantCapability — capability promotion
 *  - HYBRID_EXECUTE_FULL is AVAILABLE
 *  - BARRIER_COORDINATION is AVAILABLE
 *  - both capabilities are in availableCapabilities()
 *  - neither capability is in deferredCapabilities()
 *
 * ### HybridExecuteFullCoordinator — rollout gate
 *  - crossDeviceAllowed=false returns BLOCKED without calling executor
 *  - BLOCKED result carries correct taskId and deviceId
 *  - BLOCKED result carries non-null error string
 *
 * ### HybridExecuteFullCoordinator — execution path
 *  - crossDeviceAllowed=true delegates to local executor
 *  - executor success produces LOCAL_SUCCESS status
 *  - executor error produces LOCAL_FAILURE status
 *  - deviceId is injected when pipeline returns blank device_id
 *  - deviceId is preserved when pipeline returns non-blank device_id
 *  - taskId is echoed from payload
 *  - latencyMs is non-negative
 *  - localStepCount reflects executor step count
 *  - LOCAL_SUCCESS wireStatus is "success"
 *  - LOCAL_FAILURE wireStatus is "error"
 *
 * ### HybridExecuteFullCoordinator — toHybridResultPayload
 *  - LOCAL_SUCCESS converts to HybridResultPayload with status="success"
 *  - LOCAL_FAILURE converts to HybridResultPayload with status="error"
 *  - task_id and correlation_id are set from result taskId
 *  - device_id is preserved
 *  - local_result is preserved
 *  - error is preserved
 *  - remote_result is always null (V2-side responsibility)
 *
 * ### BarrierCoordinationParticipant — initial state
 *  - initial currentState is NOT_APPLICABLE
 *  - initial currentBarrierSessionId is null
 *
 * ### BarrierCoordinationParticipant — enterBarrierWait
 *  - enterBarrierWait transitions from NOT_APPLICABLE to WAITING
 *  - enterBarrierWait returns true on first call
 *  - enterBarrierWait returns false when already WAITING (idempotent)
 *  - currentBarrierSessionId is set after enterBarrierWait
 *
 * ### BarrierCoordinationParticipant — acknowledgeBarrierRelease
 *  - acknowledgeBarrierRelease transitions from WAITING to RELEASED
 *  - acknowledgeBarrierRelease returns true from WAITING state
 *  - acknowledgeBarrierRelease returns false when not in WAITING state
 *  - acknowledgeBarrierRelease from NOT_APPLICABLE returns false
 *
 * ### BarrierCoordinationParticipant — handleBarrierTimeout
 *  - handleBarrierTimeout transitions from WAITING to TIMED_OUT
 *  - handleBarrierTimeout returns true from WAITING state
 *  - handleBarrierTimeout returns false when not in WAITING state
 *  - handleBarrierTimeout from NOT_APPLICABLE returns false
 *
 * ### BarrierCoordinationParticipant — resetBarrier
 *  - resetBarrier transitions from RELEASED to NOT_APPLICABLE
 *  - resetBarrier transitions from TIMED_OUT to NOT_APPLICABLE
 *  - resetBarrier from NOT_APPLICABLE is a no-op (no error)
 *  - currentBarrierSessionId is null after resetBarrier
 *
 * ### BarrierCoordinationParticipant — full lifecycle
 *  - WAITING → RELEASED → NOT_APPLICABLE lifecycle completes
 *  - WAITING → TIMED_OUT → NOT_APPLICABLE lifecycle completes
 *  - isActive is true when WAITING
 *  - isActive is true when RELEASED
 *  - isActive is false when NOT_APPLICABLE
 *  - isActive is false when TIMED_OUT
 *
 * ### BarrierCoordinationParticipant — toMetadataMap
 *  - toMetadataMap includes barrier state wire value
 *  - toMetadataMap includes barrier session id (or null)
 *
 * ### AndroidMeshParticipationContract — fullMeshRuntimeExecutable reachability
 *  - evaluate returns READY with fullMeshRuntimeExecutable=true when all prerequisites met
 *  - fullMeshRuntimeExecutable=true requires no deferred required capabilities
 *  - no deferred_capability:hybrid_execute_full reason emitted when HYBRID_EXECUTE_FULL is AVAILABLE
 *  - no deferred_capability:barrier_coordination reason emitted when BARRIER_COORDINATION is AVAILABLE
 *  - PARTIAL is still possible when only some mesh paths are available
 *  - DEFERRED is still possible when orchestration is not connected
 */
class PrA1AndroidFullMeshRuntimeCapabilityTest {

    // ── Capability promotion ──────────────────────────────────────────────────

    @Test
    fun `HYBRID_EXECUTE_FULL is AVAILABLE`() {
        assertEquals(
            HybridParticipantCapability.SupportLevel.AVAILABLE,
            HybridParticipantCapability.HYBRID_EXECUTE_FULL.supportLevel
        )
    }

    @Test
    fun `BARRIER_COORDINATION is AVAILABLE`() {
        assertEquals(
            HybridParticipantCapability.SupportLevel.AVAILABLE,
            HybridParticipantCapability.BARRIER_COORDINATION.supportLevel
        )
    }

    @Test
    fun `HYBRID_EXECUTE_FULL is in availableCapabilities`() {
        assertTrue(
            HybridParticipantCapability.availableCapabilities()
                .contains(HybridParticipantCapability.HYBRID_EXECUTE_FULL)
        )
    }

    @Test
    fun `BARRIER_COORDINATION is in availableCapabilities`() {
        assertTrue(
            HybridParticipantCapability.availableCapabilities()
                .contains(HybridParticipantCapability.BARRIER_COORDINATION)
        )
    }

    @Test
    fun `HYBRID_EXECUTE_FULL is NOT in deferredCapabilities`() {
        assertFalse(
            HybridParticipantCapability.deferredCapabilities()
                .contains(HybridParticipantCapability.HYBRID_EXECUTE_FULL)
        )
    }

    @Test
    fun `BARRIER_COORDINATION is NOT in deferredCapabilities`() {
        assertFalse(
            HybridParticipantCapability.deferredCapabilities()
                .contains(HybridParticipantCapability.BARRIER_COORDINATION)
        )
    }

    // ── HybridExecuteFullCoordinator — rollout gate ───────────────────────────

    @Test
    fun `crossDeviceAllowed=false returns BLOCKED without calling executor`() = runBlocking {
        var executorCalled = false
        val coordinator = makeCoordinator(executor = HybridExecuteFullCoordinator.LocalStepExecutor {
            executorCalled = true
            makeGoalResult()
        })
        val result = coordinator.acceptHybridExecute(makeHybridPayload(), rolloutDisabled())
        assertEquals(HybridExecutionResult.Status.BLOCKED, result.status)
        assertFalse("Executor must not be called when cross_device is disabled", executorCalled)
    }

    @Test
    fun `BLOCKED result carries correct taskId`() = runBlocking {
        val coordinator = makeCoordinator()
        val result = coordinator.acceptHybridExecute(
            makeHybridPayload(taskId = "blocked-task-1"),
            rolloutDisabled()
        )
        assertEquals("blocked-task-1", result.taskId)
    }

    @Test
    fun `BLOCKED result carries coordinator deviceId`() = runBlocking {
        val coordinator = makeCoordinator(deviceId = "device-blocked-1")
        val result = coordinator.acceptHybridExecute(makeHybridPayload(), rolloutDisabled())
        assertEquals("device-blocked-1", result.deviceId)
    }

    @Test
    fun `BLOCKED result carries non-null error string`() = runBlocking {
        val coordinator = makeCoordinator()
        val result = coordinator.acceptHybridExecute(makeHybridPayload(), rolloutDisabled())
        assertNotNull(result.error)
    }

    // ── HybridExecuteFullCoordinator — execution path ─────────────────────────

    @Test
    fun `crossDeviceAllowed=true delegates to local executor`() = runBlocking {
        var executorCalled = false
        val coordinator = makeCoordinator(executor = HybridExecuteFullCoordinator.LocalStepExecutor {
            executorCalled = true
            makeGoalResult()
        })
        coordinator.acceptHybridExecute(makeHybridPayload(), rolloutEnabled())
        assertTrue("Executor must be called when cross_device is enabled", executorCalled)
    }

    @Test
    fun `executor success produces LOCAL_SUCCESS status`() = runBlocking {
        val coordinator = makeCoordinator(executor = HybridExecuteFullCoordinator.LocalStepExecutor {
            makeGoalResult(status = "success")
        })
        val result = coordinator.acceptHybridExecute(makeHybridPayload(), rolloutEnabled())
        assertEquals(HybridExecutionResult.Status.LOCAL_SUCCESS, result.status)
    }

    @Test
    fun `executor error produces LOCAL_FAILURE status`() = runBlocking {
        val coordinator = makeCoordinator(executor = HybridExecuteFullCoordinator.LocalStepExecutor {
            makeGoalResult(status = "error", error = "pipeline failed")
        })
        val result = coordinator.acceptHybridExecute(makeHybridPayload(), rolloutEnabled())
        assertEquals(HybridExecutionResult.Status.LOCAL_FAILURE, result.status)
    }

    @Test
    fun `deviceId injected when executor returns blank device_id`() = runBlocking {
        val coordinator = makeCoordinator(
            deviceId = "injected-device",
            executor = HybridExecuteFullCoordinator.LocalStepExecutor { makeGoalResult(deviceId = "") }
        )
        val result = coordinator.acceptHybridExecute(makeHybridPayload(), rolloutEnabled())
        assertEquals("injected-device", result.deviceId)
    }

    @Test
    fun `deviceId preserved when executor returns non-blank device_id`() = runBlocking {
        val coordinator = makeCoordinator(
            deviceId = "coordinator-device",
            executor = HybridExecuteFullCoordinator.LocalStepExecutor {
                makeGoalResult(deviceId = "executor-device")
            }
        )
        val result = coordinator.acceptHybridExecute(makeHybridPayload(), rolloutEnabled())
        assertEquals("executor-device", result.deviceId)
    }

    @Test
    fun `taskId is echoed from payload`() = runBlocking {
        val coordinator = makeCoordinator()
        val result = coordinator.acceptHybridExecute(
            makeHybridPayload(taskId = "echo-task-id"),
            rolloutEnabled()
        )
        assertEquals("echo-task-id", result.taskId)
    }

    @Test
    fun `latencyMs is non-negative`() = runBlocking {
        val coordinator = makeCoordinator()
        val result = coordinator.acceptHybridExecute(makeHybridPayload(), rolloutEnabled())
        assertTrue("latencyMs must be non-negative", result.latencyMs >= 0L)
    }

    @Test
    fun `localStepCount reflects executor step count`() = runBlocking {
        val coordinator = makeCoordinator(executor = HybridExecuteFullCoordinator.LocalStepExecutor {
            makeGoalResult(stepCount = 3)
        })
        val result = coordinator.acceptHybridExecute(makeHybridPayload(), rolloutEnabled())
        assertEquals(3, result.localStepCount)
    }

    @Test
    fun `LOCAL_SUCCESS wireStatus is success`() {
        assertEquals("success", HybridExecutionResult.Status.LOCAL_SUCCESS.wireStatus)
    }

    @Test
    fun `LOCAL_FAILURE wireStatus is error`() {
        assertEquals("error", HybridExecutionResult.Status.LOCAL_FAILURE.wireStatus)
    }

    // ── HybridExecuteFullCoordinator — toHybridResultPayload ─────────────────

    @Test
    fun `LOCAL_SUCCESS converts to HybridResultPayload with status success`() = runBlocking {
        val coordinator = makeCoordinator(executor = HybridExecuteFullCoordinator.LocalStepExecutor {
            makeGoalResult(status = "success", result = "done")
        })
        val execResult = coordinator.acceptHybridExecute(makeHybridPayload(taskId = "t1"), rolloutEnabled())
        val payload = coordinator.toHybridResultPayload(execResult)
        assertEquals("success", payload.status)
        assertEquals("t1", payload.task_id)
        assertEquals("t1", payload.correlation_id)
        assertEquals("done", payload.local_result)
        assertNull("remote_result must be null (V2 responsibility)", payload.remote_result)
    }

    @Test
    fun `LOCAL_FAILURE converts to HybridResultPayload with status error`() = runBlocking {
        val coordinator = makeCoordinator(executor = HybridExecuteFullCoordinator.LocalStepExecutor {
            makeGoalResult(status = "error", error = "executor failed")
        })
        val execResult = coordinator.acceptHybridExecute(makeHybridPayload(), rolloutEnabled())
        val payload = coordinator.toHybridResultPayload(execResult)
        assertEquals("error", payload.status)
        assertEquals("executor failed", payload.error)
    }

    @Test
    fun `toHybridResultPayload remote_result is always null`() = runBlocking {
        val coordinator = makeCoordinator()
        val execResult = coordinator.acceptHybridExecute(makeHybridPayload(), rolloutEnabled())
        val payload = coordinator.toHybridResultPayload(execResult)
        assertNull(payload.remote_result)
    }

    // ── BarrierCoordinationParticipant — initial state ────────────────────────

    @Test
    fun `initial currentState is NOT_APPLICABLE`() {
        val participant = BarrierCoordinationParticipant()
        assertEquals(BarrierParticipationState.NOT_APPLICABLE, participant.currentState)
    }

    @Test
    fun `initial currentBarrierSessionId is null`() {
        val participant = BarrierCoordinationParticipant()
        assertNull(participant.currentBarrierSessionId)
    }

    // ── BarrierCoordinationParticipant — enterBarrierWait ────────────────────

    @Test
    fun `enterBarrierWait transitions from NOT_APPLICABLE to WAITING`() {
        val participant = BarrierCoordinationParticipant()
        participant.enterBarrierWait("session-1")
        assertEquals(BarrierParticipationState.WAITING, participant.currentState)
    }

    @Test
    fun `enterBarrierWait returns true on first call`() {
        val participant = BarrierCoordinationParticipant()
        assertTrue(participant.enterBarrierWait("session-1"))
    }

    @Test
    fun `enterBarrierWait is idempotent when already WAITING`() {
        val participant = BarrierCoordinationParticipant()
        participant.enterBarrierWait("session-1")
        assertFalse("Second enterBarrierWait must return false when already WAITING",
            participant.enterBarrierWait("session-1"))
        assertEquals(BarrierParticipationState.WAITING, participant.currentState)
    }

    @Test
    fun `currentBarrierSessionId is set after enterBarrierWait`() {
        val participant = BarrierCoordinationParticipant()
        participant.enterBarrierWait("session-barrier-42")
        assertEquals("session-barrier-42", participant.currentBarrierSessionId)
    }

    // ── BarrierCoordinationParticipant — acknowledgeBarrierRelease ────────────

    @Test
    fun `acknowledgeBarrierRelease transitions from WAITING to RELEASED`() {
        val participant = BarrierCoordinationParticipant()
        participant.enterBarrierWait("session-1")
        participant.acknowledgeBarrierRelease("session-1")
        assertEquals(BarrierParticipationState.RELEASED, participant.currentState)
    }

    @Test
    fun `acknowledgeBarrierRelease returns true from WAITING state`() {
        val participant = BarrierCoordinationParticipant()
        participant.enterBarrierWait("session-1")
        assertTrue(participant.acknowledgeBarrierRelease("session-1"))
    }

    @Test
    fun `acknowledgeBarrierRelease returns false when not in WAITING state`() {
        val participant = BarrierCoordinationParticipant()
        assertFalse("acknowledgeBarrierRelease from NOT_APPLICABLE must return false",
            participant.acknowledgeBarrierRelease("session-1"))
    }

    @Test
    fun `acknowledgeBarrierRelease from NOT_APPLICABLE returns false`() {
        val participant = BarrierCoordinationParticipant()
        assertFalse(participant.acknowledgeBarrierRelease("session-1"))
        assertEquals(BarrierParticipationState.NOT_APPLICABLE, participant.currentState)
    }

    // ── BarrierCoordinationParticipant — handleBarrierTimeout ─────────────────

    @Test
    fun `handleBarrierTimeout transitions from WAITING to TIMED_OUT`() {
        val participant = BarrierCoordinationParticipant()
        participant.enterBarrierWait("session-1")
        participant.handleBarrierTimeout()
        assertEquals(BarrierParticipationState.TIMED_OUT, participant.currentState)
    }

    @Test
    fun `handleBarrierTimeout returns true from WAITING state`() {
        val participant = BarrierCoordinationParticipant()
        participant.enterBarrierWait("session-1")
        assertTrue(participant.handleBarrierTimeout())
    }

    @Test
    fun `handleBarrierTimeout returns false when not in WAITING state`() {
        val participant = BarrierCoordinationParticipant()
        assertFalse("handleBarrierTimeout from NOT_APPLICABLE must return false",
            participant.handleBarrierTimeout())
    }

    @Test
    fun `handleBarrierTimeout from NOT_APPLICABLE returns false`() {
        val participant = BarrierCoordinationParticipant()
        assertFalse(participant.handleBarrierTimeout())
        assertEquals(BarrierParticipationState.NOT_APPLICABLE, participant.currentState)
    }

    // ── BarrierCoordinationParticipant — resetBarrier ─────────────────────────

    @Test
    fun `resetBarrier transitions from RELEASED to NOT_APPLICABLE`() {
        val participant = BarrierCoordinationParticipant()
        participant.enterBarrierWait("session-1")
        participant.acknowledgeBarrierRelease("session-1")
        participant.resetBarrier("session-1")
        assertEquals(BarrierParticipationState.NOT_APPLICABLE, participant.currentState)
    }

    @Test
    fun `resetBarrier transitions from TIMED_OUT to NOT_APPLICABLE`() {
        val participant = BarrierCoordinationParticipant()
        participant.enterBarrierWait("session-1")
        participant.handleBarrierTimeout()
        participant.resetBarrier("session-1")
        assertEquals(BarrierParticipationState.NOT_APPLICABLE, participant.currentState)
    }

    @Test
    fun `resetBarrier from NOT_APPLICABLE does not throw`() {
        val participant = BarrierCoordinationParticipant()
        participant.resetBarrier("session-none")
        assertEquals(BarrierParticipationState.NOT_APPLICABLE, participant.currentState)
    }

    @Test
    fun `currentBarrierSessionId is null after resetBarrier`() {
        val participant = BarrierCoordinationParticipant()
        participant.enterBarrierWait("session-1")
        participant.resetBarrier("session-1")
        assertNull(participant.currentBarrierSessionId)
    }

    // ── BarrierCoordinationParticipant — full lifecycle ───────────────────────

    @Test
    fun `WAITING to RELEASED to NOT_APPLICABLE lifecycle completes`() {
        val participant = BarrierCoordinationParticipant()
        participant.enterBarrierWait("session-lifecycle-1")
        assertEquals(BarrierParticipationState.WAITING, participant.currentState)
        participant.acknowledgeBarrierRelease("session-lifecycle-1")
        assertEquals(BarrierParticipationState.RELEASED, participant.currentState)
        participant.resetBarrier("session-lifecycle-1")
        assertEquals(BarrierParticipationState.NOT_APPLICABLE, participant.currentState)
        assertNull(participant.currentBarrierSessionId)
    }

    @Test
    fun `WAITING to TIMED_OUT to NOT_APPLICABLE lifecycle completes`() {
        val participant = BarrierCoordinationParticipant()
        participant.enterBarrierWait("session-timeout-1")
        assertEquals(BarrierParticipationState.WAITING, participant.currentState)
        participant.handleBarrierTimeout()
        assertEquals(BarrierParticipationState.TIMED_OUT, participant.currentState)
        participant.resetBarrier("session-timeout-1")
        assertEquals(BarrierParticipationState.NOT_APPLICABLE, participant.currentState)
    }

    @Test
    fun `isActive is true when WAITING`() {
        assertTrue(BarrierCoordinationParticipant.isActive(BarrierParticipationState.WAITING))
    }

    @Test
    fun `isActive is true when RELEASED`() {
        assertTrue(BarrierCoordinationParticipant.isActive(BarrierParticipationState.RELEASED))
    }

    @Test
    fun `isActive is false when NOT_APPLICABLE`() {
        assertFalse(BarrierCoordinationParticipant.isActive(BarrierParticipationState.NOT_APPLICABLE))
    }

    @Test
    fun `isActive is false when TIMED_OUT`() {
        assertFalse(BarrierCoordinationParticipant.isActive(BarrierParticipationState.TIMED_OUT))
    }

    // ── BarrierCoordinationParticipant — toMetadataMap ────────────────────────

    @Test
    fun `toMetadataMap includes barrier state wire value`() {
        val participant = BarrierCoordinationParticipant()
        val map = participant.toMetadataMap()
        assertEquals(
            BarrierParticipationState.NOT_APPLICABLE.wireValue,
            map[BarrierCoordinationParticipant.KEY_BARRIER_STATE]
        )
    }

    @Test
    fun `toMetadataMap includes barrier session id after enterBarrierWait`() {
        val participant = BarrierCoordinationParticipant()
        participant.enterBarrierWait("session-map-test")
        val map = participant.toMetadataMap()
        assertEquals("session-map-test", map[BarrierCoordinationParticipant.KEY_BARRIER_SESSION_ID])
    }

    @Test
    fun `toMetadataMap barrier session id is null when NOT_APPLICABLE`() {
        val participant = BarrierCoordinationParticipant()
        val map = participant.toMetadataMap()
        assertNull(map[BarrierCoordinationParticipant.KEY_BARRIER_SESSION_ID])
    }

    // ── AndroidMeshParticipationContract — fullMeshRuntimeExecutable reachability

    @Test
    fun `evaluate returns READY with fullMeshRuntimeExecutable=true when all prerequisites met`() {
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = createHealthyOrchestrationRecord(),
            rollout = rolloutFull()
        )
        assertEquals(AndroidMeshParticipationContract.ReadinessLevel.READY, report.readinessLevel)
        assertTrue(
            "fullMeshRuntimeExecutable must be true when all capabilities are AVAILABLE " +
                "and rollout gates are open",
            report.fullMeshRuntimeExecutable
        )
    }

    @Test
    fun `fullMeshRuntimeExecutable requires no deferred required capabilities`() {
        // Both HYBRID_EXECUTE_FULL and BARRIER_COORDINATION are now AVAILABLE.
        // REQUIRED_FULL_MESH_CAPABILITIES should produce empty deferredRequiredCapabilities.
        val deferred = setOf(
            HybridParticipantCapability.HYBRID_EXECUTE_FULL,
            HybridParticipantCapability.BARRIER_COORDINATION
        ).filter { it.supportLevel != HybridParticipantCapability.SupportLevel.AVAILABLE }
        assertTrue(
            "deferredRequiredCapabilities must be empty when both HYBRID_EXECUTE_FULL and " +
                "BARRIER_COORDINATION are AVAILABLE; was: $deferred",
            deferred.isEmpty()
        )
    }

    @Test
    fun `no deferred_capability hybrid_execute_full reason emitted when HYBRID_EXECUTE_FULL is AVAILABLE`() {
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = createHealthyOrchestrationRecord(),
            rollout = rolloutFull()
        )
        assertFalse(
            "deferred_capability:hybrid_execute_full must not be in constrainedReasons when AVAILABLE",
            report.constrainedReasons.contains(
                "${AndroidMeshParticipationContract.REASON_DEFERRED_CAPABILITY_PREFIX}:hybrid_execute_full"
            )
        )
    }

    @Test
    fun `no deferred_capability barrier_coordination reason emitted when BARRIER_COORDINATION is AVAILABLE`() {
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = createHealthyOrchestrationRecord(),
            rollout = rolloutFull()
        )
        assertFalse(
            "deferred_capability:barrier_coordination must not be in constrainedReasons when AVAILABLE",
            report.constrainedReasons.contains(
                "${AndroidMeshParticipationContract.REASON_DEFERRED_CAPABILITY_PREFIX}:barrier_coordination"
            )
        )
    }

    @Test
    fun `PARTIAL readiness still possible when only mesh subtask is executable`() {
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = createHealthyOrchestrationRecord(),
            rollout = RolloutControlSnapshot(
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = false,
                fallbackToLocalAllowed = true,
                goalExecutionAllowed = true
            )
        )
        assertEquals(AndroidMeshParticipationContract.ReadinessLevel.PARTIAL, report.readinessLevel)
        assertFalse(report.fullMeshRuntimeExecutable)
    }

    @Test
    fun `DEFERRED readiness still possible when orchestration is disconnected`() {
        val disconnected = MultiDeviceParticipantOrchestrationState.from(
            healthState = ParticipantHealthState.UNKNOWN,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
        )
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = disconnected,
            rollout = rolloutFull()
        )
        assertEquals(AndroidMeshParticipationContract.ReadinessLevel.DEFERRED, report.readinessLevel)
        assertFalse(report.fullMeshRuntimeExecutable)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeCoordinator(
        executor: HybridExecuteFullCoordinator.LocalStepExecutor =
            HybridExecuteFullCoordinator.LocalStepExecutor { makeGoalResult() },
        deviceId: String = "android-test-device"
    ) = HybridExecuteFullCoordinator(localExecutor = executor, deviceId = deviceId)

    private fun makeHybridPayload(
        taskId: String = "hybrid-task-pr-a1",
        goal: String = "test goal",
        localSteps: List<String> = listOf("tap_button", "verify_result"),
        remoteSteps: List<String> = listOf("remote_analysis"),
        timeoutMs: Long = 5000L
    ) = HybridExecutePayload(
        task_id     = taskId,
        goal        = goal,
        local_steps = localSteps,
        remote_steps = remoteSteps,
        timeout_ms  = timeoutMs
    )

    private fun makeGoalResult(
        taskId: String = "hybrid-task-pr-a1",
        status: String = "success",
        deviceId: String = "android-test-device",
        stepCount: Int = 2,
        result: String? = "completed",
        error: String? = null
    ) = GoalResultPayload(
        task_id   = taskId,
        status    = status,
        device_id = deviceId,
        steps     = List(stepCount) {
            StepResult(step_id = "${it + 1}", action = "tap", success = (status == "success"))
        },
        latency_ms = 100L,
        result     = result,
        error      = error
    )

    private fun rolloutEnabled() = RolloutControlSnapshot(
        crossDeviceAllowed       = true,
        delegatedExecutionAllowed = true,
        fallbackToLocalAllowed   = true,
        goalExecutionAllowed     = true
    )

    private fun rolloutDisabled() = RolloutControlSnapshot(
        crossDeviceAllowed       = false,
        delegatedExecutionAllowed = true,
        fallbackToLocalAllowed   = true,
        goalExecutionAllowed     = false
    )

    private fun rolloutFull() = RolloutControlSnapshot(
        crossDeviceAllowed       = true,
        delegatedExecutionAllowed = true,
        fallbackToLocalAllowed   = true,
        goalExecutionAllowed     = true
    )

    private fun createHealthyOrchestrationRecord(): MultiDeviceParticipantOrchestrationState.StateRecord =
        MultiDeviceParticipantOrchestrationState.from(
            healthState        = ParticipantHealthState.HEALTHY,
            reconnectState     = ReconnectRecoveryState.IDLE,
            readinessState     = ParticipantReadinessState.READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
}

package com.ufo.galaxy.runtime

import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.session.AndroidSessionContribution
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-32 — Staged-mesh-compatible target execution compliance.
 *
 * Regression and acceptance test suite for all PR-32 additions:
 *
 *  1. [StagedMeshParticipationResult] data class — canonical result envelope for staged-mesh
 *     target execution with stable wire-key constants, [StagedMeshParticipationResult.ExecutionStatus]
 *     enum, [toMetadataMap], [toSessionContribution], and [fromGoalResult] factory.
 *
 *  2. [StagedMeshExecutionTarget] class — target-side execution compatibility shim that
 *     accepts staged-mesh subtask assignments, enforces the [RolloutControlSnapshot.crossDeviceAllowed]
 *     gate, delegates to the existing execution pipeline, and wraps the outcome in a
 *     [StagedMeshParticipationResult].
 *
 *  3. [AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK] — new kind discriminator for
 *     staged-mesh-participant contributions, with [AndroidSessionContribution.fromStagedMeshResult]
 *     factory.
 *
 *  4. [GalaxyLogger.TAG_STAGED_MESH] — new stable structured log tag for staged-mesh events.
 *
 * ## Test matrix
 *
 * ### StagedMeshParticipationResult — wire key constants
 *  - KEY_MESH_ID value is "staged_mesh_id"
 *  - KEY_SUBTASK_ID value is "staged_mesh_subtask_id"
 *  - KEY_TASK_ID value is "staged_mesh_task_id"
 *  - KEY_DEVICE_ID value is "staged_mesh_device_id"
 *  - KEY_EXECUTION_STATUS value is "staged_mesh_execution_status"
 *  - KEY_STEP_COUNT value is "staged_mesh_step_count"
 *  - KEY_LATENCY_MS value is "staged_mesh_latency_ms"
 *  - KEY_OUTPUT value is "staged_mesh_output"
 *  - KEY_ERROR value is "staged_mesh_error"
 *  - all key constants are distinct
 *
 * ### StagedMeshParticipationResult — ExecutionStatus
 *  - SUCCESS wireValue is "success"
 *  - FAILURE wireValue is "failure"
 *  - CANCELLED wireValue is "cancelled"
 *  - BLOCKED wireValue is "blocked"
 *  - all four status wire values are distinct
 *
 * ### StagedMeshParticipationResult — toMetadataMap
 *  - always-present keys are included
 *  - values reflect field values
 *  - output key absent when output is null
 *  - output key present when output is non-null
 *  - error key absent when error is null
 *  - error key present when error is non-null
 *
 * ### StagedMeshParticipationResult — toSessionContribution
 *  - SUCCESS → Kind.STAGED_MESH_SUBTASK
 *  - SUCCESS → STATUS_SUCCESS
 *  - FAILURE → Kind.FAILURE
 *  - FAILURE → STATUS_ERROR
 *  - CANCELLED → Kind.CANCELLATION
 *  - CANCELLED → STATUS_CANCELLED
 *  - BLOCKED → Kind.DISABLED
 *  - BLOCKED → STATUS_DISABLED
 *  - traceId carries meshId
 *  - groupId carries meshId
 *  - correlationId carries subtaskId
 *  - routeMode is ROUTE_CROSS_DEVICE
 *  - stepCount and latencyMs are echoed
 *  - deviceId is echoed
 *
 * ### StagedMeshParticipationResult — fromGoalResult factory
 *  - success status maps to SUCCESS
 *  - error status maps to FAILURE
 *  - timeout status maps to FAILURE
 *  - cancelled status maps to CANCELLED
 *  - disabled status maps to BLOCKED
 *  - meshId and subtaskId are preserved
 *  - taskId, stepCount, latencyMs are echoed from GoalResultPayload
 *  - output is echoed from GoalResultPayload.result
 *  - error is echoed from GoalResultPayload.error
 *
 * ### StagedMeshExecutionTarget — rollout gate
 *  - crossDeviceAllowed=false returns BLOCKED without calling executor
 *  - BLOCKED result carries correct meshId, subtaskId, and taskId
 *  - BLOCKED error string contains "cross_device_disabled"
 *
 * ### StagedMeshExecutionTarget — execution path
 *  - crossDeviceAllowed=true delegates to executor
 *  - result meshId and subtaskId are preserved
 *  - executor SUCCESS → SUCCESS result
 *  - executor error → FAILURE result
 *  - executor cancelled → CANCELLED result
 *  - executor returns empty device_id → deviceId is injected from constructor
 *  - executor returns non-empty device_id → deviceId is preserved from executor
 *
 * ### AndroidSessionContribution — STAGED_MESH_SUBTASK
 *  - Kind.STAGED_MESH_SUBTASK exists in Kind enum
 *  - STAGED_MESH_SUBTASK is distinct from FINAL_COMPLETION
 *  - STAGED_MESH_SUBTASK is distinct from FAILURE
 *  - STAGED_MESH_SUBTASK is distinct from CANCELLATION
 *  - STAGED_MESH_SUBTASK is distinct from DISABLED
 *  - STAGED_MESH_SUBTASK is distinct from TAKEOVER_CONTINUATION
 *  - fromStagedMeshResult delegates to toSessionContribution
 *  - fromStagedMeshResult preserves Kind.STAGED_MESH_SUBTASK for success
 *
 * ### GalaxyLogger — TAG_STAGED_MESH
 *  - TAG_STAGED_MESH value is "GALAXY:STAGED:MESH"
 *  - TAG_STAGED_MESH is distinct from TAG_TASK_EXEC
 *  - TAG_STAGED_MESH is distinct from TAG_ROLLOUT_CONTROL
 *  - TAG_STAGED_MESH is distinct from TAG_KILL_SWITCH
 *
 * ### Regression — existing contract stability
 *  - pre-PR32 Kind values are unaffected (stable count check)
 *  - toSessionContribution groupId is meshId (identity bridge stability)
 */
class Pr32StagedMeshTargetExecutionTest {

    // ── Test helpers ──────────────────────────────────────────────────────────

    private fun rolloutEnabled() = RolloutControlSnapshot(
        crossDeviceAllowed        = true,
        delegatedExecutionAllowed = true,
        fallbackToLocalAllowed    = true,
        goalExecutionAllowed      = true
    )

    private fun rolloutDisabled() = RolloutControlSnapshot(
        crossDeviceAllowed        = false,
        delegatedExecutionAllowed = true,
        fallbackToLocalAllowed    = true,
        goalExecutionAllowed      = false
    )

    private fun makePayload(
        taskId: String = "task-pr32",
        goal: String = "open the calculator"
    ) = GoalExecutionPayload(task_id = taskId, goal = goal)

    private fun makeGoalResult(
        taskId: String = "task-pr32",
        status: String = "success",
        deviceId: String = "dev-pr32",
        stepCount: Int = 3,
        latencyMs: Long = 800L,
        result: String? = "done",
        error: String? = null
    ) = GoalResultPayload(
        task_id    = taskId,
        status     = status,
        device_id  = deviceId,
        steps      = List(stepCount) {
            com.ufo.galaxy.protocol.StepResult(
                step_id = "${it + 1}",
                action  = "tap",
                success = (status == "success")
            )
        },
        latency_ms = latencyMs,
        result     = result,
        error      = error
    )

    private fun makeTarget(
        executor: StagedMeshExecutionTarget.SubtaskExecutor,
        deviceId: String = "android-pr32"
    ) = StagedMeshExecutionTarget(executor = executor, deviceId = deviceId)

    private fun makeSuccessResult(
        meshId: String = "mesh-pr32",
        subtaskId: String = "sub-pr32-0",
        taskId: String = "task-pr32",
        deviceId: String = "dev-pr32"
    ) = StagedMeshParticipationResult(
        meshId          = meshId,
        subtaskId       = subtaskId,
        taskId          = taskId,
        deviceId        = deviceId,
        executionStatus = StagedMeshParticipationResult.ExecutionStatus.SUCCESS,
        output          = "done",
        stepCount       = 3,
        latencyMs       = 800L
    )

    // ── StagedMeshParticipationResult — wire key constants ────────────────────

    @Test
    fun `KEY_MESH_ID wire value is stable`() {
        assertEquals("staged_mesh_id", StagedMeshParticipationResult.KEY_MESH_ID)
    }

    @Test
    fun `KEY_SUBTASK_ID wire value is stable`() {
        assertEquals("staged_mesh_subtask_id", StagedMeshParticipationResult.KEY_SUBTASK_ID)
    }

    @Test
    fun `KEY_TASK_ID wire value is stable`() {
        assertEquals("staged_mesh_task_id", StagedMeshParticipationResult.KEY_TASK_ID)
    }

    @Test
    fun `KEY_DEVICE_ID wire value is stable`() {
        assertEquals("staged_mesh_device_id", StagedMeshParticipationResult.KEY_DEVICE_ID)
    }

    @Test
    fun `KEY_EXECUTION_STATUS wire value is stable`() {
        assertEquals("staged_mesh_execution_status", StagedMeshParticipationResult.KEY_EXECUTION_STATUS)
    }

    @Test
    fun `KEY_STEP_COUNT wire value is stable`() {
        assertEquals("staged_mesh_step_count", StagedMeshParticipationResult.KEY_STEP_COUNT)
    }

    @Test
    fun `KEY_LATENCY_MS wire value is stable`() {
        assertEquals("staged_mesh_latency_ms", StagedMeshParticipationResult.KEY_LATENCY_MS)
    }

    @Test
    fun `KEY_OUTPUT wire value is stable`() {
        assertEquals("staged_mesh_output", StagedMeshParticipationResult.KEY_OUTPUT)
    }

    @Test
    fun `KEY_ERROR wire value is stable`() {
        assertEquals("staged_mesh_error", StagedMeshParticipationResult.KEY_ERROR)
    }

    @Test
    fun `all wire key constants are distinct`() {
        val keys = listOf(
            StagedMeshParticipationResult.KEY_MESH_ID,
            StagedMeshParticipationResult.KEY_SUBTASK_ID,
            StagedMeshParticipationResult.KEY_TASK_ID,
            StagedMeshParticipationResult.KEY_DEVICE_ID,
            StagedMeshParticipationResult.KEY_EXECUTION_STATUS,
            StagedMeshParticipationResult.KEY_STEP_COUNT,
            StagedMeshParticipationResult.KEY_LATENCY_MS,
            StagedMeshParticipationResult.KEY_OUTPUT,
            StagedMeshParticipationResult.KEY_ERROR
        )
        assertEquals("All KEY_* constants must be distinct", keys.distinct().size, keys.size)
    }

    // ── StagedMeshParticipationResult — ExecutionStatus wire values ───────────

    @Test
    fun `SUCCESS wireValue is success`() {
        assertEquals("success", StagedMeshParticipationResult.ExecutionStatus.SUCCESS.wireValue)
    }

    @Test
    fun `FAILURE wireValue is failure`() {
        assertEquals("failure", StagedMeshParticipationResult.ExecutionStatus.FAILURE.wireValue)
    }

    @Test
    fun `CANCELLED wireValue is cancelled`() {
        assertEquals("cancelled", StagedMeshParticipationResult.ExecutionStatus.CANCELLED.wireValue)
    }

    @Test
    fun `BLOCKED wireValue is blocked`() {
        assertEquals("blocked", StagedMeshParticipationResult.ExecutionStatus.BLOCKED.wireValue)
    }

    @Test
    fun `all ExecutionStatus wire values are distinct`() {
        val values = StagedMeshParticipationResult.ExecutionStatus.entries.map { it.wireValue }
        assertEquals("All ExecutionStatus wireValues must be distinct", values.distinct().size, values.size)
    }

    // ── StagedMeshParticipationResult — toMetadataMap ─────────────────────────

    @Test
    fun `toMetadataMap contains always-present keys`() {
        val map = makeSuccessResult().toMetadataMap()
        assertTrue(map.containsKey(StagedMeshParticipationResult.KEY_MESH_ID))
        assertTrue(map.containsKey(StagedMeshParticipationResult.KEY_SUBTASK_ID))
        assertTrue(map.containsKey(StagedMeshParticipationResult.KEY_TASK_ID))
        assertTrue(map.containsKey(StagedMeshParticipationResult.KEY_DEVICE_ID))
        assertTrue(map.containsKey(StagedMeshParticipationResult.KEY_EXECUTION_STATUS))
        assertTrue(map.containsKey(StagedMeshParticipationResult.KEY_STEP_COUNT))
        assertTrue(map.containsKey(StagedMeshParticipationResult.KEY_LATENCY_MS))
    }

    @Test
    fun `toMetadataMap values reflect field values`() {
        val result = makeSuccessResult(
            meshId    = "mesh-test-1",
            subtaskId = "sub-test-1",
            taskId    = "task-test-1",
            deviceId  = "dev-test-1"
        )
        val map = result.toMetadataMap()
        assertEquals("mesh-test-1", map[StagedMeshParticipationResult.KEY_MESH_ID])
        assertEquals("sub-test-1", map[StagedMeshParticipationResult.KEY_SUBTASK_ID])
        assertEquals("task-test-1", map[StagedMeshParticipationResult.KEY_TASK_ID])
        assertEquals("dev-test-1", map[StagedMeshParticipationResult.KEY_DEVICE_ID])
        assertEquals(
            StagedMeshParticipationResult.ExecutionStatus.SUCCESS.wireValue,
            map[StagedMeshParticipationResult.KEY_EXECUTION_STATUS]
        )
    }

    @Test
    fun `toMetadataMap omits output key when output is null`() {
        val result = makeSuccessResult().copy(output = null)
        assertFalse(result.toMetadataMap().containsKey(StagedMeshParticipationResult.KEY_OUTPUT))
    }

    @Test
    fun `toMetadataMap includes output key when output is non-null`() {
        val result = makeSuccessResult().copy(output = "result text")
        assertEquals("result text", result.toMetadataMap()[StagedMeshParticipationResult.KEY_OUTPUT])
    }

    @Test
    fun `toMetadataMap omits error key when error is null`() {
        val result = makeSuccessResult().copy(error = null)
        assertFalse(result.toMetadataMap().containsKey(StagedMeshParticipationResult.KEY_ERROR))
    }

    @Test
    fun `toMetadataMap includes error key when error is non-null`() {
        val result = StagedMeshParticipationResult(
            meshId          = "mesh-1",
            subtaskId       = "sub-1",
            taskId          = "task-1",
            deviceId        = "dev-1",
            executionStatus = StagedMeshParticipationResult.ExecutionStatus.FAILURE,
            error           = "execution failed"
        )
        assertEquals("execution failed", result.toMetadataMap()[StagedMeshParticipationResult.KEY_ERROR])
    }

    // ── StagedMeshParticipationResult — toSessionContribution ────────────────

    @Test
    fun `toSessionContribution SUCCESS maps to Kind STAGED_MESH_SUBTASK`() {
        val contrib = makeSuccessResult().toSessionContribution()
        assertEquals(AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK, contrib.kind)
    }

    @Test
    fun `toSessionContribution SUCCESS maps to STATUS_SUCCESS`() {
        val contrib = makeSuccessResult().toSessionContribution()
        assertEquals(AndroidSessionContribution.STATUS_SUCCESS, contrib.status)
    }

    @Test
    fun `toSessionContribution FAILURE maps to Kind FAILURE`() {
        val result = makeSuccessResult().copy(
            executionStatus = StagedMeshParticipationResult.ExecutionStatus.FAILURE
        )
        assertEquals(AndroidSessionContribution.Kind.FAILURE, result.toSessionContribution().kind)
    }

    @Test
    fun `toSessionContribution FAILURE maps to STATUS_ERROR`() {
        val result = makeSuccessResult().copy(
            executionStatus = StagedMeshParticipationResult.ExecutionStatus.FAILURE
        )
        assertEquals(AndroidSessionContribution.STATUS_ERROR, result.toSessionContribution().status)
    }

    @Test
    fun `toSessionContribution CANCELLED maps to Kind CANCELLATION`() {
        val result = makeSuccessResult().copy(
            executionStatus = StagedMeshParticipationResult.ExecutionStatus.CANCELLED
        )
        assertEquals(AndroidSessionContribution.Kind.CANCELLATION, result.toSessionContribution().kind)
    }

    @Test
    fun `toSessionContribution CANCELLED maps to STATUS_CANCELLED`() {
        val result = makeSuccessResult().copy(
            executionStatus = StagedMeshParticipationResult.ExecutionStatus.CANCELLED
        )
        assertEquals(AndroidSessionContribution.STATUS_CANCELLED, result.toSessionContribution().status)
    }

    @Test
    fun `toSessionContribution BLOCKED maps to Kind DISABLED`() {
        val result = makeSuccessResult().copy(
            executionStatus = StagedMeshParticipationResult.ExecutionStatus.BLOCKED
        )
        assertEquals(AndroidSessionContribution.Kind.DISABLED, result.toSessionContribution().kind)
    }

    @Test
    fun `toSessionContribution BLOCKED maps to STATUS_DISABLED`() {
        val result = makeSuccessResult().copy(
            executionStatus = StagedMeshParticipationResult.ExecutionStatus.BLOCKED
        )
        assertEquals(AndroidSessionContribution.STATUS_DISABLED, result.toSessionContribution().status)
    }

    @Test
    fun `toSessionContribution traceId carries meshId`() {
        val result = makeSuccessResult(meshId = "mesh-identity-test")
        assertEquals("mesh-identity-test", result.toSessionContribution().traceId)
    }

    @Test
    fun `toSessionContribution groupId carries meshId`() {
        val result = makeSuccessResult(meshId = "mesh-group-test")
        assertEquals("mesh-group-test", result.toSessionContribution().groupId)
    }

    @Test
    fun `toSessionContribution correlationId carries subtaskId`() {
        val result = makeSuccessResult(subtaskId = "sub-correlation-test")
        assertEquals("sub-correlation-test", result.toSessionContribution().correlationId)
    }

    @Test
    fun `toSessionContribution routeMode is ROUTE_CROSS_DEVICE`() {
        val contrib = makeSuccessResult().toSessionContribution()
        assertEquals(AndroidSessionContribution.ROUTE_CROSS_DEVICE, contrib.routeMode)
    }

    @Test
    fun `toSessionContribution stepCount is echoed`() {
        val result = makeSuccessResult().copy(stepCount = 5)
        assertEquals(5, result.toSessionContribution().stepCount)
    }

    @Test
    fun `toSessionContribution latencyMs is echoed`() {
        val result = makeSuccessResult().copy(latencyMs = 1234L)
        assertEquals(1234L, result.toSessionContribution().latencyMs)
    }

    @Test
    fun `toSessionContribution deviceId is echoed`() {
        val result = makeSuccessResult(deviceId = "device-echo-test")
        assertEquals("device-echo-test", result.toSessionContribution().deviceId)
    }

    // ── StagedMeshParticipationResult — fromGoalResult factory ────────────────

    @Test
    fun `fromGoalResult maps success status to SUCCESS`() {
        val r = StagedMeshParticipationResult.fromGoalResult("m", "s", makeGoalResult(status = "success"))
        assertEquals(StagedMeshParticipationResult.ExecutionStatus.SUCCESS, r.executionStatus)
    }

    @Test
    fun `fromGoalResult maps error status to FAILURE`() {
        val r = StagedMeshParticipationResult.fromGoalResult("m", "s", makeGoalResult(status = "error"))
        assertEquals(StagedMeshParticipationResult.ExecutionStatus.FAILURE, r.executionStatus)
    }

    @Test
    fun `fromGoalResult maps timeout status to FAILURE`() {
        val r = StagedMeshParticipationResult.fromGoalResult("m", "s", makeGoalResult(status = "timeout"))
        assertEquals(StagedMeshParticipationResult.ExecutionStatus.FAILURE, r.executionStatus)
    }

    @Test
    fun `fromGoalResult maps cancelled status to CANCELLED`() {
        val r = StagedMeshParticipationResult.fromGoalResult("m", "s", makeGoalResult(status = "cancelled"))
        assertEquals(StagedMeshParticipationResult.ExecutionStatus.CANCELLED, r.executionStatus)
    }

    @Test
    fun `fromGoalResult maps disabled status to BLOCKED`() {
        val r = StagedMeshParticipationResult.fromGoalResult("m", "s", makeGoalResult(status = "disabled"))
        assertEquals(StagedMeshParticipationResult.ExecutionStatus.BLOCKED, r.executionStatus)
    }

    @Test
    fun `fromGoalResult preserves meshId`() {
        val r = StagedMeshParticipationResult.fromGoalResult("mesh-abc", "sub-1", makeGoalResult())
        assertEquals("mesh-abc", r.meshId)
    }

    @Test
    fun `fromGoalResult preserves subtaskId`() {
        val r = StagedMeshParticipationResult.fromGoalResult("mesh-1", "sub-xyz", makeGoalResult())
        assertEquals("sub-xyz", r.subtaskId)
    }

    @Test
    fun `fromGoalResult echoes taskId from GoalResultPayload`() {
        val r = StagedMeshParticipationResult.fromGoalResult("m", "s", makeGoalResult(taskId = "task-echo"))
        assertEquals("task-echo", r.taskId)
    }

    @Test
    fun `fromGoalResult echoes stepCount from GoalResultPayload steps`() {
        val r = StagedMeshParticipationResult.fromGoalResult("m", "s", makeGoalResult(stepCount = 4))
        assertEquals(4, r.stepCount)
    }

    @Test
    fun `fromGoalResult echoes latencyMs from GoalResultPayload`() {
        val r = StagedMeshParticipationResult.fromGoalResult("m", "s", makeGoalResult(latencyMs = 999L))
        assertEquals(999L, r.latencyMs)
    }

    @Test
    fun `fromGoalResult echoes output from GoalResultPayload result field`() {
        val r = StagedMeshParticipationResult.fromGoalResult("m", "s", makeGoalResult(result = "some output"))
        assertEquals("some output", r.output)
    }

    @Test
    fun `fromGoalResult echoes error from GoalResultPayload`() {
        val r = StagedMeshParticipationResult.fromGoalResult(
            "m", "s",
            makeGoalResult(status = "error", error = "pipeline failed")
        )
        assertEquals("pipeline failed", r.error)
    }

    // ── StagedMeshExecutionTarget — rollout gate ──────────────────────────────

    @Test
    fun `crossDeviceAllowed=false returns BLOCKED without calling executor`() = runBlocking {
        var executorCalled = false
        val target = makeTarget(executor = StagedMeshExecutionTarget.SubtaskExecutor {
            executorCalled = true
            makeGoalResult()
        })
        val result = target.acceptSubtask("mesh-1", "sub-1", makePayload(), rolloutDisabled())
        assertEquals(StagedMeshParticipationResult.ExecutionStatus.BLOCKED, result.executionStatus)
        assertFalse("Executor must not be called when cross_device is disabled", executorCalled)
    }

    @Test
    fun `BLOCKED result carries correct meshId and subtaskId`() = runBlocking {
        val target = makeTarget(executor = StagedMeshExecutionTarget.SubtaskExecutor { makeGoalResult() })
        val result = target.acceptSubtask("mesh-block-test", "sub-block-test", makePayload(), rolloutDisabled())
        assertEquals("mesh-block-test", result.meshId)
        assertEquals("sub-block-test", result.subtaskId)
    }

    @Test
    fun `BLOCKED result carries correct taskId from payload`() = runBlocking {
        val target = makeTarget(executor = StagedMeshExecutionTarget.SubtaskExecutor { makeGoalResult() })
        val result = target.acceptSubtask("m", "s", makePayload(taskId = "task-blocked"), rolloutDisabled())
        assertEquals("task-blocked", result.taskId)
    }

    @Test
    fun `BLOCKED error string mentions cross_device_disabled`() = runBlocking {
        val target = makeTarget(executor = StagedMeshExecutionTarget.SubtaskExecutor { makeGoalResult() })
        val result = target.acceptSubtask("m", "s", makePayload(), rolloutDisabled())
        assertNotNull(result.error)
        assertTrue(
            "BLOCKED error must mention cross_device_disabled",
            result.error!!.contains("cross_device_disabled")
        )
    }

    // ── StagedMeshExecutionTarget — execution path ────────────────────────────

    @Test
    fun `crossDeviceAllowed=true delegates to executor`() = runBlocking {
        var executorCalled = false
        val target = makeTarget(executor = StagedMeshExecutionTarget.SubtaskExecutor { payload ->
            executorCalled = true
            makeGoalResult(taskId = payload.task_id)
        })
        target.acceptSubtask("mesh-1", "sub-1", makePayload(), rolloutEnabled())
        assertTrue("Executor must be called when cross_device is enabled", executorCalled)
    }

    @Test
    fun `result meshId is preserved after execution`() = runBlocking {
        val target = makeTarget(executor = StagedMeshExecutionTarget.SubtaskExecutor { makeGoalResult() })
        val result = target.acceptSubtask("mesh-identity-exec", "sub-1", makePayload(), rolloutEnabled())
        assertEquals("mesh-identity-exec", result.meshId)
    }

    @Test
    fun `result subtaskId is preserved after execution`() = runBlocking {
        val target = makeTarget(executor = StagedMeshExecutionTarget.SubtaskExecutor { makeGoalResult() })
        val result = target.acceptSubtask("mesh-1", "sub-identity-exec", makePayload(), rolloutEnabled())
        assertEquals("sub-identity-exec", result.subtaskId)
    }

    @Test
    fun `executor success produces SUCCESS result`() = runBlocking {
        val target = makeTarget(executor = StagedMeshExecutionTarget.SubtaskExecutor { makeGoalResult(status = "success") })
        val result = target.acceptSubtask("m", "s", makePayload(), rolloutEnabled())
        assertEquals(StagedMeshParticipationResult.ExecutionStatus.SUCCESS, result.executionStatus)
    }

    @Test
    fun `executor error produces FAILURE result`() = runBlocking {
        val target = makeTarget(executor = StagedMeshExecutionTarget.SubtaskExecutor {
            makeGoalResult(status = "error", error = "plan failed")
        })
        val result = target.acceptSubtask("m", "s", makePayload(), rolloutEnabled())
        assertEquals(StagedMeshParticipationResult.ExecutionStatus.FAILURE, result.executionStatus)
    }

    @Test
    fun `executor cancelled produces CANCELLED result`() = runBlocking {
        val target = makeTarget(executor = StagedMeshExecutionTarget.SubtaskExecutor { makeGoalResult(status = "cancelled") })
        val result = target.acceptSubtask("m", "s", makePayload(), rolloutEnabled())
        assertEquals(StagedMeshParticipationResult.ExecutionStatus.CANCELLED, result.executionStatus)
    }

    @Test
    fun `executor returns empty device_id - deviceId injected from constructor`() = runBlocking {
        val target = makeTarget(
            executor  = StagedMeshExecutionTarget.SubtaskExecutor { makeGoalResult(deviceId = "") },
            deviceId  = "injected-device-id"
        )
        val result = target.acceptSubtask("m", "s", makePayload(), rolloutEnabled())
        assertEquals("injected-device-id", result.deviceId)
    }

    @Test
    fun `executor returns non-empty device_id - preserved from executor`() = runBlocking {
        val target = makeTarget(
            executor  = StagedMeshExecutionTarget.SubtaskExecutor { makeGoalResult(deviceId = "executor-device") },
            deviceId  = "injected-device-id"
        )
        val result = target.acceptSubtask("m", "s", makePayload(), rolloutEnabled())
        assertEquals("executor-device", result.deviceId)
    }

    // ── AndroidSessionContribution — STAGED_MESH_SUBTASK ─────────────────────

    @Test
    fun `Kind STAGED_MESH_SUBTASK exists in Kind enum`() {
        val kinds = AndroidSessionContribution.Kind.entries
        assertTrue(
            "Kind.STAGED_MESH_SUBTASK must exist",
            kinds.any { it == AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK }
        )
    }

    @Test
    fun `STAGED_MESH_SUBTASK is distinct from FINAL_COMPLETION`() {
        assertFalse(
            AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK ==
                AndroidSessionContribution.Kind.FINAL_COMPLETION
        )
    }

    @Test
    fun `STAGED_MESH_SUBTASK is distinct from FAILURE`() {
        assertFalse(
            AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK ==
                AndroidSessionContribution.Kind.FAILURE
        )
    }

    @Test
    fun `STAGED_MESH_SUBTASK is distinct from CANCELLATION`() {
        assertFalse(
            AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK ==
                AndroidSessionContribution.Kind.CANCELLATION
        )
    }

    @Test
    fun `STAGED_MESH_SUBTASK is distinct from DISABLED`() {
        assertFalse(
            AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK ==
                AndroidSessionContribution.Kind.DISABLED
        )
    }

    @Test
    fun `STAGED_MESH_SUBTASK is distinct from TAKEOVER_CONTINUATION`() {
        assertFalse(
            AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK ==
                AndroidSessionContribution.Kind.TAKEOVER_CONTINUATION
        )
    }

    @Test
    fun `fromStagedMeshResult preserves Kind STAGED_MESH_SUBTASK for success`() {
        val result = makeSuccessResult()
        val contrib = AndroidSessionContribution.fromStagedMeshResult(result, deviceRole = "phone")
        assertEquals(AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK, contrib.kind)
    }

    @Test
    fun `fromStagedMeshResult delegates to toSessionContribution`() {
        val result = makeSuccessResult(meshId = "mesh-delegate-test", subtaskId = "sub-delegate-test")
        val directContrib  = result.toSessionContribution("phone")
        val factoryContrib = AndroidSessionContribution.fromStagedMeshResult(result, "phone")
        assertEquals(directContrib, factoryContrib)
    }

    // ── GalaxyLogger — TAG_STAGED_MESH ────────────────────────────────────────

    @Test
    fun `TAG_STAGED_MESH value is GALAXY STAGED MESH`() {
        assertEquals("GALAXY:STAGED:MESH", GalaxyLogger.TAG_STAGED_MESH)
    }

    @Test
    fun `TAG_STAGED_MESH is distinct from TAG_TASK_EXEC`() {
        assertFalse(GalaxyLogger.TAG_STAGED_MESH == GalaxyLogger.TAG_TASK_EXEC)
    }

    @Test
    fun `TAG_STAGED_MESH is distinct from TAG_ROLLOUT_CONTROL`() {
        assertFalse(GalaxyLogger.TAG_STAGED_MESH == GalaxyLogger.TAG_ROLLOUT_CONTROL)
    }

    @Test
    fun `TAG_STAGED_MESH is distinct from TAG_KILL_SWITCH`() {
        assertFalse(GalaxyLogger.TAG_STAGED_MESH == GalaxyLogger.TAG_KILL_SWITCH)
    }

    // ── Regression — existing contract stability ──────────────────────────────

    @Test
    fun `Kind enum has exactly 6 values (pre-PR32 count plus STAGED_MESH_SUBTASK)`() {
        // pre-PR32 kinds: FINAL_COMPLETION, FAILURE, CANCELLATION, TAKEOVER_CONTINUATION,
        // DISABLED = 5 kinds. PR-32 adds STAGED_MESH_SUBTASK = 6 total.
        assertEquals(
            "Kind enum must have exactly 6 values after PR-32",
            6,
            AndroidSessionContribution.Kind.entries.size
        )
    }

    @Test
    fun `toSessionContribution groupId equals meshId for identity bridge stability`() {
        val meshId = "mesh-regression-id"
        val result = makeSuccessResult(meshId = meshId)
        val contrib = result.toSessionContribution()
        assertEquals(
            "groupId must equal meshId to maintain identity bridge across staged-mesh execution",
            meshId,
            contrib.groupId
        )
    }

    @Test
    fun `toSessionContribution traceId equals meshId for correlation stability`() {
        val meshId = "mesh-trace-regression"
        val result = makeSuccessResult(meshId = meshId)
        val contrib = result.toSessionContribution()
        assertEquals(
            "traceId must equal meshId to enable mesh-to-session-truth correlation",
            meshId,
            contrib.traceId
        )
    }
}

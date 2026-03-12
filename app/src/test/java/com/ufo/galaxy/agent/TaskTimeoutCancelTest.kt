package com.ufo.galaxy.agent

import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.protocol.CancelResultPayload
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.TaskCancelPayload
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for task timeout and cancel handling.
 *
 * Tests cover:
 *  - [TaskCancelRegistry] register / cancel / deregister / idempotency
 *  - [GoalExecutionPayload.effectiveTimeoutMs] default and custom values
 *  - Timeout result payload contains required fields (correlation_id, device_id,
 *    group_id, subtask_index)
 *  - Cancel result payload schema (was_running=true / no_op path)
 *  - STATUS_TIMEOUT constant is distinct from STATUS_ERROR and STATUS_CANCELLED
 *  - [TaskCancelPayload] and [CancelResultPayload] model fields
 *
 * All tests are pure JVM — no Android framework dependencies.
 */
class TaskTimeoutCancelTest {

    // ── TaskCancelRegistry ────────────────────────────────────────────────────

    @Test
    fun `registry cancel returns true for registered active job`() = runBlocking {
        val registry = TaskCancelRegistry()
        val job = launch { delay(60_000) }
        registry.register("t-001", job)

        val result = registry.cancel("t-001")

        assertTrue("Expected cancel to return true for active job", result)
        assertFalse("Job should be cancelled", job.isActive)
    }

    @Test
    fun `registry cancel returns false for unknown task_id`() {
        val registry = TaskCancelRegistry()
        val result = registry.cancel("non-existent")
        assertFalse("Expected cancel to return false (no-op) for unknown id", result)
    }

    @Test
    fun `registry cancel is idempotent`() = runBlocking {
        val registry = TaskCancelRegistry()
        val job = launch { delay(60_000) }
        registry.register("t-idem", job)

        val first = registry.cancel("t-idem")
        val second = registry.cancel("t-idem")

        assertTrue("First cancel should succeed", first)
        assertFalse("Second cancel should be no-op", second)
    }

    @Test
    fun `registry deregister removes job and subsequent cancel returns false`() = runBlocking {
        val registry = TaskCancelRegistry()
        val job = launch { delay(60_000) }
        registry.register("t-dereg", job)
        registry.deregister("t-dereg")

        val result = registry.cancel("t-dereg")

        assertFalse("Cancel after deregister should be no-op", result)
        job.cancel() // clean up
    }

    @Test
    fun `registry size reflects registered jobs`() = runBlocking {
        val registry = TaskCancelRegistry()
        assertEquals(0, registry.size)

        val j1 = launch { delay(60_000) }
        val j2 = launch { delay(60_000) }
        registry.register("t-a", j1)
        registry.register("t-b", j2)
        assertEquals(2, registry.size)

        registry.deregister("t-a")
        assertEquals(1, registry.size)

        j1.cancel()
        j2.cancel()
    }

    @Test
    fun `registry isActive returns false after cancel`() = runBlocking {
        val registry = TaskCancelRegistry()
        val job = launch { delay(60_000) }
        registry.register("t-active", job)

        assertTrue(registry.isActive("t-active"))
        registry.cancel("t-active")
        assertFalse(registry.isActive("t-active"))
    }

    // ── GoalExecutionPayload.effectiveTimeoutMs ───────────────────────────────

    @Test
    fun `effectiveTimeoutMs uses DEFAULT when timeout_ms is zero`() {
        val payload = GoalExecutionPayload(task_id = "p-001", goal = "test", timeout_ms = 0L)
        assertEquals(GoalExecutionPayload.DEFAULT_TIMEOUT_MS, payload.effectiveTimeoutMs)
    }

    @Test
    fun `effectiveTimeoutMs returns custom value when set`() {
        val payload = GoalExecutionPayload(task_id = "p-002", goal = "test", timeout_ms = 5_000L)
        assertEquals(5_000L, payload.effectiveTimeoutMs)
    }

    @Test
    fun `effectiveTimeoutMs is capped at MAX_TIMEOUT_MS`() {
        val hugely = GoalExecutionPayload.MAX_TIMEOUT_MS + 999_999L
        val payload = GoalExecutionPayload(task_id = "p-003", goal = "test", timeout_ms = hugely)
        assertEquals(GoalExecutionPayload.MAX_TIMEOUT_MS, payload.effectiveTimeoutMs)
    }

    @Test
    fun `effectiveTimeoutMs uses DEFAULT when timeout_ms is negative`() {
        val payload = GoalExecutionPayload(task_id = "p-004", goal = "test", timeout_ms = -1L)
        assertEquals(GoalExecutionPayload.DEFAULT_TIMEOUT_MS, payload.effectiveTimeoutMs)
    }

    // ── STATUS_TIMEOUT constant ───────────────────────────────────────────────

    @Test
    fun `STATUS_TIMEOUT is distinct from STATUS_ERROR and STATUS_CANCELLED`() {
        assertNotEquals(EdgeExecutor.STATUS_ERROR, EdgeExecutor.STATUS_TIMEOUT)
        assertNotEquals(EdgeExecutor.STATUS_CANCELLED, EdgeExecutor.STATUS_TIMEOUT)
        assertEquals("timeout", EdgeExecutor.STATUS_TIMEOUT)
    }

    @Test
    fun `AutonomousExecutionPipeline STATUS_TIMEOUT mirrors EdgeExecutor STATUS_TIMEOUT`() {
        assertEquals(EdgeExecutor.STATUS_TIMEOUT, AutonomousExecutionPipeline.STATUS_TIMEOUT)
    }

    // ── Timeout GoalResultPayload schema ─────────────────────────────────────

    @Test
    fun `timeout GoalResultPayload has required aggregation fields`() {
        val taskId = "timeout-task-001"
        val result = GoalResultPayload(
            task_id = taskId,
            correlation_id = taskId,
            status = EdgeExecutor.STATUS_TIMEOUT,
            error = "Task exceeded timeout of 30000ms",
            group_id = "grp-timeout",
            subtask_index = 2,
            latency_ms = 30_000L,
            device_id = "device-001"
        )

        assertEquals(taskId, result.task_id)
        assertEquals(taskId, result.correlation_id)
        assertEquals(EdgeExecutor.STATUS_TIMEOUT, result.status)
        assertNotNull("error must be non-null on timeout", result.error)
        assertEquals("grp-timeout", result.group_id)
        assertEquals(2, result.subtask_index)
        assertTrue("latency_ms should be non-negative", result.latency_ms >= 0)
        assertEquals("device-001", result.device_id)
    }

    @Test
    fun `timeout GoalResultPayload without group_id is valid for standalone goals`() {
        val taskId = "timeout-standalone"
        val result = GoalResultPayload(
            task_id = taskId,
            correlation_id = taskId,
            status = EdgeExecutor.STATUS_TIMEOUT,
            error = "Task exceeded timeout of 30000ms",
            latency_ms = 30_000L,
            device_id = "device-002"
        )

        assertNull(result.group_id)
        assertNull(result.subtask_index)
        assertEquals(EdgeExecutor.STATUS_TIMEOUT, result.status)
    }

    // ── CancelResultPayload schema ────────────────────────────────────────────

    @Test
    fun `CancelResultPayload was_running true carries correct fields`() {
        val result = CancelResultPayload(
            task_id = "cancel-001",
            correlation_id = "cancel-001",
            status = EdgeExecutor.STATUS_CANCELLED,
            was_running = true,
            group_id = "grp-cancel",
            subtask_index = 1,
            device_id = "dev-cancel"
        )

        assertEquals("cancel-001", result.task_id)
        assertEquals("cancel-001", result.correlation_id)
        assertEquals(EdgeExecutor.STATUS_CANCELLED, result.status)
        assertTrue(result.was_running)
        assertEquals("grp-cancel", result.group_id)
        assertEquals(1, result.subtask_index)
        assertEquals("dev-cancel", result.device_id)
        assertNull("error should be null when successfully cancelled", result.error)
    }

    @Test
    fun `CancelResultPayload no_op path has was_running false and error set`() {
        val result = CancelResultPayload(
            task_id = "cancel-002",
            correlation_id = "cancel-002",
            status = "no_op",
            was_running = false,
            device_id = "dev-cancel",
            error = "task not found or already completed"
        )

        assertEquals("no_op", result.status)
        assertFalse(result.was_running)
        assertNotNull("error should describe why the cancel was a no-op", result.error)
    }

    // ── TaskCancelPayload schema ──────────────────────────────────────────────

    @Test
    fun `TaskCancelPayload group_id and subtask_index are optional`() {
        val cancel = TaskCancelPayload(task_id = "solo-task")
        assertNull(cancel.group_id)
        assertNull(cancel.subtask_index)
    }

    @Test
    fun `TaskCancelPayload carries group_id and subtask_index for parallel cancels`() {
        val cancel = TaskCancelPayload(
            task_id = "parallel-task",
            group_id = "grp-parallel",
            subtask_index = 3
        )
        assertEquals("grp-parallel", cancel.group_id)
        assertEquals(3, cancel.subtask_index)
    }
}

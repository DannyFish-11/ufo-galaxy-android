package com.ufo.galaxy.coordination

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [MultiDeviceCoordinator] (P3) — parallel dispatch semantics.
 *
 * All tests run on the JVM using [runBlocking]; no Android framework is required.
 * The [dispatch] lambda is replaced by in-memory stubs that record invocations.
 *
 * Test matrix:
 *  - Successful parallel dispatch → all subtask results succeed, counts match.
 *  - One device fails → failed count incremented, succeeded count correct.
 *  - All devices fail → allSucceeded is false, failedCount equals device count.
 *  - Subtask IDs follow `<groupId>_sub_<index>` format.
 *  - Group ID is echoed in [ParallelGroupResult].
 *  - Exception in dispatch lambda → captured as a failed [SubtaskResult], not thrown.
 *  - Results are ordered consistently with device list order.
 *  - Empty device list → succeededCount and failedCount are both 0.
 *  - Custom groupId is preserved in the result.
 */
class MultiDeviceCoordinatorTest {

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun coordinator(
        devices: List<String>,
        dispatch: suspend (deviceId: String, subtaskId: String, goal: String) -> MultiDeviceCoordinator.SubtaskResult
    ) = MultiDeviceCoordinator(
        deviceIds = devices,
        dispatch = dispatch
    )

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    fun `all devices succeed`() = runBlocking {
        val devices = listOf("phone-1", "phone-2", "tablet-1")
        val coord = coordinator(devices) { deviceId, subtaskId, _ ->
            MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = deviceId, success = true)
        }

        val group = coord.dispatchParallel("open settings", groupId = "grp-001")

        assertEquals(3, group.succeededCount)
        assertEquals(0, group.failedCount)
        assertTrue("allSucceeded must be true", group.allSucceeded)
    }

    @Test
    fun `group result contains all subtask results`() = runBlocking {
        val devices = listOf("dev-a", "dev-b")
        val coord = coordinator(devices) { deviceId, subtaskId, _ ->
            MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = deviceId, success = true)
        }

        val group = coord.dispatchParallel("task", groupId = "grp-x")

        assertEquals(2, group.subtaskResults.size)
    }

    // ── Partial failure ────────────────────────────────────────────────────────

    @Test
    fun `one failing device increments failedCount`() = runBlocking {
        val devices = listOf("ok-device", "fail-device")
        val coord = coordinator(devices) { deviceId, subtaskId, _ ->
            val success = deviceId != "fail-device"
            MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = deviceId,
                success = success, error = if (!success) "simulated failure" else null)
        }

        val group = coord.dispatchParallel("test goal", groupId = "grp-002")

        assertEquals(1, group.succeededCount)
        assertEquals(1, group.failedCount)
        assertFalse(group.allSucceeded)
    }

    @Test
    fun `succeeded and failed list views are correct`() = runBlocking {
        val devices = listOf("ok", "fail")
        val coord = coordinator(devices) { deviceId, subtaskId, _ ->
            MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = deviceId,
                success = deviceId == "ok")
        }

        val group = coord.dispatchParallel("goal")

        assertEquals(1, group.succeeded.size)
        assertEquals("ok", group.succeeded.first().deviceId)
        assertEquals(1, group.failed.size)
        assertEquals("fail", group.failed.first().deviceId)
    }

    // ── All devices fail ───────────────────────────────────────────────────────

    @Test
    fun `all devices fail`() = runBlocking {
        val devices = listOf("dev1", "dev2", "dev3")
        val coord = coordinator(devices) { deviceId, subtaskId, _ ->
            MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = deviceId,
                success = false, error = "unreachable")
        }

        val group = coord.dispatchParallel("failing task", groupId = "grp-fail")

        assertEquals(0, group.succeededCount)
        assertEquals(3, group.failedCount)
        assertFalse(group.allSucceeded)
    }

    // ── Subtask ID format ──────────────────────────────────────────────────────

    @Test
    fun `subtask IDs follow groupId underscore sub underscore index format`() = runBlocking {
        val devices = listOf("d0", "d1", "d2")
        val subtaskIds = mutableListOf<String>()
        val coord = coordinator(devices) { _, subtaskId, _ ->
            subtaskIds += subtaskId
            MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = "d", success = true)
        }

        coord.dispatchParallel("goal", groupId = "my-group")

        assertEquals(3, subtaskIds.size)
        assertTrue("index 0 subtask id", subtaskIds.any { it == "my-group_sub_0" })
        assertTrue("index 1 subtask id", subtaskIds.any { it == "my-group_sub_1" })
        assertTrue("index 2 subtask id", subtaskIds.any { it == "my-group_sub_2" })
    }

    @Test
    fun `subtask IDs are unique even when device IDs are the same`() = runBlocking {
        val devices = listOf("same", "same", "same")
        val subtaskIds = mutableListOf<String>()
        val coord = coordinator(devices) { _, subtaskId, _ ->
            subtaskIds += subtaskId
            MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = "same", success = true)
        }

        coord.dispatchParallel("goal", groupId = "dup-group")

        assertEquals("must produce 3 unique subtask IDs", 3, subtaskIds.toSet().size)
    }

    // ── Group ID echoed ────────────────────────────────────────────────────────

    @Test
    fun `group ID is echoed in ParallelGroupResult`() = runBlocking {
        val coord = coordinator(listOf("dev")) { deviceId, subtaskId, _ ->
            MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = deviceId, success = true)
        }

        val group = coord.dispatchParallel("goal", groupId = "echo-group")

        assertEquals("echo-group", group.groupId)
    }

    @Test
    fun `goal is echoed in ParallelGroupResult`() = runBlocking {
        val coord = coordinator(listOf("dev")) { deviceId, subtaskId, _ ->
            MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = deviceId, success = true)
        }

        val group = coord.dispatchParallel("my special goal")

        assertEquals("my special goal", group.goal)
    }

    // ── Exception handling ─────────────────────────────────────────────────────

    @Test
    fun `exception in dispatch lambda is captured as failed SubtaskResult`() = runBlocking {
        val coord = coordinator(listOf("boom")) { _, subtaskId, _ ->
            throw RuntimeException("network timeout")
        }

        // Must NOT throw; exception captured in result
        val group = coord.dispatchParallel("goal", groupId = "ex-group")

        assertEquals(0, group.succeededCount)
        assertEquals(1, group.failedCount)
        val failed = group.subtaskResults.first()
        assertFalse(failed.success)
        assertNotNull("error should be captured", failed.error)
        assertTrue("error should contain exception message", failed.error!!.contains("network timeout"))
    }

    // ── Empty device list ──────────────────────────────────────────────────────

    @Test
    fun `empty device list produces group with zero counts`() = runBlocking {
        val coord = coordinator(emptyList()) { _, subtaskId, _ ->
            MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = "", success = true)
        }

        val group = coord.dispatchParallel("goal")

        assertEquals(0, group.succeededCount)
        assertEquals(0, group.failedCount)
        assertTrue(group.allSucceeded)
        assertTrue(group.subtaskResults.isEmpty())
    }

    // ── Concurrency ────────────────────────────────────────────────────────────

    @Test
    fun `all devices are called exactly once per dispatch`() = runBlocking {
        val callCount = AtomicInteger(0)
        val devices = listOf("a", "b", "c", "d", "e")
        val coord = coordinator(devices) { deviceId, subtaskId, _ ->
            callCount.incrementAndGet()
            MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = deviceId, success = true)
        }

        coord.dispatchParallel("goal")

        assertEquals("Each device must be called exactly once", 5, callCount.get())
    }

    // ── Custom groupId auto-generation ─────────────────────────────────────────

    @Test
    fun `auto-generated groupId is non-empty when not supplied`() = runBlocking {
        val coord = coordinator(listOf("dev")) { deviceId, subtaskId, _ ->
            MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = deviceId, success = true)
        }

        val group = coord.dispatchParallel("auto group goal")

        assertTrue("Auto-generated groupId must not be empty", group.groupId.isNotEmpty())
    }
}

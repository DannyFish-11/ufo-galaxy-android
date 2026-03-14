package com.ufo.galaxy.coordination

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for multi-device concurrency additions in [MultiDeviceCoordinator]:
 *  - [ParallelGroupResult] data model
 *  - [DeviceInfo] serialisation fix (single-body class)
 *  - [CommandResult] baseline
 */
class MultiDeviceCoordinatorTest {

    // ──────────────────────────────────────────────────────────────────────────
    // DeviceInfo
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `DeviceInfo name and type aliases return correct values`() {
        val info = DeviceInfo(
            deviceId = "d1",
            deviceType = "phone",
            deviceName = "Pixel 7",
            capabilities = listOf("screenshot", "click"),
            osVersion = "14",
            appVersion = "2.5.0"
        )
        assertEquals("Pixel 7", info.name)
        assertEquals("phone", info.type)
    }

    @Test
    fun `DeviceInfo toJson round-trips via fromJson`() {
        val original = DeviceInfo(
            deviceId = "dev_42",
            deviceType = "tablet",
            deviceName = "Tab S9",
            capabilities = listOf("voice", "accessibility"),
            osVersion = "13",
            appVersion = "2.5.0",
            lastSeen = 1_000L,
            isOnline = true
        )
        val json = original.toJson()
        val restored = DeviceInfo.fromJson(json)

        assertEquals(original.deviceId, restored.deviceId)
        assertEquals(original.deviceType, restored.deviceType)
        assertEquals(original.deviceName, restored.deviceName)
        assertEquals(original.capabilities, restored.capabilities)
        assertEquals(original.osVersion, restored.osVersion)
        assertEquals(original.appVersion, restored.appVersion)
    }

    @Test
    fun `DeviceInfo fromJson handles missing optional fields`() {
        val minimal = JSONObject().apply {
            put("device_id", "min_dev")
        }
        val info = DeviceInfo.fromJson(minimal)
        assertEquals("min_dev", info.deviceId)
        assertEquals("unknown", info.deviceType)
        assertEquals("Unknown Device", info.deviceName)
        assertTrue(info.capabilities.isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ParallelGroupResult
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `ParallelGroupResult allSucceeded true when all dispatched`() {
        val results = mapOf(
            "dev_a" to TaskResult("t1_0", TaskStatus.DISPATCHED),
            "dev_b" to TaskResult("t1_1", TaskStatus.DISPATCHED),
            "dev_c" to TaskResult("t1_2", TaskStatus.COMPLETED)
        )
        val group = ParallelGroupResult(
            groupId = "grp_t1",
            taskId = "t1",
            deviceResults = results,
            allSucceeded = results.values.all {
                it.status == TaskStatus.DISPATCHED || it.status == TaskStatus.COMPLETED
            }
        )
        assertTrue(group.allSucceeded)
        assertEquals(3, group.succeeded.size)
        assertTrue(group.failed.isEmpty())
    }

    @Test
    fun `ParallelGroupResult allSucceeded false when any failed`() {
        val results = mapOf(
            "dev_a" to TaskResult("t2_0", TaskStatus.DISPATCHED),
            "dev_b" to TaskResult("t2_1", TaskStatus.FAILED, error = "timeout")
        )
        val group = ParallelGroupResult(
            groupId = "grp_t2",
            taskId = "t2",
            deviceResults = results,
            allSucceeded = false
        )
        assertFalse(group.allSucceeded)
        assertEquals(1, group.succeeded.size)
        assertEquals(1, group.failed.size)
        assertEquals("timeout", group.failed["dev_b"]?.error)
    }

    @Test
    fun `ParallelGroupResult succeeded subset contains only DISPATCHED and COMPLETED`() {
        val results = mapOf(
            "d1" to TaskResult("t3_0", TaskStatus.COMPLETED),
            "d2" to TaskResult("t3_1", TaskStatus.FAILED),
            "d3" to TaskResult("t3_2", TaskStatus.TIMEOUT),
            "d4" to TaskResult("t3_3", TaskStatus.DISPATCHED)
        )
        val group = ParallelGroupResult(
            groupId = "grp_t3",
            taskId = "t3",
            deviceResults = results,
            allSucceeded = false
        )
        assertEquals(setOf("d1", "d4"), group.succeeded.keys)
        assertEquals(setOf("d2", "d3"), group.failed.keys)
    }

    @Test
    fun `ParallelGroupResult empty deviceResults is allSucceeded true`() {
        val group = ParallelGroupResult(
            groupId = "grp_empty",
            taskId = "t_empty",
            deviceResults = emptyMap(),
            allSucceeded = true
        )
        assertTrue(group.allSucceeded)
        assertTrue(group.succeeded.isEmpty())
        assertTrue(group.failed.isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CommandResult
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `CommandResult default fields are correct`() {
        val r = CommandResult(success = true)
        assertTrue(r.success)
        assertEquals("", r.result)
        assertEquals("", r.error)
    }

    @Test
    fun `CommandResult failed has non-empty error`() {
        val r = CommandResult(success = false, error = "network_error")
        assertFalse(r.success)
        assertEquals("network_error", r.error)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TaskStatus enum completeness
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `TaskStatus has all expected values`() {
        val names = TaskStatus.values().map { it.name }.toSet()
        assertTrue(names.containsAll(setOf(
            "PENDING", "DISPATCHED", "RUNNING", "COMPLETED", "FAILED", "TIMEOUT", "UNKNOWN"
        )))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CoordinationTask
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `CoordinationTask copy changes only taskId`() {
        val original = CoordinationTask(
            taskId = "base_task",
            taskType = "execute",
            payload = JSONObject().apply { put("key", "value") },
            priority = 3,
            timeout = 45_000L
        )
        val copy = original.copy(taskId = "base_task_1")
        assertEquals("base_task_1", copy.taskId)
        assertEquals(original.taskType, copy.taskType)
        assertEquals(original.priority, copy.priority)
        assertEquals(original.timeout, copy.timeout)
    }
}

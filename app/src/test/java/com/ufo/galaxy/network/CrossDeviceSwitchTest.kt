package com.ufo.galaxy.network

import com.ufo.galaxy.data.CapabilityReport
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.MsgType
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the cross-device collaboration switch behaviour in [GalaxyWebSocketClient]
 * and [CapabilityReport].
 *
 * These are pure JVM tests (no Android framework required).
 */
class CrossDeviceSwitchTest {

    // ── CapabilityReport field presence ───────────────────────────────────────

    @Test
    fun `CapabilityReport carries high-level capabilities and metadata`() {
        val metadata = mapOf<String, Any>(
            "goal_execution_enabled" to true,
            "local_model_enabled" to true,
            "cross_device_enabled" to true,
            "parallel_execution_enabled" to true,
            "device_role" to "phone"
        )
        val report = CapabilityReport(
            platform = "android",
            device_id = "test-device",
            supported_actions = listOf("screen_capture"),
            capabilities = listOf(
                "autonomous_goal_execution",
                "local_task_planning",
                "local_ui_reasoning",
                "cross_device_coordination",
                "local_model_inference"
            ),
            metadata = metadata
        )

        assertEquals(5, report.capabilities.size)
        assertTrue(report.capabilities.contains("autonomous_goal_execution"))
        assertTrue(report.capabilities.contains("local_task_planning"))
        assertTrue(report.capabilities.contains("local_ui_reasoning"))
        assertTrue(report.capabilities.contains("cross_device_coordination"))
        assertTrue(report.capabilities.contains("local_model_inference"))

        assertEquals(true, report.metadata["goal_execution_enabled"])
        assertEquals(true, report.metadata["local_model_enabled"])
        assertEquals(true, report.metadata["cross_device_enabled"])
        assertEquals(true, report.metadata["parallel_execution_enabled"])
        assertEquals("phone", report.metadata["device_role"])
    }

    @Test
    fun `CapabilityReport defaults capabilities and metadata to empty`() {
        val report = CapabilityReport(
            platform = "android",
            device_id = "dev-x",
            supported_actions = listOf("tap")
        )
        assertTrue(report.capabilities.isEmpty())
        assertTrue(report.metadata.isEmpty())
    }

    // ── GalaxyWebSocketClient crossDeviceEnabled runtime toggle ───────────────

    @Test
    fun `setCrossDeviceEnabled changes flag correctly`() {
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        assertFalse(client.crossDeviceEnabled)

        client.setCrossDeviceEnabled(true)
        assertTrue(client.crossDeviceEnabled)

        client.setCrossDeviceEnabled(false)
        assertFalse(client.crossDeviceEnabled)
    }

    @Test
    fun `crossDeviceEnabled false by default when set to false in constructor`() {
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        assertFalse(client.crossDeviceEnabled)
    }

    @Test
    fun `crossDeviceEnabled is true when set to true in constructor`() {
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = true)
        assertTrue(client.crossDeviceEnabled)
    }

    @Test
    fun `setCrossDeviceEnabled is no-op when value unchanged`() {
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        // Should not throw or change state
        client.setCrossDeviceEnabled(false)
        assertFalse(client.crossDeviceEnabled)
    }

    // ── MsgType new values ────────────────────────────────────────────────────

    @Test
    fun `MsgType includes GOAL_EXECUTION, PARALLEL_SUBTASK, GOAL_RESULT`() {
        assertEquals("goal_execution", MsgType.GOAL_EXECUTION.value)
        assertEquals("parallel_subtask", MsgType.PARALLEL_SUBTASK.value)
        assertEquals("goal_result", MsgType.GOAL_RESULT.value)
    }

    // ── GoalExecutionPayload field presence ───────────────────────────────────

    @Test
    fun `GoalExecutionPayload carries required fields with defaults`() {
        val payload = GoalExecutionPayload(
            task_id = "goal-001",
            goal = "Open WeChat and send hello",
            group_id = "grp-001",
            subtask_index = 0
        )

        assertEquals("goal-001", payload.task_id)
        assertEquals("Open WeChat and send hello", payload.goal)
        assertEquals("grp-001", payload.group_id)
        assertEquals(0, payload.subtask_index)
        assertTrue(payload.constraints.isEmpty())
        assertEquals(10, payload.max_steps)
    }

    @Test
    fun `GoalExecutionPayload group_id and subtask_index default to null`() {
        val payload = GoalExecutionPayload(
            task_id = "solo-task",
            goal = "Set an alarm"
        )
        assertNull(payload.group_id)
        assertNull(payload.subtask_index)
    }

    // ── Listener no-op defaults ───────────────────────────────────────────────

    @Test
    fun `Listener default onGoalExecution and onParallelSubtask are no-ops`() {
        val listener = object : GalaxyWebSocketClient.Listener {
            override fun onConnected() {}
            override fun onDisconnected() {}
            override fun onMessage(message: String) {}
            override fun onError(error: String) {}
        }
        // Should not throw
        listener.onGoalExecution("t", "{}")
        listener.onParallelSubtask("t", "{}")
    }
}

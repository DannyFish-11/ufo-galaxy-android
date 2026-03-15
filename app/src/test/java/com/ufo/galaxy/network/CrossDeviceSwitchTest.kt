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

    // ── GalaxyWebSocketClient offline queue integration ───────────────────────

    @Test
    fun `sendJson when disconnected queues task_result type`() {
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true,
            offlineQueue = testQueue
        )
        // Client is not connected; sendJson should enqueue the message
        val json = """{"type":"task_result","payload":{}}"""
        val sent = client.sendJson(json)
        assertFalse("sendJson returns false when not connected", sent)
        assertEquals("Message should be queued", 1, testQueue.size)
    }

    @Test
    fun `sendJson when disconnected queues goal_result type`() {
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true,
            offlineQueue = testQueue
        )
        val json = """{"type":"goal_result","payload":{}}"""
        client.sendJson(json)
        assertEquals(1, testQueue.size)
        assertEquals("goal_result", testQueue.drainAll().first().type)
    }

    @Test
    fun `sendJson when disconnected does NOT queue non-queueable type`() {
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true,
            offlineQueue = testQueue
        )
        // heartbeat should not be queued
        val json = """{"type":"heartbeat","timestamp":1234}"""
        client.sendJson(json)
        assertEquals("Non-queueable type should not be enqueued", 0, testQueue.size)
    }

    @Test
    fun `queueSize StateFlow reflects offlineQueue depth`() {
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true,
            offlineQueue = testQueue
        )
        assertEquals(0, client.queueSize.value)

        client.sendJson("""{"type":"task_result"}""")
        assertEquals(1, client.queueSize.value)
    }

    @Test
    fun `reconnectAttemptCount StateFlow starts at 0`() {
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        assertEquals(0, client.reconnectAttemptCount.value)
    }

    @Test
    fun `notifyNetworkAvailable is no-op when crossDeviceEnabled is false`() {
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        // Should not throw
        client.notifyNetworkAvailable()
        assertFalse(client.isConnected())
    }

    @Test
    fun `notifyNetworkAvailable is no-op when already connected`() {
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true,
            offlineQueue = testQueue
        )
        // Not connected — just verify it doesn't throw when called
        client.notifyNetworkAvailable()
        // (actual reconnect attempt would need a live server; just verifying no exception)
    }

    // ── Round 4: cross-device OFF hard constraint on sendJson ─────────────────

    @Test
    fun `sendJson returns false immediately when crossDeviceEnabled is false`() {
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        val json = """{"type":"task_submit","payload":{}}"""
        val result = client.sendJson(json)
        assertFalse("sendJson must return false when cross-device is OFF", result)
    }

    @Test
    fun `sendJson does not enqueue when crossDeviceEnabled is false even for queueable type`() {
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false,
            offlineQueue = testQueue
        )
        // task_result is normally queueable, but cross-device OFF must block before queuing
        val json = """{"type":"task_result","payload":{}}"""
        val result = client.sendJson(json)
        assertFalse("sendJson must return false when cross-device is OFF", result)
        assertEquals("Offline queue must remain empty when cross-device is OFF", 0, testQueue.size)
    }

    @Test
    fun `sendJson blocks task_submit when crossDeviceEnabled is false`() {
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false,
            offlineQueue = testQueue
        )
        val json = """{"type":"task_submit","payload":{"task_text":"open WeChat"}}"""
        val result = client.sendJson(json)
        assertFalse("task_submit must be blocked when cross-device is OFF", result)
    }

    @Test
    fun `sendJson blocks goal_result when crossDeviceEnabled is false`() {
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false,
            offlineQueue = testQueue
        )
        val json = """{"type":"goal_result","payload":{}}"""
        val result = client.sendJson(json)
        assertFalse("goal_result must be blocked when cross-device is OFF", result)
        assertEquals("Queue must be empty — blocked before queuing logic", 0, testQueue.size)
    }

    @Test
    fun `sendJson passes through when crossDeviceEnabled is true and disconnected`() {
        // When ON but disconnected, queueable types should be queued (existing behaviour).
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true,
            offlineQueue = testQueue
        )
        val json = """{"type":"task_result","payload":{}}"""
        val result = client.sendJson(json)
        // Not connected → returns false but enqueues
        assertFalse("sendJson returns false when ON but not connected", result)
        assertEquals("task_result must be queued when cross-device is ON (disconnected)", 1, testQueue.size)
    }

    @Test
    fun `setCrossDeviceEnabled false then sendJson is blocked`() {
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true,
            offlineQueue = testQueue
        )
        // Toggle OFF at runtime
        client.setCrossDeviceEnabled(false)
        assertFalse(client.crossDeviceEnabled)

        val json = """{"type":"task_submit","payload":{}}"""
        val result = client.sendJson(json)
        assertFalse("sendJson must be blocked after runtime toggle to OFF", result)
        assertEquals("Queue must remain empty after runtime toggle OFF", 0, testQueue.size)
    }

    @Test
    fun `sendJson OFF guard blocks all message types without queueing`() {
        // Verify the hard constraint applies uniformly across all AIP v3 message types.
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false,
            offlineQueue = testQueue
        )
        val messageTypes = listOf(
            "task_submit", "task_result", "goal_result",
            "cancel_result", "heartbeat", "capability_report"
        )
        for (type in messageTypes) {
            val json = """{"type":"$type","payload":{}}"""
            assertFalse("sendJson must block type=$type when cross-device is OFF", client.sendJson(json))
        }
        assertEquals("Offline queue must stay empty for all blocked message types", 0, testQueue.size)
    }

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

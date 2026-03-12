package com.ufo.galaxy.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Validates AIP v3 message model fields required by the cloud-edge pipeline.
 *
 * Steps covered:
 *  [3]  TaskSubmitPayload  — Android -> Gateway uplink
 *  [6]  TaskAssignPayload  — Gateway -> Android downlink
 *  [8]  TaskResultPayload  — Android -> Gateway task result
 *  [8]  CommandResultPayload — Android -> Gateway step result
 */
class AipV3ModelsTest {

    // ── TaskSubmitPayload ─────────────────────────────────────────────────────

    @Test
    fun `TaskSubmitPayload contains required uplink fields`() {
        val payload = TaskSubmitPayload(
            task_text = "打开微信并发送消息给张三",
            device_id = "device-001",
            session_id = "session-abc"
        )

        assertEquals("打开微信并发送消息给张三", payload.task_text)
        assertEquals("device-001", payload.device_id)
        assertEquals("session-abc", payload.session_id)
        assertTrue("context defaults to empty map", payload.context.isEmpty())
    }

    @Test
    fun `TaskSubmitPayload accepts non-empty context`() {
        val payload = TaskSubmitPayload(
            task_text = "set alarm",
            device_id = "dev-x",
            session_id = "sess-y",
            context = mapOf("locale" to "zh-CN", "app_foreground" to "com.android.clock")
        )

        assertEquals("zh-CN", payload.context["locale"])
        assertEquals("com.android.clock", payload.context["app_foreground"])
    }

    // ── TaskAssignPayload ─────────────────────────────────────────────────────

    @Test
    fun `TaskAssignPayload contains all required downlink fields including require_local_agent`() {
        val payload = TaskAssignPayload(
            task_id = "task-001",
            goal = "Open WeChat and send 'hello' to Alice",
            constraints = listOf("do not navigate away from WeChat"),
            max_steps = 10,
            require_local_agent = true
        )

        assertEquals("task-001", payload.task_id)
        assertEquals("Open WeChat and send 'hello' to Alice", payload.goal)
        assertEquals(listOf("do not navigate away from WeChat"), payload.constraints)
        assertEquals(10, payload.max_steps)
        assertTrue("require_local_agent must be true", payload.require_local_agent)
    }

    @Test
    fun `TaskAssignPayload can set require_local_agent to false`() {
        val payload = TaskAssignPayload(
            task_id = "task-002",
            goal = "Fetch weather",
            max_steps = 3,
            require_local_agent = false
        )

        assertFalse(payload.require_local_agent)
        assertTrue("constraints defaults to empty list", payload.constraints.isEmpty())
    }

    @Test
    fun `TaskAssignPayload has no coordinate fields`() {
        val payload = TaskAssignPayload(
            task_id = "t",
            goal = "g",
            max_steps = 1,
            require_local_agent = true
        )
        // Verify that the data class does not expose x / y coordinate properties
        val fieldNames = payload.javaClass.declaredFields.map { it.name }
        assertFalse("TaskAssignPayload must not contain x coordinate", fieldNames.contains("x"))
        assertFalse("TaskAssignPayload must not contain y coordinate", fieldNames.contains("y"))
    }

    // ── TaskResultPayload ─────────────────────────────────────────────────────

    @Test
    fun `TaskResultPayload contains required result fields`() {
        val payload = TaskResultPayload(
            task_id = "task-001",
            step_id = "5",
            status = TaskExecutionStatus.SUCCESS
        )

        assertEquals("task-001", payload.task_id)
        assertEquals("5", payload.step_id)
        assertEquals(TaskExecutionStatus.SUCCESS, payload.status)
        assertNull("error must be null on success", payload.error)
        assertNull("snapshot is optional and null by default", payload.snapshot)
    }

    @Test
    fun `TaskResultPayload supports optional Base64 snapshot`() {
        val fakeSnapshot = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val payload = TaskResultPayload(
            task_id = "task-002",
            step_id = "3",
            status = TaskExecutionStatus.ERROR,
            error = "GUI grounding failed: element not found",
            snapshot = fakeSnapshot
        )

        assertEquals(TaskExecutionStatus.ERROR, payload.status)
        assertEquals("GUI grounding failed: element not found", payload.error)
        assertNotNull("snapshot must be present when provided", payload.snapshot)
        assertEquals(fakeSnapshot, payload.snapshot)
    }

    @Test
    fun `TaskResultPayload has no coordinate fields`() {
        val payload = TaskResultPayload(
            task_id = "t",
            step_id = "1",
            status = TaskExecutionStatus.SUCCESS
        )
        val fieldNames = payload.javaClass.declaredFields.map { it.name }
        assertFalse("TaskResultPayload must not contain x coordinate", fieldNames.contains("x"))
        assertFalse("TaskResultPayload must not contain y coordinate", fieldNames.contains("y"))
    }

    // ── CommandResultPayload ──────────────────────────────────────────────────

    @Test
    fun `CommandResultPayload contains all required step-level fields`() {
        val payload = CommandResultPayload(
            task_id = "task-003",
            step_id = "2",
            action = "tap",
            status = TaskExecutionStatus.SUCCESS
        )

        assertEquals("task-003", payload.task_id)
        assertEquals("2", payload.step_id)
        assertEquals("tap", payload.action)
        assertEquals(TaskExecutionStatus.SUCCESS, payload.status)
        assertNull(payload.error)
        assertNull(payload.snapshot)
    }

    @Test
    fun `CommandResultPayload supports snapshot on error`() {
        val fakeSnapshot = "base64encodeddata=="
        val payload = CommandResultPayload(
            task_id = "task-004",
            step_id = "1",
            action = "scroll",
            status = TaskExecutionStatus.ERROR,
            error = "element not found after scroll",
            snapshot = fakeSnapshot
        )

        assertEquals(TaskExecutionStatus.ERROR, payload.status)
        assertNotNull(payload.snapshot)
    }

    // ── TaskExecutionStatus ───────────────────────────────────────────────────

    @Test
    fun `TaskExecutionStatus values map to correct string identifiers`() {
        assertEquals("success", TaskExecutionStatus.SUCCESS.value)
        assertEquals("error", TaskExecutionStatus.ERROR.value)
        assertEquals("timeout", TaskExecutionStatus.TIMEOUT.value)
        assertEquals("cancelled", TaskExecutionStatus.CANCELLED.value)
    }

    // ── AipV3MessageType ──────────────────────────────────────────────────────

    @Test
    fun `AipV3MessageType values map to correct string identifiers`() {
        assertEquals("task_submit", AipV3MessageType.TASK_SUBMIT.value)
        assertEquals("task_assign", AipV3MessageType.TASK_ASSIGN.value)
        assertEquals("task_result", AipV3MessageType.TASK_RESULT.value)
        assertEquals("command_result", AipV3MessageType.COMMAND_RESULT.value)
    }

    // ── AipV3Envelope ─────────────────────────────────────────────────────────

    @Test
    fun `AipV3Envelope defaults to version 3_0 and auto-sets timestamp`() {
        val before = System.currentTimeMillis()
        val envelope = AipV3Envelope(
            type = AipV3MessageType.TASK_SUBMIT,
            payload = TaskSubmitPayload("test", "dev", "sess")
        )
        val after = System.currentTimeMillis()

        assertEquals("3.0", envelope.version)
        assertTrue(envelope.timestamp in before..after)
        assertNull(envelope.session_id)
        assertNull(envelope.device_id)
    }

    @Test
    fun `AipV3Envelope wraps TaskAssignPayload correctly`() {
        val assignPayload = TaskAssignPayload(
            task_id = "t-100",
            goal = "open settings",
            max_steps = 5,
            require_local_agent = true
        )
        val envelope = AipV3Envelope(
            type = AipV3MessageType.TASK_ASSIGN,
            payload = assignPayload,
            session_id = "s-001",
            device_id = "d-001"
        )

        assertEquals(AipV3MessageType.TASK_ASSIGN, envelope.type)
        assertEquals("s-001", envelope.session_id)
        assertEquals("d-001", envelope.device_id)
        val inner = envelope.payload as TaskAssignPayload
        assertTrue(inner.require_local_agent)
    }
}

package com.ufo.galaxy.protocol

import org.junit.Assert.*
import org.junit.Test

/**
 * Validates AIP v3 protocol models in [com.ufo.galaxy.protocol].
 *
 * Covers:
 *  - MsgType enum value mapping
 *  - AipMessage envelope with correlation_id
 *  - TaskSubmitPayload with TaskSubmitContext
 *  - TaskAssignPayload field presence and no-coordinate contract
 *  - StepResult field structure
 *  - TaskResultPayload with Snapshot
 *  - CommandResultPayload field structure
 *  - Snapshot data class
 */
class AipModelsTest {

    // ── MsgType ───────────────────────────────────────────────────────────────

    @Test
    fun `MsgType values map to correct string identifiers`() {
        assertEquals("task_submit", MsgType.TASK_SUBMIT.value)
        assertEquals("task_assign", MsgType.TASK_ASSIGN.value)
        assertEquals("task_result", MsgType.TASK_RESULT.value)
        assertEquals("command_result", MsgType.COMMAND_RESULT.value)
        assertEquals("device_register", MsgType.DEVICE_REGISTER.value)
        assertEquals("capability_report", MsgType.CAPABILITY_REPORT.value)
        assertEquals("heartbeat", MsgType.HEARTBEAT.value)
        assertEquals("heartbeat_ack", MsgType.HEARTBEAT_ACK.value)
    }

    // ── AipMessage ────────────────────────────────────────────────────────────

    @Test
    fun `AipMessage defaults to version 3_0 and auto-sets timestamp`() {
        val before = System.currentTimeMillis()
        val msg = AipMessage(
            type = MsgType.TASK_SUBMIT,
            payload = "test"
        )
        val after = System.currentTimeMillis()

        assertEquals("3.0", msg.version)
        assertTrue(msg.timestamp in before..after)
        assertNull(msg.correlation_id)
        assertNull(msg.session_id)
        assertNull(msg.device_id)
    }

    @Test
    fun `AipMessage carries correlation_id for reply routing`() {
        val msg = AipMessage(
            type = MsgType.TASK_RESULT,
            payload = "result",
            correlation_id = "task-abc-123"
        )

        assertEquals("task-abc-123", msg.correlation_id)
        assertEquals(MsgType.TASK_RESULT, msg.type)
    }

    @Test
    fun `AipMessage accepts session_id and device_id`() {
        val msg = AipMessage(
            type = MsgType.TASK_ASSIGN,
            payload = "payload",
            session_id = "sess-001",
            device_id = "dev-001"
        )

        assertEquals("sess-001", msg.session_id)
        assertEquals("dev-001", msg.device_id)
    }

    // ── TaskSubmitContext ─────────────────────────────────────────────────────

    @Test
    fun `TaskSubmitContext defaults to nulls and empty extra map`() {
        val ctx = TaskSubmitContext()

        assertNull(ctx.locale)
        assertNull(ctx.app_foreground)
        assertTrue(ctx.extra.isEmpty())
    }

    @Test
    fun `TaskSubmitContext accepts locale and app_foreground`() {
        val ctx = TaskSubmitContext(
            locale = "zh-CN",
            app_foreground = "com.tencent.mm",
            extra = mapOf("mode" to "voice")
        )

        assertEquals("zh-CN", ctx.locale)
        assertEquals("com.tencent.mm", ctx.app_foreground)
        assertEquals("voice", ctx.extra["mode"])
    }

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
    }

    @Test
    fun `TaskSubmitPayload context defaults to empty TaskSubmitContext`() {
        val payload = TaskSubmitPayload(
            task_text = "set alarm",
            device_id = "dev-x",
            session_id = "sess-y"
        )

        assertNull(payload.context.locale)
        assertNull(payload.context.app_foreground)
        assertTrue(payload.context.extra.isEmpty())
    }

    @Test
    fun `TaskSubmitPayload accepts structured context`() {
        val payload = TaskSubmitPayload(
            task_text = "open maps",
            device_id = "dev-x",
            session_id = "sess-y",
            context = TaskSubmitContext(locale = "en-US", app_foreground = "com.android.launcher3")
        )

        assertEquals("en-US", payload.context.locale)
        assertEquals("com.android.launcher3", payload.context.app_foreground)
    }

    // ── TaskAssignPayload ─────────────────────────────────────────────────────

    @Test
    fun `TaskAssignPayload contains all required downlink fields`() {
        val payload = TaskAssignPayload(
            task_id = "task-001",
            goal = "Open WeChat and send hello to Alice",
            constraints = listOf("do not leave WeChat"),
            max_steps = 10,
            require_local_agent = true
        )

        assertEquals("task-001", payload.task_id)
        assertEquals("Open WeChat and send hello to Alice", payload.goal)
        assertEquals(listOf("do not leave WeChat"), payload.constraints)
        assertEquals(10, payload.max_steps)
        assertTrue(payload.require_local_agent)
    }

    @Test
    fun `TaskAssignPayload has no coordinate fields`() {
        val payload = TaskAssignPayload(
            task_id = "t",
            goal = "g",
            max_steps = 1,
            require_local_agent = true
        )
        // Collect declared fields across the entire class hierarchy
        val fieldNames = generateSequence<Class<*>>(payload.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .map { it.name }
            .toSet()
        assertFalse("TaskAssignPayload must not contain x coordinate", fieldNames.contains("x"))
        assertFalse("TaskAssignPayload must not contain y coordinate", fieldNames.contains("y"))
    }

    @Test
    fun `TaskAssignPayload constraints defaults to empty list`() {
        val payload = TaskAssignPayload(
            task_id = "t",
            goal = "g",
            max_steps = 5,
            require_local_agent = false
        )

        assertTrue(payload.constraints.isEmpty())
        assertFalse(payload.require_local_agent)
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    @Test
    fun `Snapshot holds Base64 data with dimensions and timestamp`() {
        val fakeBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
        val before = System.currentTimeMillis()
        val snapshot = Snapshot(data = fakeBase64, width = 1080, height = 2340)
        val after = System.currentTimeMillis()

        assertEquals(fakeBase64, snapshot.data)
        assertEquals(1080, snapshot.width)
        assertEquals(2340, snapshot.height)
        assertTrue(snapshot.timestamp in before..after)
    }

    @Test
    fun `Snapshot has no coordinate fields`() {
        val snapshot = Snapshot(data = "abc", width = 100, height = 200)
        val fieldNames = generateSequence<Class<*>>(snapshot.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .map { it.name }
            .toSet()
        assertFalse("Snapshot must not contain x coordinate", fieldNames.contains("x"))
        assertFalse("Snapshot must not contain y coordinate", fieldNames.contains("y"))
    }

    // ── StepResult ────────────────────────────────────────────────────────────

    @Test
    fun `StepResult contains required fields with optional snapshot`() {
        val fakeSnap = Snapshot(data = "base64data", width = 100, height = 200)
        val result = StepResult(
            step_id = "1",
            action = "tap",
            success = true,
            snapshot = fakeSnap
        )

        assertEquals("1", result.step_id)
        assertEquals("tap", result.action)
        assertTrue(result.success)
        assertNull(result.error)
        assertNotNull(result.snapshot)
        assertEquals("base64data", result.snapshot!!.data)
    }

    @Test
    fun `StepResult error path has non-null error and false success`() {
        val result = StepResult(
            step_id = "2",
            action = "scroll",
            success = false,
            error = "element not found"
        )

        assertFalse(result.success)
        assertEquals("element not found", result.error)
        assertNull(result.snapshot)
    }

    @Test
    fun `StepResult latency_ms defaults to 0 and snapshot_ref defaults to null`() {
        val result = StepResult(step_id = "1", action = "tap", success = true)
        assertEquals(0L, result.latency_ms)
        assertNull(result.snapshot_ref)
    }

    @Test
    fun `StepResult accepts latency_ms and snapshot_ref`() {
        val result = StepResult(
            step_id = "3",
            action = "tap",
            success = true,
            latency_ms = 250L,
            snapshot_ref = "step3_abcd1234"
        )
        assertEquals(250L, result.latency_ms)
        assertEquals("step3_abcd1234", result.snapshot_ref)
    }

    // ── TaskResultPayload ─────────────────────────────────────────────────────

    @Test
    fun `TaskResultPayload sets correlation_id to task_id`() {
        val payload = TaskResultPayload(
            task_id = "task-100",
            correlation_id = "task-100",
            status = "success"
        )

        assertEquals("task-100", payload.task_id)
        assertEquals("task-100", payload.correlation_id)
        assertEquals("success", payload.status)
        assertNull(payload.error)
        assertTrue(payload.steps.isEmpty())
    }

    @Test
    fun `TaskResultPayload supports optional snapshot on error`() {
        val snap = Snapshot(data = "b64jpeg", width = 1080, height = 2340)
        val payload = TaskResultPayload(
            task_id = "task-200",
            correlation_id = "task-200",
            status = "error",
            error = "grounding failed",
            snapshot = snap
        )

        assertEquals("error", payload.status)
        assertEquals("grounding failed", payload.error)
        assertNotNull(payload.snapshot)
        assertEquals("b64jpeg", payload.snapshot!!.data)
    }

    @Test
    fun `TaskResultPayload has no coordinate fields`() {
        val payload = TaskResultPayload(task_id = "t", status = "success")
        val fieldNames = generateSequence<Class<*>>(payload.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .map { it.name }
            .toSet()
        assertFalse("TaskResultPayload must not contain x", fieldNames.contains("x"))
        assertFalse("TaskResultPayload must not contain y", fieldNames.contains("y"))
    }

    // ── CommandResultPayload ──────────────────────────────────────────────────

    @Test
    fun `CommandResultPayload contains all required step-level fields`() {
        val payload = CommandResultPayload(
            task_id = "task-003",
            step_id = "2",
            action = "tap",
            status = "success"
        )

        assertEquals("task-003", payload.task_id)
        assertEquals("2", payload.step_id)
        assertEquals("tap", payload.action)
        assertEquals("success", payload.status)
        assertNull(payload.error)
        assertNull(payload.snapshot)
    }

    @Test
    fun `CommandResultPayload supports snapshot on error`() {
        val snap = Snapshot(data = "b64data", width = 360, height = 800)
        val payload = CommandResultPayload(
            task_id = "task-004",
            step_id = "1",
            action = "scroll",
            status = "error",
            error = "element not found",
            snapshot = snap
        )

        assertEquals("error", payload.status)
        assertEquals("element not found", payload.error)
        assertNotNull(payload.snapshot)
    }
}

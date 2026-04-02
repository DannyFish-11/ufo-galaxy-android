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
        assertEquals("AIP/1.0", msg.protocol)
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
    fun `TaskSubmitPayload task_id defaults to empty and can be set explicitly`() {
        val withDefault = TaskSubmitPayload(
            task_text = "test",
            device_id = "dev-x",
            session_id = "sess-y"
        )
        assertEquals("", withDefault.task_id)

        val withExplicit = TaskSubmitPayload(
            task_text = "test",
            device_id = "dev-x",
            session_id = "sess-y",
            task_id = "task-uuid-001"
        )
        assertEquals("task-uuid-001", withExplicit.task_id)
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

    @Test
    fun `TaskResultPayload includes trace_id device_id result_summary fields`() {
        val payload = TaskResultPayload(
            task_id = "task-300",
            correlation_id = "task-300",
            status = "success",
            trace_id = "trace-uuid-001",
            device_id = "samsung_galaxy_s24",
            result_summary = "task_assign: 3 step(s) status=success"
        )

        assertEquals("trace-uuid-001", payload.trace_id)
        assertEquals("samsung_galaxy_s24", payload.device_id)
        assertEquals("task_assign: 3 step(s) status=success", payload.result_summary)
    }

    @Test
    fun `TaskResultPayload trace_id defaults to null`() {
        val payload = TaskResultPayload(task_id = "t", status = "success")
        assertNull("trace_id must default to null", payload.trace_id)
    }

    @Test
    fun `TaskResultPayload device_id defaults to empty string`() {
        val payload = TaskResultPayload(task_id = "t", status = "success")
        assertEquals("", payload.device_id)
    }

    @Test
    fun `TaskResultPayload result_summary defaults to null`() {
        val payload = TaskResultPayload(task_id = "t", status = "success")
        assertNull("result_summary must default to null", payload.result_summary)
    }

    @Test
    fun `TaskResultPayload error path sets trace_id and result_summary`() {
        val payload = TaskResultPayload(
            task_id = "task-error-001",
            correlation_id = "task-error-001",
            status = "error",
            error = "grounding failed",
            trace_id = "trace-err-uuid",
            device_id = "test-device",
            result_summary = "error: grounding failed"
        )

        assertEquals("error", payload.status)
        assertEquals("grounding failed", payload.error)
        assertEquals("trace-err-uuid", payload.trace_id)
        assertEquals("test-device", payload.device_id)
        assertNotNull(payload.result_summary)
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

    // ── MsgType — new entries (PR-3) ──────────────────────────────────────────

    @Test
    fun `MsgType MESH_JOIN LEAVE RESULT and DIAGNOSTICS_PAYLOAD map to correct wire strings`() {
        assertEquals("mesh_join", MsgType.MESH_JOIN.value)
        assertEquals("mesh_leave", MsgType.MESH_LEAVE.value)
        assertEquals("mesh_result", MsgType.MESH_RESULT.value)
        assertEquals("diagnostics_payload", MsgType.DIAGNOSTICS_PAYLOAD.value)
    }

    // ── DiagnosticsPayload (M2) ───────────────────────────────────────────────

    @Test
    fun `DiagnosticsPayload contains required fields`() {
        val before = System.currentTimeMillis()
        val payload = DiagnosticsPayload(
            task_id = "task-diag-001",
            device_id = "device-001",
            node_name = "android_edge",
            error_type = "network_timeout",
            error_context = "connect timed out after 30s"
        )
        val after = System.currentTimeMillis()

        assertEquals("task-diag-001", payload.task_id)
        assertEquals("device-001", payload.device_id)
        assertEquals("android_edge", payload.node_name)
        assertEquals("network_timeout", payload.error_type)
        assertEquals("connect timed out after 30s", payload.error_context)
        assertTrue(payload.timestamp in before..after)
    }

    @Test
    fun `DiagnosticsPayload wraps in AipMessage with DIAGNOSTICS_PAYLOAD type`() {
        val payload = DiagnosticsPayload(
            task_id = "t1",
            device_id = "d1",
            node_name = "n1",
            error_type = "permission_denied",
            error_context = "camera access denied"
        )
        val envelope = AipMessage(
            type = MsgType.DIAGNOSTICS_PAYLOAD,
            payload = payload,
            device_id = "d1",
            trace_id = "trace-001"
        )

        assertEquals(MsgType.DIAGNOSTICS_PAYLOAD, envelope.type)
        assertEquals("AIP/1.0", envelope.protocol)
        assertEquals("3.0", envelope.version)
        assertEquals("trace-001", envelope.trace_id)
    }

    // ── MeshJoinPayload (H5) ──────────────────────────────────────────────────

    @Test
    fun `MeshJoinPayload contains required fields with defaults`() {
        val payload = MeshJoinPayload(
            mesh_id = "mesh-abc-001",
            device_id = "phone-1"
        )

        assertEquals("mesh-abc-001", payload.mesh_id)
        assertEquals("phone-1", payload.device_id)
        assertEquals("participant", payload.role)
        assertTrue(payload.capabilities.isEmpty())
    }

    @Test
    fun `MeshJoinPayload accepts coordinator role and capabilities`() {
        val payload = MeshJoinPayload(
            mesh_id = "mesh-xyz",
            device_id = "tablet-1",
            role = "coordinator",
            capabilities = listOf("local_task_planning", "autonomous_goal_execution")
        )

        assertEquals("coordinator", payload.role)
        assertEquals(2, payload.capabilities.size)
        assertTrue(payload.capabilities.contains("local_task_planning"))
    }

    // ── MeshLeavePayload (H5) ─────────────────────────────────────────────────

    @Test
    fun `MeshLeavePayload contains required fields with default reason`() {
        val payload = MeshLeavePayload(
            mesh_id = "mesh-abc-001",
            device_id = "phone-1"
        )

        assertEquals("mesh-abc-001", payload.mesh_id)
        assertEquals("phone-1", payload.device_id)
        assertEquals("disconnect", payload.reason)
    }

    @Test
    fun `MeshLeavePayload accepts task_complete and error reasons`() {
        val complete = MeshLeavePayload(mesh_id = "m", device_id = "d", reason = "task_complete")
        val error = MeshLeavePayload(mesh_id = "m", device_id = "d", reason = "error")

        assertEquals("task_complete", complete.reason)
        assertEquals("error", error.reason)
    }

    // ── MeshResultPayload (H5) ────────────────────────────────────────────────

    @Test
    fun `MeshResultPayload contains required fields with defaults`() {
        val payload = MeshResultPayload(
            mesh_id = "mesh-001",
            task_id = "task-001",
            device_id = "phone-1",
            status = "success"
        )

        assertEquals("mesh-001", payload.mesh_id)
        assertEquals("task-001", payload.task_id)
        assertEquals("phone-1", payload.device_id)
        assertEquals("success", payload.status)
        assertTrue(payload.results.isEmpty())
        assertNull(payload.summary)
        assertEquals(0L, payload.latency_ms)
    }

    @Test
    fun `MeshResultPayload aggregates MeshSubtaskResult entries`() {
        val subtasks = listOf(
            MeshSubtaskResult(device_id = "phone-1", subtask_id = "grp_sub_0", status = "success", output = "done"),
            MeshSubtaskResult(device_id = "tablet-1", subtask_id = "grp_sub_1", status = "error", error = "timeout")
        )
        val payload = MeshResultPayload(
            mesh_id = "mesh-001",
            task_id = "task-001",
            device_id = "phone-1",
            status = "partial",
            results = subtasks,
            summary = "1 succeeded, 1 failed",
            latency_ms = 1500L
        )

        assertEquals(2, payload.results.size)
        assertEquals("partial", payload.status)
        assertEquals("1 succeeded, 1 failed", payload.summary)
        assertEquals(1500L, payload.latency_ms)
    }

    @Test
    fun `MeshSubtaskResult contains device and subtask identifiers`() {
        val result = MeshSubtaskResult(
            device_id = "dev-a",
            subtask_id = "grp-123_sub_0",
            status = "success",
            output = "open settings completed"
        )

        assertEquals("dev-a", result.device_id)
        assertEquals("grp-123_sub_0", result.subtask_id)
        assertEquals("success", result.status)
        assertEquals("open settings completed", result.output)
        assertNull(result.error)
    }
}

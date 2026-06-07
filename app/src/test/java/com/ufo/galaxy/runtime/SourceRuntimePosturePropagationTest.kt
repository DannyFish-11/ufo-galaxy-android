package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.AgentRuntimeBridge
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.network.GatewayClient
import com.ufo.galaxy.observability.MetricsRecorder
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.TaskAssignPayload
import com.ufo.galaxy.protocol.TaskSubmitPayload
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * Tests for `source_runtime_posture` parsing, propagation, and safe defaults
 * across Android-side AIP v3 payload models and the AgentRuntimeBridge handoff path.
 *
 * This is the Android-repo half of the post-PR-#533 dual-repo runtime host
 * unification contract (PR 1).
 *
 * Test matrix:
 *  - [TaskSubmitPayload] carries source_runtime_posture; defaults to null (backwards-safe).
 *  - [TaskAssignPayload] carries source_runtime_posture; defaults to null (backwards-safe).
 *  - [GoalExecutionPayload] carries source_runtime_posture; defaults to null (backwards-safe).
 *  - [GoalResultPayload] echoes source_runtime_posture from the execution payload.
 *  - [AipMessage] envelope carries source_runtime_posture; defaults to null.
 *  - [AgentRuntimeBridge.HandoffRequest] carries sourceRuntimePosture.
 *  - [AgentRuntimeBridge.buildBridgeJson] emits source_runtime_posture when set.
 *  - [AgentRuntimeBridge.buildBridgeJson] omits source_runtime_posture when null/blank.
 *  - Null posture in HandoffRequest is safe (no crash, field omitted from JSON).
 */
class SourceRuntimePosturePropagationTest {

    // ── TaskSubmitPayload ─────────────────────────────────────────────────────

    @Test
    fun `TaskSubmitPayload carries join_runtime posture`() {
        val payload = TaskSubmitPayload(
            task_text = "open settings",
            device_id = "dev-1",
            session_id = "sess-1",
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
        )
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, payload.source_runtime_posture)
    }

    @Test
    fun `TaskSubmitPayload defaults source_runtime_posture to null (backwards-safe)`() {
        val payload = TaskSubmitPayload(
            task_text = "open settings",
            device_id = "dev-1",
            session_id = "sess-1"
        )
        assertNull("Legacy payloads must not break — source_runtime_posture defaults to null", payload.source_runtime_posture)
    }

    @Test
    fun `TaskSubmitPayload validate() still passes when source_runtime_posture is null`() {
        val payload = TaskSubmitPayload(
            task_text = "open settings",
            device_id = "dev-1",
            session_id = "sess-1"
        )
        assertTrue(payload.validate())
    }

    @Test
    fun `TaskSubmitPayload validate() still passes with control_only posture`() {
        val payload = TaskSubmitPayload(
            task_text = "open settings",
            device_id = "dev-1",
            session_id = "sess-1",
            source_runtime_posture = SourceRuntimePosture.CONTROL_ONLY
        )
        assertTrue(payload.validate())
    }

    // ── TaskAssignPayload ─────────────────────────────────────────────────────

    @Test
    fun `TaskAssignPayload carries join_runtime posture from gateway`() {
        val payload = TaskAssignPayload(
            task_id = "t1",
            goal = "open camera",
            max_steps = 10,
            require_local_agent = false,
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
        )
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, payload.source_runtime_posture)
    }

    @Test
    fun `TaskAssignPayload defaults source_runtime_posture to null (backwards-safe)`() {
        val payload = TaskAssignPayload(
            task_id = "t1",
            goal = "open camera",
            max_steps = 10,
            require_local_agent = false
        )
        assertNull(payload.source_runtime_posture)
    }

    // ── GoalExecutionPayload ──────────────────────────────────────────────────

    @Test
    fun `GoalExecutionPayload carries control_only posture`() {
        val payload = GoalExecutionPayload(
            task_id = "g1",
            goal = "run test",
            source_runtime_posture = SourceRuntimePosture.CONTROL_ONLY
        )
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, payload.source_runtime_posture)
    }

    @Test
    fun `GoalExecutionPayload defaults source_runtime_posture to null (backwards-safe)`() {
        val payload = GoalExecutionPayload(task_id = "g1", goal = "run test")
        assertNull(payload.source_runtime_posture)
    }

    @Test
    fun `GoalExecutionPayload effectiveTimeoutMs is unaffected by posture field`() {
        val payload = GoalExecutionPayload(
            task_id = "g1",
            goal = "run test",
            timeout_ms = 0,
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
        )
        assertEquals(GoalExecutionPayload.DEFAULT_TIMEOUT_MS, payload.effectiveTimeoutMs)
    }

    // ── GoalResultPayload ─────────────────────────────────────────────────────

    @Test
    fun `GoalResultPayload echoes source_runtime_posture from execution`() {
        val result = GoalResultPayload(
            task_id = "g1",
            status = "success",
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
        )
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, result.source_runtime_posture)
    }

    @Test
    fun `GoalResultPayload defaults source_runtime_posture to null (backwards-safe)`() {
        val result = GoalResultPayload(task_id = "g1", status = "success")
        assertNull(result.source_runtime_posture)
    }

    // ── AipMessage envelope ───────────────────────────────────────────────────

    @Test
    fun `AipMessage carries source_runtime_posture in envelope`() {
        val msg = AipMessage(
            type = MsgType.TASK_SUBMIT,
            payload = "{}",
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
        )
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, msg.source_runtime_posture)
    }

    @Test
    fun `AipMessage defaults source_runtime_posture to null (backwards-safe)`() {
        val msg = AipMessage(type = MsgType.HEARTBEAT, payload = "{}")
        assertNull(msg.source_runtime_posture)
    }

    // ── AgentRuntimeBridge.HandoffRequest ─────────────────────────────────────

    @Test
    fun `HandoffRequest carries sourceRuntimePosture`() {
        val req = AgentRuntimeBridge.HandoffRequest(
            traceId = UUID.randomUUID().toString(),
            taskId = "t1",
            goal = "open settings",
            sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
        )
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, req.sourceRuntimePosture)
    }

    @Test
    fun `HandoffRequest defaults sourceRuntimePosture to null (backwards-safe)`() {
        val req = AgentRuntimeBridge.HandoffRequest(
            traceId = UUID.randomUUID().toString(),
            taskId = "t1",
            goal = "open settings"
        )
        assertNull(req.sourceRuntimePosture)
    }

    // ── AgentRuntimeBridge.buildBridgeJson propagation ───────────────────────

    private fun buildBridge(): AgentRuntimeBridge {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val client = object : GatewayClient {
            override fun isConnected() = true
            override fun sendJson(json: String) = true
        }
        return AgentRuntimeBridge(
            gatewayClient = client,
            settings = settings,
            metricsRecorder = MetricsRecorder(InMemoryAppSettings())
        )
    }

    @Test
    fun `buildBridgeJson includes source_runtime_posture when set to join_runtime`() {
        val bridge = buildBridge()
        val req = AgentRuntimeBridge.HandoffRequest(
            traceId = UUID.randomUUID().toString(),
            taskId = "t1",
            goal = "open camera",
            sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
        )
        val json = bridge.buildBridgeJson(req)
        val obj = JSONObject(json)
        assertTrue("source_runtime_posture must be present in bridge JSON", obj.has("source_runtime_posture"))
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, obj.getString("source_runtime_posture"))
    }

    @Test
    fun `buildBridgeJson includes source_runtime_posture when set to control_only`() {
        val bridge = buildBridge()
        val req = AgentRuntimeBridge.HandoffRequest(
            traceId = UUID.randomUUID().toString(),
            taskId = "t1",
            goal = "open camera",
            sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
        )
        val json = bridge.buildBridgeJson(req)
        val obj = JSONObject(json)
        assertTrue("source_runtime_posture must be present in bridge JSON", obj.has("source_runtime_posture"))
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, obj.getString("source_runtime_posture"))
    }

    @Test
    fun `buildBridgeJson omits source_runtime_posture when null`() {
        val bridge = buildBridge()
        val req = AgentRuntimeBridge.HandoffRequest(
            traceId = UUID.randomUUID().toString(),
            taskId = "t1",
            goal = "open camera",
            sourceRuntimePosture = null
        )
        val json = bridge.buildBridgeJson(req)
        val obj = JSONObject(json)
        assertFalse(
            "source_runtime_posture must be omitted when null (backwards-safe)",
            obj.has("source_runtime_posture")
        )
    }

    @Test
    fun `buildBridgeJson always includes required fields regardless of posture`() {
        val bridge = buildBridge()
        val req = AgentRuntimeBridge.HandoffRequest(
            traceId = "trace-xyz",
            taskId = "t-required",
            goal = "test goal",
            sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
        )
        val json = bridge.buildBridgeJson(req)
        val obj = JSONObject(json)
        assertTrue(obj.has("type"))
        assertTrue(obj.has("trace_id"))
        assertTrue(obj.has("task_id"))
        assertTrue(obj.has("exec_mode"))
        assertTrue(obj.has("route_mode"))
        assertTrue(obj.has("goal"))
        assertEquals("bridge_handoff", obj.getString("type"))
        assertEquals("trace-xyz", obj.getString("trace_id"))
    }

    @Test
    fun `handoff succeeds and sends posture in payload`() = runBlocking {
        val sentMessages = mutableListOf<String>()
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val client = object : GatewayClient {
            override fun isConnected() = true
            override fun sendJson(json: String): Boolean {
                sentMessages.add(json)
                return true
            }
        }
        val bridge = AgentRuntimeBridge(
            gatewayClient = client,
            settings = settings,
            metricsRecorder = MetricsRecorder(InMemoryAppSettings())
        )
        val req = AgentRuntimeBridge.HandoffRequest(
            traceId = UUID.randomUUID().toString(),
            taskId = "t-posture",
            goal = "open WeChat",
            sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
        )
        val result = bridge.handoff(req)
        assertTrue(result.isHandoff)
        assertEquals(1, sentMessages.size)
        val sent = JSONObject(sentMessages[0])
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, sent.getString("source_runtime_posture"))
    }
}

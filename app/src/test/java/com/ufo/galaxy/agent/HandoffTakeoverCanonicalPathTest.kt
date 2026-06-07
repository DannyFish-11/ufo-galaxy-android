package com.ufo.galaxy.agent

import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.coordination.MultiDeviceCoordinator
import com.ufo.galaxy.network.GatewayClient
import com.ufo.galaxy.observability.MetricsRecorder
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.runtime.SourceRuntimePosture
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * Android-repo half of PR-3 in the post-#533 dual-repo runtime host unification track.
 *
 * **PR-3 scope**: canonicalize cross-device handoff and takeover between the Android
 * runtime and the main repo.
 *
 * ## Test matrix
 *
 * ### TakeoverRequestEnvelope parsing
 *  - Required fields (takeover_id, task_id, trace_id, goal) are preserved.
 *  - [source_runtime_posture] is preserved from raw JSON.
 *  - Null / missing posture falls back to [SourceRuntimePosture.DEFAULT] via [resolvedPosture].
 *  - [isSourceControlOnly] / [isSourceJoinRuntime] are consistent with [resolvedPosture].
 *  - Optional fields (source_device_id, session_id, checkpoint) default to null.
 *  - exec_mode and route_mode default to canonical bridge constants.
 *
 * ### TakeoverResponseEnvelope creation and posture echo
 *  - takeover_id / task_id / trace_id are echoed from the request.
 *  - accepted=false carries the rejection reason.
 *  - source_runtime_posture is echoed from the request for correlation.
 *  - accepted=true path (future PR-5) is structurally valid.
 *
 * ### TakeoverHandlingResult
 *  - Accepted result carries the correct fields.
 *  - Rejected result carries the reason string.
 *
 * ### MsgType canonical takeover types
 *  - TAKEOVER_REQUEST is in [MsgType.ADVANCED_TYPES].
 *  - TAKEOVER_REQUEST is in [MsgType.ACK_ON_RECEIPT_TYPES].
 *  - TAKEOVER_RESPONSE is in [MsgType.ADVANCED_TYPES].
 *  - TAKEOVER_RESPONSE is NOT in [MsgType.ACK_ON_RECEIPT_TYPES].
 *  - fromValue("takeover_request") returns TAKEOVER_REQUEST.
 *  - fromValue("takeover_response") returns TAKEOVER_RESPONSE.
 *
 * ### HandoffRequest posture propagation (PR-3 gap fix)
 *  - HandoffRequest carries sourceRuntimePosture resolved via SourceRuntimePosture.fromValue.
 *  - buildBridgeJson emits source_runtime_posture when resolved from TaskAssignPayload posture.
 *  - Null posture in TaskAssignPayload resolves to DEFAULT (control_only) in HandoffRequest.
 *  - join_runtime posture propagated end-to-end: TaskAssignPayload → HandoffRequest → JSON.
 *
 * ### MultiDeviceCoordinator posture propagation
 *  - sourceRuntimePosture is echoed in ParallelGroupResult.
 *  - Null posture is preserved as null in ParallelGroupResult.
 *  - Blocked (cross-device OFF) result also echoes sourceRuntimePosture.
 */
class HandoffTakeoverCanonicalPathTest {

    // ── TakeoverRequestEnvelope: required-field preservation ──────────────────

    @Test
    fun `TakeoverRequestEnvelope preserves required fields`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-1",
            task_id = "t-1",
            trace_id = "tr-1",
            goal = "open camera"
        )
        assertEquals("to-1", envelope.takeover_id)
        assertEquals("t-1", envelope.task_id)
        assertEquals("tr-1", envelope.trace_id)
        assertEquals("open camera", envelope.goal)
    }

    @Test
    fun `TakeoverRequestEnvelope optional fields default to null`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-2",
            task_id = "t-2",
            trace_id = "tr-2",
            goal = "send message"
        )
        assertNull("source_device_id must default to null", envelope.source_device_id)
        assertNull("source_runtime_posture must default to null", envelope.source_runtime_posture)
        assertNull("session_id must default to null", envelope.session_id)
        assertNull("runtime_session_id must default to null", envelope.runtime_session_id)
        assertNull("checkpoint must default to null", envelope.checkpoint)
    }

    @Test
    fun `TakeoverRequestEnvelope exec_mode defaults to remote`() {
        val envelope = TakeoverRequestEnvelope(takeover_id = "x", task_id = "t", trace_id = "tr", goal = "g")
        assertEquals(AgentRuntimeBridge.EXEC_MODE_REMOTE, envelope.exec_mode)
    }

    @Test
    fun `TakeoverRequestEnvelope route_mode defaults to cross_device`() {
        val envelope = TakeoverRequestEnvelope(takeover_id = "x", task_id = "t", trace_id = "tr", goal = "g")
        assertEquals(AgentRuntimeBridge.ROUTE_MODE_CROSS_DEVICE, envelope.route_mode)
    }

    // ── TakeoverRequestEnvelope: posture fields ────────────────────────────────

    @Test
    fun `TakeoverRequestEnvelope carries join_runtime posture`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-3",
            task_id = "t-3",
            trace_id = "tr-3",
            goal = "run task",
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
        )
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, envelope.source_runtime_posture)
    }

    @Test
    fun `TakeoverRequestEnvelope carries control_only posture`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-4",
            task_id = "t-4",
            trace_id = "tr-4",
            goal = "execute task",
            source_runtime_posture = SourceRuntimePosture.CONTROL_ONLY
        )
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, envelope.source_runtime_posture)
    }

    @Test
    fun `TakeoverRequestEnvelope resolvedPosture falls back to DEFAULT when posture is null`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-5",
            task_id = "t-5",
            trace_id = "tr-5",
            goal = "task"
        )
        assertEquals(
            "null posture must resolve to SourceRuntimePosture.DEFAULT",
            SourceRuntimePosture.DEFAULT,
            envelope.resolvedPosture
        )
    }

    @Test
    fun `TakeoverRequestEnvelope resolvedPosture returns join_runtime for join_runtime input`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-6",
            task_id = "t-6",
            trace_id = "tr-6",
            goal = "task",
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
        )
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, envelope.resolvedPosture)
    }

    @Test
    fun `TakeoverRequestEnvelope resolvedPosture falls back to DEFAULT for unknown posture string`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-7",
            task_id = "t-7",
            trace_id = "tr-7",
            goal = "task",
            source_runtime_posture = "unknown_future_posture"
        )
        assertEquals(SourceRuntimePosture.DEFAULT, envelope.resolvedPosture)
    }

    @Test
    fun `TakeoverRequestEnvelope isSourceControlOnly is true when posture is null`() {
        val envelope = TakeoverRequestEnvelope(takeover_id = "x", task_id = "t", trace_id = "tr", goal = "g")
        assertTrue("null posture must be treated as control_only", envelope.isSourceControlOnly)
        assertFalse("null posture must NOT be treated as join_runtime", envelope.isSourceJoinRuntime)
    }

    @Test
    fun `TakeoverRequestEnvelope isSourceJoinRuntime is true when posture is join_runtime`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "x", task_id = "t", trace_id = "tr", goal = "g",
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
        )
        assertTrue("join_runtime posture must be detected", envelope.isSourceJoinRuntime)
        assertFalse("join_runtime must NOT be treated as control_only", envelope.isSourceControlOnly)
    }

    @Test
    fun `TakeoverRequestEnvelope isSourceControlOnly is true when posture is control_only`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "x", task_id = "t", trace_id = "tr", goal = "g",
            source_runtime_posture = SourceRuntimePosture.CONTROL_ONLY
        )
        assertTrue("control_only posture must be detected", envelope.isSourceControlOnly)
        assertFalse("control_only must NOT be treated as join_runtime", envelope.isSourceJoinRuntime)
    }

    // ── TakeoverResponseEnvelope: creation and posture echo ───────────────────

    @Test
    fun `TakeoverResponseEnvelope echoes takeover_id and task_id`() {
        val response = TakeoverResponseEnvelope(
            takeover_id = "to-10",
            task_id = "t-10",
            trace_id = "tr-10",
            accepted = false,
            rejection_reason = "not_ready"
        )
        assertEquals("to-10", response.takeover_id)
        assertEquals("t-10", response.task_id)
        assertEquals("tr-10", response.trace_id)
    }

    @Test
    fun `TakeoverResponseEnvelope rejection carries reason`() {
        val response = TakeoverResponseEnvelope(
            takeover_id = "to-11",
            task_id = "t-11",
            trace_id = "tr-11",
            accepted = false,
            rejection_reason = "takeover_executor_not_implemented"
        )
        assertFalse("accepted must be false for rejection", response.accepted)
        assertEquals("takeover_executor_not_implemented", response.rejection_reason)
    }

    @Test
    fun `TakeoverResponseEnvelope echoes source_runtime_posture from request`() {
        val request = TakeoverRequestEnvelope(
            takeover_id = "to-12",
            task_id = "t-12",
            trace_id = "tr-12",
            goal = "goal",
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
        )
        val response = TakeoverResponseEnvelope(
            takeover_id = request.takeover_id,
            task_id = request.task_id,
            trace_id = request.trace_id,
            accepted = false,
            rejection_reason = "not_implemented",
            source_runtime_posture = request.source_runtime_posture
        )
        assertEquals(
            "response must echo the request's source_runtime_posture",
            SourceRuntimePosture.JOIN_RUNTIME,
            response.source_runtime_posture
        )
    }

    @Test
    fun `TakeoverResponseEnvelope accepted=true path is structurally valid`() {
        val response = TakeoverResponseEnvelope(
            takeover_id = "to-13",
            task_id = "t-13",
            trace_id = "tr-13",
            accepted = true,
            rejection_reason = null,
            exec_mode = AgentRuntimeBridge.EXEC_MODE_REMOTE
        )
        assertTrue("accepted must be true for acceptance", response.accepted)
        assertNull("rejection_reason must be null for acceptance", response.rejection_reason)
    }

    // ── TakeoverHandlingResult ─────────────────────────────────────────────────

    @Test
    fun `TakeoverHandlingResult accepted result carries correct fields`() {
        val result = TakeoverHandlingResult(
            takeoverId = "to-20",
            taskId = "t-20",
            traceId = "tr-20",
            accepted = true,
            reason = "accepted"
        )
        assertEquals("to-20", result.takeoverId)
        assertEquals("t-20", result.taskId)
        assertEquals("tr-20", result.traceId)
        assertTrue(result.accepted)
        assertEquals("accepted", result.reason)
    }

    @Test
    fun `TakeoverHandlingResult rejected result carries reason`() {
        val result = TakeoverHandlingResult(
            takeoverId = "to-21",
            taskId = "t-21",
            traceId = "tr-21",
            accepted = false,
            reason = "takeover_executor_not_implemented"
        )
        assertFalse(result.accepted)
        assertEquals("takeover_executor_not_implemented", result.reason)
    }

    // ── MsgType canonical takeover types ──────────────────────────────────────

    @Test
    fun `TAKEOVER_REQUEST is in MsgType ADVANCED_TYPES`() {
        assertTrue(
            "TAKEOVER_REQUEST must be in ADVANCED_TYPES so it is never silently dropped",
            MsgType.TAKEOVER_REQUEST in MsgType.ADVANCED_TYPES
        )
    }

    @Test
    fun `TAKEOVER_REQUEST is in MsgType ACK_ON_RECEIPT_TYPES`() {
        assertTrue(
            "TAKEOVER_REQUEST must be in ACK_ON_RECEIPT_TYPES for delivery confirmation",
            MsgType.TAKEOVER_REQUEST in MsgType.ACK_ON_RECEIPT_TYPES
        )
    }

    @Test
    fun `TAKEOVER_RESPONSE is in MsgType ADVANCED_TYPES`() {
        assertTrue(
            "TAKEOVER_RESPONSE must be in ADVANCED_TYPES",
            MsgType.TAKEOVER_RESPONSE in MsgType.ADVANCED_TYPES
        )
    }

    @Test
    fun `TAKEOVER_RESPONSE is NOT in MsgType ACK_ON_RECEIPT_TYPES`() {
        assertFalse(
            "TAKEOVER_RESPONSE is uplink — device should not ack its own outbound message",
            MsgType.TAKEOVER_RESPONSE in MsgType.ACK_ON_RECEIPT_TYPES
        )
    }

    @Test
    fun `MsgType fromValue returns TAKEOVER_REQUEST for wire string`() {
        val type = MsgType.fromValue("takeover_request")
        assertNotNull("takeover_request must be a known MsgType", type)
        assertEquals(MsgType.TAKEOVER_REQUEST, type)
    }

    @Test
    fun `MsgType fromValue returns TAKEOVER_RESPONSE for wire string`() {
        val type = MsgType.fromValue("takeover_response")
        assertNotNull("takeover_response must be a known MsgType", type)
        assertEquals(MsgType.TAKEOVER_RESPONSE, type)
    }

    @Test
    fun `MsgType TAKEOVER_REQUEST wire value is takeover_request`() {
        assertEquals("takeover_request", MsgType.TAKEOVER_REQUEST.value)
    }

    @Test
    fun `MsgType TAKEOVER_RESPONSE wire value is takeover_response`() {
        assertEquals("takeover_response", MsgType.TAKEOVER_RESPONSE.value)
    }

    // ── HandoffRequest posture propagation (PR-3 gap fix) ─────────────────────

    private fun buildBridge(crossDeviceEnabled: Boolean = true): AgentRuntimeBridge {
        val settings = InMemoryAppSettings(crossDeviceEnabled = crossDeviceEnabled)
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
    fun `HandoffRequest with join_runtime posture emits correct JSON via buildBridgeJson`() {
        val bridge = buildBridge()
        // Simulates GalaxyConnectionService propagating source_runtime_posture from
        // TaskAssignPayload into HandoffRequest (the PR-3 gap fix).
        val inboundPosture = SourceRuntimePosture.JOIN_RUNTIME
        val req = AgentRuntimeBridge.HandoffRequest(
            traceId = UUID.randomUUID().toString(),
            taskId = "t-gap-fix",
            goal = "open settings",
            sourceRuntimePosture = SourceRuntimePosture.fromValue(inboundPosture)
        )
        val json = bridge.buildBridgeJson(req)
        val obj = JSONObject(json)
        assertTrue("source_runtime_posture must be present in bridge JSON", obj.has("source_runtime_posture"))
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, obj.getString("source_runtime_posture"))
    }

    @Test
    fun `HandoffRequest null posture resolves to DEFAULT via SourceRuntimePosture fromValue`() {
        // Simulates the gap fix: when TaskAssignPayload.source_runtime_posture is null,
        // GalaxyConnectionService must use SourceRuntimePosture.fromValue() which returns DEFAULT.
        val resolved = SourceRuntimePosture.fromValue(null)
        assertEquals(
            "null posture from TaskAssignPayload must resolve to control_only (DEFAULT)",
            SourceRuntimePosture.DEFAULT,
            resolved
        )
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, resolved)
    }

    @Test
    fun `HandoffRequest with control_only posture emits control_only in JSON`() {
        val bridge = buildBridge()
        val req = AgentRuntimeBridge.HandoffRequest(
            traceId = UUID.randomUUID().toString(),
            taskId = "t-ctrl",
            goal = "run query",
            sourceRuntimePosture = SourceRuntimePosture.fromValue(SourceRuntimePosture.CONTROL_ONLY)
        )
        val json = bridge.buildBridgeJson(req)
        val obj = JSONObject(json)
        assertTrue(obj.has("source_runtime_posture"))
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, obj.getString("source_runtime_posture"))
    }

    @Test
    fun `HandoffRequest posture propagated end-to-end from simulated TaskAssignPayload`() = runBlocking {
        val sentMessages = mutableListOf<String>()
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val client = object : GatewayClient {
            override fun isConnected() = true
            override fun sendJson(json: String): Boolean { sentMessages.add(json); return true }
        }
        val bridge = AgentRuntimeBridge(
            gatewayClient = client,
            settings = settings,
            metricsRecorder = MetricsRecorder(InMemoryAppSettings())
        )

        // Simulate what GalaxyConnectionService now does: propagate posture from inbound payload.
        val inboundPosture = SourceRuntimePosture.JOIN_RUNTIME // from TaskAssignPayload
        val req = AgentRuntimeBridge.HandoffRequest(
            traceId = UUID.randomUUID().toString(),
            taskId = "e2e-task",
            goal = "take screenshot",
            sourceRuntimePosture = SourceRuntimePosture.fromValue(inboundPosture)
        )
        val result = bridge.handoff(req)

        assertTrue("Handoff must succeed for eligible task", result.isHandoff)
        assertEquals(1, sentMessages.size)
        val sent = JSONObject(sentMessages[0])
        assertTrue("source_runtime_posture must be in outbound bridge_handoff", sent.has("source_runtime_posture"))
        assertEquals(
            "posture must flow from TaskAssignPayload → HandoffRequest → bridge_handoff JSON",
            SourceRuntimePosture.JOIN_RUNTIME,
            sent.getString("source_runtime_posture")
        )
    }

    @Test
    fun `HandoffRequest posture gap fix - null inbound posture resolved to control_only in JSON`() = runBlocking {
        val sentMessages = mutableListOf<String>()
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val client = object : GatewayClient {
            override fun isConnected() = true
            override fun sendJson(json: String): Boolean { sentMessages.add(json); return true }
        }
        val bridge = AgentRuntimeBridge(
            gatewayClient = client,
            settings = settings,
            metricsRecorder = MetricsRecorder(InMemoryAppSettings())
        )

        // Simulate a legacy TaskAssignPayload with no source_runtime_posture (null).
        // After the PR-3 gap fix, GalaxyConnectionService calls SourceRuntimePosture.fromValue(null)
        // which returns DEFAULT (control_only), and that is passed to HandoffRequest.
        val resolvedPosture = SourceRuntimePosture.fromValue(null) // = "control_only"
        val req = AgentRuntimeBridge.HandoffRequest(
            traceId = UUID.randomUUID().toString(),
            taskId = "legacy-task",
            goal = "check notifications",
            sourceRuntimePosture = resolvedPosture
        )
        bridge.handoff(req)

        assertEquals(1, sentMessages.size)
        val sent = JSONObject(sentMessages[0])
        assertTrue("resolved posture must appear in bridge JSON", sent.has("source_runtime_posture"))
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, sent.getString("source_runtime_posture"))
    }

    // ── MultiDeviceCoordinator posture propagation ─────────────────────────────

    @Test
    fun `dispatchParallel propagates sourceRuntimePosture to ParallelGroupResult`() = runBlocking {
        val coord = MultiDeviceCoordinator(
            deviceIds = listOf("phone-1"),
            crossDeviceEnabled = true,
            dispatch = { deviceId, subtaskId, _ ->
                MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = deviceId, success = true)
            }
        )
        val group = coord.dispatchParallel(
            goal = "open settings",
            groupId = "grp-posture",
            sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
        )
        assertEquals(
            "sourceRuntimePosture must be echoed in ParallelGroupResult",
            SourceRuntimePosture.JOIN_RUNTIME,
            group.sourceRuntimePosture
        )
    }

    @Test
    fun `dispatchParallel null posture is preserved as null in ParallelGroupResult`() = runBlocking {
        val coord = MultiDeviceCoordinator(
            deviceIds = listOf("phone-1"),
            crossDeviceEnabled = true,
            dispatch = { deviceId, subtaskId, _ ->
                MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = deviceId, success = true)
            }
        )
        val group = coord.dispatchParallel(goal = "task", groupId = "grp-null")
        assertNull(
            "null posture must be preserved as null for backwards compatibility",
            group.sourceRuntimePosture
        )
    }

    @Test
    fun `dispatchParallel blocked result also echoes sourceRuntimePosture`() = runBlocking {
        val coord = MultiDeviceCoordinator(
            deviceIds = listOf("phone-1"),
            crossDeviceEnabled = false,
            dispatch = { deviceId, subtaskId, _ ->
                MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = deviceId, success = true)
            }
        )
        val group = coord.dispatchParallel(
            goal = "blocked task",
            groupId = "grp-blocked-posture",
            sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
        )
        assertEquals(
            "blocked result must still echo sourceRuntimePosture",
            SourceRuntimePosture.CONTROL_ONLY,
            group.sourceRuntimePosture
        )
        assertFalse("blocked result must have success=false", group.allSucceeded)
    }

    @Test
    fun `dispatchParallel control_only posture echoed when source should not join runtime`() = runBlocking {
        val coord = MultiDeviceCoordinator(
            deviceIds = listOf("dev-a", "dev-b"),
            crossDeviceEnabled = true,
            dispatch = { deviceId, subtaskId, _ ->
                MultiDeviceCoordinator.SubtaskResult(subtaskId = subtaskId, deviceId = deviceId, success = true)
            }
        )
        val group = coord.dispatchParallel(
            goal = "parallel task",
            sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
        )
        assertEquals(2, group.succeededCount)
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, group.sourceRuntimePosture)
    }
}

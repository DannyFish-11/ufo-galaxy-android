package com.ufo.galaxy.network

import com.ufo.galaxy.protocol.MsgType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

/**
 * AUDIT INSPECTION — GalaxyWebSocketClient.handleMessage real dispatch chain.
 *
 * ## Purpose
 * This test exercises the PRIVATE handleMessage(String) method via reflection so that the
 * actual inbound routing logic — which maps raw WebSocket JSON to typed Listener callbacks
 * — is machine-verifiable, not just described in comments or documentation.
 *
 * Without this coverage, the entire inbound WS-to-service dispatch chain can silently
 * regress (wrong field extraction, wrong callback, missing remapping) with no automated
 * signal. No other test in the suite calls handleMessage.
 *
 * ## What this exposes
 *  - task_assign routing: taskId extracted from payload.task_id; traceId from envelope
 *  - legacy task_execute / task_status_query: remapped to onTaskAssign (compatibility gate)
 *  - goal_execution routing: dispatched to onGoalExecution
 *  - parallel_subtask routing: dispatched to onParallelSubtask
 *  - task_cancel routing: dispatched to onTaskCancel
 *  - handoff_envelope_v2 routing: dispatched to onHandoffEnvelopeV2
 *  - heartbeat_ack: no listener callbacks fire (silent ack path)
 *  - unknown message types: dispatched to onUnknownMessage (not silently dropped)
 *  - response type: dispatches payload.content to onMessage (legacy path)
 *
 * ## Known gaps exposed by this test
 *  - task_assign with blank trace_id: the listener receives null (envelope value stripped)
 *  - task_assign without a payload object: taskId defaults to ""
 *  - task_execute with no payload object: taskId falls back to top-level task_id field
 *
 * These tests will FAIL if:
 *  - dispatch routing changes field source or callback target
 *  - legacy remapping (task_execute → onTaskAssign) is removed
 *  - heartbeat_ack silently starts dispatching to a listener
 *  - unknown type falls through to a non-onUnknownMessage handler
 *
 * Tests run on the JVM; no Android framework required.
 */
class WsInboundDispatchAuditTest {

    private lateinit var client: GalaxyWebSocketClient
    private lateinit var captured: CapturingListener

    /** Reflection handle to the private handleMessage(String) method. */
    private val handleMessageMethod: Method = GalaxyWebSocketClient::class.java
        .getDeclaredMethod("handleMessage", String::class.java)
        .also { it.isAccessible = true }

    @Before
    fun setUp() {
        captured = CapturingListener()
        client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true
        )
        client.addListener(captured)
    }

    // ── task_assign ────────────────────────────────────────────────────────────

    @Test
    fun `task_assign routes to onTaskAssign with taskId from payload and traceId from envelope`() {
        dispatch(
            """
            {
              "type": "task_assign",
              "trace_id": "trace-abc",
              "correlation_id": "corr-xyz",
              "device_id": "android-1",
              "payload": {
                "task_id": "task-001",
                "goal": "open WeChat"
              }
            }
            """.trimIndent()
        )

        assertEquals("onTaskAssign must be called exactly once", 1, captured.taskAssignCalls)
        assertEquals("taskId must come from payload.task_id", "task-001", captured.lastTaskAssignId)
        assertEquals("traceId must come from envelope trace_id", "trace-abc", captured.lastTaskAssignTraceId)
        assertTrue(
            "payloadJson must contain task_id",
            captured.lastTaskAssignPayloadJson?.contains("task-001") == true
        )
        // Verify no cross-dispatch into other handlers
        assertEquals(0, captured.goalExecutionCalls)
        assertEquals(0, captured.parallelSubtaskCalls)
        assertEquals(0, captured.taskCancelCalls)
        assertEquals(0, captured.unknownMessageCalls)
    }

    @Test
    fun `task_assign with blank trace_id delivers null traceId to listener`() {
        // Blank trace_id must be stripped (takeIf { it.isNotBlank() } → null).
        // The downstream service generates a fresh UUID, so the listener should see null.
        dispatch("""{"type":"task_assign","trace_id":"","payload":{"task_id":"t1","goal":"x"}}""")

        assertEquals(1, captured.taskAssignCalls)
        assertNull(
            "Blank trace_id in envelope must arrive as null at listener",
            captured.lastTaskAssignTraceId
        )
    }

    @Test
    fun `task_assign without trace_id field delivers null traceId to listener`() {
        dispatch("""{"type":"task_assign","payload":{"task_id":"t2","goal":"y"}}""")

        assertEquals(1, captured.taskAssignCalls)
        assertNull(
            "Missing trace_id in envelope must arrive as null at listener",
            captured.lastTaskAssignTraceId
        )
    }

    @Test
    fun `task_assign without payload object defaults taskId to empty string`() {
        // When payload object is absent the router falls back to "".
        // Exposed here because a malformed gateway message should not crash the client.
        dispatch("""{"type":"task_assign","trace_id":"t-nopayload"}""")

        assertEquals(1, captured.taskAssignCalls)
        assertEquals(
            "Missing payload must default taskId to empty string",
            "",
            captured.lastTaskAssignId
        )
    }

    // ── legacy type remapping ──────────────────────────────────────────────────

    @Test
    fun `task_execute is remapped to onTaskAssign — compatibility gate must not be removed`() {
        // Legacy type task_execute must be handled via the same onTaskAssign path.
        // If this test fails, the compatibility window has been broken.
        dispatch(
            """
            {
              "type": "task_execute",
              "trace_id": "trace-legacy",
              "payload": {"task_id": "leg-001", "goal": "run something"}
            }
            """.trimIndent()
        )

        assertEquals(
            "task_execute must remap to onTaskAssign",
            1,
            captured.taskAssignCalls
        )
        assertEquals("leg-001", captured.lastTaskAssignId)
        assertEquals("trace-legacy", captured.lastTaskAssignTraceId)
        // Must not land in onUnknownMessage
        assertEquals(0, captured.unknownMessageCalls)
    }

    @Test
    fun `task_status_query is remapped to onTaskAssign — compatibility gate must not be removed`() {
        dispatch(
            """
            {
              "type": "task_status_query",
              "payload": {"task_id": "sq-001"}
            }
            """.trimIndent()
        )

        assertEquals(
            "task_status_query must remap to onTaskAssign",
            1,
            captured.taskAssignCalls
        )
        assertEquals("sq-001", captured.lastTaskAssignId)
        assertEquals(0, captured.unknownMessageCalls)
    }

    @Test
    fun `task_execute without payload object uses top-level task_id field`() {
        // When task_execute has no payload object, the router falls back to
        // json.get("task_id")?.asString. This is the documented fallback path.
        dispatch(
            """{"type":"task_execute","task_id":"top-level-id","trace_id":"t-nopayload"}"""
        )

        assertEquals(1, captured.taskAssignCalls)
        assertEquals(
            "task_execute with no payload must use top-level task_id",
            "top-level-id",
            captured.lastTaskAssignId
        )
    }

    // ── goal_execution ─────────────────────────────────────────────────────────

    @Test
    fun `goal_execution routes to onGoalExecution with correct fields`() {
        dispatch(
            """
            {
              "type": "goal_execution",
              "trace_id": "trace-goal",
              "payload": {"task_id": "goal-001", "goal": "open Maps", "group_id": "grp-1"}
            }
            """.trimIndent()
        )

        assertEquals(1, captured.goalExecutionCalls)
        assertEquals("goal-001", captured.lastGoalTaskId)
        assertEquals("trace-goal", captured.lastGoalTraceId)
        // Verify exclusive routing
        assertEquals(0, captured.taskAssignCalls)
        assertEquals(0, captured.parallelSubtaskCalls)
        assertEquals(0, captured.unknownMessageCalls)
    }

    // ── parallel_subtask ───────────────────────────────────────────────────────

    @Test
    fun `parallel_subtask routes to onParallelSubtask with correct fields`() {
        dispatch(
            """
            {
              "type": "parallel_subtask",
              "trace_id": "trace-sub",
              "payload": {
                "task_id": "sub-001",
                "goal": "subtask A",
                "group_id": "grp-1",
                "subtask_index": 0
              }
            }
            """.trimIndent()
        )

        assertEquals(1, captured.parallelSubtaskCalls)
        assertEquals("sub-001", captured.lastSubtaskId)
        assertEquals("trace-sub", captured.lastSubtaskTraceId)
        assertEquals(0, captured.taskAssignCalls)
        assertEquals(0, captured.goalExecutionCalls)
    }

    // ── task_cancel ────────────────────────────────────────────────────────────

    @Test
    fun `task_cancel routes to onTaskCancel with correct taskId`() {
        dispatch("""{"type":"task_cancel","payload":{"task_id":"cancel-001"}}""")

        assertEquals(1, captured.taskCancelCalls)
        assertEquals("cancel-001", captured.lastCancelTaskId)
        assertEquals(0, captured.taskAssignCalls)
    }

    // ── handoff_envelope_v2 ────────────────────────────────────────────────────

    @Test
    fun `handoff_envelope_v2 routes to onHandoffEnvelopeV2 before ADVANCED_TYPES fallback`() {
        // handoff_envelope_v2 must have its own dispatch path; it must not fall
        // into the ADVANCED_TYPES catch-all or onUnknownMessage.
        dispatch(
            """
            {
              "type": "handoff_envelope_v2",
              "trace_id": "trace-hv2",
              "payload": {"task_id": "hv2-001"}
            }
            """.trimIndent()
        )

        assertEquals(1, captured.handoffEnvelopeV2Calls)
        assertEquals("hv2-001", captured.lastHandoffTaskId)
        assertEquals("trace-hv2", captured.lastHandoffTraceId)
        assertEquals(0, captured.taskAssignCalls)
        assertEquals(0, captured.unknownMessageCalls)
    }

    @Test
    fun `handoff_envelope_v2 trace_id falls back to payload trace_id when envelope has none`() {
        // The code checks envelope trace_id first, then payload.trace_id as fallback.
        dispatch(
            """
            {
              "type": "handoff_envelope_v2",
              "payload": {"task_id": "hv2-002", "trace_id": "payload-trace"}
            }
            """.trimIndent()
        )

        assertEquals(1, captured.handoffEnvelopeV2Calls)
        assertEquals(
            "trace_id must fall back to payload.trace_id when absent in envelope",
            "payload-trace",
            captured.lastHandoffTraceId
        )
    }

    // ── heartbeat_ack — no listener dispatch ───────────────────────────────────

    @Test
    fun `heartbeat_ack fires NO listener callbacks — silent ack path`() {
        // heartbeat_ack is handled in the WS client itself (log only).
        // It must NOT trigger any Listener method.
        dispatch("""{"type":"heartbeat_ack","timestamp":1700000000000}""")

        assertEquals("heartbeat_ack must not call onTaskAssign",     0, captured.taskAssignCalls)
        assertEquals("heartbeat_ack must not call onGoalExecution",  0, captured.goalExecutionCalls)
        assertEquals("heartbeat_ack must not call onMessage",        0, captured.messageCalls)
        assertEquals("heartbeat_ack must not call onUnknownMessage", 0, captured.unknownMessageCalls)
        assertEquals("heartbeat_ack must not call onError",          0, captured.errorCalls)
    }

    // ── response (legacy) → onMessage ─────────────────────────────────────────

    @Test
    fun `response type dispatches payload content to onMessage`() {
        dispatch("""{"type":"response","payload":{"content":"hello from gateway"}}""")

        assertEquals(1, captured.messageCalls)
        assertEquals("hello from gateway", captured.lastMessage)
        assertEquals(0, captured.taskAssignCalls)
        assertEquals(0, captured.unknownMessageCalls)
    }

    // ── error type → onError ───────────────────────────────────────────────────

    @Test
    fun `error type dispatches message field to onError`() {
        dispatch("""{"type":"error","message":"bad request"}""")

        assertEquals(1, captured.errorCalls)
        assertEquals("bad request", captured.lastError)
        assertEquals(0, captured.taskAssignCalls)
        assertEquals(0, captured.unknownMessageCalls)
    }

    // ── unknown type → onUnknownMessage ───────────────────────────────────────

    @Test
    fun `completely unknown type routes to onUnknownMessage — not silently dropped`() {
        // This asserts the last-resort fallback is present and reachable.
        // If this fails, unknown messages are silently dropped (a silent failure mode).
        dispatch("""{"type":"not_a_real_type_xyz_audit","payload":{}}""")

        assertEquals(
            "unknown type must call onUnknownMessage — not silently dropped",
            1,
            captured.unknownMessageCalls
        )
        assertEquals("not_a_real_type_xyz_audit", captured.lastUnknownType)
        assertEquals(0, captured.taskAssignCalls)
        assertEquals(0, captured.goalExecutionCalls)
    }

    @Test
    fun `message with null type field routes to onUnknownMessage`() {
        dispatch("""{"payload":{"task_id":"t-null"}}""")

        // No "type" key — null type must still go to onUnknownMessage, not crash.
        assertEquals(
            "null type must not crash; must route to onUnknownMessage",
            1,
            captured.unknownMessageCalls
        )
        assertNull(captured.lastUnknownType)
    }

    // ── ADVANCED_TYPES → onAdvancedMessage ─────────────────────────────────────

    @Test
    fun `relay (ADVANCED_TYPES member) routes to onAdvancedMessage not onUnknownMessage`() {
        // Types in MsgType.ADVANCED_TYPES that do NOT have a dedicated when-branch
        // must go to onAdvancedMessage, not fall into onUnknownMessage.
        dispatch(
            """
            {
              "type": "relay",
              "message_id": "msg-relay-001",
              "payload": {}
            }
            """.trimIndent()
        )

        assertEquals(
            "relay must call onAdvancedMessage",
            1,
            captured.advancedMessageCalls
        )
        assertEquals(MsgType.RELAY, captured.lastAdvancedType)
        assertEquals("msg-relay-001", captured.lastAdvancedMessageId)
        // Must NOT fall into the unknown handler
        assertEquals(0, captured.unknownMessageCalls)
        assertEquals(0, captured.taskAssignCalls)
    }

    @Test
    fun `handoff_envelope_v2 routes to onHandoffEnvelopeV2 not to onAdvancedMessage`() {
        // handoff_envelope_v2 IS listed in ADVANCED_TYPES but has an explicit when-branch
        // that fires BEFORE the else-fallback. This test verifies the explicit branch wins.
        dispatch(
            """
            {
              "type": "handoff_envelope_v2",
              "trace_id": "trace-priority",
              "payload": {"task_id": "priority-001"}
            }
            """.trimIndent()
        )

        assertEquals(
            "handoff_envelope_v2 must use dedicated onHandoffEnvelopeV2 path",
            1,
            captured.handoffEnvelopeV2Calls
        )
        assertEquals(
            "handoff_envelope_v2 must NOT call onAdvancedMessage",
            0,
            captured.advancedMessageCalls
        )
    }

    // ── multiple listeners ─────────────────────────────────────────────────────

    @Test
    fun `task_assign is delivered to all registered listeners`() {
        val second = CapturingListener()
        client.addListener(second)

        dispatch("""{"type":"task_assign","payload":{"task_id":"multi-001","goal":"x"}}""")

        assertEquals("first listener must receive task_assign",  1, captured.taskAssignCalls)
        assertEquals("second listener must receive task_assign", 1, second.taskAssignCalls)
        assertEquals("multi-001", captured.lastTaskAssignId)
        assertEquals("multi-001", second.lastTaskAssignId)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Invokes the private handleMessage method on [client]. */
    private fun dispatch(rawJson: String) {
        handleMessageMethod.invoke(client, rawJson)
    }

    /**
     * Listener that records all callback invocations for assertion.
     *
     * Each field tracks the call count and the last argument received so tests can
     * assert both that the right method was called and that the right data arrived.
     */
    private class CapturingListener : GalaxyWebSocketClient.Listener {
        var connectedCalls = 0
        var disconnectedCalls = 0

        var messageCalls = 0
        var lastMessage: String? = null

        var errorCalls = 0
        var lastError: String? = null

        var taskAssignCalls       = 0
        var lastTaskAssignId      : String? = null
        var lastTaskAssignPayloadJson: String? = null
        var lastTaskAssignTraceId : String? = null

        var goalExecutionCalls  = 0
        var lastGoalTaskId      : String? = null
        var lastGoalTraceId     : String? = null

        var parallelSubtaskCalls  = 0
        var lastSubtaskId         : String? = null
        var lastSubtaskTraceId    : String? = null

        var taskCancelCalls   = 0
        var lastCancelTaskId  : String? = null

        var unknownMessageCalls = 0
        var lastUnknownType     : String? = null

        var handoffEnvelopeV2Calls = 0
        var lastHandoffTaskId      : String? = null
        var lastHandoffTraceId     : String? = null

        var advancedMessageCalls = 0
        var lastAdvancedType     : MsgType? = null
        var lastAdvancedMessageId: String?  = null

        override fun onConnected()              { connectedCalls++ }
        override fun onDisconnected()           { disconnectedCalls++ }
        override fun onMessage(message: String) { messageCalls++; lastMessage = message }
        override fun onError(error: String)     { errorCalls++; lastError = error }

        override fun onTaskAssign(taskId: String, taskAssignPayloadJson: String, traceId: String?) {
            taskAssignCalls++
            lastTaskAssignId           = taskId
            lastTaskAssignPayloadJson  = taskAssignPayloadJson
            lastTaskAssignTraceId      = traceId
        }

        override fun onGoalExecution(taskId: String, goalPayloadJson: String, traceId: String?) {
            goalExecutionCalls++
            lastGoalTaskId  = taskId
            lastGoalTraceId = traceId
        }

        override fun onParallelSubtask(taskId: String, subtaskPayloadJson: String, traceId: String?) {
            parallelSubtaskCalls++
            lastSubtaskId       = taskId
            lastSubtaskTraceId  = traceId
        }

        override fun onTaskCancel(taskId: String, cancelPayloadJson: String) {
            taskCancelCalls++
            lastCancelTaskId = taskId
        }

        override fun onUnknownMessage(rawType: String?, rawJson: String) {
            unknownMessageCalls++
            lastUnknownType = rawType
        }

        override fun onHandoffEnvelopeV2(taskId: String, envelopePayloadJson: String, traceId: String?) {
            handoffEnvelopeV2Calls++
            lastHandoffTaskId  = taskId
            lastHandoffTraceId = traceId
        }

        override fun onAdvancedMessage(type: MsgType, messageId: String?, rawJson: String) {
            advancedMessageCalls++
            lastAdvancedType      = type
            lastAdvancedMessageId = messageId
        }
    }
}

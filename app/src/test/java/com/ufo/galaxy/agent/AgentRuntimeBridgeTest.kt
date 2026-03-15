package com.ufo.galaxy.agent

import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.network.GatewayClient
import com.ufo.galaxy.observability.MetricsRecorder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [AgentRuntimeBridge] covering:
 *  - Happy path: cross-device ON, eligible task → handoff_sent.
 *  - OFF path regression: cross-device OFF → local execution, no bridge message sent.
 *  - exec_mode=local guard: bridge is skipped even when cross-device is ON.
 *  - Idempotency: repeated calls with the same trace_id return the cached result.
 *  - Timeout / fallback: send always fails → all retries exhausted → fallback result.
 *  - Retry telemetry: metrics counters updated correctly for success and fallback.
 *  - buildBridgeJson: JSON payload contains all required metadata fields.
 *
 * All tests run on the JVM without Android framework dependencies.
 */
class AgentRuntimeBridgeTest {

    // ── Fake GatewayClient implementations ───────────────────────────────────

    /** Always reports connected; [sendJson] always succeeds. */
    private class AlwaysConnectedClient : GatewayClient {
        val sentMessages = mutableListOf<String>()
        override fun isConnected() = true
        override fun sendJson(json: String): Boolean {
            sentMessages.add(json)
            return true
        }
    }

    /** Always reports connected; [sendJson] always fails (simulates send error). */
    private class AlwaysFailingClient : GatewayClient {
        val callCount = AtomicInteger(0)
        override fun isConnected() = true
        override fun sendJson(json: String): Boolean {
            callCount.incrementAndGet()
            return false
        }
    }

    /** Always disconnected. */
    private class DisconnectedClient : GatewayClient {
        override fun isConnected() = false
        override fun sendJson(json: String) = false
    }

    // ── Fake MetricsRecorder ──────────────────────────────────────────────────

    /**
     * Thin MetricsRecorder that avoids the OkHttpClient dependency (networking).
     * Uses a no-op AppSettings so no HTTP posts are made.
     */
    private fun buildMetrics(): MetricsRecorder =
        MetricsRecorder(InMemoryAppSettings())

    // ── Builder helpers ───────────────────────────────────────────────────────

    private fun buildBridge(
        client: GatewayClient = AlwaysConnectedClient(),
        crossDeviceEnabled: Boolean = true,
        metrics: MetricsRecorder = buildMetrics()
    ): AgentRuntimeBridge {
        val settings = InMemoryAppSettings(crossDeviceEnabled = crossDeviceEnabled)
        return AgentRuntimeBridge(
            gatewayClient = client,
            settings = settings,
            metricsRecorder = metrics
        )
    }

    private fun buildRequest(
        execMode: String = AgentRuntimeBridge.EXEC_MODE_REMOTE,
        traceId: String = UUID.randomUUID().toString()
    ) = AgentRuntimeBridge.HandoffRequest(
        traceId = traceId,
        taskId = "task-${UUID.randomUUID()}",
        goal = "open WeChat",
        execMode = execMode,
        routeMode = AgentRuntimeBridge.ROUTE_MODE_CROSS_DEVICE,
        capability = "task_execution"
    )

    // ── OFF path: cross-device switch OFF ─────────────────────────────────────

    @Test
    fun `handoff returns isHandoff=false when crossDeviceEnabled is false`() = runBlocking {
        val bridge = buildBridge(crossDeviceEnabled = false)
        val result = bridge.handoff(buildRequest())
        assertFalse("Bridge must not hand off when cross-device is OFF", result.isHandoff)
        assertEquals(AgentRuntimeBridge.STATUS_LOCAL, result.status)
    }

    @Test
    fun `handoff does not send any WS message when cross-device is OFF`() = runBlocking {
        val client = AlwaysConnectedClient()
        val bridge = buildBridge(client = client, crossDeviceEnabled = false)
        bridge.handoff(buildRequest())
        assertTrue("No WS messages must be sent when cross-device is OFF", client.sentMessages.isEmpty())
    }

    @Test
    fun `handoff returns isHandoff=false for exec_mode=local even when cross-device is ON`() = runBlocking {
        val bridge = buildBridge(crossDeviceEnabled = true)
        val result = bridge.handoff(buildRequest(execMode = AgentRuntimeBridge.EXEC_MODE_LOCAL))
        assertFalse("Bridge must skip EXEC_MODE_LOCAL tasks", result.isHandoff)
        assertEquals(AgentRuntimeBridge.STATUS_LOCAL, result.status)
    }

    @Test
    fun `handoff does not send WS message for exec_mode=local`() = runBlocking {
        val client = AlwaysConnectedClient()
        val bridge = buildBridge(client = client, crossDeviceEnabled = true)
        bridge.handoff(buildRequest(execMode = AgentRuntimeBridge.EXEC_MODE_LOCAL))
        assertTrue("No WS messages for exec_mode=local", client.sentMessages.isEmpty())
    }

    // ── Happy path: cross-device ON, eligible task ────────────────────────────

    @Test
    fun `handoff returns isHandoff=true when cross-device ON and exec_mode=remote`() = runBlocking {
        val bridge = buildBridge(crossDeviceEnabled = true)
        val result = bridge.handoff(buildRequest(execMode = AgentRuntimeBridge.EXEC_MODE_REMOTE))
        assertTrue("Bridge should hand off EXEC_MODE_REMOTE tasks", result.isHandoff)
        assertEquals(AgentRuntimeBridge.STATUS_HANDOFF_SENT, result.status)
    }

    @Test
    fun `handoff sends WS message when cross-device ON and eligible`() = runBlocking {
        val client = AlwaysConnectedClient()
        val bridge = buildBridge(client = client, crossDeviceEnabled = true)
        bridge.handoff(buildRequest())
        assertEquals("Exactly one bridge_handoff message should be sent", 1, client.sentMessages.size)
    }

    @Test
    fun `handoff returns isHandoff=true for exec_mode=both`() = runBlocking {
        val bridge = buildBridge(crossDeviceEnabled = true)
        val result = bridge.handoff(buildRequest(execMode = AgentRuntimeBridge.EXEC_MODE_BOTH))
        assertTrue("EXEC_MODE_BOTH should be handed off", result.isHandoff)
    }

    @Test
    fun `handoff result carries the correct trace_id and task_id`() = runBlocking {
        val traceId = "trace-abc-123"
        val bridge = buildBridge(crossDeviceEnabled = true)
        val request = buildRequest(traceId = traceId)
        val result = bridge.handoff(request)
        assertEquals("trace_id must be preserved in result", traceId, result.traceId)
        assertEquals("task_id must be preserved in result", request.taskId, result.taskId)
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    fun `handoff with same trace_id returns identical cached result`() = runBlocking {
        val client = AlwaysConnectedClient()
        val bridge = buildBridge(client = client, crossDeviceEnabled = true)
        val request = buildRequest()

        val first = bridge.handoff(request)
        val second = bridge.handoff(request)

        assertEquals("Cached status must match first result", first.status, second.status)
        assertEquals("Cached isHandoff must match first result", first.isHandoff, second.isHandoff)
        // Only one WS message should have been sent (idempotent — no re-send).
        assertEquals("Bridge message must be sent exactly once (idempotent)", 1, client.sentMessages.size)
    }

    @Test
    fun `handoff with different trace_ids sends separate messages`() = runBlocking {
        val client = AlwaysConnectedClient()
        val bridge = buildBridge(client = client, crossDeviceEnabled = true)

        bridge.handoff(buildRequest(traceId = "trace-1"))
        bridge.handoff(buildRequest(traceId = "trace-2"))

        assertEquals("Two distinct trace_ids should produce two WS messages", 2, client.sentMessages.size)
    }

    @Test
    fun `idempotent cached result for OFF path is also stable`() = runBlocking {
        // Even when cross-device is OFF, the cached result is returned on repeated calls.
        val bridge = buildBridge(crossDeviceEnabled = false)
        val request = buildRequest()
        val first = bridge.handoff(request)
        val second = bridge.handoff(request)
        assertEquals(first.status, second.status)
        assertEquals(first.isHandoff, second.isHandoff)
    }

    // ── Timeout / fallback ────────────────────────────────────────────────────

    @Test
    fun `handoff returns isHandoff=false when send always fails (fallback)`() = runBlocking {
        val client = AlwaysFailingClient()
        val metrics = buildMetrics()
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val bridge = AgentRuntimeBridge(
            gatewayClient = client,
            settings = settings,
            metricsRecorder = metrics
        )

        val result = bridge.handoff(buildRequest())
        assertFalse("All retries exhausted → must fall back", result.isHandoff)
        assertEquals(AgentRuntimeBridge.STATUS_FALLBACK, result.status)
        assertNotNull("Error must be set on fallback", result.error)
    }

    @Test
    fun `handoff fallback increments MetricsRecorder handoffFailures counter`() = runBlocking {
        val client = AlwaysFailingClient()
        val metrics = buildMetrics()
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val bridge = AgentRuntimeBridge(
            gatewayClient = client,
            settings = settings,
            metricsRecorder = metrics
        )

        bridge.handoff(buildRequest())

        assertTrue("handoffFailures must be incremented on fallback", metrics.handoffFailures.get() > 0)
        assertTrue("handoffFallbacks must be incremented on fallback", metrics.handoffFallbacks.get() > 0)
    }

    @Test
    fun `handoff success increments MetricsRecorder handoffSuccesses counter`() = runBlocking {
        val metrics = buildMetrics()
        val bridge = buildBridge(metrics = metrics, crossDeviceEnabled = true)

        bridge.handoff(buildRequest())

        assertEquals("handoffSuccesses must be 1 after success", 1, metrics.handoffSuccesses.get())
        assertEquals("handoffFailures must remain 0 after success", 0, metrics.handoffFailures.get())
        assertEquals("handoffFallbacks must remain 0 after success", 0, metrics.handoffFallbacks.get())
    }

    @Test
    fun `handoff OFF path does not touch MetricsRecorder handoff counters`() = runBlocking {
        val metrics = buildMetrics()
        val bridge = buildBridge(metrics = metrics, crossDeviceEnabled = false)

        bridge.handoff(buildRequest())

        assertEquals("No handoff success counter change for OFF path", 0, metrics.handoffSuccesses.get())
        assertEquals("No handoff failure counter change for OFF path", 0, metrics.handoffFailures.get())
        assertEquals("No handoff fallback counter change for OFF path", 0, metrics.handoffFallbacks.get())
    }

    // ── buildBridgeJson: JSON payload structure ───────────────────────────────

    @Test
    fun `buildBridgeJson contains required fields`() {
        val bridge = buildBridge()
        val request = AgentRuntimeBridge.HandoffRequest(
            traceId = "trace-xyz",
            taskId = "task-abc",
            goal = "open WeChat",
            execMode = AgentRuntimeBridge.EXEC_MODE_REMOTE,
            routeMode = AgentRuntimeBridge.ROUTE_MODE_CROSS_DEVICE,
            capability = "task_execution",
            sessionId = "session-001",
            context = mapOf("locale" to "zh-CN"),
            constraints = listOf("no audio")
        )
        val json = bridge.buildBridgeJson(request)
        val obj = org.json.JSONObject(json)

        assertEquals("bridge_handoff", obj.getString("type"))
        assertEquals("trace-xyz", obj.getString("trace_id"))
        assertEquals("task-abc", obj.getString("task_id"))
        assertEquals(AgentRuntimeBridge.EXEC_MODE_REMOTE, obj.getString("exec_mode"))
        assertEquals(AgentRuntimeBridge.ROUTE_MODE_CROSS_DEVICE, obj.getString("route_mode"))
        assertEquals("open WeChat", obj.getString("goal"))
        assertEquals("task_execution", obj.getString("capability"))
        assertEquals("session-001", obj.getString("session_id"))
        assertTrue("context field must be present", obj.has("context"))
        assertTrue("constraints field must be present", obj.has("constraints"))
    }

    @Test
    fun `buildBridgeJson omits optional fields when blank or empty`() {
        val bridge = buildBridge()
        val request = AgentRuntimeBridge.HandoffRequest(
            traceId = "t",
            taskId = "t",
            goal = "do something"
            // capability, sessionId, context, constraints all use defaults
        )
        val json = bridge.buildBridgeJson(request)
        val obj = org.json.JSONObject(json)

        assertFalse("capability must be absent when null", obj.has("capability"))
        assertFalse("session_id must be absent when null", obj.has("session_id"))
        assertFalse("context must be absent when empty", obj.has("context"))
        assertFalse("constraints must be absent when empty", obj.has("constraints"))
    }

    @Test
    fun `buildBridgeJson type is always bridge_handoff`() {
        val bridge = buildBridge()
        val json = bridge.buildBridgeJson(buildRequest())
        val obj = org.json.JSONObject(json)
        assertEquals(AgentRuntimeBridge.MSG_TYPE_BRIDGE_HANDOFF, obj.getString("type"))
    }

    // ── AipMessage trace_id + route_mode propagation ──────────────────────────

    @Test
    fun `AipMessage accepts trace_id and route_mode fields`() {
        val msg = com.ufo.galaxy.protocol.AipMessage(
            type = com.ufo.galaxy.protocol.MsgType.TASK_RESULT,
            payload = mapOf("status" to "success"),
            correlation_id = "task-1",
            device_id = "phone-1",
            trace_id = "trace-abc",
            route_mode = AgentRuntimeBridge.ROUTE_MODE_CROSS_DEVICE
        )
        assertEquals("trace-abc", msg.trace_id)
        assertEquals(AgentRuntimeBridge.ROUTE_MODE_CROSS_DEVICE, msg.route_mode)
    }

    @Test
    fun `AipMessage trace_id and route_mode default to null for backward compat`() {
        val msg = com.ufo.galaxy.protocol.AipMessage(
            type = com.ufo.galaxy.protocol.MsgType.HEARTBEAT,
            payload = emptyMap<String, Any>()
        )
        assertNull("trace_id must default to null for backward compat", msg.trace_id)
        assertNull("route_mode must default to null for backward compat", msg.route_mode)
    }

    // ── MetricsRecorder handoff counters ──────────────────────────────────────

    @Test
    fun `MetricsRecorder records handoff success correctly`() {
        val metrics = buildMetrics()
        assertEquals(0, metrics.handoffSuccesses.get())
        metrics.recordHandoffSuccess()
        assertEquals(1, metrics.handoffSuccesses.get())
        metrics.recordHandoffSuccess()
        assertEquals(2, metrics.handoffSuccesses.get())
    }

    @Test
    fun `MetricsRecorder records handoff failure correctly`() {
        val metrics = buildMetrics()
        assertEquals(0, metrics.handoffFailures.get())
        metrics.recordHandoffFailure()
        assertEquals(1, metrics.handoffFailures.get())
    }

    @Test
    fun `MetricsRecorder records handoff fallback correctly`() {
        val metrics = buildMetrics()
        assertEquals(0, metrics.handoffFallbacks.get())
        metrics.recordHandoffFallback()
        assertEquals(1, metrics.handoffFallbacks.get())
    }

    @Test
    fun `MetricsRecorder snapshot includes handoff counters`() {
        val metrics = buildMetrics()
        metrics.recordHandoffSuccess()
        metrics.recordHandoffSuccess()
        metrics.recordHandoffFailure()
        metrics.recordHandoffFallback()
        val snap = metrics.snapshot()
        assertEquals(2, snap.getInt("handoff_successes"))
        assertEquals(1, snap.getInt("handoff_failures"))
        assertEquals(1, snap.getInt("handoff_fallbacks"))
    }
}

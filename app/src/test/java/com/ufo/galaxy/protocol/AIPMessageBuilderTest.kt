package com.ufo.galaxy.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AIPMessageBuilder].
 *
 * These tests validate message building, inbound message parsing, and
 * protocol detection without requiring an Android device or emulator.
 */
class AIPMessageBuilderTest {

    // ──────────────────────────────────────────────────────────────────────
    // build()
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `build produces all required AIP-1-0 fields`() {
        val payload = JSONObject().apply { put("cmd", "test") }
        val msg = AIPMessageBuilder.build(
            messageType = "command",
            sourceNodeId = "device_1",
            targetNodeId = "Node_50",
            payload = payload
        )

        assertEquals(AIPMessageBuilder.PROTOCOL_AIP1, msg.getString("protocol"))
        assertEquals("command", msg.getString("type"))
        assertEquals("device_1", msg.getString("source_node"))
        assertEquals("Node_50", msg.getString("target_node"))
        assertTrue("timestamp should be positive", msg.getLong("timestamp") > 0)
        assertEquals("test", msg.getJSONObject("payload").getString("cmd"))
    }

    @Test
    fun `build includes v3-compatible fields by default`() {
        val msg = AIPMessageBuilder.build(
            messageType = "registration",
            sourceNodeId = "dev_42",
            targetNodeId = "Galaxy",
            payload = JSONObject()
        )

        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
        assertEquals("dev_42", msg.getString("device_id"))
        assertEquals("Android_Agent", msg.getString("device_type"))
    }

    @Test
    fun `build always includes v3 fields regardless of caller intent`() {
        // v3 fields (version, device_id, device_type) are mandatory in all outbound
        // messages and cannot be suppressed.
        val msg = AIPMessageBuilder.build(
            messageType = "heartbeat",
            sourceNodeId = "dev_1",
            targetNodeId = "Galaxy",
            payload = JSONObject()
        )

        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
        assertEquals("dev_1", msg.getString("device_id"))
        assertEquals("Android_Agent", msg.getString("device_type"))
    }

    @Test
    fun `build respects custom deviceType`() {
        val msg = AIPMessageBuilder.build(
            messageType = "registration",
            sourceNodeId = "dev_1",
            targetNodeId = "Galaxy",
            payload = JSONObject(),
            deviceType = "Custom_Device"
        )

        assertEquals("Custom_Device", msg.getString("device_type"))
    }

    @Test
    fun `build includes message_id field`() {
        val msg = AIPMessageBuilder.build(
            messageType = "command",
            sourceNodeId = "device_1",
            targetNodeId = "Node_50",
            payload = JSONObject()
        )

        assertTrue("message_id should be present", msg.has("message_id"))
        assertTrue("message_id should be non-empty", msg.getString("message_id").isNotEmpty())
    }

    @Test
    fun `build respects custom messageId`() {
        val customId = "custom_abc"
        val msg = AIPMessageBuilder.build(
            messageType = "command",
            sourceNodeId = "device_1",
            targetNodeId = "Node_50",
            payload = JSONObject(),
            messageId = customId
        )

        assertEquals(customId, msg.getString("message_id"))
    }

    @Test
    fun `build generates distinct message_ids by default`() {
        val msg1 = AIPMessageBuilder.build("heartbeat", "d1", "s", JSONObject())
        val msg2 = AIPMessageBuilder.build("heartbeat", "d1", "s", JSONObject())

        assertTrue(
            "consecutive calls should produce different message_ids",
            msg1.getString("message_id") != msg2.getString("message_id")
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // parse()
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `parse returns null for invalid JSON`() {
        assertNull(AIPMessageBuilder.parse("not json"))
        assertNull(AIPMessageBuilder.parse(""))
    }

    @Test
    fun `parse returns AIP-1-0 message unchanged`() {
        val original = JSONObject().apply {
            put("protocol", "AIP/1.0")
            put("type", "command")
            put("source_node", "android_1")
            put("target_node", "Galaxy")
            put("timestamp", 1700000000L)
            put("payload", JSONObject().apply { put("x", 1) })
        }
        val parsed = AIPMessageBuilder.parse(original.toString())

        assertNotNull(parsed)
        assertEquals("command", parsed!!.getString("type"))
        assertEquals("android_1", parsed.getString("source_node"))
    }

    @Test
    fun `parse normalises Microsoft Galaxy format`() {
        val microsoft = JSONObject().apply {
            put("message_type", "COMMAND")
            put("agent_id", "galaxy_server")
            put("session_id", 1700000000000L)
            put("payload", JSONObject().apply { put("action", "tap") })
        }
        val parsed = AIPMessageBuilder.parse(microsoft.toString())

        assertNotNull(parsed)
        assertEquals(AIPMessageBuilder.PROTOCOL_AIP1, parsed!!.getString("protocol"))
        assertEquals("command", parsed.getString("type"))
        assertEquals("galaxy_server", parsed.getString("source_node"))
        assertEquals("tap", parsed.getJSONObject("payload").getString("action"))
    }

    @Test
    fun `parse normalises v3 format`() {
        val v3 = JSONObject().apply {
            put("version", "3.0")
            put("msg_type", "heartbeat")
            put("device_id", "server_node")
            put("timestamp", 1700000000L)
            put("payload", JSONObject().apply { put("status", "ok") })
        }
        val parsed = AIPMessageBuilder.parse(v3.toString())

        assertNotNull(parsed)
        assertEquals(AIPMessageBuilder.PROTOCOL_AIP1, parsed!!.getString("protocol"))
        assertEquals("heartbeat", parsed.getString("type"))
        assertEquals("server_node", parsed.getString("source_node"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // detectProtocol()
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `detectProtocol identifies AIP-1-0`() {
        val msg = JSONObject().apply {
            put("protocol", "AIP/1.0")
            put("type", "command")
        }.toString()
        assertEquals("AIP/1.0", AIPMessageBuilder.detectProtocol(msg))
    }

    @Test
    fun `detectProtocol identifies v3`() {
        val msg = JSONObject().apply {
            put("version", "3.0")
            put("msg_type", "command")
        }.toString()
        assertEquals("v3", AIPMessageBuilder.detectProtocol(msg))
    }

    @Test
    fun `detectProtocol identifies Microsoft format`() {
        val msg = JSONObject().apply {
            put("message_type", "TASK")
            put("agent_id", "galaxy")
        }.toString()
        assertEquals("Microsoft", AIPMessageBuilder.detectProtocol(msg))
    }

    @Test
    fun `detectProtocol returns unknown for unrecognised message`() {
        assertEquals("unknown", AIPMessageBuilder.detectProtocol("""{"foo":"bar"}"""))
        assertEquals("unknown", AIPMessageBuilder.detectProtocol("bad json"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // Inbound normalization – DeviceCommunication scenarios
    // These mirror the wire formats the Galaxy backend may send so that
    // DeviceCommunication.handleMessage() can be validated end-to-end.
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `parse normalises inbound command message from Microsoft Galaxy TASK format`() {
        val microsoftCommand = JSONObject().apply {
            put("message_type", "TASK")
            put("agent_id", "galaxy_orchestrator")
            put("session_id", 1700000060000L)
            put("payload", JSONObject().apply {
                put("action", "click")
                put("x", 100)
                put("y", 200)
            })
        }
        val parsed = AIPMessageBuilder.parse(microsoftCommand.toString())

        assertNotNull(parsed)
        assertEquals(AIPMessageBuilder.PROTOCOL_AIP1, parsed!!.getString("protocol"))
        // Microsoft "TASK" is lowercased to "task" then mapped through normalise
        assertEquals("task", parsed.getString("type"))
        assertEquals("galaxy_orchestrator", parsed.getString("source_node"))
        // payload should be preserved
        val payload = parsed.getJSONObject("payload")
        assertEquals("click", payload.getString("action"))
    }

    @Test
    fun `parse normalises inbound status request from v3 format`() {
        val v3Status = JSONObject().apply {
            put("version", "3.0")
            put("msg_type", "status_request")
            put("device_id", "server_node_95")
            put("timestamp", 1700001000L)
            put("payload", JSONObject().apply { put("detail", "full") })
        }
        val parsed = AIPMessageBuilder.parse(v3Status.toString())

        assertNotNull(parsed)
        assertEquals("status_request", parsed!!.getString("type"))
        assertEquals("server_node_95", parsed.getString("source_node"))
        assertEquals(1700001000L, parsed.getLong("timestamp"))
    }

    @Test
    fun `parse preserves all AIP-1-0 fields for ack command messages`() {
        val ackMsg = JSONObject().apply {
            put("protocol", "AIP/1.0")
            put("type", "ack")
            put("action", "register")
            put("source_node", "server")
            put("target_node", "android_test")
            put("message_id", "msg_ack_001")
            put("timestamp", 1700002000L)
            put("payload", JSONObject().apply { put("status", "ok") })
        }
        val parsed = AIPMessageBuilder.parse(ackMsg.toString())

        assertNotNull(parsed)
        assertEquals("ack", parsed!!.getString("type"))
        assertEquals("register", parsed.optString("action"))
        assertEquals("msg_ack_001", parsed.getString("message_id"))
    }

    @Test
    fun `build registration message includes device_id capabilities and message_id`() {
        val capabilities = org.json.JSONArray().apply {
            put("screen"); put("touch"); put("camera")
        }
        val payload = JSONObject().apply {
            put("device_id", "android_test123")
            put("device_type", "android")
            put("capabilities", capabilities)
        }
        val msg = AIPMessageBuilder.build(
            messageType = AIPMessageBuilder.MessageType.DEVICE_REGISTER,
            sourceNodeId = "android_test123",
            targetNodeId = "server",
            payload = payload,
            deviceType = "Android_Agent"
        )

        assertEquals(AIPMessageBuilder.MessageType.DEVICE_REGISTER, msg.getString("type"))
        assertEquals("android_test123", msg.getString("device_id"))
        assertEquals("Android_Agent", msg.getString("device_type"))
        assertTrue("message_id should be present", msg.has("message_id"))
        assertTrue("timestamp should be positive", msg.getLong("timestamp") > 0)
        assertEquals("3.0", msg.getString("version"))
        val msgPayload = msg.getJSONObject("payload")
        assertEquals("android_test123", msgPayload.getString("device_id"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // MessageType constants
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `MessageType DEVICE_REGISTER equals device_register`() {
        assertEquals("device_register", AIPMessageBuilder.MessageType.DEVICE_REGISTER)
    }

    @Test
    fun `MessageType HEARTBEAT equals heartbeat`() {
        assertEquals("heartbeat", AIPMessageBuilder.MessageType.HEARTBEAT)
    }

    @Test
    fun `MessageType CAPABILITY_REPORT equals capability_report`() {
        assertEquals("capability_report", AIPMessageBuilder.MessageType.CAPABILITY_REPORT)
    }

    @Test
    fun `MessageType TASK_ASSIGN equals task_assign`() {
        assertEquals("task_assign", AIPMessageBuilder.MessageType.TASK_ASSIGN)
    }

    @Test
    fun `MessageType COMMAND_RESULT equals command_result`() {
        assertEquals("command_result", AIPMessageBuilder.MessageType.COMMAND_RESULT)
    }

    @Test
    fun `toV3Type maps legacy registration to device_register`() {
        assertEquals(
            AIPMessageBuilder.MessageType.DEVICE_REGISTER,
            AIPMessageBuilder.toV3Type("registration")
        )
    }

    @Test
    fun `toV3Type maps legacy register to device_register`() {
        assertEquals(
            AIPMessageBuilder.MessageType.DEVICE_REGISTER,
            AIPMessageBuilder.toV3Type("register")
        )
    }

    @Test
    fun `toV3Type returns input unchanged for unknown types`() {
        assertEquals("custom_type", AIPMessageBuilder.toV3Type("custom_type"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // task_assign routing – DeviceCommunication scenarios
    // These tests validate that task_assign messages are parsed correctly so
    // that DeviceCommunication.handleMessage can route them to the command
    // handler path and build the right correlation_id in the response.
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `parse accepts native task_assign message and preserves type`() {
        val msg = JSONObject().apply {
            put("protocol", "AIP/1.0")
            put("type", "task_assign")
            put("action", "screenshot")
            put("message_id", "msg_task_001")
            put("task_id", "task_abc")
            put("timestamp", 1700003000L)
            put("payload", JSONObject().apply { put("format", "png") })
        }
        val parsed = AIPMessageBuilder.parse(msg.toString())

        assertNotNull(parsed)
        assertEquals("task_assign", parsed!!.getString("type"))
        assertEquals("screenshot", parsed.optString("action"))
        assertEquals("msg_task_001", parsed.getString("message_id"))
        assertEquals("task_abc", parsed.optString("task_id"))
    }

    @Test
    fun `task_assign message without task_id falls back to message_id for correlation`() {
        // Simulate what DeviceCommunication does: use task_id when present,
        // message_id otherwise.
        val withTaskId = JSONObject().apply {
            put("type", "task_assign")
            put("message_id", "msg_002")
            put("task_id", "t_xyz")
            put("payload", JSONObject())
        }
        val withoutTaskId = JSONObject().apply {
            put("type", "task_assign")
            put("message_id", "msg_003")
            put("payload", JSONObject())
        }

        // With task_id present: correlation_id should be the task_id
        val corrWithTask = withTaskId.optString("task_id").takeIf { it.isNotEmpty() }
            ?: withTaskId.optString("message_id")
        assertEquals("t_xyz", corrWithTask)

        // Without task_id: correlation_id falls back to message_id
        val corrWithoutTask = withoutTaskId.optString("task_id").takeIf { it.isNotEmpty() }
            ?: withoutTaskId.optString("message_id")
        assertEquals("msg_003", corrWithoutTask)
    }

    @Test
    fun `parse normalises v3 task_assign wire format to AIP-1-0`() {
        // A v3 task_assign message arriving from the AndroidBridge.
        val v3Task = JSONObject().apply {
            put("version", "3.0")
            put("msg_type", "task_assign")
            put("device_id", "server_node")
            put("message_id", "msg_v3_004")
            put("task_id", "t_v3_001")
            put("timestamp", 1700004000L)
            put("payload", JSONObject().apply {
                put("action", "click")
                put("x", 50)
                put("y", 100)
            })
        }
        val parsed = AIPMessageBuilder.parse(v3Task.toString())

        assertNotNull(parsed)
        assertEquals("task_assign", parsed!!.getString("type"))
        val payload = parsed.getJSONObject("payload")
        assertEquals("click", payload.getString("action"))
    }

    @Test
    fun `MessageType TASK_ASSIGN constant matches expected wire value`() {
        assertEquals("task_assign", AIPMessageBuilder.MessageType.TASK_ASSIGN)
    }

    @Test
    fun `parse returns task_assign type unchanged for AIP-1-0 format`() {
        val aip10Task = JSONObject().apply {
            put("protocol", "AIP/1.0")
            put("type", AIPMessageBuilder.MessageType.TASK_ASSIGN)
            put("action", "swipe")
            put("message_id", "msg_swipe_005")
            put("timestamp", 1700005000L)
            put("payload", JSONObject().apply {
                put("startX", 0)
                put("startY", 500)
                put("endX", 0)
                put("endY", 100)
            })
        }
        val parsed = AIPMessageBuilder.parse(aip10Task.toString())

        assertNotNull(parsed)
        assertEquals(AIPMessageBuilder.MessageType.TASK_ASSIGN, parsed!!.getString("type"))
        assertEquals("swipe", parsed.optString("action"))
        assertEquals("msg_swipe_005", parsed.getString("message_id"))
    }

    @Test
    fun `build capability_report message includes required v3 payload fields`() {
        val capabilities = org.json.JSONArray().apply {
            put("screen_capture"); put("ui_automation"); put("touch")
        }
        val payload = JSONObject().apply {
            put("platform", "android")
            put("supported_actions", capabilities)
            put("version", "2.5.0")
        }
        val msg = AIPMessageBuilder.build(
            messageType = AIPMessageBuilder.MessageType.CAPABILITY_REPORT,
            sourceNodeId = "android_abc123",
            targetNodeId = "server",
            payload = payload,
            deviceType = "Android_Agent"
        )

        assertEquals(AIPMessageBuilder.MessageType.CAPABILITY_REPORT, msg.getString("type"))
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
        assertEquals("android_abc123", msg.getString("device_id"))
        val p = msg.getJSONObject("payload")
        assertEquals("android", p.getString("platform"))
        assertEquals("2.5.0", p.getString("version"))
        assertTrue("supported_actions should be present", p.has("supported_actions"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // trace_id / route_mode (AIP v3 required fields – Round 2)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `build includes trace_id and route_mode by default`() {
        val msg = AIPMessageBuilder.build(
            messageType  = AIPMessageBuilder.MessageType.HEARTBEAT,
            sourceNodeId = "android_dev",
            targetNodeId = "server",
            payload      = JSONObject()
        )

        assertTrue("trace_id should be present", msg.has("trace_id"))
        assertTrue("trace_id should be non-empty", msg.getString("trace_id").isNotEmpty())
        assertTrue("route_mode should be present", msg.has("route_mode"))
        assertEquals(AIPMessageBuilder.ROUTE_MODE_LOCAL, msg.getString("route_mode"))
    }

    @Test
    fun `build honours explicit trace_id`() {
        val fixedTraceId = "test-trace-12345"
        val msg = AIPMessageBuilder.build(
            messageType  = AIPMessageBuilder.MessageType.DEVICE_REGISTER,
            sourceNodeId = "android_dev",
            targetNodeId = "server",
            payload      = JSONObject(),
            traceId      = fixedTraceId
        )

        assertEquals(fixedTraceId, msg.getString("trace_id"))
    }

    @Test
    fun `build honours cross_device route_mode`() {
        val msg = AIPMessageBuilder.build(
            messageType  = AIPMessageBuilder.MessageType.HEARTBEAT,
            sourceNodeId = "android_dev",
            targetNodeId = "server",
            payload      = JSONObject(),
            routeMode    = AIPMessageBuilder.ROUTE_MODE_CROSS_DEVICE
        )

        assertEquals(AIPMessageBuilder.ROUTE_MODE_CROSS_DEVICE, msg.getString("route_mode"))
    }

    @Test
    fun `build always includes trace_id and route_mode`() {
        // trace_id and route_mode are mandatory v3 envelope fields and are always present.
        val msg = AIPMessageBuilder.build(
            messageType  = AIPMessageBuilder.MessageType.HEARTBEAT,
            sourceNodeId = "android_dev",
            targetNodeId = "server",
            payload      = JSONObject()
        )

        assertTrue("trace_id should always be present", msg.has("trace_id"))
        assertTrue("route_mode should always be present", msg.has("route_mode"))
        assertEquals(AIPMessageBuilder.ROUTE_MODE_LOCAL, msg.getString("route_mode"))
    }

    @Test
    fun `generateTraceId produces non-empty unique values`() {
        val id1 = AIPMessageBuilder.generateTraceId()
        val id2 = AIPMessageBuilder.generateTraceId()

        assertTrue("trace ID should be non-empty", id1.isNotEmpty())
        assertTrue("consecutive generateTraceId calls should differ", id1 != id2)
    }

    @Test
    fun `ROUTE_MODE_LOCAL and ROUTE_MODE_CROSS_DEVICE have expected values`() {
        assertEquals("local", AIPMessageBuilder.ROUTE_MODE_LOCAL)
        assertEquals("cross_device", AIPMessageBuilder.ROUTE_MODE_CROSS_DEVICE)
    }

    // ──────────────────────────────────────────────────────────────────────
    // v3 envelope enforcement – five core message types (PR-C1)
    // Each test asserts version="3.0" and all required top-level fields.
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `v3 envelope - device_register has all required fields`() {
        val payload = JSONObject().apply {
            put("device_id", "android_xyz")
            put("device_type", "android")
        }
        val msg = AIPMessageBuilder.build(
            messageType  = AIPMessageBuilder.MessageType.DEVICE_REGISTER,
            sourceNodeId = "android_xyz",
            targetNodeId = "server",
            payload      = payload
        )

        assertEquals(AIPMessageBuilder.PROTOCOL_AIP1, msg.getString("protocol"))
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
        assertEquals(AIPMessageBuilder.MessageType.DEVICE_REGISTER, msg.getString("type"))
        assertEquals("android_xyz", msg.getString("device_id"))
        assertEquals("Android_Agent", msg.getString("device_type"))
        assertTrue("message_id must be present", msg.has("message_id"))
        assertEquals("android_xyz", msg.getString("source_node"))
        assertEquals("server", msg.getString("target_node"))
        assertTrue("timestamp must be positive", msg.getLong("timestamp") > 0)
    }

    @Test
    fun `v3 envelope - heartbeat has all required fields`() {
        val msg = AIPMessageBuilder.build(
            messageType  = AIPMessageBuilder.MessageType.HEARTBEAT,
            sourceNodeId = "android_xyz",
            targetNodeId = "server",
            payload      = JSONObject().apply { put("status", "ok") }
        )

        assertEquals(AIPMessageBuilder.PROTOCOL_AIP1, msg.getString("protocol"))
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
        assertEquals(AIPMessageBuilder.MessageType.HEARTBEAT, msg.getString("type"))
        assertEquals("android_xyz", msg.getString("device_id"))
        assertEquals("Android_Agent", msg.getString("device_type"))
        assertTrue("message_id must be present", msg.has("message_id"))
        assertEquals("android_xyz", msg.getString("source_node"))
        assertEquals("server", msg.getString("target_node"))
        assertTrue("timestamp must be positive", msg.getLong("timestamp") > 0)
    }

    @Test
    fun `v3 envelope - capability_report via buildCapabilityReport has all required fields`() {
        val capabilities = org.json.JSONArray().apply {
            put("screen_capture"); put("touch"); put("ui_automation")
        }
        val payload = JSONObject().apply {
            put("platform", "android")
            put("supported_actions", capabilities)
            put("version", "3.0")
        }
        val msg = AIPMessageBuilder.buildCapabilityReport(
            sourceNodeId = "android_xyz",
            targetNodeId = "server",
            payload      = payload
        )

        assertEquals(AIPMessageBuilder.PROTOCOL_AIP1, msg.getString("protocol"))
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
        assertEquals(AIPMessageBuilder.MessageType.CAPABILITY_REPORT, msg.getString("type"))
        assertEquals("android_xyz", msg.getString("device_id"))
        assertEquals("Android_Agent", msg.getString("device_type"))
        assertTrue("message_id must be present", msg.has("message_id"))
        assertEquals("android_xyz", msg.getString("source_node"))
        assertEquals("server", msg.getString("target_node"))
        assertTrue("timestamp must be positive", msg.getLong("timestamp") > 0)
    }

    @Test
    fun `v3 envelope - task_assign has all required fields`() {
        val msg = AIPMessageBuilder.build(
            messageType  = AIPMessageBuilder.MessageType.TASK_ASSIGN,
            sourceNodeId = "android_xyz",
            targetNodeId = "server",
            payload      = JSONObject().apply { put("task_id", "t_001") }
        )

        assertEquals(AIPMessageBuilder.PROTOCOL_AIP1, msg.getString("protocol"))
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
        assertEquals(AIPMessageBuilder.MessageType.TASK_ASSIGN, msg.getString("type"))
        assertEquals("android_xyz", msg.getString("device_id"))
        assertEquals("Android_Agent", msg.getString("device_type"))
        assertTrue("message_id must be present", msg.has("message_id"))
        assertEquals("android_xyz", msg.getString("source_node"))
        assertEquals("server", msg.getString("target_node"))
        assertTrue("timestamp must be positive", msg.getLong("timestamp") > 0)
    }

    @Test
    fun `v3 envelope - command_result has all required fields`() {
        val msg = AIPMessageBuilder.build(
            messageType  = AIPMessageBuilder.MessageType.COMMAND_RESULT,
            sourceNodeId = "android_xyz",
            targetNodeId = "server",
            payload      = JSONObject().apply { put("status", "success") }
        )

        assertEquals(AIPMessageBuilder.PROTOCOL_AIP1, msg.getString("protocol"))
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
        assertEquals(AIPMessageBuilder.MessageType.COMMAND_RESULT, msg.getString("type"))
        assertEquals("android_xyz", msg.getString("device_id"))
        assertEquals("Android_Agent", msg.getString("device_type"))
        assertTrue("message_id must be present", msg.has("message_id"))
        assertEquals("android_xyz", msg.getString("source_node"))
        assertEquals("server", msg.getString("target_node"))
        assertTrue("timestamp must be positive", msg.getLong("timestamp") > 0)
    }

    // ──────────────────────────────────────────────────────────────────────
    // buildCapabilityReport() – negative tests for missing required fields
    // ──────────────────────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `buildCapabilityReport throws when platform is missing`() {
        val payload = JSONObject().apply {
            put("supported_actions", org.json.JSONArray().apply { put("touch") })
            put("version", "3.0")
            // 'platform' intentionally omitted
        }
        AIPMessageBuilder.buildCapabilityReport(
            sourceNodeId = "android_xyz",
            targetNodeId = "server",
            payload      = payload
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildCapabilityReport throws when supported_actions is missing`() {
        val payload = JSONObject().apply {
            put("platform", "android")
            put("version", "3.0")
            // 'supported_actions' intentionally omitted
        }
        AIPMessageBuilder.buildCapabilityReport(
            sourceNodeId = "android_xyz",
            targetNodeId = "server",
            payload      = payload
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildCapabilityReport throws when version is missing`() {
        val payload = JSONObject().apply {
            put("platform", "android")
            put("supported_actions", org.json.JSONArray().apply { put("touch") })
            // 'version' intentionally omitted
        }
        AIPMessageBuilder.buildCapabilityReport(
            sourceNodeId = "android_xyz",
            targetNodeId = "server",
            payload      = payload
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildCapabilityReport throws when platform is blank`() {
        val payload = JSONObject().apply {
            put("platform", "  ")  // blank
            put("supported_actions", org.json.JSONArray().apply { put("touch") })
            put("version", "3.0")
        }
        AIPMessageBuilder.buildCapabilityReport(
            sourceNodeId = "android_xyz",
            targetNodeId = "server",
            payload      = payload
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildCapabilityReport throws when version is blank`() {
        val payload = JSONObject().apply {
            put("platform", "android")
            put("supported_actions", org.json.JSONArray().apply { put("touch") })
            put("version", "")  // blank
        }
        AIPMessageBuilder.buildCapabilityReport(
            sourceNodeId = "android_xyz",
            targetNodeId = "server",
            payload      = payload
        )
    }
}

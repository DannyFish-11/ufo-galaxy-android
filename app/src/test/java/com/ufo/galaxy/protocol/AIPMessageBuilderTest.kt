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
    fun `build omits v3 fields when includeV3 is false`() {
        val msg = AIPMessageBuilder.build(
            messageType = "heartbeat",
            sourceNodeId = "dev_1",
            targetNodeId = "Galaxy",
            payload = JSONObject(),
            includeV3 = false
        )

        assertTrue("version field should be absent", !msg.has("version"))
        assertTrue("device_id field should be absent", !msg.has("device_id"))
        assertTrue("device_type field should be absent", !msg.has("device_type"))
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
}

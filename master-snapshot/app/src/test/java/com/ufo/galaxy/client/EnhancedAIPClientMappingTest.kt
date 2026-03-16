package com.ufo.galaxy.client

import com.ufo.galaxy.protocol.AIPMessageBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the PR-C3 Microsoft-compatibility mapping layer in [EnhancedAIPClient].
 *
 * These tests exercise [applyMicrosoftMapping] / [sendWire] logic by reproducing the
 * mapping pipeline directly (without a WebSocket connection):
 *
 *  1. Build a v3 envelope via [AIPMessageBuilder.build].
 *  2. Apply [applyMicrosoftMapping] (toggle ON) and assert `ms_*` fields are present
 *     while ALL v3 envelope fields remain intact.
 *  3. Assert that with toggle OFF, the output is the unchanged v3 payload.
 *  4. Confirm that legacy type inputs are normalised to v3 before mapping is applied.
 *
 * No Android device, emulator, or WebSocket server is needed; tests run on the JVM.
 */
class EnhancedAIPClientMappingTest {

    private val deviceId = "android_test_c3"
    private val target = "Galaxy"
    private val emptyPayload = JSONObject()

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers that mirror the production send pipeline
    // ──────────────────────────────────────────────────────────────────────────

    private val microsoftTypeMapping: Map<String, String> = mapOf(
        AIPMessageBuilder.MessageType.DEVICE_REGISTER   to "REGISTER",
        AIPMessageBuilder.MessageType.HEARTBEAT         to "HEARTBEAT",
        AIPMessageBuilder.MessageType.CAPABILITY_REPORT to "CAPABILITY_REPORT",
        AIPMessageBuilder.MessageType.TASK_ASSIGN       to "TASK",
        AIPMessageBuilder.MessageType.COMMAND_RESULT    to "COMMAND_RESULTS"
    )

    /** Simulates [EnhancedAIPClient.applyMicrosoftMapping]. */
    private fun applyMicrosoftMapping(v3Message: JSONObject): JSONObject {
        val v3Type = v3Message.optString("type")
        val msType = microsoftTypeMapping[v3Type] ?: v3Type.uppercase()
        return JSONObject(v3Message.toString()).apply {
            put("ms_message_type", msType)
            put("ms_agent_id", v3Message.optString("source_node", deviceId))
            put("ms_session_id", v3Message.optLong("timestamp") * 1000L)
        }
    }

    /**
     * Simulates [EnhancedAIPClient.sendWire]: builds the v3 envelope, then
     * optionally applies the Microsoft mapping at the outermost layer.
     */
    private fun buildWire(
        rawType: String,
        payload: JSONObject = emptyPayload,
        mappingEnabled: Boolean
    ): JSONObject {
        val v3Type = AIPMessageBuilder.toV3Type(rawType)
        val v3Envelope = AIPMessageBuilder.build(
            messageType  = v3Type,
            sourceNodeId = deviceId,
            targetNodeId = target,
            payload      = payload
        )
        return if (mappingEnabled) applyMicrosoftMapping(v3Envelope) else v3Envelope
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Toggle ON: v3 envelope preserved
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `mapping ON - version field is preserved as 3.0`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.DEVICE_REGISTER, mappingEnabled = true)
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, wire.getString("version"))
    }

    @Test
    fun `mapping ON - protocol field is preserved as AIP/1.0`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.HEARTBEAT, mappingEnabled = true)
        assertEquals(AIPMessageBuilder.PROTOCOL_AIP1, wire.getString("protocol"))
    }

    @Test
    fun `mapping ON - v3 type field is preserved intact`() {
        for (v3Type in listOf(
            AIPMessageBuilder.MessageType.DEVICE_REGISTER,
            AIPMessageBuilder.MessageType.HEARTBEAT,
            AIPMessageBuilder.MessageType.CAPABILITY_REPORT,
            AIPMessageBuilder.MessageType.TASK_ASSIGN,
            AIPMessageBuilder.MessageType.COMMAND_RESULT
        )) {
            val wire = buildWire(v3Type, mappingEnabled = true)
            assertEquals("type field must stay '$v3Type' after mapping", v3Type, wire.getString("type"))
        }
    }

    @Test
    fun `mapping ON - source_node field is preserved`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.DEVICE_REGISTER, mappingEnabled = true)
        assertEquals(deviceId, wire.getString("source_node"))
    }

    @Test
    fun `mapping ON - message_id field is preserved`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.HEARTBEAT, mappingEnabled = true)
        assertTrue("message_id must be present", wire.has("message_id"))
        assertTrue("message_id must be non-empty", wire.getString("message_id").isNotEmpty())
    }

    @Test
    fun `mapping ON - payload field is preserved`() {
        val payload = JSONObject().apply { put("status", "online") }
        val wire = buildWire(AIPMessageBuilder.MessageType.HEARTBEAT, payload, mappingEnabled = true)
        assertEquals("online", wire.getJSONObject("payload").getString("status"))
    }

    @Test
    fun `mapping ON - device_id field is preserved`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.DEVICE_REGISTER, mappingEnabled = true)
        assertEquals(deviceId, wire.getString("device_id"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Toggle ON: Microsoft ms_* headers are present and correct
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `mapping ON - ms_message_type is added for device_register`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.DEVICE_REGISTER, mappingEnabled = true)
        assertEquals("REGISTER", wire.getString("ms_message_type"))
    }

    @Test
    fun `mapping ON - ms_message_type is added for heartbeat`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.HEARTBEAT, mappingEnabled = true)
        assertEquals("HEARTBEAT", wire.getString("ms_message_type"))
    }

    @Test
    fun `mapping ON - ms_message_type is added for capability_report`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.CAPABILITY_REPORT, mappingEnabled = true)
        assertEquals("CAPABILITY_REPORT", wire.getString("ms_message_type"))
    }

    @Test
    fun `mapping ON - ms_message_type is added for task_assign`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.TASK_ASSIGN, mappingEnabled = true)
        assertEquals("TASK", wire.getString("ms_message_type"))
    }

    @Test
    fun `mapping ON - ms_message_type is added for command_result`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.COMMAND_RESULT, mappingEnabled = true)
        assertEquals("COMMAND_RESULTS", wire.getString("ms_message_type"))
    }

    @Test
    fun `mapping ON - ms_agent_id matches source_node`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.HEARTBEAT, mappingEnabled = true)
        assertEquals(wire.getString("source_node"), wire.getString("ms_agent_id"))
    }

    @Test
    fun `mapping ON - ms_session_id is timestamp in milliseconds`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.HEARTBEAT, mappingEnabled = true)
        val timestampSec = wire.getLong("timestamp")
        val msSessionId  = wire.getLong("ms_session_id")
        assertEquals(timestampSec * 1000L, msSessionId)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Toggle OFF: raw v3 payload, no ms_* fields
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `mapping OFF - output is unchanged v3 payload without ms_message_type`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.DEVICE_REGISTER, mappingEnabled = false)
        assertFalse("ms_message_type must be absent when toggle is off", wire.has("ms_message_type"))
    }

    @Test
    fun `mapping OFF - output does not contain ms_agent_id`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.HEARTBEAT, mappingEnabled = false)
        assertFalse("ms_agent_id must be absent when toggle is off", wire.has("ms_agent_id"))
    }

    @Test
    fun `mapping OFF - output does not contain ms_session_id`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.COMMAND_RESULT, mappingEnabled = false)
        assertFalse("ms_session_id must be absent when toggle is off", wire.has("ms_session_id"))
    }

    @Test
    fun `mapping OFF - v3 envelope fields are still present`() {
        val wire = buildWire(AIPMessageBuilder.MessageType.HEARTBEAT, mappingEnabled = false)
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, wire.getString("version"))
        assertEquals(AIPMessageBuilder.PROTOCOL_AIP1, wire.getString("protocol"))
        assertEquals(AIPMessageBuilder.MessageType.HEARTBEAT, wire.getString("type"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Legacy type normalisation happens before mapping
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `legacy registration type normalised to device_register before mapping ON`() {
        val wire = buildWire("registration", mappingEnabled = true)
        assertEquals(AIPMessageBuilder.MessageType.DEVICE_REGISTER, wire.getString("type"))
        assertEquals("REGISTER", wire.getString("ms_message_type"))
    }

    @Test
    fun `legacy register type normalised to device_register before mapping ON`() {
        val wire = buildWire("register", mappingEnabled = true)
        assertEquals(AIPMessageBuilder.MessageType.DEVICE_REGISTER, wire.getString("type"))
        assertEquals("REGISTER", wire.getString("ms_message_type"))
    }

    @Test
    fun `legacy heartbeat type preserved as heartbeat before mapping ON`() {
        val wire = buildWire("heartbeat", mappingEnabled = true)
        assertEquals(AIPMessageBuilder.MessageType.HEARTBEAT, wire.getString("type"))
        assertEquals("HEARTBEAT", wire.getString("ms_message_type"))
    }

    @Test
    fun `legacy command type normalised to task_assign before mapping ON`() {
        val wire = buildWire("command", mappingEnabled = true)
        assertEquals(AIPMessageBuilder.MessageType.TASK_ASSIGN, wire.getString("type"))
        assertEquals("TASK", wire.getString("ms_message_type"))
    }

    @Test
    fun `legacy registration type normalised to device_register before mapping OFF`() {
        val wire = buildWire("registration", mappingEnabled = false)
        assertEquals(AIPMessageBuilder.MessageType.DEVICE_REGISTER, wire.getString("type"))
        assertFalse("no ms_* fields expected when toggle is off", wire.has("ms_message_type"))
    }

    @Test
    fun `legacy command_result type is already v3 - preserved by mapping ON`() {
        val wire = buildWire("command_result", mappingEnabled = true)
        assertEquals(AIPMessageBuilder.MessageType.COMMAND_RESULT, wire.getString("type"))
        assertEquals("COMMAND_RESULTS", wire.getString("ms_message_type"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Mapping does NOT down-convert: v3 type names remain on the wire
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `mapping ON - type field is never replaced by ms_message_type value`() {
        // The ms_message_type (e.g. "REGISTER") must NOT overwrite the v3 type field
        val wire = buildWire(AIPMessageBuilder.MessageType.DEVICE_REGISTER, mappingEnabled = true)
        assertNotNull(wire.optString("type"))
        assertEquals(AIPMessageBuilder.MessageType.DEVICE_REGISTER, wire.getString("type"))
        // ms_message_type is a separate, supplementary key
        assertEquals("REGISTER", wire.getString("ms_message_type"))
    }

    @Test
    fun `all five v3 message types produce correct ms_message_type when mapping ON`() {
        val expected = mapOf(
            AIPMessageBuilder.MessageType.DEVICE_REGISTER   to "REGISTER",
            AIPMessageBuilder.MessageType.HEARTBEAT         to "HEARTBEAT",
            AIPMessageBuilder.MessageType.CAPABILITY_REPORT to "CAPABILITY_REPORT",
            AIPMessageBuilder.MessageType.TASK_ASSIGN       to "TASK",
            AIPMessageBuilder.MessageType.COMMAND_RESULT    to "COMMAND_RESULTS"
        )
        for ((v3Type, msType) in expected) {
            val wire = buildWire(v3Type, mappingEnabled = true)
            assertEquals("ms_message_type mismatch for $v3Type", msType, wire.getString("ms_message_type"))
            assertEquals("v3 type must be preserved for $v3Type", v3Type, wire.getString("type"))
        }
    }
}

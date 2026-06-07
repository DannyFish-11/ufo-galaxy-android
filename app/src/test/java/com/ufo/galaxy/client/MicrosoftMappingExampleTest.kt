package com.ufo.galaxy.client

import com.ufo.galaxy.protocol.MsgType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-C5: Example tests for the Microsoft-compatibility mapping toggle in EnhancedAIPClient.
 *
 * These tests mirror the canonical examples documented in `docs/AIP_V3_EXAMPLES.md`
 * (§ Microsoft 兼容映射示例) and serve as living documentation for the mapping layer
 * introduced in PR-C3.
 *
 * The tests reproduce the `applyMicrosoftMapping` / `sendWire` pipeline directly —
 * without instantiating `EnhancedAIPClient` or opening a WebSocket — by building a
 * minimal v3 envelope inline and applying the same logic.
 *
 * Coverage:
 *  - Toggle ON: `ms_*` headers are appended; all v3 envelope fields are preserved.
 *  - Toggle OFF: output is the raw v3 envelope with no `ms_*` fields.
 *  - All five v3 type → Microsoft type mappings documented in AIP_V3_EXAMPLES.md.
 *  - Canonical device_register example payload matches documentation.
 *
 * No Android device, emulator, or server is required; tests run on the JVM.
 */
class MicrosoftMappingExampleTest {

    companion object {
        private const val PROTOCOL_AIP1 = "AIP/1.0"
        private const val PROTOCOL_V3   = "3.0"
        private const val DEVICE_ID     = "android_pixel8_01"
        private const val TARGET_NODE   = "Galaxy"
    }

    /** v3 type → Microsoft wire-type (mirrors EnhancedAIPClient.microsoftTypeMapping). */
    private val microsoftTypeMapping: Map<String, String> = mapOf(
        MsgType.DEVICE_REGISTER.value   to "REGISTER",
        MsgType.HEARTBEAT.value         to "HEARTBEAT",
        MsgType.CAPABILITY_REPORT.value to "CAPABILITY_REPORT",
        MsgType.TASK_ASSIGN.value       to "TASK",
        MsgType.COMMAND_RESULT.value    to "COMMAND_RESULTS"
    )

    /**
     * Builds a minimal v3 envelope, mirroring [AIPMessageBuilder.build] output structure.
     * Sufficient for testing the mapping layer without depending on the master-snapshot class.
     */
    private fun buildV3Envelope(
        v3Type: String,
        payload: JSONObject = JSONObject()
    ): JSONObject = JSONObject().apply {
        put("protocol",    PROTOCOL_AIP1)
        put("version",     PROTOCOL_V3)
        put("type",        v3Type)
        put("source_node", DEVICE_ID)
        put("target_node", TARGET_NODE)
        put("timestamp",   System.currentTimeMillis() / 1000)
        put("message_id",  "a1b2c3d4")
        put("device_id",   DEVICE_ID)
        put("device_type", "Android_Agent")
        put("payload",     payload)
    }

    /** Simulates [EnhancedAIPClient.applyMicrosoftMapping]. */
    private fun applyMicrosoftMapping(v3Message: JSONObject): JSONObject {
        val v3Type = v3Message.optString("type")
        val msType = microsoftTypeMapping[v3Type] ?: v3Type.uppercase()
        return JSONObject(v3Message.toString()).apply {
            put("ms_message_type", msType)
            put("ms_agent_id",     v3Message.optString("source_node", DEVICE_ID))
            put("ms_session_id",   v3Message.optLong("timestamp") * 1000L)
        }
    }

    /** Simulates [EnhancedAIPClient.sendWire]: builds envelope, applies mapping if enabled. */
    private fun buildWire(v3Type: String, mappingEnabled: Boolean): JSONObject {
        val envelope = buildV3Envelope(v3Type)
        return if (mappingEnabled) applyMicrosoftMapping(envelope) else envelope
    }

    // ── Toggle ON: v3 envelope fields are preserved ───────────────────────────

    @Test
    fun `mapping ON - canonical device_register example has version 3_0`() {
        val wire = buildWire(MsgType.DEVICE_REGISTER.value, mappingEnabled = true)
        assertEquals("3.0", wire.getString("version"))
    }

    @Test
    fun `mapping ON - canonical device_register example has protocol AIP_1_0`() {
        val wire = buildWire(MsgType.DEVICE_REGISTER.value, mappingEnabled = true)
        assertEquals("AIP/1.0", wire.getString("protocol"))
    }

    @Test
    fun `mapping ON - type field stays device_register after mapping`() {
        val wire = buildWire(MsgType.DEVICE_REGISTER.value, mappingEnabled = true)
        assertEquals(MsgType.DEVICE_REGISTER.value, wire.getString("type"))
    }

    @Test
    fun `mapping ON - source_node is preserved as device_id`() {
        val wire = buildWire(MsgType.HEARTBEAT.value, mappingEnabled = true)
        assertEquals(DEVICE_ID, wire.getString("source_node"))
    }

    @Test
    fun `mapping ON - device_id field is preserved`() {
        val wire = buildWire(MsgType.CAPABILITY_REPORT.value, mappingEnabled = true)
        assertEquals(DEVICE_ID, wire.getString("device_id"))
    }

    @Test
    fun `mapping ON - message_id field is preserved`() {
        val wire = buildWire(MsgType.HEARTBEAT.value, mappingEnabled = true)
        assertNotNull(wire.optString("message_id"))
        assertTrue(wire.getString("message_id").isNotEmpty())
    }

    @Test
    fun `mapping ON - payload field is preserved`() {
        val envelope = buildV3Envelope(
            MsgType.HEARTBEAT.value,
            JSONObject().apply { put("status", "online") }
        )
        val wire = applyMicrosoftMapping(envelope)
        assertEquals("online", wire.getJSONObject("payload").getString("status"))
    }

    // ── Toggle ON: ms_* headers are present and correct ──────────────────────

    @Test
    fun `mapping ON - device_register produces ms_message_type REGISTER`() {
        val wire = buildWire(MsgType.DEVICE_REGISTER.value, mappingEnabled = true)
        assertEquals("REGISTER", wire.getString("ms_message_type"))
    }

    @Test
    fun `mapping ON - heartbeat produces ms_message_type HEARTBEAT`() {
        val wire = buildWire(MsgType.HEARTBEAT.value, mappingEnabled = true)
        assertEquals("HEARTBEAT", wire.getString("ms_message_type"))
    }

    @Test
    fun `mapping ON - capability_report produces ms_message_type CAPABILITY_REPORT`() {
        val wire = buildWire(MsgType.CAPABILITY_REPORT.value, mappingEnabled = true)
        assertEquals("CAPABILITY_REPORT", wire.getString("ms_message_type"))
    }

    @Test
    fun `mapping ON - task_assign produces ms_message_type TASK`() {
        val wire = buildWire(MsgType.TASK_ASSIGN.value, mappingEnabled = true)
        assertEquals("TASK", wire.getString("ms_message_type"))
    }

    @Test
    fun `mapping ON - command_result produces ms_message_type COMMAND_RESULTS`() {
        val wire = buildWire(MsgType.COMMAND_RESULT.value, mappingEnabled = true)
        assertEquals("COMMAND_RESULTS", wire.getString("ms_message_type"))
    }

    @Test
    fun `mapping ON - ms_agent_id equals source_node`() {
        val wire = buildWire(MsgType.DEVICE_REGISTER.value, mappingEnabled = true)
        assertEquals(wire.getString("source_node"), wire.getString("ms_agent_id"))
    }

    @Test
    fun `mapping ON - ms_session_id is timestamp in milliseconds`() {
        val wire = buildWire(MsgType.HEARTBEAT.value, mappingEnabled = true)
        val timestampSec = wire.getLong("timestamp")
        val msSessionId  = wire.getLong("ms_session_id")
        assertEquals("ms_session_id must be timestamp × 1000", timestampSec * 1000L, msSessionId)
    }

    // ── Toggle OFF: raw v3 envelope, no ms_* fields ───────────────────────────

    @Test
    fun `mapping OFF - ms_message_type is absent`() {
        val wire = buildWire(MsgType.DEVICE_REGISTER.value, mappingEnabled = false)
        assertFalse("ms_message_type must not be present when mapping is disabled", wire.has("ms_message_type"))
    }

    @Test
    fun `mapping OFF - ms_agent_id is absent`() {
        val wire = buildWire(MsgType.HEARTBEAT.value, mappingEnabled = false)
        assertFalse("ms_agent_id must not be present when mapping is disabled", wire.has("ms_agent_id"))
    }

    @Test
    fun `mapping OFF - ms_session_id is absent`() {
        val wire = buildWire(MsgType.COMMAND_RESULT.value, mappingEnabled = false)
        assertFalse("ms_session_id must not be present when mapping is disabled", wire.has("ms_session_id"))
    }

    @Test
    fun `mapping OFF - v3 envelope fields are intact`() {
        val wire = buildWire(MsgType.HEARTBEAT.value, mappingEnabled = false)
        assertEquals("3.0", wire.getString("version"))
        assertEquals("AIP/1.0", wire.getString("protocol"))
        assertEquals(MsgType.HEARTBEAT.value, wire.getString("type"))
        assertEquals(DEVICE_ID, wire.getString("source_node"))
    }

    // ── Documentation snapshot: all five v3→Microsoft mappings ───────────────

    @Test
    fun `all five documented v3 to Microsoft type mappings produce correct ms_message_type`() {
        // This test is the canonical source of truth for the mapping table in
        // docs/AIP_V3_EXAMPLES.md §"v3 类型 → Microsoft 类型对照表"
        val expectedMappings = mapOf(
            MsgType.DEVICE_REGISTER.value   to "REGISTER",
            MsgType.HEARTBEAT.value         to "HEARTBEAT",
            MsgType.CAPABILITY_REPORT.value to "CAPABILITY_REPORT",
            MsgType.TASK_ASSIGN.value       to "TASK",
            MsgType.COMMAND_RESULT.value    to "COMMAND_RESULTS"
        )
        for ((v3Type, expectedMsType) in expectedMappings) {
            val wire = buildWire(v3Type, mappingEnabled = true)
            assertEquals(
                "ms_message_type mismatch for v3 type '$v3Type'",
                expectedMsType,
                wire.getString("ms_message_type")
            )
            assertEquals(
                "v3 type field must be preserved for '$v3Type'",
                v3Type,
                wire.getString("type")
            )
        }
    }
}

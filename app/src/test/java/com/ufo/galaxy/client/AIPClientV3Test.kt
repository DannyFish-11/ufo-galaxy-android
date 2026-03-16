package com.ufo.galaxy.client

import com.ufo.galaxy.protocol.AIPMessageBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests verifying that AIPClient and EnhancedAIPClient outbound messages comply
 * with the v3 protocol: envelope fields `version="3.0"` / `protocol="AIP/1.0"`,
 * and that v3 type names are used on the wire.
 *
 * Because both clients delegate all message building to [AIPMessageBuilder], the
 * tests exercise the combined client send-path logic by:
 *  1. Reproducing the exact [AIPMessageBuilder.build] calls the clients make.
 *  2. Asserting that the resulting [JSONObject] carries the mandatory v3 fields.
 *  3. Verifying that legacy type strings are normalised before building.
 *
 * No Android device, emulator, or WebSocket server is required; these tests run
 * on the JVM.
 */
class AIPClientV3Test {

    private val deviceId = "android_test01"
    private val target = "Galaxy"
    private val emptyPayload = JSONObject()

    // ──────────────────────────────────────────────────────────────────────────
    // Helper: simulate the send-path used by both clients
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Simulates the normalise-then-build pipeline used by [AIPClient.sendAIPMessage]
     * and [EnhancedAIPClient.sendMessage].
     */
    private fun buildNormalized(rawType: String, payload: JSONObject = emptyPayload): JSONObject =
        AIPMessageBuilder.build(
            messageType  = AIPMessageBuilder.toV3Type(rawType),
            sourceNodeId = deviceId,
            targetNodeId = target,
            payload      = payload
        )

    // ──────────────────────────────────────────────────────────────────────────
    // v3 envelope on outbound messages
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `outbound device_register carries version 3_dot_0`() {
        val msg = buildNormalized(AIPMessageBuilder.MessageType.DEVICE_REGISTER)
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
    }

    @Test
    fun `outbound heartbeat carries version 3_dot_0`() {
        val msg = buildNormalized(AIPMessageBuilder.MessageType.HEARTBEAT)
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
    }

    @Test
    fun `outbound capability_report carries version 3_dot_0`() {
        val payload = JSONObject().apply {
            put("platform", "android")
            put("supported_actions", org.json.JSONArray().apply { put("screen_capture") })
            put("version", "2.5.0")
        }
        val msg = AIPMessageBuilder.buildCapabilityReport(deviceId, target, payload)
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
    }

    @Test
    fun `outbound command_result carries version 3_dot_0`() {
        val msg = buildNormalized(AIPMessageBuilder.MessageType.COMMAND_RESULT)
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
    }

    @Test
    fun `outbound task_assign carries version 3_dot_0`() {
        val msg = buildNormalized(AIPMessageBuilder.MessageType.TASK_ASSIGN)
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // v3 envelope includes protocol="AIP/1.0"
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `outbound messages include protocol AIP_1_0`() {
        listOf(
            AIPMessageBuilder.MessageType.DEVICE_REGISTER,
            AIPMessageBuilder.MessageType.HEARTBEAT,
            AIPMessageBuilder.MessageType.COMMAND_RESULT,
            AIPMessageBuilder.MessageType.TASK_ASSIGN
        ).forEach { type ->
            val msg = buildNormalized(type)
            assertEquals(
                "protocol field for type $type",
                AIPMessageBuilder.PROTOCOL_AIP1,
                msg.getString("protocol")
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // v3 envelope includes device_id, device_type, message_id, source_node
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `outbound messages carry device_id equal to sourceNodeId`() {
        val msg = buildNormalized(AIPMessageBuilder.MessageType.HEARTBEAT)
        assertEquals(deviceId, msg.getString("device_id"))
        assertEquals(deviceId, msg.getString("source_node"))
    }

    @Test
    fun `outbound messages carry non-blank message_id`() {
        val msg = buildNormalized(AIPMessageBuilder.MessageType.COMMAND_RESULT)
        assertTrue("message_id must be non-blank", msg.getString("message_id").isNotBlank())
    }

    @Test
    fun `outbound messages carry Android_Agent as device_type by default`() {
        val msg = buildNormalized(AIPMessageBuilder.MessageType.DEVICE_REGISTER)
        assertEquals("Android_Agent", msg.getString("device_type"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Legacy type normalisation
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `legacy registration maps to device_register`() {
        assertEquals(
            AIPMessageBuilder.MessageType.DEVICE_REGISTER,
            AIPMessageBuilder.toV3Type("registration")
        )
    }

    @Test
    fun `legacy register maps to device_register`() {
        assertEquals(
            AIPMessageBuilder.MessageType.DEVICE_REGISTER,
            AIPMessageBuilder.toV3Type("register")
        )
    }

    @Test
    fun `legacy command maps to task_assign`() {
        assertEquals(
            AIPMessageBuilder.MessageType.TASK_ASSIGN,
            AIPMessageBuilder.toV3Type("command")
        )
    }

    @Test
    fun `legacy command_result maps to command_result (no change)`() {
        assertEquals(
            AIPMessageBuilder.MessageType.COMMAND_RESULT,
            AIPMessageBuilder.toV3Type("command_result")
        )
    }

    @Test
    fun `legacy heartbeat maps to heartbeat (no change)`() {
        assertEquals(
            AIPMessageBuilder.MessageType.HEARTBEAT,
            AIPMessageBuilder.toV3Type("heartbeat")
        )
    }

    @Test
    fun `outbound message built from legacy registration type has v3 type device_register`() {
        val msg = buildNormalized("registration")
        assertEquals(AIPMessageBuilder.MessageType.DEVICE_REGISTER, msg.getString("type"))
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
    }

    @Test
    fun `outbound message built from legacy command type has v3 type task_assign`() {
        val msg = buildNormalized("command")
        assertEquals(AIPMessageBuilder.MessageType.TASK_ASSIGN, msg.getString("type"))
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
    }

    @Test
    fun `outbound message built from legacy register type has v3 type device_register`() {
        val msg = buildNormalized("register")
        assertEquals(AIPMessageBuilder.MessageType.DEVICE_REGISTER, msg.getString("type"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Native v3 type strings pass through unchanged
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `native v3 types pass through toV3Type unchanged`() {
        listOf(
            AIPMessageBuilder.MessageType.DEVICE_REGISTER,
            AIPMessageBuilder.MessageType.HEARTBEAT,
            AIPMessageBuilder.MessageType.CAPABILITY_REPORT,
            AIPMessageBuilder.MessageType.TASK_ASSIGN,
            AIPMessageBuilder.MessageType.COMMAND_RESULT
        ).forEach { v3Type ->
            assertEquals(
                "toV3Type($v3Type) should be a no-op for native v3 types",
                v3Type,
                AIPMessageBuilder.toV3Type(v3Type)
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // AIPClient – status_request reply uses COMMAND_RESULT (not status_update)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `status_request reply uses command_result v3 type`() {
        // Reproduces the AIPClient.handleAIPMessage "status_request" path
        val statusPayload = JSONObject().apply {
            put("battery_level", 85)
            put("location", "Lat: 34.0522, Lon: -118.2437")
            put("is_charging", false)
        }
        val msg = AIPMessageBuilder.build(
            messageType  = AIPMessageBuilder.MessageType.COMMAND_RESULT,
            sourceNodeId = deviceId,
            targetNodeId = target,
            payload      = statusPayload
        )
        assertEquals(AIPMessageBuilder.MessageType.COMMAND_RESULT, msg.getString("type"))
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
        assertNotEquals("status_update", msg.getString("type"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // capability_report payload validation (AIPMessageBuilder.buildCapabilityReport)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `buildCapabilityReport throws when platform is missing`() {
        val payload = JSONObject().apply {
            put("supported_actions", org.json.JSONArray())
            put("version", "2.5.0")
        }
        var threw = false
        try {
            AIPMessageBuilder.buildCapabilityReport(deviceId, target, payload)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("buildCapabilityReport must throw when platform is missing", threw)
    }

    @Test
    fun `buildCapabilityReport throws when supported_actions is missing`() {
        val payload = JSONObject().apply {
            put("platform", "android")
            put("version", "2.5.0")
        }
        var threw = false
        try {
            AIPMessageBuilder.buildCapabilityReport(deviceId, target, payload)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("buildCapabilityReport must throw when supported_actions is missing", threw)
    }

    @Test
    fun `buildCapabilityReport throws when version is missing`() {
        val payload = JSONObject().apply {
            put("platform", "android")
            put("supported_actions", org.json.JSONArray())
        }
        var threw = false
        try {
            AIPMessageBuilder.buildCapabilityReport(deviceId, target, payload)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("buildCapabilityReport must throw when version is missing", threw)
    }

    @Test
    fun `buildCapabilityReport succeeds with all required fields`() {
        val payload = JSONObject().apply {
            put("platform", "android")
            put("supported_actions", org.json.JSONArray().apply { put("screen_capture") })
            put("version", "2.5.0")
        }
        val msg = AIPMessageBuilder.buildCapabilityReport(deviceId, target, payload)
        assertNotNull(msg)
        assertEquals(AIPMessageBuilder.MessageType.CAPABILITY_REPORT, msg.getString("type"))
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // EnhancedAIPClient – sendMessage normalises legacy types
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `EnhancedAIPClient sendMessage with legacy registration normalises to device_register`() {
        // Reproduces the normalise-then-build pipeline of EnhancedAIPClient.sendMessage
        val msg = buildNormalized("registration")
        assertEquals(AIPMessageBuilder.MessageType.DEVICE_REGISTER, msg.getString("type"))
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
    }

    @Test
    fun `EnhancedAIPClient sendMessage with legacy command normalises to task_assign`() {
        val msg = buildNormalized("command")
        assertEquals(AIPMessageBuilder.MessageType.TASK_ASSIGN, msg.getString("type"))
        assertEquals(AIPMessageBuilder.PROTOCOL_V3, msg.getString("version"))
    }
}

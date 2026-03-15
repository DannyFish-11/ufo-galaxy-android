package com.ufo.galaxy.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DeviceRegistry] capability schema building (Round 3).
 *
 * These tests verify that [DeviceRegistry.buildCapabilitySchema] produces
 * objects with all required keys (`action`, `params`, `returns`, `version`,
 * `exec_mode`) and that the `exec_mode` values are one of the three valid
 * constants defined on [DeviceRegistry].
 *
 * No Android [Context] is required because [DeviceRegistry.buildCapabilitySchema]
 * lives in the companion object and operates on a plain string input.
 */
class DeviceRegistrySchemaTest {

    private val validExecModes = setOf(
        DeviceRegistry.EXEC_MODE_LOCAL,
        DeviceRegistry.EXEC_MODE_REMOTE,
        DeviceRegistry.EXEC_MODE_BOTH
    )

    // ──────────────────────────────────────────────────────────────────────
    // Required keys
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `buildCapabilitySchema - screen_capture has all required keys`() {
        val schema = DeviceRegistry.buildCapabilitySchema("screen_capture")
        val json = schema.toJson()

        assertRequiredKeys(json)
        assertEquals("screen_capture", json.getString("action"))
    }

    @Test
    fun `buildCapabilitySchema - touch has all required keys and exec_mode local`() {
        val schema = DeviceRegistry.buildCapabilitySchema("touch")
        val json = schema.toJson()

        assertRequiredKeys(json)
        assertEquals("touch", json.getString("action"))
        assertEquals(DeviceRegistry.EXEC_MODE_LOCAL, json.getString("exec_mode"))
    }

    @Test
    fun `buildCapabilitySchema - natural_language has exec_mode both`() {
        val schema = DeviceRegistry.buildCapabilitySchema("natural_language")
        val json = schema.toJson()

        assertRequiredKeys(json)
        assertEquals(DeviceRegistry.EXEC_MODE_BOTH, json.getString("exec_mode"))
    }

    @Test
    fun `buildCapabilitySchema - unknown action falls back to exec_mode local`() {
        val schema = DeviceRegistry.buildCapabilitySchema("unknown_capability_xyz")
        val json = schema.toJson()

        assertRequiredKeys(json)
        assertEquals("unknown_capability_xyz", json.getString("action"))
        assertEquals(DeviceRegistry.EXEC_MODE_LOCAL, json.getString("exec_mode"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // exec_mode validity across all known capabilities
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `buildCapabilitySchema - exec_mode is always a valid constant`() {
        val knownCapabilities = listOf(
            "screen", "touch", "keyboard", "text_input",
            "ui_automation", "screen_capture", "app_control", "system_control",
            "gesture_simulation", "natural_language",
            "camera", "camera_front", "camera_back",
            "microphone", "voice_input",
            "bluetooth", "nfc", "gps", "location",
            "accelerometer", "gyroscope", "compass",
            "wifi", "mobile_data"
        )

        for (cap in knownCapabilities) {
            val schema = DeviceRegistry.buildCapabilitySchema(cap)
            val execMode = schema.execMode
            assertTrue(
                "exec_mode '$execMode' for capability '$cap' must be one of $validExecModes",
                execMode in validExecModes
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // params and returns are valid JSON objects
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `buildCapabilitySchema - params is a JSON object with type field`() {
        val schema = DeviceRegistry.buildCapabilitySchema("touch")
        assertTrue("params should have a 'type' key", schema.params.has("type"))
        assertEquals("object", schema.params.getString("type"))
    }

    @Test
    fun `buildCapabilitySchema - returns is a JSON object`() {
        val schema = DeviceRegistry.buildCapabilitySchema("screen_capture")
        assertNotNull("returns should not be null", schema.returns)
        assertTrue("returns should be a non-empty JSON object", schema.returns.length() > 0)
    }

    // ──────────────────────────────────────────────────────────────────────
    // toJson() serialisation
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `toJson - tags field absent when tags list is empty`() {
        val schema = DeviceRegistry.buildCapabilitySchema("unknown_action")
        val json = schema.toJson()
        assertFalse("tags should not be present when list is empty", json.has("tags"))
    }

    @Test
    fun `toJson - tags field present when schema has tags`() {
        val schema = DeviceRegistry.buildCapabilitySchema("touch")
        val json = schema.toJson()
        assertTrue("touch schema should include tags", json.has("tags"))
        val tags = json.getJSONArray("tags")
        assertTrue("tags should be non-empty", tags.length() > 0)
    }

    @Test
    fun `toJson - version field is non-empty string`() {
        val schema = DeviceRegistry.buildCapabilitySchema("wifi")
        val json = schema.toJson()
        assertTrue("version should be non-empty", json.getString("version").isNotEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────
    // exec_mode constants
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `EXEC_MODE constants have expected values`() {
        assertEquals("local", DeviceRegistry.EXEC_MODE_LOCAL)
        assertEquals("remote", DeviceRegistry.EXEC_MODE_REMOTE)
        assertEquals("both", DeviceRegistry.EXEC_MODE_BOTH)
    }

    // ──────────────────────────────────────────────────────────────────────
    // CapabilitySchema.toJson() round-trip
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `CapabilitySchema toJson round-trip preserves all fields`() {
        val schema = DeviceRegistry.buildCapabilitySchema("natural_language")
        val json = schema.toJson()

        assertEquals(schema.action, json.getString("action"))
        assertEquals(schema.version, json.getString("version"))
        assertEquals(schema.execMode, json.getString("exec_mode"))
        assertNotNull(json.getJSONObject("params"))
        assertNotNull(json.getJSONObject("returns"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun assertRequiredKeys(json: JSONObject) {
        listOf("action", "params", "returns", "version", "exec_mode").forEach { key ->
            assertTrue("Required key '$key' missing from schema", json.has(key))
        }
    }
}

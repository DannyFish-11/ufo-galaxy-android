package com.ufo.galaxy.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [AppSettings] and its implementations.
 *
 * Uses [InMemoryAppSettings] so no Android framework or [android.content.SharedPreferences]
 * is required. The [SharedPrefsAppSettings] companion object key constants are also
 * validated here to guard against accidental renames that would silently reset persisted
 * settings on existing devices.
 *
 * Verify manually: install the APK, toggle the cross-device switch in Settings, kill and
 * relaunch the app — the switch must retain the last-set state.
 */
class AppSettingsTest {

    // ── Default values ────────────────────────────────────────────────────────

    @Test
    fun `default crossDeviceEnabled is false`() {
        val settings = InMemoryAppSettings()
        assertFalse("crossDeviceEnabled must default to false", settings.crossDeviceEnabled)
    }

    @Test
    fun `default goalExecutionEnabled is false`() {
        val settings = InMemoryAppSettings()
        assertFalse(settings.goalExecutionEnabled)
    }

    @Test
    fun `default localModelEnabled is false`() {
        val settings = InMemoryAppSettings()
        assertFalse(settings.localModelEnabled)
    }

    @Test
    fun `default parallelExecutionEnabled is false`() {
        val settings = InMemoryAppSettings()
        assertFalse(settings.parallelExecutionEnabled)
    }

    @Test
    fun `default deviceRole is phone`() {
        val settings = InMemoryAppSettings()
        assertEquals(SharedPrefsAppSettings.DEFAULT_DEVICE_ROLE, settings.deviceRole)
    }

    @Test
    fun `default modelReady is false`() {
        val settings = InMemoryAppSettings()
        assertFalse(settings.modelReady)
    }

    @Test
    fun `default accessibilityReady is false`() {
        val settings = InMemoryAppSettings()
        assertFalse(settings.accessibilityReady)
    }

    @Test
    fun `default overlayReady is false`() {
        val settings = InMemoryAppSettings()
        assertFalse(settings.overlayReady)
    }

    @Test
    fun `degradedMode is true when all readiness flags are false`() {
        val settings = InMemoryAppSettings()
        assertTrue(settings.degradedMode)
    }

    @Test
    fun `degradedMode is false when all readiness flags are true`() {
        val settings = InMemoryAppSettings(
            modelReady = true,
            accessibilityReady = true,
            overlayReady = true
        )
        assertFalse(settings.degradedMode)
    }

    @Test
    fun `degradedMode is true when only one readiness flag is false`() {
        val settings = InMemoryAppSettings(
            modelReady = true,
            accessibilityReady = true,
            overlayReady = false
        )
        assertTrue(settings.degradedMode)
    }

    // ── Toggle persistence ────────────────────────────────────────────────────

    @Test
    fun `crossDeviceEnabled can be toggled on and off`() {
        val settings = InMemoryAppSettings()
        assertFalse(settings.crossDeviceEnabled)

        settings.crossDeviceEnabled = true
        assertTrue(settings.crossDeviceEnabled)

        settings.crossDeviceEnabled = false
        assertFalse(settings.crossDeviceEnabled)
    }

    @Test
    fun `goalExecutionEnabled persists value`() {
        val settings = InMemoryAppSettings()
        settings.goalExecutionEnabled = true
        assertTrue(settings.goalExecutionEnabled)
    }

    @Test
    fun `localModelEnabled persists value`() {
        val settings = InMemoryAppSettings()
        settings.localModelEnabled = true
        assertTrue(settings.localModelEnabled)
    }

    @Test
    fun `parallelExecutionEnabled persists value`() {
        val settings = InMemoryAppSettings()
        settings.parallelExecutionEnabled = true
        assertTrue(settings.parallelExecutionEnabled)
    }

    @Test
    fun `deviceRole persists custom value`() {
        val settings = InMemoryAppSettings()
        settings.deviceRole = "tablet"
        assertEquals("tablet", settings.deviceRole)
    }

    // ── toMetadataMap ─────────────────────────────────────────────────────────

    @Test
    fun `toMetadataMap includes all eight required capability report keys`() {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = true,
            goalExecutionEnabled = true,
            localModelEnabled = true,
            parallelExecutionEnabled = true,
            deviceRole = "tablet",
            modelReady = true,
            accessibilityReady = true,
            overlayReady = true
        )
        val map = settings.toMetadataMap()

        assertEquals(8, map.size)
        assertEquals(true, map["cross_device_enabled"])
        assertEquals(true, map["goal_execution_enabled"])
        assertEquals(true, map["local_model_enabled"])
        assertEquals(true, map["parallel_execution_enabled"])
        assertEquals("tablet", map["device_role"])
        assertEquals(true, map["model_ready"])
        assertEquals(true, map["accessibility_ready"])
        assertEquals(true, map["overlay_ready"])
    }

    @Test
    fun `toMetadataMap reflects default values`() {
        val settings = InMemoryAppSettings()
        val map = settings.toMetadataMap()

        assertEquals(false, map["cross_device_enabled"])
        assertEquals(false, map["goal_execution_enabled"])
        assertEquals(false, map["local_model_enabled"])
        assertEquals(false, map["parallel_execution_enabled"])
        assertEquals("phone", map["device_role"])
        assertEquals(false, map["model_ready"])
        assertEquals(false, map["accessibility_ready"])
        assertEquals(false, map["overlay_ready"])
    }

    @Test
    fun `toMetadataMap cross_device_enabled reflects toggled state`() {
        val settings = InMemoryAppSettings()
        assertFalse(settings.toMetadataMap()["cross_device_enabled"] as Boolean)

        settings.crossDeviceEnabled = true
        assertTrue(settings.toMetadataMap()["cross_device_enabled"] as Boolean)
    }

    // ── SharedPrefsAppSettings key constants ──────────────────────────────────

    @Test
    fun `SharedPrefsAppSettings key constants are stable`() {
        assertEquals("cross_device_enabled", SharedPrefsAppSettings.KEY_CROSS_DEVICE_ENABLED)
        assertEquals("goal_execution_enabled", SharedPrefsAppSettings.KEY_GOAL_EXECUTION_ENABLED)
        assertEquals("local_model_enabled", SharedPrefsAppSettings.KEY_LOCAL_MODEL_ENABLED)
        assertEquals("parallel_execution_enabled", SharedPrefsAppSettings.KEY_PARALLEL_EXECUTION_ENABLED)
        assertEquals("device_role", SharedPrefsAppSettings.KEY_DEVICE_ROLE)
        assertEquals("model_ready", SharedPrefsAppSettings.KEY_MODEL_READY)
        assertEquals("accessibility_ready", SharedPrefsAppSettings.KEY_ACCESSIBILITY_READY)
        assertEquals("overlay_ready", SharedPrefsAppSettings.KEY_OVERLAY_READY)
    }

    @Test
    fun `SharedPrefsAppSettings DEFAULT_DEVICE_ROLE is phone`() {
        assertEquals("phone", SharedPrefsAppSettings.DEFAULT_DEVICE_ROLE)
    }

    @Test
    fun `SharedPrefsAppSettings PREFS_NAME is stable`() {
        assertEquals("ufo_galaxy_settings", SharedPrefsAppSettings.PREFS_NAME)
    }

    // ── URL fields (P0: avoid hardcoded URLs) ─────────────────────────────────

    @Test
    fun `default galaxyGatewayUrl falls back to compile-time default`() {
        val settings = InMemoryAppSettings()
        assertEquals(SharedPrefsAppSettings.DEFAULT_GATEWAY_URL, settings.galaxyGatewayUrl)
    }

    @Test
    fun `default restBaseUrl falls back to compile-time default`() {
        val settings = InMemoryAppSettings()
        assertEquals(SharedPrefsAppSettings.DEFAULT_REST_BASE_URL, settings.restBaseUrl)
    }

    @Test
    fun `galaxyGatewayUrl can be overridden`() {
        val settings = InMemoryAppSettings()
        settings.galaxyGatewayUrl = "ws://192.168.1.42:8765"
        assertEquals("ws://192.168.1.42:8765", settings.galaxyGatewayUrl)
    }

    @Test
    fun `restBaseUrl can be overridden`() {
        val settings = InMemoryAppSettings()
        settings.restBaseUrl = "http://192.168.1.42:8765"
        assertEquals("http://192.168.1.42:8765", settings.restBaseUrl)
    }

    @Test
    fun `SharedPrefsAppSettings URL key constants are stable`() {
        assertEquals("galaxy_gateway_url", SharedPrefsAppSettings.KEY_GALAXY_GATEWAY_URL)
        assertEquals("rest_base_url", SharedPrefsAppSettings.KEY_REST_BASE_URL)
    }
}

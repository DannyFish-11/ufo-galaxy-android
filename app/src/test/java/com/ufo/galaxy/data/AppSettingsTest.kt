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

    // ── Network config key constants (网络与诊断增强包) ──────────────────────

    @Test
    fun `SharedPrefsAppSettings network config key constants are stable`() {
        assertEquals("gateway_host", SharedPrefsAppSettings.KEY_GATEWAY_HOST)
        assertEquals("gateway_port", SharedPrefsAppSettings.KEY_GATEWAY_PORT)
        assertEquals("use_tls", SharedPrefsAppSettings.KEY_USE_TLS)
        assertEquals("allow_self_signed", SharedPrefsAppSettings.KEY_ALLOW_SELF_SIGNED)
        assertEquals("device_id", SharedPrefsAppSettings.KEY_DEVICE_ID)
        assertEquals("metrics_endpoint", SharedPrefsAppSettings.KEY_METRICS_ENDPOINT)
    }

    @Test
    fun `DEFAULT_GATEWAY_PORT is 8765`() {
        assertEquals(8765, SharedPrefsAppSettings.DEFAULT_GATEWAY_PORT)
    }

    @Test
    fun `effectiveGatewayWsUrl falls back to galaxyGatewayUrl when host is blank`() {
        val settings = InMemoryAppSettings(galaxyGatewayUrl = "ws://10.0.0.1:9000")
        assertEquals("ws://10.0.0.1:9000/ws/android", settings.effectiveGatewayWsUrl())
    }

    @Test
    fun `effectiveGatewayWsUrl uses host+port when gatewayHost is set`() {
        val settings = InMemoryAppSettings(gatewayHost = "100.64.0.1", gatewayPort = 8765)
        assertEquals("ws://100.64.0.1:8765/ws/android", settings.effectiveGatewayWsUrl())
    }

    @Test
    fun `effectiveGatewayWsUrl uses wss scheme when useTls is true`() {
        val settings = InMemoryAppSettings(gatewayHost = "100.64.0.1", gatewayPort = 8765, useTls = true)
        assertEquals("wss://100.64.0.1:8765/ws/android", settings.effectiveGatewayWsUrl())
    }

    @Test
    fun `effectiveRestBaseUrl uses http+host when host is set`() {
        val settings = InMemoryAppSettings(gatewayHost = "100.64.0.1", gatewayPort = 8765, useTls = false)
        assertEquals("http://100.64.0.1:8765", settings.effectiveRestBaseUrl())
    }

    @Test
    fun `effectiveRestBaseUrl uses https when useTls is true`() {
        val settings = InMemoryAppSettings(gatewayHost = "100.64.0.1", gatewayPort = 8765, useTls = true)
        assertEquals("https://100.64.0.1:8765", settings.effectiveRestBaseUrl())
    }

    // ── Local-chain execution settings (PR-B) ────────────────────────────────

    @Test
    fun `default plannerMaxTokens matches compile-time fallback`() {
        val settings = InMemoryAppSettings()
        assertEquals(SharedPrefsAppSettings.DEFAULT_PLANNER_MAX_TOKENS, settings.plannerMaxTokens)
    }

    @Test
    fun `default plannerTemperature matches compile-time fallback`() {
        val settings = InMemoryAppSettings()
        assertEquals(SharedPrefsAppSettings.DEFAULT_PLANNER_TEMPERATURE, settings.plannerTemperature, 0.0001)
    }

    @Test
    fun `default plannerTimeoutMs matches compile-time fallback`() {
        val settings = InMemoryAppSettings()
        assertEquals(SharedPrefsAppSettings.DEFAULT_PLANNER_TIMEOUT_MS, settings.plannerTimeoutMs)
    }

    @Test
    fun `default groundingTimeoutMs matches compile-time fallback`() {
        val settings = InMemoryAppSettings()
        assertEquals(SharedPrefsAppSettings.DEFAULT_GROUNDING_TIMEOUT_MS, settings.groundingTimeoutMs)
    }

    @Test
    fun `default scaledMaxEdge matches compile-time fallback`() {
        val settings = InMemoryAppSettings()
        assertEquals(SharedPrefsAppSettings.DEFAULT_SCALED_MAX_EDGE, settings.scaledMaxEdge)
    }

    @Test
    fun `plannerMaxTokens can be overridden`() {
        val settings = InMemoryAppSettings()
        settings.plannerMaxTokens = 1024
        assertEquals(1024, settings.plannerMaxTokens)
    }

    @Test
    fun `plannerTemperature can be overridden`() {
        val settings = InMemoryAppSettings()
        settings.plannerTemperature = 0.7
        assertEquals(0.7, settings.plannerTemperature, 0.0001)
    }

    @Test
    fun `groundingTimeoutMs can be overridden`() {
        val settings = InMemoryAppSettings()
        settings.groundingTimeoutMs = 5000
        assertEquals(5000, settings.groundingTimeoutMs)
    }

    @Test
    fun `scaledMaxEdge zero disables downscaling`() {
        val settings = InMemoryAppSettings(scaledMaxEdge = 0)
        assertEquals(0, settings.scaledMaxEdge)
    }

    @Test
    fun `SharedPrefsAppSettings local-chain key constants are stable`() {
        assertEquals("planner_max_tokens", SharedPrefsAppSettings.KEY_PLANNER_MAX_TOKENS)
        assertEquals("planner_temperature", SharedPrefsAppSettings.KEY_PLANNER_TEMPERATURE)
        assertEquals("planner_timeout_ms", SharedPrefsAppSettings.KEY_PLANNER_TIMEOUT_MS)
        assertEquals("grounding_timeout_ms", SharedPrefsAppSettings.KEY_GROUNDING_TIMEOUT_MS)
        assertEquals("scaled_max_edge", SharedPrefsAppSettings.KEY_SCALED_MAX_EDGE)
    }

    @Test
    fun `compile-time fallback DEFAULT_PLANNER_MAX_TOKENS is 512`() {
        assertEquals(512, SharedPrefsAppSettings.DEFAULT_PLANNER_MAX_TOKENS)
    }

    @Test
    fun `compile-time fallback DEFAULT_PLANNER_TIMEOUT_MS is 30000`() {
        assertEquals(30_000, SharedPrefsAppSettings.DEFAULT_PLANNER_TIMEOUT_MS)
    }

    @Test
    fun `compile-time fallback DEFAULT_GROUNDING_TIMEOUT_MS is 15000`() {
        assertEquals(15_000, SharedPrefsAppSettings.DEFAULT_GROUNDING_TIMEOUT_MS)
    }

    @Test
    fun `compile-time fallback DEFAULT_SCALED_MAX_EDGE is 720`() {
        assertEquals(720, SharedPrefsAppSettings.DEFAULT_SCALED_MAX_EDGE)
    }

    // ── H1: /ws/android path appending ───────────────────────────────────────

    @Test
    fun `effectiveGatewayWsUrl does not duplicate path when already present`() {
        val settings = InMemoryAppSettings(galaxyGatewayUrl = "ws://10.0.0.1:9000/ws/android")
        assertEquals("ws://10.0.0.1:9000/ws/android", settings.effectiveGatewayWsUrl())
    }

    @Test
    fun `effectiveGatewayWsUrl respects custom path configured by user`() {
        val settings = InMemoryAppSettings(galaxyGatewayUrl = "ws://10.0.0.1:9000/custom/path")
        assertEquals("ws://10.0.0.1:9000/custom/path", settings.effectiveGatewayWsUrl())
    }

    // ── H3: gatewayToken key constant ─────────────────────────────────────────

    @Test
    fun `SharedPrefsAppSettings gateway token key constant is stable`() {
        assertEquals("gateway_token", SharedPrefsAppSettings.KEY_GATEWAY_TOKEN)
    }

    @Test
    fun `default gatewayToken is blank`() {
        val settings = InMemoryAppSettings()
        assertEquals("", settings.gatewayToken)
    }

    @Test
    fun `gatewayToken can be set`() {
        val settings = InMemoryAppSettings()
        settings.gatewayToken = "test-token-abc"
        assertEquals("test-token-abc", settings.gatewayToken)
    }
}

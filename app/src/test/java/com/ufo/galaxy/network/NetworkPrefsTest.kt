package com.ufo.galaxy.network

import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.data.SharedPrefsAppSettings
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the network-config extensions added by the 网络与诊断增强包.
 *
 * Tests cover:
 * - Default values for new AppSettings fields
 * - Config priority / URL building (host+port+tls → effectiveGatewayWsUrl / effectiveRestBaseUrl)
 * - Self-signed flag propagation
 * - SharedPrefsAppSettings key constant stability
 * - MetricsRecorder counter logic
 * - NetworkDiagnostics.CheckResult data class
 */
class NetworkPrefsTest {

    // ── Default values ────────────────────────────────────────────────────────

    @Test
    fun `default gatewayHost is empty`() {
        val s = InMemoryAppSettings()
        assertEquals("", s.gatewayHost)
    }

    @Test
    fun `default gatewayPort is 8765`() {
        val s = InMemoryAppSettings()
        assertEquals(SharedPrefsAppSettings.DEFAULT_GATEWAY_PORT, s.gatewayPort)
    }

    @Test
    fun `default useTls is false`() {
        val s = InMemoryAppSettings()
        assertFalse(s.useTls)
    }

    @Test
    fun `default allowSelfSigned is false`() {
        val s = InMemoryAppSettings()
        assertFalse(s.allowSelfSigned)
    }

    @Test
    fun `default deviceId is empty`() {
        val s = InMemoryAppSettings()
        assertEquals("", s.deviceId)
    }

    @Test
    fun `default metricsEndpoint is empty`() {
        val s = InMemoryAppSettings()
        assertEquals("", s.metricsEndpoint)
    }

    // ── effectiveGatewayWsUrl — priority logic ────────────────────────────────

    @Test
    fun `effectiveGatewayWsUrl uses galaxyGatewayUrl when gatewayHost is blank`() {
        val s = InMemoryAppSettings(galaxyGatewayUrl = "ws://192.168.1.1:9000")
        // gatewayHost is blank → fall back to galaxyGatewayUrl
        assertEquals("ws://192.168.1.1:9000", s.effectiveGatewayWsUrl())
    }

    @Test
    fun `effectiveGatewayWsUrl builds ws URL from host+port when host is set`() {
        val s = InMemoryAppSettings(gatewayHost = "100.64.0.1", gatewayPort = 8765, useTls = false)
        assertEquals("ws://100.64.0.1:8765", s.effectiveGatewayWsUrl())
    }

    @Test
    fun `effectiveGatewayWsUrl builds wss URL when useTls is true`() {
        val s = InMemoryAppSettings(gatewayHost = "100.64.0.1", gatewayPort = 8765, useTls = true)
        assertEquals("wss://100.64.0.1:8765", s.effectiveGatewayWsUrl())
    }

    @Test
    fun `effectiveGatewayWsUrl host takes priority over galaxyGatewayUrl`() {
        val s = InMemoryAppSettings(
            galaxyGatewayUrl = "ws://old-host:9999",
            gatewayHost = "100.64.0.2",
            gatewayPort = 8765
        )
        // host is set → ignore galaxyGatewayUrl
        assertEquals("ws://100.64.0.2:8765", s.effectiveGatewayWsUrl())
    }

    // ── effectiveRestBaseUrl — priority logic ─────────────────────────────────

    @Test
    fun `effectiveRestBaseUrl uses restBaseUrl when gatewayHost is blank`() {
        val s = InMemoryAppSettings(restBaseUrl = "http://192.168.1.1:9000")
        assertEquals("http://192.168.1.1:9000", s.effectiveRestBaseUrl())
    }

    @Test
    fun `effectiveRestBaseUrl builds http URL from host+port when host is set`() {
        val s = InMemoryAppSettings(gatewayHost = "100.64.0.1", gatewayPort = 8765, useTls = false)
        assertEquals("http://100.64.0.1:8765", s.effectiveRestBaseUrl())
    }

    @Test
    fun `effectiveRestBaseUrl builds https URL when useTls is true`() {
        val s = InMemoryAppSettings(gatewayHost = "100.64.0.1", gatewayPort = 8765, useTls = true)
        assertEquals("https://100.64.0.1:8765", s.effectiveRestBaseUrl())
    }

    // ── allowSelfSigned flag ──────────────────────────────────────────────────

    @Test
    fun `allowSelfSigned can be toggled`() {
        val s = InMemoryAppSettings()
        assertFalse(s.allowSelfSigned)
        s.allowSelfSigned = true
        assertTrue(s.allowSelfSigned)
        s.allowSelfSigned = false
        assertFalse(s.allowSelfSigned)
    }

    @Test
    fun `allowSelfSigned does not affect URL scheme selection`() {
        // allowSelfSigned only affects OkHttp cert validation; useTls controls scheme
        val s = InMemoryAppSettings(gatewayHost = "100.64.0.1", gatewayPort = 8765,
            useTls = false, allowSelfSigned = true)
        // scheme is still ws:// because useTls=false
        assertTrue(s.effectiveGatewayWsUrl().startsWith("ws://"))
    }

    // ── Field persistence ─────────────────────────────────────────────────────

    @Test
    fun `gatewayHost persists value`() {
        val s = InMemoryAppSettings()
        s.gatewayHost = "100.64.0.5"
        assertEquals("100.64.0.5", s.gatewayHost)
    }

    @Test
    fun `gatewayPort persists value`() {
        val s = InMemoryAppSettings()
        s.gatewayPort = 9090
        assertEquals(9090, s.gatewayPort)
    }

    @Test
    fun `deviceId persists value`() {
        val s = InMemoryAppSettings()
        s.deviceId = "my-android-phone"
        assertEquals("my-android-phone", s.deviceId)
    }

    @Test
    fun `metricsEndpoint persists value`() {
        val s = InMemoryAppSettings()
        s.metricsEndpoint = "http://100.64.0.1:9090/metrics"
        assertEquals("http://100.64.0.1:9090/metrics", s.metricsEndpoint)
    }

    // ── SharedPrefsAppSettings key constant stability ─────────────────────────

    @Test
    fun `network config key constants are stable`() {
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
}

/**
 * Unit tests for [NetworkDiagnostics.CheckResult].
 */
class NetworkDiagnosticsCheckResultTest {

    @Test
    fun `CheckResult ok is true when successful`() {
        val r = NetworkDiagnostics.CheckResult("DNS", true, 42L, "→ 100.64.0.1")
        assertTrue(r.ok)
        assertEquals("DNS", r.name)
        assertEquals(42L, r.durationMs)
    }

    @Test
    fun `CheckResult ok is false when failed`() {
        val r = NetworkDiagnostics.CheckResult("HTTP /health", false, 150L, "Connection refused")
        assertFalse(r.ok)
        assertEquals("Connection refused", r.detail)
    }

    @Test
    fun `DiagnosticsReport allOk is true when all checks pass`() {
        val checks = listOf(
            NetworkDiagnostics.CheckResult("DNS", true, 10, "ok"),
            NetworkDiagnostics.CheckResult("HTTP", true, 20, "HTTP 200")
        )
        val report = NetworkDiagnostics.DiagnosticsReport(
            gatewayHost = "100.64.0.1", gatewayPort = 8765, useTls = false, checks = checks
        )
        assertTrue(report.allOk)
    }

    @Test
    fun `DiagnosticsReport allOk is false when any check fails`() {
        val checks = listOf(
            NetworkDiagnostics.CheckResult("DNS", true, 10, "ok"),
            NetworkDiagnostics.CheckResult("HTTP", false, 200, "timeout")
        )
        val report = NetworkDiagnostics.DiagnosticsReport(
            gatewayHost = "100.64.0.1", gatewayPort = 8765, useTls = false, checks = checks
        )
        assertFalse(report.allOk)
    }

    @Test
    fun `DiagnosticsReport toText contains gateway address`() {
        val report = NetworkDiagnostics.DiagnosticsReport(
            gatewayHost = "100.64.0.5", gatewayPort = 8080, useTls = true,
            checks = emptyList()
        )
        val text = report.toText()
        assertTrue("toText should contain host", text.contains("100.64.0.5"))
        assertTrue("toText should contain port", text.contains("8080"))
    }
}

/**
 * Unit tests for [com.ufo.galaxy.observability.MetricsRecorder] counters.
 */
class MetricsRecorderCounterTest {

    private fun makeSettings() = InMemoryAppSettings()

    @Test
    fun `wsReconnects starts at 0`() {
        val m = com.ufo.galaxy.observability.MetricsRecorder(makeSettings())
        assertEquals(0, m.wsReconnects.get())
    }

    @Test
    fun `recordWsReconnect increments counter`() {
        val m = com.ufo.galaxy.observability.MetricsRecorder(makeSettings())
        m.recordWsReconnect()
        m.recordWsReconnect()
        assertEquals(2, m.wsReconnects.get())
    }

    @Test
    fun `recordRegistrationFailure increments counter`() {
        val m = com.ufo.galaxy.observability.MetricsRecorder(makeSettings())
        m.recordRegistrationFailure()
        assertEquals(1, m.registrationFailures.get())
    }

    @Test
    fun `recordTaskSuccess and recordTaskFailure increment independently`() {
        val m = com.ufo.galaxy.observability.MetricsRecorder(makeSettings())
        m.recordTaskSuccess()
        m.recordTaskSuccess()
        m.recordTaskFailure()
        assertEquals(2, m.taskSuccesses.get())
        assertEquals(1, m.taskFailures.get())
    }

    @Test
    fun `snapshot contains all metric keys`() {
        val m = com.ufo.galaxy.observability.MetricsRecorder(makeSettings())
        m.recordWsReconnect()
        m.recordTaskSuccess()
        val snap = m.snapshot()
        assertEquals(1, snap.getInt("ws_reconnects"))
        assertEquals(0, snap.getInt("registration_failures"))
        assertEquals(1, snap.getInt("task_successes"))
        assertEquals(0, snap.getInt("task_failures"))
        assertTrue("snapshot should have uptime_ms", snap.has("uptime_ms"))
        assertTrue("snapshot should have ts", snap.has("ts"))
    }
}

/**
 * Tests that [GalaxyWebSocketClient.buildOkHttpClient] does not throw
 * for both allowSelfSigned=false and allowSelfSigned=true.
 */
class OkHttpClientBuilderTest {

    @Test
    fun `buildOkHttpClient with allowSelfSigned=false succeeds`() {
        val client = GalaxyWebSocketClient.buildOkHttpClient(allowSelfSigned = false)
        assertNotNull(client)
    }

    @Test
    fun `buildOkHttpClient with allowSelfSigned=true succeeds`() {
        val client = GalaxyWebSocketClient.buildOkHttpClient(allowSelfSigned = true)
        assertNotNull(client)
    }
}

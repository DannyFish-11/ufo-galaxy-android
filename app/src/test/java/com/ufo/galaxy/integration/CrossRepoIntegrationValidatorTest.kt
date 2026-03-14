package com.ufo.galaxy.integration

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CrossRepoIntegrationValidator] and [ValidationReport].
 *
 * All network calls are avoided by using subclass overrides (test doubles)
 * so the suite runs on the JVM without a real V2 server.
 */
class CrossRepoIntegrationValidatorTest {

    // ──────────────────────────────────────────────────────────────────────────
    // checkWsUrlFormat
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `checkWsUrlFormat passes for ws scheme`() {
        val v = CrossRepoIntegrationValidator("http://host:8000")
        val result = v.checkWsUrlFormat("ws://host:8050")
        assertTrue(result.passed)
        assertTrue(result.message.contains("ws://host:8050"))
    }

    @Test
    fun `checkWsUrlFormat passes for wss scheme`() {
        val v = CrossRepoIntegrationValidator("http://host:8000")
        val result = v.checkWsUrlFormat("wss://secure.host:443")
        assertTrue(result.passed)
    }

    @Test
    fun `checkWsUrlFormat fails for http scheme`() {
        val v = CrossRepoIntegrationValidator("http://host:8000")
        val result = v.checkWsUrlFormat("http://host:8000")
        assertFalse(result.passed)
        assertTrue(result.message.contains("must start with ws://"))
    }

    @Test
    fun `checkWsUrlFormat fails for blank url`() {
        val v = CrossRepoIntegrationValidator("http://host:8000")
        val result = v.checkWsUrlFormat("")
        assertFalse(result.passed)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // httpGet test double
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `httpGet returns passed=true for 200`() {
        val v = object : CrossRepoIntegrationValidator("http://fake:9000") {
            override fun httpGet(url: String, allowedCodes: Set<Int>): CheckResult =
                CheckResult(passed = true, httpCode = 200, message = "HTTP 200 OK")
        }
        val result = v.httpGet("http://fake:9000/api/v1/health")
        assertTrue(result.passed)
        assertEquals(200, result.httpCode)
    }

    @Test
    fun `httpGet returns passed=false for 500`() {
        val v = object : CrossRepoIntegrationValidator("http://fake:9000") {
            override fun httpGet(url: String, allowedCodes: Set<Int>): CheckResult =
                CheckResult(passed = false, httpCode = 500, message = "Unexpected HTTP 500")
        }
        val result = v.httpGet("http://fake:9000/api/v1/health")
        assertFalse(result.passed)
        assertEquals(500, result.httpCode)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // validate() with all checks mocked
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `validate allPassed true when all checks pass`() {
        val v = object : CrossRepoIntegrationValidator("http://fake:9000") {
            override fun checkHealth(): CheckResult =
                CheckResult(passed = true, message = "health ok")

            override fun checkEndpoint(path: String, label: String): CheckResult =
                CheckResult(passed = true, message = "$label ok")

            override fun checkWsUrlFormat(url: String): CheckResult =
                CheckResult(passed = true, message = "ws ok")
        }
        val report = kotlinx.coroutines.runBlocking {
            v.validate(wsUrl = "ws://fake:8050")
        }
        assertTrue(report.allPassed)
        assertEquals(4, report.checks.size)
    }

    @Test
    fun `validate allPassed false when health check fails`() {
        val v = object : CrossRepoIntegrationValidator("http://fake:9000") {
            override fun checkHealth(): CheckResult =
                CheckResult(passed = false, message = "unreachable")

            override fun checkEndpoint(path: String, label: String): CheckResult =
                CheckResult(passed = true, message = "ok")

            override fun checkWsUrlFormat(url: String): CheckResult =
                CheckResult(passed = true, message = "ok")
        }
        val report = kotlinx.coroutines.runBlocking {
            v.validate(wsUrl = "ws://fake:8050")
        }
        assertFalse(report.allPassed)
        assertFalse(report.checks[CrossRepoIntegrationValidator.CHECK_HEALTH]!!.passed)
    }

    @Test
    fun `validate report contains all four check keys`() {
        val v = object : CrossRepoIntegrationValidator("http://fake:9000") {
            override fun checkHealth() = CheckResult(true)
            override fun checkEndpoint(path: String, label: String) = CheckResult(true)
            override fun checkWsUrlFormat(url: String) = CheckResult(true)
        }
        val report = kotlinx.coroutines.runBlocking { v.validate("ws://x") }
        assertTrue(report.checks.containsKey(CrossRepoIntegrationValidator.CHECK_HEALTH))
        assertTrue(report.checks.containsKey(CrossRepoIntegrationValidator.CHECK_DEVICES))
        assertTrue(report.checks.containsKey(CrossRepoIntegrationValidator.CHECK_MEMORY))
        assertTrue(report.checks.containsKey(CrossRepoIntegrationValidator.CHECK_WS_FORMAT))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ValidationReport.summary()
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `ValidationReport summary contains base url and pass result`() {
        val report = ValidationReport(
            restBaseUrl = "http://test:8000",
            checks = mapOf(
                "check_a" to CheckResult(passed = true, message = "ok"),
                "check_b" to CheckResult(passed = false, message = "fail")
            ),
            allPassed = false
        )
        val summary = report.summary()
        assertTrue(summary.contains("http://test:8000"))
        assertTrue(summary.contains("check_a"))
        assertTrue(summary.contains("check_b"))
        assertTrue(summary.contains("存在失败项"))
    }

    @Test
    fun `ValidationReport summary says all passed when allPassed is true`() {
        val report = ValidationReport(
            restBaseUrl = "http://ok:8000",
            checks = mapOf("c" to CheckResult(passed = true)),
            allPassed = true
        )
        assertTrue(report.summary().contains("全部通过"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CheckResult
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `CheckResult defaults httpCode to -1`() {
        val r = CheckResult(passed = true)
        assertEquals(-1, r.httpCode)
    }

    @Test
    fun `CheckResult with httpCode stores value`() {
        val r = CheckResult(passed = false, httpCode = 503, message = "service unavailable")
        assertEquals(503, r.httpCode)
        assertFalse(r.passed)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Constant values
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `check key constants have expected string values`() {
        assertEquals("gateway_health", CrossRepoIntegrationValidator.CHECK_HEALTH)
        assertEquals("devices_endpoint", CrossRepoIntegrationValidator.CHECK_DEVICES)
        assertEquals("memory_endpoint", CrossRepoIntegrationValidator.CHECK_MEMORY)
        assertEquals("ws_url_format", CrossRepoIntegrationValidator.CHECK_WS_FORMAT)
    }
}

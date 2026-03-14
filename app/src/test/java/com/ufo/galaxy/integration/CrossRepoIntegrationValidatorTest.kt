package com.ufo.galaxy.integration

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [CrossRepoIntegrationValidator] (P3).
 *
 * All HTTP calls are intercepted via a custom [OkHttpClient] interceptor so that no
 * real network I/O occurs.  Only the WS URL format check is truly I/O-free.
 *
 * Test matrix:
 *  - All checks pass → [ValidationReport.allPassed] true, [ValidationReport.summary] ends with "OK".
 *  - One endpoint returns a non-2xx response → corresponding check fails.
 *  - WS URL with valid scheme (ws://) → passes.
 *  - WS URL with invalid scheme → fails with descriptive error.
 *  - All checks fail → failedCount == 4, summary reports all failures.
 */
class CrossRepoIntegrationValidatorTest {

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Builds a fake [OkHttpClient] that returns [code] for every request.
     */
    private fun fakeClient(code: Int, body: String = "{}"): OkHttpClient {
        val interceptor = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    /** Builds a validator using the supplied HTTP client and WS URL. */
    private fun validator(
        client: OkHttpClient = fakeClient(200),
        wsUrl: String = "ws://100.0.0.1:8765"
    ) = CrossRepoIntegrationValidator(
        restBaseUrl = "http://100.0.0.1:8765",
        wsUrl = wsUrl,
        httpClient = client
    )

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    fun `all checks pass when server returns 200 and WS URL is valid`() {
        val report = validator(fakeClient(200), "ws://100.0.0.1:8765").validate()

        assertTrue("allPassed must be true when all checks succeed", report.allPassed)
        assertEquals("All 4 checks should pass", 4, report.passedCount)
        assertEquals(0, report.failedCount)
        assertTrue("summary should end with OK", report.summary().endsWith("OK"))
    }

    @Test
    fun `summary reports correct passed count on full success`() {
        val report = validator().validate()
        assertTrue(report.summary().startsWith("Integration: 4/4 passed"))
    }

    // ── Partial failures ───────────────────────────────────────────────────────

    @Test
    fun `health check fails when server returns 500`() {
        val report = validator(fakeClient(500)).validate()

        val healthCheck = report.results.first { it.name == "GET /api/v1/health" }
        assertFalse("health check should fail on 500", healthCheck.passed)
        assertEquals(500, healthCheck.httpStatus)
        assertNotNull("error message should be populated", healthCheck.error)
    }

    @Test
    fun `devices list check fails when server returns 404`() {
        val report = validator(fakeClient(404)).validate()

        val devicesCheck = report.results.first { it.name == "GET /api/v1/devices/list" }
        assertFalse("devices list check should fail on 404", devicesCheck.passed)
    }

    @Test
    fun `memory store check passes on 422 Unprocessable Entity`() {
        // 422 means the endpoint exists but rejected the dry-run body schema — acceptable.
        val report = validator(fakeClient(422)).validate()

        val memCheck = report.results.first { it.name == "POST /api/v1/memory/store" }
        assertTrue("memory store check should pass on 422", memCheck.passed)
    }

    // ── WS URL format ─────────────────────────────────────────────────────────

    @Test
    fun `WS URL format check passes for ws scheme`() {
        val report = validator(wsUrl = "ws://192.168.1.1:8765").validate()
        val wsCheck = report.results.first { it.name == "WS URL format" }
        assertTrue("ws:// URL should pass format check", wsCheck.passed)
    }

    @Test
    fun `WS URL format check passes for wss scheme`() {
        val report = validator(wsUrl = "wss://example.com/ws").validate()
        val wsCheck = report.results.first { it.name == "WS URL format" }
        assertTrue("wss:// URL should pass format check", wsCheck.passed)
    }

    @Test
    fun `WS URL format check passes for short host`() {
        // Regression: old pattern required >=2 chars after scheme; fixed to accept any non-empty host.
        val report = validator(wsUrl = "ws://a").validate()
        val wsCheck = report.results.first { it.name == "WS URL format" }
        assertTrue("short ws:// URL should pass format check", wsCheck.passed)
    }

    @Test
    fun `WS URL format check fails for http scheme`() {
        val report = validator(wsUrl = "http://100.0.0.1:8765").validate()
        val wsCheck = report.results.first { it.name == "WS URL format" }
        assertFalse("http:// URL should fail WS format check", wsCheck.passed)
        assertNotNull("error should describe the problem", wsCheck.error)
        assertTrue("error should mention ws://", wsCheck.error!!.contains("ws://"))
    }

    @Test
    fun `WS URL format check fails for blank URL`() {
        val report = validator(wsUrl = "").validate()
        val wsCheck = report.results.first { it.name == "WS URL format" }
        assertFalse("blank URL should fail WS format check", wsCheck.passed)
    }

    // ── ValidationReport helpers ───────────────────────────────────────────────

    @Test
    fun `summary lists failed check names when some checks fail`() {
        val report = validator(fakeClient(500), wsUrl = "bad-url").validate()

        val summary = report.summary()
        assertTrue("summary should contain FAIL marker", summary.contains("FAIL"))
        assertTrue("summary should mention health check", summary.contains("GET /api/v1/health"))
        assertTrue("summary should mention WS format check", summary.contains("WS URL format"))
    }

    @Test
    fun `ValidationReport passedCount and failedCount are consistent`() {
        val report = validator(fakeClient(200), "ws://ok").validate()
        assertEquals(report.results.size, report.passedCount + report.failedCount)
    }

    @Test
    fun `results list has exactly 4 entries`() {
        val report = validator().validate()
        assertEquals("validate() must always produce exactly 4 check results", 4, report.results.size)
    }

    // ── CheckResult fields ─────────────────────────────────────────────────────

    @Test
    fun `CheckResult httpStatus is null for non-HTTP checks`() {
        val report = validator(wsUrl = "ws://ok").validate()
        val wsCheck = report.results.first { it.name == "WS URL format" }
        assertNull("WS format check has no HTTP status", wsCheck.httpStatus)
    }

    @Test
    fun `CheckResult httpStatus is populated for HTTP checks`() {
        val report = validator(fakeClient(200)).validate()
        val healthCheck = report.results.first { it.name == "GET /api/v1/health" }
        assertEquals(200, healthCheck.httpStatus)
    }
}

package com.ufo.galaxy.integration

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Cross-repo integration validator (P3).
 *
 * Validates that the Galaxy gateway REST and WebSocket endpoints are reachable and
 * respond with acceptable status codes.  All checks are network I/O operations and
 * **must** be called from a background thread or coroutine.
 *
 * Checks performed:
 *  1. `GET  /api/v1/health`         — gateway liveness ping.
 *  2. `GET  /api/v1/config`         — config discovery endpoint (M3/M7).
 *  3. `GET  /api/v1/devices/list`   — device registry reachability.
 *  4. `POST /api/v1/memory/store`   — memory endpoint write access (dry-run body).
 *  5. WS URL format validation      — [wsUrl] must be a valid ws:// or wss:// URL.
 *
 * Usage:
 * ```kotlin
 * val validator = CrossRepoIntegrationValidator(restBaseUrl, wsUrl)
 * val report = validator.validate()          // blocks; run in IO coroutine
 * Log.i(TAG, report.summary())
 * ```
 *
 * @param restBaseUrl  REST base URL, e.g. `"http://100.0.0.1:8765"` (no trailing slash).
 * @param wsUrl        WebSocket URL, e.g. `"ws://100.0.0.1:8765/ws/device/android-1"`.
 * @param httpClient   Optional [OkHttpClient]; a default client is created if not supplied.
 *                     Inject a custom client in tests to avoid real network calls.
 */
class CrossRepoIntegrationValidator(
    private val restBaseUrl: String,
    private val wsUrl: String,
    private val httpClient: OkHttpClient = defaultClient()
) {

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Executes all four validation checks and returns a [ValidationReport].
     *
     * Never throws; individual check errors are captured in [CheckResult.error].
     */
    fun validate(): ValidationReport {
        val results = mutableListOf<CheckResult>()
        results += checkHealth()
        results += checkConfig()
        results += checkDevicesList()
        results += checkMemoryStore()
        results += checkWsUrlFormat()
        return ValidationReport(results = results)
    }

    // ── Individual checks ─────────────────────────────────────────────────────

    private fun checkHealth(): CheckResult {
        val url = "${restBaseUrl.trimEnd('/')}/api/v1/health"
        return runGetCheck(name = "GET /api/v1/health", url = url,
            missingHint = "Server liveness ping failed — ensure gateway is running")
    }

    private fun checkConfig(): CheckResult {
        val url = "${restBaseUrl.trimEnd('/')}/api/v1/config"
        return runGetCheck(name = "GET /api/v1/config", url = url,
            missingHint = "Config discovery endpoint missing — server may not expose /api/v1/config")
    }

    private fun checkDevicesList(): CheckResult {
        val url = "${restBaseUrl.trimEnd('/')}/api/v1/devices/list"
        return runGetCheck(name = "GET /api/v1/devices/list", url = url,
            missingHint = "Device registry endpoint unreachable — check /api/v1/devices/* routes on server")
    }

    private fun checkMemoryStore(): CheckResult {
        val url = "${restBaseUrl.trimEnd('/')}/api/v1/memory/store"
        val body = """{"task_id":"validator-probe","goal":"probe","status":"ok","summary":"dry-run probe","route_mode":"local","timestamp_ms":${System.currentTimeMillis()}}"""
        return try {
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 422) {
                    // 422 Unprocessable Entity is acceptable here — it means the endpoint
                    // exists and validated the schema; the dry-run body may be rejected.
                    CheckResult(name = "POST /api/v1/memory/store", passed = true,
                        httpStatus = response.code)
                } else {
                    CheckResult(name = "POST /api/v1/memory/store", passed = false,
                        httpStatus = response.code,
                        error = "unexpected HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "POST /api/v1/memory/store failed: ${e.message}")
            CheckResult(name = "POST /api/v1/memory/store", passed = false,
                error = e.message ?: "unknown error")
        }
    }

    private fun checkWsUrlFormat(): CheckResult {
        val hasValidScheme = WS_URL_PATTERN.matcher(wsUrl).matches()
        val canonicalPath = wsUrl.contains("/ws/device/")
        val valid = hasValidScheme && canonicalPath
        return if (valid) {
            CheckResult(name = "WS URL format (/ws/device/{device_id})", passed = true)
        } else {
            CheckResult(
                name = "WS URL format (/ws/device/{device_id})",
                passed = false,
                error = "URL must start with ws:// or wss:// and include canonical /ws/device/{device_id} path — got: $wsUrl"
            )
        }
    }

    private fun runGetCheck(name: String, url: String, missingHint: String? = null): CheckResult {
        return try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    CheckResult(name = name, passed = true, httpStatus = response.code)
                } else {
                    val baseError = "unexpected HTTP ${response.code}"
                    val error = if (missingHint != null && response.code == 404)
                        "$baseError — $missingHint"
                    else baseError
                    CheckResult(name = name, passed = false, httpStatus = response.code, error = error)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$name failed: ${e.message}")
            CheckResult(name = name, passed = false,
                error = (e.message ?: "unknown error").let {
                    if (missingHint != null) "$it — $missingHint" else it
                })
        }
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    /**
     * Result for a single validation check.
     *
     * @param name       Human-readable check label.
     * @param passed     Whether the check succeeded.
     * @param httpStatus HTTP response status code, or null for non-HTTP checks.
     * @param error      Error description when [passed] is false.
     */
    data class CheckResult(
        val name: String,
        val passed: Boolean,
        val httpStatus: Int? = null,
        val error: String? = null
    )

    /**
     * Aggregated validation report produced by [validate].
     *
     * @param results  Ordered list of [CheckResult] entries, one per check.
     */
    data class ValidationReport(val results: List<CheckResult>) {

        /** True when every check in [results] passed. */
        val allPassed: Boolean get() = results.all { it.passed }

        /** Number of checks that passed. */
        val passedCount: Int get() = results.count { it.passed }

        /** Number of checks that failed. */
        val failedCount: Int get() = results.count { !it.passed }

        /**
         * Human-readable one-line summary suitable for UI display or logging.
         *
         * Example output:
         * ```
         * Integration: 3/4 passed — FAIL: WS URL format (URL must start with ws://)
         * ```
         */
        fun summary(): String {
            val total = results.size
            val passed = passedCount
            val prefix = "Integration: $passed/$total passed"
            val failures = results.filter { !it.passed }
                .joinToString("; ") { r ->
                    "${r.name}" + if (r.error != null) " (${r.error})" else ""
                }
            return if (failures.isEmpty()) "$prefix — OK" else "$prefix — FAIL: $failures"
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "CrossRepoValidator"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val WS_URL_PATTERN: Pattern =
            Pattern.compile("^wss?://\\S+$", Pattern.CASE_INSENSITIVE)

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}

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
 *  3. `GET  /api/v1/devices`        — device registry reachability.
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
 * @param restBaseUrl  REST base URL, e.g. `"http://100.0.0.1:9000"` (no trailing slash).
 * @param wsUrl        WebSocket URL, e.g. `"ws://100.0.0.1:9000/ws/device/android-1"`.
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

    // 这三条此前分别打 /api/v1/health、/api/v1/config、/api/v1/devices/list ——
    // **三条在 V2 服务端都不存在**,于是这个"跨仓集成校验器"的四项检查里有三项恒判失败。
    // 一个恒报失败的校验器比没有校验器更糟:它不再区分"今天真的坏了"和"一直就这样",
    // 于是所有人学会无视它的结论。
    //
    // 实测(对着 core.api_routes 组装出的 350 条权威路由逐条打真实 HTTP):
    //   GET /api/v1/health        → 404   实际存在的是 /api/v1/health/quick 与 /unified
    //   GET /api/v1/config        → 404   配置值在 /api/config;/api/v1/config/status 是
    //                                     **配置管理器状态**,与配置值不是一回事,不能拿来顶替
    //   GET /api/v1/devices/list  → 404   实际存在的是 /api/v1/devices(与 /api/devices/list)
    private fun checkHealth(): CheckResult {
        val url = "${restBaseUrl.trimEnd('/')}$HEALTH_PATH"
        return runGetCheck(name = "GET $HEALTH_PATH", url = url,
            missingHint = "Server liveness ping failed — ensure gateway is running")
    }

    private fun checkConfig(): CheckResult {
        val url = "${restBaseUrl.trimEnd('/')}$CONFIG_PATH"
        return runGetCheck(name = "GET $CONFIG_PATH", url = url,
            missingHint = "Config discovery endpoint missing — server may not expose $CONFIG_PATH")
    }

    private fun checkDevicesList(): CheckResult {
        val url = "${restBaseUrl.trimEnd('/')}$DEVICES_PATH"
        return runGetCheck(name = "GET $DEVICES_PATH", url = url,
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
        val canonicalPath = WS_CANONICAL_PATH_PATTERN.matcher(wsUrl).matches()
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

        // 服务端权威路径。抽成常量是为了让"这条路径还在不在"能被单测直接钉住 ——
        // 散在方法体里的字符串字面量,漂了没人看得见(这正是它们此前漂掉的原因)。
        /** 存活探针。V2 只有 /quick 与 /unified,没有裸 /api/v1/health。 */
        const val HEALTH_PATH = "/api/v1/health/quick"
        /** 配置值。注意不是 /api/v1/config/status —— 那是配置管理器状态,不是配置本身。 */
        const val CONFIG_PATH = "/api/config"
        /** 设备清单。V2 是 /api/v1/devices,没有 /api/v1/devices/list。 */
        const val DEVICES_PATH = "/api/v1/devices"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val WS_URL_PATTERN: Pattern =
            Pattern.compile("^wss?://\\S+$", Pattern.CASE_INSENSITIVE)
        private val WS_CANONICAL_PATH_PATTERN: Pattern =
            Pattern.compile("^wss?://[^\\s]+/ws/device/[^/\\s]+/?$", Pattern.CASE_INSENSITIVE)

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}

package com.ufo.galaxy.integration

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 跨仓联调验证器（CrossRepoIntegrationValidator）
 *
 * P3 跨仓联调的探针工具：在 Android 端发起一次轻量 HTTP 探测，验证以下各项：
 *  1. V2 Gateway 基础连通性（GET /api/v1/health 或 /api/health）
 *  2. 设备注册端点可用性（GET /api/v1/devices/list 的 HTTP 状态码）
 *  3. 记忆库写入端点可用性（HEAD /api/v1/memory/store 的 HTTP 状态码）
 *  4. WebSocket 地址格式有效性（本地规则，不实际建立连接）
 *
 * 典型用法（在 Activity / ViewModel 中）：
 * ```kotlin
 * val validator = CrossRepoIntegrationValidator(settings.restBaseUrl)
 * val report = validator.validate()
 * if (report.allPassed) { /* 联调就绪 */ }
 * ```
 *
 * 所有网络操作均在 IO 线程执行，返回 [ValidationReport] 对象，可直接展示给开发者。
 *
 * @param restBaseUrl V2 HTTP 基础地址，例如 `http://100.x.x.x:8000`
 * @param apiKey      可选的 Bearer token
 *
 * @author UFO³ Galaxy
 * @version 1.0 (P3)
 */
class CrossRepoIntegrationValidator(
    private val restBaseUrl: String,
    private val apiKey: String = ""
) {
    companion object {
        private const val TAG = "CrossRepoValidator"
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val READ_TIMEOUT_MS = 8_000

        // Validation check keys
        const val CHECK_HEALTH = "gateway_health"
        const val CHECK_DEVICES = "devices_endpoint"
        const val CHECK_MEMORY = "memory_endpoint"
        const val CHECK_WS_FORMAT = "ws_url_format"
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 执行全部联调验证检查，返回汇总报告。
     *
     * @param wsUrl 可选 WebSocket 地址（用于格式校验，例如 `ws://host:8050`）
     */
    suspend fun validate(wsUrl: String = ""): ValidationReport = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, CheckResult>()

        results[CHECK_HEALTH] = checkHealth()
        results[CHECK_DEVICES] = checkEndpoint("/api/v1/devices/list", "devices")
        results[CHECK_MEMORY] = checkEndpoint("/api/v1/memory/store", "memory_store")
        results[CHECK_WS_FORMAT] = checkWsUrlFormat(wsUrl.ifBlank { restBaseUrl })

        val allPassed = results.values.all { it.passed }
        val report = ValidationReport(
            restBaseUrl = restBaseUrl,
            checks = results,
            allPassed = allPassed
        )
        Log.i(TAG, "验证完成 allPassed=$allPassed\n${report.summary()}")
        report
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Individual checks
    // ──────────────────────────────────────────────────────────────────────────

    internal open fun checkHealth(): CheckResult {
        // Try v1 health first, then legacy /api/health
        for (path in listOf("/api/v1/health", "/api/health")) {
            val result = httpGet(restBaseUrl.trimEnd('/') + path)
            if (result.passed) return result
        }
        return CheckResult(
            passed = false,
            message = "Gateway health endpoint not reachable at $restBaseUrl"
        )
    }

    internal open fun checkEndpoint(path: String, label: String): CheckResult {
        val url = restBaseUrl.trimEnd('/') + path
        return httpGet(url, allowedCodes = setOf(200, 201, 204, 401, 403, 405))
            .let {
                if (it.passed) it
                else it.copy(message = "$label endpoint not reachable: ${it.message}")
            }
    }

    internal open fun checkWsUrlFormat(url: String): CheckResult {
        val valid = url.startsWith("ws://") || url.startsWith("wss://")
        return if (valid) {
            CheckResult(passed = true, message = "WS URL format valid: $url")
        } else {
            CheckResult(
                passed = false,
                message = "WS URL must start with ws:// or wss://, got: $url"
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HTTP helper
    // ──────────────────────────────────────────────────────────────────────────

    internal open fun httpGet(
        url: String,
        allowedCodes: Set<Int> = setOf(200, 201, 204)
    ): CheckResult {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            val passed = code in allowedCodes
            CheckResult(
                passed = passed,
                httpCode = code,
                message = if (passed) "HTTP $code OK" else "Unexpected HTTP $code from $url"
            )
        } catch (e: Exception) {
            CheckResult(passed = false, message = "Connection error: ${e.message}")
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Result data model
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 单项检查结果
 *
 * @property passed   是否通过
 * @property httpCode HTTP 状态码（-1 表示未发出 HTTP 请求）
 * @property message  可读描述
 */
data class CheckResult(
    val passed: Boolean,
    val httpCode: Int = -1,
    val message: String = ""
)

/**
 * 联调验证报告（全部检查汇总）
 *
 * @property restBaseUrl V2 HTTP 基础地址
 * @property checks      各项检查结果，key 为检查名称
 * @property allPassed   所有检查均通过时为 true
 */
data class ValidationReport(
    val restBaseUrl: String,
    val checks: Map<String, CheckResult>,
    val allPassed: Boolean
) {
    /** 生成可读的多行摘要（适合日志或 UI 展示）。 */
    fun summary(): String = buildString {
        appendLine("=== 跨仓联调验证报告 ===")
        appendLine("服务地址: $restBaseUrl")
        checks.forEach { (key, result) ->
            val icon = if (result.passed) "✅" else "❌"
            appendLine("$icon [$key] ${result.message}")
        }
        appendLine("总结: ${if (allPassed) "全部通过" else "存在失败项"}")
    }
}

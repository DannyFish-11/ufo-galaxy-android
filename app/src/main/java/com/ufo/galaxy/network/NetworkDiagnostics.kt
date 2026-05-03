package com.ufo.galaxy.network

import android.util.Log
import com.ufo.galaxy.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 网络诊断模块
 *
 * 提供以下诊断能力：
 * 1. DNS 解析检查 — 对 gateway_host 执行 DNS 查询
 * 2. HTTP 健康检查 — GET `rest_base/health`
 * 3. WebSocket 握手测试 — 尝试连接 `ws://host:port/ws/device/{device_id}`
 * 4. AIP ping — 向 REST 端点发 OPTIONS 请求
 *
 * 每项检查均带超时（[CHECK_TIMEOUT_MS]）并记录耗时与失败原因。
 *
 * @param settings 应用设置，提供网关地址、端口、TLS 标志和自签名设置。
 */
class NetworkDiagnostics(private val settings: AppSettings) {

    companion object {
        private const val TAG = "NetworkDiagnostics"

        /** 单项检查的最大等待时间（毫秒）。 */
        const val CHECK_TIMEOUT_MS = 8_000L

        /** HTTP 客户端连接超时（毫秒）。 */
        private const val HTTP_CONNECT_TIMEOUT_MS = 5_000L
    }

    /**
     * Returns an [OkHttpClient] configured for HTTP diagnostics.
     * Always reads [settings.allowSelfSigned] at call time so runtime config changes
     * are picked up without recreating the [NetworkDiagnostics] instance.
     */
    private fun buildHttpClient(): OkHttpClient =
        GalaxyWebSocketClient.buildOkHttpClient(settings.effectiveAllowSelfSigned())
            .newBuilder()
            .connectTimeout(HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

    /**
     * 单项诊断结果。
     *
     * @param name        检查名称（显示用）。
     * @param ok          检查是否通过。
     * @param durationMs  检查耗时（毫秒）。
     * @param detail      成功时的简短摘要或失败原因。
     */
    data class CheckResult(
        val name: String,
        val ok: Boolean,
        val durationMs: Long,
        val detail: String
    )

    /**
     * 完整诊断报告。
     */
    data class DiagnosticsReport(
        val gatewayHost: String,
        val gatewayPort: Int,
        val useTls: Boolean,
        val checks: List<CheckResult>
    ) {
        /** true 当且仅当所有检查均通过。 */
        val allOk: Boolean get() = checks.all { it.ok }

        /** 以换行分隔的人类可读摘要，用于显示在 UI 或复制到剪贴板。 */
        fun toText(): String = buildString {
            appendLine("=== 网络诊断报告 ===")
            appendLine("网关: ${gatewayHost}:${gatewayPort}  TLS=${useTls}")
            appendLine()
            checks.forEach { r ->
                val icon = if (r.ok) "✓" else "✗"
                appendLine("$icon ${r.name} (${r.durationMs}ms)")
                if (r.detail.isNotBlank()) appendLine("  ${r.detail}")
            }
        }.trimEnd()
    }

    /**
     * 运行完整诊断套件并返回 [DiagnosticsReport]。
     *
     * 按顺序执行所有检查；单项失败不中断后续检查，使报告更完整。
     */
    suspend fun runAll(): DiagnosticsReport {
        val host = settings.gatewayHost.ifBlank {
            // Derive host from full URL as fallback
            settings.galaxyGatewayUrl
                .removePrefix("ws://").removePrefix("wss://")
                .substringBefore(":")
                .substringBefore("/")
        }
        val port = settings.gatewayPort
        val useTls = settings.useTls
        val scheme = if (useTls) "https" else "http"
        val wsScheme = if (useTls) "wss" else "ws"
        val restBase = settings.effectiveRestBaseUrl()
        val deviceId = settings.deviceId.ifBlank {
            "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}"
        }

        val checks = mutableListOf<CheckResult>()
        checks += checkDns(host)
        checks += checkHttpHealth(restBase)
        checks += checkWsHandshake("$wsScheme://$host:$port/ws/device/$deviceId")
        checks += checkAipPing(restBase)

        return DiagnosticsReport(host, port, useTls, checks)
    }

    // ── Individual checks ─────────────────────────────────────────────────────

    /**
     * DNS 解析检查：尝试解析 [host]。
     */
    suspend fun checkDns(host: String): CheckResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val name = "DNS 解析"
        try {
            val result = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                InetAddress.getByName(host)
            }
            val duration = System.currentTimeMillis() - t0
            if (result != null) {
                Log.d(TAG, "DNS OK: $host → ${result.hostAddress} (${duration}ms)")
                CheckResult(name, true, duration, "→ ${result.hostAddress}")
            } else {
                CheckResult(name, false, duration, "超时 (${CHECK_TIMEOUT_MS}ms)")
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - t0
            Log.w(TAG, "DNS FAIL: $host — ${e.message}")
            CheckResult(name, false, duration, e.message ?: "解析失败")
        }
    }

    /**
     * HTTP 健康检查：GET `[restBase]/health`。
     */
    suspend fun checkHttpHealth(restBase: String): CheckResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val name = "HTTP /health"
        val url = "$restBase/health"
        val httpClient = buildHttpClient()
        try {
            val response = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                val req = Request.Builder().url(url).get().build()
                httpClient.newCall(req).execute()
            }
            val duration = System.currentTimeMillis() - t0
            if (response != null) {
                val code = response.code
                response.close()
                Log.d(TAG, "HTTP health $url → HTTP $code (${duration}ms)")
                if (response.isSuccessful) {
                    CheckResult(name, true, duration, "HTTP $code")
                } else {
                    CheckResult(name, false, duration, "HTTP $code")
                }
            } else {
                CheckResult(name, false, duration, "请求超时 (${CHECK_TIMEOUT_MS}ms)")
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - t0
            Log.w(TAG, "HTTP health FAIL $url — ${e.message}")
            CheckResult(name, false, duration, e.message ?: "请求失败")
        }
    }

    /**
     * WebSocket 握手测试：尝试连接 [wsUrl] 并等待 [onOpen] 或 [onFailure]。
     */
    suspend fun checkWsHandshake(wsUrl: String): CheckResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val name = "WS 握手"
        try {
            // Build a short-lived WS client for the probe; close immediately on open.
            val probeClient = GalaxyWebSocketClient.buildOkHttpClient(settings.effectiveAllowSelfSigned())
                .newBuilder()
                .connectTimeout(CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

            val result = withTimeoutOrNull(CHECK_TIMEOUT_MS + 1_000) {
                suspendCancellableCoroutine<Pair<Boolean, String>> { cont ->
                    val req = Request.Builder().url(wsUrl).build()
                    val ws = probeClient.newWebSocket(req, object : WebSocketListener() {
                        override fun onOpen(ws: WebSocket, response: Response) {
                            ws.close(1000, "probe")
                            if (cont.isActive) cont.resume(true to "HTTP ${response.code}")
                        }
                        override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                            if (cont.isActive) cont.resume(false to (t.message ?: "握手失败"))
                        }
                    })
                    cont.invokeOnCancellation { ws.cancel() }
                }
            }

            val duration = System.currentTimeMillis() - t0
            return@withContext if (result != null) {
                Log.d(TAG, "WS handshake $wsUrl → ok=${result.first} detail=${result.second} (${duration}ms)")
                CheckResult(name, result.first, duration, result.second)
            } else {
                CheckResult(name, false, duration, "超时 (${CHECK_TIMEOUT_MS}ms)")
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - t0
            Log.w(TAG, "WS handshake FAIL $wsUrl — ${e.message}")
            CheckResult(name, false, duration, e.message ?: "握手失败")
        }
    }

    /**
     * AIP ping：向 [restBase] 发送 HEAD 请求（轻量连通性探测）。
     */
    suspend fun checkAipPing(restBase: String): CheckResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val name = "AIP Ping"
        val httpClient = buildHttpClient()
        try {
            val response = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                val req = Request.Builder().url(restBase).head().build()
                httpClient.newCall(req).execute()
            }
            val duration = System.currentTimeMillis() - t0
            if (response != null) {
                val code = response.code
                response.close()
                Log.d(TAG, "AIP ping $restBase → HTTP $code (${duration}ms)")
                // 2xx or 4xx both mean server is reachable; 5xx / timeout = not reachable
                val reachable = code < 500
                CheckResult(name, reachable, duration, "HTTP $code")
            } else {
                CheckResult(name, false, duration, "请求超时 (${CHECK_TIMEOUT_MS}ms)")
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - t0
            Log.w(TAG, "AIP ping FAIL $restBase — ${e.message}")
            CheckResult(name, false, duration, e.message ?: "请求失败")
        }
    }
}

package com.ufo.galaxy.network

import android.util.Log
import com.ufo.galaxy.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Tailscale 网络适配器
 *
 * UFO Galaxy 系统支持通过 Tailscale VPN 实现跨设备通信。
 * 该适配器负责：
 * - 检测设备是否在 Tailscale 网络 (100.64.0.0/10)
 * - 自动发现网关节点 (autoDiscoverNode50)
 * - 将发现的地址一键写入 [AppSettings]
 *
 * @param settings  应用设置，用于读取/写入已保存的网关地址。
 */
class TailscaleAdapter(private val settings: AppSettings) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "TailscaleAdapter"

        /** Tailscale IP 范围前缀: 100.64.0.0/10 */
        const val TAILSCALE_IP_PREFIX = "100."

        /** 自动发现时使用的健康检查端点路径 */
        const val HEALTH_PATH = "/health"

        /** 默认网关端口 */
        const val DEFAULT_GATEWAY_PORT = 8765
    }

    /**
     * 检查设备是否在 Tailscale 网络中。
     *
     * 通过枚举所有网络接口判断是否有地址落在 Tailscale 范围 (100.x.x.x)。
     * 该操作在 IO 线程执行，结果不会缓存。
     */
    suspend fun isInTailscaleNetwork(): Boolean = withContext(Dispatchers.IO) {
        try {
            val found = enumerateTailscaleAddresses().isNotEmpty()
            Log.i(TAG, if (found) "✅ 检测到 Tailscale 网络" else "⚠️ 未检测到 Tailscale 网络")
            found
        } catch (e: Exception) {
            Log.e(TAG, "❌ Tailscale 网络检测失败: ${e.message}")
            false
        }
    }

    /**
     * 获取本机 Tailscale IP 地址（第一个匹配 100.x.x.x 的地址）。
     * 返回 null 表示未检测到 Tailscale 网络。
     */
    suspend fun getLocalTailscaleIp(): String? = withContext(Dispatchers.IO) {
        try {
            enumerateTailscaleAddresses().firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "❌ 获取本地 Tailscale IP 失败: ${e.message}")
            null
        }
    }

    /**
     * Enumerates all network interface addresses and returns those in the Tailscale
     * range (100.64.0.0/10, i.e. starting with "100.").
     *
     * Uses [java.net.NetworkInterface] instead of [java.net.InetAddress.getLocalHost] to
     * reliably enumerate all interfaces on Android devices that may not have a configured
     * hostname.
     */
    private fun enumerateTailscaleAddresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in interfaces.asSequence()) {
                for (addr in iface.inetAddresses.asSequence()) {
                    val hostAddress = addr.hostAddress ?: continue
                    if (hostAddress.startsWith(TAILSCALE_IP_PREFIX)) {
                        result += hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "enumerateTailscaleAddresses error: ${e.message}")
        }
        return result
    }

    /**
     * 自动发现网关节点（Node 50）。
     *
     * 扫描常见 Tailscale IP 段，对每个候选地址发 GET /health 请求。
     * 若找到可达节点，自动将其写入 [AppSettings.gatewayHost]。
     *
     * @return 找到的网关 host（IP 字符串），未找到则返回 null。
     */
    suspend fun autoDiscoverNode50(): String? = withContext(Dispatchers.IO) {
        Log.i(TAG, "🔍 正在自动发现 Gateway 节点...")

        // 若已保存地址，先验证
        val savedHost = settings.gatewayHost
        if (savedHost.isNotBlank()) {
            val port = settings.gatewayPort
            val scheme = if (settings.useTls) "https" else "http"
            if (checkHealth("$scheme://$savedHost:$port")) {
                Log.i(TAG, "✅ 已保存的网关可达: $savedHost:$port")
                return@withContext savedHost
            }
        }

        val port = DEFAULT_GATEWAY_PORT
        val candidateIPs = listOf(
            "100.64.0.1",
            "100.64.0.2",
            "100.64.0.3",
            "100.64.0.4",
            "100.64.0.5",
            "100.100.100.100",
            "100.101.102.103"
        )

        for (ip in candidateIPs) {
            val url = "http://$ip:$port"
            Log.d(TAG, "🔍 尝试: $url")
            if (checkHealth(url)) {
                Log.i(TAG, "✅ 发现可用网关: $ip:$port")
                return@withContext ip
            }
        }

        Log.w(TAG, "❌ 未能自动发现 Gateway 节点")
        null
    }

    /**
     * 将 [host] 写入 [AppSettings] 作为网关主机地址，同时更新端口。
     * 调用方（通常是 NetworkSettingsScreen 的"一键填入"按钮）负责触发 UI 刷新。
     */
    fun applyGatewayHost(host: String, port: Int = DEFAULT_GATEWAY_PORT) {
        settings.gatewayHost = host
        settings.gatewayPort = port
        Log.i(TAG, "✅ 已写入网关地址: $host:$port")
    }

    /**
     * 对给定 [baseUrl] 发 GET /health 请求，成功（HTTP 2xx）返回 true。
     */
    suspend fun checkHealth(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl$HEALTH_PATH").get().build()
            val response = okHttpClient.newCall(request).execute()
            val ok = response.isSuccessful
            response.close()
            ok
        } catch (e: Exception) {
            false
        }
    }
}

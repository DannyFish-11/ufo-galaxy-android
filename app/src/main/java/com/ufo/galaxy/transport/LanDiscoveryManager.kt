package com.ufo.galaxy.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LAN 网关发现（阶段 1）：用系统 NsdManager（mDNS）浏览 V2 网关 TCP P2P
 * 服务 `_galaxy-aip3._tcp.`（tcp_adapter.register_local_service 广播的那个，
 * TXT 里带 device_id/version，默认端口 19421）。
 *
 * 职责刻意收窄：只负责「发现 → 解析 → 回调 host/port」。连接、注册进
 * [AipTransportManager] 都由调用方（GalaxyConnectionService）在既有汇聚点
 * 完成 —— 本类不持有传输，也不做任何路由决策。
 */
class LanDiscoveryManager(
    context: Context,
    private val onGatewayFound: (host: String, port: Int, gatewayDeviceId: String) -> Unit,
) {

    companion object {
        private const val TAG = "LanDiscoveryManager"

        /** 与 V2 tcp_adapter.GALAXY_SERVICE_TYPE 对齐（NsdManager 不要 `.local.` 后缀）。 */
        const val SERVICE_TYPE = "_galaxy-aip3._tcp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val discovering = AtomicBoolean(false)

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.i(TAG, "LAN 发现已启动: $serviceType")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "发现服务: ${serviceInfo.serviceName}")
            // NsdManager 一次只能挂一个 resolve —— 逐个解析，失败只记日志（周期性
            // 重播的 mDNS 公告会再触发一次,无需自建重试）。
            try {
                @Suppress("DEPRECATION")
                nsdManager?.resolveService(serviceInfo, resolveListener())
            } catch (e: Exception) {
                Log.w(TAG, "resolveService 异常: ${e.message}")
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "服务下线: ${serviceInfo.serviceName}")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "LAN 发现已停止")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discovering.set(false)
            Log.w(TAG, "LAN 发现启动失败: $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "LAN 发现停止失败: $errorCode")
        }
    }

    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            val port = serviceInfo.port
            val gatewayId = serviceInfo.attributes?.get("device_id")?.let { String(it, Charsets.UTF_8) }
                ?: serviceInfo.serviceName
            Log.i(TAG, "解析到网关 $gatewayId @ $host:$port")
            try {
                onGatewayFound(host, port, gatewayId)
            } catch (e: Exception) {
                Log.w(TAG, "onGatewayFound 回调异常: ${e.message}")
            }
        }

        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.d(TAG, "解析失败(${serviceInfo.serviceName}): $errorCode")
        }
    }

    /** 开始浏览。NsdManager 不可用（个别 ROM/受限环境）时如实返回 false。 */
    fun start(): Boolean {
        val nsd = nsdManager ?: run {
            Log.w(TAG, "NsdManager 不可用，LAN 发现关闭")
            return false
        }
        if (!discovering.compareAndSet(false, true)) return true
        return try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            true
        } catch (e: Exception) {
            discovering.set(false)
            Log.w(TAG, "discoverServices 异常: ${e.message}")
            false
        }
    }

    fun stop() {
        if (!discovering.compareAndSet(true, false)) return
        try {
            nsdManager?.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.d(TAG, "stopServiceDiscovery 异常: ${e.message}")
        }
    }
}

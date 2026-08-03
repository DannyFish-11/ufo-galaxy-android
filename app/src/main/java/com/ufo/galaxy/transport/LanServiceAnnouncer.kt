package com.ufo.galaxy.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机侧 mDNS 广播：把本机的 [TcpDirectServer] 端口以 `_galaxy-aip3._tcp.`
 * 广播出去（TXT 带 device_id，与 V2 tcp_adapter.register_local_service 同款），
 * 让 V2 网关的 lan_discovery（已浏览此类型）把手机镜像进 UDM ——
 * 网关随即可主动直连手机，mesh 也把手机收为邻居。
 */
class LanServiceAnnouncer(context: Context, private val deviceId: String) {

    companion object {
        private const val TAG = "LanServiceAnnouncer"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val registered = AtomicBoolean(false)

    private val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.i(TAG, "mDNS 已广播: ${info.serviceName}")
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            registered.set(false)
            Log.w(TAG, "mDNS 广播失败: $errorCode")
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            Log.i(TAG, "mDNS 广播已撤销")
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "mDNS 撤销失败: $errorCode")
        }
    }

    fun start(port: Int): Boolean {
        val nsd = nsdManager ?: run {
            Log.w(TAG, "NsdManager 不可用，跳过广播")
            return false
        }
        if (!registered.compareAndSet(false, true)) return true
        return try {
            val info = NsdServiceInfo().apply {
                serviceName = deviceId
                serviceType = LanDiscoveryManager.SERVICE_TYPE
                setPort(port)
                setAttribute("device_id", deviceId)
                setAttribute("version", "3.0")
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            true
        } catch (e: Exception) {
            registered.set(false)
            Log.w(TAG, "registerService 异常: ${e.message}")
            false
        }
    }

    fun stop() {
        if (!registered.compareAndSet(true, false)) return
        try {
            nsdManager?.unregisterService(listener)
        } catch (e: Exception) {
            Log.d(TAG, "unregisterService 异常: ${e.message}")
        }
    }
}

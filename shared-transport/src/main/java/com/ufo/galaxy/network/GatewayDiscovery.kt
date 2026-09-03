package com.ufo.galaxy.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 局域网零配置发现网关（mDNS / DNS-SD，`_galaxy._tcp`）。
 *
 * 这一步补的是一个明显的缺口：**V2 网关一直在广播，安卓端却从来没人在听。**
 *
 *  - 网关侧 `galaxy_gateway/bootstrap/lifecycle.py` 启动时发布 `_galaxy._tcp`；
 *  - 手表侧当时自带一份 `MdnsDiscovery` 在监听（同一件事的第二份实现，现已收敛到本类）；
 *  - 安卓侧只有 `transport/LanServiceAnnouncer` —— 那是**反方向**的，
 *    它广播手机自己好让 V2 找到手机，不解决"手机怎么找到网关"。
 *
 * 于是手机端的"自动发现"退化成了两处硬编码 IP 猜测：
 * `FirstTimeSetupActivity.TAILSCALE_CANDIDATES`（30 个地址，逐个 3 秒 TCP 超时，
 * 最坏 90 秒）和 `TailscaleAdapter.autoDiscoverNode50`（7 个地址）。两份名单猜的都是
 * `100.64.0.1..20` / `100.100.100.x` —— 而 Tailscale 从 100.64.0.0/10 里分配，
 * 那是四百万个地址；`100.100.100.100` 是 MagicDNS 的地址，不是任何一台节点。
 * 猜中的概率接近零，代价却是首屏卡上一分半钟。
 *
 * mDNS 不猜：网关自己说它在哪。
 *
 * 放在 `:shared-transport`：手表侧此前自带一份 `MdnsDiscovery`，与本类是同一件事的
 * 两份实现。手表仓只把 `:shared-transport` 当子项目引进去，落在 `:app` 里的类它看不见 ——
 * 所以"能共享"的前提是住在这个模块里。历史缘由见 [GatewayAddress]。
 *
 * 已知边界
 * --------
 * Android 的 NSD 实现有长期存在的可靠性问题（并发解析、回调丢失），这是平台层面的，
 * 不是本类能修的。所以发现失败**不是异常路径**：返回空表，调用方回落到配对短码/
 * 手工输入。发现只是"能省一步就省一步"，不是唯一入口。
 */
class GatewayDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "GALAXY:DISCOVERY"

        /** 与 V2 `galaxy_gateway/mdns_announcer.py` 广播的服务类型一致。 */
        const val SERVICE_TYPE = "_galaxy._tcp"

        /**
         * 默认发现窗口。
         *
         * 2.5 秒是个折中：mDNS 应答通常在几百毫秒内到，但 Wi-Fi 省电状态下的组播
         * 唤醒会慢一些。取值比手表侧（2 秒）略长，因为手机上这一步通常发生在
         * 首次设置页，用户已经在等，多半秒换一次成功率是划算的。
         */
        const val DEFAULT_TIMEOUT_MS = 2500L
    }

    /** 一台发现到的网关。 */
    data class Found(val host: String, val port: Int) {
        /**
         * 组播发现到的地址天然是明文局域网地址，这里给出 `ws://`。
         *
         * 刻意不猜 `wss://`：网关在局域网里是否起了 TLS 由它自己决定，猜错会让
         * 握手直接失败，而失败原因（scheme 猜错）比"连不上"还难看出来。是否升级到
         * TLS 交给上层按 `AppSettings.useTls` 决定。
         */
        val wsUrl: String get() = "ws://$host:$port"

        /** 对应的 REST 基址，用于 `/api/v1/pair/claim` 换令牌。 */
        val restUrl: String get() = "http://$host:$port"
    }

    /**
     * 发现网关，返回第一个解析成功的。找不到返回 null。
     *
     * @param timeoutMs 发现窗口。超时即返回，不抛异常。
     */
    suspend fun discover(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Found? {
        val nsd = runCatching {
            context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        }.getOrNull()
        if (nsd == null) {
            Log.w(TAG, "NsdManager 不可用，跳过 mDNS 发现")
            return null
        }

        val result = CompletableDeferred<Found?>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "mDNS 发现已启动: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                // Android 各版本对服务类型尾部的点不一致：discoverServices 传的是
                // "_galaxy._tcp"，但部分机型的 onServiceFound 报回来带尾点。
                // 严格 == 在那些机型上永远不匹配 —— 发现得到却从不解析，
                // 表现为"明明同一个 Wi-Fi 却发现不了"。两边都去掉尾点再比。
                if (service.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                // resolveService / NsdServiceInfo.host 在 API 34 起标记为过时（新 API 是
                // registerServiceInfoCallback），但 minSdk 是 26，两条路都要覆盖时旧 API 仍是
                // 唯一在全区间可用的那条。过时只是告警，本模块没开 allWarningsAsErrors。
                @Suppress("DEPRECATION")
                runCatching {
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "解析失败 errorCode=$errorCode")
                        }

                        override fun onServiceResolved(s: NsdServiceInfo) {
                            val host = s.host?.hostAddress ?: return
                            Log.i(TAG, "发现网关: $host:${s.port}")
                            // complete 幂等：多台网关同时应答时取先到的那台，
                            // 后到的 complete 返回 false 被丢弃，不会覆盖。
                            result.complete(Found(host, s.port))
                        }
                    })
                }.onFailure { Log.w(TAG, "resolveService 抛出: ${it.message}") }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "发现启动失败 errorCode=$errorCode")
                result.complete(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        return try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(timeoutMs) { result.await() }
        } catch (e: Exception) {
            Log.w(TAG, "mDNS 发现异常: ${e.message}")
            null
        } finally {
            // 必须停：NSD 的 listener 不解注册会泄漏一条系统线程，
            // 反复进出设置页会累积。
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }
}

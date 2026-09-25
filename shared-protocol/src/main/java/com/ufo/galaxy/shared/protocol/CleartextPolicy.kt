package com.ufo.galaxy.shared.protocol

import java.net.URI

/**
 * CleartextPolicy — 「这个地址能不能用明文（ws:// / http://）连」。
 *
 * 为什么需要它
 * ============
 * 本系统设备间只走内网：同一个 Wi-Fi 直连，或者 tailnet。这两条路上网关默认说的
 * 都是明文 ws:// —— 局域网是家里的信任边界，tailnet 的加密与身份由 WireGuard 负责，
 * 而 wss:// 到一个裸 IP 本来就拿不到可信证书（公共 CA 不给 IP 发证）。
 *
 * 但 Android 的 network_security_config **只能按域名/单个 IP 放行明文，不认网段**。
 * 两个 App 原先都是"默认禁明文 + 写死几个 IP 放行"（10.0.2.2 / 10.0.0.1 /
 * 100.64.0.1 / localhost）。结果：
 *
 *   * 家里真实的局域网地址（192.168.1.23 之类）—— 被拦，局域网直连连不上；
 *   * tailnet 里按加入顺序分配的 100.x.y.z —— 除非恰好是 .1，否则被拦。
 *
 * 也就是说，"走内网"这件事在 release 版上从来就没通过。
 *
 * 所以把"只许对内网地址说明文"这条规则从 XML 挪到代码里：XML 放开明文，
 * 这里按网段判。**两个 App 用同一份**（本模块由手机和手表共用），并且由
 * [ConnectionPathPlanner.planAttempts] 在排序前统一过滤 —— 一个要拿明文去连
 * 公网地址的候选，根本不会被试。
 *
 * 判据
 * ====
 * * `wss://` / `https://` —— 永远可以（TLS 自己负责）；
 * * `ws://` / `http://` —— 只有主机是下面这些时才可以：
 *     - IPv4 字面量：10/8、172.16/12、192.168/16（RFC 1918 私网）、
 *       100.64/10（CGNAT —— Tailscale/headscale 的地址段）、169.254/16（链路本地）、
 *       127/8（回环）；
 *     - IPv6 字面量：::1、fc00::/7（ULA，含 Tailscale 的 fd7a:115c:a1e0::/48）、fe80::/10；
 *     - `localhost`、以 `.local` 结尾的 mDNS 名。
 * * 其他一律不行 —— 包括普通域名。一个域名解析到哪不做 DNS 是不知道的，
 *   而这里刻意**不做 I/O**（同 [ConnectionPathPlanner]）。
 *
 * 局限，写明白
 * ============
 * 这条只管**本系统自己拨网关**的那条路。App 里其他 HTTP 调用不经过这里 ——
 * XML 放开明文之后，它们的明文请求不再被系统拦。凡是连公网的调用都应该本来就是
 * https；两份 network_security_config 里对已知公网域名显式禁明文的条目保留。
 */
object CleartextPolicy {

    /** 这个地址能不能按其当前协议去连。解析不了的一律不能。 */
    @JvmStatic
    fun isPermitted(url: String): Boolean {
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        return when (scheme) {
            "wss", "https" -> true
            "ws", "http" -> {
                val host = uri.host ?: return false
                isInternalHost(host)
            }
            else -> false
        }
    }

    /** 主机名/IP 字面量是不是内网地址。**不做 DNS。** */
    @JvmStatic
    fun isInternalHost(rawHost: String): Boolean {
        val host = rawHost.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".local.")) return true
        parseIpv4(host)?.let { return isInternalIpv4(it) }
        if (host.contains(':')) return isInternalIpv6(host)
        return false
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it.isDigit() }) return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v
        }
        return out
    }

    private fun isInternalIpv4(o: IntArray): Boolean {
        val (a, b) = o[0] to o[1]
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127) || // CGNAT：Tailscale/headscale 的 100.64.0.0/10
            (a == 169 && b == 254) ||
            a == 127
    }

    private fun isInternalIpv6(host: String): Boolean {
        // 去掉 zone id（fe80::1%wlan0）
        val h = host.substringBefore('%')
        if (h == "::1") return true
        val first = h.substringBefore(':')
        if (first.isEmpty()) return false // 以 :: 开头的其余形式（如 ::ffff:x）不认
        val word = first.toIntOrNull(16) ?: return false
        return (word and 0xFE00) == 0xFC00 || // fc00::/7  ULA（含 Tailscale fd7a:115c:a1e0::/48）
            (word and 0xFFC0) == 0xFE80 // fe80::/10 链路本地
    }
}

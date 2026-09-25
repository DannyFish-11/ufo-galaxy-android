package com.ufo.galaxy.shared.protocol

import com.ufo.galaxy.shared.protocol.ConnectionPathPlanner.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「只许对内网地址说明文」的钉子。
 *
 * 这条规则替代的是两个 App 的 network_security_config 里"禁明文 + 写死几个 IP"
 * 的写法 —— 那种写法让真实的局域网地址和 tailnet 里随机分配的 100.x.y.z 全被拦，
 * "走内网"在 release 版上从来没通过。XML 不认网段，所以网段判在这里。
 */
class CleartextPolicyTest {

    // ── 一、内网地址：明文可以 ───────────────────────────────────────────────

    @Test
    fun `home lan addresses may use cleartext`() {
        for (u in listOf(
            "ws://192.168.1.23:9000/ws/device/w",
            "ws://10.0.0.5:9000/ws/device/w",
            "ws://172.16.4.2:9000/ws/device/w",
            "ws://172.31.255.254:9000/ws/device/w",
        )) assertTrue(u, CleartextPolicy.isPermitted(u))
    }

    @Test
    fun `any tailnet address may use cleartext, not just 100_64_0_1`() {
        // 原来 XML 里只放行了 100.64.0.1 —— 而 tailnet 地址按加入顺序分配。
        for (u in listOf(
            "ws://100.64.0.1:9000/ws/device/w",
            "ws://100.101.7.42:9000/ws/device/w",
            "ws://100.127.255.254:9000/ws/device/w",
        )) assertTrue(u, CleartextPolicy.isPermitted(u))
    }

    @Test
    fun `mdns names and localhost may use cleartext`() {
        assertTrue(CleartextPolicy.isPermitted("ws://galaxy-desk.local:9000/ws/device/w"))
        assertTrue(CleartextPolicy.isPermitted("http://localhost:9000/api/v1/pair/claim"))
        assertTrue(CleartextPolicy.isPermitted("ws://127.0.0.1:9000/ws"))
        assertTrue(CleartextPolicy.isPermitted("ws://169.254.3.4:9000/ws"))
    }

    @Test
    fun `ipv6 internal ranges may use cleartext`() {
        assertTrue(CleartextPolicy.isPermitted("ws://[::1]:9000/ws"))
        assertTrue(CleartextPolicy.isPermitted("ws://[fd7a:115c:a1e0::1]:9000/ws")) // Tailscale ULA
        assertTrue(CleartextPolicy.isPermitted("ws://[fe80::1]:9000/ws"))
    }

    // ── 二、公网：明文不行 ───────────────────────────────────────────────────

    @Test
    fun `public addresses may not use cleartext`() {
        for (u in listOf(
            "ws://8.8.8.8:9000/ws/device/w",
            "ws://1.2.3.4:9000/ws",
            "ws://[2001:db8::1]:9000/ws",
            // 同样以 f 开头、但不在 fc00::/7 / fe80::/10 里的：组播、已废弃的站点本地。
            // 掩码写宽一位（比如按 f000::/4 判），这两条会被当成内网放行。
            "ws://[ff02::1]:9000/ws",
            "ws://[fec0::1]:9000/ws",
        )) assertFalse(u, CleartextPolicy.isPermitted(u))
    }

    @Test
    fun `neighbours of the private ranges are public`() {
        // 边界钉子：差一位就是公网。写错一个比较号，这几条会红。
        for (u in listOf(
            "ws://172.15.0.1:9000/ws",
            "ws://172.32.0.1:9000/ws",
            "ws://100.63.255.255:9000/ws",
            "ws://100.128.0.1:9000/ws",
            "ws://11.0.0.1:9000/ws",
            "ws://192.169.0.1:9000/ws",
        )) assertFalse(u, CleartextPolicy.isPermitted(u))
    }

    @Test
    fun `plain domain names may not use cleartext`() {
        // 域名解析到哪不做 DNS 是不知道的 —— Funnel 的 *.ts.net 就解析到公网。
        assertFalse(CleartextPolicy.isPermitted("ws://box.tail1234.ts.net/ws"))
        assertFalse(CleartextPolicy.isPermitted("ws://galaxy.example.com:9000/ws"))
        assertFalse(CleartextPolicy.isPermitted("ws://notlocal.localhost.evil.com/ws"))
    }

    // ── 三、TLS 永远可以；坏输入永远不行 ─────────────────────────────────────

    @Test
    fun `tls is always permitted`() {
        assertTrue(CleartextPolicy.isPermitted("wss://box.tail1234.ts.net/ws/device/w"))
        assertTrue(CleartextPolicy.isPermitted("wss://8.8.8.8:9000/ws"))
        assertTrue(CleartextPolicy.isPermitted("https://example.com/"))
    }

    @Test
    fun `garbage is never permitted`() {
        for (u in listOf("", "not a url", "ftp://192.168.1.1/", "ws://", "192.168.1.1:9000", "ws://999.1.1.1/ws")) {
            assertFalse("[$u]", CleartextPolicy.isPermitted(u))
        }
    }

    // ── 四、真的接在排序器上 ─────────────────────────────────────────────────

    @Test
    fun `the planner never schedules cleartext to a public host`() {
        val plan = ConnectionPathPlanner.planAttempts(
            listOf(
                Candidate("lan", "ws://192.168.1.5:9000/ws/device/d", 1),
                Candidate("tailscale", "ws://100.101.7.42:9000/ws/device/d", 2),
                Candidate("bogus", "ws://8.8.8.8:9000/ws/device/d", 3),
                Candidate("funnel", "wss://box.ts.net/ws/device/d", 4),
            ),
        )
        assertEquals(listOf("lan", "tailscale", "funnel"), plan.map { it.kind })
    }

    @Test
    fun `all candidates filtered means nothing to try`() {
        val plan = ConnectionPathPlanner.planAttempts(listOf(Candidate("x", "ws://8.8.8.8:9000/ws", 1)))
        assertTrue(plan.isEmpty())
    }
}

package com.ufo.galaxy.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `wss://localhost:9000` 在手机上永远连不通，所以它不算"已配置"。
 *
 * 这条守的是一个会把用户引到错误方向的判定。
 *
 * `assets/config.properties` 的出厂默认是 `galaxy_gateway_url=wss://localhost:9000`。
 * 在手机上，`localhost` 指向手机自己，而网关跑在 PC 上 —— 这个值不是默认值，
 * 是一个伪装成默认值的占位符。
 *
 * 改前 `MainViewModel.isRealUrl` 只认 `100.x.x.x` 这类字面占位形态，环回不在它眼里。
 * 于是 `toggleCrossDeviceEnabled` 认为"网关已配置"，直接去连、超时、报一个网络错误。
 * 用户看到的是"连接失败，请检查网络"，而实际情况是"你还没告诉我网关在哪"——
 * 提示把人引向了完全无关的排查方向。
 *
 * 测试放在 :app 而不是 :shared-transport
 * --------------------------------------
 * 被测类在 :shared-transport，但 CI 只跑 `:app:testDebugUnitTest`。
 * 放进 :shared-transport 需要给那个模块补 junit 依赖**并且**改 CI 的任务列表，
 * 否则测试写了也不会执行 —— 一条不会执行的测试比没有更糟，它让人以为守住了。
 * :app 依赖 :shared-transport，从这里测是同一个类。
 */
class GatewayAddressTest {

    // ── 事故本身 ─────────────────────────────────────────────────────────────

    @Test
    fun `出厂默认的 localhost 网关不算已配置`() {
        assertFalse(
            "wss://localhost:9000 是 config.properties 的出厂默认，它在手机上必然连不通",
            GatewayAddress.isUsableGatewayUrl("wss://localhost:9000"),
        )
        assertFalse(GatewayAddress.isUsableGatewayUrl("https://localhost:9000"))
    }

    @Test
    fun `整个 127 段都算环回而不只是 127_0_0_1`() {
        // 127.0.0.0/8 整段都是环回。只挡 127.0.0.1 的话，写 127.1.2.3 一样连不到
        // 别的机器，却会被判成"已配置"。
        for (host in listOf("127.0.0.1", "127.0.0.2", "127.1.2.3", "127.255.255.254")) {
            assertTrue("$host 应被判为环回", GatewayAddress.isLoopbackHost(host))
            assertFalse(GatewayAddress.isUsableGatewayUrl("ws://$host:9000"))
        }
    }

    @Test
    fun `IPv6 环回与通配地址同样不算已配置`() {
        for (url in listOf("ws://[::1]:9000", "ws://[0:0:0:0:0:0:0:1]:9000", "ws://0.0.0.0:9000")) {
            assertFalse("$url 不该被判为可用", GatewayAddress.isUsableGatewayUrl(url))
        }
    }

    // ── 原有的字面占位判定不能被改坏 ────────────────────────────────────────

    @Test
    fun `100_x_x_x 这类字面占位依旧不算已配置`() {
        assertFalse(GatewayAddress.isUsableGatewayUrl("wss://100.x.x.x:9000"))
        assertFalse(GatewayAddress.isUsableGatewayUrl(""))
        assertFalse(GatewayAddress.isUsableGatewayUrl("   "))
    }

    // ── 真地址必须照常通过 ──────────────────────────────────────────────────

    @Test
    fun `真实地址判为已配置`() {
        // 这几条是反向保险：把"不可用"判得太宽会让真能连的地址被拦下来，
        // 那是比原缺陷更糟的回归。
        for (url in listOf(
            "wss://100.64.12.34:9000",      // Tailscale 分配的真实地址
            "ws://192.168.1.100:9000",      // 局域网
            "wss://galaxy.ufo.ai:9000",     // 域名
            "ws://10.0.0.5:9000/ws/device/abc",
            "wss://[2001:db8::1]:9000",     // 真实 IPv6
        )) {
            assertTrue("$url 应判为可用", GatewayAddress.isUsableGatewayUrl(url))
        }
    }

    @Test
    fun `含字母 x 的合法域名不被误判成占位`() {
        // 原判据是「含 x 且整体不匹配 [0-9a-fA-F:.]+」。写这两条用例时它是**红的**：
        // xbox.lan 和 nx-gateway.example.com 都含 x、也都不是纯十六进制，于是被判成
        // "没配置"。这个方向的误判比漏判更糟 —— 用户明明填了个能连的地址，
        // App 却说他没填，而且不会告诉他为什么。
        // 判据因此改成按形状认：整串只有数字、点和 x，才是 100.x.x.x 那种占位。
        assertTrue(GatewayAddress.isUsableGatewayUrl("wss://nx-gateway.example.com:9000"))
        assertTrue(GatewayAddress.isUsableGatewayUrl("wss://xbox.lan:9000"))
    }

    // ── host 切分 ───────────────────────────────────────────────────────────

    @Test
    fun `host 切分对端口 路径 IPv6 方括号都成立`() {
        assertEquals("192.168.1.5", GatewayAddress.hostOf("ws://192.168.1.5:9000/ws/device/x"))
        assertEquals("192.168.1.5", GatewayAddress.hostOf("ws://192.168.1.5/ws"))
        assertEquals("192.168.1.5", GatewayAddress.hostOf("ws://192.168.1.5"))
        // 方括号形态必须整体取出，否则会被冒号切成 "["。
        assertEquals("2001:db8::1", GatewayAddress.hostOf("wss://[2001:db8::1]:9000/ws"))
        assertEquals("::1", GatewayAddress.hostOf("ws://[::1]:9000"))
        // 没有 scheme 时按整串当 authority 处理，而不是返回空。
        assertEquals("example.com", GatewayAddress.hostOf("example.com:9000"))
    }

    @Test
    fun `尾点形态的主机名也算环回`() {
        // DNS 里 "localhost." 是完全限定形态，和 "localhost" 是同一台机器。
        assertTrue(GatewayAddress.isLoopbackHost("localhost."))
        assertTrue(GatewayAddress.isLoopbackHost("LocalHost"))
    }
}

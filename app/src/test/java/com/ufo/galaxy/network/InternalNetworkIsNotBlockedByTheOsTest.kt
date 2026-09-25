package com.ufo.galaxy.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「走内网」不许再被系统的网络安全配置悄悄拦死。
 *
 * 为什么要这道门
 * ==============
 * 网关默认说明文 ws://（局域网是家里的信任边界；tailnet 由 WireGuard 加密；wss 到裸 IP
 * 拿不到可信证书）。而 Android 的 network_security_config 只认域名 / 单个 IP，不认网段。
 *
 * 这里原先是：
 *   · main 版"禁明文 + 写死 10.0.2.2 / 10.0.0.1 / 100.64.0.1 / localhost"，
 *     还注明"192.168.* removed for release safety"；
 *   · release 源集再覆盖一份，**完全**禁明文。
 * 结果 release 版的手机连不上任何默认配置的网关 —— 局域网直连和 tailnet 从来没通过，
 * 而编译、单测全绿，因为这类失败只在真机上的 OkHttp 里才发生。
 *
 * 现在的规则：XML 放开明文，网段判定在 shared-protocol 的 CleartextPolicy，并由
 * ConnectionPathPlanner 在排序前统一过滤（那两条由 shared-protocol 自己的单测钉着）。
 * 这里钉的是 XML 这一侧不回退。
 */
class InternalNetworkIsNotBlockedByTheOsTest {

    private fun appDir(): File {
        val candidates = listOf(File("."), File("app"), File("../app"))
        return candidates.firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("找不到 app 模块目录，试过：${candidates.map { it.absolutePath }}")
    }

    private fun stripComments(xml: String) = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    @Test
    fun `there is exactly one network security config`() {
        // 两份 = 两套规则。release 覆盖就是这么把内网整个禁掉的。
        val found = appDir().resolve("src").walkTopDown()
            .filter { it.isFile && it.name.startsWith("network_security_config") && it.extension == "xml" }
            .map { it.relativeTo(appDir()).path }
            .toList()
        assertEquals("应当只有 main 源集那一份：$found", listOf("src/main/res/xml/network_security_config.xml"), found)
    }

    @Test
    fun `cleartext is not blanket-denied`() {
        val xml = stripComments(appDir().resolve("src/main/res/xml/network_security_config.xml").readText())
        val base = Regex("<base-config[^>]*>").find(xml)?.value ?: error("没有 base-config")
        assertTrue(
            "base-config 又禁了明文：$base —— 局域网 / tailnet 的 ws:// 会被系统拦死。" +
                "要收紧请改 CleartextPolicy（能按网段判），不要改这里（只能按单个 IP）。",
            base.contains("cleartextTrafficPermitted=\"true\""),
        )
    }

    @Test
    fun `known public endpoints stay tls-only`() {
        val xml = stripComments(appDir().resolve("src/main/res/xml/network_security_config.xml").readText())
        val deny = Regex("<domain-config cleartextTrafficPermitted=\"false\">(.*?)</domain-config>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: ""
        for (d in listOf("huggingface.co", "github.com")) {
            assertTrue("$d 不再被显式禁明文", deny.contains(d))
        }
    }

    @Test
    fun `no hand-picked ip whitelist sneaks back`() {
        // 单个 IP 白名单正是原来那个坑：看起来放行了"内网"，实际只放行了四个地址。
        val xml = stripComments(appDir().resolve("src/main/res/xml/network_security_config.xml").readText())
        assertFalse(
            "又出现了按单个 IP 放行明文的写法",
            Regex("<domain-config cleartextTrafficPermitted=\"true\">").containsMatchIn(xml),
        )
    }
}

package com.ufo.galaxy.contract

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多路径连接：**装了，得有人用**。
 *
 * 这条测试守的不是逻辑，是**接线**。
 *
 * 起因是一次真实的漏接：配对时网关把全部可达路径交了过来，
 * [com.ufo.galaxy.data.AppSettings.effectiveCandidateWsUrls] 也照
 * [com.ufo.galaxy.shared.protocol.ConnectionPathPlanner] 排好了序，
 * 单测 CandidateWsUrlOrderTest 全绿 —— 但**连接层从头到尾没调过它**。
 * 手机照旧只认 effectiveGatewayWsUrl 那一个地址。
 *
 * 于是"出门带流量连不上"这个问题，从代码上看像是已经解决了：类在、测试在、顺序对。
 * 只是没人用。这类缺陷不会让任何测试变红，也不会报错 —— 逻辑正确、覆盖率好看、
 * 功能不存在。
 *
 * 所以这里直接读源码钉三条接线。粗，但它盯的正是"能力与调用之间断开"这件事，
 * 而那是任何行为测试都测不到的：被测的东西根本没被调用。
 */
class CandidatePathsAreActuallyUsedTest {

    private fun source(relative: String): String {
        val root = locateMainSourceRoot()
        val f = File(root, relative)
        assertTrue("源文件不存在：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    @Test
    fun `the websocket client accepts a candidate list`() {
        val src = source("com/ufo/galaxy/network/GalaxyWebSocketClient.kt")
        assertTrue(
            "连接层没有接收候选路径的入口 —— 多路径就只是设置里的一串 JSON",
            src.contains("fun setConnectionCandidates("),
        )
    }

    @Test
    fun `reconnect rotates to the next candidate instead of retrying one url forever`() {
        val src = source("com/ufo/galaxy/network/GalaxyWebSocketClient.kt")
        assertTrue("没有换路逻辑", src.contains("private fun advanceToNextCandidate("))
        // 光有函数不够 —— 重连调度里必须真的调它。
        // 在同一条上重试到死正是"出门就连不上"的形状：局域网那条在外面
        // 永远超时，重试多少次都一样。
        val scheduleBody = src.substringAfter("private fun scheduleReconnect()")
        assertTrue(
            "重连调度没有调用 advanceToNextCandidate —— 会在同一条路上重试到死",
            scheduleBody.contains("advanceToNextCandidate()"),
        )
    }

    @Test
    fun `the app hands the candidates to the client on startup`() {
        val src = source("com/ufo/galaxy/UFOGalaxyApplication.kt")
        assertTrue(
            "冷启动没有把候选交给连接层",
            src.contains("setConnectionCandidates(") && src.contains("effectiveCandidateWsUrls()"),
        )
    }

    @Test
    fun `pairing hands the fresh candidates over immediately`() {
        // 存进设置但不交出去的话，这一整套要等下次冷启动才生效 ——
        // 而"刚配完对却连不上"恰恰发生在此刻。
        val src = source("com/ufo/galaxy/ui/viewmodel/MainViewModel.kt")
        val paired = src.substringAfter("fun pairThisDevice(")
        assertTrue(
            "配对成功后没有把新拿到的候选交给连接层",
            paired.contains("setConnectionCandidates("),
        )
    }

    @Test
    fun `a successful connect records which path worked`() {
        // 不记的话，每次断线都要从第一条重新试探一轮；在外面走流量时，
        // 局域网那条每次都要白等一个超时才轮到能用的。
        val src = source("com/ufo/galaxy/service/GalaxyConnectionService.kt")
        assertTrue(
            "连上之后没有记录 last-good 路径",
            src.contains("lastGoodCandidateKind"),
        )
    }

    private fun locateMainSourceRoot(): File {
        // 单测的工作目录在不同调用方式下不一样(模块目录 / 仓库根),两种都试。
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "找不到 src/main/java。试过:${candidates.joinToString { it.absolutePath }}。" +
                    "这里刻意不跳过 —— 一个『找不到就当通过』的守卫等于没有守卫。"
            )
    }
}

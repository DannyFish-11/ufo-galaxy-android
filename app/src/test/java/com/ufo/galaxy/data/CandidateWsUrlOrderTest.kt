package com.ufo.galaxy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `effectiveCandidateWsUrls()` —— 换个网还能连上，靠的就是这一串。
 *
 * 配对时网关把它所有可达路径交了过来。同一台手机在家、在公司、带流量出门，
 * 能连通的是**不同**的那一条。只认一个地址等于换个网就连不上，而用户看到的
 * 只是"连不上"，没有任何线索说该换哪条。
 *
 * 这里用 [InMemoryAppSettings]（AppSettings 的 data class 实现），不碰 Android
 * 的 SharedPreferences —— 要考的是"顺序对不对、坏数据怎么处理"，不是存储。
 */
class CandidateWsUrlOrderTest {

    private val threePaths = """[
        {"kind":"lan","url":"ws://192.168.1.5:9000/ws/device/desk","priority":1},
        {"kind":"tailscale","url":"wss://100.9.9.9:9000/ws/device/desk","priority":2},
        {"kind":"funnel","url":"wss://box.ts.net/ws/device/desk","priority":3}
    ]"""

    private fun settings(
        candidates: String = "",
        lastGood: String = "",
        host: String = "192.168.1.5",
        devId: String = "phone-1",
    ) = InMemoryAppSettings(
        gatewayCandidatesJson = candidates,
        lastGoodCandidateKind = lastGood,
        gatewayHost = host,
        deviceId = devId,
    )

    @Test
    fun `candidates are ordered by priority`() {
        val urls = settings(candidates = threePaths).effectiveCandidateWsUrls()
        assertEquals(3, urls.size)
        assertTrue(urls[0].startsWith("ws://192.168.1.5"))
        assertTrue(urls[1].startsWith("wss://100.9.9.9"))
        assertTrue(urls[2].startsWith("wss://box.ts.net"))
    }

    @Test
    fun `last good path is tried first`() {
        val urls = settings(candidates = threePaths, lastGood = "funnel").effectiveCandidateWsUrls()
        assertTrue("上次通的那条该排最前", urls[0].startsWith("wss://box.ts.net"))
        // 但不是独占 —— 环境真变了时其余还得试
        assertEquals(3, urls.size)
    }

    @Test
    fun `no candidates falls back to the single derived url`() {
        // 老版本网关不给候选。设备不能因此完全连不上 —— 退回单地址逻辑。
        val urls = settings(candidates = "").effectiveCandidateWsUrls()
        assertEquals(1, urls.size)
        assertTrue(urls[0].contains("192.168.1.5"))
    }

    @Test
    fun `a corrupted cache does not silently look like never-paired`() {
        // 空表意味着"连地址都没有"。这里退回单地址，而不是返回空表让调用方
        // 误以为设备从没配过对。
        val urls = settings(candidates = "{ this is not json").effectiveCandidateWsUrls()
        assertEquals(1, urls.size)
    }

    @Test
    fun `entries without a url are skipped, not fatal`() {
        val urls = settings(
            candidates = """[
                {"kind":"lan","priority":1},
                {"kind":"funnel","url":"wss://box.ts.net/ws/device/desk","priority":2}
            ]"""
        ).effectiveCandidateWsUrls()
        assertEquals(1, urls.size)
        assertTrue(urls[0].startsWith("wss://box.ts.net"))
    }

    @Test
    fun `an entry without priority keeps its array position instead of being dropped`() {
        val parsed = settings().parseCandidates(
            """[{"kind":"lan","url":"ws://a/ws/device/d"},{"kind":"funnel","url":"wss://b/ws/device/d"}]"""
        )
        assertEquals(listOf(1, 2), parsed.map { it.priority })
    }

    @Test
    fun `a stale last good kind does not empty the list`() {
        // 换了网关之后上次通的那条可能已不在清单里 —— 不能因此一条都不试。
        val urls = settings(candidates = threePaths, lastGood = "no-such-kind").effectiveCandidateWsUrls()
        assertEquals(3, urls.size)
        assertTrue(urls[0].startsWith("ws://192.168.1.5"))
    }
}

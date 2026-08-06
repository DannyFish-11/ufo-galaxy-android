package com.ufo.galaxy.shared.protocol

import com.ufo.galaxy.shared.protocol.ConnectionPathPlanner.AttemptOutcome
import com.ufo.galaxy.shared.protocol.ConnectionPathPlanner.Candidate
import com.ufo.galaxy.shared.protocol.ConnectionPathPlanner.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ConnectionPathPlanner 的行为钉子。
 *
 * 这个类不做 I/O，所以能在普通 JVM 单测里跑完整逻辑 —— 而"顺序"和"怎么解释失败"
 * 恰恰是最容易两边写歪、又最难在真机上复现的两件事。
 */
class ConnectionPathPlannerTest {

    private fun lan(p: Int = 1) = Candidate("lan", "ws://192.168.1.5:9000/ws/device/desk", p)
    private fun ts(p: Int = 2) = Candidate("tailscale", "wss://100.9.9.9:9000/ws/device/desk", p)
    private fun funnel(p: Int = 3) = Candidate("funnel", "wss://box.ts.net/ws/device/desk", p)

    // ── 一、顺序 ─────────────────────────────────────────────────────────────

    @Test
    fun `attempts follow priority when nothing succeeded before`() {
        val order = ConnectionPathPlanner.planAttempts(listOf(funnel(), lan(), ts()))
        assertEquals(listOf("lan", "tailscale", "funnel"), order.map { it.kind })
    }

    @Test
    fun `the last good path is tried first`() {
        // 环境多半没变，先试上次通的那条能把整轮试探省掉。
        val order = ConnectionPathPlanner.planAttempts(listOf(lan(), ts(), funnel()), lastGoodKind = "funnel")
        assertEquals(listOf("funnel", "lan", "tailscale"), order.map { it.kind })
    }

    @Test
    fun `the last good path is promoted, not made exclusive`() {
        // 区分度：如果实现写成"只试上次那条"，环境真变了时设备就再也连不上，
        // 而且没有任何提示。这条钉住其余路径仍在队列里。
        val order = ConnectionPathPlanner.planAttempts(listOf(lan(), ts(), funnel()), lastGoodKind = "funnel")
        assertEquals(3, order.size)
        assertTrue(order.map { it.kind }.containsAll(listOf("lan", "tailscale", "funnel")))
    }

    @Test
    fun `a stale last good kind does not empty the queue`() {
        // 换了网关之后，上次通的那条可能压根不在新清单里。
        // 此时不能空手而归 —— 那等于设备再也不会尝试连接。
        val order = ConnectionPathPlanner.planAttempts(listOf(lan(), funnel()), lastGoodKind = "tailscale")
        assertEquals(listOf("lan", "funnel"), order.map { it.kind })
    }

    @Test
    fun `no candidates yields no attempts`() {
        assertTrue(ConnectionPathPlanner.planAttempts(emptyList()).isEmpty())
        assertTrue(ConnectionPathPlanner.planAttempts(emptyList(), "lan").isEmpty())
    }

    @Test
    fun `priority gaps do not reorder anything`() {
        // 服务端保证 priority 从 1 起连续，但客户端不该依赖这一点 ——
        // 依赖了的话，将来加一条路就可能悄悄改变顺序。
        val order = ConnectionPathPlanner.planAttempts(listOf(funnel(90), lan(7), ts(42)))
        assertEquals(listOf("lan", "tailscale", "funnel"), order.map { it.kind })
    }

    // ── 二、成功 ─────────────────────────────────────────────────────────────

    @Test
    fun `lan success is a plain success`() {
        val v = ConnectionPathPlanner.classify(
            listOf(AttemptOutcome("lan", connected = true, timedOut = false)),
            tailscaleAvailable = true,
        )
        assertEquals(Verdict.Connected("lan"), v)
        assertFalse(ConnectionPathPlanner.isWorthTellingTheUser(v))
    }

    @Test
    fun `lan down but funnel up is not a failure and must not nag`() {
        // **这就是出门时的正常形态。** 报成故障的话，用户每次出门都被弹一次。
        val v = ConnectionPathPlanner.classify(
            listOf(
                AttemptOutcome("lan", connected = false, timedOut = true),
                AttemptOutcome("funnel", connected = true, timedOut = false),
            ),
            tailscaleAvailable = true,
        )
        assertEquals(Verdict.DegradedButFine("funnel"), v)
        assertFalse("这是正常现象，不该打扰用户", ConnectionPathPlanner.isWorthTellingTheUser(v))
    }

    @Test
    fun `funnel success without trying lan is a plain success`() {
        // 上次通的是 funnel，这次它排第一就通了 —— 局域网那条根本没试过，
        // 说不上"降级"。区分度：写成"只要不是 lan 就算降级"的话这条会红。
        val v = ConnectionPathPlanner.classify(
            listOf(AttemptOutcome("funnel", connected = true, timedOut = false)),
            tailscaleAvailable = true,
        )
        assertEquals(Verdict.Connected("funnel"), v)
    }

    // ── 三、三档失败必须分得开 ───────────────────────────────────────────────

    @Test
    fun `all timed out with tailscale available means unreachable`() {
        val v = ConnectionPathPlanner.classify(
            listOf(
                AttemptOutcome("lan", connected = false, timedOut = true),
                AttemptOutcome("tailscale", connected = false, timedOut = true),
                AttemptOutcome("funnel", connected = false, timedOut = true),
            ),
            tailscaleAvailable = true,
        )
        assertEquals(Verdict.Unreachable, v)
        assertTrue(ConnectionPathPlanner.isWorthTellingTheUser(v))
        assertTrue("该指向那台电脑，而不是让人折腾手机", ConnectionPathPlanner.explain(v).contains("电脑"))
    }

    @Test
    fun `all timed out without tailscale is the only time to suggest installing it`() {
        val v = ConnectionPathPlanner.classify(
            listOf(AttemptOutcome("lan", connected = false, timedOut = true)),
            tailscaleAvailable = false,
        )
        assertEquals(Verdict.NeedsTailscale, v)
        assertTrue(ConnectionPathPlanner.explain(v).contains("Tailscale"))
    }

    @Test
    fun `an explicit rejection is not reported as unreachable-because-no-tailscale`() {
        // 令牌过期会立刻回 401，不是超时。把它报成"去装 Tailscale"是纯误导 ——
        // 用户装完了还是连不上，而真正的原因（令牌）一直没人提。
        val v = ConnectionPathPlanner.classify(
            listOf(AttemptOutcome("lan", connected = false, timedOut = false, elapsedMs = 40)),
            tailscaleAvailable = false,
        )
        assertEquals(Verdict.Unreachable, v)
        assertFalse(
            "有明确拒绝时不该建议装 Tailscale",
            ConnectionPathPlanner.explain(v).contains("Tailscale"),
        )
    }

    @Test
    fun `a mix of timeout and rejection is not treated as all-timeout`() {
        val v = ConnectionPathPlanner.classify(
            listOf(
                AttemptOutcome("lan", connected = false, timedOut = true),
                AttemptOutcome("funnel", connected = false, timedOut = false),
            ),
            tailscaleAvailable = false,
        )
        // 有一条是被明确拒的 → 网关是活的 → 不该建议装 Tailscale
        assertEquals(Verdict.Unreachable, v)
    }

    @Test
    fun `no attempts at all means not paired`() {
        val v = ConnectionPathPlanner.classify(emptyList(), tailscaleAvailable = true)
        assertEquals(Verdict.NotPaired, v)
        assertTrue(ConnectionPathPlanner.explain(v).contains("配对"))
    }

    // ── 四、超时预算 ─────────────────────────────────────────────────────────

    @Test
    fun `per candidate timeout is bounded so a full sweep stays tolerable`() {
        // 3 秒 × 3 条 = 9 秒。再长的话用户会以为应用卡死了；再短则局域网
        // 握手可能来不及（尤其手表刚从省电模式醒来）。
        assertEquals(3_000L, ConnectionPathPlanner.PER_CANDIDATE_TIMEOUT_MS)
        assertTrue(ConnectionPathPlanner.PER_CANDIDATE_TIMEOUT_MS * 3 <= 10_000L)
    }

    @Test
    fun `kind constants match the V2 side vocabulary`() {
        // 这三个字符串是跨仓协议的一部分：V2 的 build_candidates 产出它们。
        // 拼错的话候选路径会被当成 unknown，排序和判档全部错位。
        assertEquals("lan", ConnectionPathPlanner.KIND_LAN)
        assertEquals("tailscale", ConnectionPathPlanner.KIND_TAILSCALE)
        assertEquals("funnel", ConnectionPathPlanner.KIND_FUNNEL)
    }
}

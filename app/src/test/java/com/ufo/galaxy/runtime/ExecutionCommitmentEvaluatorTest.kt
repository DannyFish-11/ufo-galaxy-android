package com.ufo.galaxy.runtime

import com.ufo.galaxy.runtime.LocalExecutionModeGate.ExecutionModeState as Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这次任务我接不接」的判定。
 *
 * 这些断言看着朴素，但它们守的是一件具体的事：中心要**按拒绝原因换策略**
 * （busy 换一台、not_ready 等会儿再问同一台、policy_declined 别重试、
 * no_permission 去问人）。原因一旦串了，中心的重试行为就全错，而现象只是
 * "有时候派不出去" —— 没有报错，没法排查。
 */
class ExecutionCommitmentEvaluatorTest {

    private val now = 1_000_000L

    private fun evaluate(
        mode: Mode,
        inFlight: Boolean = false,
        permitted: Boolean = true,
    ) = ExecutionCommitmentEvaluator.evaluate(
        ExecutionCommitmentEvaluator.DeviceSituation(mode, inFlight, permitted),
        now,
    )

    @Test
    fun `a ready idle device accepts and its promise carries a deadline`() {
        val c = evaluate(Mode.CROSS_DEVICE_ACTIVE)
        assertTrue(c.accepted)
        assertNull(c.declineReason)
        assertEquals(now + ExecutionCommitmentEvaluator.DEFAULT_TTL_MS, c.validUntilMs)
        assertEquals(ExecutionCommitmentEvaluator.LEVEL_FULL, c.bestLevel)
    }

    @Test
    fun `a promise never goes out without a deadline even when the ttl is zero`() {
        // V2 把 valid_until_ms == 0 直接判为不可用 —— 没有有效期等于让中心去赌。
        val c = ExecutionCommitmentEvaluator.evaluate(
            ExecutionCommitmentEvaluator.DeviceSituation(Mode.CROSS_DEVICE_ACTIVE),
            now,
            ttlMs = 0L,
        )
        assertTrue("有效期为 0 的承诺会被中心整条丢掉", c.validUntilMs > now)
    }

    @Test
    fun `a degraded device still accepts and says so`() {
        // 中心通过能力快照早就知道它降级了。设备在这里替中心判断"这次要不要那份
        // 被降掉的能力"，等于把一个中心信息更全的决定抢过来做。
        val c = evaluate(Mode.CROSS_DEVICE_DEGRADED)
        assertTrue(c.accepted)
        assertEquals(ExecutionCommitmentEvaluator.LEVEL_DEGRADED, c.bestLevel)
    }

    @Test
    fun `local only is a policy refusal not a readiness one`() {
        // 这两个都"不接跨设备任务"，但中心的反应必须相反：
        // local_only 是有意退出，重试多少次都一样；not_ready 过一会儿就好了。
        val c = evaluate(Mode.LOCAL_ONLY)
        assertFalse(c.accepted)
        assertEquals(ExecutionCommitmentEvaluator.DeclineReason.POLICY_DECLINED, c.declineReason)
    }

    @Test
    fun `inactive and transitioning are both worth asking again later`() {
        assertEquals(
            ExecutionCommitmentEvaluator.DeclineReason.NOT_READY,
            evaluate(Mode.INACTIVE).declineReason,
        )
        assertEquals(
            ExecutionCommitmentEvaluator.DeclineReason.NOT_READY,
            evaluate(Mode.TRANSITIONING).declineReason,
        )
    }

    @Test
    fun `a refusal carries no deadline because it is about now not about a window`() {
        assertEquals(0L, evaluate(Mode.LOCAL_ONLY).validUntilMs)
    }

    @Test
    fun `being unable outranks being busy`() {
        // 一台没就位的设备说"我忙"是误导性的：中心会等它闲下来再问，
        // 而它闲下来之后还是不能做。
        assertEquals(
            ExecutionCommitmentEvaluator.DeclineReason.NOT_READY,
            evaluate(Mode.INACTIVE, inFlight = true).declineReason,
        )
    }

    @Test
    fun `a missing permission outranks being busy too`() {
        // no_permission 让中心去问人要授权；busy 会让它傻等这台闲下来。
        assertEquals(
            ExecutionCommitmentEvaluator.DeclineReason.NO_PERMISSION,
            evaluate(Mode.CROSS_DEVICE_ACTIVE, inFlight = true, permitted = false).declineReason,
        )
    }

    @Test
    fun `a ready device with a task in flight is busy`() {
        assertEquals(
            ExecutionCommitmentEvaluator.DeclineReason.BUSY,
            evaluate(Mode.CROSS_DEVICE_ACTIVE, inFlight = true).declineReason,
        )
    }

    @Test
    fun `the payload uses exactly the field names V2 reads`() {
        // V2 的 Commitment.from_payload 读这几个 key；名字对不上时它不会报错，
        // 只会 fail-closed 成"不接" —— 一条静默的、永远选不中任何设备的通道。
        val p = evaluate(Mode.CROSS_DEVICE_ACTIVE).toPayload("dev-1", "prop-9")
        assertTrue(
            p.keys.containsAll(
                setOf("proposal_id", "device_id", "accepted", "valid_until_ms", "best_level"),
            ),
        )
        assertEquals("prop-9", p["proposal_id"])
        assertEquals("dev-1", p["device_id"])
        // 必须是真正的 Boolean：V2 判的是 `payload.get("accepted") is True`，
        // 字符串 "true" 会被当成"不接"。
        assertEquals(true, p["accepted"])
    }

    @Test
    fun `only a refusal carries a decline reason`() {
        assertFalse(evaluate(Mode.CROSS_DEVICE_ACTIVE).toPayload("d", "p").containsKey("decline_reason"))
        assertEquals(
            "policy_declined",
            evaluate(Mode.LOCAL_ONLY).toPayload("d", "p")["decline_reason"],
        )
    }

    @Test
    fun `every decline reason is one V2 knows`() {
        // 封闭枚举的意义就在两端对得上。多出来的原因在中心侧会退化成"未知失败"，
        // 于是所有失败被一视同仁 —— 这一轮问话的信息量就白拿了。
        val v2Knows = setOf("busy", "not_ready", "policy_declined", "no_permission", "unsupported")
        val ours = ExecutionCommitmentEvaluator.DeclineReason.entries.map { it.wireValue }.toSet()
        assertEquals(v2Knows, ours)
    }
}

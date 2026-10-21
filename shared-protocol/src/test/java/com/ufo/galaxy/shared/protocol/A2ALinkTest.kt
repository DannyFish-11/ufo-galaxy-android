package com.ufo.galaxy.shared.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对外 A2A 的「必达」:留存未确认、去重、按序交付、重连补发。与 V2 `tests/test_external_a2a_link.py`
 * 同一组场景 —— 两端行为必须一致,否则手机与中心之间会出现「一边以为送到了、一边没收到」。
 */
class A2ALinkTest {

    private var n = 0
    private fun pair(): Pair<A2ALink, A2ALink> {
        val ids = { "m${++n}" }
        return A2ALink("s1", "center", "phone-agent", clock = { 1L }, newId = ids) to
            A2ALink("s1", "phone-agent", "center", clock = { 1L }, newId = ids)
    }

    @Test
    fun `a message is kept until it is acknowledged`() {
        val (center, phone) = pair()
        val env = center.make(A2A.Kind.DELEGATE_GOAL, buildJsonObject { put("goal", "打开微信") })
        assertEquals(listOf(env), center.unacked())
        val got = phone.accept(env)
        assertEquals(listOf(env), got.deliver)
        assertNotNull(got.ack)
        center.accept(got.ack!!)
        assertTrue(center.unacked().isEmpty())
    }

    @Test
    fun `a duplicate is acknowledged again but delivered once`() {
        val (center, phone) = pair()
        val env = center.make(A2A.Kind.CONVERSATION)
        assertEquals(1, phone.accept(env).deliver.size)
        val again = phone.accept(env)
        assertTrue(again.deliver.isEmpty())
        assertNotNull("重复的也要回确认,发送方才会停", again.ack)
    }

    @Test
    fun `out of order messages are delivered in order`() {
        val (center, phone) = pair()
        val a = center.make(A2A.Kind.DELEGATE_PROGRESS)
        val b = center.make(A2A.Kind.DELEGATE_PROGRESS)
        val c = center.make(A2A.Kind.DELEGATE_RESULT)
        assertTrue(phone.accept(c).deliver.isEmpty())
        assertTrue(phone.accept(b).deliver.isEmpty())
        assertEquals(listOf(a, b, c), phone.accept(a).deliver)
    }

    @Test
    fun `after a reconnect the unacknowledged ones are resent and nothing is delivered twice`() {
        val (center, phone) = pair()
        val a = center.make(A2A.Kind.CONTEXT_RETURN)
        val b = center.make(A2A.Kind.RECALL)
        phone.accept(a) // a 到了但确认在断线时丢了;b 根本没到
        val resend = center.unacked()
        assertEquals(listOf(a, b), resend)
        val delivered = resend.flatMap { phone.accept(it).deliver }
        assertEquals(listOf(b), delivered)
    }

    @Test
    fun `messages that need no ack are not kept`() {
        val (center, _) = pair()
        center.make(A2A.Kind.DELEGATE_PROGRESS, ackRequired = false)
        assertTrue(center.unacked().isEmpty())
    }

    @Test
    fun `an ack is unordered and never acknowledged itself`() {
        val (center, phone) = pair()
        val ack = phone.accept(center.make(A2A.Kind.TOOL_CALL)).ack!!
        assertEquals(0L, ack.seq)
        assertEquals(A2A.Kind.ACK, ack.kind)
        assertNull(center.accept(ack).ack)
    }

    @Test
    fun `the wire form round trips and rejects what it does not know`() {
        val (center, _) = pair()
        val env = center.make(A2A.Kind.AGENT_DISPATCH, buildJsonObject { put("bridge", "v1") }, taskId = "t1", traceId = "tr")
        assertEquals(env, A2AEnvelope.fromJson(env.toJson()))
        val frame = env.toAipFrame("phone-1")
        assertEquals("a2a", (frame["type"] as JsonPrimitive).content)
        val bad = buildJsonObject {
            env.toJson().forEach { (k, v) -> put(k, v) }
            put("kind", "made_up")
        }
        assertNull(A2AEnvelope.fromJson(bad))
    }

    @Test
    fun `a gap that never fills does not jam the link`() {
        val (center, phone) = pair()
        center.make(A2A.Kind.CONVERSATION) // seq 1 永远丢了
        val later = (0..A2ALink.MAX_BUFFERED).map { center.make(A2A.Kind.CONVERSATION) }
        val out = later.flatMap { phone.accept(it).deliver }
        assertEquals("缺口补不上时按序交出缓着的,不卡死", later, out)
    }

    @Test
    fun `when the phone restarts its new messages are still delivered`() {
        val (center, phone) = pair()
        repeat(3) { center.accept(phone.make(A2A.Kind.CONVERSATION)) }
        val reborn = A2ALink("s1", "phone-agent", "center") // 手机进程重启:序号从 1 重新数
        val first = reborn.make(A2A.Kind.CONVERSATION, buildJsonObject { put("text", "我回来了") })
        assertEquals("不能因为序号比上一次小就当成旧消息吞掉", listOf(first), center.accept(first).deliver)
    }

    @Test
    fun `when the center restarts it picks up where the phone is`() {
        val (center, phone) = pair()
        repeat(3) { phone.accept(center.accept(phone.make(A2A.Kind.CONVERSATION)).ack!!) }
        val reborn = A2ALink("s1", "center", "phone-agent") // 中心重启:不认识手机这一次的身份
        val next = phone.make(A2A.Kind.DELEGATE_RESULT)
        assertEquals("不能干等手机早就了结的 1..3", listOf(next), reborn.accept(next).deliver)
    }

    @Test
    fun `after a center restart the phone's resend fills in what was never acknowledged`() {
        val (center, phone) = pair()
        phone.accept(center.accept(phone.make(A2A.Kind.CONVERSATION)).ack!!)
        val lost = listOf(phone.make(A2A.Kind.CONVERSATION), phone.make(A2A.Kind.CONVERSATION))
        val reborn = A2ALink("s1", "center", "phone-agent")
        val out = phone.unacked().flatMap { reborn.accept(it).deliver }
        assertEquals(lost.map { it.msgId }, out.map { it.msgId })
    }

    @Test
    fun `a lost message that needed no ack does not hold back the next`() {
        val (center, phone) = pair()
        phone.make(A2A.Kind.DELEGATE_PROGRESS, ackRequired = false) // 丢了,也没人会补
        val next = phone.make(A2A.Kind.DELEGATE_RESULT)
        assertEquals(listOf(next), center.accept(next).deliver)
    }

    @Test
    fun `what was buffered when the peer restarted is still delivered`() {
        val (center, phone) = pair()
        phone.make(A2A.Kind.CONVERSATION) // seq 1 永远没到
        val held = phone.make(A2A.Kind.CONVERSATION)
        assertTrue("等 1", center.accept(held).deliver.isEmpty())
        val reborn = A2ALink("s1", "phone-agent", "center")
        val fresh = reborn.make(A2A.Kind.CONVERSATION)
        assertEquals("已确认过的不能因为对端重启就丢了", listOf(held, fresh), center.accept(fresh).deliver)
    }

    @Test
    fun `the kinds are exactly the ones v2 knows`() {
        assertEquals(
            setOf(
                "agent_dispatch", "agent_installed", "context_handoff", "conversation",
                "delegate_goal", "delegate_progress", "delegate_result",
                "tool_call", "tool_result", "context_return", "recall", "ack",
            ),
            A2A.Kind.ALL,
        )
        assertEquals("a2a", A2A.AIP_TYPE)
    }
}

package com.ufo.galaxy.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一条提问只答一次:中心撤回了、过了 `timeout_ms`、已经答过的,再点也不发(V2 `docs/DEVICE_CLIENT_REQUIREMENTS.md` A1)。
 * 决定结束后才到的回答,中心会当成人说的一句话交给智能体 —— 所以宁可不发。
 */
class DecisionBookTest {

    private fun decision(id: String, timeoutMs: Long? = 90_000L) = DecisionFrames.Decision(
        id, "要删掉 a.txt 吗?", "", listOf(DecisionFrames.Option("approve", "批准"), DecisionFrames.Option("deny", "不要")), timeoutMs,
    )

    @Test
    fun `a decision is answered once`() {
        val book = DecisionBook()
        assertTrue(book.open(decision("d-1"), nowMs = 0))
        assertEquals(90_000L, book.deadlineOf("d-1"))
        assertTrue(book.claim("d-1", nowMs = 1_000))
        assertFalse("同一条第二次点不再发", book.claim("d-1", nowMs = 2_000))
        assertNull("答过了,点开通知也不再显示", book.peek("d-1", nowMs = 2_000))
        assertFalse("中心重发同一条也不再弹", book.open(decision("d-1"), nowMs = 3_000))
    }

    @Test
    fun `a withdraw closes only the decision it names`() {
        val book = DecisionBook()
        book.open(decision("d-1"), 0)
        book.open(decision("d-2"), 0)
        assertTrue("还摆着,要收起通知", book.withdraw("d-1"))
        assertFalse("撤回之后再点不发", book.claim("d-1", 1_000))
        assertTrue("另一条不受影响", book.isOpen("d-2"))
        assertTrue(book.claim("d-2", 1_000))
        assertFalse("已经答过的,撤回什么都不用做", book.withdraw("d-2"))
    }

    @Test
    fun `after the timeout the reply is not sent`() {
        val book = DecisionBook()
        book.open(decision("d-1", timeoutMs = 10_000), nowMs = 5_000)
        assertEquals(15_000L, book.deadlineOf("d-1"))
        assertTrue(book.peek("d-1", 14_999) != null)
        assertNull("到期就不显示了", book.peek("d-1", 15_000))
        assertFalse("到期再点不发", book.claim("d-1", 15_001))

        val other = DecisionBook()
        other.open(decision("d-2", timeoutMs = 10_000), nowMs = 0)
        assertFalse("没先点开,直接点按钮也一样按到期算", other.claim("d-2", 10_000))
    }

    @Test
    fun `a repeated request keeps the first deadline`() {
        val book = DecisionBook()
        book.open(decision("d-1", timeoutMs = 10_000), nowMs = 0)
        assertTrue("重连后中心重发同一条:更新内容", book.open(decision("d-1", timeoutMs = 10_000), nowMs = 8_000))
        assertEquals("到期时刻按第一次收到的算", 10_000L, book.deadlineOf("d-1"))
    }

    @Test
    fun `after a restart the button's own deadline decides`() {
        val book = DecisionBook()
        assertTrue("没过期、没结过:认一次", book.claim("d-1", nowMs = 1_000, deadlineHintMs = 5_000))
        assertFalse(book.claim("d-1", nowMs = 1_500, deadlineHintMs = 5_000))
        assertFalse("过期了:不发", DecisionBook().claim("d-2", nowMs = 6_000, deadlineHintMs = 5_000))
        assertFalse("不知道还算不算数:不发", DecisionBook().claim("d-3", nowMs = 1_000, deadlineHintMs = null))
        assertFalse("中心已撤回的,重启前的按钮也不发", DecisionBook().apply { withdraw("d-4") }.claim("d-4", 1_000, 5_000))
    }

    @Test
    fun `a decision without a timeout stays open until answered or withdrawn`() {
        val book = DecisionBook()
        book.open(decision("d-1", timeoutMs = null), 0)
        assertNull(book.deadlineOf("d-1"))
        assertTrue(book.peek("d-1", Long.MAX_VALUE - 1) != null)
        assertTrue(book.claim("d-1", Long.MAX_VALUE - 1))
    }

    @Test
    fun `an agent message is not taken as a reply to a decision`() {
        val msg = """{"type":"agent_message","payload":{"text":"删好了","decision_id":"d-1"}}"""
        assertNull(DecisionFrames.parseRequest(msg))
        assertNull(DecisionFrames.parseWithdraw(msg))
        val book = DecisionBook()
        book.open(decision("d-1"), 0)
        assertTrue("没被别的帧关掉", book.isOpen("d-1"))
    }

    @Test
    fun `the closed list does not grow without bound`() {
        val book = DecisionBook()
        repeat(DecisionBook.MAX_CLOSED + 10) { book.withdraw("w-$it") }
        assertTrue("最早结过的被挤掉后,账里没有、也没有到期时刻,照样不发", !book.claim("w-0", 0))
        assertFalse(book.claim("w-${DecisionBook.MAX_CLOSED + 9}", 0, deadlineHintMs = 1_000))
    }
}

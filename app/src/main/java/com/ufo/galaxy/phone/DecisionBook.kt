package com.ufo.galaxy.phone

/**
 * 手机上摆着的那几条提问,各自还算不算数(V2 `docs/DEVICE_CLIENT_REQUIREMENTS.md` A1)。
 *
 * 一条提问只答一次;别处答了 / 中心撤回(`decision_withdraw`)、或过了 `timeout_ms`,就不再回 ——
 * 决定结束后再回,中心会把这次回答当成人说的一句话交给智能体,所以宁可不发。
 *
 * 进程重启后这本账是空的,而通知还挂着:按钮里带着到期时刻([claim] 的 `deadlineHintMs`),没到期、没结过的照样认一次。
 *
 * 纯 Kotlin,不碰 Android。
 */
class DecisionBook {

    private data class Entry(val decision: DecisionFrames.Decision, val deadlineMs: Long?)

    private val open = LinkedHashMap<String, Entry>()

    /** 结过的(答了、撤了、过期了),再来的回答一律不发。 */
    private val closed = LinkedHashSet<String>()

    /**
     * 摆出来一条。返回 false = 这条已经结过(答了、撤了、过期了),不要再弹。
     * 重复收到同一条(重连后重发)只更新内容,到期时刻按第一次收到的算。
     */
    @Synchronized
    fun open(d: DecisionFrames.Decision, nowMs: Long): Boolean {
        if (d.decisionId.isBlank() || d.decisionId in closed) return false
        val deadline = open[d.decisionId]?.deadlineMs ?: d.timeoutMs?.let { nowMs + it }
        open[d.decisionId] = Entry(d, deadline)
        return true
    }

    /** 这条的到期时刻(没给 `timeout_ms`、或不在账里是 null)。 */
    @Synchronized
    fun deadlineOf(decisionId: String): Long? = open[decisionId]?.deadlineMs

    /** 还在等人答的那一条(点开通知时用);撤了、过期了、答过了是 null。 */
    @Synchronized
    fun peek(decisionId: String, nowMs: Long): DecisionFrames.Decision? {
        val e = open[decisionId] ?: return null
        if (e.deadlineMs != null && nowMs >= e.deadlineMs) {
            close(decisionId)
            return null
        }
        return e.decision
    }

    /**
     * 人点了:这一回答还发不发。只有第一次、且没撤没过期时返回 true(同时记成结过)。
     * 账里没有(进程重启过)时看按钮带来的到期时刻 [deadlineHintMs]:没到期、没结过才认。
     */
    @Synchronized
    fun claim(decisionId: String, nowMs: Long, deadlineHintMs: Long? = null): Boolean {
        if (decisionId.isBlank() || decisionId in closed) return false
        val e = open[decisionId]
        val deadline = e?.deadlineMs ?: deadlineHintMs?.takeIf { it > 0 }
        if (e == null && deadline == null) return false
        close(decisionId)
        return deadline == null || nowMs < deadline
    }

    /**
     * 中心撤回(别处答了、超时、取消、被新的顶掉)。返回 true = 这条还摆着、要收起通知;
     * 人已经点过了(或从没收到过)就是 false,什么都不用做。
     */
    @Synchronized
    fun withdraw(decisionId: String): Boolean {
        val wasOpen = open.containsKey(decisionId)
        close(decisionId)
        return wasOpen
    }

    @Synchronized
    fun isOpen(decisionId: String): Boolean = open.containsKey(decisionId)

    private fun close(decisionId: String) {
        open.remove(decisionId)
        closed.add(decisionId)
        while (closed.size > MAX_CLOSED) closed.remove(closed.first())
    }

    companion object {
        /** 结过的记多少条(够挡住迟到的点击与重复帧,不无限长)。 */
        const val MAX_CLOSED = 256
    }
}

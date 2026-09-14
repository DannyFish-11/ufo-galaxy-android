package com.ufo.galaxy.runtime

import com.ufo.galaxy.network.OfflineTaskQueue
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 策略描述里的数字必须和实现对得上。
 *
 * [OfflineQueueReplayPolicy] 存在的全部意义,是给下游一份**关于离线队列语义的权威
 * 描述**,让人不必回去读实现。它此前四处写着 "max 50 messages",而
 * [OfflineTaskQueue.MAX_QUEUE_SIZE] 早已是 1000 —— 差 20 倍,而且外面还套着一整套
 * 测试(Pr71OfflineQueueReplayPolicyTest),看起来像是被验证过的。
 *
 * 那一整套测试验的是结构:维度齐不齐、状态值对不对、wire map 稳不稳。**没有一条**
 * 验描述里的数字和被描述的东西是否一致 —— 于是 C12-FIX 把上限从 50 提到 1000 时,
 * 策略描述留在原地,一片绿。
 *
 * 这一条补的就是那个缺口。
 */
class ReplayPolicyNumbersMatchTheQueueTest {

    private fun policySource(): String {
        val candidates = listOf(
            File("src/main/java/com/ufo/galaxy/runtime/OfflineQueueReplayPolicy.kt"),
            File("app/src/main/java/com/ufo/galaxy/runtime/OfflineQueueReplayPolicy.kt"),
            File("../app/src/main/java/com/ufo/galaxy/runtime/OfflineQueueReplayPolicy.kt"),
        )
        val f = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "找不到 OfflineQueueReplayPolicy.kt。试过:" +
                    candidates.joinToString { it.absolutePath } +
                    "。这里刻意不跳过 —— 一个『找不到就当通过』的守卫等于没有守卫。"
            )
        return f.readText()
    }

    @Test
    fun `报告里出现的队列上限就是实现里的那个数`() {
        val cap = OfflineTaskQueue.MAX_QUEUE_SIZE.toString()
        val report = OfflineQueueReplayPolicy.buildReport()
        val talksAboutCap = report.entries.flatMap {
            listOfNotNull(it.limitations, it.evidenceReference)
        }.filter { it.contains("capped at") || it.contains("max ") || it.contains("at most") }

        assertTrue("没有任何一条描述提到队列上限 —— 断言失去了对象", talksAboutCap.isNotEmpty())
        talksAboutCap.forEach { text ->
            assertTrue(
                "这条描述提到了上限却不是 $cap:\n$text",
                text.contains(cap),
            )
        }
    }

    @Test
    fun `源文件里不再手写队列上限的数字`() {
        // 光让当前的数字对上还不够:下次实现改了,手写的数字照样会留在原地。
        // 所以钉的是"不许手写",而不是"手写的那个值得对"。
        val src = policySource()
        assertTrue(
            "策略描述没有引用 OfflineTaskQueue.MAX_QUEUE_SIZE —— 数字一旦手写就会再漂一次",
            src.contains("OfflineTaskQueue.MAX_QUEUE_SIZE"),
        )
        assertFalse(
            "策略描述里还留着手写的 \"50 messages\"",
            src.contains("50 messages"),
        )
    }

    @Test
    fun `TTL 那个数也对得上`() {
        // 24 小时这个数在两边都是手写的。它眼下是对的,钉住它,别让它变成下一个 50。
        val report = OfflineQueueReplayPolicy.buildReport()
        val mentionsTtl = report.entries.flatMap {
            listOfNotNull(it.limitations, it.evidenceReference)
        }.filter { it.contains("24 h") || it.contains("24 hour") }
        assertTrue("没有任何一条描述提到 TTL", mentionsTtl.isNotEmpty())
    }
}

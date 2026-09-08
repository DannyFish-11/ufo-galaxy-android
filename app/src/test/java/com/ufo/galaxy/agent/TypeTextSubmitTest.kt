package com.ufo.galaxy.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「填完要不要按回车」这件事，两条执行路径必须做同一个决定。
 *
 * ## 为什么这是一条要单独钉的性质
 *
 * 输入这一步此前只做 `ACTION_SET_TEXT`：字填进去了，**从不提交**。搜索、发消息这类
 * 任务于是永远停在"填完"那一刻 —— 而每一步都报成功，看起来像是模型没规划对。
 *
 * 但"总是回车"同样是错的，而且错的方向相反：聊天框里多按一次回车，消息就发出去了。
 * 所以只能由规划器按当前这一步的语义决定，落在 `parameters.submit` 上。
 *
 * 而这份参数会被**两条**执行路径消费：本地闭环的 [com.ufo.galaxy.loop.ExecutorBridge]
 * 和跨设备的 [EdgeExecutor]。两处各写一遍解析，结果就是同一份计划在两条路上做不同的事。
 * 这个仓在别处吃过这个亏，所以解析收在 [AccessibilityExecutor.AccessibilityAction.TypeText.from]
 * 一处，本文件钉住它的判据。
 */
class TypeTextSubmitTest {

    private fun from(vararg pairs: Pair<String, String>, fallback: String = "") =
        AccessibilityExecutor.AccessibilityAction.TypeText.from(mapOf(*pairs), fallback)

    @Test
    fun `默认不提交`() {
        // 安全的那一侧：少一次回车任务停住（看得见），多一次回车消息发出去（收不回）。
        assertFalse(from("text" to "你好").submit)
    }

    @Test
    fun `模型说要提交就提交`() {
        assertTrue(from("text" to "咖啡", "submit" to "true").submit)
    }

    @Test
    fun `认得出模型的几种写法`() {
        // 同一个模型在不同轮次里会写成 true / "1" / yes / TRUE，都得认。
        for (raw in listOf("true", "TRUE", " True ", "1", "yes", "YES")) {
            assertTrue("没认出 submit=$raw", from("text" to "x", "submit" to raw).submit)
        }
    }

    @Test
    fun `看不懂的值一律不提交`() {
        // 关键是**不许猜**：看不懂就当没说，而没说 = 不提交。
        for (raw in listOf("false", "0", "no", "", "maybe", "真", "null")) {
            assertFalse("submit=$raw 被当成了真", from("text" to "x", "submit" to raw).submit)
        }
    }

    @Test
    fun `text 缺省时用调用方给的兜底`() {
        assertEquals("点开搜索框", from(fallback = "点开搜索框").text)
    }

    @Test
    fun `跨设备路径没有兜底文本时填空串，而不是填进一句意图描述`() {
        // EdgeExecutor 那条路径不传 fallback。往输入框里填一句"输入搜索关键词"
        // 比什么都不填更糟：它会真的被搜索出去。
        assertEquals("", from().text)
    }

    @Test
    fun `text 存在时兜底不生效`() {
        assertEquals("拿铁", from("text" to "拿铁", fallback = "点开搜索框").text)
    }
}

package com.ufo.galaxy.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结构化快照的 prompt 渲染与意图匹配测试(纯 JVM)。
 */
class UiStructuredSnapshotTest {

    private fun element(
        index: Int,
        text: String = "",
        desc: String = "",
        clickable: Boolean = false,
        l: Int = 0, t: Int = 0, r: Int = 100, b: Int = 50
    ) = UiStructuredSnapshot.UiElement(
        index = index, text = text, contentDescription = desc,
        className = "android.widget.TextView", clickable = clickable,
        left = l, top = t, right = r, bottom = b
    )

    @Test
    fun `toPromptBlock renders one line per labeled element with center coords`() {
        val snap = UiStructuredSnapshot(
            screenWidth = 1080, screenHeight = 2340,
            elements = listOf(
                element(0, text = "Login", clickable = true, l = 400, t = 1100, r = 680, b = 1240),
                element(1, desc = "avatar")
            )
        )
        val block = snap.toPromptBlock()
        val lines = block.lines()
        assertEquals(2, lines.size)
        assertTrue("首行应含编号/标签/类型/clickable/中心点: ${lines[0]}",
            lines[0].contains("[0]") && lines[0].contains("\"Login\"") &&
                lines[0].contains("TextView") && lines[0].contains("clickable") &&
                lines[0].contains("(540,1170)"))
        assertTrue("desc 元素用 contentDescription 作标签", lines[1].contains("\"avatar\""))
        assertFalse("非 clickable 行不得带 clickable 标记", lines[1].contains("clickable"))
    }

    @Test
    fun `toPromptBlock skips unlabeled elements and returns empty when none labeled`() {
        val snap = UiStructuredSnapshot(
            elements = listOf(element(0), element(1, clickable = true))
        )
        assertEquals("无语义标签元素不应产生任何 prompt 内容", "", snap.toPromptBlock())
    }

    @Test
    fun `toPromptBlock truncation prefers clickable elements`() {
        val many = (0 until 10).map { i ->
            element(i, text = "item$i", clickable = i == 9)
        }
        val block = UiStructuredSnapshot(elements = many).toPromptBlock(maxElements = 3)
        assertTrue("截断时 clickable 元素必须保留", block.contains("\"item9\""))
        assertEquals(3, block.lines().size)
    }

    @Test
    fun `long labels are truncated in prompt`() {
        val longText = "x".repeat(200)
        val block = UiStructuredSnapshot(
            elements = listOf(element(0, text = longText))
        ).toPromptBlock()
        assertTrue(
            "标签必须截断到上限以内",
            block.length < longText.length &&
                block.contains("x".repeat(UiStructuredSnapshot.MAX_LABEL_LENGTH))
        )
    }

    @Test
    fun `matchCandidates ranks exact label containment above weak overlap`() {
        val snap = UiStructuredSnapshot(
            elements = listOf(
                element(0, text = "Settings", clickable = true),
                element(1, text = "Set alarm", clickable = true)
            )
        )
        val candidates = snap.matchCandidates("open Settings")
        assertTrue(candidates.isNotEmpty())
        assertEquals("完整包含的 Settings 应排第一", 0, candidates.first().element.index)
    }

    @Test
    fun `matchCandidates works for cjk labels`() {
        val snap = UiStructuredSnapshot(
            elements = listOf(
                element(0, text = "扫一扫", clickable = true),
                element(1, text = "朋友圈", clickable = true)
            )
        )
        val candidates = snap.matchCandidates("打开扫一扫")
        assertTrue(candidates.isNotEmpty())
        assertEquals(0, candidates.first().element.index)
        assertTrue("完整包含 + 可点击应给出高分", candidates.first().score >= 0.5f)
    }

    @Test
    fun `matchCandidates returns empty for blank intent`() {
        val snap = UiStructuredSnapshot(elements = listOf(element(0, text = "anything")))
        assertTrue(snap.matchCandidates("   ").isEmpty())
    }
}

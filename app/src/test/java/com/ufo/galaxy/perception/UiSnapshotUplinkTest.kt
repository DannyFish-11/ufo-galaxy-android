package com.ufo.galaxy.perception

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结构化界面快照上行（Stage C）。
 *
 * 钉住三件事：默认不上行；上行时字段与 V2 投影器逐字对齐；超限时先保住能点的东西。
 */
class UiSnapshotUplinkTest {

    @After
    fun tearDown() {
        UiSnapshotUplink.setEnabled(UiSnapshotUplink.DEFAULT_ENABLED)
    }

    private fun element(
        index: Int,
        text: String = "",
        desc: String = "",
        clickable: Boolean = false,
        cls: String = "android.widget.TextView"
    ) = UiStructuredSnapshot.UiElement(
        index = index,
        text = text,
        contentDescription = desc,
        className = cls,
        clickable = clickable,
        left = index * 10,
        top = index * 10,
        right = index * 10 + 100,
        bottom = index * 10 + 40
    )

    private fun snapshot(vararg elements: UiStructuredSnapshot.UiElement) =
        UiStructuredSnapshot(
            packageName = "com.tencent.mm",
            screenWidth = 1440,
            screenHeight = 3200,
            elements = elements.toList()
        )

    @Test
    fun `默认不上行`() {
        assertFalse("默认开着的话，每一拍都会传上百个节点", UiSnapshotUplink.DEFAULT_ENABLED)
        assertNull(UiSnapshotUplink.toPayload(snapshot(element(0, text = "发送", clickable = true))))
    }

    @Test
    fun `打开后产出对齐 V2 投影器的字段`() {
        UiSnapshotUplink.setEnabled(true)
        val payload = UiSnapshotUplink.toPayload(
            snapshot(element(0, text = "发送", clickable = true, cls = "android.widget.Button"))
        )
        assertNotNull(payload)
        assertEquals("com.tencent.mm", payload!!.packageName)
        assertEquals(1440, payload.screenWidth)
        assertEquals(1, payload.elements.size)
        val e = payload.elements.first()
        assertEquals("发送", e.text)
        assertEquals("android.widget.Button", e.className)
        assertTrue(e.clickable)
        assertEquals(0, e.left)
        assertEquals(100, e.right)
    }

    @Test
    fun `快照为空时不带这个字段`() {
        UiSnapshotUplink.setEnabled(true)
        assertNull(UiSnapshotUplink.toPayload(null))
    }

    @Test
    fun `全是无标签元素时不带这个字段`() {
        UiSnapshotUplink.setEnabled(true)
        assertNull(UiSnapshotUplink.toPayload(snapshot(element(0), element(1))))
    }

    @Test
    fun `超限时优先保留可点元素`() {
        UiSnapshotUplink.setEnabled(true)
        val many = buildList {
            repeat(UiSnapshotUplink.MAX_UPLINK_ELEMENTS + 40) { i ->
                add(element(i, text = "项目$i", clickable = i % 7 == 0))
            }
        }
        val payload = UiSnapshotUplink.toPayload(snapshot(*many.toTypedArray()))
        assertNotNull(payload)
        assertEquals(UiSnapshotUplink.MAX_UPLINK_ELEMENTS, payload!!.elements.size)
        val clickableTotal = many.count { it.clickable }
        assertEquals(
            "超限截断把能点的东西砍掉了 —— 服务端要看见的首先是能动的",
            clickableTotal,
            payload.elements.count { it.clickable }
        )
    }

    @Test
    fun `截断后仍按原序`() {
        UiSnapshotUplink.setEnabled(true)
        val many = (0 until UiSnapshotUplink.MAX_UPLINK_ELEMENTS + 10).map {
            element(it, text = "项目$it", clickable = it % 3 == 0)
        }
        val payload = UiSnapshotUplink.toPayload(snapshot(*many.toTypedArray()))!!
        val indices = payload.elements.map { it.index }
        assertEquals("序号乱了，模型引用 [n] 会指到别的控件", indices.sorted(), indices)
    }
}

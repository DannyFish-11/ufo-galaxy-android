package com.ufo.galaxy.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图结果的判据。
 *
 * 这个类存在的全部理由是：**别把"等一下就好"和"再试也没用"混成同一个 null**。
 * 所以要钉的就是 [ScreenshotOutcome.retryable] 的边界，以及每个错误码有各自可辨认的原因串。
 */
class ScreenshotOutcomeTest {

    @Test
    fun `有像素才算成功`() {
        assertTrue(ScreenshotOutcome.ok(byteArrayOf(1, 2, 3)).succeeded)
    }

    @Test
    fun `零字节的截图算失败`() {
        // 空字节数组不是"一张空白截图"，是"什么也没拿到"。当成功往下走，
        // 后面每一级（缩放、编码、送模型）都会拿到一个不可解释的输入。
        val outcome = ScreenshotOutcome(bytes = ByteArray(0))

        assertFalse(outcome.succeeded)
        assertEquals("empty_bitmap", outcome.reason)
    }

    @Test
    fun `只有节流值得重试`() {
        assertTrue(
            "节流是唯一「等一个窗口就必然能成」的失败",
            ScreenshotOutcome.failed(ScreenshotOutcome.ERROR_INTERVAL_TIME_SHORT).retryable,
        )
        for (code in listOf(
            ScreenshotOutcome.ERROR_INTERNAL,
            ScreenshotOutcome.ERROR_NO_ACCESSIBILITY_ACCESS,
            ScreenshotOutcome.ERROR_INVALID_DISPLAY,
            ScreenshotOutcome.ERROR_SECURE_WINDOW,
            ScreenshotOutcome.ERROR_UNSUPPORTED_SDK,
            ScreenshotOutcome.ERROR_MAIN_THREAD,
        )) {
            assertFalse(
                "errorCode=$code 重试多少次结果都一样，重试只是白付一个节流窗口",
                ScreenshotOutcome.failed(code).retryable,
            )
        }
    }

    @Test
    fun `成功的结果不该被重试`() {
        assertFalse(ScreenshotOutcome.ok(byteArrayOf(1)).retryable)
    }

    @Test
    fun `超时不重试`() {
        // 等不到任何回调说明系统那侧卡住了，紧接着再发一次只会一起卡。
        val outcome = ScreenshotOutcome.timeout()

        assertFalse(outcome.retryable)
        assertEquals("timeout", outcome.reason)
    }

    @Test
    fun `每个错误码有各自可辨认的原因串`() {
        // 真机日志回流时靠这个串区分"该去设置里修"和"这一屏截不了"。
        val reasons = listOf(
            ScreenshotOutcome.ERROR_INTERVAL_TIME_SHORT to "throttled",
            ScreenshotOutcome.ERROR_SECURE_WINDOW to "secure_window",
            ScreenshotOutcome.ERROR_NO_ACCESSIBILITY_ACCESS to "no_a11y_access",
            ScreenshotOutcome.ERROR_INVALID_DISPLAY to "invalid_display",
            ScreenshotOutcome.ERROR_INTERNAL to "internal_error",
            ScreenshotOutcome.ERROR_UNSUPPORTED_SDK to "unsupported_sdk",
            ScreenshotOutcome.ERROR_MAIN_THREAD to "called_on_main_thread",
        )
        for ((code, expected) in reasons) {
            assertEquals(expected, ScreenshotOutcome.failed(code).reason)
        }
        assertEquals(
            "未知码也要能看出是几号",
            "error_99",
            ScreenshotOutcome.failed(99).reason,
        )
    }

    @Test
    fun `错误码两两不同`() {
        // 撞码会让两种完全不同的失败在日志里长得一样。
        val codes = listOf(
            ScreenshotOutcome.NO_ERROR,
            ScreenshotOutcome.ERROR_INTERNAL,
            ScreenshotOutcome.ERROR_NO_ACCESSIBILITY_ACCESS,
            ScreenshotOutcome.ERROR_INTERVAL_TIME_SHORT,
            ScreenshotOutcome.ERROR_INVALID_DISPLAY,
            ScreenshotOutcome.ERROR_SECURE_WINDOW,
            ScreenshotOutcome.ERROR_UNSUPPORTED_SDK,
            ScreenshotOutcome.ERROR_MAIN_THREAD,
        )
        assertEquals("错误码有重复", codes.size, codes.toSet().size)
    }

    @Test
    fun `自定义码不会和平台码撞`() {
        // 平台的码是从 1 开始递增的正数，将来还会加。自己造的码必须取负。
        assertTrue(
            "ERROR_UNSUPPORTED_SDK 必须为负，否则平台新增一个码就会撞上",
            ScreenshotOutcome.ERROR_UNSUPPORTED_SDK < 0,
        )
        assertTrue(
            "ERROR_MAIN_THREAD 必须为负，同理",
            ScreenshotOutcome.ERROR_MAIN_THREAD < 0,
        )
    }

    @Test
    fun `内容相同的结果相等`() {
        // data class 带 ByteArray 时默认按引用比 —— 覆写没写对的话这条会红。
        assertEquals(
            ScreenshotOutcome.ok(byteArrayOf(1, 2, 3)),
            ScreenshotOutcome.ok(byteArrayOf(1, 2, 3)),
        )
        assertEquals(
            ScreenshotOutcome.ok(byteArrayOf(1, 2, 3)).hashCode(),
            ScreenshotOutcome.ok(byteArrayOf(1, 2, 3)).hashCode(),
        )
        assertNotEquals(
            ScreenshotOutcome.ok(byteArrayOf(1, 2, 3)),
            ScreenshotOutcome.ok(byteArrayOf(1, 2, 4)),
        )
    }
}

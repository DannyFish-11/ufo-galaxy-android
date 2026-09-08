package com.ufo.galaxy.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「服务启用了」不等于「能干活」。
 *
 * 本地闭环踩在三个**各自独立**的能力位上，缺任何一个，对应的调用都会**静默**失当：
 * `rootInActiveWindow` 返回 null、`dispatchGesture` 不生效、`takeScreenshot` 抛异常。
 * 而此前两道就位检查问的都不是这个 —— [ReadinessChecker] 问的是
 * `AccessibilityManager`「服务启用了吗」，
 * [com.ufo.galaxy.local.DefaultLocalLoopReadinessProvider] 里干脆写着
 * `screenshotReady = accessibilityBound`。服务一绑定，两者都报 OK。
 *
 * 于是能力位关着的时候，真机上看到的是「模型选了个坏动作」，排查方向被引向模型。
 * 这类问题最贵的地方不是修，是找。
 */
class A11yCapabilitiesTest {

    private fun all(sdk: Int = 34) = A11yCapabilities(
        serviceBound = true,
        canRetrieveWindowContent = true,
        canPerformGestures = true,
        canTakeScreenshot = true,
        sdkInt = sdk,
    )

    @Test
    fun `三位齐全且系统够新才算全通`() {
        val caps = all()

        assertTrue(caps.allReady)
        assertEquals(emptyList<String>(), caps.blockers)
    }

    @Test
    fun `每一位各自只挡住自己那条通道`() {
        // 三条通道是独立的：少了读屏的位，不该把执行也判死 —— 否则排查时会被引偏。
        val noContent = all().copy(canRetrieveWindowContent = false)
        assertFalse(noContent.perceptionReady)
        assertTrue("读屏位不该影响执行", noContent.actionsReady)
        assertTrue("读屏位不该影响截图", noContent.screenshotReady)

        val noGestures = all().copy(canPerformGestures = false)
        assertFalse(noGestures.actionsReady)
        assertTrue(noGestures.perceptionReady)

        val noShot = all().copy(canTakeScreenshot = false)
        assertFalse(noShot.screenshotReady)
        assertTrue(noShot.perceptionReady)
        assertTrue(noShot.actionsReady)
    }

    @Test
    fun `API 低于 30 时截图必判不可用，哪怕能力位是开的`() {
        // takeScreenshot 是 API 30 引入的，低版本上方法根本不存在；
        // 本应用不申请 MediaProjection，所以没有任何替代路径。
        val old = all(sdk = 29)

        assertFalse("API 29 上不可能截到图", old.screenshotReady)
        assertFalse(old.allReady)
        assertTrue(
            "阻塞原因要说清楚是版本问题，别让人去查能力位：${old.blockers}",
            old.blockers.any { it.contains("API 29") && it.contains("MediaProjection") },
        )
    }

    @Test
    fun `服务没绑定时只报一条原因，不报一串噪声`() {
        // 服务没绑定时后面几位根本读不到，逐条报「未授予」是误导。
        val caps = A11yCapabilities.NOT_BOUND

        assertFalse(caps.allReady)
        assertEquals(1, caps.blockers.size)
    }

    @Test
    fun `服务没绑定时的原因要提到强行停止会永久吊销授权`() {
        // 这是真机上最常见、也最难自己想明白的一种：用户在设置里「强行停止」过一次，
        // 系统就把服务从 mEnabledServices 里摘掉并持久化（AOSP 行为，不是厂商魔改），
        // 之后必须回设置里重新勾选。不写在这儿，看到的人只会以为是应用崩了。
        val reason = A11yCapabilities.NOT_BOUND.blockers.single()

        assertTrue("原因里没提强行停止：$reason", reason.contains("强行停止"))
        assertTrue("原因里没说去哪儿修：$reason", reason.contains("无障碍"))
    }

    @Test
    fun `每条阻塞原因都说清楚了缺它会怎样`() {
        // 这些串会直接出现在任务的失败信息里。看到的人不该还要回来翻代码。
        val caps = A11yCapabilities(serviceBound = true, sdkInt = 34)

        assertEquals(3, caps.blockers.size)
        assertTrue(caps.blockers.any { it.contains("rootInActiveWindow") })
        assertTrue(caps.blockers.any { it.contains("dispatchGesture") })
        assertTrue(caps.blockers.any { it.contains("takeScreenshot") })
    }

    @Test
    fun `截图的版本门钉在 API 30`() {
        // AccessibilityService.takeScreenshot 是 Android 11 / API 30 引入的。
        // 把它调低，「API 29 上不可能截到图」那条会立刻红。
        assertEquals(30, A11yCapabilities.MIN_SDK_FOR_SCREENSHOT)
    }
}

package com.ufo.galaxy.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图闸门的正确性。
 *
 * 要钉住的性质只有一条：**任意两次相邻的截图发起之间，间隔严格大于平台的 333ms**。
 * 平台的判据是 `<=333` 就拒，所以"正好 333"必须仍然要等 —— 这一条最容易写错，
 * 也正是写错之后真机上每一步的第二次截图都被拒的那个原因。
 */
class ScreenshotThrottleTest {

    /** 手动推进的假时钟，配一个只记账不真睡的 sleeper。 */
    private class Fake {
        var now = 1_000L
        val slept = mutableListOf<Long>()
        val throttle = ScreenshotThrottle(clock = { now })
        fun acquire(): Long = throttle.acquire { ms -> slept += ms; now += ms }
    }

    @Test
    fun `第一次截图不用等`() {
        val f = Fake()

        assertEquals(0L, f.acquire())
        assertEquals("第一次不该睡", emptyList<Long>(), f.slept)
    }

    @Test
    fun `紧接着的第二次要等满一个窗口`() {
        // 这就是 LoopController 每步里"动作前截图 + 动作后截图"的形态：
        // 中间没有任何等待，时钟一动不动。
        val f = Fake()
        f.acquire()

        val waited = f.acquire()

        assertEquals("没等满窗口，平台会用 ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT 拒掉这次截图",
            ScreenshotThrottle.MIN_INTERVAL_MS, waited)
    }

    @Test
    fun `正好隔了平台的 333ms 仍然要等`() {
        // AOSP 的判据是 `(now - last) <= 333` 就拒 —— 边界在拒的那一侧。
        // 若把最小间隔写成 333 并用 `>=` 判定，这条会红。
        val f = Fake()
        f.acquire()
        f.now += ScreenshotThrottle.PLATFORM_INTERVAL_MS

        val waited = f.acquire()

        assertTrue("正好 333ms 平台照样拒，必须还要再等一点", waited > 0L)
    }

    @Test
    fun `已经等够了就不再等`() {
        val f = Fake()
        f.acquire()
        f.now += ScreenshotThrottle.MIN_INTERVAL_MS

        assertEquals(0L, f.acquire())
    }

    @Test
    fun `等待是按上一次发起时刻算的，不是按上一次完成时刻`() {
        // 平台在**通过节流检查那一刻**记时间戳，不等截图完成。所以一次慢截图
        // （比如 200ms 才回调）不应该让下一次多等 200ms。
        val f = Fake()
        f.acquire()
        f.now += 200L // 模拟这次截图本身耗时 200ms

        val waited = f.acquire()

        assertEquals("按发起时刻算，只该再等 MIN - 200",
            ScreenshotThrottle.MIN_INTERVAL_MS - 200L, waited)
    }

    @Test
    fun `连续多次的总间隔都满足窗口`() {
        val f = Fake()
        val starts = mutableListOf<Long>()
        repeat(5) {
            f.acquire()
            starts += f.now
        }

        starts.zipWithNext { a, b ->
            assertTrue(
                "相邻两次发起间隔 ${b - a}ms，没有严格超过平台的 ${ScreenshotThrottle.PLATFORM_INTERVAL_MS}ms",
                (b - a) > ScreenshotThrottle.PLATFORM_INTERVAL_MS,
            )
        }
    }

    @Test
    fun `最小间隔严格大于平台阈值`() {
        // 常量本身的守卫：把 MIN_INTERVAL_MS 调回 333 时这条立刻红。
        assertTrue(
            "MIN_INTERVAL_MS 必须严格大于 PLATFORM_INTERVAL_MS，否则边界那一次必被拒",
            ScreenshotThrottle.MIN_INTERVAL_MS > ScreenshotThrottle.PLATFORM_INTERVAL_MS,
        )
    }

    @Test
    fun `时钟回退不会算出负的等待`() {
        val f = Fake()
        f.acquire()
        f.now -= 5_000L

        val waited = f.throttle.waitMillis()

        assertTrue("等待时间不能是负数", waited >= 0L)
    }

    @Test
    fun `并发取号不会有人跳过窗口`() {
        // 闸门要在锁内串行化：否则两个线程同时判定"不用等"，一起去调 takeScreenshot。
        val ticks = java.util.concurrent.atomic.AtomicLong(0L)
        val throttle = ScreenshotThrottle(clock = { ticks.get() })
        val starts = java.util.Collections.synchronizedList(mutableListOf<Long>())

        val threads = (1..4).map {
            Thread {
                repeat(5) {
                    throttle.acquire { ms -> ticks.addAndGet(ms) }
                    starts += ticks.get()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val sorted = starts.sorted()
        sorted.zipWithNext { a, b ->
            assertTrue(
                "并发下出现了间隔 ${b - a}ms 的两次发起 —— 第二次会被平台拒",
                (b - a) > ScreenshotThrottle.PLATFORM_INTERVAL_MS || (b - a) == 0L,
            )
        }
        // 时钟只在等待时推进，所以总推进量必须够 19 个窗口（20 次发起）。
        assertTrue(
            "总时钟推进 ${ticks.get()}ms 不足以容纳 20 次合规发起",
            ticks.get() >= 19 * ScreenshotThrottle.MIN_INTERVAL_MS,
        )
    }
}

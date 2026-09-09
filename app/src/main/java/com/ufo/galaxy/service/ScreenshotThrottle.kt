package com.ufo.galaxy.service

/**
 * `AccessibilityService.takeScreenshot()` 的**调用频率闸门**。
 *
 * 为什么必须有这一层
 * ==================
 * 平台对这个 API 有一条硬节流，写在 AOSP `AccessibilityService.takeScreenshot()` 里：
 *
 * ```java
 * final long currentTimestamp = SystemClock.uptimeMillis();
 * if ((currentTimestamp - mRequestTakeScreenshotTimestampMs)
 *         <= ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIME_MILLIS) {   // 333
 *     sendScreenshotFailure(ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT, ...);
 *     return;
 * }
 * ```
 *
 * 两点容易踩空：
 *
 *  1. 判据是 `<=`，所以"正好隔了 333ms"**照样被拒**，真正的下限是 334ms；
 *  2. 被拒时走的是 `onFailure(errorCode)` 这条**正常回调**，不是异常 —— 调用方拿到的
 *     只是一个 null，看起来和"这一帧截不出来"完全一样。
 *
 * 而 [com.ufo.galaxy.loop.LoopController] 每步最多发起三次截图（动作前、动作后、
 * 重规划各一次），三次之间没有任何等待。于是真机上的表现是：**第二次截图必被拒**，
 * 循环立刻以 `screenshot_failed` 整个终止。这条路在 JVM 单测里永远看不见，因为
 * 单测里的 ScreenshotProvider 是个假的、没有节流。
 *
 * 设计
 * ====
 * [acquire] 是**等到能截为止**再返回，而不是"告诉你现在不能截"。理由是调用方拿到
 * "现在不能截"以后唯一合理的动作就是等，把等待留给调用方只会让每个调用点各写一遍、
 * 且迟早有人漏写。
 *
 * 时间戳在**发起请求时**记录，与平台一致（平台也是在通过节流检查后立刻记录，而不是
 * 等截图完成），所以慢速截图不会额外拉长下一次的等待。
 *
 * 时钟默认用 [System.nanoTime]：单调、不受系统时间调整影响，且在 JVM 与 Android 上
 * 都可用，本文件因此完全不依赖 Android 运行时，可以直接单测。
 */
class ScreenshotThrottle(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {

    companion object {
        /**
         * 平台常量 `ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIME_MILLIS`。
         * 不直接引用 `AccessibilityService` 上的同名常量：它是 API 30 才有的
         * `@hide`/受限符号，而本文件要保持纯 JVM 可测。
         */
        const val PLATFORM_INTERVAL_MS = 333L

        /**
         * 时钟余量。平台用 `SystemClock.uptimeMillis()` 计时，本类用 [System.nanoTime]，
         * 两者都是单调钟但不是同一个源；再加上平台判据是 `<=`（等于 333 也拒），
         * 所以在 334 的真实下限之上再留一帧（16ms）。
         *
         * 这个余量是**纯成本换确定性**：多等十几毫秒不影响任何东西，而少等一毫秒
         * 会让整个会话终止。
         */
        const val CLOCK_MARGIN_MS = 16L

        /** 实际使用的最小间隔。 */
        const val MIN_INTERVAL_MS = PLATFORM_INTERVAL_MS + 1L + CLOCK_MARGIN_MS
    }

    private val lock = Any()

    /** 上一次**发起**截图请求的时刻；[Long.MIN_VALUE] 表示还没截过。 */
    private var lastRequestAtMs: Long = Long.MIN_VALUE

    /**
     * 等到允许截图为止，然后把"本次发起"记下来。
     *
     * @param sleeper 等待实现。默认 [Thread.sleep]；单测注入假的以便断言等了多久。
     * @return 本次实际等待的毫秒数（0 表示无需等待）。
     */
    fun acquire(sleeper: (Long) -> Unit = { Thread.sleep(it) }): Long = synchronized(lock) {
        // 在锁内等待是刻意的：这个闸门的语义就是串行化：若放到锁外，两个线程会同时
        // 判定"不用等"，然后一起去调 takeScreenshot，第二个必被平台拒。
        // 本类的调用方（循环）本就是单线程，锁只是防止将来被并发误用。
        val waited = waitMillisLocked()
        if (waited > 0) sleeper(waited)
        lastRequestAtMs = clock()
        waited
    }

    /**
     * 距离"可以截图"还差多少毫秒；0 表示现在就能截。不修改任何状态，供日志与测试用。
     */
    fun waitMillis(): Long = synchronized(lock) { waitMillisLocked() }

    private fun waitMillisLocked(): Long {
        if (lastRequestAtMs == Long.MIN_VALUE) return 0L
        val elapsed = clock() - lastRequestAtMs
        // 时钟回退（理论上单调钟不会，但不为一个不可能的输入崩掉）：当作"刚截过"。
        if (elapsed < 0L) return minIntervalMs
        val remaining = minIntervalMs - elapsed
        return if (remaining > 0L) remaining else 0L
    }
}

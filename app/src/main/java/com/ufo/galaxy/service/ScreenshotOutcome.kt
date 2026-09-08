package com.ufo.galaxy.service

/**
 * 一次 `takeScreenshot` 的**结构化结果**。
 *
 * 为什么不能只返回 `ByteArray?`
 * ============================
 * 平台把所有失败都走 `onFailure(errorCode)` 这条**正常回调**（不是异常），于是
 * "被 333ms 节流拒了"和"这一帧根本截不出来"到了调用方手里长得一模一样 —— 都是
 * 一个 null。而两者的正确处置完全相反：
 *
 *  · [ERROR_INTERVAL_TIME_SHORT]（节流）：等一个窗口重试，必然能成功；
 *  · [ERROR_SECURE_WINDOW]（FLAG_SECURE 窗口，如银行/密码页）：重试一万次也一样，
 *    该做的是换一条路（读无障碍树）或者直接告诉用户这一屏截不了；
 *  · [ERROR_NO_ACCESSIBILITY_ACCESS]：能力位没开或授权被吊销，该去设置里修，
 *    在循环里重试纯属浪费。
 *
 * 把 errorCode 丢掉，真机上这三种就都表现为"截图失败"，然后 [com.ufo.galaxy.loop.LoopController]
 * 用同一个 `screenshot_failed` 终止整个会话 —— 排查时无从知道到底是哪一种。
 *
 * 本类刻意是**纯 JVM**：错误码在这里重新声明而不是引用
 * `android.accessibilityservice.AccessibilityService` 上的同名常量，这样它可以在
 * 不起 Android 运行时的单测里被构造与断言（见 ScreenshotOutcomeTest）。数值与平台
 * 常量的对应关系写在每个常量的文档里。
 */
data class ScreenshotOutcome(
    val bytes: ByteArray?,
    /** 平台的 errorCode；[NO_ERROR] 表示没有走 onFailure。 */
    val errorCode: Int = NO_ERROR,
    /** 未能在超时内等到任何回调 —— 平台连 onFailure 都没给。 */
    val timedOut: Boolean = false
) {

    /** 拿到了非空像素。空字节数组算失败：一个 0 字节的"截图"没有任何用处。 */
    val succeeded: Boolean get() = bytes != null && bytes.isNotEmpty()

    /**
     * 重试还有意义吗。
     *
     * 只有节流是"等一下就好"；其余失败重试都只是把同一个错误再犯一遍，
     * 而每次重试都要付一个节流窗口的时间。
     */
    val retryable: Boolean get() = !succeeded && errorCode == ERROR_INTERVAL_TIME_SHORT

    /** 供日志与失败原因用的短标签。 */
    val reason: String
        get() = when {
            succeeded -> "ok"
            timedOut -> "timeout"
            errorCode == ERROR_INTERVAL_TIME_SHORT -> "throttled"
            errorCode == ERROR_SECURE_WINDOW -> "secure_window"
            errorCode == ERROR_NO_ACCESSIBILITY_ACCESS -> "no_a11y_access"
            errorCode == ERROR_INVALID_DISPLAY -> "invalid_display"
            errorCode == ERROR_INTERNAL -> "internal_error"
            errorCode == ERROR_UNSUPPORTED_SDK -> "unsupported_sdk"
            errorCode != NO_ERROR -> "error_$errorCode"
            else -> "empty_bitmap"
        }

    // data class 里带 ByteArray 时，自动生成的 equals/hashCode 按**引用**比较 ——
    // 两个内容相同的结果会被判为不等。显式覆写，避免调用方踩这个坑。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenshotOutcome) return false
        return errorCode == other.errorCode &&
            timedOut == other.timedOut &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var h = bytes?.contentHashCode() ?: 0
        h = 31 * h + errorCode
        h = 31 * h + timedOut.hashCode()
        return h
    }

    companion object {
        /** 没有走 onFailure。 */
        const val NO_ERROR = 0

        /** 平台 `ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR`。 */
        const val ERROR_INTERNAL = 1

        /** 平台 `ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS`：截屏能力位没开/授权已失效。 */
        const val ERROR_NO_ACCESSIBILITY_ACCESS = 2

        /** 平台 `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`：撞上 333ms 节流。 */
        const val ERROR_INTERVAL_TIME_SHORT = 3

        /** 平台 `ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY`。 */
        const val ERROR_INVALID_DISPLAY = 4

        /** 平台 `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`（API 34+）：FLAG_SECURE 窗口。 */
        const val ERROR_SECURE_WINDOW = 5

        /**
         * 不是平台的码：设备 API < 30，压根没有 `takeScreenshot` 这个方法。
         * 取负值，以免和平台将来新增的码撞上。
         */
        const val ERROR_UNSUPPORTED_SDK = -1

        /** 成功。 */
        fun ok(bytes: ByteArray) = ScreenshotOutcome(bytes = bytes)

        /** 平台回调了 onFailure。 */
        fun failed(errorCode: Int) = ScreenshotOutcome(bytes = null, errorCode = errorCode)

        /** 等不到任何回调。 */
        fun timeout() = ScreenshotOutcome(bytes = null, timedOut = true)
    }
}

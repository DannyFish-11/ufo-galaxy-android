package com.ufo.galaxy.service

import com.ufo.galaxy.agent.EdgeExecutor

/**
 * 由 [HardwareKeyListener] 支撑的 [EdgeExecutor.ScreenshotProvider]。
 *
 * 截图走 [android.accessibilityservice.AccessibilityService.takeScreenshot]（API 30+）。
 * 拿不到时抛 [IllegalStateException]，由上层映射成结构化失败。
 *
 * ## 异常信息里必须带上原因
 * 平台把所有失败都走 `onFailure(errorCode)` 这条**正常回调**：撞上 333ms 节流、
 * 当前是 FLAG_SECURE 窗口（银行/密码页）、截屏能力位被吊销 —— 到调用方手里全是一个
 * null。此前这里抛的是一句固定的「takeScreenshot requires Android 11 (API 30) or later」,
 * 于是真机上无论哪种失败,日志里看到的都是这同一句**而且往往是错的那一句**。
 *
 * 现在把 [ScreenshotOutcome.reason] 带进异常信息:`throttled` / `secure_window` /
 * `no_a11y_access` / `unsupported_sdk` / `timeout` —— 这几种的处置完全不同,
 * 分不出来就无从下手。
 *
 * 必须在 [HardwareKeyListener] 被用户在无障碍设置里启用之后创建。
 */
class AccessibilityScreenshotProvider : EdgeExecutor.ScreenshotProvider {

    override fun captureJpeg(): ByteArray {
        val service = HardwareKeyListener.instance
            ?: throw IllegalStateException(
                "无障碍服务未连接（reason=service_not_bound）。" +
                    "若刚在系统设置里「强行停止」过本应用，授权会被永久吊销，" +
                    "需回「设置 → 无障碍」重新勾选。"
            )
        val outcome = service.capture()
        return outcome.bytes?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("截图失败（reason=${outcome.reason}）")
    }

    override fun screenWidth(): Int =
        HardwareKeyListener.instance?.getScreenWidth() ?: 0

    override fun screenHeight(): Int =
        HardwareKeyListener.instance?.getScreenHeight() ?: 0
}

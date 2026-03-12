package com.ufo.galaxy.service

import com.ufo.galaxy.agent.EdgeExecutor

/**
 * Concrete [EdgeExecutor.ScreenshotProvider] backed by [HardwareKeyListener].
 *
 * Screenshot capture uses [HardwareKeyListener.captureJpeg] which relies on
 * [android.accessibilityservice.AccessibilityService.takeScreenshot] (API 30+).
 * On API < 30 or when the accessibility service is not active,
 * [captureJpeg] throws [IllegalStateException] which [EdgeExecutor] maps to an
 * ERROR task result.
 *
 * Must be created after [HardwareKeyListener] has been enabled by the user in
 * Android accessibility settings.
 */
class AccessibilityScreenshotProvider : EdgeExecutor.ScreenshotProvider {

    override fun captureJpeg(): ByteArray {
        val service = HardwareKeyListener.instance
            ?: throw IllegalStateException(
                "Accessibility service is not active. " +
                "Enable HardwareKeyListener in Android accessibility settings."
            )
        return service.captureJpeg()
            ?: throw IllegalStateException(
                "Screenshot capture failed: " +
                "takeScreenshot requires Android 11 (API 30) or later."
            )
    }

    override fun screenWidth(): Int =
        HardwareKeyListener.instance?.getScreenWidth() ?: 0

    override fun screenHeight(): Int =
        HardwareKeyListener.instance?.getScreenHeight() ?: 0
}

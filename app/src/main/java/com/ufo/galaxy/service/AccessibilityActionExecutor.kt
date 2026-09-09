package com.ufo.galaxy.service

import com.ufo.galaxy.agent.AccessibilityExecutor

/**
 * Concrete [AccessibilityExecutor] backed by [HardwareKeyListener].
 *
 * All actions are dispatched via the Android AccessibilityService APIs:
 *  - Tap / Scroll → [android.accessibilityservice.AccessibilityService.dispatchGesture]
 *  - TypeText     → [android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT]
 *                   (submit 为真时再加一次 ACTION_IME_ENTER)
 *  - OpenApp      → [android.content.pm.PackageManager.getLaunchIntentForPackage]
 *  - Back / Home  → [android.accessibilityservice.AccessibilityService.performGlobalAction]
 *
 * [execute] returns false (rather than throwing) when the accessibility service is not
 * active, allowing [com.ufo.galaxy.agent.EdgeExecutor] to produce a structured error result.
 *
 * 手势类动作的返回值以系统的 `GestureResultCallback` 为准 —— "已派发"不算数:
 * 被用户触屏或另一个手势打断的手势同样是"已派发",但屏幕上什么也没发生。
 */
class AccessibilityActionExecutor : AccessibilityExecutor {

    override fun execute(action: AccessibilityExecutor.AccessibilityAction): Boolean {
        val service = HardwareKeyListener.instance ?: return false
        return service.executeAction(action)
    }
}

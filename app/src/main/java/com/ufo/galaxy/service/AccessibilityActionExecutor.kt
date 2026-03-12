package com.ufo.galaxy.service

import com.ufo.galaxy.agent.AccessibilityExecutor

/**
 * Concrete [AccessibilityExecutor] backed by [HardwareKeyListener].
 *
 * All actions are dispatched via the Android AccessibilityService APIs:
 *  - Tap / Scroll → [android.accessibilityservice.AccessibilityService.dispatchGesture]
 *  - TypeText     → [android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT]
 *  - OpenApp      → [android.content.pm.PackageManager.getLaunchIntentForPackage]
 *  - Back / Home  → [android.accessibilityservice.AccessibilityService.performGlobalAction]
 *
 * [execute] returns false (rather than throwing) when the accessibility service is not
 * active, allowing [com.ufo.galaxy.agent.EdgeExecutor] to produce a structured error result.
 */
class AccessibilityActionExecutor : AccessibilityExecutor {

    override fun execute(action: AccessibilityExecutor.AccessibilityAction): Boolean {
        val service = HardwareKeyListener.instance ?: return false
        return service.executeAction(action)
    }
}

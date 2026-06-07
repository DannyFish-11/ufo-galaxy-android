package com.ufo.galaxy.agent

/**
 * Accessibility action executor (Mobile-Agent → AccessibilityService).
 * All physical interactions with the device UI are routed through this interface.
 *
 * This is the sole canonical interface retained from the original agent package.
 * Concrete implementations: [com.ufo.galaxy.service.AccessibilityActionExecutor].
 * Active consumers: [EdgeExecutor], [com.ufo.galaxy.loop.ExecutorBridge],
 * [com.ufo.galaxy.service.HardwareKeyListener].
 */
interface AccessibilityExecutor {

    /** Sealed hierarchy of all supported device actions. */
    sealed class AccessibilityAction {
        /** Tap the pixel at ([x], [y]). */
        data class Tap(val x: Int, val y: Int) : AccessibilityAction()

        /** Scroll from ([x], [y]) in [direction] ("up" | "down" | "left" | "right"). */
        data class Scroll(val x: Int, val y: Int, val direction: String) : AccessibilityAction()

        /** Type [text] into the focused input field. */
        data class TypeText(val text: String) : AccessibilityAction()

        /** Launch the application identified by [packageName]. */
        data class OpenApp(val packageName: String) : AccessibilityAction()

        /** Press the system Back button. */
        object Back : AccessibilityAction()

        /** Press the system Home button. */
        object Home : AccessibilityAction()
    }

    /**
     * Executes [action] via the Android AccessibilityService.
     * @return `true` if the action was dispatched successfully.
     */
    fun execute(action: AccessibilityAction): Boolean
}

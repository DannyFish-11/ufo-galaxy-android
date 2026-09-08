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

        /**
         * 往输入框里填 [text]；[submit] 为真时填完再按一次输入法的确认键
         * （搜索 / 发送 / 前往）。
         *
         * 为什么提交要显式表达，而不是"填完总是回车"
         * ==========================================
         * 搜索框里少一次回车，任务停在填完那一刻；聊天框里多一次回车，消息就发出去了。
         * 两种默认都会错，而且错的方向相反 —— 所以由规划器按当前这一步的语义决定，
         * 落在 `parameters.submit` 上（默认 false，即不提交）。
         */
        data class TypeText(val text: String, val submit: Boolean = false) : AccessibilityAction() {
            companion object {
                /**
                 * 从规划器给的 `parameters` 里构造。
                 *
                 * 两条执行路径（本地闭环的 [com.ufo.galaxy.loop.ExecutorBridge] 与跨设备的
                 * [EdgeExecutor]）消费的是**同一份**计划，所以解析必须是同一处 ——
                 * 各写一遍的结果是同一个计划在两条路上做出不同的事，这个仓吃过这个亏。
                 *
                 * `text` 缺省时退回 [fallbackText]（本地路径传 intent）是下策：那样填进去的
                 * 会是"输入搜索关键词"这类意图描述本身。保留它只是不让一次参数缺失变成崩溃，
                 * 真正的修复在规划器 prompt 里把 parameters 的键写清楚。
                 */
                fun from(parameters: Map<String, String>, fallbackText: String = ""): TypeText =
                    TypeText(
                        text = parameters["text"] ?: fallbackText,
                        submit = isTruthy(parameters["submit"])
                    )

                /**
                 * 模型给的布尔值形态不稳定：可能是 `true` / `"true"` / `"1"` / `"yes"`。
                 * 只认这几种为真，其余（含 null、空串、看不懂的值）一律 false ——
                 * 提交是有副作用的动作（消息就发出去了），拿不准的时候不做才是安全的那一侧。
                 */
                internal fun isTruthy(raw: String?): Boolean =
                    raw?.trim()?.lowercase() in setOf("true", "1", "yes")
            }
        }

        /** Launch the application identified by [packageName]. */
        data class OpenApp(val packageName: String) : AccessibilityAction()

        /** Press the system Back button. */
        object Back : AccessibilityAction()

        /** Press the system Home button. */
        object Home : AccessibilityAction()
    }

    /**
     * 通过无障碍服务执行 [action]。
     *
     * @return 动作**确实做完了**才返回 true。手势类动作以系统的
     *   `GestureResultCallback` 为准 —— "已派发"不算数：被用户触屏或另一个手势
     *   打断的手势同样是"已派发"，但屏幕上什么也没发生。
     */
    fun execute(action: AccessibilityAction): Boolean
}

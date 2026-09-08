package com.ufo.galaxy.service

/**
 * 无障碍服务**实际被系统授予**的能力，以及由它推导出的三条执行通道是否可用。
 *
 * 为什么"服务启用了"不等于"能干活"
 * ================================
 * 本地闭环踩在三个各自独立的能力位上：
 *
 * | 调用                   | 依赖的能力位              | 缺了会怎样                          |
 * |------------------------|---------------------------|-------------------------------------|
 * | `rootInActiveWindow`   | canRetrieveWindowContent  | 返回 null —— 整棵树读不到，且不报错  |
 * | `dispatchGesture`      | canPerformGestures        | 不生效 —— 点击与滑动全部落空        |
 * | `takeScreenshot`       | canTakeScreenshot         | 抛 SecurityException                |
 *
 * 而此前两道就位检查问的都不是这个：[ReadinessChecker] 问 `AccessibilityManager`
 * 「这个服务启用了吗」，[com.ufo.galaxy.local.DefaultLocalLoopReadinessProvider] 直接写的是
 * `screenshotReady = accessibilityBound` —— 服务一绑定，两者都报 OK。于是能力位关着
 * 的时候，真机上看到的是"模型选了个坏动作"，排查方向被引向模型。
 *
 * 这些位由系统按 `accessibility_service_config.xml` 授予，运行期可以从
 * `AccessibilityServiceInfo` 上读回来 —— 读回来的是**系统给了什么**，而不是我们
 * 声明了什么，两者可能不一致（比如厂商 ROM 的裁剪）。
 *
 * 另外 `takeScreenshot` 是 API 30 才有的，本模块 minSdk 26：低版本上即使能力位声明了
 * 也没有这个方法，而本应用不申请 MediaProjection，**没有任何替代路径**。所以
 * [sdkInt] 也是判据的一部分。
 *
 * 本类是纯 JVM 数据模型，可以直接构造与断言；采集在 [HardwareKeyListener.capabilities]。
 */
data class A11yCapabilities(
    /** 无障碍服务当前是否绑定。为 false 时下面几位都无从谈起。 */
    val serviceBound: Boolean,
    /** 系统是否授予了 `CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT`。 */
    val canRetrieveWindowContent: Boolean = false,
    /** 系统是否授予了 `CAPABILITY_CAN_PERFORM_GESTURES`。 */
    val canPerformGestures: Boolean = false,
    /** 系统是否授予了 `CAPABILITY_CAN_TAKE_SCREENSHOT`（API 30+）。 */
    val canTakeScreenshot: Boolean = false,
    /** 设备的 `Build.VERSION.SDK_INT`。 */
    val sdkInt: Int = 0
) {

    /** 能不能读到屏幕内容（无障碍树）。 */
    val perceptionReady: Boolean
        get() = serviceBound && canRetrieveWindowContent

    /** 能不能点、能不能滑。 */
    val actionsReady: Boolean
        get() = serviceBound && canPerformGestures

    /**
     * 能不能截图。要同时满足能力位与 API 30 —— 低版本上这个方法根本不存在，
     * 而本应用没有 MediaProjection 之类的替代路径。
     */
    val screenshotReady: Boolean
        get() = serviceBound && canTakeScreenshot && sdkInt >= MIN_SDK_FOR_SCREENSHOT

    /** 三条通道全通才算这台设备能跑本地闭环。 */
    val allReady: Boolean
        get() = perceptionReady && actionsReady && screenshotReady

    /**
     * 说人话的阻塞原因，按"先修哪个"排序。空表示全通。
     *
     * 每条都写清楚**缺了它哪个调用会哑掉**，以及**该去哪儿修** —— 这些原因会直接
     * 出现在任务的失败信息里，看到的人不该还要回来翻代码。
     */
    val blockers: List<String>
        get() = buildList {
            if (!serviceBound) {
                add(
                    "无障碍服务未连接。若刚刚在系统设置里「强行停止」过本应用，" +
                        "授权会被系统永久吊销（这是 Android 的行为，不是某家厂商的毛病），" +
                        "必须回「设置 → 无障碍」里重新勾选一次。"
                )
                return@buildList // 服务没绑定时，下面几位读不到，报了也是噪声
            }
            if (!canRetrieveWindowContent) {
                add(
                    "系统未授予 canRetrieveWindowContent：rootInActiveWindow 会返回 null，" +
                        "无障碍树整棵读不到，而且不报错。"
                )
            }
            if (!canPerformGestures) {
                add("系统未授予 canPerformGestures：dispatchGesture 不生效，点击与滑动全部落空。")
            }
            if (sdkInt < MIN_SDK_FOR_SCREENSHOT) {
                add(
                    "设备是 API $sdkInt，takeScreenshot 需要 API $MIN_SDK_FOR_SCREENSHOT+。" +
                        "本应用不申请 MediaProjection，低版本上没有任何替代的截屏路径，" +
                        "本地闭环在这台设备上跑不起来。"
                )
            } else if (!canTakeScreenshot) {
                add("系统未授予 canTakeScreenshot：takeScreenshot 会抛 SecurityException。")
            }
        }

    companion object {
        /** `AccessibilityService.takeScreenshot` 的引入版本（Android 11）。 */
        const val MIN_SDK_FOR_SCREENSHOT = 30

        /** 服务没绑定时的快照。 */
        val NOT_BOUND = A11yCapabilities(serviceBound = false)
    }
}

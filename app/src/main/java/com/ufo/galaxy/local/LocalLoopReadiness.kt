package com.ufo.galaxy.local

/**
 * Structured readiness model for the local closed-loop inference pipeline.
 *
 * This is the **single source of truth** for whether the local loop can execute
 * end-to-end. Each property represents one subsystem whose availability matters.
 *
 * Obtain instances from [LocalLoopReadinessProvider.getReadiness]; do not construct
 * directly except in tests.
 *
 * @property modelFilesReady       Local model weight files are present and verified on disk.
 * @property plannerLoaded         Unified VLM planner service is loaded and reachable.
 * @property groundingLoaded       Unified VLM grounding service is loaded and reachable.
 * @property accessibilityReady    HardwareKeyListener accessibility service is enabled.
 * @property screenshotReady       Screenshot capture subsystem is available.
 * @property actionExecutorReady   Action executor subsystem is available.
 * @property blockers              All readiness failures present at snapshot time.
 */
data class LocalLoopReadiness(
    val modelFilesReady: Boolean,
    val plannerLoaded: Boolean,
    val groundingLoaded: Boolean,
    val accessibilityReady: Boolean,
    val screenshotReady: Boolean,
    val actionExecutorReady: Boolean,
    val blockers: List<LocalLoopFailureType> = emptyList()
) {
    /**
     * True when every subsystem required for full inference execution is ready.
     * False when any blocker is present.
     */
    val isFullyReady: Boolean
        get() = modelFilesReady && plannerLoaded && groundingLoaded &&
            accessibilityReady && screenshotReady && actionExecutorReady

    /**
     * Derives the overall [LocalLoopState] from the current readiness snapshot.
     *
     * - [LocalLoopState.READY] when [isFullyReady].
     * - [LocalLoopState.UNAVAILABLE] when any [LocalLoopFailureType.isCritical] blocker is present.
     * - [LocalLoopState.DEGRADED] otherwise (non-critical blockers only, e.g. model files missing).
     */
    val state: LocalLoopState
        get() = when {
            isFullyReady -> LocalLoopState.READY
            // 执行通道没有 = 什么都做不了。
            !actionExecutorReady -> LocalLoopState.UNAVAILABLE
            // 两条感知通道**都**没有 = 看不见屏幕,这一步无从判断。
            //
            // 关键是"都"。此前 SCREENSHOT_UNAVAILABLE 是 isCritical=true,于是只要截不了图
            // 整条闭环就被判死 —— 可截图在三种真实情况下本来就拿不到:设备 API < 30
            // (takeScreenshot 是 API 30 才有的,而本模块 minSdk 26)、当前是 FLAG_SECURE
            // 窗口(银行/密码页)、撞上平台的 333ms 节流。这些时候只要无障碍树读得到,
            // 定位(树救场)与变化检测(树指纹)都还成立,闭环完全跑得动。
            //
            // 把「截不了图」当成「跑不了」,等于在 minSdk 与实际可用区间之间凭空挖了一个洞。
            !screenshotReady && !accessibilityReady -> LocalLoopState.UNAVAILABLE
            blockers.any { it.isCritical } -> LocalLoopState.UNAVAILABLE
            else -> LocalLoopState.DEGRADED
        }

    companion object {
        /**
         * Returns an all-false [LocalLoopReadiness] snapshot with every blocker set.
         * Useful as a safe default before the first real check completes.
         */
        fun unavailable(): LocalLoopReadiness = LocalLoopReadiness(
            modelFilesReady = false,
            plannerLoaded = false,
            groundingLoaded = false,
            accessibilityReady = false,
            screenshotReady = false,
            actionExecutorReady = false,
            blockers = LocalLoopFailureType.entries.toList()
        )
    }
}

/**
 * Categorises individual readiness failures for the local loop.
 *
 * Failures whose [isCritical] flag is `true` prevent any execution; non-critical
 * failures may still allow a degraded path (rule-based planning when the model is absent).
 */
enum class LocalLoopFailureType(
    /** True when this failure completely blocks local loop execution. */
    val isCritical: Boolean
) {
    /** Required model weight files are missing or corrupted on disk. */
    MODEL_FILES_MISSING(isCritical = false),

    /** Unified VLM planner service is not loaded or not reachable. */
    PLANNER_UNAVAILABLE(isCritical = false),

    /** Unified VLM grounding service is not loaded or not reachable. */
    GROUNDING_UNAVAILABLE(isCritical = false),

    /**
     * 读不到无障碍树(服务未绑定,或 canRetrieveWindowContent 未授予)。
     *
     * isCritical=false 是刻意的:它单独出现时并不致命 —— 有截图 + 有视觉模型时,
     * 纯视觉路径照样能跑。真正致命的是**两条感知通道都没有**,那由
     * [LocalLoopReadiness.state] 判定,因为那是一个组合条件,不是单项属性。
     */
    ACCESSIBILITY_SERVICE_DISABLED(isCritical = false),

    /**
     * 截不了图(能力位未授予,或设备 API < 30 —— `takeScreenshot` 是 API 30 引入的,
     * 而本模块 minSdk 26)。
     *
     * isCritical **从 true 改为 false**:截不了图不等于跑不了。只要无障碍树读得到,
     * 定位走树救场、变化检测走树指纹,闭环完全成立。FLAG_SECURE 窗口(银行/密码页)
     * 与撞上平台节流同理。同上:致命的是两条通道都没有,由 [LocalLoopReadiness.state] 判。
     */
    SCREENSHOT_UNAVAILABLE(isCritical = false),

    /** Action executor is unavailable (accessibility service not bound). */
    ACTION_EXECUTOR_UNAVAILABLE(isCritical = true);
}

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
 * @property plannerLoaded         MobileVLM planner service is loaded and reachable.
 * @property groundingLoaded       SeeClick grounding service is loaded and reachable.
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

    /** MobileVLM planner service is not loaded or not reachable. */
    PLANNER_UNAVAILABLE(isCritical = false),

    /** SeeClick grounding service is not loaded or not reachable. */
    GROUNDING_UNAVAILABLE(isCritical = false),

    /** HardwareKeyListener accessibility service is not currently enabled. */
    ACCESSIBILITY_SERVICE_DISABLED(isCritical = true),

    /** Screenshot capture is unavailable (accessibility service not bound). */
    SCREENSHOT_UNAVAILABLE(isCritical = true),

    /** Action executor is unavailable (accessibility service not bound). */
    ACTION_EXECUTOR_UNAVAILABLE(isCritical = true);
}

package com.ufo.galaxy.local

/**
 * Coarse-grained state of the local on-device closed loop.
 *
 * Transitions in priority order (worst to best):
 *  [UNAVAILABLE] → [DEGRADED] → [READY]
 *
 * [UNINITIALIZED] is only the initial transient state before the first [LocalLoopReadinessProvider.assess] call.
 * [CHECKING] is set during an in-progress assessment (reserved for async assessments in later milestones).
 */
enum class LocalLoopState {
    /** Readiness has not yet been assessed. */
    UNINITIALIZED,
    /** An assessment is currently in progress. */
    CHECKING,
    /** All critical and non-critical components are ready; autonomous execution is fully operational. */
    READY,
    /**
     * One or more non-critical components are unavailable (e.g., model files missing, planner not loaded).
     * The loop can still execute using rule-based fallbacks but may have reduced capability.
     */
    DEGRADED,
    /**
     * One or more critical components are unavailable (e.g., accessibility service disabled).
     * Autonomous execution is not possible in this state.
     */
    UNAVAILABLE
}

/**
 * Enumeration of specific failure conditions that can block or degrade the local loop.
 *
 * Only the subset required for Milestone 1 (state/entry unification) is listed here.
 * Additional types for later milestones (e.g., RUNTIME_PROCESS_DEAD, VERSION_MISMATCH)
 * will be added in subsequent PRs.
 */
enum class LocalLoopFailureType {
    /** Local model weight files are absent or failed checksum verification. */
    MODEL_FILES_MISSING,
    /** The MobileVLM planner service is not loaded (rule-based fallback will be used). */
    PLANNER_NOT_LOADED,
    /** The SeeClick grounding service is not loaded (coordinate grounding unavailable). */
    GROUNDING_NOT_LOADED,
    /** The HardwareKeyListener accessibility service is not enabled. */
    ACCESSIBILITY_SERVICE_DISABLED,
    /** Screen capture is unavailable (requires accessibility service). */
    SCREENSHOT_UNAVAILABLE,
    /** Action execution is unavailable (requires accessibility service). */
    ACTION_EXECUTOR_UNAVAILABLE,
    /** The SYSTEM_ALERT_WINDOW overlay permission has not been granted. */
    OVERLAY_PERMISSION_MISSING
}

/**
 * Structured snapshot of the local on-device closed-loop readiness.
 *
 * Replaces the scattered boolean checks spread across [com.ufo.galaxy.service.ReadinessChecker],
 * [com.ufo.galaxy.service.ReadinessState], and individual service [isModelLoaded] calls.
 *
 * This is the authoritative readiness model for the local loop. Obtain an instance from
 * [LocalLoopReadinessProvider.assess].
 *
 * @param state               Coarse-grained overall state of the local loop.
 * @param modelFilesReady     Local model weight files are present and pass checksum verification.
 * @param plannerLoaded       MobileVLM planner service is loaded and ready for inference.
 * @param groundingLoaded     SeeClick grounding service is loaded and ready for inference.
 * @param accessibilityReady  HardwareKeyListener accessibility service is currently enabled.
 * @param screenshotReady     Screenshot capability is available (requires accessibility service).
 * @param actionExecutorReady Action execution capability is available (requires accessibility service).
 * @param overlayReady        SYSTEM_ALERT_WINDOW overlay permission has been granted.
 * @param blockers            Ordered list of active failure conditions, most-severe first.
 */
data class LocalLoopReadiness(
    val state: LocalLoopState,
    val modelFilesReady: Boolean,
    val plannerLoaded: Boolean,
    val groundingLoaded: Boolean,
    val accessibilityReady: Boolean,
    val screenshotReady: Boolean,
    val actionExecutorReady: Boolean,
    val overlayReady: Boolean,
    val blockers: List<LocalLoopFailureType> = emptyList()
) {
    /** True when the loop is in [LocalLoopState.READY] state. */
    val isReady: Boolean get() = state == LocalLoopState.READY

    /** True when the loop is degraded but still operational via fallbacks. */
    val isDegraded: Boolean get() = state == LocalLoopState.DEGRADED

    /** True when the loop cannot execute (critical component missing). */
    val isUnavailable: Boolean get() = state == LocalLoopState.UNAVAILABLE

    /** True when any blocker is present (degraded or unavailable). */
    val hasBlockers: Boolean get() = blockers.isNotEmpty()

    companion object {
        /** Returns a readiness snapshot representing the uninitialized state. */
        fun uninitialized(): LocalLoopReadiness = LocalLoopReadiness(
            state = LocalLoopState.UNINITIALIZED,
            modelFilesReady = false,
            plannerLoaded = false,
            groundingLoaded = false,
            accessibilityReady = false,
            screenshotReady = false,
            actionExecutorReady = false,
            overlayReady = false,
            blockers = emptyList()
        )
    }
}

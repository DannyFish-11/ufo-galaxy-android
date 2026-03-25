package com.ufo.galaxy.local

import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.model.ModelAssetManager

/**
 * Provides the unified [LocalLoopReadiness] snapshot for the local on-device closed loop.
 *
 * Replaces the ad-hoc scattered checks across [com.ufo.galaxy.service.ReadinessChecker],
 * [com.ufo.galaxy.service.ReadinessState], and individual [LocalPlannerService.isModelLoaded] /
 * [LocalGroundingService.isModelLoaded] call-sites.
 *
 * Readiness is assessed lazily on each [assess] call; this class holds no cached state.
 * Callers are responsible for caching the returned [LocalLoopReadiness] if needed.
 *
 * All checks are non-blocking and reflect the current system state at the time of the call.
 *
 * @param modelAssetManager   Verifies local model weight files on disk.
 * @param plannerService      MobileVLM planner; checked via [LocalPlannerService.isModelLoaded].
 * @param groundingService    SeeClick grounding engine; checked via [LocalGroundingService.isModelLoaded].
 * @param accessibilityChecker Lambda that returns true when the HardwareKeyListener accessibility
 *                            service is currently bound. Defaults to checking the static instance.
 *                            Injected as a lambda so the provider is JVM-testable without
 *                            Android framework classes.
 * @param overlayChecker      Lambda that returns true when the SYSTEM_ALERT_WINDOW permission
 *                            is currently granted. Injected for the same testability reason.
 */
class LocalLoopReadinessProvider(
    private val modelAssetManager: ModelAssetManager,
    private val plannerService: LocalPlannerService,
    private val groundingService: LocalGroundingService,
    private val accessibilityChecker: () -> Boolean,
    private val overlayChecker: () -> Boolean
) {

    /**
     * Assesses the current local-loop readiness and returns a fresh [LocalLoopReadiness] snapshot.
     *
     * Failure classification:
     *  - Accessibility disabled → [LocalLoopState.UNAVAILABLE] (blocks execution + screenshot).
     *  - Model files missing, planner not loaded, or grounding not loaded → [LocalLoopState.DEGRADED]
     *    (loop falls back to rule-based planning; coordinate grounding is unavailable).
     *  - Overlay permission missing → [LocalLoopState.DEGRADED] (floating overlay unavailable
     *    but core loop can still execute).
     *  - All checks pass → [LocalLoopState.READY].
     */
    fun assess(): LocalLoopReadiness {
        val modelFilesReady = checkModelFiles()
        val plannerLoaded = plannerService.isModelLoaded()
        val groundingLoaded = groundingService.isModelLoaded()
        val accessibilityReady = accessibilityChecker()
        val screenshotReady = accessibilityReady
        val actionExecutorReady = accessibilityReady
        val overlayReady = overlayChecker()

        val blockers = mutableListOf<LocalLoopFailureType>()
        if (!accessibilityReady) {
            blockers += LocalLoopFailureType.ACCESSIBILITY_SERVICE_DISABLED
            blockers += LocalLoopFailureType.SCREENSHOT_UNAVAILABLE
            blockers += LocalLoopFailureType.ACTION_EXECUTOR_UNAVAILABLE
        }
        if (!modelFilesReady) blockers += LocalLoopFailureType.MODEL_FILES_MISSING
        if (!plannerLoaded) blockers += LocalLoopFailureType.PLANNER_NOT_LOADED
        if (!groundingLoaded) blockers += LocalLoopFailureType.GROUNDING_NOT_LOADED
        if (!overlayReady) blockers += LocalLoopFailureType.OVERLAY_PERMISSION_MISSING

        val state = when {
            !accessibilityReady -> LocalLoopState.UNAVAILABLE
            blockers.isEmpty() -> LocalLoopState.READY
            else -> LocalLoopState.DEGRADED
        }

        return LocalLoopReadiness(
            state = state,
            modelFilesReady = modelFilesReady,
            plannerLoaded = plannerLoaded,
            groundingLoaded = groundingLoaded,
            accessibilityReady = accessibilityReady,
            screenshotReady = screenshotReady,
            actionExecutorReady = actionExecutorReady,
            overlayReady = overlayReady,
            blockers = blockers
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun checkModelFiles(): Boolean {
        val statuses = modelAssetManager.verifyAll()
        return statuses.values.all {
            it == ModelAssetManager.ModelStatus.READY || it == ModelAssetManager.ModelStatus.LOADED
        }
    }
}

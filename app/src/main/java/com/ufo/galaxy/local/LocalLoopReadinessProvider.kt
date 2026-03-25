package com.ufo.galaxy.local

import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.service.HardwareKeyListener

/**
 * **Full local-loop readiness gate** — six-subsystem pre-execution readiness check.
 *
 * Provides a [LocalLoopReadiness] snapshot reflecting the state of every subsystem
 * required for the local closed-loop execution pipeline. Evaluated by
 * [com.ufo.galaxy.loop.LoopController] before starting any session.
 *
 * ## Scope — distinction from [com.ufo.galaxy.service.ReadinessChecker]
 * [com.ufo.galaxy.service.ReadinessChecker] is a lightweight **three-check** probe
 * (model files, accessibility, overlay) whose results are persisted to
 * [com.ufo.galaxy.data.AppSettings] and included in the gateway `capability_report`.
 * **This interface** is the richer **six-check** gate that also covers planner loading,
 * grounding loading, and per-subsystem failure categorisation; it is evaluated
 * immediately before local execution begins and is not surfaced in the capability_report.
 *
 * Implementations must be lightweight and non-blocking: they are called from the UI
 * thread for status indicators as well as from background threads before execution.
 */
interface LocalLoopReadinessProvider {

    /**
     * Returns a fresh [LocalLoopReadiness] snapshot.
     * The snapshot reflects the system state at the moment of the call.
     */
    fun getReadiness(): LocalLoopReadiness
}

/**
 * Default [LocalLoopReadinessProvider] that queries all local-loop subsystems.
 *
 * Checks:
 * 1. Model file presence on disk ([ModelAssetManager.verifyAll]).
 * 2. Planner loaded state ([LocalPlannerService.isModelLoaded]).
 * 3. Grounding loaded state ([LocalGroundingService.isModelLoaded]).
 * 4. Accessibility service bound ([HardwareKeyListener.instance] non-null).
 * 5. Screenshot and action-executor availability (derived from accessibility binding).
 *
 * @param modelAssetManager  Source of model file status.
 * @param plannerService     MobileVLM planner backend.
 * @param groundingService   SeeClick grounding backend.
 */
class DefaultLocalLoopReadinessProvider(
    private val modelAssetManager: ModelAssetManager,
    private val plannerService: LocalPlannerService,
    private val groundingService: LocalGroundingService
) : LocalLoopReadinessProvider {

    override fun getReadiness(): LocalLoopReadiness {
        val statuses = modelAssetManager.verifyAll()
        val modelFilesReady = statuses.values.all {
            it == ModelAssetManager.ModelStatus.READY || it == ModelAssetManager.ModelStatus.LOADED
        }
        val plannerLoaded = plannerService.isModelLoaded()
        val groundingLoaded = groundingService.isModelLoaded()

        // Both screenshot capture and action dispatch rely on the accessibility service binding.
        val accessibilityBound = HardwareKeyListener.instance != null
        val screenshotReady = accessibilityBound
        val actionExecutorReady = accessibilityBound

        val blockers = buildList {
            if (!modelFilesReady) add(LocalLoopFailureType.MODEL_FILES_MISSING)
            if (!plannerLoaded) add(LocalLoopFailureType.PLANNER_UNAVAILABLE)
            if (!groundingLoaded) add(LocalLoopFailureType.GROUNDING_UNAVAILABLE)
            if (!accessibilityBound) add(LocalLoopFailureType.ACCESSIBILITY_SERVICE_DISABLED)
            if (!screenshotReady) add(LocalLoopFailureType.SCREENSHOT_UNAVAILABLE)
            if (!actionExecutorReady) add(LocalLoopFailureType.ACTION_EXECUTOR_UNAVAILABLE)
        }

        return LocalLoopReadiness(
            modelFilesReady = modelFilesReady,
            plannerLoaded = plannerLoaded,
            groundingLoaded = groundingLoaded,
            accessibilityReady = accessibilityBound,
            screenshotReady = screenshotReady,
            actionExecutorReady = actionExecutorReady,
            blockers = blockers
        )
    }
}

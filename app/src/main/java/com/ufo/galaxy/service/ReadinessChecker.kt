package com.ufo.galaxy.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.observability.GalaxyLogger

/**
 * Snapshot of the three capability readiness checks used in [ReadinessChecker].
 *
 * @property modelReady          Local model files are present and verified on disk.
 * @property accessibilityReady  [HardwareKeyListener] accessibility service is enabled.
 * @property overlayReady        Overlay (SYSTEM_ALERT_WINDOW) permission is granted.
 * @property degradedMode        True when any check failed; autonomous execution may be limited.
 *
 * @see ReadinessChecker
 */
data class ReadinessState(
    val modelReady: Boolean,
    val accessibilityReady: Boolean,
    val overlayReady: Boolean
) {
    val degradedMode: Boolean
        get() = !modelReady || !accessibilityReady || !overlayReady
}

/**
 * **Capability-report readiness probe** — lightweight three-check surface-level audit.
 *
 * This object performs the three readiness checks whose results are persisted to
 * [com.ufo.galaxy.data.AppSettings] and surfaced in the gateway `capability_report`
 * metadata (`model_ready`, `accessibility_ready`, `overlay_ready`). It is the
 * **minimal readiness contract** understood by the Gateway and is evaluated at app start
 * and whenever capability metadata must be refreshed (e.g., on permission grant, boot).
 *
 * ## Scope — what this is **not**
 * This is **not** the full pre-execution readiness gate for the local closed-loop pipeline.
 * Before the [com.ufo.galaxy.loop.LoopController] runs a task it evaluates the richer
 * six-subsystem check provided by
 * [com.ufo.galaxy.local.LocalLoopReadinessProvider] / [com.ufo.galaxy.local.LocalLoopReadiness],
 * which additionally covers planner loading, grounding loading, per-subsystem failure
 * categorisation, and [com.ufo.galaxy.local.LocalLoopState] transitions.
 *
 * ## Relationship summary
 * | Class | Checks | Used by |
 * |---|---|---|
 * | [ReadinessChecker] | 3 (model files, accessibility, overlay) | capability_report metadata, [com.ufo.galaxy.UFOGalaxyApplication], UI readiness flags |
 * | [com.ufo.galaxy.local.DefaultLocalLoopReadinessProvider] | 6 (above + planner, grounding, screenshot, action executor) | [com.ufo.galaxy.loop.LoopController] pre-execution gate |
 *
 * All checks are non-blocking and reflect the current system state at the time of the call.
 * Results are written back to [com.ufo.galaxy.data.AppSettings] by the caller so they
 * are available to the UI and capability_report metadata without requiring a re-check.
 */
object ReadinessChecker {

    private const val TAG = "ReadinessChecker"

    /**
     * Checks whether all required local model files are present and verified.
     *
     * Re-runs [ModelAssetManager.verifyAll] to refresh disk state, then returns true only
     * if every model is in [ModelAssetManager.ModelStatus.READY] or
     * [ModelAssetManager.ModelStatus.LOADED] state.
     */
    fun checkModelReady(context: Context): Boolean {
        val assetManager = UFOGalaxyApplication.modelAssetManager
        val statuses = assetManager.verifyAll()
        val ready = statuses.values.all {
            it == ModelAssetManager.ModelStatus.READY || it == ModelAssetManager.ModelStatus.LOADED
        }
        Log.d(TAG, "checkModelReady=$ready statuses=$statuses")
        return ready
    }

    /**
     * Checks whether the [HardwareKeyListener] accessibility service is currently enabled.
     *
     * Fast path: returns true if [HardwareKeyListener.instance] is non-null (service already
     * bound). The static `instance` var is marked `@Volatile` on the JVM so the read is
     * thread-safe for a simple null check.
     * Fallback: queries the system-level enabled accessibility services list.
     */
    fun checkAccessibilityReady(context: Context): Boolean {
        if (HardwareKeyListener.instance != null) {
            Log.d(TAG, "checkAccessibilityReady=true (instance bound)")
            return true
        }
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val ready = enabled.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        Log.d(TAG, "checkAccessibilityReady=$ready (system list)")
        return ready
    }

    /**
     * Checks whether the app has been granted the SYSTEM_ALERT_WINDOW (overlay) permission.
     */
    fun checkOverlayReady(context: Context): Boolean {
        val ready = Settings.canDrawOverlays(context)
        Log.d(TAG, "checkOverlayReady=$ready")
        return ready
    }

    /**
     * Runs all three checks and returns a [ReadinessState].
     *
     * Also persists results to [com.ufo.galaxy.data.AppSettings] so that the UI and
     * capability_report metadata reflect current state without requiring a re-check.
     */
    fun checkAll(context: Context): ReadinessState {
        val modelReady = checkModelReady(context)
        val accessibilityReady = checkAccessibilityReady(context)
        val overlayReady = checkOverlayReady(context)
        val state = ReadinessState(
            modelReady = modelReady,
            accessibilityReady = accessibilityReady,
            overlayReady = overlayReady
        )
        Log.i(
            TAG,
            "readiness: modelReady=$modelReady accessibilityReady=$accessibilityReady " +
                "overlayReady=$overlayReady degradedMode=${state.degradedMode}"
        )
        GalaxyLogger.log(GalaxyLogger.TAG_READINESS, mapOf(
            "model_ready" to modelReady,
            "accessibility_ready" to accessibilityReady,
            "overlay_ready" to overlayReady,
            "degraded_mode" to state.degradedMode
        ))
        if (state.degradedMode) {
            GalaxyLogger.log(GalaxyLogger.TAG_DEGRADED, mapOf(
                "model_ready" to modelReady,
                "accessibility_ready" to accessibilityReady,
                "overlay_ready" to overlayReady
            ))
        }
        // Persist to AppSettings so the capability_report and UI can read without re-checking.
        val settings = UFOGalaxyApplication.appSettings
        settings.modelReady = modelReady
        settings.accessibilityReady = accessibilityReady
        settings.overlayReady = overlayReady
        return state
    }
}

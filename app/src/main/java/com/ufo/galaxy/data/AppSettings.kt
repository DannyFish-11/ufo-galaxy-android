package com.ufo.galaxy.data

import android.content.Context

/**
 * Abstraction over persistent application settings.
 *
 * Extracted as an interface so that unit tests can inject an [InMemoryAppSettings]
 * without requiring a real Android [Context].
 *
 * All five keys required by the capability_report metadata payload are covered:
 * [goalExecutionEnabled], [localModelEnabled], [crossDeviceEnabled],
 * [parallelExecutionEnabled], and [deviceRole].
 */
interface AppSettings {
    /**
     * Cross-device collaboration toggle.
     *
     * When [false] (the default) the Android client must NOT connect or register to
     * the gateway WebSocket and must NOT call legacy register endpoints.
     * When [true] the client proceeds normally.
     */
    var crossDeviceEnabled: Boolean

    /** Whether autonomous goal execution is advertised in the capability report. */
    var goalExecutionEnabled: Boolean

    /** Whether on-device model inference is available (updated when models load/unload). */
    var localModelEnabled: Boolean

    /** Whether parallel subtask execution is enabled. */
    var parallelExecutionEnabled: Boolean

    /**
     * Logical role of this device in the cluster.
     * Typical values: "phone", "tablet", "hub".
     */
    var deviceRole: String

    // ── Readiness flags (updated by ReadinessChecker) ────────────────────────

    /** True when all required local model files are present and verified. */
    var modelReady: Boolean

    /** True when the HardwareKeyListener accessibility service is enabled. */
    var accessibilityReady: Boolean

    /** True when the SYSTEM_ALERT_WINDOW (overlay) permission is granted. */
    var overlayReady: Boolean

    /**
     * True when any readiness check has failed.
     * Autonomous execution and the floating window may be limited in this state.
     */
    val degradedMode: Boolean
        get() = !modelReady || !accessibilityReady || !overlayReady

    /**
     * Returns all settings as a [Map] suitable for inclusion in a
     * [CapabilityReport.metadata] payload sent to the gateway.
     *
     * Keys match the field names expected by the server. Gateways that do not
     * understand extra fields will ignore them, preserving backward compatibility.
     */
    fun toMetadataMap(): Map<String, Any> = mapOf(
        "goal_execution_enabled" to goalExecutionEnabled,
        "local_model_enabled" to localModelEnabled,
        "cross_device_enabled" to crossDeviceEnabled,
        "parallel_execution_enabled" to parallelExecutionEnabled,
        "device_role" to deviceRole,
        "model_ready" to modelReady,
        "accessibility_ready" to accessibilityReady,
        "overlay_ready" to overlayReady
    )
}

/**
 * Pure in-memory [AppSettings] implementation.
 *
 * Suitable for use in unit tests where a real Android [Context] and
 * [android.content.SharedPreferences] are unavailable.
 */
class InMemoryAppSettings(
    override var crossDeviceEnabled: Boolean = false,
    override var goalExecutionEnabled: Boolean = false,
    override var localModelEnabled: Boolean = false,
    override var parallelExecutionEnabled: Boolean = false,
    override var deviceRole: String = SharedPrefsAppSettings.DEFAULT_DEVICE_ROLE,
    override var modelReady: Boolean = false,
    override var accessibilityReady: Boolean = false,
    override var overlayReady: Boolean = false
) : AppSettings

/**
 * [android.content.SharedPreferences]-backed implementation of [AppSettings].
 *
 * All writes are applied asynchronously via [apply] so they never block the
 * calling thread. The preference file is stored as a private file named [PREFS_NAME].
 *
 * Default values:
 * - [crossDeviceEnabled]: `false` — device starts in local-only mode until the user
 *   explicitly enables cross-device collaboration via the settings toggle.
 * - [goalExecutionEnabled]: `false`
 * - [localModelEnabled]: `false` — updated to `true` by [GalaxyConnectionService] when
 *   both MobileVLM and SeeClick models are successfully loaded.
 * - [parallelExecutionEnabled]: `false`
 * - [deviceRole]: [DEFAULT_DEVICE_ROLE] ("phone")
 * - [modelReady]: `false` — updated by [ReadinessChecker] at startup and before autonomous execution.
 * - [accessibilityReady]: `false` — updated by [ReadinessChecker].
 * - [overlayReady]: `false` — updated by [ReadinessChecker].
 */
class SharedPrefsAppSettings(context: Context) : AppSettings {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var crossDeviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_CROSS_DEVICE_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_CROSS_DEVICE_ENABLED, value).apply() }

    override var goalExecutionEnabled: Boolean
        get() = prefs.getBoolean(KEY_GOAL_EXECUTION_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_GOAL_EXECUTION_ENABLED, value).apply() }

    override var localModelEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_MODEL_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_LOCAL_MODEL_ENABLED, value).apply() }

    override var parallelExecutionEnabled: Boolean
        get() = prefs.getBoolean(KEY_PARALLEL_EXECUTION_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_PARALLEL_EXECUTION_ENABLED, value).apply() }

    override var deviceRole: String
        get() = prefs.getString(KEY_DEVICE_ROLE, DEFAULT_DEVICE_ROLE) ?: DEFAULT_DEVICE_ROLE
        set(value) { prefs.edit().putString(KEY_DEVICE_ROLE, value).apply() }

    override var modelReady: Boolean
        get() = prefs.getBoolean(KEY_MODEL_READY, false)
        set(value) { prefs.edit().putBoolean(KEY_MODEL_READY, value).apply() }

    override var accessibilityReady: Boolean
        get() = prefs.getBoolean(KEY_ACCESSIBILITY_READY, false)
        set(value) { prefs.edit().putBoolean(KEY_ACCESSIBILITY_READY, value).apply() }

    override var overlayReady: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_READY, false)
        set(value) { prefs.edit().putBoolean(KEY_OVERLAY_READY, value).apply() }

    companion object {
        /** SharedPreferences file name. */
        const val PREFS_NAME = "ufo_galaxy_settings"

        const val KEY_CROSS_DEVICE_ENABLED = "cross_device_enabled"
        const val KEY_GOAL_EXECUTION_ENABLED = "goal_execution_enabled"
        const val KEY_LOCAL_MODEL_ENABLED = "local_model_enabled"
        const val KEY_PARALLEL_EXECUTION_ENABLED = "parallel_execution_enabled"
        const val KEY_DEVICE_ROLE = "device_role"
        const val KEY_MODEL_READY = "model_ready"
        const val KEY_ACCESSIBILITY_READY = "accessibility_ready"
        const val KEY_OVERLAY_READY = "overlay_ready"

        /** Default device role sent in capability reports. */
        const val DEFAULT_DEVICE_ROLE = "phone"
    }
}

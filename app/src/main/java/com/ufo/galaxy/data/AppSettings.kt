package com.ufo.galaxy.data

import android.content.Context
import java.util.Properties

/**
 * Abstraction over persistent application settings.
 *
 * Extracted as an interface so that unit tests can inject an [InMemoryAppSettings]
 * without requiring a real Android [Context].
 *
 * All five keys required by the capability_report metadata payload are covered:
 * [goalExecutionEnabled], [localModelEnabled], [crossDeviceEnabled],
 * [parallelExecutionEnabled], and [deviceRole].
 *
 * Runtime connection URLs ([galaxyGatewayUrl], [restBaseUrl]) default to the values in
 * `assets/config.properties` so that the gateway address can be changed at runtime
 * without a recompile (P0 requirement: avoid hardcoded URLs in GalaxyWebSocketClient).
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

    /**
     * WebSocket URL of the Galaxy gateway server.
     * Default is read from `assets/config.properties` key `galaxy_gateway_url`.
     * Persisted in SharedPreferences so the user can override it at runtime.
     */
    var galaxyGatewayUrl: String

    /**
     * REST base URL of the Galaxy gateway server.
     * Default is read from `assets/config.properties` key `rest_base_url`.
     * Persisted in SharedPreferences so the user can override it at runtime.
     */
    var restBaseUrl: String

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

    // ── Network configuration (网络与诊断增强包) ──────────────────────────────

    /**
     * Gateway host (IP or hostname), e.g. "100.64.0.1".
     * When non-blank, [effectiveGatewayWsUrl] and [effectiveRestBaseUrl] are
     * built from this field, [gatewayPort], and [useTls] instead of using
     * [galaxyGatewayUrl] / [restBaseUrl] directly.
     */
    var gatewayHost: String

    /** Gateway port number, e.g. 8765. */
    var gatewayPort: Int

    /**
     * When true, use `wss://` and `https://` schemes; otherwise `ws://` and `http://`.
     * Default is false (plain, suitable for Tailscale private networks).
     */
    var useTls: Boolean

    /**
     * When true, OkHttp accepts self-signed TLS certificates.
     * Only effective when [useTls] is true. **Debug/dev environments only** —
     * never use in production over public networks.
     */
    var allowSelfSigned: Boolean

    /**
     * Stable device identifier included in handshake and diagnostics payloads.
     * Defaults to `"${Build.MANUFACTURER}_${Build.MODEL}"` when blank.
     */
    var deviceId: String

    /**
     * Optional HTTP endpoint for posting telemetry metrics.
     * E.g. `"http://100.64.0.1:9090/metrics"`. Blank = metrics are only logged locally.
     */
    var metricsEndpoint: String

    /**
     * Builds the effective WebSocket base URL from the fine-grained fields when
     * [gatewayHost] is set, otherwise falls back to [galaxyGatewayUrl].
     *
     * Priority: gatewayHost/port/tls (SharedPrefs) → galaxyGatewayUrl (SharedPrefs or
     * assets/config.properties default) → compile-time default.
     */
    fun effectiveGatewayWsUrl(): String =
        if (gatewayHost.isNotBlank()) {
            val scheme = if (useTls) "wss" else "ws"
            "$scheme://$gatewayHost:$gatewayPort"
        } else galaxyGatewayUrl

    /**
     * Builds the effective REST base URL from the fine-grained fields when
     * [gatewayHost] is set, otherwise falls back to [restBaseUrl].
     */
    fun effectiveRestBaseUrl(): String =
        if (gatewayHost.isNotBlank()) {
            val scheme = if (useTls) "https" else "http"
            "$scheme://$gatewayHost:$gatewayPort"
        } else restBaseUrl

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
    override var galaxyGatewayUrl: String = SharedPrefsAppSettings.DEFAULT_GATEWAY_URL,
    override var restBaseUrl: String = SharedPrefsAppSettings.DEFAULT_REST_BASE_URL,
    override var goalExecutionEnabled: Boolean = false,
    override var localModelEnabled: Boolean = false,
    override var parallelExecutionEnabled: Boolean = false,
    override var deviceRole: String = SharedPrefsAppSettings.DEFAULT_DEVICE_ROLE,
    override var modelReady: Boolean = false,
    override var accessibilityReady: Boolean = false,
    override var overlayReady: Boolean = false,
    // Network config fields
    override var gatewayHost: String = "",
    override var gatewayPort: Int = SharedPrefsAppSettings.DEFAULT_GATEWAY_PORT,
    override var useTls: Boolean = false,
    override var allowSelfSigned: Boolean = false,
    override var deviceId: String = "",
    override var metricsEndpoint: String = ""
) : AppSettings

/**
 * [android.content.SharedPreferences]-backed implementation of [AppSettings].
 *
 * All writes are applied asynchronously via [apply] so they never block the
 * calling thread. The preference file is stored as a private file named [PREFS_NAME].
 *
 * Default values for [galaxyGatewayUrl] and [restBaseUrl] are read once from
 * `assets/config.properties` (keys `galaxy_gateway_url` / `rest_base_url`) on first
 * construction, so the gateway address can be adjusted without a recompile.
 *
 * Default values for other settings:
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

    // Defaults loaded once from assets/config.properties; fall back to compile-time constants.
    private val defaultGatewayUrl: String
    private val defaultRestBaseUrl: String

    init {
        val props = loadConfigProperties(context)
        defaultGatewayUrl = props.getProperty("galaxy_gateway_url", DEFAULT_GATEWAY_URL)
        defaultRestBaseUrl = props.getProperty("rest_base_url", DEFAULT_REST_BASE_URL)
    }

    override var crossDeviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_CROSS_DEVICE_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_CROSS_DEVICE_ENABLED, value).apply() }

    override var galaxyGatewayUrl: String
        get() = prefs.getString(KEY_GALAXY_GATEWAY_URL, defaultGatewayUrl) ?: defaultGatewayUrl
        set(value) { prefs.edit().putString(KEY_GALAXY_GATEWAY_URL, value).apply() }

    override var restBaseUrl: String
        get() = prefs.getString(KEY_REST_BASE_URL, defaultRestBaseUrl) ?: defaultRestBaseUrl
        set(value) { prefs.edit().putString(KEY_REST_BASE_URL, value).apply() }

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

    // ── Network config fields ─────────────────────────────────────────────────

    override var gatewayHost: String
        get() = prefs.getString(KEY_GATEWAY_HOST, "") ?: ""
        set(value) { prefs.edit().putString(KEY_GATEWAY_HOST, value).apply() }

    override var gatewayPort: Int
        get() = prefs.getInt(KEY_GATEWAY_PORT, DEFAULT_GATEWAY_PORT)
        set(value) { prefs.edit().putInt(KEY_GATEWAY_PORT, value).apply() }

    override var useTls: Boolean
        get() = prefs.getBoolean(KEY_USE_TLS, false)
        set(value) { prefs.edit().putBoolean(KEY_USE_TLS, value).apply() }

    override var allowSelfSigned: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_SELF_SIGNED, false)
        set(value) { prefs.edit().putBoolean(KEY_ALLOW_SELF_SIGNED, value).apply() }

    override var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_DEVICE_ID, value).apply() }

    override var metricsEndpoint: String
        get() = prefs.getString(KEY_METRICS_ENDPOINT, "") ?: ""
        set(value) { prefs.edit().putString(KEY_METRICS_ENDPOINT, value).apply() }

    companion object {
        /** SharedPreferences file name. */
        const val PREFS_NAME = "ufo_galaxy_settings"

        const val KEY_CROSS_DEVICE_ENABLED = "cross_device_enabled"
        const val KEY_GALAXY_GATEWAY_URL = "galaxy_gateway_url"
        const val KEY_REST_BASE_URL = "rest_base_url"
        const val KEY_GOAL_EXECUTION_ENABLED = "goal_execution_enabled"
        const val KEY_LOCAL_MODEL_ENABLED = "local_model_enabled"
        const val KEY_PARALLEL_EXECUTION_ENABLED = "parallel_execution_enabled"
        const val KEY_DEVICE_ROLE = "device_role"
        const val KEY_MODEL_READY = "model_ready"
        const val KEY_ACCESSIBILITY_READY = "accessibility_ready"
        const val KEY_OVERLAY_READY = "overlay_ready"

        // Network config keys
        const val KEY_GATEWAY_HOST = "gateway_host"
        const val KEY_GATEWAY_PORT = "gateway_port"
        const val KEY_USE_TLS = "use_tls"
        const val KEY_ALLOW_SELF_SIGNED = "allow_self_signed"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_METRICS_ENDPOINT = "metrics_endpoint"

        /** Default device role sent in capability reports. */
        const val DEFAULT_DEVICE_ROLE = "phone"

        /** Default gateway port. */
        const val DEFAULT_GATEWAY_PORT = 8765

        /** Compile-time fallback gateway URL (used when assets/config.properties is absent). */
        const val DEFAULT_GATEWAY_URL = "ws://100.x.x.x:8765"

        /** Compile-time fallback REST base URL. */
        const val DEFAULT_REST_BASE_URL = "http://100.x.x.x:8765"

        /**
         * Loads `assets/config.properties` and returns the parsed [Properties].
         * Returns an empty [Properties] if the file is absent or unreadable.
         */
        fun loadConfigProperties(context: Context): Properties {
            val props = Properties()
            try {
                context.assets.open("config.properties").use { props.load(it) }
            } catch (_: Exception) {
                // File absent or unreadable — callers fall back to compile-time defaults.
            }
            return props
        }
    }
}

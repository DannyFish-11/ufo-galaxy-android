package com.ufo.galaxy.data

import android.content.Context
import com.ufo.galaxy.BuildConfig
import com.ufo.galaxy.network.resolveAllowSelfSigned
import java.util.Properties
import org.json.JSONObject

/**
 * Abstraction over persistent application settings.
 *
 * Extracted as an interface so that unit tests can inject an [InMemoryAppSettings]
 * without requiring a real Android [Context].
 *
 * All eight keys required by the [CapabilityReport.REQUIRED_METADATA_KEYS] contract are covered:
 * [goalExecutionEnabled], [localModelEnabled], [crossDeviceEnabled], [parallelExecutionEnabled],
 * [deviceRole], [modelReady], [accessibilityReady], and [overlayReady].
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

    /**
     * PR-31 — Rollout-control flag: whether this device may accept inbound
     * delegated-takeover tasks from the main runtime.
     *
     * When `false`, [com.ufo.galaxy.agent.TakeoverEligibilityAssessor] will reject every
     * inbound takeover request with
     * [com.ufo.galaxy.agent.TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_DELEGATED_EXECUTION_DISABLED].
     * Cross-device connectivity (WS connection, session registration) remains active so
     * the operator can disable delegation without triggering a full reconnect cycle.
     *
     * Default: `true` — preserves backward compatibility; delegation was previously
     * controlled exclusively through [crossDeviceEnabled] and [goalExecutionEnabled].
     */
    var delegatedExecutionAllowed: Boolean

    /**
     * PR-31 — Rollout-control flag: whether local fallback is permitted when a
     * delegated-takeover execution fails.
     *
     * When `false`, a [com.ufo.galaxy.runtime.TakeoverFallbackEvent] is still emitted
     * via [com.ufo.galaxy.runtime.RuntimeController.notifyTakeoverFailed] but the
     * surface layer must not automatically re-execute the task locally.  Use this to
     * prevent unintended local executions in environments where strict cross-device
     * execution semantics are required.
     *
     * Default: `true` — preserves the prior fallback-always-allowed behaviour.
     */
    var fallbackToLocalAllowed: Boolean

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
     * Returns the effective self-signed-TLS policy for the current build variant.
     *
     * Release / production builds always return `false` even if a caller or persisted
     * preference requested otherwise.
     */
    fun effectiveAllowSelfSigned(isDebugBuild: Boolean = BuildConfig.DEBUG): Boolean =
        resolveAllowSelfSigned(allowSelfSigned, isDebugBuild)

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
     * Bearer token used to authenticate the WebSocket connection to the gateway.
     * Corresponds to `GALAXY_API_TOKEN` / `gatewayToken` in server configuration.
     * When non-blank, the value is sent as `Authorization: Bearer <token>` during WS handshake.
     * When blank, no Authorization header is added.
     */
    var gatewayToken: String

    /**
     * PR-7 — Prior durable session ID for process-recreation re-attach.
     *
     * Persisted when a durable session era ends (clean stop or era invalidation).
     * On process recreation, Android reads this value to construct a
     * [com.ufo.galaxy.runtime.ProcessRecreatedReattachHint] and include it in the
     * `DeviceConnected` event, allowing V2 to optionally correlate the re-attaching
     * device with its prior session.
     *
     * A blank string indicates no prior session is known (true first launch or after an
     * explicit clear). V2 must treat a blank value as equivalent to a brand-new device
     * attachment.
     *
     * **Authority boundary**: Android only presents this hint; V2 decides whether to
     * restore participant state. Android must never self-authorize session continuation
     * based on this field.
     */
    var lastDurableSessionId: String

    // ── Local-chain execution settings (planner / grounding) ─────────────────

    /**
     * Maximum tokens the MobileVLM planner may generate per call.
     * Default is read from `assets/config.properties` key `planner_max_tokens`.
     */
    var plannerMaxTokens: Int

    /**
     * Sampling temperature for the MobileVLM planner (lower = more deterministic).
     * Default is read from `assets/config.properties` key `planner_temperature`.
     */
    var plannerTemperature: Double

    /**
     * HTTP connect+read timeout for MobileVLM inference calls (milliseconds).
     * Default is read from `assets/config.properties` key `planner_timeout_ms`.
     */
    var plannerTimeoutMs: Int

    /**
     * HTTP connect+read timeout for SeeClick grounding calls (milliseconds).
     * Default is read from `assets/config.properties` key `grounding_timeout_ms`.
     */
    var groundingTimeoutMs: Int

    /**
     * Longest edge (pixels) for screenshot downscaling before grounding.
     * 0 = disabled (pass full-resolution image to grounding engine).
     * Default is read from `assets/config.properties` key `scaled_max_edge`.
     */
    var scaledMaxEdge: Int

    /**
     * Builds the effective WebSocket base URL from the fine-grained fields when
     * [gatewayHost] is set, otherwise falls back to [galaxyGatewayUrl].
     *
     * Priority: gatewayHost/port/tls (SharedPrefs) → galaxyGatewayUrl (SharedPrefs or
     * assets/config.properties default) → compile-time default.
     *
     * The canonical gateway ingress `/ws/device/{device_id}` path is appended automatically unless the
     * URL already carries a path component (i.e. the part after `://` contains a `/`),
     * which lets users who have configured a full URL keep their path unchanged.
     */
    fun effectiveGatewayWsUrl(): String {
        val base = if (gatewayHost.isNotBlank()) {
            val scheme = if (useTls) "wss" else "ws"
            "$scheme://$gatewayHost:$gatewayPort"
        } else galaxyGatewayUrl
        // Append canonical /ws/device/{device_id} only when no explicit path exists.
        val afterScheme = base.substringAfter("://")
        val defaultPath = CANONICAL_WS_DEVICE_PATH_TEMPLATE.replace("{device_id}", resolveDeviceIdForPath())
        return if (afterScheme.contains('/')) base else "$base$defaultPath"
    }

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
     * Applies a gateway config [JSONObject] returned by `/api/v1/config` to this settings
     * instance (M3 – config discovery).
     *
     * Recognised keys (all optional; unrecognised keys are silently ignored):
     * - `ws_url`        / `gateway_ws_url`   – full WebSocket URL (updates [galaxyGatewayUrl]).
     * - `ws_base` + `ws_paths`               – structured WS contract fields.
     * - `gateway_host`                        – gateway hostname or IP (updates [gatewayHost]).
     * - `gateway_port`                        – gateway port number (updates [gatewayPort]).
     * - `use_tls`                             – TLS toggle (updates [useTls]).
     * - `gateway_token` / `token`             – WS auth token (updates [gatewayToken]).
     *
     * Failure to parse any individual key is silently ignored; the remaining keys are still
     * applied. The caller is responsible for triggering a reconnect if the WS URL changed.
     */
    fun applyGatewayConfig(config: JSONObject) {
        val wsUrl = config.optString("ws_url").takeIf { it.isNotBlank() }
            ?: config.optString("gateway_ws_url").takeIf { it.isNotBlank() }
            ?: resolveWsUrlFromStructuredConfig(config)
        if (wsUrl != null) galaxyGatewayUrl = wsUrl

        val host = config.optString("gateway_host").takeIf { it.isNotBlank() }
        if (host != null) gatewayHost = host

        val port = config.optInt("gateway_port", -1).takeIf { it > 0 }
        if (port != null) gatewayPort = port

        if (config.has("use_tls")) useTls = config.optBoolean("use_tls", useTls)

        val token = config.optString("gateway_token").takeIf { it.isNotBlank() }
            ?: config.optString("token").takeIf { it.isNotBlank() }
        if (token != null) gatewayToken = token
    }

    /** Resolves a structured ws_base/ws_paths contract into a full WS URL when available. */
    fun resolveWsUrlFromStructuredConfig(config: JSONObject): String? {
        val wsBase = config.optString("ws_base").takeIf { it.isNotBlank() } ?: return null
        val wsPaths = config.optJSONObject("ws_paths")
        val canonicalPathTemplate = wsPaths?.optString("device").takeIf { !it.isNullOrBlank() }
            ?: wsPaths?.optString("device_path").takeIf { !it.isNullOrBlank() }
            ?: wsPaths?.optString("canonical").takeIf { !it.isNullOrBlank() }
            ?: wsPaths?.optString("android").takeIf { !it.isNullOrBlank() } // compat fallback
            ?: CANONICAL_WS_DEVICE_PATH_TEMPLATE
        val resolvedPath = canonicalPathTemplate
            .replace("{device_id}", resolveDeviceIdForPath())
            .replace("{id}", resolveDeviceIdForPath())
        return joinWsBaseAndPath(wsBase, resolvedPath)
    }

    fun resolveDeviceIdForPath(): String =
        sanitizeDeviceIdForPath(
            deviceId.takeIf { it.isNotBlank() }
                ?: "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}"
        )

    fun sanitizeDeviceIdForPath(rawDeviceId: String): String =
        rawDeviceId
            .trim()
            .replace("\\s+".toRegex(), "_")
            .replace("[^A-Za-z0-9._-]".toRegex(), "_")

    fun joinWsBaseAndPath(base: String, path: String): String {
        val normalisedPath = if (path.startsWith("/")) path else "/$path"
        return "${base.trimEnd('/')}${normalisedPath}"
    }

    /**
     * Returns all eight canonical runtime-identity fields as a [Map] suitable for
     * inclusion in a [CapabilityReport.metadata] payload sent to the gateway.
     *
     * The returned map satisfies [CapabilityReport.REQUIRED_METADATA_KEYS]: every key in
     * that set is present with a Boolean or String value reflecting the current device
     * state. The server consumes this map directly as canonical runtime identity without
     * any Android-specific translation.
     *
     * Keys and value semantics:
     * - `goal_execution_enabled`     – [goalExecutionEnabled]
     * - `local_model_enabled`        – [localModelEnabled]
     * - `cross_device_enabled`       – [crossDeviceEnabled]
     * - `parallel_execution_enabled` – [parallelExecutionEnabled]
     * - `device_role`                – [deviceRole]
     * - `model_ready`                – [modelReady]
     * - `accessibility_ready`        – [accessibilityReady]
     * - `overlay_ready`              – [overlayReady]
     *
     * Gateways that do not understand extra keys (if any are ever added) will ignore
     * them, preserving backward compatibility during future migrations.
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

    companion object {
        const val CANONICAL_WS_DEVICE_PATH_TEMPLATE = "/ws/device/{device_id}"
    }
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
    override var delegatedExecutionAllowed: Boolean = true,
    override var fallbackToLocalAllowed: Boolean = true,
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
    override var metricsEndpoint: String = "",
    override var gatewayToken: String = "",
    override var lastDurableSessionId: String = "",
    // Local-chain execution settings
    override var plannerMaxTokens: Int = SharedPrefsAppSettings.DEFAULT_PLANNER_MAX_TOKENS,
    override var plannerTemperature: Double = SharedPrefsAppSettings.DEFAULT_PLANNER_TEMPERATURE,
    override var plannerTimeoutMs: Int = SharedPrefsAppSettings.DEFAULT_PLANNER_TIMEOUT_MS,
    override var groundingTimeoutMs: Int = SharedPrefsAppSettings.DEFAULT_GROUNDING_TIMEOUT_MS,
    override var scaledMaxEdge: Int = SharedPrefsAppSettings.DEFAULT_SCALED_MAX_EDGE
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
 * Default values for local-chain execution settings ([plannerMaxTokens], [plannerTemperature],
 * [plannerTimeoutMs], [groundingTimeoutMs], [scaledMaxEdge]) are likewise read from
 * `assets/config.properties` on first construction, so tuning values can be adjusted
 * without a recompile.  Build-time [BuildConfig] constants serve as the last-resort
 * fallback only — they are never the authoritative runtime source.
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
    private val defaultPlannerMaxTokens: Int
    private val defaultPlannerTemperature: Double
    private val defaultPlannerTimeoutMs: Int
    private val defaultGroundingTimeoutMs: Int
    private val defaultScaledMaxEdge: Int

    init {
        val props = loadConfigProperties(context)
        defaultGatewayUrl = props.getProperty("galaxy_gateway_url", DEFAULT_GATEWAY_URL)
        defaultRestBaseUrl = props.getProperty("rest_base_url", DEFAULT_REST_BASE_URL)
        defaultPlannerMaxTokens =
            props.getProperty("planner_max_tokens")?.toIntOrNull() ?: DEFAULT_PLANNER_MAX_TOKENS
        defaultPlannerTemperature =
            props.getProperty("planner_temperature")?.toDoubleOrNull() ?: DEFAULT_PLANNER_TEMPERATURE
        defaultPlannerTimeoutMs =
            props.getProperty("planner_timeout_ms")?.toIntOrNull() ?: DEFAULT_PLANNER_TIMEOUT_MS
        defaultGroundingTimeoutMs =
            props.getProperty("grounding_timeout_ms")?.toIntOrNull() ?: DEFAULT_GROUNDING_TIMEOUT_MS
        defaultScaledMaxEdge =
            props.getProperty("scaled_max_edge")?.toIntOrNull() ?: DEFAULT_SCALED_MAX_EDGE
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

    // PR-31: Rollout-control flags
    override var delegatedExecutionAllowed: Boolean
        get() = prefs.getBoolean(KEY_DELEGATED_EXECUTION_ALLOWED, true)
        set(value) { prefs.edit().putBoolean(KEY_DELEGATED_EXECUTION_ALLOWED, value).apply() }

    override var fallbackToLocalAllowed: Boolean
        get() = prefs.getBoolean(KEY_FALLBACK_TO_LOCAL_ALLOWED, true)
        set(value) { prefs.edit().putBoolean(KEY_FALLBACK_TO_LOCAL_ALLOWED, value).apply() }

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
        get() = resolveAllowSelfSigned(
            requested = prefs.getBoolean(KEY_ALLOW_SELF_SIGNED, false),
            isDebugBuild = BuildConfig.DEBUG
        )
        set(value) {
            prefs.edit()
                .putBoolean(
                    KEY_ALLOW_SELF_SIGNED,
                    value
                )
                .apply()
        }

    override var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_DEVICE_ID, value).apply() }

    override var metricsEndpoint: String
        get() = prefs.getString(KEY_METRICS_ENDPOINT, "") ?: ""
        set(value) { prefs.edit().putString(KEY_METRICS_ENDPOINT, value).apply() }

    override var gatewayToken: String
        get() = prefs.getString(KEY_GATEWAY_TOKEN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_GATEWAY_TOKEN, value).apply() }

    // ── PR-7: Prior durable session ID (process-recreation re-attach hint) ────

    override var lastDurableSessionId: String
        get() = prefs.getString(KEY_LAST_DURABLE_SESSION_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LAST_DURABLE_SESSION_ID, value).apply() }

    // ── Local-chain execution settings ───────────────────────────────────────

    override var plannerMaxTokens: Int
        get() = prefs.getInt(KEY_PLANNER_MAX_TOKENS, defaultPlannerMaxTokens)
        set(value) { prefs.edit().putInt(KEY_PLANNER_MAX_TOKENS, value).apply() }

    override var plannerTemperature: Double
        get() = java.lang.Double.longBitsToDouble(
            prefs.getLong(KEY_PLANNER_TEMPERATURE, java.lang.Double.doubleToRawLongBits(defaultPlannerTemperature))
        )
        set(value) {
            prefs.edit().putLong(KEY_PLANNER_TEMPERATURE, java.lang.Double.doubleToRawLongBits(value)).apply()
        }

    override var plannerTimeoutMs: Int
        get() = prefs.getInt(KEY_PLANNER_TIMEOUT_MS, defaultPlannerTimeoutMs)
        set(value) { prefs.edit().putInt(KEY_PLANNER_TIMEOUT_MS, value).apply() }

    override var groundingTimeoutMs: Int
        get() = prefs.getInt(KEY_GROUNDING_TIMEOUT_MS, defaultGroundingTimeoutMs)
        set(value) { prefs.edit().putInt(KEY_GROUNDING_TIMEOUT_MS, value).apply() }

    override var scaledMaxEdge: Int
        get() = prefs.getInt(KEY_SCALED_MAX_EDGE, defaultScaledMaxEdge)
        set(value) { prefs.edit().putInt(KEY_SCALED_MAX_EDGE, value).apply() }

    companion object {
        /** SharedPreferences file name. */
        const val PREFS_NAME = "ufo_galaxy_settings"

        const val KEY_CROSS_DEVICE_ENABLED = "cross_device_enabled"
        const val KEY_GALAXY_GATEWAY_URL = "galaxy_gateway_url"
        const val KEY_REST_BASE_URL = "rest_base_url"
        const val KEY_GOAL_EXECUTION_ENABLED = "goal_execution_enabled"
        // PR-31: Rollout-control flag keys
        const val KEY_DELEGATED_EXECUTION_ALLOWED = "delegated_execution_allowed"
        const val KEY_FALLBACK_TO_LOCAL_ALLOWED = "fallback_to_local_allowed"
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
        const val KEY_GATEWAY_TOKEN = "gateway_token"

        // PR-7: Process-recreation re-attach hint key
        const val KEY_LAST_DURABLE_SESSION_ID = "last_durable_session_id"

        // Local-chain execution keys
        const val KEY_PLANNER_MAX_TOKENS = "planner_max_tokens"
        const val KEY_PLANNER_TEMPERATURE = "planner_temperature"
        const val KEY_PLANNER_TIMEOUT_MS = "planner_timeout_ms"
        const val KEY_GROUNDING_TIMEOUT_MS = "grounding_timeout_ms"
        const val KEY_SCALED_MAX_EDGE = "scaled_max_edge"

        /** Default device role sent in capability reports. */
        const val DEFAULT_DEVICE_ROLE = "phone"

        /** Default gateway port. */
        const val DEFAULT_GATEWAY_PORT = 8765

        /** Compile-time fallback gateway URL (used when assets/config.properties is absent). */
        const val DEFAULT_GATEWAY_URL = "ws://100.x.x.x:8765"

        /** Compile-time fallback REST base URL. */
        const val DEFAULT_REST_BASE_URL = "http://100.x.x.x:8765"

        // ── Local-chain compile-time fallbacks ────────────────────────────────
        // These are the last-resort defaults used only when assets/config.properties
        // is absent and no SharedPreferences override exists. They mirror the
        // BuildConfig constants in app/build.gradle and must stay in sync.

        /** Compile-time fallback: maximum planner tokens. */
        const val DEFAULT_PLANNER_MAX_TOKENS = 512

        /** Compile-time fallback: planner sampling temperature. */
        const val DEFAULT_PLANNER_TEMPERATURE = 0.1

        /** Compile-time fallback: planner HTTP timeout (ms). */
        const val DEFAULT_PLANNER_TIMEOUT_MS = 30_000

        /** Compile-time fallback: grounding HTTP timeout (ms). */
        const val DEFAULT_GROUNDING_TIMEOUT_MS = 15_000

        /** Compile-time fallback: screenshot downscaling longest edge (px). */
        const val DEFAULT_SCALED_MAX_EDGE = 720

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

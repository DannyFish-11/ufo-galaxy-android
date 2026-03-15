package com.ufo.galaxy.config

/**
 * Centralised server URL and endpoint configuration.
 *
 * WebSocket paths are listed in priority order.  If the preferred path fails to
 * connect, the client should fall back to the next candidate.  The placeholder
 * `{id}` in a path is replaced with the device identifier at runtime.
 *
 * Usage example:
 * ```kotlin
 * val url = ServerConfig.buildWsUrl(baseUrl, deviceId, pathIndex = 0)
 * ```
 */
object ServerConfig {

    /** Default WebSocket base URL (no trailing slash). Override via config.properties. */
    const val DEFAULT_BASE_URL = "ws://100.123.215.126:8050"

    /**
     * Default STUN server URL used for ICE candidate gathering in the WebRTC
     * [PeerConnection].  Override in the gateway discovery response when TURN
     * servers are required.
     */
    const val DEFAULT_STUN_URL = "stun:stun.l.google.com:19302"

    // ──────────────────────────────────────────────────────────────────────────
    // Runtime overrides (set by RemoteConfigFetcher at startup)
    //
    // All fields are nullable; null means "use the compile-time default above".
    // Use the corresponding effective* properties for connection logic so that
    // remote-config overrides are respected automatically.
    // ──────────────────────────────────────────────────────────────────────────

    /** Runtime WS base URL applied by [com.ufo.galaxy.config.RemoteConfigFetcher]. */
    @Volatile var runtimeWsBase: String? = null

    /** Runtime REST base URL applied by [com.ufo.galaxy.config.RemoteConfigFetcher]. */
    @Volatile var runtimeRestBase: String? = null

    /** Runtime WebSocket path list applied by [com.ufo.galaxy.config.RemoteConfigFetcher]. */
    @Volatile var runtimeWsPaths: List<String>? = null

    /** Runtime WebRTC signaling path applied by [com.ufo.galaxy.config.RemoteConfigFetcher]. */
    @Volatile var runtimeWebrtcWsPath: String? = null

    /**
     * Runtime ICE server list (STUN + TURN) applied by
     * [com.ufo.galaxy.config.RemoteConfigFetcher].
     */
    @Volatile var runtimeIceServers: List<String>? = null

    /** Transport priority list from remote config, e.g. ["tailscale","intranet","internet"]. */
    @Volatile var runtimeTransportPriority: List<String>? = null

    /** Feature flags from remote config. */
    @Volatile var runtimeFeatureFlags: Map<String, Boolean>? = null

    /**
     * Label of the transport channel chosen after probing
     * (e.g. "tailscale", "intranet", "internet", or "default").
     * Exposed for logging and diagnostics.
     */
    @Volatile var chosenChannelLabel: String? = null

    // ──────────────────────────────────────────────────────────────────────────
    // Effective values (runtime override → local config → compile-time default)
    // ──────────────────────────────────────────────────────────────────────────

    /** Effective WebSocket base URL to use for all WS connections. */
    val effectiveWsBase: String
        get() = runtimeWsBase ?: DEFAULT_BASE_URL

    /** Effective REST base URL to use for all REST calls. */
    val effectiveRestBase: String
        get() = runtimeRestBase ?: wsToHttpBase(DEFAULT_BASE_URL)

    /** Effective WebSocket path list in priority order. */
    val effectiveWsPaths: List<String>
        get() = runtimeWsPaths ?: WS_PATHS

    /** Effective WebRTC signaling WS path. */
    val effectiveWebrtcWsPath: String
        get() = runtimeWebrtcWsPath ?: WEBRTC_WS_PATH

    /**
     * Effective ICE server list for [org.webrtc.PeerConnection].
     * Includes both STUN and TURN servers when provided by remote config.
     */
    val effectiveIceServers: List<String>
        get() = runtimeIceServers ?: listOf(DEFAULT_STUN_URL)

    /** Effective feature flags (empty map when none provided). */
    val effectiveFeatureFlags: Map<String, Boolean>
        get() = runtimeFeatureFlags ?: emptyMap()

    /** Clear all runtime overrides, reverting to compile-time defaults. */
    fun clearRuntimeOverrides() {
        runtimeWsBase = null
        runtimeRestBase = null
        runtimeWsPaths = null
        runtimeWebrtcWsPath = null
        runtimeIceServers = null
        runtimeTransportPriority = null
        runtimeFeatureFlags = null
        chosenChannelLabel = null
    }

    /**
     * Primary WebSocket path for the AndroidBridge (server realization-v2).
     * `{id}` is substituted with the device identifier at runtime.
     *
     * This is the canonical route exposed by `galaxy_gateway/android_bridge.py`
     * in ufo-galaxy-realization-v2.  All new connections should prefer this path.
     */
    const val ANDROID_BRIDGE_WS_PATH = "/ws/android/{id}"

    /**
     * WebSocket paths in descending priority order.
     *
     * Index 0 ([ANDROID_BRIDGE_WS_PATH]) is the canonical AndroidBridge route
     * and is always tried first.  The remaining entries are fallback candidates
     * used when the preferred path is unavailable (e.g. older server deployments).
     * `{id}` is substituted with the actual device identifier at runtime.
     */
    val WS_PATHS: List<String> = listOf(
        ANDROID_BRIDGE_WS_PATH, // preferred: AndroidBridge route (realization-v2)
        "/ws/device/{id}",       // device-specific fallback
        "/ws/android",            // generic Android fallback
        "/ws/ufo3/{id}"           // legacy UFO³ path
    )

    /** Current v1 REST devices prefix (active server routes). */
    const val REST_V1_PREFIX = "/api/v1/devices"

    /** Legacy REST devices prefix – used as a fallback when v1 returns 404. */
    const val REST_LEGACY_PREFIX = "/api/devices"

    /**
     * WebSocket path for the WebRTC signaling proxy.
     * `{id}` is replaced with the device identifier at runtime.
     */
    const val WEBRTC_WS_PATH = "/ws/webrtc/{id}"

    /**
     * REST path to discover the WebRTC signaling endpoint from the gateway.
     * The gateway (Server PR #35) exposes this endpoint to return the active
     * signaling WS address and any configuration needed by the Android client.
     */
    const val WEBRTC_ENDPOINT_REST_PATH = "/api/v1/webrtc/endpoint"

    // ──────────────────────────────────────────────────────────────────────────
    // WebSocket URL helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Build a WebSocket URL for [baseUrl] and [deviceId].
     *
     * @param baseUrl    WebSocket base (e.g. `"ws://host:port"`).
     * @param deviceId   Device identifier used to fill `{id}` placeholders.
     * @param pathIndex  Index into [WS_PATHS]; defaults to 0 (highest priority).
     * @throws IllegalArgumentException when [pathIndex] is out of range.
     */
    fun buildWsUrl(baseUrl: String, deviceId: String, pathIndex: Int = 0): String {
        require(pathIndex in WS_PATHS.indices) {
            "pathIndex $pathIndex is out of range (${WS_PATHS.size} paths available)"
        }
        val path = WS_PATHS[pathIndex].replace("{id}", deviceId)
        return "$baseUrl$path"
    }

    /**
     * Build a WebSocket URL using the effective runtime path list and base URL.
     *
     * Respects any [runtimeWsPaths] override set by [com.ufo.galaxy.config.RemoteConfigFetcher].
     * Falls back to [WS_PATHS] if no runtime override is set.
     *
     * @param baseUrl    WebSocket base URL; falls back to [effectiveWsBase] when blank.
     * @param deviceId   Device identifier used to fill `{id}` placeholders.
     * @param pathIndex  Index into [effectiveWsPaths]; defaults to 0 (highest priority).
     * @throws IllegalArgumentException when [pathIndex] is out of range.
     */
    fun buildEffectiveWsUrl(baseUrl: String, deviceId: String, pathIndex: Int = 0): String {
        val paths = effectiveWsPaths
        require(pathIndex in paths.indices) {
            "pathIndex $pathIndex is out of range (${paths.size} paths available)"
        }
        val path = paths[pathIndex].replace("{id}", deviceId)
        return "$baseUrl$path"
    }

    /**
     * Return all candidate WebSocket URLs for [baseUrl] and [deviceId] in
     * priority order (index 0 = highest priority).
     */
    fun allWsUrls(baseUrl: String, deviceId: String): List<String> =
        WS_PATHS.indices.map { buildWsUrl(baseUrl, deviceId, it) }

    // ──────────────────────────────────────────────────────────────────────────
    // REST URL helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Build a REST endpoint URL.
     *
     * @param httpBaseUrl  HTTP base (e.g. `"http://host:port"`).
     * @param path         Path segment appended after the prefix (e.g. `"/register"`).
     * @param v1           When `true` uses [REST_V1_PREFIX]; otherwise [REST_LEGACY_PREFIX].
     */
    fun buildRestUrl(httpBaseUrl: String, path: String, v1: Boolean = true): String {
        val prefix = if (v1) REST_V1_PREFIX else REST_LEGACY_PREFIX
        return "$httpBaseUrl$prefix$path"
    }

    /**
     * Convert a WebSocket base URL to an HTTP base URL (strips `ws://` / `wss://`).
     */
    fun wsToHttpBase(wsBase: String): String =
        wsBase.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")

    // ──────────────────────────────────────────────────────────────────────────
    // WebRTC URL helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Build the WebRTC signaling WebSocket URL for [baseUrl] and [deviceId].
     *
     * Example: `buildWebRtcWsUrl("ws://host:8050", "abc123")`
     * → `"ws://host:8050/ws/webrtc/abc123"`
     */
    fun buildWebRtcWsUrl(baseUrl: String, deviceId: String): String =
        "$baseUrl${WEBRTC_WS_PATH.replace("{id}", deviceId)}"

    /**
     * Build the REST URL used to discover the active WebRTC signaling endpoint
     * from the gateway.
     *
     * Example: `buildWebRtcEndpointUrl("http://host:8050")`
     * → `"http://host:8050/api/v1/webrtc/endpoint"`
     */
    fun buildWebRtcEndpointUrl(httpBase: String): String =
        "$httpBase$WEBRTC_ENDPOINT_REST_PATH"
}

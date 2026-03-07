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
     * WebSocket paths in descending priority order.
     * `{id}` is substituted with the actual device identifier at runtime.
     */
    val WS_PATHS: List<String> = listOf(
        "/ws/device/{id}",   // preferred
        "/ws/android",        // generic Android path
        "/ws/ufo3/{id}"       // legacy UFO³ path
    )

    /** Current v1 REST devices prefix (active server routes). */
    const val REST_V1_PREFIX = "/api/v1/devices"

    /** Legacy REST devices prefix – used as a fallback when v1 returns 404. */
    const val REST_LEGACY_PREFIX = "/api/devices"

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
}

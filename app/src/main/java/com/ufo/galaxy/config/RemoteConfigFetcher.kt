package com.ufo.galaxy.config

import android.util.Log
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches remote gateway configuration from `GET /api/v1/config` at startup.
 *
 * On success the in-memory [ServerConfig] runtime overrides are updated with
 * the values returned by the server:
 * - `ws_base` / `rest_base`
 * - `ws_paths` (WebSocket path priority list)
 * - `webrtc_gateway_ws_path`
 * - `stun` / `turn` (ICE servers for WebRTC)
 * - `transport_priority` (channel order, e.g. tailscale > intranet > internet)
 * - `transport_candidates` (map of label → WS host)
 * - `feature_flags`
 *
 * Multi-channel probing is performed when `transport_candidates` is present:
 * all candidate hosts are probed in parallel (HTTP HEAD) and the reachable
 * host with the highest priority is chosen.  The chosen channel label is
 * recorded in [ServerConfig.chosenChannelLabel] and the corresponding host
 * is applied to [AppSettings].
 *
 * On any failure the existing local config is left unchanged so the app falls
 * back to `assets/config.properties` + [ServerConfig] compile-time defaults.
 */
object RemoteConfigFetcher {

    private const val TAG = "RemoteConfigFetcher"

    /** Path of the remote config endpoint on the gateway. */
    internal const val CONFIG_PATH = "/api/v1/config"

    /** Connect + read timeout for the config fetch request (ms). */
    private const val FETCH_TIMEOUT_MS = 5_000L

    /** Per-host connect timeout for channel probing (ms). */
    internal const val PROBE_TIMEOUT_MS = 3_000L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fetch remote config and apply it to [ServerConfig]; then probe transport
     * channels and update [appSettings] with the best host.
     *
     * Must be called at startup **before** [com.ufo.galaxy.network.GalaxyWebSocketClient]
     * or [com.ufo.galaxy.webrtc.WebRTCManager] are initialised.
     *
     * When [crossDeviceEnabled] is `false` no WS connection will be opened
     * regardless (the caller gates on [AppSettings.crossDeviceEnabled]).
     *
     * @return `true` if remote config was successfully fetched and applied;
     *         `false` if the local fallback is in use.
     */
    suspend fun fetchAndApply(appSettings: AppSettings): Boolean =
        withContext(Dispatchers.IO) {
            val localRestBase = appSettings.restBaseUrl
            val configUrl = "$localRestBase$CONFIG_PATH"
            Log.i(TAG, "[CONFIG] Fetching remote config → $configUrl")

            val config = try {
                fetchConfig(configUrl)
            } catch (e: Exception) {
                Log.e(TAG, "[CONFIG] Fetch error: ${e.message}; using local fallback", e)
                null
            }

            if (config == null) {
                Log.w(TAG, "[CONFIG] Remote config unavailable; local fallback active")
                return@withContext false
            }

            applyToServerConfig(config)

            // Channel probing (optional – only when candidates are provided)
            val candidates = config.transportCandidates
            val priority = config.transportPriority ?: listOf("tailscale", "intranet", "internet")

            if (!candidates.isNullOrEmpty()) {
                val best = probeChannels(candidates, priority)
                if (best != null) {
                    Log.i(
                        TAG,
                        "[CHANNEL] Selected channel: label=${best.label} " +
                            "host=${best.host} rtt=${best.rttMs}ms"
                    )
                    ServerConfig.chosenChannelLabel = best.label
                    ServerConfig.runtimeWsBase = best.host
                    ServerConfig.runtimeRestBase = ServerConfig.wsToHttpBase(best.host)
                    appSettings.galaxyGatewayUrl = best.host
                    appSettings.restBaseUrl = ServerConfig.wsToHttpBase(best.host)
                } else {
                    Log.w(TAG, "[CHANNEL] No candidate reachable; keeping ws_base from config")
                    if (ServerConfig.chosenChannelLabel == null) {
                        ServerConfig.chosenChannelLabel = "default"
                    }
                }
            } else {
                Log.i(
                    TAG,
                    "[CHANNEL] No transport_candidates; using ws_base=${ServerConfig.effectiveWsBase}"
                )
                ServerConfig.chosenChannelLabel = "default"
            }

            Log.i(
                TAG,
                "[CONFIG] Applied — wsBase=${ServerConfig.effectiveWsBase} " +
                    "restBase=${ServerConfig.effectiveRestBase} " +
                    "paths=${ServerConfig.effectiveWsPaths.size} " +
                    "ice=${ServerConfig.effectiveIceServers.size} " +
                    "channel=${ServerConfig.chosenChannelLabel}"
            )
            true
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Apply parsed [RemoteGatewayConfig] values to [ServerConfig] runtime overrides. */
    private fun applyToServerConfig(config: RemoteGatewayConfig) {
        config.wsBase?.let { ServerConfig.runtimeWsBase = it }
        config.restBase?.let { ServerConfig.runtimeRestBase = it }
        config.wsPaths?.takeIf { it.isNotEmpty() }?.let { ServerConfig.runtimeWsPaths = it }
        config.webrtcGatewayWsPath?.let { ServerConfig.runtimeWebrtcWsPath = it }
        config.transportPriority?.let { ServerConfig.runtimeTransportPriority = it }
        config.featureFlags?.let { ServerConfig.runtimeFeatureFlags = it }

        // Build merged ICE list (stun + turn)
        val ice = buildList {
            config.stunServers?.forEach { add(it) }
            config.turnServers?.forEach { add(it) }
        }.takeIf { it.isNotEmpty() }
        ice?.let { ServerConfig.runtimeIceServers = it }
    }

    /**
     * Probe all [candidates] in parallel.
     *
     * Returns the first reachable host in [priority] order.  If no candidate
     * matches the priority list, returns the reachable candidate with the
     * lowest RTT.  Returns `null` when no candidate is reachable.
     */
    internal suspend fun probeChannels(
        candidates: Map<String, String>,
        priority: List<String>
    ): ChannelProbeResult? = coroutineScope {
        Log.i(
            TAG,
            "[CHANNEL] Probing candidates: priority=$priority " +
                "hosts=${candidates.keys.joinToString()}"
        )

        // Launch all probes in parallel
        val probeJobs: Map<String, Deferred<Long?>> = candidates.mapValues { (label, host) ->
            async(Dispatchers.IO) { probeHost(label, host) }
        }

        // Collect results
        val results: Map<String, Long?> = probeJobs.mapValues { (_, d) -> d.await() }
        Log.i(TAG, "[CHANNEL] Probe results: ${results.entries.joinToString { "${it.key}=${it.value}ms" }}")

        // Pick by priority
        for (label in priority) {
            val rtt = results[label] ?: continue
            val host = candidates[label] ?: continue
            return@coroutineScope ChannelProbeResult(host = host, label = label, rttMs = rtt)
        }

        // Fallback: any reachable candidate, lowest RTT wins
        results.entries
            .filter { it.value != null }
            .minByOrNull { it.value!! }
            ?.let { (label, rtt) ->
                ChannelProbeResult(host = candidates[label]!!, label = label, rttMs = rtt!!)
            }
    }

    /**
     * Probe [wsHost] with an HTTP HEAD request; returns RTT in ms, or `null`
     * if the host is unreachable within [PROBE_TIMEOUT_MS].
     */
    internal fun probeHost(label: String, wsHost: String): Long? {
        val httpBase = ServerConfig.wsToHttpBase(wsHost)
        val probeUrl = "$httpBase$CONFIG_PATH"
        return try {
            val probeClient = OkHttpClient.Builder()
                .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder().url(probeUrl).head().build()
            val t0 = System.currentTimeMillis()
            val response = probeClient.newCall(request).execute()
            val rtt = System.currentTimeMillis() - t0
            response.close()
            Log.d(TAG, "[CHANNEL] Probe $label ($probeUrl): HTTP ${response.code} ${rtt}ms")
            rtt
        } catch (e: Exception) {
            Log.d(TAG, "[CHANNEL] Probe $label ($probeUrl): unreachable — ${e.message}")
            null
        }
    }

    /**
     * Fetch and parse the remote config JSON.
     *
     * @return parsed [RemoteGatewayConfig], or `null` on network/HTTP/parse failure.
     */
    internal fun fetchConfig(url: String): RemoteGatewayConfig? {
        val request = Request.Builder().url(url).get().build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            Log.w(TAG, "[CONFIG] Network error reaching $url: ${e.message}")
            return null
        }
        if (!response.isSuccessful) {
            Log.w(TAG, "[CONFIG] HTTP ${response.code} from $url")
            response.close()
            return null
        }
        val body = response.body?.string().also { response.close() }
        if (body.isNullOrBlank()) {
            Log.w(TAG, "[CONFIG] Empty response body from $url")
            return null
        }
        return try {
            parseConfig(JSONObject(body))
        } catch (e: Exception) {
            Log.w(TAG, "[CONFIG] JSON parse error: ${e.message}")
            null
        }
    }

    /** Parse a [JSONObject] config payload into a [RemoteGatewayConfig]. */
    internal fun parseConfig(json: JSONObject): RemoteGatewayConfig {
        val wsBase = json.optString("ws_base").takeIf { it.isNotBlank() }
        val restBase = json.optString("rest_base").takeIf { it.isNotBlank() }
        val webrtcWsPath = json.optString("webrtc_gateway_ws_path").takeIf { it.isNotBlank() }

        val wsPaths = json.optJSONArray("ws_paths")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        val stunServers = json.optJSONArray("stun")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        val turnServers = json.optJSONArray("turn")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        val transportPriority = json.optJSONArray("transport_priority")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        val transportCandidates = json.optJSONObject("transport_candidates")?.let { obj ->
            buildMap { obj.keys().forEach { key -> put(key, obj.getString(key)) } }
        }
        val featureFlags = json.optJSONObject("feature_flags")?.let { obj ->
            buildMap { obj.keys().forEach { key -> put(key, obj.optBoolean(key)) } }
        }

        return RemoteGatewayConfig(
            wsBase = wsBase,
            restBase = restBase,
            wsPaths = wsPaths,
            webrtcGatewayWsPath = webrtcWsPath,
            stunServers = stunServers,
            turnServers = turnServers,
            transportPriority = transportPriority,
            transportCandidates = transportCandidates,
            featureFlags = featureFlags
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Data models
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Parsed representation of the `GET /api/v1/config` response.
 *
 * All fields are nullable so that partial responses are handled gracefully;
 * missing keys fall back to [ServerConfig] compile-time defaults.
 */
data class RemoteGatewayConfig(
    val wsBase: String?,
    val restBase: String?,
    val wsPaths: List<String>?,
    val webrtcGatewayWsPath: String?,
    val stunServers: List<String>?,
    val turnServers: List<String>?,
    val transportPriority: List<String>?,
    /** Map of channel label (e.g. "tailscale") → WebSocket host (e.g. "ws://100.x.x.x:8050"). */
    val transportCandidates: Map<String, String>?,
    val featureFlags: Map<String, Boolean>?
)

/**
 * Result of a successful channel probe.
 *
 * @param host   WebSocket base URL of the chosen candidate.
 * @param label  Channel label (e.g. "tailscale", "intranet", "internet").
 * @param rttMs  Round-trip time measured during the probe (ms).
 */
data class ChannelProbeResult(
    val host: String,
    val label: String,
    val rttMs: Long
)

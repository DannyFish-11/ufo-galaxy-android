package com.ufo.galaxy.config

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [RemoteConfigFetcher] parsing logic and [ServerConfig] runtime
 * overrides.  No network calls are made; all tests run on the JVM.
 */
class RemoteConfigFetcherTest {

    @Before
    fun setUp() {
        ServerConfig.clearRuntimeOverrides()
    }

    @After
    fun tearDown() {
        ServerConfig.clearRuntimeOverrides()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // parseConfig — full response
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `parseConfig extracts ws_base`() {
        val json = JSONObject("""{"ws_base":"ws://gw.example.com:8050"}""")
        val config = RemoteConfigFetcher.parseConfig(json)
        assertEquals("ws://gw.example.com:8050", config.wsBase)
    }

    @Test
    fun `parseConfig extracts rest_base`() {
        val json = JSONObject("""{"rest_base":"http://gw.example.com:8050"}""")
        val config = RemoteConfigFetcher.parseConfig(json)
        assertEquals("http://gw.example.com:8050", config.restBase)
    }

    @Test
    fun `parseConfig extracts ws_paths list`() {
        val json = JSONObject("""{"ws_paths":["/ws/android/{id}","/ws/device/{id}"]}""")
        val config = RemoteConfigFetcher.parseConfig(json)
        assertEquals(listOf("/ws/android/{id}", "/ws/device/{id}"), config.wsPaths)
    }

    @Test
    fun `parseConfig extracts webrtc_gateway_ws_path`() {
        val json = JSONObject("""{"webrtc_gateway_ws_path":"/ws/webrtc/{id}"}""")
        val config = RemoteConfigFetcher.parseConfig(json)
        assertEquals("/ws/webrtc/{id}", config.webrtcGatewayWsPath)
    }

    @Test
    fun `parseConfig extracts stun list`() {
        val json = JSONObject("""{"stun":["stun:stun.l.google.com:19302"]}""")
        val config = RemoteConfigFetcher.parseConfig(json)
        assertEquals(listOf("stun:stun.l.google.com:19302"), config.stunServers)
    }

    @Test
    fun `parseConfig extracts turn list`() {
        val json = JSONObject("""{"turn":["turn:turn.example.com:3478"]}""")
        val config = RemoteConfigFetcher.parseConfig(json)
        assertEquals(listOf("turn:turn.example.com:3478"), config.turnServers)
    }

    @Test
    fun `parseConfig extracts transport_priority`() {
        val json = JSONObject("""{"transport_priority":["tailscale","intranet","internet"]}""")
        val config = RemoteConfigFetcher.parseConfig(json)
        assertEquals(listOf("tailscale", "intranet", "internet"), config.transportPriority)
    }

    @Test
    fun `parseConfig extracts transport_candidates map`() {
        val json = JSONObject(
            """{"transport_candidates":{"tailscale":"ws://100.64.0.1:8050","intranet":"ws://192.168.1.100:8050"}}"""
        )
        val config = RemoteConfigFetcher.parseConfig(json)
        assertNotNull(config.transportCandidates)
        assertEquals("ws://100.64.0.1:8050", config.transportCandidates?.get("tailscale"))
        assertEquals("ws://192.168.1.100:8050", config.transportCandidates?.get("intranet"))
    }

    @Test
    fun `parseConfig extracts feature_flags`() {
        val json = JSONObject("""{"feature_flags":{"webrtc_enabled":true,"beta_ui":false}}""")
        val config = RemoteConfigFetcher.parseConfig(json)
        assertNotNull(config.featureFlags)
        assertEquals(true, config.featureFlags?.get("webrtc_enabled"))
        assertEquals(false, config.featureFlags?.get("beta_ui"))
    }

    @Test
    fun `parseConfig handles empty json gracefully`() {
        val config = RemoteConfigFetcher.parseConfig(JSONObject("{}"))
        assertNull(config.wsBase)
        assertNull(config.restBase)
        assertNull(config.wsPaths)
        assertNull(config.stunServers)
        assertNull(config.transportCandidates)
        assertNull(config.featureFlags)
    }

    @Test
    fun `parseConfig ignores blank ws_base`() {
        val json = JSONObject("""{"ws_base":""}""")
        val config = RemoteConfigFetcher.parseConfig(json)
        assertNull(config.wsBase)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ServerConfig runtime overrides
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `effectiveWsBase returns DEFAULT_BASE_URL when no override`() {
        assertEquals(ServerConfig.DEFAULT_BASE_URL, ServerConfig.effectiveWsBase)
    }

    @Test
    fun `effectiveWsBase returns runtimeWsBase when set`() {
        ServerConfig.runtimeWsBase = "ws://custom:9000"
        assertEquals("ws://custom:9000", ServerConfig.effectiveWsBase)
    }

    @Test
    fun `effectiveWsPaths returns WS_PATHS when no override`() {
        assertEquals(ServerConfig.WS_PATHS, ServerConfig.effectiveWsPaths)
    }

    @Test
    fun `effectiveWsPaths returns runtimeWsPaths when set`() {
        val custom = listOf("/ws/custom/{id}")
        ServerConfig.runtimeWsPaths = custom
        assertEquals(custom, ServerConfig.effectiveWsPaths)
    }

    @Test
    fun `effectiveIceServers returns DEFAULT_STUN_URL list when no override`() {
        assertEquals(listOf(ServerConfig.DEFAULT_STUN_URL), ServerConfig.effectiveIceServers)
    }

    @Test
    fun `effectiveIceServers returns runtime list when set`() {
        val ice = listOf("stun:custom.stun:3478", "turn:turn.example.com:3478")
        ServerConfig.runtimeIceServers = ice
        assertEquals(ice, ServerConfig.effectiveIceServers)
    }

    @Test
    fun `effectiveFeatureFlags returns empty map when no override`() {
        assertTrue(ServerConfig.effectiveFeatureFlags.isEmpty())
    }

    @Test
    fun `effectiveFeatureFlags returns runtime flags when set`() {
        ServerConfig.runtimeFeatureFlags = mapOf("beta_ui" to true)
        assertEquals(true, ServerConfig.effectiveFeatureFlags["beta_ui"])
    }

    @Test
    fun `clearRuntimeOverrides resets all fields to null`() {
        ServerConfig.runtimeWsBase = "ws://custom:9000"
        ServerConfig.runtimeWsPaths = listOf("/ws/x")
        ServerConfig.runtimeIceServers = listOf("stun:x:3478")
        ServerConfig.runtimeFeatureFlags = mapOf("flag" to true)
        ServerConfig.chosenChannelLabel = "tailscale"

        ServerConfig.clearRuntimeOverrides()

        assertNull(ServerConfig.runtimeWsBase)
        assertNull(ServerConfig.runtimeWsPaths)
        assertNull(ServerConfig.runtimeIceServers)
        assertNull(ServerConfig.runtimeFeatureFlags)
        assertNull(ServerConfig.chosenChannelLabel)

        // Effective values fall back to defaults
        assertEquals(ServerConfig.DEFAULT_BASE_URL, ServerConfig.effectiveWsBase)
        assertEquals(ServerConfig.WS_PATHS, ServerConfig.effectiveWsPaths)
        assertEquals(listOf(ServerConfig.DEFAULT_STUN_URL), ServerConfig.effectiveIceServers)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // buildEffectiveWsUrl
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `buildEffectiveWsUrl uses WS_PATHS when no runtime override`() {
        val url = ServerConfig.buildEffectiveWsUrl("ws://host:8050", "dev1", 0)
        assertEquals("ws://host:8050/ws/android/dev1", url)
    }

    @Test
    fun `buildEffectiveWsUrl uses runtimeWsPaths when set`() {
        ServerConfig.runtimeWsPaths = listOf("/ws/custom/{id}")
        val url = ServerConfig.buildEffectiveWsUrl("ws://host:8050", "dev1", 0)
        assertEquals("ws://host:8050/ws/custom/dev1", url)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildEffectiveWsUrl throws on out-of-range index`() {
        ServerConfig.buildEffectiveWsUrl("ws://host:8050", "dev1", 99)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // probeChannels – deterministic tests using pre-computed probe results
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Simulates the channel selection logic without network calls by using
     * predefined probe results.  Mirrors the selection algorithm in
     * [RemoteConfigFetcher.probeChannels].
     */
    private fun selectBestChannel(
        candidates: Map<String, String>,
        results: Map<String, Long?>,
        priority: List<String>
    ): ChannelProbeResult? {
        for (label in priority) {
            val rtt = results[label] ?: continue
            val host = candidates[label] ?: continue
            return ChannelProbeResult(host = host, label = label, rttMs = rtt)
        }
        return results.entries
            .filter { it.value != null }
            .minByOrNull { it.value!! }
            ?.let { (l, r) -> ChannelProbeResult(host = candidates[l]!!, label = l, rttMs = r!!) }
    }

    @Test
    fun `probeChannels picks highest-priority reachable candidate`() {
        val candidates = mapOf(
            "tailscale" to "ws://ts-host:8050",
            "intranet" to "ws://lan-host:8050",
            "internet" to "ws://pub-host:8050"
        )
        val priority = listOf("tailscale", "intranet", "internet")
        // tailscale unreachable, intranet at 10ms, internet at 5ms
        val results: Map<String, Long?> = mapOf(
            "tailscale" to null,
            "intranet" to 10L,
            "internet" to 5L
        )

        val best = selectBestChannel(candidates, results, priority)

        assertNotNull(best)
        assertEquals("intranet", best!!.label)
        assertEquals("ws://lan-host:8050", best.host)
        assertEquals(10L, best.rttMs)
    }

    @Test
    fun `probeChannels falls back to lowest RTT when none in priority list`() {
        val candidates = mapOf(
            "tailscale" to "ws://ts-host:8050",
            "internet" to "ws://pub-host:8050"
        )
        // "intranet" is in priority but not in candidates; only "internet" reachable
        val results: Map<String, Long?> = mapOf(
            "tailscale" to null,
            "internet" to 50L
        )
        val priority = listOf("tailscale", "intranet")

        val best = selectBestChannel(candidates, results, priority)

        assertNotNull(best)
        assertEquals("internet", best!!.label)
    }

    @Test
    fun `probeChannels returns null when all candidates unreachable`() {
        val candidates = mapOf("tailscale" to "ws://ts:8050")
        val results: Map<String, Long?> = mapOf("tailscale" to null)
        val priority = listOf("tailscale")

        val best = selectBestChannel(candidates, results, priority)

        assertNull(best)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ChannelProbeResult data class
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `ChannelProbeResult holds host label and rtt`() {
        val result = ChannelProbeResult(
            host = "ws://100.64.0.1:8050",
            label = "tailscale",
            rttMs = 12L
        )
        assertEquals("ws://100.64.0.1:8050", result.host)
        assertEquals("tailscale", result.label)
        assertEquals(12L, result.rttMs)
    }
}

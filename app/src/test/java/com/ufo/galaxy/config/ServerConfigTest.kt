package com.ufo.galaxy.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ServerConfig].
 *
 * Validates URL-building utilities without requiring an Android device.
 */
class ServerConfigTest {

    private val baseUrl = "ws://192.168.1.100:8050"
    private val deviceId = "android_test_device"

    // ──────────────────────────────────────────────────────────────────────
    // buildWsUrl
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `buildWsUrl index 0 uses preferred device path`() {
        val url = ServerConfig.buildWsUrl(baseUrl, deviceId, 0)
        assertEquals("$baseUrl/ws/device/$deviceId", url)
    }

    @Test
    fun `buildWsUrl index 1 uses android path`() {
        val url = ServerConfig.buildWsUrl(baseUrl, deviceId, 1)
        assertEquals("$baseUrl/ws/android", url)
    }

    @Test
    fun `buildWsUrl index 2 uses legacy ufo3 path`() {
        val url = ServerConfig.buildWsUrl(baseUrl, deviceId, 2)
        assertEquals("$baseUrl/ws/ufo3/$deviceId", url)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildWsUrl throws on out-of-range index`() {
        ServerConfig.buildWsUrl(baseUrl, deviceId, 99)
    }

    @Test
    fun `buildWsUrl default index is 0`() {
        assertEquals(
            ServerConfig.buildWsUrl(baseUrl, deviceId, 0),
            ServerConfig.buildWsUrl(baseUrl, deviceId)
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // allWsUrls
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `allWsUrls returns list with same size as WS_PATHS`() {
        val urls = ServerConfig.allWsUrls(baseUrl, deviceId)
        assertEquals(ServerConfig.WS_PATHS.size, urls.size)
    }

    @Test
    fun `allWsUrls first entry equals preferred device path`() {
        val urls = ServerConfig.allWsUrls(baseUrl, deviceId)
        assertEquals("$baseUrl/ws/device/$deviceId", urls[0])
    }

    // ──────────────────────────────────────────────────────────────────────
    // buildRestUrl
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `buildRestUrl v1 true uses v1 prefix`() {
        val url = ServerConfig.buildRestUrl("http://host:8000", "/register", v1 = true)
        assertEquals("http://host:8000/api/v1/devices/register", url)
    }

    @Test
    fun `buildRestUrl v1 false uses legacy prefix`() {
        val url = ServerConfig.buildRestUrl("http://host:8000", "/register", v1 = false)
        assertEquals("http://host:8000/api/devices/register", url)
    }

    @Test
    fun `buildRestUrl defaults to v1`() {
        assertEquals(
            ServerConfig.buildRestUrl("http://host:8000", "/heartbeat", v1 = true),
            ServerConfig.buildRestUrl("http://host:8000", "/heartbeat")
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // wsToHttpBase
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `wsToHttpBase converts ws scheme`() {
        assertEquals("http://host:8000", ServerConfig.wsToHttpBase("ws://host:8000"))
    }

    @Test
    fun `wsToHttpBase converts wss scheme`() {
        assertEquals("https://host:443", ServerConfig.wsToHttpBase("wss://host:443"))
    }

    @Test
    fun `wsToHttpBase leaves http base unchanged`() {
        assertEquals("http://host:8000", ServerConfig.wsToHttpBase("http://host:8000"))
    }
}

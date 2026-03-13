package com.ufo.galaxy.network

import com.ufo.galaxy.data.InMemoryAppSettings
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [MessageRouter].
 *
 * These are pure JVM tests; no Android framework required.
 */
class MessageRouterTest {

    // ── Local fallback path ───────────────────────────────────────────────────

    @Test
    fun `route calls localFallback when crossDeviceEnabled is false`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = false)
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        var localCalled = false

        val router = MessageRouter(settings, client) { _ -> localCalled = true }
        val result = router.route("open WeChat")

        assertFalse("WS path should not be used", result)
        assertTrue("Local fallback must be called", localCalled)
    }

    @Test
    fun `route calls localFallback when crossDeviceEnabled is true but WS disconnected`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        // client crossDeviceEnabled=false so it won't connect → isConnected() = false
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        var localCalled = false

        val router = MessageRouter(settings, client) { _ -> localCalled = true }
        val result = router.route("open WeChat")

        assertFalse(result)
        assertTrue(localCalled)
    }

    @Test
    fun `route ignores empty text and returns false`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = false)
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        var localCalled = false

        val router = MessageRouter(settings, client) { _ -> localCalled = true }
        val result = router.route("   ")

        assertFalse(result)
        assertFalse("Local fallback must not be called for empty input", localCalled)
    }

    @Test
    fun `route ignores blank string and returns false`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        var localCalled = false

        val router = MessageRouter(settings, client) { _ -> localCalled = true }
        val result = router.route("")

        assertFalse(result)
        assertFalse(localCalled)
    }

    // ── WS path (sendJson returns false when not connected → falls back) ──────

    @Test
    fun `route calls localFallback when WS sendJson fails`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        // Client with crossDeviceEnabled=true but no server → sendJson returns false
        val testQueue = OfflineTaskQueue(prefs = null)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true,
            offlineQueue = testQueue
        )
        // Simulate that crossDeviceEnabled is set but not connected
        settings.crossDeviceEnabled = true
        client.setCrossDeviceEnabled(true)
        // isConnected() is false because we never called connect() successfully

        var localCalled = false
        val router = MessageRouter(settings, client) { _ -> localCalled = true }
        // WS is not connected → should fall back to local
        router.route("say hello")
        // localFallback should have been called (either crossDevice+disconnected → local,
        // or crossDevice+connected→sendFails→local)
        assertTrue("Local fallback must be invoked when WS is unavailable", localCalled)
    }
}

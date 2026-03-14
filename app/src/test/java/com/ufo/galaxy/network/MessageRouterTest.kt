package com.ufo.galaxy.network

import com.ufo.galaxy.data.InMemoryAppSettings
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [MessageRouter] (P2 strong-consistency routing).
 *
 * P2 routing rules:
 *  - crossDeviceEnabled=false → always LOCAL; task_submit uplink forbidden.
 *  - crossDeviceEnabled=true + WS connected → CROSS_DEVICE (WS uplink).
 *  - crossDeviceEnabled=true + WS NOT connected → ERROR; no silent local fallback.
 *
 * These are pure JVM tests; no Android framework required.
 */
class MessageRouterTest {

    // ── OFF mode (crossDeviceEnabled=false) ────────────────────────────────────

    @Test
    fun `route returns LOCAL and calls localFallback when crossDeviceEnabled is false`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = false)
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        var localCalled = false
        var errorCalled = false

        val router = MessageRouter(settings, client, onError = { errorCalled = true }) { _ -> localCalled = true }
        val result = router.route("open WeChat")

        assertEquals("Should take LOCAL path when crossDeviceEnabled=false", MessageRouter.RouteMode.LOCAL, result)
        assertTrue("Local fallback must be called in OFF mode", localCalled)
        assertFalse("onError must NOT be called in OFF mode", errorCalled)
    }

    @Test
    fun `route OFF mode never calls task_submit uplink`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = false)
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        var crossDeviceCalled = false

        val router = MessageRouter(settings, client) { _ -> }
        val result = router.route("do something")

        assertNotEquals("OFF mode must never produce CROSS_DEVICE", MessageRouter.RouteMode.CROSS_DEVICE, result)
    }

    // ── ON mode + WS unavailable → explicit error, no silent fallback ─────────

    @Test
    fun `route returns ERROR when crossDeviceEnabled is true but WS disconnected`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        // client is not connected (never called connect())
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = true)
        var localCalled = false
        var errorCalled = false

        val router = MessageRouter(settings, client, onError = { errorCalled = true }) { _ -> localCalled = true }
        val result = router.route("open WeChat")

        assertEquals("Must return ERROR when WS unavailable in cross-device mode",
            MessageRouter.RouteMode.ERROR, result)
        assertFalse("Local fallback must NOT be called silently when WS unavailable", localCalled)
        assertTrue("onError callback must be invoked when WS unavailable", errorCalled)
    }

    @Test
    fun `route returns ERROR when crossDeviceEnabled true and client crossDeviceEnabled false`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        // WS client with crossDeviceEnabled=false won't have a connection
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        var localCalled = false
        var errorCalled = false

        val router = MessageRouter(settings, client, onError = { errorCalled = true }) { _ -> localCalled = true }
        val result = router.route("open WeChat")

        assertEquals(MessageRouter.RouteMode.ERROR, result)
        assertFalse("No silent local fallback", localCalled)
        assertTrue("Error must be surfaced", errorCalled)
    }

    // ── Empty input ────────────────────────────────────────────────────────────

    @Test
    fun `route ignores empty text and returns LOCAL without calling fallback`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = false)
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        var localCalled = false

        val router = MessageRouter(settings, client) { _ -> localCalled = true }
        val result = router.route("   ")

        assertEquals(MessageRouter.RouteMode.LOCAL, result)
        assertFalse("Local fallback must not be called for whitespace-only input", localCalled)
    }

    @Test
    fun `route ignores blank string and returns LOCAL without calling fallback`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = false)
        var localCalled = false

        val router = MessageRouter(settings, client) { _ -> localCalled = true }
        val result = router.route("")

        assertEquals(MessageRouter.RouteMode.LOCAL, result)
        assertFalse(localCalled)
    }

    // ── onError is optional ────────────────────────────────────────────────────

    @Test
    fun `route with no onError callback still returns ERROR mode without crashing`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        val client = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = true)
        // No onError provided (default null)
        val router = MessageRouter(settings, client) { _ -> }
        // Should not throw even though WS is disconnected
        val result = router.route("test message")
        assertEquals(MessageRouter.RouteMode.ERROR, result)
    }
}

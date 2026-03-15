package com.ufo.galaxy.speech

import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.network.MessageRouter
import com.ufo.galaxy.network.OfflineTaskQueue
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [NaturalLanguageInputManager].
 *
 * Validates P1.7 requirement: NaturalLanguageInputManager must only collect input
 * and forward it to the [MessageRouter] — it must not make routing decisions itself.
 *
 * These are pure JVM tests; no Android framework required.
 */
class NaturalLanguageInputManagerTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildRouter(
        crossDeviceEnabled: Boolean = false,
        onLocal: (String) -> Unit = {}
    ): MessageRouter {
        val settings = InMemoryAppSettings(crossDeviceEnabled = crossDeviceEnabled)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false,
            offlineQueue = OfflineTaskQueue(prefs = null)
        )
        return MessageRouter(settings, client, localFallback = onLocal)
    }

    // ── submit() ──────────────────────────────────────────────────────────────

    @Test
    fun `submit forwards non-empty text to router`() {
        var routed = ""
        val router = buildRouter(crossDeviceEnabled = false, onLocal = { routed = it })
        val manager = NaturalLanguageInputManager(router)

        manager.submit("打开微信")

        assertEquals("打开微信", routed)
    }

    @Test
    fun `submit trims whitespace before routing`() {
        var routed = ""
        val router = buildRouter(crossDeviceEnabled = false, onLocal = { routed = it })
        val manager = NaturalLanguageInputManager(router)

        manager.submit("  发送消息  ")

        assertEquals("发送消息", routed)
    }

    @Test
    fun `submit returns false for empty string`() {
        var localCalled = false
        val router = buildRouter(crossDeviceEnabled = false, onLocal = { localCalled = true })
        val manager = NaturalLanguageInputManager(router)

        val result = manager.submit("")

        assertFalse("empty input must return false", result)
        assertFalse("local fallback must not be invoked for empty input", localCalled)
    }

    @Test
    fun `submit returns false for blank string`() {
        var localCalled = false
        val router = buildRouter(crossDeviceEnabled = false, onLocal = { localCalled = true })
        val manager = NaturalLanguageInputManager(router)

        val result = manager.submit("   ")

        assertFalse("blank input must return false", result)
        assertFalse(localCalled)
    }

    @Test
    fun `submit delegates routing decision to MessageRouter not to NLInputManager`() {
        // With crossDeviceEnabled=false and no live WS, router always goes local.
        var localCallCount = 0
        val router = buildRouter(crossDeviceEnabled = false, onLocal = { localCallCount++ })
        val manager = NaturalLanguageInputManager(router)

        // NaturalLanguageInputManager must not decide local vs WS — it just forwards.
        manager.submit("task one")
        manager.submit("task two")

        // Both go through router → local fallback since WS is not connected.
        assertEquals("both inputs must reach local fallback", 2, localCallCount)
    }

    // ── submitVoiceResult() ───────────────────────────────────────────────────

    @Test
    fun `submitVoiceResult forwards voice transcript to router`() {
        var routed = ""
        val router = buildRouter(crossDeviceEnabled = false, onLocal = { routed = it })
        val manager = NaturalLanguageInputManager(router)

        manager.submitVoiceResult("打开设置")

        assertEquals("打开设置", routed)
    }

    @Test
    fun `submitVoiceResult returns false for empty transcript`() {
        var localCalled = false
        val router = buildRouter(crossDeviceEnabled = false, onLocal = { localCalled = true })
        val manager = NaturalLanguageInputManager(router)

        val result = manager.submitVoiceResult("")

        assertFalse("empty voice transcript must return false", result)
        assertFalse(localCalled)
    }

    @Test
    fun `submitVoiceResult behaves identically to submit`() {
        var textRouted = ""
        var voiceRouted = ""

        val routerForText = buildRouter(crossDeviceEnabled = false, onLocal = { textRouted = it })
        val routerForVoice = buildRouter(crossDeviceEnabled = false, onLocal = { voiceRouted = it })

        NaturalLanguageInputManager(routerForText).submit("go home")
        NaturalLanguageInputManager(routerForVoice).submitVoiceResult("go home")

        assertEquals("submit and submitVoiceResult must produce identical routing",
            textRouted, voiceRouted)
    }

    // ── Routing decisions must stay in MessageRouter ──────────────────────────

    @Test
    fun `NLInputManager does not call connect or disconnect on client directly`() {
        // NaturalLanguageInputManager must not touch GalaxyWebSocketClient directly.
        // It only calls router.route() which may or may not use the client.
        val settings = InMemoryAppSettings(crossDeviceEnabled = false)
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = false,
            offlineQueue = OfflineTaskQueue(prefs = null)
        )
        val router = MessageRouter(settings, client) { /* local fallback */ }
        val manager = NaturalLanguageInputManager(router)

        // After submit, client must still be disconnected (no connect() called).
        manager.submit("hello")

        assertFalse(
            "NLInputManager must not connect the WebSocket client",
            client.isConnected()
        )
    }
}

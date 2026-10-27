package com.ufo.galaxy.network

import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 和智能体通话的信令在连接这一层:网关下行的 `voice_call_accepted` / `voice_call_end` / `voice_event` /
 * `voice_ice` 原样交给 [GalaxyWebSocketClient.Listener.onVoiceCallFrame](通话期间 PhoneCallService 在听),
 * 不落进 onMessage / onUnknownMessage —— 此前手机没有这一路,网关接通了手机也看不见。
 */
class PhoneCallWireTest {

    private val processMessage: Method = GalaxyWebSocketClient::class.java
        .getDeclaredMethod("processMessage", String::class.java)
        .also { it.isAccessible = true }

    private class Captured : GalaxyWebSocketClient.Listener {
        val frames = mutableListOf<String>()
        var other = 0
        override fun onConnected() = Unit
        override fun onDisconnected() = Unit
        override fun onMessage(message: String) { other++ }
        override fun onError(error: String) = Unit
        override fun onUnknownMessage(rawType: String?, rawJson: String) { other++ }
        override fun onVoiceCallFrame(frameJson: String) { frames += frameJson }
    }

    @Test
    fun `call signalling from the gateway reaches the call listener untouched`() {
        val c = GalaxyWebSocketClient(serverUrl = "ws://localhost:9999", crossDeviceEnabled = true, offlineQueue = OfflineTaskQueue(prefs = null))
        val cap = Captured().also { c.addListener(it) }
        val sent = listOf(
            """{"type":"voice_call_accepted","device_id":"p","call_id":"c1","sdp":"v=0"}""",
            """{"type":"voice_event","device_id":"p","call_id":"c1","event":"agent_task","text":"在做:x"}""",
            """{"type":"voice_ice","payload":{"call_id":"c1","candidate":"candidate:1"}}""",
            """{"type":"voice_call_end","device_id":"p","call_id":"c1","reason":"user_hangup"}""",
        )
        sent.forEach { processMessage.invoke(c, it) }
        assertEquals(sent, cap.frames)
        assertTrue("不该再落进 onMessage / onUnknownMessage", cap.other == 0)
    }
}

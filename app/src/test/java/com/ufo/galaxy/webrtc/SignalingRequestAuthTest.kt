package com.ufo.galaxy.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The gateway's WebRTC signaling path is members-only (V2 `galaxy_gateway/webrtc_proxy.py`):
 * no valid token → close 4401. It used to be gated by the desktop's 「跨设备」 switch instead,
 * which shut a joined phone out whenever the desktop was in local mode. The phone now sends
 * its pairing token — in a header, never in the URL.
 */
class SignalingRequestAuthTest {

    @Test
    fun `the pairing token goes in a bearer header`() {
        val req = WebRTCSignalingClient.buildRequest("ws://192.168.1.5:9000/ws/webrtc/phone-1", " tok-abc ")
        assertEquals("Bearer tok-abc", req.header("Authorization"))
    }

    @Test
    fun `the token never ends up in the url`() {
        val req = WebRTCSignalingClient.buildRequest("ws://192.168.1.5:9000/ws/webrtc/phone-1", "tok-abc")
        assertFalse(req.url.toString().contains("tok-abc"))
    }

    @Test
    fun `no token means no header rather than an empty bearer`() {
        val req = WebRTCSignalingClient.buildRequest("ws://192.168.1.5:9000/ws/webrtc/phone-1", "")
        assertNull(req.header("Authorization"))
    }
}

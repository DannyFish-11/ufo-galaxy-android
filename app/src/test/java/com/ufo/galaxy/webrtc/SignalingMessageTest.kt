package com.ufo.galaxy.webrtc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SignalingMessage] JSON serialization and deserialization.
 *
 * Validates the round-trip encoding expected by the Galaxy Gateway WebRTC
 * signaling proxy without requiring a device or emulator.
 */
class SignalingMessageTest {

    // ──────────────────────────────────────────────────────────────────────
    // toJson / fromJson – offer
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `offer message round-trips through JSON`() {
        val original = SignalingMessage(
            type = "offer",
            sdp = "v=0\r\no=- 123 456 IN IP4 127.0.0.1\r\n",
            deviceId = "android_test_device"
        )
        val json = original.toJson()
        val restored = SignalingMessage.fromJson(json)

        assertEquals("offer", restored.type)
        assertEquals(original.sdp, restored.sdp)
        assertEquals(original.deviceId, restored.deviceId)
        assertNull("No candidate in offer", restored.candidate)
    }

    @Test
    fun `offer toJson contains required fields`() {
        val msg = SignalingMessage(type = "offer", sdp = "v=0", deviceId = "dev1")
        val json = msg.toJson()

        assertEquals("offer", json.getString("type"))
        assertEquals("v=0", json.getString("sdp"))
        assertEquals("dev1", json.getString("device_id"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // toJson / fromJson – answer
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `answer message round-trips through JSON`() {
        val original = SignalingMessage(
            type = "answer",
            sdp = "v=0\r\no=- 789 012 IN IP4 127.0.0.1\r\n",
            deviceId = "android_test_device"
        )
        val restored = SignalingMessage.fromJson(original.toJson())

        assertEquals("answer", restored.type)
        assertEquals(original.sdp, restored.sdp)
        assertNull("No candidate in answer", restored.candidate)
    }

    // ──────────────────────────────────────────────────────────────────────
    // toJson / fromJson – ice_candidate
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `ice_candidate message round-trips through JSON`() {
        val iceCandidate = SignalingMessage.IceCandidate(
            candidate = "candidate:1 1 UDP 2130706431 192.168.1.1 54321 typ host",
            sdpMid = "0",
            sdpMLineIndex = 0
        )
        val original = SignalingMessage(
            type = "ice_candidate",
            candidate = iceCandidate,
            deviceId = "android_test_device"
        )
        val restored = SignalingMessage.fromJson(original.toJson())

        assertEquals("ice_candidate", restored.type)
        assertNotNull(restored.candidate)
        assertEquals(iceCandidate.candidate, restored.candidate!!.candidate)
        assertEquals(iceCandidate.sdpMid, restored.candidate.sdpMid)
        assertEquals(iceCandidate.sdpMLineIndex, restored.candidate.sdpMLineIndex)
        assertNull("No SDP in ice_candidate", restored.sdp)
    }

    @Test
    fun `ice_candidate toJson contains nested candidate object`() {
        val msg = SignalingMessage(
            type = "ice_candidate",
            candidate = SignalingMessage.IceCandidate("candidate:abc", "audio", 1)
        )
        val json = msg.toJson()

        assertEquals("ice_candidate", json.getString("type"))
        val cJson = json.getJSONObject("candidate")
        assertEquals("candidate:abc", cJson.getString("candidate"))
        assertEquals("audio", cJson.getString("sdpMid"))
        assertEquals(1, cJson.getInt("sdpMLineIndex"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // fromJsonString
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `fromJsonString parses valid JSON string`() {
        val json = """{"type":"offer","sdp":"v=0","device_id":"dev99"}"""
        val msg = SignalingMessage.fromJsonString(json)

        assertNotNull(msg)
        assertEquals("offer", msg!!.type)
        assertEquals("v=0", msg.sdp)
        assertEquals("dev99", msg.deviceId)
    }

    @Test
    fun `fromJsonString returns null for invalid JSON`() {
        assertNull(SignalingMessage.fromJsonString("not json"))
        assertNull(SignalingMessage.fromJsonString(""))
        assertNull(SignalingMessage.fromJsonString("{no type here}"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // Optional fields
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `message without deviceId omits device_id field`() {
        val msg = SignalingMessage(type = "offer", sdp = "v=0")
        val json = msg.toJson()

        assertTrue("device_id should be absent", !json.has("device_id"))
    }

    @Test
    fun `message without sdp omits sdp field`() {
        val msg = SignalingMessage(
            type = "ice_candidate",
            candidate = SignalingMessage.IceCandidate("c", null, 0)
        )
        val json = msg.toJson()

        assertTrue("sdp field should be absent", !json.has("sdp"))
    }

    @Test
    fun `IceCandidate without sdpMid omits sdpMid field`() {
        val candidate = SignalingMessage.IceCandidate("c", null, 0)
        val json = candidate.toJson()

        assertTrue("sdpMid should be absent when null", !json.has("sdpMid"))
        assertEquals(0, json.getInt("sdpMLineIndex"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // Gateway JSON compatibility
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `parses gateway-format offer JSON`() {
        // Simulate a JSON message as produced by the gateway signaling proxy
        val gatewayJson = JSONObject().apply {
            put("type", "offer")
            put("sdp", "v=0\r\no=- 1 2 IN IP4 0.0.0.0\r\n")
            put("device_id", "gateway_node_95")
        }
        val msg = SignalingMessage.fromJson(gatewayJson)

        assertEquals("offer", msg.type)
        assertEquals("gateway_node_95", msg.deviceId)
        assertNotNull(msg.sdp)
    }

    @Test
    fun `parses gateway-format ice_candidate JSON`() {
        val candidatePayload = JSONObject().apply {
            put("candidate", "candidate:0 1 UDP 2122252543 10.0.0.1 54321 typ host")
            put("sdpMid", "0")
            put("sdpMLineIndex", 0)
        }
        val gatewayJson = JSONObject().apply {
            put("type", "ice_candidate")
            put("candidate", candidatePayload)
        }
        val msg = SignalingMessage.fromJson(gatewayJson)

        assertEquals("ice_candidate", msg.type)
        assertNotNull(msg.candidate)
        assertTrue(msg.candidate!!.candidate.startsWith("candidate:"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // AIP v3 metadata fields (protocol / version / trace_id / route_mode)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `toJson always includes protocol and version AIP v3 fields`() {
        val msg = SignalingMessage(type = "offer", sdp = "v=0", deviceId = "dev1")
        val json = msg.toJson()

        assertEquals("AIP/1.0", json.getString("protocol"))
        assertEquals("3.0", json.getString("version"))
    }

    @Test
    fun `toJson includes trace_id when provided`() {
        val traceId = "trace-abc-123"
        val msg = SignalingMessage(type = "offer", sdp = "v=0", traceId = traceId)
        val json = msg.toJson()

        assertEquals(traceId, json.getString("trace_id"))
    }

    @Test
    fun `toJson includes route_mode when provided`() {
        val msg = SignalingMessage(type = "offer", sdp = "v=0", routeMode = "cross_device")
        val json = msg.toJson()

        assertEquals("cross_device", json.getString("route_mode"))
    }

    @Test
    fun `toJson omits trace_id and route_mode when not provided`() {
        val msg = SignalingMessage(type = "offer", sdp = "v=0", deviceId = "dev1")
        val json = msg.toJson()

        assertTrue("trace_id should be absent when not set", !json.has("trace_id"))
        assertTrue("route_mode should be absent when not set", !json.has("route_mode"))
    }

    @Test
    fun `offer message with full AIP v3 metadata round-trips through JSON`() {
        val original = SignalingMessage(
            type      = "offer",
            sdp       = "v=0\r\n",
            deviceId  = "android_dev",
            traceId   = "trace-round-trip",
            routeMode = "local"
        )
        val restored = SignalingMessage.fromJson(original.toJson())

        assertEquals(original.traceId, restored.traceId)
        assertEquals(original.routeMode, restored.routeMode)
    }

    @Test
    fun `ice_candidate with trace_id includes it in JSON envelope`() {
        val msg = SignalingMessage(
            type      = "ice_candidate",
            candidate = SignalingMessage.IceCandidate("candidate:1 1 UDP 1 0.0.0.0 1234 typ host", "0", 0),
            deviceId  = "android_dev",
            traceId   = "trace-ice-001",
            routeMode = "cross_device"
        )
        val json = msg.toJson()

        assertEquals("trace-ice-001", json.getString("trace_id"))
        assertEquals("cross_device", json.getString("route_mode"))
        assertEquals("AIP/1.0", json.getString("protocol"))
        assertEquals("3.0", json.getString("version"))
    }
}

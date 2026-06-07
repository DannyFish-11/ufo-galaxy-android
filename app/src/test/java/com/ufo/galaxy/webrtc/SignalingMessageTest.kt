package com.ufo.galaxy.webrtc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SignalingMessage] and [TurnConfig] JSON serialization,
 * deserialization, and Round-6 multi-candidate / TURN-config handling.
 *
 * All tests run on the JVM without a device or emulator.
 */
class SignalingMessageTest {

    // ──────────────────────────────────────────────────────────────────────────
    // offer / answer – round-trip (legacy compatibility)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `offer message round-trips through JSON`() {
        val original = SignalingMessage(
            type = "offer",
            sdp = "v=0\r\no=- 123 456 IN IP4 127.0.0.1\r\n",
            deviceId = "android_test_device",
            traceId = "trace-001"
        )
        val restored = SignalingMessage.fromJson(original.toJson())

        assertEquals("offer", restored.type)
        assertEquals(original.sdp, restored.sdp)
        assertEquals(original.deviceId, restored.deviceId)
        assertEquals("trace-001", restored.traceId)
        assertNull(restored.candidate)
        assertTrue(restored.candidates.isEmpty())
    }

    @Test
    fun `offer toJson contains required fields`() {
        val msg = SignalingMessage(type = "offer", sdp = "v=0", deviceId = "dev1", traceId = "t1")
        val json = msg.toJson()

        assertEquals("offer", json.getString("type"))
        assertEquals("v=0", json.getString("sdp"))
        assertEquals("dev1", json.getString("device_id"))
        assertEquals("t1", json.getString("trace_id"))
    }

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
        assertNull(restored.candidate)
        assertTrue(restored.candidates.isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Legacy single ICE candidate (ice_candidate)
    // ──────────────────────────────────────────────────────────────────────────

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
        assertNull(restored.sdp)
        assertTrue(restored.candidates.isEmpty())
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

    // ──────────────────────────────────────────────────────────────────────────
    // Round-6 multi-candidate (ice_candidates)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `ice_candidates message with multiple candidates round-trips`() {
        val c1 = SignalingMessage.IceCandidate("candidate:1 1 UDP 2130706431 10.0.0.1 54321 typ host", "0", 0)
        val c2 = SignalingMessage.IceCandidate("candidate:2 1 UDP 1694498815 1.2.3.4 54321 typ srflx raddr 10.0.0.1 rport 54321", "0", 0)
        val c3 = SignalingMessage.IceCandidate("candidate:3 1 UDP 33562623 5.6.7.8 3478 typ relay raddr 1.2.3.4 rport 54321", "0", 0)

        val original = SignalingMessage(
            type = "ice_candidates",
            candidates = listOf(c1, c2, c3),
            deviceId = "dev-42",
            traceId = "trace-multi"
        )
        val restored = SignalingMessage.fromJson(original.toJson())

        assertEquals("ice_candidates", restored.type)
        assertEquals(3, restored.candidates.size)
        assertEquals(c1.candidate, restored.candidates[0].candidate)
        assertEquals(c2.candidate, restored.candidates[1].candidate)
        assertEquals(c3.candidate, restored.candidates[2].candidate)
        assertNull(restored.candidate)
        assertEquals("trace-multi", restored.traceId)
    }

    @Test
    fun `ice_candidates toJson serialises candidates array`() {
        val msg = SignalingMessage(
            type = "ice_candidates",
            candidates = listOf(
                SignalingMessage.IceCandidate("c1", "0", 0),
                SignalingMessage.IceCandidate("c2", "1", 1)
            )
        )
        val json = msg.toJson()

        assertTrue(json.has("candidates"))
        val arr = json.getJSONArray("candidates")
        assertEquals(2, arr.length())
        assertEquals("c1", arr.getJSONObject(0).getString("candidate"))
    }

    @Test
    fun `allCandidates combines single and batch fields`() {
        val single = SignalingMessage.IceCandidate("c1", "0", 0)
        val batch = listOf(
            SignalingMessage.IceCandidate("c2", "0", 0),
            SignalingMessage.IceCandidate("c3", "0", 0)
        )
        val msg = SignalingMessage(type = "ice_candidate", candidate = single, candidates = batch)

        assertEquals(3, msg.allCandidates.size)
    }

    @Test
    fun `hasAnyCandidates is true with single candidate`() {
        val msg = SignalingMessage(
            type = "ice_candidate",
            candidate = SignalingMessage.IceCandidate("c", null, 0)
        )
        assertTrue(msg.hasAnyCandidates)
    }

    @Test
    fun `hasAnyCandidates is true with candidates list`() {
        val msg = SignalingMessage(
            type = "ice_candidates",
            candidates = listOf(SignalingMessage.IceCandidate("c", null, 0))
        )
        assertTrue(msg.hasAnyCandidates)
    }

    @Test
    fun `hasAnyCandidates is false with no candidates`() {
        val msg = SignalingMessage(type = "offer", sdp = "v=0")
        assertFalse(msg.hasAnyCandidates)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TURN config
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `ice_candidates message with turn_config round-trips`() {
        val turnConfig = TurnConfig(
            urls = listOf("turn:100.64.0.1:3478", "turns:100.64.0.1:5349"),
            username = "galaxy_user",
            credential = "s3cr3t"
        )
        val original = SignalingMessage(
            type = "ice_candidates",
            candidates = listOf(SignalingMessage.IceCandidate("candidate:1 1 UDP 33562623 5.6.7.8 3478 typ relay", "0", 0)),
            turnConfig = turnConfig,
            traceId = "turn-trace"
        )
        val restored = SignalingMessage.fromJson(original.toJson())

        assertNotNull(restored.turnConfig)
        assertEquals(2, restored.turnConfig!!.urls.size)
        assertEquals("turn:100.64.0.1:3478", restored.turnConfig.urls[0])
        assertEquals("turns:100.64.0.1:5349", restored.turnConfig.urls[1])
        assertEquals("galaxy_user", restored.turnConfig.username)
        assertEquals("s3cr3t", restored.turnConfig.credential)
        assertEquals("turn-trace", restored.traceId)
    }

    @Test
    fun `TurnConfig fromJson accepts single url field`() {
        val json = JSONObject().apply {
            put("url", "turn:10.0.0.1:3478")
            put("username", "u")
            put("credential", "p")
        }
        val config = TurnConfig.fromJson(json)

        assertEquals(1, config.urls.size)
        assertEquals("turn:10.0.0.1:3478", config.urls[0])
    }

    @Test
    fun `TurnConfig toJson omits username and credential when null`() {
        val config = TurnConfig(urls = listOf("stun:stun.example.com"))
        val json = config.toJson()

        assertFalse(json.has("username"))
        assertFalse(json.has("credential"))
        assertEquals(1, json.getJSONArray("urls").length())
    }

    @Test
    fun `message without turn_config has null turnConfig`() {
        val json = JSONObject().apply {
            put("type", "ice_candidates")
            put("candidates", JSONArray())
        }
        val msg = SignalingMessage.fromJson(json)
        assertNull(msg.turnConfig)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // IceCandidate.candidateType
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `candidateType is relay for relay candidates`() {
        val c = SignalingMessage.IceCandidate("candidate:1 1 UDP 33562623 5.6.7.8 3478 typ relay raddr 1.2.3.4 rport 54321", "0", 0)
        assertEquals(SignalingMessage.IceCandidate.TYPE_RELAY, c.candidateType)
    }

    @Test
    fun `candidateType is srflx for server-reflexive candidates`() {
        val c = SignalingMessage.IceCandidate("candidate:2 1 UDP 1694498815 1.2.3.4 54321 typ srflx raddr 10.0.0.1 rport 54321", "0", 0)
        assertEquals(SignalingMessage.IceCandidate.TYPE_SRFLX, c.candidateType)
    }

    @Test
    fun `candidateType defaults to host`() {
        val c = SignalingMessage.IceCandidate("candidate:0 1 UDP 2130706431 192.168.1.1 54321 typ host", "0", 0)
        assertEquals(SignalingMessage.IceCandidate.TYPE_HOST, c.candidateType)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Error messages
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `error message round-trips through JSON`() {
        val original = SignalingMessage(
            type = "error",
            error = "ICE gathering timeout",
            traceId = "err-trace-001"
        )
        val restored = SignalingMessage.fromJson(original.toJson())

        assertEquals("error", restored.type)
        assertEquals("ICE gathering timeout", restored.error)
        assertEquals("err-trace-001", restored.traceId)
    }

    @Test
    fun `message without error field has null error`() {
        val json = JSONObject().apply { put("type", "offer"); put("sdp", "v=0") }
        assertNull(SignalingMessage.fromJson(json).error)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // fromJsonString
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `fromJsonString parses valid JSON string`() {
        val json = """{"type":"offer","sdp":"v=0","device_id":"dev99","trace_id":"t99"}"""
        val msg = SignalingMessage.fromJsonString(json)

        assertNotNull(msg)
        assertEquals("offer", msg!!.type)
        assertEquals("v=0", msg.sdp)
        assertEquals("dev99", msg.deviceId)
        assertEquals("t99", msg.traceId)
    }

    @Test
    fun `fromJsonString returns null for invalid JSON`() {
        assertNull(SignalingMessage.fromJsonString("not json"))
        assertNull(SignalingMessage.fromJsonString(""))
        assertNull(SignalingMessage.fromJsonString("{no type here}"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Optional field omission
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `message without deviceId omits device_id field`() {
        val msg = SignalingMessage(type = "offer", sdp = "v=0")
        assertFalse(msg.toJson().has("device_id"))
    }

    @Test
    fun `message without traceId omits trace_id field`() {
        val msg = SignalingMessage(type = "offer", sdp = "v=0")
        assertFalse(msg.toJson().has("trace_id"))
    }

    @Test
    fun `message without candidates omits candidates array`() {
        val msg = SignalingMessage(type = "offer", sdp = "v=0")
        assertFalse(msg.toJson().has("candidates"))
    }

    @Test
    fun `IceCandidate without sdpMid omits sdpMid field`() {
        val candidate = SignalingMessage.IceCandidate("c", null, 0)
        val json = candidate.toJson()
        assertFalse(json.has("sdpMid"))
        assertEquals(0, json.getInt("sdpMLineIndex"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Gateway JSON compatibility
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `parses gateway-format offer JSON`() {
        val gatewayJson = JSONObject().apply {
            put("type", "offer")
            put("sdp", "v=0\r\no=- 1 2 IN IP4 0.0.0.0\r\n")
            put("device_id", "gateway_node_95")
            put("trace_id", "gateway-trace-1")
        }
        val msg = SignalingMessage.fromJson(gatewayJson)

        assertEquals("offer", msg.type)
        assertEquals("gateway_node_95", msg.deviceId)
        assertEquals("gateway-trace-1", msg.traceId)
        assertNotNull(msg.sdp)
    }

    @Test
    fun `parses gateway-format ice_candidates JSON with TURN`() {
        val candidate1 = JSONObject().apply {
            put("candidate", "candidate:0 1 UDP 2122252543 10.0.0.1 54321 typ host")
            put("sdpMid", "0")
            put("sdpMLineIndex", 0)
        }
        val candidate2 = JSONObject().apply {
            put("candidate", "candidate:1 1 UDP 33562623 5.6.7.8 3478 typ relay raddr 10.0.0.1 rport 54321")
            put("sdpMid", "0")
            put("sdpMLineIndex", 0)
        }
        val turnJson = JSONObject().apply {
            put("urls", JSONArray().put("turn:100.64.0.1:3478"))
            put("username", "u1")
            put("credential", "p1")
        }
        val gatewayJson = JSONObject().apply {
            put("type", "ice_candidates")
            put("candidates", JSONArray().put(candidate1).put(candidate2))
            put("turn_config", turnJson)
            put("trace_id", "trace-gw-2")
        }
        val msg = SignalingMessage.fromJson(gatewayJson)

        assertEquals("ice_candidates", msg.type)
        assertEquals(2, msg.candidates.size)
        assertNotNull(msg.turnConfig)
        assertEquals("turn:100.64.0.1:3478", msg.turnConfig!!.urls[0])
        assertEquals("u1", msg.turnConfig.username)
        assertEquals("trace-gw-2", msg.traceId)
    }
}

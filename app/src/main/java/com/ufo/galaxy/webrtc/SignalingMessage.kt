package com.ufo.galaxy.webrtc

import com.ufo.galaxy.protocol.AIPMessageBuilder
import org.json.JSONObject

/**
 * Represents a WebRTC signaling message exchanged with the Galaxy Gateway proxy.
 *
 * Gateway JSON envelope:
 * ```json
 * // Offer / Answer
 * { "type": "offer"|"answer", "sdp": "<SDP string>", "device_id": "<id>" }
 *
 * // ICE candidate
 * { "type": "ice_candidate", "candidate": { "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0 }, "device_id": "<id>" }
 * ```
 *
 * Message types:
 * - `offer`          – SDP offer from the Android device to the remote peer.
 * - `answer`         – SDP answer from the remote peer to the Android device.
 * - `ice_candidate`  – ICE candidate exchanged between peers.
 *
 * AIP v3 metadata fields (`protocol`, `version`, `trace_id`, `route_mode`)
 * are included in every outbound JSON envelope so that the gateway and
 * remote peers can correlate and route signaling frames alongside other
 * AIP messages.
 */
data class SignalingMessage(
    val type: String,
    val sdp: String? = null,
    val candidate: IceCandidate? = null,
    val deviceId: String? = null,
    /** AIP v3 trace identifier – reuse the session trace ID from [GalaxyWebSocketClient]. */
    val traceId: String? = null,
    /** AIP v3 route mode – `"local"` or `"cross_device"`. */
    val routeMode: String? = null
) {

    /**
     * ICE candidate payload carried inside an `ice_candidate` message.
     */
    data class IceCandidate(
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("candidate", candidate)
            sdpMid?.let { put("sdpMid", it) }
            put("sdpMLineIndex", sdpMLineIndex)
        }

        companion object {
            fun fromJson(json: JSONObject): IceCandidate = IceCandidate(
                candidate = json.getString("candidate"),
                sdpMid = json.optString("sdpMid").takeIf { it.isNotEmpty() },
                sdpMLineIndex = json.optInt("sdpMLineIndex", 0)
            )
        }
    }

    /**
     * Serialize this message to a [JSONObject] suitable for sending over the WebSocket.
     *
     * AIP v3 envelope fields (`protocol`, `version`, `trace_id`, `route_mode`) are
     * always included so the gateway can correlate signaling frames with other AIP
     * messages from the same session.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        // AIP v3 envelope metadata
        put("protocol", AIPMessageBuilder.PROTOCOL_AIP1)
        put("version", AIPMessageBuilder.PROTOCOL_V3)
        // WebRTC-specific fields
        put("type", type)
        sdp?.let { put("sdp", it) }
        candidate?.let { put("candidate", it.toJson()) }
        deviceId?.let { put("device_id", it) }
        traceId?.let { put("trace_id", it) }
        routeMode?.let { put("route_mode", it) }
    }

    companion object {
        /**
         * Deserialize a [SignalingMessage] from the given [JSONObject].
         *
         * @throws org.json.JSONException if `type` is missing.
         */
        fun fromJson(json: JSONObject): SignalingMessage = SignalingMessage(
            type = json.getString("type"),
            sdp = json.optString("sdp").takeIf { it.isNotEmpty() },
            candidate = json.optJSONObject("candidate")?.let { IceCandidate.fromJson(it) },
            deviceId = json.optString("device_id").takeIf { it.isNotEmpty() },
            traceId = json.optString("trace_id").takeIf { it.isNotEmpty() },
            routeMode = json.optString("route_mode").takeIf { it.isNotEmpty() }
        )

        /**
         * Deserialize a [SignalingMessage] from a JSON string.
         *
         * @return the parsed [SignalingMessage], or `null` if parsing fails.
         */
        fun fromJsonString(text: String): SignalingMessage? = try {
            fromJson(JSONObject(text))
        } catch (e: Exception) {
            null
        }
    }
}

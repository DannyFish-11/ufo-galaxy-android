package com.ufo.galaxy.webrtc

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
 */
data class SignalingMessage(
    val type: String,
    val sdp: String? = null,
    val candidate: IceCandidate? = null,
    val deviceId: String? = null
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
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        sdp?.let { put("sdp", it) }
        candidate?.let { put("candidate", it.toJson()) }
        deviceId?.let { put("device_id", it) }
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
            deviceId = json.optString("device_id").takeIf { it.isNotEmpty() }
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

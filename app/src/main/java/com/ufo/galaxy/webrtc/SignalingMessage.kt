package com.ufo.galaxy.webrtc

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a WebRTC signaling message exchanged with the Galaxy Gateway proxy.
 *
 * Supports both legacy single-candidate and Round-6 multi-candidate / TURN formats.
 *
 * Gateway JSON formats:
 * ```json
 * // Offer / Answer
 * { "type": "offer"|"answer", "sdp": "<SDP>", "device_id": "<id>",
 *   "trace_id": "<UUID>" }
 *
 * // Single ICE candidate (legacy – backward compatible)
 * { "type": "ice_candidate",
 *   "candidate": { "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0 },
 *   "device_id": "<id>", "trace_id": "<UUID>" }
 *
 * // Multi ICE candidate (Round 6 – trickle / batch)
 * { "type": "ice_candidates",
 *   "candidates": [ { "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0 }, ... ],
 *   "turn_config": { "urls": ["turn:..."], "username": "u", "credential": "p" },
 *   "device_id": "<id>", "trace_id": "<UUID>" }
 *
 * // Error message
 * { "type": "error", "error": "<description>", "trace_id": "<UUID>" }
 * ```
 *
 * Message types:
 * - `offer`           – SDP offer from the Android device to the remote peer.
 * - `answer`          – SDP answer from the remote peer to the Android device.
 * - `ice_candidate`   – Single ICE candidate (legacy).
 * - `ice_candidates`  – Batch of ICE candidates (Round 6 trickle/batch).
 * - `error`           – Signaling error from the gateway, with optional trace_id.
 *
 * @param type        Message type identifier.
 * @param sdp         SDP payload (offer / answer messages only).
 * @param candidate   Single ICE candidate (legacy `ice_candidate` type).
 * @param candidates  Multiple ICE candidates (Round 6 `ice_candidates` type).
 * @param turnConfig  TURN server configuration delivered alongside candidates.
 * @param deviceId    Device identifier.
 * @param traceId     End-to-end trace identifier propagated across all hops; used
 *                    for log correlation and error surfacing.
 * @param error       Human-readable error description for `type = "error"` messages.
 */
data class SignalingMessage(
    val type: String,
    val sdp: String? = null,
    val candidate: IceCandidate? = null,
    val candidates: List<IceCandidate> = emptyList(),
    val turnConfig: TurnConfig? = null,
    val deviceId: String? = null,
    val traceId: String? = null,
    val error: String? = null
) {

    /**
     * ICE candidate payload carried inside an `ice_candidate` or `ice_candidates` message.
     *
     * The [candidateType] property classifies the candidate as `host`, `srflx`, or `relay`
     * so that [IceCandidateManager] can apply priority ordering without parsing the raw SDP.
     *
     * @param candidate     Raw SDP candidate string (e.g. `"candidate:1 1 UDP 2130706431 ..."`).
     * @param sdpMid        Media stream identifier.
     * @param sdpMLineIndex Media line index within the SDP.
     */
    data class IceCandidate(
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int
    ) {
        /**
         * Candidate type extracted from the SDP string:
         * - `"relay"` – TURN relay candidate
         * - `"srflx"` – STUN server-reflexive candidate
         * - `"host"`  – Direct local candidate (default)
         */
        val candidateType: String
            get() = when {
                candidate.contains("typ relay", ignoreCase = true) -> TYPE_RELAY
                candidate.contains("typ srflx", ignoreCase = true) -> TYPE_SRFLX
                else -> TYPE_HOST
            }

        fun toJson(): JSONObject = JSONObject().apply {
            put("candidate", candidate)
            sdpMid?.let { put("sdpMid", it) }
            put("sdpMLineIndex", sdpMLineIndex)
        }

        companion object {
            const val TYPE_RELAY = "relay"
            const val TYPE_SRFLX = "srflx"
            const val TYPE_HOST = "host"

            fun fromJson(json: JSONObject): IceCandidate = IceCandidate(
                candidate = json.getString("candidate"),
                sdpMid = json.optString("sdpMid").takeIf { it.isNotEmpty() },
                sdpMLineIndex = json.optInt("sdpMLineIndex", 0)
            )
        }
    }

    /**
     * Returns `true` if this message carries any ICE candidates (single or batch).
     */
    val hasAnyCandidates: Boolean
        get() = candidate != null || candidates.isNotEmpty()

    /**
     * All ICE candidates from this message, combining both the legacy single-candidate
     * field and the Round-6 batch field.
     */
    val allCandidates: List<IceCandidate>
        get() = buildList {
            candidate?.let { add(it) }
            addAll(candidates)
        }

    /**
     * Serialize this message to a [JSONObject] suitable for sending over the WebSocket.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        sdp?.let { put("sdp", it) }
        candidate?.let { put("candidate", it.toJson()) }
        if (candidates.isNotEmpty()) {
            val arr = JSONArray()
            candidates.forEach { arr.put(it.toJson()) }
            put("candidates", arr)
        }
        turnConfig?.let { put("turn_config", it.toJson()) }
        deviceId?.let { put("device_id", it) }
        traceId?.let { put("trace_id", it) }
        error?.let { put("error", it) }
    }

    companion object {
        const val TYPE_OFFER = "offer"
        const val TYPE_ANSWER = "answer"
        const val TYPE_ICE_CANDIDATE = "ice_candidate"
        const val TYPE_ICE_CANDIDATES = "ice_candidates"
        const val TYPE_ERROR = "error"

        /**
         * Deserialize a [SignalingMessage] from the given [JSONObject].
         *
         * @throws org.json.JSONException if `type` is missing.
         */
        fun fromJson(json: JSONObject): SignalingMessage {
            val candidatesList = mutableListOf<IceCandidate>()
            if (json.has("candidates")) {
                val arr = json.getJSONArray("candidates")
                for (i in 0 until arr.length()) {
                    candidatesList.add(IceCandidate.fromJson(arr.getJSONObject(i)))
                }
            }
            return SignalingMessage(
                type = json.getString("type"),
                sdp = json.optString("sdp").takeIf { it.isNotEmpty() },
                candidate = json.optJSONObject("candidate")?.let { IceCandidate.fromJson(it) },
                candidates = candidatesList,
                turnConfig = json.optJSONObject("turn_config")?.let { TurnConfig.fromJson(it) },
                deviceId = json.optString("device_id").takeIf { it.isNotEmpty() },
                traceId = json.optString("trace_id").takeIf { it.isNotEmpty() },
                error = json.optString("error").takeIf { it.isNotEmpty() }
            )
        }

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

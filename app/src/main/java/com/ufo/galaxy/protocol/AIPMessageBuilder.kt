package com.ufo.galaxy.protocol

import org.json.JSONObject
import java.util.UUID

/**
 * Central builder and parser for AIP messages.
 *
 * Every outbound message should pass through [build] so that AIP/1.0 fields and
 * optional v3-compatible fields are set in a single, consistent place.
 *
 * Every inbound message should pass through [parse] (or [normalise]) so that
 * callers always see AIP/1.0 field names regardless of which wire format the
 * sender used (AIP/1.0 native, Microsoft Galaxy, or v3).
 */
object AIPMessageBuilder {

    /** AIP/1.0 protocol identifier included in every outbound message. */
    const val PROTOCOL_AIP1 = "AIP/1.0"

    /** v3 protocol version string. */
    const val PROTOCOL_V3 = "3.0"

    // ──────────────────────────────────────────────────────────────────────────
    // Outbound message building
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Build an outbound AIP message.
     *
     * The resulting [JSONObject] contains:
     * - All required **AIP/1.0** fields (`protocol`, `type`, `source_node`,
     *   `target_node`, `timestamp`, `message_id`, `payload`) for backward
     *   compatibility.
     * - Optional **v3-compatible** fields (`version`, `device_id`, `device_type`)
     *   that maximise server compatibility when [includeV3] is `true` (the default).
     *
     * @param messageType   AIP/1.0 `type` value (e.g. `"registration"`, `"command"`).
     * @param sourceNodeId  Sending device / node identifier.
     * @param targetNodeId  Destination node identifier.
     * @param payload       Arbitrary payload [JSONObject].
     * @param deviceType    Optional device-type hint (default `"Android_Agent"`).
     * @param includeV3     When `true`, v3-compatible extra fields are included.
     * @param messageId     Unique message identifier; auto-generated if omitted.
     */
    fun build(
        messageType: String,
        sourceNodeId: String,
        targetNodeId: String,
        payload: JSONObject,
        deviceType: String = "Android_Agent",
        includeV3: Boolean = true,
        messageId: String = UUID.randomUUID().toString().take(8)
    ): JSONObject {
        return JSONObject().apply {
            // ── AIP/1.0 required fields ──────────────────────────────────────
            put("protocol", PROTOCOL_AIP1)
            put("type", messageType)
            put("source_node", sourceNodeId)
            put("target_node", targetNodeId)
            put("timestamp", System.currentTimeMillis() / 1000)
            put("message_id", messageId)
            put("payload", payload)

            // ── v3-compatible optional fields ─────────────────────────────────
            if (includeV3) {
                put("version", PROTOCOL_V3)
                put("device_id", sourceNodeId)
                put("device_type", deviceType)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Inbound message parsing / normalisation
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Parse an inbound message string and normalise it to AIP/1.0 field names.
     *
     * Handles three wire formats:
     * - **AIP/1.0 native** – returned as-is.
     * - **Microsoft Galaxy** – `message_type` / `agent_id` keys are mapped.
     * - **v3** – `version:"3.0"` / `msg_type` / `device_id` keys are mapped.
     *
     * @return Normalised [JSONObject], or `null` if [text] is not valid JSON.
     */
    fun parse(text: String): JSONObject? {
        val raw = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        return normalise(raw)
    }

    /**
     * Normalise a parsed JSON object to AIP/1.0 field names.
     *
     * The original [raw] object is returned unmodified when it is already in
     * AIP/1.0 format.
     */
    fun normalise(raw: JSONObject): JSONObject {
        // Already AIP/1.0 (has "protocol" = "AIP/1.0" or at least a "type" key)
        if (raw.optString("protocol") == PROTOCOL_AIP1 || raw.has("type")) {
            return raw
        }

        // Microsoft Galaxy format: message_type / agent_id keys
        if (raw.has("message_type")) {
            return JSONObject().apply {
                put("protocol", PROTOCOL_AIP1)
                put("type", raw.optString("message_type").lowercase())
                put("source_node", raw.optString("agent_id", "Galaxy"))
                put("target_node", "")
                // session_id in Microsoft format is in milliseconds; divide by 1000 to get seconds
                put("timestamp", raw.optLong("session_id", System.currentTimeMillis()) / 1000)
                put("payload", raw.optJSONObject("payload") ?: JSONObject())
            }
        }

        // v3 format: version:"3.0" / msg_type / device_id keys
        if (raw.optString("version") == PROTOCOL_V3) {
            return JSONObject().apply {
                put("protocol", PROTOCOL_AIP1)
                put("type", raw.optString("msg_type", "unknown"))
                put("source_node", raw.optString("device_id", ""))
                put("target_node", "")
                put("timestamp", raw.optLong("timestamp", System.currentTimeMillis() / 1000))
                put("payload", raw.optJSONObject("payload") ?: JSONObject())
            }
        }

        // Unknown format – return as-is so existing callers are not broken
        return raw
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Protocol detection
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Detect which wire-protocol format a raw message string uses.
     *
     * @return One of `"AIP/1.0"`, `"v3"`, `"Microsoft"`, or `"unknown"`.
     */
    fun detectProtocol(text: String): String {
        val raw = try { JSONObject(text) } catch (e: Exception) { return "unknown" }
        return when {
            raw.optString("protocol") == PROTOCOL_AIP1 -> "AIP/1.0"
            raw.optString("version") == PROTOCOL_V3    -> "v3"
            raw.has("message_type")                    -> "Microsoft"
            else                                       -> "unknown"
        }
    }
}

package com.ufo.galaxy.protocol

import org.json.JSONObject
import java.util.UUID

/**
 * Central builder and parser for AIP messages.
 *
 * Every outbound message should pass through [build] (or a typed convenience
 * function such as [buildCapabilityReport]) so that v3 envelope fields are set
 * in a single, consistent place.
 *
 * Every inbound message should pass through [parse] (or [normalise]) so that
 * callers always see AIP/1.0 field names regardless of which wire format the
 * sender used (AIP/1.0 native, Microsoft Galaxy, or v3).
 *
 * ## v3 enforcement
 * All outbound messages produced by [build] **always** include the full v3
 * envelope: `protocol`, `version`, `device_id`, `device_type`, `message_id`,
 * `source_node`, `target_node`, `timestamp`, `trace_id`, and `route_mode`.
 * There is no opt-out for outbound messages.
 */
object AIPMessageBuilder {

    /** AIP/1.0 protocol identifier included in every outbound message. */
    const val PROTOCOL_AIP1 = "AIP/1.0"

    /** v3 protocol version string. */
    const val PROTOCOL_V3 = "3.0"

    /** Route mode value for local-only execution (cross-device switch OFF). */
    const val ROUTE_MODE_LOCAL = "local"

    /** Route mode value for cross-device execution (cross-device switch ON). */
    const val ROUTE_MODE_CROSS_DEVICE = "cross_device"

    /**
     * Generate a fresh trace identifier for a new session or connection.
     *
     * Callers should generate one trace ID per WS session and reuse it across
     * all messages sent within that session.
     */
    fun generateTraceId(): String = UUID.randomUUID().toString()

    // ──────────────────────────────────────────────────────────────────────────
    // v3 message type constants (authoritative names expected by AndroidBridge)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * AIP v3 message type names as expected by the server AndroidBridge.
     *
     * All new outbound messages should use these constants so that the wire
     * `type` field matches the server's routing logic exactly.
     *
     * Legacy callers that previously used `"registration"` or `"register"` must
     * migrate to [DEVICE_REGISTER].  [LEGACY_TYPE_MAP] documents the mapping
     * for reference and is used by [toLegacyType] / [toV3Type] utilities.
     */
    object MessageType {
        /** Device registers itself with the AndroidBridge. */
        const val DEVICE_REGISTER = "device_register"

        /** Periodic keep-alive sent to the server. */
        const val HEARTBEAT = "heartbeat"

        /**
         * Sent immediately after a successful registration ACK to report the
         * device's full capability set.  Payload must contain `platform`,
         * `supported_actions`, and `version`.
         */
        const val CAPABILITY_REPORT = "capability_report"

        /** Server assigns a task or command to this device. */
        const val TASK_ASSIGN = "task_assign"

        /** Device reports the result of an executed command or task. */
        const val COMMAND_RESULT = "command_result"
    }

    /**
     * Mapping from legacy outbound type names → authoritative v3 names.
     *
     * This map is provided for reference and migration tooling only.  New
     * code must use [MessageType] constants directly rather than legacy strings.
     */
    val LEGACY_TYPE_MAP: Map<String, String> = mapOf(
        "registration" to MessageType.DEVICE_REGISTER,
        "register"     to MessageType.DEVICE_REGISTER,
        "heartbeat"    to MessageType.HEARTBEAT,
        "command"      to MessageType.TASK_ASSIGN,
        "command_result" to MessageType.COMMAND_RESULT
    )

    /**
     * Convert a legacy type string to its authoritative v3 equivalent.
     *
     * Returns the input unchanged when it is already a v3 name or unknown.
     */
    fun toV3Type(legacyType: String): String = LEGACY_TYPE_MAP[legacyType] ?: legacyType

    // ──────────────────────────────────────────────────────────────────────────
    // Outbound message building
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Build an outbound AIP message with a mandatory v3 envelope.
     *
     * The resulting [JSONObject] always contains every top-level required field:
     * `protocol`, `version`, `device_id`, `device_type`, `message_id`,
     * `source_node`, `target_node`, `timestamp`, `trace_id`, `route_mode`,
     * and `payload`.
     *
     * @param messageType   v3 `type` value – prefer [MessageType] constants
     *                      (e.g. [MessageType.DEVICE_REGISTER], [MessageType.HEARTBEAT]).
     * @param sourceNodeId  Sending device / node identifier.
     * @param targetNodeId  Destination node identifier.
     * @param payload       Arbitrary payload [JSONObject].
     * @param deviceType    Optional device-type hint (default `"Android_Agent"`).
     * @param messageId     Unique message identifier; auto-generated if omitted.
     * @param traceId       Trace identifier for the current session; auto-generated
     *                      if omitted.  Callers should reuse the same trace ID for
     *                      every message in a single WS session – use
     *                      [generateTraceId] once at session start and pass the
     *                      result here.
     * @param routeMode     Routing mode for this message.  Use [ROUTE_MODE_LOCAL]
     *                      when the cross-device switch is OFF, or
     *                      [ROUTE_MODE_CROSS_DEVICE] when it is ON.
     */
    fun build(
        messageType: String,
        sourceNodeId: String,
        targetNodeId: String,
        payload: JSONObject,
        deviceType: String = "Android_Agent",
        messageId: String = UUID.randomUUID().toString().take(8),
        traceId: String = generateTraceId(),
        routeMode: String = ROUTE_MODE_LOCAL
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

            // ── v3 required fields (always present) ───────────────────────────
            put("version", PROTOCOL_V3)
            put("device_id", sourceNodeId)
            put("device_type", deviceType)
            put("trace_id", traceId)
            put("route_mode", routeMode)
        }
    }

    /**
     * Build a `capability_report` outbound message with payload validation.
     *
     * This is a convenience wrapper around [build] that enforces the required
     * `capability_report` payload contract before constructing the envelope.
     * An [IllegalArgumentException] is thrown when any required payload field
     * (`platform`, `supported_actions`, `version`) is absent or blank, so that
     * an invalid message is never sent.
     *
     * @param sourceNodeId     Sending device / node identifier.
     * @param targetNodeId     Destination node identifier.
     * @param payload          Payload [JSONObject] that **must** contain:
     *                         - `platform`          – non-blank platform string (e.g. `"android"`).
     *                         - `supported_actions` – [org.json.JSONArray] of action names.
     *                         - `version`           – non-blank version string (e.g. `"2.5.0"`).
     * @param deviceType       Optional device-type hint (default `"Android_Agent"`).
     * @param messageId        Unique message identifier; auto-generated if omitted.
     * @param traceId          Trace identifier; auto-generated if omitted.
     * @param routeMode        Routing mode; defaults to [ROUTE_MODE_LOCAL].
     * @throws IllegalArgumentException if `platform`, `supported_actions`, or
     *                                  `version` are missing from [payload].
     */
    fun buildCapabilityReport(
        sourceNodeId: String,
        targetNodeId: String,
        payload: JSONObject,
        deviceType: String = "Android_Agent",
        messageId: String = UUID.randomUUID().toString().take(8),
        traceId: String = generateTraceId(),
        routeMode: String = ROUTE_MODE_LOCAL
    ): JSONObject {
        require(payload.optString("platform").isNotBlank()) {
            "capability_report payload must include a non-blank 'platform' field"
        }
        require(payload.has("supported_actions")) {
            "capability_report payload must include a 'supported_actions' field"
        }
        require(payload.optString("version").isNotBlank()) {
            "capability_report payload must include a non-blank 'version' field"
        }
        return build(
            messageType  = MessageType.CAPABILITY_REPORT,
            sourceNodeId = sourceNodeId,
            targetNodeId = targetNodeId,
            payload      = payload,
            deviceType   = deviceType,
            messageId    = messageId,
            traceId      = traceId,
            routeMode    = routeMode
        )
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

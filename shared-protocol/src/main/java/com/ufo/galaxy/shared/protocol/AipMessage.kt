package com.ufo.galaxy.shared.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Unified AIP v3 message envelope shared across Android and Wear OS.
 *
 * This is the canonical wire-format data class that both platforms use for
 * all cross-device communication.  It merges the Android-side
 * [com.ufo.galaxy.protocol.AipMessage] and Wear-OS-side
 * [com.galaxy.wear.data.AIPMessage] into a single source of truth.
 *
 * Field names use @SerialName with snake_case to match the server-side
 * AipMessage structure exactly.
 *
 * ## Merge rules applied
 * - All fields from the Android AipMessage are retained (it is the superset).
 * - Wear-OS-specific convenience accessors (payloadObject, token, id, command,
 *   success, event, msgType) are added as extension properties.
 * - kotlinx.serialization is the **primary** serializer; Gson interop is
 *   available via the [toGsonJson] / [fromGsonJson] helpers when needed.
 *
 * @param type           Message type identifier (canonical [MsgType] enum).
 * @param payload        Typed payload as [JsonElement]; default [JsonNull].
 * @param correlationId  Request/response correlation identifier.
 * @param protocol       Wire-protocol identifier; always `"AIP/1.0"` for AIP v3.
 * @param version        Protocol version; always `"3.0"`.
 * @param timestamp      Unix epoch millis auto-set at construction.
 * @param deviceId       Device identifier for origin tracking.
 * @param traceId        End-to-end trace identifier propagated across all hops.
 * @param sessionId      Optional session identifier.
 * @param routeMode      Routing path: `"local"` or `"cross_device"`.
 * @param runtimeSessionId  Stable per-app-launch session identifier.
 * @param idempotencyKey    Per-send unique key for safe deduplication.
 * @param sourceRuntimePosture  Device participation posture (`"control_only"` or `"join_runtime"`).
 * @param dispatchTraceId   Optional cross-system dispatch trace correlation identifier (PR-G).
 * @param sessionCorrelationId  Optional session-level correlation identifier (PR-G).
 */
@Serializable
data class AipMessage(
    val type: MsgType,
    val payload: JsonElement = JsonNull,
    @SerialName("correlation_id") val correlationId: String = "",
    val protocol: String = "AIP/1.0",
    val version: String = "3.0",
    val timestamp: Long = System.currentTimeMillis(),
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("trace_id") val traceId: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("route_mode") val routeMode: String? = null,
    @SerialName("runtime_session_id") val runtimeSessionId: String? = null,
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
    @SerialName("source_runtime_posture") val sourceRuntimePosture: String? = null,
    // ── PR-G: V2 observability/tracing metadata (optional; null-safe for legacy senders) ──
    @SerialName("dispatch_trace_id") val dispatchTraceId: String? = null,
    @SerialName("session_correlation_id") val sessionCorrelationId: String? = null
) {
    /**
     * Convenience accessor for payload as [JsonObject].
     * Returns empty JsonObject if payload is not a JsonObject.
     */
    val payloadObject: JsonObject
        get() = payload as? JsonObject ?: JsonObject(emptyMap())

    /** Backward-compatible payload field accessors for migration from sealed interface pattern. */
    val token: String
        get() = payloadObject["token"]?.toString()?.trim('"') ?: ""

    val id: Int
        get() = payloadObject["id"]?.toString()?.toIntOrNull() ?: 0

    val command: String
        get() = payloadObject["command"]?.toString()?.trim('"') ?: ""

    val success: Boolean
        get() = payloadObject["success"].let { it != null && it.toString() == "true" }

    val event: String
        get() = payloadObject["event"]?.toString()?.trim('"') ?: ""

    val msgType: String
        get() = payloadObject["msg_type"]?.toString()?.trim('"') ?: ""

    companion object {
        /** Canonical JSON serializer for AipMessage. */
        val DefaultJson = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        /** Deserialize from a JSON string using kotlinx.serialization. */
        fun fromJson(json: String): AipMessage =
            DefaultJson.decodeFromString(serializer(), json)

        /** Serialize to a JSON string using kotlinx.serialization. */
        fun toJson(msg: AipMessage): String =
            DefaultJson.encodeToString(serializer(), msg)
    }
}

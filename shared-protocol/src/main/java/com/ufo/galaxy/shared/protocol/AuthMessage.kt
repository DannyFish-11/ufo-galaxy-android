package com.ufo.galaxy.shared.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Unified WebSocket authentication message shared across Android and Wear OS.
 *
 * Both platforms now use the **same** post-connect auth message for authentication
 * instead of relying solely on HTTP headers (Android used Bearer token in headers,
 * Wear OS sent an inline auth payload).  The WebSocket connection is established
 * first, then this message is sent as the first envelope to authenticate the device.
 *
 * ## Authentication flow (unified)
 * 1. Open WebSocket connection (no Authorization header needed — or it may be
 *    sent as a compatibility fallback for legacy gateways).
 * 2. Immediately send [AuthMessage] as the first text frame after onOpen.
 * 3. Wait for `auth_ok`, `auth_failed`, or `auth_invalid` response.
 * 4. On `auth_ok`: mark connection as AUTHENTICATED and start heartbeat.
 *
 * @param type             Message type; always `"auth"`.
 * @param token            Authentication token (JWT or opaque bearer token).
 * @param deviceId         Stable unique device identifier (UUID v4, persisted).
 * @param deviceType       Platform discriminator: `"android"` for phone/tablet,
 *                         `"wearos"` for Wear OS watches.
 * @param protocolVersion  AIP protocol version; always `"3.0"`.
 * @param protocol         Wire-protocol identifier; always `"AIP/1.0"`.
 */
@Serializable
data class AuthMessage(
    val type: String = "auth",
    val token: String = "",
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_type") val deviceType: String,
    @SerialName("protocol_version") val protocolVersion: String = "3.0",
    val protocol: String = "AIP/1.0"
) {
    companion object {
        /** Device type value for Android phones / tablets. */
        const val DEVICE_TYPE_ANDROID = "android"

        /** Device type value for Wear OS watches. */
        const val DEVICE_TYPE_WEAROS = "wearos"
    }

    /**
     * Validate that the authentication message contains required non-empty fields.
     * Returns true if both token and deviceId are non-blank.
     */
    fun validate(): Boolean {
        return token.isNotBlank() && deviceId.isNotBlank()
    }

    /**
     * Convert this AuthMessage to a canonical [AipMessage] envelope for sending
     * through the unified transport layer.
     */
    fun toAipMessage(): AipMessage {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("token", token)
            put("device_type", deviceType)
            put("protocol_version", protocolVersion)
        }
        return AipMessage(
            type = MsgType.AUTH,
            payload = payload,
            deviceId = deviceId,
            traceId = "auth_${System.currentTimeMillis()}"
        )
    }
}

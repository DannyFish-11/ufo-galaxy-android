package com.ufo.lumiv.protocol

import com.ufo.lumiv.shared.protocol.AuthMessage

fun AuthMessage.toAipMessage(): AipMessage {
    return AipMessage(
        type = MsgType.AUTH,
        payload = AuthPayloadAdapter.createPayload(this),
        device_id = deviceId,
        trace_id = "auth_${System.currentTimeMillis()}"
    )
}

/**
 * Internal helper to build a Gson JsonElement payload from AuthMessage fields.
 * Kept in the same file so the extension function stays self-contained.
 */
private object AuthPayloadAdapter {
    fun createPayload(auth: AuthMessage): com.google.gson.JsonElement {
        return com.google.gson.JsonObject().apply {
            addProperty("token", auth.token)
            addProperty("device_type", auth.deviceType)
            addProperty("protocol_version", auth.protocolVersion)
        }
    }
}

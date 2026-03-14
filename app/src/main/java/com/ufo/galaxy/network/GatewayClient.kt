package com.ufo.galaxy.network

/**
 * Minimal interface for the AIP v3 gateway transport.
 *
 * Extracting this two-method slice from [GalaxyWebSocketClient] lets [com.ufo.galaxy.input.InputRouter]
 * be tested without a live network connection: tests inject a lightweight fake that can be
 * configured to appear "connected" and to record or accept outgoing JSON messages.
 *
 * [GalaxyWebSocketClient] implements this interface; callers that previously passed a concrete
 * [GalaxyWebSocketClient] to [com.ufo.galaxy.input.InputRouter] continue to compile unchanged.
 */
interface GatewayClient {

    /**
     * Returns `true` when the transport layer has an active connection to the Galaxy Gateway
     * and is ready to send messages.
     */
    fun isConnected(): Boolean

    /**
     * Sends [json] to the Galaxy Gateway.
     *
     * @return `true` if the message was successfully dispatched; `false` if the connection is
     *         unavailable or the send failed. Callers must surface a send failure to the user.
     */
    fun sendJson(json: String): Boolean
}

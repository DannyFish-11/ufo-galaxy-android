package com.ufo.galaxy.transport

import android.util.Log
import com.ufo.galaxy.network.GatewayClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * AIP v3 Unified Transport Manager for Android.
 *
 * Manages multiple transport adapters (WebSocket, MQTT, BLE) and routes
 * messages according to the transport field in AIP v3 messages.
 *
 * Architecture:
 *   App → AipTransportManager.sendJson()
 *            │
 *            ├── message["transport"] == "websocket" → WebSocketAdapter
 *            ├── message["transport"] == "mqtt"     → MQTTAdapter
 *            ├── message["transport"] == "ble"      → BLEAdapter
 *            └── default                              → WebSocketAdapter (fallback)
 *
 * PR-AIP-UNIFIED-ANDROID: Provides the same unified transport entry as V2's
 * AIPTransport, enabling multi-protocol hybrid transmission on Android.
 */
class AipTransportManager private constructor() : GatewayClient {

    companion object {
        private const val TAG = "AipTransportManager"

        @Volatile
        private var instance: AipTransportManager? = null

        fun getInstance(): AipTransportManager {
            return instance ?: synchronized(this) {
                instance ?: AipTransportManager().also { instance = it }
            }
        }

        fun resetInstance() {
            instance = null
        }
    }

    /** Registered transport adapters: transport_type → GatewayClient */
    private val adapters = ConcurrentHashMap<String, GatewayClient>()

    /** Default transport when message has no transport field or specified transport unavailable */
    private var defaultTransport: String = "websocket"

    /**
     * Register a transport adapter.
     */
    fun registerAdapter(transportType: String, client: GatewayClient) {
        adapters[transportType] = client
        Log.i(TAG, "Transport adapter registered: $transportType")
    }

    /**
     * Unregister a transport adapter.
     */
    fun unregisterAdapter(transportType: String) {
        adapters.remove(transportType)
        Log.i(TAG, "Transport adapter unregistered: $transportType")
    }

    /**
     * List registered transport types.
     */
    fun listAdapters(): List<String> = adapters.keys.toList()

    /**
     * Set default transport type for fallback.
     */
    fun setDefaultTransport(transportType: String) {
        defaultTransport = transportType
    }

    /**
     * Send AIP v3 message via the appropriate transport.
     *
     * Reads transport field from JSON message, falls back to default if
     * specified transport is not available.
     *
     * @param json AIP v3 message JSON string (must contain "transport" field)
     * @return true if send succeeded
     */
    override fun sendJson(json: String): Boolean {
        return try {
            val message = JSONObject(json)
            // PR-AIP-UNIFIED: Auto-inject transport field if missing (backward compat)
            val transport: String
            val jsonToSend: String
            if (message.has("transport")) {
                transport = message.getString("transport")
                jsonToSend = json
            } else {
                transport = defaultTransport
                message.put("transport", transport)
                jsonToSend = message.toString()
            }
            val target = message.optString("target", "")

            // Try specified transport
            val adapter = adapters[transport]
            if (adapter != null && adapter.isConnected()) {
                Log.d(TAG, "Sending via $transport to $target")
                return adapter.sendJson(jsonToSend)
            }

            // Fallback to default transport
            Log.d(TAG, "Transport '$transport' not available, fallback to $defaultTransport")
            val fallbackAdapter = adapters[defaultTransport]
            if (fallbackAdapter != null && fallbackAdapter.isConnected()) {
                return fallbackAdapter.sendJson(jsonToSend)
            }

            Log.w(TAG, "No available transport (tried: $transport, $defaultTransport)")
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            // ROUND-2-FIX: Re-throw CancellationException so callers can respond to
            // coroutine cancellation correctly instead of silently swallowing it.
            throw e
        } catch (e: org.json.JSONException) {
            // ROUND-3-FIX: If JSON parsing fails, the input is structurally invalid.
            // Do NOT fall back to sending the malformed payload — log the error and
            // return false so the caller knows the send was rejected.
            Log.e(TAG, "Route failed: JSON parse error — refusing to send malformed payload: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Route failed: ${e.message}")
            // ROUND-3-FIX: Only fall back to websocket for non-JSON errors;
            // the fallback payload must be well-formed JSON.
            adapters["websocket"]?.sendJson(json) ?: false
        }
    }

    /**
     * Check if any transport is connected.
     */
    override fun isConnected(): Boolean {
        return adapters.values.any { it.isConnected() }
    }

    /**
     * Check if specific transport is connected.
     */
    fun isTransportConnected(transportType: String): Boolean {
        return adapters[transportType]?.isConnected() ?: false
    }

    /**
     * Disconnect all transports.
     */
    fun disconnectAll() {
        adapters.values.forEach { client ->
            if (client is AutoCloseable) {
                try {
                    client.close()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // ROUND-2-FIX: Re-throw CancellationException so callers can respond
                    // to coroutine cancellation correctly instead of silently swallowing it.
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "disconnectAll: adapter close failed: ${e.message}")
                }
            }
        }
    }
}

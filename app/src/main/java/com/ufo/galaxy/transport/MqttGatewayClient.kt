package com.ufo.galaxy.transport

import android.content.Context
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.ufo.galaxy.network.GatewayClient
import java.util.UUID

/**
 * MQTT Transport Adapter for Android — AIP v3 over MQTT.
 *
 * Enables Android devices to communicate with Galaxy Gateway via MQTT
 * broker when WebSocket is unavailable (e.g., poor network, firewall).
 *
 * Features:
 * - Auto-reconnect with exponential backoff
 * - QoS 1 for reliable message delivery
 * - Topic pattern: galaxy/aip/v3/{device_id}
 * - Last Will and Testament for device offline detection
 *
 * Usage:
 *   val mqtt = MqttGatewayClient(context, brokerHost="mqtt.example.com")
 *   mqtt.connect()
 *   mqtt.sendJson("{\"type\":\"device.ping\",...}")
 */
class MqttGatewayClient(
    private val context: Context,
    private val brokerHost: String,
    private val brokerPort: Int = 8883,
    private val deviceId: String = "",
    private val username: String = "",
    private val password: String = "",
    private val useTls: Boolean = true,
) : GatewayClient, AutoCloseable {

    companion object {
        private const val TAG = "MqttGatewayClient"
        private const val TOPIC_PREFIX = "galaxy/aip/v3"
        private const val RECONNECT_BASE_DELAY_MS = 1000L
        private const val RECONNECT_MAX_DELAY_MS = 30000L
    }

    @Volatile
    private var client: Mqtt3AsyncClient? = null

    @Volatile
    private var connected = false

    @Volatile
    private var reconnectAttempt = 0

    // LOW-FIX: Optional message callback for incoming MQTT payloads.
    // Set by upstream code to receive JSON messages from the MQTT transport.
    var onMessageReceived: ((String) -> Unit)? = null

    private val pendingMessages = java.util.Collections.synchronizedList(mutableListOf<String>())

    // ROUND-2-FIX: Saved Runnable reference for cancellable reconnect postDelayed.
    private var reconnectRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Connect to MQTT broker.
     */
    fun connect() {
        try {
            val clientId = deviceId.ifEmpty { "galaxy_android_${UUID.randomUUID().toString().take(8)}" }

            val builder = MqttClient.builder()
                .useMqttVersion3()
                .identifier(clientId)
                .serverHost(brokerHost)
                .serverPort(brokerPort)

            if (useTls) {
                builder.sslWithDefaultConfig()
            }

            val mqttClient = builder.buildAsync()

            mqttClient.connectWith()
                .cleanSession(false)
                .keepAlive(60)
                .apply {
                    if (username.isNotEmpty()) {
                        simpleAuth(username, password)
                    }
                }
                .willPublish()
                    .topic("$TOPIC_PREFIX/$deviceId/lwt")
                    .payload("{\"status\":\"offline\",\"device_id\":\"$deviceId\"}".toByteArray())
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .retain(true)
                    .applyWill()
                .send()
                .whenComplete { _, throwable ->
                    if (throwable == null) {
                        Log.i(TAG, "MQTT connected to $brokerHost:$brokerPort")
                        connected = true
                        reconnectAttempt = 0
                        subscribeToTopics(mqttClient)
                        flushPendingMessages(mqttClient)
                    } else {
                        Log.e(TAG, "MQTT connection failed: ${throwable.message}")
                        scheduleReconnect()
                    }
                }

            client = mqttClient
        } catch (e: Exception) {
            Log.e(TAG, "MQTT connect error: ${e.message}")
            scheduleReconnect()
        }
    }

    /**
     * Disconnect from MQTT broker.
     */
    fun disconnect() {
        // ROUND-2-FIX: Cancel any pending reconnect task before disconnecting.
        reconnectRunnable?.let { handler.removeCallbacks(it); reconnectRunnable = null }
        try {
            client?.disconnect()?.whenComplete { _, _ ->
                Log.i(TAG, "MQTT disconnected")
                connected = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "MQTT disconnect error: ${e.message}")
        }
    }

    override fun isConnected(): Boolean = connected

    /**
     * Publish AIP v3 message to MQTT topic.
     */
    override fun sendJson(json: String): Boolean {
        if (!connected) {
            // Queue message for later delivery
            synchronized(pendingMessages) {
                if (pendingMessages.size < 100) {
                    pendingMessages.add(json)
                    Log.d(TAG, "Message queued (offline), queue size: ${pendingMessages.size}")
                }
            }
            return false
        }

        return try {
            val targetTopic = "$TOPIC_PREFIX/$deviceId/in"
            client?.publishWith()
                ?.topic(targetTopic)
                ?.payload(json.toByteArray())
                ?.qos(MqttQos.AT_LEAST_ONCE)
                ?.send()
                ?.whenComplete { _, throwable ->
                    if (throwable != null) {
                        Log.w(TAG, "MQTT publish failed: ${throwable.message}")
                    }
                }
            true
        } catch (e: Exception) {
            Log.e(TAG, "MQTT send failed: ${e.message}")
            false
        }
    }

    override fun close() {
        // ROUND-2-FIX: Cancel pending reconnect before cleanup.
        reconnectRunnable?.let { handler.removeCallbacks(it); reconnectRunnable = null }
        disconnect()
        client = null
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun subscribeToTopics(mqttClient: Mqtt3AsyncClient) {
        val topics = listOf(
            "$TOPIC_PREFIX/$deviceId/out",   // Commands from gateway
            "$TOPIC_PREFIX/broadcast",        // Broadcast messages
        )
        topics.forEach { topic ->
            mqttClient.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback { publish ->
                    val payload = String(publish.payloadAsBytes)
                    Log.d(TAG, "Received on $topic: $payload")
                    // LOW-FIX: Route received MQTT message to registered handler.
                    // Messages are delivered as-is (JSON string) for upstream parsing.
                    onMessageReceived?.invoke(payload)
                        ?: Log.w(TAG, "No message handler registered — MQTT message dropped")
                }
                .send()
        }
    }

    private fun flushPendingMessages(mqttClient: Mqtt3AsyncClient) {
        synchronized(pendingMessages) {
            val messages = pendingMessages.toList()
            pendingMessages.clear()
            messages.forEach { msg ->
                try {
                    mqttClient.publishWith()
                        .topic("$TOPIC_PREFIX/$deviceId/in")
                        .payload(msg.toByteArray())
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .send()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to flush pending message: ${e.message}")
                }
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectAttempt++
        val delay = (RECONNECT_BASE_DELAY_MS * (1 shl (reconnectAttempt - 1).coerceAtMost(5)))
            .coerceAtMost(RECONNECT_MAX_DELAY_MS)
        Log.d(TAG, "Reconnect scheduled in ${delay}ms (attempt: $reconnectAttempt)")

        reconnectRunnable?.let { handler.removeCallbacks(it) }
        val reconnRunnable = Runnable { connect() }
        reconnectRunnable = reconnRunnable
        handler.postDelayed(reconnRunnable, delay)
    }
}

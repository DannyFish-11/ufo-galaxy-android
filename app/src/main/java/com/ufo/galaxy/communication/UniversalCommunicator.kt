package com.ufo.galaxy.communication

import android.util.Log
import com.ufo.galaxy.protocol.AIPMessageV3
import com.ufo.galaxy.protocol.MessageType
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * UFO Galaxy - Universal Node Communication System (Android)
 *
 * Provides bidirectional communication between ANY nodes:
 * - Android nodes ↔ Server nodes
 * - Android nodes ↔ Android nodes
 * - Any node → Self (self-activation)
 *
 * This is the Android counterpart to Python's node_communication.py
 */

private const val TAG = "UniversalCommunicator"

/**
 * Helper function to convert JSONObject to Map
 */
private fun jsonObjectToMap(json: JSONObject): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (key !in setOf("message_id", "source_id", "target_id", "priority")) {
            map[key] = json.get(key)
        }
    }
    return map
}

/**
 * Node identity information
 */
data class NodeIdentity(
    val nodeId: String,
    val nodeType: NodeType,
    val nodeName: String,
    val host: String = "localhost",
    val port: Int = 0,
    val capabilities: List<String> = emptyList(),
    val metadata: Map<String, Any> = emptyMap()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("node_id", nodeId)
            put("node_type", nodeType.value)
            put("node_name", nodeName)
            put("host", host)
            put("port", port)
            put("capabilities", capabilities)
            put("metadata", JSONObject(metadata))
        }
    }
}

/**
 * Node types
 */
enum class NodeType(val value: String) {
    SERVER("server"),
    ANDROID("android"),
    IOS("ios"),
    WEB("web"),
    EMBEDDED("embedded"),
    CLOUD("cloud")
}

/**
 * Universal message format
 */
data class UniversalMessage(
    val messageType: MessageType,
    val sourceId: String,
    val targetId: String,  // "*" for broadcast, "self" for self
    val payload: Map<String, Any> = emptyMap(),
    val messageId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val priority: Int = 5  // 1-10, lower = higher priority
) {
    fun toAIPMessage(): AIPMessageV3 {
        val payloadJson = JSONObject()
        payload.forEach { (key, value) ->
            payloadJson.put(key, value)
        }
        payloadJson.put("message_id", messageId)
        payloadJson.put("source_id", sourceId)
        payloadJson.put("target_id", targetId)
        payloadJson.put("priority", priority)
        
        return AIPMessageV3(
            type = messageType,
            payload = payloadJson,
            deviceId = sourceId
        )
    }

    companion object {
        fun fromAIPMessage(message: AIPMessageV3): UniversalMessage {
            val payload = message.payload
            return UniversalMessage(
                messageType = message.type,
                sourceId = payload.optString("source_id", message.deviceId),
                targetId = payload.optString("target_id", "*"),
                payload = jsonObjectToMap(payload),
                messageId = payload.optString("message_id", UUID.randomUUID().toString()),
                timestamp = message.timestamp,
                priority = payload.optInt("priority", 5)
            )
        }
    }
}

/**
 * Central node registry for tracking all nodes
 */
class NodeRegistry {
    private val nodes = ConcurrentHashMap<String, NodeIdentity>()
    private val handlers = ConcurrentHashMap<String, suspend (UniversalMessage) -> Unit>()
    private val subscribers = ConcurrentHashMap<String, MutableSet<String>>()
    private val lock = Mutex()

    suspend fun registerNode(node: NodeIdentity, handler: (suspend (UniversalMessage) -> Unit)? = null) {
        lock.withLock {
            nodes[node.nodeId] = node
            handler?.let { handlers[node.nodeId] = it }
        }
        Log.i(TAG, "Node registered: ${node.nodeId} (${node.nodeName})")
    }

    suspend fun unregisterNode(nodeId: String) {
        lock.withLock {
            nodes.remove(nodeId)
            handlers.remove(nodeId)
        }
        Log.i(TAG, "Node unregistered: $nodeId")
    }

    fun getNode(nodeId: String): NodeIdentity? = nodes[nodeId]

    fun getAllNodes(): List<NodeIdentity> = nodes.values.toList()

    fun getNodesByType(nodeType: NodeType): List<NodeIdentity> =
        nodes.values.filter { it.nodeType == nodeType }

    fun getHandler(nodeId: String): (suspend (UniversalMessage) -> Unit)? = handlers[nodeId]

    suspend fun subscribe(nodeId: String, eventType: String) {
        lock.withLock {
            subscribers.getOrPut(eventType) { mutableSetOf() }.add(nodeId)
        }
    }

    suspend fun unsubscribe(nodeId: String, eventType: String) {
        lock.withLock {
            subscribers[eventType]?.remove(nodeId)
        }
    }

    fun getSubscribers(eventType: String): Set<String> =
        subscribers[eventType] ?: emptySet()
}

/**
 * Universal Communicator for ANY node-to-node communication
 */
class UniversalCommunicator(
    private val registry: NodeRegistry,
    private val webSocketClient: com.ufo.galaxy.network.WebSocketClient
) {
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<Map<String, Any>?>>()
    private val eventListeners = ConcurrentHashMap<String, MutableList<suspend (Map<String, Any>) -> Unit>>()

    // ========================================================================
    // Public API - Send Messages
    // ========================================================================

    /**
     * Send message to a specific node
     */
    suspend fun sendToNode(
        sourceId: String,
        targetId: String,
        messageType: MessageType,
        payload: Map<String, Any> = emptyMap(),
        waitResponse: Boolean = false,
        timeout: Long = 30000,
        priority: Int = 5
    ): Map<String, Any>? {
        var actualTargetId = targetId

        // Handle self-targeting
        if (targetId == "self") {
            actualTargetId = sourceId
        }

        // Create message
        val message = UniversalMessage(
            messageType = messageType,
            sourceId = sourceId,
            targetId = actualTargetId,
            payload = payload,
            priority = priority
        )

        // Handle broadcast
        if (actualTargetId == "*") {
            return broadcast(message)
        }

        // Check if target is local
        val targetNode = registry.getNode(actualTargetId)

        return if (targetNode?.nodeType == NodeType.ANDROID && targetNode.nodeId == sourceId) {
            // Self-activation or local Android node
            handleLocalMessage(message)
        } else {
            // Send via WebSocket to server
            sendViaWebSocket(message, waitResponse, timeout)
        }
    }

    /**
     * Send message to server node
     */
    suspend fun sendToServer(
        sourceId: String,
        serverNodeId: String,
        messageType: MessageType,
        payload: Map<String, Any> = emptyMap(),
        waitResponse: Boolean = false,
        timeout: Long = 30000
    ): Map<String, Any>? {
        return sendToNode(
            sourceId = sourceId,
            targetId = serverNodeId,
            messageType = messageType,
            payload = payload,
            waitResponse = waitResponse,
            timeout = timeout
        )
    }

    /**
     * Broadcast message to all nodes
     */
    suspend fun broadcast(
        sourceId: String,
        messageType: MessageType,
        payload: Map<String, Any> = emptyMap(),
        nodeTypes: List<NodeType>? = null
    ): Map<String, Any> {
        val message = UniversalMessage(
            messageType = messageType,
            sourceId = sourceId,
            targetId = "*",
            payload = payload
        )
        return broadcast(message, nodeTypes)
    }

    /**
     * Activate self (node controls itself)
     */
    suspend fun activateSelf(
        nodeId: String,
        action: String,
        params: Map<String, Any> = emptyMap()
    ): Map<String, Any>? {
        return sendToNode(
            sourceId = nodeId,
            targetId = nodeId,  // Self-targeting
            messageType = MessageType.NODE_ACTIVATE,
            payload = mapOf("action" to action, "params" to params),
            waitResponse = true
        )
    }

    /**
     * Wake up a node
     */
    suspend fun wakeupNode(
        sourceId: String,
        targetId: String,
        reason: String = "",
        params: Map<String, Any> = emptyMap()
    ): Map<String, Any>? {
        return sendToNode(
            sourceId = sourceId,
            targetId = targetId,
            messageType = MessageType.NODE_WAKEUP,
            payload = mapOf("reason" to reason, "params" to params),
            waitResponse = true
        )
    }

    /**
     * Execute command on target node
     */
    suspend fun executeCommand(
        sourceId: String,
        targetId: String,
        command: String,
        args: List<Any> = emptyList(),
        kwargs: Map<String, Any> = emptyMap(),
        timeout: Long = 30000
    ): Map<String, Any>? {
        return sendToNode(
            sourceId = sourceId,
            targetId = targetId,
            messageType = MessageType.COMMAND,
            payload = mapOf(
                "command" to command,
                "args" to args,
                "kwargs" to kwargs
            ),
            waitResponse = true,
            timeout = timeout
        )
    }

    // ========================================================================
    // Event System
    // ========================================================================

    fun onEvent(eventType: String, handler: suspend (Map<String, Any>) -> Unit) {
        eventListeners.getOrPut(eventType) { mutableListOf() }.add(handler)
    }

    suspend fun publishEvent(
        sourceId: String,
        eventType: String,
        data: Map<String, Any>
    ) {
        // Get subscribers
        val subscribers = registry.getSubscribers(eventType)

        // Send to each subscriber
        subscribers.forEach { nodeId ->
            sendToNode(
                sourceId = sourceId,
                targetId = nodeId,
                messageType = MessageType.EVENT_BROADCAST,
                payload = mapOf("event_type" to eventType, "data" to data)
            )
        }

        // Call local listeners
        eventListeners[eventType]?.forEach { handler ->
            try {
                handler(data)
            } catch (e: Exception) {
                Log.e(TAG, "Event handler error: ${e.message}")
            }
        }
    }

    // ========================================================================
    // Response Handling
    // ========================================================================

    fun sendResponse(messageId: String, response: Map<String, Any>) {
        pendingResponses[messageId]?.complete(response)
        pendingResponses.remove(messageId)
    }

    fun handleIncomingMessage(message: UniversalMessage) {
        // Handle response to pending request
        val originalMessageId = message.payload["original_message_id"] as? String
        if (originalMessageId != null) {
            sendResponse(originalMessageId, message.payload)
            return
        }

        // Handle different message types
        when (message.messageType) {
            MessageType.NODE_WAKEUP -> handleWakeup(message)
            MessageType.NODE_ACTIVATE -> handleActivate(message)
            MessageType.COMMAND -> handleCommand(message)
            MessageType.EVENT_BROADCAST -> handleEventBroadcast(message)
            else -> Log.d(TAG, "Unhandled message type: ${message.messageType}")
        }
    }

    // ========================================================================
    // Internal Methods
    // ========================================================================

    private suspend fun broadcast(
        message: UniversalMessage,
        nodeTypes: List<NodeType>? = null
    ): Map<String, Any> {
        val results = mutableMapOf<String, MutableList<String>>(
            "success" to mutableListOf(),
            "failed" to mutableListOf()
        )

        registry.getAllNodes().forEach { node ->
            // Filter by node type
            if (nodeTypes != null && node.nodeType !in nodeTypes) return@forEach

            // Skip self if broadcasting
            if (node.nodeId == message.sourceId) return@forEach

            try {
                sendViaWebSocket(
                    message.copy(targetId = node.nodeId),
                    waitResponse = false
                )
                results["success"]?.add(node.nodeId)
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast to ${node.nodeId} failed: ${e.message}")
                results["failed"]?.add(node.nodeId)
            }
        }

        return results
    }

    private suspend fun sendViaWebSocket(
        message: UniversalMessage,
        waitResponse: Boolean,
        timeout: Long = 30000
    ): Map<String, Any>? {
        val aipMessage = message.toAIPMessage()

        return if (waitResponse) {
            val deferred = CompletableDeferred<Map<String, Any>?>()
            pendingResponses[message.messageId] = deferred

            webSocketClient.sendMessage(aipMessage.toJSON())

            try {
                withTimeout(timeout) {
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                pendingResponses.remove(message.messageId)
                Log.w(TAG, "Response timeout for message: ${message.messageId}")
                null
            }
        } else {
            webSocketClient.sendMessage(aipMessage.toJSON())
            mapOf("success" to true, "message_id" to message.messageId)
        }
    }

    private suspend fun handleLocalMessage(message: UniversalMessage): Map<String, Any> {
        return when (message.messageType) {
            MessageType.NODE_WAKEUP -> mapOf(
                "success" to true,
                "status" to "awake",
                "node_id" to message.targetId
            )
            MessageType.NODE_ACTIVATE -> {
                val action = message.payload["action"] as? String ?: "unknown"
                mapOf(
                    "success" to true,
                    "action" to action,
                    "node_id" to message.targetId,
                    "result" to "Action '$action' executed locally"
                )
            }
            else -> mapOf("success" to false, "error" to "Unhandled message type for local node")
        }
    }

    private fun handleWakeup(message: UniversalMessage) {
        Log.i(TAG, "Node ${message.targetId} waking up (reason: ${message.payload["reason"]})")
        // Trigger local wakeup logic
    }

    private fun handleActivate(message: UniversalMessage) {
        val action = message.payload["action"] as? String
        val params = message.payload["params"] as? Map<String, Any>
        Log.i(TAG, "Node ${message.targetId} activating action: $action")
        // Trigger local activation logic
    }

    private fun handleCommand(message: UniversalMessage) {
        val command = message.payload["command"] as? String
        val args = message.payload["args"] as? List<Any>
        val kwargs = message.payload["kwargs"] as? Map<String, Any>
        Log.i(TAG, "Executing command '$command' on ${message.targetId}")
        // Trigger local command execution
    }

    private fun handleEventBroadcast(message: UniversalMessage) {
        val eventType = message.payload["event_type"] as? String
        val data = message.payload["data"] as? Map<String, Any>
        Log.d(TAG, "Event '$eventType' received from ${message.sourceId}")
        // Trigger local event handling
    }
}

// =============================================================================
// Global Instance
// =============================================================================

object UniversalCommunicationManager {
    val nodeRegistry = NodeRegistry()
    lateinit var communicator: UniversalCommunicator

    fun initialize(webSocketClient: com.ufo.galaxy.network.WebSocketClient) {
        communicator = UniversalCommunicator(nodeRegistry, webSocketClient)
    }
}

// =============================================================================
// Convenience Functions
// =============================================================================

suspend fun wakeupNode(
    sourceId: String,
    targetId: String,
    reason: String = "",
    params: Map<String, Any> = emptyMap()
): Map<String, Any>? {
    return UniversalCommunicationManager.communicator.wakeupNode(
        sourceId = sourceId,
        targetId = targetId,
        reason = reason,
        params = params
    )
}

suspend fun sendToNode(
    sourceId: String,
    targetId: String,
    messageType: MessageType,
    payload: Map<String, Any> = emptyMap(),
    waitResponse: Boolean = false,
    timeout: Long = 30000
): Map<String, Any>? {
    return UniversalCommunicationManager.communicator.sendToNode(
        sourceId = sourceId,
        targetId = targetId,
        messageType = messageType,
        payload = payload,
        waitResponse = waitResponse,
        timeout = timeout
    )
}

suspend fun activateSelf(
    nodeId: String,
    action: String,
    params: Map<String, Any> = emptyMap()
): Map<String, Any>? {
    return UniversalCommunicationManager.communicator.activateSelf(
        nodeId = nodeId,
        action = action,
        params = params
    )
}

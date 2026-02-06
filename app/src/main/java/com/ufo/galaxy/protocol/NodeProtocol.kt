package com.ufo.galaxy.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * UFO Galaxy Android - 节点通信协议
 * ==================================
 * 
 * 与服务端 core/node_protocol.py 颗粒度对齐
 * 
 * 支持的消息类型：
 * - request/response: 请求响应
 * - event: 事件通知
 * - stream_*: 流式传输
 * - ping/pong: 心跳
 * 
 * 作者：Manus AI
 * 日期：2026-02-06
 */
class NodeProtocol {

    // ========================================================================
    // 消息类型
    // ========================================================================
    
    enum class MessageType(val value: String) {
        REQUEST("request"),
        RESPONSE("response"),
        EVENT("event"),
        BROADCAST("broadcast"),
        STREAM_START("stream_start"),
        STREAM_DATA("stream_data"),
        STREAM_END("stream_end"),
        PING("ping"),
        PONG("pong"),
        SUBSCRIBE("subscribe"),
        UNSUBSCRIBE("unsubscribe"),
        ERROR("error");
        
        companion object {
            fun fromValue(value: String): MessageType {
                return values().find { it.value == value } ?: REQUEST
            }
        }
    }
    
    enum class MessagePriority(val value: Int) {
        LOW(0),
        NORMAL(1),
        HIGH(2),
        CRITICAL(3);
        
        companion object {
            fun fromValue(value: Int): MessagePriority {
                return values().find { it.value == value } ?: NORMAL
            }
        }
    }
    
    // ========================================================================
    // 消息头
    // ========================================================================
    
    data class MessageHeader(
        val messageId: String = UUID.randomUUID().toString(),
        val messageType: MessageType = MessageType.REQUEST,
        val timestamp: Long = System.currentTimeMillis(),
        val sourceNode: String = "",
        val targetNode: String = "",
        val correlationId: String? = null,
        val priority: MessagePriority = MessagePriority.NORMAL,
        val ttl: Int = 30
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("message_id", messageId)
                put("message_type", messageType.value)
                put("timestamp", timestamp / 1000.0) // 转换为秒（与 Python 对齐）
                put("source_node", sourceNode)
                put("target_node", targetNode)
                put("correlation_id", correlationId)
                put("priority", priority.value)
                put("ttl", ttl)
            }
        }
        
        companion object {
            fun fromJson(json: JSONObject): MessageHeader {
                return MessageHeader(
                    messageId = json.optString("message_id", UUID.randomUUID().toString()),
                    messageType = MessageType.fromValue(json.optString("message_type", "request")),
                    timestamp = (json.optDouble("timestamp", System.currentTimeMillis() / 1000.0) * 1000).toLong(),
                    sourceNode = json.optString("source_node", ""),
                    targetNode = json.optString("target_node", ""),
                    correlationId = json.optString("correlation_id", null),
                    priority = MessagePriority.fromValue(json.optInt("priority", 1)),
                    ttl = json.optInt("ttl", 30)
                )
            }
        }
    }
    
    // ========================================================================
    // 消息
    // ========================================================================
    
    data class Message(
        val header: MessageHeader,
        val action: String = "",
        val payload: JSONObject = JSONObject(),
        val metadata: JSONObject = JSONObject()
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("header", header.toJson())
                put("action", action)
                put("payload", payload)
                put("metadata", metadata)
            }
        }
        
        fun toJsonString(): String = toJson().toString()
        
        fun isExpired(): Boolean {
            return System.currentTimeMillis() - header.timestamp > header.ttl * 1000
        }
        
        companion object {
            fun fromJson(json: JSONObject): Message {
                return Message(
                    header = MessageHeader.fromJson(json.optJSONObject("header") ?: JSONObject()),
                    action = json.optString("action", ""),
                    payload = json.optJSONObject("payload") ?: JSONObject(),
                    metadata = json.optJSONObject("metadata") ?: JSONObject()
                )
            }
            
            fun fromJsonString(jsonString: String): Message {
                return fromJson(JSONObject(jsonString))
            }
        }
    }
    
    // ========================================================================
    // 请求消息
    // ========================================================================
    
    data class Request(
        val header: MessageHeader,
        val action: String,
        val params: JSONObject = JSONObject()
    ) {
        fun toMessage(): Message {
            return Message(
                header = header.copy(messageType = MessageType.REQUEST),
                action = action,
                payload = params
            )
        }
        
        companion object {
            fun create(
                source: String,
                target: String,
                action: String,
                params: JSONObject = JSONObject(),
                priority: MessagePriority = MessagePriority.NORMAL
            ): Request {
                return Request(
                    header = MessageHeader(
                        sourceNode = source,
                        targetNode = target,
                        messageType = MessageType.REQUEST,
                        priority = priority
                    ),
                    action = action,
                    params = params
                )
            }
        }
    }
    
    // ========================================================================
    // 响应消息
    // ========================================================================
    
    data class Response(
        val header: MessageHeader,
        val action: String,
        val data: JSONObject = JSONObject(),
        val success: Boolean = true,
        val error: String? = null
    ) {
        fun toMessage(): Message {
            return Message(
                header = header.copy(messageType = MessageType.RESPONSE),
                action = action,
                payload = JSONObject().apply {
                    put("data", data)
                    put("success", success)
                    put("error", error)
                }
            )
        }
        
        companion object {
            fun fromRequest(
                request: Request,
                success: Boolean = true,
                data: JSONObject = JSONObject(),
                error: String? = null
            ): Response {
                return Response(
                    header = MessageHeader(
                        sourceNode = request.header.targetNode,
                        targetNode = request.header.sourceNode,
                        messageType = MessageType.RESPONSE,
                        correlationId = request.header.messageId
                    ),
                    action = request.action,
                    data = data,
                    success = success,
                    error = error
                )
            }
        }
    }
    
    // ========================================================================
    // 事件消息
    // ========================================================================
    
    data class Event(
        val header: MessageHeader,
        val eventType: String,
        val data: JSONObject = JSONObject()
    ) {
        fun toMessage(): Message {
            return Message(
                header = header.copy(messageType = MessageType.EVENT),
                action = eventType,
                payload = data,
                metadata = JSONObject().apply {
                    put("event_type", eventType)
                }
            )
        }
        
        companion object {
            fun create(
                source: String,
                eventType: String,
                data: JSONObject = JSONObject()
            ): Event {
                return Event(
                    header = MessageHeader(
                        sourceNode = source,
                        messageType = MessageType.EVENT
                    ),
                    eventType = eventType,
                    data = data
                )
            }
        }
    }
    
    // ========================================================================
    // 流式消息
    // ========================================================================
    
    data class StreamMessage(
        val header: MessageHeader,
        val streamId: String,
        val sequence: Int,
        val data: Any? = null,
        val isFinal: Boolean = false
    ) {
        fun toMessage(): Message {
            return Message(
                header = header,
                payload = JSONObject().apply {
                    put("stream_id", streamId)
                    put("sequence", sequence)
                    put("data", data)
                    put("is_final", isFinal)
                }
            )
        }
    }
    
    class StreamSession(
        val streamId: String,
        val source: String,
        val target: String
    ) {
        private var sequence = 0
        private var started = false
        private var ended = false
        
        fun start(): StreamMessage {
            started = true
            return StreamMessage(
                header = MessageHeader(
                    sourceNode = source,
                    targetNode = target,
                    messageType = MessageType.STREAM_START
                ),
                streamId = streamId,
                sequence = 0
            )
        }
        
        fun send(data: Any): StreamMessage {
            sequence++
            return StreamMessage(
                header = MessageHeader(
                    sourceNode = source,
                    targetNode = target,
                    messageType = MessageType.STREAM_DATA
                ),
                streamId = streamId,
                sequence = sequence,
                data = data
            )
        }
        
        fun end(finalData: Any? = null): StreamMessage {
            ended = true
            sequence++
            return StreamMessage(
                header = MessageHeader(
                    sourceNode = source,
                    targetNode = target,
                    messageType = MessageType.STREAM_END
                ),
                streamId = streamId,
                sequence = sequence,
                data = finalData,
                isFinal = true
            )
        }
        
        fun isStarted() = started
        fun isEnded() = ended
    }
    
    // ========================================================================
    // 消息路由器
    // ========================================================================
    
    class MessageRouter {
        private val handlers = ConcurrentHashMap<String, suspend (JSONObject) -> JSONObject>()
        private val eventHandlers = ConcurrentHashMap<String, MutableList<suspend (JSONObject) -> Unit>>()
        private val pendingRequests = ConcurrentHashMap<String, (Response) -> Unit>()
        private val streams = ConcurrentHashMap<String, StreamSession>()
        
        fun registerHandler(action: String, handler: suspend (JSONObject) -> JSONObject) {
            handlers[action] = handler
        }
        
        fun registerEventHandler(eventType: String, handler: suspend (JSONObject) -> Unit) {
            eventHandlers.getOrPut(eventType) { mutableListOf() }.add(handler)
        }
        
        suspend fun routeMessage(message: Message): Response? {
            return when (message.header.messageType) {
                MessageType.REQUEST -> handleRequest(message)
                MessageType.RESPONSE -> {
                    handleResponse(message)
                    null
                }
                MessageType.EVENT -> {
                    handleEvent(message)
                    null
                }
                MessageType.PING -> handlePing(message)
                else -> null
            }
        }
        
        private suspend fun handleRequest(message: Message): Response {
            val action = message.action
            val handler = handlers[action]
            
            return if (handler != null) {
                try {
                    val result = handler(message.payload)
                    Response(
                        header = MessageHeader(
                            sourceNode = message.header.targetNode,
                            targetNode = message.header.sourceNode,
                            messageType = MessageType.RESPONSE,
                            correlationId = message.header.messageId
                        ),
                        action = action,
                        data = result,
                        success = true
                    )
                } catch (e: Exception) {
                    Response(
                        header = MessageHeader(
                            sourceNode = message.header.targetNode,
                            targetNode = message.header.sourceNode,
                            messageType = MessageType.RESPONSE,
                            correlationId = message.header.messageId
                        ),
                        action = action,
                        success = false,
                        error = e.message
                    )
                }
            } else {
                Response(
                    header = MessageHeader(
                        sourceNode = message.header.targetNode,
                        targetNode = message.header.sourceNode,
                        messageType = MessageType.RESPONSE,
                        correlationId = message.header.messageId
                    ),
                    action = action,
                    success = false,
                    error = "Handler not found: $action"
                )
            }
        }
        
        private fun handleResponse(message: Message) {
            val correlationId = message.header.correlationId ?: return
            val callback = pendingRequests.remove(correlationId)
            
            callback?.invoke(Response(
                header = message.header,
                action = message.action,
                data = message.payload.optJSONObject("data") ?: JSONObject(),
                success = message.payload.optBoolean("success", true),
                error = message.payload.optString("error", null)
            ))
        }
        
        private suspend fun handleEvent(message: Message) {
            val eventType = message.metadata.optString("event_type", message.action)
            val handlers = eventHandlers[eventType] ?: return
            
            handlers.forEach { handler ->
                try {
                    handler(message.payload)
                } catch (e: Exception) {
                    // Log error
                }
            }
        }
        
        private fun handlePing(message: Message): Response {
            return Response(
                header = MessageHeader(
                    sourceNode = message.header.targetNode,
                    targetNode = message.header.sourceNode,
                    messageType = MessageType.PONG,
                    correlationId = message.header.messageId
                ),
                action = "pong",
                data = JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                }
            )
        }
        
        fun registerPendingRequest(requestId: String, callback: (Response) -> Unit) {
            pendingRequests[requestId] = callback
        }
    }
    
    // ========================================================================
    // 协议适配器
    // ========================================================================
    
    object ProtocolAdapter {
        /**
         * 转换为服务端格式
         */
        fun toServerFormat(message: Message): JSONObject {
            return message.toJson()
        }
        
        /**
         * 从服务端格式转换
         */
        fun fromServerFormat(json: JSONObject): Message {
            return Message.fromJson(json)
        }
        
        /**
         * 转换为简化格式（用于 WebSocket）
         */
        fun toSimpleFormat(message: Message): JSONObject {
            return JSONObject().apply {
                put("id", message.header.messageId)
                put("type", message.header.messageType.value)
                put("action", message.action)
                put("data", message.payload)
                put("timestamp", message.header.timestamp)
                put("source", message.header.sourceNode)
                put("target", message.header.targetNode)
            }
        }
        
        /**
         * 从简化格式转换
         */
        fun fromSimpleFormat(json: JSONObject): Message {
            return Message(
                header = MessageHeader(
                    messageId = json.optString("id", UUID.randomUUID().toString()),
                    messageType = MessageType.fromValue(json.optString("type", "request")),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    sourceNode = json.optString("source", ""),
                    targetNode = json.optString("target", "")
                ),
                action = json.optString("action", ""),
                payload = json.optJSONObject("data") ?: JSONObject()
            )
        }
    }
}

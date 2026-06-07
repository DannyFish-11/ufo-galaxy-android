package com.ufo.galaxy.service.handler

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.MsgType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * SECURITY-FIX (P2): Extracted from GalaxyConnectionService to separate message routing
 * from service lifecycle management. Routes inbound WebSocket messages to the appropriate
 * handler based on message type.
 */
class MessageRouter(
    private val gson: Gson,
    private val serviceScope: CoroutineScope,
    private val taskHandler: TaskHandler,
    private val onStateEvent: (category: String, action: String, payloadJson: String, traceId: String?) -> Unit,
    private val onLiquidEvent: (payloadJson: String, traceId: String?) -> Unit,
    private val onHandoffEnvelopeV2: (taskId: String, envelopePayloadJson: String, traceId: String?) -> Unit,
    private val onOperatorAction: (actionId: String, payloadJson: String, traceId: String?) -> Unit,
    private val onAdvancedMessage: (msgType: MsgType, messageId: String?, rawJson: String) -> Unit,
    private val onUnknownMessage: (rawType: String?, rawJson: String) -> Unit
) {
    companion object {
        private const val TAG = "GalaxyConnectionService:MessageRouter"
    }

    /**
     * Routes an inbound WebSocket text message to the appropriate handler.
     * Parses the message type and dispatches to TaskHandler for task-related messages
     * or to the appropriate callback for other message types.
     */
    fun routeMessage(text: String) {
        try {
            val root = gson.fromJson(text, JsonObject::class.java)
            val msgTypeStr = root.get("type")?.asString
            val traceId = root.get("trace_id")?.asString
            val payload = root.getAsJsonObject("payload")

            val msgType = try {
                msgTypeStr?.let { MsgType.fromValue(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Unknown msg_type '$msgTypeStr': ${e.message}")
                null
            }

            val payloadStr = payload?.toString() ?: "{}"
            val taskId = payload?.get("task_id")?.asString ?: ""

            when (msgType) {
                MsgType.TASK_ASSIGN -> {
                    serviceScope.launch {
                        taskHandler.handleTaskAssign(taskId, payloadStr, traceId)
                    }
                }
                MsgType.GOAL_EXECUTION -> {
                    serviceScope.launch {
                        taskHandler.handleGoalExecution(taskId, payloadStr, traceId)
                    }
                }
                MsgType.PARALLEL_SUBTASK -> {
                    serviceScope.launch {
                        taskHandler.handleParallelSubtask(taskId, payloadStr, traceId)
                    }
                }
                MsgType.TASK_CANCEL -> {
                    // Non-blocking cancellation
                    taskHandler.handleTaskCancel(taskId, payloadStr)
                }
                MsgType.STATE_EVENT -> {
                    val eventCategory = payload?.get("category")?.asString ?: ""
                    val eventAction = payload?.get("action")?.asString ?: ""
                    onStateEvent(eventCategory, eventAction, payloadStr, traceId)
                }
                MsgType.HANDOFF_ENVELOPE_V2 -> {
                    onHandoffEnvelopeV2(taskId, payloadStr, traceId)
                }
                MsgType.OPERATOR_ACTION_REQUEST -> {
                    val actionId = payload?.get("action_id")?.asString ?: ""
                    onOperatorAction(actionId, payloadStr, traceId)
                }
                MsgType.HEARTBEAT_ACK -> {
                    // Heartbeat ACK is handled directly by GalaxyWebSocketClient (pong tracking)
                    Log.d(TAG, "[MSG:HEARTBEAT_ACK] received")
                }
                in MsgType.ADVANCED_TYPES -> {
                    val messageId = root.get("message_id")?.asString
                    val type = msgType ?: run {
                        Log.w(TAG, "msgType is null in ADVANCED_TYPES branch, using UNKNOWN")
                        MsgType.UNKNOWN
                    }
                    onAdvancedMessage(type, messageId, text)
                }
                else -> {
                    if (msgType == null) {
                        onUnknownMessage(msgTypeStr, text)
                    } else {
                        Log.d(TAG, "[MSG:ROUTE] Unhandled message type: ${msgType.value}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Message routing error: ${e.message}")
            GalaxyLogger.log(TAG, mapOf("event" to "message_route_error", "error" to (e.message ?: "unknown")))
            onUnknownMessage(null, text)
        }
    }
}

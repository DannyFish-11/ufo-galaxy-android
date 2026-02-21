package com.ufo.galaxy.nodes

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * UFO Galaxy Android - 节点注册表
 * 管理所有本地节点的注册和调用
 * 
 * 与服务端 104 个节点对应，安卓端实现核心移动端节点
 */
class NodeRegistry private constructor(private val context: Context) {

    private val nodes = ConcurrentHashMap<String, BaseNode>()
    private val nodeCapabilities = ConcurrentHashMap<String, List<String>>()

    companion object {
        @Volatile
        private var instance: NodeRegistry? = null

        fun getInstance(context: Context): NodeRegistry {
            return instance ?: synchronized(this) {
                instance ?: NodeRegistry(context.applicationContext).also { instance = it }
            }
        }

        // 安卓端核心节点 ID 映射（与服务端对齐）
        const val NODE_SCREEN_CAPTURE = "Node_33"      // 屏幕捕获
        const val NODE_ADB_CONTROL = "Node_34"         // ADB 控制
        const val NODE_ACCESSIBILITY = "Node_35"       // 无障碍服务
        const val NODE_INPUT_INJECTION = "Node_36"     // 输入注入
        const val NODE_APP_MANAGER = "Node_37"         // 应用管理
        const val NODE_NOTIFICATION = "Node_38"        // 通知管理
        const val NODE_SENSOR = "Node_39"              // 传感器
        const val NODE_LOCATION = "Node_40"            // 位置服务
        const val NODE_WEBRTC = "Node_95"              // WebRTC 流
        const val NODE_SMART_ROUTER = "Node_96"        // 智能路由
    }

    init {
        registerDefaultNodes()
    }

    private fun registerDefaultNodes() {
        // 注册核心移动端节点
        registerNode(NODE_SCREEN_CAPTURE, ScreenCaptureNode(context))
        registerNode(NODE_ACCESSIBILITY, AccessibilityNode(context))
        registerNode(NODE_INPUT_INJECTION, InputInjectionNode(context))
        registerNode(NODE_APP_MANAGER, AppManagerNode(context))
        registerNode(NODE_NOTIFICATION, NotificationNode(context))
    }

    fun registerNode(nodeId: String, node: BaseNode) {
        nodes[nodeId] = node
        nodeCapabilities[nodeId] = node.getCapabilities()
    }

    fun unregisterNode(nodeId: String) {
        nodes.remove(nodeId)
        nodeCapabilities.remove(nodeId)
    }

    fun getNode(nodeId: String): BaseNode? = nodes[nodeId]

    fun getAllNodes(): Map<String, BaseNode> = nodes.toMap()

    fun getNodeCapabilities(nodeId: String): List<String> = nodeCapabilities[nodeId] ?: emptyList()

    fun getAllCapabilities(): Map<String, List<String>> = nodeCapabilities.toMap()

    /**
     * 执行节点任务
     */
    suspend fun executeNode(nodeId: String, action: String, params: JSONObject): NodeResult {
        val node = nodes[nodeId] ?: return NodeResult.error("Node not found: $nodeId")
        return try {
            node.execute(action, params)
        } catch (e: Exception) {
            NodeResult.error("Execution failed: ${e.message}")
        }
    }

    /**
     * 根据能力查找节点
     */
    fun findNodeByCapability(capability: String): String? {
        return nodeCapabilities.entries.find { it.value.contains(capability) }?.key
    }

    /**
     * 获取节点状态汇总
     */
    fun getStatusSummary(): JSONObject {
        val summary = JSONObject()
        summary.put("total_nodes", nodes.size)
        summary.put("node_ids", nodes.keys.toList())
        
        val capabilitiesList = mutableListOf<String>()
        nodeCapabilities.values.forEach { capabilitiesList.addAll(it) }
        summary.put("total_capabilities", capabilitiesList.distinct().size)
        
        return summary
    }
}

/**
 * 节点执行结果
 */
data class NodeResult(
    val success: Boolean,
    val data: JSONObject? = null,
    val error: String? = null
) {
    companion object {
        fun success(data: JSONObject? = null) = NodeResult(true, data, null)
        fun error(message: String) = NodeResult(false, null, message)
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("success", success)
        if (data != null) json.put("data", data)
        if (error != null) json.put("error", error)
        return json
    }
}

package com.ufo.galaxy.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.ufo.galaxy.nodes.*
import com.ufo.galaxy.service.DeviceRegistrationService
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * UFO Galaxy Android Sub-Agent Core
 * 安卓子 Agent 核心引擎
 */
class AgentCore(private val context: Context) {

    companion object {
        private const val TAG = "AgentCore"
        
        @Volatile
        private var instance: AgentCore? = null
        
        fun getInstance(context: Context): AgentCore {
            return instance ?: synchronized(this) {
                instance ?: AgentCore(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // 节点注册表
    private val nodes = ConcurrentHashMap<String, BaseNode>()
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 工具注册表
    private val toolRegistry = ToolRegistry(context)
    
    // 设备注册服务
    private val registrationService = DeviceRegistrationService(context)

    fun initialize() {
        Log.i(TAG, "Initializing UFO Galaxy Android Sub-Agent...")
        
        // 注册核心节点
        registerNodes()
        
        // 启动工具发现
        toolRegistry.discoverTools()
        
        Log.i(TAG, "Agent initialized successfully")
    }
    
    /**
     * 注册设备到主系统
     */
    suspend fun registerToGalaxy(node50Url: String): Boolean {
        return registrationService.registerDevice(node50Url)
    }
    
    /**
     * 注销设备
     */
    suspend fun unregisterFromGalaxy(node50Url: String): Boolean {
        return registrationService.unregisterDevice(node50Url)
    }
    
    /**
     * 获取设备 ID
     */
    fun getDeviceId(): String = registrationService.getDeviceId()
    
    /**
     * 检查是否已注册
     */
    fun isRegistered(): Boolean = registrationService.isDeviceRegistered()

    private fun registerNodes() {
        // Node 00: State Machine (简化版)
        nodes["00"] = Node00StateMachine(context)
        
        // Node 04: Tool Router (安卓版)
        nodes["04"] = Node04ToolRouter(context, toolRegistry)
        
        // Node 33: ADB Self-Control
        nodes["33"] = Node33ADBSelf(context)
        
        // Node 41: MQTT Communication
        nodes["41"] = Node41MQTT(context)
        
        // Node 58: Model Router (可选，如果需要本地推理)
        nodes["58"] = Node58ModelRouter(context)
        
        Log.i(TAG, "Registered ${nodes.size} nodes")
    }

    /**
     * 处理任务请求
     */
    suspend fun handleTask(taskDescription: String, context: Map<String, Any> = emptyMap()): JSONObject {
        return withContext(Dispatchers.IO) {
            try {
                // 使用 Node 04 进行智能路由
                val router = nodes["04"] as? Node04ToolRouter
                    ?: return@withContext JSONObject().apply {
                        put("success", false)
                        put("error", "Router not available")
                    }
                
                router.routeTask(taskDescription, context)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling task", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }
            }
        }
    }

    /**
     * 获取节点
     */
    fun getNode(nodeId: String): BaseNode? = nodes[nodeId]

    /**
     * 获取所有节点状态
     */
    fun getNodesStatus(): JSONObject {
        return JSONObject().apply {
            put("total", nodes.size)
            put("nodes", JSONObject().apply {
                nodes.forEach { (id, node) ->
                    put(id, JSONObject().apply {
                        put("name", node.name)
                        put("status", node.getStatus())
                    })
                }
            })
        }
    }

    /**
     * 关闭
     */
    fun shutdown() {
        scope.cancel()
        nodes.values.forEach { it.shutdown() }
        registrationService.shutdown()
        Log.i(TAG, "Agent shutdown")
    }
}

/**
 * 工具注册表
 */
class ToolRegistry(private val context: Context) {

    companion object {
        private const val TAG = "ToolRegistry"
    }

    private val tools = ConcurrentHashMap<String, ToolInfo>()

    data class ToolInfo(
        val name: String,
        val packageName: String,
        val capabilities: List<String>,
        val type: ToolType
    )

    enum class ToolType {
        APP,
        SYSTEM_SERVICE,
        TERMUX_COMMAND
    }

    /**
     * 发现安卓工具
     */
    fun discoverTools() {
        Log.i(TAG, "Discovering Android tools...")
        
        // 扫描已安装的 App
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        
        for (app in installedApps) {
            val packageName = app.packageName
            val appName = pm.getApplicationLabel(app).toString()
            
            // 推断能力
            val capabilities = inferCapabilities(packageName, appName)
            
            if (capabilities.isNotEmpty()) {
                tools[packageName] = ToolInfo(
                    name = appName,
                    packageName = packageName,
                    capabilities = capabilities,
                    type = ToolType.APP
                )
            }
        }
        
        // 检查 Termux
        if (tools.containsKey("com.termux")) {
            discoverTermuxTools()
        }
        
        Log.i(TAG, "Discovered ${tools.size} tools")
    }

    private fun inferCapabilities(packageName: String, appName: String): List<String> {
        val capabilities = mutableListOf<String>()
        
        val lowerName = appName.lowercase()
        val lowerPackage = packageName.lowercase()
        
        when {
            "termux" in lowerPackage -> capabilities.addAll(listOf("shell", "python", "programming"))
            "tasker" in lowerPackage -> capabilities.addAll(listOf("automation", "task_scheduling"))
            "automate" in lowerPackage -> capabilities.addAll(listOf("automation", "flow"))
            "camera" in lowerName -> capabilities.add("camera")
            "gallery" in lowerName || "photo" in lowerName -> capabilities.add("image")
            "video" in lowerName -> capabilities.add("video")
            "music" in lowerName || "audio" in lowerName -> capabilities.add("audio")
            "browser" in lowerName || "chrome" in lowerPackage -> capabilities.add("web_browsing")
            "file" in lowerName || "manager" in lowerName -> capabilities.add("file_management")
        }
        
        return capabilities
    }

    private fun discoverTermuxTools() {
        // 如果有 Termux，可以扫描其中的命令
        // 这里简化处理，假设有常见命令
        val termuxCommands = listOf("python", "git", "curl", "wget", "ffmpeg")
        
        termuxCommands.forEach { cmd ->
            tools["termux:$cmd"] = ToolInfo(
                name = "Termux: $cmd",
                packageName = "com.termux",
                capabilities = listOf("shell", cmd),
                type = ToolType.TERMUX_COMMAND
            )
        }
    }

    fun getTools(): Map<String, ToolInfo> = tools.toMap()

    fun findByCapability(capability: String): List<ToolInfo> {
        return tools.values.filter { capability in it.capabilities }
    }
}

package com.ufo.galaxy.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.ufo.galaxy.agent.*
import com.ufo.galaxy.nodes.*
import com.ufo.galaxy.service.DeviceRegistrationService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * UFO Galaxy Android Sub-Agent Core
 * 安卓子 Agent 核心引擎
 *
 * 统一集成:
 * - Node 58 MultiModel Router → 服务端 MultiLLMRouter → Provider/OneAPI
 * - AgentFactory → OpenClaw 风格人格系统 (SOUL.md/AGENTS.md/USER.md)
 * - AgentTeam/AgentSwarm → 多智能体协作
 * - 设备操控节点 (Node 33/35/36/37/38)
 *
 * 供给 Dashboard 和 Windows 主 UI 的统一入口。
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

    // Agent 工厂 — OpenClaw 风格
    val agentFactory: AgentFactory by lazy { AgentFactory.getInstance(context) }

    // 团队注册表
    private val teams = ConcurrentHashMap<String, AgentTeam>()
    private val swarms = ConcurrentHashMap<String, AgentSwarm>()

    fun initialize() {
        Log.i(TAG, "Initializing UFO Galaxy Android Sub-Agent...")

        // 注册核心节点
        registerNodes()

        // 启动工具发现
        toolRegistry.discoverTools()

        // 初始化 Agent 工厂并绑定 ModelRouter
        val modelRouter = nodes["58"] as? Node58ModelRouter
        if (modelRouter != null) {
            agentFactory.initialize(modelRouter)
            Log.i(TAG, "AgentFactory bound to Node58 ModelRouter")
        }

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
     * 获取 ModelRouter
     */
    fun getModelRouter(): Node58ModelRouter? = nodes["58"] as? Node58ModelRouter

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

    // ──────────────────────────────────────────────────────────────────────────
    // 统一 API 入口 — 供 Dashboard / Windows 主 UI / WebSocket 调用
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 统一请求处理入口 — Dashboard 和 Windows UI 的所有请求通过此方法路由
     *
     * 支持的 action:
     *   chat            → 多模型智能路由聊天
     *   list_models     → 列出所有可用模型
     *   list_providers  → 列出所有 Provider 状态
     *   provider_stats  → Provider 统计信息
     *   create_agent    → 创建智能体 (AgentFactory)
     *   list_agents     → 列出活跃智能体
     *   agent_chat      → 与指定智能体对话
     *   destroy_agent   → 销毁智能体
     *   list_templates  → 列出可用模板
     *   create_team     → 创建智能体团队
     *   team_task       → 团队执行任务
     *   create_swarm    → 创建蜂群
     *   swarm_batch     → 蜂群批量执行
     *   device_action   → 设备操作
     *   health          → 健康检查
     *   status          → 系统全局状态
     */
    suspend fun handleUnifiedRequest(request: JSONObject): JSONObject {
        val action = request.optString("action", "")
        Log.d(TAG, "Unified request: $action")

        return try {
            when (action) {
                // ── 多模型智能路由 ──
                "chat" -> getModelRouter()?.handle(request)
                    ?: JSONObject().apply { put("success", false); put("error", "ModelRouter unavailable") }

                "list_models" -> getModelRouter()?.handle(
                    JSONObject().put("action", "list_models")
                ) ?: JSONObject().apply { put("success", false); put("error", "ModelRouter unavailable") }

                "list_providers" -> getModelRouter()?.handle(
                    JSONObject().put("action", "list_providers")
                ) ?: JSONObject().apply { put("success", false); put("error", "ModelRouter unavailable") }

                "provider_stats" -> getModelRouter()?.handle(
                    JSONObject().put("action", "provider_stats")
                ) ?: JSONObject().apply { put("success", false); put("error", "ModelRouter unavailable") }

                // ── Agent 工厂 ──
                "create_agent" -> handleCreateAgent(request)
                "create_custom_agent" -> handleCreateCustomAgent(request)
                "list_agents" -> agentFactory.listActiveAgents()
                "list_templates" -> agentFactory.listTemplates()
                "agent_chat" -> handleAgentChat(request)
                "destroy_agent" -> handleDestroyAgent(request)

                // ── 多智能体协作 ──
                "create_team" -> handleCreateTeam(request)
                "team_task" -> handleTeamTask(request)
                "list_teams" -> listTeams()
                "create_swarm" -> handleCreateSwarm(request)
                "swarm_batch" -> handleSwarmBatch(request)

                // ── 设备操控 ──
                "device_action" -> handleDeviceAction(request)

                // ── 系统 ──
                "health" -> getModelRouter()?.handle(
                    JSONObject().put("action", "health")
                ) ?: JSONObject().apply { put("success", false) }

                "status" -> getGlobalStatus()

                else -> JSONObject().apply {
                    put("success", false)
                    put("error", "Unknown action: $action")
                    put("available_actions", JSONArray(listOf(
                        "chat", "list_models", "list_providers", "provider_stats",
                        "create_agent", "create_custom_agent", "list_agents", "list_templates",
                        "agent_chat", "destroy_agent",
                        "create_team", "team_task", "list_teams",
                        "create_swarm", "swarm_batch",
                        "device_action", "health", "status"
                    )))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unified request failed: $action", e)
            JSONObject().apply {
                put("success", false)
                put("error", "${e.javaClass.simpleName}: ${e.message}")
                put("action", action)
            }
        }
    }

    private fun handleCreateAgent(request: JSONObject): JSONObject {
        val templateId = request.optString("template_id", "")
        val agentId = request.optString("agent_id", "").takeIf { it.isNotEmpty() }

        if (templateId.isEmpty()) {
            return JSONObject().apply {
                put("success", false)
                put("error", "template_id is required")
            }
        }

        val agent = agentFactory.createAgentFromTemplate(templateId, agentId)
            ?: return JSONObject().apply {
                put("success", false)
                put("error", "Template not found: $templateId")
            }

        return JSONObject().apply {
            put("success", true)
            put("agent", agent.toJson())
        }
    }

    private fun handleCreateCustomAgent(request: JSONObject): JSONObject {
        val definition = request.optJSONObject("definition")
            ?: return JSONObject().apply {
                put("success", false)
                put("error", "definition object is required")
            }

        val agent = agentFactory.createCustomAgent(definition)
        return JSONObject().apply {
            put("success", true)
            put("agent", agent.toJson())
        }
    }

    private suspend fun handleAgentChat(request: JSONObject): JSONObject {
        val agentId = request.optString("agent_id", "")
        val message = request.optString("message", "")

        if (agentId.isEmpty() || message.isEmpty()) {
            return JSONObject().apply {
                put("success", false)
                put("error", "agent_id and message are required")
            }
        }

        val agent = agentFactory.getAgent(agentId)
            ?: return JSONObject().apply {
                put("success", false)
                put("error", "Agent not found: $agentId")
            }

        return agent.chat(message, request.optJSONObject("context"))
    }

    private fun handleDestroyAgent(request: JSONObject): JSONObject {
        val agentId = request.optString("agent_id", "")
        if (agentId.isEmpty()) {
            return JSONObject().apply {
                put("success", false)
                put("error", "agent_id is required")
            }
        }
        agentFactory.destroyAgent(agentId)
        return JSONObject().apply {
            put("success", true)
            put("destroyed", agentId)
        }
    }

    private fun handleCreateTeam(request: JSONObject): JSONObject {
        val teamId = request.optString("team_id", "team_${System.currentTimeMillis()}")
        val teamName = request.optString("name", teamId)
        val memberDefs = request.optJSONArray("members") ?: JSONArray()

        val team = AgentTeam(teamId, teamName)

        for (i in 0 until memberDefs.length()) {
            val memberDef = memberDefs.getJSONObject(i)
            val templateId = memberDef.optString("template_id", "")
            val role = try {
                AgentTeam.TeamRole.valueOf(memberDef.optString("role", "EXECUTOR").uppercase())
            } catch (_: Exception) {
                AgentTeam.TeamRole.EXECUTOR
            }
            val priority = memberDef.optInt("priority", 0)

            val agent = agentFactory.createAgentFromTemplate(templateId, "${teamId}_${templateId}_$i")
            if (agent != null) {
                team.addMember(agent, role, priority)
            }
        }

        teams[teamId] = team
        return JSONObject().apply {
            put("success", true)
            put("team", team.getMembers())
        }
    }

    private suspend fun handleTeamTask(request: JSONObject): JSONObject {
        val teamId = request.optString("team_id", "")
        val task = request.optString("task", "")
        val mode = try {
            AgentTeam.ExecutionMode.valueOf(request.optString("mode", "SEQUENTIAL").uppercase())
        } catch (_: Exception) {
            AgentTeam.ExecutionMode.SEQUENTIAL
        }

        val team = teams[teamId]
            ?: return JSONObject().apply {
                put("success", false)
                put("error", "Team not found: $teamId")
            }

        return team.executeTask(task, mode, request.optJSONObject("context"))
    }

    private fun listTeams(): JSONObject = JSONObject().apply {
        put("teams", JSONArray().apply {
            teams.forEach { (_, team) -> put(team.getMembers()) }
        })
        put("count", teams.size)
    }

    private fun handleCreateSwarm(request: JSONObject): JSONObject {
        val swarmId = request.optString("swarm_id", "swarm_${System.currentTimeMillis()}")
        val swarmName = request.optString("name", swarmId)
        val templateId = request.optString("template_id", "")
        val size = request.optInt("size", 3)

        if (templateId.isEmpty()) {
            return JSONObject().apply {
                put("success", false)
                put("error", "template_id is required")
            }
        }

        val swarm = AgentSwarm(swarmId, swarmName, agentFactory, templateId, size)
        swarm.initialize()
        swarms[swarmId] = swarm

        return JSONObject().apply {
            put("success", true)
            put("swarm", swarm.getStatus())
        }
    }

    private suspend fun handleSwarmBatch(request: JSONObject): JSONObject {
        val swarmId = request.optString("swarm_id", "")
        val tasks = request.optJSONArray("tasks")

        val swarm = swarms[swarmId]
            ?: return JSONObject().apply {
                put("success", false)
                put("error", "Swarm not found: $swarmId")
            }

        if (tasks != null) {
            val taskList = (0 until tasks.length()).map { tasks.getString(it) }
            return swarm.executeBatch(taskList, request.optJSONObject("context"))
        }

        val singleTask = request.optString("task", "")
        if (singleTask.isNotEmpty()) {
            return swarm.swarmProcess(singleTask, request.optJSONObject("context"))
        }

        return JSONObject().apply {
            put("success", false)
            put("error", "tasks array or task string is required")
        }
    }

    private suspend fun handleDeviceAction(request: JSONObject): JSONObject {
        val nodeId = request.optString("node_id", "")
        val action = request.optString("device_action", "")
        val params = request.optJSONObject("params") ?: JSONObject()

        if (nodeId.isEmpty() || action.isEmpty()) {
            return JSONObject().apply {
                put("success", false)
                put("error", "node_id and device_action are required")
            }
        }

        val node = nodes[nodeId]
            ?: return JSONObject().apply {
                put("success", false)
                put("error", "Node not found: $nodeId")
            }

        val result = node.execute(action, params)
        return result.toJson()
    }

    private fun getGlobalStatus(): JSONObject = JSONObject().apply {
        put("success", true)
        put("nodes", getNodesStatus())
        put("agents", agentFactory.listActiveAgents())
        put("teams", JSONObject().apply {
            put("count", teams.size)
            put("ids", JSONArray(teams.keys.toList()))
        })
        put("swarms", JSONObject().apply {
            put("count", swarms.size)
            put("ids", JSONArray(swarms.keys.toList()))
        })
        put("device_id", getDeviceId())
        put("registered", isRegistered())
    }

    /**
     * 关闭
     */
    fun shutdown() {
        scope.cancel()
        nodes.values.forEach { it.shutdown() }
        teams.values.forEach { it.shutdown() }
        swarms.values.forEach { it.shutdown() }
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

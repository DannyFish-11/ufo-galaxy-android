package com.ufo.galaxy.agent

import android.content.Context
import android.util.Log
import com.ufo.galaxy.nodes.BaseNode
import com.ufo.galaxy.nodes.Node58ModelRouter
import com.ufo.galaxy.nodes.NodeRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenClaw 风格 Agent 工厂
 *
 * 根据模板和人格定义创建智能体实例。每个智能体：
 * - 拥有独立工作区（workspace-{agentId}）
 * - 通过 SOUL.md/AGENTS.md/USER.md 定义人格
 * - 通过 Node58 MultiModelRouter 选择最合适的 LLM
 * - 可注入设备操控能力（Node 33/35/36/37/38）
 *
 * 链路:
 *   Dashboard/Windows UI → AgentFactory.createAgent(template)
 *     → AgentPersonality(SOUL.md)
 *     → AgentInstance(personality + modelRouter + deviceNodes)
 *     → Node58.routeChat(systemPrompt + userMessage)
 *     → 服务端 MultiLLMRouter → Provider/OneAPI
 */
class AgentFactory private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AgentFactory"

        @Volatile
        private var instance: AgentFactory? = null

        fun getInstance(context: Context): AgentFactory {
            return instance ?: synchronized(this) {
                instance ?: AgentFactory(context.applicationContext).also { instance = it }
            }
        }
    }

    // 活跃智能体注册表
    private val activeAgents = ConcurrentHashMap<String, AgentInstance>()

    // 内置模板注册表
    private val templates = ConcurrentHashMap<String, AgentTemplate>()

    // 模型路由器
    private var modelRouter: Node58ModelRouter? = null

    init {
        registerBuiltinTemplates()
    }

    /**
     * 初始化工厂 — 绑定模型路由器
     */
    fun initialize(router: Node58ModelRouter) {
        this.modelRouter = router
        Log.i(TAG, "AgentFactory initialized with ModelRouter")
    }

    /**
     * 根据模板创建智能体
     */
    fun createAgent(template: AgentTemplate): AgentInstance {
        val personality = AgentPersonality.fromTemplate(template)
        personality.saveToWorkspace(context)

        val agent = AgentInstance(
            id = template.id,
            name = template.name,
            personality = personality,
            modelRouter = modelRouter,
            nodeRegistry = NodeRegistry.getInstance(context),
            deviceCapabilities = template.deviceCapabilities,
            preferredModel = template.preferredModel,
            preferredProvider = template.preferredProvider
        )

        activeAgents[template.id] = agent
        Log.i(TAG, "Created agent: ${template.id} (${template.name})")
        return agent
    }

    /**
     * 根据 ID 创建 — 使用已注册的模板
     */
    fun createAgentFromTemplate(templateId: String, agentId: String? = null): AgentInstance? {
        val template = templates[templateId] ?: return null
        val actualTemplate = if (agentId != null) {
            template.copy(id = agentId)
        } else {
            template.copy(id = "${template.id}_${System.currentTimeMillis()}")
        }
        return createAgent(actualTemplate)
    }

    /**
     * 从工作区恢复智能体
     */
    fun loadAgent(agentId: String): AgentInstance? {
        val personality = AgentPersonality.loadFromWorkspace(context, agentId) ?: return null

        val agent = AgentInstance(
            id = agentId,
            name = agentId,
            personality = personality,
            modelRouter = modelRouter,
            nodeRegistry = NodeRegistry.getInstance(context),
            deviceCapabilities = emptyList(),
            preferredModel = "auto",
            preferredProvider = null
        )

        activeAgents[agentId] = agent
        return agent
    }

    /**
     * 根据 JSON 定义创建自定义智能体
     */
    fun createCustomAgent(definition: JSONObject): AgentInstance {
        val soul = SoulConfig(
            role = definition.optString("role", "General Assistant"),
            personality = jsonArrayToList(definition.optJSONArray("personality")),
            canDo = jsonArrayToList(definition.optJSONArray("can_do")),
            cannotDo = jsonArrayToList(definition.optJSONArray("cannot_do")),
            workStyle = jsonArrayToList(definition.optJSONArray("work_style")),
            tone = definition.optString("tone", "professional")
        )

        val agentSpec = AgentSpec(
            workflow = jsonArrayToList(definition.optJSONArray("workflow")),
            qualityStandards = jsonArrayToList(definition.optJSONArray("quality_standards")),
            toolUsage = jsonArrayToList(definition.optJSONArray("tool_usage")),
            constraints = jsonArrayToList(definition.optJSONArray("constraints"))
        )

        val template = AgentTemplate(
            id = definition.optString("id", "custom_${System.currentTimeMillis()}"),
            name = definition.optString("name", "Custom Agent"),
            type = AgentTemplate.AgentType.CUSTOM,
            soul = soul,
            agentSpec = agentSpec,
            preferredModel = definition.optString("model", "auto"),
            preferredProvider = definition.optString("provider", null),
            deviceCapabilities = jsonArrayToList(definition.optJSONArray("device_capabilities"))
        )

        return createAgent(template)
    }

    /**
     * 注册自定义模板
     */
    fun registerTemplate(template: AgentTemplate) {
        templates[template.id] = template
        Log.i(TAG, "Registered template: ${template.id}")
    }

    /**
     * 获取智能体实例
     */
    fun getAgent(agentId: String): AgentInstance? = activeAgents[agentId]

    /**
     * 销毁智能体
     */
    fun destroyAgent(agentId: String) {
        activeAgents.remove(agentId)?.shutdown()
        Log.i(TAG, "Destroyed agent: $agentId")
    }

    /**
     * 列出所有活跃智能体
     */
    fun listActiveAgents(): JSONObject = JSONObject().apply {
        put("agents", JSONArray().apply {
            activeAgents.forEach { (id, agent) ->
                put(agent.toJson())
            }
        })
        put("count", activeAgents.size)
    }

    /**
     * 列出可用模板
     */
    fun listTemplates(): JSONObject = JSONObject().apply {
        put("templates", JSONArray().apply {
            templates.forEach { (_, template) ->
                put(template.toJson())
            }
        })
        put("count", templates.size)
    }

    /**
     * 内置模板注册
     */
    private fun registerBuiltinTemplates() {
        // 写作助手
        templates["writer"] = AgentTemplate(
            id = "writer",
            name = "写作助手",
            type = AgentTemplate.AgentType.WRITER,
            soul = SoulConfig(
                role = "专注于内容创作、文案撰写和文档优化，不处理技术代码任务。",
                personality = listOf("用词优美、生动，善用比喻和例子", "注重文章的可读性和逻辑结构", "对文字有洁癖，追求表达的精准"),
                canDo = listOf("撰写各类文章", "润色文本", "提供创意灵感"),
                cannotDo = listOf("代码编写", "数据分析", "系统运维"),
                workStyle = listOf("先理解需求，再动笔", "注重细节，反复打磨", "保持客观，避免过度修饰"),
                tone = "优雅、专业"
            ),
            agentSpec = AgentSpec(
                workflow = listOf("理解写作需求", "构思大纲", "撰写初稿", "润色修改", "交付终稿"),
                qualityStandards = listOf("语句通顺", "逻辑清晰", "无语法错误"),
                toolUsage = emptyList(),
                constraints = listOf("不处理代码相关任务")
            ),
            preferredModel = "auto",
            preferredProvider = null
        )

        // 编程助手
        templates["coder"] = AgentTemplate(
            id = "coder",
            name = "编程助手",
            type = AgentTemplate.AgentType.CODER,
            soul = SoulConfig(
                role = "专注于代码编写、调试和架构设计。擅长多种编程语言和框架。",
                personality = listOf("逻辑严谨，注重代码质量", "善于拆解复杂问题", "追求简洁优雅的解决方案"),
                canDo = listOf("编写代码", "调试Bug", "代码审查", "架构设计", "性能优化"),
                cannotDo = listOf("UI设计", "文案创作"),
                workStyle = listOf("先理解需求和约束", "分析现有代码", "编写并测试", "代码审查和优化"),
                tone = "技术、简洁"
            ),
            agentSpec = AgentSpec(
                workflow = listOf("分析需求", "阅读现有代码", "设计方案", "编码实现", "测试验证"),
                qualityStandards = listOf("代码可读", "有必要的注释", "遵循项目风格"),
                toolUsage = listOf("代码搜索", "文件编辑", "终端命令"),
                constraints = emptyList()
            ),
            preferredModel = "auto",
            preferredProvider = "deepseek"
        )

        // 设备控制智能体
        templates["device_controller"] = AgentTemplate(
            id = "device_controller",
            name = "设备控制专家",
            type = AgentTemplate.AgentType.DEVICE_CONTROL,
            soul = SoulConfig(
                role = "操控 Android 设备，执行屏幕操作、应用管理、输入注入等自动化任务。",
                personality = listOf("精准执行指令", "谨慎处理敏感操作", "清晰报告执行结果"),
                canDo = listOf("屏幕点击/滑动", "文字输入", "应用启动/管理", "截屏", "UI 元素查找"),
                cannotDo = listOf("修改系统底层设置", "绕过安全限制"),
                workStyle = listOf("确认目标操作", "检查前置条件", "执行操作", "验证结果"),
                tone = "简洁、精确"
            ),
            agentSpec = AgentSpec(
                workflow = listOf("解析指令", "检查设备状态", "执行操作序列", "验证执行结果", "报告状态"),
                qualityStandards = listOf("操作准确", "有异常处理", "执行后验证"),
                toolUsage = listOf("Node_33 屏幕捕获", "Node_35 无障碍服务", "Node_36 输入注入", "Node_37 应用管理", "Node_38 通知管理"),
                constraints = listOf("不执行可能导致数据丢失的操作", "敏感操作需确认")
            ),
            preferredModel = "auto",
            preferredProvider = null,
            deviceCapabilities = listOf("screen_capture", "ui_automation", "input_injection", "app_manager", "notification")
        )

        // 分析师
        templates["analyst"] = AgentTemplate(
            id = "analyst",
            name = "数据分析师",
            type = AgentTemplate.AgentType.ANALYST,
            soul = SoulConfig(
                role = "专注于数据分析、趋势识别和洞察提取。",
                personality = listOf("数据驱动", "善于发现规律", "客观理性"),
                canDo = listOf("数据分析", "趋势预测", "报告生成", "数据可视化建议"),
                cannotDo = listOf("主观判断", "非数据类创作"),
                workStyle = listOf("收集数据", "清洗整理", "分析建模", "生成报告"),
                tone = "严谨、客观"
            ),
            agentSpec = AgentSpec.default(),
            preferredModel = "auto",
            preferredProvider = "openai"
        )

        // 规划师
        templates["planner"] = AgentTemplate(
            id = "planner",
            name = "项目规划师",
            type = AgentTemplate.AgentType.PLANNER,
            soul = SoulConfig(
                role = "专注于项目规划、任务分解和执行策略制定。",
                personality = listOf("系统性思维", "善于分解复杂任务", "关注风险和依赖"),
                canDo = listOf("项目规划", "任务分解", "时间线制定", "风险评估"),
                cannotDo = listOf("具体技术实现"),
                workStyle = listOf("理解目标", "分析约束", "制定计划", "迭代优化"),
                tone = "条理清晰"
            ),
            agentSpec = AgentSpec.default(),
            preferredModel = "auto",
            preferredProvider = "anthropic"
        )
    }

    private fun jsonArrayToList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }
}

/**
 * 智能体实例 — 运行时的智能体
 */
class AgentInstance(
    val id: String,
    val name: String,
    val personality: AgentPersonality,
    private val modelRouter: Node58ModelRouter?,
    private val nodeRegistry: NodeRegistry,
    private val deviceCapabilities: List<String>,
    private val preferredModel: String,
    private val preferredProvider: String?
) {
    enum class State { IDLE, BUSY, ERROR, SHUTDOWN }

    @Volatile
    var state: State = State.IDLE
        private set

    private val conversationHistory = mutableListOf<JSONObject>()

    /**
     * 发送消息给智能体 — 自动注入人格 system prompt 和路由到合适的模型
     */
    suspend fun chat(message: String, extraContext: JSONObject? = null): JSONObject {
        if (state == State.SHUTDOWN) {
            return JSONObject().apply {
                put("success", false)
                put("error", "Agent has been shut down")
            }
        }

        state = State.BUSY
        try {
            val systemPrompt = personality.toSystemPrompt()

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                conversationHistory.forEach { put(it) }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", message)
                })
            }

            conversationHistory.add(JSONObject().apply {
                put("role", "user")
                put("content", message)
            })

            val request = JSONObject().apply {
                put("action", "chat")
                put("messages", messages)
                put("model", preferredModel)
                preferredProvider?.let { put("provider", it) }
                extraContext?.let { put("context", it) }
            }

            val result = modelRouter?.handle(request)
                ?: return JSONObject().apply {
                    put("success", false)
                    put("error", "Model router not available")
                }

            if (result.optBoolean("success", false)) {
                val content = result.optString("content", "")
                conversationHistory.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", content)
                })
            }

            state = State.IDLE
            return result.apply {
                put("agent_id", id)
                put("agent_name", name)
            }
        } catch (e: Exception) {
            state = State.ERROR
            return JSONObject().apply {
                put("success", false)
                put("error", "Agent chat failed: ${e.message}")
                put("agent_id", id)
            }
        }
    }

    /**
     * 执行设备操作 — 通过本地节点
     */
    suspend fun executeDeviceAction(nodeId: String, action: String, params: JSONObject): JSONObject {
        if (!deviceCapabilities.any { cap ->
            nodeRegistry.getNodeCapabilities(nodeId).contains(cap)
        } && deviceCapabilities.isNotEmpty()) {
            return JSONObject().apply {
                put("success", false)
                put("error", "Agent $id not authorized for node $nodeId capabilities")
            }
        }

        val result = nodeRegistry.executeNode(nodeId, action, params)
        return result.toJson().apply {
            put("agent_id", id)
        }
    }

    /**
     * 获取可用的设备节点
     */
    fun getAvailableNodes(): List<String> {
        return if (deviceCapabilities.isEmpty()) {
            emptyList()
        } else {
            deviceCapabilities.mapNotNull { cap ->
                nodeRegistry.findNodeByCapability(cap)
            }.distinct()
        }
    }

    fun clearHistory() {
        conversationHistory.clear()
    }

    fun shutdown() {
        state = State.SHUTDOWN
        conversationHistory.clear()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("state", state.name)
        put("preferred_model", preferredModel)
        preferredProvider?.let { put("preferred_provider", it) }
        put("device_capabilities", JSONArray(deviceCapabilities))
        put("available_nodes", JSONArray(getAvailableNodes()))
        put("conversation_length", conversationHistory.size)
    }
}

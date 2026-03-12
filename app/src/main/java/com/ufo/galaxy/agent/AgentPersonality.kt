package com.ufo.galaxy.agent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * OpenClaw 风格的智能体人格系统
 *
 * 每个智能体通过三个 Markdown 文件定义完整"灵魂"：
 * - SOUL.md  — 核心脑区：角色/性格/核心职责/能力边界
 * - AGENTS.md — 工作规范：工作流程/质量标准/工具调用规范
 * - USER.md  — 交互网关（可选）：用户偏好/历史上下文
 *
 * 每个智能体拥有独立工作区实现物理隔离：
 *   workspace-{agentId}/SOUL.md
 *   workspace-{agentId}/AGENTS.md
 *   workspace-{agentId}/USER.md
 */
class AgentPersonality(
    val agentId: String,
    val soul: SoulConfig,
    val agentSpec: AgentSpec,
    val userContext: UserContext? = null
) {

    companion object {
        private const val TAG = "AgentPersonality"

        /**
         * 从工作区目录加载人格定义
         */
        fun loadFromWorkspace(context: Context, agentId: String): AgentPersonality? {
            val workspaceDir = getWorkspaceDir(context, agentId)
            if (!workspaceDir.exists()) {
                Log.w(TAG, "Workspace not found for agent: $agentId")
                return null
            }

            val soul = parseSoulMd(File(workspaceDir, "SOUL.md")) ?: return null
            val agentSpec = parseAgentsMd(File(workspaceDir, "AGENTS.md")) ?: AgentSpec.default()
            val userContext = parseUserMd(File(workspaceDir, "USER.md"))

            return AgentPersonality(agentId, soul, agentSpec, userContext)
        }

        /**
         * 从 JSON 模板创建人格
         */
        fun fromTemplate(template: AgentTemplate): AgentPersonality {
            return AgentPersonality(
                agentId = template.id,
                soul = template.soul,
                agentSpec = template.agentSpec,
                userContext = null
            )
        }

        fun getWorkspaceDir(context: Context, agentId: String): File {
            return File(context.filesDir, "workspace-$agentId")
        }

        private fun parseSoulMd(file: File): SoulConfig? {
            if (!file.exists()) return null
            try {
                val content = file.readText()
                val sections = parseMarkdownSections(content)

                return SoulConfig(
                    role = sections["核心职责"] ?: sections["Core Role"] ?: "",
                    personality = parseList(sections["性格特点"] ?: sections["Personality"] ?: ""),
                    canDo = parseList(sections["能力边界"]?.let { extractCanDo(it) } ?: ""),
                    cannotDo = parseList(sections["能力边界"]?.let { extractCannotDo(it) } ?: ""),
                    workStyle = parseList(sections["工作风格"] ?: sections["Work Style"] ?: ""),
                    tone = sections["说话语气"] ?: sections["Tone"] ?: "professional"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse SOUL.md", e)
                return null
            }
        }

        private fun parseAgentsMd(file: File): AgentSpec? {
            if (!file.exists()) return null
            try {
                val content = file.readText()
                val sections = parseMarkdownSections(content)

                return AgentSpec(
                    workflow = parseList(sections["工作流程"] ?: sections["Workflow"] ?: ""),
                    qualityStandards = parseList(sections["质量标准"] ?: sections["Quality Standards"] ?: ""),
                    toolUsage = parseList(sections["工具调用"] ?: sections["Tool Usage"] ?: ""),
                    constraints = parseList(sections["约束条件"] ?: sections["Constraints"] ?: "")
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse AGENTS.md", e)
                return null
            }
        }

        private fun parseUserMd(file: File): UserContext? {
            if (!file.exists()) return null
            try {
                val content = file.readText()
                val sections = parseMarkdownSections(content)

                return UserContext(
                    preferences = parseList(sections["偏好"] ?: sections["Preferences"] ?: ""),
                    history = parseList(sections["历史"] ?: sections["History"] ?: ""),
                    customRules = parseList(sections["自定义规则"] ?: sections["Custom Rules"] ?: "")
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse USER.md", e)
                return null
            }
        }

        private fun parseMarkdownSections(content: String): Map<String, String> {
            val sections = mutableMapOf<String, String>()
            var currentSection = ""
            val buffer = StringBuilder()

            for (line in content.lines()) {
                if (line.startsWith("## ")) {
                    if (currentSection.isNotEmpty()) {
                        sections[currentSection] = buffer.toString().trim()
                    }
                    currentSection = line.removePrefix("## ").trim()
                    buffer.clear()
                } else if (currentSection.isNotEmpty()) {
                    buffer.appendLine(line)
                }
            }
            if (currentSection.isNotEmpty()) {
                sections[currentSection] = buffer.toString().trim()
            }
            return sections
        }

        private fun parseList(text: String): List<String> {
            return text.lines()
                .map { it.trim() }
                .filter { it.startsWith("- ") || it.startsWith("* ") }
                .map { it.removePrefix("- ").removePrefix("* ").trim() }
                .filter { it.isNotEmpty() }
        }

        private fun extractCanDo(text: String): String {
            val lines = text.lines()
            return lines.filter { it.contains("可以做") || it.contains("Can do") || it.startsWith("✅") }
                .joinToString("\n")
        }

        private fun extractCannotDo(text: String): String {
            val lines = text.lines()
            return lines.filter { it.contains("不做") || it.contains("Cannot") || it.startsWith("❌") }
                .joinToString("\n")
        }
    }

    /**
     * 保存人格定义到工作区
     */
    fun saveToWorkspace(context: Context) {
        val workspaceDir = getWorkspaceDir(context, agentId)
        workspaceDir.mkdirs()

        File(workspaceDir, "SOUL.md").writeText(soul.toMarkdown(agentId))
        File(workspaceDir, "AGENTS.md").writeText(agentSpec.toMarkdown())
        userContext?.let {
            File(workspaceDir, "USER.md").writeText(it.toMarkdown())
        }
    }

    /**
     * 生成系统提示词 — 注入到 LLM 调用中
     */
    fun toSystemPrompt(): String {
        val sb = StringBuilder()
        sb.appendLine("# Agent Identity: $agentId")
        sb.appendLine()
        sb.appendLine("## Core Role")
        sb.appendLine(soul.role)
        sb.appendLine()

        if (soul.personality.isNotEmpty()) {
            sb.appendLine("## Personality")
            soul.personality.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        if (soul.canDo.isNotEmpty()) {
            sb.appendLine("## Capabilities")
            soul.canDo.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        if (soul.cannotDo.isNotEmpty()) {
            sb.appendLine("## Boundaries (DO NOT)")
            soul.cannotDo.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        if (agentSpec.workflow.isNotEmpty()) {
            sb.appendLine("## Workflow")
            agentSpec.workflow.forEachIndexed { i, step -> sb.appendLine("${i + 1}. $step") }
            sb.appendLine()
        }

        if (agentSpec.toolUsage.isNotEmpty()) {
            sb.appendLine("## Available Tools")
            agentSpec.toolUsage.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        userContext?.let { ctx ->
            if (ctx.preferences.isNotEmpty()) {
                sb.appendLine("## User Preferences")
                ctx.preferences.forEach { sb.appendLine("- $it") }
                sb.appendLine()
            }
        }

        sb.appendLine("Tone: ${soul.tone}")
        return sb.toString()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("agent_id", agentId)
        put("soul", soul.toJson())
        put("agent_spec", agentSpec.toJson())
        userContext?.let { put("user_context", it.toJson()) }
    }
}

/**
 * SOUL.md 配置
 */
data class SoulConfig(
    val role: String,
    val personality: List<String>,
    val canDo: List<String>,
    val cannotDo: List<String>,
    val workStyle: List<String>,
    val tone: String
) {
    fun toMarkdown(agentId: String): String = buildString {
        appendLine("# SOUL.md - $agentId")
        appendLine()
        appendLine("## 核心职责")
        appendLine(role)
        appendLine()
        appendLine("## 性格特点")
        personality.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## 能力边界")
        canDo.forEach { appendLine("✅ $it") }
        cannotDo.forEach { appendLine("❌ $it") }
        appendLine()
        appendLine("## 工作风格")
        workStyle.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## 说话语气")
        appendLine(tone)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("personality", JSONArray(personality))
        put("can_do", JSONArray(canDo))
        put("cannot_do", JSONArray(cannotDo))
        put("work_style", JSONArray(workStyle))
        put("tone", tone)
    }
}

/**
 * AGENTS.md 配置
 */
data class AgentSpec(
    val workflow: List<String>,
    val qualityStandards: List<String>,
    val toolUsage: List<String>,
    val constraints: List<String>
) {
    companion object {
        fun default() = AgentSpec(
            workflow = listOf("Receive task", "Analyze context", "Execute", "Report result"),
            qualityStandards = emptyList(),
            toolUsage = emptyList(),
            constraints = emptyList()
        )
    }

    fun toMarkdown(): String = buildString {
        appendLine("# AGENTS.md - Work Specification")
        appendLine()
        appendLine("## 工作流程")
        workflow.forEachIndexed { i, step -> appendLine("${i + 1}. $step") }
        appendLine()
        if (qualityStandards.isNotEmpty()) {
            appendLine("## 质量标准")
            qualityStandards.forEach { appendLine("- $it") }
            appendLine()
        }
        if (toolUsage.isNotEmpty()) {
            appendLine("## 工具调用")
            toolUsage.forEach { appendLine("- $it") }
            appendLine()
        }
        if (constraints.isNotEmpty()) {
            appendLine("## 约束条件")
            constraints.forEach { appendLine("- $it") }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("workflow", JSONArray(workflow))
        put("quality_standards", JSONArray(qualityStandards))
        put("tool_usage", JSONArray(toolUsage))
        put("constraints", JSONArray(constraints))
    }
}

/**
 * USER.md 配置
 */
data class UserContext(
    val preferences: List<String>,
    val history: List<String>,
    val customRules: List<String>
) {
    fun toMarkdown(): String = buildString {
        appendLine("# USER.md - User Context")
        appendLine()
        if (preferences.isNotEmpty()) {
            appendLine("## 偏好")
            preferences.forEach { appendLine("- $it") }
            appendLine()
        }
        if (history.isNotEmpty()) {
            appendLine("## 历史")
            history.forEach { appendLine("- $it") }
            appendLine()
        }
        if (customRules.isNotEmpty()) {
            appendLine("## 自定义规则")
            customRules.forEach { appendLine("- $it") }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("preferences", JSONArray(preferences))
        put("history", JSONArray(history))
        put("custom_rules", JSONArray(customRules))
    }
}

/**
 * 智能体模板 — 用于 AgentFactory 批量创建
 */
data class AgentTemplate(
    val id: String,
    val name: String,
    val type: AgentType,
    val soul: SoulConfig,
    val agentSpec: AgentSpec,
    val preferredModel: String = "auto",
    val preferredProvider: String? = null,
    val deviceCapabilities: List<String> = emptyList()
) {
    enum class AgentType {
        WRITER,         // 写作助手
        CODER,          // 编程助手
        ANALYST,        // 分析师
        PLANNER,        // 规划师
        DEVICE_CONTROL, // 设备控制
        CREATIVE,       // 创意助手
        GENERAL,        // 通用助手
        CUSTOM          // 自定义
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type.name)
        put("soul", soul.toJson())
        put("agent_spec", agentSpec.toJson())
        put("preferred_model", preferredModel)
        preferredProvider?.let { put("preferred_provider", it) }
        put("device_capabilities", JSONArray(deviceCapabilities))
    }
}

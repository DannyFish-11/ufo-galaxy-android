package com.ufo.galaxy.agent

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 多智能体协作系统
 *
 * 支持两种协作模式：
 * - AgentTeam: 异构团队，每个成员有明确角色分工（适合复杂项目）
 * - AgentSwarm: 同构蜂群，所有成员并行处理同类任务（适合批量处理）
 *
 * 链路:
 *   Dashboard/Windows UI → AgentTeam.executeTask(task)
 *     → Coordinator 分解任务
 *     → 各 AgentInstance 并行/串行执行子任务
 *     → Node58 MultiModelRouter → 服务端 MultiLLMRouter → Provider/OneAPI
 *     → 结果聚合 → 返回
 */
class AgentTeam(
    val teamId: String,
    val name: String,
    private val coordinator: AgentInstance? = null
) {
    companion object {
        private const val TAG = "AgentTeam"
    }

    /**
     * 团队成员及其角色
     */
    data class TeamMember(
        val agent: AgentInstance,
        val role: TeamRole,
        val priority: Int = 0
    )

    enum class TeamRole {
        LEADER,      // 团队领导 — 负责任务分解和结果整合
        EXECUTOR,    // 执行者 — 负责具体任务执行
        REVIEWER,    // 审查者 — 负责质量审查
        SPECIALIST,  // 专家 — 特定领域处理
        OBSERVER     // 观察者 — 监控和记录
    }

    enum class ExecutionMode {
        SEQUENTIAL,  // 串行执行 — 按优先级顺序
        PARALLEL,    // 并行执行 — 所有成员同时工作
        PIPELINE,    // 流水线 — 前一个的输出是后一个的输入
        VOTING       // 投票 — 多个成员给出答案，取最优
    }

    private val members = ConcurrentHashMap<String, TeamMember>()
    private val taskHistory = ConcurrentLinkedQueue<TaskRecord>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    data class TaskRecord(
        val taskId: String,
        val task: String,
        val results: Map<String, JSONObject>,
        val finalResult: JSONObject,
        val timestamp: Long
    )

    /**
     * 添加团队成员
     */
    fun addMember(agent: AgentInstance, role: TeamRole, priority: Int = 0) {
        members[agent.id] = TeamMember(agent, role, priority)
        Log.i(TAG, "Added ${agent.id} as $role to team $teamId")
    }

    fun removeMember(agentId: String) {
        members.remove(agentId)
    }

    /**
     * 团队执行任务
     */
    suspend fun executeTask(
        task: String,
        mode: ExecutionMode = ExecutionMode.SEQUENTIAL,
        context: JSONObject? = null
    ): JSONObject {
        val taskId = "task_${System.currentTimeMillis()}"
        Log.i(TAG, "Team $teamId executing task $taskId in $mode mode")

        return when (mode) {
            ExecutionMode.SEQUENTIAL -> executeSequential(taskId, task, context)
            ExecutionMode.PARALLEL -> executeParallel(taskId, task, context)
            ExecutionMode.PIPELINE -> executePipeline(taskId, task, context)
            ExecutionMode.VOTING -> executeVoting(taskId, task, context)
        }
    }

    /**
     * 串行执行 — 按优先级依次执行
     */
    private suspend fun executeSequential(taskId: String, task: String, ctx: JSONObject?): JSONObject {
        val sortedMembers = members.values.sortedByDescending { it.priority }
        val results = mutableMapOf<String, JSONObject>()
        var lastResult: JSONObject? = null

        for (member in sortedMembers) {
            val enrichedTask = if (lastResult != null) {
                "$task\n\n前一步结果: ${lastResult.optString("content", "")}"
            } else {
                task
            }

            val result = member.agent.chat(enrichedTask, ctx)
            results[member.agent.id] = result
            lastResult = result

            if (!result.optBoolean("success", false)) {
                Log.w(TAG, "Member ${member.agent.id} failed, continuing with next")
            }
        }

        val finalResult = JSONObject().apply {
            put("success", true)
            put("task_id", taskId)
            put("mode", "sequential")
            put("team_id", teamId)
            put("member_results", JSONObject().apply {
                results.forEach { (id, r) -> put(id, r) }
            })
            put("final_result", lastResult ?: JSONObject())
        }

        taskHistory.add(TaskRecord(taskId, task, results, finalResult, System.currentTimeMillis()))
        return finalResult
    }

    /**
     * 并行执行 — 所有成员同时处理
     */
    private suspend fun executeParallel(taskId: String, task: String, ctx: JSONObject?): JSONObject {
        val results = ConcurrentHashMap<String, JSONObject>()

        coroutineScope {
            members.values.map { member ->
                async {
                    val result = member.agent.chat(task, ctx)
                    results[member.agent.id] = result
                }
            }.awaitAll()
        }

        val finalResult = JSONObject().apply {
            put("success", true)
            put("task_id", taskId)
            put("mode", "parallel")
            put("team_id", teamId)
            put("member_results", JSONObject().apply {
                results.forEach { (id, r) -> put(id, r) }
            })
            put("total_members", members.size)
            put("successful", results.values.count { it.optBoolean("success", false) })
        }

        taskHistory.add(TaskRecord(taskId, task, results, finalResult, System.currentTimeMillis()))
        return finalResult
    }

    /**
     * 流水线执行 — 输出链式传递
     */
    private suspend fun executePipeline(taskId: String, task: String, ctx: JSONObject?): JSONObject {
        val sortedMembers = members.values.sortedByDescending { it.priority }
        val results = mutableMapOf<String, JSONObject>()
        var pipelineContent = task

        for ((index, member) in sortedMembers.withIndex()) {
            val stageTask = if (index == 0) {
                pipelineContent
            } else {
                "基于上一阶段的输出继续处理:\n\n$pipelineContent"
            }

            val result = member.agent.chat(stageTask, ctx)
            results[member.agent.id] = result

            pipelineContent = result.optString("content", pipelineContent)

            if (!result.optBoolean("success", false)) {
                break
            }
        }

        val finalResult = JSONObject().apply {
            put("success", true)
            put("task_id", taskId)
            put("mode", "pipeline")
            put("team_id", teamId)
            put("pipeline_stages", results.size)
            put("final_content", pipelineContent)
            put("member_results", JSONObject().apply {
                results.forEach { (id, r) -> put(id, r) }
            })
        }

        taskHistory.add(TaskRecord(taskId, task, results, finalResult, System.currentTimeMillis()))
        return finalResult
    }

    /**
     * 投票执行 — 多成员各自给答案，选最优
     */
    private suspend fun executeVoting(taskId: String, task: String, ctx: JSONObject?): JSONObject {
        // 先并行获取所有答案
        val results = ConcurrentHashMap<String, JSONObject>()

        coroutineScope {
            members.values
                .filter { it.role != TeamRole.LEADER }
                .map { member ->
                    async {
                        val result = member.agent.chat(task, ctx)
                        results[member.agent.id] = result
                    }
                }.awaitAll()
        }

        // 如果有 Leader，让 Leader 选出最佳答案
        val leader = members.values.find { it.role == TeamRole.LEADER }
        val bestResult = if (leader != null) {
            val summaryTask = buildString {
                appendLine("以下是团队成员对任务的回答，请选出最佳答案并整合：")
                appendLine()
                appendLine("任务: $task")
                appendLine()
                results.forEach { (id, r) ->
                    appendLine("--- $id 的回答 ---")
                    appendLine(r.optString("content", ""))
                    appendLine()
                }
                appendLine("请整合以上回答，给出最终的最优结果。")
            }
            leader.agent.chat(summaryTask, ctx)
        } else {
            // 无 Leader 则取第一个成功结果
            results.values.firstOrNull { it.optBoolean("success", false) }
                ?: JSONObject().apply { put("success", false); put("error", "No successful results") }
        }

        val finalResult = JSONObject().apply {
            put("success", true)
            put("task_id", taskId)
            put("mode", "voting")
            put("team_id", teamId)
            put("votes", results.size)
            put("best_result", bestResult)
            put("member_results", JSONObject().apply {
                results.forEach { (id, r) -> put(id, r) }
            })
        }

        taskHistory.add(TaskRecord(taskId, task, results, finalResult, System.currentTimeMillis()))
        return finalResult
    }

    fun getMembers(): JSONObject = JSONObject().apply {
        put("team_id", teamId)
        put("name", name)
        put("members", JSONArray().apply {
            members.forEach { (_, member) ->
                put(JSONObject().apply {
                    put("agent_id", member.agent.id)
                    put("agent_name", member.agent.name)
                    put("role", member.role.name)
                    put("priority", member.priority)
                    put("state", member.agent.state.name)
                })
            }
        })
        put("count", members.size)
    }

    fun shutdown() {
        scope.cancel()
        members.clear()
    }
}

/**
 * AgentSwarm — 同构蜂群模式
 *
 * 多个相同角色的智能体并行处理批量任务。
 * 适用场景：批量翻译、批量分析、大规模数据处理。
 */
class AgentSwarm(
    val swarmId: String,
    val name: String,
    private val agentFactory: AgentFactory,
    private val templateId: String,
    private val size: Int
) {
    companion object {
        private const val TAG = "AgentSwarm"
    }

    private val workers = mutableListOf<AgentInstance>()
    private val taskQueue = ConcurrentLinkedQueue<SwarmTask>()

    data class SwarmTask(
        val id: String,
        val content: String,
        val context: JSONObject? = null
    )

    /**
     * 初始化蜂群 — 创建指定数量的同类智能体
     */
    fun initialize() {
        repeat(size) { i ->
            val agent = agentFactory.createAgentFromTemplate(
                templateId,
                agentId = "${swarmId}_worker_$i"
            )
            if (agent != null) {
                workers.add(agent)
            }
        }
        Log.i(TAG, "Swarm $swarmId initialized with ${workers.size} workers")
    }

    /**
     * 批量执行任务 — 将任务均匀分配给所有 worker
     */
    suspend fun executeBatch(tasks: List<String>, context: JSONObject? = null): JSONObject {
        val results = ConcurrentHashMap<Int, JSONObject>()

        coroutineScope {
            tasks.mapIndexed { index, task ->
                val worker = workers[index % workers.size]
                async {
                    val result = worker.chat(task, context)
                    results[index] = result
                }
            }.awaitAll()
        }

        return JSONObject().apply {
            put("success", true)
            put("swarm_id", swarmId)
            put("total_tasks", tasks.size)
            put("workers_used", workers.size)
            put("results", JSONArray().apply {
                (0 until tasks.size).forEach { i ->
                    put(results[i] ?: JSONObject().apply { put("error", "No result") })
                }
            })
            put("successful", results.values.count { it.optBoolean("success", false) })
        }
    }

    /**
     * 单任务蜂群处理 — 所有 worker 给出不同视角的回答
     */
    suspend fun swarmProcess(task: String, context: JSONObject? = null): JSONObject {
        val results = ConcurrentHashMap<String, JSONObject>()

        coroutineScope {
            workers.map { worker ->
                async {
                    val result = worker.chat(task, context)
                    results[worker.id] = result
                }
            }.awaitAll()
        }

        return JSONObject().apply {
            put("success", true)
            put("swarm_id", swarmId)
            put("workers", workers.size)
            put("results", JSONObject().apply {
                results.forEach { (id, r) -> put(id, r) }
            })
        }
    }

    fun getStatus(): JSONObject = JSONObject().apply {
        put("swarm_id", swarmId)
        put("name", name)
        put("template", templateId)
        put("workers", workers.size)
        put("worker_states", JSONArray().apply {
            workers.forEach { put(it.state.name) }
        })
    }

    fun shutdown() {
        workers.forEach { it.shutdown() }
        workers.clear()
    }
}

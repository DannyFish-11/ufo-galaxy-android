package com.ufo.galaxy.bridge

import com.ufo.galaxy.shared.protocol.A2A
import com.ufo.galaxy.shared.protocol.A2AEnvelope
import com.ufo.galaxy.shared.protocol.A2ALink
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 手机上的桥接 Agent(跨设备整体方案阶段 3)。
 *
 * 手机打开跨设备、登录进网络后,中心智能体派一个桥接 Agent 过来(`agent_dispatch`);这里收下它,
 * 把手机本地正在跑的上下文交给中心(`context_handoff`),从此手机上说的话进中心智能体的同一个会话
 * (`conversation`),中心要动手机时把整段目标交过来(`delegate_goal`)由手机本地循环去做,关跨设备
 * 时要回上下文(`context_return`)。全部走对外 A2A([A2ALink] 保证必达)。
 *
 * 纯 Kotlin,不碰 Android:收发、界面、本地执行都经 [Host] 交给应用层(`BridgeAgent` 接线),
 * 所以能在 JVM 上直接测。线上帧与 V2 `core/phone_bridge.py` 对得上。
 *
 * @param deviceId 本机设备号(与登记用的同一个,配对后可能变,所以每次现取);本端 Agent 叫 `agent:<deviceId>`,中心叫 `center`
 * @param send 把一条线上帧(JSON 文本)发出去,发不出去返回 false(要确认的消息留在链路里,重连补发)
 */
class BridgeAgentRuntime(
    private val deviceId: () -> String,
    private val send: (String) -> Boolean,
    private val host: Host,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** 应用层要提供的四件事。都要快速返回;久的事(跑目标)自己起协程,完成时调回调。 */
    interface Host {
        /** 交给中心的本地上下文:`dialog`(最近对话)、`goal`、`loop_progress`、`trajectory`;屏幕默认不带。 */
        fun handoffContext(): JsonObject

        /** 中心智能体的回答到了,显示给人。 */
        fun onReply(text: String, success: Boolean)

        /** 中心交来一整段目标,用手机本地循环去做;[progress] 可多次调,[done] 调一次。 */
        fun runGoal(taskId: String, goal: String, constraints: List<String>, progress: (JsonObject) -> Unit, done: (JsonObject) -> Unit)

        /** 中心交还了上下文(摘要 + 没做完的事),或收回了桥接 Agent;从此手机自己接着来。 */
        fun onReturned(summary: String, openItems: List<String>)
    }

    @Volatile
    private var link: A2ALink? = null

    /** 桥接 Agent 装上了没有。装上之后人说的话走中心智能体([say])。 */
    @Volatile
    var installed: Boolean = false
        private set

    /** 中心给的说明(自治档位、要确认的动作……),原样留着给界面和执行器看。 */
    @Volatile
    var spec: JsonObject = JsonObject(emptyMap())
        private set

    /** 收下中心发来的一条 `a2a` 帧(整帧 JSON 文本)。 */
    fun onFrame(frameJson: String) {
        val frame = try {
            Json.parseToJsonElement(frameJson) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return
        val env = (frame["payload"] as? JsonObject)?.let { A2AEnvelope.fromJson(it) } ?: return
        if (env.fromAgent != A2A_CENTER) return // 只认中心
        val l = linkFor(env.sessionId)
        val got = l.accept(env)
        got.ack?.let { send(it.toAipFrame(deviceId()).toString()) }
        got.deliver.forEach { handle(it) }
    }

    /** 人在手机上说了一句话:桥接 Agent 装上了就交给中心智能体,返回 true;没装上返回 false(调用方走原来的路)。 */
    fun say(text: String): Boolean {
        if (!installed || text.isBlank()) return false
        emit(A2A.Kind.CONVERSATION, buildJsonObject { put("text", text.trim()); put("role", "user") })
        return true
    }

    /** 要关跨设备:请中心交还上下文。没装桥接 Agent 时返回 false。 */
    fun requestReturn(): Boolean {
        if (!installed) return false
        emit(A2A.Kind.CONTEXT_RETURN, buildJsonObject { put("request", true) })
        return true
    }

    /** 重新登记后补发还没确认的(按序号)。返回补发了几条。 */
    fun resendUnacked(): Int = link?.unacked()?.count { send(it.toAipFrame(deviceId()).toString()) } ?: 0

    private fun linkFor(sessionId: String): A2ALink {
        val cur = link
        if (cur != null && cur.sessionId == sessionId) return cur
        // 中心换了会话(重新派了一次桥接 Agent):开一条新链路,上一段的安装作废
        installed = false
        return A2ALink(sessionId, "agent:${deviceId()}", A2A_CENTER, clock = clock, newId = newId).also { link = it }
    }

    private fun emit(kind: String, body: JsonObject, taskId: String = "", traceId: String = "", ackRequired: Boolean = true) {
        val l = link ?: return
        val env = l.make(kind, body, taskId = taskId, traceId = traceId, ackRequired = ackRequired)
        send(env.toAipFrame(deviceId()).toString())
    }

    private fun handle(env: A2AEnvelope) {
        when (env.kind) {
            A2A.Kind.AGENT_DISPATCH -> {
                // 断线重连后中心会再派一次:已经装着(同一条链路)就只回一句「装着呢」,上下文不再交一遍
                val resumed = installed
                spec = env.body
                installed = true
                emit(A2A.Kind.AGENT_INSTALLED, buildJsonObject {
                    put("runtime", "android")
                    put("bridge_version", str(env.body, "bridge_version"))
                    put("resumed", resumed)
                }, traceId = env.traceId)
                if (!resumed) emit(A2A.Kind.CONTEXT_HANDOFF, host.handoffContext(), traceId = env.traceId)
            }
            A2A.Kind.CONVERSATION -> {
                val text = str(env.body, "text")
                if (text.isNotEmpty()) host.onReply(text, (env.body["success"] as? JsonPrimitive)?.booleanOrNull ?: true)
            }
            A2A.Kind.DELEGATE_GOAL -> {
                val goal = str(env.body, "goal")
                val constraints = (env.body["constraints"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
                if (goal.isEmpty()) {
                    emit(A2A.Kind.DELEGATE_RESULT, buildJsonObject {
                        put("success", false)
                        put("error", "empty goal")
                    }, taskId = env.taskId, traceId = env.traceId)
                    return
                }
                host.runGoal(
                    env.taskId, goal, constraints,
                    { p -> emit(A2A.Kind.DELEGATE_PROGRESS, p, taskId = env.taskId, traceId = env.traceId, ackRequired = false) },
                    { r -> emit(A2A.Kind.DELEGATE_RESULT, r, taskId = env.taskId, traceId = env.traceId) },
                )
            }
            A2A.Kind.TOOL_CALL -> {
                // 单个动作中心走 command 帧(devices__invoke);这里如实回「不经 A2A 调」,不让它干等
                emit(A2A.Kind.TOOL_RESULT, buildJsonObject {
                    put("success", false)
                    put("error", "tool calls go through command frames on this device")
                }, taskId = env.taskId, traceId = env.traceId)
            }
            A2A.Kind.CONTEXT_RETURN, A2A.Kind.RECALL -> {
                installed = false
                val items = (env.body["open_items"] as? JsonArray)?.mapNotNull {
                    ((it as? JsonObject)?.get("task_id") as? JsonPrimitive)?.contentOrNull
                } ?: emptyList()
                host.onReturned(str(env.body, "summary"), items)
            }
            else -> Unit // agent_installed / context_handoff / delegate_* 是手机发的,中心不会发回来
        }
    }

    private fun str(o: JsonObject, k: String): String = (o[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    companion object {
        /** 中心智能体在 A2A 里的名字(V2 `core/a2a_link.CENTER_AGENT`)。 */
        const val A2A_CENTER = "center"

        /** 报能力时声明的桥接 Agent 版本(V2 `core/phone_bridge.BRIDGE_VERSION`);V2 只给声明了的设备派。 */
        const val BRIDGE_VERSION = "bridge/1"
    }
}

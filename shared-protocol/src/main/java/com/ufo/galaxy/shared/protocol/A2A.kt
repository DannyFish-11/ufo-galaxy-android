package com.ufo.galaxy.shared.protocol

import java.util.TreeMap
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 对外 A2A —— 中心智能体与派出去的 Agent 之间的通话协议(跨设备整体方案阶段 2)。
 *
 * 中心智能体内部的多 Agent 协作已经有(V2 `core/agent_team.py`);这里是**对外**那一套:中心与派到
 * 手机上的桥接 Agent、Linux / 云载体上的 Agent 之间。它跑在 AIP v3 上:一条 [MsgType.A2A] 帧,
 * 信封 [A2AEnvelope] 放在 `payload` 里。
 *
 * 「必达」由 [A2ALink] 保证,两端语义相同(V2 `core/a2a_link.py`):
 *  * 发送方留着没收到确认的消息,重连后补发([A2ALink.unacked]);
 *  * 接收方按消息号去重,对每条要确认的消息回 [Kind.ACK](重复的也回,发送方才会停);
 *  * 同一会话按序号排序交付,先到的后面的消息先缓着,等前面的补上。
 *
 * 哪一端重启都不会卡住或吞掉消息:每条信封带发送方链路的 `epoch`(这一次的身份,重启就换)和 `floor`
 * (发送方手里还没确认的最小序号,比它小的都已了结)。接收方看到对端换了 `epoch` 就从 `floor` 重新数;
 * 同一个 `epoch` 里 `floor` 只往前推,推过去的缺口(丢了、又不要确认的消息)不再等。
 *
 * 纯 Kotlin,不碰 Android —— 手机与手表都引用这个模块。
 */
object A2A {
    const val PROTOCOL = "a2a/1"

    /** 承载 A2A 信封的 AIP v3 帧类型。 */
    val AIP_TYPE: String = MsgType.A2A.value

    /** 六类消息(加确认)。线值与 V2 `core/a2a_link.Kind` 逐字一致。 */
    object Kind {
        /** 中心 → 载体:派一个桥接 Agent 过去(接入握手)。 */
        const val AGENT_DISPATCH = "agent_dispatch"

        /** 载体 → 中心:已装好。 */
        const val AGENT_INSTALLED = "agent_installed"

        /** 载体 → 中心:本地上下文交接(对话、目标、轨迹、屏幕状态)。 */
        const val CONTEXT_HANDOFF = "context_handoff"

        /** 双向:人说的话上行,回答与进度下行。 */
        const val CONVERSATION = "conversation"

        /** 中心 → 载体:整段目标交给载体做。 */
        const val DELEGATE_GOAL = "delegate_goal"

        /** 载体 → 中心:委派目标的进度。 */
        const val DELEGATE_PROGRESS = "delegate_progress"

        /** 载体 → 中心:委派目标的结果。 */
        const val DELEGATE_RESULT = "delegate_result"

        /** 双向:调对方的一个能力。 */
        const val TOOL_CALL = "tool_call"

        /** 双向:能力调用的结果。 */
        const val TOOL_RESULT = "tool_result"

        /** 中心 → 载体:上下文交还(关闭跨设备时:摘要 + 未完成事项)。 */
        const val CONTEXT_RETURN = "context_return"

        /** 中心 → 载体:收回派出去的 Agent。 */
        const val RECALL = "recall"

        /** 确认。不占序号、不再确认。 */
        const val ACK = "ack"

        val ALL: Set<String> = setOf(
            AGENT_DISPATCH, AGENT_INSTALLED, CONTEXT_HANDOFF, CONVERSATION,
            DELEGATE_GOAL, DELEGATE_PROGRESS, DELEGATE_RESULT,
            TOOL_CALL, TOOL_RESULT, CONTEXT_RETURN, RECALL, ACK,
        )
    }
}

/**
 * 一条 A2A 消息。线上的键是 snake_case,与 V2 `core/a2a_link.Envelope` 一致。
 *
 * @param seq 同一条链路、同一方向上的序号,从 1 起;确认帧为 0(不排序)
 * @param ackRequired 要不要对方确认;要的话发送方会留着它直到收到确认
 */
data class A2AEnvelope(
    val msgId: String,
    val seq: Long,
    val sessionId: String,
    val fromAgent: String,
    val toAgent: String,
    val kind: String,
    val body: JsonObject = JsonObject(emptyMap()),
    val taskId: String = "",
    val traceId: String = "",
    val ackRequired: Boolean = true,
    val ts: Long = 0L,
    val epoch: String = "",
    val floor: Long = 0L,
    val protocol: String = A2A.PROTOCOL,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("protocol", protocol)
        put("msg_id", msgId)
        put("seq", seq)
        put("session_id", sessionId)
        put("from_agent", fromAgent)
        put("to_agent", toAgent)
        put("kind", kind)
        put("body", body)
        put("task_id", taskId)
        put("trace_id", traceId)
        put("ack_required", ackRequired)
        put("ts", ts)
        put("epoch", epoch)
        put("floor", floor)
    }

    /** 包成一条 AIP v3 帧(信封进 payload)。 */
    fun toAipFrame(deviceId: String): JsonObject = buildJsonObject {
        put("version", "3.0")
        put("type", A2A.AIP_TYPE)
        put("device_id", deviceId)
        put("timestamp", ts)
        put("payload", toJson())
    }

    companion object {
        /** 解析一条信封;协议不对、缺必填字段、kind 不认识时返回 null(不猜)。 */
        fun fromJson(o: JsonObject): A2AEnvelope? {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (str("protocol") != A2A.PROTOCOL) return null
            val kind = str("kind")
            if (kind !in A2A.Kind.ALL) return null
            val msgId = str("msg_id").ifBlank { return null }
            val seq = (o["seq"] as? JsonPrimitive)?.longOrNull ?: return null
            return A2AEnvelope(
                msgId = msgId,
                seq = seq,
                sessionId = str("session_id"),
                fromAgent = str("from_agent"),
                toAgent = str("to_agent"),
                kind = kind,
                body = o["body"] as? JsonObject ?: JsonObject(emptyMap()),
                taskId = str("task_id"),
                traceId = str("trace_id"),
                ackRequired = (o["ack_required"] as? JsonPrimitive)?.booleanOrNull ?: true,
                ts = (o["ts"] as? JsonPrimitive)?.longOrNull ?: 0L,
                epoch = str("epoch"),
                floor = ((o["floor"] as? JsonPrimitive)?.longOrNull ?: 0L).coerceAtLeast(0L),
            )
        }
    }
}

/**
 * 一条 A2A 链路:一个会话里、本端 Agent 与对端 Agent 之间。负责「必达」:编号、留存未确认、
 * 去重、按序交付。不管传输 —— 调用方把 [make] 出来的信封发出去,把收到的交给 [accept]。
 */
class A2ALink(
    val sessionId: String,
    val localAgent: String,
    val peerAgent: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** [accept] 的结果:按序该交付的消息,和要回给对方的确认(没有就是 null)。 */
    data class Accepted(val deliver: List<A2AEnvelope>, val ack: A2AEnvelope?)

    /** 这条链路这一次的身份;进程重启后是新的一条链路、新的身份。 */
    val epoch: String = newId()

    private var nextSeq = 1L
    private var expectedSeq = 1L
    private var peerEpoch = ""
    private val outbox = LinkedHashMap<String, A2AEnvelope>()
    private val seen = LinkedHashSet<String>()
    private val buffer = TreeMap<Long, A2AEnvelope>()

    /** 造一条发给对端的消息;要确认的会留在发件箱里,直到 [accept] 收到它的确认。 */
    @Synchronized
    fun make(
        kind: String,
        body: JsonObject = JsonObject(emptyMap()),
        taskId: String = "",
        traceId: String = "",
        ackRequired: Boolean = true,
    ): A2AEnvelope {
        require(kind in A2A.Kind.ALL && kind != A2A.Kind.ACK) { "unknown or reserved A2A kind: $kind" }
        val env = A2AEnvelope(
            msgId = newId(),
            seq = nextSeq++,
            sessionId = sessionId,
            fromAgent = localAgent,
            toAgent = peerAgent,
            kind = kind,
            body = body,
            taskId = taskId,
            traceId = traceId,
            ackRequired = ackRequired,
            ts = clock(),
            epoch = epoch,
        )
        if (ackRequired) outbox[env.msgId] = env
        return env.copy(floor = floor(env.seq)).also { if (ackRequired) outbox[it.msgId] = it }
    }

    private fun floor(default: Long): Long = outbox.values.minOfOrNull { it.seq } ?: default

    /** 还没收到确认的消息,按序号排好(带上此刻的 `floor`)—— 重连后照这个顺序补发。 */
    @Synchronized
    fun unacked(): List<A2AEnvelope> {
        val f = floor(nextSeq)
        return outbox.values.sortedBy { it.seq }.map { it.copy(floor = f) }
    }

    /** 收下对端的一条消息。 */
    @Synchronized
    fun accept(env: A2AEnvelope): Accepted {
        if (env.kind == A2A.Kind.ACK) {
            (env.body["ack_of"] as? JsonPrimitive)?.contentOrNull?.let { outbox.remove(it) }
            return Accepted(emptyList(), null)
        }
        val ack = if (env.ackRequired) ackFor(env) else null
        if (!seen.add(env.msgId)) return Accepted(emptyList(), ack) // 重复:不交付,但照样确认
        trimSeen()
        if (env.seq <= 0L) return Accepted(listOf(env), ack) // 不排序的消息
        val out = ArrayList<A2AEnvelope>()
        if (env.epoch.isNotEmpty() && env.epoch != peerEpoch) {
            // 对端换了一次身份(它重启了),或者我们自己是新的、还不认识它:从它说的 floor 重新数。
            // 上一次身份留下、缓着等缺口的已经确认过,缺口再也不会来了 —— 按序交出,不丢。
            peerEpoch = env.epoch
            while (buffer.isNotEmpty()) out.add(buffer.pollFirstEntry().value)
            expectedSeq = if (env.floor > 0L) env.floor else 1L
        } else if (env.floor > expectedSeq) {
            // 比 floor 小的对端都已了结:缓着的按序交出,缺口(丢了、又不要确认的)不再等
            while (buffer.isNotEmpty() && buffer.firstKey() < env.floor) out.add(buffer.pollFirstEntry().value)
            expectedSeq = env.floor
        }
        if (env.seq < expectedSeq) return Accepted(out, ack)
        buffer[env.seq] = env
        while (true) {
            val next = buffer.remove(expectedSeq) ?: break
            out.add(next)
            expectedSeq++
        }
        if (buffer.size > MAX_BUFFERED) {
            // 缺口一直补不上(对端发件箱也丢了):别把整条链路卡死,按序交出缓着的,跳过缺口。
            while (buffer.isNotEmpty()) {
                val (s, e) = buffer.pollFirstEntry().let { it.key to it.value }
                out.add(e)
                expectedSeq = s + 1
            }
        }
        return Accepted(out, ack)
    }

    private fun ackFor(env: A2AEnvelope) = A2AEnvelope(
        msgId = newId(),
        seq = 0L,
        sessionId = sessionId,
        fromAgent = localAgent,
        toAgent = env.fromAgent,
        kind = A2A.Kind.ACK,
        body = buildJsonObject { put("ack_of", env.msgId) },
        taskId = env.taskId,
        traceId = env.traceId,
        ackRequired = false,
        ts = clock(),
        epoch = epoch,
    )

    private fun trimSeen() {
        while (seen.size > MAX_SEEN) seen.remove(seen.first())
    }

    companion object {
        /** 去重记住的消息号上限。 */
        const val MAX_SEEN = 4096

        /** 缓着等缺口的消息上限,超过就跳过缺口。 */
        const val MAX_BUFFERED = 256
    }
}

package com.ufo.galaxy.shared.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
// Gson 的 @SerializedName 与 kotlinx 的 @SerialName 并列:MsgType 是线格式枚举,
// 生产 canonical 路径走 kotlinx(尊重 @SerialName),但仍有 deprecated 的 gson 路径,
// 且协议单测用 Gson 校验线值。加上 @SerializedName 让【任一序列化器】都输出
// 小写线值(如 device_register)而非枚举常量名(DEVICE_REGISTER),彻底消除歧义。
import com.google.gson.annotations.SerializedName

/**
 * Unified AIP v3 message type identifiers shared across Android and Wear OS.
 *
 * This enum merges the Android-side [com.ufo.galaxy.protocol.MsgType] and the
 * Wear-OS-side [com.galaxy.wear.data.MsgType] into a single canonical source of
 * truth.  Both projects consume this module so that wire-format values are
 * guaranteed to be identical.
 *
 * Values use snake_case to match the server-side MsgType enum exactly.
 *
 * ## Merge rules applied
 * - Every type present in the Android enum is retained (it is the superset).
 * - Wear-OS-specific types (AUTH, PING, VOICE_QUERY, PHASE_REPORT) are added.
 * - Advanced / transitional types are grouped at the end and marked
 *   `@status minimal-compat` or `@status promoted` as appropriate.
 */
@Serializable
enum class MsgType(val value: String) {

    /** SECURITY-FIX: Fallback for null or unrecognised message types. */
    @SerialName("unknown")
    @SerializedName("unknown")
    UNKNOWN("unknown"),

    // ── Core protocol types ──────────────────────────────────────────────────────────────

    @SerialName("auth")
    @SerializedName("auth")
    AUTH("auth"),

    @SerialName("ping")
    @SerializedName("ping")
    PING("ping"),

    @SerialName("command")
    @SerializedName("command")
    COMMAND("command"),

    @SerialName("command_result")
    @SerializedName("command_result")
    COMMAND_RESULT("command_result"),

    @SerialName("event")
    @SerializedName("event")
    EVENT("event"),

    @SerialName("device_register")
    @SerializedName("device_register")
    DEVICE_REGISTER("device_register"),

    @SerialName("capability_report")
    @SerializedName("capability_report")
    CAPABILITY_REPORT("capability_report"),

    @SerialName("heartbeat")
    @SerializedName("heartbeat")
    HEARTBEAT("heartbeat"),

    @SerialName("heartbeat_ack")
    @SerializedName("heartbeat_ack")
    HEARTBEAT_ACK("heartbeat_ack"),

    @SerialName("voice_query")
    @SerializedName("voice_query")
    VOICE_QUERY("voice_query"),

    @SerialName("phase_report")
    @SerializedName("phase_report")
    PHASE_REPORT("phase_report"),

    // ── Task pipeline types ──────────────────────────────────────────────────────────────

    @SerialName("task_submit")
    @SerializedName("task_submit")
    TASK_SUBMIT("task_submit"),

    @SerialName("task_assign")
    @SerializedName("task_assign")
    TASK_ASSIGN("task_assign"),

    @SerialName("task_result")
    @SerializedName("task_result")
    TASK_RESULT("task_result"),

    @SerialName("goal_execution")
    @SerializedName("goal_execution")
    GOAL_EXECUTION("goal_execution"),

    @SerialName("parallel_subtask")
    @SerializedName("parallel_subtask")
    PARALLEL_SUBTASK("parallel_subtask"),

    @SerialName("goal_result")
    @SerializedName("goal_result")
    GOAL_RESULT("goal_result"),

    @SerialName("goal_execution_result")
    @SerializedName("goal_execution_result")
    GOAL_EXECUTION_RESULT("goal_execution_result"),

    @SerialName("task_cancel")
    @SerializedName("task_cancel")
    TASK_CANCEL("task_cancel"),

    @SerialName("cancel_result")
    @SerializedName("cancel_result")
    CANCEL_RESULT("cancel_result"),

    // ── Mesh session types ───────────────────────────────────────────────────────────────

    @SerialName("mesh_join")
    @SerializedName("mesh_join")
    MESH_JOIN("mesh_join"),

    @SerialName("mesh_leave")
    @SerializedName("mesh_leave")
    MESH_LEAVE("mesh_leave"),

    @SerialName("mesh_result")
    @SerializedName("mesh_result")
    MESH_RESULT("mesh_result"),

    // ── Cross-device sync types ──────────────────────────────────────────────────────────

    @SerialName("state_event")
    @SerializedName("state_event")
    STATE_EVENT("state_event"),

    @SerialName("liquid_event")
    @SerializedName("liquid_event")
    LIQUID_EVENT("liquid_event"),

    // ── Diagnostic / reporting types ─────────────────────────────────────────────────────

    @SerialName("diagnostics_payload")
    @SerializedName("diagnostics_payload")
    DIAGNOSTICS_PAYLOAD("diagnostics_payload"),

    @SerialName("device_state_snapshot")
    @SerializedName("device_state_snapshot")
    DEVICE_STATE_SNAPSHOT("device_state_snapshot"),

    @SerialName("device_execution_event")
    @SerializedName("device_execution_event")
    DEVICE_EXECUTION_EVENT("device_execution_event"),

    @SerialName("device_perception_emission")
    @SerializedName("device_perception_emission")
    DEVICE_PERCEPTION_EMISSION("device_perception_emission"),

    @SerialName("device_readiness_report")
    @SerializedName("device_readiness_report")
    DEVICE_READINESS_REPORT("device_readiness_report"),

    @SerialName("device_governance_report")
    @SerializedName("device_governance_report")
    DEVICE_GOVERNANCE_REPORT("device_governance_report"),

    @SerialName("device_acceptance_report")
    @SerializedName("device_acceptance_report")
    DEVICE_ACCEPTANCE_REPORT("device_acceptance_report"),

    @SerialName("device_strategy_report")
    @SerializedName("device_strategy_report")
    DEVICE_STRATEGY_REPORT("device_strategy_report"),

    @SerialName("device_audit_report")
    @SerializedName("device_audit_report")
    DEVICE_AUDIT_REPORT("device_audit_report"),

    @SerialName("reconciliation_signal")
    @SerializedName("reconciliation_signal")
    RECONCILIATION_SIGNAL("reconciliation_signal"),

    // ── PR-3: Handoff / takeover contract ────────────────────────────────────────────────

    @SerialName("takeover_request")
    @SerializedName("takeover_request")
    TAKEOVER_REQUEST("takeover_request"),

    @SerialName("takeover_response")
    @SerializedName("takeover_response")
    TAKEOVER_RESPONSE("takeover_response"),

    @SerialName("delegated_execution_signal")
    @SerializedName("delegated_execution_signal")
    DELEGATED_EXECUTION_SIGNAL("delegated_execution_signal"),

    @SerialName("handoff_envelope_v2")
    @SerializedName("handoff_envelope_v2")
    HANDOFF_ENVELOPE_V2("handoff_envelope_v2"),

    @SerialName("handoff_envelope_v2_result")
    @SerializedName("handoff_envelope_v2_result")
    HANDOFF_ENVELOPE_V2_RESULT("handoff_envelope_v2_result"),

    // ── Operator / action types ──────────────────────────────────────────────────────────

    @SerialName("operator_action_request")
    @SerializedName("operator_action_request")
    OPERATOR_ACTION_REQUEST("operator_action_request"),

    @SerialName("operator_action_result")
    @SerializedName("operator_action_result")
    OPERATOR_ACTION_RESULT("operator_action_result"),

    /** DECISION_REQUEST: V2 asks the human to choose (human-in-the-loop / HITL).
     *  Drives a decision notification; user reply goes back via human_input. */
    @SerialName("decision_request")
    @SerializedName("decision_request")
    DECISION_REQUEST("decision_request"),

    /** DECISION_WITHDRAW: 这条决策不用管了,把通知收起来。
     *
     * 一条 decision_request 会被**并行分叉**给所有连着的手表与手机。服务端处理了
     * 重复回答(first reply wins),但此前没有任何东西告诉其余设备撤回通知 ——
     * 于是在手表上答完之后,手机上那条还挂着。点它服务端是 no-op,可手机本地会把
     * 通知消掉,用户以为自己答了,实际什么都没发生;更糟的是他可能在那边给了个
     * **不同**的答案。
     *
     * 这是 SIP 分叉的 CANCEL(RFC 3261 §16.7)那一步。
     *
     * payload: `decision_id` + `reason`(封闭枚举:answered_elsewhere / timed_out /
     * cancelled / superseded)。**不带答案** —— 设备只需要把这条收起来,不需要
     * 知道别人选了什么。
     */
    DECISION_WITHDRAW("decision_withdraw"),

    /** EXECUTION_PROPOSAL: 中心问一句「这件事你能不能、愿不愿意做」。
     *
     * **只说做什么,不说怎么做。** 怎么做是本机 Agent 自己的事 —— 它有自己的降级链
     * 和就位自检。中心替它决定,就是第二份实现,而且中心手里那份状态是几百毫秒前的。
     *
     * payload: `proposal_id` · `intent` · `deadline_ms` · `risk_level`。
     */
    EXECUTION_PROPOSAL("execution_proposal"),

    /** EXECUTION_COMMITMENT: 本机判断之后的回答。
     *
     * `valid_until_ms` **不是可选的**:设备说"我能做"时看到的那一屏,几秒之后可能
     * 已经不在了。没有有效期的承诺等于让中心去赌"从收到承诺到真正派发之间什么都
     * 没变"。过期的承诺一律作废重来。
     *
     * `decline_reason` 是封闭枚举(busy / not_ready / policy_declined /
     * no_permission / unsupported),不是自由文本 —— 中心要据此换策略:忙就换一台,
     * 不就绪就等一会儿再问同一台,权限不足就去问人。
     */
    EXECUTION_COMMITMENT("execution_commitment"),

    /** EXECUTION_COMMIT: 选定了,就是你(或者:这次不是你)。
     *
     * 落选的设备**也要收到**。否则它会一直占着为这次提议留的资源,而且不知道自己
     * 已经出局 —— 和决策分叉不撤回是同一个形状的问题。
     */
    EXECUTION_COMMIT("execution_commit"),

    // ── Advanced / low-priority capability channels ──────────────────────────────────────
    // These types receive minimal-compat handling (log + optional ack) except where promoted.

    @SerialName("relay")
    @SerializedName("relay")
    RELAY("relay"),

    @SerialName("forward")
    @SerializedName("forward")
    FORWARD("forward"),

    @SerialName("reply")
    @SerializedName("reply")
    REPLY("reply"),

    @SerialName("ack")
    @SerializedName("ack")
    ACK("ack"),

    @SerialName("hybrid_execute")
    @SerializedName("hybrid_execute")
    HYBRID_EXECUTE("hybrid_execute"),

    @SerialName("hybrid_result")
    @SerializedName("hybrid_result")
    HYBRID_RESULT("hybrid_result"),

    @SerialName("hybrid_degrade")
    @SerializedName("hybrid_degrade")
    HYBRID_DEGRADE("hybrid_degrade"),

    @SerialName("rag_query")
    @SerializedName("rag_query")
    RAG_QUERY("rag_query"),

    @SerialName("rag_result")
    @SerializedName("rag_result")
    RAG_RESULT("rag_result"),

    @SerialName("code_execute")
    @SerializedName("code_execute")
    CODE_EXECUTE("code_execute"),

    @SerialName("code_result")
    @SerializedName("code_result")
    CODE_RESULT("code_result"),

    @SerialName("peer_announce")
    @SerializedName("peer_announce")
    PEER_ANNOUNCE("peer_announce"),

    @SerialName("peer_exchange")
    @SerializedName("peer_exchange")
    PEER_EXCHANGE("peer_exchange"),

    @SerialName("mesh_topology")
    @SerializedName("mesh_topology")
    MESH_TOPOLOGY("mesh_topology"),

    @SerialName("wake_event")
    @SerializedName("wake_event")
    WAKE_EVENT("wake_event"),

    @SerialName("session_migrate")
    @SerializedName("session_migrate")
    SESSION_MIGRATE("session_migrate"),

    @SerialName("coord_sync")
    @SerializedName("coord_sync")
    COORD_SYNC("coord_sync"),

    @SerialName("broadcast")
    @SerializedName("broadcast")
    BROADCAST("broadcast"),

    @SerialName("lock")
    @SerializedName("lock")
    LOCK("lock"),

    @SerialName("unlock")
    @SerializedName("unlock")
    UNLOCK("unlock"),

    // ── 实时语音通话（设备 ↔ AI，像打电话一样）──────────────────────────────
    //
    // 分工：**信令与事件走 AIP，音频走 WebRTC 媒体轨**。
    //
    // 音频不走 AIP 的理由：AIP WebSocket 建在 TCP 上，丢一个包后面全被堵住，而重传
    // 回来的是过期音频 —— 实时语音里迟到的音频没有价值，只会把延迟越堆越高。手表
    // 天线小、贴在手腕上、跟着人移动，本就是最容易落在弱信号下的设备；独立流量
    // (LTE) 下网络更不可控。所以媒体面必须走能丢包补偿、能抖动缓冲的 WebRTC。
    //
    // 信令仍走 AIP 的理由：设备已经连着它，不必为通话再开一条连接；而转写、回复
    // 增量这些文字事件本来就属于控制面。

    @SerialName("voice_call_start")
    @SerializedName("voice_call_start")
    VOICE_CALL_START("voice_call_start"),

    @SerialName("voice_call_accepted")
    @SerializedName("voice_call_accepted")
    VOICE_CALL_ACCEPTED("voice_call_accepted"),

    @SerialName("voice_call_end")
    @SerializedName("voice_call_end")
    VOICE_CALL_END("voice_call_end"),

    @SerialName("voice_ice")
    @SerializedName("voice_ice")
    VOICE_ICE("voice_ice"),

    @SerialName("voice_event")
    @SerializedName("voice_event")
    VOICE_EVENT("voice_event"),

    @SerialName("voice_interrupt")
    @SerializedName("voice_interrupt")
    VOICE_INTERRUPT("voice_interrupt"),

    /**
     * 智能体**主动**发给设备的一句话。
     *
     * 协议里此前没有任何一条类型能表达这件事:[DECISION_REQUEST] 是「请你做个决定」
     * (带选项、等你选,语义是阻塞的);[VOICE_QUERY] 只有设备→网关一个方向,它的回复
     * 以 [COMMAND_RESULT] + correlation_id 回来,是**请求/响应**形状 —— 只在用户
     * 先问了的前提下存在。
     *
     * 手表上这个缺口同时让两件事说不通:通知路径没有可推的东西(只接得住
     * decision_request),会话存储没有可存的东西。
     *
     * v2 侧定义见 `galaxy_gateway/protocol/aip_v3.MessageType.AGENT_MESSAGE`
     * 与 `core/schemas/aip_v3.AgentMessageMsg`。
     */
    AGENT_MESSAGE("agent_message");

    companion object {
        /**
         * Mapping from legacy / v2 outbound type strings to authoritative AIP v3 names.
         *
         * New code must use [MsgType] enum entries directly.  This map exists solely for
         * normalisation of legacy inputs before messages are sent.
         */
        val LEGACY_TYPE_MAP: Map<String, String> = mapOf(
            "registration"      to DEVICE_REGISTER.value,
            "register"          to DEVICE_REGISTER.value,
            "heartbeat"         to HEARTBEAT.value,
            "command"           to TASK_ASSIGN.value,
            "command_result"    to COMMAND_RESULT.value,
            "task_execute"      to TASK_ASSIGN.value,
            "task_status_query" to TASK_ASSIGN.value
        )

        /** Converts a legacy / v2 type string to its authoritative AIP v3 equivalent. */
        fun toV3Type(legacyType: String): String = LEGACY_TYPE_MAP[legacyType] ?: legacyType

        /** Backing O(1) lookup map for [fromValue]. Built once at class-load time. */
        private val VALUE_MAP: Map<String, MsgType> = entries.associateBy { it.value }

        /** Looks up a [MsgType] by its wire-format [value] string. Returns `null` when not found. */
        fun fromValue(value: String): MsgType? = VALUE_MAP[value]

        /**
         * Set of advanced / low-priority message types that receive minimal-compat handling
         * (log + optional ack) in transport clients, except for promoted types which have
         * dedicated stateful handlers.
         */
        val ADVANCED_TYPES: Set<MsgType> = setOf(
            RELAY, FORWARD, REPLY, ACK,
            HYBRID_EXECUTE, HYBRID_RESULT, HYBRID_DEGRADE,
            RAG_QUERY, RAG_RESULT,
            CODE_EXECUTE, CODE_RESULT,
            PEER_ANNOUNCE, PEER_EXCHANGE, MESH_TOPOLOGY,
            WAKE_EVENT, SESSION_MIGRATE,
            COORD_SYNC, BROADCAST, LOCK, UNLOCK,
            TAKEOVER_REQUEST, TAKEOVER_RESPONSE,
            HANDOFF_ENVELOPE_V2
        )

        /** Advanced types for which the device should send an ack reply on receipt. */
        val ACK_ON_RECEIPT_TYPES: Set<MsgType> = setOf(
            RELAY, WAKE_EVENT, LOCK, UNLOCK,
            TAKEOVER_REQUEST
        )
    }
}

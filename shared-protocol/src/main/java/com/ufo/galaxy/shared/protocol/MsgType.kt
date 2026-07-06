package com.ufo.galaxy.shared.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    UNKNOWN("unknown"),

    // ── Core protocol types ──────────────────────────────────────────────────────────────

    @SerialName("auth")
    AUTH("auth"),

    @SerialName("ping")
    PING("ping"),

    @SerialName("command")
    COMMAND("command"),

    @SerialName("command_result")
    COMMAND_RESULT("command_result"),

    @SerialName("event")
    EVENT("event"),

    @SerialName("device_register")
    DEVICE_REGISTER("device_register"),

    @SerialName("capability_report")
    CAPABILITY_REPORT("capability_report"),

    @SerialName("heartbeat")
    HEARTBEAT("heartbeat"),

    @SerialName("heartbeat_ack")
    HEARTBEAT_ACK("heartbeat_ack"),

    @SerialName("voice_query")
    VOICE_QUERY("voice_query"),

    @SerialName("phase_report")
    PHASE_REPORT("phase_report"),

    // ── Task pipeline types ──────────────────────────────────────────────────────────────

    @SerialName("task_submit")
    TASK_SUBMIT("task_submit"),

    @SerialName("task_assign")
    TASK_ASSIGN("task_assign"),

    @SerialName("task_result")
    TASK_RESULT("task_result"),

    @SerialName("goal_execution")
    GOAL_EXECUTION("goal_execution"),

    @SerialName("parallel_subtask")
    PARALLEL_SUBTASK("parallel_subtask"),

    @SerialName("goal_result")
    GOAL_RESULT("goal_result"),

    @SerialName("goal_execution_result")
    GOAL_EXECUTION_RESULT("goal_execution_result"),

    @SerialName("task_cancel")
    TASK_CANCEL("task_cancel"),

    @SerialName("cancel_result")
    CANCEL_RESULT("cancel_result"),

    // ── Mesh session types ───────────────────────────────────────────────────────────────

    @SerialName("mesh_join")
    MESH_JOIN("mesh_join"),

    @SerialName("mesh_leave")
    MESH_LEAVE("mesh_leave"),

    @SerialName("mesh_result")
    MESH_RESULT("mesh_result"),

    // ── Cross-device sync types ──────────────────────────────────────────────────────────

    @SerialName("state_event")
    STATE_EVENT("state_event"),

    @SerialName("liquid_event")
    LIQUID_EVENT("liquid_event"),

    // ── Diagnostic / reporting types ─────────────────────────────────────────────────────

    @SerialName("diagnostics_payload")
    DIAGNOSTICS_PAYLOAD("diagnostics_payload"),

    @SerialName("device_state_snapshot")
    DEVICE_STATE_SNAPSHOT("device_state_snapshot"),

    @SerialName("device_execution_event")
    DEVICE_EXECUTION_EVENT("device_execution_event"),

    @SerialName("device_perception_emission")
    DEVICE_PERCEPTION_EMISSION("device_perception_emission"),

    @SerialName("device_readiness_report")
    DEVICE_READINESS_REPORT("device_readiness_report"),

    @SerialName("device_governance_report")
    DEVICE_GOVERNANCE_REPORT("device_governance_report"),

    @SerialName("device_acceptance_report")
    DEVICE_ACCEPTANCE_REPORT("device_acceptance_report"),

    @SerialName("device_strategy_report")
    DEVICE_STRATEGY_REPORT("device_strategy_report"),

    @SerialName("device_audit_report")
    DEVICE_AUDIT_REPORT("device_audit_report"),

    @SerialName("reconciliation_signal")
    RECONCILIATION_SIGNAL("reconciliation_signal"),

    // ── PR-3: Handoff / takeover contract ────────────────────────────────────────────────

    @SerialName("takeover_request")
    TAKEOVER_REQUEST("takeover_request"),

    @SerialName("takeover_response")
    TAKEOVER_RESPONSE("takeover_response"),

    @SerialName("delegated_execution_signal")
    DELEGATED_EXECUTION_SIGNAL("delegated_execution_signal"),

    @SerialName("handoff_envelope_v2")
    HANDOFF_ENVELOPE_V2("handoff_envelope_v2"),

    @SerialName("handoff_envelope_v2_result")
    HANDOFF_ENVELOPE_V2_RESULT("handoff_envelope_v2_result"),

    // ── Operator / action types ──────────────────────────────────────────────────────────

    @SerialName("operator_action_request")
    OPERATOR_ACTION_REQUEST("operator_action_request"),

    @SerialName("operator_action_result")
    OPERATOR_ACTION_RESULT("operator_action_result"),

    /** DECISION_REQUEST: V2 asks the human to choose (human-in-the-loop / HITL).
     *  Drives a decision notification; user reply goes back via human_input. */
    @SerialName("decision_request")
    DECISION_REQUEST("decision_request"),

    // ── Advanced / low-priority capability channels ──────────────────────────────────────
    // These types receive minimal-compat handling (log + optional ack) except where promoted.

    @SerialName("relay")
    RELAY("relay"),

    @SerialName("forward")
    FORWARD("forward"),

    @SerialName("reply")
    REPLY("reply"),

    @SerialName("ack")
    ACK("ack"),

    @SerialName("hybrid_execute")
    HYBRID_EXECUTE("hybrid_execute"),

    @SerialName("hybrid_result")
    HYBRID_RESULT("hybrid_result"),

    @SerialName("hybrid_degrade")
    HYBRID_DEGRADE("hybrid_degrade"),

    @SerialName("rag_query")
    RAG_QUERY("rag_query"),

    @SerialName("rag_result")
    RAG_RESULT("rag_result"),

    @SerialName("code_execute")
    CODE_EXECUTE("code_execute"),

    @SerialName("code_result")
    CODE_RESULT("code_result"),

    @SerialName("peer_announce")
    PEER_ANNOUNCE("peer_announce"),

    @SerialName("peer_exchange")
    PEER_EXCHANGE("peer_exchange"),

    @SerialName("mesh_topology")
    MESH_TOPOLOGY("mesh_topology"),

    @SerialName("wake_event")
    WAKE_EVENT("wake_event"),

    @SerialName("session_migrate")
    SESSION_MIGRATE("session_migrate"),

    @SerialName("coord_sync")
    COORD_SYNC("coord_sync"),

    @SerialName("broadcast")
    BROADCAST("broadcast"),

    @SerialName("lock")
    LOCK("lock"),

    @SerialName("unlock")
    UNLOCK("unlock");

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

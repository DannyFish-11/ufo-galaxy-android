package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.MsgType

/**
 * PR-10v2 (Android) — bounded relative subject runtime contract.
 *
 * This contract fixes Android's formal role in dual-repo governance as:
 * - bounded relative subject runtime
 * - local runtime host
 * - local continuity holder
 * - local execution policy participant
 * - local AI consumer host
 * - distributed participant
 *
 * It explicitly enforces bounded authority: Android can make local runtime decisions
 * and execute local AI consumption paths, but does not own global truth finalization,
 * dispatch arbitration, or closure authority.
 */
object AndroidBoundedSubjectRuntimeContract {

    const val INTRODUCED_PR = 10
    const val SCHEMA_VERSION = "1.0"

    enum class FormalRole(val wireValue: String) {
        BOUNDED_RELATIVE_SUBJECT_RUNTIME("bounded_relative_subject_runtime"),
        LOCAL_RUNTIME_HOST("local_runtime_host"),
        LOCAL_CONTINUITY_HOLDER("local_continuity_holder"),
        LOCAL_EXECUTION_POLICY_PARTICIPANT("local_execution_policy_participant"),
        LOCAL_AI_CONSUMER_HOST("local_ai_consumer_host"),
        DISTRIBUTED_PARTICIPANT("distributed_participant")
    }

    enum class UnifiedTerm(val wireValue: String, val meaning: String) {
        CENTER("center", "V2 canonical governance center with final dispatch/truth/closure authority."),
        SUBJECT("subject", "Bounded runtime subject with local lifecycle and local execution judgment."),
        PARTICIPANT("participant", "A subject runtime instance enrolled by the center in distributed collaboration."),
        TARGET("target", "A routed or assigned execution target, not an authority owner."),
        TRUTH("truth", "Verifiable state facts; Android publishes participant truth and center performs final convergence."),
        DISPATCH("dispatch", "Task dispatch and routing arbitration; center owns final arbitration authority."),
        CONTINUITY("continuity", "Continuity semantics across reconnect/replay/recovery/handoff/takeover paths."),
        CLOSURE("closure", "Execution closure semantics; Android contributes evidence and center emits canonical closure verdict."),
        BOUNDED_AUTHORITY("bounded_authority", "Android may decide local runtime behavior but does not own global finalization authority.")
    }

    enum class AuthorityTopic {
        LOCAL_EXECUTION_MODE_TRANSITION,
        LOCAL_CONTINUITY_ATTACH_RESUME,
        LOCAL_AI_CONSUMPTION_EXECUTION,
        PARTICIPANT_TRUTH_PUBLICATION,
        RUNTIME_STATE_PUBLICATION,
        EXECUTION_RESULT_PUBLICATION,
        DIAGNOSTICS_PUBLICATION,
        CANONICAL_DISPATCH_ARBITRATION,
        CANONICAL_TRUTH_FINALIZATION,
        CANONICAL_CLOSURE_FINALIZATION
    }

    enum class AuthorityVerdict(val wireValue: String) {
        LOCAL_DECIDES("local_decides"),
        LOCAL_CONTRIBUTES_CENTER_FINALIZES("local_contributes_center_finalizes"),
        CENTER_ONLY("center_only")
    }

    fun classifyAuthority(topic: AuthorityTopic): AuthorityVerdict = when (topic) {
        AuthorityTopic.LOCAL_EXECUTION_MODE_TRANSITION,
        AuthorityTopic.LOCAL_CONTINUITY_ATTACH_RESUME,
        AuthorityTopic.LOCAL_AI_CONSUMPTION_EXECUTION -> AuthorityVerdict.LOCAL_DECIDES

        AuthorityTopic.PARTICIPANT_TRUTH_PUBLICATION,
        AuthorityTopic.RUNTIME_STATE_PUBLICATION,
        AuthorityTopic.EXECUTION_RESULT_PUBLICATION,
        AuthorityTopic.DIAGNOSTICS_PUBLICATION -> AuthorityVerdict.LOCAL_CONTRIBUTES_CENTER_FINALIZES

        AuthorityTopic.CANONICAL_DISPATCH_ARBITRATION,
        AuthorityTopic.CANONICAL_TRUTH_FINALIZATION,
        AuthorityTopic.CANONICAL_CLOSURE_FINALIZATION -> AuthorityVerdict.CENTER_ONLY
    }

    enum class UplinkSemanticLayer(val wireValue: String) {
        PARTICIPANT_TRUTH_UPLINK("participant_truth_uplink"),
        RUNTIME_STATE_UPLINK("runtime_state_uplink"),
        EXECUTION_RESULT_UPLINK("execution_result_uplink"),
        DIAGNOSTICS_UPLINK("diagnostics_uplink"),
        COMPAT_MINIMAL_UPLINK("compat_minimal_uplink")
    }

    private val PARTICIPANT_TRUTH_MSG_TYPES = setOf(
        MsgType.DEVICE_REGISTER,
        MsgType.CAPABILITY_REPORT,
        MsgType.DEVICE_READINESS_REPORT,
        MsgType.DEVICE_GOVERNANCE_REPORT,
        MsgType.DEVICE_ACCEPTANCE_REPORT
    )

    private val RUNTIME_STATE_MSG_TYPES = setOf(
        MsgType.HEARTBEAT,
        MsgType.HEARTBEAT_ACK,
        MsgType.DEVICE_STATE_SNAPSHOT
    )

    private val EXECUTION_RESULT_MSG_TYPES = setOf(
        MsgType.TASK_RESULT,
        MsgType.CANCEL_RESULT,
        MsgType.GOAL_RESULT,
        MsgType.GOAL_EXECUTION_RESULT,
        MsgType.DEVICE_EXECUTION_EVENT
    )

    private val DIAGNOSTICS_MSG_TYPES = setOf(
        MsgType.DIAGNOSTICS_PAYLOAD,
        MsgType.DEVICE_AUDIT_REPORT
    )

    fun classifyUplink(msgType: MsgType): UplinkSemanticLayer = when (msgType) {
        in PARTICIPANT_TRUTH_MSG_TYPES -> UplinkSemanticLayer.PARTICIPANT_TRUTH_UPLINK
        in RUNTIME_STATE_MSG_TYPES -> UplinkSemanticLayer.RUNTIME_STATE_UPLINK
        in EXECUTION_RESULT_MSG_TYPES -> UplinkSemanticLayer.EXECUTION_RESULT_UPLINK
        in DIAGNOSTICS_MSG_TYPES -> UplinkSemanticLayer.DIAGNOSTICS_UPLINK
        else -> UplinkSemanticLayer.COMPAT_MINIMAL_UPLINK
    }

    val UPLINK_SEMANTIC_LAYER_MAP: Map<UplinkSemanticLayer, Set<MsgType>> = mapOf(
        UplinkSemanticLayer.PARTICIPANT_TRUTH_UPLINK to PARTICIPANT_TRUTH_MSG_TYPES,
        UplinkSemanticLayer.RUNTIME_STATE_UPLINK to RUNTIME_STATE_MSG_TYPES,
        UplinkSemanticLayer.EXECUTION_RESULT_UPLINK to EXECUTION_RESULT_MSG_TYPES,
        UplinkSemanticLayer.DIAGNOSTICS_UPLINK to DIAGNOSTICS_MSG_TYPES
    )

    val ANDROID_RUNTIME_CHAIN_PATHS: Map<String, String> = mapOf(
        "runtime_controller" to "app/src/main/java/com/ufo/galaxy/runtime/RuntimeController.kt",
        "local_execution_mode_gate" to "app/src/main/java/com/ufo/galaxy/runtime/LocalExecutionModeGate.kt",
        "connection_service" to "app/src/main/java/com/ufo/galaxy/service/GalaxyConnectionService.kt",
        "websocket_client" to "app/src/main/java/com/ufo/galaxy/network/GalaxyWebSocketClient.kt",
        "autonomous_execution_pipeline" to "app/src/main/java/com/ufo/galaxy/agent/AutonomousExecutionPipeline.kt",
        "local_loop_executor" to "app/src/main/java/com/ufo/galaxy/local/LocalLoopExecutor.kt",
        "offline_task_queue" to "app/src/main/java/com/ufo/galaxy/network/OfflineTaskQueue.kt",
        "android_continuity_integration" to "app/src/main/java/com/ufo/galaxy/runtime/AndroidContinuityIntegration.kt"
    )

    val V2_CANONICAL_GOVERNANCE_PATHS: Map<String, String> = mapOf(
        "canonical_dispatch_authority" to "core/command_router.py",
        "canonical_truth_ingress" to "core/unified_runtime_truth_ingress.py",
        "canonical_result_acceptance_closure" to "core/unified_result_ingress.py",
        "canonical_continuity_legality" to "core/unified_continuity_legality_authority.py",
        "android_participant_truth_ingress" to "core/android_participant_truth_ingress.py",
        "operator_surface" to "core/operator_surface.py",
        "operator_route" to "core/routes/operator.py"
    )

    val BOUNDED_SUBJECT_RUNTIME_INVARIANTS: Set<String> = setOf(
        "android_is_not_passive_endpoint",
        "android_is_not_parallel_canonical_center",
        "android_has_local_runtime_authority",
        "android_has_local_continuity_authority",
        "android_participates_in_local_execution_policy",
        "android_is_local_ai_consumer_host",
        "android_uplink_is_layered_participant_runtime_result_diagnostics_compat",
        "android_does_not_finalize_global_truth",
        "android_does_not_own_global_dispatch_authority",
        "android_does_not_own_global_closure_authority"
    )
}

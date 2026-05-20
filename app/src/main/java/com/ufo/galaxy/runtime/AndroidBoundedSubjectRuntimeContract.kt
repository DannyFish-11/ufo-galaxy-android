package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.MsgType

/**
 * PR-10v2 (Android) — bounded relative subject runtime contract.
 *
 * 固化 Android 在双仓系统中的正式角色：
 * - bounded relative subject runtime
 * - local runtime host
 * - local continuity holder
 * - local execution policy participant
 * - local AI consumer host
 * - distributed participant
 *
 * 本合约显式声明 Android 的 bounded authority：Android 具备本地判定与本地执行承接能力，
 * 但不拥有全局 truth finalization、dispatch arbitration、closure authority。
 */
object AndroidBoundedSubjectRuntimeContract {

    const val INTRODUCED_PR = 110
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
        SUBJECT("subject", "Bounded runtime主体，可拥有本地 lifecycle 与 local execution judgment。"),
        PARTICIPANT("participant", "被 center 纳入分布式协同链路的 subject 运行实例。"),
        TARGET("target", "被路由或被分配的执行目标，不等于 authority owner。"),
        TRUTH("truth", "可验证状态事实；Android 产出 participant truth，center 做 final convergence。"),
        DISPATCH("dispatch", "任务分发与路由仲裁；center 拥有 final arbitration authority。"),
        CONTINUITY("continuity", "跨 reconnect/replay/recovery/handoff/takeover 的连续性语义。"),
        CLOSURE("closure", "执行闭合判定；Android 贡献 evidence，center 做 canonical closure verdict。"),
        BOUNDED_AUTHORITY("bounded_authority", "Android 可本地决定运行态，但不拥有全局终裁权。")
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

    enum class LocalAiConsumerFlowStage(val wireValue: String) {
        RUNTIME_ENTRY("runtime_entry"),
        LOCAL_MODE_GATE("local_mode_gate"),
        LOCAL_POLICY_ROUTING("local_policy_routing"),
        PIPELINE_EXECUTION("pipeline_execution"),
        LOCAL_LOOP_EXECUTION("local_loop_execution"),
        CONTINUITY_HANDLING("continuity_handling"),
        OFFLINE_REPLAY_BUFFERING("offline_replay_buffering"),
        CANONICAL_UPLINK("canonical_uplink")
    }

    enum class CanonicalArbitrationBoundary(val wireValue: String) {
        LOCAL_RUNTIME_DECISION("local_runtime_decision"),
        LOCAL_DECISION_WITH_CANONICAL_UPLINK("local_decision_with_canonical_uplink"),
        CENTER_CANONICAL_ARBITRATION("center_canonical_arbitration")
    }

    data class LocalAiConsumerFlowBoundary(
        val stage: LocalAiConsumerFlowStage,
        val runtimeModule: String,
        val arbitrationBoundary: CanonicalArbitrationBoundary
    )

    enum class LocalVisibleClass(val wireValue: String) {
        LOCAL_VISIBLE("local_visible"),
        RUNTIME_VISIBLE("runtime_visible"),
        PRODUCT_VISIBLE("product_visible"),
        DIAGNOSTICS_VISIBLE("diagnostics_visible")
    }

    enum class CanonicalUplinkClass(val wireValue: String) {
        PARTICIPANT_TRUTH_UPLINK("canonical_participant_truth_uplink"),
        EXECUTION_RESULT_UPLINK("canonical_execution_result_uplink"),
        CONTINUITY_STATE_UPLINK("canonical_continuity_state_uplink"),
        DIAGNOSTICS_UPLINK("canonical_diagnostics_uplink"),
        COMPAT_MINIMAL_UPLINK("compat_minimal_uplink")
    }

    enum class LocalConsumptionSurface {
        LOCAL_RUNTIME_SURFACE,
        RUNTIME_VISIBLE_SURFACE,
        PRODUCT_SURFACE,
        DIAGNOSTICS_SURFACE
    }

    enum class ObservabilityContractClass(val wireValue: String) {
        LOCAL_VISIBLE("local_visible"),
        RUNTIME_VISIBLE("runtime_visible"),
        DIAGNOSTICS_VISIBLE("diagnostics_visible"),
        PRODUCT_VISIBLE("product_visible"),
        PARTICIPANT_TRUTH_UPLINK("participant_truth_uplink"),
        EXECUTION_RESULT_UPLINK("execution_result_uplink"),
        CONTINUITY_STATE_UPLINK("continuity_state_uplink")
    }

    enum class ObservabilityConsumptionBoundary(val wireValue: String) {
        LOCAL_FACING_ONLY("local_facing_only"),
        CANONICAL_UPLINK_ONLY("canonical_uplink_only")
    }

    enum class EvidenceBoundaryClass(val wireValue: String) {
        LOCAL_VISIBLE_EVIDENCE("local_visible_evidence"),
        PARTICIPANT_UPLINK_EVIDENCE("participant_uplink_evidence")
    }

    data class ObservabilityBoundaryEntry(
        val contractClass: ObservabilityContractClass,
        val runtimeModule: String,
        val consumptionBoundary: ObservabilityConsumptionBoundary,
        val evidenceBoundaryClass: EvidenceBoundaryClass,
        val canonicalFinalAuthority: Boolean = false
    )

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

    val LOCAL_AI_CONSUMER_FLOW_BOUNDARIES: List<LocalAiConsumerFlowBoundary> = listOf(
        LocalAiConsumerFlowBoundary(
            stage = LocalAiConsumerFlowStage.RUNTIME_ENTRY,
            runtimeModule = "RuntimeController",
            arbitrationBoundary = CanonicalArbitrationBoundary.LOCAL_RUNTIME_DECISION
        ),
        LocalAiConsumerFlowBoundary(
            stage = LocalAiConsumerFlowStage.LOCAL_MODE_GATE,
            runtimeModule = "LocalExecutionModeGate",
            arbitrationBoundary = CanonicalArbitrationBoundary.LOCAL_RUNTIME_DECISION
        ),
        LocalAiConsumerFlowBoundary(
            stage = LocalAiConsumerFlowStage.LOCAL_POLICY_ROUTING,
            runtimeModule = "GalaxyConnectionService",
            arbitrationBoundary = CanonicalArbitrationBoundary.LOCAL_DECISION_WITH_CANONICAL_UPLINK
        ),
        LocalAiConsumerFlowBoundary(
            stage = LocalAiConsumerFlowStage.PIPELINE_EXECUTION,
            runtimeModule = "AutonomousExecutionPipeline",
            arbitrationBoundary = CanonicalArbitrationBoundary.LOCAL_RUNTIME_DECISION
        ),
        LocalAiConsumerFlowBoundary(
            stage = LocalAiConsumerFlowStage.LOCAL_LOOP_EXECUTION,
            runtimeModule = "LocalLoopExecutor",
            arbitrationBoundary = CanonicalArbitrationBoundary.LOCAL_RUNTIME_DECISION
        ),
        LocalAiConsumerFlowBoundary(
            stage = LocalAiConsumerFlowStage.CONTINUITY_HANDLING,
            runtimeModule = "AndroidContinuityIntegration",
            arbitrationBoundary = CanonicalArbitrationBoundary.LOCAL_DECISION_WITH_CANONICAL_UPLINK
        ),
        LocalAiConsumerFlowBoundary(
            stage = LocalAiConsumerFlowStage.OFFLINE_REPLAY_BUFFERING,
            runtimeModule = "OfflineTaskQueue",
            arbitrationBoundary = CanonicalArbitrationBoundary.LOCAL_DECISION_WITH_CANONICAL_UPLINK
        ),
        LocalAiConsumerFlowBoundary(
            stage = LocalAiConsumerFlowStage.CANONICAL_UPLINK,
            runtimeModule = "GalaxyWebSocketClient",
            arbitrationBoundary = CanonicalArbitrationBoundary.CENTER_CANONICAL_ARBITRATION
        )
    )

    fun classifyLocalSurface(surface: LocalConsumptionSurface): LocalVisibleClass = when (surface) {
        LocalConsumptionSurface.LOCAL_RUNTIME_SURFACE -> LocalVisibleClass.LOCAL_VISIBLE
        LocalConsumptionSurface.RUNTIME_VISIBLE_SURFACE -> LocalVisibleClass.RUNTIME_VISIBLE
        LocalConsumptionSurface.PRODUCT_SURFACE -> LocalVisibleClass.PRODUCT_VISIBLE
        LocalConsumptionSurface.DIAGNOSTICS_SURFACE -> LocalVisibleClass.DIAGNOSTICS_VISIBLE
    }

    fun classifyObservabilityContractClass(
        localSurface: LocalConsumptionSurface? = null,
        msgType: MsgType? = null,
        reconciliationKind: ReconciliationSignal.Kind? = null
    ): ObservabilityContractClass? {
        localSurface?.let { surface ->
            return when (classifyLocalSurface(surface)) {
                LocalVisibleClass.LOCAL_VISIBLE -> ObservabilityContractClass.LOCAL_VISIBLE
                LocalVisibleClass.RUNTIME_VISIBLE -> ObservabilityContractClass.RUNTIME_VISIBLE
                LocalVisibleClass.DIAGNOSTICS_VISIBLE -> ObservabilityContractClass.DIAGNOSTICS_VISIBLE
                LocalVisibleClass.PRODUCT_VISIBLE -> ObservabilityContractClass.PRODUCT_VISIBLE
            }
        }
        val uplinkMsgType = msgType ?: return null
        return when (classifyCanonicalUplink(uplinkMsgType, reconciliationKind)) {
            CanonicalUplinkClass.PARTICIPANT_TRUTH_UPLINK ->
                ObservabilityContractClass.PARTICIPANT_TRUTH_UPLINK
            CanonicalUplinkClass.EXECUTION_RESULT_UPLINK ->
                ObservabilityContractClass.EXECUTION_RESULT_UPLINK
            CanonicalUplinkClass.CONTINUITY_STATE_UPLINK ->
                ObservabilityContractClass.CONTINUITY_STATE_UPLINK
            CanonicalUplinkClass.DIAGNOSTICS_UPLINK,
            CanonicalUplinkClass.COMPAT_MINIMAL_UPLINK -> null
        }
    }

    val OBSERVABILITY_BOUNDARY_ENTRIES: List<ObservabilityBoundaryEntry> = listOf(
        ObservabilityBoundaryEntry(
            contractClass = ObservabilityContractClass.LOCAL_VISIBLE,
            runtimeModule = "LocalLoopExecutor",
            consumptionBoundary = ObservabilityConsumptionBoundary.LOCAL_FACING_ONLY,
            evidenceBoundaryClass = EvidenceBoundaryClass.LOCAL_VISIBLE_EVIDENCE
        ),
        ObservabilityBoundaryEntry(
            contractClass = ObservabilityContractClass.RUNTIME_VISIBLE,
            runtimeModule = "RuntimeController",
            consumptionBoundary = ObservabilityConsumptionBoundary.LOCAL_FACING_ONLY,
            evidenceBoundaryClass = EvidenceBoundaryClass.LOCAL_VISIBLE_EVIDENCE
        ),
        ObservabilityBoundaryEntry(
            contractClass = ObservabilityContractClass.DIAGNOSTICS_VISIBLE,
            runtimeModule = "GalaxyConnectionService",
            consumptionBoundary = ObservabilityConsumptionBoundary.LOCAL_FACING_ONLY,
            evidenceBoundaryClass = EvidenceBoundaryClass.LOCAL_VISIBLE_EVIDENCE
        ),
        ObservabilityBoundaryEntry(
            contractClass = ObservabilityContractClass.PRODUCT_VISIBLE,
            runtimeModule = "AutonomousExecutionPipeline",
            consumptionBoundary = ObservabilityConsumptionBoundary.LOCAL_FACING_ONLY,
            evidenceBoundaryClass = EvidenceBoundaryClass.LOCAL_VISIBLE_EVIDENCE
        ),
        ObservabilityBoundaryEntry(
            contractClass = ObservabilityContractClass.PARTICIPANT_TRUTH_UPLINK,
            runtimeModule = "GalaxyConnectionService",
            consumptionBoundary = ObservabilityConsumptionBoundary.CANONICAL_UPLINK_ONLY,
            evidenceBoundaryClass = EvidenceBoundaryClass.PARTICIPANT_UPLINK_EVIDENCE
        ),
        ObservabilityBoundaryEntry(
            contractClass = ObservabilityContractClass.EXECUTION_RESULT_UPLINK,
            runtimeModule = "GalaxyWebSocketClient",
            consumptionBoundary = ObservabilityConsumptionBoundary.CANONICAL_UPLINK_ONLY,
            evidenceBoundaryClass = EvidenceBoundaryClass.PARTICIPANT_UPLINK_EVIDENCE
        ),
        ObservabilityBoundaryEntry(
            contractClass = ObservabilityContractClass.CONTINUITY_STATE_UPLINK,
            runtimeModule = "AndroidContinuityIntegration",
            consumptionBoundary = ObservabilityConsumptionBoundary.CANONICAL_UPLINK_ONLY,
            evidenceBoundaryClass = EvidenceBoundaryClass.PARTICIPANT_UPLINK_EVIDENCE
        ),
        ObservabilityBoundaryEntry(
            contractClass = ObservabilityContractClass.CONTINUITY_STATE_UPLINK,
            runtimeModule = "OfflineTaskQueue",
            consumptionBoundary = ObservabilityConsumptionBoundary.CANONICAL_UPLINK_ONLY,
            evidenceBoundaryClass = EvidenceBoundaryClass.PARTICIPANT_UPLINK_EVIDENCE
        )
    )

    fun classifyCanonicalUplink(
        msgType: MsgType,
        reconciliationKind: ReconciliationSignal.Kind? = null
    ): CanonicalUplinkClass {
        if (reconciliationKind == ReconciliationSignal.Kind.PARTICIPANT_STATE ||
            reconciliationKind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
        ) {
            return CanonicalUplinkClass.CONTINUITY_STATE_UPLINK
        }
        return when (msgType) {
            in PARTICIPANT_TRUTH_MSG_TYPES -> CanonicalUplinkClass.PARTICIPANT_TRUTH_UPLINK
            in EXECUTION_RESULT_MSG_TYPES -> CanonicalUplinkClass.EXECUTION_RESULT_UPLINK
            MsgType.HEARTBEAT,
            MsgType.HEARTBEAT_ACK,
            MsgType.DEVICE_STATE_SNAPSHOT -> CanonicalUplinkClass.CONTINUITY_STATE_UPLINK
            in DIAGNOSTICS_MSG_TYPES -> CanonicalUplinkClass.DIAGNOSTICS_UPLINK
            else -> CanonicalUplinkClass.COMPAT_MINIMAL_UPLINK
        }
    }

    val V2_CANONICAL_GOVERNANCE_PATHS: Map<String, String> = mapOf(
        "canonical_dispatch_authority" to "core/command_router.py",
        "canonical_truth_ingress" to "core/unified_runtime_truth_ingress.py",
        "canonical_result_acceptance_closure" to "core/unified_result_ingress.py",
        "canonical_continuity_legality" to "core/unified_continuity_legality_authority.py",
        "android_participant_truth_ingress" to "core/android_participant_truth_ingress.py",
        "operator_governance_surface" to "core/operator_surface.py + core/routes/operator.py"
    )

    val BOUNDED_SUBJECT_RUNTIME_INVARIANTS: Map<String, Boolean> = mapOf(
        "android_is_not_passive_endpoint" to true,
        "android_is_not_parallel_canonical_center" to true,
        "android_has_local_runtime_authority" to true,
        "android_has_local_continuity_authority" to true,
        "android_participates_in_local_execution_policy" to true,
        "android_is_local_ai_consumer_host" to true,
        "local_ai_consumer_flow_is_runtime_bounded_and_center_aligned" to true,
        "local_visible_product_visible_diagnostics_visible_are_not_canonical_truth" to true,
        "runtime_visible_is_local_consumption_not_canonical_truth" to true,
        "participant_truth_execution_result_continuity_uplinks_feed_canonical_chain" to true,
        "diagnostics_evidence_must_not_claim_canonical_final_truth" to true,
        "outward_facing_layers_consume_only_bounded_or_canonical_outputs" to true,
        "android_uplink_is_layered_participant_runtime_result_diagnostics_compat" to true,
        "android_does_not_finalize_global_truth" to true,
        "android_does_not_own_global_dispatch_authority" to true,
        "android_does_not_own_global_closure_authority" to true
    )
}
